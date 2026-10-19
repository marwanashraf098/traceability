package com.traceability.courier;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.function.BiFunction;

/**
 * Non-Bosta couriers G2 — the one-time catch-up when a tenant switches "I ship with other couriers" ON.
 *
 * Orders that already carry another courier's tracking number in Shopify were captured into
 * order_fulfillment_tracking while the switch was off, but got no generic shipment. For each OPEN
 * post-connect order this creates the generic shipment the live capture would have created, with G1's
 * eligibility rules ({@link GenericShipments#onFulfillments}) applied to the stored rows:
 * <ul>
 *   <li>a merchant order (merchant_orders) placed at/after its store's orders_ingest_from (NULL never filters),
 *       status new / ready_to_pick, no active forward shipment (V104);</li>
 *   <li>a stored fulfillment row that isn't cancelled and has a Shopify fulfillment id;</li>
 *   <li>its carrier class — computed again now by the capture's own classifier, so a carrier named since the
 *       row was stored (QP Express, V171) counts — 'other_known', or 'unknown' while the tenant has no active
 *       Bosta account; never 'bosta';</li>
 *   <li>a non-empty {@link GenericTrackingKey}, not already another generic shipment's key in this tenant.</li>
 * </ul>
 * The earliest such row per order wins (first_seen_at, then id). Idempotent: the INSERT is
 * {@link GenericShipments#insertFromFulfillment} (ON CONFLICT DO NOTHING), and a second run finds every
 * order it handled already holding an active forward leg. {@link #dryRun} answers the same question
 * without writing — the count {@link #run} would create.
 *
 * Not a bean: the caller passes its JdbcTemplate (its connection, tenant context and transaction).
 */
public final class GenericCatchUp {

    private GenericCatchUp() {}

    public record Candidate(UUID orderId, String orderNumber, String trackingNumber, String key,
                            String fulfillmentId, String carrierName, String trackingUrl) {}

    /** What a run would do: the orders it would create a shipment for, per carrier as Shopify named it, and the
     *  orders skipped only because their key is already another generic shipment's. */
    public record DryRun(int wouldCreate, int keyInUse, Map<String, Integer> byCarrier, List<Candidate> candidates) {}

    /**
     * SQL (binds tenant): the stored fulfillment rows of this tenant's open post-connect orders with no active
     * forward shipment, earliest first per order. The carrier class and key are decided in Java (the capture's
     * classifier, GenericTrackingKey); scripts/generic_courier_catchup_dryrun.sql mirrors both for prod.
     */
    static final String ROWS_SQL =
        "SELECT o.id AS order_id, o.number, t.tracking_number, t.carrier_raw, t.tracking_url, " +
        "       t.shopify_fulfillment_id " +
        "FROM merchant_orders o " +
        "JOIN stores st ON st.id = o.store_id " +
        "JOIN order_fulfillment_tracking t ON t.order_id = o.id AND t.tenant_id = o.tenant_id " +
        "WHERE o.tenant_id = ? " +
        "  AND o.status IN ('new', 'ready_to_pick') " +
        "  AND (st.orders_ingest_from IS NULL OR o.placed_at IS NULL OR o.placed_at >= st.orders_ingest_from) " +
        "  AND NOT EXISTS (SELECT 1 FROM shipments s WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id " +
        "                    AND s.shipment_leg = 'forward' AND s.internal_state NOT IN ('terminated', 'cancelled')) " +
        "  AND lower(coalesce(t.fulfillment_status, '')) <> 'cancelled' " +
        "  AND t.shopify_fulfillment_id IS NOT NULL " +
        "ORDER BY o.id, t.first_seen_at, t.id";

    public static DryRun dryRun(JdbcTemplate jdbc, UUID tenantId, BiFunction<String, String, String> classOf) {
        boolean tenantHasBosta = Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active')",
            Boolean.class, tenantId));
        Set<String> keysInUse = new HashSet<>(jdbc.queryForList(
            "SELECT tracking_key FROM shipments WHERE tenant_id = ? AND provider = 'generic'", String.class, tenantId));

        Map<UUID, Candidate> firstPerOrder = new LinkedHashMap<>();
        Set<UUID> keyInUseOrders = new HashSet<>();
        for (Map<String, Object> r : jdbc.queryForList(ROWS_SQL, tenantId)) {
            UUID orderId = (UUID) r.get("order_id");
            if (firstPerOrder.containsKey(orderId)) continue;
            String company = trimToNull((String) r.get("carrier_raw"));
            String url = (String) r.get("tracking_url");
            String cls = classOf.apply(company, url);
            if (!("other_known".equals(cls) || ("unknown".equals(cls) && !tenantHasBosta))) continue;
            String tn = (String) r.get("tracking_number");
            String key = GenericTrackingKey.of(tn);
            if (key == null) continue;
            if (keysInUse.contains(key)) { keyInUseOrders.add(orderId); continue; }   // another order's — never guess
            keysInUse.add(key);
            firstPerOrder.put(orderId, new Candidate(orderId, (String) r.get("number"), tn, key,
                (String) r.get("shopify_fulfillment_id"), company, url));
        }
        Map<String, Integer> byCarrier = new TreeMap<>();
        for (Candidate c : firstPerOrder.values()) {
            byCarrier.merge(c.carrierName() == null ? "(no carrier name)" : c.carrierName(), 1, Integer::sum);
        }
        keyInUseOrders.removeAll(firstPerOrder.keySet());
        return new DryRun(firstPerOrder.size(), keyInUseOrders.size(), byCarrier, List.copyOf(firstPerOrder.values()));
    }

    /** Creates the shipments {@link #dryRun} lists; returns how many were created now. */
    public static int run(JdbcTemplate jdbc, UUID tenantId, BiFunction<String, String, String> classOf) {
        int created = 0;
        for (Candidate c : dryRun(jdbc, tenantId, classOf).candidates()) {
            if (GenericShipments.insertFromFulfillment(jdbc, tenantId, c.orderId(), c.trackingNumber(), c.key(),
                    c.fulfillmentId(), c.carrierName(), c.trackingUrl()) != null) {
                created++;
            }
        }
        return created;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
