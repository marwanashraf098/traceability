package com.traceability;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyImportJob;
import com.traceability.notifications.EmailGateway;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.http.HttpClient;
import java.util.UUID;

import static com.traceability.ShopifySessionTokenFilterTest.makeToken;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the embedded Shopify dashboard (ShopifySessionTokenFilter +
 * EmbeddedController). All tests run against a real Postgres via Testcontainers and
 * the full Spring Boot filter chain.
 *
 * Load-bearing tests (must pass):
 *   E1 — Valid token → /embedded/inventory/summary → 200 (happy path)
 *   E2 — Valid token shop-A → /embedded/stores/status → shop-A data ONLY (CROSS-TENANT isolation)
 *   E3 — Valid token shop-B → /embedded/stores/status → shop-B data ONLY (CROSS-TENANT isolation)
 *   E4 — Valid Shopify token → POST /api/v1/exceptions/resolve → 401
 *         (ShopifyFilter skips non-embedded path; JwtFilter rejects Shopify token → 401)
 *   E5 — Valid Shopify token → POST /api/v1/bosta/pickup/schedule → 401 (same reason)
 *   E6 — Traced OWNER JWT → GET /embedded/inventory/summary → 403 (@PreAuthorize gate)
 *   E7 — Shopify token → GET /api/v1/orders → 401 (filter skips; JwtFilter rejects)
 *   E8 — Unknown shop (not in stores) → GET /embedded/inventory/summary → 401
 *   E9 — Valid token → /embedded/orders/daily-counts → 200
 *  E10 — Valid token → /embedded/exceptions → 200 + count:0 + empty list (zero-data guard)
 *  E11 — Valid token → /embedded/stores/status → 200
 *  E12 — Zero-data store → all four endpoints → 200 + empty/zero, NOT 500
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmbeddedIntegrationTest {

    @Container
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

    @MockBean EmailGateway     emailGateway;
    @MockBean ShopifyGateway   shopifyGateway;
    @MockBean JobScheduler     jobScheduler;
    @MockBean ShopifyImportJob importJob;

    @Value("${shopify.client-secret}") String clientSecret;
    @Value("${shopify.client-id}")     String clientId;

    // Tenant A — shop-a
    UUID tenantA;
    UUID ownerA;
    static final String SHOP_A = "embedded-test-a.myshopify.com";

    // Tenant B — shop-b (cross-tenant isolation)
    UUID tenantB;
    static final String SHOP_B = "embedded-test-b.myshopify.com";

    UUID storeAId;
    UUID storeBId;

    // Tenant C — not registered (unknown shop test)
    static final String SHOP_C = "embedded-test-c.myshopify.com";

    RestTemplate rest;

    @BeforeAll
    void setup() {
        rest = buildRestTemplate();

        tenantA = UUID.randomUUID();
        ownerA  = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'EmbeddedTestA')", tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'EmbeddedTestB')", tenantB);

        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Owner A', 'owner@embedded-a.test', 'hash', 'owner')",
            ownerA, tenantA);

        // shop-A: linked to tenantA
        storeAId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, status) VALUES (?, ?, ?, 'connected')",
            storeAId, tenantA, SHOP_A);

        // shop-B: linked to tenantB (cross-tenant isolation fixture)
        storeBId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, status) VALUES (?, ?, ?, 'connected')",
            storeBId, tenantB, SHOP_B);
    }

    @AfterAll
    void teardown() {
        jdbc.update("DELETE FROM orders  WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM stores  WHERE shop_domain IN (?, ?)", SHOP_A, SHOP_B);
        jdbc.update("DELETE FROM users   WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM tenants WHERE id IN (?, ?)", tenantA, tenantB);
    }

    // ── E1: Happy path — valid token → 200 ──────────────────────────────────

    @Test @Order(1)
    void e1_validToken_inventorySummary_200() throws Exception {
        ResponseEntity<String> r = get("/api/v1/embedded/inventory/summary", tokenA());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("groupA", "groupB");
    }

    // ── E2 + E3: Cross-tenant isolation (THE load-bearing test) ─────────────

    @Test @Order(2)
    void e2_shopAToken_storesStatus_containsOnlyShopA() throws Exception {
        ResponseEntity<String> r = get("/api/v1/embedded/stores/status", tokenA());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains(SHOP_A);
        assertThat(r.getBody()).doesNotContain(SHOP_B);
    }

    @Test @Order(3)
    void e3_shopBToken_storesStatus_containsOnlyShopB() throws Exception {
        ResponseEntity<String> r = get("/api/v1/embedded/stores/status", tokenB());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains(SHOP_B);
        assertThat(r.getBody()).doesNotContain(SHOP_A);
    }

    // ── E4: Shopify token on mutating endpoint → 401 ─────────────────────────
    // ShopifySessionTokenFilter.shouldNotFilter() skips non-embedded paths.
    // JwtAuthenticationFilter fails the Shopify token (wrong secret) → unauthenticated.
    // anyRequest().authenticated() → 401.

    @Test @Order(4)
    void e4_shopifyToken_postExceptionsResolve_401() throws Exception {
        HttpHeaders h = bearerHeaders(tokenA());
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = rest.exchange(
            base() + "/api/v1/exceptions/resolve", HttpMethod.POST,
            new HttpEntity<>("{\"exceptionType\":\"lost\",\"subjectKey\":\"x\",\"note\":\"\"}", h),
            String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test @Order(5)
    void e5_shopifyToken_postPickupSchedule_401() throws Exception {
        HttpHeaders h = bearerHeaders(tokenA());
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = rest.exchange(
            base() + "/api/v1/bosta/pickup/schedule", HttpMethod.POST,
            new HttpEntity<>("{\"scheduledDate\":\"2026-06-28\"}", h),
            String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── E6: Traced OWNER JWT → embedded endpoint → 403 ──────────────────────
    // JwtAuthenticationFilter authenticates → ROLE_OWNER in SecurityContext.
    // ShopifySessionTokenFilter: auth already set → passes through.
    // @PreAuthorize("hasRole('SHOPIFY_EMBEDDED')") → false → 403.

    @Test @Order(6)
    void e6_tracedOwnerJwt_embeddedEndpoint_403() throws Exception {
        String tracedJwt = buildTracedJwt();
        ResponseEntity<String> r = get("/api/v1/embedded/inventory/summary", tracedJwt);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── E7: Shopify token on non-embedded path → 401 ─────────────────────────

    @Test @Order(7)
    void e7_shopifyToken_nonEmbeddedPath_401() throws Exception {
        ResponseEntity<String> r = get("/api/v1/orders", tokenA());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── E8: Unknown shop (not in stores table) → 401 ─────────────────────────

    @Test @Order(8)
    void e8_unknownShop_401() throws Exception {
        String token = makeToken(SHOP_C, clientId, clientSecret, 120, false);
        ResponseEntity<String> r = get("/api/v1/embedded/inventory/summary", token);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── E9–E11: Remaining endpoints return 200 with valid token ──────────────

    @Test @Order(9)
    void e9_validToken_dailyCounts_200() throws Exception {
        ResponseEntity<String> r = get("/api/v1/embedded/orders/daily-counts", tokenA());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test @Order(10)
    void e10_validToken_exceptions_200() throws Exception {
        ResponseEntity<String> r = get("/api/v1/embedded/exceptions", tokenA());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Fresh store has no exceptions — must return 200 with count=0, not a 500.
        // (Bug: without tx.execute(), the tenants RLS query returned 0 rows →
        //  EmptyResultDataAccessException on app_user connections in production.)
        assertThat(r.getBody()).contains("\"count\":0");
        assertThat(r.getBody()).contains("\"exceptions\":[]");
    }

    // ── E12: All four endpoints handle zero-data store gracefully (200, not 500) ─

    @Test @Order(12)
    void e12_zeroData_allFourEndpoints_200_notError() throws Exception {
        // inventory/summary: a store with no pieces must return all-zero counts, not blow up.
        ResponseEntity<String> inv = get("/api/v1/embedded/inventory/summary", tokenA());
        assertThat(inv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inv.getBody()).contains("\"count\":0");

        // orders/daily-counts: no orders → every day count must be 0.
        ResponseEntity<String> dc = get("/api/v1/embedded/orders/daily-counts?days=7", tokenA());
        assertThat(dc.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 7 day-slots present, all with count:0 (generate_series + LEFT JOIN + COALESCE)
        assertThat(dc.getBody()).contains("\"count\":0");
        assertThat(dc.getBody()).doesNotContain("\"count\":1");

        // stores/status: list endpoint — always safe (queryForList returns empty list).
        ResponseEntity<String> ss = get("/api/v1/embedded/stores/status", tokenA());
        assertThat(ss.getStatusCode()).isEqualTo(HttpStatus.OK);

        // exceptions: zero exceptions → 200 + count=0 + empty list, NOT 500.
        // This is the regression guard for the EmptyResultDataAccessException bug
        // (EmbeddedController.exceptions() was missing the tx.execute() wrapper that
        // the other three endpoints have; without it the tenants RLS query returned 0 rows).
        ResponseEntity<String> ex = get("/api/v1/embedded/exceptions", tokenA());
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ex.getBody()).contains("\"count\":0");
        assertThat(ex.getBody()).contains("\"exceptions\":[]");
    }

    @Test @Order(11)
    void e11_validToken_storesStatus_200() throws Exception {
        ResponseEntity<String> r = get("/api/v1/embedded/stores/status", tokenA());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── E13: /orders/funnel — cross-tenant isolation (build-spec item 4) ────────
    // Tenant A gets 2 'new'-status orders placed today, tenant B gets 5 — under RLS
    // isolation, each token's funnel().newCount must equal ONLY that tenant's seeded
    // count, never the sum. This is the tenant-isolation "fail closed" proof for the
    // new /orders/funnel endpoint.

    @Test @Order(13)
    void e13_ordersFunnel_crossTenantIsolation() throws Exception {
        seedOrders(tenantA, storeAId, "new", "now()", 2, "EMB-FUNNEL-A");
        seedOrders(tenantB, storeBId, "new", "now()", 5, "EMB-FUNNEL-B");

        ResponseEntity<String> a = get("/api/v1/embedded/orders/funnel", tokenA());
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(a.getBody()).contains("\"newCount\":2");

        ResponseEntity<String> b = get("/api/v1/embedded/orders/funnel", tokenB());
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.getBody()).contains("\"newCount\":5");
    }

    // ── E14: /overview/late-to-pack — cross-tenant isolation ────────────────────
    // Tenant A: 1 pre-pack order placed 30h ago (overdue, not over48). Tenant B: 3
    // pre-pack orders placed 50h ago (overdue AND over48). Each token must see ONLY
    // its own tenant's counts.

    @Test @Order(14)
    void e14_lateToPack_crossTenantIsolation() throws Exception {
        seedOrders(tenantA, storeAId, "new", "now() - interval '30 hours'", 1, "EMB-LTP-A");
        seedOrders(tenantB, storeBId, "new", "now() - interval '50 hours'", 3, "EMB-LTP-B");

        ResponseEntity<String> a = get("/api/v1/embedded/overview/late-to-pack", tokenA());
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(a.getBody()).contains("\"overdue\":1", "\"over48\":0");

        ResponseEntity<String> b = get("/api/v1/embedded/overview/late-to-pack", tokenB());
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.getBody()).contains("\"overdue\":3", "\"over48\":3");
    }

    // ── E15: /orders/list — cross-tenant isolation ───────────────────────────────
    // Tenant A and tenant B each get one order with a distinctive order number.
    // Tenant A's token must see A's order number and NEVER B's, and vice versa.

    @Test @Order(15)
    void e15_ordersList_crossTenantIsolation() throws Exception {
        jdbc.update(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "customer_name, customer_phone, placed_at) " +
            "VALUES (?, ?, 'EXT-EMB-LIST-A', '#EMB-LIST-A', 'new'::order_status, " +
            "'Alice A', '01000000001', now())",
            tenantA, storeAId);
        jdbc.update(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "customer_name, customer_phone, placed_at) " +
            "VALUES (?, ?, 'EXT-EMB-LIST-B', '#EMB-LIST-B', 'new'::order_status, " +
            "'Bob B', '01000000002', now())",
            tenantB, storeBId);

        ResponseEntity<String> a = get("/api/v1/embedded/orders/list", tokenA());
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(a.getBody()).contains("#EMB-LIST-A", "Alice A");
        assertThat(a.getBody()).doesNotContain("#EMB-LIST-B", "Bob B");

        ResponseEntity<String> b = get("/api/v1/embedded/orders/list", tokenB());
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.getBody()).contains("#EMB-LIST-B", "Bob B");
        assertThat(b.getBody()).doesNotContain("#EMB-LIST-A", "Alice A");
    }

    // ── E16: /orders/list — proves OrderStatusDeriver actually ran server-side ──
    // A 'packed', no-shipment-linked order must come back with primaryKey=status.packed
    // AND fulfillmentKey=status.fulfilled. fulfillmentKey=status.fulfilled is NOT a raw
    // column value anywhere — it only exists if OrderStatusDeriver.derive()'s
    // packedConfirmed branch actually ran server-side. A naive passthrough of
    // orders.status would never produce it.

    @Test @Order(16)
    void e16_ordersList_derivesStatusServerSide_notClientReDerived() throws Exception {
        jdbc.update(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, 'EXT-EMB-DERIVE', '#EMB-DERIVE', 'packed'::order_status, now())",
            tenantA, storeAId);

        ResponseEntity<String> r = get("/api/v1/embedded/orders/list", tokenA());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("#EMB-DERIVE");
        assertThat(r.getBody()).contains("\"primaryKey\":\"status.packed\"");
        assertThat(r.getBody()).contains("\"fulfillmentKey\":\"status.fulfilled\"");
    }

    /** Seeds N orders of the given status/placed_at expression for one tenant/store. */
    private void seedOrders(UUID tenantId, UUID storeId, String status, String placedAtExpr,
                             int count, String numberPrefix) {
        for (int i = 0; i < count; i++) {
            jdbc.update(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
                "VALUES (?, ?, ?, ?, ?::order_status, " + placedAtExpr + ")",
                tenantId, storeId, "EXT-" + numberPrefix + "-" + i, "#" + numberPrefix + "-" + i, status);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String tokenA() throws Exception {
        return makeToken(SHOP_A, clientId, clientSecret, 300, false);
    }

    private String tokenB() throws Exception {
        return makeToken(SHOP_B, clientId, clientSecret, 300, false);
    }

    @Autowired
    com.traceability.identity.JwtService jwtSvc;

    /** Build a Traced-platform JWT for ownerA (ROLE_OWNER) signed with the Traced JWT secret. */
    private String buildTracedJwt() {
        return jwtSvc.issueAccessToken(ownerA, tenantA, "owner");
    }

    private ResponseEntity<String> get(String path, String bearerToken) {
        return rest.exchange(base() + path, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(bearerToken)), String.class);
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private String base() { return "http://localhost:" + port; }

    private static RestTemplate buildRestTemplate() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        RestTemplate rt = new RestTemplate(new JdkClientHttpRequestFactory(client));
        rt.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) { return false; }
        });
        return rt;
    }
}
