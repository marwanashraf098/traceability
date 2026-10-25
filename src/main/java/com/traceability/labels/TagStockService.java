package com.traceability.labels;

import com.traceability.inventory.InventoryLedger;
import com.traceability.inventory.ScanInputNormalizer;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Flexible labeling slice 3 — Tag stock: give untagged pieces at the MAIN warehouse their labels.
 * Pick a variant, print N named labels (N ≤ its untagged count), stick one on each piece and scan it:
 * the scan binds the code to an untagged piece of the code's variant (InventoryLedger.bindLabel — no stock
 * change, no Shopify call). Every method answers 404 unless the tenant has flexible labeling.
 * Counts are of AVAILABLE pieces at the main warehouse (untagged stock only exists there).
 */
@Service
public class TagStockService {

    /** unusedThisSession: issued tag codes printed in the caller's open session (0 without one) — reprint first. */
    /** reusableLabels: issued tag / replacement codes of the variant with no order refs — reprinted before new ones. */
    public record VariantStock(UUID variantId, String productTitle, String variantTitle, String sku, String imageUrl,
                               int untagged, int labeled, int unusedLabels, int unusedThisSession, int reusableLabels) {}
    /** One product's labels in a multi-product print: reused (reprinted) codes first, then new ones. */
    public record PrintGroup(UUID variantId, String productTitle, String variantTitle, String sku, List<UUID> codeIds,
                             int reprints) {}
    public record PrintMany(int labels, int reprints, int issued, List<PrintGroup> groups) {}
    public record PrintLine(UUID variantId, Integer count) {}
    /** leftovers: labels left unused by ENDED sessions, per variant (reprint or void them). */
    public record Overview(int labeled, int untagged, List<VariantStock> variants, List<Leftover> leftovers) {}
    public record Leftover(UUID variantId, String productTitle, String variantTitle, String sku, String imageUrl,
                           int count, List<UUID> codeIds) {}
    public record SessionVariant(UUID variantId, String productTitle, String variantTitle, String sku, String imageUrl,
                                 int printed, int tagged, int unused, int untaggedLeft, int labeled) {}
    public record Session(UUID id, String startedAt, String endedAt, int tagged, List<SessionVariant> variants) {}
    public record ScanOutcome(String outcome, String code, String messageEn, String messageAr, VariantStock variant,
                              int taggedThisSession, boolean viaPhone) {}

    private final JdbcTemplate jdbc;
    private final InventoryLedger ledger;
    private final LabelCodeService labelCodes;

