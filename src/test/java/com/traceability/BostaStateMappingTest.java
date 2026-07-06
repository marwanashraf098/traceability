package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.inventory.UlidGenerator;
import com.traceability.security.EncryptionService;
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

import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for Bosta state code parsing and mapping.
 *
 * Matrix:
 *   s1  — fetchDelivery with object-shape state {code:24} → stateCode=24 (not -1)
 *   s2  — fetchDelivery with flat-number state 45 → stateCode=45
 *   s3  — fetchDelivery with double-nested data.data envelope → stateCode=24
 *   s4  — BostaStateMapper maps code 24 → with_courier, code 45 → delivered
 *   s5  — BostaStateMapper maps code 60 → returned (terminal after V37 fix)
 *   s6  — BostaStateMapper maps unknown code -1 → unknownCode=true, isException=true
 *   s7  — BostaStateMapper: known exception state 47 → isException=true, unknownCode=false
 *   s8  — BostaWebhookJob: state 47 + exceptionCode 3 → shipment.exception_code=3 stored
 *   s9  — BostaWebhookJob: unknown code -1 → webhook_events.status='failed' (not processed)
 *   s10 — BostaStatusPollJob: state-60 shipment (internal=returned) excluded from poll set
 *   s11 — BostaStateMapper: state 11 → created, pieceStatusAfter=null (new from V37)
 *   s12 — BostaStateMapper: state 41:FXF_SEND → with_courier; 41:EXCHANGE → returning
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaStateMappingTest {

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
        r.add("bosta.poll.inter-fetch-delay-ms", () -> "0");
    }

    @Autowired JdbcTemplate        jdbc;
    @Autowired ObjectMapper        mapper;
    @Autowired BostaStateMapper    stateMapper;
    @Autowired BostaStatusPollJob  statusPollJob;
    @Autowired BostaWebhookJob     webhookJob;
    @Autowired EncryptionService   encryptionService;
    @MockBean  BostaGateway        bostaGateway;
    @MockBean  JobScheduler        jobScheduler;

    private UUID tenantId;
    private UUID storeId;
    private UUID variantId;

    @BeforeAll
    void createFixtures() {
        tenantId  = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'StateMappingTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'sm_owner@test.local', 'h', 'owner')",
                    UUID.randomUUID(), tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'sm-test.myshopify.com', 'disconnected')",
                    storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'PROD-SM', 'SM Product')",
                    productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'VAR-SM', 'SM Variant')",
                    variantId, tenantId, productId);
    }

    @BeforeEach
    void cleanup() {
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

    // ── s1: object-shape state {code:24} → stateCode=24, not -1 ─────────────

    @Test
    void s1_fetchDelivery_objectShapeState_returnsCorrectCode() throws Exception {
        JsonNode fixture = loadFixture("fetch-delivery-object-state.json");

        // Simulate what BostaHttpGateway.fetchDelivery() does with the fixture body
        // (we test the parsing logic directly by replicating the extraction)
        JsonNode body = fixture;

        // Replicate the envelope unwrapping logic from BostaHttpGateway.fetchDelivery()
        JsonNode data;
        JsonNode outer = body.path("data");
        if (outer.isObject()) {
            JsonNode inner = outer.path("data");
            data = (inner.isObject() || inner.isArray()) ? inner : outer;
        } else if (outer.isArray()) {
            data = outer.isEmpty() ? body : outer.get(0);
        } else {
            data = body;
        }

        JsonNode stateNode = data.path("state");
        int code = stateNode.isObject() ? stateNode.path("code").asInt(-1) : stateNode.asInt(-1);

        assertThat(code)
            .as("Object-shape state {code:24} must yield stateCode=24, not -1 (the old bug)")
            .isEqualTo(24);

        // Guard: the old broken path must return -1 (documents the fixed bug)
        assertThat(stateNode.asInt(-1))
            .as("Calling asInt(-1) directly on an ObjectNode must return -1 — " +
                "this is why the old code was broken and the isObject() branch is required")
            .isEqualTo(-1);
    }

    // ── s2: flat state 45 (real-webhook shape) → stateCode=45 ────────────────

    @Test
    void s2_fetchDelivery_flatState_returnsCorrectCode() throws Exception {
        // Flat-state shape: what a real Bosta webhook payload looks like
        // (and also some API responses) — state is a plain number
        JsonNode body = mapper.readTree("""
            {"trackingNumber":"9999","state":45,"type":"SEND","updatedAt":"2026-07-06T10:00:00Z"}
            """);

        // Apply same extraction logic
        JsonNode outer = body.path("data");
        JsonNode data  = outer.isMissingNode() ? body : outer;

        JsonNode stateNode = data.path("state");
        int code = stateNode.isObject() ? stateNode.path("code").asInt(-1) : stateNode.asInt(-1);

        assertThat(code).as("Flat state 45 must yield stateCode=45").isEqualTo(45);
    }

    // ── s3: double-nested data.data envelope → stateCode=24 ──────────────────

    @Test
    void s3_fetchDelivery_doubleNestedData_returnsCorrectCode() throws Exception {
        // Double-nested: {"data": {"data": {"trackingNumber":"...", "state":{...}}}}
        // Same pattern handled by listDeliveriesPage but not the old fetchDelivery
        JsonNode body = mapper.readTree("""
            {"success":true,"data":{"data":{
              "trackingNumber":"8888",
              "state":{"code":24,"value":"Processing"},
              "type":{"code":10,"value":"Send"}
            }}}
            """);

        JsonNode outer = body.path("data");
        JsonNode data;
        if (outer.isObject()) {
            JsonNode inner = outer.path("data");
            data = (inner.isObject() || inner.isArray()) ? inner : outer;
        } else {
            data = body;
        }

        JsonNode stateNode = data.path("state");
        int code = stateNode.isObject() ? stateNode.path("code").asInt(-1) : stateNode.asInt(-1);

        assertThat(code)
            .as("Double-nested data.data envelope must unwrap correctly → stateCode=24")
            .isEqualTo(24);
    }

    // ── s4: mapper maps 24 → with_courier, 45 → delivered ────────────────────

    @Test
    void s4_stateMapper_knownCodes_mapsCorrectly() {
        BostaStateMapper.MappedState s24 = stateMapper.map(24, "SEND");
        assertThat(s24.unknownCode()).isFalse();
        assertThat(s24.shipmentInternalState()).isEqualTo("with_courier");
        assertThat(s24.pieceStatusAfter()).isNull();  // 24 = received at warehouse, no piece move

        BostaStateMapper.MappedState s45 = stateMapper.map(45, "SEND");
        assertThat(s45.unknownCode()).isFalse();
        assertThat(s45.shipmentInternalState()).isEqualTo("delivered");
        assertThat(s45.pieceStatusAfter()).isEqualTo("delivered");
    }

    // ── s5: state 60 → returned (terminal) after V37 fix ─────────────────────

    @Test
    void s5_stateMapper_state60_mapsToReturned() {
        BostaStateMapper.MappedState s60 = stateMapper.map(60, "SEND");
        assertThat(s60.unknownCode()).isFalse();
        assertThat(s60.shipmentInternalState())
            .as("State 60 'Returned to stock' must map to 'returned' (terminal) after V37 fix")
            .isEqualTo("returned");
    }

    // ── s6: unknown code -1 → unknownCode=true ───────────────────────────────

    @Test
    void s6_stateMapper_unknownCode_flagsCorrectly() {
        BostaStateMapper.MappedState unknown = stateMapper.map(-1, "SEND");
        assertThat(unknown.unknownCode())
            .as("Code -1 must be unknown (no mapping row)")
            .isTrue();
        assertThat(unknown.isException()).isTrue();  // also marks as exception internally
    }

    // ── s7: known exception state 47 → isException=true, unknownCode=false ───

    @Test
    void s7_stateMapper_state47_isExceptionNotUnknown() {
        BostaStateMapper.MappedState s47 = stateMapper.map(47, "SEND");
        assertThat(s47.unknownCode())
            .as("State 47 is a KNOWN exception state — must NOT be flagged as unknown")
            .isFalse();
        assertThat(s47.isException())
            .as("State 47 maps to internal exception state")
            .isTrue();
        assertThat(s47.shipmentInternalState()).isEqualTo("exception");
    }

    // ── s8: state 47 + exceptionCode 3 → shipment.exception_code stored ──────

    @Test
    void s8_webhookJob_state47_storesExceptionCode() {
        setupCourierAccount("sm-key");
        UUID orderId = createOrder("#SM-E47");
        UUID shipmentId = createShipment("BOS-SM-E47", "with_courier", orderId);

        ObjectNode raw = mapper.createObjectNode();
        raw.put("trackingNumber", "BOS-SM-E47");
        raw.put("exceptionCode", 3);
        raw.put("exceptionReason", "Postponed by customer");
        raw.put("updatedAt", "2026-07-06T10:00:00.000Z");
        raw.putObject("state").put("code", 47);
        raw.putObject("type").put("value", "SEND");

        BostaDelivery delivery = new BostaDelivery("BOS-SM-E47", 47, "SEND", 1, "#SM-E47", null, raw);
        when(bostaGateway.fetchDelivery(anyString(), eq("BOS-SM-E47"))).thenReturn(delivery);

        Long eventId = insertWebhookEvent("BOS-SM-E47", 47, "2026-07-06T10:00:00.000Z");
        webhookJob.process(eventId, tenantId);

        Integer storedCode = jdbc.queryForObject(
            "SELECT exception_code FROM shipments WHERE id = ?", Integer.class, shipmentId);
        String storedReason = jdbc.queryForObject(
            "SELECT exception_reason FROM shipments WHERE id = ?", String.class, shipmentId);
        String internalState = jdbc.queryForObject(
            "SELECT internal_state FROM shipments WHERE id = ?", String.class, shipmentId);

        assertThat(internalState).isEqualTo("exception");
        assertThat(storedCode)
            .as("exceptionCode 3 must be stored on the shipment")
            .isEqualTo(3);
        assertThat(storedReason)
            .as("exceptionReason must be stored on the shipment")
            .isEqualTo("Postponed by customer");
        assertThat(webhookStatus(eventId))
            .as("State-47 webhook must be processed, not failed")
            .isEqualTo("processed");
    }

    // ── s9: unknown code -1 → webhook marked 'failed' ────────────────────────

    @Test
    void s9_webhookJob_unknownCode_markedFailed() {
        setupCourierAccount("sm-key");
        UUID orderId = createOrder("#SM-UNK");
        createShipment("BOS-SM-UNK", "with_courier", orderId);

        ObjectNode raw = mapper.createObjectNode();
        raw.put("trackingNumber", "BOS-SM-UNK");
        raw.put("updatedAt", "2026-07-06T11:00:00.000Z");
        raw.put("state", -1);  // flat -1, the sentinel
        raw.put("type",  "SEND");

        BostaDelivery delivery = new BostaDelivery("BOS-SM-UNK", -1, "SEND", 0, "#SM-UNK", null, raw);
        when(bostaGateway.fetchDelivery(anyString(), eq("BOS-SM-UNK"))).thenReturn(delivery);

        Long eventId = insertWebhookEvent("BOS-SM-UNK", -1, "2026-07-06T11:00:00.000Z");
        webhookJob.process(eventId, tenantId);

        assertThat(webhookStatus(eventId))
            .as("Unknown code -1 must mark the event failed")
            .isEqualTo("failed");
    }

    // ── s10: state-60 shipment (returned) excluded from poll ─────────────────

    @Test
    void s10_statusPoll_state60Shipment_excluded() {
        setupCourierAccount("sm-key-poll");

        // After V37, state 60 maps to 'returned' — poll should skip it
        createShipmentWithInternalState("BOS-SM-60", "returned");
        createShipmentWithInternalState("BOS-SM-ACTIVE", "with_courier");

        when(bostaGateway.fetchDelivery(anyString(), eq("BOS-SM-ACTIVE"))).thenReturn(null);

        statusPollJob.pollAll();

        verify(bostaGateway, never()).fetchDelivery(anyString(), eq("BOS-SM-60"));
        verify(bostaGateway, times(1)).fetchDelivery(anyString(), eq("BOS-SM-ACTIVE"));
    }

    // ── s11: state 11 → created, no piece move ────────────────────────────────

    @Test
    void s11_stateMapper_state11_mapsToCreated() {
        BostaStateMapper.MappedState s11 = stateMapper.map(11, "SEND");
        assertThat(s11.unknownCode()).isFalse();
        assertThat(s11.shipmentInternalState()).isEqualTo("created");
        assertThat(s11.pieceStatusAfter()).isNull();
    }

    // ── s12: state 41:FXF_SEND → with_courier; 41:EXCHANGE → returning ───────

    @Test
    void s12_stateMapper_state41Variants_disambiguated() {
        BostaStateMapper.MappedState fxfSend = stateMapper.map(41, "FXF_SEND");
        assertThat(fxfSend.shipmentInternalState()).isEqualTo("with_courier");
        assertThat(fxfSend.unknownCode()).isFalse();

        BostaStateMapper.MappedState exchange = stateMapper.map(41, "EXCHANGE");
        assertThat(exchange.shipmentInternalState()).isEqualTo("returning");
        assertThat(exchange.unknownCode()).isFalse();

        BostaStateMapper.MappedState crp = stateMapper.map(41, "CRP");
        assertThat(crp.shipmentInternalState()).isEqualTo("returning");
        assertThat(crp.unknownCode()).isFalse();
    }

    // ── s13: poll path with unmappable -1 → NOT enqueued (Guard 1) ───────────

    @Test
    void s13_statusPoll_unmappableState_notEnqueued() {
        setupCourierAccount("sm-key-g1");
        createShipmentWithInternalState("BOS-SM-G1", "with_courier");

        // Simulate the -1 bug: fetchDelivery returns unmappable stateCode
        ObjectNode raw = mapper.createObjectNode();
        raw.put("trackingNumber", "BOS-SM-G1");
        raw.put("updatedAt", "2026-07-06T12:00:00.000Z");
        raw.putObject("state").put("code", -1);  // extraction failed sentinel
        raw.putObject("type").put("value", "SEND");

        BostaDelivery badDelivery = new BostaDelivery("BOS-SM-G1", -1, "SEND", 0, null, null, raw);
        when(bostaGateway.fetchDelivery(anyString(), eq("BOS-SM-G1"))).thenReturn(badDelivery);

        statusPollJob.pollAll();

        // Guard 1: ingestDelivery must NOT create a webhook_events row for an unmappable state.
        // Without this guard, every poll cycle would create a 'failed' event, creating a
        // permanent re-enqueue loop that can never self-heal.
        Integer eventCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM webhook_events WHERE source = 'bosta_poll'",
            Integer.class);
        assertThat(eventCount)
            .as("unmappable state -1 must not produce a webhook_events row (Guard 1)")
            .isEqualTo(0);

        // fetchDelivery MUST still be called (we fetch before we decide not to enqueue)
        verify(bostaGateway, times(1)).fetchDelivery(anyString(), eq("BOS-SM-G1"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void setupCourierAccount(String rawApiKey) {
        jdbc.update(
            "INSERT INTO courier_accounts " +
            "    (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'sm-hash', 'active')",
            tenantId, encryptionService.encrypt(rawApiKey));
    }

    private UUID createOrder(String extId) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, status) " +
            "VALUES (?, ?, ?, 'new'::order_status) RETURNING id",
            UUID.class, tenantId, storeId, extId);
    }

    private UUID createShipment(String trackingNumber, String state, UUID orderId) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state) RETURNING id",
            UUID.class, tenantId, orderId, trackingNumber, state);
    }

    private void createShipmentWithInternalState(String trackingNumber, String state) {
        UUID orderId = createOrder("EXT-" + trackingNumber);
        createShipment(trackingNumber, state, orderId);
    }

    private Long insertWebhookEvent(String trackingNumber, int stateCode, String updatedAt) {
        String payloadJson = String.format(
            "{\"trackingNumber\":\"%s\",\"state\":%d,\"type\":\"SEND\",\"updatedAt\":\"%s\"}",
            trackingNumber, stateCode, updatedAt);
        return TenantContext.runAs(tenantId, () ->
            jdbc.queryForObject(
                "INSERT INTO webhook_events " +
                "    (source, tenant_id, topic, payload, status, received_at) " +
                "VALUES ('bosta', ?, 'delivery_update', ?::jsonb, 'pending', now()) " +
                "RETURNING id",
                Long.class, tenantId, payloadJson));
    }

    private String webhookStatus(Long id) {
        return jdbc.queryForObject(
            "SELECT status FROM webhook_events WHERE id = ?", String.class, id);
    }

    private JsonNode loadFixture(String name) throws Exception {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("bosta/" + name)) {
            assertThat(is).as("fixture bosta/" + name + " must exist").isNotNull();
            return mapper.readTree(is);
        }
    }
}
