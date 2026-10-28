package com.traceability.labels;

import com.traceability.inventory.LabelPdfRenderer;
import com.traceability.inventory.PieceLabelLayout;
import com.traceability.receiving.ReceivingImportService;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.*;

/**
 * Flexible labeling slice 6a — factory runs: an order to a factory, per line a variant and the pieces expected.
 * The run's print issues one named FACTORY label per expected piece (kind factory, V187: each code names its
 * run line); the factory sticks each label on a matching piece. Those pieces enter stock only when the run is
 * received (slice 6b) — until then every scan path refuses / explains a factory code (InventoryLedger.bindLabel,
 * UnusedLabels). Nothing here touches pieces, stock or Shopify.
 *
 * <ul>
 *   <li>{@link #create}: factory name, optional reference, lines (variant + expected 1..20,000, each variant once,
 *       at most {@link #MAX_RUN_UNITS} pieces in all).</li>
 *   <li>{@link #print}: issues the codes each line still lacks (expected − codes already printed for it), in
 *       batches of ≤ 2,000, under the run's row lock — a second print (or a concurrent one) issues none.</li>
 *   <li>{@link #parts} / {@link #pdf}: the run's still-ISSUED codes as named piece labels, grouped by product
 *       (one header-first separator per line: product, then variant), in parts of ≤ 2,000 labels that never
 *       split a line's group unless the group alone is larger. Reprint = the same PDF: the same codes, none new.</li>
 *   <li>{@link #close} (slice 6c): every line with a gap (expected − received &gt; 0) needs a reason. Short-shipped /
 *       Lost voids that line's leftover issued codes (reasons factory_run_short_shipped / factory_run_lost); Still
 *       coming keeps them valid and the run open. The run closes only when no line is Still coming — then every
 *       code of the run still issued is voided (factory_run_closed), in the same transaction; a later close
 *       may turn Still coming into Short-shipped / Lost (never back). No reopen. Refused while an open receiving
 *       session of the run holds live scans.</li>
 *   <li>{@link #report} (slice 6c): per line expected / received / gap / reason / overage — CSV and A4 PDF.</li>
 * </ul>
 * Caller contract: owner / manager (controller); 404 unless the tenant has flexible labeling.
 */
@Service
public class LabelRunService {

    public static final int MAX_RUN_UNITS = 20_000;
    public static final int MAX_LINE_UNITS = 20_000;
    static final int PART_SIZE = LabelCodeService.MAX_CODES_PER_BATCH;

    public record CreateLine(UUID variantId, Integer expectedQty) {}
    /** {@code stillComing}: an open run with at least one line closed as Still coming (slice 6c). */
    public record RunSummary(UUID id, String factoryName, String reference, String status, String createdAt,
                             int lines, int expected, int printed, int received, boolean stillComing) {}
    /**
     * {@code overage}: the line's codes printed for a piece that arrived without a label once the line was full;
     * {@code gap} = max(0, expected − received); {@code closeReason}: short_shipped | lost | still_coming | null.
     */
    public record RunLine(UUID id, int lineNo, UUID variantId, String productTitle, String variantTitle, String sku,
                          String imageUrl, int expected, int printed, int received, int unused, int voided, int overage,
                          int gap, String closeReason) {}
    public record PartGroup(String productTitle, String variantTitle, String sku, int labels) {}
    public record Part(int part, int labels, List<PartGroup> groups) {}
    public record RunDetail(UUID id, String factoryName, String reference, String status, String createdAt,
                            String createdBy, int expected, int printed, int received, int toPrint,
                            List<RunLine> lines, List<Part> parts, boolean stillComing, String closedAt, String closedBy) {}
    public record PrintResult(int issued, RunDetail run) {}

    private final JdbcTemplate jdbc;
    private final LabelCodeService labelCodes;
    private final ReceivingImportService importService;

    public LabelRunService(JdbcTemplate jdbc, LabelCodeService labelCodes, ReceivingImportService importService) {
        this.jdbc = jdbc;
        this.labelCodes = labelCodes;
        this.importService = importService;
    }

