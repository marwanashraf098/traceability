package com.traceability.labels;

import com.traceability.inventory.LabelPdfRenderer;
import com.traceability.inventory.PieceCodeCounter;
import com.traceability.inventory.PieceLabelLayout;
import com.traceability.inventory.ScanInputNormalizer;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.*;

/**
 * Flexible labeling — NAMED label codes (V176, named since V181 / slice 3). Every method answers 404 unless
 * the caller's tenant has {@code flexible_labeling_enabled} ({@link FlexibleLabeling}).
 *
 * <ul>
 *   <li>{@link #issue}: one code per unit of each line, each tied to its line's VARIANT (and, for packing,
 *       the order line it was printed for — grouping only). Codes come from the tenant's short-code sequence
 *       through {@link PieceCodeCounter}, the same claim receiving uses, so a code never collides with a
 *       received piece. A repeated idempotency key returns the batch it already made and never issues again.</li>
 *   <li>{@link #voidCodes}: issued → voided only, all or nothing.</li>
 *   <li>{@link #resolve}: what a scanned code is to the label-code table.</li>
 *   <li>{@link #batchPdf}: the batch's still-issued codes printed as the NORMAL named piece label
 *       (code, product, variant, SKU — PieceLabelLayout via LabelPdfRenderer.render, 50×25 like every piece
 *       label today).</li>
 * </ul>
 * Binding a code to a piece is InventoryLedger.bindLabel (the ledger stays the only writer of pieces).
 */
@Service
public class LabelCodeService {

    public static final int MAX_CODES_PER_BATCH = 2000;
    public static final Set<String> KINDS = Set.of("pack", "tag", "factory", "replacement");
    static final float LABEL_WIDTH_MM = 50f, LABEL_HEIGHT_MM = 25f;   // = LabelService.DEFAULT_*_MM

    /** One line of an issue request: {@code count} codes for {@code variantId} (order refs: packing only). */
    public record Line(UUID variantId, Integer count, UUID orderId, UUID orderItemId) {}
    public record IssuedCode(UUID id, String code, UUID variantId) {}
    public record Batch(UUID id, String kind, int count, boolean created, List<IssuedCode> codes) {}
    public record Resolution(String status, UUID id, UUID batchId, String kind, String pieceId, UUID variantId) {
        static Resolution unknown() { return new Resolution("unknown", null, null, null, null, null); }
    }

    private final JdbcTemplate jdbc;

    public LabelCodeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── issue ─────────────────────────────────────────────────────────────────

    @Transactional
    public Batch issue(UUID actorUserId, String kind, List<Line> lines, String idempotencyKey) {
        return issue(actorUserId, kind, lines, idempotencyKey, null);
    }

    /** {@code tagSessionId}: the Tag stock session a tag / replacement print belongs to (else null). */
    @Transactional
    public Batch issue(UUID actorUserId, String kind, List<Line> lines, String idempotencyKey, UUID tagSessionId) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        if (kind == null || !KINDS.contains(kind)) throw LabelCodeException.invalidKind();
        // Factory codes belong to a factory run line (slice 6) and are issued only by the run's print, never here.
        if ("factory".equals(kind)) throw LabelCodeException.factoryKindOnlyFromRun();
        List<Line> clean = validateLines(tenantId, lines);
        int total = clean.stream().mapToInt(Line::count).sum();
        String key = idempotencyKey == null ? "" : idempotencyKey.strip();
        if (key.isEmpty() || key.length() > 100) throw LabelCodeException.invalidKey();

        // A repeated key: the batch it already made (a concurrent duplicate waits on the unique index
        // in the INSERT below, then finds nothing to insert and reads the winner's batch here).
        Map<String, Object> existing = findBatch(tenantId, key);
        if (existing != null) return replay(tenantId, existing, kind, clean);

