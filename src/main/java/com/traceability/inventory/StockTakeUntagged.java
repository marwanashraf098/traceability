package com.traceability.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Flexible labeling slice 7 — a stock take counts UNTAGGED pieces per variant. They can't be scanned, so
 * the person running the count types how many are on the shelf (stock_take_untagged_counts, V183).
 *
 * Expected = the session's snapshot pieces that were untagged at open AND are still untagged, available and
 * at the session's location now (a piece bound to a label while the session is open leaves the count, like
 * the labeled drift guard). Finalize writes off (expected − counted) of them, oldest first (created_at, id);
 * a surplus writes nothing — creating pieces from a stock take is not approved.
 *
 * This class is the ONLY place a stock take picks untagged pieces to write off. The labeled rules (scan,
 * Found, per-row write-off, damage correction, the not-scanned rule) never touch a row with
 * stock_take_expected.untagged_at_open. Not a bean: built on the caller's JdbcTemplate (caller's tenant).
 */
final class StockTakeUntagged {

    private StockTakeUntagged() {}

    /**
     * One variant's untagged count. Open session: {@code expected} is live, {@code writeOffs} the pieces finalize
     * would write off now. Finalized: {@code expected} is what finalize compared with, {@code writtenOff} what it did.
     */
    record Row(UUID variantId, String variantTitle, String sku, String productTitle,
               int expected, Integer counted, int writtenOff, List<String> writeOffs) {

        boolean missing()  { return counted == null && expected > 0; }
        int shortfall()    { return counted == null ? 0 : Math.max(0, expected - counted); }
        int extra()        { return counted == null ? 0 : Math.max(0, counted - expected); }
    }

    private static final String ROWS_SQL =
        "WITH snap AS ( " +
        "  SELECT se.variant_id, " +
        "         count(*) FILTER (WHERE " + UntaggedPieces.untaggedSql("p") + " AND p.status = 'available' " +
        "                            AND p.current_location_id = ?)::int AS expected " +
        "  FROM stock_take_expected se JOIN pieces p ON p.id = se.piece_id AND p.tenant_id = se.tenant_id " +
        "  WHERE se.session_id = ? AND se.tenant_id = ? AND se.untagged_at_open " +
        "  GROUP BY se.variant_id), " +
        "cnt AS (SELECT variant_id, counted, expected_at_finalize FROM stock_take_untagged_counts " +
        "        WHERE session_id = ? AND tenant_id = ?) " +
        "SELECT v.id AS variant_id, v.title AS variant_title, v.sku, pr.title AS product_title, " +
        "       COALESCE(snap.expected, 0) AS expected, cnt.counted, cnt.expected_at_finalize, " +
        "       (SELECT count(*)::int FROM piece_events pe JOIN pieces p2 ON p2.id = pe.piece_id AND p2.tenant_id = pe.tenant_id " +
        "        WHERE pe.tenant_id = ? AND p2.variant_id = v.id AND pe.to_status = 'lost'::piece_status " +
        "          AND pe.metadata->>'session_id' = ? AND pe.metadata->>'reason' = 'stock_take_missing' " +
        "          AND pe.metadata->>'untagged' = 'true') AS written_off " +
        "FROM snap FULL JOIN cnt ON cnt.variant_id = snap.variant_id " +
        "JOIN variants v ON v.id = COALESCE(snap.variant_id, cnt.variant_id) " +
        "JOIN products pr ON pr.id = v.product_id " +
        "ORDER BY pr.title, v.title, v.id";

    /** Untagged pieces of one variant finalize may write off, oldest first. */
    private static final String CANDIDATES_SQL =
        "SELECT p.id FROM stock_take_expected se JOIN pieces p ON p.id = se.piece_id AND p.tenant_id = se.tenant_id " +
        "WHERE se.session_id = ? AND se.tenant_id = ? AND se.untagged_at_open AND se.variant_id = ? " +
        "  AND " + UntaggedPieces.untaggedSql("p") + " AND p.status = 'available' AND p.current_location_id = ? " +
        "ORDER BY p.created_at, p.id LIMIT ?";

    static List<Row> rows(JdbcTemplate jdbc, UUID tenantId, UUID sessionId, UUID locationId, boolean open) {
        List<Row> out = new ArrayList<>();
        jdbc.query(ROWS_SQL, rs -> {
            UUID variantId = rs.getObject("variant_id", UUID.class);
            Integer counted = (Integer) rs.getObject("counted");
            Integer atFinalize = (Integer) rs.getObject("expected_at_finalize");
            int expected = open || atFinalize == null ? rs.getInt("expected") : atFinalize;
            int writtenOff = rs.getInt("written_off");
            if (expected == 0 && counted == null && writtenOff == 0) return;   // nothing left to count
            List<String> writeOffs = List.of();
            if (open && counted != null && counted < expected) {
                writeOffs = jdbc.queryForList(CANDIDATES_SQL, String.class,
                    sessionId, tenantId, variantId, locationId, expected - counted);
            }
            out.add(new Row(variantId, rs.getString("variant_title"), rs.getString("sku"), rs.getString("product_title"),
                expected, counted, writtenOff, writeOffs));
        }, locationId, sessionId, tenantId, sessionId, tenantId, tenantId, sessionId.toString());
        return out;
    }

    /** Variants in this session that have untagged pieces in the snapshot (the only ones a count may be typed for). */
    static boolean inSnapshot(JdbcTemplate jdbc, UUID tenantId, UUID sessionId, UUID variantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM stock_take_expected WHERE session_id = ? AND tenant_id = ? " +
            "  AND variant_id = ? AND untagged_at_open)", Boolean.class, sessionId, tenantId, variantId));
    }

    static java.util.Map<String, Object> toResponse(Row r, boolean open) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("variantId", r.variantId());
        m.put("variantTitle", r.variantTitle());
        m.put("sku", r.sku());
        m.put("productTitle", r.productTitle());
        m.put("expected", r.expected());
        m.put("counted", r.counted());
        m.put("writeOff", open ? r.writeOffs().size() : r.writtenOff());
        m.put("extra", r.extra());
        return m;
    }
}
