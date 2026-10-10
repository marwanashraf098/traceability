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

import static com.traceability.OrdersTableAccessGuardTest.Cls.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4a Guard 1 — portal pre-connect orders (orders.origin = 'portal_pre_connect', V163) must never
 * show up outside the returns paths. Every merchant-facing query reads and writes the
 * {@code merchant_orders} view; naming the {@code orders} TABLE in SQL ({@code FROM | JOIN |
 * UPDATE | INTO orders}) is allowed only in the members listed below, each with its class and why.
 *
 * A new query on {@code orders} fails here until someone decides: switch it to
 * {@code merchant_orders} (the default — almost always right), or add it to the list with a class:
 * <ul>
 *   <li>ALREADY_EXCLUDED_BY_KEY — matches by Shopify external_id or filters {@code internal:%};
 *       a portal row's external_id is {@code internal:portal:<uuid>}, so it can never match.</li>
 *   <li>SAFE_BY_ID — reached only by an order id that came from pieces / allocations / shipments
 *       of a merchant order (a portal order has no pieces or allocations).</li>
 *   <li>MUST_INCLUDE — returns, portal, GDPR: portal rows must be visible.</li>
 *   <li>SEEDER — demo / review fixtures.</li>
 * </ul>
 * Stale entries (no longer matching any SQL) fail too, so the list stays exact.
 * The runtime backstop is PortalOrderLeakTest.
 */
class OrdersTableAccessGuardTest {

    enum Cls { ALREADY_EXCLUDED_BY_KEY, SAFE_BY_ID, MUST_INCLUDE, SEEDER }

    record Entry(Cls cls, String reason) {}

    private static final Path SRC_MAIN = Paths.get("src/main/java");
    private static final String PKG = "com/traceability/";

