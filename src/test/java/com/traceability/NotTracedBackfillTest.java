package com.traceability;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Queue-gating-not-traced spec — V57 migration backfill.
 *
 * Runs Flyway twice against the same container: first up to V56 (pre-feature schema) to seed
 * the stuck population directly, then to the latest version so only V57 executes and its
 * backfill UPDATE runs against real, pre-existing rows — not the empty schema every other
 * migration test starts from.
 *
 * Fixture shape mirrors the confirmed production population (Jumi, 58 orders): status='new',
 * on_hold=false, zero allocations, forward-leg shipment already 'delivered'. A sibling
 * "traced" order — same status, same terminal shipment state, but WITH a packed allocation —
 * proves the zero-allocation guard, not just the shipment-state check, gates the backfill.
 */
@Testcontainers
class NotTracedBackfillTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    void backfill_tagsStuckPopulation_leavesTracedOrderUntouched() throws Exception {
        // 1. Migrate up to V56 only — schema as it existed before this feature.
        Flyway toV56 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("56")
                .load();
        MigrateResult r1 = toV56.migrate();
        assertThat(r1.success).as("migration to V56 must succeed").isTrue();

        UUID tenantId      = UUID.randomUUID();
        UUID storeId       = UUID.randomUUID();
        UUID productId     = UUID.randomUUID();
        UUID variantId     = UUID.randomUUID();
        UUID stuckOrderId  = UUID.randomUUID();
        UUID tracedOrderId = UUID.randomUUID();
        UUID tracedItemId  = UUID.randomUUID();
        String tracedPieceId = "BFTRACEDPIECE001";

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            exec(conn, "INSERT INTO tenants (id, name) VALUES (?, 'BackfillTenant')", tenantId);
            exec(conn, "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                       "VALUES (?, ?, 'shopify', 'backfill.myshopify.com', 'disconnected')",
                       storeId, tenantId);
            exec(conn, "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                       "VALUES (?, ?, ?, 'BF-PROD', 'BF Product', 'active')",
                       productId, tenantId, storeId);
            exec(conn, "INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
                       "VALUES (?, ?, ?, 'BF-VAR', 'BF Variant')",
                       variantId, tenantId, productId);

            // Stuck order: status='new', zero allocations, forward shipment already 'delivered'.
            exec(conn, "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, on_hold, placed_at) " +
                       "VALUES (?, ?, ?, 'EXT-BF-STUCK', '#BF-STUCK', 'new'::order_status, false, now())",
                       stuckOrderId, tenantId, storeId);
            exec(conn, "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                       "VALUES (?, ?, 'bosta', 'BF-TN-STUCK', 'delivered'::shipment_internal_state, 'forward')",
                       tenantId, stuckOrderId);

            // Traced order: same status + same terminal shipment state, but HAS a packed
            // allocation — must be left untouched by the backfill.
            exec(conn, "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, on_hold, placed_at) " +
                       "VALUES (?, ?, ?, 'EXT-BF-TRACED', '#BF-TRACED', 'new'::order_status, false, now())",
                       tracedOrderId, tenantId, storeId);
            exec(conn, "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                       "VALUES (?, ?, 'bosta', 'BF-TN-TRACED', 'delivered'::shipment_internal_state, 'forward')",
                       tenantId, tracedOrderId);
            exec(conn, "INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) " +
                       "VALUES (?, ?, ?, ?, 1)",
                       tracedItemId, tenantId, tracedOrderId, variantId);
            exec(conn, "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                       "VALUES (?, ?, ?, ?, 'BF900001', 'packed'::piece_status)",
                       tracedPieceId, tenantId, variantId, "PC-" + tracedPieceId);
            exec(conn, "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status) " +
                       "VALUES (gen_random_uuid(), ?, ?, ?, 'packed'::allocation_status)",
                       tenantId, tracedItemId, tracedPieceId);
        }

        // 2. Apply the rest of the migrations — V57 (this test's subject) and whatever
        //    has landed since (currently V58, an unrelated allocations backfill that only
        //    touches 'available' pieces; V59, a locations.is_fulfillment column add +
        //    backfill; and V60, a CHECK constraint widen on shopify_inventory_adjustments —
        //    none of these touch orders/allocations, so their presence here doesn't affect
        //    this test's assertions).
        Flyway toLatest = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        MigrateResult r2 = toLatest.migrate();
        assertThat(r2.success).as("migrations after V56 must succeed").isTrue();
        assertThat(r2.migrationsExecuted).as("V57 + V58 + V59 + V60 pending after V56").isEqualTo(4);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(notTracedAt(conn, stuckOrderId))
                    .as("stuck order (zero allocations, terminal forward shipment) must be tagged")
                    .isNotNull();
            assertThat(notTracedAt(conn, tracedOrderId))
                    .as("traced order (has a packed allocation) must be left untouched")
                    .isNull();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void exec(Connection conn, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.execute();
        }
    }

    private static Timestamp notTracedAt(Connection conn, UUID orderId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT not_traced_at FROM orders WHERE id = ?")) {
            ps.setObject(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp("not_traced_at");
            }
        }
    }
}
