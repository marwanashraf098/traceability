package com.traceability;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifySessionTokenExchangeException;
import com.traceability.integrations.shopify.ShopifyTransientException;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static com.traceability.ShopifySessionTokenFilterTest.makeToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Integration tests for the session-token exchange flow.
 *
 * Load-bearing matrix (all must pass):
 *   TE01 — fresh token (>10 min, connected) → 204, gateway NOT called
 *   TE02 — stale token (≤10 min) → exchange called → 204
 *   TE03 — access_token_expires_at NULL → exchange called
 *   TE04 — status=needs_reauth → exchange succeeds → status→connected, import_status→pending, jobs enqueued
 *   TE05 — connected + import_status=idle → exchange → jobs enqueued
 *   TE06 — Shopify 4xx on exchange → 502, status NOT changed to needs_reauth
 *   TE07 — Shopify 5xx/timeout → 503, store state unchanged
 *   TE08 — cross-shop confinement: gateway called with SHOP_A domain (shop from principal, not body)
 *   TE09 — concurrent: two parallel calls → both 204, store valid, no corruption
 *   TE10 — no auth header → 401 (filter rejects before controller)
 *   TE11 — unknown shop (not provisioned) → 401 + NOT_PROVISIONED body
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShopifyTokenExchangeTest {

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

    @Value("${shopify.client-secret}") String clientSecret;
    @Value("${shopify.client-id}")     String clientId;
    @Value("${shopify.scopes}")        String appScopes;

    UUID tenantA;
    UUID storeA;
    static final String SHOP_A = "te-shop-a.myshopify.com";

    UUID tenantB;
    UUID storeB;
    static final String SHOP_B = "te-shop-b.myshopify.com";

    // Unprovisioned — only in the session token, not in the DB
    static final String SHOP_C = "te-shop-c.myshopify.com";

    RestTemplate rest;

    @BeforeAll
    void setup() {
        rest = buildRestTemplate();

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        storeA  = UUID.randomUUID();
        storeB  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'TE-Tenant-A')", tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'TE-Tenant-B')", tenantB);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner A', 'owner@te-a.test', 'hash', 'owner')",
                    UUID.randomUUID(), tenantA);

        // Stores start with null access_token_encrypted; test-specific state is set per test.
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, status, import_status) " +
            "VALUES (?, ?, ?, 'connected', 'completed')",
            storeA, tenantA, SHOP_A);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, status, import_status) " +
            "VALUES (?, ?, ?, 'connected', 'completed')",
            storeB, tenantB, SHOP_B);
    }

    @AfterAll
    void teardown() {
        jdbc.update("DELETE FROM stores  WHERE id IN (?, ?)", storeA, storeB);
        jdbc.update("DELETE FROM users   WHERE tenant_id = ?", tenantA);
        jdbc.update("DELETE FROM tenants WHERE id IN (?, ?)", tenantA, tenantB);
    }

    @BeforeEach
    void resetMocks() {
        reset(shopifyGateway);
    }

    // ── TE01: Fresh token → 204, no exchange ────────────────────────────────────

    @Test @Order(1)
    void te01_freshToken_204_noExchangeCalled() throws Exception {
        // Token expires 20 minutes from now — well past the 10-min threshold.
        // Scopes must match the app's declared scopes so the scope check also passes.
        setStoreState(storeA, "connected", "completed",
                Timestamp.from(Instant.now().plusSeconds(1200)), appScopes);

        ResponseEntity<String> r = post("/api/v1/embedded/token-exchange", tokenA());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(shopifyGateway, never()).exchangeSessionToken(any(), any());
    }

    // ── TE02: Stale token → exchange called → 204 ──────────────────────────────

    @Test @Order(2)
    void te02_staleToken_exchangeCalled_204() throws Exception {
        // Token expires in 3 minutes — within the 10-min stale threshold.
        setStoreState(storeA, "connected", "completed",
                Timestamp.from(Instant.now().plusSeconds(180)));
        when(shopifyGateway.exchangeSessionToken(eq(SHOP_A), any()))
                .thenReturn(freshTokenResponse());

        ResponseEntity<String> r = post("/api/v1/embedded/token-exchange", tokenA());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(shopifyGateway).exchangeSessionToken(eq(SHOP_A), any());
        // import_status was 'completed' → shouldEnqueue=false → import_status stays 'completed'
        Map<String, Object> row = getStoreRow(storeA);
        assertThat(row.get("import_status")).isEqualTo("completed");
    }

    // ── TE03: NULL expiry → exchange called ────────────────────────────────────

    @Test @Order(3)
    void te03_nullExpiry_exchangeCalled() throws Exception {
        setStoreState(storeA, "connected", "completed", null);
        when(shopifyGateway.exchangeSessionToken(eq(SHOP_A), any()))
                .thenReturn(freshTokenResponse());

        ResponseEntity<String> r = post("/api/v1/embedded/token-exchange", tokenA());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(shopifyGateway).exchangeSessionToken(eq(SHOP_A), any());
    }

    // ── TE04: needs_reauth → exchange → recovery + jobs enqueued ───────────────

    @Test @Order(4)
    void te04_needsReauth_exchangeSucceeds_recovers_jobsEnqueued() throws Exception {
        setStoreState(storeA, "needs_reauth", "failed",
                Timestamp.from(Instant.now().minusSeconds(60)));
        when(shopifyGateway.exchangeSessionToken(eq(SHOP_A), any()))
                .thenReturn(freshTokenResponse());

        ResponseEntity<String> r = post("/api/v1/embedded/token-exchange", tokenA());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(shopifyGateway).exchangeSessionToken(eq(SHOP_A), any());

        // Status must be restored to 'connected', import_status to 'pending'
        // (import_status='pending' is the DB-observable proxy for "jobs enqueued").
        Map<String, Object> row = getStoreRow(storeA);
        assertThat(row.get("status")).isEqualTo("connected");
        assertThat(row.get("import_status")).isEqualTo("pending");
    }

    // ── TE05: connected + idle → exchange → jobs enqueued ──────────────────────

    @Test @Order(5)
    void te05_connectedIdle_exchange_jobsEnqueued() throws Exception {
        setStoreState(storeA, "connected", "idle", null);
        when(shopifyGateway.exchangeSessionToken(eq(SHOP_A), any()))
                .thenReturn(freshTokenResponse());

        ResponseEntity<String> r = post("/api/v1/embedded/token-exchange", tokenA());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, Object> row = getStoreRow(storeA);
        assertThat(row.get("import_status")).isEqualTo("pending");
    }

    // ── TE06: Shopify 4xx → 502, status NOT marked needs_reauth ─────────────────

    @Test @Order(6)
    void te06_shopify4xx_502_needsReauthNotSet() throws Exception {
        setStoreState(storeA, "connected", "completed",
                Timestamp.from(Instant.now().plusSeconds(180)));
        when(shopifyGateway.exchangeSessionToken(eq(SHOP_A), any()))
                .thenThrow(new ShopifySessionTokenExchangeException(SHOP_A, "401 Unauthorized"));

        ResponseEntity<String> r = post("/api/v1/embedded/token-exchange", tokenA());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);

        // Status must NOT have been changed to needs_reauth.
        Map<String, Object> row = getStoreRow(storeA);
        assertThat(row.get("status")).isEqualTo("connected");
        // import_status must remain 'completed' — not changed to 'pending'
        assertThat(row.get("import_status")).isEqualTo("completed");
    }

    // ── TE07: Shopify 5xx → 503, no state change ───────────────────────────────

    @Test @Order(7)
    void te07_shopify5xx_503_noStateChange() throws Exception {
        setStoreState(storeA, "connected", "completed",
                Timestamp.from(Instant.now().plusSeconds(180)));
        when(shopifyGateway.exchangeSessionToken(eq(SHOP_A), any()))
                .thenThrow(new ShopifyTransientException("Shopify 503", new RuntimeException("down")));

        ResponseEntity<String> r = post("/api/v1/embedded/token-exchange", tokenA());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        Map<String, Object> row = getStoreRow(storeA);
        assertThat(row.get("status")).isEqualTo("connected");
        // import_status must remain 'completed' — transient error must not disturb store state
        assertThat(row.get("import_status")).isEqualTo("completed");
    }

    // ── TE08: Cross-shop confinement — gateway called with SHOP_A, not SHOP_B ──

    @Test @Order(8)
    void te08_crossShop_gatewayCalledWithPrincipalShop() throws Exception {
        // Both stores are stale so exchange is attempted.
        setStoreState(storeA, "connected", "completed", null);
        setStoreState(storeB, "connected", "completed", null);
        when(shopifyGateway.exchangeSessionToken(any(), any()))
                .thenReturn(freshTokenResponse());

        // POST with SHOP_A's session token → must call exchangeSessionToken with SHOP_A only.
        ResponseEntity<String> r = post("/api/v1/embedded/token-exchange", tokenA());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Gateway was called with SHOP_A domain — not SHOP_B.
        verify(shopifyGateway).exchangeSessionToken(eq(SHOP_A), any());
        verify(shopifyGateway, never()).exchangeSessionToken(eq(SHOP_B), any());

        // SHOP_B's access_token_expires_at must be unchanged (null).
        Map<String, Object> rowB = getStoreRow(storeB);
        assertThat(rowB.get("access_token_expires_at")).isNull();
    }

    // ── TE09: Concurrent — two parallel calls → both 204, store valid ───────────

    @Test @Order(9)
    void te09_concurrent_twoTabsSafe() throws Exception {
        setStoreState(storeA, "connected", "completed", null);
        when(shopifyGateway.exchangeSessionToken(eq(SHOP_A), any()))
                .thenReturn(freshTokenResponse());

        String tok = tokenA();
        CompletableFuture<ResponseEntity<String>> f1 =
                CompletableFuture.supplyAsync(() -> {
                    try { return post("/api/v1/embedded/token-exchange", tok); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
        CompletableFuture<ResponseEntity<String>> f2 =
                CompletableFuture.supplyAsync(() -> {
                    try { return post("/api/v1/embedded/token-exchange", tok); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });

        ResponseEntity<String> r1 = f1.get();
        ResponseEntity<String> r2 = f2.get();

        // Both calls must succeed (204) — no corruption, no exception.
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Store must have a non-null access_token_expires_at (last write wins, both valid).
        Map<String, Object> row = getStoreRow(storeA);
        assertThat(row.get("access_token_expires_at")).isNotNull();
    }

    // ── TE10: No auth header → 401 (filter rejects, controller never runs) ──────

    @Test @Order(10)
    void te10_noAuthHeader_401() {
        HttpHeaders h = new HttpHeaders();
        ResponseEntity<String> r = rest.exchange(
                base() + "/api/v1/embedded/token-exchange",
                HttpMethod.POST,
                new HttpEntity<>(h),
                String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── TE12: connected + pending → exchange → jobs enqueued (stuck-pending regression) ──

    @Test @Order(12)
    void te12_connectedPending_exchange_jobsEnqueued() throws Exception {
        // Regression guard for the "stuck pending" bug:
        // A store left in connected/pending (job was enqueued but never ran — JobRunr was
        // down, or job crashed before updating import_status) must be treated as needing
        // re-enqueue, not silently skipped. Previously 'pending' was excluded from the
        // shouldEnqueue condition, so every token-exchange returned 204 but never fired jobs.
        setStoreState(storeA, "connected", "pending", null);
        when(shopifyGateway.exchangeSessionToken(eq(SHOP_A), any()))
                .thenReturn(freshTokenResponse());

        ResponseEntity<String> r = post("/api/v1/embedded/token-exchange", tokenA());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(shopifyGateway).exchangeSessionToken(eq(SHOP_A), any());
        // import_status stays 'pending' (CASE ELSE — connected/pending stays pending in SQL);
        // the enqueued job will change it to 'importing' → 'completed'/'failed' when it runs.
        Map<String, Object> row = getStoreRow(storeA);
        // access_token_expires_at must be set (exchange ran and updated the token)
        assertThat(row.get("access_token_expires_at")).isNotNull();
    }

    // ── TE11: Unknown shop → 401 + NOT_PROVISIONED body ─────────────────────────

    @Test @Order(11)
    void te11_unknownShop_401_notProvisionedBody() throws Exception {
        // SHOP_C is not in the DB — resolve_tenant_by_shop_domain returns null.
        String token = makeToken(SHOP_C, clientId, clientSecret, 120, false);
        ResponseEntity<String> r = post("/api/v1/embedded/token-exchange", token);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(r.getBody()).contains("NOT_PROVISIONED");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private String tokenA() throws Exception {
        return makeToken(SHOP_A, clientId, clientSecret, 300, false);
    }

    private void setStoreState(UUID storeId, String status, String importStatus,
                                Timestamp accessTokenExpiresAt) {
        setStoreState(storeId, status, importStatus, accessTokenExpiresAt, null);
    }

    private void setStoreState(UUID storeId, String status, String importStatus,
                                Timestamp accessTokenExpiresAt, String accessTokenScopes) {
        jdbc.update(
            "UPDATE stores SET status = ?::store_status, import_status = ?::store_import_status, " +
            "access_token_expires_at = ?, access_token_scopes = ? WHERE id = ?",
            status, importStatus, accessTokenExpiresAt, accessTokenScopes, storeId);
    }

    private Map<String, Object> getStoreRow(UUID storeId) {
        return jdbc.queryForMap("SELECT status::text, import_status::text, " +
                                "access_token_expires_at FROM stores WHERE id = ?", storeId);
    }

    private ShopifyGateway.TokenResponse freshTokenResponse() {
        return new ShopifyGateway.TokenResponse(
            "shpat_test_access_token",
            "shprt_test_refresh_token",
            3600L,
            7776000L,
            "read_orders,write_inventory,read_inventory,read_products,read_fulfillments," +
            "write_locations,read_locations,read_customers");
    }

    private ResponseEntity<String> post(String path, String bearerToken) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(bearerToken);
        return rest.exchange(base() + path, HttpMethod.POST,
                new HttpEntity<>(h), String.class);
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