    /** file (under com/traceability/) + '#' + member (method name, or field:NAME). */
    static final Map<String, Entry> ALLOWLIST = allowlist(
        // ── ALREADY_EXCLUDED_BY_KEY
        e("integrations/bosta/OrderReference.java#query", ALREADY_EXCLUDED_BY_KEY,
          "reference resolver filters external_id NOT LIKE 'internal:%'"),
        e("integrations/bosta/PreConnectDeliveryFilter.java#belowLowestOrderNumber", ALREADY_EXCLUDED_BY_KEY,
          "lowest-number scan filters external_id NOT LIKE 'internal:%'"),
        e("integrations/shopify/FulfillmentTrackingCapture.java#orderId", ALREADY_EXCLUDED_BY_KEY,
          "looks the order up by its Shopify external_id"),
        e("integrations/shopify/ShopifyReconcileJob.java#field:ORDER_EXISTS", ALREADY_EXCLUDED_BY_KEY,
          "existence check by Shopify external_id"),
        e("integrations/shopify/ShopifySyncService.java#field:UPSERT_ORDER", ALREADY_EXCLUDED_BY_KEY,
          "upsert keyed ON CONFLICT (store, external_id) — a portal row's external_id is internal:portal:…"),
        e("integrations/shopify/ShopifySyncService.java#field:FLAG_ORDER_UNMAPPED", ALREADY_EXCLUDED_BY_KEY,
          "flags the id UPSERT_ORDER just returned"),
        e("integrations/shopify/ShopifySyncService.java#field:ORDER_EXISTS_BY_STORE", ALREADY_EXCLUDED_BY_KEY,
          "FR-18 cutoff guard's existence check by Shopify external_id"),
        e("integrations/shopify/ShopifyWebhookProcessorJob.java#handleOrderUpdated", ALREADY_EXCLUDED_BY_KEY,
          "orders/updated looks the order up by Shopify external_id"),
        e("integrations/shopify/ShopifyWebhookProcessorJob.java#handleOrderCancelled", ALREADY_EXCLUDED_BY_KEY,
          "orders/cancelled looks the order up by Shopify external_id, then updates that id"),
        e("inventory/ExchangeService.java#commit", ALREADY_EXCLUDED_BY_KEY,
          "INSERTs a new internal:exchange: replacement order; never reads or writes another row"),
        // ── SAFE_BY_ID
        e("fulfillment/OrderCarrier.java#recompute", SAFE_BY_ID,
          "recomputes one order's carrier class from its own shipments; no list, no count"),
        e("inventory/PieceAdjustService.java#field:FIND_COMMITTED_ORDER", SAFE_BY_ID,
          "order of a piece's active allocation; a portal order has no allocations"),
        e("inventory/ShopifyInventoryService.java#claimInspectionRestore", SAFE_BY_ID,
          "order of a restocked piece (by piece id); a portal order has no pieces"),
        e("inventory/ShopifyInventoryService.java#processReturnInspection", SAFE_BY_ID,
          "order of an inspected piece (by piece id); a portal order has no pieces"),
        e("inventory/ShopifyInventoryService.java#processExchangeDispatch", SAFE_BY_ID,
          "order of a replacement piece's allocation; a portal order has no allocations"),
        e("inventory/StockTakeReconciliationService.java#field:EXPECTED_QUERY", SAFE_BY_ID,
          "LEFT JOIN from pieces.current_order_id; a portal order has no pieces"),
        e("inventory/StockTakeReconciliationService.java#fetchCommittedOrder", SAFE_BY_ID,
          "order of a piece's active allocation; a portal order has no allocations"),
        e("inventory/ShipmentLinkService.java#clearReconcileFlag", SAFE_BY_ID,
          "clears the reconcile flag on the order a shipment was just linked to"),
        // ── MUST_INCLUDE
        e("portal/PortalPhotoService.java#upload", MUST_INCLUDE,
          "portal photo upload checks the customer's (looked-up) order"),
        e("portal/PortalService.java#findEligibleOrder", MUST_INCLUDE,
          "the portal lookup — portal_pre_connect rows are found here"),
        e("portal/PortalService.java#submitInTenant", MUST_INCLUDE,
          "portal submission re-reads the looked-up order"),
        e("portal/RefundSuggestionService.java#suggest", MUST_INCLUDE,
          "refund suggestion for a return request's order"),
        e("portal/ReturnPickupBookingService.java#verifyInTenant", MUST_INCLUDE,
          "pickup booking for a return request's order"),
        e("portal/ReturnPickupBookingService.java#confirmBooked", MUST_INCLUDE,
          "booking confirmation for a return request's order"),
        e("portal/ReturnPickupBookingService.java#loadContext", MUST_INCLUDE,
          "booking payload for a return request's order"),
        e("portal/ReturnRequestLifecycle.java#recordRefund", MUST_INCLUDE,
          "refund currency of a return request's order"),
        e("portal/ReturnRequestService.java#list", MUST_INCLUDE,
          "Requests list shows portal requests on pre-connect orders"),
        e("portal/ReturnRequestService.java#detail", MUST_INCLUDE,
          "request drawer shows portal requests on pre-connect orders"),
        e("portal/ReturnRequestService.java#exchangeProgress", MUST_INCLUDE,
          "exchange progress of a return request"),
        e("privacy/CustomerDataRequestService.java#export", MUST_INCLUDE,
          "GDPR export must include every order of the customer"),
        e("privacy/CustomerRedaction.java#field:REDACT_ORDERS", MUST_INCLUDE,
          "GDPR redact must reach every order of the customer"),
        e("privacy/CustomerSubject.java#resolve", MUST_INCLUDE,
          "GDPR subject scope must include every order of the customer"),
        e("returncases/ReturnCaseService.java#field:CASES_A", MUST_INCLUDE,
          "Returns & exchanges case A: a return request and its order"),
        e("returncases/ReturnCaseService.java#field:CASES_B", MUST_INCLUDE,
          "Returns & exchanges case B: dashboard exchange's matched order"),
        e("returncases/ReturnCaseService.java#field:CASES_C", MUST_INCLUDE,
          "Returns & exchanges case C: a return leg and its order"),
        e("inventory/ReturnSessionService.java#recordReprint", MUST_INCLUDE,
          "Scan returns: order label of a returned piece"),
        e("inventory/ReturnSessionService.java#fetchPieceContextForOldReprintGate", MUST_INCLUDE,
          "Scan returns: order context of a returned piece"),
        e("inventory/ReturnSessionService.java#getSession", MUST_INCLUDE,
          "Scan returns: session view"),
        e("inventory/ReturnSessionService.java#addParcelView", MUST_INCLUDE,
          "Scan returns: parcel card of a return leg"),
        e("inventory/ReturnSessionService.java#fetchPieceByScan", MUST_INCLUDE,
          "Scan returns: scanned piece's order"),
        e("inventory/ReturnSessionService.java#fetchExpectedPieces", MUST_INCLUDE,
          "Scan returns: pieces expected on a return leg"),
        e("inventory/ReturnSessionService.java#itemRow", MUST_INCLUDE,
          "Scan returns: item row's order number"),
        e("inventory/ReturnSessionService.java#fetchItemByPiece", MUST_INCLUDE,
          "Scan returns: item's order number"),
        e("inventory/ShipmentLinkService.java#listCrpReturns", MUST_INCLUDE,
          "Scan returns: return legs awaiting intake"),
        e("inventory/ShipmentLinkService.java#isOrderUntracked", MUST_INCLUDE,
          "Scan returns: untracked-order check for a return leg's order"),
        e("inventory/ShipmentLinkService.java#awaitingScan", MUST_INCLUDE,
          "Scan returns: awaiting-scan callout"),
        e("inventory/ExchangeService.java#findOwnExchange", MUST_INCLUDE,
          "a type-30 we booked for a return request is ours, whatever its order's origin"),
        e("inventory/ExceptionService.java#detectRequestItemToReceive", MUST_INCLUDE,
          "return alert"),
        e("inventory/ExceptionService.java#detectReturnToReceive", MUST_INCLUDE,
          "return alert"),
        e("inventory/ExceptionService.java#detectPickupBookingProblem", MUST_INCLUDE,
          "return alert"),
        e("inventory/ExceptionService.java#detectReturnLinkAmbiguous", MUST_INCLUDE,
          "return alert"),
        e("inventory/ExceptionService.java#detectRefundPendingOverdue", MUST_INCLUDE,
          "return alert"),
        e("inventory/ExceptionService.java#detectReturnItemsOverdue", MUST_INCLUDE,
          "return alert"),
        e("inventory/ExceptionService.java#detectReturnLegUnscanned", MUST_INCLUDE,
          "return alert"),
        e("inventory/ExceptionService.java#detectReturnInTransitStuck", MUST_INCLUDE,
          "return alert"),
        e("inventory/ExceptionService.java#detectUnexpectedReturn", MUST_INCLUDE,
          "return alert"),
        e("inventory/ExceptionService.java#detectDeliveryLimbo", MUST_INCLUDE,
          "shipment alert with no leg filter: a portal order's only shipments are its Traced-booked return legs"),
        e("inventory/ExceptionService.java#detectNdr", MUST_INCLUDE,
          "shipment alert with no leg filter: a portal order's only shipments are its Traced-booked return legs"),
        e("inventory/ExceptionService.java#detectMissingAwb", MUST_INCLUDE,
          "shipment alert with no leg filter: a portal order's only shipments are its Traced-booked return legs"),
        e("inventory/ExceptionService.java#detectHighAttempts", MUST_INCLUDE,
          "shipment alert with no leg filter: a portal order's only shipments are its Traced-booked return legs"),
        e("inventory/ExceptionService.java#detectMissingProviderId", MUST_INCLUDE,
          "shipment alert with no leg filter: a portal order's only shipments are its Traced-booked return legs"),
        e("overview/OverviewService.java#exchangesRaw", MUST_INCLUDE,
          "Overview exchanges tile counts replacement (internal:exchange) orders"),
        // ── SEEDER
        e("demo/DemoSeeder.java#insertPickableOrders", SEEDER,
          "demo fixture insert"),
        e("demo/DemoSeeder.java#insertInTransitShipments", SEEDER,
          "demo fixture insert"),
        e("demo/DemoSeeder.java#insertExceptions", SEEDER,
          "demo fixture insert"),
        e("review/ReviewTenantSeeder.java#order", SEEDER,
          "review fixture insert"),
        e("review/ReviewTenantService.java#seed", SEEDER,
          "'tenant has no orders' precondition — any row, portal included, must block the fixture")
    );

