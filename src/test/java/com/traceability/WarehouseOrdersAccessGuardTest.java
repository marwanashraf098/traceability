package com.traceability;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shopify-native returns S3a (V216) — Guard 1 for the warehouse side. {@code warehouse_orders} shows every order
 * except portal pre-connect rows, so it also shows Shopify-native exchange SHADOW orders ('internal:shopify-exchange:'),
 * which are warehouse jobs and never sales (decision C1). Naming it in SQL ({@code FROM | JOIN | UPDATE | INTO
 * warehouse_orders}) is allowed only in the members listed here, each with why a shadow order belongs there. Anything
 * else stays on {@code merchant_orders} (the default; it hides shadows) — swapping a sales screen, count, analytics
 * or reconcile query to warehouse_orders fails here until someone decides. Stale entries fail too.
 * The runtime backstop is ShadowOrderLeakTest.
 */
class WarehouseOrdersAccessGuardTest {

    record Entry(String reason) {}

    private static final Path SRC_MAIN = Paths.get("src/main/java");
    private static final String PKG = "com/traceability/";

    /** file (under com/traceability/) + '#' + member (method name, or field:NAME) → why a shadow order belongs. */
    static final Map<String, Entry> WAREHOUSE_ALLOWLIST = allowlist(
        // S3b (V219) — Shopify-native exchange shadow orders
        // S3c — Pick & Pack (held / released)
        "inventory/ShopifyShadowOrders.java#pickState",
            "S3c: scan() / complete() / the waybill resolver read a shadow's held / ended / packable state",
        "inventory/ShopifyShadowOrders.java#updateShadow",
            "S3c: a shadow's line quantity follows the mirror only while the shadow is still to be picked (its status)",
        "inventory/FulfillService.java#field:QUEUE_ORDER_IDS_SQL",
            "S3c: THE Pick & Pack order set — released shadows in, held / ended shadows out",
        "inventory/FulfillService.java#getWaitingForRelease",
            "S3c: Waiting for release lists the held shadows beside the queue",
        "inventory/ExceptionService.java#detectShopifyExchangeShippedInShopify",
            "S3b: names the shadow whose exchange Shopify fulfilled while Traced still had the piece",
        "inventory/ShopifyShadowOrders.java#shortages",
            "S3b: Short by N — the live shadows' demand per variant, oldest first",
        "inventory/VariantStockService.java#field:SHADOW_COMMITTED_SQL",
            "S3b: a shadow's replacement is committed from approval (by count)",
        "returncases/ShopifyReturnsView.java#keptAside",
            "S3b: the Shopify return card's \"#…-X1 · kept aside\" line names the return's shadow",
        "courier/GenericDeliveryService.java#carriersWithCourier",
            "G3 carriers with a leg at the courier — a shadow's generic leg is warehouse/courier work",
        "courier/GenericDeliveryService.java#markDelivered",
            "G3 Mark delivered acts on a shadow's generic forward leg like any order's",
        "courier/GenericShipments.java#field:CANCELLABLE_SQL",
            "G2 cancels a shadow's 'created' generic leg like any order's",
        "courier/GenericShipments.java#onFulfillments",
            "G2 generic forward leg from a fulfillment — S3d routes an exchange fulfillment to its shadow",
        "courier/TestWaybillService.java#test",
            "Settings test-waybill names the order a scanned waybill belongs to — a shadow's waybill included",
        "courier/TrackingChanges.java#lockAndCheckFrozen",
            "row lock + frozen check by id on a tracking change — must see a shadow being packed",
        "courier/TrackingChanges.java#relabelReasons",
            "relabel reasons of a tracking change on a packed shadow",
        "fulfillment/OrderController.java#detail",
            "Q3 (approved 2026-11-06): the drawer opens for a shadow from Pick & Pack / Lookup links (by id; never in the list)",
        "fulfillment/OrderController.java#shippingBadge",
            "Q3: the drawer's shipping badge of a shadow (by id)",
        "fulfillment/OrderController.java#timeline",
            "Q3: the drawer's timeline of a shadow (by id)",
        "fulfillment/OrderNotesService.java#requireOrder",
            "Q3: notes in a shadow's drawer (by id)",
        "integrations/bosta/BostaAwbService.java#printSimulated",
            "simulated waybill of a shadow's leg (Pick & Pack print)",
        "integrations/bosta/BostaController.java#backfillPii",
            "fills empty PII from a linked Bosta delivery — a shadow's leg too (fill-only)",
        "integrations/bosta/BostaPickupService.java#field:AWAITING_PICKUP_SQL",
            "Pickups: a packed shadow waits for the courier",
        "integrations/bosta/BostaPickupService.java#getManifest",
            "Pickups manifest lists a shadow's parcel",
        "integrations/bosta/BostaRelink.java#attempt",
            "re-link of a shadow's Bosta forward leg",
        "integrations/bosta/SimulatedShipments.java#ensureForwardShipment",
            "review mode: a shadow gets its simulated forward leg like any order",
        "inventory/ExceptionService.java#detectCancelledWithLiveShipment",
            "warehouse / courier alert (S3 decision: pack & ship operational alerts include shadows)",
        "inventory/ExceptionService.java#detectGenericBostaConflictAtPack",
            "warehouse / courier alert (S3 decision: pack & ship operational alerts include shadows)",
        "inventory/ExceptionService.java#detectGenericCancelledLiveShipment",
            "warehouse / courier alert (S3 decision: pack & ship operational alerts include shadows)",
        "inventory/ExceptionService.java#detectGenericDeliveredWithoutPickup",
            "warehouse / courier alert (S3 decision: pack & ship operational alerts include shadows)",
        "inventory/ExceptionService.java#detectGuidedUnpack",
            "warehouse / courier alert (S3 decision: pack & ship operational alerts include shadows)",
        "inventory/ExceptionService.java#detectLost",
            "warehouse / courier alert (S3 decision: pack & ship operational alerts include shadows)",
        "inventory/ExceptionService.java#detectNeverReceived",
            "warehouse / courier alert (S3 decision: pack & ship operational alerts include shadows)",
        "inventory/ExceptionService.java#detectPackCancelledAfterPrint",
            "warehouse / courier alert (S3 decision: pack & ship operational alerts include shadows)",
        "inventory/ExceptionService.java#detectPackSetAside",
            "warehouse / courier alert (S3 decision: pack & ship operational alerts include shadows)",
        "inventory/ExceptionService.java#detectRestockedTwice",
            "warehouse / courier alert (S3 decision: pack & ship operational alerts include shadows)",
        "inventory/ExceptionService.java#detectStuck",
            "warehouse / courier alert (S3 decision: pack & ship operational alerts include shadows)",
        "inventory/ExceptionService.java#detectSupersededWaybillMoving",
            "warehouse / courier alert (S3 decision: pack & ship operational alerts include shadows)",
        "inventory/ExceptionService.java#detectTrackingChanges",
            "warehouse / courier alert (S3 decision: pack & ship operational alerts include shadows)",
        "inventory/ExceptionService.java#detectWaybillDeletedInBosta",
            "warehouse / courier alert (S3 decision: pack & ship operational alerts include shadows)",
        "inventory/FulfillService.java#complete",
            "Pick & Pack (queue, pick, pack, hold, handover, unpack) — a shadow is packed through Traced",
        "inventory/FulfillService.java#convertToSelfPickup",
            "Pick & Pack (queue, pick, pack, hold, handover, unpack) — a shadow is packed through Traced",
        "inventory/FulfillService.java#getAwaitingWaybillCount",
            "Pick & Pack (queue, pick, pack, hold, handover, unpack) — a shadow is packed through Traced",
        "inventory/FulfillService.java#getGatherList",
            "Pick & Pack (queue, pick, pack, hold, handover, unpack) — a shadow is packed through Traced",
        "inventory/FulfillService.java#getOrder",
            "Pick & Pack (queue, pick, pack, hold, handover, unpack) — a shadow is packed through Traced",
        "inventory/FulfillService.java#getQueue",
            "Pick & Pack (queue, pick, pack, hold, handover, unpack) — a shadow is packed through Traced",
        "inventory/FulfillService.java#handover",
            "Pick & Pack (queue, pick, pack, hold, handover, unpack) — a shadow is packed through Traced",
        "inventory/FulfillService.java#holdOrder",
            "Pick & Pack (queue, pick, pack, hold, handover, unpack) — a shadow is packed through Traced",
        "inventory/FulfillService.java#lockOrder",
            "Pick & Pack (queue, pick, pack, hold, handover, unpack) — a shadow is packed through Traced",
        "inventory/FulfillService.java#releaseHold",
            "Pick & Pack (queue, pick, pack, hold, handover, unpack) — a shadow is packed through Traced",
        "inventory/FulfillService.java#releaseOrder",
            "Pick & Pack (queue, pick, pack, hold, handover, unpack) — a shadow is packed through Traced",
        "inventory/FulfillService.java#requireMerchantOrder",
            "Pick & Pack (queue, pick, pack, hold, handover, unpack) — a shadow is packed through Traced",
        "inventory/FulfillService.java#requirePickableStatus",
            "Pick & Pack (queue, pick, pack, hold, handover, unpack) — a shadow is packed through Traced",
        "inventory/FulfillService.java#setSelfPickup",
            "Pick & Pack (queue, pick, pack, hold, handover, unpack) — a shadow is packed through Traced",
        "inventory/FulfillService.java#unpackOrder",
            "Pick & Pack (queue, pick, pack, hold, handover, unpack) — a shadow is packed through Traced",
        "inventory/FulfillService.java#unpackPiece",
            "Pick & Pack (queue, pick, pack, hold, handover, unpack) — a shadow is packed through Traced",
        "inventory/InventoryStockController.java#pieces",
            "inventory piece list names the order a reserved / packed piece is on — a shadow included",
        "inventory/LookupService.java#lookupPiece",
            "Lookup a piece: its order may be a shadow (shown nested under the original in S3d)",
        "inventory/LookupService.java#lookupTracking",
            "Lookup a waybill: a shadow's forward leg",
        "inventory/MainReconcileRules.java#field:EXCHANGE_HOLD_SQL",
            "S3a interim guard: Main reconcile holds while a shadow is open — it must see shadows",
        "inventory/OrderLineFreeze.java#isFrozen",
            "a packed / shipped shadow is frozen like any order (hidden it would read as not frozen)",
        "inventory/PackClaim.java#heldByOther",
            "waybill-mode pack claim on a shadow",
        "inventory/PackClaim.java#refresh",
            "waybill-mode pack claim on a shadow",
        "inventory/PackClaim.java#release",
            "waybill-mode pack claim on a shadow",
        "inventory/PackClaim.java#take",
            "waybill-mode pack claim on a shadow",
        "inventory/PackCompleter.java#completeAndLink",
            "waybill-mode pack completion of a shadow",
        "inventory/PackListService.java#batchesToday",
            "print batches / pack list include a shadow",
        "inventory/PackListService.java#field:ITEM_STATE_FROM",
            "print batches / pack list include a shadow",
        "inventory/PackListService.java#reprintItemsSql",
            "print batches / pack list include a shadow",
        "inventory/PackListService.java#summary",
            "print batches / pack list include a shadow",
        "inventory/PackPrintBatchStore.java#candidates",
            "print batch candidates include a pickable shadow",
        "inventory/PackPrintBatchStore.java#queueOrderIds",
            "print batch candidates include a pickable shadow",
        "inventory/PackSessionStore.java#end",
            "waybill pack session opens / shows / ends a shadow",
        "inventory/PackSessionStore.java#orderCard",
            "waybill pack session opens / shows / ends a shadow",
        "inventory/PackSessionStore.java#view",
            "waybill pack session opens / shows / ends a shadow",
        "inventory/PackedOrderService.java#list",
            "packed list shows a packed shadow",
        "inventory/PickErrorReversalService.java#reverse",
            "pick-error reversal of a shadow (status write)",
        "inventory/PickupSessionService.java#closeSession",
            "pickup session scans / closes a shadow's parcel",
        "inventory/PickupSessionService.java#getSession",
            "pickup session scans / closes a shadow's parcel",
        "inventory/PickupSessionService.java#scan",
            "pickup session scans / closes a shadow's parcel",
        "inventory/ReturnService.java#neverReceived",
            "never-received check of a shadow's shipped piece (returns side)",
        "inventory/ScanHelperService.java#pickup",
            "click-to-scan helpers offer what the screen's own scan accepts — a shadow included",
        "inventory/ScanHelperService.java#returns",
            "click-to-scan helpers offer what the screen's own scan accepts — a shadow included",
        "inventory/ScanHelperService.java#waybills",
            "click-to-scan helpers offer what the screen's own scan accepts — a shadow included",
        "inventory/ShipmentLinkService.java#completeGenericLink",
            "links a waybill to a shadow / moves it to awaiting_pickup (a write through merchant_orders would change 0 rows)",
        "inventory/ShipmentLinkService.java#completeLink",
            "links a waybill to a shadow / moves it to awaiting_pickup (a write through merchant_orders would change 0 rows)",
        "inventory/ShipmentLinkService.java#handleSwappedAwbCheck",
            "links a waybill to a shadow / moves it to awaiting_pickup (a write through merchant_orders would change 0 rows)",
        "inventory/ShipmentLinkService.java#linkAtMapTime",
            "links a waybill to a shadow / moves it to awaiting_pickup (a write through merchant_orders would change 0 rows)",
        "inventory/ShipmentLinkService.java#linkByAwbScan",
            "links a waybill to a shadow / moves it to awaiting_pickup (a write through merchant_orders would change 0 rows)",
        "inventory/ShipmentLinkService.java#linkDeliveryToOrder",
            "links a waybill to a shadow / moves it to awaiting_pickup (a write through merchant_orders would change 0 rows)",
        "inventory/ShipmentLinkService.java#linkMatchedDelivery",
            "links a waybill to a shadow / moves it to awaiting_pickup (a write through merchant_orders would change 0 rows)",
        "inventory/ShipmentLinkService.java#manualLink",
            "links a waybill to a shadow / moves it to awaiting_pickup (a write through merchant_orders would change 0 rows)",
        "inventory/ShipmentLinkService.java#populateConsigneePiiFromRaw",
            "links a waybill to a shadow / moves it to awaiting_pickup (a write through merchant_orders would change 0 rows)",
        "inventory/VariantStockService.java#field:COMMITTED_SQL",
            "committed demand: a shadow's replacement is kept aside (R2/R3); S3b adds the from-approval term",
        "inventory/WaybillResolver.java#notPackable",
            "waybill scan resolution (both modes) — HELD_FOR_RELEASE added in S3c",
        "inventory/WaybillResolver.java#resolve",
            "waybill scan resolution (both modes) — HELD_FOR_RELEASE added in S3c",
        "inventory/WaybillResolver.java#resolveGeneric",
            "waybill scan resolution (both modes) — HELD_FOR_RELEASE added in S3c",
        "labels/LabelCodeService.java#validateLines",
            "packing labels of a shadow's lines",
        "labels/PackLabelService.java#orders",
            "packing labels of a shadow",
        "labels/PackLabelService.java#pdf",
            "packing labels of a shadow",
        "labels/PackLabelService.java#printPlan",
            "packing labels of a shadow",
        "labels/PackLabelService.java#replacement",
            "packing labels of a shadow",
        "overview/OverviewService.java#exceptionsRaw",
            "only its guided_unpack and cancelled_with_live_shipment queries (warehouse alerts, mirroring the detectors); the Shopify-signal queries stay on merchant_orders",
        "overview/OverviewService.java#lateToPack",
            "Q8 (approved 2026-11-06): late to pack is a warehouse metric — shadows count",
        "places/PickPlacesService.java#ordersFor",
            "pick places of a shadow being picked",
        "places/PutAwayService.java#waiting",
            "put away 'came from' names a returned piece's order — a shadow included",
        "portal/RequestAmounts.java#collector",
            "pre-connect test: warehouse_orders hides exactly the portal pre-connect rows (merchant_orders now also hides shadows)"
    );

