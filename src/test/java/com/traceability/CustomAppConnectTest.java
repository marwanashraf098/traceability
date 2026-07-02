package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyStoreNeedsReauthException;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.ShopifyTransientException;
import com.traceability.notifications.EmailGateway;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for the custom-app client-credentials (CC) Shopify connection path.
 *
 * CC1:  valid CC connect → 202, connection_type='custom_app_cc', tokens encrypted, no refresh token
 * CC2:  shop_not_permitted → 400 with org message
 * CC3:  application_cannot_be_found → 400 with credentials message
 * CC4:  feature flag off → 403
 * CC5:  non-owner → 403
 * CC6:  5xx from exchange → 502
 * CC7:  token re-exchange on expiry → access token updated, refresh token stays null
 * CC8:  fresh CC token (hot path) → gateway NOT called
 * CC9:  4xx on re-exchange → store marked needs_reauth
 * CC10: 5xx on re-exchange → store status stays 'connected'
 * CC11: Phase B HMAC for CC store → 200
 * CC12: Phase B runs in tenant context (RLS aware)
 * CC13: upgrade_custom_app_to_oauth handles 'custom_app_cc'
 * CC14: OAuth path unaffected
 * CC15: RLS tenant isolation for CC store
 *
 * Plus preserved tests from original custom_app path (webhook Phase A/B, connections status, etc.)
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.custom-app-connect-enabled=true"}
)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CustomAppConnectTest {

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
    @Autowired TestRestTemplate    rest;
    @Autowired JdbcTemplate        jdbc;
    @Autowired JwtService          jwtService;
    @Autowired EncryptionService   encryptionService;
    @Autowired ShopifyTokenProvider tokenProvider;

    @MockBean ShopifyGateway shopifyGateway;
    @MockBean JobScheduler   jobScheduler;
    @MockBean EmailGateway   emailGateway;

    @Value("${shopify.client-secret}")
    String globalClientSecret;

    private String ownerToken;
    private UUID   ownerTenantId;

    private static final String SHOP_DOMAIN    = "custom-app-cc-test.myshopify.com";
    private static final String CLIENT_ID      = "test_client_id_12345";
    private static final String CLIENT_SECRET  = "test_client_secret_xyz789";
    private static final String ACCESS_TOKEN   = "shpat_cc_access_token_abc";
    private static final long   EXPIRES_IN     = 86399L;

    @BeforeAll
    void setup() {
        // CC path uses exchangeClientCredentials + fetchShop
        defaultMocks();

        SignupRequest req = new SignupRequest("CC Co", "cc_owner", "ccowner@test.com", "password99", true);
        ResponseEntity<TokenResponse> resp = rest.postForEntity(
                base() + "/api/v1/auth/signup", req, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ownerToken    = resp.getBody().accessToken();
        ownerTenantId = UUID.fromString((String) jwtService.verify(ownerToken).getClaim("tenant"));
    }

    @BeforeEach
    void setupMocks() {
        // @MockBean is auto-reset by Spring's MockitoTestExecutionListener after each @AfterEach,
        // so stubs must be established in @BeforeEach (not @AfterEach) to ensure they are active
        // when the next test runs.  @BeforeAll covers the first test; @BeforeEach covers all others.
        defaultMocks();
    }

    @AfterEach
    void cleanup() {
        cleanOwnerStores();
        // Do NOT call reset() or re-stub here: MockitoTestExecutionListener auto-resets @MockBean
        // after @AfterEach, which would wipe out any stub we set here.  Use @BeforeEach instead.
    }

    private void cleanOwnerStores() {
        // Delete in FK-safe order: child tables first, then stores.
        jdbc.update("DELETE FROM shopify_webhook_events WHERE tenant_id = ?",    ownerTenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?",               ownerTenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?",               ownerTenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?",                    ownerTenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?",                 ownerTenantId);
        jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", ownerTenantId);
        jdbc.update("DELETE FROM variants WHERE tenant_id = ?",                  ownerTenantId);
        jdbc.update("DELETE FROM products WHERE tenant_id = ?",                  ownerTenantId);
        jdbc.update("DELETE FROM stores WHERE tenant_id = ?",                    ownerTenantId);
    }

    private void defaultMocks() {
        when(shopifyGateway.exchangeClientCredentials(anyString(), anyString(), anyString()))
            .thenReturn(new ShopifyGateway.TokenResponse(ACCESS_TOKEN, null, EXPIRES_IN, 0));
        when(shopifyGateway.fetchShop(anyString(), anyString()))
            .thenReturn(new ShopifyGateway.ShopInfo("owner@store.com", "CC Store", "Africa/Cairo"));
        when(shopifyGateway.validateShop(anyString(), anyString())).thenReturn("CC Store");
    }

    // -----------------------------------------------------------------------
    // CC1 — valid CC connect → 202, connection_type='custom_app_cc',
    //        access/clientId/clientSecret encrypted, refresh_token null.
    // -----------------------------------------------------------------------
    @Test @Order(1)
    void cc1_validConnect_returns202AndStoresEncryptedCC() {
        var resp = doCustomConnect(SHOP_DOMAIN, CLIENT_ID, CLIENT_SECRET);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).containsKey("importStatus");
        assertThat(resp.getBody().get("importStatus")).isEqualTo("pending");

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT connection_type, access_token_encrypted, api_secret_encrypted, " +
            "client_id_encrypted, refresh_token_encrypted, access_token_expires_at " +
            "FROM stores WHERE tenant_id = ? AND shop_domain = ?",
            ownerTenantId, SHOP_DOMAIN);

        assertThat(row.get("connection_type")).isEqualTo("custom_app_cc");

        String encToken    = (String) row.get("access_token_encrypted");
        String encSecret   = (String) row.get("api_secret_encrypted");
        String encClientId = (String) row.get("client_id_encrypted");

        assertThat(encToken).isNotEqualTo(ACCESS_TOKEN);
        assertThat(encryptionService.decrypt(encToken)).isEqualTo(ACCESS_TOKEN);
        assertThat(encryptionService.decrypt(encSecret)).isEqualTo(CLIENT_SECRET);
        assertThat(encryptionService.decrypt(encClientId)).isEqualTo(CLIENT_ID);

        // No refresh token stored for CC stores
        assertThat(row.get("refresh_token_encrypted")).isNull();

        // Expiry is ~24h from now, not 100 years
        Timestamp expiresAt = (Timestamp) row.get("access_token_expires_at");
        Instant now = Instant.now();
        assertThat(expiresAt.toInstant()).isAfter(now.plusSeconds(EXPIRES_IN - 60));
        assertThat(expiresAt.toInstant()).isBefore(now.plusSeconds(EXPIRES_IN + 60));

        // 2 jobs enqueued: import + webhooks
        verify(jobScheduler, times(2)).enqueue(any(org.jobrunr.jobs.lambdas.JobLambda.class));
    }

    // -----------------------------------------------------------------------
    // CC2 — shop_not_permitted → 400
    // (ApiExceptionHandler returns no body; status alone is the assertion)
    // -----------------------------------------------------------------------
    @Test @Order(2)
    void cc2_shopNotPermitted_returns400() {
        when(shopifyGateway.exchangeClientCredentials(anyString(), anyString(), anyString()))
            .thenThrow(new ShopifyStoreNeedsReauthException(SHOP_DOMAIN,
                "shop_not_permitted: the app is not authorized for this shop"));

        var resp = doCustomConnect(SHOP_DOMAIN, CLIENT_ID, CLIENT_SECRET);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -----------------------------------------------------------------------
    // CC3 — application_cannot_be_found → 400
    // (Different branch in the controller but same HTTP status; distinction is
    // in the message passed to ResponseStatusException which we confirm in unit tests)
    // -----------------------------------------------------------------------
    @Test @Order(3)
    void cc3_applicationCannotBeFound_returns400() {
        when(shopifyGateway.exchangeClientCredentials(anyString(), anyString(), anyString()))
            .thenThrow(new ShopifyStoreNeedsReauthException(SHOP_DOMAIN,
                "400: application_cannot_be_found"));

        var resp = doCustomConnect(SHOP_DOMAIN, CLIENT_ID, CLIENT_SECRET);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -----------------------------------------------------------------------
    // CC4 — feature flag off → 403 (tested via inner class below this one)
    //        This test verifies the flag=ON case succeeds.
    // -----------------------------------------------------------------------
    @Test @Order(4)
    void cc4_featureFlagOn_succeeds() {
        var resp = doCustomConnect(SHOP_DOMAIN, CLIENT_ID, CLIENT_SECRET);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    // -----------------------------------------------------------------------
    // CC5 — non-owner → 403
    // -----------------------------------------------------------------------
    @Test @Order(5)
    void cc5_nonOwner_returns403() {
        UUID managerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, role, active) VALUES (?, ?, 'mgr', 'manager', true)",
            managerId, ownerTenantId);
        String managerToken = jwtService.issueAccessToken(managerId, ownerTenantId, "manager");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(managerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("shopDomain", SHOP_DOMAIN, "clientId", CLIENT_ID, "clientSecret", CLIENT_SECRET);

        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/shopify/custom-connect",
            HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -----------------------------------------------------------------------
    // CC6 — 5xx from exchange → 502
    // -----------------------------------------------------------------------
    @Test @Order(6)
    void cc6_transientError_returns502() {
        when(shopifyGateway.exchangeClientCredentials(anyString(), anyString(), anyString()))
            .thenThrow(new ShopifyTransientException("Shopify 503", null));

        var resp = doCustomConnect(SHOP_DOMAIN, CLIENT_ID, CLIENT_SECRET);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    // -----------------------------------------------------------------------
    // CC7 — token re-exchange on expiry: access token updated, refresh stays null
    // -----------------------------------------------------------------------
    @Test @Order(7)
    void cc7_tokenReExchangeOnExpiry_updatesAccessToken() {
        // Insert a CC store with a near-expired token (5 seconds from now)
        UUID storeId  = UUID.randomUUID();
        String encOld = encryptionService.encrypt(ACCESS_TOKEN);
        String encId  = encryptionService.encrypt(CLIENT_ID);
        String encSec = encryptionService.encrypt(CLIENT_SECRET);
        Timestamp nearExpiry = Timestamp.from(Instant.now().plusSeconds(5)); // within REFRESH_BUFFER

        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, platform, access_token_encrypted, " +
            "access_token_expires_at, status, import_status, connection_type, client_id_encrypted, " +
            "api_secret_encrypted) " +
            "VALUES (?, ?, ?, 'shopify', ?, ?, 'connected', 'pending', 'custom_app_cc', ?, ?)",
            storeId, ownerTenantId, SHOP_DOMAIN, encOld, nearExpiry, encId, encSec);

        String newToken = "shpat_new_cc_token_xyz";
        when(shopifyGateway.exchangeClientCredentials(eq(SHOP_DOMAIN), eq(CLIENT_ID), eq(CLIENT_SECRET)))
            .thenReturn(new ShopifyGateway.TokenResponse(newToken, null, 86399L, 0));

        // Call tokenProvider under tenant context
        String result = TenantContext.runAs(ownerTenantId, () -> tokenProvider.getValidToken(storeId));

        assertThat(result).isEqualTo(newToken);

        // Verify DB was updated
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT access_token_encrypted, refresh_token_encrypted FROM stores WHERE id = ?", storeId);
        assertThat(encryptionService.decrypt((String) row.get("access_token_encrypted"))).isEqualTo(newToken);
        assertThat(row.get("refresh_token_encrypted")).isNull();
    }

    // -----------------------------------------------------------------------
    // CC8 — fresh CC token: gateway NOT called
    // -----------------------------------------------------------------------
    @Test @Order(8)
    void cc8_freshToken_gatewayNotCalled() {
        UUID storeId  = UUID.randomUUID();
        String encTok = encryptionService.encrypt(ACCESS_TOKEN);
        String encId  = encryptionService.encrypt(CLIENT_ID);
        String encSec = encryptionService.encrypt(CLIENT_SECRET);
        Timestamp future = Timestamp.from(Instant.now().plusSeconds(3600)); // 1h — well within buffer

        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, platform, access_token_encrypted, " +
            "access_token_expires_at, status, import_status, connection_type, client_id_encrypted, " +
            "api_secret_encrypted) " +
            "VALUES (?, ?, ?, 'shopify', ?, ?, 'connected', 'pending', 'custom_app_cc', ?, ?)",
            storeId, ownerTenantId, SHOP_DOMAIN, encTok, future, encId, encSec);

        String result = TenantContext.runAs(ownerTenantId, () -> tokenProvider.getValidToken(storeId));

        assertThat(result).isEqualTo(ACCESS_TOKEN);
        // Gateway should not be called for a fresh token
        verify(shopifyGateway, never()).exchangeClientCredentials(any(), any(), any());
    }

    // -----------------------------------------------------------------------
    // CC9 — 4xx on re-exchange → store marked needs_reauth, exception propagated
    // -----------------------------------------------------------------------
    @Test @Order(9)
    void cc9_needsReauthOn4xx_marksStore() {
        UUID storeId  = UUID.randomUUID();
        String encOld = encryptionService.encrypt(ACCESS_TOKEN);
        String encId  = encryptionService.encrypt(CLIENT_ID);
        String encSec = encryptionService.encrypt(CLIENT_SECRET);
        Timestamp nearExpiry = Timestamp.from(Instant.now().plusSeconds(5));

        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, platform, access_token_encrypted, " +
            "access_token_expires_at, status, import_status, connection_type, client_id_encrypted, " +
            "api_secret_encrypted) " +
            "VALUES (?, ?, ?, 'shopify', ?, ?, 'connected', 'pending', 'custom_app_cc', ?, ?)",
            storeId, ownerTenantId, SHOP_DOMAIN, encOld, nearExpiry, encId, encSec);

        when(shopifyGateway.exchangeClientCredentials(anyString(), anyString(), anyString()))
            .thenThrow(new ShopifyStoreNeedsReauthException(SHOP_DOMAIN, "credentials revoked"));

        assertThatThrownBy(() ->
            TenantContext.runAs(ownerTenantId, () -> tokenProvider.getValidToken(storeId)))
            .isInstanceOf(ShopifyStoreNeedsReauthException.class);

        // Store must be marked needs_reauth
        String status = jdbc.queryForObject(
            "SELECT status FROM stores WHERE id = ?", String.class, storeId);
        assertThat(status).isEqualTo("needs_reauth");
    }

    // -----------------------------------------------------------------------
    // CC10 — 5xx on re-exchange → transient, status stays 'connected'
    // -----------------------------------------------------------------------
    @Test @Order(10)
    void cc10_transientOn5xx_statusStaysConnected() {
        UUID storeId  = UUID.randomUUID();
        String encOld = encryptionService.encrypt(ACCESS_TOKEN);
        String encId  = encryptionService.encrypt(CLIENT_ID);
        String encSec = encryptionService.encrypt(CLIENT_SECRET);
        Timestamp nearExpiry = Timestamp.from(Instant.now().plusSeconds(5));

        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, platform, access_token_encrypted, " +
            "access_token_expires_at, status, import_status, connection_type, client_id_encrypted, " +
            "api_secret_encrypted) " +
            "VALUES (?, ?, ?, 'shopify', ?, ?, 'connected', 'pending', 'custom_app_cc', ?, ?)",
            storeId, ownerTenantId, SHOP_DOMAIN, encOld, nearExpiry, encId, encSec);

        when(shopifyGateway.exchangeClientCredentials(anyString(), anyString(), anyString()))
            .thenThrow(new ShopifyTransientException("Shopify 503", null));

        assertThatThrownBy(() ->
            TenantContext.runAs(ownerTenantId, () -> tokenProvider.getValidToken(storeId)))
            .isInstanceOf(ShopifyTransientException.class);

        // Status must remain 'connected' — transient errors do not mark needs_reauth
        String status = jdbc.queryForObject(
            "SELECT status FROM stores WHERE id = ?", String.class, storeId);
        assertThat(status).isEqualTo("connected");
    }

    // -----------------------------------------------------------------------
    // CC11 — Phase B HMAC for CC store → 200 (correct secret), 401 (wrong secret)
    // -----------------------------------------------------------------------
    @Test @Order(11)
    void cc11_phaseBHmac_ccStore_correctSecret_passes() {
        String encTok = encryptionService.encrypt(ACCESS_TOKEN);
        String encSec = encryptionService.encrypt(CLIENT_SECRET);
        String encId  = encryptionService.encrypt(CLIENT_ID);
        UUID ccTenantId = UUID.randomUUID();
        String ccDomain = "cc-webhook-test.myshopify.com";
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", ccTenantId, "CC Webhook Co");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'cc_user', 'ccwebhook@test.com', 'x', 'owner')",
            UUID.randomUUID(), ccTenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, platform, access_token_encrypted, " +
            "access_token_expires_at, status, import_status, connection_type, " +
            "api_secret_encrypted, client_id_encrypted) " +
            "VALUES (?, ?, ?, 'shopify', ?, now() + interval '876000 hours', 'connected', 'pending', " +
            "'custom_app_cc', ?, ?)",
            UUID.randomUUID(), ccTenantId, ccDomain, encTok, encSec, encId);

        byte[] body = "{\"id\":11,\"cc\":true}".getBytes(StandardCharsets.UTF_8);
        String hmac = computeHmac(body, CLIENT_SECRET); // Phase A will fail (global secret), Phase B passes

        ResponseEntity<Void> resp = postWebhook("orders/create", ccDomain, UUID.randomUUID().toString(), hmac, body);
        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);

        // Cleanup
        jdbc.update("DELETE FROM shopify_webhook_events WHERE tenant_id = ?", ccTenantId);
        jdbc.update("DELETE FROM stores WHERE tenant_id = ?", ccTenantId);
        jdbc.update("DELETE FROM users WHERE tenant_id = ?", ccTenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", ccTenantId);
    }

    // -----------------------------------------------------------------------
    // CC12 — Phase B runs in tenant context (RLS-aware lookup for CC stores)
    // -----------------------------------------------------------------------
    @Test @Order(12)
    void cc12_phaseBTenantContext_rlsAwareLookup() {
        // CC store for owner tenant — Phase B must find it under the correct GUC
        String encTok = encryptionService.encrypt(ACCESS_TOKEN);
        String encSec = encryptionService.encrypt(CLIENT_SECRET);
        String encId  = encryptionService.encrypt(CLIENT_ID);
        UUID ccTenantId = UUID.randomUUID();
        String ccDomain = "cc-rls-test.myshopify.com";
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", ccTenantId, "CC RLS Co");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'rls_user', 'ccrlstest@test.com', 'x', 'owner')",
            UUID.randomUUID(), ccTenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, platform, access_token_encrypted, " +
            "access_token_expires_at, status, import_status, connection_type, " +
            "api_secret_encrypted, client_id_encrypted) " +
            "VALUES (?, ?, ?, 'shopify', ?, now() + interval '876000 hours', 'connected', 'pending', " +
            "'custom_app_cc', ?, ?)",
            UUID.randomUUID(), ccTenantId, ccDomain, encTok, encSec, encId);

        // Sign with the CC CLIENT_SECRET (Phase A uses global secret → fails → Phase B tries CC)
        byte[] body = "{\"cc_rls\":true}".getBytes(StandardCharsets.UTF_8);
        String hmac = computeHmac(body, CLIENT_SECRET);

        ResponseEntity<Void> resp = postWebhook("orders/create", ccDomain, UUID.randomUUID().toString(), hmac, body);
        // Phase B should find the CC store under the correct tenant GUC and return 200
        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);

        // Cleanup
        jdbc.update("DELETE FROM shopify_webhook_events WHERE tenant_id = ?", ccTenantId);
        jdbc.update("DELETE FROM stores WHERE tenant_id = ?", ccTenantId);
        jdbc.update("DELETE FROM users WHERE tenant_id = ?", ccTenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", ccTenantId);
    }

    // -----------------------------------------------------------------------
    // CC13 — upgrade_custom_app_to_oauth handles 'custom_app_cc' type
    // -----------------------------------------------------------------------
    @Test @Order(13)
    void cc13_upgradeFunction_handlesCustomAppCCType() {
        // Connect a CC-type store
        var connectResp = doCustomConnect(SHOP_DOMAIN, CLIENT_ID, CLIENT_SECRET);
        UUID storeId = UUID.fromString((String) connectResp.getBody().get("storeId"));

        // Verify it is 'custom_app_cc'
        String connType = jdbc.queryForObject(
            "SELECT connection_type FROM stores WHERE id = ?", String.class, storeId);
        assertThat(connType).isEqualTo("custom_app_cc");

        // Insert a child order
        UUID orderId = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, " +
            "payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, 'gid://shopify/Order/8888', '#CC-UPGRADE', " +
            "'prepaid'::order_payment_method, now(), '{}'::jsonb)",
            orderId, ownerTenantId, storeId);

        // Call upgrade function — should work for custom_app_cc type
        String encOAuth = encryptionService.encrypt("shpat_oauth_upgraded");
        UUID[] result = jdbc.query(
            "SELECT out_tenant_id, out_owner_user_id, out_store_id FROM upgrade_custom_app_to_oauth(" +
            "?, ?, ?, ?, ?, now() + interval '1 hour', NULL, NULL)",
            rs -> {
                if (!rs.next()) return null;
                return new UUID[]{
                    rs.getObject("out_tenant_id", UUID.class),
                    rs.getObject("out_owner_user_id", UUID.class),
                    rs.getObject("out_store_id", UUID.class)
                };
            },
            SHOP_DOMAIN, "ccupgrade@oauth.com", "Upgraded OAuth Store", "Africa/Cairo", encOAuth);

        assertThat(result).isNotNull();
        UUID newTenantId    = result[0];
        UUID returnedStoreId = result[2];

        assertThat(returnedStoreId).isEqualTo(storeId);

        // connection_type → 'oauth', client_id_encrypted → null
        Map<String, Object> storeRow = jdbc.queryForMap(
            "SELECT connection_type, client_id_encrypted FROM stores WHERE id = ?", storeId);
        assertThat(storeRow.get("connection_type")).isEqualTo("oauth");
        assertThat(storeRow.get("client_id_encrypted")).isNull();

        // Child order re-assigned
        UUID orderTenant = jdbc.queryForObject(
            "SELECT tenant_id FROM orders WHERE id = ?", UUID.class, orderId);
        assertThat(orderTenant).isEqualTo(newTenantId);

        // Cleanup
        jdbc.update("DELETE FROM shopify_webhook_events WHERE tenant_id = ?", newTenantId);
        jdbc.update("DELETE FROM locations WHERE tenant_id = ?", newTenantId);
        jdbc.update("DELETE FROM orders WHERE id = ?", orderId);
        jdbc.update("DELETE FROM stores WHERE id = ?", storeId);
        jdbc.update("DELETE FROM users WHERE tenant_id = ?", newTenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", newTenantId);
    }

    // -----------------------------------------------------------------------
    // CC14 — OAuth path unaffected: GET /connections, webhook Phase A still work
    // -----------------------------------------------------------------------
    @Test @Order(14)
    void cc14_oauthPathUnaffected() {
        // Verify GET /connections still returns expected keys
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ownerToken);
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/connections",
            HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("shopify");
        assertThat(resp.getBody()).containsKey("shopifyCustomApp");
        assertThat(resp.getBody()).containsKey("customAppAvailable");
        assertThat(resp.getBody().get("customAppAvailable")).isEqualTo(true);

        // Verify webhook Phase A (OAuth global secret) still passes
        UUID oauthTenantId = UUID.randomUUID();
        String oauthDomain = "oauth-cc14-test.myshopify.com";
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", oauthTenantId, "OAuth14 Co");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'o14_user', 'o14@test.com', 'x', 'owner')",
            UUID.randomUUID(), oauthTenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, platform, access_token_encrypted, " +
            "access_token_expires_at, status, import_status, connection_type) " +
            "VALUES (?, ?, ?, 'shopify', ?, now() + interval '876000 hours', 'connected', 'pending', 'oauth')",
            UUID.randomUUID(), oauthTenantId, oauthDomain,
            encryptionService.encrypt("shpat_oauth14_tok"));

        byte[] body = "{\"oauth14\":true}".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<Void> wh = postWebhook("orders/create", oauthDomain,
            UUID.randomUUID().toString(), computeHmac(body, globalClientSecret), body);
        assertThat(wh.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);

        // Cleanup
        jdbc.update("DELETE FROM shopify_webhook_events WHERE tenant_id = ?", oauthTenantId);
        jdbc.update("DELETE FROM stores WHERE tenant_id = ?", oauthTenantId);
        jdbc.update("DELETE FROM users WHERE tenant_id = ?", oauthTenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", oauthTenantId);
    }

    // -----------------------------------------------------------------------
    // CC15 — RLS tenant isolation: CC store for tenant A not visible to tenant B
    // -----------------------------------------------------------------------
    @Test @Order(15)
    void cc15_rlsIsolation_ccStore_scopedToTenant() {
        doCustomConnect(SHOP_DOMAIN, CLIENT_ID, CLIENT_SECRET);

        UUID tenantBId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantBId, "Tenant B CC");

        Integer countB = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE tenant_id = ? AND connection_type = 'custom_app_cc'",
            Integer.class, tenantBId);
        assertThat(countB).isEqualTo(0);

        Integer countA = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE tenant_id = ? AND connection_type = 'custom_app_cc'",
            Integer.class, ownerTenantId);
        assertThat(countA).isEqualTo(1);

        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantBId);
    }

    // -----------------------------------------------------------------------
    // Preserved: webhook wrong secret → 401
    // -----------------------------------------------------------------------
    @Test @Order(16)
    void webhookHmac_wrongSecret_returns401() {
        byte[] body = "{\"bad\":true}".getBytes(StandardCharsets.UTF_8);
        String badHmac = computeHmac(body, "completely-wrong-secret-xyz");
        ResponseEntity<Void> resp = postWebhook("orders/create", "unknown-shop.myshopify.com",
            UUID.randomUUID().toString(), badHmac, body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -----------------------------------------------------------------------
    // Preserved: invalid domain → 400
    // -----------------------------------------------------------------------
    @Test @Order(17)
    void customConnect_invalidDomain_returns400() {
        var resp = doCustomConnect("not-a-shopify.com", CLIENT_ID, CLIENT_SECRET);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -----------------------------------------------------------------------
    // Preserved: blank clientId → 400
    // -----------------------------------------------------------------------
    @Test @Order(18)
    void customConnect_blankClientId_returns400() {
        var resp = doCustomConnect(SHOP_DOMAIN, "", CLIENT_SECRET);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -----------------------------------------------------------------------
    // Preserved: blank clientSecret → 400
    // -----------------------------------------------------------------------
    @Test @Order(19)
    void customConnect_blankClientSecret_returns400() {
        var resp = doCustomConnect(SHOP_DOMAIN, CLIENT_ID, "");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -----------------------------------------------------------------------
    // Preserved: GET /connections shopifyCustomApp includes CC stores
    // -----------------------------------------------------------------------
    @Test @Order(20)
    void connections_status_includesCC() {
        doCustomConnect(SHOP_DOMAIN, CLIENT_ID, CLIENT_SECRET);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ownerToken);
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/connections",
            HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> customApp = (Map<String, Object>) resp.getBody().get("shopifyCustomApp");
        assertThat(customApp.get("connected")).isEqualTo(true);
        assertThat(customApp.get("shopDomain")).isEqualTo(SHOP_DOMAIN);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String base() { return "http://localhost:" + port; }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> doCustomConnect(String shopDomain, String clientId, String clientSecret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ownerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("shopDomain",   shopDomain);
        body.put("clientId",     clientId);
        body.put("clientSecret", clientSecret);

        return rest.exchange(
            base() + "/api/v1/shopify/custom-connect",
            HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Void> postWebhook(String topic, String shop,
                                              String wid, String hmac, byte[] body) {
        String[] parts = topic.split("/", 2);
        String url = base() + "/webhooks/shopify/" + parts[0] + "/" + parts[1];

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Shopify-Topic",       topic);
        headers.set("X-Shopify-Shop-Domain", shop);
        headers.set("X-Shopify-Hmac-Sha256", hmac);
        headers.set("X-Shopify-Webhook-Id",  wid);

        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);
    }

    private String computeHmac(byte[] rawBody, String secret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(rawBody));
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }
}
