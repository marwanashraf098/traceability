package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantContext;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-EXCHANGE Step 4-close Part 1 (bug #1) — BostaWebhookJob.reinterpretExchangeForwardLeg()
 * must always attempt a match afterward, mirroring process()'s own unconditional
 * attemptMatch() call on the live post-pack path. Before this fix, the method returned
 * right after applyMappedState() (or right after the "shipment not found at
 * internal_state='created'" guard) without ever calling attemptMatch() — an admin-unstuck
 * exchange sat at status='mapped' forever.
 *
 * (r1) stuck-at-'created' shipment + a single matching delivered candidate → reinterpret
 *      flips the forward leg AND resolves the exchange to 'matched' in the same call.
 * (r2) stuck-at-'created' shipment + no matching candidate at all → reinterpret flips the
 *      forward leg AND resolves the exchange to 'unmatched' (not left at 'mapped').
 * (r3) shipment ALREADY at internal_state='delivered' (the real 184907356/877468285
 *      shape post-unstick) + a matching candidate → reinterpret() returns false (no flip
 *      to apply) but STILL resolves the exchange to 'matched' — the exact scenario the
 *      diagnosis found broken.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReinterpretExchangeMatcherTest {

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

    @Autowired JdbcTemplate      jdbc;
    @Autowired ObjectMapper      mapper;
    @Autowired BostaWebhookJob   webhookJob;
    @MockBean  JobScheduler      jobScheduler;

    UUID tenantId, storeId, productId, variantId;

    @BeforeAll
    void setupFixture() {
        tenantId  = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ReinterpretMatcherTenant')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'reint.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-REINT', 'Bucket Hat', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-REINT', 'Red', 'RED-REINT')", variantId, tenantId, productId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clear() {
        TenantContext.clear();
        jdbc.update("DELETE FROM exchanges WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    // ── r1: stuck at 'created' + one candidate → flips AND matches ──────────────

    @Test
    void r1_stuckAtCreated_withSingleCandidate_flipsAndResolvesMatched() {
        UUID originalOrderId = createOrder("01001112222");
        String pieceId = createDeliveredPiece(originalOrderId);
        UUID outboundOrderId = createOrder("01009998888"); // synthetic Model-A order
        String tracking = "REINT-R1";
        String raw = realTimelineRaw("01001112222");
        seedForwardShipment(tracking, outboundOrderId, "created", raw);
        UUID exchangeId = seedExchange(tracking, outboundOrderId, raw);

        boolean applied = webhookJob.reinterpretExchangeForwardLeg(tracking);

        assertThat(applied).as("shipment was at 'created' — the flip must apply").isTrue();
        String shipmentState = jdbc.queryForObject(
            "SELECT internal_state::text FROM shipments WHERE tracking_number = ?", String.class, tracking);
        assertThat(shipmentState).isEqualTo("delivered");

        Map<String, Object> exchange = jdbc.queryForMap("SELECT * FROM exchanges WHERE id = ?", exchangeId);
        assertThat(exchange.get("status")).as("bug #1 fix: matcher must run after the flip").isEqualTo("matched");
        assertThat(exchange.get("matched_order_id").toString()).isEqualTo(originalOrderId.toString());
    }

    // ── r2: stuck at 'created' + no candidate → flips AND resolves unmatched ────

    @Test
    void r2_stuckAtCreated_withNoCandidate_flipsAndResolvesUnmatched() {
        UUID outboundOrderId = createOrder("01009997777");
        String tracking = "REINT-R2";
        // No order anywhere has this phone.
        String raw = realTimelineRaw("01000000001");
        seedForwardShipment(tracking, outboundOrderId, "created", raw);
        UUID exchangeId = seedExchange(tracking, outboundOrderId, raw);

        boolean applied = webhookJob.reinterpretExchangeForwardLeg(tracking);

        assertThat(applied).isTrue();
        Map<String, Object> exchange = jdbc.queryForMap("SELECT * FROM exchanges WHERE id = ?", exchangeId);
        assertThat(exchange.get("status"))
            .as("empty candidate pool — the honest landing is 'unmatched', not left at 'mapped'")
            .isEqualTo("unmatched");
    }

    // ── r3: THE diagnosis scenario — already 'delivered', re-invoked → still matches ──

    @Test
    void r3_alreadyDelivered_reinvoked_noFlipButStillResolvesMatched() {
        UUID originalOrderId = createOrder("01005556666");
        String pieceId = createDeliveredPiece(originalOrderId);
        UUID outboundOrderId = createOrder("01004443333");
        String tracking = "REINT-R3";
        String raw = realTimelineRaw("01005556666");
        // Already 'delivered' — exactly the post-unstick shape for 184907356/877468285.
        seedForwardShipment(tracking, outboundOrderId, "delivered", raw);
        UUID exchangeId = seedExchange(tracking, outboundOrderId, raw);

        boolean applied = webhookJob.reinterpretExchangeForwardLeg(tracking);

        assertThat(applied)
            .as("nothing to flip — internal_state was already past 'created'")
            .isFalse();
        Map<String, Object> exchange = jdbc.queryForMap("SELECT * FROM exchanges WHERE id = ?", exchangeId);
        assertThat(exchange.get("status"))
            .as("Step 4-close bug #1: the flip no-ops but the match MUST still run")
            .isEqualTo("matched");
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private UUID createOrder(String phone) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    customer_name, customer_phone, payment_method, placed_at) " +
            "VALUES (?, ?, ?, '#REINT', 'new'::order_status, 'Buyer', ?, 'cod', now()) RETURNING id",
            UUID.class, tenantId, storeId, "EXT-REINT-" + UUID.randomUUID(), phone);
    }

    private String createDeliveredPiece(UUID orderId) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces " +
            "(id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
            "        'delivered'::piece_status, ?, now())",
            id, tenantId, variantId, "PC-" + id, id, orderId);
        return id;
    }

    private void seedForwardShipment(String tracking, UUID orderId, String internalState, String rawJson) {
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward', ?::jsonb)",
            tenantId, orderId, tracking, internalState, rawJson);
    }

    private UUID seedExchange(String tracking, UUID outboundOrderId, String rawJson) {
        return jdbc.queryForObject(
            "INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_order_id, raw) " +
            "VALUES (?, ?, 'mapped', ?, ?::jsonb) RETURNING id",
            UUID.class, tenantId, tracking, outboundOrderId, rawJson);
    }

    /** Real 184907356-shaped payload (ExchangeStateInterpreterTest fixture (a)) — the
     *  most-recently-completed timeline milestone is out_for_return/exchanged_returned →
     *  interpretForwardLeg() resolves to 'delivered'. returnSpecs populated (required for
     *  attemptMatch() to proceed past its not-ready early-bail) with the given phone. */
    private String realTimelineRaw(String phone) {
        ObjectNode raw = mapper.createObjectNode();
        raw.putObject("state").put("code", 46).put("value", "Returned to business");
        raw.putObject("type").put("code", 30).put("value", "EXCHANGE");
        ArrayNode timeline = raw.putArray("timeline");
        doneEntry(timeline, 10, "new", "2026-08-15T11:36:34.368Z");
        doneEntry(timeline, 21, "picked_up", "2026-08-18T13:44:08.456Z");
        doneEntry(timeline, 30, "in_transit", "2026-08-20T14:06:58.136Z");
        doneEntry(timeline, 41, "out_for_exchange", "2026-08-19T05:41:59.465Z");
        doneEntry(timeline, 46, "out_for_return", "2026-08-23T07:35:59.939Z");
        doneEntry(timeline, 46, "exchanged_returned", "2026-08-22T13:44:13.236Z");
        raw.putObject("receiver").put("phone", phone);
        raw.putObject("returnSpecs").putObject("packageDetails")
            .put("description", "Red Bucket Hat").put("itemsCount", 1);
        return raw.toString();
    }

    private void doneEntry(ArrayNode timeline, int code, String value, String isoDate) {
        ObjectNode entry = timeline.addObject();
        entry.put("code", code).put("done", true).put("value", value).put("date", isoDate);
    }
}
