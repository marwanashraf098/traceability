package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.account.AuditService;
import com.traceability.account.ConnectionsController;
import com.traceability.account.OnboardingController;
import com.traceability.account.OnboardingService;
import com.traceability.identity.CustomUserDetails;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * FR-1.2 (connections + onboarding) backend tests.
 *
 * Matrix:
 *   c1  — connections: no integrations → both false
 *   c2  — connections: Shopify connected → shopify.connected=true
 *   c3  — connections: Bosta active → bosta.connected=true
 *   o1  — onboarding: fresh tenant → connect_shopify/connect_bosta/test_label/
 *         first_receiving pending; location already done (the @BeforeAll fixture
 *         creates one location per tenant for receipt-FK purposes — see setup())
 *   o2  — onboarding: Shopify connected → connect_shopify done
 *   o3  — onboarding: location exists → location done (the shared fixture location)
 *   o4  — onboarding: label reprint exists → test_label done
 *   o5  — onboarding: finalized receipt → first_receiving done
 *   o6  — onboarding: all signals present → allDone=true
 *   o7  — onboarding: manual check on a step with auto=false → done=true, auto=false
 *   o8  — onboarding: manual uncheck clears the flag only — auto still true keeps done=true
 *   o9  — onboarding: unknown step key → 400, no write
 *   o10 — onboarding: checking an already-checked step is idempotent (no duplicate in the array)
 *   o11 — onboarding: app_user (RLS enforced, no BYPASSRLS) round-trip — POST /steps then
 *         GET /status as two separate app_user transactions, proving tenants.
 *         onboarding_manual_steps actually persists and reads back under RLS, not just
 *         under the postgres/BYPASSRLS connection every other test in this class uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectionsOnboardingTest {

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

    @Autowired ConnectionsController connCtl;
    @Autowired OnboardingController  onboardCtl;
    @Autowired OnboardingService     onboardSvc;
    @Autowired EncryptionService     encSvc;
    @Autowired JdbcTemplate          jdbc;
    @Autowired ObjectMapper          mapper;
    @MockBean  BostaGateway          bostaGateway;
    @MockBean  JobScheduler          jobScheduler;

    UUID tenantId, ownerId, storeId, locationId, variantId;

    // ---- app_user infrastructure (RLS enforced, no BYPASSRLS — see o11) ----
    // Same construction pattern as InventoryLedgerTest's appUserLedger: a real,
    // non-Spring-proxied OnboardingController wired to an app_user TenantAwareDataSource.
    //
    // status() (the read) explicitly calls its own this.tx.execute(...) regardless of
    // proxying, so no external wrapper is needed for it. setStep() (the write) does NOT —
    // it has no explicit transaction boundary of its own and relies entirely on
    // OnboardingService.setManualStep()'s @Transactional annotation, which is only
    // AOP-active on a Spring-managed proxy. This bare `new OnboardingService(...)` is not
    // one, so o11 wraps the setStep() call itself in appUserTx — verified empirically:
    // without this wrapper, the write's INSERT/UPDATE runs outside any transaction,
    // TenantAwareConnection.setAutoCommit(false) never fires, no GUC gets set, and the
    // audit_log INSERT is rejected by RLS with "new row violates row-level security
    // policy" — a real finding this test surfaced, not a production bug (in production
    // OnboardingService is a real Spring @Service bean and @Transactional IS AOP-active).
    OnboardingController appUserOnboardCtl;
    TransactionTemplate  appUserTx;

    @BeforeAll
    void setup() {
        tenantId   = UUID.randomUUID();
        ownerId    = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        locationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ConnTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'o@c.local', 'h', 'owner')", ownerId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'conn.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_default) " +
                    "VALUES (?, ?, 'Main', true)", locationId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-CO', 'Widget', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-CO', 'Red', 'W-CO')", variantId, tenantId, productId);

        // app_user datasource — TestSetup (ApplicationReadyEvent) ran before @BeforeAll,
        // so 'testpw' is already set.
        DriverManagerDataSource rawAppUser = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawAppUser);
        JdbcTemplate appUserJdbc = new JdbcTemplate(appUserDs);
        DataSourceTransactionManager appUserTxm = new DataSourceTransactionManager(appUserDs);
        appUserTx = new TransactionTemplate(appUserTxm);
        AuditService appUserAudit = new AuditService(appUserJdbc, mapper);
        OnboardingService appUserOnboardSvc = new OnboardingService(appUserJdbc, appUserAudit);
        appUserOnboardCtl = new OnboardingController(appUserJdbc, appUserTxm, appUserOnboardSvc);
    }

    @BeforeEach
    void ctx() {
        TenantContext.set(tenantId);
        var p = principal();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities()));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        // Reset integration state
        jdbc.update("UPDATE stores SET status='disconnected', import_status='idle' WHERE tenant_id=?", tenantId);
        jdbc.update("DELETE FROM courier_accounts WHERE tenant_id=?", tenantId);
        jdbc.update("DELETE FROM label_reprints WHERE tenant_id=?", tenantId);
        jdbc.update("DELETE FROM receipt_lines   WHERE tenant_id=?", tenantId);
        jdbc.update("DELETE FROM receipts        WHERE tenant_id=?", tenantId);
        jdbc.update("UPDATE tenants SET onboarding_manual_steps = '[]'::jsonb WHERE id=?", tenantId);
    }

    CustomUserDetails principal() {
        return new CustomUserDetails(ownerId, tenantId, "owner", null);
    }

    // ── Connections ───────────────────────────────────────────────────────────

    @Test
    void c1_noIntegrations_bothFalse() {
        Map<String, Object> status = connCtl.status(principal());
        @SuppressWarnings("unchecked")
        Map<String, Object> shopify = (Map<String, Object>) status.get("shopify");
        @SuppressWarnings("unchecked")
        Map<String, Object> bosta = (Map<String, Object>) status.get("bosta");
        assertThat(shopify.get("connected")).isEqualTo(false);
        assertThat(bosta.get("connected")).isEqualTo(false);
    }

    @Test
    void c2_shopifyConnected_reflectsInStatus() {
        jdbc.update("UPDATE stores SET status='connected' WHERE id=?", storeId);

        Map<String, Object> status = connCtl.status(principal());
        @SuppressWarnings("unchecked")
        Map<String, Object> shopify = (Map<String, Object>) status.get("shopify");
        assertThat(shopify.get("connected")).isEqualTo(true);
        assertThat(shopify.get("shopDomain")).isEqualTo("conn.myshopify.com");
    }

    @Test
    void c3_bostaActive_reflectsInStatus() {
        jdbc.update(
            "INSERT INTO courier_accounts (id, tenant_id, provider, api_key_encrypted, webhook_secret, status, business_ref) " +
            "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'wh', 'active', 'Acme Logistics')",
            tenantId, encSvc.encrypt("test-key"));

        Map<String, Object> status = connCtl.status(principal());
        @SuppressWarnings("unchecked")
        Map<String, Object> bosta = (Map<String, Object>) status.get("bosta");
        assertThat(bosta.get("connected")).isEqualTo(true);
        assertThat(bosta.get("businessName")).isEqualTo("Acme Logistics");
    }

    // ── Onboarding ────────────────────────────────────────────────────────────

    @Test
    void o1_freshTenant_locationDoneFromSharedFixture_restPending() {
        Map<String, Object> result = onboardCtl.status(principal());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        assertThat(stepDone(steps, "connect_shopify")).isFalse();
        assertThat(stepDone(steps, "connect_bosta")).isFalse();
        assertThat(stepDone(steps, "test_label")).isFalse();
        assertThat(stepDone(steps, "first_receiving")).isFalse();
        // The @BeforeAll fixture creates one location per tenant (receipt FK requirement) —
        // location is therefore already done for every test in this class.
        assertThat(stepDone(steps, "location")).isTrue();
        assertThat(result.get("allDone")).isEqualTo(false);
    }

    @Test
    void o2_shopifyConnected_connectShopifyDone() {
        jdbc.update("UPDATE stores SET status='connected' WHERE id=?", storeId);

        Map<String, Object> result = onboardCtl.status(principal());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        assertThat(stepDone(steps, "connect_shopify")).isTrue();
        assertThat(stepDone(steps, "connect_bosta")).isFalse();
    }

    @Test
    void o3_locationExists_locationDone() {
        Map<String, Object> result = onboardCtl.status(principal());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        assertThat(stepDone(steps, "location")).isTrue();
    }

    @Test
    void o4_labelReprint_testLabelDone() {
        // Create a receipt to satisfy FK
        UUID receiptId = jdbc.queryForObject(
            "INSERT INTO receipts (id, tenant_id, received_by, location_id, status) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'open') RETURNING id",
            UUID.class, tenantId, ownerId, locationId);
        jdbc.update(
            "INSERT INTO label_reprints (tenant_id, receipt_id, reprinted_by, piece_count) " +
            "VALUES (?, ?, ?, 5)", tenantId, receiptId, ownerId);

        Map<String, Object> result = onboardCtl.status(principal());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        assertThat(stepDone(steps, "test_label")).isTrue();
    }

    @Test
    void o5_finalizedReceipt_firstReceivingDone() {
        jdbc.update(
            "INSERT INTO receipts (id, tenant_id, received_by, location_id, status, finalized_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'finalized', now())",
            tenantId, ownerId, locationId);

        Map<String, Object> result = onboardCtl.status(principal());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        assertThat(stepDone(steps, "first_receiving")).isTrue();
    }

    @Test
    void o6_allSignalsPresent_allDoneTrue() {
        // ① Shopify
        jdbc.update("UPDATE stores SET status='connected', import_status='completed' WHERE id=?", storeId);
        // ② Bosta
        jdbc.update(
            "INSERT INTO courier_accounts (id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'wh', 'active')",
            tenantId, encSvc.encrypt("k2"));
        // ③ location already done via the shared @BeforeAll fixture
        // ④ label reprint
        UUID receiptId = jdbc.queryForObject(
            "INSERT INTO receipts (id, tenant_id, received_by, location_id, status, finalized_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'finalized', now()) RETURNING id",
            UUID.class, tenantId, ownerId, locationId);
        jdbc.update("INSERT INTO label_reprints (tenant_id, receipt_id, reprinted_by, piece_count) " +
                    "VALUES (?, ?, ?, 1)", tenantId, receiptId, ownerId);

        Map<String, Object> result = onboardCtl.status(principal());
        assertThat(result.get("allDone")).isEqualTo(true);
    }

    // ── Manual override endpoint ─────────────────────────────────────────────

    @Test
    void o7_manualCheck_doneTrueAutoFalse() {
        onboardCtl.setStep(new OnboardingController.SetStepRequest("connect_bosta", true), principal());

        Map<String, Object> result = onboardCtl.status(principal());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        Map<String, Object> connectBosta = steps.stream()
            .filter(s -> "connect_bosta".equals(s.get("key"))).findFirst().orElseThrow();
        assertThat(connectBosta.get("done")).isEqualTo(true);
        assertThat(connectBosta.get("auto")).isEqualTo(false);
        assertThat(connectBosta.get("manual")).isEqualTo(true);
    }

    @Test
    void o8_manualUncheck_clearsFlagOnly_autoStillDoneKeepsStepDone() {
        jdbc.update("UPDATE stores SET status='connected' WHERE id=?", storeId);
        onboardCtl.setStep(new OnboardingController.SetStepRequest("connect_shopify", true), principal());
        onboardCtl.setStep(new OnboardingController.SetStepRequest("connect_shopify", false), principal());

        Map<String, Object> result = onboardCtl.status(principal());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        Map<String, Object> connectShopify = steps.stream()
            .filter(s -> "connect_shopify".equals(s.get("key"))).findFirst().orElseThrow();
        assertThat(connectShopify.get("manual")).isEqualTo(false);
        // auto signal (stores.status='connected') is still true — step stays done.
        assertThat(connectShopify.get("auto")).isEqualTo(true);
        assertThat(connectShopify.get("done")).isEqualTo(true);
    }

    @Test
    void o9_unknownStep_rejected() {
        assertThatThrownBy(() -> onboardSvc.setManualStep(ownerId, "not_a_real_step", true))
            .isInstanceOf(ResponseStatusException.class);

        List<String> manual = onboardSvc.readManualSteps(tenantId);
        assertThat(manual).isEmpty();
    }

    @Test
    void o10_manualCheckTwice_noDuplicateInArray() {
        onboardCtl.setStep(new OnboardingController.SetStepRequest("location", true), principal());
        onboardCtl.setStep(new OnboardingController.SetStepRequest("location", true), principal());

        List<String> manual = onboardSvc.readManualSteps(tenantId);
        assertThat(manual).containsExactly("location");
    }

    // (o11) app_user RLS round-trip: POST /steps then GET /status as two separate
    // app_user transactions. If OnboardingController.status()'s read of
    // tenants.onboarding_manual_steps ran outside the tenant-scoped GUC (or if the
    // write never actually committed under RLS), this would come back manual=false
    // instead of throwing — a silently-empty read, not a loud failure. Asserting
    // manual:true is the only way to catch that.
    @Test
    void o11_appUserRlsRoundTrip_manualStepPersistsAndReadsBack() {
        // setStep() has no transaction boundary of its own (see the field comment above) —
        // appUserTx supplies one here, standing in for the Spring AOP proxy that gives
        // OnboardingService.setManualStep()'s @Transactional real effect in production.
        // TenantContext must be set BEFORE appUserTx.execute() begins the transaction —
        // TenantAwareConnection.setAutoCommit(false) fires at transaction-start and reads
        // whatever TenantContext holds at THAT moment, before setStep()'s own internal
        // TenantContext.runAs() ever runs (it fires too late here to matter — proven
        // empirically: without this pre-seed, the write's audit_log INSERT throws
        // "new row violates row-level security policy", not a silent no-op).
        TenantContext.set(tenantId);
        try {
            appUserTx.execute(txStatus -> {
                appUserOnboardCtl.setStep(
                    new OnboardingController.SetStepRequest("connect_bosta", true), principal());
                return null;
            });
        } finally {
            TenantContext.clear();
        }

        // status() needs no such pre-seeding — it wraps its own tx.execute() inside its own
        // TenantContext.runAs(), in the correct order, exactly like a real HTTP request.
        Map<String, Object> result = appUserOnboardCtl.status(principal());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        Map<String, Object> connectBosta = steps.stream()
            .filter(s -> "connect_bosta".equals(s.get("key"))).findFirst().orElseThrow();

        assertThat(connectBosta.get("manual"))
            .as("manual step written by app_user in one transaction must read back as true " +
                "from a second app_user transaction — proves the write committed and the " +
                "read ran under the same tenant-scoped RLS, not BYPASSRLS")
            .isEqualTo(true);
    }

    private static boolean stepDone(List<Map<String, Object>> steps, String key) {
        return steps.stream()
            .filter(s -> key.equals(s.get("key")))
            .map(s -> (Boolean) s.get("done"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Step not found: " + key));
    }
}
