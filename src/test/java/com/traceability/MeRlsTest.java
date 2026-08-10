package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.account.AuditService;
import com.traceability.account.UserService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies UserService.getSelf() (GET /api/v1/me backing query) under app_user
 * (RLS enforced), NOT postgres (BYPASSRLS):
 *   (a) same-tenant positive control — app_user WITH the correct GUC resolves
 *       the caller's own row.
 *   (b) cross-tenant negative control — app_user under a DIFFERENT tenant's GUC,
 *       querying a real userId that belongs to the FIRST tenant, must resolve
 *       nothing. Proves RLS blocks the cross-tenant read even though the id
 *       itself is a valid, existing row — not just an application-level filter.
 *   (c) no-GUC control — the same raw SELECT with no TenantContext/GUC set at
 *       all must return zero rows (silent empty result), not throw.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MeRlsTest {

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

    @Autowired JdbcTemplate     jdbc;   // postgres (BYPASSRLS) for fixture inserts
    @Autowired PasswordEncoder  encoder;

    // app_user infrastructure — non-proxied UserService; appUserTx wraps calls
    // the same way @Transactional does in production.
    UserService          appUserUserSvc;
    JdbcTemplate          appUserJdbc;
    TransactionTemplate   appUserTx;

    UUID tenantId, userId;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        userId   = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'MeRlsTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Rls Owner', 'rls-owner@me.local', 'h', 'owner')",
                    userId, tenantId);

        // TestSetup (ApplicationReadyEvent) has already run ALTER USER app_user PASSWORD 'testpw'
        DriverManagerDataSource rawAppUser = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawAppUser);
        appUserJdbc = new JdbcTemplate(appUserDs);
        DataSourceTransactionManager appUserTxm = new DataSourceTransactionManager(appUserDs);
        appUserTx = new TransactionTemplate(appUserTxm);
        // Non-proxied: @Transactional is inactive, so we wrap with appUserTx explicitly —
        // same effect as the production @Transactional(readOnly=true) proxy. audit is unused
        // by getSelf() — a real-but-uninvoked AuditService, not a mock, keeps the constructor honest.
        appUserUserSvc = new UserService(appUserJdbc, encoder, new AuditService(appUserJdbc, new ObjectMapper()));
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @AfterAll
    void teardown() {
        jdbc.update("DELETE FROM users WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    @Test
    void a_getSelf_sameTenantPositiveControl_resolvesOwnRow() {
        TenantContext.set(tenantId);
        Map<String, Object> self = appUserTx.execute(txs -> appUserUserSvc.getSelf(userId));

        assertThat(self).isNotNull();
        assertThat(self.get("name")).isEqualTo("Rls Owner");
        assertThat(self.get("email")).isEqualTo("rls-owner@me.local");
        assertThat(self.get("role")).isEqualTo("owner");
    }

    @Test
    void b_getSelf_crossTenantNegativeControl_resolvesNothing() {
        UUID otherTenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'MeRlsOtherTenant')", otherTenantId);

        try {
            // Real userId belongs to `tenantId`, but the GUC is set to a DIFFERENT tenant —
            // RLS must hide the row even though the id itself is valid and exists.
            TenantContext.set(otherTenantId);
            Map<String, Object> self = appUserTx.execute(txs -> appUserUserSvc.getSelf(userId));

            assertThat(self).as("cross-tenant GUC must not resolve another tenant's user")
                .isNull();
        } finally {
            jdbc.update("DELETE FROM tenants WHERE id = ?", otherTenantId);
        }
    }

    @Test
    void c_getSelf_withoutGuc_appUser_returnsNoRows() {
        // Raw query mirroring UserService.getSelf() — no TenantContext, no appUserTx wrap,
        // so app.current_tenant is never set. RLS must return zero rows (not throw),
        // matching the documented "silent zero rows" read behavior.
        List<Map<String, Object>> rows = appUserJdbc.queryForList(
            "SELECT name, email, role FROM users WHERE id = ? AND tenant_id = ?",
            userId, tenantId);

        assertThat(rows).isEmpty();
    }
}
