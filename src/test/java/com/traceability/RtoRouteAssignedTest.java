package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.fulfillment.OrderController;
import com.traceability.fulfillment.OrderNotesService;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V122 — return-to-origin (RTO) progress maps to 'returning', not a false 'exception'.
 *
 * Bosta relabels a SEND as type 20 "Return to Origin" when it starts coming back (same tracking
 * number, still the order's forward leg). Realistic history used throughout:
 *   SEND@10 → SEND@21 → SEND@24 → RTO@24 → RTO@20 → RTO@46
 * with the verify-by-fetch type string exactly as BostaHttpGateway produces it
 * ("RETURN TO ORIGIN" = type.value uppercased) and bare numeric tracking numbers.
 *
 * r1  RTO@20 after progress → 'returning' (not 'exception'); no piece moves.
 * r1b RTO@41 after progress → 'returning'; webhook processed, not failed; no piece moves.
 * r2  SEND@20 after progress → still 'exception' (the monotonic guard is unchanged).
 * r3  RTO@46 afterwards → 'returned'; piece with_courier → return_pending_inspection (unchanged).
 * r4  Derived order status is "Returning" after RTO@20 and stays so through RTO@24 / RTO@30.
 * r5  An RTO first seen at @20 (no prior history) → 'returning'.
 * r6  V122's repair UPDATE: stuck RTO forward leg → 'returning'; SEND and CRP 'exception' legs
 *     untouched; re-running changes nothing; derived status of the repaired leg reported.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RtoRouteAssignedTest {

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

    private static final String SEND = "SEND";
    private static final String RTO  = "RETURN TO ORIGIN";

    @Autowired JdbcTemplate               jdbc;
    @Autowired ObjectMapper               mapper;
    @Autowired EncryptionService          encryptionService;
    @Autowired BostaWebhookJob            webhookJob;
    @Autowired ShipmentLinkService        shipmentLinkService;
    @Autowired PlatformTransactionManager txm;
    @Autowired com.traceability.inventory.FulfillService fulfillService;
    @MockBean  BostaGateway               bostaGateway;
    @MockBean  JobScheduler               jobScheduler;

    private UUID tenantId;
    private UUID storeId;
    private UUID variantId;
    private OrderController controller;
    private int clock;
    private int pieceSeq;

    @BeforeAll
    void createFixtures() {
        tenantId  = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'RtoTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'rto_owner@test.local', 'h', 'owner')",
                    UUID.randomUUID(), tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'rto-test.myshopify.com', 'disconnected')",
                    storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'RTO-PROD', 'RTO Product', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'RTO-VAR', 'RTO Variant')", variantId, tenantId, productId);
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (?, 'bosta', ?, 'hash', 'active')", tenantId, encryptionService.encrypt("rto-key"));
        controller = new OrderController(jdbc, mapper, txm, new OrderNotesService(jdbc), fulfillService);
    }

    @BeforeEach
    void cleanup() {
        jdbc.execute("DELETE FROM shipment_status_history");
        jdbc.execute("DELETE FROM unlinked_bosta_deliveries");
        jdbc.execute("DELETE FROM piece_events");
        jdbc.execute("DELETE FROM allocations");
        jdbc.execute("DELETE FROM pieces");
        jdbc.execute("DELETE FROM shipments");
        jdbc.execute("DELETE FROM order_items");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM webhook_events");
        reset(bostaGateway);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    // ── r1: RTO@20 after progress → 'returning', no piece moves ─────────────────
    @Test
    void r1_rtoRouteAssignedAfterProgress_isReturning_movesNoPiece() {
        String tn = "7201550431";
        UUID orderId = createOrder("1001");
        String courierPiece = addPiece(orderId, "packed", "packed");

        deliver(tn, "1001", 10, SEND);
        deliver(tn, "1001", 21, SEND);            // packed → with_courier (real path)
        deliver(tn, "1001", 24, SEND);
        deliver(tn, "1001", 24, RTO);
        assertThat(pieceStatus(courierPiece)).isEqualTo("with_courier");

        // A piece still 'packed' on the order (custody lag) — 20:ALL would move it to
        // awaiting_pickup; the RTO row carries no piece move.
        String laggingPiece = addPiece(orderId, "packed", "packed");
        long eventsBefore = pieceEventCount();

        long ev = deliver(tn, "1001", 20, RTO);

        assertThat(shipmentState(tn)).as("RTO@20 after progress is return progress").isEqualTo("returning");
        assertThat(webhookStatus(ev)).isEqualTo("processed");
        assertThat(lastHistory(tn)).isEqualTo("returning@20");
        assertThat(pieceStatus(courierPiece)).isEqualTo("with_courier");
        assertThat(pieceStatus(laggingPiece)).as("no piece move on RTO@20").isEqualTo("packed");
        assertThat(pieceEventCount()).isEqualTo(eventsBefore);
    }

    // ── r1b: RTO@41 after progress → 'returning', webhook not failed ────────────
    @Test
    void r1b_rtoOutForReturnAfterProgress_isReturning_notFailed() {
        String tn = "7201550432";
        UUID orderId = createOrder("1002");
        String piece = addPiece(orderId, "packed", "packed");

        deliver(tn, "1002", 10, SEND);
        deliver(tn, "1002", 21, SEND);
        deliver(tn, "1002", 24, SEND);
        deliver(tn, "1002", 24, RTO);
        deliver(tn, "1002", 20, RTO);
        long eventsBefore = pieceEventCount();

        long ev = deliver(tn, "1002", 41, RTO);

        assertThat(webhookStatus(ev)).as("RTO@41 is a known state, not an unknown code").isEqualTo("processed");
        assertThat(shipmentState(tn)).isEqualTo("returning");
        assertThat(lastHistory(tn)).isEqualTo("returning@41");
        assertThat(pieceStatus(piece)).as("no return_in_transit move on RTO@41").isEqualTo("with_courier");
        assertThat(pieceEventCount()).isEqualTo(eventsBefore);
    }

    // ── r2: SEND@20 after progress → still 'exception' ──────────────────────────
    @Test
    void r2_sendRouteAssignedAfterProgress_stillException() {
        String tn = "7201550433";
        createOrder("1003");

        deliver(tn, "1003", 10, SEND);
        deliver(tn, "1003", 21, SEND);
        deliver(tn, "1003", 24, SEND);
        deliver(tn, "1003", 20, SEND);

        assertThat(shipmentState(tn)).as("monotonic guard unchanged for SEND").isEqualTo("exception");
        assertThat(lastHistory(tn)).isEqualTo("created@20");
    }

    // ── r3: RTO@46 afterwards → 'returned', piece → return_pending_inspection ───
    @Test
    void r3_rtoReturnedAfterRouteAssigned_returned_pieceToInspection() {
        String tn = "7201550434";
        UUID orderId = createOrder("1004");
        String piece = addPiece(orderId, "packed", "packed");

        deliver(tn, "1004", 10, SEND);
        deliver(tn, "1004", 21, SEND);
        deliver(tn, "1004", 24, SEND);
        deliver(tn, "1004", 24, RTO);
        deliver(tn, "1004", 20, RTO);
        deliver(tn, "1004", 46, RTO);

        assertThat(shipmentState(tn)).isEqualTo("returned");
        assertThat(pieceStatus(piece)).isEqualTo("return_pending_inspection");
    }

    // ── r4: derived order status "Returning" after RTO@20, stays through @24/@30 ─
    @Test
    void r4_derivedStatus_returning_andStaysAfterLaterRtoStates() {
        String tn = "7201550435";
        UUID orderId = createOrder("1005");

        deliver(tn, "1005", 10, SEND);
        deliver(tn, "1005", 21, SEND);
        deliver(tn, "1005", 24, SEND);
        deliver(tn, "1005", 24, RTO);
        assertThat(derivedKey(orderId)).isEqualTo("status.in_transit");

        deliver(tn, "1005", 20, RTO);
        assertThat(derivedKey(orderId)).isEqualTo("status.returning");

        deliver(tn, "1005", 24, RTO);
        assertThat(shipmentState(tn)).isEqualTo("with_courier");
        assertThat(derivedKey(orderId)).as("label follows furthest progress").isEqualTo("status.returning");

        deliver(tn, "1005", 30, RTO);
        assertThat(derivedKey(orderId)).isEqualTo("status.returning");
    }

    // ── r5: RTO first seen at @20 (no prior history) → 'returning' ──────────────
    @Test
    void r5_rtoFirstSeenAtRouteAssigned_isReturning() {
        String tn = "7201550436";
        createOrder("1006");

        long ev = deliver(tn, "1006", 20, RTO);

        assertThat(webhookStatus(ev)).isEqualTo("processed");
        assertThat(shipmentState(tn)).isEqualTo("returning");
    }

    // ── r6: V122 repair UPDATE ──────────────────────────────────────────────────
    @Test
    void r6_repair_stuckRtoLegOnly_idempotent() throws Exception {
        String repair = repairStatement();

        // Stuck RTO forward leg: the pre-V122 outcome of SEND@10 → 21 → 24 → RTO@24 → RTO@20.
        UUID rtoOrder = createOrder("1007");
        UUID rtoLeg = seedLeg(rtoOrder, "7201550437", "forward", 20, "Return to Origin", 20, "exception");
        seedHistory(rtoLeg, "created", 10);
        seedHistory(rtoLeg, "with_courier", 21);
        seedHistory(rtoLeg, "with_courier", 24);
        seedHistory(rtoLeg, "with_courier", 24);
        seedHistory(rtoLeg, "created", 20);

        // SEND forward leg the guard turned into 'exception' — must stay.
        UUID sendOrder = createOrder("1008");
        UUID sendLeg = seedLeg(sendOrder, "7201550438", "forward", 10, "Send", 20, "exception");
        seedHistory(sendLeg, "with_courier", 21);
        seedHistory(sendLeg, "created", 20);

        // CRP return leg at exception/20 — must stay (a CRP is not an RTO).
        UUID crpOrder = createOrder("1009");
        UUID crpLeg = seedLeg(crpOrder, "7201550439", "return", 25, "Customer Return Pickup", 20, "exception");
        seedHistory(crpLeg, "with_courier", 24);
        seedHistory(crpLeg, "created", 20);

        int first = jdbc.update(repair);
        assertThat(first).isEqualTo(1);
        assertThat(stateOf(rtoLeg)).isEqualTo("returning");
        assertThat(stateOf(sendLeg)).isEqualTo("exception");
        assertThat(stateOf(crpLeg)).isEqualTo("exception");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipment_status_history WHERE shipment_id = ? AND internal_state = 'returning'",
            Integer.class, rtoLeg)).as("history untouched").isZero();

        int second = jdbc.update(repair);
        assertThat(second).as("re-running changes nothing").isZero();

        // History still says created@20, so the label comes from its furthest progress
        // (with_courier) — "In transit", no longer "Needs attention".
        assertThat(derivedKey(rtoOrder)).isEqualTo("status.in_transit");
    }

    // ── CRP legs and the guard (Step 1 item 4) — demonstration only ─────────────
    @Disabled("Known gap, not fixed (Step 1 item 4): a CRP return leg that the monotonic guard turns " +
              "into 'exception' on CRP@20 after progress counts as TERMINAL (RETURN_LEG_TERMINAL_STATES), " +
              "so hasReturnLegAwaitingIntake() is false while it sits there. A mapping row is not the fix — " +
              "CRP@20 before pickup is genuinely 'created'. Enable when a fix is approved.")
    @Test
    void crp_exceptionFromGuard_stillAwaitsIntake() {
        UUID orderId = createOrder("1010");
        UUID leg = seedLeg(orderId, "7201550440", "return", 25, "Customer Return Pickup", 20, "exception");
        seedHistory(leg, "with_courier", 24);
        seedHistory(leg, "created", 20);

        assertThat(shipmentLinkService.hasReturnLegAwaitingIntake(orderId, tenantId))
            .as("a CRP leg between hub and merchant should still accept its intake scan")
            .isTrue();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** Runs one realistic Bosta update through the real webhook pipeline. */
    private long deliver(String tn, String ref, int state, String type) {
        String updatedAt = String.format("2026-09-%02dT%02d:00:00.000Z", 20 + (clock / 24), clock % 24);
        clock++;
        ObjectNode raw = mapper.createObjectNode();
        raw.put("trackingNumber", tn);
        raw.putObject("type").put("code", RTO.equals(type) ? 20 : 10)
            .put("value", RTO.equals(type) ? "Return to Origin" : "Send");
        raw.putObject("state").put("code", state);
        raw.put("businessReference", ref);
        raw.put("updatedAt", updatedAt);
        when(bostaGateway.fetchDelivery(anyString(), eq(tn)))
            .thenReturn(new BostaDelivery(tn, state, type, 1, ref, null, raw));
        long ev = jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status) " +
            "VALUES ('bosta_poll'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending') RETURNING id",
            Long.class, tenantId,
            String.format("{\"trackingNumber\":\"%s\",\"state\":%d,\"type\":\"%s\",\"updatedAt\":\"%s\"}",
                tn, state, type, updatedAt));
        webhookJob.process(ev, tenantId);
        return ev;
    }

    private UUID createOrder(String ref) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, now()) RETURNING id",
            UUID.class, tenantId, storeId, ref, "#" + ref);
    }

    private String addPiece(UUID orderId, String pieceStatus, String allocStatus) {
        String pieceId = "RTOPIECE" + String.format("%04d", ++pieceSeq);
        UUID itemId = jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, tenantId, orderId, variantId);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                    "VALUES (?, ?, ?, ?, ?, ?::piece_status)",
                    pieceId, tenantId, variantId, "PC-" + pieceId, "RT" + String.format("%06d", pieceSeq), pieceStatus);
        jdbc.update("INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status) " +
                    "VALUES (gen_random_uuid(), ?, ?, ?, ?::allocation_status)",
                    tenantId, itemId, pieceId, allocStatus);
        return pieceId;
    }

    private UUID seedLeg(UUID orderId, String tn, String leg, int typeCode, String typeValue,
                         int providerState, String internalState) {
        String raw = String.format(
            "{\"trackingNumber\":\"%s\",\"type\":{\"code\":%d,\"value\":\"%s\"},\"state\":{\"code\":%d}}",
            tn, typeCode, typeValue, providerState);
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, " +
            "    shipment_leg, provider_state, raw) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, ?, ?, ?::jsonb) RETURNING id",
            UUID.class, tenantId, orderId, tn, internalState, leg, providerState, raw);
    }

    private void seedHistory(UUID shipmentId, String internalState, int providerState) {
        jdbc.update(
            "INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, provider_state, occurred_at) " +
            "VALUES (?, ?, ?, ?, now() + (? * interval '1 minute'))",
            tenantId, shipmentId, internalState, providerState, clock++);
    }

    /** The repair statement exactly as V122 ships it (everything from its UPDATE on). */
    private String repairStatement() throws Exception {
        String sql = new String(new ClassPathResource("db/migration/V122__rto_route_assigned_returning.sql")
            .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int at = sql.indexOf("UPDATE shipments");
        assertThat(at).as("V122 must carry the repair UPDATE").isGreaterThanOrEqualTo(0);
        return sql.substring(at).trim().replaceAll(";$", "");
    }

    private String derivedKey(UUID orderId) {
        TenantContext.set(tenantId);
        try {
            return controller.detail(orderId).derivedStatus().primaryKey();
        } finally {
            TenantContext.clear();
        }
    }

    private String shipmentState(String tn) {
        return jdbc.queryForObject(
            "SELECT internal_state::text FROM shipments WHERE tracking_number = ?", String.class, tn);
    }

    private String stateOf(UUID shipmentId) {
        return jdbc.queryForObject(
            "SELECT internal_state::text FROM shipments WHERE id = ?", String.class, shipmentId);
    }

    private String lastHistory(String tn) {
        return jdbc.queryForObject(
            "SELECT h.internal_state || '@' || h.provider_state FROM shipment_status_history h " +
            "JOIN shipments s ON s.id = h.shipment_id WHERE s.tracking_number = ? " +
            "ORDER BY h.occurred_at DESC, h.id DESC LIMIT 1", String.class, tn);
    }

    private String webhookStatus(long ev) {
        return jdbc.queryForObject("SELECT status::text FROM webhook_events WHERE id = ?", String.class, ev);
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private long pieceEventCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM piece_events", Long.class);
    }
}
