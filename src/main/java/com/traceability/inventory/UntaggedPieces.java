package com.traceability.inventory;

import com.traceability.labels.UntaggedException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Flexible labeling slice 2 — an UNTAGGED piece is a real piece with no printed code yet
 * ({@code pieces.short_code IS NULL}, count-only receiving). v1 rule: it can't be reprinted,
 * transferred, held, damaged, voided or written off, and a stock take can't run over one, until a
 * label is bound to it. Every single-piece path asks here, on the caller's connection and tenant.
 * Its internal barcode (PC-&lt;ULID&gt;) is never printed, but it IS shown in Lookup and every scan
 * resolver also matches it — so a typed internal code must be refused the same way.
 */
public final class UntaggedPieces {

    private UntaggedPieces() {}

    /** The one predicate, for SQL built elsewhere ({@code p} = the pieces alias). */
    public static String untaggedSql(String alias) {
        return alias + ".short_code IS NULL";
    }

    /**
     * A variant's AVAILABLE pieces (every location), split by whether they carry a code:
     * {available, labeled, untagged} — available = labeled + untagged.
     */
    public static java.util.Map<String, Object> availableCounts(JdbcTemplate jdbc, UUID tenantId, UUID variantId) {
        return jdbc.queryForMap(
            "SELECT count(*)::int AS available, " +
            "       count(*) FILTER (WHERE short_code IS NOT NULL)::int AS labeled, " +
            "       count(*) FILTER (WHERE short_code IS NULL)::int AS untagged " +
            "FROM pieces WHERE tenant_id = ? AND variant_id = ? AND status = 'available'",
            tenantId, variantId);
    }

    public static boolean isUntagged(JdbcTemplate jdbc, UUID tenantId, String pieceId) {
        List<Boolean> r = jdbc.queryForList(
            "SELECT short_code IS NULL FROM pieces WHERE id = ? AND tenant_id = ?", Boolean.class, pieceId, tenantId);
        return !r.isEmpty() && Boolean.TRUE.equals(r.get(0));
    }

    /** 409 PIECE_UNTAGGED when the piece is untagged; a missing piece is left to the caller's own 404. */
    public static void refuse(JdbcTemplate jdbc, UUID tenantId, String pieceId) {
        if (isUntagged(jdbc, tenantId, pieceId)) throw UntaggedException.piece();
    }
}