    @Test
    void warehouseOrdersIsNamedOnlyByClassifiedMembers() throws Exception {
        List<OrdersSqlScanner.Hit> hits = OrdersSqlScanner.scanTree(SRC_MAIN, OrdersSqlScanner.WAREHOUSE_ORDERS_REF);
        assertThat(hits).as("the scanner must see the allowlisted SQL at all").isNotEmpty();

        List<String> unlisted = hits.stream()
            .filter(h -> !WAREHOUSE_ALLOWLIST.containsKey(h.key()))
            .map(h -> h.key() + ":" + h.line() + "  " + h.snippet())
            .distinct()
            .toList();
        assertThat(unlisted)
            .as("SQL naming warehouse_orders outside the allowlist. warehouse_orders shows Shopify-native exchange "
                + "shadow orders — use merchant_orders unless the query is warehouse work (Pick & Pack, waybills, "
                + "pickups, labels, committed demand, warehouse alerts), then add it to "
                + "WarehouseOrdersAccessGuardTest.WAREHOUSE_ALLOWLIST with a reason.")
            .isEmpty();

        Set<String> hitKeys = hits.stream().map(OrdersSqlScanner.Hit::key).collect(Collectors.toSet());
        Set<String> stale = new TreeSet<>(WAREHOUSE_ALLOWLIST.keySet());
        stale.removeAll(hitKeys);
        assertThat(stale).as("allowlist entries that no longer name warehouse_orders — remove them").isEmpty();
    }

