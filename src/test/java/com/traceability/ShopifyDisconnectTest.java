package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyStoreDisconnectedException;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.notifications.EmailGateway;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PART A — POST /api/v1/shopify/stores/{storeId}/disconnect: Owner-only soft disconnect,
 *          reusing the app/uninstalled status-flip seam, plus best-effort Shopify revoke.
 * PART B — ShopifyTokenProvider.getValidToken() central guard: a 'disconnected' store's
 *          token must never be decrypted or refreshed.
 * PART C — Sibling-door fix: the disconnected guard pushed down to the shared UPDATE_TOKENS /
 *          UPDATE_ACCESS_ONLY writes themselves (WHERE status <> 'disconnected'), plus a
 *          lock-scoped re-check in refreshWithLock()/reExchangeWithLock() before any Shopify
 *          network call. forceReExchangeNow() (POST /stores/{id}/refresh-cc-scopes) had NO
 *          status guard at all — it silently refreshed live Shopify credentials on a
 *          disconnected CC store. The other two callers (getValidToken()'s OAuth and CC
 *          branches) were already guarded at entry; the new lock-scoped checks and the
 *          affected-row-count checks are the backstop for the race window between that
 *          entry check and the row lock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyDisconnectTest {

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
    @Autowired TestRestTemplate     rest;
    @Autowired JdbcTemplate         jdbc;
    @Autowired JwtService           jwtService;
    @Autowired EncryptionService    encryptionService;
    @Autowired ShopifyTokenProvider tokenProvider;

    @MockBean ShopifyGateway shopifyGateway;
    @MockBean JobScheduler   jobScheduler;
    @MockBean EmailGateway   emailGateway;

    private static final String ACCESS_TOKEN = "shpat_disconnect_test_token";

    private record Signup(String token, UUID tenantId) {}

    private String base() { return "http://localhost:" + port; }

    private Signup signupOwner(String company, String username, String email) {
        var req = new SignupRequest(company, username, email, "01012345678", "password99", true);
        var resp = rest.postForEntity(base() + "/api/v1/auth/signup", req, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = resp.getBody().accessToken();
        UUID tenantId = UUID.fromString((String) jwtService.verify(token).getClaim("tenant"));
        return new Signup(token, tenantId);
    }

    private UUID insertStore(UUID tenantId, String shopDomain, String status, String accessTokenEncrypted) {
        UUID storeId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, platform, access_token_encrypted, " +
            "access_token_expires_at, status, import_status) " +
            "VALUES (?, ?, ?, 'shopify', ?, ?, ?::store_status, 'completed')",
            storeId, tenantId, shopDomain, accessTokenEncrypted,
            Timestamp.from(Instant.now().plusSeconds(3600)), status);
        return storeId;
    }

    private ResponseEntity<Map> doDisconnect(String token, UUID storeId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(
            base() + "/api/v1/shopify/stores/" + storeId + "/disconnect",
            HttpMethod.POST, new HttpEntity<>(headers), Map.class);
    }

    // =========================================================================
    // PART A — disconnect endpoint
    // =========================================================================

    @Test
    void owner_disconnectsConnectedStore_returns204AndFlipsStatus() {
        Signup owner = signupOwner("Disconnect A1 Corp", "disc_a1_owner", "disc_a1@test.com");
        String shopDomain = "disc-a1.myshopify.com";
        String encToken = encryptionService.encrypt(ACCESS_TOKEN);
        UUID storeId = insertStore(owner.tenantId(), shopDomain, "connected", encToken);

        var resp = doDisconnect(owner.token(), storeId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, access_token_encrypted FROM stores WHERE id = ?", storeId);
        assertThat(row.get("status")).isEqualTo("disconnected");
        // Seam parity: access_token_encrypted must NOT be nulled — matches
        // ShopifyWebhookProcessorJob.handleAppUninstalled()'s exact behavior.
        assertThat(row.get("access_token_encrypted")).isEqualTo(encToken);

        ArgumentCaptor<String> shopCap  = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> tokenCap = ArgumentCaptor.forClass(String.class);
        verify(shopifyGateway, times(1)).revokeAccessToken(shopCap.capture(), tokenCap.capture());
        assertThat(shopCap.getValue()).isEqualTo(shopDomain);
        assertThat(tokenCap.getValue()).isEqualTo(ACCESS_TOKEN);
    }

    @Test
    void manager_disconnect_returns403() {
        Signup owner = signupOwner("Disconnect A2 Corp", "disc_a2_owner", "disc_a2@test.com");
        UUID storeId = insertStore(owner.tenantId(), "disc-a2.myshopify.com", "connected",
            encryptionService.encrypt(ACCESS_TOKEN));

        UUID managerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, role, active) VALUES (?, ?, 'mgr', 'manager', true)",
            managerId, owner.tenantId());
        String managerToken = jwtService.issueAccessToken(managerId, owner.tenantId(), "manager");

        var resp = doDisconnect(managerToken, storeId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(shopifyGateway, never()).revokeAccessToken(any(), any());

        String status = jdbc.queryForObject("SELECT status FROM stores WHERE id = ?", String.class, storeId);
        assertThat(status).isEqualTo("connected");
    }

    @Test
    void worker_disconnect_returns403() {
        Signup owner = signupOwner("Disconnect A3 Corp", "disc_a3_owner", "disc_a3@test.com");
        UUID storeId = insertStore(owner.tenantId(), "disc-a3.myshopify.com", "connected",
            encryptionService.encrypt(ACCESS_TOKEN));

        UUID workerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, role, active) VALUES (?, ?, 'wkr', 'worker', true)",
            workerId, owner.tenantId());
        String workerToken = jwtService.issueAccessToken(workerId, owner.tenantId(), "worker");

        var resp = doDisconnect(workerToken, storeId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(shopifyGateway, never()).revokeAccessToken(any(), any());
    }

    @Test
    void alreadyDisconnected_returns204NoOp_revokeNotCalled() {
        Signup owner = signupOwner("Disconnect A4 Corp", "disc_a4_owner", "disc_a4@test.com");
        UUID storeId = insertStore(owner.tenantId(), "disc-a4.myshopify.com", "disconnected",
            encryptionService.encrypt(ACCESS_TOKEN));

        var resp = doDisconnect(owner.token(), storeId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(shopifyGateway, never()).revokeAccessToken(any(), any());

        String status = jdbc.queryForObject("SELECT status FROM stores WHERE id = ?", String.class, storeId);
        assertThat(status).isEqualTo("disconnected");
    }

    @Test
    void revokeThrows_localDisconnectStillCommitted() {
        Signup owner = signupOwner("Disconnect A5 Corp", "disc_a5_owner", "disc_a5@test.com");
        String shopDomain = "disc-a5.myshopify.com";
        UUID storeId = insertStore(owner.tenantId(), shopDomain, "connected",
            encryptionService.encrypt(ACCESS_TOKEN));

        doThrow(new RuntimeException("simulated Shopify timeout"))
            .when(shopifyGateway).revokeAccessToken(anyString(), anyString());

        var resp = doDisconnect(owner.token(), storeId);

        // The failed/timed-out revoke must never surface as an error to the caller —
        // the local disconnect already committed before the revoke was attempted.
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String status = jdbc.queryForObject("SELECT status FROM stores WHERE id = ?", String.class, storeId);
        assertThat(status).as("local flip must survive a revoke failure").isEqualTo("disconnected");

        ArgumentCaptor<String> shopCap = ArgumentCaptor.forClass(String.class);
        verify(shopifyGateway, times(1)).revokeAccessToken(shopCap.capture(), anyString());
        assertThat(shopCap.getValue()).isEqualTo(shopDomain);
    }

    @Test
    void disconnect_unknownStore_returns404() {
        Signup owner = signupOwner("Disconnect A6 Corp", "disc_a6_owner", "disc_a6@test.com");

        var resp = doDisconnect(owner.token(), UUID.randomUUID());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // Cross-tenant negative test, paired with a same-tenant positive control on the SAME
    // code path (owner_disconnectsConnectedStore_returns204AndFlipsStatus above proves the
    // identical request succeeds for the rightful tenant). Uses a REAL second tenant's store
    // row — not just a random/nonexistent UUID like disconnect_unknownStore_returns404 — so
    // a 404 here can only come from the tenant_id scoping in ShopifyDisconnectService's query
    // (defense-in-depth) / RLS, not from the row simply not existing.
    @Test
    void disconnect_otherTenantsRealStore_returns404_rowUntouched() {
        Signup victim   = signupOwner("Disconnect A7 Victim Corp", "disc_a7_victim", "disc_a7_victim@test.com");
        Signup attacker = signupOwner("Disconnect A7 Attacker Corp", "disc_a7_attacker", "disc_a7_attacker@test.com");

        String shopDomain = "disc-a7-victim.myshopify.com";
        String encToken = encryptionService.encrypt(ACCESS_TOKEN);
        UUID storeId = insertStore(victim.tenantId(), shopDomain, "connected", encToken);

        var resp = doDisconnect(attacker.token(), storeId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(shopifyGateway, never()).revokeAccessToken(any(), any());

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, access_token_encrypted FROM stores WHERE id = ?", storeId);
        assertThat(row.get("status")).as("victim's store must be untouched by the attacker's call")
            .isEqualTo("connected");
        assertThat(row.get("access_token_encrypted")).isEqualTo(encToken);
    }

    // =========================================================================
    // PART B — ShopifyTokenProvider.getValidToken() central guard
    // =========================================================================

    @Test
    void getValidToken_disconnectedStore_throwsWithoutDecryptOrRefresh() {
        Signup owner = signupOwner("Disconnect B1 Corp", "disc_b1_owner", "disc_b1@test.com");
        // Deliberately NOT a valid ciphertext — if the guard did not short-circuit before
        // decrypt(), this would throw a decryption error instead of our exception, proving
        // the guard fires first.
        UUID storeId = insertStore(owner.tenantId(), "disc-b1.myshopify.com", "disconnected",
            "not-a-valid-ciphertext-blob");

        assertThatThrownBy(() ->
            TenantContext.runAs(owner.tenantId(), () -> tokenProvider.getValidToken(storeId)))
            .isInstanceOf(ShopifyStoreDisconnectedException.class);

        verify(shopifyGateway, never()).refreshAccessToken(any(), any());
        verify(shopifyGateway, never()).exchangeClientCredentials(any(), any(), any());
    }

    // =========================================================================
    // PART C — sibling-door fix (write-level guard on UPDATE_TOKENS / UPDATE_ACCESS_ONLY)
    // =========================================================================

    // The actual reported gap: forceReExchangeNow() has NO entry-level status check at all.
    // Before this fix it went straight to a live Shopify CC exchange call and persisted the
    // result on a disconnected store. Real HTTP path, matching how Part A tests the sibling
    // disconnect endpoint.
    @Test
    void forceReExchangeNow_disconnectedCcStore_returns409_noExchangeCall_noWrite() {
        Signup owner = signupOwner("Disconnect C1 Corp", "disc_c1_owner", "disc_c1@test.com");
        String shopDomain = "disc-c1.myshopify.com";
        String encToken   = encryptionService.encrypt("shpat_cc_sentinel_token");
        String encClientId = encryptionService.encrypt("client-id-sentinel");
        String encSecret   = encryptionService.encrypt("client-secret-sentinel");
        String scopesSentinel = "read_products,read_orders";
        UUID storeId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, platform, access_token_encrypted, " +
            "access_token_expires_at, status, import_status, connection_type, client_id_encrypted, " +
            "api_secret_encrypted, access_token_scopes) " +
            "VALUES (?, ?, ?, 'shopify', ?, ?, 'disconnected', 'completed', 'custom_app_cc', ?, ?, ?)",
            storeId, owner.tenantId(), shopDomain, encToken,
            Timestamp.from(Instant.now().plusSeconds(3600)), encClientId, encSecret, scopesSentinel);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(owner.token());
        var resp = rest.exchange(
            base() + "/api/v1/shopify/stores/" + storeId + "/refresh-cc-scopes",
            HttpMethod.POST, new HttpEntity<>(headers), Map.class);

        // Sane disconnected signal — not a silent 200.
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).containsEntry("error", "SHOPIFY_STORE_DISCONNECTED");

        // No live Shopify call — the lock-scoped re-check fires before the network call,
        // not merely before the DB write.
        verify(shopifyGateway, never()).exchangeClientCredentials(any(), any(), any());

        // 0 rows written: token, scopes, and status all byte-for-byte unchanged.
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, access_token_encrypted, access_token_scopes FROM stores WHERE id = ?", storeId);
        assertThat(row.get("status")).isEqualTo("disconnected");
        assertThat(row.get("access_token_encrypted")).isEqualTo(encToken);
        assertThat(row.get("access_token_scopes")).isEqualTo(scopesSentinel);
    }

    // Race-window backstop for refreshWithLock() (OAuth path, via getValidToken()). Realistically
    // unreachable while the row lock is held (Postgres blocks a concurrent writer), but this
    // proves the affected-row check is live code, not decoration: the mocked gateway call
    // flips the row to disconnected (same thread/transaction, so it's visible to the later
    // UPDATE) before returning a token — without the `rows == 0` check this would silently
    // persist "shpat_should_never_be_persisted" and return it as if nothing were wrong.
    @Test
    void refreshWithLock_disconnectedBetweenRecheckAndWrite_backstopRejectsWrite() {
        Signup owner = signupOwner("Disconnect C2 Corp", "disc_c2_owner", "disc_c2@test.com");
        String shopDomain = "disc-c2.myshopify.com";
        String originalEncToken   = encryptionService.encrypt("shpat_original");
        String originalEncRefresh = encryptionService.encrypt("shprt_original");
        UUID storeId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, platform, access_token_encrypted, " +
            "access_token_expires_at, refresh_token_encrypted, refresh_token_expires_at, " +
            "status, import_status, connection_type) " +
            "VALUES (?, ?, ?, 'shopify', ?, ?, ?, ?, 'connected', 'completed', 'oauth')",
            storeId, owner.tenantId(), shopDomain, originalEncToken,
            Timestamp.from(Instant.now().plusSeconds(60)), originalEncRefresh,
            Timestamp.from(Instant.now().plusSeconds(7_000_000)));

        when(shopifyGateway.refreshAccessToken(eq(shopDomain), anyString()))
            .thenAnswer(invocation -> {
                jdbc.update("UPDATE stores SET status = 'disconnected' WHERE id = ?", storeId);
                return new ShopifyGateway.TokenResponse(
                    "shpat_should_never_be_persisted", "shprt_should_never_be_persisted",
                    3600L, 7776000L, null);
            });

        assertThatThrownBy(() ->
            TenantContext.runAs(owner.tenantId(), () -> tokenProvider.getValidToken(storeId)))
            .isInstanceOf(ShopifyStoreDisconnectedException.class);

        // The fake token from the mocked call must never have been persisted.
        String tokenAfter = jdbc.queryForObject(
            "SELECT access_token_encrypted FROM stores WHERE id = ?", String.class, storeId);
        assertThat(tokenAfter).isEqualTo(originalEncToken);
    }

    // Same backstop, other shared write: reExchangeWithLock() via getValidToken()'s CC branch
    // (force=false) — distinct code path from forceReExchangeNow() (force=true) above, but the
    // same shared method and the same UPDATE_ACCESS_ONLY write.
    @Test
    void reExchangeWithLock_viaGetValidToken_disconnectedBetweenRecheckAndWrite_backstopRejectsWrite() {
        Signup owner = signupOwner("Disconnect C3 Corp", "disc_c3_owner", "disc_c3@test.com");
        String shopDomain = "disc-c3.myshopify.com";
        String originalEncToken = encryptionService.encrypt("shpat_cc_original");
        String encClientId = encryptionService.encrypt("client-id-c3");
        String encSecret   = encryptionService.encrypt("client-secret-c3");
        UUID storeId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, platform, access_token_encrypted, " +
            "access_token_expires_at, status, import_status, connection_type, client_id_encrypted, " +
            "api_secret_encrypted) " +
            "VALUES (?, ?, ?, 'shopify', ?, ?, 'connected', 'completed', 'custom_app_cc', ?, ?)",
            storeId, owner.tenantId(), shopDomain, originalEncToken,
            Timestamp.from(Instant.now().plusSeconds(60)), encClientId, encSecret);

        when(shopifyGateway.exchangeClientCredentials(eq(shopDomain), anyString(), anyString()))
            .thenAnswer(invocation -> {
                jdbc.update("UPDATE stores SET status = 'disconnected' WHERE id = ?", storeId);
                return new ShopifyGateway.TokenResponse(
                    "shpat_should_never_be_persisted", null, 86399L, 0, null);
            });

        assertThatThrownBy(() ->
            TenantContext.runAs(owner.tenantId(), () -> tokenProvider.getValidToken(storeId)))
            .isInstanceOf(ShopifyStoreDisconnectedException.class);

        String tokenAfter = jdbc.queryForObject(
            "SELECT access_token_encrypted FROM stores WHERE id = ?", String.class, storeId);
        assertThat(tokenAfter).isEqualTo(originalEncToken);
    }

    // Positive control: a genuinely connected store's OAuth refresh must still work — proves
    // the new `AND status <> 'disconnected'` WHERE clause doesn't break the happy path.
    @Test
    void getValidToken_connectedStore_oauthRefresh_stillWorks_positiveControl() {
        Signup owner = signupOwner("Disconnect C4 Corp", "disc_c4_owner", "disc_c4@test.com");
        String shopDomain = "disc-c4.myshopify.com";
        String oldEncToken   = encryptionService.encrypt("shpat_old");
        String oldEncRefresh = encryptionService.encrypt("shprt_old");
        UUID storeId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, platform, access_token_encrypted, " +
            "access_token_expires_at, refresh_token_encrypted, refresh_token_expires_at, " +
            "status, import_status, connection_type) " +
            "VALUES (?, ?, ?, 'shopify', ?, ?, ?, ?, 'connected', 'completed', 'oauth')",
            storeId, owner.tenantId(), shopDomain, oldEncToken,
            Timestamp.from(Instant.now().plusSeconds(60)), oldEncRefresh,
            Timestamp.from(Instant.now().plusSeconds(7_000_000)));

        when(shopifyGateway.refreshAccessToken(eq(shopDomain), anyString()))
            .thenReturn(new ShopifyGateway.TokenResponse(
                "shpat_new", "shprt_new", 3600L, 7776000L, null));

        String result = TenantContext.runAs(owner.tenantId(), () -> tokenProvider.getValidToken(storeId));

        assertThat(result).isEqualTo("shpat_new");
        String encTokenAfter = jdbc.queryForObject(
            "SELECT access_token_encrypted FROM stores WHERE id = ?", String.class, storeId);
        assertThat(encryptionService.decrypt(encTokenAfter)).isEqualTo("shpat_new");
    }
}
