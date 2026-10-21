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
import java.math.BigDecimal;
import java.util.*;

/**
 * Flexible labeling slice 1 — blank label codes (V176). Every method answers 404 unless the caller's
 * tenant has {@code flexible_labeling_enabled} ({@link FlexibleLabeling}).
 *
 * <ul>
 *   <li>{@link #issue}: claims count + spares codes from the tenant's short-code sequence through
 *       {@link PieceCodeCounter} — the same claim receiving uses, so a blank code never collides with
 *       a received piece — and records them as issued. A repeated idempotency key returns the batch
 *       it already made and never issues again.</li>
 *   <li>{@link #voidCodes}: issued → voided only, all or nothing.</li>
 *   <li>{@link #resolve}: what a scanned code is to the label-code table. Not wired into any scan
 *       path yet (slices 3 / 4).</li>
 *   <li>{@link #batchPdf}: the batch's still-issued codes as blank labels, at the size the batch
 *       was issued with (the tenant's label size then, else 50×25).</li>
 * </ul>
 * There is no bind here: binding a code to a piece belongs to InventoryLedger (slice 3).
 */
@Service
public class LabelCodeService {

    public static final int MAX_CODES_PER_BATCH = 2000;
    public static final Set<String> KINDS = Set.of("pack", "tag", "factory", "replacement");
    static final BigDecimal DEFAULT_WIDTH_MM  = new BigDecimal("50.00");
    static final BigDecimal DEFAULT_HEIGHT_MM = new BigDecimal("25.00");

    public record IssuedCode(UUID id, String code) {}
    public record Batch(UUID id, String kind, int count, int spares, BigDecimal labelWidthMm,
                        BigDecimal labelHeightMm, boolean created, List<IssuedCode> codes) {}
    public record Resolution(String status, UUID id, UUID batchId, String kind, String pieceId) {
        static Resolution unknown() { return new Resolution("unknown", null, null, null, null); }
    }

    private final JdbcTemplate jdbc;

    public LabelCodeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── issue ─────────────────────────────────────────────────────────────────

    @Transactional
    public Batch issue(UUID actorUserId, String kind, int count, int spares, String idempotencyKey) {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        if (kind == null || !KINDS.contains(kind)) {
            throw new LabelCodeException(HttpStatus.BAD_REQUEST, LabelCodeException.Code.INVALID_KIND,
                "Label kind must be pack, tag, factory or replacement",
                "نوع الملصق يجب أن يكون pack أو tag أو factory أو replacement");
        }
        if (count < 0 || spares < 0 || count + spares < 1 || count + spares > MAX_CODES_PER_BATCH) {
            throw new LabelCodeException(HttpStatus.BAD_REQUEST, LabelCodeException.Code.INVALID_COUNT,
                "Labels plus spares must be between 1 and " + MAX_CODES_PER_BATCH,
                "عدد الملصقات مع الاحتياطي يجب أن يكون بين 1 و" + MAX_CODES_PER_BATCH);
        }
        String key = idempotencyKey == null ? "" : idempotencyKey.strip();
        if (key.isEmpty() || key.length() > 100) {
            throw new LabelCodeException(HttpStatus.BAD_REQUEST, LabelCodeException.Code.INVALID_IDEMPOTENCY_KEY,
                "A request key of 1 to 100 characters is required",
                "مطلوب مفتاح طلب من 1 إلى 100 حرف");
        }

        // A repeated key: the batch it already made (a concurrent duplicate waits on the unique index
        // in the INSERT below, then finds nothing to insert and reads the winner's batch here).
        Map<String, Object> existing = findBatch(tenantId, key);
        if (existing != null) return replay(tenantId, existing, kind, count, spares);

        BigDecimal[] size = tenantLabelSize(tenantId);
        List<UUID> inserted = jdbc.queryForList(
            "INSERT INTO label_code_batches (tenant_id, kind, count, spares, label_w_mm, label_h_mm, " +
            "                                idempotency_key, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (tenant_id, idempotency_key) DO NOTHING RETURNING id",
            UUID.class, tenantId, kind, count, spares, size[0], size[1], key, actorUserId);
        if (inserted.isEmpty()) return replay(tenantId, findBatch(tenantId, key), kind, count, spares);
        UUID batchId = inserted.get(0);

        int total = count + spares;
        long first = PieceCodeCounter.claim(jdbc, tenantId, total);
        StringBuilder sql = new StringBuilder(
            "INSERT INTO label_codes (tenant_id, code, kind, batch_id, issued_by) VALUES ");
        List<Object> params = new ArrayList<>(total * 5);
        for (int i = 0; i < total; i++) {
            if (i > 0) sql.append(',');
            sql.append("(?,?,?,?,?)");
            params.add(tenantId);
            params.add(PieceCodeCounter.format(first + i));
            params.add(kind);
            params.add(batchId);
            params.add(actorUserId);
        }
        jdbc.update(sql.toString(), params.toArray());

        return new Batch(batchId, kind, count, spares, size[0], size[1], true, codes(tenantId, batchId, false));
    }

