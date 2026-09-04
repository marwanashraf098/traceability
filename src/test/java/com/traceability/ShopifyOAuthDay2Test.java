package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyImportJob;
import com.traceability.notifications.EmailGateway;
import com.traceability.integrations.shopify.ShopifyStateCleanupJob; // instantiated directly — not autowired (bean is conditional on background-job-server.enabled)
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
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * OAuth Day 2 integration tests — FR-3.1 resolve-or-create decision tree.
 *
 * Tests 1–7: decision tree branches (Path-1 new, Path-1 existing, Path-1 cross-tenant,
 *             Path-2 new provisioning, Path-2 existing, race, atomicity).
 * Tests 8–10: A1 timestamp freshness, A2 state sweep, RLS opacity of cross-tenant detect.
 *
 * ShopifyGateway and JobScheduler are @MockBean — no real Shopify calls.
 * Real Postgres via Testcontainers — required for provisioning, RLS, and race tests.
 *
 * Separate @Container from Day 1 tests: Spring context isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyOAuthDay2Test {

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
    @MockBean EmailGateway     emailGateway; // MagicLinkService dependency (context wiring only — PROVISIONED path is dead, see LinkOutcome)

    @Value("${shopify.client-secret}")
    String clientSecret;

    private String ownerToken;
    private UUID   ownerTenantId;

    // No-redirect RestTemplate — avoids following 302 to standalone SPA not running in tests.
    private RestTemplate noRedirectRest;

    private static final String CODE_A = "auth-code-a";
    private static final String CODE_B = "auth-code-b";
    private static final String TOKEN_A = "shpat_test_token_day2_a";
    private static final String TOKEN_B = "shpat_test_token_day2_b";
    // TokenResponse wrappers — exchangeCode() now returns these instead of a plain String.
    private static final ShopifyGateway.TokenResponse EXCHANGE_A =
        new ShopifyGateway.TokenResponse(TOKEN_A, "shprt_refresh_a", 3600L, 7776000L, null);
    private static final ShopifyGateway.TokenResponse EXCHANGE_B =
        new ShopifyGateway.TokenResponse(TOKEN_B, "shprt_refresh_b", 3600L, 7776000L, null);
    private static final String SHOP_PATH1    = "path1.myshopify.com";
    private static final String SHOP_PATH2    = "path2-new.myshopify.com";
    private static final String SHOP_CROSS    = "cross-tenant.myshopify.com";
    private static final String SHOP_RACE     = "race-shop.myshopify.com";
    private static final String SHOP_ATOMIC   = "atomic-test.myshopify.com";
    private static final String SHOP_PROV_EMAIL = "owner@atomic-test.myshopify.com";
    private static final String SHOP_PROV_NAME  = "Atomic Shop";

    @BeforeAll
    void setup() {
        HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        noRedirectRest = new RestTemplate(new JdkClientHttpRequestFactory(httpClient));
        noRedirectRest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) { return false; }
        });

        // Sign up a tenant/owner for Path-1 tests
        var req = new SignupRequest("Day2 Corp", "day2_owner", "day2@test.com", "01012345678", "password99", true);
        var resp = rest.postForEntity(base() + "/api/v1/auth/signup", req, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ownerToken    = resp.getBody().accessToken();
        ownerTenantId = UUID.fromString((String) jwtService.verify(ownerToken).getClaim("tenant"));
    }

    @BeforeEach
    void cleanState() {
        jdbc.execute("DELETE FROM shopify_oauth_state");
        // Remove test stores but not the owner's tenant/user (created in @BeforeAll)
        jdbc.execute("DELETE FROM stores WHERE shop_domain IN ('" + SHOP_PATH1 + "','" +
            SHOP_PATH2 + "','" + SHOP_CROSS + "','" + SHOP_RACE + "','" + SHOP_ATOMIC + "')");
        // Clean up Path-2 provisioned tenants (distinct from ownerTenantId)
        jdbc.execute("DELETE FROM stores WHERE shop_domain IN ('" + SHOP_PATH2 +
            "','" + SHOP_RACE + "','" + SHOP_ATOMIC + "')");
        // Cascade: remove tenants provisioned in prior tests (not the owner's tenant)
        // magic_link_tokens FK on users must be deleted first
        jdbc.execute("DELETE FROM magic_link_tokens WHERE user_id IN (" +
            "SELECT id FROM users WHERE email IN ('owner@" + SHOP_PATH2 +
            "','owner@" + SHOP_RACE + "','" + SHOP_PROV_EMAIL + "'))");
        jdbc.execute("DELETE FROM users WHERE email IN ('owner@" + SHOP_PATH2 +
            "','owner@" + SHOP_RACE + "','" + SHOP_PROV_EMAIL + "')");
        jdbc.execute("DELETE FROM tenants WHERE name IN ('Path2 New Shop','Race Shop','" + SHOP_PROV_NAME + "')");
        reset(jobScheduler);
    }

    // -----------------------------------------------------------------------
    // 1. Path-1 new shop → store created under state.tenantId, import enqueued
    // -----------------------------------------------------------------------
    @Test
    void path1_newShop_storeLinkedAndImportEnqueued() {
        when(shopifyGateway.exchangeCode(eq(SHOP_PATH1), eq(CODE_A))).thenReturn(EXCHANGE_A);

        String nonce = insertState(ownerTenantId, SHOP_PATH1, Instant.now());

        var resp = noRedirectRest.getForEntity(
            base() + "/auth/shopify/callback?" + callbackParams(nonce, SHOP_PATH1, CODE_A), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE shop_domain = ? AND tenant_id = ?",
            Integer.class, SHOP_PATH1, ownerTenantId);
        assertThat(count).isEqualTo(1);

        verify(jobScheduler, times(2)).enqueue(any(org.jobrunr.jobs.lambdas.JobLambda.class));
    }

    // -----------------------------------------------------------------------
    // 2. Path-1 same-tenant re-install → token updated, no second store, no new owner
    // -----------------------------------------------------------------------
    @Test
    void path1_sameTenanReinstall_idempotentRelink() {
        when(shopifyGateway.exchangeCode(eq(SHOP_PATH1), eq(CODE_A))).thenReturn(EXCHANGE_A);
        when(shopifyGateway.exchangeCode(eq(SHOP_PATH1), eq(CODE_B))).thenReturn(EXCHANGE_B);

        // First install
        String nonce1 = insertState(ownerTenantId, SHOP_PATH1, Instant.now());
        noRedirectRest.getForEntity(
            base() + "/auth/shopify/callback?" + callbackParams(nonce1, SHOP_PATH1, CODE_A), Void.class);

        String tokenAfterFirst = jdbc.queryForObject(
            "SELECT access_token_encrypted FROM stores WHERE shop_domain = ?",
            String.class, SHOP_PATH1);

        // Re-install with same tenant
        String nonce2 = insertState(ownerTenantId, SHOP_PATH1, Instant.now());
        var resp = noRedirectRest.getForEntity(
            base() + "/auth/shopify/callback?" + callbackParams(nonce2, SHOP_PATH1, CODE_B), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);

        Integer storeCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE shop_domain = ?", Integer.class, SHOP_PATH1);
        assertThat(storeCount).isEqualTo(1); // exactly one store row

        String tokenAfterSecond = jdbc.queryForObject(
            "SELECT access_token_encrypted FROM stores WHERE shop_domain = ?",
            String.class, SHOP_PATH1);
        assertThat(tokenAfterSecond).isNotEqualTo(tokenAfterFirst); // token updated
    }

    // -----------------------------------------------------------------------
    // 3. Path-1, shop owned by DIFFERENT tenant → SHOPIFY_STORE_ALREADY_CONNECTED redirect
    //    Existing row's token and tenant must be unchanged (byte-for-byte).
    // -----------------------------------------------------------------------
    @Test
    void path1_crossTenant_redirectToError_existingRowUntouched() {
        // Establish SHOP_CROSS under ownerTenantId
        when(shopifyGateway.exchangeCode(eq(SHOP_CROSS), eq(CODE_A))).thenReturn(EXCHANGE_A);
        String nonce1 = insertState(ownerTenantId, SHOP_CROSS, Instant.now());
        noRedirectRest.getForEntity(
            base() + "/auth/shopify/callback?" + callbackParams(nonce1, SHOP_CROSS, CODE_A), Void.class);

        String originalToken = jdbc.queryForObject(
            "SELECT access_token_encrypted FROM stores WHERE shop_domain = ?",
            String.class, SHOP_CROSS);
        UUID originalTenant = jdbc.queryForObject(
            "SELECT tenant_id FROM stores WHERE shop_domain = ?",
            UUID.class, SHOP_CROSS);

        // Sign up a second tenant (the intruder)
        var intruderResp = rest.postForEntity(
            base() + "/api/v1/auth/signup",
            new SignupRequest("Intruder Corp", "intruder_day2", "intruder_day2@test.com", "01012345678", "password99", true),
            TokenResponse.class);
        UUID intruderTenantId = UUID.fromString(
            (String) jwtService.verify(intruderResp.getBody().accessToken()).getClaim("tenant"));

        when(shopifyGateway.exchangeCode(eq(SHOP_CROSS), eq(CODE_B))).thenReturn(EXCHANGE_B);
        String nonce2 = insertState(intruderTenantId, SHOP_CROSS, Instant.now());
        var resp = noRedirectRest.getForEntity(
            base() + "/auth/shopify/callback?" + callbackParams(nonce2, SHOP_CROSS, CODE_B), Void.class);

        // Must redirect to error page — never return 500
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        String location = resp.getHeaders().getFirst("Location");
        assertThat(location).contains("SHOPIFY_STORE_ALREADY_CONNECTED");

        // Original row unchanged
        String afterToken = jdbc.queryForObject(
            "SELECT access_token_encrypted FROM stores WHERE shop_domain = ?",
            String.class, SHOP_CROSS);
        UUID afterTenant = jdbc.queryForObject(
            "SELECT tenant_id FROM stores WHERE shop_domain = ?",
            UUID.class, SHOP_CROSS);
        assertThat(afterToken).isEqualTo(originalToken);
        assertThat(afterTenant).isEqualTo(originalTenant);
    }

    // -----------------------------------------------------------------------
    // 4. Path-2 new shop, cold install (Option A, 2026-09-04): creates NOTHING —
    //    no tenant, no owner, no store, no jobs — and redirects back into the
    //    embedded/standalone app root, never to a pricing/setup-pending page.
    //    fetchShop() must never even be called — zero provisioning attempt.
    // -----------------------------------------------------------------------
    @Test
    void path2_newShop_coldInstall_createsNothing_redirectsToEmbedded() {
        when(shopifyGateway.exchangeCode(eq(SHOP_PATH2), eq(CODE_A))).thenReturn(EXCHANGE_A);

        String nonce = insertState(null, SHOP_PATH2, Instant.now());
        var resp = noRedirectRest.getForEntity(
            base() + "/auth/shopify/callback?" + callbackParams(nonce, SHOP_PATH2, CODE_A), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        String location = resp.getHeaders().getFirst("Location");
        assertThat(location)
            .as("must redirect back into the app — never a pricing/paywall/setup-pending page")
            .doesNotContain("setup-pending")
            .doesNotContain("connect/error")
            .doesNotContain("pricing");

        // Zero tenant, owner, store rows — nothing created
        Integer tenantCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tenants WHERE name = 'Path2 New Shop'", Integer.class);
        assertThat(tenantCount).as("cold install must create no tenant").isEqualTo(0);

        Integer ownerCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE email = 'owner@path2-new.myshopify.com'", Integer.class);
        assertThat(ownerCount).as("cold install must create no owner user").isEqualTo(0);

        Integer storeCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE shop_domain = ?", Integer.class, SHOP_PATH2);
        assertThat(storeCount).as("cold install must create no store").isEqualTo(0);

        // No jobs enqueued
        verify(jobScheduler, never()).enqueue(any(org.jobrunr.jobs.lambdas.JobLambda.class));

        // fetchShop() is what would surface the shop's owner email for provisioning —
        // must never be invoked, proving zero attempt was made (not just a rolled-back one).
        verify(shopifyGateway, never()).fetchShop(anyString(), anyString());
    }

    // -----------------------------------------------------------------------
    // 5. Path-2, shop ALREADY linked to a tenant (seeded directly — Option A cold
    //    installs no longer provision, so we can't establish this via a first Path-2
    //    install any more) → idempotent re-link, no new tenant/owner/store.
    // -----------------------------------------------------------------------
    @Test
    void path2_existingShop_idempotentLink() {
        UUID seededTenant = jdbc.queryForObject(
            "INSERT INTO tenants (name) VALUES ('Path2 New Shop') RETURNING id", UUID.class);
        jdbc.update(
            "INSERT INTO users (tenant_id, name, email, role) VALUES (?, 'Seed Owner', ?, 'owner')",
            seededTenant, "owner@path2-new.myshopify.com");
        jdbc.update(
            "INSERT INTO stores (tenant_id, shop_domain, platform, access_token_encrypted, status, import_status) " +
            "VALUES (?, ?, 'shopify', 'seed-token', 'connected', 'idle')",
            seededTenant, SHOP_PATH2);

        when(shopifyGateway.exchangeCode(eq(SHOP_PATH2), eq(CODE_B))).thenReturn(EXCHANGE_B);

        String nonce2 = insertState(null, SHOP_PATH2, Instant.now());
        var resp = noRedirectRest.getForEntity(
            base() + "/auth/shopify/callback?" + callbackParams(nonce2, SHOP_PATH2, CODE_B), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);

        long tenantCountAfter = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tenants WHERE name = 'Path2 New Shop'", Long.class);
        long ownerCountAfter = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE email = 'owner@path2-new.myshopify.com'", Long.class);

        assertThat(tenantCountAfter).isEqualTo(1); // still exactly one — no new tenant
        assertThat(ownerCountAfter).isEqualTo(1);  // still exactly one — no new owner

        Integer storeCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE shop_domain = ?", Integer.class, SHOP_PATH2);
        assertThat(storeCount).isEqualTo(1); // still exactly one store

        String token = jdbc.queryForObject(
            "SELECT access_token_encrypted FROM stores WHERE shop_domain = ?", String.class, SHOP_PATH2);
        assertThat(token).isNotEqualTo("seed-token"); // token updated — idempotent link still functions
    }

    // -----------------------------------------------------------------------
    // 6. Concurrent double cold-install (real threads, same never-seen Path-2 shop).
    //    Option A (2026-09-04): cold path2() no longer writes anything, so the
    //    provisioning race this test originally exercised no longer exists — both
    //    concurrent installs must complete cleanly (no 5xx) and create ZERO rows.
    // -----------------------------------------------------------------------
    @Test
    void concurrentDoubleInstall_race_coldInstall_createsNothing() throws Exception {
        when(shopifyGateway.exchangeCode(eq(SHOP_RACE), eq(CODE_A))).thenReturn(EXCHANGE_A);
        when(shopifyGateway.exchangeCode(eq(SHOP_RACE), eq(CODE_B))).thenReturn(EXCHANGE_B);

        String nonceA = insertState(null, SHOP_RACE, Instant.now());
        String nonceB = insertState(null, SHOP_RACE, Instant.now());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go    = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(0);

        java.util.function.BiFunction<String, String, Runnable> makeRequest =
            (nonce, code) -> () -> {
                try {
                    ready.countDown();
                    go.await();
                    var r = noRedirectRest.getForEntity(
                        base() + "/auth/shopify/callback?" + callbackParams(nonce, SHOP_RACE, code),
                        Void.class);
                    if (r.getStatusCode() == HttpStatus.FOUND) {
                        successes.incrementAndGet();
                    }
                } catch (Exception e) {
                    // thread failure — counted as 0 success from this thread
                }
            };

        Thread t1 = new Thread(makeRequest.apply(nonceA, CODE_A));
        Thread t2 = new Thread(makeRequest.apply(nonceB, CODE_B));
        t1.start();
        t2.start();
        ready.await();
        go.countDown(); // release both threads simultaneously
        t1.join();
        t2.join();

        // Both threads must complete without 5xx — at least one 302
        assertThat(successes.get()).isGreaterThanOrEqualTo(1);

        // Database: zero of each — cold install creates nothing, even under concurrency
        Integer tenantCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tenants WHERE name = 'Race Shop'", Integer.class);
        assertThat(tenantCount).as("cold install creates no tenant, even under concurrency").isEqualTo(0);

        Integer ownerCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE email = 'owner@race-shop.myshopify.com'", Integer.class);
        assertThat(ownerCount).as("cold install creates no owner, even under concurrency").isEqualTo(0);

        Integer storeCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE shop_domain = ?", Integer.class, SHOP_RACE);
        assertThat(storeCount).as("cold install creates no store, even under concurrency").isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // 7. Provisioning atomicity: a forced stores 23505 inside the function
    //    → zero orphan tenants, zero orphan owner users
    // -----------------------------------------------------------------------
    @Test
    void provisioningAtomicity_storeConflict_noOrphanTenantOrUser() {
        // Pre-seed a store row to cause 23505 when the function tries to INSERT it
        jdbc.execute(
            "INSERT INTO tenants (name) VALUES ('Pre-existing Tenant') ON CONFLICT DO NOTHING");
        UUID preTenantId = jdbc.queryForObject(
            "SELECT id FROM tenants WHERE name = 'Pre-existing Tenant'", UUID.class);
        jdbc.update(
            "INSERT INTO stores (tenant_id, shop_domain, platform, access_token_encrypted, status, import_status) " +
            "VALUES (?, ?, 'shopify', 'some-token', 'connected', 'idle') ON CONFLICT DO NOTHING",
            preTenantId, SHOP_ATOMIC);

        // Directly call the provisioning function — expect 23505 → rolled back
        boolean threw = false;
        try {
            jdbc.query(
                "SELECT tenant_id FROM provision_tenant_from_shopify(?,?,?,?,?)",
                rs -> null,
                SHOP_ATOMIC, SHOP_PROV_EMAIL, SHOP_PROV_NAME, "Africa/Cairo", "encrypted-token-x");
        } catch (Exception e) {
            threw = true;
            // expect duplicate key (23505) or similar
        }
        assertThat(threw).as("provision call must throw on duplicate shop_domain").isTrue();

        // Zero orphan tenants for our test shop name
        Integer orphanTenants = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tenants WHERE name = ?", Integer.class, SHOP_PROV_NAME);
        assertThat(orphanTenants).as("no orphan tenants").isEqualTo(0);

        // Zero orphan users for our test email
        Integer orphanUsers = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, SHOP_PROV_EMAIL);
        assertThat(orphanUsers).as("no orphan users").isEqualTo(0);

        // Cleanup
        jdbc.update("DELETE FROM stores WHERE shop_domain = ?", SHOP_ATOMIC);
        jdbc.execute("DELETE FROM tenants WHERE name = 'Pre-existing Tenant'");
    }

    // -----------------------------------------------------------------------
    // 8. A1: Stale timestamp (>300s ago) with valid HMAC → SHOPIFY_REQUEST_EXPIRED
    //    Applies to BOTH /auth/shopify/install and /auth/shopify/callback
    // -----------------------------------------------------------------------
    @Test
    void timestampFreshness_staleTimestamp_returnsRequestExpired() {
        long staleTs = Instant.now().minus(10, ChronoUnit.MINUTES).getEpochSecond();

        // Install endpoint
        Map<String, String> installParams = new LinkedHashMap<>();
        installParams.put("shop",      SHOP_PATH1);
        installParams.put("timestamp", String.valueOf(staleTs));
        installParams.put("hmac",      computeHmac(installParams));

        var installResp = noRedirectRest.getForEntity(
            base() + "/auth/shopify/install?" + buildQueryString(installParams), Map.class);
        assertThat(installResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(installResp.getBody()).containsEntry("code", "SHOPIFY_REQUEST_EXPIRED");

        // Callback endpoint — need a valid state nonce first
        String nonce = insertState(ownerTenantId, SHOP_PATH1, Instant.now());
        Map<String, String> cbParams = new LinkedHashMap<>();
        cbParams.put("code",      CODE_A);
        cbParams.put("shop",      SHOP_PATH1);
        cbParams.put("state",     nonce);
        cbParams.put("timestamp", String.valueOf(staleTs));
        cbParams.put("hmac",      computeHmac(cbParams));

        var cbResp = noRedirectRest.getForEntity(
            base() + "/auth/shopify/callback?" + buildQueryString(cbParams), Map.class);
        assertThat(cbResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(cbResp.getBody()).containsEntry("code", "SHOPIFY_REQUEST_EXPIRED");
    }

    // -----------------------------------------------------------------------
    // 9. A2: State sweep — rows >1h deleted, fresh rows retained
    // -----------------------------------------------------------------------
    @Test
    void stateSweep_deletesStaleRows_keepsFreshRows() {
        // Insert one stale and one fresh row
        String staleNonce = insertState(ownerTenantId, SHOP_PATH1,
            Instant.now().minus(2, ChronoUnit.HOURS));
        String freshNonce = insertState(ownerTenantId, SHOP_PATH1, Instant.now());

        new ShopifyStateCleanupJob(jdbc).purgeExpiredStates();

        Integer staleRemaining = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_oauth_state WHERE nonce = ?", Integer.class, staleNonce);
        Integer freshRemaining = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_oauth_state WHERE nonce = ?", Integer.class, freshNonce);

        assertThat(staleRemaining).as("stale row must be deleted").isEqualTo(0);
        assertThat(freshRemaining).as("fresh row must be retained").isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // 10. Cross-tenant detection works via resolve_tenant_by_shop_domain (not via
    //     tenant-scoped SELECT): verify reject fires even when the existing store
    //     would be invisible under the intended tenant's RLS GUC.
    //
    //     We confirm this by checking that resolve_tenant_by_shop_domain returns
    //     the owning tenant WITHOUT any GUC set (simulating a call with no context),
    //     then check that the callback correctly redirects to the error page.
    // -----------------------------------------------------------------------
    @Test
    void crossTenantDetect_usesDefinerNotRlsSelect() {
        // Establish SHOP_CROSS under ownerTenantId via the callback (identical to test 3 setup)
        when(shopifyGateway.exchangeCode(eq(SHOP_CROSS), eq(CODE_A))).thenReturn(EXCHANGE_A);
        String nonce1 = insertState(ownerTenantId, SHOP_CROSS, Instant.now());
        noRedirectRest.getForEntity(
            base() + "/auth/shopify/callback?" + callbackParams(nonce1, SHOP_CROSS, CODE_A), Void.class);

        // Directly verify that resolve_tenant_by_shop_domain sees the store WITHOUT GUC set.
        // If cross-tenant detection were done via a tenant-scoped SELECT instead, with
        // a DIFFERENT tenant's GUC set it would return null (hidden by RLS).
        UUID resolvedOwner = jdbc.queryForObject(
            "SELECT resolve_tenant_by_shop_domain(?)", UUID.class, SHOP_CROSS);
        assertThat(resolvedOwner)
            .as("DEFINER function must see the owning tenant without GUC set")
            .isEqualTo(ownerTenantId);

        // Second tenant tries to claim the same shop
        var intruderResp = rest.postForEntity(
            base() + "/api/v1/auth/signup",
            new SignupRequest("RLS Test Corp", "rls_test_day2", "rls_test@test.com", "01012345678", "password99", true),
            TokenResponse.class);
        UUID intruderTenantId = UUID.fromString(
            (String) jwtService.verify(intruderResp.getBody().accessToken()).getClaim("tenant"));

        when(shopifyGateway.exchangeCode(eq(SHOP_CROSS), eq(CODE_B))).thenReturn(EXCHANGE_B);
        String nonce2 = insertState(intruderTenantId, SHOP_CROSS, Instant.now());
        var resp = noRedirectRest.getForEntity(
            base() + "/auth/shopify/callback?" + callbackParams(nonce2, SHOP_CROSS, CODE_B), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getFirst("Location")).contains("SHOPIFY_STORE_ALREADY_CONNECTED");
    }

    // ---- helpers ----------------------------------------------------------

    private String base() { return "http://localhost:" + port; }

    private String insertState(UUID tenantId, String shopDomain, Instant createdAt) {
        byte[] nonceBytes = new byte[16];
        new SecureRandom().nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        jdbc.update(
            "INSERT INTO shopify_oauth_state (nonce, tenant_id, shop_domain, created_at) VALUES (?, ?, ?, ?)",
            nonce, tenantId, shopDomain, Timestamp.from(createdAt));
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
