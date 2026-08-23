package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.security.EncryptionService;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-08-24 build (Step 0 Spec 2, Part B): monotonic invariant on
 * BostaWebhookJob.applyMappedState()'s internal_state write.
 *
 * mb1 — incident shape: shipment reaches with_courier (code 21), then a stray/re-route
 *       code-20 ("back at hub") webhook arrives. internal_state must resolve to 'exception',
 *       NOT regress to 'created'. shipment_status_history still records the raw mapped
 *       'created' value unfiltered — only the live column is guarded. RED before Part B
 *       (today's behavior: internal_state becomes 'created' again).
 * mb2 — positive control: a shipment that has ONLY ever received codes mapping to 'created'
 *       (10 → 11 → 20, never dispatched) must keep resolving to 'created' normally — the
 *       guard must not fire when there is no real progress to protect. GREEN both before
 *       and after Part B (proves the guard doesn't over-block).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonotonicShipmentStateTest {

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

    @Autowired JdbcTemplate      jdbc;
    @Autowired ObjectMapper      mapper;
    @Autowired EncryptionService encryptionService;
    @Autowired BostaWebhookJob   webhookJob;
    @MockBean  BostaGateway      bostaGateway;
    @MockBean  JobScheduler      jobScheduler;

    private UUID tenantId;
    private UUID storeId;

    @BeforeAll
    void createFixtures() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'MonotonicTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'mono_owner@test.local', 'h', 'owner')",
                    UUID.randomUUID(), tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'mono-test.myshopify.com', 'disconnected')",
                    storeId, tenantId);
        setupCourierAccount("mono-key");
    }

    @BeforeEach
    void cleanup() {
        jdbc.execute("DELETE FROM shipment_status_history");
        jdbc.execute("DELETE FROM unlinked_bosta_deliveries");
        jdbc.execute("DELETE FROM shipments");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM webhook_events");
        reset(bostaGateway);
    }

    // mb1: with_courier already reached, stray code-20 must NOT regress to 'created'
    @Test
    void mb1_backAtHubAfterWithCourier_resolvesToException() {
        String tracking = "MONO-MB1";
        String upd1      = "2026-08-17T10:00:00.000Z";
        String upd2      = "2026-08-22T21:03:00.000Z"; // matches the real incident timestamp

        UUID orderId = createOrder("EXT-MB1");

        // First event: code 21 (picked up) → with_courier. Real write path, not a direct seed.
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 21, "SEND", 1, "EXT-MB1", null, raw(upd1)));
        long ev1 = insertPendingEvent(tracking, 21, upd1);
        webhookJob.process(ev1, tenantId);

        UUID shipmentId = jdbc.queryForObject(
            "SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tracking);
        assertThat(jdbc.queryForObject(
            "SELECT internal_state FROM shipments WHERE id = ?", String.class, shipmentId))
            .isEqualTo("with_courier");

        // Second event: code 20 ("Route assigned" / real-world "back at hub" re-route) —
        // the incident shape. No custody lock involved.
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 20, "SEND", 1, "EXT-MB1", null, raw(upd2)));
        long ev2 = insertPendingEvent(tracking, 20, upd2);
        webhookJob.process(ev2, tenantId);

        String finalState = jdbc.queryForObject(
            "SELECT internal_state FROM shipments WHERE id = ?", String.class, shipmentId);
        assertThat(finalState)
            .as("internal_state must NOT regress to 'created' after with_courier")
            .isEqualTo("exception");

        // History is unfiltered — records the raw mapped 'created' value Bosta actually sent.
        List<Map<String, Object>> history = jdbc.queryForList(
            "SELECT internal_state, provider_state FROM shipment_status_history " +
            "WHERE shipment_id = ? ORDER BY occurred_at ASC", shipmentId);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("internal_state")).isEqualTo("with_courier");
        assertThat(history.get(1).get("internal_state"))
            .as("history must still record the truth Bosta sent, unfiltered")
            .isEqualTo("created");
        assertThat(((Number) history.get(1).get("provider_state")).intValue()).isEqualTo(20);
    }

    // mb2: positive control — a shipment that has ONLY ever been in 'created' must keep
    // resolving to 'created' normally. Proves the guard only fires on real progress.
    @Test
    void mb2_neverProgressedPastCreated_staysCreated() {
        String tracking = "MONO-MB2";
        String upd1 = "2026-08-10T09:00:00.000Z";
        String upd2 = "2026-08-10T09:05:00.000Z";
        String upd3 = "2026-08-10T09:10:00.000Z";

        UUID orderId = createOrder("EXT-MB2");

        // 10 (pickup requested) → 11 (waiting for route) → 20 (route assigned) — all 'created'.
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 10, "SEND", 0, "EXT-MB2", null, raw(upd1)))
            .thenReturn(new BostaDelivery(tracking, 11, "SEND", 0, "EXT-MB2", null, raw(upd2)))
            .thenReturn(new BostaDelivery(tracking, 20, "SEND", 0, "EXT-MB2", null, raw(upd3)));

        long ev1 = insertPendingEvent(tracking, 10, upd1);
        webhookJob.process(ev1, tenantId);
        long ev2 = insertPendingEvent(tracking, 11, upd2);
        webhookJob.process(ev2, tenantId);
        long ev3 = insertPendingEvent(tracking, 20, upd3);
        webhookJob.process(ev3, tenantId);

        String finalState = jdbc.queryForObject(
            "SELECT internal_state FROM shipments WHERE tracking_number = ?",
            String.class, tracking);
        assertThat(finalState)
            .as("never-dispatched shipment must remain pickable at 'created'")
            .isEqualTo("created");
    }

    // ── helpers (mirrors DeliveryStatusTest) ────────────────────────────────────

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

    private long insertPendingEvent(String tracking, int stateCode, String updatedAt) {
        String payload = String.format(
            "{\"trackingNumber\":\"%s\",\"state\":%d,\"type\":\"SEND\",\"updatedAt\":\"%s\"}",
            tracking, stateCode, updatedAt);
        return jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status) " +
            "VALUES ('bosta_poll'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending') " +
            "RETURNING id",
            Long.class, tenantId, payload);
    }

    private ObjectNode raw(String updatedAt) {
        ObjectNode n = mapper.createObjectNode();
        n.put("updatedAt", updatedAt);
        return n;
    }
}