    private Map<String, Object> findBatch(UUID tenantId, String key) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, kind, count, spares, label_w_mm, label_h_mm FROM label_code_batches " +
            "WHERE tenant_id = ? AND idempotency_key = ?", tenantId, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Batch replay(UUID tenantId, Map<String, Object> b, String kind, int count, int spares) {
        if (!kind.equals(b.get("kind")) || count != (Integer) b.get("count") || spares != (Integer) b.get("spares")) {
            throw new LabelCodeException(HttpStatus.CONFLICT, LabelCodeException.Code.IDEMPOTENCY_KEY_REUSED,
                "This request key was already used for a different batch",
                "مفتاح الطلب هذا استُخدم بالفعل لدفعة مختلفة");
        }
        UUID batchId = (UUID) b.get("id");
        return new Batch(batchId, kind, count, spares, (BigDecimal) b.get("label_w_mm"), (BigDecimal) b.get("label_h_mm"),
            false, codes(tenantId, batchId, false));
    }

    /** The batch's codes in code order (numeric — P1000000 after P999999); issued ones only when asked. */
    private List<IssuedCode> codes(UUID tenantId, UUID batchId, boolean issuedOnly) {
        return jdbc.query(
            "SELECT id, code FROM label_codes WHERE tenant_id = ? AND batch_id = ? " +
            (issuedOnly ? "AND status = 'issued' " : "") +
            "ORDER BY length(code), code",
            (rs, i) -> new IssuedCode(rs.getObject("id", UUID.class), rs.getString("code")),
            tenantId, batchId);
    }

    /** tenants.label_width_mm / label_height_mm (Settings → label size), else 50×25. */
    private BigDecimal[] tenantLabelSize(UUID tenantId) {
        return jdbc.queryForObject(
            "SELECT label_width_mm, label_height_mm FROM tenants WHERE id = ?",
            (rs, i) -> {
                BigDecimal w = rs.getBigDecimal("label_width_mm");
                BigDecimal h = rs.getBigDecimal("label_height_mm");
                return (w == null || h == null || w.signum() <= 0 || h.signum() <= 0)
                    ? new BigDecimal[] { DEFAULT_WIDTH_MM, DEFAULT_HEIGHT_MM }
                    : new BigDecimal[] { w, h };
            },
            tenantId);
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
        // Lock the requested rows first, so a code can't be bound (slice 3) between the check and the void.
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
            "SELECT id, status, batch_id, kind, piece_id FROM label_codes WHERE tenant_id = ? AND code = ?",
            (rs, i) -> new Resolution(rs.getString("status"), rs.getObject("id", UUID.class),
                rs.getObject("batch_id", UUID.class), rs.getString("kind"), rs.getString("piece_id")),
            tenantId, code);
        return rows.isEmpty() ? Resolution.unknown() : rows.get(0);
    }

    // ── PDF ───────────────────────────────────────────────────────────────────

    /** The batch's still-issued codes as blank labels (a reprint prints nothing that was bound or voided). */
    @Transactional(readOnly = true)
    public byte[] batchPdf(UUID batchId) throws IOException {
        UUID tenantId = TenantContext.require();
        FlexibleLabeling.require(jdbc, tenantId);
        List<Map<String, Object>> b = jdbc.queryForList(
            "SELECT label_w_mm, label_h_mm FROM label_code_batches WHERE id = ? AND tenant_id = ?", batchId, tenantId);
        if (b.isEmpty()) {
            throw new LabelCodeException(HttpStatus.NOT_FOUND, LabelCodeException.Code.BATCH_NOT_FOUND,
                "Label batch not found", "لم يتم العثور على دفعة الملصقات");
        }
        List<String> codes = codes(tenantId, batchId, true).stream().map(IssuedCode::code).toList();
        if (codes.isEmpty()) {
            throw new LabelCodeException(HttpStatus.UNPROCESSABLE_ENTITY, LabelCodeException.Code.NO_ISSUED_CODES,
                "Every code in this batch is already used or voided",
                "كل رموز هذه الدفعة مستخدمة أو ملغاة");
        }
        BigDecimal w = (BigDecimal) b.get(0).get("label_w_mm");
        BigDecimal h = (BigDecimal) b.get(0).get("label_h_mm");
        return LabelPdfRenderer.renderBlank(codes, PieceLabelLayout.Spec.of(w.floatValue(), h.floatValue()));
    }
}