    @Test
    void ordersTableIsNamedOnlyByClassifiedMembers() throws Exception {
        List<OrdersSqlScanner.Hit> hits = OrdersSqlScanner.scanTree(SRC_MAIN);
        assertThat(hits).as("the scanner must see the allowlisted SQL at all").isNotEmpty();

        List<String> unlisted = hits.stream()
            .filter(h -> !ALLOWLIST.containsKey(h.key()))
            .map(h -> h.key() + ":" + h.line() + "  " + h.snippet())
            .toList();
        assertThat(unlisted)
            .as("SQL naming the orders TABLE outside the allowlist. Use merchant_orders (portal "
                + "pre-connect orders must not leak into merchant screens, jobs or counts), or "
                + "classify the member in OrdersTableAccessGuardTest.ALLOWLIST with a reason.")
            .isEmpty();

        Set<String> hitKeys = hits.stream().map(OrdersSqlScanner.Hit::key).collect(Collectors.toSet());
        Set<String> stale = new TreeSet<>(ALLOWLIST.keySet());
        stale.removeAll(hitKeys);
        assertThat(stale).as("allowlist entries that no longer name the orders table — remove them").isEmpty();
    }

    @Test
    void everyEntryHasAReason() {
        ALLOWLIST.forEach((k, e) -> assertThat(e.reason()).as(k).isNotBlank());
    }

