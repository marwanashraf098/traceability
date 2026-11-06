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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shopify-native returns S3a — runtime backstop for the merchant_orders / warehouse_orders split (V216; the static
 * guards are OrdersTableAccessGuardTest and WarehouseOrdersAccessGuardTest). A Shopify-native exchange shadow order
 * (external_id 'internal:shopify-exchange:', origin 'shopify_exchange', number '#…-X1') is a WAREHOUSE job, never a
 * sale (decision C1): Pick & Pack, waybills, pickups, committed demand and warehouse alerts see it; the Orders list,
 * Analytics, Overview order counts, the embedded app, the portal lookup, Main reconcile and the not-traced / Bosta
 * matching jobs don't.
 *
 * Two tenants, each with normal orders (positive controls) and two pickable-shaped shadow orders seeded AFTER a
 * baseline of every LIST / INCLUDE endpoint (the same endpoint registry as PortalOrderLeakTest — a copy, so the two
 * tests can't drift silently: a_ fails when the endpoint set differs from the app's):
 * <ul>
 *   <li>LIST / INCLUDE, default HIDE — called as owner AND worker before and after the shadow rows exist: no shadow
 *       sentinel, and byte-identical to the baseline (counts and totals can't move);</li>
 *   <li>{@link #SHOWS} — warehouse screens (each with a reason): the owner's body MUST change to include tenant A's own
 *       shadow work and never tenant B's;</li>
 *   <li>{@link #SHADOW_BY_ID} — GETs by order id: tenant A's own shadow answers 200 with its number, tenant B's shadow
 *       404 (cross-tenant), tenant A's normal order 200 (same-tenant control);</li>
 *   <li>every other BY_ORDER_ID (writes) — S3b / S3c decide (409 "Managed in Shopify", HELD_FOR_RELEASE); here only:
 *       the OTHER tenant's shadow is never touched.</li>
 * </ul>
 * Non-endpoint work runs directly: detectors, the not-traced tagger, the Bosta candidate sets, the order reconcile
 * job, the pickable filter on both views, committed stock, the portal lookup's order match and Main reconcile's
 * exchange hold.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class ShadowOrderLeakTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
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
        reg("GET /api/v1/orders/counts",                 list("", "?days=all"));
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
        reg("GET /api/v1/embedded/setup",                embedded());
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
        reg("GET /api/v1/fulfill/packed",                list());
        reg("GET /api/v1/fulfill/{orderId}",             byOrder());
        reg("POST /api/v1/fulfill/{orderId}/unpack-order", byOrder());
        // Warehouse places S3 — display-only places (PickPlacesService, merchant_orders; 404 without places).
        reg("GET /api/v1/fulfill/{orderId}/places",      byOrder());
        reg("POST /api/v1/fulfill/places/variants",      skip("counts pieces per variant id — no order is read, nothing per order is returned"));
        reg("POST /api/v1/fulfill/places/orders",        skip("reads only merchant_orders by id; an id outside it is left out (PickPlacesTest c2)"));
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
        // Non-Bosta couriers G2 — Settings → Couriers.
        reg("GET /api/v1/settings/couriers",             skip("tenant settings (switch, default carrier, carrier names) — no order data"));
        reg("PUT /api/v1/settings/couriers",             skip("tenant settings write — no order data returned"));
        reg("GET /api/v1/settings/couriers/catch-up/preview", skip("counts only; candidates are read from merchant_orders (GenericCatchUp.ROWS_SQL)"));
        reg("POST /api/v1/settings/couriers/test-waybill", skip("one scanned value; order numbers come only from joins on merchant_orders (TestWaybillService)"));
        // Non-Bosta couriers G3 — the Orders list's carrier filter and Mark delivered.
        reg("GET /api/v1/generic-shipments/carriers",    skip("carrier names + counts only; legs joined to merchant_orders (GenericDeliveryService)"));
        reg("POST /api/v1/generic-shipments/mark-delivered", skip("order ids the caller sends; each leg is read through merchant_orders, a portal row is NO_GENERIC_SHIPMENT"));
        reg("POST /api/v1/label-codes/batches",           skip("label codes only; no order or piece data"));
        reg("GET /api/v1/label-codes/batches/{id}/pdf",   skip("label codes only; no order or piece data"));
        reg("POST /api/v1/label-codes/void",              skip("label codes only; no order or piece data"));
        reg("POST /api/v1/receiving/sessions/{sessionId}/import/preview", skip("receiving import: variants and receipt lines only, no order data"));
        reg("POST /api/v1/receiving/sessions/{sessionId}/import/apply",   skip("receiving import: variants and receipt lines only, no order data"));
        reg("GET /api/v1/receiving/import/template",                     skip("receiving import: variants and receipt lines only, no order data"));
        // S1b: one Return To Receive source by its own id (return leg / untracked unit / request item) — a return of a
        // portal pre-connect order must resolve too (returns MUST include them); never a list of orders.
        reg("GET /api/v1/receiving/return-source",                       skip("returns: one return source by its own id, portal orders included by design"));
        for (String k : List.of("GET /api/v1/tag-stock", "POST /api/v1/tag-stock/sessions", "GET /api/v1/tag-stock/sessions/{id}",
                "POST /api/v1/tag-stock/sessions/{id}/print", "POST /api/v1/tag-stock/sessions/{id}/replacement",
                "POST /api/v1/tag-stock/sessions/{id}/scan", "POST /api/v1/tag-stock/sessions/{id}/end",
                "POST /api/v1/tag-stock/sessions/{id}/void-unused",
                "POST /api/v1/tag-stock/sessions/{id}/reprint", "POST /api/v1/tag-stock/leftovers/{variantId}/pdf",
                "POST /api/v1/tag-stock/sessions/{id}/print-many", "POST /api/v1/tag-stock/sessions/{id}/pdf")) {
            reg(k, skip("Tag stock: variants, untagged pieces and label codes at the main warehouse; no order data"));
        }
        // Warehouse places S1 — places (zones / racks / shelves / boxes), their piece counts and stickers; no order data.
        for (String k : List.of("GET /api/v1/places", "GET /api/v1/places/{id}", "POST /api/v1/places",
                "POST /api/v1/places/bulk/preview", "POST /api/v1/places/bulk", "PUT /api/v1/places/{id}",
                "PUT /api/v1/places/{id}/parent", "PUT /api/v1/places/{id}/purpose", "POST /api/v1/places/{id}/archive",
                "POST /api/v1/places/stickers", "POST /api/v1/places/stickers/plan")) {
            reg(k, skip("warehouse places: places, piece counts per place and stickers; no order data"));
        }
        // Warehouse places S2 — put away: waiting pieces, places, put-away sessions and Lookup's Move. The waiting
        // list's "came from" names a return's order NUMBER only (via merchant_orders, so never a portal row).
        for (String k : List.of("GET /api/v1/put-away/summary", "GET /api/v1/put-away/waiting",
                "GET /api/v1/put-away/places", "POST /api/v1/put-away/sessions", "GET /api/v1/put-away/sessions",
                "GET /api/v1/put-away/sessions/{id}",
                "POST /api/v1/put-away/sessions/{id}/scan", "POST /api/v1/put-away/sessions/{id}/close",
                "PUT /api/v1/put-away/pieces/{pieceId}/place")) {
            reg(k, skip("warehouse places: pieces and places; a return's order number via merchant_orders only"));
        }
        // Warehouse places S4 — put-away lists of a receipt / Tag stock session / return session / transfer:
        // pieces, variants and places by those ids; no order is read or returned.
        for (String k : List.of("GET /api/v1/put-away/lists/{source}/{sourceId}",
                "POST /api/v1/put-away/lists/{source}/{sourceId}/sessions", "GET /api/v1/put-away/sessions/{id}/list")) {
            reg(k, skip("warehouse places S4: pieces and places of a receipt / tag session / return session / transfer; no order data"));
        }
        // Warehouse places S5 — one-place counts, "Where is it", Put away › Move a box: pieces, variants and places;
        // no order is read or returned.
        for (String k : List.of("POST /api/v1/place-counts", "GET /api/v1/place-counts/{id}",
                "POST /api/v1/place-counts/{id}/scan", "POST /api/v1/place-counts/{id}/finish",
                "POST /api/v1/place-counts/{id}/cancel", "GET /api/v1/places/where/{variantId}",
                "POST /api/v1/put-away/box-moves/box", "POST /api/v1/put-away/box-moves")) {
            reg(k, skip("warehouse places S5: place counts, where a variant is, box moves; no order data"));
        }
        // Places rollout — Settings › Places "Use places in this store": the tenant's own switch (tenants row).
        reg("GET /api/v1/places/settings", skip("the tenant's own places switch and whether a shelf / box exists — no order rows"));
        reg("PUT /api/v1/places/settings", skip(R_CONFIG));
        // Flexible labeling slice 4 — packing labels (a portal order is ORDER_NOT_PACKABLE: PackLabelsTest.x2).
        reg("POST /api/v1/pack-labels/preview",     skip("orders come from PackPrintBatchStore.candidates / the last batch / one order id, each read through merchant_orders"));
        // Flexible labeling slice 6a — factory runs: runs, run lines, variants and factory label codes; no order data.
        for (String k : List.of("GET /api/v1/label-runs", "POST /api/v1/label-runs", "GET /api/v1/label-runs/{id}",
                "POST /api/v1/label-runs/{id}/print", "GET /api/v1/label-runs/{id}/pdf", "POST /api/v1/label-runs/import/preview")) {
            reg(k, skip("factory runs: runs, run lines, variants and factory label codes; no order data"));
        }
        // Flexible labeling slice 6c — closing a run and its shortfall report: run lines and factory codes; no order data.
        for (String k : List.of("POST /api/v1/label-runs/{id}/close", "GET /api/v1/label-runs/{id}/report/csv",
                "GET /api/v1/label-runs/{id}/report/pdf")) {
            reg(k, skip("factory run close / shortfall report: run lines, variants and factory label codes; no order data"));
        }
        // Flexible labeling slice 6b — factory receiving: staged scans, factory label codes, run lines; no order data.
        for (String k : List.of("POST /api/v1/receiving/sessions/{id}/factory/scan", "POST /api/v1/receiving/sessions/{id}/factory/undo",
                "POST /api/v1/receiving/sessions/{id}/factory/labels", "GET /api/v1/receiving/sessions/{id}/factory/labels/{codeId}/pdf")) {
            reg(k, skip("factory receiving: staged scans, factory label codes and run lines; no order data"));
        }
        reg("GET /api/v1/label-settings",           skip("tenant setting (receiving default mode) — no order data"));
        reg("PUT /api/v1/label-settings",           skip("tenant setting (receiving default mode) — no order data"));
        reg("POST /api/v1/pack-labels/plan",        skip("counts only: PackPrintBatchStore.candidates / queueOrderIds / the last batch, each read through merchant_orders"));
        reg("POST /api/v1/pack-labels/print",       skip("orders come from PackPrintBatchStore.candidates / the last batch / one order id, each read through merchant_orders"));
        reg("POST /api/v1/pack-labels/pdf",         skip("label PDF (binary) for order ids the caller sends; codes joined to merchant_orders"));
        reg("POST /api/v1/pack-labels/replacement", skip("label PDF (binary) for one order line, read through merchant_orders"));
        reg("POST /api/v1/fulfill/print-jobs",           skip("starts a print job; its waybills come only from PackPrintBatchStore.candidates "
                                                              + "(merchant_orders) or an existing batch's reprint items — no order query of its own"));
        reg("GET /api/v1/fulfill/print-jobs/latest",     list());
        reg("GET /api/v1/fulfill/print-jobs/{jobId}",    skip("one print job by id — its stored items were taken from PackPrintBatchStore.candidates / reprint items"));
        reg("GET /api/v1/fulfill/print-jobs/{jobId}/pdf", skip("one print job's PDF by id (binary)"));
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
        reg("POST /api/v1/shopify/location/check",       skip(R_OPS));
        reg("POST /api/v1/shopify/location/resend-failed", skip(R_OPS));

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
        reg("GET /api/v1/exchanges",                     skip("exchanges domain (P4b): lists every exchanges row, including one Traced "
                                                              + "booked for a portal order's request — wanted, like any request's exchange; "
                                                              + "it carries the matched order's id only (no order fields, no link)"));
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
                                "switch-to-refund", "booking/book-now", "booking/confirm", "booking/not-booked", "booking/retry",
                                "manual-courier", "cancel-exchange")) {
            reg("POST /api/v1/return-requests/{id}/" + a, skip(R_RETURNS));
        }
        reg("POST /api/v1/return-requests/{id}/refunds/{refundId}/void", skip(R_RETURNS));
        reg("POST /api/v1/return-requests/{id}/items/{itemId}/arrived",      skip(R_RETURNS));
        reg("POST /api/v1/return-requests/{id}/items/{itemId}/arrived/undo", skip(R_RETURNS));
        reg("PUT /api/v1/return-requests/{id}/pickup-area", skip(R_RETURNS));
        reg("PUT /api/v1/return-requests/{id}/amounts", skip(R_RETURNS));          // F2: fee override
        // C2 (2026-10-28): Lookup Exchange — the original-order search reads merchant_orders minus internal orders;
        // what can come back is by order id; the create takes the order in its body (merchant_orders → ORDER_NOT_FOUND
        // for a portal order — LookupExchangeTest).
        reg("GET /api/v1/lookup-exchange/orders",        list("?q=PPCLEAK", "?q=0109999990"));
        reg("GET /api/v1/lookup-exchange/orders/{orderId}/returnables", byOrder());
        reg("POST /api/v1/pieces/{id}/exchange",         skip("orderId in the body, read through merchant_orders "
            + "(LookupExchangeService.requireOriginalOrder) — a portal order is ORDER_NOT_FOUND, LookupExchangeTest"));
        reg("GET /api/v1/refunds",                       skip(R_RETURNS));
        reg("GET /api/v1/returns/pending",               skip(R_RETURNS));
        reg("GET /api/v1/returns/awaiting-scan",         skip(R_RETURNS));
        reg("GET /api/v1/returns/analytics",             skip(R_RETURNS));
        reg("GET /api/v1/returns/sessions",              skip(R_RETURNS));
        reg("GET /api/v1/returns/sessions/{sessionId}",  skip(R_RETURNS));
        reg("GET /api/v1/returns/pieces/{pieceId}/label",skip("label PDF of one piece"));
        reg("POST /api/v1/pieces/{id}/label/reprint",   skip("label PDF of one piece"));
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
        reg("GET /api/v1/portal/{slug}/catalogue",       skip(R_PORTAL));        // F3: other products
        reg("POST /api/v1/portal/{slug}/lookup",         skip(R_PORTAL));
        reg("POST /api/v1/portal/{slug}/photos",         skip(R_PORTAL));
        reg("POST /api/v1/portal/{slug}/requests",       skip(R_PORTAL));
        reg("POST /api/v1/portal/{slug}/quote",          skip(R_PORTAL));        // F2: fee quote
        reg("GET /api/v1/tenant/portal-settings",        list());
        reg("PUT /api/v1/tenant/portal-settings",        skip(R_CONFIG));
        reg("GET /api/v1/tenant/portal-settings/logo",   list());
        reg("PUT /api/v1/tenant/portal-settings/logo",   skip(R_CONFIG));
        reg("DELETE /api/v1/tenant/portal-settings/logo",skip(R_CONFIG));
        // Non-Bosta couriers G4a — the "accept returns for other couriers" switch: the tenant's own flags, no order rows.
        reg("GET /api/v1/tenant/portal-settings/other-couriers", skip("the tenant's own two switches (tenants row) — no order rows"));
        reg("PUT /api/v1/tenant/portal-settings/other-couriers", skip(R_CONFIG));
        reg("GET /api/v1/tenant/portal-settings/customer-fees", skip("the tenant's own fee rules, area overrides and settings — no order rows"));
        reg("GET /api/v1/tenant/portal-settings/customer-fees/areas", skip("Bosta city / district names (global reference data) — no order rows"));
        reg("PUT /api/v1/tenant/portal-settings/customer-fees/rules/{kind}", skip(R_CONFIG));
        reg("PUT /api/v1/tenant/portal-settings/customer-fees/merchant-fault-reasons", skip(R_CONFIG));
        reg("PUT /api/v1/tenant/portal-settings/customer-fees/exchange-pricing", skip(R_CONFIG));
        reg("POST /api/v1/tenant/portal-settings/customer-fees/area-overrides", skip(R_CONFIG));
        reg("PUT /api/v1/tenant/portal-settings/customer-fees/area-overrides/{id}", skip(R_CONFIG));
        reg("DELETE /api/v1/tenant/portal-settings/customer-fees/area-overrides/{id}", skip(R_CONFIG));
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
                "GET /api/v1/stock-takes/sessions/{sessionId}/untagged-counts",
                "PUT /api/v1/stock-takes/sessions/{sessionId}/untagged-counts/{variantId}",
                "GET /api/v1/transfers/{transferId}", "POST /api/v1/transfers",
                "POST /api/v1/transfers/{transferId}/begin-reconcile", "POST /api/v1/transfers/{transferId}/cancel",
                "POST /api/v1/transfers/{transferId}/classify", "POST /api/v1/transfers/{transferId}/close",
                "POST /api/v1/transfers/{transferId}/close-one-way", "POST /api/v1/transfers/{transferId}/mark-sent",
                "POST /api/v1/transfers/{transferId}/reprint-outstanding", "POST /api/v1/transfers/{transferId}/return-scan-out",
                "POST /api/v1/transfers/{transferId}/scan-back", "POST /api/v1/transfers/{transferId}/scan-out",
                "POST /api/v1/pieces/{id}/adjust", "POST /api/v1/pieces/{id}/hold", "POST /api/v1/pieces/{id}/release-for-adjust",
                "POST /api/v1/pieces/{id}/restore", "POST /api/v1/pieces/{id}/unhold", "POST /api/v1/pieces/{id}/void",
                // C1 (2026-10-25): Lookup Sold / Not sold — one piece by id, no order data.
                "POST /api/v1/pieces/{id}/sold", "POST /api/v1/pieces/{id}/not-sold")) {
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
        reg("POST /api/v1/shopify/custom-app-deletion/confirm", skip(R_CONFIG));
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
        reg("GET /api/v1/setup/address",                 list());
        reg("PUT /api/v1/setup/address",                 skip(R_CONFIG));
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
        reg("POST /api/v1/ops/receipt-reversal",          skip(R_OPS));   // one-off, removed after the run
        reg("GET /api/v1/ops/receipt-reversal",           skip(R_OPS));
        reg("POST /api/v1/admin/incidents/2212102474-pick-error/reverse", skip("one fixed incident order by number — reads merchant_orders"));
        reg("GET /api/v1/test/probe",                    skip("test-only probe controller (src/test/java)"));
    }

    // ── shadow classification (on top of the shared registry) ─────────────────

    /**
     * LIST / INCLUDE endpoints that are warehouse screens: a pickable shadow order IS their work (S3a decision C1).
     * Each must change once the shadows exist (proves the switch to warehouse_orders took) and never show tenant B's.
     */
    static final Map<String, String> SHOWS = new LinkedHashMap<>();
    static {
        SHOWS.put("GET /api/v1/fulfill/queue",        "Pick & Pack queue (FulfillService.getQueue on warehouse_orders)");
        SHOWS.put("GET /api/v1/fulfill/gather",       "gather list of the queue's orders");
        SHOWS.put("GET /api/v1/overview/late-to-pack","late to pack is a warehouse metric (Q8, approved 2026-11-06)");
        SHOWS.put("GET /api/v1/embedded/overview/late-to-pack", "the same late-to-pack metric inside the embedded app (Q8)");
        SHOWS.put("GET /api/v1/inventory/stock",      "committed demand (VariantStockService): a shadow's replacement is kept aside");
        SHOWS.put("GET /api/v1/inventory/variants/{variantId}/breakdown", "committed demand of the variant (as above)");
        SHOWS.put("GET /api/v1/catalog",              "committed demand in the catalog (as above)");
    }

    /** GETs by order id that open a shadow order (Q3: the drawer opens for Pick & Pack / Lookup links; Pick & Pack). */
    static final Set<String> SHADOW_BY_ID = Set.of(
        "GET /api/v1/orders/{orderId}", "GET /api/v1/orders/{orderId}/timeline", "GET /api/v1/orders/{orderId}/notes",
        "GET /api/v1/fulfill/{orderId}");

    // ── per-tenant fixture ────────────────────────────────────────────────────

    static final class T {
        final String tag;          // "A" / "B"
        String ownerToken, workerToken, shop;
        UUID tenantId, ownerId, workerId, storeId, variantId, pieceId, locationId, controlOrder, controlDelivered;
        UUID exchangeId, packSessionId, requestId;
        String pieceBarcode;
        long unlinkedId;
        final List<UUID> shadows = new ArrayList<>();
        UUID pickableShadow, legShadow, heldShadow, deliveredShadow;
        T(String tag) { this.tag = tag; }
        String n() { return "A".equals(tag) ? "1" : "2"; }
        String phone() { return "0109777777" + n(); }
        String legTracking() { return "5554" + n() + "00006"; }
        /** Strings that appear only on this tenant's shadow orders. */
        List<String> sentinels() {
            List<String> s = new ArrayList<>(List.of("SHDLEAK" + tag, "Shadow Sentinel " + tag, phone(), "109777777" + n(),
                legTracking()));
            shadows.forEach(id -> s.add(id.toString()));
            return s;
        }
    }

    final T a = new T("A"), b = new T("B");

    /** LIST / INCLUDE baseline (before the shadow rows exist): key "METHOD path?query|role" → body. */
    final Map<String, String> baseline = new LinkedHashMap<>();

    @BeforeAll
    void setup() throws Exception {
        rest = restTemplate();
        for (T t : List.of(a, b)) seedTenant(t);
        for (T t : List.of(a, b)) seedControl(t);
        for (Map.Entry<String, Spec> e : REGISTRY.entrySet()) {
            if (e.getValue().kind() != Kind.LIST && e.getValue().kind() != Kind.INCLUDE) continue;
            for (String role : roles(e.getValue())) {
                for (String q : e.getValue().queries()) {
                    baseline.put(e.getKey() + q + "|" + role, call(e.getKey(), q, a, role, null).getBody());
                }
            }
        }
        for (T t : List.of(a, b)) seedShadows(t);
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
        assertThat(unclassified).as("every /api/** endpoint must be classified in ShadowOrderLeakTest.REGISTRY "
            + "(the same registry as PortalOrderLeakTest)").isEmpty();
        Set<String> stale = new TreeSet<>(REGISTRY.keySet());
        stale.removeAll(discovered);
        assertThat(stale).as("registry entries for endpoints that no longer exist").isEmpty();
        for (String k : SHOWS.keySet()) {
            assertThat(REGISTRY.get(k)).as(k).isNotNull();
            assertThat(REGISTRY.get(k).kind()).as(k).isIn(Kind.LIST, Kind.INCLUDE);
        }
        for (String k : SHADOW_BY_ID) assertThat(REGISTRY.get(k).kind()).as(k).isEqualTo(Kind.BY_ORDER_ID);
    }

    // ── 2. LIST / INCLUDE: hidden (byte-identical) unless a warehouse screen ──

    @Test
    void b_listEndpoints_hideShadowOrders_unlessAWarehouseScreen() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Spec> e : REGISTRY.entrySet()) {
            Spec s = e.getValue();
            if (s.kind() != Kind.LIST && s.kind() != Kind.INCLUDE) continue;
            boolean shows = SHOWS.containsKey(e.getKey());
            boolean changedForOwner = false;
            for (String role : roles(s)) {
                for (int i = 0; i < s.queries().size(); i++) {
                    String q = s.queries().get(i);
                    ResponseEntity<String> r = call(e.getKey(), q, a, role, null);
                    String body = Objects.toString(r.getBody(), "");
                    String before = baseline.get(e.getKey() + q + "|" + role);
                    for (T t : List.of(a, b)) {
                        if (shows && t == a) continue;
                        for (String sentinel : t.sentinels()) {
                            if (body.contains(sentinel) && !Objects.toString(before, "").contains(sentinel)) {
                                failures.add(e.getKey() + q + " as " + role + " (" + r.getStatusCode().value()
                                    + ") shows shadow sentinel '" + sentinel + "' of tenant " + t.tag);
                            }
                        }
                    }
                    boolean same = Objects.equals(normalize(before), normalize(body));
                    if (!shows && s.reason() == null && !same) {
                        failures.add(e.getKey() + q + " as " + role + " changed once shadow orders existed:\n" + diff(before, body));
                    }
                    if (!same && !"worker".equals(role)) changedForOwner = true;
                    if (s.control() && i == 0 && "owner".equals(role) && !body.contains("CTL" + a.tag + "1001")) {
                        failures.add(e.getKey() + q + " positive control missing: #CTL" + a.tag + "1001 ("
                            + r.getStatusCode().value() + ")");
                    }
                }
            }
            if (shows && !changedForOwner) {
                failures.add(e.getKey() + " is a warehouse screen (" + SHOWS.get(e.getKey()) + ") but did not change once "
                    + "tenant A's pickable shadow orders existed — is it still reading merchant_orders?");
            }
        }
        assertThat(failures).as("shadow orders must stay out of sales screens and show on warehouse screens").isEmpty();
    }

    // ── 3. BY_ORDER_ID ────────────────────────────────────────────────────────

    /** Q3: a shadow's drawer / Pick & Pack order opens for its own tenant, 404 for another; normal order = control. */
    @Test
    void c_getByOrderId_ownShadowOpens_otherTenantsShadow404() {
        List<String> failures = new ArrayList<>();
        for (String key : SHADOW_BY_ID) {
            ResponseEntity<String> control = call(key, "", a, "owner", a.controlOrder);
            if (control.getStatusCode().value() != 200) failures.add(key + " control order → " + control.getStatusCode().value());
            for (UUID own : List.of(a.pickableShadow, a.legShadow)) {
                ResponseEntity<String> r = call(key, "", a, "owner", own);
                if (r.getStatusCode().value() != 200) {
                    failures.add(key + " own shadow → " + r.getStatusCode().value() + ": " + abbreviate(r.getBody()));
                }
            }
            String orderBody = Objects.toString(call(key, "", a, "owner", a.pickableShadow).getBody(), "");
            if (!key.endsWith("/notes") && !key.endsWith("/timeline") && !orderBody.contains("SHDLEAKA1-X1")) {
                failures.add(key + " own shadow body has no number: " + abbreviate(orderBody));
            }
            for (UUID other : b.shadows) {
                ResponseEntity<String> r = call(key, "", a, "owner", other);
                if (r.getStatusCode().value() != 404) {
                    failures.add(key + " tenant B's shadow as tenant A → " + r.getStatusCode().value());
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    /** Every by-order write, as tenant A on tenant B's shadow: the same answer as a missing order, B's rows unchanged. */
    @Test
    void d_writesByOrderId_neverReachAnotherTenantsShadow() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Spec> e : REGISTRY.entrySet()) {
            if (e.getValue().kind() != Kind.BY_ORDER_ID) continue;
            for (UUID other : b.shadows) {
                String before = shadowState(b);
                UUID missing = UUID.randomUUID();
                ResponseEntity<String> none = call(e.getKey(), "", a, "owner", missing);
                ResponseEntity<String> r = call(e.getKey(), "", a, "owner", other);
                if (r.getStatusCode().value() != none.getStatusCode().value()) {
                    failures.add(e.getKey() + " with tenant B's shadow → " + r.getStatusCode().value()
                        + ", with a missing order → " + none.getStatusCode().value());
                }
                String after = shadowState(b);
                if (!before.equals(after)) failures.add(e.getKey() + " CHANGED tenant B's shadow " + other);
            }
        }
        assertThat(failures).as("tenant A must never reach tenant B's shadow orders").isEmpty();
    }

    // ── 4. non-endpoint work ──────────────────────────────────────────────────

    /** Detector types that may name a shadow order: warehouse / courier / returns alerts (warehouse_orders or raw orders). */
    static final Set<String> SHADOW_OK_TYPES = Set.of(
        "lost", "pack_set_aside", "pack_cancelled_after_print", "restocked_twice", "never_received",
        "generic_bosta_conflict_at_pack", "tracking_changed", "superseded_waybill_moving", "stuck", "guided_unpack",
        "cancelled_with_live_shipment", "generic_cancelled_live_shipment", "generic_delivered_without_pickup",
        "waybill_deleted_in_bosta",
        "request_item_to_receive", "return_to_receive", "pickup_booking_problem", "return_link_ambiguous",
        "refund_pending_overdue", "return_items_overdue", "return_leg_unscanned", "return_in_transit_stuck",
        "unexpected_return", "delivery_limbo", "ndr", "missing_awb", "high_attempts", "missing_provider_id");

    /**
     * The held shadow is bait for every Shopify-signal / customer detector that stays on merchant_orders (Q5, Q6, Q9:
     * blocked customer, Shopify cancel vs in-flight, cancelled-but-delivered, edit conflict): none may name it.
     */
    @Test
    void e_jobs_salesSideDetectorsNeverRaiseOnShadowOrders() {
        for (T t : List.of(a, b)) {
            List<Map<String, Object>> found = TenantContext.runAs(t.tenantId, () -> exceptions.detectAllOpen());
            List<String> leaks = new ArrayList<>();
            for (Map<String, Object> ex : found) {
                String text = ex.toString();
                boolean shadow = t.sentinels().stream().anyMatch(text::contains);
                Object type = ex.getOrDefault("type", ex.get("exceptionType"));
                if (shadow && !SHADOW_OK_TYPES.contains(String.valueOf(type))) leaks.add(type + ": " + abbreviate(text));
            }
            assertThat(leaks).as("sales-side detectors must not see tenant " + t.tag + "'s shadow orders").isEmpty();
            assertThat(found.toString()).as("positive control: blocked normal order detected").contains("CTL" + t.tag + "1002");
        }
    }

    @Test
    void f_jobs_notTracedTagger_skipsShadowOrders() {
        TenantContext.runAs(a.tenantId, () -> { notTraced.maybeTagNotTraced(a.deliveredShadow, a.tenantId); return null; });
        assertThat(jdbc.queryForObject("SELECT not_traced_at FROM orders WHERE id = ?", Object.class, a.deliveredShadow))
            .as("Q8: a shadow is never tagged not-traced").isNull();
        UUID control = a.controlDelivered;
        TenantContext.runAs(a.tenantId, () -> { notTraced.maybeTagNotTraced(control, a.tenantId); return null; });
        assertThat(jdbc.queryForObject("SELECT not_traced_at FROM orders WHERE id = ?", Object.class, control)).isNotNull();
    }

    @Test
    void g_jobs_bostaCandidateSets_skipShadowOrders() throws Exception {
        for (T t : List.of(a, b)) {
            String before = shadowState(t);
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
            assertThat(shadowState(t)).as("order reconcile touched tenant " + t.tag + "'s shadow orders").isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT bosta_link_attempts FROM orders WHERE id = ?", Integer.class, t.controlOrder))
                .as("positive control: the reconcile job did process the normal order").isPositive();
        }
    }

    @Test
    void h_jobs_pickableFilterOnBothViews_andCommittedStockCountsShadows() throws Exception {
        Field f = Class.forName("com.traceability.inventory.FulfillService").getDeclaredField("PICKABLE_ORDERS_FILTER");
        f.setAccessible(true);
        String filter = (String) f.get(null);
        List<UUID> warehouse = jdbc.queryForList("SELECT o.id FROM warehouse_orders o " + filter, UUID.class, a.tenantId, 30);
        assertThat(warehouse).contains(a.controlOrder, a.pickableShadow, a.legShadow)
            .doesNotContain(a.heldShadow, a.deliveredShadow).doesNotContainAnyElementsOf(b.shadows);
        List<UUID> merchant = jdbc.queryForList("SELECT o.id FROM merchant_orders o " + filter, UUID.class, a.tenantId, 30);
        assertThat(merchant).contains(a.controlOrder).doesNotContainAnyElementsOf(a.shadows);

        Map<UUID, VariantStockService.VariantStock> stock = TenantContext.runAs(a.tenantId, () -> variantStock.computeAll());
        assertThat(variantStock.forVariant(stock, a.variantId).committed())
            .as("committed = the normal order's 1 unit + the two pickable shadows' 1 each (held / delivered shadows don't count)")
            .isEqualTo(3);
    }

    /** The portal lookup never matches a shadow, even with its exact number and phone (PortalService.findEligibleOrder). */
    @Test
    void i_portalLookup_neverMatchesAShadowOrder() throws Exception {
        Method m = Class.forName("com.traceability.portal.PortalService").getDeclaredMethod("findEligibleOrder",
            UUID.class, String.class, String.class, String.class);
        m.setAccessible(true);
        Object portal = applicationContext.getBean(Class.forName("com.traceability.portal.PortalService"));
        Object shadow = TenantContext.runAs(a.tenantId, () -> invoke(m, portal, a.tenantId, "#SHDLEAKA4-X1", "SHDLEAKA4-X1", a.phone()));
        assertThat(shadow).as("a delivered shadow, its exact number and phone").isNull();
        Object control = TenantContext.runAs(a.tenantId, () -> invoke(m, portal, a.tenantId, "#CTLA1004", "CTLA1004", "01044444444"));
        assertThat(control).as("positive control: the delivered normal order is found").isNotNull();
    }

    @Autowired org.springframework.context.ApplicationContext applicationContext;

    static Object invoke(Method m, Object target, Object... args) {
        try { return m.invoke(target, args); } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Main reconcile's interim exchange hold: any open shadow holds the tenant; a tenant with none is not held. */
    @Test
    void j_mainReconcileExchangeHold_seesOpenShadows() {
        for (T t : List.of(a, b)) {
            assertThat(jdbc.queryForObject(com.traceability.inventory.MainReconcileRules.EXCHANGE_HOLD_SQL, Boolean.class,
                t.tenantId, t.tenantId)).as("tenant " + t.tag + " has open shadow orders").isTrue();
        }
        UUID none = UUID.randomUUID();
        assertThat(jdbc.queryForObject(com.traceability.inventory.MainReconcileRules.EXCHANGE_HOLD_SQL, Boolean.class,
            none, none)).isFalse();
    }

    // ── seeding ───────────────────────────────────────────────────────────────

    void seedTenant(T t) throws Exception {
        String suffix = t.tag.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<TokenResponse> resp = rest.postForEntity(base() + "/api/v1/auth/signup",
            new SignupRequest("Shadow Co " + t.tag, "shadow_owner_" + t.tag, "shadow-" + suffix + "@test.com",
                "01012345678", "Password99!", true), TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        t.ownerToken = resp.getBody().accessToken();
        t.tenantId = UUID.fromString((String) jwt.verify(t.ownerToken).getClaim("tenant"));
        t.ownerId = jdbc.queryForObject("SELECT id FROM users WHERE tenant_id = ? AND role = 'owner'", UUID.class, t.tenantId);
        t.workerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Shadow Worker', ?, 'x', 'worker')",
            t.workerId, t.tenantId, "worker-" + suffix + "@test.com");
        t.workerToken = jwt.issueAccessToken(t.workerId, t.tenantId, "worker");

        t.shop = "shadow-" + suffix + ".myshopify.com";
        t.storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', NULL)", t.storeId, t.tenantId, t.shop);
        UUID productId = UUID.randomUUID();
        t.variantId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, ?, 'active')",
            productId, t.tenantId, t.storeId, "gid://shopify/Product/" + suffix, "Shadow Widget " + t.tag);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'Default', ?)",
            t.variantId, t.tenantId, productId, "gid://shopify/ProductVariant/" + suffix, "SHD-" + t.tag);
        t.locationId = jdbc.queryForObject("SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment = true LIMIT 1",
            UUID.class, t.tenantId);
        t.pieceId = UUID.randomUUID();
        t.pieceBarcode = "SHDPC" + t.tag + suffix.substring(2).replace("-", "");
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, ?, 'available'::piece_status, ?)",
            t.pieceId, t.tenantId, t.variantId, t.pieceBarcode, "S" + t.tag + suffix.substring(2, 7), t.locationId);
    }

    /** Normal (Shopify) orders: the positive controls (same shapes as PortalOrderLeakTest). */
    void seedControl(T t) {
        t.controlOrder = order(t, "gid://shopify/Order/" + t.tag + "1001", "#CTL" + t.tag + "1001", "Control Person " + t.tag,
            "01011111111", "new", true, false, null, "'shopify'", "now() - interval '1 day'", "'{}'::jsonb");
        item(t, t.controlOrder, 1);
        UUID blocked = order(t, "gid://shopify/Order/" + t.tag + "1002", "#CTL" + t.tag + "1002", "Control Blocked " + t.tag,
            "01022222222", "new", false, true, "Blocked customer", "'shopify'", "now() - interval '1 day'", "'{}'::jsonb");
        item(t, blocked, 1);
        UUID fulfilled = order(t, "gid://shopify/Order/" + t.tag + "1003", "#CTL" + t.tag + "1003", "Control Shipped " + t.tag,
            "01033333333", "ready_to_pick", false, false, null, "'shopify'", "now() - interval '1 day'",
            bostaFulfillmentRaw("5551" + t.n() + "00003"));
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, carrier_class, fulfillment_status) " +
            "VALUES (?, ?, ?, 'Bosta', 'bosta', 'success')", t.tenantId, fulfilled, "5551" + t.n() + "00003");
        // #CTL…1004: delivered (yesterday) forward shipment, no allocations — not-traced tagger + portal lookup controls.
        t.controlDelivered = order(t, "gid://shopify/Order/" + t.tag + "1004", "#CTL" + t.tag + "1004", "Control Delivered " + t.tag,
            "01044444444", "delivered", false, false, null, "'shopify'", "now() - interval '3 days'", "'{}'::jsonb");
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at) " +
            "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', now() - interval '1 day')",
            t.tenantId, t.controlDelivered, "5552" + t.n() + "00004");
        // A return request on the delivered normal order — the /return-requests/{id} INCLUDE endpoints act on it.
        t.requestId = jdbc.queryForObject("INSERT INTO return_requests (tenant_id, order_id, type, status, reference) " +
            "VALUES (?, ?, 'refund', 'approved', ?) RETURNING id", UUID.class, t.tenantId, t.controlDelivered, "RR-SHD" + t.tag);

        t.exchangeId = jdbc.queryForObject("INSERT INTO exchanges (tenant_id, tracking_number, status, raw) " +
            "VALUES (?, ?, 'needs_mapping', '{}'::jsonb) RETURNING id", UUID.class, t.tenantId, "TN-SHD-EXC-" + t.tag);
        t.unlinkedId = jdbc.queryForObject("INSERT INTO unlinked_bosta_deliveries (tenant_id, tracking_number, bosta_state_code, bosta_order_type, raw) " +
            "VALUES (?, ?, 10, 'SEND', '{\"type\": {\"code\": 10, \"value\": \"SEND\"}}'::jsonb) RETURNING id",
            Long.class, t.tenantId, "5553" + t.n() + "00005");
        ResponseEntity<String> ps = waybillMode(t, () -> exchange(HttpMethod.POST, "/api/v1/pack-sessions", t.ownerToken, null));
        assertThat(ps.getStatusCode().is2xxSuccessful()).as("open pack session: " + ps.getBody()).isTrue();
        Matcher m = Pattern.compile("\"(?:id|sessionId)\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(ps.getBody());
        assertThat(m.find()).as("pack session id in " + ps.getBody()).isTrue();
        t.packSessionId = UUID.fromString(m.group(1));
    }

    /**
     * Shadow orders (origin 'shopify_exchange'), each shaped to trip a different path if it leaked into a sales
     * screen — sentinel number / name / phone, placed yesterday (inside every default window).
     */
    void seedShadows(T t) {
        String raw = bostaFulfillmentRaw("5559" + t.n() + "00001");
        // 1: pickable (new, self-pickup), a Bosta fulfillment in raw + a tracking row (catch-up / visibility bait).
        t.pickableShadow = shadow(t, 1, "new", true, false, null, raw);
        item(t, t.pickableShadow, 1);
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, carrier_class, fulfillment_status) " +
            "VALUES (?, ?, ?, 'Bosta', 'bosta', 'success')", t.tenantId, t.pickableShadow, "5559" + t.n() + "00001");
        // 2: pickable through its 'created' Bosta forward leg (waybill / pickups / lookup-by-tracking).
        t.legShadow = shadow(t, 2, "new", false, false, null, "'{}'::jsonb");
        item(t, t.legShadow, 1);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'created'::shipment_internal_state, 'forward')", t.tenantId, t.legShadow, t.legTracking());
        // 3: held as blocked + Shopify edit conflict + Shopify cancel requested — bait for the sales-side detectors.
        t.heldShadow = shadow(t, 3, "awaiting_pickup", false, true, "Blocked customer", raw);
        item(t, t.heldShadow, 1);
        jdbc.update("UPDATE orders SET shopify_edit_conflict_at = now(), shopify_cancel_requested_at = now() WHERE id = ?", t.heldShadow);
        // 4: delivered forward leg, no allocations — not-traced tagger bait; portal lookup bait (number + phone).
        t.deliveredShadow = shadow(t, 4, "delivered", false, false, null, "'{}'::jsonb");
        item(t, t.deliveredShadow, 1);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, " +
            "    provider_delivery_id, delivered_at) VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', " +
            "    'shd-fwd', now() - interval '1 day')", t.tenantId, t.deliveredShadow, "5557" + t.n() + "00005");
    }

    UUID shadow(T t, int n, String status, boolean selfPickup, boolean onHold, String holdReason, String rawSql) {
        UUID id = order(t, "internal:shopify-exchange:" + UUID.randomUUID(), "#SHDLEAK" + t.tag + n + "-X1",
            "Shadow Sentinel " + t.tag, t.phone(), status, selfPickup, onHold, holdReason, "'shopify_exchange'",
            "now() - interval '1 day'", rawSql);
        t.shadows.add(id);
        return id;
    }

    UUID order(T t, String externalId, String number, String name, String phone, String status, boolean selfPickup,
               boolean onHold, String holdReason, String originSql, String placedSql, String rawSql) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, customer_name, customer_phone, status, " +
            "    is_self_pickup, on_hold, hold_reason, payment_method, cod_amount, placed_at, raw, origin) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?::order_status, ?, ?, ?, 'cod', 777, " + placedSql + ", " + rawSql + ", " +
            originSql + ") RETURNING id",
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

    /** The shadow orders and everything a write could add to / change on them. */
    String shadowState(T t) {
        return String.join("\n", jdbc.queryForList(
            "SELECT jsonb_build_object(" +
            "  'o', to_jsonb(o) - 'updated_at', " +
            "  'items', (SELECT jsonb_agg(to_jsonb(oi) ORDER BY oi.id) FROM order_items oi WHERE oi.order_id = o.id), " +
            "  'shipments', (SELECT jsonb_agg(to_jsonb(s) - 'updated_at' ORDER BY s.id) FROM shipments s WHERE s.order_id = o.id), " +
            "  'allocations', (SELECT count(*) FROM allocations al JOIN order_items oi ON oi.id = al.order_item_id WHERE oi.order_id = o.id), " +
            "  'notes', (SELECT count(*) FROM order_notes n WHERE n.order_id = o.id), " +
            "  'packSession', (SELECT count(*) FROM pack_session_orders p WHERE p.order_id = o.id), " +
            "  'pieceEvents', (SELECT count(*) FROM piece_events e WHERE e.order_id = o.id), " +
            "  'exchanges', (SELECT count(*) FROM exchanges x WHERE x.matched_order_id = o.id OR x.outbound_order_id = o.id) " +
            ")::text FROM orders o WHERE o.tenant_id = ? AND o.origin = 'shopify_exchange' ORDER BY o.id",
            String.class, t.tenantId));
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    List<String> roles(Spec s) {
        return s.auth() == Auth.EMBEDDED ? List.of("embedded") : List.of("owner", "worker");
    }

    ResponseEntity<String> call(String key, String query, T t, String role, UUID portalOrder) {
        // portalOrder = the order id put in {orderId} / {portal} (here: a shadow order id), null = the control order
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
