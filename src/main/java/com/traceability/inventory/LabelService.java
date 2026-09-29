package com.traceability.inventory;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.traceability.tenancy.TenantContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * Piece label PDFs for every feature (Receiving session / variant print + reprint, Returns
 * reprint, Transfer reprint-outstanding): the queries, the label_reprints log and the size
 * defaults live here; the layout is PieceLabelLayout and the drawing LabelPdfRenderer.
 *
 * Size: 50×25 mm unless the caller passes widthMm/heightMm. The tenant's Settings value
 * (tenants.label_width_mm / label_height_mm) is not read yet — the layout fully supports 40×25.
 */
@Service
public class LabelService {

    // Default label size
    static final float DEFAULT_WIDTH_MM  = 50f;
    static final float DEFAULT_HEIGHT_MM = 25f;

    private final JdbcTemplate jdbc;

    public LabelService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generates a PDF containing one label per piece in the given session.
     * If the session has no pieces yet (not finalized), throws 422.
     *
     * @param sessionId  receipt UUID
     * @param labelW_mm  label width in mm  (null → DEFAULT_WIDTH_MM)
     * @param labelH_mm  label height in mm (null → DEFAULT_HEIGHT_MM)
     */
    @Transactional(readOnly = true)
    public byte[] generateSessionLabels(UUID sessionId, Float labelW_mm, Float labelH_mm)
            throws IOException {
        UUID tenantId = TenantContext.require();

        List<Map<String, Object>> pieces = jdbc.queryForList(
            "SELECT p.id, p.barcode, p.short_code, v.sku, v.title AS variant_title, pr.title AS product_title " +
            "FROM pieces p " +
            "JOIN variants v ON v.id = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE p.receipt_id = ? AND p.tenant_id = ? " +
            "ORDER BY p.created_at",
            sessionId, tenantId);

        if (pieces.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "No pieces found for session — finalize first");
        }