    /** The scanner itself: string concatenation, text blocks, comments, look-alike names. */
    @Test
    void scannerSeesConcatenationAndTextBlocks_andIgnoresCommentsAndLookalikes() {
        String src = """
            package x;
            class Q {
                static final String A = "SELECT 1 FROM " +
                    "orders o WHERE o.id = ?";
                // SELECT * FROM orders  (a comment — ignored)
                /** reads from orders.raw (prose) */
                String b() {
                    return \"\"\"
                        UPDATE
                          orders SET x = 1
                        \"\"\";
                }
                @SuppressWarnings("unused")
                void c() {
                    jdbc.query("SELECT 1 FROM merchant_orders m JOIN order_items oi ON true " +
                               "JOIN orders_archive z ON true", r -> null);
                    jdbc.update("INSERT INTO orders (id) VALUES (?)", 1);
                    Runnable r = () -> jdbc.update("DELETE FROM public.orders WHERE id = ?", 2);
                }
            }
            """;
        List<OrdersSqlScanner.Hit> hits = OrdersSqlScanner.scan("x/Q.java", src);
        assertThat(hits).extracting(OrdersSqlScanner.Hit::member, OrdersSqlScanner.Hit::keyword)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("field:A", "FROM"),
                org.assertj.core.groups.Tuple.tuple("b", "UPDATE"),
                org.assertj.core.groups.Tuple.tuple("c", "INTO"),
                org.assertj.core.groups.Tuple.tuple("c", "FROM"));
    }

    private static Map<String, Entry> allowlist(Object... flat) {
        Map<String, Entry> m = new LinkedHashMap<>();
        for (Object o : flat) {
            Object[] e = (Object[]) o;
            String key = PKG + e[0];
            if (m.put(key, new Entry((Cls) e[1], (String) e[2])) != null) {
                throw new IllegalStateException("duplicate allowlist entry " + key);
            }
        }
        return m;
    }

    private static Object[] e(String member, Cls cls, String reason) {
        return new Object[]{member, cls, reason};
    }
}
