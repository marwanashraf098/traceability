package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaStatusPollJob;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyImportJob;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.notifications.EmailGateway;
import com.traceability.portal.RefundDetailsCipher;
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
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * K1 key ring (2026-11-28) — the app end to end with APP_ENCRYPTION_WRITE_VERSION=v2 (the K2a state):
 * new values are written "v2:…", values written before (legacy, zero key — prod today) keep working,
 * and a legacy value is rewritten as v2 the next time the app writes it.
 *
 *   o1  Shopify OAuth callback stores v2 access + refresh tokens; getValidToken returns the token
 *   o2  an expired v2 token refreshes: Shopify gets the plaintext refresh token, new values are v2
 *   o3  a LEGACY store row (pre-key-ring ciphertext) reads and refreshes; afterwards it is v2
 *   p1  Bosta connect stores a v2 key; the status poll calls Bosta with the plaintext key
 *       a LEGACY courier row is polled with its plaintext key alongside
 *   r1  refund details: v2 + AAD bound to the request; the pre-key-ring golden (legacy + AAD) opens
 *
 * Shopify, Bosta and JobRunr are mocked; nothing leaves the JVM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class EncryptionKeyRingV2IntegrationTest {

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
        r.add("app.encryption-write-version", () -> "v2");
    }

    static final String ZERO_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    /** The pre-key-ring code, as prod runs it today: legacy zero key, no V2. */
    static final EncryptionService LEGACY_WRITER = new EncryptionService(ZERO_KEY, "", "v1");

    @LocalServerPort int port;
    @Autowired TestRestTemplate     rest;
    @Autowired JdbcTemplate         jdbc;
    @Autowired JwtService           jwtService;
    @Autowired EncryptionService    encryptionService;
    @Autowired ShopifyTokenProvider tokenProvider;
    @Autowired BostaStatusPollJob   statusPoll;
    @Autowired RefundDetailsCipher  refundCipher;

    @MockBean ShopifyGateway   shopifyGateway;
    @MockBean ShopifyImportJob importJob;
    @MockBean JobScheduler     jobScheduler;
    @MockBean EmailGateway     emailGateway;
    @MockBean BostaGateway     bostaGateway;
    @MockBean BostaV2Client    bostaV2;

    @Value("${shopify.client-secret}") String clientSecret;

    private RestTemplate noRedirect;
    private String ownerToken;
    private UUID   tenantId;

    static final String SHOP = "keyring-v2.myshopify.com";

    @BeforeAll
    void setup() {
        noRedirect = new RestTemplate(new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
        noRedirect.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) { return false; }
        });
        var resp = rest.postForEntity(base() + "/api/v1/auth/signup",
            new SignupRequest("KeyRing Corp", "keyring_owner", "keyring@test.com", "01012345678", "password99", true),
            TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ownerToken = resp.getBody().accessToken();
        tenantId = UUID.fromString((String) jwtService.verify(ownerToken).getClaim("tenant"));
    }

    @Test
    void a0_theAppWritesV2() {
        assertThat(encryptionService.writeVersion()).isEqualTo(EncryptionService.WriteVersion.V2);
    }

    // ── Shopify ──────────────────────────────────────────────────────────────────────────

    @Test
    void o1_oauthCallback_storesV2Tokens_getValidTokenDecrypts() {
        when(shopifyGateway.exchangeCode(eq(SHOP), eq("code-v2")))
            .thenReturn(new ShopifyGateway.TokenResponse("shpat_v2_access", "shprt_v2_refresh", 3600L, 7776000L, null));

        var resp = noRedirect.getForEntity(base() + "/auth/shopify/callback?" +
            callbackParams(insertState(tenantId, SHOP), SHOP, "code-v2"), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT id, access_token_encrypted, refresh_token_encrypted FROM stores WHERE shop_domain = ?", SHOP);
        assertThat((String) row.get("access_token_encrypted")).startsWith("v2:").doesNotContain("shpat_v2_access");
        assertThat((String) row.get("refresh_token_encrypted")).startsWith("v2:").doesNotContain("shprt_v2_refresh");

        UUID storeId = (UUID) row.get("id");
        assertThat(TenantContext.runAs(tenantId, () -> tokenProvider.getValidToken(storeId))).isEqualTo("shpat_v2_access");
    }

    @Test
    void o2_expiredV2Token_refreshes_shopifyGetsPlaintextRefreshToken_newValuesV2() {
        UUID storeId = jdbc.queryForObject("SELECT id FROM stores WHERE shop_domain = ?", UUID.class, SHOP);
        jdbc.update("UPDATE stores SET access_token_expires_at = now() - interval '1 hour' WHERE id = ?", storeId);
        when(shopifyGateway.refreshAccessToken(eq(SHOP), eq("shprt_v2_refresh")))
            .thenReturn(new ShopifyGateway.TokenResponse("shpat_v2_refreshed", "shprt_v2_refresh_2", 3600L, 7776000L, null));

        assertThat(TenantContext.runAs(tenantId, () -> tokenProvider.getValidToken(storeId))).isEqualTo("shpat_v2_refreshed");
        verify(shopifyGateway).refreshAccessToken(SHOP, "shprt_v2_refresh");

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT access_token_encrypted, refresh_token_encrypted FROM stores WHERE id = ?", storeId);
        assertThat(encryptionService.decrypt((String) row.get("access_token_encrypted"))).isEqualTo("shpat_v2_refreshed");
        assertThat((String) row.get("access_token_encrypted")).startsWith("v2:");
        assertThat((String) row.get("refresh_token_encrypted")).startsWith("v2:");
        assertThat(encryptionService.decrypt((String) row.get("refresh_token_encrypted"))).isEqualTo("shprt_v2_refresh_2");
    }

    @Test
    void o3_legacyStoreRow_readsAndRefreshes_thenIsV2() {
        UUID t = newTenant("Legacy Shopify");
        UUID storeId = UUID.randomUUID();
        String shop = "keyring-legacy.myshopify.com";
        String legacyAccess  = LEGACY_WRITER.encrypt("shpat_legacy_access");
        String legacyRefresh = LEGACY_WRITER.encrypt("shprt_legacy_refresh");
        assertThat(legacyAccess).doesNotStartWith("v2:");
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, platform, connection_type, access_token_encrypted, " +
            "access_token_expires_at, refresh_token_encrypted, refresh_token_expires_at, status, import_status) " +
            "VALUES (?, ?, ?, 'shopify', 'oauth', ?, now() + interval '1 hour', ?, now() + interval '30 days', 'connected', 'pending')",
            storeId, t, shop, legacyAccess, legacyRefresh);

        // Fresh legacy token reads as is.
        assertThat(TenantContext.runAs(t, () -> tokenProvider.getValidToken(storeId))).isEqualTo("shpat_legacy_access");

        // Expired → refresh with the decrypted LEGACY refresh token → stored as v2.
        jdbc.update("UPDATE stores SET access_token_expires_at = now() - interval '1 minute' WHERE id = ?", storeId);
        when(shopifyGateway.refreshAccessToken(eq(shop), eq("shprt_legacy_refresh")))
            .thenReturn(new ShopifyGateway.TokenResponse("shpat_after_refresh", "shprt_after_refresh", 3600L, 7776000L, null));
        assertThat(TenantContext.runAs(t, () -> tokenProvider.getValidToken(storeId))).isEqualTo("shpat_after_refresh");
        verify(shopifyGateway).refreshAccessToken(shop, "shprt_legacy_refresh");

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT access_token_encrypted, refresh_token_encrypted FROM stores WHERE id = ?", storeId);
        assertThat((String) row.get("access_token_encrypted")).startsWith("v2:");
        assertThat((String) row.get("refresh_token_encrypted")).startsWith("v2:");
        assertThat(TenantContext.runAs(t, () -> tokenProvider.getValidToken(storeId))).isEqualTo("shpat_after_refresh");
    }

    // ── Bosta ────────────────────────────────────────────────────────────────────────────

    @Test
    void p1_bostaConnectStoresV2_pollUsesPlaintextKeys_legacyRowToo() {
        when(bostaGateway.fetchBusinessProfile(eq("bosta-key-v2"))).thenReturn("KeyRing Business");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(ownerToken);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = rest.exchange(base() + "/api/v1/bosta/connect", HttpMethod.POST,
            new HttpEntity<>(Map.of("apiKey", "bosta-key-v2"), h), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String stored = jdbc.queryForObject(
            "SELECT api_key_encrypted FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta'", String.class, tenantId);
        assertThat(stored).startsWith("v2:").doesNotContain("bosta-key-v2");
        shipment(tenantId, storeOf(tenantId), "70000001");

        // Legacy courier row (written by the pre-key-ring code) on another tenant.
        UUID legacyTenant = newTenant("Legacy Bosta");
        UUID legacyStore = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            legacyStore, legacyTenant, "keyring-legacy-bosta.myshopify.com");
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'h', 'active')", legacyTenant, LEGACY_WRITER.encrypt("bosta-key-legacy"));
        shipment(legacyTenant, legacyStore, "70000002");

        when(bostaV2.searchDeliveriesPage(anyString(), anyInt(), anyInt(), anyString())).thenReturn(List.of());
        statusPoll.pollAll();

        verify(bostaV2, atLeastOnce()).searchDeliveriesPage(eq("bosta-key-v2"), anyInt(), anyInt(), anyString());
        verify(bostaV2, atLeastOnce()).searchDeliveriesPage(eq("bosta-key-legacy"), anyInt(), anyInt(), anyString());
        verify(bostaV2, never()).searchDeliveriesPage(argThat(k -> k.startsWith("v2:") || k.length() > 40),
            anyInt(), anyInt(), anyString());
    }

    // ── refund details ───────────────────────────────────────────────────────────────────

    @Test
    void r1_refundDetails_v2BoundToRequest_legacyGoldenOpens() {
        UUID t = UUID.randomUUID(), req = UUID.randomUUID(), other = UUID.randomUUID();
        String enc = refundCipher.encrypt(t, req, "{\"iban\":\"EG380019000500000000263180002\"}");
        assertThat(enc).startsWith("v2:").doesNotContain("EG38");
        assertThat(refundCipher.decrypt(t, req, enc)).contains("EG380019000500000000263180002");
        assertThatThrownBy(() -> refundCipher.decrypt(t, other, enc)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> refundCipher.decrypt(other, req, enc)).isInstanceOf(IllegalStateException.class);

        // Pre-key-ring golden (legacy zero key, AAD "tenant|request") still opens through the bean.
        assertThat(refundCipher.decrypt(UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            "SRAXLKtY9vYXY11eKwODKhxxuIlmJFkHrCEsDDZM3uJbOBQ2wajQ73D/cHsgvSsSKR/AEegNB9dcJ/VQ3gWADZI1Mx8="))
            .isEqualTo("{\"iban\":\"EG380019000500000000263180002\"}");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private String base() { return "http://localhost:" + port; }

    private UUID newTenant(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private UUID storeOf(UUID tenant) {
        return jdbc.queryForObject("SELECT id FROM stores WHERE tenant_id = ? LIMIT 1", UUID.class, tenant);
    }

    private void shipment(UUID tenant, UUID store, String tn) {
        UUID order = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, now()) RETURNING id", UUID.class,
            tenant, store, "gid://shopify/Order/" + tn, "ORD-" + tn);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, raw) " +
            "VALUES (?, ?, 'bosta', ?, 'with_courier'::shipment_internal_state, '{\"numberOfAttempts\":0}'::jsonb)",
            tenant, order, tn);
    }

    private String insertState(UUID tenant, String shop) {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        jdbc.update("INSERT INTO shopify_oauth_state (nonce, tenant_id, shop_domain, created_at) VALUES (?, ?, ?, ?)",
            nonce, tenant, shop, Timestamp.from(Instant.now()));
        return nonce;
    }

    private String callbackParams(String nonce, String shop, String code) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("code", code);
        p.put("shop", shop);
        p.put("state", nonce);
        p.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        TreeMap<String, String> sorted = new TreeMap<>(p);
        StringBuilder canonical = new StringBuilder();
        sorted.forEach((k, v) -> {
            if (!canonical.isEmpty()) canonical.append('&');
            canonical.append(k).append('=').append(v);
        });
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            p.put("hmac", HexFormat.of().formatHex(mac.doFinal(canonical.toString().getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        StringBuilder q = new StringBuilder();
        p.forEach((k, v) -> {
            if (!q.isEmpty()) q.append('&');
            q.append(k).append('=').append(v);
        });
        return q.toString();
    }
}
