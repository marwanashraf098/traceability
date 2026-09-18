package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifySameShopGuard;
import com.traceability.integrations.shopify.ShopifySyncService;
import com.traceability.inventory.BlocklistService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * FR-3.1 follow-up (ambient-context happy path) — proves ShopifySameShopGuard does NOT
 * clear the request-filter-set (ambient) TenantContext out from under
 * ShopifySyncService.connectCustomAppCC()'s own subsequent write, under GENUINE RLS
 * enforcement (not the postgres-superuser bypass every other integration test in this
 * suite uses for convenience — see the class-level note on why that bypass would make
 * this specific test meaningless).
 *
 * WHY THIS IS NOT A MockMvc/HTTP-LEVEL TEST (the task's stated preference) — investigated
 * and reported, not guessed:
 *
 * The obvious approach is to override spring.datasource.username to app_user for the
 * whole @SpringBootTest context (the @Primary `dataSource` bean IS the app_user-backed
 * one in production — DataSourceConfig.dataSource() — every test just overrides it to
 * "postgres" for RLS-friction-free convenience). This was tried first and fails at
 * context startup: app_user is created with LOGIN but NO PASSWORD by V1__baseline.sql
 * ("CREATE ROLE app_user LOGIN;" — no password clause), and TestSetup only sets
 * app_user's test password ('testpw') via an ApplicationReadyEvent listener, which by
 * definition fires AFTER every singleton bean is already constructed. Some beans do
 * eager DB access from their OWN constructor during context refresh — e.g.
 * BostaStateMapper (see its constructor) runs a jdbc.query(...) immediately, through the
 * SAME @Primary bean. With spring.datasource.username=app_user set from the start, that
 * eager query hits "FATAL: password authentication failed for user app_user" (confirmed
 * empirically) — app_user's password isn't 'testpw' yet, because the ApplicationReadyEvent
 * listener that would set it hasn't fired, because THAT bean hasn't finished being built.
 * Fixing this generally (e.g. moving the password-set earlier, to a BeanFactoryPostProcessor
 * or a raw pre-context JDBC call in @DynamicPropertySource) either still races against
 * Flyway (app_user's ROLE doesn't exist until V1 runs, and Flyway itself runs during
 * context refresh, not before it) or requires restructuring DataSourceConfig / eager beans
 * — production-code changes outside this task's scope ("no migration... RLS/tenant-scoping
 * unchanged" per the build instructions), so not attempted.
 *
 * The fix actually used: keep this class's own context on the SAME postgres-superuser
 * override every other test uses (context boots normally, TestSetup runs normally,
 * 'testpw' gets set normally) — then, in @BeforeAll, build a SEPARATE, manually-wired,
 * non-Spring-proxied ShopifySameShopGuard + ShopifySyncService pair connected through a
 * real app_user TenantAwareDataSource, exactly the established pattern
 * InventoryLedgerTest's `appUserLedger` and ConnectionsOnboardingTest's `o11` test already
 * use for this identical class of problem (real RLS enforcement for one specific
 * service-level call, without paying for an app_user-primary Spring context). This calls
 * the REAL connectCustomAppCC() method body (guard, then upsert) under genuine RLS, with
 * TenantContext managed the way the request filter manages it — set once at "request"
 * start, never cleared until "request" end — so a guard bug that clears it would make the
 * immediately-following INSERT's WITH CHECK RLS policy reject the write.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyConnectAmbientContextTest {

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
        r.add("spring.flyway.url",      POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",     POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate       jdbc;               // postgres — setup/verification only
    @Autowired EncryptionService  encryptionService;
    @Autowired ObjectMapper       mapper;
    @Autowired BlocklistService   blocklist;

    @MockBean ShopifyGateway shopifyGateway;

    private static final String SHOP_DOMAIN   = "ambient-ctx-test.myshopify.com";
    private static final String CLIENT_ID     = "ambient_ctx_client_id";
    private static final String CLIENT_SECRET = "ambient_ctx_client_secret";
    private static final String ACCESS_TOKEN  = "shpat_ambient_ctx_token";
    private static final long   EXPIRES_IN    = 86399L;
    private static final String GRANTED_SCOPES =
        "read_products,read_orders,read_fulfillments,write_inventory,write_locations";

    JdbcTemplate        appUserJdbc;
    TransactionTemplate appUserTx;
    ShopifySyncService  appUserSyncService;
    UUID                tenantId;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Ambient Context Co')", tenantId);

        // app_user datasource — TestSetup (ApplicationReadyEvent) ran before @BeforeAll on
        // THIS context (which boots as postgres, like every other test class), so
        // 'testpw' is already set.
        DriverManagerDataSource rawAppUser = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawAppUser);
        appUserJdbc = new JdbcTemplate(appUserDs);
        DataSourceTransactionManager appUserTxm = new DataSourceTransactionManager(appUserDs);
        // Bare (non-transactional) queries never trigger TenantAwareConnection's GUC-fire
        // (that only happens on the setAutoCommit(false) transition at a transaction's
        // start) — so the verification read below must run inside this SAME kind of
        // explicit transaction the guard/service itself uses, or it would see zero rows
        // regardless of whether the write actually succeeded under the right tenant.
        appUserTx = new TransactionTemplate(appUserTxm);

        // Real, non-Spring-proxied instances — same construction pattern as
        // InventoryLedgerTest's appUserLedger and ConnectionsOnboardingTest's o11 harness.
        ShopifySameShopGuard appUserGuard = new ShopifySameShopGuard(appUserJdbc, appUserTxm);
        appUserSyncService = new ShopifySyncService(
            appUserJdbc, shopifyGateway, encryptionService, mapper, appUserTxm,
            blocklist, appUserGuard, 30);
    }

    @BeforeEach
    void mocks() {
        when(shopifyGateway.fetchGrantedScopes(anyString(), anyString()))
            .thenReturn(GRANTED_SCOPES);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM stores WHERE tenant_id = ?", tenantId);
        TenantContext.clear();
    }

    // -----------------------------------------------------------------------
    // The one test: TenantContext set once (matching what TenantContextFilter does at
    // real request start — never re-set, never cleared mid-flow) -> real
    // connectCustomAppCC() call, which internally runs ShopifySameShopGuard.assertBoundShop
    // THEN its own UPSERT_STORE_CUSTOM_APP_CC transaction, both under the SAME ambient
    // TenantContext -> a subsequent tenant-scoped read, still under that SAME ambient
    // context and the SAME app_user connection, must see the row (count > 0). If the
    // guard had cleared TenantContext, the UPSERT's WITH CHECK RLS policy would have
    // rejected the write outright (connectCustomAppCC would throw), so this test fails
    // loudly, not silently.
    // -----------------------------------------------------------------------
    @Test
    void connectCustomAppCC_ambientContextSurvivesGuard_writeSucceedsAndRowIsReadable() {
        TenantContext.set(tenantId);

        ShopifySyncService.ConnectResult result = appUserSyncService.connectCustomAppCC(
            tenantId, SHOP_DOMAIN, CLIENT_ID, CLIENT_SECRET, ACCESS_TOKEN, EXPIRES_IN);

        assertThat(result.storeId()).isNotNull();

        // Subsequent tenant-scoped read, same thread, same still-set ambient context,
        // same app_user (RLS-enforced) role — proves the row persisted under the correct
        // tenant_id and is genuinely readable, not just "didn't throw". Wrapped in
        // appUserTx.execute(...) so TenantAwareConnection actually fires SET LOCAL for
        // this query too (a bare autocommit query never triggers it).
        Integer count = appUserTx.execute(s -> appUserJdbc.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE tenant_id = ? AND shop_domain = ?",
            Integer.class, tenantId, SHOP_DOMAIN));
        assertThat(count)
            .as("the new store must persist and be readable under real RLS with the " +
                "ambient tenant context the guard call ran under")
            .isEqualTo(1);

        String connectionType = appUserTx.execute(s -> appUserJdbc.queryForObject(
            "SELECT connection_type FROM stores WHERE tenant_id = ? AND shop_domain = ?",
            String.class, tenantId, SHOP_DOMAIN));
        assertThat(connectionType).isEqualTo("custom_app_cc");
    }
}
