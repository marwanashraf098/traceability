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
        " AND NOT EXISTS (SELECT 1 FROM warehouse_orders o2 WHERE o2.id = s.order_id AND o2.tenant_id = s.tenant_id " +
        "                 AND o2.status IN ('packed', 'awaiting_pickup', 'with_courier', 'delivered', " +
        "                                   'returning', 'returned', 'lost')))";

    /**
     * Non-Bosta couriers G3 — the ONE definition of "this order ships with another courier" for the returns
     * portal: its NEWEST forward leg (any state, delivered included) is generic. Such an order stays out of the
     * portal until G4 — never eligible for lookup / submission, never booked as a Bosta type 25 / 30.
     * {@code orderIdExpr}: SQL for the order id (a column or a bind '?'). Bosta-only orders: always false.
     */
    public static String newestForwardIsGenericSql(String orderIdExpr) {
        return "COALESCE((SELECT s_g.provider = 'generic' FROM shipments s_g " +
               "          WHERE s_g.order_id = " + orderIdExpr + " AND s_g.shipment_leg = 'forward' " +
               "          ORDER BY s_g.created_at DESC, s_g.id DESC LIMIT 1), false)";
    }

    /** Is the tenant switched on? (Default false; Settings → Couriers, owner only, since G2.) */
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
     *   <li>the order is a post-connect order (in warehouse_orders, placed at/after its store's
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
        // V200: the live leg follows Shopify's edited tracking number while the parcel is still in custody.
        followTrackingEdit(jdbc, tenantId, orderId, fulfillments, classOf, trackingNumbersOf);

        Map<String, Object> order = jdbc.queryForList(
            "SELECT o.id FROM warehouse_orders o LEFT JOIN stores st ON st.id = o.store_id " +
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
                // a conflict (replay, or the key is another order's) → null: never guess further
                UUID created = insertFromFulfillment(jdbc, tenantId, orderId, tn, key, text(f, "id"),
                    trimToNull(text(f, "tracking_company")), firstUrl(f));
                if (created != null) recordReplacedLeg(jdbc, tenantId, orderId, created, tn, text(f, "id"), text(f, "updated_at"));
                return created;
            }
        }
        return null;
    }

    /**
     * The INSERT of a generic shipment from a Shopify fulfillment — shared by the live capture
     * ({@link #onFulfillments}) and the G2 switch-on catch-up ({@link GenericCatchUp}). Idempotent:
     * ON CONFLICT DO NOTHING (tenant + fulfillment id, tenant + key, V104's one active forward leg).
     * @return the new shipment's id, or null when nothing was inserted
     */
    static UUID insertFromFulfillment(JdbcTemplate jdbc, UUID tenantId, UUID orderId, String trackingNumber,
                                      String key, String fulfillmentId, String carrierName, String trackingUrl) {
        UUID id = UUID.randomUUID();
        int n = jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, tracking_key, " +
            "    internal_state, source, shopify_fulfillment_id, carrier_name, tracking_url) " +
            "VALUES (?, ?, ?, 'generic', ?, ?, 'created', 'shopify_fulfillment', ?, ?, ?) " +
            "ON CONFLICT DO NOTHING",
            id, tenantId, orderId, trackingNumber, key, fulfillmentId, carrierName, trackingUrl);
        if (n == 1) {
            log.info("Generic shipment: order {} — carrier '{}' (fulfillment {})", orderId, carrierName, fulfillmentId);
            return id;
        }
        return null;
    }

    /**
     * A cancelled Shopify fulfillment cancels its generic shipment only while {@link #CANCELLABLE_SQL}
     * holds — through {@link #cancel}, the same state write (no history row) as any other cancellation.
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

    // ── Shopify tracking-number edits (V200, S2) ─────────────────────────────────────────────────

    /**
     * The order's live generic forward leg L (from a Shopify fulfillment F, still 'created' — anything past that is
     * frozen anyway) follows Shopify when the merchant changed its number. No write at all for a fulfillment whose
     * numbers didn't change. The shapes (TrackingChanges kinds):
     * <ul>
     *   <li><b>edit</b> — F still live, L's number gone from it (the capture marked it removed, so the payload is not
     *       stale), exactly one new number of the same carrier type → L takes it in place;</li>
     *   <li><b>cancel_refulfill</b> — F cancelled while L could no longer be cancelled (something packed), exactly one
     *       new live fulfillment G of a generic carrier with exactly one number, L's number on no live fulfillment →
     *       L takes G's number and fulfillment id in place. (Nothing packed: the cancelled-fulfillment rule already
     *       cancelled L and the new leg comes from G, as before.)</li>
     *   <li><b>cosmetic</b> — the same number written differently (same GenericTrackingKey) → the text only, quietly;</li>
     *   <li><b>blanked</b> — F live with no number → recorded only (D5);</li>
     *   <li>anything looser (two new numbers, or more than one new live fulfillment) → refused 'ambiguous', alert.</li>
     * </ul>
     * Under the order row lock: frozen (OrderLineFreeze) → refused HIGH; in an open pickup session → refused HIGH;
     * the new key (or G) already another shipment's → refused 'key_taken'; else applied, with the relabel reasons.
     * L's number still on another live fulfillment, or a carrier change to Bosta (the Bosta-wins path), → nothing.
     */
    static void followTrackingEdit(JdbcTemplate jdbc, UUID tenantId, UUID orderId, JsonNode fulfillments,
                                   java.util.function.BiFunction<String, String, String> classOf,
                                   Function<JsonNode, List<String>> trackingNumbersOf) {
        Map<String, Object> leg = jdbc.queryForList(
            "SELECT s.id, s.tracking_number, s.tracking_key, s.shopify_fulfillment_id FROM shipments s " +
            "JOIN merchant_orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id " +
            "WHERE s.order_id = ? AND s.tenant_id = ? AND s.provider = 'generic' AND s.shipment_leg = 'forward' " +
            "  AND s.source = 'shopify_fulfillment' AND s.internal_state NOT IN ('terminated', 'cancelled') " +
            "  AND s.shopify_fulfillment_id IS NOT NULL AND o.external_id NOT LIKE 'internal:%'",
            orderId, tenantId).stream().findFirst().orElse(null);
        if (leg == null) return;
        Leg l = new Leg((UUID) leg.get("id"), (String) leg.get("tracking_number"), (String) leg.get("tracking_key"),
            (String) leg.get("shopify_fulfillment_id"));
        JsonNode f = null;
        for (JsonNode x : fulfillments) if (l.fulfillmentId().equals(text(x, "id"))) f = x;
        if (f == null) return;

        if (!isCancelled(f)) {
            List<String> numbers = trackingNumbersOf.apply(f);
            if (numbers.contains(l.trackingNumber())) return;                     // unchanged, or a number added
            if (liveElsewhere(fulfillments, l, trackingNumbersOf)) return;        // its number moved to another parcel
            if (!oldNumberRemoved(jdbc, tenantId, orderId, l.trackingNumber())) return;   // stale payload
            String u = text(f, "updated_at");
            for (String n : numbers) {
                if (l.key().equals(GenericTrackingKey.of(n))) {
                    decide(jdbc, tenantId, orderId, l, TrackingChanges.KIND_COSMETIC, n, f);
                    return;
                }
            }
            if (numbers.isEmpty()) {
                TrackingChanges.record(jdbc, tenantId, orderId, l.id(), null, TrackingChanges.KIND_BLANKED,
                    l.trackingNumber(), null, l.fulfillmentId(), null, u, "recorded", null, List.of());
                return;
            }
            if (!genericClass(jdbc, tenantId, f, classOf)) return;                // now Bosta: the Bosta-wins path
            List<String> keyed = numbers.stream().filter(n -> GenericTrackingKey.of(n) != null).toList();
            if (keyed.size() == 1) {
                decide(jdbc, tenantId, orderId, l, TrackingChanges.KIND_EDIT, keyed.get(0), f);
            } else if (keyed.size() > 1) {
                TrackingChanges.record(jdbc, tenantId, orderId, l.id(), null, TrackingChanges.KIND_EDIT,
                    l.trackingNumber(), String.join(", ", keyed), l.fulfillmentId(), l.fulfillmentId(), u,
                    "refused", TrackingChanges.REASON_AMBIGUOUS, List.of());
            }
            return;
        }

        // F cancelled and L still live: L was no longer cancellable (something packed). The stored row must say so
        // too (the capture never lets an older payload rewrite it), so a stale payload never moves L.
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM order_fulfillment_tracking WHERE tenant_id = ? AND order_id = ? " +
                "  AND tracking_number = ? AND lower(coalesce(fulfillment_status, '')) = 'cancelled')",
                Boolean.class, tenantId, orderId, l.trackingNumber()))) return;
        if (liveElsewhere(fulfillments, l, trackingNumbersOf)) return;
        List<JsonNode> others = new ArrayList<>();
        for (JsonNode x : fulfillments) {
            if (!isCancelled(x) && text(x, "id") != null && !trackingNumbersOf.apply(x).isEmpty()) others.add(x);
        }
        if (others.isEmpty()) return;   // waiting for the refulfill (generic_fulfillment_cancelled says so)
        if (others.size() == 1) {
            JsonNode g = others.get(0);
            if (!genericClass(jdbc, tenantId, g, classOf)) return;                // refulfilled with Bosta
            List<String> keyed = trackingNumbersOf.apply(g).stream().filter(n -> GenericTrackingKey.of(n) != null).toList();
            if (keyed.size() == 1) {
                decide(jdbc, tenantId, orderId, l, TrackingChanges.KIND_CANCEL_REFULFILL, keyed.get(0), g);
                return;
            }
        }
        List<String> all = new ArrayList<>();
        for (JsonNode x : others) all.addAll(trackingNumbersOf.apply(x));
        TrackingChanges.record(jdbc, tenantId, orderId, l.id(), null, TrackingChanges.KIND_CANCEL_REFULFILL,
            l.trackingNumber(), String.join(", ", all), l.fulfillmentId(), null, null,
            "refused", TrackingChanges.REASON_AMBIGUOUS, List.of());
    }

    private record Leg(UUID id, String trackingNumber, String key, String fulfillmentId) {}

    /**
     * Unpacked cancel-and-refulfill (V200): the cancelled-fulfillment rule cancelled the old leg and this call (or a
     * later one) created the new leg from the new fulfillment. Recorded as a cancel_refulfill decision on the old leg
     * pointing at the new one (new_shipment_id), with the relabel reasons the old waybill still carries (printed /
     * claimed — nothing is packed, or the old leg couldn't have been cancelled). The old leg = the order's newest
     * generic forward leg from a Shopify fulfillment that is cancelled and whose fulfillment Shopify cancelled
     * (never a Bosta-wins cancel, whose fulfillment is live). Once per old leg. Nothing else changes.
     */
    private static void recordReplacedLeg(JdbcTemplate jdbc, UUID tenantId, UUID orderId, UUID newLeg, String newTn,
                                          String newFid, String updatedAt) {
        List<Map<String, Object>> old = jdbc.queryForList(
            "SELECT s.id, s.tracking_number, s.shopify_fulfillment_id FROM shipments s " +
            "WHERE s.order_id = ? AND s.tenant_id = ? AND s.id <> ? AND s.provider = 'generic' AND s.shipment_leg = 'forward' " +
            "  AND s.source = 'shopify_fulfillment' AND s.internal_state = 'cancelled' AND s.provider_deleted_at IS NULL " +
            "  AND EXISTS (SELECT 1 FROM order_fulfillment_tracking t WHERE t.tenant_id = s.tenant_id AND t.order_id = s.order_id " +
            "              AND t.shopify_fulfillment_id = s.shopify_fulfillment_id " +
            "              AND lower(coalesce(t.fulfillment_status, '')) = 'cancelled') " +
            "  AND NOT EXISTS (SELECT 1 FROM shipment_tracking_changes c WHERE c.shipment_id = s.id AND c.new_shipment_id IS NOT NULL) " +
            "ORDER BY s.created_at DESC, s.id DESC LIMIT 1",
            orderId, tenantId, newLeg);
        if (old.isEmpty()) return;
        UUID oldId = (UUID) old.get(0).get("id");
        String oldTn = (String) old.get(0).get("tracking_number");
        List<String> relabel = TrackingChanges.relabelReasons(jdbc, tenantId, orderId, oldId, oldTn);
        TrackingChanges.record(jdbc, tenantId, orderId, oldId, newLeg, TrackingChanges.KIND_CANCEL_REFULFILL, oldTn, newTn,
            (String) old.get(0).get("shopify_fulfillment_id"), newFid, updatedAt, "applied", null, relabel);
    }

    /** Freeze / pickup / key checks under the order row lock, then the in-place write. */
    private static void decide(JdbcTemplate jdbc, UUID tenantId, UUID orderId, Leg l, String kind, String newTn,
                               JsonNode to) {
        String newKey = GenericTrackingKey.of(newTn);
        String newFid = text(to, "id");
        String u = text(to, "updated_at");
        boolean frozen = TrackingChanges.lockAndCheckFrozen(jdbc, tenantId, orderId);
        List<Map<String, Object>> now = jdbc.queryForList(
            "SELECT internal_state::text AS state, tracking_number FROM shipments WHERE id = ? AND tenant_id = ? FOR UPDATE",
            l.id(), tenantId);
        if (now.isEmpty() || !l.trackingNumber().equals(now.get(0).get("tracking_number"))) return;   // changed meanwhile
        boolean cosmetic = TrackingChanges.KIND_COSMETIC.equals(kind);
        // Frozen first: a leg that left custody (whatever its live state says) is refused and raises the HIGH alert.
        if (frozen) {
            if (!cosmetic) record(jdbc, tenantId, orderId, l, kind, newTn, newFid, u, "refused", TrackingChanges.REASON_FROZEN, List.of());
            return;
        }
        if (!"created".equals(now.get(0).get("state"))) return;
        if (!cosmetic && TrackingChanges.inOpenPickupSession(jdbc, tenantId, l.id())) {
            record(jdbc, tenantId, orderId, l, kind, newTn, newFid, u, "refused", TrackingChanges.REASON_PICKUP_SESSION, List.of());
            return;
        }
        if (!cosmetic && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM shipments WHERE tenant_id = ? AND provider = 'generic' AND id <> ? " +
                "  AND (tracking_key = ? OR shopify_fulfillment_id = ?))",
                Boolean.class, tenantId, l.id(), newKey, newFid))) {
            record(jdbc, tenantId, orderId, l, kind, newTn, newFid, u, "refused", TrackingChanges.REASON_KEY_TAKEN, List.of());
            return;
        }
        List<String> relabel = cosmetic ? List.of()
            : TrackingChanges.relabelReasons(jdbc, tenantId, orderId, l.id(), l.trackingNumber());
        int n = jdbc.update(
            "UPDATE shipments SET tracking_number = ?, tracking_key = ?, shopify_fulfillment_id = ?, " +
            "    carrier_name = coalesce(?, carrier_name), tracking_url = coalesce(?, tracking_url), " +
            "    tracking_changed_at = now(), last_synced_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND provider = 'generic' AND internal_state = 'created' AND tracking_number = ?",
            newTn, newKey, newFid, trimToNull(text(to, "tracking_company")), firstUrl(to),
            l.id(), tenantId, l.trackingNumber());
        if (n != 1) return;
        record(jdbc, tenantId, orderId, l, kind, newTn, newFid, u, "applied", null, relabel);
        log.info("Generic shipment {} (order {}): tracking changed in Shopify ({}), relabel {}", l.id(), orderId, kind, relabel);
    }

    private static void record(JdbcTemplate jdbc, UUID tenantId, UUID orderId, Leg l, String kind, String newTn,
                               String newFid, String u, String outcome, String reason, List<String> relabel) {
        TrackingChanges.record(jdbc, tenantId, orderId, l.id(), null, kind, l.trackingNumber(), newTn,
            l.fulfillmentId(), newFid, u, outcome, reason, relabel);
    }

    /** L's number (by key) is on another live fulfillment of the payload. */
    private static boolean liveElsewhere(JsonNode fulfillments, Leg l, Function<JsonNode, List<String>> trackingNumbersOf) {
        for (JsonNode x : fulfillments) {
            if (isCancelled(x) || l.fulfillmentId().equals(text(x, "id"))) continue;
            for (String n : trackingNumbersOf.apply(x)) if (l.key().equals(GenericTrackingKey.of(n))) return true;
        }
        return false;
    }

    /**
     * The capture (FulfillmentTrackingCapture.markRemoved) marked L's number removed from its fulfillment — only a
     * payload at least as new as the one that wrote it does that, so an older payload never moves L.
     */
    private static boolean oldNumberRemoved(JdbcTemplate jdbc, UUID tenantId, UUID orderId, String tn) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM order_fulfillment_tracking WHERE tenant_id = ? AND order_id = ? " +
            "  AND tracking_number = ? AND removed_at IS NOT NULL)", Boolean.class, tenantId, orderId, tn));
    }

    /** G1's class rule: a named non-Bosta carrier, or an unknown one while the tenant has no active Bosta account. */
    private static boolean genericClass(JdbcTemplate jdbc, UUID tenantId, JsonNode f,
                                        java.util.function.BiFunction<String, String, String> classOf) {
        String cls = classOf.apply(text(f, "tracking_company"), firstUrl(f));
        if ("other_known".equals(cls)) return true;
        if (!"unknown".equals(cls)) return false;
        return !Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active')",
            Boolean.class, tenantId));
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
     * The one locked read of {@link #yieldToBosta} (binds tenant, order). Scoped to ONE order: it reaches
     * the order's shipments through an order_id index (shipments_order_idx / the V104 partial
     * ux_active_forward_shipment_per_order) and locks only rows it returns — that order's live generic
     * forward legs. Bosta rows fail {@code provider = 'generic'} before the LockRows step, so they are never
     * locked or waited on; an order with no generic leg locks nothing. GenericYieldLockTest proves both.
     */
    public static final String YIELD_SQL =
        "SELECT s.id, " + CANCELLABLE_SQL + " AS cancellable FROM shipments s " +
        "WHERE s.tenant_id = ? AND s.order_id = ? AND s.provider = 'generic' " +
        "  AND s.shipment_leg = 'forward' AND s.internal_state NOT IN ('terminated', 'cancelled') " +
        "FOR UPDATE OF s";

    /**
     * Called by the Bosta link paths right before they would create the order's Bosta forward
     * shipment. One locked read; writes only when a generic leg exists and is still cancellable.
     */
    public static BostaLink yieldToBosta(JdbcTemplate jdbc, UUID tenantId, UUID orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList(YIELD_SQL, tenantId, orderId);
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

    /**
     * The generic leg's state write: 'cancelled' and NO shipment_status_history row — the BostaDeletionService
     * pattern. Traced cancels only a leg still in 'created' (Bosta wins, or Shopify cancelled the fulfillment);
     * a history row past 'created' reads as "this order shipped" to OrderLineFreeze, FulfillService's
     * ALREADY_SHIPPED guard and WaybillResolver's moved state, which would then refuse to pack the order against
     * its next waybill (GenericCancelPackTest). The leg's own internal_state records the cancel.
     */
    static void cancel(JdbcTemplate jdbc, UUID tenantId, UUID shipmentId) {
        jdbc.update(
            "UPDATE shipments SET internal_state = 'cancelled', last_synced_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND provider = 'generic' AND internal_state = 'created'",
            shipmentId, tenantId);
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
