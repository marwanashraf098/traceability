package com.traceability.integrations.bosta;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shopify tracking-number edits, S3 (V202) — a Bosta forward leg REPLACED by the order's new Bosta delivery.
 * Not a bean: every caller passes its own JdbcTemplate (caller's connection, tenant context, transaction).
 *
 * <ul>
 *   <li>{@link #replace} — the one writer of the replacement: inside the link transaction, the old leg becomes
 *       'cancelled' with NO shipment_status_history row (no freeze, no ALREADY_SHIPPED), superseded_at /
 *       superseded_reason set; superseded_by_shipment_id once the new leg exists ({@link #pointAt}). Traced never
 *       writes to Bosta (Mode B): the old delivery may stay live there.</li>
 *   <li>{@link #fence} — every later event for the OLD delivery (webhook, status poll, discovery, safety net)
 *       stores raw / provider_state / superseded_last_state (+ superseded_moved_at on real movement) only: no history
 *       row, no piece move, no internal_state change, so no unique-index error and no order effect.</li>
 *   <li>{@link #watchedSql} — the ONE definition of "the status poll still watches this replaced leg".</li>
 * </ul>
 */
public final class BostaSupersede {

    public static final String REASON = "shopify_tracking_edit";
    /** A replaced leg is watched at most this long after the switch. */
    public static final int WATCH_DAYS = 14;

    /** States a fenced event maps to that are real movement of the old delivery (HIGH superseded_waybill_moving). */
    static final Set<String> MOVEMENT = Set.of("with_courier", "delivered", "returning", "returned", "lost", "exception");

    private BostaSupersede() {}

    /**
     * SQL (alias {@code a} = shipments): a replaced leg the status poll still watches — replaced less than
     * {@link #WATCH_DAYS} days ago, the old delivery not deleted or not found in Bosta, and its latest fenced state
     * not terminal. Polled at the lowest priority: walk items cost no call; the per-leg check runs after every
     * normal poll fetch of the cycle and only when the cycle wasn't rate limited (BostaStatusPollJob).
     */
    public static String watchedSql(String a) {
        return "(" + a + ".superseded_at IS NOT NULL AND " + a + ".superseded_at > now() - INTERVAL '" + WATCH_DAYS + " days' " +
               " AND " + a + ".provider_deleted_at IS NULL AND " + a + ".provider_not_found_at IS NULL " +
               " AND coalesce(" + a + ".superseded_last_state, '') NOT IN ('delivered', 'returned', 'lost', 'terminated', 'cancelled'))";
    }

    /** The shipment was replaced (superseded_at set). */
    public static boolean isReplaced(JdbcTemplate jdbc, UUID shipmentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM shipments WHERE id = ? AND superseded_at IS NOT NULL)", Boolean.class, shipmentId));
    }

    /**
     * The replacement, on the caller's transaction (the link transaction). Only a live, Bosta, forward leg of the
     * order moves; returns false (nothing written) for anything else.
     */
    public static boolean replace(JdbcTemplate jdbc, UUID tenantId, UUID orderId, UUID oldLegId) {
        return jdbc.update(
            "UPDATE shipments SET internal_state = 'cancelled', superseded_at = now(), superseded_reason = ?, " +
            "    last_synced_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND order_id = ? AND provider = 'bosta' AND shipment_leg = 'forward' " +
            "  AND internal_state NOT IN ('terminated', 'cancelled') AND superseded_at IS NULL",
            REASON, oldLegId, tenantId, orderId) == 1;
    }

    /** The replaced leg points at the leg that replaced it. */
    public static void pointAt(JdbcTemplate jdbc, UUID tenantId, UUID oldLegId, UUID newLegId) {
        jdbc.update("UPDATE shipments SET superseded_by_shipment_id = ? WHERE id = ? AND tenant_id = ? AND superseded_at IS NOT NULL",
            newLegId, oldLegId, tenantId);
    }

    /** The fence (class javadoc). {@code mappedState} = the internal state Bosta's event maps to. */
    public static void fence(JdbcTemplate jdbc, UUID shipmentId, BostaDelivery delivery, String mappedState) {
        boolean moved = mappedState != null && MOVEMENT.contains(mappedState);
        jdbc.update(
            "UPDATE shipments SET raw = ?::jsonb, provider_state = ?, last_synced_at = now(), superseded_last_state = ?, " +
            "    superseded_moved_at = CASE WHEN ? THEN coalesce(superseded_moved_at, now()) ELSE superseded_moved_at END " +
            "WHERE id = ? AND superseded_at IS NOT NULL",
            delivery.raw() != null ? delivery.raw().toString() : null, delivery.stateCode(), mappedState, moved, shipmentId);
        // The old delivery's wallet is real money even after the switch (COD collected under the old waybill).
        ShipmentSettlement.apply(jdbc, shipmentId, delivery.raw());
    }

    /** link_reason of a row re-queued by {@link #requeueAfterLegEnded} — its next attempt is the one re-attempt. */
    public static final String REQUEUE_REASON_PREFIX = "requeued: ";

    /**
     * S4 (d, approved 2026-11-03) — the order's Bosta forward leg {@code endedTn} ended in Bosta (deletion confirmed,
     * or 48 / 49 / 104): its 'skipped' / 'gave_up' Bosta tracking rows that Shopify still shows (not removed, not
     * cancelled, not already a shipment) get ONE re-attempt through the normal link pipeline — every S3 gate applies
     * (freeze, pickup session, D2, identity), the retry sweeper picks them up. Never touches another order's rows,
     * so a second parcel whose order's leg is live is never re-queued. No Bosta call here.
     */
    public static int requeueAfterLegEnded(JdbcTemplate jdbc, UUID tenantId, UUID orderId, String endedTn) {
        return jdbc.update(
            "UPDATE order_fulfillment_tracking t SET link_status = 'retry', link_reason = ?, link_next_retry_at = now(), " +
            "    link_first_failed_at = NULL, link_checked_at = now() " +
            "WHERE t.tenant_id = ? AND t.order_id = ? AND t.carrier_class = 'bosta' AND t.link_status IN ('skipped', 'gave_up') " +
            "  AND t.removed_at IS NULL AND lower(coalesce(t.fulfillment_status, '')) <> 'cancelled' " +
            "  AND NOT EXISTS (SELECT 1 FROM shipments s WHERE s.tenant_id = t.tenant_id AND s.tracking_number = t.tracking_number " +
            "                    AND s.provider = 'bosta')",
            REQUEUE_REASON_PREFIX + "old waybill " + endedTn + " ended in Bosta", tenantId, orderId);
    }

    /** The order's live Bosta forward leg of a Shopify order (never an internal order), or null. */
    static Map<String, Object> liveLeg(JdbcTemplate jdbc, UUID tenantId, UUID orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT s.id, s.tracking_number, s.provider_deleted_at FROM shipments s " +
            "JOIN merchant_orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id " +
            "WHERE s.order_id = ? AND s.tenant_id = ? AND s.provider = 'bosta' AND s.shipment_leg = 'forward' " +
            "  AND s.internal_state NOT IN ('terminated', 'cancelled') AND o.external_id NOT LIKE 'internal:%' " +
            "ORDER BY s.created_at DESC, s.id DESC LIMIT 1",
            orderId, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
