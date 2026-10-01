package com.traceability.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pick &amp; Pack S3 — the "someone is packing this order" claim, on the existing
 * orders.locked_by / locked_at columns (no new columns).
 *
 * Taken when a waybill opens an order in a waybill session, refreshed on every piece scan /
 * undo there, released on auto-complete, set aside and session end (the manager release
 * endpoint still clears it too). A claim with no activity for {@link #STALE_AFTER_MINUTES}
 * minutes is stale: anyone may take it, and it no longer blocks a scan.
 *
 * Not a bean: callers pass their own JdbcTemplate so the statements join their transaction.
 * The single place the claim rule lives — never re-derive it inline.
 */
public final class PackClaim {

    private PackClaim() {}

    /** Minutes without a scan after which a claim stops counting. */
    public static final int STALE_AFTER_MINUTES = 10;

    /** SQL predicate: the order's claim (alias {@code o}) is live and not the given user's. Binds one param (userId). */
    static final String HELD_BY_OTHER_SQL =
        "(o.locked_by IS NOT NULL AND o.locked_by <> ? " +
        // A claim without a timestamp counts as stale (never blocks forever).
        " AND COALESCE(o.locked_at, '-infinity'::timestamptz) > now() - interval '" + STALE_AFTER_MINUTES + " minutes')";

    /**
     * Atomically take the claim: only if the order is unclaimed, already mine, or the claim is
     * stale. Returns true when this user now holds it.
     */
    public static boolean take(JdbcTemplate jdbc, UUID orderId, UUID tenantId, UUID userId) {
        return jdbc.update(
            "UPDATE orders o SET locked_by = ?, locked_at = now() " +
            "WHERE o.id = ? AND o.tenant_id = ? AND NOT " + HELD_BY_OTHER_SQL,
            userId, orderId, tenantId, userId) == 1;
    }

    /** Activity on a claimed order: push its stale time out. No-op unless this user holds it. */
    public static void refresh(JdbcTemplate jdbc, UUID orderId, UUID tenantId, UUID userId) {
        jdbc.update("UPDATE orders SET locked_at = now() WHERE id = ? AND tenant_id = ? AND locked_by = ?",
            orderId, tenantId, userId);
    }

    /** Release this user's claim. No-op if someone else holds it now. */
    public static void release(JdbcTemplate jdbc, UUID orderId, UUID tenantId, UUID userId) {
        jdbc.update("UPDATE orders SET locked_by = NULL, locked_at = NULL " +
                    "WHERE id = ? AND tenant_id = ? AND locked_by = ?",
            orderId, tenantId, userId);
    }

    /** The other user holding a live claim on the order, or null. Name may be null if the user row is gone. */
    public static Holder heldByOther(JdbcTemplate jdbc, UUID orderId, UUID tenantId, UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT o.locked_by, u.name FROM orders o LEFT JOIN users u ON u.id = o.locked_by " +
            "WHERE o.id = ? AND o.tenant_id = ? AND " + HELD_BY_OTHER_SQL,
            orderId, tenantId, userId);
        if (rows.isEmpty()) return null;
        return new Holder((UUID) rows.get(0).get("locked_by"), (String) rows.get(0).get("name"));
    }

    public record Holder(UUID userId, String name) {}
}
