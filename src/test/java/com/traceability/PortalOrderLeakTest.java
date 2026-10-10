package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.NotTracedTagger;
import com.traceability.inventory.VariantStockService;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4a runtime backstop for the merchant_orders exclusion (V163; the static guard is
 * OrdersTableAccessGuardTest). Two tenants, each with a normal order (positive control) and
 * portal pre-connect orders (origin = 'portal_pre_connect') shaped to be as leak-prone as possible:
 * pickable-looking (new, self-pickup), held as blocked, Shopify edit conflict, cancel-in-flight,
 * a Bosta fulfillment in raw + order_fulfillment_tracking (catch-up / visibility candidates), a
 * portal return request with a return leg, and one with a forward shipment (not-traced tagger).
 *
 * Every /api/** endpoint is classified (unclassified fails, like RlsCoverageTest's registry):
 * <ul>
 *   <li>LIST — called as owner AND worker with the given query strings, once BEFORE the portal rows
 *       exist and once after: the body must contain no portal sentinel and must be byte-identical to
 *       the baseline (so counts and totals can't move either).</li>
 *   <li>BY_ORDER_ID — called as owner with a portal order's id (path or body): 404, and the portal
 *       order (row, items, shipments, allocations, notes, pack-session rows, piece events) unchanged.</li>
 *   <li>INCLUDE — returns paths: the owner's body MUST contain its own portal order, never the other
 *       tenant's.</li>
 *   <li>SKIP — with a written reason.</li>
 * </ul>
 * Non-endpoint work runs directly: every exception detector, the not-traced tagger, the Bosta
 * fulfillment catch-up / visibility-check candidates, the order reconcile job, the pickable filter
 * and the committed-stock sum. Portal rows are seeded directly — P4a has no fetch code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class PortalOrderLeakTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;
    @Autowired ExceptionService exceptions;
    @Autowired NotTracedTagger notTraced;
    @Autowired VariantStockService variantStock;
    @Autowired com.traceability.integrations.bosta.BostaFulfillmentCatchUpService catchUp;
    @Autowired com.traceability.integrations.bosta.BostaVisibilityCheckService visibility;
    @Autowired com.traceability.integrations.bosta.BostaOrderReconcileJob reconcile;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean BostaV2Client bostaV2;
    @Value("${shopify.client-secret}") String clientSecret;
    @Value("${shopify.client-id}")     String clientId;

    RestTemplate rest;

    // ── registry ──────────────────────────────────────────────────────────────

    enum Kind { LIST, BY_ORDER_ID, INCLUDE, SKIP }
    enum Auth { APP, EMBEDDED }

    /**
     * @param queries   LIST / INCLUDE: query strings appended to the path ("" = defaults)
     * @param body      BY_ORDER_ID: JSON body ({portal} = the portal order id), null = none
     * @param control   LIST: the owner's first response must contain the normal order (positive control)
     */
    record Spec(Kind kind, Auth auth, List<String> queries, String body, boolean control, String reason) {}

    static Spec list(String... q)    { return new Spec(Kind.LIST, Auth.APP, q.length == 0 ? List.of("") : List.of(q), null, false, null); }
    static Spec listCtl(String... q) { return new Spec(Kind.LIST, Auth.APP, q.length == 0 ? List.of("") : List.of(q), null, true, null); }
    static Spec embedded(String... q){ return new Spec(Kind.LIST, Auth.EMBEDDED, q.length == 0 ? List.of("") : List.of(q), null, false, null); }
    static Spec include(String... q) { return new Spec(Kind.INCLUDE, Auth.APP, q.length == 0 ? List.of("") : List.of(q), null, false, null); }
    static Spec byOrder()            { return new Spec(Kind.BY_ORDER_ID, Auth.APP, List.of(), null, false, null); }
    static Spec byOrder(String body) { return new Spec(Kind.BY_ORDER_ID, Auth.APP, List.of(), body, false, null); }
    /** LIST whose body legitimately differs call to call (sentinel check only). */
    static Spec listVolatile(String reason) { return new Spec(Kind.LIST, Auth.APP, List.of(""), null, false, reason); }
    static Spec skip(String reason)  { return new Spec(Kind.SKIP, Auth.APP, List.of(), null, false, reason); }

    static final String R_IDENTITY  = "identity / session / device pairing — touches no order rows";
    static final String R_CONFIG    = "settings / configuration write — touches no order rows";
    static final String R_PIECES    = "piece / stock workflow — reaches an order only through a piece's allocation; a portal order has none";
    static final String R_RETURNS   = "returns path — portal orders are in scope BY DESIGN (MUST_INCLUDE in OrdersTableAccessGuardTest); behaviour in P4b";
    static final String R_PORTAL    = "public returns portal — MUST_INCLUDE by design (PortalService.findEligibleOrder); the on-demand path is P4c";
    static final String R_OPS       = "ops / background trigger — its order queries read merchant_orders (OrdersTableAccessGuardTest); the "
                                      + "Bosta candidate sets are run directly in jobs_*";
    static final String R_AWB       = "acts on forward shipments by AWB / shipment id — a portal order has no forward shipment; the lookup reads merchant_orders";
    static final String R_EXCHANGE  = "acts on a dashboard exchange row, not an order";
    static final String R_SESSION   = "session-scoped GET of a session this test doesn't open — lists only what was scanned into it";

    static final Map<String, Spec> REGISTRY = new LinkedHashMap<>();
    static void reg(String key, Spec s) {
        if (REGISTRY.put(key, s) != null) throw new IllegalStateException("duplicate " + key);
    }
    static {
        // ── Orders, notes, embedded app ─────────────────────────────────────
        reg("GET /api/v1/orders",                        listCtl("", "?q=PPCLEAK", "?q=0109999990", "?status=new", "?deliveryState=delivered"));
        reg("GET /api/v1/orders/summary",                list());
        reg("GET /api/v1/orders/funnel",                 list());
        reg("GET /api/v1/orders/daily-counts",           list("", "?days=7"));
        reg("GET /api/v1/orders/{orderId}",              byOrder());
        reg("GET /api/v1/orders/{orderId}/timeline",     byOrder());
        reg("GET /api/v1/orders/{orderId}/notes",        byOrder());
        reg("POST /api/v1/orders/{orderId}/notes",       byOrder("{\"body\":\"leak\"}"));
        reg("GET /api/v1/embedded/orders/list",          embedded("", "?q=PPCLEAK"));
        reg("GET /api/v1/embedded/orders/funnel",        embedded());
        reg("GET /api/v1/embedded/orders/daily-counts",  embedded());
        reg("GET /api/v1/embedded/overview/late-to-pack",embedded());
        reg("GET /api/v1/embedded/exceptions",           embedded());
        reg("GET /api/v1/embedded/inventory/summary",    embedded());
        reg("GET /api/v1/embedded/stores/status",        embedded());
        reg("GET /api/v1/embedded/onboarding/prefill",   skip("tenantless onboarding principal for an UNLINKED shop — no tenant data"));
        reg("POST /api/v1/embedded/onboarding/signup",   skip(R_IDENTITY));
        reg("POST /api/v1/embedded/onboarding/pending-link", skip(R_IDENTITY));
        reg("POST /api/v1/embedded/token-exchange",      skip(R_IDENTITY));

        // ── Overview ───────────────────────────────────────────────────────
        reg("GET /api/v1/overview/trends",               list("", "?range=7d"));
        reg("GET /api/v1/overview/late-to-pack",         list());
        reg("GET /api/v1/overview/top-skus",             list());
        reg("GET /api/v1/activity/recent",               list());

        // ── Pick & Pack ────────────────────────────────────────────────────
        reg("GET /api/v1/fulfill/queue",                 listCtl());
        reg("GET /api/v1/fulfill/queue/awaiting-waybill-count", list());
        reg("GET /api/v1/fulfill/gather",                list());
        reg("GET /api/v1/fulfill/mode",                  list());
        reg("GET /api/v1/fulfill/print-batches/options", list());
        reg("GET /api/v1/fulfill/print-batches/today",   list());
        reg("GET /api/v1/fulfill/printed-not-packed",    list());
        reg("GET /api/v1/fulfill/{orderId}",             byOrder());
        reg("POST /api/v1/fulfill/{orderId}/lock",       byOrder());
        reg("DELETE /api/v1/fulfill/{orderId}/lock",     byOrder());
        reg("POST /api/v1/fulfill/{orderId}/scan",       byOrder("{\"barcode\":\"{barcode}\"}"));
        reg("DELETE /api/v1/fulfill/{orderId}/scan/{pieceId}", byOrder());
        reg("POST /api/v1/fulfill/{orderId}/complete",   byOrder());
        reg("POST /api/v1/fulfill/{orderId}/cancel",     byOrder());
        reg("POST /api/v1/fulfill/{orderId}/convert-to-self-pickup", byOrder("{\"reason\":\"leak\"}"));
        reg("POST /api/v1/fulfill/{orderId}/handover",   byOrder());
        reg("POST /api/v1/fulfill/{orderId}/hold",       byOrder("{\"reason\":\"leak\"}"));
        reg("POST /api/v1/fulfill/{orderId}/release-hold", byOrder());
        reg("POST /api/v1/fulfill/{orderId}/link",       byOrder("{\"trackingNumber\":\"5550009999\"}"));
        reg("POST /api/v1/fulfill/{orderId}/unpack/{pieceId}", byOrder());
        reg("PATCH /api/v1/fulfill/{orderId}/cod",       byOrder("{\"amount\":1}"));
        reg("PATCH /api/v1/fulfill/{orderId}/self-pickup", byOrder("{\"selfPickup\":false}"));
        reg("POST /api/v1/fulfill/print-batches",        skip("creates a batch from PackPrintBatchStore.candidates (merchant_orders) — the "
                                                              + "pickable filter is checked directly in jobs_pickableFilterAndCommittedStock"));
        reg("POST /api/v1/fulfill/print-batches/{batchId}/reprint", skip("reprints an existing batch's items"));
        reg("POST /api/v1/pack-sessions",                skip("opens the caller's pack session — no order"));
        reg("GET /api/v1/pack-sessions/summary",         list());
        reg("GET /api/v1/pack-sessions/{id}",            list());
        reg("GET /api/v1/pack-sessions/{sessionId}/summary", list());
        reg("POST /api/v1/pack-sessions/{id}/end",       skip("ends the caller's pack session"));
        reg("POST /api/v1/pack-sessions/{id}/waybill",   skip(R_AWB));
        reg("POST /api/v1/pack-sessions/{id}/orders/{orderId}/scan",     byOrder("{\"code\":\"{barcode}\"}"));
        reg("POST /api/v1/pack-sessions/{id}/orders/{orderId}/complete", byOrder());
        reg("POST /api/v1/pack-sessions/{id}/orders/{orderId}/set-aside",byOrder("{\"reason\":\"other\"}"));
        reg("DELETE /api/v1/pack-sessions/{id}/orders/{orderId}/scan/{pieceId}", byOrder());
        reg("GET /api/v1/pickup-sessions",               list());
        reg("GET /api/v1/pickup-sessions/{id}",          skip(R_SESSION));
        reg("GET /api/v1/pickup-sessions/{id}/manifest", skip(R_SESSION));
        reg("POST /api/v1/pickup-sessions",              skip("opens a pickup session — no order"));
        reg("POST /api/v1/pickup-sessions/{id}/scans",   skip(R_AWB));
        reg("DELETE /api/v1/pickup-sessions/{id}/scans/{shipmentId}", skip(R_AWB));
        reg("POST /api/v1/pickup-sessions/{id}/close",   skip(R_AWB));
        reg("POST /api/v1/bosta/awb/print",              skip(R_AWB));
        reg("POST /api/v1/bosta/pickup/schedule",        skip(R_AWB));
        reg("GET /api/v1/bosta/pickup/manifest/{pickupId}", skip(R_SESSION));

        // ── Lookup, inventory, catalog ─────────────────────────────────────
        reg("GET /api/v1/lookup",                        list("?q=PPCLEAKA1", "?q=%23PPCLEAKA1", "?q=0109999991", "?q=CTLA1001"));
        reg("GET /api/v1/pieces",                        list());
        reg("GET /api/v1/inventory/summary",             list());
        reg("GET /api/v1/inventory/status-totals",       list());
        reg("GET /api/v1/inventory/valuation",           list());
        reg("GET /api/v1/inventory/throughput",          list());
        reg("GET /api/v1/inventory/stock",               list());
        reg("GET /api/v1/inventory/breakdown",           list());
        reg("GET /api/v1/inventory/pieces",              list());
        reg("GET /api/v1/inventory/movements",           list());
        reg("GET /api/v1/inventory/variants/{variantId}/breakdown", list());
        reg("GET /api/v1/catalog",                       list());
        reg("GET /api/v1/variants",                      list());
        reg("GET /api/v1/blocklist",                     list());
        reg("POST /api/v1/blocklist",                    skip(R_CONFIG));
        reg("DELETE /api/v1/blocklist/{id}",             skip(R_CONFIG));

        // ── Exceptions ─────────────────────────────────────────────────────
        reg("GET /api/v1/exceptions",                    list("", "?severity=HIGH"));
        reg("GET /api/v1/exceptions/count",              list());
        reg("GET /api/v1/exceptions/resolutions",        list());
        reg("POST /api/v1/exceptions/resolve",           skip("resolves by (type, subject key) — no order lookup"));
        reg("POST /api/v1/exceptions/increment-sync/repush", skip(R_OPS));
        reg("POST /api/v1/exceptions/void-hold-sync/repush", skip(R_OPS));

        // ── Bosta forward side ─────────────────────────────────────────────
        reg("GET /api/v1/shipments/unlinked",            list());
        reg("POST /api/v1/shipments/unlinked/{id}/link", byOrder("{\"orderId\":\"{portal}\"}"));
        reg("GET /api/v1/bosta/sync/status",             list());
        reg("POST /api/v1/bosta/sync",                   skip(R_OPS));
        reg("POST /api/v1/bosta/connect",                skip(R_CONFIG));
        reg("PUT /api/v1/bosta/settings",                skip(R_CONFIG));
        reg("POST /api/v1/bosta/regenerate-secret",      skip(R_CONFIG));
        reg("POST /api/v1/bosta/backfill-pii",           skip(R_OPS));
        reg("POST /api/v1/bosta/fulfillment-link/catch-up", skip(R_OPS));
        reg("POST /api/v1/bosta/visibility-check",       skip(R_OPS));
        reg("POST /api/v1/bosta/exchange-reference/catch-up", skip(R_OPS));
        reg("POST /api/v1/bosta/reinterpret-exchange",   skip(R_OPS));
        reg("POST /api/v1/bosta/reprocess-rate-limited", skip(R_OPS));
        reg("POST /api/v1/webhooks/bosta",               skip("Bosta webhook ingest — forward linking reads merchant_orders (Guard 1); "
                                                              + "the reference resolver filters internal:%"));

        // ── Exchanges (dashboard) ──────────────────────────────────────────
        reg("GET /api/v1/exchanges",                     list("", "?status=needs_mapping"));
        reg("GET /api/v1/exchanges/{id}",                list());
        reg("GET /api/v1/exchanges/{id}/candidates",     list());
        reg("GET /api/v1/exchanges/{id}/outbound-candidates", list());
        reg("POST /api/v1/exchanges/{id}/attach",        byOrder("{\"orderId\":\"{portal}\"}"));
        reg("POST /api/v1/exchanges/{id}/map",           skip(R_EXCHANGE));
        reg("POST /api/v1/exchanges/{id}/dismiss",       skip(R_EXCHANGE));
        reg("POST /api/v1/exchanges/{id}/bare-return",   skip(R_EXCHANGE));
        reg("POST /api/v1/exchanges/{id}/outbound-variant", skip(R_EXCHANGE));

        // ── Returns (portal orders are IN scope) ───────────────────────────
        reg("GET /api/v1/return-requests",               include());
        reg("GET /api/v1/return-requests/{id}",          include());
        reg("GET /api/v1/returns-exchanges",             include("?tab=requests"));
        reg("GET /api/v1/returns-exchanges/counts",      skip("counts only (no sentinel to look for) — returns cases INCLUDE portal orders by design"));
        reg("GET /api/v1/return-requests/{id}/pickup-areas",      skip(R_RETURNS));
        reg("GET /api/v1/return-requests/{id}/refund-suggestion", skip(R_RETURNS));
        reg("GET /api/v1/return-requests/{id}/refund-details",    skip(R_RETURNS));
        reg("GET /api/v1/return-requests/{id}/photos/{photoId}",  skip(R_RETURNS));
        for (String a : List.of("approve", "reject", "close", "rest-not-coming", "mark-refunded", "refunds", "link-leg",
                                "switch-to-refund", "booking/book-now", "booking/confirm", "booking/not-booked", "booking/retry")) {
            reg("POST /api/v1/return-requests/{id}/" + a, skip(R_RETURNS));
        }
        reg("POST /api/v1/return-requests/{id}/refunds/{refundId}/void", skip(R_RETURNS));
        reg("POST /api/v1/return-requests/{id}/items/{itemId}/arrived",      skip(R_RETURNS));
        reg("POST /api/v1/return-requests/{id}/items/{itemId}/arrived/undo", skip(R_RETURNS));
        reg("PUT /api/v1/return-requests/{id}/pickup-area", skip(R_RETURNS));
        reg("GET /api/v1/refunds",                       skip(R_RETURNS));
        reg("GET /api/v1/returns/pending",               skip(R_RETURNS));
        reg("GET /api/v1/returns/awaiting-scan",         skip(R_RETURNS));
        reg("GET /api/v1/returns/analytics",             skip(R_RETURNS));
        reg("GET /api/v1/returns/sessions",              skip(R_RETURNS));
        reg("GET /api/v1/returns/sessions/{sessionId}",  skip(R_RETURNS));
        reg("GET /api/v1/returns/pieces/{pieceId}/label",skip("label PDF of one piece"));
        reg("POST /api/v1/returns/sessions",             skip(R_RETURNS));
        reg("DELETE /api/v1/returns/sessions/{sessionId}", skip(R_RETURNS));
        for (String a : List.of("close", "scan", "items/{pieceId}/disposition", "pieces/{pieceId}/reprint-label",
                                "parcels/{shipmentId}/mark-received", "parcels/{shipmentId}/undo-mark-received",
                                "parcels/{shipmentId}/units/arrived", "parcels/{shipmentId}/units/arrived/undo",
                                "request-items/{itemId}/arrived", "request-items/{itemId}/arrived/undo")) {
            reg("POST /api/v1/returns/sessions/{sessionId}/" + a, skip(R_RETURNS));
        }
        reg("GET /api/v1/portal/{slug}/config",          skip(R_PORTAL));
        reg("GET /api/v1/portal/{slug}/logo",            skip(R_PORTAL));
        reg("GET /api/v1/portal/{slug}/districts",       skip(R_PORTAL));
        reg("POST /api/v1/portal/{slug}/lookup",         skip(R_PORTAL));
        reg("POST /api/v1/portal/{slug}/photos",         skip(R_PORTAL));
        reg("POST /api/v1/portal/{slug}/requests",       skip(R_PORTAL));
        reg("GET /api/v1/tenant/portal-settings",        list());
        reg("PUT /api/v1/tenant/portal-settings",        skip(R_CONFIG));
        reg("GET /api/v1/tenant/portal-settings/logo",   list());
        reg("PUT /api/v1/tenant/portal-settings/logo",   skip(R_CONFIG));
        reg("DELETE /api/v1/tenant/portal-settings/logo",skip(R_CONFIG));
        reg("GET /api/v1/tenant/bosta/return-locations", skip("Bosta pickup locations (mocked v2 read) — no order rows"));
        reg("PUT /api/v1/variants/{id}/non-returnable",  skip(R_CONFIG));

        // ── GDPR ───────────────────────────────────────────────────────────
        reg("GET /api/v1/privacy/data-requests",         list());
        reg("GET /api/v1/privacy/data-requests/{id}/export", skip("GDPR export — MUST_INCLUDE by design; P4b adds shopify_order_gid matching"));

        // ── Analytics (owner only) ─────────────────────────────────────────
        for (String p : List.of("sales/variants", "sales/products", "sales/cities", "sales/variants/daily",
                                "money/pipeline", "money/fees", "money/fees/extra", "money/stuck", "money/payouts",
                                "revenue/summary", "revenue/breakdown", "revenue/discounts", "revenue/heatmap",
                                "delivery/summary", "delivery/failure-reasons", "products/extras",
                                "orders/export.csv", "alerts", "cash-forecast",
                                "stock/summary", "stock/variants", "stock/restock", "settings", "pieces",
                                "customers/summary", "customers/top", "customers/by-governorate", "customers/cohorts",
                                "customers/watch", "profit/summary", "profit/by-product-type", "profit/skus",
                                "inventory-sync/status")) {
            reg("GET /api/v1/analytics/" + p, list());
        }
        reg("GET /api/v1/analytics/orders",              listCtl("", "?q=PPCLEAK"));
        reg("GET /api/v1/analytics/variants/{id}/orders",list());
        reg("GET /api/v1/analytics/variants/{id}/pieces",list());
        reg("GET /api/v1/analytics/pieces/{id}/history", list());
        reg("PUT /api/v1/analytics/settings",            skip(R_CONFIG));
        reg("POST /api/v1/analytics/inventory-sync/run", skip(R_OPS));

        // ── Stock, receiving, transfers, stock takes, pieces ───────────────
        for (String k : List.of(
                "GET /api/v1/receiving/sessions", "GET /api/v1/receiving/sessions/{sessionId}",
                "GET /api/v1/receiving/sessions/{sessionId}/pieces", "GET /api/v1/receiving/variants/search",
                "GET /api/v1/receiving/sessions/{sessionId}/labels",
                "GET /api/v1/receiving/sessions/{sessionId}/variants/{variantId}/labels",
                "POST /api/v1/receiving/sessions", "POST /api/v1/receiving/sessions/{sessionId}/finalize",
                "POST /api/v1/receiving/sessions/{sessionId}/lines", "POST /api/v1/receiving/sessions/{sessionId}/reprint",
                "POST /api/v1/receiving/sessions/{sessionId}/variants/{variantId}/reprint",
                "PUT /api/v1/receiving/sessions/{sessionId}/lines/{lineId}",
                "DELETE /api/v1/receiving/sessions/{sessionId}", "DELETE /api/v1/receiving/sessions/{sessionId}/lines/{lineId}",
                "GET /api/v1/stock-takes/sessions/{sessionId}", "GET /api/v1/stock-takes/sessions/{sessionId}/reconciliation",
                "POST /api/v1/stock-takes/sessions", "POST /api/v1/stock-takes/sessions/{sessionId}/attest-complete",
                "POST /api/v1/stock-takes/sessions/{sessionId}/cancel", "POST /api/v1/stock-takes/sessions/{sessionId}/finalize",
                "POST /api/v1/stock-takes/sessions/{sessionId}/resolve", "POST /api/v1/stock-takes/sessions/{sessionId}/scan",
                "POST /api/v1/stock-takes/sessions/{sessionId}/sync/mark-resolved",
                "POST /api/v1/stock-takes/sessions/{sessionId}/sync/repush",
                "DELETE /api/v1/stock-takes/sessions/{sessionId}/scan/{pieceId}",
                "GET /api/v1/transfers/{transferId}", "POST /api/v1/transfers",
                "POST /api/v1/transfers/{transferId}/begin-reconcile", "POST /api/v1/transfers/{transferId}/cancel",
                "POST /api/v1/transfers/{transferId}/classify", "POST /api/v1/transfers/{transferId}/close",
                "POST /api/v1/transfers/{transferId}/close-one-way", "POST /api/v1/transfers/{transferId}/mark-sent",
                "POST /api/v1/transfers/{transferId}/reprint-outstanding", "POST /api/v1/transfers/{transferId}/return-scan-out",
                "POST /api/v1/transfers/{transferId}/scan-back", "POST /api/v1/transfers/{transferId}/scan-out",
                "POST /api/v1/pieces/{id}/adjust", "POST /api/v1/pieces/{id}/hold", "POST /api/v1/pieces/{id}/release-for-adjust",
                "POST /api/v1/pieces/{id}/restore", "POST /api/v1/pieces/{id}/unhold", "POST /api/v1/pieces/{id}/void")) {
            reg(k, skip(R_PIECES));
        }
        reg("GET /api/v1/stock-takes/sessions",          list());
        reg("GET /api/v1/stock-takes/summary",           list());
        reg("GET /api/v1/transfers",                     list());
        reg("GET /api/v1/transfers/returnable-pieces",   list());
        reg("GET /api/v1/shopify-inventory/adjustments", list());
        reg("GET /api/v1/shopify-inventory/adjustments/export.csv", list());
        reg("GET /api/v1/shopify/inventory/reconcile",   skip("Shopify inventory reconcile preview — calls the (mocked) Shopify gateway; pieces only"));
        reg("POST /api/v1/shopify/inventory/reconcile/apply", skip(R_OPS));
        reg("POST /api/v1/shopify/inventory/activate",   skip(R_OPS));

        // ── Locations, stores, Shopify ─────────────────────────────────────
        reg("GET /api/v1/locations",                     list());
        reg("GET /api/v1/locations/shopify-junk-report", skip("calls the (mocked) Shopify gateway — locations only"));
        reg("POST /api/v1/locations",                    skip(R_CONFIG));
        reg("POST /api/v1/locations/shopify/activate-fulfillment", skip(R_OPS));
        reg("POST /api/v1/locations/{id}/shopify-cleanup", skip(R_OPS));
        reg("PUT /api/v1/locations/{id}/shopify-sync-mode",skip(R_CONFIG));
        reg("GET /api/v1/shopify/stores",                list());
        reg("GET /api/v1/shopify/stores/{storeId}/status", list());
        reg("GET /api/v1/connections",                   list());
        for (String k : List.of("POST /api/v1/shopify/stores/{storeId}/sync", "POST /api/v1/shopify/stores/{storeId}/disconnect",
                "POST /api/v1/shopify/stores/{storeId}/register-webhooks", "POST /api/v1/shopify/stores/{storeId}/refresh-cc-scopes",
                "POST /api/v1/shopify/backfill-product-images", "POST /api/v1/shopify/webhooks/reprocess")) {
            reg(k, skip(R_OPS));
        }
        reg("POST /api/v1/shopify/custom-connect",       skip(R_IDENTITY));
        reg("POST /api/v1/shopify/oauth/initiate",       skip(R_IDENTITY));
        reg("POST /api/v1/shopify/resolve-store",        skip(R_IDENTITY));
        reg("POST /api/v1/shopify/pending-link/preview", skip(R_IDENTITY));
        reg("POST /api/v1/shopify/pending-link/confirm", skip(R_IDENTITY));

        // ── Tenant, users, auth, station, misc ─────────────────────────────
        reg("GET /api/v1/me",                            list());
        reg("GET /api/v1/users",                         list());
        reg("GET /api/v1/audit-log",                     listVolatile("the baseline's own calls (analytics export) write audit rows"));
        reg("GET /api/v1/tenant/settings",               list());
        reg("PUT /api/v1/tenant/settings",               skip(R_CONFIG));
        reg("GET /api/v1/onboarding/status",             list());
        reg("POST /api/v1/onboarding/dismiss",           skip(R_CONFIG));
        reg("POST /api/v1/onboarding/steps",             skip(R_CONFIG));
        reg("GET /api/v1/health",                        list());
        reg("GET /api/v1/station/roster",                list());
        reg("GET /api/v1/station/pairings/current",      list());
        reg("GET /api/v1/station/relay-stream",          skip("Server-Sent Events stream, held open — " + R_IDENTITY));
        reg("GET /api/v1/scan-helpers/{context}",        skip("capability-gated: 404 for every real tenant (the only kind seeded here)"));
        reg("GET /api/v1/scan-pair/status",              skip(R_IDENTITY));
        reg("GET /api/v1/scan-pair/scan/{eventId}",      skip(R_IDENTITY));
        for (String k : List.of("POST /api/v1/users", "PATCH /api/v1/users/{id}", "POST /api/v1/users/{id}/deactivate",
                "POST /api/v1/auth/signup", "POST /api/v1/auth/login", "POST /api/v1/auth/logout", "POST /api/v1/auth/logout-all",
                "POST /api/v1/auth/pin", "POST /api/v1/auth/refresh", "POST /api/v1/auth/forgot-password",
                "POST /api/v1/auth/reset-password", "POST /api/v1/station/pairings", "DELETE /api/v1/station/pairings/current",
                "PUT /api/v1/station/pairings/current/target", "POST /api/v1/station/relay-events/{eventId}/outcome",
                "DELETE /api/v1/pack-sessions/pairings/mine", "POST /api/v1/scan-pair/claim", "POST /api/v1/scan-pair/scan")) {
            reg(k, skip(R_IDENTITY));
        }
        reg("POST /api/v1/demo/reseed",                  skip("demo tenant only (DemoSeeder) — never a portal row"));
        reg("POST /api/v1/public/demo/start",            skip("demo tenant only (DemoSeeder) — never a portal row"));
        reg("POST /api/v1/ops/review-tenant",            skip(R_OPS));
        reg("POST /api/v1/ops/review-tenant/{tenantId}/seed", skip(R_OPS));
        reg("POST /api/v1/ops/repair/lookup-adjust-2026-10-10/{tenantId}", skip(R_OPS));
        reg("POST /api/v1/admin/incidents/2212102474-pick-error/reverse", skip("one fixed incident order by number — reads merchant_orders"));
        reg("GET /api/v1/test/probe",                    skip("test-only probe controller (src/test/java)"));
    }

    // ── per-tenant fixture ────────────────────────────────────────────────────

    static final class T {
        final String tag;          // "A" / "B"
        String ownerToken, workerToken, shop;
        UUID tenantId, ownerId, workerId, storeId, variantId, pieceId, locationId, controlOrder, controlDelivered;
        UUID exchangeId, packSessionId, requestId;
        long unlinkedId;
        String pieceBarcode;
        final List<UUID> portalOrders = new ArrayList<>();
        T(String tag) { this.tag = tag; }
        String phone() { return "A".equals(tag) ? "01099999991" : "01099999992"; }
        /** Strings that appear only on this tenant's portal orders. */
        List<String> sentinels() {
            List<String> s = new ArrayList<>(List.of("PPCLEAK" + tag, "Leak Sentinel " + tag, phone(), "1099999999" + ("A".equals(tag) ? "1" : "2")));
            portalOrders.forEach(id -> s.add(id.toString()));
            return s;
        }
    }

    final T a = new T("A"), b = new T("B");

    /** LIST baseline (before the portal rows exist): key "METHOD path?query|role" → body. */
    final Map<String, String> baseline = new LinkedHashMap<>();

    @BeforeAll
    void setup() throws Exception {
        rest = restTemplate();
        for (T t : List.of(a, b)) seedTenant(t);
        for (T t : List.of(a, b)) seedControl(t);
        // Baseline: every LIST endpoint for tenant A, before any portal row exists.
        for (Map.Entry<String, Spec> e : REGISTRY.entrySet()) {
            if (e.getValue().kind() != Kind.LIST) continue;
            for (String role : roles(e.getValue())) {
                for (String q : e.getValue().queries()) {
                    baseline.put(e.getKey() + q + "|" + role, call(e.getKey(), q, a, role, null).getBody());
                }
            }
        }
        for (T t : List.of(a, b)) seedPortal(t);
    }

    // ── 1. registry completeness ──────────────────────────────────────────────

    @Test
    void a_everyApiEndpointIsClassified() {
        Set<String> discovered = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, hm) -> {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String p : info.getPatternValues()) {
                if (!p.startsWith("/api/")) continue;
                if (methods.isEmpty()) discovered.add("* " + p);
                for (RequestMethod m : methods) discovered.add(m.name() + " " + p);
            }
        });
        Set<String> unclassified = new TreeSet<>(discovered);
        unclassified.removeAll(REGISTRY.keySet());
        assertThat(unclassified)
            .as("every /api/** endpoint must be classified in PortalOrderLeakTest.REGISTRY "
                + "(LIST / BY_ORDER_ID / INCLUDE / SKIP with a reason)")
            .isEmpty();
        Set<String> stale = new TreeSet<>(REGISTRY.keySet());
        stale.removeAll(discovered);
        assertThat(stale).as("registry entries for endpoints that no longer exist").isEmpty();
        REGISTRY.forEach((k, s) -> {
            if (s.kind() == Kind.SKIP) assertThat(s.reason()).as(k).isNotBlank();
        });
    }

    // ── 2. LIST: no sentinel, identical to the baseline ───────────────────────

    @Test
    void b_listEndpoints_neverShowPortalOrders_andDoNotMove() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Spec> e : REGISTRY.entrySet()) {
            Spec s = e.getValue();
            if (s.kind() != Kind.LIST) continue;
            for (String role : roles(s)) {
                for (int i = 0; i < s.queries().size(); i++) {
                    String q = s.queries().get(i);
                    ResponseEntity<String> r = call(e.getKey(), q, a, role, null);
                    String body = Objects.toString(r.getBody(), "");
                    String before = baseline.get(e.getKey() + q + "|" + role);
                    for (T t : List.of(a, b)) {
                        for (String sentinel : t.sentinels()) {
                            // in the baseline too = the query string echoed back (no portal row existed then)
                            if (body.contains(sentinel) && !Objects.toString(before, "").contains(sentinel)) {
                                failures.add(e.getKey() + q + " as " + role + " (" + r.getStatusCode().value()
                                    + ") shows portal sentinel '" + sentinel + "' of tenant " + t.tag);
                            }
                        }
                    }
                    if (s.reason() == null && !Objects.equals(normalize(before), normalize(body))) {
                        failures.add(e.getKey() + q + " as " + role + " changed once portal rows existed:\n" + diff(before, body));
                    }
                    if (s.control() && i == 0 && "owner".equals(role) && !body.contains("CTL" + a.tag + "1001")) {
                        failures.add(e.getKey() + q + " positive control missing: the normal order #CTL"
                            + a.tag + "1001 should be listed (" + r.getStatusCode().value() + ")");
                    }
                }
            }
        }
        assertThat(failures).as("portal pre-connect orders leaked into merchant LIST endpoints").isEmpty();
    }

    // ── 3. BY_ORDER_ID: 404 and nothing changes ───────────────────────────────

    @Test
    void c_byOrderIdEndpoints_404_andThePortalOrderIsUnchanged() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Spec> e : REGISTRY.entrySet()) {
            Spec s = e.getValue();
            if (s.kind() != Kind.BY_ORDER_ID) continue;
            for (UUID portal : a.portalOrders) {
                String before = portalState(a);
                String path = e.getKey().substring(e.getKey().indexOf(' ') + 1);
                UUID missing = UUID.randomUUID();
                ResponseEntity<String> none = call(e.getKey(), "", a, "owner", missing);
                ResponseEntity<String> r = call(e.getKey(), "", a, "owner", portal);
                int want = NOT_404.containsKey(e.getKey()) ? none.getStatusCode().value() : 404;
                if (r.getStatusCode().value() != want || none.getStatusCode().value() != want) {
                    failures.add(e.getKey() + " with portal order → " + r.getStatusCode().value() + ", with a missing order → "
                        + none.getStatusCode().value() + " (want " + want + "): " + abbreviate(r.getBody()));
                }
                String portalBody = normalize(Objects.toString(r.getBody(), "")).replace(portal.toString(), "<order>");
                String noneBody = normalize(Objects.toString(none.getBody(), "")).replace(missing.toString(), "<order>");
                if (!portalBody.equals(noneBody)) {
                    failures.add(e.getKey() + " answers a portal order differently from a missing one:\n" + diff(noneBody, portalBody));
                }
                String after = portalState(a);
                if (!before.equals(after)) {
                    failures.add(e.getKey() + " with portal order " + portal + " CHANGED it:\n   before: "
                        + abbreviate(before) + "\n   after:  " + abbreviate(after));
                }
                assertThat(path).isNotBlank();
            }
        }
        assertThat(failures).as("merchant write / detail endpoints must not see portal orders").isEmpty();
    }

    /**
     * BY_ORDER_ID endpoints whose answer for ANY unknown order is not 404 — kept as is (existing
     * contract); the portal order must still get exactly that answer.
     */
    static final Map<String, String> NOT_404 = Map.of(
        "POST /api/v1/exchanges/{id}/attach", "400 'Order not found' for any unknown order (ExchangeMatchService.searchAttach)",
        "POST /api/v1/pack-sessions/{id}/orders/{orderId}/scan", "409 ORDER_NOT_OPEN — acts only on the session's open order; "
            + "a portal order can never be opened (WaybillResolver reads merchant_orders)",
        "POST /api/v1/pack-sessions/{id}/orders/{orderId}/complete", "409 ORDER_NOT_OPEN — as above",
        "POST /api/v1/pack-sessions/{id}/orders/{orderId}/set-aside", "409 ORDER_NOT_OPEN — as above",
        "DELETE /api/v1/pack-sessions/{id}/orders/{orderId}/scan/{pieceId}", "409 ORDER_NOT_OPEN — as above");

    // ── 4. INCLUDE: own portal order shown, never the other tenant's ─────────

    @Test
    void d_includeEndpoints_showOwnPortalOrder_only() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Spec> e : REGISTRY.entrySet()) {
            Spec s = e.getValue();
            if (s.kind() != Kind.INCLUDE) continue;
            for (String q : s.queries()) {
                ResponseEntity<String> r = call(e.getKey(), q, a, "owner", null);
                String body = Objects.toString(r.getBody(), "");
                if (!body.contains("PPCLEAKA1")) {
                    failures.add(e.getKey() + q + " (" + r.getStatusCode().value() + ") does not show tenant A's portal order: "
                        + abbreviate(body));
                }
                for (String sentinel : b.sentinels()) {
                    if (body.contains(sentinel)) failures.add(e.getKey() + q + " shows tenant B's '" + sentinel + "'");
                }
            }
        }
        assertThat(failures).as("returns paths must include portal orders (own tenant only)").isEmpty();
    }

    // ── 5. non-endpoint work ──────────────────────────────────────────────────

    /** Detector types that may legitimately name a portal order (MUST_INCLUDE in Guard 1). */
    static final Set<String> RETURN_SIDE_TYPES = Set.of(
        "request_item_to_receive", "return_to_receive", "pickup_booking_problem", "return_link_ambiguous",
        "refund_pending_overdue", "return_items_overdue", "return_leg_unscanned", "return_in_transit_stuck",
        "unexpected_return", "delivery_limbo", "ndr", "missing_awb", "high_attempts", "missing_provider_id");

    @Test
    void e_jobs_detectors_neverRaiseForwardAlertsOnPortalOrders() {
        for (T t : List.of(a, b)) {
            List<Map<String, Object>> found = TenantContext.runAs(t.tenantId, () -> exceptions.detectAllOpen());
            List<String> leaks = new ArrayList<>();
            for (Map<String, Object> ex : found) {
                String text = ex.toString();
                boolean portal = t.sentinels().stream().anyMatch(text::contains);
                Object type = ex.getOrDefault("type", ex.get("exceptionType"));
                if (portal && !RETURN_SIDE_TYPES.contains(String.valueOf(type))) leaks.add(type + ": " + abbreviate(text));
            }
            assertThat(leaks).as("forward exception detectors must not see tenant " + t.tag + "'s portal orders").isEmpty();
            // positive control: the same tenant's blocked normal order IS detected
            assertThat(found.toString()).as("positive control: blocked normal order detected").contains("CTL" + t.tag + "1002");
        }
    }

    @Test
    void f_jobs_notTracedTagger_skipsPortalOrders() {
        for (UUID portal : a.portalOrders) {
            TenantContext.runAs(a.tenantId, () -> { notTraced.maybeTagNotTraced(portal, a.tenantId); return null; });
            assertThat(jdbc.queryForObject("SELECT not_traced_at FROM orders WHERE id = ?", Object.class, portal))
                .as("not-traced tagger touched portal order " + portal).isNull();
        }
        // positive control: the same shape on a normal order IS tagged
        UUID control = a.controlDelivered;
        TenantContext.runAs(a.tenantId, () -> { notTraced.maybeTagNotTraced(control, a.tenantId); return null; });
        assertThat(jdbc.queryForObject("SELECT not_traced_at FROM orders WHERE id = ?", Object.class, control)).isNotNull();
    }

    @Test
    void g_jobs_bostaCandidateSets_skipPortalOrders() throws Exception {
        for (T t : List.of(a, b)) {
            String before = portalState(t);
            for (Object svc : List.of(catchUp, visibility)) {
                Method m = svc.getClass().getDeclaredMethod("candidates", UUID.class);
                m.setAccessible(true);
                Object candidates = TenantContext.runAs(t.tenantId, () -> {
                    try { return m.invoke(svc, t.tenantId); } catch (Exception ex) { throw new RuntimeException(ex); }
                });
                String text = String.valueOf(candidates);
                assertThat(t.sentinels()).as(svc.getClass().getSimpleName() + " candidates of tenant " + t.tag)
                    .noneMatch(text::contains);
                assertThat(text).as("positive control: the normal order with a Bosta fulfillment is a candidate")
                    .contains("CTL" + t.tag + "1003");
            }
            Method rt = reconcile.getClass().getDeclaredMethod("reconcileTenant", UUID.class);
            rt.setAccessible(true);
            rt.invoke(reconcile, t.tenantId);
            assertThat(portalState(t)).as("order reconcile touched tenant " + t.tag + "'s portal orders").isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT bosta_link_attempts FROM orders WHERE id = ?", Integer.class, t.controlOrder))
                .as("positive control: the reconcile job did process the normal order").isPositive();
        }
    }

    @Test
    void h_jobs_pickableFilterAndCommittedStock_excludePortalOrders() throws Exception {
        Field f = Class.forName("com.traceability.inventory.FulfillService").getDeclaredField("PICKABLE_ORDERS_FILTER");
        f.setAccessible(true);
        String filter = (String) f.get(null);
        List<UUID> pickable = jdbc.queryForList("SELECT o.id FROM merchant_orders o " + filter, UUID.class, a.tenantId, 30);
        assertThat(pickable).contains(a.controlOrder).doesNotContainAnyElementsOf(a.portalOrders);
        // sensitivity: the first portal order IS pickable-shaped — on the raw table the same filter finds it
        List<UUID> raw = jdbc.queryForList("SELECT o.id FROM orders o " + filter, UUID.class, a.tenantId, 30);
        assertThat(raw).contains(a.portalOrders.get(0));

        Map<UUID, VariantStockService.VariantStock> stock = TenantContext.runAs(a.tenantId, () -> variantStock.computeAll());
        assertThat(variantStock.forVariant(stock, a.variantId).committed())
            .as("committed = the normal order's 1 unit only (the portal order's 2 units must not count)")
            .isEqualTo(1);
    }

    // ── seeding ───────────────────────────────────────────────────────────────

    void seedTenant(T t) throws Exception {
        String suffix = t.tag.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<TokenResponse> resp = rest.postForEntity(base() + "/api/v1/auth/signup",
            new SignupRequest("Leak Co " + t.tag, "leak_owner_" + t.tag, "leak-" + suffix + "@test.com",
                "01012345678", "Password99!", true), TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        t.ownerToken = resp.getBody().accessToken();
        t.tenantId = UUID.fromString((String) jwt.verify(t.ownerToken).getClaim("tenant"));
        t.ownerId = jdbc.queryForObject("SELECT id FROM users WHERE tenant_id = ? AND role = 'owner'", UUID.class, t.tenantId);
        t.workerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Leak Worker', ?, 'x', 'worker')",
            t.workerId, t.tenantId, "worker-" + suffix + "@test.com");
        t.workerToken = jwt.issueAccessToken(t.workerId, t.tenantId, "worker");

        t.shop = "leak-" + suffix + ".myshopify.com";
        t.storeId = UUID.randomUUID();
        // orders_ingest_from NULL on purpose: analytics floors at the store's cutoff, which would hide a
        // pre-connect order by itself — the test must prove the VIEW excludes it, not the floor.
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', NULL)", t.storeId, t.tenantId, t.shop);
        UUID productId = UUID.randomUUID();
        t.variantId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, ?, 'active')",
            productId, t.tenantId, t.storeId, "gid://shopify/Product/" + suffix, "Leak Widget " + t.tag);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'Default', ?)",
            t.variantId, t.tenantId, productId, "gid://shopify/ProductVariant/" + suffix, "LEAK-" + t.tag);
        t.locationId = jdbc.queryForObject("SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment = true LIMIT 1",
            UUID.class, t.tenantId);
        t.pieceId = UUID.randomUUID();
        t.pieceBarcode = "LEAKPC" + t.tag + suffix.substring(2).replace("-", "");
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, ?, 'available'::piece_status, ?)",
            t.pieceId, t.tenantId, t.variantId, t.pieceBarcode, "L" + t.tag + suffix.substring(2, 7), t.locationId);
    }

    /** Normal (Shopify) orders: the positive controls. */
    void seedControl(T t) {
        // #CTL…1001: pickable (new, self-pickup), 1 unit — Orders list, Pick queue, committed stock.
        t.controlOrder = order(t, "gid://shopify/Order/" + t.tag + "1001", "#CTL" + t.tag + "1001", "Control Person " + t.tag,
            "01011111111", "new", true, false, null, "'shopify'", "NULL", "now() - interval '1 day'", "'{}'::jsonb");
        item(t, t.controlOrder, 1);
        // #CTL…1002: held as blocked — the blocked-customer detector's positive control.
        UUID blocked = order(t, "gid://shopify/Order/" + t.tag + "1002", "#CTL" + t.tag + "1002", "Control Blocked " + t.tag,
            "01022222222", "new", false, true, "Blocked customer", "'shopify'", "NULL", "now() - interval '1 day'", "'{}'::jsonb");
        item(t, blocked, 1);
        // #CTL…1003: a Bosta fulfillment and no forward shipment — catch-up / visibility candidate.
        UUID fulfilled = order(t, "gid://shopify/Order/" + t.tag + "1003", "#CTL" + t.tag + "1003", "Control Shipped " + t.tag,
            "01033333333", "ready_to_pick", false, false, null, "'shopify'", "NULL", "now() - interval '1 day'",
            bostaFulfillmentRaw("5551" + ("A".equals(t.tag) ? "1" : "2") + "00003"));
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, carrier_class, fulfillment_status) " +
            "VALUES (?, ?, ?, 'Bosta', 'bosta', 'success')", t.tenantId, fulfilled, "5551" + ("A".equals(t.tag) ? "1" : "2") + "00003");
        // #CTL…1004: delivered forward shipment, no allocations — the not-traced tagger's positive control.
        t.controlDelivered = order(t, "gid://shopify/Order/" + t.tag + "1004", "#CTL" + t.tag + "1004", "Control Delivered " + t.tag,
            "01044444444", "delivered", false, false, null, "'shopify'", "NULL", "now() - interval '3 days'", "'{}'::jsonb");
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward')",
            t.tenantId, t.controlDelivered, "5552" + ("A".equals(t.tag) ? "1" : "2") + "00004");

        // fixtures the BY_ORDER_ID calls act on
        t.exchangeId = jdbc.queryForObject("INSERT INTO exchanges (tenant_id, tracking_number, status, raw) " +
            "VALUES (?, ?, 'needs_mapping', '{}'::jsonb) RETURNING id", UUID.class, t.tenantId, "TN-LEAK-EXC-" + t.tag);
        t.unlinkedId = jdbc.queryForObject("INSERT INTO unlinked_bosta_deliveries (tenant_id, tracking_number, bosta_state_code, bosta_order_type, raw) " +
            "VALUES (?, ?, 10, 'SEND', '{\"type\": {\"code\": 10, \"value\": \"SEND\"}}'::jsonb) RETURNING id",
            Long.class, t.tenantId, "5553" + ("A".equals(t.tag) ? "1" : "2") + "00005");
        ResponseEntity<String> ps = waybillMode(t, () -> exchange(HttpMethod.POST, "/api/v1/pack-sessions", t.ownerToken, null));
        assertThat(ps.getStatusCode().is2xxSuccessful()).as("open pack session: " + ps.getBody()).isTrue();
        Matcher m = Pattern.compile("\"(?:id|sessionId)\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(ps.getBody());
        assertThat(m.find()).as("pack session id in " + ps.getBody()).isTrue();
        t.packSessionId = UUID.fromString(m.group(1));
    }

    /**
     * Portal pre-connect orders, each shaped to trip a different merchant path if it leaked. Placed
     * two days ago (before nothing — but inside every default date window), sentinel number /
     * name / phone.
     */
    void seedPortal(T t) {
        String raw = bostaFulfillmentRaw("5559" + ("A".equals(t.tag) ? "1" : "2") + "00001");
        // 1: pickable-shaped (new, self-pickup), 2 units; Bosta fulfillment in raw + tracking row; a
        //    portal return request with a return leg.
        UUID p1 = portal(t, 1, "new", true, false, null, raw);
        item(t, p1, 2);
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, carrier_class, fulfillment_status) " +
            "VALUES (?, ?, ?, 'Bosta', 'bosta', 'success')", t.tenantId, p1, "5559" + ("A".equals(t.tag) ? "1" : "2") + "00001");
        UUID leg = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'with_courier'::shipment_internal_state, 'return') RETURNING id",
            UUID.class, t.tenantId, p1, "5558" + ("A".equals(t.tag) ? "1" : "2") + "00001");
        t.requestId = jdbc.queryForObject("INSERT INTO return_requests (tenant_id, order_id, type, status, reference, return_shipment_id) " +
            "VALUES (?, ?, 'refund', 'approved', ?, ?) RETURNING id", UUID.class, t.tenantId, p1, "RR-LEAK" + t.tag, leg);
        UUID oi = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ?", UUID.class, p1);
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, order_item_id, unit_no, variant_id, reason_code) " +
            "VALUES (?, ?, ?, 1, ?, 'wrong_size')", t.tenantId, t.requestId, oi, t.variantId);
        // 2: held as blocked + Shopify edit conflict.
        UUID p2 = portal(t, 2, "new", false, true, "Blocked customer", raw);
        item(t, p2, 1);
        jdbc.update("UPDATE orders SET shopify_edit_conflict_at = now() WHERE id = ?", p2);
        // 3: cancel requested while self-pickup pending (guided unpack).
        UUID p3 = portal(t, 3, "self_pickup_pending", true, false, null, raw);
        item(t, p3, 1);
        jdbc.update("UPDATE orders SET cancel_requested_at = now() WHERE id = ?", p3);
        // 4: Shopify cancel while awaiting pickup.
        UUID p4 = portal(t, 4, "awaiting_pickup", false, false, null, raw);
        item(t, p4, 1);
        jdbc.update("UPDATE orders SET shopify_cancel_requested_at = now() WHERE id = ?", p4);
        // 5: adversarial — a delivered FORWARD shipment and no allocations (not-traced tagger shape).
        UUID p5 = portal(t, 5, "delivered", false, false, null, "'{}'::jsonb");
        item(t, p5, 1);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, provider_delivery_id) " +
            "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', 'leak-fwd')",
            t.tenantId, p5, "5557" + ("A".equals(t.tag) ? "1" : "2") + "00005");
    }

    UUID portal(T t, int n, String status, boolean selfPickup, boolean onHold, String holdReason, String rawSql) {
        UUID id = order(t, "internal:portal:" + UUID.randomUUID(), "#PPCLEAK" + t.tag + n, "Leak Sentinel " + t.tag,
            t.phone(), status, selfPickup, onHold, holdReason, "'portal_pre_connect'",
            "'gid://shopify/Order/99" + ("A".equals(t.tag) ? "1" : "2") + "00" + n + "'", "now() - interval '2 days'", rawSql);
        jdbc.update("UPDATE orders SET portal_fetched_at = now(), portal_delivered_at = now() - interval '1 day', " +
            "portal_delivery = jsonb_build_object('trackingNumber', '5559', 'receiver', jsonb_build_object('fullName', 'Leak Sentinel " + t.tag + "')) " +
            "WHERE id = ?", id);
        t.portalOrders.add(id);
        return id;
    }

    UUID order(T t, String externalId, String number, String name, String phone, String status, boolean selfPickup,
               boolean onHold, String holdReason, String originSql, String gidSql, String placedSql, String rawSql) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, customer_name, customer_phone, status, " +
            "    is_self_pickup, on_hold, hold_reason, payment_method, cod_amount, placed_at, raw, origin, shopify_order_gid) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?::order_status, ?, ?, ?, 'cod', 777, " + placedSql + ", " + rawSql + ", " +
            originSql + ", " + gidSql + ") RETURNING id",
            UUID.class, t.tenantId, t.storeId, externalId, number, name, phone, status, selfPickup, onHold, holdReason);
    }

    void item(T t, UUID order, int qty) {
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?)",
            t.tenantId, order, t.variantId, qty);
    }

    static String bostaFulfillmentRaw(String tracking) {
        return "jsonb_build_object('fulfillments', jsonb_build_array(jsonb_build_object(" +
            "'tracking_company', 'Bosta', 'tracking_number', '" + tracking + "', 'status', 'success')))";
    }

    /** The portal orders and everything a merchant write could add to / change on them. */
    String portalState(T t) {
        return String.join("\n", jdbc.queryForList(
            "SELECT jsonb_build_object(" +
            "  'o', to_jsonb(o) - 'updated_at', " +
            "  'items', (SELECT jsonb_agg(to_jsonb(oi) ORDER BY oi.id) FROM order_items oi WHERE oi.order_id = o.id), " +
            "  'shipments', (SELECT jsonb_agg(to_jsonb(s) ORDER BY s.id) FROM shipments s WHERE s.order_id = o.id), " +
            "  'allocations', (SELECT count(*) FROM allocations al JOIN order_items oi ON oi.id = al.order_item_id WHERE oi.order_id = o.id), " +
            "  'notes', (SELECT count(*) FROM order_notes n WHERE n.order_id = o.id), " +
            "  'packSession', (SELECT count(*) FROM pack_session_orders p WHERE p.order_id = o.id), " +
            "  'pieceEvents', (SELECT count(*) FROM piece_events e WHERE e.order_id = o.id), " +
            "  'exchanges', (SELECT count(*) FROM exchanges x WHERE x.matched_order_id = o.id OR x.outbound_order_id = o.id), " +
            "  'unlinked', (SELECT count(*) FROM unlinked_bosta_deliveries u WHERE u.tenant_id = o.tenant_id AND u.resolved) " +
            ")::text FROM orders o WHERE o.tenant_id = ? AND o.origin = 'portal_pre_connect' ORDER BY o.id",
            String.class, t.tenantId));
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    List<String> roles(Spec s) {
        return s.auth() == Auth.EMBEDDED ? List.of("embedded") : List.of("owner", "worker");
    }

    ResponseEntity<String> call(String key, String query, T t, String role, UUID portalOrder) {
        HttpMethod method = HttpMethod.valueOf(key.substring(0, key.indexOf(' ')));
        String path = key.substring(key.indexOf(' ') + 1);
        path = path.replace("{orderId}", portalOrder != null ? portalOrder.toString() : t.controlOrder.toString())
                   .replace("{pieceId}", t.pieceId.toString())
                   .replace("{variantId}", t.variantId.toString())
                   .replace("{sessionId}", t.packSessionId.toString())
                   .replace("{storeId}", t.storeId.toString());
        if (path.startsWith("/api/v1/pack-sessions/")) path = path.replace("{id}", t.packSessionId.toString());
        if (path.startsWith("/api/v1/exchanges/"))     path = path.replace("{id}", t.exchangeId.toString());
        if (path.startsWith("/api/v1/shipments/unlinked/")) path = path.replace("{id}", Long.toString(t.unlinkedId));
        if (path.startsWith("/api/v1/return-requests/"))    path = path.replace("{id}", t.requestId.toString());
        if (path.startsWith("/api/v1/analytics/variants/")) path = path.replace("{id}", t.variantId.toString());
        if (path.startsWith("/api/v1/analytics/pieces/"))   path = path.replace("{id}", t.pieceId.toString());
        assertThat(path).as("unresolved path variable in " + key).doesNotContain("{");
        Spec s = REGISTRY.get(key);
        String body = s.body() == null ? null : s.body()
            .replace("{portal}", portalOrder != null ? portalOrder.toString() : "")
            .replace("{barcode}", t.pieceBarcode);
        String token = switch (role) {
            case "owner" -> t.ownerToken;
            case "worker" -> t.workerToken;
            case "embedded" -> embeddedToken(t);
            default -> throw new IllegalArgumentException(role);
        };
        String url = path + query;
        String b = body;
        return path.startsWith("/api/v1/pack-sessions/")
            ? waybillMode(t, () -> exchange(method, url, token, b))
            : exchange(method, url, token, b);
    }

    /** Waybill pack sessions answer MODE_NOT_WAYBILL in the default order-queue mode — switch for the call only. */
    <R> R waybillMode(T t, java.util.function.Supplier<R> call) {
        jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", t.tenantId);
        try { return call.get(); }
        finally { jdbc.update("UPDATE tenants SET pick_pack_mode = 'order_queue' WHERE id = ?", t.tenantId); }
    }

    ResponseEntity<String> exchange(HttpMethod method, String pathAndQuery, String token, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        if (body != null) h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(java.net.URI.create(base() + pathAndQuery), method, new HttpEntity<>(body, h), String.class);
    }

    String embeddedToken(T t) {
        try {
            return ShopifySessionTokenFilterTest.makeToken(t.shop, clientId, clientSecret, 300, false);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String base() { return "http://localhost:" + port; }

    static RestTemplate restTemplate() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        RestTemplate rt = new RestTemplate(new JdkClientHttpRequestFactory(client));
        rt.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) { return false; }
        });
        return rt;
    }

    /** Timestamps generated per request (e.g. "generatedAt", "now") are the only per-call noise. */
    static String normalize(String body) {
        if (body == null) return "";
        return body.replaceAll("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}(:\\d{2})?)?", "<ts>")
                   .replaceAll("\"(durationSeconds|ageMinutes|ageHours|ageSeconds)\":\\d+", "\"$1\":<n>");
    }

    /** The first difference, with context. */
    static String diff(String a, String b) {
        a = normalize(a); b = normalize(b);
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
        int from = Math.max(0, i - 160);
        return "   before: …" + a.substring(from, Math.min(a.length(), i + 240)) + "\n   after:  …" + b.substring(from, Math.min(b.length(), i + 240));
    }

    static String abbreviate(String s) {
        if (s == null) return "null";
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }
}