        return renderPdf(pieces,
            labelW_mm  != null ? labelW_mm  : DEFAULT_WIDTH_MM,
            labelH_mm  != null ? labelH_mm  : DEFAULT_HEIGHT_MM);
    }

    /** Generates a PDF for a single piece by its ID (for reprint). */
    @Transactional(readOnly = true)
    public byte[] generatePieceLabel(String pieceId, Float labelW_mm, Float labelH_mm)
            throws IOException {
        UUID tenantId = TenantContext.require();

        List<Map<String, Object>> pieces = jdbc.queryForList(
            "SELECT p.id, p.barcode, p.short_code, v.sku, v.title AS variant_title, pr.title AS product_title " +
            "FROM pieces p " +
            "JOIN variants v ON v.id = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE p.id = ? AND p.tenant_id = ?",
            pieceId, tenantId);

        if (pieces.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found");
        }

        return renderPdf(pieces,
            labelW_mm  != null ? labelW_mm  : DEFAULT_WIDTH_MM,
            labelH_mm  != null ? labelH_mm  : DEFAULT_HEIGHT_MM);
    }

    /**
     * One PDF for several pieces, in the given order — rendered into a single document (never
     * a merge of single-page PDFs, whose images died with their closed source documents). Any
     * id not found for this tenant → 404.
     */
    @Transactional(readOnly = true)
    public byte[] generatePieceLabels(List<String> pieceIds, Float labelW_mm, Float labelH_mm)
            throws IOException {
        UUID tenantId = TenantContext.require();
        Map<String, Map<String, Object>> byId = new java.util.HashMap<>();
        for (Map<String, Object> row : jdbc.query(con -> {
                var ps = con.prepareStatement(
                    "SELECT p.id, p.barcode, p.short_code, v.sku, v.title AS variant_title, pr.title AS product_title " +
                    "FROM pieces p " +
                    "JOIN variants v ON v.id = p.variant_id " +
                    "JOIN products pr ON pr.id = v.product_id " +
                    "WHERE p.id = ANY(?) AND p.tenant_id = ?");
                ps.setArray(1, con.createArrayOf("text", pieceIds.toArray()));
                ps.setObject(2, tenantId);
                return ps;
            }, new org.springframework.jdbc.core.ColumnMapRowMapper())) {
            byId.put((String) row.get("id"), row);
        }
        List<Map<String, Object>> pieces = new java.util.ArrayList<>();
        for (String id : pieceIds) {
            Map<String, Object> row = byId.get(id);
            if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found");
            pieces.add(row);
        }
        return renderPdf(pieces,
            labelW_mm  != null ? labelW_mm  : DEFAULT_WIDTH_MM,
            labelH_mm  != null ? labelH_mm  : DEFAULT_HEIGHT_MM);
    }

    /** Logs a reprint event and returns the PDF bytes. */
    @Transactional
    public byte[] reprint(UUID sessionId, UUID actorUserId, String note,
                          Float labelW_mm, Float labelH_mm) throws IOException {
        UUID tenantId = TenantContext.require();
        byte[] pdf = generateSessionLabels(sessionId, labelW_mm, labelH_mm);

        int count = countPieces(sessionId, tenantId);
        jdbc.update(
            "INSERT INTO label_reprints (tenant_id, receipt_id, reprinted_by, piece_count, note) " +
            "VALUES (?, ?, ?, ?, ?)",
            tenantId, sessionId, actorUserId, count, note);

        return pdf;
    }

    // ── Per-variant labels ────────────────────────────────────────────────────

    /** Returned by generateVariantLabels so the controller can build a SKU-based filename. */
    public record VariantPdf(byte[] pdf, String sku) {}

    /**
     * Generates a PDF containing one label per piece for a specific variant
     * in the given session.  Session must be finalized (404 if not found,
     * 422 if open).  Zero matching pieces → 422.
     * Ordering: barcode ASC (ULID is time-sortable = generation order).
     */
    @Transactional(readOnly = true)
    public VariantPdf generateVariantLabels(UUID sessionId, UUID variantId,
                                            Float labelW_mm, Float labelH_mm) throws IOException {
        UUID tenantId = TenantContext.require();
        requireFinalized(sessionId, tenantId);

        List<Map<String, Object>> pieces = jdbc.queryForList(
            "SELECT p.id, p.barcode, p.short_code, v.sku, v.title AS variant_title, pr.title AS product_title " +
            "FROM pieces p " +
            "JOIN variants v  ON v.id  = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE p.receipt_id = ? AND p.variant_id = ? AND p.tenant_id = ? " +
            "ORDER BY p.barcode ASC",
            sessionId, variantId, tenantId);

        if (pieces.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "No pieces found for this variant in this session");
        }

        String sku = (String) pieces.get(0).get("sku");
        byte[] pdf = renderPdf(pieces,
            labelW_mm != null ? labelW_mm : DEFAULT_WIDTH_MM,
            labelH_mm != null ? labelH_mm : DEFAULT_HEIGHT_MM);
        return new VariantPdf(pdf, sku);
    }

    /**
     * Logs a variant-level reprint event and returns the PDF bytes.
     * Writes a label_reprints row with variant_id set.
     */
    @Transactional
    public byte[] reprintVariant(UUID sessionId, UUID variantId, UUID actorUserId, String note,
                                 Float labelW_mm, Float labelH_mm) throws IOException {
        UUID tenantId = TenantContext.require();
        VariantPdf result = generateVariantLabels(sessionId, variantId, labelW_mm, labelH_mm);
        int count = countVariantPieces(sessionId, variantId, tenantId);
        jdbc.update(
            "INSERT INTO label_reprints (tenant_id, receipt_id, variant_id, reprinted_by, piece_count, note) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            tenantId, sessionId, variantId, actorUserId, count, note);
        return result.pdf();
    }

    private void requireFinalized(UUID sessionId, UUID tenantId) {
        List<String> rows = jdbc.queryForList(
            "SELECT status FROM receipts WHERE id = ? AND tenant_id = ?",
            String.class, sessionId, tenantId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
        }
        if (!"finalized".equals(rows.get(0))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Session is not finalized — finalize the session first");
        }
    }

    private int countVariantPieces(UUID sessionId, UUID variantId, UUID tenantId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pieces WHERE receipt_id = ? AND variant_id = ? AND tenant_id = ?",
            Integer.class, sessionId, variantId, tenantId);
        return n != null ? n : 0;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private byte[] renderPdf(List<Map<String, Object>> pieces,
                             float widthMm, float heightMm) throws IOException {
        List<LabelPdfRenderer.Piece> labels = pieces.stream()
            .map(p -> new LabelPdfRenderer.Piece(
                (String) p.get("short_code"),
                (String) p.get("product_title"),
                (String) p.get("variant_title"),
                (String) p.get("sku")))
            .toList();
        return LabelPdfRenderer.render(labels, PieceLabelLayout.Spec.of(widthMm, heightMm));
    }

    /**
     * Arabic shaping (contextual letter forms) + bidi reordering to visual left-to-right order;
     * Latin text passes through unchanged. Delegates to LabelTextFitter.display.
     */
    public static String shapeForDisplay(String text) {
        return LabelTextFitter.display(text);
    }

    private int countPieces(UUID sessionId, UUID tenantId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pieces WHERE receipt_id = ? AND tenant_id = ?",
            Integer.class, sessionId, tenantId);
        return n != null ? n : 0;
    }
}
