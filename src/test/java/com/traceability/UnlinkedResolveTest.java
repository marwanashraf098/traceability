package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.security.EncryptionService;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Integration tests for FR-4.4 — every path that auto-links a Bosta delivery must mark
 * the source unlinked_bosta_deliveries row resolved=true in the same transaction.
 *
 * Production evidence (tenant 07fc572c): order #385328419470 had shipment 364f52de
 * (tracking 7378083355, created via ingest), but the unlinked row stayed resolved=false
 * because tryMatchDelivery() called createOrFindShipment() without calling resolveUnlinked().
 * The reconcile manualLink path was the known-good reference — it resolved correctly.
 *
 * Matrix:
 *   ul1 — ingest path (BostaWebhookJob → tryMatchDelivery) succeeds after a prior NO_MATCH:
 *          resolved=true, unlinked count decreases by exactly 1 (asserted via app_user/RLS)
 *   ul2 — reconcile manualLink regression: resolved=true still visible via app_user (known-good)
 *   ul3 — ingest path with no prior unlinked row: resolveUnlinked() is a no-op, link succeeds
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnlinkedResolveTest {

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

    @Autowired JdbcTemplate        jdbc;
    @Autowired ObjectMapper        mapper;
    @Autowired EncryptionService   encryptionService;
    @Autowired BostaWebhookJob     bostaWebhookJob;
    @Autowired ShipmentLinkService shipmentLinkService;
    @MockBean  BostaGateway        bostaGateway;
    @MockBean  JobScheduler        jobScheduler;

    // app_user datasource — RLS enforced (no BYPASSRLS). Used for assertions that prove
    // the resolved update is tenant-scoped and visible under the GUC-driven RLS policy.
    private JdbcTemplate        appUserJdbc;
    private TransactionTemplate appUserTx;

    private UUID tenantId;
    private UUID storeId;

    @BeforeAll
    void setupAppUser() {
        DriverManagerDataSource rawDs =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawDs);
        appUserJdbc = new JdbcTemplate(appUserDs);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
    }

    @BeforeAll
    void createFixtures() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'UnlinkedResolveTenant')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Owner', 'ul_owner@test.local', 'h', 'owner')",
            UUID.randomUUID(), tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'ul-test.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO courier_accounts " +
            "  (id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'testhash', 'active')",
            tenantId, encryptionService.encrypt("ul-api-key"));
    }

    @BeforeEach
    void cleanup() {
        reset(bostaGateway);
        jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments        WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items      WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders           WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM webhook_events   WHERE tenant_id = ?", tenantId);
    }

    // ── ul1: ingest path resolves prior unlinked row — count must decrease by exactly 1 ──────

    @Test
    void ul1_ingestPath_successAfterPriorNoMatch_resolvesUnlinkedRow_countDecreasesByOne() {
        String tracking = "UL-TN-01";
        String orderNum = "#UL-001";
        UUID orderId = insertOrder(orderNum);

        // Simulate a prior NO_MATCH ingest run: BostaWebhookJob.recordUnlinked() wrote this row.
        // State code 24 (with_courier) was the state at the time — now state changed to 41
        // (also with_courier via FXF_SEND), which is why Guard 3 allowed re-enqueueing.
        jdbc.update(
            "INSERT INTO unlinked_bosta_deliveries " +
            "  (tenant_id, tracking_number, business_reference, bosta_state_code, " +
            "   bosta_order_type, match_reason, resolved) " +
            "VALUES (?, ?, ?, 24, 'SEND', 'NO_MATCH', false)",
            tenantId, tracking, orderNum);

        // Assert pre-condition via app_user: RLS sees exactly 1 unresolved row for this tenant.
        int countBefore = countUnlinkedViaAppUser();
        assertThat(countBefore).as("pre-condition: 1 unlinked row before linking").isEqualTo(1);

        // New ingest event — state changed from 24 → 41, Guard 3 allowed re-enqueue.
        Long wid = insertWebhookEvent(tracking, 41, "2026-07-10T20:40:54.000Z");
        // Delivery now has businessReference matching the order — tryMatchDelivery() will link it.
        when(bostaGateway.fetchDelivery(eq("ul-api-key"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 41, "SEND", 0, orderNum, null, minimalRaw()));

        bostaWebhookJob.process(wid, tenantId);

        // Shipment created by the ingest path.
        assertThat(shipmentExists(tracking)).isTrue();
        assertThat(webhookStatus(wid)).isEqualTo("processed");

        // Count via app_user after: RLS sees 0 unresolved rows (the row was resolved).
        int countAfter = countUnlinkedViaAppUser();
        assertThat(countAfter).as("no unlinked rows remain after auto-link").isEqualTo(0);

        // The decrease must be exactly 1 — guards against resolving zero rows (fix absent)
        // or more than one (logic error targeting wrong rows).
        assertThat(countBefore - countAfter)
            .as("unlinked count must decrease by exactly 1")
            .isEqualTo(1);

        // Also verify the row-level flag directly via app_user (GUC-scoped / RLS-visible).
        Boolean resolved = resolvedViaAppUser(tracking);
        assertThat(resolved)
            .as("unlinked row resolved=true visible via app_user (RLS-enforced GUC context)")
            .isTrue();
    }

    // ── ul2: reconcile manualLink — regression, known-good path visible via app_user ──────────

    @Test
    void ul2_manualLink_reconcileRegression_resolvedTrueViaAppUser() {
        String tracking = "UL-TN-02";
        UUID orderId = insertOrder("#UL-002");

        Long unlinkedId = jdbc.queryForObject(
            "INSERT INTO unlinked_bosta_deliveries " +
            "  (tenant_id, tracking_number, business_reference, bosta_state_code, " +
            "   bosta_order_type, match_reason, resolved) " +
            "VALUES (?, ?, '#UL-002', 45, 'SEND', 'NO_MATCH', false) RETURNING id",
            Long.class, tenantId, tracking);

        // manualLink is @Transactional — same code path as BostaOrderReconcileJob.processOrder().
        TenantContext.runAs(tenantId, () ->
            shipmentLinkService.manualLink(unlinkedId, orderId, null));

        // Shipment created.
        assertThat(shipmentExists(tracking)).isTrue();

        // Known-good path verified via app_user: RLS sees the resolved row.
        Boolean resolved = resolvedViaAppUser(tracking);
        assertThat(resolved)
            .as("manualLink resolved=true visible via app_user (regression guard)")
            .isTrue();
    }

    // ── ul3: ingest path with no prior unlinked row — resolveUnlinked() is a silent no-op ──

    @Test
    void ul3_ingestPath_noPriorUnlinkedRow_linkSucceedsAndZeroUnlinkedRows() {
        String tracking = "UL-TN-03";
        insertOrder("#UL-003");

        // No pre-inserted unlinked row. resolveUnlinked() must handle this gracefully
        // (WHERE resolved=false matches 0 rows → 0-row UPDATE, no exception).
        Long wid = insertWebhookEvent(tracking, 41, "2026-07-10T20:41:00.000Z");
        when(bostaGateway.fetchDelivery(eq("ul-api-key"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 41, "SEND", 0, "#UL-003", null, minimalRaw()));

        bostaWebhookJob.process(wid, tenantId);

        assertThat(shipmentExists(tracking)).as("shipment created via ingest path").isTrue();
        assertThat(webhookStatus(wid)).isEqualTo("processed");

        // No unlinked rows exist — neither a pre-existing one nor a newly created NO_MATCH.
        int count = countUnlinkedViaAppUser();
        assertThat(count).as("0 unlinked rows when no prior NO_MATCH existed").isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private UUID insertOrder(String number) {
        return jdbc.queryForObject(
            "INSERT INTO orders " +
            "  (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, now()) RETURNING id",
            UUID.class, tenantId, storeId, "EXT-" + number, number);
    }

    private Long insertWebhookEvent(String tracking, int state, String updatedAt) {
        String payload = String.format(
            "{\"trackingNumber\":\"%s\",\"state\":%d,\"updatedAt\":\"%s\"}",
            tracking, state, updatedAt);
        return jdbc.queryForObject(
            "INSERT INTO webhook_events " +
            "  (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta', ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, tenantId, payload);
    }

    /** Minimal valid raw node — empty object serialises to '{}' which is valid jsonb. */
    private ObjectNode minimalRaw() {
        return mapper.createObjectNode();
    }

    private boolean shipmentExists(String tracking) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            Integer.class, tracking, tenantId);
        return n != null && n > 0;
    }

    private String webhookStatus(Long id) {
        return jdbc.queryForObject(
            "SELECT status FROM webhook_events WHERE id = ?", String.class, id);
    }

    /**
     * Counts unresolved rows for this tenant using app_user (RLS enforced).
     * TenantContext.runAs() sets the GUC → TenantAwareDataSource fires SET LOCAL → RLS
     * policy sees NULLIF(current_setting(...),'')::uuid = tenantId → scopes to this tenant.
     * No explicit tenant_id filter needed: RLS provides it.
     */
    private int countUnlinkedViaAppUser() {
        Integer n = TenantContext.runAs(tenantId, () -> appUserTx.execute(s ->
            appUserJdbc.queryForObject(
                "SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE resolved = false",
                Integer.class)));
        return n != null ? n : 0;
    }

    /** Reads resolved flag for the tracking number via app_user (RLS enforced). */
    private Boolean resolvedViaAppUser(String tracking) {
        return TenantContext.runAs(tenantId, () -> appUserTx.execute(s ->
            appUserJdbc.queryForObject(
                "SELECT resolved FROM unlinked_bosta_deliveries WHERE tracking_number = ?",
                Boolean.class, tracking)));
    }
}
