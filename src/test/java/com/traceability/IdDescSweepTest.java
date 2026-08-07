package com.traceability;

import com.traceability.inventory.FulfillService;
import com.traceability.inventory.NotTracedTagger;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * id-DESC latest-row sweep — closes the two offenders the Bosta ingest audit found:
 * {@code FulfillService.PICKABLE_ORDERS_FILTER} and {@code NotTracedTagger.maybeTagNotTraced()}
 * both picked an order's "latest forward shipment" via a bare {@code ORDER BY id DESC}.
 * UUIDv4 is not time-ordered (see CLAUDE.md's invariant), so that tie-break can silently
 * disagree with actual recency.
 *
 * Test inventory:
 *   a — THE POINT: an order with two forward shipments where the OLDER row (still
 *       'created') carries the lexically HIGHER uuid and the NEWER row ('terminated')
 *       carries the lower one. Under the old bare `id DESC`, the older 'created' row wins
 *       the tie-break — the pick queue would wrongly show the order as pickable, and
 *       NotTracedTagger would wrongly leave it untagged. Under the fix
 *       (`created_at DESC, id DESC`), the newer 'terminated' row wins — the order is
 *       correctly excluded from the queue and correctly tagged not_traced.
 *   b — regression: a single-forward-shipment 'created' order is still queueable (pick
 *       queue) and still left untagged (NotTracedTagger) — the common case, unaffected.
 *   c — RLS: app_user with the correct tenant GUC can tag its own tenant's tie-break
 *       order; the same call under a mismatched tenant GUC is a no-op (cross-tenant).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdDescSweepTest {

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

    @Autowired FulfillService  fulfillSvc;
    @Autowired NotTracedTagger notTracedTagger;
    @Autowired JdbcTemplate    jdbc;
    @MockBean  JobScheduler    jobScheduler;

    // app_user infrastructure for the RLS test — mirrors NotTracedDetectorTest's pattern.
    private TransactionTemplate appUserTx;
    private NotTracedTagger     appUserTagger;

    private UUID tenantId, storeId;

    // Explicit UUID literals — deliberately NOT relying on generation order, which is
    // exactly the non-determinism under test. OLDER carries the lexically HIGHER uuid,
    // NEWER the lower one, so a bare `id DESC` picks the wrong (older) row.
    private static final UUID OLDER_HIGH_UUID = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
    private static final UUID NEWER_LOW_UUID  = UUID.fromString("00000000-0000-4000-8000-000000000000");

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'IdDescSweepTenant')", tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'id-desc-sweep.myshopify.com', 'disconnected')",
            storeId, tenantId);

        DriverManagerDataSource rawDs =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(rawDs);
        JdbcTemplate appUserJdbc = new JdbcTemplate(appDs);
        appUserTx     = new TransactionTemplate(new DataSourceTransactionManager(appDs));
        appUserTagger = new NotTracedTagger(appUserJdbc);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clean() {
        TenantContext.clear();
        jdbc.update("DELETE FROM shipments   WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    // ── a: THE POINT — tie-break correctness ────────────────────────────────────

    @Test
    void a_tieBreak_pickQueueAndNotTracedTagger_bothFollowCreatedAtNotId() {
        UUID orderId = insertOrder("TIE-BREAK-001");
        // Older row, higher uuid, still 'created'.
        insertForwardShipment(OLDER_HIGH_UUID, orderId, "created", "2026-08-01T10:00:00Z");
        // Newer row, lower uuid, 'terminated' — the true latest by created_at.
        insertForwardShipment(NEWER_LOW_UUID, orderId, "terminated", "2026-08-01T11:00:00Z");

        // Pick queue: the order's true latest shipment is 'terminated', not 'created' —
        // it must NOT appear in the pick queue (nothing left to physically pick).
        List<Map<String, Object>> queue = fulfillSvc.getQueue();
        assertThat(queue)
            .as("order whose true latest forward shipment is 'terminated' must not be pickable, "
                + "even though an older 'created' row (with the higher uuid) still exists")
            .noneMatch(o -> orderId.equals(o.get("id")));

        // NotTracedTagger: zero allocations + true latest shipment 'terminated' (<> 'created')
        // ⇒ must be tagged not_traced.
        notTracedTagger.maybeTagNotTraced(orderId, tenantId);
        assertThat(notTracedAt(orderId))
            .as("order must be tagged not_traced — the true latest (by created_at) forward "
                + "shipment is 'terminated', not the older, higher-uuid 'created' row")
            .isNotNull();
    }

    // ── b: regression — single-shipment orders unaffected ───────────────────────

    @Test
    void b_regression_singleForwardShipment_stillQueueableAndUntagged() {
        UUID orderId = insertOrder("SINGLE-001");
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', 'created'::shipment_internal_state, 'forward')",
            tenantId, orderId);

        List<Map<String, Object>> queue = fulfillSvc.getQueue();
        assertThat(queue).as("single 'created' forward shipment — still pickable")
            .anyMatch(o -> orderId.equals(o.get("id")));

        notTracedTagger.maybeTagNotTraced(orderId, tenantId);
        assertThat(notTracedAt(orderId))
            .as("single 'created' forward shipment — must stay untagged").isNull();
    }

    @Test
    void b_regression_singleForwardShipment_delivered_stillTagsWhenZeroAllocations() {
        UUID orderId = insertOrder("SINGLE-002");
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', 'delivered'::shipment_internal_state, 'forward')",
            tenantId, orderId);

        notTracedTagger.maybeTagNotTraced(orderId, tenantId);
        assertThat(notTracedAt(orderId))
            .as("single terminal forward shipment, zero allocations — still tags as before")
            .isNotNull();
    }

    // ── c: RLS — positive same-tenant control + cross-tenant no-op ──────────────

    @Test
    void c_rls_appUser_sameTenantPositive_crossTenantNoOp() {
        UUID tenantB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'IdDescSweepTenantB')", tenantB);
        UUID storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'id-desc-sweep-b.myshopify.com', 'disconnected')",
                    storeB, tenantB);

        // Tenant A: the tie-break fixture, exercised under app_user with the correct GUC.
        UUID orderA = insertOrder("RLS-TIE-A");
        insertForwardShipment(OLDER_HIGH_UUID, orderA, "created", "2026-08-01T10:00:00Z");
        insertForwardShipment(NEWER_LOW_UUID, orderA, "terminated", "2026-08-01T11:00:00Z");

        // Tenant B: same shape, different tenant — must never be reachable from tenant A's GUC.
        UUID orderB = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, 'EXT-RLS-TIE-B', '#RLS-TIE-B', 'new'::order_status, now()) RETURNING id",
            UUID.class, tenantB, storeB);
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', 'terminated'::shipment_internal_state, 'forward')",
            tenantB, orderB);

        // Positive same-tenant control: app_user WITH the correct GUC tags orderA.
        TenantContext.runAs(tenantId, () ->
            appUserTx.execute(s -> {
                appUserTagger.maybeTagNotTraced(orderA, tenantId);
                return null;
            }));
        assertThat(notTracedAt(orderA))
            .as("app_user with correct tenant GUC tags its own tenant's tie-break order")
            .isNotNull();

        // Cross-tenant no-op: GUC set to tenant A, target order/tenant param is tenant B.
        TenantContext.runAs(tenantId, () ->
            appUserTx.execute(s -> {
                appUserTagger.maybeTagNotTraced(orderB, tenantB);
                return null;
            }));
        assertThat(notTracedAt(orderB))
            .as("app_user cannot tag another tenant's order across a GUC mismatch")
            .isNull();

        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantB);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantB);
        jdbc.update("DELETE FROM stores WHERE tenant_id = ?", tenantB);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantB);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID insertOrder(String extId) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, now()) RETURNING id",
            UUID.class, tenantId, storeId, "EXT-" + extId, "#" + extId);
    }

    private void insertForwardShipment(UUID id, UUID orderId, String state, String createdAtIso) {
        jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, provider, internal_state, " +
            "    shipment_leg, created_at) " +
            "VALUES (?, ?, ?, 'bosta', ?::shipment_internal_state, 'forward', ?::timestamptz)",
            id, tenantId, orderId, state, createdAtIso);
    }

    private java.time.Instant notTracedAt(UUID orderId) {
        return jdbc.query(
            "SELECT not_traced_at FROM orders WHERE id = ?",
            rs -> {
                if (!rs.next()) return null;
                var ts = rs.getTimestamp("not_traced_at");
                return ts != null ? ts.toInstant() : null;
            }, orderId);
    }
}
