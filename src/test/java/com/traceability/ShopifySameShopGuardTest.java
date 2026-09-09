package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyImportJob;
import com.traceability.notifications.EmailGateway;
import com.traceability.security.EncryptionService;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * PART C — same-shop-only guard (FR: "a tenant is permanently bound to its original
 * shop_domain"). Two layers:
 *
 *   Layer 1 — POST /api/v1/shopify/oauth/initiate rejects pre-consent, before any state
 *             nonce is generated or written, when the tenant already owns a different shop.
 *   Layer 2 — ShopifyOAuthService.path1()'s owner==null branch is a write-site backstop:
 *             even if a state row for a mismatched shop somehow exists (initiate() bypassed),
 *             the callback still refuses to insert a second store row.
 *
 * Isolation-style tests: each test creates its own tenant to avoid cross-test bleed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifySameShopGuardTest {

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
    @Autowired TestRestTemplate  rest;
    @Autowired JdbcTemplate      jdbc;
    @Autowired JwtService        jwtService;
    @Autowired EncryptionService encryptionService;

    @MockBean ShopifyGateway   shopifyGateway;
    @MockBean JobScheduler     jobScheduler;
    @MockBean ShopifyImportJob importJob;
    @MockBean EmailGateway     emailGateway;

    @Value("${shopify.client-secret}")
    String clientSecret;

    private RestTemplate noRedirectRest;

    private static final String SHOP_X = "same-shop-guard-x.myshopify.com";
    private static final String SHOP_Y = "same-shop-guard-y.myshopify.com";

    @BeforeAll
    void setupClient() {
        HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        noRedirectRest = new RestTemplate(new JdkClientHttpRequestFactory(httpClient));
        noRedirectRest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) { return false; }
        });
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("DELETE FROM shopify_oauth_state WHERE shop_domain IN ('" + SHOP_X + "','" + SHOP_Y + "')");
        jdbc.execute("DELETE FROM stores WHERE shop_domain IN ('" + SHOP_X + "','" + SHOP_Y + "')");
        reset(jobScheduler);
    }

    private record Signup(String token, UUID tenantId) {}

    private Signup signupOwner(String company, String username, String email) {
        var req = new SignupRequest(company, username, email, "01012345678", "password99", true);
        var resp = rest.postForEntity(base() + "/api/v1/auth/signup", req, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = resp.getBody().accessToken();
        UUID tenantId = UUID.fromString((String) jwtService.verify(token).getClaim("tenant"));
        return new Signup(token, tenantId);
    }

    // -----------------------------------------------------------------------
    // DEPLOY-READINESS CHECK #1 (run first — most likely to fail): self-reconnect.
    // Connect → user-initiated disconnect (status='disconnected', row NOT deleted) →
    // reconnect the SAME shop via the real /oauth/initiate + /auth/shopify/callback
    // flow. Must succeed as an idempotent re-link: status back to 'connected', same
    // store row (no new insert), and must NOT throw SHOPIFY_SHOP_MISMATCH — a
    // disconnected same-domain row is re-linkable, not "tenant already owns a shop".
    // -----------------------------------------------------------------------
    @Test
    void selfReconnect_afterDisconnect_sameShop_idempotentRelink_noMismatch() {
        Signup owner = signupOwner("SameShopGuard SelfReconnect Corp", "ssg_sr_owner", "ssg_sr@test.com");

        UUID storeId = UUID.randomUUID();
        String originalEncToken = encryptionService.encrypt("shpat_original_token");
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, platform, access_token_encrypted, " +
            "status, import_status) VALUES (?, ?, ?, 'shopify', ?, 'connected', 'completed')",
            storeId, owner.tenantId(), SHOP_X, originalEncToken);

        // Step 1: user-initiated disconnect via the real endpoint.
        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth(owner.token());
        var disconnectResp = rest.exchange(
            base() + "/api/v1/shopify/stores/" + storeId + "/disconnect",
            HttpMethod.POST, new HttpEntity<>(authHeaders), Void.class);
        assertThat(disconnectResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, Object> afterDisconnect = jdbc.queryForMap(
            "SELECT status FROM stores WHERE id = ?", storeId);
        assertThat(afterDisconnect.get("status")).isEqualTo("disconnected");

        Integer rowStillExists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE id = ?", Integer.class, storeId);
        assertThat(rowStillExists).as("disconnect must not delete the row").isEqualTo(1);

        // Step 2: reconnect — initiate() for the SAME shop must proceed (no SHOPIFY_SHOP_MISMATCH).
        HttpHeaders jsonAuthHeaders = new HttpHeaders();
        jsonAuthHeaders.setBearerAuth(owner.token());
        jsonAuthHeaders.setContentType(MediaType.APPLICATION_JSON);
        var initiateResp = rest.exchange(
            base() + "/api/v1/shopify/oauth/initiate",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("shop", SHOP_X), jsonAuthHeaders),
            Map.class);
        assertThat(initiateResp.getStatusCode())
            .as("reconnecting the same shop after disconnect must not be rejected as a mismatch")
            .isEqualTo(HttpStatus.OK);

        String nonce = jdbc.queryForObject(
            "SELECT nonce FROM shopify_oauth_state WHERE tenant_id = ? AND shop_domain = ? " +
            "ORDER BY created_at DESC LIMIT 1",
            String.class, owner.tenantId(), SHOP_X);

        // Step 3: complete the callback — must idempotently re-link, not insert a second row.
        String code = "self-reconnect-code";
        when(shopifyGateway.exchangeCode(eq(SHOP_X), eq(code)))
            .thenReturn(new ShopifyGateway.TokenResponse(
                "shpat_reconnected_token", "shprt_reconnected_refresh", 3600L, 7776000L, null));

        var callbackResp = noRedirectRest.getForEntity(
            base() + "/auth/shopify/callback?" + callbackParams(nonce, SHOP_X, code), Void.class);

        assertThat(callbackResp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        String location = callbackResp.getHeaders().getFirst("Location");
        assertThat(location)
            .as("must not be rejected as a shop mismatch or cross-tenant conflict")
            .doesNotContain("SHOPIFY_SHOP_MISMATCH")
            .doesNotContain("SHOPIFY_STORE_ALREADY_CONNECTED");

        Integer totalRowsForTenant = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE tenant_id = ?", Integer.class, owner.tenantId());
        assertThat(totalRowsForTenant).as("idempotent re-link: no new store row, no new owner").isEqualTo(1);

        Map<String, Object> afterReconnect = jdbc.queryForMap(
            "SELECT id, status, shop_domain FROM stores WHERE tenant_id = ?", owner.tenantId());
        assertThat(afterReconnect.get("id")).as("same store row reused").isEqualTo(storeId);
        assertThat(afterReconnect.get("status")).isEqualTo("connected");
        assertThat(afterReconnect.get("shop_domain")).isEqualTo(SHOP_X);
    }

    // -----------------------------------------------------------------------
    // Layer 1a — tenant already owns SHOP_X (disconnected), initiates for SHOP_Y →
    // SHOPIFY_SHOP_MISMATCH, no state row, no write.
    // -----------------------------------------------------------------------
    @Test
    void initiate_tenantOwnsDifferentShop_rejectedPreConsent() {
        Signup owner = signupOwner("SameShopGuard L1a Corp", "ssg_l1a_owner", "ssg_l1a@test.com");

        jdbc.update(
            "INSERT INTO stores (tenant_id, shop_domain, platform, status, import_status) " +
            "VALUES (?, ?, 'shopify', 'disconnected', 'completed')",
            owner.tenantId(), SHOP_X);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(owner.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        var resp = rest.exchange(
            base() + "/api/v1/shopify/oauth/initiate",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("shop", SHOP_Y), headers),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).containsEntry("code", "SHOPIFY_SHOP_MISMATCH");

        Integer stateCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_oauth_state WHERE shop_domain = ?", Integer.class, SHOP_Y);
        assertThat(stateCount).as("no state nonce written for the mismatched shop").isEqualTo(0);

        Integer storeCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE shop_domain = ?", Integer.class, SHOP_Y);
        assertThat(storeCount).as("no store row written for the mismatched shop").isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Layer 1b — positive control: same tenant, initiates for its OWN shop → proceeds
    // normally (200, consent URL, state row written).
    // -----------------------------------------------------------------------
    @Test
    void initiate_tenantOwnsSameShop_proceedsNormally() {
        Signup owner = signupOwner("SameShopGuard L1b Corp", "ssg_l1b_owner", "ssg_l1b@test.com");

        jdbc.update(
            "INSERT INTO stores (tenant_id, shop_domain, platform, status, import_status) " +
            "VALUES (?, ?, 'shopify', 'disconnected', 'completed')",
            owner.tenantId(), SHOP_X);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(owner.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        var resp = rest.exchange(
            base() + "/api/v1/shopify/oauth/initiate",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("shop", SHOP_X), headers),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("consentUrl");
        assertThat((String) resp.getBody().get("consentUrl")).contains(SHOP_X);

        Integer stateCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_oauth_state WHERE shop_domain = ? AND tenant_id = ?",
            Integer.class, SHOP_X, owner.tenantId());
        assertThat(stateCount).as("state nonce written for the tenant's own shop").isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Layer 1c — zero existing rows → first connect, any valid shop is allowed.
    // -----------------------------------------------------------------------
    @Test
    void initiate_noExistingStore_firstConnectAllowsAnyShop() {
        Signup owner = signupOwner("SameShopGuard L1c Corp", "ssg_l1c_owner", "ssg_l1c@test.com");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(owner.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        var resp = rest.exchange(
            base() + "/api/v1/shopify/oauth/initiate",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("shop", SHOP_Y), headers),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("consentUrl");
    }

    // -----------------------------------------------------------------------
    // Layer 2 — write-site backstop. A state row for SHOP_Y is inserted directly
    // (bypassing initiate()/assertBoundShop()) while the tenant already owns SHOP_X.
    // path1()'s owner==null branch must refuse the second row.
    // -----------------------------------------------------------------------
    @Test
    void path1Backstop_ownerNull_tenantOwnsDifferentShop_rejectsNoSecondRow() {
        Signup owner = signupOwner("SameShopGuard L2 Corp", "ssg_l2_owner", "ssg_l2@test.com");

        jdbc.update(
            "INSERT INTO stores (tenant_id, shop_domain, platform, status, import_status) " +
            "VALUES (?, ?, 'shopify', 'connected', 'completed')",
            owner.tenantId(), SHOP_X);

        String code = "backstop-code";
        when(shopifyGateway.exchangeCode(eq(SHOP_Y), eq(code)))
            .thenReturn(new ShopifyGateway.TokenResponse(
                "shpat_backstop_token", "shprt_backstop_refresh", 3600L, 7776000L, null));

        String nonce = insertState(owner.tenantId(), SHOP_Y, Instant.now());
        var resp = noRedirectRest.getForEntity(
            base() + "/auth/shopify/callback?" + callbackParams(nonce, SHOP_Y, code), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getFirst("Location")).contains("SHOPIFY_SHOP_MISMATCH");

        Integer totalStores = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE tenant_id = ?", Integer.class, owner.tenantId());
        assertThat(totalStores).as("no second store row inserted").isEqualTo(1);

        Integer shopYCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE shop_domain = ?", Integer.class, SHOP_Y);
        assertThat(shopYCount).isEqualTo(0);
    }

    // ---- helpers ------------------------------------------------------------

    private String base() { return "http://localhost:" + port; }

    private String insertState(UUID tenantId, String shopDomain, Instant createdAt) {
        byte[] nonceBytes = new byte[16];
        new SecureRandom().nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        jdbc.update(
            "INSERT INTO shopify_oauth_state (nonce, tenant_id, shop_domain, created_at, host) VALUES (?, ?, ?, ?, ?)",
            nonce, tenantId, shopDomain, Timestamp.from(createdAt), null);
        return nonce;
    }

    private String callbackParams(String nonce, String shop, String code) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code",      code);
        params.put("shop",      shop);
        params.put("state",     nonce);
        params.put("timestamp", timestamp);
        params.put("hmac",      computeHmac(params));
        return buildQueryString(params);
    }

    private String computeHmac(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.remove("hmac");
        StringBuilder canonical = new StringBuilder();
        sorted.forEach((k, v) -> {
            if (!canonical.isEmpty()) canonical.append('&');
            canonical.append(k).append('=').append(v);
        });
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                clientSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(
                canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Test HMAC failed", e);
        }
    }

    private static String buildQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }
}
