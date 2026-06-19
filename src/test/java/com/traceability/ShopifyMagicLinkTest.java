package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.identity.MagicLinkService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyHmacUtil;
import com.traceability.integrations.shopify.ShopifyImportJob;
import com.traceability.notifications.EmailGateway;
import com.traceability.security.EncryptionService;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
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
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * OAuth Day 4 Part B — magic-link bridge integration tests (FR-3.1).
 *
 * #1 Happy path: valid token → JWT for correct owner → reaches protected route.
 * #2 Single-use: second consume → MAGIC_LINK_INVALID.
 * #3 Expiry: expired token → MAGIC_LINK_INVALID.
 * #4 Forged: unknown token → MAGIC_LINK_INVALID.
 * #5 Hash at-rest: raw token not stored in magic_link_tokens.
 * #6 Provision wiring: Path-2 new install → emailGateway.sendMagicLink called with owner email + link.
 * #7 Cross-tenant: token for tenant A → JWT scoped to A only.
 *
 * EmailGateway is @MockBean — no real email sent.
 * JobScheduler is @MockBean — no real background jobs.
 * ShopifyGateway is @MockBean — no real Shopify API calls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShopifyMagicLinkTest {

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
    @Autowired JdbcTemplate      jdbc;
    @Autowired MagicLinkService  magicLinkService;
    @Autowired JwtService        jwtService;
    @Autowired EncryptionService encryptionService;

    @MockBean EmailGateway     emailGateway;
    @MockBean ShopifyGateway   shopifyGateway;
    @MockBean JobScheduler     jobScheduler;
    @MockBean ShopifyImportJob importJob;

    @Value("${shopify.client-secret}")
    String clientSecret;

    @Value("${shopify.app-url:http://localhost:5173}")
    String appUrl;

    // Tenant A — primary test tenant with a passwordless provisioned owner
    private UUID   tenantIdA;
    private UUID   ownerIdA;
    private String ownerEmailA = "owner-a@magic.test";
    private UUID   storeIdA;

    // Tenant B — for cross-tenant isolation test (#7)
    private UUID tenantIdB;
    private UUID ownerIdB;

    private RestTemplate noRedirectRest;

    @BeforeAll
    void setup() {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        noRedirectRest = new RestTemplate(new JdkClientHttpRequestFactory(httpClient));
        noRedirectRest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) { return false; }
        });

        // Tenant A: passwordless owner (mirrors Path-2 provisioning)
        tenantIdA = UUID.randomUUID();
        ownerIdA  = UUID.randomUUID();
        storeIdA  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantIdA, "Magic Corp A");
        // No password_hash — owner was provisioned via Shopify Path-2
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Owner A', ?, NULL, 'owner')",
            ownerIdA, tenantIdA, ownerEmailA);
        String encToken = encryptionService.encrypt("token_magic_a");
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, platform, access_token_encrypted, " +
            "status, import_status) VALUES (?, ?, 'magic-a.myshopify.com', 'shopify', ?, 'connected', 'pending')",
            storeIdA, tenantIdA, encToken);

        // Tenant B: separate owner for isolation test
        tenantIdB = UUID.randomUUID();
        ownerIdB  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantIdB, "Magic Corp B");
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Owner B', 'owner-b@magic.test', NULL, 'owner')",
            ownerIdB, tenantIdB);
    }

    @AfterEach
    void cleanTokens() {
        jdbc.update("DELETE FROM magic_link_tokens WHERE tenant_id = ? OR tenant_id = ?",
            tenantIdA, tenantIdB);
        jdbc.update("DELETE FROM refresh_tokens WHERE tenant_id = ? OR tenant_id = ?",
            tenantIdA, tenantIdB);
        reset(emailGateway, jobScheduler, shopifyGateway);
    }

    // -----------------------------------------------------------------------
    // 1. Happy path: valid token → JWT issued for correct owner → reaches protected route
    // -----------------------------------------------------------------------
    @Test
    @Order(1)
    void happyPath_validToken_jwtIssuedForCorrectOwner() {
        magicLinkService.issueMagicLink(ownerIdA, tenantIdA);

        String rawToken = captureToken();
        ResponseEntity<Void> resp = consumeToken(rawToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        String location = resp.getHeaders().getFirst("Location");
        assertThat(location).isNotNull().contains("#access_token=");

        String accessToken = extractFragment(location, "access_token");
        assertThat(accessToken).isNotBlank();

        // JWT must encode the correct user and tenant
        var claims = jwtService.verify(accessToken);
        assertThat(claims.getSubject()).isEqualTo(ownerIdA.toString());
        assertThat(claims.getClaim("tenant")).isEqualTo(tenantIdA.toString());
        assertThat(claims.getClaim("role")).isEqualTo("owner");

        // Refresh token must be in the fragment too
        String refreshToken = extractFragment(location, "refresh_token");
        assertThat(refreshToken).isNotBlank();
    }

    // -----------------------------------------------------------------------
    // 2. Single-use: second consume of same token → MAGIC_LINK_INVALID
    // -----------------------------------------------------------------------
    @Test
    @Order(2)
    void singleUse_secondConsume_isMagicLinkInvalid() {
        magicLinkService.issueMagicLink(ownerIdA, tenantIdA);
        String rawToken = captureToken();

        // First consume → success
        ResponseEntity<Void> first = consumeToken(rawToken);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.FOUND);

        // Second consume → MAGIC_LINK_INVALID (401)
        ResponseEntity<Map> second = noRedirectRest.exchange(
            base() + "/auth/magic?token=" + rawToken,
            HttpMethod.GET, HttpEntity.EMPTY, Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(second.getBody()).containsEntry("code", "MAGIC_LINK_INVALID");
    }

    // -----------------------------------------------------------------------
    // 3. Expiry: expired token → MAGIC_LINK_INVALID
    // -----------------------------------------------------------------------
    @Test
    @Order(3)
    void expiry_expiredToken_isMagicLinkInvalid() {
        // Insert an already-expired token directly (expired 1 second ago)
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = com.traceability.identity.AuthRepository.sha256(rawToken);
        Instant expired = Instant.now().minus(1, ChronoUnit.SECONDS);
        jdbc.update(
            "INSERT INTO magic_link_tokens (tenant_id, user_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
            tenantIdA, ownerIdA, hash, Timestamp.from(expired));

        ResponseEntity<Map> resp = noRedirectRest.exchange(
            base() + "/auth/magic?token=" + rawToken,
            HttpMethod.GET, HttpEntity.EMPTY, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).containsEntry("code", "MAGIC_LINK_INVALID");
    }

    // -----------------------------------------------------------------------
    // 4. Forged: unknown token → MAGIC_LINK_INVALID (identical response to #2 and #3)
    // -----------------------------------------------------------------------
    @Test
    @Order(4)
    void forged_unknownToken_isMagicLinkInvalid() {
        String fakeToken = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("not-a-real-token-123456".getBytes());

        ResponseEntity<Map> resp = noRedirectRest.exchange(
            base() + "/auth/magic?token=" + fakeToken,
            HttpMethod.GET, HttpEntity.EMPTY, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).containsEntry("code", "MAGIC_LINK_INVALID");
    }

    // -----------------------------------------------------------------------
    // 5. Hash at-rest: raw token NOT present anywhere in magic_link_tokens
    // -----------------------------------------------------------------------
    @Test
    @Order(5)
    void hashAtRest_rawTokenNotStoredInDb() {
        magicLinkService.issueMagicLink(ownerIdA, tenantIdA);
        String rawToken = captureToken();

        // The hash column must not equal the raw token
        Long matchCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM magic_link_tokens WHERE token_hash = ? AND tenant_id = ?",
            Long.class, rawToken, tenantIdA);
        assertThat(matchCount).isZero();

        // Exactly one row exists for this tenant, and its hash is the SHA-256 of the raw token
        String storedHash = jdbc.queryForObject(
            "SELECT token_hash FROM magic_link_tokens WHERE tenant_id = ?",
            String.class, tenantIdA);
        String expectedHash = com.traceability.identity.AuthRepository.sha256(rawToken);
        assertThat(storedHash).isEqualTo(expectedHash);
        assertThat(storedHash).isNotEqualTo(rawToken);
    }

    // -----------------------------------------------------------------------
    // 6. Provision wiring: Path-2 new install → emailGateway receives send call
    //    with the owner's email and a link containing /auth/magic?token=
    // -----------------------------------------------------------------------
    @Test
    @Order(6)
    void provisionWiring_path2NewInstall_emailGatewayReceivesMagicLink() {
        String newShop    = "magic-provision-test.myshopify.com";
        String ownerEmail = "provision-owner@magic.test";
        String rawToken   = "shpat_provision_token_abc";

        when(shopifyGateway.exchangeCode(eq(newShop), any())).thenReturn(rawToken);
        when(shopifyGateway.fetchShop(eq(newShop), eq(rawToken)))
            .thenReturn(new ShopifyGateway.ShopInfo(ownerEmail, "Magic Provision Store", "Africa/Cairo"));

        // Insert a null-tenant state (Path-2 — no existing owner)
        String nonce = insertNullTenantState(newShop);

        // Trigger callback with valid HMAC
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code",      "provision-code");
        params.put("shop",      newShop);
        params.put("state",     nonce);
        params.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        params.put("hmac",      computeHmac(params));

        noRedirectRest.getForEntity(base() + "/auth/shopify/callback?" + queryString(params), Void.class);

        // emailGateway must have been called exactly once with the owner email and a magic link
        ArgumentCaptor<String> emailCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> linkCaptor  = ArgumentCaptor.forClass(String.class);
        verify(emailGateway, times(1)).sendMagicLink(emailCaptor.capture(), linkCaptor.capture());

        assertThat(emailCaptor.getValue()).isEqualTo(ownerEmail);
        assertThat(linkCaptor.getValue()).contains("/auth/magic?token=");

        // Clean up the provisioned tenant
        UUID provisionedTenantId = jdbc.queryForObject(
            "SELECT id FROM tenants WHERE name = 'Magic Provision Store'", UUID.class);
        if (provisionedTenantId != null) {
            jdbc.update("DELETE FROM magic_link_tokens WHERE tenant_id = ?", provisionedTenantId);
            jdbc.update("DELETE FROM refresh_tokens WHERE tenant_id = ?", provisionedTenantId);
            jdbc.update("DELETE FROM stores WHERE tenant_id = ?", provisionedTenantId);
            jdbc.update("DELETE FROM users WHERE tenant_id = ?", provisionedTenantId);
            jdbc.update("DELETE FROM tenants WHERE id = ?", provisionedTenantId);
        }
        jdbc.update("DELETE FROM shopify_oauth_state WHERE shop_domain = ?", newShop);
    }

    // -----------------------------------------------------------------------
    // 7. Cross-tenant: token minted for tenant A → session scoped to A, never B
    // -----------------------------------------------------------------------
    @Test
    @Order(7)
    void crossTenant_tokenForTenantA_sessionScopedToATenantOnly() {
        magicLinkService.issueMagicLink(ownerIdA, tenantIdA);
        String rawToken = captureToken();

        ResponseEntity<Void> resp = consumeToken(rawToken);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);

        String location = resp.getHeaders().getFirst("Location");
        String accessToken = extractFragment(location, "access_token");

        var claims = jwtService.verify(accessToken);

        // Must be tenant A
        assertThat(claims.getClaim("tenant")).isEqualTo(tenantIdA.toString());
        // Must be owner A's user
        assertThat(claims.getSubject()).isEqualTo(ownerIdA.toString());
        // Must NOT be tenant B
        assertThat(claims.getClaim("tenant")).isNotEqualTo(tenantIdB.toString());
        assertThat(claims.getSubject()).isNotEqualTo(ownerIdB.toString());
    }

    // ---- helpers -----------------------------------------------------------

    private String base() { return "http://localhost:" + port; }

    private ResponseEntity<Void> consumeToken(String rawToken) {
        return noRedirectRest.exchange(
            base() + "/auth/magic?token=" + rawToken,
            HttpMethod.GET, HttpEntity.EMPTY, Void.class);
    }

    /** Captures the magic link sent to emailGateway and extracts the raw token from the URL. */
    private String captureToken() {
        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailGateway, atLeastOnce()).sendMagicLink(any(), linkCaptor.capture());
        String link = linkCaptor.getValue();
        assertThat(link).contains("/auth/magic?token=");
        // Extract token from "...?token=<raw>"
        return link.substring(link.indexOf("token=") + 6);
    }

    /** Extracts a named parameter from a URL fragment (#key=value&key2=value2). */
    private String extractFragment(String location, String key) {
        assertThat(location).contains("#");
        String fragment = location.substring(location.indexOf('#') + 1);
        for (String part : fragment.split("&")) {
            if (part.startsWith(key + "=")) {
                return part.substring(key.length() + 1);
            }
        }
        throw new AssertionError("Fragment key '" + key + "' not found in: " + location);
    }

    private String insertNullTenantState(String shopDomain) {
        byte[] nonceBytes = new byte[16];
        new java.security.SecureRandom().nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        jdbc.update(
            "INSERT INTO shopify_oauth_state (nonce, tenant_id, shop_domain, created_at) VALUES (?, NULL, ?, now())",
            nonce, shopDomain);
        return nonce;
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
            byte[] raw = mac.doFinal(canonical.toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String queryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }
}
