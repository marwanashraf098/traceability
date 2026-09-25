package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaIngestionHelper;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ReturnSessionService;
import com.traceability.inventory.ShipmentLinkService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Step 4c-1 (V104) — more than one courier-return (CRP) leg per order.
 *
 * m1–m4, m6 are the two-leg cases for the canonical scan-evidence rule
 * (ShipmentLinkService.returnLegScanEvidenceSql) as used by close() intake stamping,
 * resolveReturnLegIfComplete() and the awaiting-scan / return_leg_unscanned check, plus the
 * second CRP on an order linking as a return leg. All of them fail against the pre-V104
 * order-wide logic (m4 against the V43 index). m5 pins today's single-leg courier-lag
 * behaviour (an in-transit single leg is stamped by an item scan alone) — it must pass
 * before and after.
 *
 * CRP fixtures go through the real ingest pipeline (BostaIngestionHelper →
 * BostaWebhookJob.process) with type.code=25 and bare numeric tracking numbers; state 41
 * maps a CRP to 'returning' (in transit), 46 to 'returned'.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiReturnLegTest {

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

    private static final String RAW_API_KEY = "multi-leg-api-key";
    private static final int    OUT_OF_WINDOW_DAYS = 60;   // > customer_return_window_days (30)

    @Autowired JdbcTemplate          jdbc;
    @Autowired ObjectMapper          mapper;
    @Autowired EncryptionService     encryptionService;
    @Autowired BostaWebhookJob       webhookJob;
    @Autowired BostaIngestionHelper  ingestionHelper;
    @Autowired ReturnSessionService  sessionSvc;
    @Autowired ShipmentLinkService   linkSvc;
    @Autowired ExceptionService      exceptionSvc;

    @MockBean BostaGateway bostaGateway;
    @MockBean JobScheduler jobScheduler;

    UUID tenantId, storeId, variantId, locationId, actorId;

    @BeforeAll
    void setupFixture() {
        tenantId   = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        variantId  = UUID.randomUUID();
        locationId = UUID.randomUUID();
        actorId    = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'MultiLegTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Worker', 'w@multileg.test', 'h', 'owner')", actorId, tenantId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Returns Bay')",
                    locationId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'multileg.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-ML', 'Linen Shirt', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-ML', 'Sand M', 'LIN-SAND-M')", variantId, tenantId, productId);
        jdbc.update("INSERT INTO courier_accounts " +
                    "(id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'multi-leg-hash', 'active')",
                    tenantId, encryptionService.encrypt(RAW_API_KEY));
    }

    @BeforeEach void setCtx() { TenantContext.set(tenantId); }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_session_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_session_shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_sessions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shopify_inventory_adjustments WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipment_status_history WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM webhook_events WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    // ── m1: C + D — A returned and scanned via its AWB, B still in transit ──────

    @Test
    void m1_closeStampsOnlyTheScannedLeg_inTransitLegNeitherStampedNorFlipped() {
        DeliveredOrder o = deliveredOrder(2);
        String legA = ingestCrp(o, 41, 46);
        String legB = ingestCrp(o, 41);
        assertThat(state(legB)).isEqualTo("returning");

        UUID s1 = sessionSvc.createSession(null, actorId);
        sessionSvc.scan(s1, legA, locationId, actorId);                          // A's AWB
        sessionSvc.scan(s1, "PC-" + o.pieces().get(0), locationId, actorId);
        sessionSvc.disposition(s1, o.pieces().get(0), "restock", null, locationId, actorId);
        assertThat(state(legB))
            .as("resolveReturnLegIfComplete must not flip B — it has no scan evidence of its own")
            .isEqualTo("returning");
        sessionSvc.close(s1, actorId);

        assertThat(intakeCompletedAt(legA)).as("A: own AWB + items scanned → stamped").isNotNull();
        assertThat(intakeCompletedAt(legB)).as("B: still with the courier, never scanned").isNull();

        // A later session: another piece of the order is scanned (no AWB) and restocked.
        // B is now the order's only unstamped leg, but it is in transit on a multi-leg
        // order, so only its own AWB could speak for it.
        UUID s2 = sessionSvc.createSession(null, actorId);
        sessionSvc.scan(s2, "PC-" + o.pieces().get(1), locationId, actorId);
        sessionSvc.disposition(s2, o.pieces().get(1), "restock", null, locationId, actorId);
        assertThat(state(legB)).as("still not flipped").isEqualTo("returning");
        sessionSvc.close(s2, actorId);
        assertThat(intakeCompletedAt(legB)).as("still not stamped").isNull();
    }

    // ── m2: E — a newer leg's scans no longer hide an older unscanned leg ────────

    @Test
    void m2_olderUnscannedLegStillAwaitingAndRaised_newerScannedLegIsNot() {
        DeliveredOrder o = deliveredOrder(2);
        String legA = ingestCrp(o, 41, 46);
        String legB = ingestCrp(o, 41, 46);
        jdbc.update("UPDATE shipments SET returned_at = now() - interval '5 days' " +
                    "WHERE tenant_id = ? AND tracking_number IN (?, ?)", tenantId, legA, legB);

        UUID s = sessionSvc.createSession(null, actorId);
        sessionSvc.scan(s, legB, locationId, actorId);                           // B's AWB
        sessionSvc.scan(s, "PC-" + o.pieces().get(0), locationId, actorId);
        sessionSvc.disposition(s, o.pieces().get(0), "restock", null, locationId, actorId);
        sessionSvc.close(s, actorId);

        assertThat(intakeCompletedAt(legB)).as("B: own AWB scanned → stamped").isNotNull();
        assertThat(intakeCompletedAt(legA)).as("A: never scanned → not stamped").isNull();

        assertThat(awaitingScanTrackings()).as("A is still waiting to be scanned").contains(legA);
        assertThat(awaitingScanTrackings()).doesNotContain(legB);
        assertThat(unscannedTrackings()).as("A still raises return_leg_unscanned past the window")
            .contains(legA);
        assertThat(unscannedTrackings()).doesNotContain(legB);
    }

    // ── m3: C — two unstamped legs, items scanned, no AWB → no guessing ──────────

    @Test
    void m3_twoUnstampedLegs_itemsScannedNoAwb_neitherStamped() {
        DeliveredOrder o = deliveredOrder(2);
        String legA = ingestCrp(o, 41, 46);
        String legB = ingestCrp(o, 41, 46);

        UUID s = sessionSvc.createSession(null, actorId);
        sessionSvc.scan(s, "PC-" + o.pieces().get(0), locationId, actorId);
        sessionSvc.disposition(s, o.pieces().get(0), "restock", null, locationId, actorId);
        sessionSvc.close(s, actorId);

        assertThat(intakeCompletedAt(legA)).as("A: which parcel was it? unknown → not stamped").isNull();
        assertThat(intakeCompletedAt(legB)).as("B: which parcel was it? unknown → not stamped").isNull();
        assertThat(awaitingScanTrackings()).contains(legA, legB);
    }

    // ── m4: the second CRP on an order with a finished first leg links ───────────

    @Test
    void m4_secondCrpOnOrderWithFinishedLeg_linksAsReturnLeg_notUnlinked() {
        DeliveredOrder o = deliveredOrder(2);
        String legA = ingestCrp(o, 41, 46);
        UUID s = sessionSvc.createSession(null, actorId);
        sessionSvc.scan(s, legA, locationId, actorId);
        sessionSvc.scan(s, "PC-" + o.pieces().get(0), locationId, actorId);
        sessionSvc.disposition(s, o.pieces().get(0), "restock", null, locationId, actorId);
        sessionSvc.close(s, actorId);
        assertThat(intakeCompletedAt(legA)).isNotNull();

        String legB = ingestCrp(o, 41);

        Map<String, Object> b = jdbc.queryForMap(
            "SELECT order_id, shipment_leg, internal_state::text AS internal_state FROM shipments " +
            "WHERE tracking_number = ? AND tenant_id = ?", legB, tenantId);
        assertThat(b.get("order_id")).isEqualTo(o.orderId());
        assertThat(b.get("shipment_leg")).isEqualTo("return");
        assertThat(b.get("internal_state")).isEqualTo("returning");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE tenant_id = ? AND tracking_number = ? " +
            "AND resolved = false", Integer.class, tenantId, legB))
            .as("second CRP must not land in unlinked_bosta_deliveries").isZero();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tenant_id = ? AND order_id = ? AND shipment_leg = 'return'",
            Integer.class, tenantId, o.orderId())).isEqualTo(2);
    }

    // ── m5: single-leg courier lag — exactly as today ────────────────────────────

    @Test
    void m5_singleLegInTransit_itemScannedNoAwb_stampedAsToday() {
        DeliveredOrder o = deliveredOrder(1);
        String leg = ingestCrp(o, 41);
        assertThat(state(leg)).isEqualTo("returning");

        UUID s = sessionSvc.createSession(null, actorId);
        sessionSvc.scan(s, "PC-" + o.pieces().get(0), locationId, actorId);
        sessionSvc.disposition(s, o.pieces().get(0), "restock", null, locationId, actorId);
        assertThat(state(leg))
            .as("single leg, piece dispositioned → resolveReturnLegIfComplete flips it, as today")
            .isEqualTo("returned");
        sessionSvc.close(s, actorId);

        Map<String, Object> m = jdbc.queryForMap(
            "SELECT return_intake_completed_at, return_intake_outcome, return_intake_by, return_intake_session_id " +
            "FROM shipments WHERE tracking_number = ? AND tenant_id = ?", leg, tenantId);
        assertThat(m.get("return_intake_completed_at")).as("stamped by the item scan alone, as today").isNotNull();
        assertThat(m.get("return_intake_outcome")).isEqualTo("scanned");
        assertThat(m.get("return_intake_by")).isEqualTo(actorId);
        assertThat(m.get("return_intake_session_id")).isEqualTo(s);
    }

    // ── m6: multi-leg courier lag — in-transit legs need their own AWB ───────────

    @Test
    void m6_twoLegsInTransit_itemScannedNoAwb_neitherStamped() {
        DeliveredOrder o = deliveredOrder(2);
        String legA = ingestCrp(o, 41);
        String legB = ingestCrp(o, 41);

        UUID s = sessionSvc.createSession(null, actorId);
        sessionSvc.scan(s, "PC-" + o.pieces().get(0), locationId, actorId);
        sessionSvc.disposition(s, o.pieces().get(0), "restock", null, locationId, actorId);
        sessionSvc.close(s, actorId);

        assertThat(intakeCompletedAt(legA)).isNull();
        assertThat(intakeCompletedAt(legB)).isNull();
        assertThat(state(legA)).as("not flipped").isEqualTo("returning");
        assertThat(state(legB)).as("not flipped").isEqualTo("returning");
    }

    @Test
    void m6b_inTransitLegBesideAFinishedStampedLeg_itemScannedNoAwb_notStamped() {
        DeliveredOrder o = deliveredOrder(2);
        String legA = ingestCrp(o, 41, 46);
        jdbc.update("UPDATE shipments SET return_intake_completed_at = now(), return_intake_outcome = 'scanned' " +
                    "WHERE tracking_number = ? AND tenant_id = ?", legA, tenantId);
        String legB = ingestCrp(o, 41);

        UUID s = sessionSvc.createSession(null, actorId);
        sessionSvc.scan(s, "PC-" + o.pieces().get(0), locationId, actorId);
        sessionSvc.disposition(s, o.pieces().get(0), "restock", null, locationId, actorId);
        sessionSvc.close(s, actorId);

        assertThat(intakeCompletedAt(legB))
            .as("B is the only unstamped leg, but in transit on a multi-leg order → needs its own AWB")
            .isNull();
        assertThat(state(legB)).as("not flipped").isEqualTo("returning");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    record DeliveredOrder(UUID orderId, String number, List<String> pieces) {}

    private static String bareTracking() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
    }

    /** Out-of-window delivered order: N delivered pieces with packed allocations + a delivered forward leg. */
    private DeliveredOrder deliveredOrder(int pieceCount) {
        String number = "#" + ThreadLocalRandom.current().nextInt(10_000, 99_999);
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now()) RETURNING id",
            UUID.class, tenantId, storeId, "gid://shopify/Order/" + UUID.randomUUID(), number);
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward')",
            tenantId, orderId, bareTracking());
        List<String> pieces = new ArrayList<>();
        for (int i = 0; i < pieceCount; i++) {
            String id = UlidGenerator.generate();
            jdbc.update(
                "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
                "        'delivered'::piece_status, ?, now() - (interval '1 day' * ?))",
                id, tenantId, variantId, "PC-" + id, id, orderId, OUT_OF_WINDOW_DAYS);
            UUID itemId = UUID.randomUUID();
            jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
                itemId, tenantId, orderId, variantId);
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
                tenantId, itemId, id);
            pieces.add(id);
        }
        return new DeliveredOrder(orderId, number, pieces);
    }

    /** A new CRP (type 25, businessReference = order number) through the real ingest pipeline, one event per state. */
    private String ingestCrp(DeliveredOrder o, int... states) {
        String tracking = bareTracking();
        int day = 20;
        for (int state : states) {
            ingestCrpAt(tracking, state, o.number(), "2026-09-" + (day++) + "T10:00:00.000Z");
        }
        return tracking;
    }

    private void ingestCrpAt(String tracking, int state, String businessRef, String updatedAt) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "crp-" + tracking);
        raw.put("trackingNumber", tracking);
        raw.putObject("type").put("code", 25).put("value", "Customer Return Pickup");
        raw.putObject("state").put("code", state);
        raw.put("businessReference", businessRef);
        raw.put("updatedAt", updatedAt);
        BostaDelivery d = new BostaDelivery(tracking, state, "CUSTOMER RETURN PICKUP", 0, businessRef, null, raw);
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking))).thenReturn(d);

        boolean enqueued = TenantContext.runAs(tenantId,
            () -> ingestionHelper.ingestDelivery(tenantId, RAW_API_KEY, tracking, "bosta_poll"));
        assertThat(enqueued).as("ingest must enqueue CRP state %d", state).isTrue();
        Long eventId = jdbc.queryForObject(
            "SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
            "AND (payload->>'state')::int = ? ORDER BY received_at DESC, id DESC LIMIT 1",
            Long.class, tenantId, tracking, state);
        webhookJob.process(eventId, tenantId);
        TenantContext.set(tenantId);
    }

    private String state(String tracking) {
        return jdbc.queryForObject(
            "SELECT internal_state::text FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            String.class, tracking, tenantId);
    }

    private Object intakeCompletedAt(String tracking) {
        return jdbc.queryForObject(
            "SELECT return_intake_completed_at FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            Object.class, tracking, tenantId);
    }

    @SuppressWarnings("unchecked")
    private List<String> awaitingScanTrackings() {
        return ((List<Map<String, Object>>) linkSvc.awaitingScan().get("items")).stream()
            .map(e -> String.valueOf(e.get("trackingNumber"))).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> unscannedTrackings() {
        return ((List<Map<String, Object>>) exceptionSvc
            .listExceptions("return_leg_unscanned", null, 0, 100).get("items")).stream()
            .map(e -> String.valueOf(e.get("tracking_number"))).toList();
    }
}
