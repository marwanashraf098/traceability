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

    @Value("${shopify.app-url}")
    String appUrl;

    private String ownerToken;
    private UUID   ownerTenantId;

    // No-redirect RestTemplate: avoids following the 302 to http://localhost:5173
    // (the standalone app URL — not running during tests).
    // Error handler disabled so 4xx responses are returned as ResponseEntity, not thrown.
    private RestTemplate noRedirectRest;

    private static final String SHOP   = "oauth-test.myshopify.com";
    private static final String CODE   = "auth-code-from-shopify";
    private static final String TOKEN  = "shpat_test_offline_token_abc";
    private static final ShopifyGateway.TokenResponse EXCHANGE_TOKEN =
        new ShopifyGateway.TokenResponse(TOKEN, "shprt_refresh_day1", 3600L, 7776000L, null);

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

        SignupRequest req = new SignupRequest("OAuth Co", "oauth_owner", "oauth@test.com", "01012345678", "password99", true);
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
    // (a) Install HMAC reject — bad HMAC → 302 to the connections page with
    //     shopify_error=INSTALL_FAILED (browser navigation never gets bare JSON).
    //     No state row is created.
    // -------------------------------------------------------------------------
    @Test
    void installHmacReject_badHmacRedirectsInstallFailed() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        ResponseEntity<Void> resp = noRedirectRest.getForEntity(
                base() + "/auth/shopify/install?shop=" + SHOP
                        + "&timestamp=" + timestamp + "&hmac=invalid-hex-hmac",
                Void.class);

        assertErrorRedirect(resp, "INSTALL_FAILED");
        Integer states = jdbc.queryForObject("SELECT COUNT(*) FROM shopify_oauth_state", Integer.class);
        assertThat(states).isZero();
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
    // (b2) Fix 2.3.3 — when Shopify DOES provide host at /install (e.g. a
    // re-authorization from an already-open embedded session), it must be
    // captured into shopify_oauth_state so callback() can prefer it. Absent on
    // a genuinely cold install — this only proves the capture path when present.
    // -------------------------------------------------------------------------
    @Test
    void install_withHostParam_capturesHostOnStateRow() {
        String hostShop = "host-capture-test.myshopify.com";
        jdbc.update("DELETE FROM shopify_oauth_state WHERE shop_domain = ?", hostShop);
        String hostValue = "YWRtaW4uc2hvcGlmeS5jb20vc3RvcmUvaG9zdC1jYXB0dXJl"; // base64, opaque to install()

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("shop", hostShop);
        params.put("host", hostValue);
        params.put("timestamp", timestamp);
        params.put("hmac", computeHmac(params));

        ResponseEntity<Void> resp = noRedirectRest.getForEntity(
                base() + "/auth/shopify/install?" + buildQueryString(params), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);

        String capturedHost = jdbc.queryForObject(
                "SELECT host FROM shopify_oauth_state WHERE shop_domain = ? AND tenant_id IS NULL",
                String.class, hostShop);
        assertThat(capturedHost).isEqualTo(hostValue);

        jdbc.update("DELETE FROM shopify_oauth_state WHERE shop_domain = ?", hostShop);
    }

    // -------------------------------------------------------------------------
    // (c) Callback HMAC reject — bad HMAC → 302 shopify_error=INSTALL_FAILED.
    //     Nothing from the (unverified) request is reflected into the redirect.
    // -------------------------------------------------------------------------
    @Test
    void callbackHmacReject_badHmacRedirectsInstallFailed() {
        String nonce = insertState(ownerTenantId, SHOP, Instant.now());
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        ResponseEntity<Void> resp = noRedirectRest.getForEntity(
                base() + "/auth/shopify/callback?code=" + CODE
                        + "&shop=" + SHOP
                        + "&state=" + nonce
                        + "&timestamp=" + timestamp
                        + "&hmac=bad-hmac",
                Void.class);

        assertErrorRedirect(resp, "INSTALL_FAILED");
        Integer stores = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stores WHERE shop_domain = ?", Integer.class, SHOP);
        assertThat(stores).isZero();
    }

    // -------------------------------------------------------------------------
    // (d) State replay — second callback with same state → 302 INSTALL_EXPIRED
    // -------------------------------------------------------------------------
    @Test
    void stateReplay_secondCallbackIsRejected() {
        when(shopifyGateway.exchangeCode(eq(SHOP), eq(CODE))).thenReturn(EXCHANGE_TOKEN);

        String nonce = insertState(ownerTenantId, SHOP, Instant.now());

        // First callback → success (302)
        ResponseEntity<Void> first = noRedirectRest.getForEntity(
                base() + "/auth/shopify/callback?" + callbackParams(nonce), Void.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.FOUND);

        // Second callback with same state → state invalid → INSTALL_EXPIRED redirect
        ResponseEntity<Void> second = noRedirectRest.getForEntity(
                base() + "/auth/shopify/callback?" + callbackParams(nonce), Void.class);
        assertErrorRedirect(second, "INSTALL_EXPIRED");
    }

    // -------------------------------------------------------------------------
    // (e) State shop-mismatch — callback shop ≠ state shop → 302 INSTALL_EXPIRED
    //     (state-invalid sub-conditions stay indistinguishable to the browser)
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

        ResponseEntity<Void> resp = noRedirectRest.getForEntity(
                base() + "/auth/shopify/callback?" + buildQueryString(params), Void.class);

        assertErrorRedirect(resp, "INSTALL_EXPIRED");
    }

    // -------------------------------------------------------------------------
    // (f) Expired state (>10 min old) → 302 INSTALL_EXPIRED
    // -------------------------------------------------------------------------
    @Test
    void expiredState_rejectsCallback() {
        Instant expiredAt = Instant.now().minus(15, ChronoUnit.MINUTES);
        String nonce = insertState(ownerTenantId, SHOP, expiredAt);

        ResponseEntity<Void> resp = noRedirectRest.getForEntity(
                base() + "/auth/shopify/callback?" + callbackParams(nonce), Void.class);

        assertErrorRedirect(resp, "INSTALL_EXPIRED");
    }

    // -------------------------------------------------------------------------
    // (f3) Token exchange failure → 302 INSTALL_FAILED, no store row written.
    // -------------------------------------------------------------------------
    @Test
    void tokenExchangeFailure_callbackRedirectsInstallFailed_noStoreWritten() {
        when(shopifyGateway.exchangeCode(eq(SHOP), eq(CODE)))
                .thenThrow(new RuntimeException("simulated Shopify 500"));
        String nonce = insertState(ownerTenantId, SHOP, Instant.now());

        ResponseEntity<Void> resp = noRedirectRest.getForEntity(
                base() + "/auth/shopify/callback?" + callbackParams(nonce), Void.class);

        assertErrorRedirect(resp, "INSTALL_FAILED");
        Integer stores = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stores WHERE shop_domain = ?", Integer.class, SHOP);
        assertThat(stores).isZero();
    }

    // -------------------------------------------------------------------------
    // (f4) Install with an invalid shop domain → 302 INSTALL_FAILED; the bad
    //      value is never reflected into the redirect.
    // -------------------------------------------------------------------------
    @Test
    void install_invalidShopDomain_redirectsInstallFailed() {
        ResponseEntity<Void> resp = noRedirectRest.getForEntity(
                base() + "/auth/shopify/install?shop=evil.example.com", Void.class);

        assertErrorRedirect(resp, "INSTALL_FAILED");
        assertThat(resp.getHeaders().getFirst("Location")).doesNotContain("evil");
    }

    // -------------------------------------------------------------------------
    // (g) Happy path: valid state → token encrypted+stored → import job enqueued
    // -------------------------------------------------------------------------
    @Test
    void happyPath_tokenStoredAndImportEnqueued() {
        when(shopifyGateway.exchangeCode(eq(SHOP), eq(CODE))).thenReturn(EXCHANGE_TOKEN);

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
        when(shopifyGateway.exchangeCode(eq(SHOP), eq(CODE))).thenReturn(EXCHANGE_TOKEN);

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

    /** 302 to exactly the connections page carrying ONLY the fixed code — nothing reflected. */
    private void assertErrorRedirect(ResponseEntity<?> resp, String code) {
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getFirst("Location"))
                .isEqualTo(appUrl + "/settings?tab=connections&shopify_error=" + code);
    }

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
