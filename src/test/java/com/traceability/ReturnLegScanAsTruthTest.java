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
 * V98 — return legs: scan-as-truth.
 *
 * Return-leg (shipment_leg='return', Bosta type 25 CRP) courier states update the shipment
 * row only; only an intake scan moves a delivered piece to return_pending_inspection.
 *
 * T1/T2 are the revert-to-confirm pair: both are RED with BostaWebhookJob.applyMappedState()'s
 * return-leg skip removed (the pre-V98 behavior, where CRP state 46 moved EVERY delivered piece
 * on the order) and GREEN with it. CRP fixtures go through the real ingest pipeline
 * (BostaIngestionHelper → BostaWebhookJob.process) with type.code=25, provider states 41 → 46
 * and bare numeric tracking numbers, as in production.
 *
 * T3 intake completion on close; T4 listCrpReturns inspection_state; T5 the
 * return_leg_unscanned detector; T7 return_kind precedence.
 * (T6, the V98 backfill, lives in ReturnIntakeBackfillTest — it needs a pre-V98 schema.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnLegScanAsTruthTest {

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

    private static final String RAW_API_KEY = "scan-truth-api-key";
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

    private JdbcTemplate        appUserJdbc;
    private TransactionTemplate appUserTx;

    UUID tenantId, tenantBId, storeId, variantId, locationId, actorId;

    @BeforeAll
    void setupFixture() {
        DriverManagerDataSource rawDs =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawDs);
        appUserJdbc = new JdbcTemplate(appUserDs);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));

        tenantId   = UUID.randomUUID();
        tenantBId  = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        variantId  = UUID.randomUUID();
        locationId = UUID.randomUUID();
        actorId    = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ScanTruthTenant')", tenantId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ScanTruthTenantB')", tenantBId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Worker', 'w@scantruth.test', 'h', 'owner')", actorId, tenantId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Returns Bay')",
                    locationId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'scantruth.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-ST', 'Linen Shirt', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-ST', 'Sand M', 'LIN-SAND-M')", variantId, tenantId, productId);
        jdbc.update("INSERT INTO courier_accounts " +
                    "(id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'scan-truth-hash', 'active')",
                    tenantId, encryptionService.encrypt(RAW_API_KEY));
    }

    @BeforeEach void setCtx() { TenantContext.set(tenantId); }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (UUID t : List.of(tenantId, tenantBId)) {
            jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_session_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_session_shipments WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM exchanges WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_sessions WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shopify_inventory_adjustments WHERE tenant_id = ?", t);
            jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shipment_status_history WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM webhook_events WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t);
        }
        jdbc.update("DELETE FROM stores WHERE tenant_id = ?", tenantBId);
    }

    // ── T1 ────────────────────────────────────────────────────────────────────

    @Test
    void t1_crpIngestedThroughState46_movesNoPieces() {
        DeliveredOrder o = deliveredOrder(3, 0);
        String crp = ingestCrpThrough46(o);

        assertThat(jdbc.queryForObject(
                "SELECT internal_state::text FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
                String.class, crp, tenantId))
            .as("return leg itself still follows Bosta to 'returned'").isEqualTo("returned");
        assertThat(jdbc.queryForObject(
                "SELECT shipment_leg FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
                String.class, crp, tenantId)).isEqualTo("return");

        for (String p : o.pieces()) {
            assertThat(pieceStatus(p))
                .as("CRP state 46 must not move piece %s — only an intake scan does", p)
                .isEqualTo("delivered");
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM piece_events WHERE tenant_id = ? AND order_id = ? " +
                "AND event_type = 'courier_update'", Integer.class, tenantId, o.orderId()))
            .as("no courier_update piece events for a return leg").isZero();
    }

    // ── T2 ────────────────────────────────────────────────────────────────────

    @Test
    void t2_outOfWindow_crpAt46_scanOnePiece_onlyThatPieceMoves() {
        DeliveredOrder o = deliveredOrder(3, OUT_OF_WINDOW_DAYS);
        ingestCrpThrough46(o);

        UUID session = sessionSvc.createSession(null, actorId);
        Map<String, Object> scan = sessionSvc.scan(session, "PC-" + o.pieces().get(0), locationId, actorId);

        assertThat(scan.get("unexpected")).isEqualTo(false);
        assertThat(pieceStatus(o.pieces().get(0)))
            .as("scanned piece is accepted via the return leg awaiting intake")
            .isEqualTo("return_pending_inspection");
        assertThat(pieceStatus(o.pieces().get(1))).isEqualTo("delivered");
        assertThat(pieceStatus(o.pieces().get(2))).isEqualTo("delivered");
        assertThat(returnKind(o.pieces().get(0))).isEqualTo("crp_return");
    }

    // ── T3 ────────────────────────────────────────────────────────────────────

    @Test
    void t3_closeStampsIntake_laterOutOfWindowScanRejected() {
        DeliveredOrder o = deliveredOrder(3, OUT_OF_WINDOW_DAYS);
        String crp = ingestCrpThrough46(o);

        UUID s1 = sessionSvc.createSession(null, actorId);
        sessionSvc.scan(s1, "PC-" + o.pieces().get(0), locationId, actorId);
        assertThat(intakeCompletedAt(crp)).as("not stamped while the session is open").isNull();
        sessionSvc.disposition(s1, o.pieces().get(0), "restock", null, locationId, actorId);
        sessionSvc.close(s1, actorId);

        assertThat(intakeCompletedAt(crp)).as("close() stamps return_intake_completed_at").isNotNull();
        assertThat(linkSvc.hasReturnLegAwaitingIntake(o.orderId(), tenantId)).isFalse();

        UUID s2 = sessionSvc.createSession(null, actorId);
        Map<String, Object> scan = sessionSvc.scan(s2, "PC-" + o.pieces().get(1), locationId, actorId);
        assertThat(scan.get("unexpected"))
            .as("intake complete → later out-of-window scan is illegal-state, as before V98")
            .isEqualTo(true);
        assertThat(pieceStatus(o.pieces().get(1))).isEqualTo("delivered");
    }

    @Test
    void t3b_abandonDoesNotStampIntake() {
        DeliveredOrder o = deliveredOrder(1, OUT_OF_WINDOW_DAYS);
        String crp = ingestCrpThrough46(o);

        UUID s1 = sessionSvc.createSession(null, actorId);
        sessionSvc.scan(s1, "PC-" + o.pieces().get(0), locationId, actorId);
        sessionSvc.abandon(s1, actorId);

        assertThat(intakeCompletedAt(crp)).as("abandon never completes intake").isNull();
    }

    // ── T4 ────────────────────────────────────────────────────────────────────

    @Test
    void t4_listCrpReturns_needsInspectionUntilScannedAndClosed_thenResolved() {
        DeliveredOrder o = deliveredOrder(3, OUT_OF_WINDOW_DAYS);
        String crp = ingestCrpThrough46(o);

        assertThat(inspectionState(crp))
            .as("returned but unscanned — zero pending pieces must NOT read as resolved")
            .isEqualTo("needs_inspection");

        UUID s = sessionSvc.createSession(null, actorId);
        sessionSvc.scan(s, "PC-" + o.pieces().get(0), locationId, actorId);
        assertThat(inspectionState(crp)).isEqualTo("needs_inspection");
        sessionSvc.disposition(s, o.pieces().get(0), "restock", null, locationId, actorId);
        assertThat(inspectionState(crp)).as("dispositioned but session still open").isEqualTo("needs_inspection");
        sessionSvc.close(s, actorId);

        assertThat(inspectionState(crp)).isEqualTo("resolved");
    }

    // ── T5 ────────────────────────────────────────────────────────────────────

    @Test
    void t5_detector_firesOnlyForReturnedUnscannedLegPastWindow() {
        UUID pastWindow   = seededReturnedLeg(tenantId, 5, false);
        UUID insideWindow = seededReturnedLeg(tenantId, 1, false);
        UUID intakeDone   = seededReturnedLeg(tenantId, 5, true);

        List<String> open = unscannedShipmentIds();
        assertThat(open).as("returned, unscanned, 5 days > 3-day window → fires")
            .contains(pastWindow.toString());
        assertThat(open).as("inside the window → silent").doesNotContain(insideWindow.toString());
        assertThat(open).as("intake completed → silent").doesNotContain(intakeDone.toString());

        Map<String, Object> item = listUnscanned().stream()
            .filter(e -> pastWindow.toString().equals(String.valueOf(e.get("shipment_id"))))
            .findFirst().orElseThrow();
        assertThat(item.get("severity")).isEqualTo("HIGH");
        assertThat((String) item.get("descriptionEn")).contains("never scanned");
        assertThat((String) item.get("descriptionAr")).isNotBlank();

        exceptionSvc.resolve("return_leg_unscanned", "return_leg_unscanned:shipment:" + pastWindow, actorId, "checked");
        assertThat(unscannedShipmentIds()).as("resolved → silent").doesNotContain(pastWindow.toString());
    }

    @Test
    void t5b_detector_silentWhenPieceScannedAndSessionStillOpen() {
        DeliveredOrder o = deliveredOrder(3, OUT_OF_WINDOW_DAYS);
        String crp = ingestCrpThrough46(o);
        jdbc.update("UPDATE shipments SET returned_at = now() - interval '5 days' " +
                    "WHERE tracking_number = ? AND tenant_id = ?", crp, tenantId);
        UUID legId = jdbc.queryForObject(
            "SELECT id FROM shipments WHERE tracking_number = ? AND tenant_id = ?", UUID.class, crp, tenantId);

        assertThat(unscannedShipmentIds()).as("positive control: unscanned, past window → fires")
            .contains(legId.toString());

        UUID s = sessionSvc.createSession(null, actorId);
        sessionSvc.scan(s, "PC-" + o.pieces().get(0), locationId, actorId);

        assertThat(intakeCompletedAt(crp)).as("session still open").isNull();
        assertThat(unscannedShipmentIds())
            .as("one piece scanned (scan evidence) → the parcel WAS scanned, no exception")
            .doesNotContain(legId.toString());
    }

    @Test
    void t5c_detector_crossTenantIsolation_withSameTenantPositiveControl() {
        UUID storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'scantruth-b.myshopify.com', 'disconnected')", storeB, tenantBId);
        UUID legB = seededReturnedLeg(tenantBId, storeB, 5, false);
        UUID legA = seededReturnedLeg(tenantId, 5, false);

        List<String> asA = unscannedShipmentIds();
        assertThat(asA).as("same-tenant positive control").contains(legA.toString());
        assertThat(asA).as("tenant B's leg never appears for tenant A").doesNotContain(legB.toString());

        // RLS: the detector's source rows are invisible to tenant A under app_user.
        Integer visibleToA = TenantContext.runAs(tenantId, () -> appUserTx.execute(st ->
            appUserJdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE id = ?", Integer.class, legB)));
        Integer visibleToB = TenantContext.runAs(tenantBId, () -> appUserTx.execute(st ->
            appUserJdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE id = ?", Integer.class, legB)));
        assertThat(visibleToA).isZero();
        assertThat(visibleToB).as("positive control under app_user").isEqualTo(1);
    }

    // ── T7: return_kind precedence ──────────────────────────────────────────────

    @Test
    void t7_returnKindPrecedence_exchangeMatch_gt_crpReturn_gt_customerAfterDelivery() {
        UUID s = sessionSvc.createSession(null, actorId);

        // Exchange + CRP awaiting intake, out of window → exchange_match wins.
        DeliveredOrder both = deliveredOrder(1, OUT_OF_WINDOW_DAYS);
        ingestCrpThrough46(both);
        createMatchedExchange(both.orderId());
        sessionSvc.scan(s, "PC-" + both.pieces().get(0), locationId, actorId);
        assertThat(returnKind(both.pieces().get(0))).isEqualTo("exchange_match");

        // Exchange only, IN window → exchange_match (labels are window-independent).
        DeliveredOrder exInWindow = deliveredOrder(1, 0);
        createMatchedExchange(exInWindow.orderId());
        sessionSvc.scan(s, "PC-" + exInWindow.pieces().get(0), locationId, actorId);
        assertThat(returnKind(exInWindow.pieces().get(0))).isEqualTo("exchange_match");

        // CRP awaiting intake, IN window → crp_return beats customer_after_delivery.
        DeliveredOrder crpInWindow = deliveredOrder(1, 0);
        ingestCrpThrough46(crpInWindow);
        sessionSvc.scan(s, "PC-" + crpInWindow.pieces().get(0), locationId, actorId);
        assertThat(returnKind(crpInWindow.pieces().get(0))).isEqualTo("crp_return");

        // CRP awaiting intake, out of window → crp_return, accepted via hasReturnLegAwaitingIntake
        // (the leg is 'returned', so hasActiveReturnLeg() alone would have rejected it).
        DeliveredOrder crpOut = deliveredOrder(1, OUT_OF_WINDOW_DAYS);
        ingestCrpThrough46(crpOut);
        assertThat(linkSvc.hasActiveReturnLeg(crpOut.orderId(), tenantId)).isFalse();
        assertThat(linkSvc.hasReturnLegAwaitingIntake(crpOut.orderId(), tenantId)).isTrue();
        sessionSvc.scan(s, "PC-" + crpOut.pieces().get(0), locationId, actorId);
        assertThat(pieceStatus(crpOut.pieces().get(0))).isEqualTo("return_pending_inspection");
        assertThat(returnKind(crpOut.pieces().get(0))).isEqualTo("crp_return");

        // Neither, in window → customer_after_delivery.
        DeliveredOrder plain = deliveredOrder(1, 0);
        sessionSvc.scan(s, "PC-" + plain.pieces().get(0), locationId, actorId);
        assertThat(returnKind(plain.pieces().get(0))).isEqualTo("customer_after_delivery");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    record DeliveredOrder(UUID orderId, String number, List<String> pieces) {}

    private static String bareTracking() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
    }

    /** Delivered order: N delivered pieces with packed allocations + a delivered forward leg. */
    private DeliveredOrder deliveredOrder(int pieceCount, int deliveredDaysAgo) {
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
                id, tenantId, variantId, "PC-" + id, id, orderId, deliveredDaysAgo);
            UUID itemId = UUID.randomUUID();
            jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
                itemId, tenantId, orderId, variantId);
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
                tenantId, itemId, id);
            pieces.add(id);
        }
        return new DeliveredOrder(orderId, number, pieces);
    }

    /** Runs a CRP (type 25, businessReference = order number) through the real ingest pipeline: 41 then 46. */
    private String ingestCrpThrough46(DeliveredOrder o) {
        String tracking = bareTracking();
        ingestCrpAt(tracking, 41, o.number(), "2026-09-20T10:00:00.000Z");
        ingestCrpAt(tracking, 46, o.number(), "2026-09-21T10:00:00.000Z");
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

    private UUID seededReturnedLeg(UUID tenant, int returnedDaysAgo, boolean intakeDone) {
        return seededReturnedLeg(tenant, storeId, returnedDaysAgo, intakeDone);
    }

    private UUID seededReturnedLeg(UUID tenant, UUID store, int returnedDaysAgo, boolean intakeDone) {
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now()) RETURNING id",
            UUID.class, tenant, store, "gid://shopify/Order/" + UUID.randomUUID(),
            "#" + ThreadLocalRandom.current().nextInt(10_000, 99_999));
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, " +
            "    created_at, returned_at, return_intake_completed_at) " +
            "VALUES (?, ?, 'bosta', ?, 'returned'::shipment_internal_state, 'return', " +
            "    now() - interval '10 days', now() - (interval '1 day' * ?), " +
            "    CASE WHEN ? THEN now() ELSE NULL END) RETURNING id",
            UUID.class, tenant, orderId, bareTracking(), returnedDaysAgo, intakeDone);
    }

    private void createMatchedExchange(UUID orderId) {
        jdbc.update(
            "INSERT INTO exchanges (tenant_id, tracking_number, status, matched_order_id, match_method, matched_at, raw) " +
            "VALUES (?, ?, 'matched', ?, 'phone', now(), '{}'::jsonb)",
            tenantId, bareTracking(), orderId);
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String returnKind(String pieceId) {
        return jdbc.queryForObject(
            "SELECT metadata->>'return_kind' FROM piece_events " +
            "WHERE piece_id = ? AND event_type = 'return_received' ORDER BY occurred_at DESC, id DESC LIMIT 1",
            String.class, pieceId);
    }

    private Object intakeCompletedAt(String tracking) {
        return jdbc.queryForObject(
            "SELECT return_intake_completed_at FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            Object.class, tracking, tenantId);
    }

    private String inspectionState(String tracking) {
        return (String) linkSvc.listCrpReturns(0, 100).stream()
            .filter(r -> tracking.equals(r.get("tracking_number")))
            .findFirst().orElseThrow().get("inspection_state");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listUnscanned() {
        return (List<Map<String, Object>>) exceptionSvc
            .listExceptions("return_leg_unscanned", null, 0, 100).get("items");
    }

    private List<String> unscannedShipmentIds() {
        return listUnscanned().stream().map(e -> String.valueOf(e.get("shipment_id"))).toList();
    }
}
