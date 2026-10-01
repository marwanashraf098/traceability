package com.traceability;

import com.traceability.account.AuditService;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.InventoryLedger;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pick &amp; Pack S3 commit 1 — FulfillService.getOrder() returns exactly one forward leg,
 * deterministically: an active one before a terminated/cancelled one, newest first. Before the
 * fix a plain LEFT JOIN returned one row per forward leg and rows.get(0) was arbitrary.
 * Runs as app_user (RLS) like FulfillShipmentHasCourierTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetOrderForwardLegTest {

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

    @Autowired JdbcTemplate    jdbc;            // postgres — fixtures only
    @Autowired InventoryLedger ledger;
    @Autowired AuditService    auditService;
    @MockBean  JobScheduler    jobScheduler;

    private TransactionTemplate appUserTx;
    private FulfillService      appUserFulfill;

    @BeforeAll
    void setup() {
        DriverManagerDataSource rawDs = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(rawDs);
        appUserTx      = new TransactionTemplate(new DataSourceTransactionManager(appDs));
        appUserFulfill = new FulfillService(new JdbcTemplate(appDs), ledger, auditService, 30);
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    @Test
    void oldTerminatedLegAndNewActiveLeg_returnsTheActiveOne() {
        UUID tenant = tenant("FwdLegNewActive");
        UUID orderId = order(tenant);
        UUID old = leg(tenant, orderId, "1111100001", "terminated", "now() - interval '3 days'");
        UUID active = leg(tenant, orderId, "1111100002", "created", "now() - interval '1 day'");

        Map<String, Object> order = getOrder(tenant, orderId);
        assertThat(order.get("shipment_id")).isEqualTo(active).isNotEqualTo(old);
        assertThat(order.get("tracking_number")).isEqualTo("1111100002");
    }

    @Test
    void activeLegWinsEvenWhenTheTerminatedOneIsNewer() {
        UUID tenant = tenant("FwdLegOldActive");
        UUID orderId = order(tenant);
        UUID active = leg(tenant, orderId, "1111100003", "created", "now() - interval '3 days'");
        leg(tenant, orderId, "1111100004", "cancelled", "now() - interval '1 day'");

        assertThat(getOrder(tenant, orderId).get("shipment_id")).isEqualTo(active);
    }

    @Test
    void onlyEndedLegs_returnsTheNewestOne() {
        UUID tenant = tenant("FwdLegAllEnded");
        UUID orderId = order(tenant);
        leg(tenant, orderId, "1111100005", "terminated", "now() - interval '3 days'");
        UUID newest = leg(tenant, orderId, "1111100006", "terminated", "now() - interval '1 day'");

        assertThat(getOrder(tenant, orderId).get("shipment_id")).isEqualTo(newest);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> getOrder(UUID tenant, UUID orderId) {
        return TenantContext.runAs(tenant, () -> appUserTx.execute(s -> appUserFulfill.getOrder(orderId)));
    }

    private UUID tenant(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private UUID order(UUID tenant) {
        UUID storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', ?, 'disconnected')",
                    storeId, tenant, "fl-" + storeId + ".myshopify.com");
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, status) " +
            "VALUES (?, ?, ?, 'ready_to_pick'::order_status) RETURNING id",
            UUID.class, tenant, storeId, "EXT-" + UUID.randomUUID());
    }

    private UUID leg(UUID tenant, UUID orderId, String tracking, String state, String createdAtSql) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, " +
            "                       shipment_leg, created_at) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward', " + createdAtSql + ") RETURNING id",
            UUID.class, tenant, orderId, tracking, state);
    }
}
