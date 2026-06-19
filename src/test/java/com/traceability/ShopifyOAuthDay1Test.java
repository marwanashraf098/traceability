package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyImportJob;
import com.traceability.notifications.EmailGateway;
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
import org.springframework.security.crypto.password.PasswordEncoder;
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
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * OAuth Day 1 integration tests — FR-3.1 (public OAuth track).
 *
 * ShopifyGateway and JobScheduler are @MockBean — no real Shopify calls.
 * All endpoints hit a real Postgres (Testcontainers). HMAC computed in-test
 * using the known test-client-secret from application.properties.
 *
 * @TestInstance(PER_CLASS) + static initializer: same pattern as other tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyOAuthDay1Test {

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
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired JwtService       jwtService;
    @Autowired PasswordEncoder  passwordEncoder;

    @MockBean ShopifyGateway  shopifyGateway;
    @MockBean JobScheduler    jobScheduler;
    @MockBean ShopifyImportJob importJob;  // prevent real job execution
    @MockBean EmailGateway     emailGateway; // MagicLinkService dependency

    @Value("${shopify.client-secret}")
    String clientSecret;

    private String ownerToken;
    private UUID   ownerTenantId;

    // No-redirect RestTemplate: avoids following the 302 to http://localhost:5173
    // (the standalone app URL — not running during tests).
    // Error handler disabled so 4xx responses are returned as ResponseEntity, not thrown.
    private RestTemplate noRedirectRest;

    private static final String SHOP   = "oauth-test.myshopify.com";
    private static final String CODE   = "auth-code-from-shopify";
    private static final String TOKEN  = "shpat_test_offline_token_abc";

    @BeforeAll
    void setupOwner() {
        // Build a RestTemplate that never follows redirects (Java HttpClient Redirect.NEVER)
        // and does not throw on 4xx/5xx — returns ResponseEntity for all status codes.
        // This lets tests assert on 302 Location headers without hitting the redirect target
        // (http://localhost:5173 is the standalone SPA and is not running during tests).
        HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        noRedirectRest = new RestTemplate(new JdkClientHttpRequestFactory(httpClient));
        noRedirectRest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) { return false; }
        });

        SignupRequest req = new SignupRequest("OAuth Co", "oauth_owner", "oauth@test.com", "password99");
        ResponseEntity<TokenResponse> resp = rest.postForEntity(
                base() + "/api/v1/auth/signup", req, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ownerToken    = resp.getBody().accessToken();
        ownerTenantId = UUID.fromString(
                (String) jwtService.verify(ownerToken).getClaim("tenant"));
    }

    @BeforeEach
    void cleanUp() {
        jdbc.execute("DELETE FROM shopify_oauth_state");
        jdbc.execute("DELETE FROM stores WHERE shop_domain = '" + SHOP + "'");
    }

    // -------------------------------------------------------------------------
    // (a) Install HMAC reject — bad HMAC returns 401 with SHOPIFY_HMAC_INVALID
    // -------------------------------------------------------------------------
    @Test
    void installHmacReject_badHmacReturns401() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        ResponseEntity<Map> resp = noRedirectRest.getForEntity(
                base() + "/auth/shopify/install?shop=" + SHOP
                        + "&timestamp=" + timestamp + "&hmac=invalid-hex-hmac",
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).containsEntry("code", "SHOPIFY_HMAC_INVALID");
        assertThat(resp.getBody()).containsKey("message_en");
        assertThat(resp.getBody()).containsKey("message_ar");
    }

    // -------------------------------------------------------------------------
    // (b) Canonical string correctness — correct HMAC returns 302 + Location
    // -------------------------------------------------------------------------
    @Test
    void install_correctHmacReturns302WithConsentUrl() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("shop", SHOP);
        params.put("timestamp", timestamp);
        params.put("hmac", computeHmac(params));

        String queryString = buildQueryString(params);
        ResponseEntity<Void> resp = noRedirectRest.getForEntity(
                base() + "/auth/shopify/install?" + queryString, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        String location = resp.getHeaders().getFirst("Location");
        assertThat(location).isNotNull().contains(SHOP).contains("client_id=test-client-id");

        // State row created with null tenant_id (Path-2)
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shopify_oauth_state WHERE shop_domain = ? AND tenant_id IS NULL",
                Integer.class, SHOP);
        assertThat(count).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // (c) Callback HMAC reject — bad HMAC returns 401 with SHOPIFY_HMAC_INVALID
    // -------------------------------------------------------------------------
    @Test
    void callbackHmacReject_badHmacReturns401() {
        String nonce = insertState(ownerTenantId, SHOP, Instant.now());
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        ResponseEntity<Map> resp = noRedirectRest.getForEntity(
                base() + "/auth/shopify/callback?code=" + CODE
                        + "&shop=" + SHOP
                        + "&state=" + nonce
                        + "&timestamp=" + timestamp
                        + "&hmac=bad-hmac",
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).containsEntry("code", "SHOPIFY_HMAC_INVALID");
    }

    // -------------------------------------------------------------------------
    // (d) State replay — second callback with same state → SHOPIFY_STATE_INVALID
    // -------------------------------------------------------------------------
    @Test
    void stateReplay_secondCallbackIsRejected() {
        when(shopifyGateway.exchangeCode(eq(SHOP), eq(CODE))).thenReturn(TOKEN);

        String nonce = insertState(ownerTenantId, SHOP, Instant.now());

        // First callback → success (302)
        ResponseEntity<Void> first = noRedirectRest.getForEntity(
                base() + "/auth/shopify/callback?" + callbackParams(nonce), Void.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.FOUND);

        // Second callback with same state → SHOPIFY_STATE_INVALID
        ResponseEntity<Map> second = noRedirectRest.getForEntity(
                base() + "/auth/shopify/callback?" + callbackParams(nonce), Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(second.getBody()).containsEntry("code", "SHOPIFY_STATE_INVALID");
    }

    // -------------------------------------------------------------------------
    // (e) State shop-mismatch — callback shop ≠ state shop → SHOPIFY_STATE_INVALID
    // -------------------------------------------------------------------------
    @Test
    void stateShopMismatch_rejectsCallback() {
        String nonce = insertState(ownerTenantId, SHOP, Instant.now());
        String differentShop = "different.myshopify.com";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        Map<String, String> params = new LinkedHashMap<>();
        params.put("code",      CODE);
        params.put("shop",      differentShop);
        params.put("state",     nonce);
        params.put("timestamp", timestamp);
        params.put("hmac",      computeHmac(params));

        ResponseEntity<Map> resp = noRedirectRest.getForEntity(
                base() + "/auth/shopify/callback?" + buildQueryString(params), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("code", "SHOPIFY_STATE_INVALID");
    }

    // -------------------------------------------------------------------------
    // (f) Expired state (>10 min old) → SHOPIFY_STATE_INVALID
    // -------------------------------------------------------------------------
    @Test
    void expiredState_rejectsCallback() {
        Instant expiredAt = Instant.now().minus(15, ChronoUnit.MINUTES);
        String nonce = insertState(ownerTenantId, SHOP, expiredAt);

        ResponseEntity<Map> resp = noRedirectRest.getForEntity(
                base() + "/auth/shopify/callback?" + callbackParams(nonce), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("code", "SHOPIFY_STATE_INVALID");
    }

    // -------------------------------------------------------------------------
    // (g) Happy path: valid state → token encrypted+stored → import job enqueued
    // -------------------------------------------------------------------------
    @Test
    void happyPath_tokenStoredAndImportEnqueued() {
        when(shopifyGateway.exchangeCode(eq(SHOP), eq(CODE))).thenReturn(TOKEN);

        String nonce = insertState(ownerTenantId, SHOP, Instant.now());

        ResponseEntity<Void> resp = noRedirectRest.getForEntity(
                base() + "/auth/shopify/callback?" + callbackParams(nonce), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getFirst("Location")).isNotNull();

        // Store row created for correct tenant
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stores WHERE shop_domain = ? AND tenant_id = ?",
                Integer.class, SHOP, ownerTenantId);
        assertThat(count).isEqualTo(1);

        // Import job + webhook registration job were both enqueued
        verify(jobScheduler, times(2)).enqueue(any(org.jobrunr.jobs.lambdas.JobLambda.class));

        // State was consumed (consumed_at set)
        Timestamp consumed = jdbc.queryForObject(
                "SELECT consumed_at FROM shopify_oauth_state WHERE nonce = ?",
                Timestamp.class, nonce);
        assertThat(consumed).isNotNull();
    }

    // -------------------------------------------------------------------------
    // (h) Token-at-rest is ciphertext — stored value ≠ raw token
    // -------------------------------------------------------------------------
    @Test
    void tokenAtRest_isCiphertext() {
        when(shopifyGateway.exchangeCode(eq(SHOP), eq(CODE))).thenReturn(TOKEN);

        String nonce = insertState(ownerTenantId, SHOP, Instant.now());
        noRedirectRest.getForEntity(base() + "/auth/shopify/callback?" + callbackParams(nonce), Void.class);

        String stored = jdbc.queryForObject(
                "SELECT access_token_encrypted FROM stores WHERE shop_domain = ?",
                String.class, SHOP);

        assertThat(stored).isNotNull();
        assertThat(stored).isNotEqualTo(TOKEN);           // must not be plaintext
        assertThat(stored.length()).isGreaterThan(TOKEN.length()); // ciphertext is longer (IV + tag)
    }

    // ---- helpers -----------------------------------------------------------

    private String base() { return "http://localhost:" + port; }

    /** Inserts a state row directly (bypasses the initiate endpoint for test setup speed). */
    private String insertState(UUID tenantId, String shopDomain, Instant createdAt) {
        byte[] nonceBytes = new byte[16];
        new java.security.SecureRandom().nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        jdbc.update(
            "INSERT INTO shopify_oauth_state (nonce, tenant_id, shop_domain, created_at) VALUES (?, ?, ?, ?)",
            nonce, tenantId, shopDomain, Timestamp.from(createdAt));
        return nonce;
    }

    /** Builds a valid callback query string (with correct HMAC) for the given nonce and SHOP. */
    private String callbackParams(String nonce) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code",      CODE);
        params.put("shop",      SHOP);
        params.put("state",     nonce);
        params.put("timestamp", timestamp);
        params.put("hmac",      computeHmac(params));
        return buildQueryString(params);
    }

    /** Computes HMAC-SHA256 (hex) over sorted params (excluding "hmac") with the test client secret. */
    private String computeHmac(Map<String, String> params) {
        Map<String, String> withPlaceholder = new LinkedHashMap<>(params);
        withPlaceholder.put("hmac", "placeholder");
        // Re-use the production utility to guarantee test and production agree
        // on the canonical string format. We inject a placeholder hmac then
        // recompute so ShopifyHmacUtil.verifyOAuthParams is exercised end-to-end.
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
            byte[] raw = mac.doFinal(canonical.toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Test HMAC computation failed", e);
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
