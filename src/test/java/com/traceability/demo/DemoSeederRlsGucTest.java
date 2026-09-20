package com.traceability.demo;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantAwareDataSource;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-DEMO GUC hotfix — proves the confirmed prod root cause under GENUINE RLS enforcement
 * (not the postgres-superuser bypass every other DemoSeeder test in this suite uses).
 *
 * Root cause: resolveOwnerId() and ensureBootstrapped()'s EXISTS check ran a bare
 * TenantContext.runAs(...) + jdbc.queryForObject(...) with NO surrounding transaction.
 * TenantAwareConnection only fires SET LOCAL app.current_tenant on a connection's
 * setAutoCommit(true->false) transition — a plain autocommit=true query never crosses that
 * transition, so the GUC is never set, so RLS silently returns zero rows for every
 * tenant-scoped table, including tenants and users.
 *
 * This class follows the SAME established pattern as ShopifyConnectAmbientContextTest /
 * InventoryLedgerTest's appUserLedger / ConnectionsOnboardingTest's o11: the Spring context
 * itself stays on the postgres-superuser primary bean (so TestSetup's ApplicationReadyEvent
 * listener can set app_user's test password without racing eager-bean DB access — see that
 * class's javadoc for why overriding spring.datasource.username=app_user for the WHOLE
 * context fails at startup). A SECOND, manually-wired DemoSeeder instance is built directly
 * against a real app_user (RLS-enforced, no BYPASSRLS) TenantAwareDataSource, and its REAL
 * resolveOwnerId()/ensureBootstrapped() method bodies are called directly — proving the fix
 * against actual RLS enforcement, not a superuser connection that would pass regardless of
 * whether the GUC was ever set.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoSeederRlsGucTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        // Primary bean stays postgres-superuser — see class javadoc for why.
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("shopify.api-version",        () -> "2024-10");
        r.add("shopify.client-id",          () -> "test-client-id");
        r.add("shopify.client-secret",      () -> "test-client-secret");
        r.add("shopify.scopes",             () -> "read_products");
        r.add("shopify.webhook-base-url",   () -> "https://test.example.com");
        r.add("bosta.api-base-url",         () -> "https://app.bosta.co");
    }

    @MockBean JobScheduler                 jobScheduler;
    @MockBean ShopifyGateway               shopifyGateway;
    @MockBean ShopifyTokenProvider         tokenProvider;
    // Keeps the DB genuinely empty at context startup — this class drives DemoSeeder
    // directly against a real app_user connection, not through the startup listener.
    @MockBean DemoBootstrapStartupListener demoBootstrapStartupListener;

    @Autowired JdbcTemplate    jdbc;              // postgres — setup/verification only
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired @FlywayDataSource DataSource ownerDs;

    /** Real, non-Spring-proxied DemoSeeder wired to a genuine app_user (RLS-enforced) connection. */
    DemoSeeder appUserDemoSeeder;
    /** Same, for DemoBootstrapStartupListener — same class of GUC bug, same fix shape. */
    DemoBootstrapStartupListener appUserListener;

    @BeforeAll
    void setup() {
        // app_user datasource — TestSetup (ApplicationReadyEvent) already ran on this
        // (postgres-primary) context before @BeforeAll fires, so 'testpw' is set.
        DriverManagerDataSource rawAppUser =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawAppUser);
        JdbcTemplate appUserJdbc = new JdbcTemplate(appUserDs);
        DataSourceTransactionManager appUserTxm = new DataSourceTransactionManager(appUserDs);

        appUserDemoSeeder = new DemoSeeder(appUserJdbc, ownerDs, passwordEncoder, appUserTxm);
        appUserListener = new DemoBootstrapStartupListener(appUserDemoSeeder, appUserJdbc, appUserTxm);
    }

    /**
     * Mirrors the confirmed prod shape: a real tenant row (is_demo=true) and a real owner
     * row (role='owner', active=true) already exist under DEMO_TENANT_ID — deliberately with
     * a RANDOM owner id and a different email than the DEMO_OWNER_ID/DEMO_OWNER_EMAIL
     * constants, exactly like prod's owner 363d6c76 predating the fixed-id bootstrap. The
     * data is unambiguously correct; only the RLS/GUC context is in question.
     */
    @BeforeEach
    void seedRealisticDemoTenant() {
        jdbc.update("DELETE FROM users     WHERE tenant_id = ?", DemoSeeder.DEMO_TENANT_ID);
        jdbc.update("DELETE FROM locations WHERE tenant_id = ?", DemoSeeder.DEMO_TENANT_ID);
        jdbc.update("DELETE FROM tenants   WHERE id = ?", DemoSeeder.DEMO_TENANT_ID);

        jdbc.update("INSERT INTO tenants (id, name, is_demo) VALUES (?, 'Traced Demo Store', true)",
                DemoSeeder.DEMO_TENANT_ID);
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, ?, 'Legacy Demo Owner', 'legacy-owner@tracedtech.invalid', 'x', 'owner', true)",
                UUID.randomUUID(), DemoSeeder.DEMO_TENANT_ID);
    }

    // -----------------------------------------------------------------------
    // resolveOwnerId() — the confirmed prod 500 ("Incorrect result size: expected 1, actual 0")
    // -----------------------------------------------------------------------
    @Test
    void resolveOwnerId_underRealRls_findsTheOwnerRow() {
        UUID resolved = appUserDemoSeeder.resolveOwnerId();
        assertThat(resolved)
                .as("the owner row genuinely exists and must resolve under real RLS")
                .isNotNull();
    }

    // -----------------------------------------------------------------------
    // ensureBootstrapped() — the EXISTS check must actually see the pre-existing tenant row
    // and short-circuit, not silently re-run insertDemoFixtureIdempotent() every call.
    // -----------------------------------------------------------------------
    @Test
    void ensureBootstrapped_underRealRls_shortCircuitsWhenTenantAlreadyExists() {
        appUserDemoSeeder.ensureBootstrapped();

        Long ownerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id = ? AND role = 'owner'",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(ownerCount)
                .as("EXISTS must see the real tenant row and skip insertDemoFixtureIdempotent() "
                        + "entirely — a false EXISTS would silently create a SECOND (canonical-id) "
                        + "owner alongside the legacy one")
                .isEqualTo(1L);

        Long workerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id = ? AND role = 'worker'",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(workerCount)
                .as("no workers should be created either — the fixture insert must not run at all")
                .isEqualTo(0L);
    }

    // -----------------------------------------------------------------------
    // DemoBootstrapStartupListener.onApplicationReady() — the 4th (final) bare-runAs site.
    // Its EXISTS check ("existedBefore") only ever feeds a log line — ensureBootstrapped()
    // is called unconditionally either way, and is already independently fixed/tested above
    // — so the boolean itself is not observable through any DB side effect. Per direction,
    // this is covered by capturing the actual log line the listener emits, proving the
    // EXISTS read itself resolves correctly (true, tenant exists) under real RLS.
    // -----------------------------------------------------------------------
    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void attachLogCapture() {
        logCapture = new ListAppender<>();
        logCapture.start();
        ((Logger) LoggerFactory.getLogger(DemoBootstrapStartupListener.class)).addAppender(logCapture);
    }

    @AfterEach
    void detachLogCapture() {
        ((Logger) LoggerFactory.getLogger(DemoBootstrapStartupListener.class)).detachAppender(logCapture);
    }

    @Test
    void onApplicationReady_underRealRls_existsCheckSeesThePreExistingTenant() {
        appUserListener.onApplicationReady();

        List<String> messages = logCapture.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        assertThat(messages)
                .as("existedBefore must resolve true under real RLS when the tenant genuinely "
                        + "exists — a false EXISTS (the pre-fix bug) would always print the "
                        + "'bootstrapped' line instead, even on a box that's been running for weeks")
                .contains("Demo tenant already present");
        assertThat(messages).doesNotContain("Demo tenant bootstrapped: " + DemoSeeder.DEMO_TENANT_ID);
    }
}
