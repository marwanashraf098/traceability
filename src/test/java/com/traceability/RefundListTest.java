package com.traceability;

import com.traceability.inventory.ReturnService;
import com.traceability.inventory.ShipmentLinkService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-EXCHANGE Step 4a-2 — ShipmentLinkService.listCrpReturns(), the "Exchanges & Refunds"
 * tab's CRP (refund) feed. No such cross-order list existed before this — ReturnController.
 * pending()'s items were always a hardcoded empty list.
 *
 * (r1) a CRP return-leg shipment lists with its order number/customer/lifecycle badge —
 *      the badge must equal OrderStatusDeriver.deriveLegStatus(internal_state), the same
 *      primitive OrderController.detail() already applies per-leg.
 * (r2) a forward-leg shipment on the same order is never surfaced by this list.
 * (r3) ORDER BY created_at DESC, id DESC.
 * (r4) cross-tenant isolation, with a same-tenant positive control.
 *
 * Step 4-close Part 2 (gap #4) — inspection_state, additive to leg_status:
 * (r5) internal_state pre-'returned' → inspection_state='in_transit'.
 * (r6) internal_state='returned' + a piece still at return_pending_inspection for the
 *      order → inspection_state='needs_inspection'.
 * (r7) internal_state='returned' + no piece at return_pending_inspection → 'resolved'.
 * (r8) THE real transition, through the actual disposition path (not simulated): a piece
 *      at return_pending_inspection restocked via ReturnService.restock() flips the same
 *      row from 'needs_inspection' to 'resolved' — proves listCrpReturns() and
 *      resolveReturnLegIfComplete() agree, not just that the SQL predicate looks right.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefundListTest {

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

    @Autowired ShipmentLinkService linkSvc;
    @Autowired ReturnService       returnSvc;
    @Autowired JdbcTemplate        jdbc;
    @MockBean  JobScheduler        jobScheduler;

    UUID tenantId, storeId, productId, variantId, locationId, actorId;

    @BeforeAll
    void setupFixture() {
        tenantId   = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        productId  = UUID.randomUUID();
        variantId  = UUID.randomUUID();
        locationId = UUID.randomUUID();
        actorId    = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'RefundListTenant')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'refund-list.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-RFD', 'Bucket Hat', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-RFD', 'Red', 'RED-RFD')", variantId, tenantId, productId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                    "VALUES (?, ?, 'RFD Warehouse', 'warehouse', true, true)", locationId, tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Actor', 'rfd-actor@test.com', 'x', 'owner'::user_role)", actorId, tenantId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clear() {
        TenantContext.clear();
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations   WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events  WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces        WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments     WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders        WHERE tenant_id = ?", tenantId);
    }

    private UUID seedOrder(String extId, String customerName, String customerPhone) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    customer_name, customer_phone, payment_method, placed_at, on_hold) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, ?, ?, 'cod', now(), false) RETURNING id",
            UUID.class, tenantId, storeId, extId, "#" + extId, customerName, customerPhone);
    }

    private UUID seedCrpShipment(UUID orderId, String tracking, String internalState) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "    internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'return') RETURNING id",
            UUID.class, tenantId, orderId, tracking, internalState);
    }

    @Test
    void r1_listCrpReturns_surfacesOrderAndDeriveLegStatusBadge() {
        UUID orderId = seedOrder("EXT-RFD-001", "Nour Adel", "01012340001");
        seedCrpShipment(orderId, "RFD-TN-001", "with_courier");

        List<Map<String, Object>> rows = linkSvc.listCrpReturns(0, 50);
        Map<String, Object> row = rows.stream()
            .filter(r -> "RFD-TN-001".equals(r.get("tracking_number")))
            .findFirst().orElseThrow();

        assertThat(row.get("order_id")).isEqualTo(orderId.toString());
        assertThat(row.get("order_number")).isEqualTo("#EXT-RFD-001");
        assertThat(row.get("customer_name")).isEqualTo("Nour Adel");
        assertThat(row.get("customer_phone")).isEqualTo("01012340001");
        assertThat(row.get("internal_state")).isEqualTo("with_courier");

        Object legStatus = row.get("leg_status");
        assertThat(legStatus).isNotNull();
        // OrderStatusDeriver.LegStatus is a record (primaryKey, tone) — assert via its
        // accessor methods directly rather than duplicating the LEG_KEY/LEG_TONE maps.
        var expected = com.traceability.fulfillment.OrderStatusDeriver.deriveLegStatus("with_courier");
        assertThat(legStatus).isEqualTo(expected);
    }

    @Test
    void r2_listCrpReturns_excludesForwardLegShipments() {
        UUID orderId = seedOrder("EXT-RFD-002", "Omar Said", "01012340002");
        seedCrpShipment(orderId, "RFD-TN-002-RETURN", "delivered");
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', 'RFD-TN-002-FORWARD', 'delivered'::shipment_internal_state, 'forward')",
            tenantId, orderId);

        List<Map<String, Object>> rows = linkSvc.listCrpReturns(0, 50);
        assertThat(rows).noneMatch(r -> "RFD-TN-002-FORWARD".equals(r.get("tracking_number")));
        assertThat(rows).anyMatch(r -> "RFD-TN-002-RETURN".equals(r.get("tracking_number")));
    }

    @Test
    void r3_listCrpReturns_ordersByCreatedAtDescThenIdDesc() {
        // Two different orders — ux_active_shipment_per_order_leg allows only one active
        // return-leg shipment per order, so ordering must be proven across orders.
        UUID olderOrderId = seedOrder("EXT-RFD-003-OLD", "Lina Fathy", "01012340003");
        UUID newerOrderId = seedOrder("EXT-RFD-003-NEW", "Lina Fathy", "01012340003");
        UUID older = seedCrpShipment(olderOrderId, "RFD-TN-003-OLD", "created");
        UUID newer = seedCrpShipment(newerOrderId, "RFD-TN-003-NEW", "created");
        jdbc.update("UPDATE shipments SET created_at = now() - interval '2 days' WHERE id = ?", older);
        jdbc.update("UPDATE shipments SET created_at = now() - interval '1 day'  WHERE id = ?", newer);

        List<Map<String, Object>> rows = linkSvc.listCrpReturns(0, 50);
        int olderIdx = indexOfTracking(rows, "RFD-TN-003-OLD");
        int newerIdx = indexOfTracking(rows, "RFD-TN-003-NEW");
        assertThat(newerIdx).isLessThan(olderIdx);
    }

    @Test
    void r4_crossTenantIsolation_withSameTenantPositiveControl() {
        UUID tenantB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'RefundListTenantB')", tenantB);
        UUID storeBId = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'refund-list-b.myshopify.com', 'disconnected')", storeBId, tenantB);
        UUID orderB = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    customer_name, customer_phone, payment_method, placed_at, on_hold) " +
            "VALUES (?, ?, 'EXT-RFD-B', '#EXT-RFD-B', 'new'::order_status, 'Tenant B Buyer', " +
            "    '01099990000', 'cod', now(), false) RETURNING id",
            UUID.class, tenantB, storeBId);
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', 'RFD-TN-004-TENANT-B', 'created'::shipment_internal_state, 'return')",
            tenantB, orderB);

        // Negative: tenant A's context must never see tenant B's CRP leg.
        List<Map<String, Object>> asTenantA = linkSvc.listCrpReturns(0, 50);
        assertThat(asTenantA).noneMatch(r -> "RFD-TN-004-TENANT-B".equals(r.get("tracking_number")));

        // Same-tenant positive control: tenant A's own CRP leg does appear.
        UUID orderA = seedOrder("EXT-RFD-004-A", "Positive Control", "01012340004");
        seedCrpShipment(orderA, "RFD-TN-004-TENANT-A", "created");
        List<Map<String, Object>> asTenantAAgain = linkSvc.listCrpReturns(0, 50);
        assertThat(asTenantAAgain).anyMatch(r -> "RFD-TN-004-TENANT-A".equals(r.get("tracking_number")));

        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantB);
        jdbc.update("DELETE FROM orders    WHERE tenant_id = ?", tenantB);
        jdbc.update("DELETE FROM stores    WHERE tenant_id = ?", tenantB);
        jdbc.update("DELETE FROM tenants   WHERE id = ?", tenantB);
    }

    private String seedPendingInspectionPiece(UUID orderId) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
            "        'return_pending_inspection'::piece_status, ?)",
            id, tenantId, variantId, "PC-" + id, id, orderId);
        return id;
    }

    private Map<String, Object> findRow(List<Map<String, Object>> rows, String tracking) {
        return rows.stream().filter(r -> tracking.equals(r.get("tracking_number"))).findFirst().orElseThrow();
    }

    @Test
    void r5_preReturned_inspectionStateIsInTransit() {
        UUID orderId = seedOrder("EXT-RFD-005", "Yara Fouad", "01012340005");
        seedCrpShipment(orderId, "RFD-TN-005", "with_courier");

        Map<String, Object> row = findRow(linkSvc.listCrpReturns(0, 50), "RFD-TN-005");
        assertThat(row.get("inspection_state")).isEqualTo("in_transit");
    }

    @Test
    void r6_returnedWithPendingPiece_inspectionStateNeedsInspection() {
        UUID orderId = seedOrder("EXT-RFD-006", "Hana Wael", "01012340006");
        seedCrpShipment(orderId, "RFD-TN-006", "returned");
        seedPendingInspectionPiece(orderId);

        Map<String, Object> row = findRow(linkSvc.listCrpReturns(0, 50), "RFD-TN-006");
        assertThat(row.get("inspection_state")).isEqualTo("needs_inspection");
    }

    @Test
    void r7_returnedWithNoPendingPiece_inspectionStateResolved() {
        UUID orderId = seedOrder("EXT-RFD-007", "Karim Adly", "01012340007");
        seedCrpShipment(orderId, "RFD-TN-007", "returned");
        // No piece at return_pending_inspection for this order at all.

        Map<String, Object> row = findRow(linkSvc.listCrpReturns(0, 50), "RFD-TN-007");
        assertThat(row.get("inspection_state")).isEqualTo("resolved");
    }

    @Test
    void r8_realRestockTransition_needsInspectionThenResolved() {
        UUID orderId = seedOrder("EXT-RFD-008", "Sara Kamal", "01012340008");
        seedCrpShipment(orderId, "RFD-TN-008", "returned");
        String pieceId = seedPendingInspectionPiece(orderId);

        Map<String, Object> before = findRow(linkSvc.listCrpReturns(0, 50), "RFD-TN-008");
        assertThat(before.get("inspection_state"))
            .as("undispositioned piece still at return_pending_inspection").isEqualTo("needs_inspection");

        returnSvc.restock(pieceId, locationId, actorId);

        Map<String, Object> after = findRow(linkSvc.listCrpReturns(0, 50), "RFD-TN-008");
        assertThat(after.get("inspection_state"))
            .as("Step 4-close Part 2: restocking the last pending piece must flip the row to resolved")
            .isEqualTo("resolved");
    }

    private int indexOfTracking(List<Map<String, Object>> rows, String tracking) {
        for (int i = 0; i < rows.size(); i++) {
            if (tracking.equals(rows.get(i).get("tracking_number"))) return i;
        }
        throw new AssertionError("tracking not found in rows: " + tracking);
    }
}