    @Test
    void everyEntryHasAReason() {
        WAREHOUSE_ALLOWLIST.forEach((k, e) -> assertThat(e.reason()).as(k).isNotBlank());
    }

    /** The scanner sees warehouse_orders and only it (never merchant_orders, never orders). */
    @Test
    void scannerSeesWarehouseOrdersOnly() {
        String src = """
            package x;
            class Q {
                static final String A = "SELECT 1 FROM " +
                    "warehouse_orders o WHERE o.id = ?";
                // SELECT * FROM warehouse_orders  (a comment — ignored)
                void c() {
                    jdbc.query("SELECT 1 FROM merchant_orders m JOIN orders z ON true", r -> null);
                    jdbc.update("UPDATE warehouse_orders SET x = 1");
                }
            }
            """;
        List<OrdersSqlScanner.Hit> hits = OrdersSqlScanner.scan("x/Q.java", src, OrdersSqlScanner.WAREHOUSE_ORDERS_REF);
        assertThat(hits).extracting(OrdersSqlScanner.Hit::member, OrdersSqlScanner.Hit::keyword)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("field:A", "FROM"),
                org.assertj.core.groups.Tuple.tuple("c", "UPDATE"));
    }

    private static Map<String, Entry> allowlist(String... flat) {
        Map<String, Entry> m = new LinkedHashMap<>();
        for (int i = 0; i < flat.length; i += 2) {
            String key = PKG + flat[i];
            if (m.put(key, new Entry(flat[i + 1])) != null) throw new IllegalStateException("duplicate allowlist entry " + key);
        }
        return m;
    }
}