    public TagStockService(JdbcTemplate jdbc, InventoryLedger ledger, LabelCodeService labelCodes) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.labelCodes = labelCodes;
    }

    // ── overview ──────────────────────────────────────────────────────────────

    /** Variants with untagged stock (most untagged first), filtered by product / variant / SKU; warehouse totals. */
    @Transactional(readOnly = true)
    public Overview overview(String query) {
        return overview(query, null);
    }

    @Transactional(readOnly = true)
    public Overview overview(String query, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        UUID main = mainWarehouse(tenantId);
        UUID openSession = actorUserId == null ? null : openSession(tenantId, actorUserId);
        Map<String, Object> totals = jdbc.queryForMap(
            "SELECT count(*) FILTER (WHERE short_code IS NOT NULL)::int AS labeled, " +
            "       count(*) FILTER (WHERE short_code IS NULL)::int AS untagged " +
            "FROM pieces WHERE tenant_id = ? AND current_location_id = ? AND status = 'available'", tenantId, main);
        String q = query == null ? "" : query.strip();
        List<VariantStock> variants = jdbc.query(
            VARIANT_SQL + "WHERE v.tenant_id = ? AND s.untagged > 0 " +
            (q.isEmpty() ? "" : "AND (pr.title ILIKE ? OR v.title ILIKE ? OR v.sku ILIKE ?) ") +
            "ORDER BY s.untagged DESC, pr.title, v.title LIMIT 200",
            VARIANT_ROW,
            q.isEmpty() ? new Object[] { openSession, tenantId, main, tenantId, tenantId }
                        : new Object[] { openSession, tenantId, main, tenantId, tenantId, like(q), like(q), like(q) });
        return new Overview((Integer) totals.get("labeled"), (Integer) totals.get("untagged"), variants,
            leftovers(tenantId, null));
    }

    /**
     * Labels left over from ENDED Tag stock sessions: issued tag / replacement codes (no order refs) whose batch
     * belongs to a session that has ended. They still bind when scanned; the list offers reprint and void.
     */
    private List<Leftover> leftovers(UUID tenantId, UUID variantId) {
        Map<UUID, List<UUID>> codes = new LinkedHashMap<>();
        Map<UUID, Object[]> info = new HashMap<>();
        jdbc.query(
            "SELECT lc.id, lc.variant_id, pr.title AS product_title, v.title AS variant_title, v.sku, pr.image_url " +
            "FROM label_codes lc " +
            "JOIN label_code_batches b ON b.id = lc.batch_id AND b.tenant_id = lc.tenant_id " +
            "JOIN tag_sessions ts ON ts.id = b.tag_session_id AND ts.tenant_id = b.tenant_id AND ts.ended_at IS NOT NULL " +
            "JOIN variants v ON v.id = lc.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE lc.tenant_id = ? AND lc.status = 'issued' AND lc.kind IN ('tag','replacement') AND lc.order_id IS NULL " +
            "  AND (?::uuid IS NULL OR lc.variant_id = ?) " +
            "ORDER BY pr.title, v.title, length(lc.code), lc.code",
            rs -> {
                UUID v = rs.getObject("variant_id", UUID.class);
                codes.computeIfAbsent(v, k -> new ArrayList<>()).add(rs.getObject("id", UUID.class));
                info.putIfAbsent(v, new Object[] { rs.getString("product_title"), rs.getString("variant_title"),
                    rs.getString("sku"), rs.getString("image_url") });
            },
            tenantId, variantId, variantId);
        List<Leftover> out = new ArrayList<>();
        codes.forEach((v, ids) -> {
            Object[] i = info.get(v);
            out.add(new Leftover(v, (String) i[0], (String) i[1], (String) i[2], (String) i[3], ids.size(), List.copyOf(ids)));
        });
        return out;
    }

    /** A variant's leftover labels (ended sessions) as a PDF — the same codes, nothing new issued. */
    @Transactional(readOnly = true)
    public byte[] leftoverPdf(UUID variantId) throws java.io.IOException {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        List<Leftover> l = leftovers(tenantId, variantId);
        return labelCodes.codesPdf(l.isEmpty() ? List.of() : l.get(0).codeIds());
    }

    /** First parameter: the session whose unused tag codes count as unusedThisSession (NULL → 0). */
    private static final String VARIANT_SQL =
        "SELECT v.id, pr.title AS product_title, v.title AS variant_title, v.sku, pr.image_url, " +
        "       (SELECT count(*)::int FROM label_codes lc JOIN label_code_batches b ON b.id = lc.batch_id " +
        "          AND b.tenant_id = lc.tenant_id WHERE lc.tenant_id = v.tenant_id AND lc.variant_id = v.id " +
        "          AND lc.status = 'issued' AND lc.kind = 'tag' AND b.tag_session_id = ?::uuid) AS unused_session, " +
        "       (SELECT count(*)::int FROM label_codes lc WHERE lc.tenant_id = v.tenant_id AND lc.variant_id = v.id " +
        "          AND lc.status = 'issued' AND lc.kind IN ('tag','replacement') AND lc.order_id IS NULL) AS reusable, " +
        "       coalesce(s.untagged, 0) AS untagged, coalesce(s.labeled, 0) AS labeled, " +
        "       (SELECT count(*)::int FROM label_codes lc WHERE lc.tenant_id = v.tenant_id AND lc.variant_id = v.id " +
        "          AND lc.status = 'issued') AS unused " +
        "FROM variants v JOIN products pr ON pr.id = v.product_id " +
        "LEFT JOIN (SELECT variant_id, count(*) FILTER (WHERE short_code IS NULL)::int AS untagged, " +
        "                  count(*) FILTER (WHERE short_code IS NOT NULL)::int AS labeled " +
        "           FROM pieces WHERE tenant_id = ? AND current_location_id = ? AND status = 'available' " +
        "           GROUP BY variant_id) s ON s.variant_id = v.id " +
        "AND v.tenant_id = ? ";

    private static final org.springframework.jdbc.core.RowMapper<VariantStock> VARIANT_ROW = (rs, i) -> new VariantStock(
        rs.getObject("id", UUID.class), rs.getString("product_title"), rs.getString("variant_title"), rs.getString("sku"),
        rs.getString("image_url"), rs.getInt("untagged"), rs.getInt("labeled"), rs.getInt("unused"),
        rs.getInt("unused_session"), rs.getInt("reusable"));

    private VariantStock variantStock(UUID tenantId, UUID main, UUID variantId, UUID sessionId) {
        List<VariantStock> rows = jdbc.query(VARIANT_SQL + "WHERE v.tenant_id = ? AND v.id = ?", VARIANT_ROW,
            sessionId, tenantId, main, tenantId, tenantId, variantId);
        if (rows.isEmpty()) throw LabelCodeException.unknownVariant();
        return rows.get(0);
    }

    private static String like(String q) {
        return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    // ── sessions ──────────────────────────────────────────────────────────────

    /** The caller's open session at the main warehouse, else a new one. */
    @Transactional
    public Session start(UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        UUID main = mainWarehouse(tenantId);
        UUID open = openSession(tenantId, actorUserId);
        UUID id = open == null
            ? jdbc.queryForObject("INSERT INTO tag_sessions (tenant_id, location_id, started_by) VALUES (?, ?, ?) RETURNING id",
                UUID.class, tenantId, main, actorUserId)
            : open;
        return session(tenantId, main, id);
    }

    private UUID openSession(UUID tenantId, UUID actorUserId) {
        List<UUID> open = jdbc.queryForList(
            "SELECT id FROM tag_sessions WHERE tenant_id = ? AND started_by = ? AND ended_at IS NULL " +
            "ORDER BY started_at DESC, id DESC LIMIT 1", UUID.class, tenantId, actorUserId);
        return open.isEmpty() ? null : open.get(0);
    }

    @Transactional(readOnly = true)
    public Session get(UUID sessionId) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        requireSession(tenantId, sessionId, false);
        return session(tenantId, mainWarehouse(tenantId), sessionId);
    }

    /** Print {@code count} named labels for a variant (count ≤ its untagged pieces at the main warehouse). */
    @Transactional
    public LabelCodeService.Batch print(UUID sessionId, UUID variantId, int count, String idempotencyKey, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        requireSession(tenantId, sessionId, true);
        VariantStock v = variantStock(tenantId, mainWarehouse(tenantId), variantId, sessionId);
        if (count > v.untagged()) throw LabelCodeException.tooManyLabels(v.untagged());
        return labelCodes.issue(actorUserId, "tag", List.of(new LabelCodeService.Line(variantId, count, null, null)),
            idempotencyKey, sessionId);
    }

    /**
     * Inventory → Untagged (UI pass 2026-10-25): labels for several products in ONE print. Per product (in the order
     * given), its reusable codes — issued tag / replacement codes with no order refs, oldest first — are reprinted
     * first and only the remainder is newly issued (one issue call, the caller's idempotency key, this session).
     * A retry with the same key reuses what the first attempt issued, so nothing is ever issued twice; a per-tenant
     * lock keeps two prints from both issuing. count ≤ the product's untagged pieces; ≤ 2,000 labels in all.
     */
    @Transactional
    public PrintMany printMany(UUID sessionId, List<PrintLine> lines, String idempotencyKey, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        requireSession(tenantId, sessionId, true);
        if (lines == null || lines.isEmpty()) throw LabelCodeException.invalidCount();
        Map<UUID, Integer> wanted = new LinkedHashMap<>();
        int total = 0;
        for (PrintLine l : lines) {
            if (l == null || l.variantId() == null || l.count() == null || l.count() < 1 || wanted.containsKey(l.variantId())) {
                throw LabelCodeException.invalidCount();
            }
            wanted.put(l.variantId(), l.count());
            total += l.count();
        }
        if (total > LabelCodeService.MAX_CODES_PER_BATCH) throw LabelCodeException.invalidCount();
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('tag-print:' || ?::text))::text", String.class, tenantId);
        UUID main = mainWarehouse(tenantId);

        Map<UUID, VariantStock> stock = new LinkedHashMap<>();
        Map<UUID, List<UUID>> reused = new LinkedHashMap<>();
        List<LabelCodeService.Line> toIssue = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : wanted.entrySet()) {
            VariantStock v = variantStock(tenantId, main, e.getKey(), sessionId);
            if (e.getValue() > v.untagged()) throw LabelCodeException.tooManyLabels(v.untagged());
            stock.put(e.getKey(), v);
            List<UUID> ids = jdbc.queryForList(
                "SELECT id FROM label_codes WHERE tenant_id = ? AND variant_id = ? AND status = 'issued' " +
                "  AND kind IN ('tag','replacement') AND order_id IS NULL " +
                // UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
                "ORDER BY created_at, length(code), code LIMIT ? FOR UPDATE",
                UUID.class, tenantId, e.getKey(), e.getValue());
            reused.put(e.getKey(), ids);
            int rest = e.getValue() - ids.size();
            if (rest > 0) toIssue.add(new LabelCodeService.Line(e.getKey(), rest, null, null));
        }
        Map<UUID, List<UUID>> fresh = new HashMap<>();
        if (!toIssue.isEmpty()) {
            LabelCodeService.Batch b = labelCodes.issue(actorUserId, "tag", toIssue, idempotencyKey, sessionId);
            for (LabelCodeService.IssuedCode c : b.codes()) fresh.computeIfAbsent(c.variantId(), k -> new ArrayList<>()).add(c.id());
        }
        List<PrintGroup> groups = new ArrayList<>();
        int reprints = 0, issued = 0;
        for (UUID variantId : wanted.keySet()) {
            VariantStock v = stock.get(variantId);
            List<UUID> ids = new ArrayList<>(reused.get(variantId));
            List<UUID> added = fresh.getOrDefault(variantId, List.of());
            ids.addAll(added);
            reprints += reused.get(variantId).size();
            issued += added.size();
            groups.add(new PrintGroup(variantId, v.productTitle(), v.variantTitle(), v.sku(), List.copyOf(ids),
                reused.get(variantId).size()));
        }
        return new PrintMany(reprints + issued, reprints, issued, groups);
    }

    /**
     * ONE PDF for a multi-product print, grouped by product: a plain-text product separator (product name, then the
     * variant — no barcode) as each group's HEADER, then that product's labels; nothing after the last label. Prints
     * only codes of that product that are still issued.
     */
    @Transactional(readOnly = true)
    public byte[] groupsPdf(UUID sessionId, List<PrintGroup> groups) throws java.io.IOException {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        requireSession(tenantId, sessionId, false);
        List<com.traceability.inventory.LabelPdfRenderer.Page> pages = new ArrayList<>();
        int labels = 0;
        for (PrintGroup g : groups == null ? List.<PrintGroup>of() : groups) {
            if (g == null || g.variantId() == null || g.codeIds() == null || g.codeIds().isEmpty()) continue;
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT lc.code, pr.title AS product_title, v.title AS variant_title, v.sku " +
                "FROM label_codes lc JOIN variants v ON v.id = lc.variant_id JOIN products pr ON pr.id = v.product_id " +
                "WHERE lc.tenant_id = ? AND lc.variant_id = ? AND lc.id = ANY(?::uuid[]) AND lc.status = 'issued' " +
                "ORDER BY length(lc.code), lc.code",
                tenantId, g.variantId(), g.codeIds().toArray(new UUID[0]));
            if (rows.isEmpty()) continue;
            Map<String, Object> first = rows.get(0);
            pages.add(new com.traceability.inventory.LabelPdfRenderer.Separator(
                (String) first.get("product_title"), (String) first.get("variant_title")));
            for (Map<String, Object> r : rows) {
                pages.add(new com.traceability.inventory.LabelPdfRenderer.Piece((String) r.get("code"),
                    (String) r.get("product_title"), (String) r.get("variant_title"), (String) r.get("sku")));
            }
            labels += rows.size();
        }
        if (labels == 0) {
            throw new LabelCodeException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                LabelCodeException.Code.NO_ISSUED_CODES, "These labels are already used or voided", "هذه الملصقات مستخدمة أو ملغاة");
        }
        return com.traceability.inventory.LabelPdfRenderer.renderPages(pages,
            com.traceability.inventory.PieceLabelLayout.Spec.of(LabelCodeService.LABEL_WIDTH_MM, LabelCodeService.LABEL_HEIGHT_MM));
    }

    /**
     * Reprint, not reissue (live-test fix): the variant's tag codes printed in THIS session that are still unused,
     * as a PDF of the same codes. Nothing is issued. 422 NO_ISSUED_CODES when there are none.
     */
    @Transactional(readOnly = true)
    public byte[] reprintUnused(UUID sessionId, UUID variantId) throws java.io.IOException {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        requireSession(tenantId, sessionId, true);
        List<UUID> ids = jdbc.queryForList(
            "SELECT lc.id FROM label_codes lc JOIN label_code_batches b ON b.id = lc.batch_id AND b.tenant_id = lc.tenant_id " +
            "WHERE lc.tenant_id = ? AND lc.variant_id = ? AND lc.status = 'issued' AND lc.kind = 'tag' AND b.tag_session_id = ?",
            UUID.class, tenantId, variantId, sessionId);
        return labelCodes.codesPdf(ids);
    }

    /**
     * One named label for a torn / misprinted one (kind replacement, same variant). An unused replacement code of
     * the variant (no order refs, still issued — from any Tag stock print) is reprinted before a new one is issued;
     * its batch is answered with created = false (a replacement batch holds that one code).
     */
    @Transactional
    public LabelCodeService.Batch replacement(UUID sessionId, UUID variantId, String idempotencyKey, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        requireSession(tenantId, sessionId, true);
        variantStock(tenantId, mainWarehouse(tenantId), variantId, sessionId);
        List<LabelCodeService.Batch> unused = jdbc.query(
            "SELECT lc.id, lc.code, lc.batch_id FROM label_codes lc " +
            "WHERE lc.tenant_id = ? AND lc.variant_id = ? AND lc.kind = 'replacement' AND lc.status = 'issued' " +
            "  AND lc.order_id IS NULL " +
            "  AND (SELECT count(*) FROM label_codes o WHERE o.batch_id = lc.batch_id AND o.tenant_id = lc.tenant_id) = 1 " +
            // UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
            "ORDER BY lc.created_at, lc.id LIMIT 1",
            (rs, i) -> new LabelCodeService.Batch(rs.getObject("batch_id", UUID.class), "replacement", 1, false,
                List.of(new LabelCodeService.IssuedCode(rs.getObject("id", UUID.class), rs.getString("code"), variantId))),
            tenantId, variantId);
        if (!unused.isEmpty()) return unused.get(0);
        return labelCodes.issue(actorUserId, "replacement", List.of(new LabelCodeService.Line(variantId, 1, null, null)),
            idempotencyKey, sessionId);
    }

    /**
     * A scanned label: an ISSUED code is bound to an untagged piece of its variant (TAGGED); a code already on a
     * piece — bound earlier, or any piece label — is ALREADY_TAGGED (info, nothing written); anything else is
     * REJECTED with a readable reason (unknown code, voided, no untagged piece of that variant left).
     */
    @Transactional
    public ScanOutcome scan(UUID sessionId, String raw, UUID actorUserId, boolean viaPhone) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        requireSession(tenantId, sessionId, true);
        UUID main = mainWarehouse(tenantId);
        String code = ScanInputNormalizer.normalizePiece(raw == null ? "" : raw);

        List<Map<String, Object>> label = jdbc.queryForList(
            "SELECT id, status, variant_id FROM label_codes WHERE tenant_id = ? AND code = ?", tenantId, code);
        if (label.isEmpty()) {
            List<UUID> piece = jdbc.queryForList(
                "SELECT variant_id FROM pieces WHERE tenant_id = ? AND short_code = ?", UUID.class, tenantId, code);
            if (!piece.isEmpty()) return info(tenantId, main, sessionId, piece.get(0), code, viaPhone);
            return rejected(tenantId, sessionId, code, LabelCodeException.unknownCode(), viaPhone);
        }
        UUID variantId = (UUID) label.get(0).get("variant_id");
        try {
            InventoryLedger.BindResult r = ledger.bindLabel((UUID) label.get(0).get("id"), actorUserId,
                InventoryLedger.BindRef.tagSession(sessionId, viaPhone), raw);
            if (r.alreadyBound()) return info(tenantId, main, sessionId, variantId, code, viaPhone);
            return new ScanOutcome("TAGGED", code, null, null, variantStock(tenantId, main, variantId, sessionId),
                taggedIn(tenantId, sessionId), viaPhone);
        } catch (LabelCodeException e) {
            return rejected(tenantId, sessionId, code, e, viaPhone, variantStock(tenantId, main, variantId, sessionId));
        }
    }

    private ScanOutcome info(UUID tenantId, UUID main, UUID sessionId, UUID variantId, String code, boolean viaPhone) {
        return new ScanOutcome("ALREADY_TAGGED", code, "Already tagged", "مُلصقة بالفعل",
            variantStock(tenantId, main, variantId, sessionId), taggedIn(tenantId, sessionId), viaPhone);
    }

    private ScanOutcome rejected(UUID tenantId, UUID sessionId, String code, LabelCodeException e, boolean viaPhone) {
        return rejected(tenantId, sessionId, code, e, viaPhone, null);
    }

    private ScanOutcome rejected(UUID tenantId, UUID sessionId, String code, LabelCodeException e, boolean viaPhone,
                                 VariantStock variant) {
        return new ScanOutcome("REJECTED:" + e.code().name(), code, e.messageEn(), e.messageAr(), variant,
            taggedIn(tenantId, sessionId), viaPhone);
    }

    /** Ends the session; its unused labels stay valid for their variant (or void them with {@link #voidUnused}). */
    @Transactional
    public Session end(UUID sessionId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        requireSession(tenantId, sessionId, false);
        jdbc.update("UPDATE tag_sessions SET ended_at = now(), ended_by = ? WHERE id = ? AND tenant_id = ? AND ended_at IS NULL",
            actorUserId, sessionId, tenantId);
        return session(tenantId, mainWarehouse(tenantId), sessionId);
    }

    /** Voids the session's still-issued labels (every variant, or one). Returns how many. */
    @Transactional
    public int voidUnused(UUID sessionId, UUID variantId) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        requireSession(tenantId, sessionId, false);
        return jdbc.update(
            "UPDATE label_codes SET status = 'voided', voided_at = now(), void_reason = 'tag_session_unused' " +
            "WHERE tenant_id = ? AND status = 'issued' AND (?::uuid IS NULL OR variant_id = ?) " +
            "  AND batch_id IN (SELECT id FROM label_code_batches WHERE tenant_id = ? AND tag_session_id = ?)",
            tenantId, variantId, variantId, tenantId, sessionId);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID mainWarehouse(UUID tenantId) {
        List<UUID> main = jdbc.queryForList("SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment", UUID.class, tenantId);
        if (main.isEmpty()) throw LabelCodeException.noMainWarehouse();
        return main.get(0);
    }

    private void requireSession(UUID tenantId, UUID sessionId, boolean mustBeOpen) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT ended_at FROM tag_sessions WHERE id = ? AND tenant_id = ?", sessionId, tenantId);
        if (rows.isEmpty()) throw LabelCodeException.tagSessionNotFound();
        if (mustBeOpen && rows.get(0).get("ended_at") != null) throw LabelCodeException.tagSessionEnded();
    }

    /** label_bound events of this session (one per piece tagged in it). */
    private int taggedIn(UUID tenantId, UUID sessionId) {
        Integer n = jdbc.queryForObject(
            "SELECT count(*)::int FROM piece_events WHERE tenant_id = ? AND event_type = 'label_bound' " +
            "AND metadata ->> 'tag_session_id' = ?", Integer.class, tenantId, sessionId.toString());
        return n == null ? 0 : n;
    }

    private Session session(UUID tenantId, UUID main, UUID sessionId) {
        Map<String, Object> s = jdbc.queryForMap(
            "SELECT started_at, ended_at FROM tag_sessions WHERE id = ? AND tenant_id = ?", sessionId, tenantId);
        // Per variant: printed (codes of this session's batches), tagged (this session's label_bound events),
        // unused (this session's codes still issued), plus the variant's live counts.
        List<SessionVariant> variants = jdbc.query(
            "WITH printed AS (SELECT lc.variant_id, count(*)::int AS printed, " +
            "                        count(*) FILTER (WHERE lc.status = 'issued')::int AS unused " +
            "                 FROM label_codes lc JOIN label_code_batches b ON b.id = lc.batch_id AND b.tenant_id = lc.tenant_id " +
            "                 WHERE lc.tenant_id = ? AND b.tag_session_id = ? GROUP BY lc.variant_id), " +
            "     tagged AS (SELECT p.variant_id, count(*)::int AS tagged FROM piece_events e " +
            "                JOIN pieces p ON p.id = e.piece_id AND p.tenant_id = e.tenant_id " +
            "                WHERE e.tenant_id = ? AND e.event_type = 'label_bound' AND e.metadata ->> 'tag_session_id' = ? " +
            "                GROUP BY p.variant_id), " +
            "     live AS (SELECT variant_id, count(*) FILTER (WHERE short_code IS NULL)::int AS untagged, " +
            "                     count(*) FILTER (WHERE short_code IS NOT NULL)::int AS labeled " +
            "              FROM pieces WHERE tenant_id = ? AND current_location_id = ? AND status = 'available' GROUP BY variant_id) " +
            "SELECT v.id, pr.title AS product_title, v.title AS variant_title, v.sku, pr.image_url, " +
            "       coalesce(pt.printed, 0) AS printed, coalesce(t.tagged, 0) AS tagged, coalesce(pt.unused, 0) AS unused, " +
            "       coalesce(l.untagged, 0) AS untagged, coalesce(l.labeled, 0) AS labeled " +
            "FROM variants v JOIN products pr ON pr.id = v.product_id " +
            "LEFT JOIN printed pt ON pt.variant_id = v.id LEFT JOIN tagged t ON t.variant_id = v.id " +
            "LEFT JOIN live l ON l.variant_id = v.id " +
            "WHERE v.tenant_id = ? AND (pt.variant_id IS NOT NULL OR t.variant_id IS NOT NULL) " +
            "ORDER BY pr.title, v.title",
            (rs, i) -> new SessionVariant(rs.getObject("id", UUID.class), rs.getString("product_title"),
                rs.getString("variant_title"), rs.getString("sku"), rs.getString("image_url"), rs.getInt("printed"),
                rs.getInt("tagged"), rs.getInt("unused"), rs.getInt("untagged"), rs.getInt("labeled")),
            tenantId, sessionId, tenantId, sessionId.toString(), tenantId, main, tenantId);
        Object ended = s.get("ended_at");
        return new Session(sessionId, s.get("started_at").toString(), ended == null ? null : ended.toString(),
            taggedIn(tenantId, sessionId), variants);
    }
}
