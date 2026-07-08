package com.traceability;

import com.traceability.inventory.ExceptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
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

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that ExceptionService.listExceptions() works under app_user (RLS enforced),
 * NOT postgres (BYPASSRLS). Reproduces the EmptyResultDataAccessException that occurred
 * at line 54 (queryForMap("...FROM tenants WHERE id=?")) when no transaction was open
 * and the GUC (app.current_tenant) was never set.
 *
 * Fix: @Transactional(readOnly=true) on listExceptions() → TenantAwareConnection fires
 * SET LOCAL app.current_tenant on setAutoCommit(false) → RLS policy sees the GUC → tenant
 * row is visible → no EmptyResultDataAccessException.
 *
 * (a) empty tenant → returns items=[], NOT throws
 * (b) tenant with blocked order → blocked_customer exception surfaces correctly
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExceptionRlsTest {

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

    @Autowired JdbcTemplate jdbc;   // postgres (BYPASSRLS) for fixture inserts
    @MockBean  JobScheduler jobScheduler;

    // app_user infrastructure — non-proxied ExceptionService; appUserTx wraps calls
    // the same way @Transactional does in production.
    ExceptionService    appUserExcSvc;
    TransactionTemplate appUserTx;

    UUID tenantId, storeId;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'RlsTestTenant')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'rls-test.myshopify.com', 'disconnected')",
                    storeId, tenantId);

        // TestSetup (ApplicationReadyEvent) has already run ALTER USER app_user PASSWORD 'testpw'
        DriverManagerDataSource rawAppUser = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawAppUser);
        JdbcTemplate appUserJdbc = new JdbcTemplate(appUserDs);
        DataSourceTransactionManager appUserTxm = new DataSourceTransactionManager(appUserDs);
        appUserTx = new TransactionTemplate(appUserTxm);
        // Non-proxied: @Transactional is inactive, so we wrap with appUserTx explicitly —
        // same effect as the production @Transactional(readOnly=true) proxy.
        appUserExcSvc = new ExceptionService(appUserJdbc, Clock.systemUTC());
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clear() {
        TenantContext.clear();
        jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    /**
     * (a) No exceptions exist — must return empty items list, NOT throw
     * EmptyResultDataAccessException from the tenants queryForMap at line 54.
     */
    @Test
    void a_emptyTenant_returnsEmptyList_doesNotThrow() {
        Map<String, Object> result = appUserTx.execute(txs ->
                appUserExcSvc.listExceptions(null, null, 0, 50));

        assertThat(result).isNotNull();
        assertThat(result.get("total")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).isEmpty();
    }

    /**
     * (b) Tenant has a blocked order — blocked_customer exception surfaces via app_user RLS.
     * Proves all 15 detector queries run correctly under app_user without privilege errors.
     */
    @Test
    void b_blockedOrder_surfacesViaAppUser() {
        jdbc.update(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at, on_hold, hold_reason) " +
            "VALUES (?, ?, 'RLS-HOLD-001', '#RLS-001', 'processing'::order_status, " +
            "    'cod', now(), true, 'Blocked customer')",
            tenantId, storeId);

        Map<String, Object> result = appUserTx.execute(txs ->
                appUserExcSvc.listExceptions(null, null, 0, 50));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).anyMatch(e -> "blocked_customer".equals(e.get("type")));
    }
}