    // ── list / detail ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RunSummary> list() {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        return jdbc.query(
            "SELECT r.id, r.factory_name, r.reference, r.status, r.created_at, " +
            "       (SELECT count(*)::int FROM label_run_lines l WHERE l.run_id = r.id AND l.tenant_id = r.tenant_id) AS lines, " +
            "       (SELECT COALESCE(sum(l.expected_qty), 0)::int FROM label_run_lines l " +
            "         WHERE l.run_id = r.id AND l.tenant_id = r.tenant_id) AS expected, " +
            "       (SELECT count(*)::int FROM label_codes c JOIN label_run_lines l ON l.id = c.run_line_id " +
            "         WHERE l.run_id = r.id AND c.tenant_id = r.tenant_id AND NOT c.overage) AS printed, " +
            "       (SELECT count(*)::int FROM label_codes c JOIN label_run_lines l ON l.id = c.run_line_id " +
            "         WHERE l.run_id = r.id AND c.tenant_id = r.tenant_id AND c.status = 'bound') AS received, " +
            "       EXISTS (SELECT 1 FROM label_run_lines l WHERE l.run_id = r.id AND l.tenant_id = r.tenant_id " +
            "                AND l.close_reason = 'still_coming') AS still_coming " +
            "FROM label_runs r WHERE r.tenant_id = ? ORDER BY r.created_at DESC, r.id DESC LIMIT 200",
            (rs, i) -> new RunSummary(rs.getObject("id", UUID.class), rs.getString("factory_name"), rs.getString("reference"),
                rs.getString("status"), rs.getObject("created_at", java.time.OffsetDateTime.class).toString(),
                rs.getInt("lines"), rs.getInt("expected"), rs.getInt("printed"), rs.getInt("received"),
                rs.getBoolean("still_coming")),
            tenantId);
    }

    @Transactional(readOnly = true)
    public RunDetail get(UUID runId) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        return detail(tenantId, runId);
    }

    private RunDetail detail(UUID tenantId, UUID runId) {
        List<Map<String, Object>> run = jdbc.queryForList(
            "SELECT r.id, r.factory_name, r.reference, r.status, r.created_at, u.name AS created_by, " +
            "       r.closed_at, cu.name AS closed_by " +
            "FROM label_runs r LEFT JOIN users u ON u.id = r.created_by LEFT JOIN users cu ON cu.id = r.closed_by " +
            "WHERE r.id = ? AND r.tenant_id = ?", runId, tenantId);
        if (run.isEmpty()) throw LabelCodeException.runNotFound();
        Map<String, Object> r = run.get(0);
        List<RunLine> lines = jdbc.query(
            "SELECT l.id, l.line_no, l.variant_id, l.expected_qty, l.close_reason, pr.title AS product_title, " +
            "       v.title AS variant_title, v.sku, pr.image_url, " +
            "       count(c.id) FILTER (WHERE NOT c.overage)::int        AS printed, " +
            "       count(c.id) FILTER (WHERE c.status = 'bound')::int    AS received, " +
            "       count(c.id) FILTER (WHERE c.status = 'issued')::int   AS unused, " +
            "       count(c.id) FILTER (WHERE c.status = 'voided')::int   AS voided, " +
            "       count(c.id) FILTER (WHERE c.overage)::int             AS overage " +
            "FROM label_run_lines l JOIN variants v ON v.id = l.variant_id JOIN products pr ON pr.id = v.product_id " +
            "LEFT JOIN label_codes c ON c.run_line_id = l.id AND c.tenant_id = l.tenant_id " +
            "WHERE l.run_id = ? AND l.tenant_id = ? " +
            "GROUP BY l.id, l.line_no, l.variant_id, l.expected_qty, l.close_reason, pr.title, v.title, v.sku, pr.image_url " +
            "ORDER BY l.line_no",
            (rs, i) -> new RunLine(rs.getObject("id", UUID.class), rs.getInt("line_no"), rs.getObject("variant_id", UUID.class),
                rs.getString("product_title"), rs.getString("variant_title"), rs.getString("sku"), rs.getString("image_url"),
                rs.getInt("expected_qty"), rs.getInt("printed"), rs.getInt("received"), rs.getInt("unused"),
                rs.getInt("voided"), rs.getInt("overage"), Math.max(0, rs.getInt("expected_qty") - rs.getInt("received")),
                rs.getString("close_reason")),
            runId, tenantId);
        int expected = lines.stream().mapToInt(RunLine::expected).sum();
        int printed = lines.stream().mapToInt(RunLine::printed).sum();
        int received = lines.stream().mapToInt(RunLine::received).sum();
        int toPrint = lines.stream().mapToInt(l -> Math.max(0, l.expected() - l.printed())).sum();
        List<Part> parts = new ArrayList<>();
        for (List<Chunk> p : pack(groups(tenantId, runId))) {
            List<PartGroup> g = p.stream().map(c -> new PartGroup(c.productTitle(), c.variantTitle(), c.sku(), c.codes().size())).toList();
            parts.add(new Part(parts.size() + 1, g.stream().mapToInt(PartGroup::labels).sum(), g));
        }
        return new RunDetail(runId, (String) r.get("factory_name"), (String) r.get("reference"), (String) r.get("status"),
            r.get("created_at").toString(), (String) r.get("created_by"), expected, printed, received, toPrint, lines, parts,
            lines.stream().anyMatch(l -> REASON_STILL_COMING.equals(l.closeReason())),
            r.get("closed_at") == null ? null : r.get("closed_at").toString(), (String) r.get("closed_by"));
    }

    // ── close (slice 6c) ──────────────────────────────────────────────────────

    public static final String REASON_SHORT_SHIPPED = "short_shipped";
    public static final String REASON_LOST          = "lost";
    public static final String REASON_STILL_COMING  = "still_coming";
    /** void_reason of the codes still issued when the run closes. */
    public static final String REASON_RUN_CLOSED   = "factory_run_closed";
    private static final Set<String> REASONS = Set.of(REASON_SHORT_SHIPPED, REASON_LOST, REASON_STILL_COMING);

    public record CloseLine(UUID lineId, String reason) {}
    /** {@code voided}: leftover codes voided by this close; {@code closed}: the run is now closed. */
    public record CloseResult(int voided, boolean closed, RunDetail run) {}

    @Transactional
    public CloseResult close(UUID runId, UUID actorUserId, List<CloseLine> reasons) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        // The run first (finalize and the scans lock it too): nothing is received or staged while it closes.
        List<String> status = jdbc.queryForList(
            "SELECT status FROM label_runs WHERE id = ? AND tenant_id = ? FOR UPDATE", String.class, runId, tenantId);
        if (status.isEmpty()) throw LabelCodeException.runNotFound();
        if (!"open".equals(status.get(0))) throw LabelCodeException.runClosed();

        List<Map<String, Object>> live = jdbc.queryForList(
            "SELECT r.id, r.reference, r.created_at, count(*)::int AS scans FROM receipt_factory_scans s " +
            "JOIN receipts r ON r.id = s.receipt_id AND r.tenant_id = s.tenant_id " +
            "WHERE r.tenant_id = ? AND r.label_run_id = ? AND r.status = 'open' AND s.undone_at IS NULL " +
            "GROUP BY r.id, r.reference, r.created_at ORDER BY r.created_at, r.id",
            tenantId, runId);
        if (!live.isEmpty()) {
            List<Map<String, Object>> sessions = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Map<String, Object> l : live) {
                String name = l.get("reference") != null ? l.get("reference").toString()
                    : l.get("created_at").toString().substring(0, 16).replace('T', ' ');
                names.add("«" + name + "»");
                sessions.add(Map.of("sessionId", l.get("id"), "name", name, "scans", l.get("scans")));
            }
            throw LabelCodeException.runHasLiveScans(String.join(", ", names), sessions);
        }

        Map<UUID, String> asked = new HashMap<>();
        for (CloseLine c : reasons == null ? List.<CloseLine>of() : reasons) {
            if (c == null || c.lineId() == null || c.reason() == null || !REASONS.contains(c.reason())
                    || asked.put(c.lineId(), c.reason()) != null) {
                throw LabelCodeException.invalidClose("Each line can have one reason: short-shipped, lost or still coming",
                    "لكل سطر سبب واحد: شحن ناقص أو مفقود أو ما زال قادمًا", List.of());
            }
        }
        RunDetail before = detail(tenantId, runId);
        Set<UUID> known = new HashSet<>();
        before.lines().forEach(l -> known.add(l.id()));
        if (!known.containsAll(asked.keySet())) {
            throw LabelCodeException.invalidClose("A line in this request isn't in this run — reload the run",
                "سطر في هذا الطلب ليس ضمن هذه الدفعة — أعد تحميل الدفعة", List.of());
        }

        List<Map<String, Object>> missing = new ArrayList<>();
        Map<UUID, String> apply = new LinkedHashMap<>();   // line → new reason (null = clear a Still coming)
        for (RunLine l : before.lines()) {
            String current = l.closeReason();
            String given = asked.get(l.id());
            boolean fin = REASON_SHORT_SHIPPED.equals(current) || REASON_LOST.equals(current);
            if (fin) {
                if (given != null && !given.equals(current)) {
                    throw LabelCodeException.invalidClose("A short-shipped or lost line can't be changed",
                        "لا يمكن تغيير سطر مسجل كشحن ناقص أو مفقود",
                        List.of(Map.of("lineId", l.id(), "closeReason", current)));
                }
                continue;
            }
            if (l.gap() == 0) {
                // Everything arrived: nothing to record; a Still coming line that has since arrived is cleared.
                if (current != null) apply.put(l.id(), null);
                continue;
            }
            String reason = given != null ? given : current;   // an untouched Still coming line stays Still coming
            if (reason == null) {
                missing.add(Map.of("lineId", l.id(), "gap", l.gap()));
                continue;
            }
            if (!reason.equals(current)) apply.put(l.id(), reason);
        }
        if (!missing.isEmpty()) {
            throw LabelCodeException.invalidClose("Choose a reason for every line with missing pieces",
                "اختر سببًا لكل سطر به قطع ناقصة", missing);
        }

        int voided = 0;
        for (Map.Entry<UUID, String> e : apply.entrySet()) {
            String reason = e.getValue();
            if (reason == null) {
                jdbc.update("UPDATE label_run_lines SET close_reason = NULL, close_recorded_at = NULL, close_recorded_by = NULL " +
                    "WHERE id = ? AND tenant_id = ?", e.getKey(), tenantId);
                continue;
            }
            if (!REASON_STILL_COMING.equals(reason)) {
                // The line's leftover codes stop working — all or nothing with the rest of the close.
                voided += jdbc.update(
                    "UPDATE label_codes SET status = 'voided', voided_at = now(), void_reason = ? " +
                    "WHERE tenant_id = ? AND run_line_id = ? AND status = 'issued'",
                    "factory_run_" + reason, tenantId, e.getKey());
            }
            jdbc.update("UPDATE label_run_lines SET close_reason = ?, close_recorded_at = now(), close_recorded_by = ? " +
                "WHERE id = ? AND tenant_id = ?", reason, actorUserId, e.getKey(), tenantId);
        }
        Boolean stillComing = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM label_run_lines WHERE run_id = ? AND tenant_id = ? AND close_reason = 'still_coming')",
            Boolean.class, runId, tenantId);
        boolean closed = !Boolean.TRUE.equals(stillComing);
        if (closed) {
            jdbc.update("UPDATE label_runs SET status = 'closed', closed_at = now(), closed_by = ? " +
                "WHERE id = ? AND tenant_id = ? AND status = 'open'", actorUserId, runId, tenantId);
            // A closed run has no live labels: every code still issued — any line, including a complete line whose
            // leftovers were replaced by overage codes, and unscanned overage codes — is voided in the same transaction.
            voided += jdbc.update(
                "UPDATE label_codes c SET status = 'voided', voided_at = now(), void_reason = ? " +
                "FROM label_run_lines l WHERE l.id = c.run_line_id AND l.run_id = ? AND l.tenant_id = ? " +
                "AND c.tenant_id = ? AND c.status = 'issued'",
                REASON_RUN_CLOSED, runId, tenantId, tenantId);
        }
        return new CloseResult(voided, closed, detail(tenantId, runId));
    }

    // ── shortfall report (slice 6c) ───────────────────────────────────────────

    /** The run's report: per line expected / received / gap / reason / overage (overage = received beyond expected). */
    @Transactional(readOnly = true)
    public FactoryRunReport.Report report(UUID runId) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        RunDetail d = detail(tenantId, runId);
        List<FactoryRunReport.Row> rows = d.lines().stream().map(l -> new FactoryRunReport.Row(l.productTitle(),
            l.variantTitle(), l.sku(), l.expected(), l.received(), l.gap(), l.closeReason(),
            Math.max(0, l.received() - l.expected()))).toList();
        return new FactoryRunReport.Report(d.factoryName(), d.reference(), d.status(), d.stillComing(), d.createdAt(),
            d.closedAt(), rows);
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Transactional
    public RunDetail create(UUID actorUserId, String factoryName, String reference, List<CreateLine> lines) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        String factory = factoryName == null ? "" : factoryName.strip();
        if (factory.isEmpty() || factory.length() > 120) {
            throw LabelCodeException.invalidRun("Enter the factory's name (up to 120 characters)",
                "أدخل اسم المصنع (حتى 120 حرفًا)");
        }
        String ref = reference == null || reference.isBlank() ? null : reference.strip();
        if (ref != null && ref.length() > 100) {
            throw LabelCodeException.invalidRun("The reference can be up to 100 characters", "يمكن أن يصل المرجع إلى 100 حرف");
        }
        if (lines == null || lines.isEmpty()) {
            throw LabelCodeException.invalidRun("Add at least one product to the run", "أضف منتجًا واحدًا على الأقل إلى الدفعة");
        }
        Set<UUID> seen = new LinkedHashSet<>();
        long total = 0;
        for (CreateLine l : lines) {
            if (l == null || l.variantId() == null || l.expectedQty() == null
                    || l.expectedQty() < 1 || l.expectedQty() > MAX_LINE_UNITS) {
                throw LabelCodeException.invalidRun("Each product needs an expected quantity from 1 to " + MAX_LINE_UNITS,
                    "يحتاج كل منتج إلى كمية متوقعة من 1 إلى " + MAX_LINE_UNITS);
            }
            if (!seen.add(l.variantId())) {
                throw LabelCodeException.invalidRun("Each product variant can be in the run only once",
                    "يمكن أن يظهر كل متغير منتج في الدفعة مرة واحدة فقط");
            }
            total += l.expectedQty();
        }
        if (total > MAX_RUN_UNITS) throw LabelCodeException.runTooLarge(MAX_RUN_UNITS);
        Integer known = jdbc.queryForObject("SELECT count(*)::int FROM variants WHERE tenant_id = ? AND id = ANY(?::uuid[])",
            Integer.class, tenantId, seen.toArray(new UUID[0]));
        if (known == null || known != seen.size()) throw LabelCodeException.unknownVariant();

        UUID runId = jdbc.queryForObject(
            "INSERT INTO label_runs (tenant_id, factory_name, reference, created_by) VALUES (?, ?, ?, ?) RETURNING id",
            UUID.class, tenantId, factory, ref, actorUserId);
        int no = 0;
        for (CreateLine l : lines) {
            jdbc.update("INSERT INTO label_run_lines (tenant_id, run_id, line_no, variant_id, expected_qty) VALUES (?, ?, ?, ?, ?)",
                tenantId, runId, ++no, l.variantId(), l.expectedQty());
        }
        return detail(tenantId, runId);
    }

    // ── print ─────────────────────────────────────────────────────────────────

    /**
     * Issues what each line still lacks, so the run holds exactly its expected number of (non-overage) codes per
     * line. The run row is locked first: a second print — or two at once — finds nothing left to issue.
     */
    @Transactional
    public PrintResult print(UUID runId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        List<String> status = jdbc.queryForList(
            "SELECT status FROM label_runs WHERE id = ? AND tenant_id = ? FOR UPDATE", String.class, runId, tenantId);
        if (status.isEmpty()) throw LabelCodeException.runNotFound();
        if (!"open".equals(status.get(0))) throw LabelCodeException.runClosed();

        List<LabelCodeService.FactoryLine> missing = jdbc.query(
            "SELECT l.id, l.variant_id, l.expected_qty - (SELECT count(*)::int FROM label_codes c " +
            "         WHERE c.run_line_id = l.id AND c.tenant_id = l.tenant_id AND NOT c.overage) AS missing " +
            "FROM label_run_lines l WHERE l.run_id = ? AND l.tenant_id = ? ORDER BY l.line_no",
            (rs, i) -> new LabelCodeService.FactoryLine(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class),
                rs.getInt("missing"), false),
            runId, tenantId);
        Integer batches = jdbc.queryForObject(
            "SELECT count(*)::int FROM label_code_batches WHERE run_id = ? AND tenant_id = ?", Integer.class, runId, tenantId);
        int batchNo = batches == null ? 0 : batches;
        int issued = 0;
        List<LabelCodeService.FactoryLine> batch = new ArrayList<>();
        int inBatch = 0;
        for (LabelCodeService.FactoryLine l : missing) {
            int left = l.count();
            while (left > 0) {
                int take = Math.min(left, PART_SIZE - inBatch);
                batch.add(new LabelCodeService.FactoryLine(l.runLineId(), l.variantId(), take, false));
                inBatch += take;
                left -= take;
                if (inBatch == PART_SIZE) {
                    issued += labelCodes.issueFactoryBatch(tenantId, actorUserId, runId, batch, "run-" + runId + "-" + (++batchNo)).size();
                    batch = new ArrayList<>();
                    inBatch = 0;
                }
            }
        }
        if (inBatch > 0) {
            issued += labelCodes.issueFactoryBatch(tenantId, actorUserId, runId, batch, "run-" + runId + "-" + (++batchNo)).size();
        }
        return new PrintResult(issued, detail(tenantId, runId));
    }

    // ── PDF ───────────────────────────────────────────────────────────────────

    /** Part {@code part} (1-based) of the run's still-issued labels; the same codes every time (reprint). */
    @Transactional(readOnly = true)
    public byte[] pdf(UUID runId, int part) throws IOException {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        Integer exists = jdbc.queryForObject("SELECT count(*)::int FROM label_runs WHERE id = ? AND tenant_id = ?",
            Integer.class, runId, tenantId);
        if (exists == null || exists == 0) throw LabelCodeException.runNotFound();
        List<List<Chunk>> parts = pack(groups(tenantId, runId));
        if (parts.isEmpty()) {
            throw new LabelCodeException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, LabelCodeException.Code.NO_ISSUED_CODES,
                "Every label of this run is already received or voided", "كل ملصقات هذه الدفعة مستلمة أو ملغاة");
        }
        if (part < 1 || part > parts.size()) throw LabelCodeException.invalidPart();
        List<LabelPdfRenderer.Page> pages = new ArrayList<>();
        for (Chunk c : parts.get(part - 1)) {
            pages.add(new LabelPdfRenderer.Separator(c.productTitle(), c.variantTitle()));     // the group's HEADER
            for (String code : c.codes()) {
                pages.add(new LabelPdfRenderer.Piece(code, c.productTitle(), c.variantTitle(), c.sku()));
            }
        }
        return LabelPdfRenderer.renderPages(pages,
            PieceLabelLayout.Spec.of(LabelCodeService.LABEL_WIDTH_MM, LabelCodeService.LABEL_HEIGHT_MM));
    }

    /** One line's issued codes (numeric order) — a group of the PDF, or part of one when the group is split. */
    record Chunk(UUID lineId, String productTitle, String variantTitle, String sku, List<String> codes) {}

    /**
     * The run's still-issued codes, one group per line, grouped by product: products in the order they first appear
     * in the run's lines, a product's lines by line number.
     */
    private List<Chunk> groups(UUID tenantId, UUID runId) {
        Map<UUID, Chunk> byLine = new LinkedHashMap<>();
        jdbc.query(
            "SELECT l.id AS line_id, pr.title AS product_title, v.title AS variant_title, v.sku, c.code " +
            "FROM label_run_lines l JOIN variants v ON v.id = l.variant_id JOIN products pr ON pr.id = v.product_id " +
            "JOIN label_codes c ON c.run_line_id = l.id AND c.tenant_id = l.tenant_id AND c.status = 'issued' " +
            "WHERE l.run_id = ? AND l.tenant_id = ? " +
            "ORDER BY min(l.line_no) OVER (PARTITION BY v.product_id), v.product_id, l.line_no, length(c.code), c.code",
            rs -> {
                UUID line = rs.getObject("line_id", UUID.class);
                byLine.computeIfAbsent(line, k -> {
                    try {
                        return new Chunk(k, rs.getString("product_title"), rs.getString("variant_title"), rs.getString("sku"),
                            new ArrayList<>());
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }).codes().add(rs.getString("code"));
            },
            runId, tenantId);
        return new ArrayList<>(byLine.values());
    }

    /**
     * Parts of ≤ {@link #PART_SIZE} labels. A line's group moves whole to the next part rather than being split; only a
     * group larger than a part is split (it then starts a fresh part and fills parts in turn, each piece with its header).
     */
    static List<List<Chunk>> pack(List<Chunk> groups) {
        List<List<Chunk>> parts = new ArrayList<>();
        List<Chunk> current = new ArrayList<>();
        int size = 0;
        for (Chunk g : groups) {
            if (g.codes().size() <= PART_SIZE) {
                if (size + g.codes().size() > PART_SIZE) {
                    parts.add(current);
                    current = new ArrayList<>();
                    size = 0;
                }
                current.add(g);
                size += g.codes().size();
                continue;
            }
            if (!current.isEmpty()) {
                parts.add(current);
                current = new ArrayList<>();
                size = 0;
            }
            for (int from = 0; from < g.codes().size(); from += PART_SIZE) {
                List<String> slice = g.codes().subList(from, Math.min(g.codes().size(), from + PART_SIZE));
                Chunk c = new Chunk(g.lineId(), g.productTitle(), g.variantTitle(), g.sku(), List.copyOf(slice));
                if (slice.size() == PART_SIZE) {
                    parts.add(List.of(c));
                } else {
                    current.add(c);
                    size = slice.size();
                }
            }
        }
        if (!current.isEmpty()) parts.add(current);
        return parts;
    }

    // ── Excel import (expected quantities) ───────────────────────────────────

    /**
     * The receiving import's own parse + match step (Shopify inventory file or the template), "On hand (new)" read as
     * each variant's EXPECTED quantity. Writes nothing; the screen fills the run's lines from the matched rows. A row
     * above the per-line limit moves to the unmatched list as too_large.
     */
    @Transactional(readOnly = true)
    public ReceivingImportService.Preview importPreview(String filename, byte[] bytes) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        ReceivingImportService.Preview p = importService.match(tenantId, filename, bytes, false);
        List<ReceivingImportService.MatchedRow> matched = new ArrayList<>();
        List<ReceivingImportService.UnmatchedRow> unmatched = new ArrayList<>(p.unmatched());
        for (ReceivingImportService.MatchedRow m : p.matched()) {
            if (m.quantity() > MAX_LINE_UNITS) {
                unmatched.add(new ReceivingImportService.UnmatchedRow(m.rowNumber(), null, m.productTitle(), m.variantTitle(),
                    m.sku(), String.valueOf(m.quantity()), "too_large"));
            } else {
                matched.add(m);
            }
        }
        unmatched.sort(Comparator.comparingInt(ReceivingImportService.UnmatchedRow::rowNumber));
        int units = matched.stream().mapToInt(ReceivingImportService.MatchedRow::quantity).sum();
        return new ReceivingImportService.Preview(p.location(), units, matched, unmatched, p.skipped(),
            ReceivingImportService.COLUMN_NEW, p.newColumnEmpty());
    }
}
