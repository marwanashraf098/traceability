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
 * Review mode S1 — the shop binding rule (ShopifySameShopGuard.boundShopDomains), through BOTH
 * layers for every case: Layer 1 = POST /api/v1/shopify/oauth/initiate (pre-consent), Layer 2 =
 * the OAuth callback's write-site backstop (ShopifyOAuthService.path1(), reached with a state row
 * inserted directly, bypassing initiate).
 *
 *   b1 real tenant, disconnected A, connect B          → rejected at initiate AND callback
 *   b2 real tenant, disconnected A, reconnect A        → allowed at both, same row re-linked
 *   b3 simulated tenant, disconnected placeholder + disconnected old review shop, new shop C
 *                                                       → allowed at both
 *   b4 simulated tenant, connected A, connect B         → rejected at both
 *   b5 no store rows                                    → allowed at both (first connect)
 *
 * The custom-app CC path is covered by CustomAppConnectTest
 * .sameShopGuard_onlyDisconnectedRow_differentShop_rejected409.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopBindingRuleTest {

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

    @MockBean ShopifyGateway   shopifyGateway;
    @MockBean JobScheduler     jobScheduler;
    @MockBean ShopifyImportJob importJob;
    @MockBean EmailGateway     emailGateway;

    @Value("${shopify.app-url}")       String appUrl;
    @Value("${shopify.client-secret}") String clientSecret;

    private RestTemplate noRedirectRest;
    private int seq = 0;

    private static final String SHOP_A           = "binding-a.myshopify.com";
    private static final String SHOP_B           = "binding-b.myshopify.com";
    private static final String SHOP_C           = "binding-c.myshopify.com";
    private static final String SHOP_OLD_REVIEW  = "binding-old-review.myshopify.com";
    private static final String PLACEHOLDER      = "binding-review-placeholder.myshopify.invalid";
    private static final List<String> ALL_SHOPS  = List.of(SHOP_A, SHOP_B, SHOP_C, SHOP_OLD_REVIEW, PLACEHOLDER);

    @BeforeAll
    void setupClient() {
        HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        noRedirectRest = new RestTemplate(new JdkClientHttpRequestFactory(httpClient));
        noRedirectRest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) { return false; }
        });
    }

    @AfterEach
    void cleanup() {
        String in = "('" + String.join("','", ALL_SHOPS) + "')";
        jdbc.execute("DELETE FROM shopify_oauth_state WHERE shop_domain IN " + in);
        jdbc.execute("DELETE FROM stores WHERE shop_domain IN " + in);
        reset(jobScheduler, shopifyGateway);
    }

    // -----------------------------------------------------------------------
    @Test
    void b1_realTenant_disconnectedA_connectB_rejectedAtInitiateAndCallback() {
        Owner o = signup("b1");
        insertStore(o.tenantId(), SHOP_A, "disconnected");

        var init = initiate(o, SHOP_B);
        assertThat(init.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(init.getBody()).containsEntry("code", "SHOPIFY_SHOP_MISMATCH");
        assertThat((String) init.getBody().get("message_en")).contains(SHOP_A);
        assertThat(stateRows(SHOP_B)).as("no state nonce for B").isZero();

        String location = callback(o.tenantId(), SHOP_B);
        assertThat(location).isEqualTo(appUrl + "/settings?tab=connections&shopify_error=SHOP_MISMATCH");
        assertThat(storeRows(o.tenantId())).as("no second row").isEqualTo(1);
        assertThat(storeRowsForShop(SHOP_B)).isZero();
    }

    @Test
    void b2_realTenant_disconnectedA_reconnectA_allowedAtBoth() {
        Owner o = signup("b2");
        UUID storeId = insertStore(o.tenantId(), SHOP_A, "disconnected");

        var init = initiate(o, SHOP_A);
        assertThat(init.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(init.getBody()).containsKey("consentUrl");

        String location = callback(o.tenantId(), SHOP_A);
        assertThat(location).doesNotContain("shopify_error");
        assertThat(storeRows(o.tenantId())).as("same row re-linked, no new row").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status::text FROM stores WHERE id = ?", String.class, storeId))
            .isEqualTo("connected");
    }

    @Test
    void b3_simulatedTenant_disconnectedPlaceholderAndOldReviewShop_newShop_allowedAtBoth() {
        Owner o = signup("b3");
        insertStore(o.tenantId(), PLACEHOLDER, "disconnected");
        insertStore(o.tenantId(), SHOP_OLD_REVIEW, "disconnected");
        simulate(o.tenantId());

        var init = initiate(o, SHOP_C);
        assertThat(init.getStatusCode())
            .as("a simulated tenant's disconnected rows never block the next review shop")
            .isEqualTo(HttpStatus.OK);
        assertThat(init.getBody()).containsKey("consentUrl");

        String location = callback(o.tenantId(), SHOP_C);
        assertThat(location).doesNotContain("shopify_error");
        assertThat(jdbc.queryForObject(
            "SELECT status::text FROM stores WHERE tenant_id = ? AND shop_domain = ?",
            String.class, o.tenantId(), SHOP_C)).isEqualTo("connected");
        assertThat(storeRows(o.tenantId())).as("old rows kept + the new one").isEqualTo(3);
    }

    @Test
    void b4_simulatedTenant_connectedA_connectB_rejectedAtBoth() {
        Owner o = signup("b4");
        insertStore(o.tenantId(), SHOP_A, "connected");
        simulate(o.tenantId());

        var init = initiate(o, SHOP_B);
        assertThat(init.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(init.getBody()).containsEntry("code", "SHOPIFY_SHOP_MISMATCH");
        assertThat((String) init.getBody().get("message_en")).contains(SHOP_A);
        assertThat(stateRows(SHOP_B)).isZero();

        String location = callback(o.tenantId(), SHOP_B);
        assertThat(location).isEqualTo(appUrl + "/settings?tab=connections&shopify_error=SHOP_MISMATCH");
        assertThat(storeRowsForShop(SHOP_B)).isZero();
    }

    @Test
    void b5_noStoreRows_firstConnect_allowedAtBoth() {
        Owner o = signup("b5");

        var init = initiate(o, SHOP_A);
        assertThat(init.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(init.getBody()).containsKey("consentUrl");

        String location = callback(o.tenantId(), SHOP_A);
        assertThat(location).doesNotContain("shopify_error");
        assertThat(storeRows(o.tenantId())).isEqualTo(1);
    }

    // ---- helpers ------------------------------------------------------------

    private record Owner(String token, UUID tenantId) {}

    private Owner signup(String tag) {
        String u = "sbr_" + tag + "_" + (++seq) + "_" + System.nanoTime();
        var req = new SignupRequest("ShopBinding " + tag, u, u + "@test.com", "01012345678", "password99", true);
        var resp = rest.postForEntity(base() + "/api/v1/auth/signup", req, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = resp.getBody().accessToken();
        return new Owner(token, UUID.fromString((String) jwtService.verify(token).getClaim("tenant")));
    }

    private UUID insertStore(UUID tenantId, String shop, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, platform, status, import_status) " +
            "VALUES (?, ?, ?, 'shopify', ?::store_status, 'completed')", id, tenantId, shop, status);
        return id;
    }

    /** Review mode flag — written as postgres, exactly like the ops script / seeder will. */
    private void simulate(UUID tenantId) {
        jdbc.update("INSERT INTO tenant_courier_simulation (tenant_id, note) VALUES (?, 'test')", tenantId);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> initiate(Owner o, String shop) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(o.token());
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + "/api/v1/shopify/oauth/initiate", HttpMethod.POST,
            new HttpEntity<>(Map.of("shop", shop), h), Map.class);
    }

    /** Layer 2: a state row inserted directly (initiate bypassed), then the real callback. */
    private String callback(UUID tenantId, String shop) {
        String code = "code-" + UUID.randomUUID();
        when(shopifyGateway.exchangeCode(eq(shop), eq(code)))
            .thenReturn(new ShopifyGateway.TokenResponse("shpat_" + code, "shprt_" + code, 3600L, 7776000L, null));
        String nonce = insertState(tenantId, shop);
        var resp = noRedirectRest.getForEntity(base() + "/auth/shopify/callback?" + callbackParams(nonce, shop, code), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        return resp.getHeaders().getFirst("Location");
    }

    private int stateRows(String shop) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shopify_oauth_state WHERE shop_domain = ?", Integer.class, shop);
    }

    private int storeRows(UUID tenantId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stores WHERE tenant_id = ?", Integer.class, tenantId);
    }

    private int storeRowsForShop(String shop) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stores WHERE shop_domain = ?", Integer.class, shop);
    }

    private String base() { return "http://localhost:" + port; }

    private String insertState(UUID tenantId, String shopDomain) {
        byte[] nonceBytes = new byte[16];
        new SecureRandom().nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        jdbc.update(
            "INSERT INTO shopify_oauth_state (nonce, tenant_id, shop_domain, created_at, host) VALUES (?, ?, ?, ?, ?)",
            nonce, tenantId, shopDomain, Timestamp.from(Instant.now()), null);
        return nonce;
    }

    private String callbackParams(String nonce, String shop, String code) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code",      code);
        params.put("shop",      shop);
        params.put("state",     nonce);
        params.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        params.put("hmac",      computeHmac(params));
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('&');
            sb.append(k).append('=').append(java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8));
        });
        return sb.toString();
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
            byte[] raw = mac.doFinal(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Test HMAC failed", e);
        }
    }
}
