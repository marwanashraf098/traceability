package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for Bosta delivery status display:
 *   d1  — Orders-list API returns correct delivery_state + exception_reason per order
 *   d2  — Exception state: exception_reason returned in both list and detail
 *   d3  — Order with NO shipment: delivery_state=null, no crash in list or detail
 *   d4  — History: N transitions recorded in shipment_status_history with correct state + timestamp
 *   d5  — History idempotent: same webhook_event_id never creates more than one history row
 *   d6  — Tenant isolation: app_user only sees own tenant's history (RLS enforced)
 *
 * All state changes go through BostaWebhookJob.process() with a mocked BostaGateway.
 * HTTP controllers are not wired — this is a service/job layer test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeliveryStatusTest {

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
        r.add("bosta.backfill.inter-fetch-delay-ms", () -> "0");
        r.add("bosta.poll.inter-fetch-delay-ms",     () -> "0");
    }

    @Autowired JdbcTemplate     jdbc;
    @Autowired ObjectMapper     mapper;
    @Autowired EncryptionService encryptionService;
    @Autowired BostaWebhookJob  webhookJob;
    @MockBean  BostaGateway     bostaGateway;
    @MockBean  JobScheduler     jobScheduler;

    // app_user datasource for RLS tests
    private JdbcTemplate        appUserJdbc;
    private TransactionTemplate appUserTx;

    private UUID tenantId;
    private UUID storeId;

    @BeforeAll
    void setupAppUser() {
        DriverManagerDataSource rawDs = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(rawDs);
        appUserJdbc = new JdbcTemplate(appDs);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(appDs));
    }

    @BeforeAll
    void createFixtures() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'DeliveryStatusTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'ds_owner@test.local', 'h', 'owner')",
                    UUID.randomUUID(), tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'ds-test.myshopify.com', 'disconnected')",
                    storeId, tenantId);
    }

    @BeforeEach
    void cleanup() {
        jdbc.execute("DELETE FROM shipment_status_history");
        jdbc.execute("DELETE FROM unlinked_bosta_deliveries");
        jdbc.execute("DELETE FROM piece_events");
        jdbc.execute("DELETE FROM allocations");
        jdbc.execute("UPDATE pieces SET current_order_id = NULL");
        jdbc.execute("DELETE FROM shipments");
        jdbc.execute("DELETE FROM order_items");
        jdbc.execute("DELETE FROM pieces");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM webhook_events");
        jdbc.execute("DELETE FROM courier_accounts");
        reset(bostaGateway);
    }

    // ── d1: orders-list returns delivery_state + exception_reason ──────────────

    @Test
    void d1_ordersList_returnsDeliveryState() {
        String tracking = "DS-D1";
        setupCourierAccount("ds-key-d1");
        UUID orderId = createOrder("EXT-D1");
        createShipment(tracking, orderId, "with_courier", null, null);

        // Verify via direct SQL (controller test is outside scope, but check the JOIN works)
        String state = jdbc.queryForObject(
            "SELECT s.internal_state FROM orders o " +
            "LEFT JOIN shipments s ON s.order_id = o.id " +
            "WHERE o.id = ?",
            String.class, orderId);
        assertThat(state).isEqualTo("with_courier");
    }

    // ── d2: exception state carries exception_reason ───────────────────────────

    @Test
    void d2_exceptionState_reasonPropagated() {
        String tracking   = "DS-D2";
        String updatedAt  = "2026-07-10T08:00:00.000Z";
        String reason     = "Customer not answering";

        setupCourierAccount("ds-key-d2");
        UUID orderId = createOrder("EXT-D2");
        createShipment(tracking, orderId, "with_courier", null, null);

        // Bosta returns state 47 (exception) with an exceptionReason in raw payload
        ObjectNode raw = mapper.createObjectNode();
        raw.put("updatedAt", updatedAt);
        raw.put("exceptionCode", 7);
        raw.put("exceptionReason", reason);

        BostaDelivery delivery = new BostaDelivery(tracking, 47, "SEND", 2, "EXT-D2", null, raw);
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking))).thenReturn(delivery);

        long eventId = insertPendingEvent(tenantId, tracking, 47, updatedAt);
        webhookJob.process(eventId, tenantId);

        // Shipment should now be in exception state with the reason
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT internal_state, exception_code, exception_reason FROM shipments WHERE tracking_number = ?",
            tracking);
        assertThat(row.get("internal_state")).isEqualTo("exception");
        assertThat(row.get("exception_code")).isEqualTo(7);
        assertThat(row.get("exception_reason")).isEqualTo(reason);

        // Verify exception_reason visible in a list-style JOIN (simulates orders-list query)
        String listReason = jdbc.queryForObject(
            "SELECT s.exception_reason FROM orders o " +
            "LEFT JOIN shipments s ON s.order_id = o.id " +
            "WHERE o.id = ?",
            String.class, orderId);
        assertThat(listReason).isEqualTo(reason);
    }

    // ── d3: order with no shipment — null delivery_state, no crash ─────────────

    @Test
    void d3_orderWithNoShipment_nullDeliveryState() {
        UUID orderId = createOrder("EXT-D3");

        // LEFT JOIN should produce NULL for shipment columns
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT o.id, o.status, s.internal_state AS delivery_state " +
            "FROM orders o LEFT JOIN shipments s ON s.order_id = o.id " +
            "WHERE o.id = ?",
            orderId);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("delivery_state")).isNull();
    }

    // ── d4: history records N transitions with correct state + timestamp ────────

    @Test
    void d4_multipleTransitions_historyRecorded() {
        String tracking = "DS-D4";
        setupCourierAccount("ds-key-d4");
        UUID orderId = createOrder("EXT-D4");
        createShipment(tracking, orderId, "created", null, null);

        // Simulate state progression: 21 → with_courier, then 45 → delivered
        String upd1 = "2026-07-10T09:00:00.000Z";
        String upd2 = "2026-07-10T11:00:00.000Z";

        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 21, "SEND", 1, "EXT-D4", null, raw(upd1)))
            .thenReturn(new BostaDelivery(tracking, 45, "SEND", 1, "EXT-D4", null, raw(upd2)));

        long ev1 = insertPendingEvent(tenantId, tracking, 21, upd1);
        long ev2 = insertPendingEvent(tenantId, tracking, 45, upd2);

        webhookJob.process(ev1, tenantId);
        webhookJob.process(ev2, tenantId);

        UUID shipmentId = jdbc.queryForObject(
            "SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tracking);

        List<Map<String, Object>> history = jdbc.queryForList(
            "SELECT internal_state, provider_state, webhook_event_id " +
            "FROM shipment_status_history WHERE shipment_id = ? ORDER BY occurred_at ASC",
            shipmentId);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("internal_state")).isEqualTo("with_courier");
        assertThat(((Number) history.get(0).get("provider_state")).intValue()).isEqualTo(21);
        assertThat(history.get(1).get("internal_state")).isEqualTo("delivered");
        assertThat(((Number) history.get(1).get("provider_state")).intValue()).isEqualTo(45);
    }

    // ── d5: history idempotent — same webhook_event_id never duplicates ─────────

    @Test
    void d5_historyIdempotent_onConflictDoNothing() {
        String tracking = "DS-D5";
        String upd      = "2026-07-10T10:00:00.000Z";
        setupCourierAccount("ds-key-d5");
        UUID orderId = createOrder("EXT-D5");
        createShipment(tracking, orderId, "created", null, null);

        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 21, "SEND", 1, "EXT-D5", null, raw(upd)));

        long eventId = insertPendingEvent(tenantId, tracking, 21, upd);
        webhookJob.process(eventId, tenantId);

        UUID shipmentId = jdbc.queryForObject(
            "SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tracking);

        Long count1 = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipment_status_history WHERE shipment_id = ?",
            Long.class, shipmentId);
        assertThat(count1).isEqualTo(1L);

        // Direct duplicate INSERT for the same webhook_event_id must be silently ignored.
        jdbc.update(
            "INSERT INTO shipment_status_history " +
            "  (tenant_id, shipment_id, internal_state, provider_state, webhook_event_id) " +
            "VALUES (?, ?, 'with_courier', 21, ?) " +
            "ON CONFLICT (webhook_event_id) WHERE webhook_event_id IS NOT NULL DO NOTHING",
            tenantId, shipmentId, eventId);

        Long count2 = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipment_status_history WHERE shipment_id = ?",
            Long.class, shipmentId);
        assertThat(count2).as("no duplicate history row created").isEqualTo(1L);
    }

    // ── d6: tenant isolation — app_user cannot see another tenant's history ─────

    @Test
    void d6_tenantIsolation_historyRls() {
        String tracking = "DS-D6";
        String upd      = "2026-07-10T12:00:00.000Z";
        setupCourierAccount("ds-key-d6");
        UUID orderId = createOrder("EXT-D6");
        createShipment(tracking, orderId, "created", null, null);

        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 21, "SEND", 1, "EXT-D6", null, raw(upd)));

        long eventId = insertPendingEvent(tenantId, tracking, 21, upd);
        webhookJob.process(eventId, tenantId);

        UUID shipmentId = jdbc.queryForObject(
            "SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tracking);

        // postgres (BYPASSRLS) can see the row
        Long adminCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipment_status_history WHERE shipment_id = ?",
            Long.class, shipmentId);
        assertThat(adminCount).isEqualTo(1L);

        // app_user without GUC set → RLS returns 0 rows (no error, just no data)
        Long appCount = appUserJdbc.queryForObject(
            "SELECT COUNT(*) FROM shipment_status_history WHERE shipment_id = ?",
            Long.class, shipmentId);
        assertThat(appCount).as("app_user without GUC sees 0 history rows").isZero();

        // app_user WITH the correct tenant GUC → sees the row.
        // TenantContext.runAs() MUST be the outer wrapper so the GUC is set before
        // appUserTx.execute() fires setAutoCommit(false) → TenantAwareConnection reads the GUC.
        Long appCountWithGuc;
        try {
            appCountWithGuc = TenantContext.runAs(tenantId, () ->
                appUserTx.execute(s ->
                    appUserJdbc.queryForObject(
                        "SELECT COUNT(*) FROM shipment_status_history WHERE shipment_id = ?",
                        Long.class, shipmentId)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(appCountWithGuc).as("app_user with correct GUC sees 1 history row").isEqualTo(1L);
    }

    // ── d7: delivered + returned terminal states display correctly ─────────────

    @Test
    void d7_terminalStates_historyAndShipmentStateCorrect() {
        setupCourierAccount("ds-key-d7");
        for (String[] tc : new String[][]{
                {"DS-D7-DEL", "45", "delivered", "2026-07-10T13:00:00.000Z"},
                {"DS-D7-RET", "46", "returned",  "2026-07-10T14:00:00.000Z"},
        }) {
            String tracking   = tc[0];
            int    stateCode  = Integer.parseInt(tc[1]);
            String expectState = tc[2];
            String upd         = tc[3];

            UUID orderId = createOrder("EXT-" + tracking);
            createShipment(tracking, orderId, "with_courier", null, null);
            when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
                .thenReturn(new BostaDelivery(tracking, stateCode, "SEND", 1, null, null, raw(upd)));
            long evId = insertPendingEvent(tenantId, tracking, stateCode, upd);
            webhookJob.process(evId, tenantId);

            String state = jdbc.queryForObject(
                "SELECT internal_state FROM shipments WHERE tracking_number = ?",
                String.class, tracking);
            assertThat(state).as("tracking=" + tracking).isEqualTo(expectState);

            UUID shipmentId = jdbc.queryForObject(
                "SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tracking);
            Long historyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shipment_status_history WHERE shipment_id = ?",
                Long.class, shipmentId);
            assertThat(historyCount).as("history row for " + tracking).isEqualTo(1L);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void setupCourierAccount(String rawApiKey) {
        jdbc.update(
            "INSERT INTO courier_accounts " +
            "  (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'hash', 'active')",
            tenantId, encryptionService.encrypt(rawApiKey));
    }

    private UUID createOrder(String extId) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, status) " +
            "VALUES (?, ?, ?, 'new'::order_status) RETURNING id",
            UUID.class, tenantId, storeId, extId);
    }

    private void createShipment(String tracking, UUID orderId, String state,
                                 Integer exceptionCode, String exceptionReason) {
        jdbc.update(
            "INSERT INTO shipments " +
            "  (tenant_id, order_id, provider, tracking_number, internal_state, " +
            "   exception_code, exception_reason) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, ?, ?)",
            tenantId, orderId, tracking, state, exceptionCode, exceptionReason);
    }

    private long insertPendingEvent(UUID tenant, String tracking, int stateCode, String updatedAt) {
        String payload = String.format(
            "{\"trackingNumber\":\"%s\",\"state\":%d,\"type\":\"SEND\",\"updatedAt\":\"%s\"}",
            tracking, stateCode, updatedAt);
        return jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status) " +
            "VALUES ('bosta_poll'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending') " +
            "RETURNING id",
            Long.class, tenant, payload);
    }

    private ObjectNode raw(String updatedAt) {
        ObjectNode n = mapper.createObjectNode();
        n.put("updatedAt", updatedAt);
        return n;
    }
}
