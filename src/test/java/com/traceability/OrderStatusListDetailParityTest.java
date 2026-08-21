package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.fulfillment.OrderController;
import com.traceability.fulfillment.OrderController.OrderDetail;
import com.traceability.fulfillment.OrderController.OrderPage;
import com.traceability.fulfillment.OrderController.OrderSummary;
import com.traceability.fulfillment.OrderController.ShipmentDetail;
import com.traceability.fulfillment.OrderController.TimelineItem;
import com.traceability.fulfillment.OrderStatusDeriver.DerivedOrderStatus;
import com.traceability.fulfillment.OrderStatusDeriver.Tone;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-7/FR-11 A2 — proves OrderController.list() and OrderController.detail() can never
 * derive a different DerivedOrderStatus for the same order (the exact divergence A1/A2
 * exists to kill), and that the detail path's RLS still holds under app_user.
 *
 * OrderController is constructed directly (new, not @Autowired) — matches the pattern in
 * PickQueueRecencyTest of testing business/query logic without going through the HTTP +
 * @PreAuthorize layer, which needs a Spring Security auth context this test doesn't set up.
 *
 * Fixtures are production-shaped: bare numeric tracking numbers, a regressed
 * created-after-with_courier history, a delivered+failed shipment, and a cancelled order
 * with a still-live shipment — not clean-slate rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderStatusListDetailParityTest {

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

    @Autowired JdbcTemplate              jdbc;
    @Autowired ObjectMapper              mapper;
    @Autowired PlatformTransactionManager txm;

    // app_user infrastructure for the RLS test — mirrors DeliveryStatusTest's appUserJdbc/Tx.
    private JdbcTemplate        appUserJdbc;
    private PlatformTransactionManager appUserTxm;

    private OrderController controller;

    UUID tenantId, otherTenantId, storeId, productId, variantId;

    @BeforeAll
    void setupAppUser() {
        DriverManagerDataSource rawDs =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(rawDs);
        appUserJdbc = new JdbcTemplate(appDs);
        appUserTxm  = new DataSourceTransactionManager(appDs);
    }

    @BeforeAll
    void setupFixture() {
        controller = new OrderController(jdbc, mapper, txm);

        tenantId      = UUID.randomUUID();
        otherTenantId = UUID.randomUUID();
        storeId       = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ParityTenant')", tenantId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ParityOtherTenant')", otherTenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'parity-test.myshopify.com', 'connected')",
                    storeId, tenantId);

        // Product/variant fixture for the timeline's piece_events (pick/pack) rows.
        productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-PARITY', 'Parity Widget', 'active')",
                    productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-PARITY', 'Default', 'PARITY-001')",
                    variantId, tenantId, productId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clean() { TenantContext.clear(); }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertOrder(String extId, String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantId, storeId, extId, "#" + extId, status);
    }

    private UUID insertForwardShipment(UUID orderId, String tracking, String state,
                                        int numberOfAttempts, int failedDeliveryAttempts) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "    internal_state, shipment_leg, number_of_attempts, failed_delivery_attempts) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward', ?, ?) RETURNING id",
            UUID.class, tenantId, orderId, tracking, state, numberOfAttempts, failedDeliveryAttempts);
    }

    private UUID insertReturnShipment(UUID orderId, String tracking, String state) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "    internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'return') RETURNING id",
            UUID.class, tenantId, orderId, tracking, state);
    }

    private void insertHistory(UUID shipmentId, String state) {
        jdbc.update(
            "INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) " +
            "VALUES (?, ?, ?::shipment_internal_state)", tenantId, shipmentId, state);
    }

    // ── timeline (pass-b) helpers — explicit timestamps for deterministic ordering
    // assertions; insertOrder/insertForwardShipment above default to now(), too coarse
    // for asserting chronological order across several rows inserted in one test. ──────

    private UUID insertOrderAt(String extId, String status, Instant placedAt) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'cod', ?) RETURNING id",
            UUID.class, tenantId, storeId, extId, "#" + extId, status, Timestamp.from(placedAt));
    }

    private UUID insertForwardShipmentAt(UUID orderId, String tracking, String state, Instant createdAt) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "    internal_state, shipment_leg, created_at) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward', ?) RETURNING id",
            UUID.class, tenantId, orderId, tracking, state, Timestamp.from(createdAt));
    }

    private void insertHistoryAt(UUID shipmentId, String state, Instant occurredAt,
                                  Integer exceptionCode, String exceptionReason) {
        jdbc.update(
            "INSERT INTO shipment_status_history " +
            "(tenant_id, shipment_id, internal_state, occurred_at, exception_code, exception_reason) " +
            "VALUES (?, ?, ?::shipment_internal_state, ?, ?, ?)",
            tenantId, shipmentId, state, Timestamp.from(occurredAt), exceptionCode, exceptionReason);
    }

    private UUID insertReturnShipmentAt(UUID orderId, String tracking, String state, Instant createdAt) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "    internal_state, shipment_leg, created_at) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'return', ?) RETURNING id",
            UUID.class, tenantId, orderId, tracking, state, Timestamp.from(createdAt));
    }

    // Direct piece_events insert (not through InventoryLedger — this suite tests the
    // TIMELINE READ, not the ledger). actorUserId may be null (system/automated, same as
    // production — no users fixture exists in this class).
    private void insertPieceEvent(UUID orderId, String eventType, String fromStatus,
                                   String toStatus, Instant occurredAt) {
        String pieceId = com.traceability.inventory.UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status)",
            pieceId, tenantId, variantId, "PC-" + pieceId, pieceId, toStatus);
        jdbc.update(
            "INSERT INTO piece_events " +
            "(tenant_id, piece_id, event_type, actor_user_id, order_id, occurred_at, from_status, to_status) " +
            "VALUES (?, ?, ?, NULL, ?, ?, ?::piece_status, ?::piece_status)",
            tenantId, pieceId, eventType, orderId, Timestamp.from(occurredAt), fromStatus, toStatus);
    }

    // ── list vs detail parity ────────────────────────────────────────────────

    @Test
    void parity_regressedCreatedAfterWithCourier_listAndDetailAgree() {
        UUID orderId = insertOrder("PARITY-REGRESSED", "awaiting_pickup");
        UUID shipmentId = insertForwardShipment(orderId, "9810234561", "created", 3, 3);
        insertHistory(shipmentId, "created");
        insertHistory(shipmentId, "with_courier");
        insertHistory(shipmentId, "created"); // regressed

        assertParity(orderId);
    }

    @Test
    void parity_regressedHistory_zeroFailedAttempts_maxRankDrivesPrimaryKey_listAndDetailAgree() {
        // Unlike parity_regressedCreatedAfterWithCourier_listAndDetailAgree above (which
        // has failed_delivery_attempts=3 and short-circuits derive() BEFORE
        // maxProgressRank is ever consulted for primaryKey), this fixture has
        // failedDeliveryAttempts=0 so primaryKey genuinely flows through the
        // furthest-progress branch — the one place list()'s SQL CASE and detail()'s Java
        // reducer could silently diverge without any other branch masking it.
        UUID orderId = insertOrder("PARITY-REGRESSED-NOFAIL", "awaiting_pickup");
        UUID shipmentId = insertForwardShipment(orderId, "9810234565", "created", 0, 0);
        insertHistory(shipmentId, "created");
        insertHistory(shipmentId, "with_courier");
        insertHistory(shipmentId, "created"); // regressed, no failed attempts to mask it

        assertParity(orderId);

        // Not just agreement — agreement on the CORRECT answer. Two paths silently computing
        // the same wrong maxRank would still pass assertParity alone.
        OrderDetail detail = controller.detail(orderId);
        assertThat(detail.derivedStatus().primaryKey())
            .as("maxRank=2 (from history's with_courier row) must win over the regressed 'created' latest state")
            .isEqualTo("status.in_transit");
    }

    @Test
    void parity_deliveredPlusFailedAttempt_listAndDetailAgree() {
        UUID orderId = insertOrder("PARITY-DELIVERED-FAIL", "new");
        UUID shipmentId = insertForwardShipment(orderId, "9810234562", "delivered", 2, 1);
        insertHistory(shipmentId, "with_courier");
        insertHistory(shipmentId, "delivered");

        assertParity(orderId);
    }

    @Test
    void parity_cancelledOrderWithLiveShipment_listAndDetailAgree() {
        UUID orderId = insertOrder("PARITY-CANCELLED-LIVE", "cancelled");
        UUID shipmentId = insertForwardShipment(orderId, "9810234563", "with_courier", 1, 0);
        insertHistory(shipmentId, "created");
        insertHistory(shipmentId, "with_courier");

        assertParity(orderId);
    }

    @Test
    void parity_noShipmentLinked_listAndDetailAgree() {
        UUID orderId = insertOrder("PARITY-NO-SHIPMENT", "new");
        assertParity(orderId);
    }

    // ── Slice 1 (status-split fix): packedConfirmed, through the real DB-wired path ─────

    @Test
    void slice1_webhookEarlyMatch_shipmentLinkedButOrderNeverPacked_packedConfirmedFalse_listAndDetailAgree() {
        // Mode-B webhook auto-match: shipment exists at label_created (rank 1) while
        // order.status is still pre-pack — ShipmentLinkService.tryMatchDelivery()'s guarded
        // "UPDATE ... WHERE status='packed'" is a documented no-op here since packing hasn't
        // happened. packedConfirmed must be false — this is the exact phantom-progress shape.
        UUID orderId = insertOrder("SLICE1-EARLY-MATCH", "picking");
        insertForwardShipment(orderId, "9810234591", "created", 0, 0);

        OrderDetail detail = controller.detail(orderId);
        assertThat(detail.derivedStatus().primaryKey()).isEqualTo("status.label_created");
        assertThat(detail.derivedStatus().packedConfirmed())
            .as("a shipment record existing is not proof packing happened")
            .isFalse();

        assertParity(orderId);
    }

    @Test
    void slice1_packerFirst_orderStatusPacked_packedConfirmedTrue_evenWithoutShipmentYet() {
        // The normal, gated linkByAwbScan() flow: order.status reaches 'packed' before any
        // shipment exists. packedConfirmed must be true from order.status alone.
        UUID orderId = insertOrder("SLICE1-PACKED-NO-SHIP", "packed");

        OrderDetail detail = controller.detail(orderId);
        assertThat(detail.derivedStatus().packedConfirmed()).isTrue();

        assertParity(orderId);
    }

    @Test
    void slice1_courierHolding_orderStatusStuckPrePack_packedConfirmedTrueViaShipmentRank() {
        // Physical proof branch: the courier holding the parcel (rank>=2) proves packing
        // happened even if Traced's own order.status never got updated (e.g. it stayed at
        // 'picking' because the guarded packed->awaiting_pickup flip only fires once, at
        // shipment-creation time, not on every later shipment progression).
        UUID orderId = insertOrder("SLICE1-COURIER-HOLDING", "picking");
        insertForwardShipment(orderId, "9810234592", "with_courier", 0, 0);

        OrderDetail detail = controller.detail(orderId);
        assertThat(detail.derivedStatus().primaryKey()).isEqualTo("status.in_transit");
        assertThat(detail.derivedStatus().packedConfirmed()).isTrue();

        assertParity(orderId);
    }

    @Test
    void slice1_cancelledShipment_notProofOfPacking_packedConfirmedFalse() {
        // A Bosta-side cancel is explicitly excluded from the terminal-implies-packed branch —
        // a shipment can be cancelled before ever being physically handed over.
        UUID orderId = insertOrder("SLICE1-CANCELLED-SHIP", "picking");
        insertForwardShipment(orderId, "9810234593", "cancelled", 0, 0);

        OrderDetail detail = controller.detail(orderId);
        assertThat(detail.derivedStatus().packedConfirmed())
            .as("a Bosta-side shipment cancellation is not proof packing happened")
            .isFalse();

        assertParity(orderId);
    }

    // ── A3: cancelled-order conflict flag, through the real DB-wired path ──────

    @Test
    void a3_cancelledOrder_deliveredShipment_conflictFlag_noHealthChips() {
        UUID orderId = insertOrder("A3-CANCELLED-DELIVERED", "cancelled");
        insertForwardShipment(orderId, "9810234571", "delivered", 2, 1);

        DerivedOrderStatus detailDerived = controller.detail(orderId).derivedStatus();
        assertThat(detailDerived.primaryKey()).isEqualTo("status.cancelled");
        assertThat(detailDerived.conflictKey()).isEqualTo("status.conflict.cancelled_but_delivered");
        assertThat(detailDerived.healthChips()).isEmpty();

        assertParity(orderId);
    }

    @Test
    void a3_cancelledOrder_liveShipment_conflictFlag_noHealthChips() {
        UUID orderId = insertOrder("A3-CANCELLED-LIVE", "cancelled");
        // isDelayed=true would normally raise chip.delayed on a non-cancelled order — A3
        // must suppress it here since the order itself is cancelled.
        UUID shipmentId = insertForwardShipment(orderId, "9810234572", "created", 0, 0);
        jdbc.update("UPDATE shipments SET is_delayed = true WHERE id = ?", shipmentId);

        DerivedOrderStatus detailDerived = controller.detail(orderId).derivedStatus();
        assertThat(detailDerived.primaryKey()).isEqualTo("status.cancelled");
        assertThat(detailDerived.conflictKey()).isEqualTo("status.conflict.live_shipment");
        assertThat(detailDerived.healthChips()).isEmpty();

        assertParity(orderId);
    }

    @Test
    void a3_cancelledOrder_returnedShipment_noConflict_cleanCancel() {
        UUID orderId = insertOrder("A3-CANCELLED-RETURNED", "cancelled");
        insertForwardShipment(orderId, "9810234573", "returned", 0, 0);

        DerivedOrderStatus detailDerived = controller.detail(orderId).derivedStatus();
        assertThat(detailDerived.primaryKey()).isEqualTo("status.cancelled");
        assertThat(detailDerived.conflictKey()).as("returned shipment is a clean cancel").isNull();
        assertThat(detailDerived.healthChips()).isEmpty();

        assertParity(orderId);
    }

    // ── A3.1: return-leg leg-scoped badge, through the real DB-wired path ──────

    @Test
    void a31_returnLeg_returning_getsCorrectLegBadge_headerUnaffected() {
        UUID orderId = insertOrder("A31-RETURN-RETURNING", "returning");
        insertForwardShipment(orderId, "9810234581", "returned", 0, 0);
        insertReturnShipment(orderId, "9810234582", "returning");

        OrderDetail detail = controller.detail(orderId);
        ShipmentDetail returnLeg = detail.shipments().stream()
            .filter(s -> "return".equals(s.shipmentLeg())).findFirst().orElseThrow();
        assertThat(returnLeg.legStatus().primaryKey()).isEqualTo("status.returning");
        assertThat(returnLeg.legStatus().tone()).isEqualTo(Tone.WARN);

        // Header is driven by the FORWARD leg only — the return leg's state must not
        // leak into the order-level derivation.
        assertThat(detail.derivedStatus().primaryKey()).isEqualTo("status.returned");
        assertParity(orderId);
    }

    @Test
    void a31_returnLeg_returned_getsCorrectLegBadge() {
        UUID orderId = insertOrder("A31-RETURN-RETURNED", "returned");
        insertForwardShipment(orderId, "9810234583", "returned", 0, 0);
        insertReturnShipment(orderId, "9810234584", "returned");

        ShipmentDetail returnLeg = controller.detail(orderId).shipments().stream()
            .filter(s -> "return".equals(s.shipmentLeg())).findFirst().orElseThrow();
        assertThat(returnLeg.legStatus().primaryKey()).isEqualTo("status.returned");
        assertThat(returnLeg.legStatus().tone()).isEqualTo(Tone.WARN);
    }

    @Test
    void a31_returnLeg_exception_getsCorrectLegBadge() {
        UUID orderId = insertOrder("A31-RETURN-EXCEPTION", "returning");
        insertForwardShipment(orderId, "9810234585", "returning", 0, 0);
        insertReturnShipment(orderId, "9810234586", "exception");

        ShipmentDetail returnLeg = controller.detail(orderId).shipments().stream()
            .filter(s -> "return".equals(s.shipmentLeg())).findFirst().orElseThrow();
        assertThat(returnLeg.legStatus().primaryKey()).isEqualTo("status.needs_attention");
        assertThat(returnLeg.legStatus().tone()).isEqualTo(Tone.WARN);
    }

    @Test
    void a31_forwardLeg_carriesLegStatus_butOrderHeaderIsWhatUltimatelyRenders() {
        // The backend computes legStatus uniformly for every shipment row (cheap, pure) —
        // it's the FRONTEND that gates rendering to the return leg only. This test just
        // confirms the forward leg's own legStatus is internally consistent with its state,
        // not that the API omits it (it doesn't need to — no UI ever renders it for forward).
        UUID orderId = insertOrder("A31-FORWARD-CONSISTENCY", "awaiting_pickup");
        insertForwardShipment(orderId, "9810234587", "with_courier", 0, 0);

        ShipmentDetail forwardLeg = controller.detail(orderId).shipments().get(0);
        assertThat(forwardLeg.shipmentLeg()).isEqualTo("forward");
        assertThat(forwardLeg.legStatus().primaryKey()).isEqualTo("status.in_transit");
    }

    private void assertParity(UUID orderId) {
        OrderSummary fromList = controller.list(null, null, null, null, 0, 100).items().stream()
            .filter(o -> o.id().equals(orderId.toString()))
            .findFirst().orElseThrow();
        OrderDetail fromDetail = controller.detail(orderId);

        DerivedOrderStatus listDerived   = fromList.derivedStatus();
        DerivedOrderStatus detailDerived = fromDetail.derivedStatus();

        assertThat(listDerived).as("list vs detail derived status for order " + orderId)
            .isEqualTo(detailDerived);
    }

    // ── RLS on the detail path ───────────────────────────────────────────────

    @Test
    void rls_detail_sameTenantPositiveControl_crossTenantNegativeControl() {
        UUID orderId = insertOrder("PARITY-RLS", "new");
        insertForwardShipment(orderId, "9810234564", "created", 0, 0);

        OrderController appUserController = new OrderController(appUserJdbc, mapper, appUserTxm);

        // Positive control: app_user WITH the correct tenant GUC can fetch its own order.
        OrderDetail found = TenantContext.runAs(tenantId, () -> appUserController.detail(orderId));
        assertThat(found.id()).isEqualTo(orderId.toString());
        assertThat(found.derivedStatus()).isNotNull();

        // Negative control: app_user under a DIFFERENT tenant's GUC cannot see it — RLS
        // makes the row invisible, so detail() throws 404, not a security-bypass 200.
        assertThatThrownBy(() -> TenantContext.runAs(otherTenantId, () -> appUserController.detail(orderId)))
            .as("app_user under a different tenant's GUC must not see this order")
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404");
    }

    // ── deliveryState filter on list() — guards the silent-zero-row class ───────
    // (review, before pilot deploy) A filter returning empty because of a scoping bug
    // looks identical to "no matching orders" from the outside — the positive control
    // is what actually proves the predicate matches, not just that it doesn't leak
    // across tenants. Mirrors rls_detail_sameTenantPositiveControl_crossTenantNegativeControl
    // above, but exercises the NEW `s.internal_state = ?::shipment_internal_state`
    // predicate specifically (list()'s pre-existing `status`/`tracking` filters already
    // had no equivalent test before this pass either — scoped to the predicate this
    // review is about, not a general backfill).

    @Test
    void rls_list_deliveryStateFilter_sameTenantPositiveControl_forEveryTabState() {
        OrderController appUserController = new OrderController(appUserJdbc, mapper, appUserTxm);

        for (String state : List.of("with_courier", "delivered", "returned")) {
            UUID orderId = insertOrder("DSF-POS-" + state, "with_courier");
            insertForwardShipment(orderId, "DSF-TRK-" + state, state, 1, 0);

            OrderPage page = TenantContext.runAs(tenantId,
                () -> appUserController.list(null, state, null, null, 0, 100));

            assertThat(page.items())
                .as("deliveryState=%s must return the seeded same-tenant order, not a silent empty page", state)
                .anyMatch(o -> o.id().equals(orderId.toString()));
        }
    }

    @Test
    void rls_list_deliveryStateFilter_crossTenantNegativeControl() {
        UUID orderId = insertOrder("DSF-NEG", "with_courier");
        insertForwardShipment(orderId, "DSF-TRK-NEG", "with_courier", 1, 0);

        OrderController appUserController = new OrderController(appUserJdbc, mapper, appUserTxm);

        // Same filter, different tenant's GUC — the seeded row must not leak across
        // tenants through the new predicate (an empty page here, not a 404 — list()
        // has no single-entity 404 concept, unlike detail()).
        OrderPage page = TenantContext.runAs(otherTenantId,
            () -> appUserController.list(null, "with_courier", null, null, 0, 100));

        assertThat(page.items())
            .as("app_user under a different tenant's GUC must not see this order via deliveryState")
            .noneMatch(o -> o.id().equals(orderId.toString()));
    }

    // ── Orders-rebuild pass (b): GET /orders/{id}/timeline ──────────────────────

    @Test
    void rls_timeline_sameTenantPositiveControl_orderedSeededEvents_crossTenantNegativeControl() {
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        UUID orderId = insertOrderAt("TL-POS", "with_courier", t0);
        insertPieceEvent(orderId, "scan", "available", "reserved", t0.plus(1, ChronoUnit.HOURS));
        insertPieceEvent(orderId, "pack", "reserved", "packed", t0.plus(2, ChronoUnit.HOURS));
        UUID shipmentId = insertForwardShipmentAt(orderId, "9810299001", "created", t0.plus(3, ChronoUnit.HOURS));
        insertHistoryAt(shipmentId, "created", t0.plus(4, ChronoUnit.HOURS), null, null);
        insertHistoryAt(shipmentId, "with_courier", t0.plus(5, ChronoUnit.HOURS), null, null);

        OrderController appUserController = new OrderController(appUserJdbc, mapper, appUserTxm);

        // Positive control: app_user WITH the correct tenant GUC sees the full, correctly
        // ordered, seeded timeline — proves this isn't a silent-empty-page RLS/GUC bug
        // wearing a "no events yet" costume.
        List<TimelineItem> timeline =
            TenantContext.runAs(tenantId, () -> appUserController.timeline(orderId));

        assertThat(timeline).extracting(TimelineItem::eventKey).containsExactly(
            "order_created", "picked_up", "packed", "awb_linked",
            "shipment_state_with_courier");
        assertThat(timeline.get(3).detail())
            .as("awb_linked carries the tracking number; the earliest real Bosta row ('created') is subsumed into it, not duplicated")
            .isEqualTo("9810299001");
        assertThat(timeline).isSortedAccordingTo(Comparator.comparing(TimelineItem::occurredAt));
        assertThat(timeline.get(timeline.size() - 1).kind())
            .as("chronologically-last row, no failure seeded -> 'now'")
            .isEqualTo("now");
        assertThat(timeline.subList(0, timeline.size() - 1))
            .as("every non-last row is 'done' — no failure seeded")
            .allMatch(i -> "done".equals(i.kind()));

        // Negative control: app_user under a DIFFERENT tenant's GUC cannot see it — 404,
        // matching detail()'s existing contract, not a security-bypass 200/empty list.
        assertThatThrownBy(() -> TenantContext.runAs(otherTenantId, () -> appUserController.timeline(orderId)))
            .as("app_user under a different tenant's GUC must not see this order's timeline")
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404");
    }

    @Test
    void timeline_deliveryAttemptFailed_marksFailKind_notOverriddenByLastRowNowRule() {
        Instant t0 = Instant.parse("2026-02-01T09:00:00Z");
        UUID orderId = insertOrderAt("TL-FAIL", "with_courier", t0);
        UUID shipmentId = insertForwardShipmentAt(orderId, "9810299005", "with_courier", t0.plus(1, ChronoUnit.HOURS));
        insertHistoryAt(shipmentId, "with_courier", t0.plus(2, ChronoUnit.HOURS), null, null);
        // Last row IS the failure — must stay 'fail', never flip to 'now'.
        insertHistoryAt(shipmentId, "exception", t0.plus(3, ChronoUnit.HOURS), 8, "Customer refused");

        List<TimelineItem> timeline = controller.timeline(orderId);
        TimelineItem last = timeline.get(timeline.size() - 1);
        assertThat(last.eventKey()).isEqualTo("delivery_attempt_failed");
        assertThat(last.kind()).isEqualTo("fail");
        assertThat(last.detail()).isEqualTo("Customer refused");
    }

    // ── Orders fix pass — 2a: AWB-linked relabeling ─────────────────────────────

    @Test
    void timeline_awbLinked_zeroHistoryRows_fallsBackToShipmentCreatedAt() {
        // Just-linked shipment, no Bosta webhook received yet — the AWB is still a true
        // fact to show, not hide, so awb_linked falls back to shipments.created_at.
        Instant t0 = Instant.parse("2026-03-01T09:00:00Z");
        UUID orderId = insertOrderAt("TL-AWB-FALLBACK", "awaiting_pickup", t0);
        insertForwardShipmentAt(orderId, "9810299030", "created", t0.plus(1, ChronoUnit.HOURS));

        List<TimelineItem> timeline = controller.timeline(orderId);
        TimelineItem awb = timeline.stream()
            .filter(i -> "awb_linked".equals(i.eventKey())).findFirst().orElseThrow();
        assertThat(awb.occurredAt()).isEqualTo(t0.plus(1, ChronoUnit.HOURS));
        assertThat(awb.detail()).isEqualTo("9810299030");
        assertThat(timeline).extracting(TimelineItem::eventKey).doesNotContain("handed_to_bosta");
    }

    @Test
    void timeline_awbLinked_realHistoryRowWins_neverUsesCreatedAtOnceOneExists() {
        // Once any real Bosta row exists, shipments.created_at is never consulted again —
        // the earliest real row's own occurred_at becomes the AWB-linked timestamp, and
        // that row is subsumed (not duplicated) rather than also appearing under its own
        // "Label created" label.
        Instant t0 = Instant.parse("2026-03-02T09:00:00Z");
        UUID orderId = insertOrderAt("TL-AWB-REAL", "with_courier", t0);
        UUID shipmentId = insertForwardShipmentAt(orderId, "9810299031", "created", t0.plus(1, ChronoUnit.HOURS));
        insertHistoryAt(shipmentId, "created", t0.plus(2, ChronoUnit.HOURS), null, null);

        List<TimelineItem> timeline = controller.timeline(orderId);
        assertThat(timeline).extracting(TimelineItem::eventKey)
            .as("the 'created' row is subsumed into awb_linked, not shown twice")
            .containsExactly("order_created", "awb_linked");
        TimelineItem awb = timeline.get(1);
        assertThat(awb.occurredAt())
            .as("the real Bosta row's timestamp wins over shipments.created_at")
            .isEqualTo(t0.plus(2, ChronoUnit.HOURS));
        assertThat(awb.detail()).isEqualTo("9810299031");
    }

    @Test
    void timeline_returnLeg_getsSameAwbLinkedTreatment() {
        Instant t0 = Instant.parse("2026-03-03T09:00:00Z");
        UUID orderId = insertOrderAt("TL-AWB-RETURN", "returning", t0);
        UUID returnShipmentId = insertReturnShipmentAt(orderId, "9810299032", "created",
            t0.plus(1, ChronoUnit.HOURS));
        insertHistoryAt(returnShipmentId, "created", t0.plus(2, ChronoUnit.HOURS), null, null);

        List<TimelineItem> timeline = controller.timeline(orderId);
        assertThat(timeline).extracting(TimelineItem::eventKey)
            .containsExactly("order_created", "return_awb_linked");
        assertThat(timeline.get(1).detail()).isEqualTo("9810299032");
        assertThat(timeline.get(1).occurredAt()).isEqualTo(t0.plus(2, ChronoUnit.HOURS));
    }

    @Test
    void timeline_awbLinked_firstRowIsException_notSilentlyAbsorbed() {
        // Rare edge case: the very first-ever Bosta webhook for a shipment is itself an
        // exception. It must NOT be silently relabeled away as a bland "done" AWB marker —
        // the failure stays its own 'fail' row alongside the AWB marker.
        Instant t0 = Instant.parse("2026-03-04T09:00:00Z");
        UUID orderId = insertOrderAt("TL-AWB-FIRST-EXC", "with_courier", t0);
        UUID shipmentId = insertForwardShipmentAt(orderId, "9810299033", "exception", t0.plus(1, ChronoUnit.HOURS));
        insertHistoryAt(shipmentId, "exception", t0.plus(2, ChronoUnit.HOURS), 8, "Refused on first contact");

        List<TimelineItem> timeline = controller.timeline(orderId);
        assertThat(timeline).extracting(TimelineItem::eventKey)
            .containsExactly("order_created", "awb_linked", "delivery_attempt_failed");
        TimelineItem exceptionRow = timeline.get(2);
        assertThat(exceptionRow.kind()).isEqualTo("fail");
        assertThat(exceptionRow.detail()).isEqualTo("Refused on first contact");
    }

    // ── Orders fix pass — 2b: consecutive-only collapse ─────────────────────────

    @Test
    void timeline_consecutiveIdenticalState_collapsesToFirstOccurrence() {
        // Live-order shape: repeated webhooks re-polling the SAME state (e.g. "Out for
        // delivery" ×4) must collapse to a single row at the first occurrence.
        Instant t0 = Instant.parse("2026-03-05T09:00:00Z");
        UUID orderId = insertOrderAt("TL-COLLAPSE", "with_courier", t0);
        UUID shipmentId = insertForwardShipmentAt(orderId, "9810299040", "with_courier", t0.plus(1, ChronoUnit.HOURS));
        insertHistoryAt(shipmentId, "created", t0.plus(2, ChronoUnit.HOURS), null, null);
        insertHistoryAt(shipmentId, "with_courier", t0.plus(3, ChronoUnit.HOURS), null, null);
        insertHistoryAt(shipmentId, "with_courier", t0.plus(4, ChronoUnit.HOURS), null, null); // re-poll
        insertHistoryAt(shipmentId, "with_courier", t0.plus(5, ChronoUnit.HOURS), null, null); // re-poll

        List<TimelineItem> timeline = controller.timeline(orderId);
        assertThat(timeline).extracting(TimelineItem::eventKey)
            .containsExactly("order_created", "awb_linked", "shipment_state_with_courier");
        assertThat(timeline.get(2).occurredAt())
            .as("collapses to the FIRST occurrence's timestamp, not the last")
            .isEqualTo(t0.plus(3, ChronoUnit.HOURS));
    }

    @Test
    void timeline_reattemptAfterException_survivesCollapse_notFoldedAsRepoll() {
        // The critical case from the fix-pass spec: with_courier -> exception -> with_courier
        // MUST keep BOTH with_courier rows — the second one is a genuine re-attempt, not a
        // re-poll of the first, because it is not ADJACENT to it (an exception sits between).
        Instant t0 = Instant.parse("2026-03-06T09:00:00Z");
        UUID orderId = insertOrderAt("TL-REATTEMPT", "with_courier", t0);
        UUID shipmentId = insertForwardShipmentAt(orderId, "9810299041", "with_courier", t0.plus(1, ChronoUnit.HOURS));
        insertHistoryAt(shipmentId, "created", t0.plus(2, ChronoUnit.HOURS), null, null);
        insertHistoryAt(shipmentId, "with_courier", t0.plus(3, ChronoUnit.HOURS), null, null);
        insertHistoryAt(shipmentId, "exception", t0.plus(4, ChronoUnit.HOURS), 8, "Customer not answering");
        insertHistoryAt(shipmentId, "with_courier", t0.plus(5, ChronoUnit.HOURS), null, null); // re-attempt

        List<TimelineItem> timeline = controller.timeline(orderId);
        assertThat(timeline).extracting(TimelineItem::eventKey)
            .as("both with_courier rows survive — separated by a real exception, not consecutive")
            .containsExactly("order_created", "awb_linked", "shipment_state_with_courier",
                "delivery_attempt_failed", "shipment_state_with_courier");
        assertThat(timeline).extracting(TimelineItem::occurredAt)
            .containsExactly(t0, t0.plus(2, ChronoUnit.HOURS), t0.plus(3, ChronoUnit.HOURS),
                t0.plus(4, ChronoUnit.HOURS), t0.plus(5, ChronoUnit.HOURS));
    }

    @Test
    void timeline_consecutiveExceptions_sameCodeCollapses_differentCodeDoesNot() {
        // Keyed on (internal_state, exception_code) per the 2b clarification — reusing
        // groupHistory()'s existing frontend precedent (OrderDetail.tsx): two consecutive
        // exceptions with the SAME code fold like any other repeated state; a different
        // code is a genuinely different event and must never merge.
        Instant t0 = Instant.parse("2026-03-07T09:00:00Z");
        UUID orderId = insertOrderAt("TL-EXC-COLLAPSE", "with_courier", t0);
        UUID shipmentId = insertForwardShipmentAt(orderId, "9810299042", "exception", t0.plus(1, ChronoUnit.HOURS));
        insertHistoryAt(shipmentId, "created", t0.plus(2, ChronoUnit.HOURS), null, null);
        insertHistoryAt(shipmentId, "exception", t0.plus(3, ChronoUnit.HOURS), 8, "Refused by customer");
        insertHistoryAt(shipmentId, "exception", t0.plus(4, ChronoUnit.HOURS), 8, "Refused by customer"); // re-poll, same code
        insertHistoryAt(shipmentId, "exception", t0.plus(5, ChronoUnit.HOURS), 1, "Not at address"); // different code

        List<TimelineItem> timeline = controller.timeline(orderId);
        assertThat(timeline).extracting(TimelineItem::eventKey)
            .containsExactly("order_created", "awb_linked", "delivery_attempt_failed", "delivery_attempt_failed");
        assertThat(timeline).extracting(TimelineItem::occurredAt)
            .containsExactly(t0, t0.plus(2, ChronoUnit.HOURS), t0.plus(3, ChronoUnit.HOURS), t0.plus(5, ChronoUnit.HOURS));
        assertThat(timeline.get(2).detail()).isEqualTo("Refused by customer");
        assertThat(timeline.get(3).detail()).isEqualTo("Not at address");
    }

    // ── Orders fix pass — 2c: phase-primary ordering, immune to clock skew ──────

    @Test
    void timeline_phasePrimaryOrdering_returnLegAlwaysAfterCourier_evenWithClockSkew() {
        // Deliberately construct a clock-skew shape: the return leg's real timestamp is
        // EARLIER than the forward leg's delivered timestamp. Phase must still win — the
        // return leg's row renders after ALL courier-phase rows regardless.
        Instant t0 = Instant.parse("2026-03-08T09:00:00Z");
        UUID orderId = insertOrderAt("TL-PHASE-SKEW", "returning", t0);

        UUID forwardId = insertForwardShipmentAt(orderId, "9810299050", "delivered", t0.plus(1, ChronoUnit.HOURS));
        insertHistoryAt(forwardId, "created", t0.plus(2, ChronoUnit.HOURS), null, null);
        insertHistoryAt(forwardId, "delivered", t0.plus(3, ChronoUnit.HOURS), null, null);

        // Return shipment's own AWB-linked row lands at t0+2h45m — chronologically BEFORE
        // the forward leg's "delivered" at t0+3h, but must still sort AFTER it.
        UUID returnId = insertReturnShipmentAt(orderId, "9810299051", "created", t0.plus(1, ChronoUnit.HOURS));
        insertHistoryAt(returnId, "created", t0.plus(2, ChronoUnit.HOURS).plus(45, ChronoUnit.MINUTES), null, null);

        List<TimelineItem> timeline = controller.timeline(orderId);
        assertThat(timeline).extracting(TimelineItem::eventKey).containsExactly(
            "order_created", "awb_linked", "shipment_state_delivered", "return_awb_linked");
        assertThat(timeline.get(2).occurredAt()).isEqualTo(t0.plus(3, ChronoUnit.HOURS));
        assertThat(timeline.get(3).occurredAt())
            .as("chronologically earlier than shipment_state_delivered, but phase (return) wins over raw time")
            .isEqualTo(t0.plus(2, ChronoUnit.HOURS).plus(45, ChronoUnit.MINUTES))
            .isBefore(timeline.get(2).occurredAt());
    }

    @Test
    void timeline_phasePrimaryOrdering_reattemptInterleaving_preservedWithinCourierPhase() {
        // The other named risk for 2c: phase-primary sorting must NOT regroup a real
        // with_courier -> exception -> with_courier interleaving into
        // with_courier -> with_courier -> exception just because all three share one phase.
        // All three are courier-phase (rank 2), so the comparator falls through to
        // occurred_at and must preserve their real order exactly.
        Instant t0 = Instant.parse("2026-03-09T09:00:00Z");
        UUID orderId = insertOrderAt("TL-PHASE-INTERLEAVE", "with_courier", t0);
        UUID shipmentId = insertForwardShipmentAt(orderId, "9810299052", "with_courier", t0.plus(1, ChronoUnit.HOURS));
        insertHistoryAt(shipmentId, "created", t0.plus(2, ChronoUnit.HOURS), null, null);
        insertHistoryAt(shipmentId, "with_courier", t0.plus(3, ChronoUnit.HOURS), null, null);
        insertHistoryAt(shipmentId, "exception", t0.plus(4, ChronoUnit.HOURS), 8, "Customer not answering");
        insertHistoryAt(shipmentId, "with_courier", t0.plus(5, ChronoUnit.HOURS), null, null);

        List<TimelineItem> timeline = controller.timeline(orderId);
        assertThat(timeline).extracting(TimelineItem::eventKey)
            .containsExactly("order_created", "awb_linked", "shipment_state_with_courier",
                "delivery_attempt_failed", "shipment_state_with_courier");
    }

    // ── Orders fix pass — item 3: image_url on the item DTO ─────────────────────

    @Test
    void detail_orderItem_imageUrl_sameTenantPositiveControl() {
        UUID imgProductId = UUID.randomUUID();
        UUID imgVariantId = UUID.randomUUID();
        String imageUrl = "https://cdn.shopify.com/s/files/1/parity-widget.jpg";
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status, image_url) " +
                    "VALUES (?, ?, ?, 'P-IMG', 'Imaged Widget', 'active', ?)",
                    imgProductId, tenantId, storeId, imageUrl);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-IMG', 'Default', 'IMG-001')",
                    imgVariantId, tenantId, imgProductId);

        UUID orderId = insertOrder("IMG-POS", "new");
        UUID itemId = jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, tenantId, orderId, imgVariantId);

        OrderController.OrderItem item = controller.detail(orderId).items().stream()
            .filter(i -> i.id().equals(itemId.toString())).findFirst().orElseThrow();
        assertThat(item.imageUrl())
            .as("same-tenant positive control — products.image_url flows through fetchItems() to the DTO")
            .isEqualTo(imageUrl);
    }

    // ── attempt-count canonical source: exception is NOT 1:1 with a delivery attempt ──

    @Test
    void attemptCount_exceptionNotOneToOneWithDeliveryAttempts_scopedToForwardNdrCodes() {
        UUID orderId = insertOrder("ATTEMPT-SCOPE", "with_courier");
        UUID shipmentId = insertForwardShipment(orderId, "9810299002", "with_courier", 2, 0);

        // Genuine forward delivery-attempt failure — NDR code 8 ("Refused by customer"),
        // ndr_codes.category='forward' — MUST count.
        insertHistoryAt(shipmentId, "exception", Instant.now(), 8, "Customer refused");
        // Bosta states 101/102/103 (damage found / investigation / hub-limbo) also collapse
        // into internal_state='exception' but carry no §8.4 forward NDR code — MUST NOT
        // count as a delivery attempt.
        insertHistoryAt(shipmentId, "exception", Instant.now(), null, "Investigation opened");

        ShipmentDetail forward = controller.detail(orderId).shipments().get(0);
        assertThat(forward.failedDeliveryAttempts())
            .as("only the genuine NDR forward-attempt row counts, not the uncoded exception")
            .isEqualTo(1);
    }

    // ── in-transit convergence: summary().withCourier and list(deliveryState=with_courier)
    // must share one predicate — the review finding this pass fixes. ────────────────────

    @Test
    void inTransit_convergedPredicate_summaryCountMatchesListMembership() {
        UUID a = insertOrder("CONV-A", "with_courier");
        insertForwardShipment(a, "9810299010", "with_courier", 0, 0);
        UUID b = insertOrder("CONV-B", "awaiting_pickup");
        insertForwardShipment(b, "9810299011", "with_courier", 0, 0);
        // Noise — NOT with_courier, must not appear in the list and must not be double-
        // counted, proving the assertion isn't trivially true.
        UUID c = insertOrder("CONV-C", "awaiting_pickup");
        insertForwardShipment(c, "9810299012", "created", 0, 0);

        OrderController.OrderSummaryCounts summary = controller.summary();
        OrderPage list = controller.list(null, "with_courier", null, null, 0, 100);

        assertThat((long) summary.withCourier())
            .as("summary().withCourier and list(deliveryState=with_courier).total must agree — one predicate")
            .isEqualTo(list.total());
        assertThat(list.items()).extracting(OrderSummary::id)
            .contains(a.toString(), b.toString())
            .doesNotContain(c.toString());
    }

    // ── attempt-count LEFT JOIN: unmapped codes must fail OPEN, not silently undercount ──

    @Test
    void attemptCount_unmappedForwardCode_stillCountsAsAttempt_notSilentlyDropped() {
        // exception_code has no FK to ndr_codes (confirmed by grep of V40's schema) — an
        // INNER JOIN would silently drop a code Bosta reports that isn't in our V2 seed.
        // 999 is deliberately not seeded by V2__bosta_seed.sql.
        UUID orderId = insertOrder("ATTEMPT-UNMAPPED", "with_courier");
        UUID shipmentId = insertForwardShipment(orderId, "9810299020", "with_courier", 1, 0);
        insertHistoryAt(shipmentId, "exception", Instant.now(), 999, "Unrecognized courier code");

        ShipmentDetail forward = controller.detail(orderId).shipments().get(0);
        assertThat(forward.failedDeliveryAttempts())
            .as("a present-but-unmapped exception_code must still count — fail open, never silently undercount")
            .isEqualTo(1);
    }

    @Test
    void attemptCount_returnLeg_unmappedCode_countsAsAttempt_butKnownCriticalEvidenceCodeDoesNot() {
        UUID orderId = insertOrder("ATTEMPT-RETURN-SCOPE", "returning");
        insertForwardShipment(orderId, "9810299021", "returning", 0, 0);
        UUID returnShipmentId = insertReturnShipment(orderId, "9810299022", "returning");

        // Unmapped return-side code — fail open, counts.
        insertHistoryAt(returnShipmentId, "exception", Instant.now(), 998, "Unrecognized return code");
        // Known critical evidence code (26 = "Order damaged") — courier-side evidence, not
        // an RTO attempt (ndr_codes.severity='critical') — must NOT count.
        insertHistoryAt(returnShipmentId, "exception", Instant.now(), 26, "Order damaged");

        ShipmentDetail returnLeg = controller.detail(orderId).shipments().stream()
            .filter(s -> "return".equals(s.shipmentLeg())).findFirst().orElseThrow();
        assertThat(returnLeg.failedDeliveryAttempts())
            .as("unmapped return code counts (fail open); known critical evidence code (26) does not")
            .isEqualTo(1);
    }
}
