package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.inventory.PickupSessionService;
import com.traceability.inventory.ShopifyInventoryService;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V207 — the first forward state proving Bosta has the parcel (24 received at warehouse, 30 in transit
 * between hubs, 41 out for delivery) moves its pieces to with_courier, as 21 always did (BROEK 2026-11-05:
 * parcels went 10 → 24 → 30 → 41 → 46 while their pieces sat at awaiting_pickup).
 *
 * f1  SEND 10 → 24: awaiting_pickup and packed pieces → with_courier; event 'courier_update', no actor,
 *     provider_state 24 — a Bosta status update, never a 'handed_to_courier' pickup handover.
 * f2  SEND 10 → 30 → with_courier.
 * f3  SEND 10 → 41 → with_courier.
 * f4  24, 30, 41 in a row: exactly one with_courier event per piece; the Step 5a hook fires once per piece.
 * f5  a piece still being picked ('reserved') does not move on 24.
 * f6  pickup scan after 24: ALREADY_WITH_COURIER, nothing recorded (no scan row, no piece event, no history
 *     row); a repeat scan answers the same.
 * f7  scanned into a pickup session before 24, closed after it: the close adds no second with_courier event.
 * f8  46 after 24: with_courier → return_pending_inspection (unchanged path).
 * f9  10 / 11 / 20 still move nothing to with_courier (pre-dispatch codes unchanged).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaForwardProofWithCourierTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
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

    @Autowired JdbcTemplate          jdbc;
    @Autowired ObjectMapper          mapper;
    @Autowired EncryptionService     encryptionService;
    @Autowired BostaWebhookJob       webhookJob;
    @Autowired PickupSessionService  pickups;
    @MockBean  BostaGateway          bostaGateway;
    @MockBean  JobScheduler          jobScheduler;
    @MockBean  ShopifyInventoryService shopifyInventory;

    private UUID tenantId, storeId, variantId, ownerId;
    private int clock;
    private int pieceSeq;

    @BeforeAll
    void createFixtures() {
        tenantId  = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        variantId = UUID.randomUUID();
        ownerId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ForwardProofTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'fwdproof_owner@test.local', 'h', 'owner')", ownerId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'fwdproof-test.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'FP-PROD', 'FP Product', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'FP-VAR', 'FP Variant')", variantId, tenantId, productId);
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (?, 'bosta', ?, 'hash', 'active')", tenantId, encryptionService.encrypt("fp-key"));
    }

    @BeforeEach
    void cleanup() {
        jdbc.execute("DELETE FROM pickup_shipments");
        jdbc.execute("DELETE FROM pickups");
        jdbc.execute("DELETE FROM shipment_status_history");
        jdbc.execute("DELETE FROM unlinked_bosta_deliveries");
        jdbc.execute("DELETE FROM piece_events");
        jdbc.execute("DELETE FROM allocations");
        jdbc.execute("DELETE FROM pieces");
        jdbc.execute("DELETE FROM shipments");
        jdbc.execute("DELETE FROM order_items");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM webhook_events");
        reset(bostaGateway, shopifyInventory);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void f1_received24_movesPiecesToWithCourier_asBostaStatusUpdate() {
        String tn = "7301550001";
        UUID orderId = createOrder("2001");
        String awaiting = addPiece(orderId, "awaiting_pickup", "packed");
        String packed   = addPiece(orderId, "packed", "packed");

        deliver(tn, "2001", 10, SEND);
        assertThat(pieceStatus(awaiting)).isEqualTo("awaiting_pickup");

        deliver(tn, "2001", 24, SEND);

        for (String p : List.of(awaiting, packed)) {
            assertThat(pieceStatus(p)).isEqualTo("with_courier");
            Map<String, Object> ev = withCourierEvents(p).get(0);
            assertThat(withCourierEvents(p)).hasSize(1);
            assertThat(ev.get("event_type")).as("Bosta status update, like 21").isEqualTo("courier_update");
            assertThat(ev.get("actor_user_id")).as("system — no person").isNull();
            assertThat(ev.get("provider_state")).isEqualTo("24");
            assertThat(ev.get("pickup_id")).as("not a pickup handover").isNull();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE event_type = 'handed_to_courier'",
            Integer.class)).isZero();
        assertThat(shipmentState(tn)).isEqualTo("with_courier");
    }

    @Test
    void f2_inTransit30_movesPieceToWithCourier() {
        String tn = "7301550002";
        UUID orderId = createOrder("2002");
        String piece = addPiece(orderId, "awaiting_pickup", "packed");

        deliver(tn, "2002", 10, SEND);
        deliver(tn, "2002", 30, SEND);

        assertThat(pieceStatus(piece)).isEqualTo("with_courier");
        assertThat(withCourierEvents(piece).get(0).get("provider_state")).isEqualTo("30");
    }

    @Test
    void f3_outForDelivery41_movesPieceToWithCourier() {
        String tn = "7301550003";
        UUID orderId = createOrder("2003");
        String piece = addPiece(orderId, "awaiting_pickup", "packed");

        deliver(tn, "2003", 10, SEND);
        deliver(tn, "2003", 41, SEND);

        assertThat(pieceStatus(piece)).isEqualTo("with_courier");
        assertThat(withCourierEvents(piece).get(0).get("provider_state")).isEqualTo("41");
    }

    @Test
    void f4_24_30_41_oneEventPerPiece_dispatchHookOnce() {
        String tn = "7301550004";
        UUID orderId = createOrder("2004");
        String piece = addPiece(orderId, "awaiting_pickup", "packed");

        deliver(tn, "2004", 10, SEND);
        deliver(tn, "2004", 24, SEND);
        deliver(tn, "2004", 30, SEND);
        deliver(tn, "2004", 41, SEND);
        deliver(tn, "2004", 24, SEND);

        assertThat(withCourierEvents(piece)).hasSize(1);
        verify(shopifyInventory, times(1)).onExchangeReplacementDispatched(eq(tenantId), eq(piece));
    }

    @Test
    void f5_reservedPiece_doesNotMoveOn24() {
        String tn = "7301550005";
        UUID orderId = createOrder("2005");
        String reserved = addPiece(orderId, "reserved", "active");

        deliver(tn, "2005", 10, SEND);
        long before = pieceEventCount();
        deliver(tn, "2005", 24, SEND);

        assertThat(pieceStatus(reserved)).isEqualTo("reserved");
        assertThat(pieceEventCount()).isEqualTo(before);
    }

    @Test
    void f6_pickupScanAfter24_alreadyWithCourier_nothingRecorded() {
        String tn = "7301550006";
        UUID orderId = createOrder("2006");
        String piece = addPiece(orderId, "awaiting_pickup", "packed");
        deliver(tn, "2006", 10, SEND);
        deliver(tn, "2006", 24, SEND);
        assertThat(pieceStatus(piece)).isEqualTo("with_courier");

        UUID session = pickups.openSession(tenantId, ownerId, LocalDate.now(), "10:00 to 13:00", null);
        long events  = pieceEventCount();
        long history = historyCount(tn);

        PickupSessionService.ScanResult r = pickups.scan(tenantId, session, ownerId, tn);

        assertThat(r.outcome()).isEqualTo(PickupSessionService.ScanOutcome.ALREADY_WITH_COURIER);
        assertThat(r.entry()).isNotNull();
        assertThat(r.entry().trackingNumber()).isEqualTo(tn);
        assertThat(r.entry().orderNumber()).isEqualTo("#2006");
        assertThat(scanRows(session)).as("no scan row").isZero();
        assertThat(pieceEventCount()).as("no duplicate piece event").isEqualTo(events);
        assertThat(historyCount(tn)).as("no history row").isEqualTo(history);

        PickupSessionService.ScanResult again = pickups.scan(tenantId, session, ownerId, tn);
        assertThat(again.outcome()).isEqualTo(PickupSessionService.ScanOutcome.ALREADY_WITH_COURIER);
        assertThat(scanRows(session)).isZero();
        assertThat(withCourierEvents(piece)).hasSize(1);
        assertThat(withCourierEvents(piece).get(0).get("event_type")).isEqualTo("courier_update");
    }

    @Test
    void f7_scannedBefore24_closedAfter_noSecondWithCourierEvent() {
        String tn = "7301550007";
        UUID orderId = createOrder("2007");
        String piece = addPiece(orderId, "awaiting_pickup", "packed");
        deliver(tn, "2007", 10, SEND);

        UUID session = pickups.openSession(tenantId, ownerId, LocalDate.now(), "10:00 to 13:00", null);
        assertThat(pickups.scan(tenantId, session, ownerId, tn).outcome())
            .isEqualTo(PickupSessionService.ScanOutcome.ACCEPTED);

        deliver(tn, "2007", 24, SEND);
        assertThat(pieceStatus(piece)).isEqualTo("with_courier");

        PickupSessionService.CloseResult closed = pickups.closeSession(tenantId, session, ownerId);

        assertThat(closed.pieceExceptions()).isEmpty();
        assertThat(pieceStatus(piece)).isEqualTo("with_courier");
        assertThat(withCourierEvents(piece)).hasSize(1);
        assertThat(withCourierEvents(piece).get(0).get("event_type")).isEqualTo("courier_update");
    }

    @Test
    void f8_returned46After24_toInspection() {
        String tn = "7301550008";
        UUID orderId = createOrder("2008");
        String piece = addPiece(orderId, "awaiting_pickup", "packed");

        deliver(tn, "2008", 10, SEND);
        deliver(tn, "2008", 24, SEND);
        deliver(tn, "2008", 46, SEND);

        assertThat(pieceStatus(piece)).isEqualTo("return_pending_inspection");
        assertThat(jdbc.queryForObject(
            "SELECT from_status::text FROM piece_events WHERE piece_id = ? AND to_status = 'return_pending_inspection'",
            String.class, piece)).isEqualTo("with_courier");
    }

    @Test
    void f9_preDispatchCodes_moveNothingToWithCourier() {
        String tn = "7301550009";
        UUID orderId = createOrder("2009");
        String piece = addPiece(orderId, "packed", "packed");

        deliver(tn, "2009", 10, SEND);
        deliver(tn, "2009", 11, SEND);
        deliver(tn, "2009", 20, SEND);

        assertThat(pieceStatus(piece)).as("20 moves packed → awaiting_pickup, never further").isEqualTo("awaiting_pickup");
        assertThat(withCourierEvents(piece)).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** Runs one Bosta update through the real webhook pipeline (verify-by-fetch stubbed). */
    private long deliver(String tn, String ref, int state, String type) {
        String updatedAt = String.format("2026-11-%02dT%02d:00:00.000Z", 1 + (clock / 24), clock % 24);
        clock++;
        ObjectNode raw = mapper.createObjectNode();
        raw.put("trackingNumber", tn);
        raw.putObject("type").put("code", 10).put("value", "Send");
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
            "VALUES (?, ?, ?, ?, 'packed'::order_status, now()) RETURNING id",
            UUID.class, tenantId, storeId, ref, "#" + ref);
    }

    private String addPiece(UUID orderId, String pieceStatus, String allocStatus) {
        String pieceId = "FPPIECE" + String.format("%05d", ++pieceSeq);
        UUID itemId = jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, tenantId, orderId, variantId);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?::piece_status, ?)",
                    pieceId, tenantId, variantId, "PC-" + pieceId, "FP" + String.format("%06d", pieceSeq),
                    pieceStatus, orderId);
        jdbc.update("INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status) " +
                    "VALUES (gen_random_uuid(), ?, ?, ?, ?::allocation_status)",
                    tenantId, itemId, pieceId, allocStatus);
        return pieceId;
    }

    private List<Map<String, Object>> withCourierEvents(String pieceId) {
        return jdbc.queryForList(
            "SELECT event_type, actor_user_id, metadata->>'provider_state' AS provider_state, " +
            "       metadata->>'pickup_id' AS pickup_id " +
            "FROM piece_events WHERE piece_id = ? AND to_status = 'with_courier' ORDER BY id", pieceId);
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String shipmentState(String tn) {
        return jdbc.queryForObject(
            "SELECT internal_state::text FROM shipments WHERE tracking_number = ?", String.class, tn);
    }

    private long pieceEventCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM piece_events", Long.class);
    }

    private long historyCount(String tn) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipment_status_history h JOIN shipments s ON s.id = h.shipment_id " +
            "WHERE s.tracking_number = ?", Long.class, tn);
    }

    private long scanRows(UUID session) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pickup_shipments WHERE pickup_id = ?", Long.class, session);
    }
}
