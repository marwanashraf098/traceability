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
 * T6 — V98's evidence-based backfill of shipments.return_intake_completed_at.
 *
 * Migrates to V97, seeds return legs in the shapes production has, then applies V98:
 *   - terminal leg WITH a return_received event after its created_at → stamped with that
 *     event's occurred_at (earliest qualifying);
 *   - terminal leg with NO scan evidence → stays NULL (genuinely unscanned);
 *   - terminal leg whose only piece movement was the old CRP state-46 courier_update
 *     (the pre-V98 order-scoped move) → stays NULL — a courier_update is not a scan;
 *   - terminal leg whose only return_received event PRE-dates the leg → stays NULL;
 *   - non-terminal leg with scan evidence → stays NULL (backfill only touches terminal legs).
 */
@Testcontainers
class ReturnIntakeBackfillTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    void v98Backfill_stampsOnlyTerminalLegsWithScanEvidence() throws Exception {
        MigrateResult r1 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("97")
                .load().migrate();
        assertThat(r1.success).isTrue();

        UUID tenantId = UUID.randomUUID(), storeId = UUID.randomUUID(),
             productId = UUID.randomUUID(), variantId = UUID.randomUUID();

        UUID legScanned, legUnscanned, legCourierOnly, legEventBefore, legInTransit;
        Timestamp firstScan = Timestamp.valueOf("2026-09-10 10:00:00");

        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            exec(c, "INSERT INTO tenants (id, name) VALUES (?, 'BackfillTenant')", tenantId);
            exec(c, "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'v98-backfill.myshopify.com', 'disconnected')", storeId, tenantId);
            exec(c, "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-V98', 'Tote', 'active')", productId, tenantId, storeId);
            exec(c, "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-V98', 'Black', 'TOTE-BLK')", variantId, tenantId, productId);

            legScanned = seedLeg(c, tenantId, storeId, "returned", "2026-09-01 09:00:00");
            String p1 = seedPiece(c, tenantId, variantId, "V98PIECE000001");
            seedEvent(c, tenantId, p1, orderOf(c, legScanned), "return_received", "2026-09-12 10:00:00");
            seedEvent(c, tenantId, p1, orderOf(c, legScanned), "return_received", firstScan.toString());

            legUnscanned = seedLeg(c, tenantId, storeId, "returned", "2026-09-01 09:00:00");

            legCourierOnly = seedLeg(c, tenantId, storeId, "returned", "2026-09-01 09:00:00");
            String p3 = seedPiece(c, tenantId, variantId, "V98PIECE000003");
            seedEvent(c, tenantId, p3, orderOf(c, legCourierOnly), "courier_update", "2026-09-05 10:00:00");

            legEventBefore = seedLeg(c, tenantId, storeId, "returned", "2026-09-01 09:00:00");
            String p4 = seedPiece(c, tenantId, variantId, "V98PIECE000004");
            seedEvent(c, tenantId, p4, orderOf(c, legEventBefore), "return_received", "2026-08-20 10:00:00");

            legInTransit = seedLeg(c, tenantId, storeId, "returning", "2026-09-01 09:00:00");
            String p5 = seedPiece(c, tenantId, variantId, "V98PIECE000005");
            seedEvent(c, tenantId, p5, orderOf(c, legInTransit), "return_received", "2026-09-10 10:00:00");
        }

        MigrateResult r2 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load().migrate();
        assertThat(r2.success).isTrue();
        assertThat(r2.migrationsExecuted).as("V98 only").isEqualTo(1);

        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(intake(c, legScanned))
                .as("terminal leg with scan evidence → earliest qualifying return_received occurred_at")
                .isEqualTo(firstScan);
            assertThat(intake(c, legUnscanned)).as("no evidence → NULL").isNull();
            assertThat(intake(c, legCourierOnly)).as("courier_update is not a scan → NULL").isNull();
            assertThat(intake(c, legEventBefore)).as("scan before the leg existed → NULL").isNull();
            assertThat(intake(c, legInTransit)).as("non-terminal legs are not backfilled").isNull();

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT return_unscanned_window_days FROM tenants WHERE id = ?")) {
                ps.setObject(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(3);
                }
            }
        }
    }

    private static UUID seedLeg(Connection c, UUID tenantId, UUID storeId, String state, String createdAt)
            throws Exception {
        UUID orderId = UUID.randomUUID();
        exec(c, "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
                "VALUES (?, ?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now())",
                orderId, tenantId, storeId, "gid://shopify/Order/" + orderId, "#" + orderId.toString().substring(0, 6));
        UUID legId = UUID.randomUUID();
        exec(c, "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, internal_state, " +
                "    shipment_leg, created_at) " +
                "VALUES (?, ?, ?, 'bosta', ?, ?::shipment_internal_state, 'return', ?::timestamptz)",
                legId, tenantId, orderId, String.valueOf(Math.abs(legId.getMostSignificantBits()) % 9_000_000_000L + 1_000_000_000L),
                state, createdAt);
        return legId;
    }

    private static String seedPiece(Connection c, UUID tenantId, UUID variantId, String id) throws Exception {
        exec(c, "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                "VALUES (?, ?, ?, ?, ?, 'return_pending_inspection'::piece_status)",
                id, tenantId, variantId, "PC-" + id, "P" + id.substring(id.length() - 6));
        return id;
    }

    private static void seedEvent(Connection c, UUID tenantId, String pieceId, UUID orderId,
                                  String eventType, String occurredAt) throws Exception {
        exec(c, "INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, from_status, to_status, occurred_at) " +
                "VALUES (?, ?, ?, ?, 'delivered'::piece_status, 'return_pending_inspection'::piece_status, ?::timestamptz)",
                tenantId, pieceId, eventType, orderId, occurredAt);
    }

    private static UUID orderOf(Connection c, UUID legId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT order_id FROM shipments WHERE id = ?")) {
            ps.setObject(1, legId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getObject(1, UUID.class); }
        }
    }

    private static Timestamp intake(Connection c, UUID legId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT return_intake_completed_at FROM shipments WHERE id = ?")) {
            ps.setObject(1, legId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getTimestamp(1); }
        }
    }

    private static void exec(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }
}
