package com.traceability.fulfillment;

import com.traceability.TestPostgres;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pack scan of a piece that came back from the courier and still waits for its return verdict
 * (BROEK, 2026-11-05): the packer saw "Piece is already reserved for another order" — the piece's
 * allocation on the old order is released only by Scan returns. The scan is still refused, now
 * with RETURNED_NEEDS_INSPECTION telling the packer what to do.
 *
 *   r1  return_pending_inspection piece with a live 'packed' allocation on an old order →
 *       RETURNED_NEEDS_INSPECTION (EN + AR), nothing written.
 *   r2  control: a piece reserved to another order → ALREADY_RESERVED, unchanged.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackScanReturnedPieceTest {

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
    }

    @Autowired FulfillService fulfill;
    @Autowired JdbcTemplate   jdbc;

    UUID tenantId, actorId, storeId, variantId, locationId;

    @BeforeAll
    void setup() {
        tenantId   = UUID.randomUUID();
        actorId    = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        variantId  = UUID.randomUUID();
        locationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'PackScanReturned')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Owner', 'owner@packscanreturned.local', 'h', 'owner')", actorId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'packscanreturned.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'P-1', 'Hoodie', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'V-1', 'Hoodie XXL', 'HOOD-XXL')", variantId, tenantId, productId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true)", locationId, tenantId);
    }

    @BeforeEach void ctx() { TenantContext.set(tenantId); }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    @Test
    void r1_returnedPiece_awaitingInspection_isRefusedWithScanReturnsMessage() {
        UUID oldOrder = order("awaiting_pickup");
        UUID oldItem  = item(oldOrder);
        String piece  = piece("return_pending_inspection", oldOrder);
        allocation(oldItem, piece, "packed");

        UUID newOrder = order("new");
        item(newOrder);

        FulfillService.ScanResult r = fulfill.scan(newOrder, "PC-" + piece, actorId);

        assertThat(r.success()).isFalse();
        assertThat(r.code()).isEqualTo("RETURNED_NEEDS_INSPECTION");
        assertThat(r.message()).isEqualTo("This piece came back from the courier — run it through Scan returns first");
        assertThat(r.messageAr()).isNotBlank();
        // Nothing written: still waiting for inspection, old allocation untouched, no new one.
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece))
            .isEqualTo("return_pending_inspection");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM allocations WHERE piece_id = ? AND status = 'packed' AND order_item_id = ?",
            Integer.class, piece, oldItem)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM allocations WHERE piece_id = ?", Integer.class, piece))
            .isEqualTo(1);
    }

    @Test
    void r2_pieceReservedToAnotherOrder_stillAlreadyReserved() {
        UUID otherOrder = order("new");
        UUID otherItem  = item(otherOrder);
        String piece    = piece("reserved", otherOrder);
        allocation(otherItem, piece, "active");

        UUID newOrder = order("new");
        item(newOrder);

        FulfillService.ScanResult r = fulfill.scan(newOrder, "PC-" + piece, actorId);

        assertThat(r.success()).isFalse();
        assertThat(r.code()).isEqualTo("ALREADY_RESERVED");
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private UUID order(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, status, on_hold, placed_at) " +
            "VALUES (?, ?, ?, ?, ?::order_status, false, now())", id, tenantId, storeId, id.toString(), status);
        return id;
    }

    private UUID item(UUID orderId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            id, tenantId, orderId, variantId);
        return id;
    }

    private String piece(String status, UUID currentOrderId) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, " +
            "current_order_id) VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
            "?::piece_status, ?, ?)",
            id, tenantId, variantId, "PC-" + id, id, status, locationId, currentOrderId);
        return id;
    }

    private void allocation(UUID orderItemId, String pieceId, String status) {
        jdbc.update("INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status, allocated_by) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?::allocation_status, ?)",
            tenantId, orderItemId, pieceId, status, actorId);
    }
}
