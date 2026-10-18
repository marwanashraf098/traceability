package com.traceability.courier;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Non-Bosta couriers, G1 (V169) — the ONE writer of provider = 'generic' shipments.
 *
 * A generic shipment is the order's forward leg when the merchant ships it with a carrier Traced has
 * no API for. G1 creates it from the order's Shopify fulfillment and never moves it past 'created'
 * except to 'cancelled' — no scan, no link, no 'delivered' writer (G2).
 *
 * Everything here is a no-op unless {@code tenants.generic_courier_enabled} is true (default false
 * for every tenant). The Bosta-wins check ({@link #yieldToBosta}) is the only entry Bosta code calls;
 * for a tenant with no generic row it reads one row and returns {@link BostaLink#NO_GENERIC}.
 *
 * Not a bean: every caller passes its own JdbcTemplate so this runs on the caller's connection,
 * tenant context and transaction (like OrderCarrier / ReturnRequestLifecycle).
 */
public final class GenericShipments {

    private static final Logger log = LoggerFactory.getLogger(GenericShipments.class);

    private GenericShipments() {}

    /**
     * SQL (alias {@code s} = a generic forward shipment): it may still be cancelled — Bosta may replace
     * it, a cancelled Shopify fulfillment may cancel it. True only while it is 'created' AND nothing of
     * the order is packed (no 'packed' allocation, order not packed / awaiting pickup or later).
     * Anything else is "past created" → never cancelled automatically, an exception instead.
     */
    public static final String CANCELLABLE_SQL =
        "(s.internal_state = 'created' " +
        " AND NOT EXISTS (SELECT 1 FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
        "                 WHERE oi.order_id = s.order_id AND a.tenant_id = s.tenant_id AND a.status = 'packed') " +
        " AND NOT EXISTS (SELECT 1 FROM merchant_orders o2 WHERE o2.id = s.order_id AND o2.tenant_id = s.tenant_id " +
        "                 AND o2.status IN ('packed', 'awaiting_pickup', 'with_courier', 'delivered', " +
        "                                   'returning', 'returned', 'lost')))";

    /** Is the tenant switched on? (Default false; set only by SQL in G1.) */
    public static boolean enabled(JdbcTemplate jdbc, UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT generic_courier_enabled FROM tenants WHERE id = ?", Boolean.class, tenantId));
    }

    // ── Shopify fulfillments → generic shipment ──────────────────────────────────────────────────

    /**
     * Called by FulfillmentTrackingCapture.capture after the tracking rows are upserted, only when
     * {@link #enabled} is true. Cancels first (a cancelled fulfillment's 'created' generic leg), then
     * creates at most one generic shipment for the order, when ALL hold:
     * <ul>
     *   <li>the order is a post-connect merchant order (in merchant_orders, placed at/after its store's
     *       orders_ingest_from — a NULL cutoff, Jumi's, never filters);</li>
     *   <li>the order has no active forward shipment (V104 rule);</li>
     *   <li>the fulfillment isn't cancelled, its class is 'other_known', or 'unknown' while the tenant
     *       has no active Bosta account — never 'bosta';</li>
     *   <li>it carries a tracking number with a non-empty {@link GenericTrackingKey}.</li>
     * </ul>
     * The earliest such fulfillment wins. Idempotent on (tenant, shopify_fulfillment_id): a replay
     * finds the row already there (ON CONFLICT DO NOTHING). Further parcels are recorded nowhere new —
     * the "multiple parcels" exception is derived from order_fulfillment_tracking.
     *
     * @param classOf the capture's own carrier classification (company, url) → 'bosta' | 'other_known' | 'unknown'
     * @return the id of a shipment created now, or null
     */
    public static UUID onFulfillments(JdbcTemplate jdbc, UUID tenantId, UUID orderId, JsonNode payload,
                                      java.util.function.BiFunction<String, String, String> classOf,
                                      Function<JsonNode, List<String>> trackingNumbersOf) {
        JsonNode fulfillments = payload.path("fulfillments");
        if (!fulfillments.isArray() || fulfillments.isEmpty()) return null;

        cancelForCancelledFulfillments(jdbc, tenantId, orderId, fulfillments);

        Map<String, Object> order = jdbc.queryForList(
            "SELECT o.id FROM merchant_orders o LEFT JOIN stores st ON st.id = o.store_id " +
            "WHERE o.id = ? AND o.tenant_id = ? " +
            "  AND (st.orders_ingest_from IS NULL OR o.placed_at IS NULL OR o.placed_at >= st.orders_ingest_from)",
            orderId, tenantId).stream().findFirst().orElse(null);
        if (order == null) return null;   // not a post-connect merchant order

        Boolean hasForward = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM shipments WHERE order_id = ? AND tenant_id = ? " +
            "  AND shipment_leg = 'forward' AND internal_state NOT IN ('terminated', 'cancelled'))",
            Boolean.class, orderId, tenantId);
        if (Boolean.TRUE.equals(hasForward)) return null;   // V104: one active forward leg per order

        boolean tenantHasBosta = Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active')",
            Boolean.class, tenantId));

        List<JsonNode> eligible = new ArrayList<>();
        for (JsonNode f : fulfillments) {
            if (isCancelled(f) || text(f, "id") == null) continue;
            String cls = classOf.apply(text(f, "tracking_company"), firstUrl(f));
            boolean ok = "other_known".equals(cls) || ("unknown".equals(cls) && !tenantHasBosta);
            if (ok) eligible.add(f);
        }
        eligible.sort(Comparator.comparing((JsonNode f) -> text(f, "created_at"),
            Comparator.nullsLast(Comparator.naturalOrder())));

        for (JsonNode f : eligible) {
            for (String tn : trackingNumbersOf.apply(f)) {
                String key = GenericTrackingKey.of(tn);
                if (key == null) continue;
                UUID id = UUID.randomUUID();
                int n = jdbc.update(
                    "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, tracking_key, " +
                    "    internal_state, source, shopify_fulfillment_id, carrier_name, tracking_url) " +
                    "VALUES (?, ?, ?, 'generic', ?, ?, 'created', 'shopify_fulfillment', ?, ?, ?) " +
                    "ON CONFLICT DO NOTHING",
                    id, tenantId, orderId, tn, key, text(f, "id"),
                    trimToNull(text(f, "tracking_company")), firstUrl(f));
                if (n == 1) {
                    log.info("Generic shipment: order {} — carrier '{}' (fulfillment {})",
                        orderId, text(f, "tracking_company"), text(f, "id"));
                    return id;
                }
                return null;   // a conflict (replay, or the key is another order's): never guess further
            }
        }
        return null;
    }

    /**
     * A cancelled Shopify fulfillment cancels its generic shipment only while {@link #CANCELLABLE_SQL}
     * holds — through {@link #cancel}, the same state write + history row as any other cancellation.
     * Past 'created', nothing is written: the generic_fulfillment_cancelled exception derives it.
     */
    private static void cancelForCancelledFulfillments(JdbcTemplate jdbc, UUID tenantId, UUID orderId,
                                                       JsonNode fulfillments) {
        List<String> cancelledIds = new ArrayList<>();
        List<String> liveIds = new ArrayList<>();
        for (JsonNode f : fulfillments) {
            String id = text(f, "id");
            if (id == null) continue;
            (isCancelled(f) ? cancelledIds : liveIds).add(id);
        }
        cancelledIds.removeAll(liveIds);
        for (String fid : cancelledIds) {
            List<UUID> ids = jdbc.queryForList(
                "SELECT s.id FROM shipments s " +
                "WHERE s.tenant_id = ? AND s.order_id = ? AND s.provider = 'generic' " +
                "  AND s.shopify_fulfillment_id = ? AND s.shipment_leg = 'forward' " +
                "  AND s.internal_state NOT IN ('terminated', 'cancelled') AND " + CANCELLABLE_SQL +
                " FOR UPDATE OF s",
                UUID.class, tenantId, orderId, fid);
            for (UUID id : ids) cancel(jdbc, tenantId, id);
        }
    }

    // ── Bosta wins ───────────────────────────────────────────────────────────────────────────────

    public enum BostaLink {
        /** No active generic forward leg on the order — the Bosta path continues exactly as before. */
        NO_GENERIC,
        /** The order's generic leg was still cancellable and is now cancelled — link Bosta. */
        GENERIC_CANCELLED,
        /** The order's generic leg is past 'created' — do NOT link; never guess. */
        BLOCKED
    }

    /**
     * Called by the Bosta link paths right before they would create the order's Bosta forward
     * shipment. One locked read; writes only when a generic leg exists and is still cancellable.
     */
    public static BostaLink yieldToBosta(JdbcTemplate jdbc, UUID tenantId, UUID orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT s.id, " + CANCELLABLE_SQL + " AS cancellable FROM shipments s " +
            "WHERE s.tenant_id = ? AND s.order_id = ? AND s.provider = 'generic' " +
            "  AND s.shipment_leg = 'forward' AND s.internal_state NOT IN ('terminated', 'cancelled') " +
            "FOR UPDATE OF s",
            tenantId, orderId);
        if (rows.isEmpty()) return BostaLink.NO_GENERIC;
        for (Map<String, Object> r : rows) {
            if (!Boolean.TRUE.equals(r.get("cancellable"))) {
                log.warn("Bosta delivery for order {} not linked: its generic shipment {} is past 'created'",
                    orderId, r.get("id"));
                return BostaLink.BLOCKED;
            }
        }
        for (Map<String, Object> r : rows) cancel(jdbc, tenantId, (UUID) r.get("id"));
        log.info("Bosta wins: order {} — generic shipment cancelled before linking Bosta", orderId);
        return BostaLink.GENERIC_CANCELLED;
    }

    /**
     * SQL predicate (alias {@code s} = shipments): a live generic forward leg that is past 'created' — the
     * case where Bosta must NOT be linked (BLOCKED in {@link #yieldToBosta}).
     */
    public static final String BLOCKING_GENERIC_SQL =
        "(s.provider = 'generic' AND s.shipment_leg = 'forward' " +
        " AND s.internal_state NOT IN ('terminated', 'cancelled') AND NOT " + CANCELLABLE_SQL + ")";

    /** The generic leg's state write: 'cancelled' + one shipment_status_history row (only if it moved). */
    static void cancel(JdbcTemplate jdbc, UUID tenantId, UUID shipmentId) {
        int n = jdbc.update(
            "UPDATE shipments SET internal_state = 'cancelled', last_synced_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND provider = 'generic' AND internal_state = 'created'",
            shipmentId, tenantId);
        if (n == 1) {
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) " +
                "VALUES (?, ?, 'cancelled')", tenantId, shipmentId);
        }
    }

    // ── JSON helpers (same reading as FulfillmentTrackingCapture) ────────────────────────────────

    private static boolean isCancelled(JsonNode f) {
        return "cancelled".equalsIgnoreCase(text(f, "status"));
    }

    private static String firstUrl(JsonNode f) {
        String u = text(f, "tracking_url");
        if (u != null) return u;
        for (JsonNode n : f.path("tracking_urls")) {
            String v = n.asText(null);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText();
        return s.isBlank() ? null : s;
    }
}
