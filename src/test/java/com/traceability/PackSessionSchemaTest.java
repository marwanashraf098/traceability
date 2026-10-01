package com.traceability;

import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
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

import static org.assertj.core.api.Assertions.*;

/**
 * Pick &amp; Pack S3 commit 2 — V126 schema: tenants.pick_pack_mode default, pack_sessions /
 * pack_session_orders tenant RLS as app_user (with same-tenant positive control), grants, and
 * one open session per (tenant, user).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackSessionSchemaTest {

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

    @Autowired JdbcTemplate jdbc;            // postgres — fixtures only
    @MockBean  JobScheduler jobScheduler;

    private TransactionTemplate appUserTx;
    private JdbcTemplate        appUserJdbc;

    @BeforeAll
    void setup() {
        DriverManagerDataSource rawDs = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(rawDs);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(appDs));
        appUserJdbc = new JdbcTemplate(appDs);
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    @Test
    void pickPackMode_defaultsToOrderQueue_andOnlyAcceptsKnownModes() {
        UUID t = tenant("ModeDefault");
        assertThat(jdbc.queryForObject("SELECT pick_pack_mode FROM tenants WHERE id = ?", String.class, t))
            .isEqualTo("order_queue");
        jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", t);
        assertThatThrownBy(() -> jdbc.update("UPDATE tenants SET pick_pack_mode = 'other' WHERE id = ?", t))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rls_crossTenantReadsReturnNothing_withSameTenantPositiveControl() {
        UUID a = tenant("SessA"), b = tenant("SessB");
        UUID userA = user(a);
        UUID session = session(a, userA);
        jdbc.update("INSERT INTO pack_session_orders (tenant_id, session_id, raw_scan, outcome, reason) " +
                    "VALUES (?, ?, 'X-1', 'rejected', 'NOT_FOUND')", a, session);

        assertThat(count(a, "pack_sessions")).isEqualTo(1);
        assertThat(count(a, "pack_session_orders")).isEqualTo(1);
        assertThat(count(b, "pack_sessions")).isZero();
        assertThat(count(b, "pack_session_orders")).isZero();
    }

    @Test
    void grants_sessionsUpdatable_sessionOrdersAppendOnly_nothingDeletable() {
        UUID t = tenant("Grants");
        UUID u = user(t);
        UUID session = session(t, u);
        jdbc.update("INSERT INTO pack_session_orders (tenant_id, session_id, raw_scan, outcome) " +
                    "VALUES (?, ?, 'X-2', 'rejected')", t, session);

        // Ending a session is the one UPDATE app_user needs.
        Integer ended = TenantContext.runAs(t, () -> appUserTx.execute(s -> appUserJdbc.update(
            "UPDATE pack_sessions SET status = 'ended', ended_at = now() WHERE id = ?", session)));
        assertThat(ended).isEqualTo(1);

        assertThatThrownBy(() -> TenantContext.runAs(t, () -> appUserTx.execute(s ->
                appUserJdbc.update("UPDATE pack_session_orders SET reason = 'x'"))))
            .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> TenantContext.runAs(t, () -> appUserTx.execute(s ->
                appUserJdbc.update("DELETE FROM pack_session_orders"))))
            .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> TenantContext.runAs(t, () -> appUserTx.execute(s ->
                appUserJdbc.update("DELETE FROM pack_sessions"))))
            .hasStackTraceContaining("permission denied");
    }

    @Test
    void oneOpenSessionPerUser_endedOnesDontCount() {
        UUID t = tenant("OneOpen");
        UUID u = user(t), other = user(t);
        UUID first = session(t, u);
        assertThatThrownBy(() -> session(t, u)).isInstanceOf(DataIntegrityViolationException.class);
        session(t, other);                                   // another packer: fine
        jdbc.update("UPDATE pack_sessions SET status = 'ended', ended_at = now() WHERE id = ?", first);
        session(t, u);                                       // after ending: fine
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int count(UUID tenant, String table) {
        return TenantContext.runAs(tenant, () -> appUserTx.execute(s ->
            appUserJdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)));
    }

    private UUID tenant(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private UUID user(UUID tenant) {
        return jdbc.queryForObject(
            "INSERT INTO users (tenant_id, name, email, password_hash, role) " +
            "VALUES (?, 'Packer', ?, 'x', 'worker'::user_role) RETURNING id",
            UUID.class, tenant, "p-" + UUID.randomUUID() + "@test.com");
    }

    private UUID session(UUID tenant, UUID user) {
        return jdbc.queryForObject(
            "INSERT INTO pack_sessions (tenant_id, user_id, mode) VALUES (?, ?, 'waybill_scan') RETURNING id",
            UUID.class, tenant, user);
    }
}