        List<UUID> inserted = jdbc.queryForList(
            "INSERT INTO label_code_batches (tenant_id, kind, count, idempotency_key, created_by, tag_session_id) " +
            "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, idempotency_key) DO NOTHING RETURNING id",
            UUID.class, tenantId, kind, total, key, actorUserId, tagSessionId);
        if (inserted.isEmpty()) return replay(tenantId, findBatch(tenantId, key), kind, clean);
        UUID batchId = inserted.get(0);

        long next = PieceCodeCounter.claim(jdbc, tenantId, total);
        StringBuilder sql = new StringBuilder(
            "INSERT INTO label_codes (tenant_id, code, kind, batch_id, issued_by, variant_id, order_id, order_item_id) VALUES ");
        List<Object> params = new ArrayList<>(total * 8);
        boolean first = true;
        for (Line l : clean) {
            for (int i = 0; i < l.count(); i++) {
                if (!first) sql.append(',');
                first = false;
                sql.append("(?,?,?,?,?,?,?,?)");
                params.add(tenantId);
                params.add(PieceCodeCounter.format(next++));
                params.add(kind);
                params.add(batchId);
                params.add(actorUserId);
                params.add(l.variantId());
                params.add(l.orderId());
                params.add(l.orderItemId());
            }
        }
        jdbc.update(sql.toString(), params.toArray());
        return new Batch(batchId, kind, total, true, codes(tenantId, batchId, false));
    }

    /**
     * 1..2,000 codes in all, each line ≥ 1, every variant this tenant's; an order line (packing) must be this
     * tenant's, belong to the given order and be of the line's variant.
     */
    private List<Line> validateLines(UUID tenantId, List<Line> lines) {
        if (lines == null || lines.isEmpty() || lines.stream().anyMatch(l -> l == null || l.variantId() == null
                || l.count() == null || l.count() < 1 || l.count() > MAX_CODES_PER_BATCH)) {
            throw LabelCodeException.invalidCount();
        }
        long total = lines.stream().mapToLong(Line::count).sum();
        if (total > MAX_CODES_PER_BATCH) throw LabelCodeException.invalidCount();
        Set<UUID> variants = new HashSet<>();
        lines.forEach(l -> variants.add(l.variantId()));
        Integer known = jdbc.queryForObject("SELECT count(*)::int FROM variants WHERE tenant_id = ? AND id = ANY(?::uuid[])",
            Integer.class, tenantId, variants.toArray(new UUID[0]));
        if (known == null || known != variants.size()) throw LabelCodeException.unknownVariant();
        for (Line l : lines) {
            if (l.orderItemId() == null && l.orderId() == null) continue;
            if (l.orderItemId() == null) {
                Integer o = jdbc.queryForObject("SELECT count(*)::int FROM merchant_orders WHERE id = ? AND tenant_id = ?",
                    Integer.class, l.orderId(), tenantId);
                if (o == null || o == 0) throw LabelCodeException.invalidOrderRef();
                continue;
            }
            Integer ok = jdbc.queryForObject(
                "SELECT count(*)::int FROM order_items oi JOIN merchant_orders o ON o.id = oi.order_id AND o.tenant_id = oi.tenant_id " +
                "WHERE oi.id = ? AND oi.tenant_id = ? AND oi.order_id = ? AND oi.variant_id = ?",
                Integer.class, l.orderItemId(), tenantId, l.orderId(), l.variantId());
            if (ok == null || ok == 0) throw LabelCodeException.invalidOrderRef();
        }
        return lines;
    }

    private Map<String, Object> findBatch(UUID tenantId, String key) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, kind, count FROM label_code_batches WHERE tenant_id = ? AND idempotency_key = ?", tenantId, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Same key: the same batch — only if it is the same request (kind and per-variant / order-line counts). */
    private Batch replay(UUID tenantId, Map<String, Object> b, String kind, List<Line> lines) {
        UUID batchId = (UUID) b.get("id");
        Map<String, Integer> asked = new TreeMap<>();
        lines.forEach(l -> asked.merge(l.variantId() + "|" + l.orderItemId(), l.count(), Integer::sum));
        Map<String, Integer> made = new TreeMap<>();
        jdbc.query("SELECT variant_id, order_item_id, count(*)::int AS n FROM label_codes WHERE tenant_id = ? AND batch_id = ? " +
                   "GROUP BY variant_id, order_item_id",
            rs -> { made.put(rs.getObject("variant_id", UUID.class) + "|" + rs.getObject("order_item_id", UUID.class), rs.getInt("n")); },
            tenantId, batchId);
        if (!kind.equals(b.get("kind")) || !asked.equals(made)) throw LabelCodeException.keyReused();
        return new Batch(batchId, kind, (Integer) b.get("count"), false, codes(tenantId, batchId, false));
    }

    /** The batch's codes in code order (numeric — P1000000 after P999999); issued ones only when asked. */
    private List<IssuedCode> codes(UUID tenantId, UUID batchId, boolean issuedOnly) {
        return jdbc.query(
            "SELECT id, code, variant_id FROM label_codes WHERE tenant_id = ? AND batch_id = ? " +
            (issuedOnly ? "AND status = 'issued' " : "") +
            "ORDER BY length(code), code",
            (rs, i) -> new IssuedCode(rs.getObject("id", UUID.class), rs.getString("code"), rs.getObject("variant_id", UUID.class)),
            tenantId, batchId);
    }

    // ── void ──────────────────────────────────────────────────────────────────

    /**
     * Voids issued codes — all or nothing. A code that is bound, already voided, or not this
     * tenant's refuses the whole request (409 VOID_REFUSED, each refused id with its status).
     * Returns how many were voided.
     */
    @Transactional
    public int voidCodes(List<UUID> codeIds, String reason) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        String why = reason == null ? "" : reason.strip();
        Set<UUID> ids = codeIds == null ? Set.of() : new LinkedHashSet<>(codeIds);
        ids.remove(null);
        if (ids.isEmpty() || ids.size() > MAX_CODES_PER_BATCH || why.isEmpty() || why.length() > 100) {
            throw new LabelCodeException(HttpStatus.BAD_REQUEST, LabelCodeException.Code.INVALID_VOID_REQUEST,
                "Choose 1 to " + MAX_CODES_PER_BATCH + " codes and give a reason of up to 100 characters",
                "اختر من 1 إلى " + MAX_CODES_PER_BATCH + " رمز واكتب سببًا حتى 100 حرف");
        }
        UUID[] arr = ids.toArray(new UUID[0]);
        // Lock the requested rows first, so a code can't be bound between the check and the void.
        Map<UUID, String> found = new HashMap<>();
        jdbc.query("SELECT id, status FROM label_codes WHERE tenant_id = ? AND id = ANY(?::uuid[]) FOR UPDATE",
            rs -> { found.put(rs.getObject("id", UUID.class), rs.getString("status")); },
            tenantId, arr);
        List<Map<String, Object>> refused = new ArrayList<>();
        for (UUID id : ids) {
            String status = found.getOrDefault(id, "unknown");
            if (!"issued".equals(status)) refused.add(Map.of("id", id, "status", status));
        }
        if (!refused.isEmpty()) {
            throw new LabelCodeException(HttpStatus.CONFLICT, LabelCodeException.Code.VOID_REFUSED,
                "Only unused (issued) codes can be voided — nothing was voided",
                "يمكن إلغاء الرموز غير المستخدمة فقط — لم يُلغَ أي رمز",
                refused);
        }
        // Conditional on 'issued' anyway — the claim, not the check, is the guard.
        return jdbc.update(
            "UPDATE label_codes SET status = 'voided', voided_at = now(), void_reason = ? " +
            "WHERE tenant_id = ? AND id = ANY(?::uuid[]) AND status = 'issued'",
            why, tenantId, arr);
    }

    // ── resolve ───────────────────────────────────────────────────────────────

    /** issued | bound | voided | unknown for a scanned value (normalized like every piece scan). */
    @Transactional(readOnly = true)
    public Resolution resolve(String scanned) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        if (scanned == null) return Resolution.unknown();
        String code = ScanInputNormalizer.normalizePiece(scanned);
        List<Resolution> rows = jdbc.query(
            "SELECT id, status, batch_id, kind, piece_id, variant_id FROM label_codes WHERE tenant_id = ? AND code = ?",
            (rs, i) -> new Resolution(rs.getString("status"), rs.getObject("id", UUID.class),
                rs.getObject("batch_id", UUID.class), rs.getString("kind"), rs.getString("piece_id"),
                rs.getObject("variant_id", UUID.class)),
            tenantId, code);
        return rows.isEmpty() ? Resolution.unknown() : rows.get(0);
    }

    // ── PDF ───────────────────────────────────────────────────────────────────

    /**
     * Named piece labels for the given codes that are still ISSUED (a reprint never prints a bound or voided code);
     * 422 NO_ISSUED_CODES when none is. Same order and layout as {@link #batchPdf}. Callers pick the codes.
     */
    @Transactional(readOnly = true)
    public byte[] codesPdf(List<UUID> codeIds) throws IOException {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        List<LabelPdfRenderer.Piece> labels = codeIds == null || codeIds.isEmpty() ? List.of() : jdbc.query(
            "SELECT lc.code, pr.title AS product_title, v.title AS variant_title, v.sku " +
            "FROM label_codes lc JOIN variants v ON v.id = lc.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE lc.tenant_id = ? AND lc.id = ANY(?::uuid[]) AND lc.status = 'issued' " +
            "ORDER BY length(lc.code), lc.code",
            (rs, i) -> new LabelPdfRenderer.Piece(rs.getString("code"), rs.getString("product_title"),
                rs.getString("variant_title"), rs.getString("sku")),
            tenantId, codeIds.toArray(new UUID[0]));
        if (labels.isEmpty()) {
            throw new LabelCodeException(HttpStatus.UNPROCESSABLE_ENTITY, LabelCodeException.Code.NO_ISSUED_CODES,
                "These labels are already used or voided", "هذه الملصقات مستخدمة أو ملغاة");
        }
        return LabelPdfRenderer.render(labels, PieceLabelLayout.Spec.of(LABEL_WIDTH_MM, LABEL_HEIGHT_MM));
    }

    /** The batch's still-issued codes as named piece labels (a reprint prints nothing bound or voided). */
    @Transactional(readOnly = true)
    public byte[] batchPdf(UUID batchId) throws IOException {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        Integer exists = jdbc.queryForObject("SELECT count(*)::int FROM label_code_batches WHERE id = ? AND tenant_id = ?",
            Integer.class, batchId, tenantId);
        if (exists == null || exists == 0) {
            throw new LabelCodeException(HttpStatus.NOT_FOUND, LabelCodeException.Code.BATCH_NOT_FOUND,
                "Label batch not found", "لم يتم العثور على دفعة الملصقات");
        }
        List<LabelPdfRenderer.Piece> labels = jdbc.query(
            "SELECT lc.code, pr.title AS product_title, v.title AS variant_title, v.sku " +
            "FROM label_codes lc JOIN variants v ON v.id = lc.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE lc.tenant_id = ? AND lc.batch_id = ? AND lc.status = 'issued' " +
            "ORDER BY length(lc.code), lc.code",
            (rs, i) -> new LabelPdfRenderer.Piece(rs.getString("code"), rs.getString("product_title"),
                rs.getString("variant_title"), rs.getString("sku")),
            tenantId, batchId);
        if (labels.isEmpty()) {
            throw new LabelCodeException(HttpStatus.UNPROCESSABLE_ENTITY, LabelCodeException.Code.NO_ISSUED_CODES,
                "Every code in this batch is already used or voided",
                "كل رموز هذه الدفعة مستخدمة أو ملغاة");
        }
        return LabelPdfRenderer.render(labels, PieceLabelLayout.Spec.of(LABEL_WIDTH_MM, LABEL_HEIGHT_MM));
    }
}
