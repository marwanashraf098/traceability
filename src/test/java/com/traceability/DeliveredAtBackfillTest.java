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
 * V100 — shipments.delivered_at backfill, forward legs only, earliest source:
 * the first 'delivered' shipment_status_history row; else the first piece_event
 * to_status='delivered' for a piece of that order at/after the shipment's created_at.
 */
@Testcontainers
class DeliveredAtBackfillTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    void v100_backfillsForwardLegsFromEarliestSource() throws Exception {
        MigrateResult r1 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("99").load().migrate();
        assertThat(r1.success).isTrue();

        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        UUID fromHistory, fromEvents, none, returnLeg;
        try (Connection c = conn()) {
            exec(c, "INSERT INTO tenants (id, name) VALUES (?, 'DA')", tenant);
            exec(c, "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'da.myshopify.com', 'disconnected')", store, tenant);
            exec(c, "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'P', 'T', 'active')", product, tenant, store);
            exec(c, "INSERT INTO variants (id, tenant_id, product_id, external_id, title) VALUES (?, ?, ?, 'V', 'V')", variant, tenant, product);

            // History wins over piece events, and the EARLIEST history row is used.
            UUID o1 = order(c, tenant, store);
            fromHistory = shipment(c, tenant, o1, "forward", "2026-09-01 08:00:00");
            history(c, tenant, fromHistory, "delivered", "2026-09-05 12:00:00");
            history(c, tenant, fromHistory, "delivered", "2026-09-03 12:00:00");
            history(c, tenant, fromHistory, "with_courier", "2026-09-02 12:00:00");
            pieceEvent(c, tenant, variant, o1, "2026-09-02 09:00:00");

            // No history → earliest delivered piece event at/after created_at (earlier one ignored).
            UUID o2 = order(c, tenant, store);
            fromEvents = shipment(c, tenant, o2, "forward", "2026-09-10 08:00:00");
            pieceEvent(c, tenant, variant, o2, "2026-09-09 09:00:00");   // before the shipment existed
            pieceEvent(c, tenant, variant, o2, "2026-09-12 09:00:00");
            pieceEvent(c, tenant, variant, o2, "2026-09-11 09:00:00");

            UUID o3 = order(c, tenant, store);
            none = shipment(c, tenant, o3, "forward", "2026-09-10 08:00:00");

            UUID o4 = order(c, tenant, store);
            returnLeg = shipment(c, tenant, o4, "return", "2026-09-10 08:00:00");
            history(c, tenant, returnLeg, "delivered", "2026-09-11 12:00:00");
        }

        MigrateResult r2 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("100").load().migrate();
        assertThat(r2.success).isTrue();
        assertThat(r2.migrationsExecuted).as("V100 only").isEqualTo(1);

        try (Connection c = conn()) {
            assertThat(deliveredAt(c, fromHistory)).isEqualTo(Timestamp.valueOf("2026-09-03 12:00:00"));
            assertThat(deliveredAt(c, fromEvents)).isEqualTo(Timestamp.valueOf("2026-09-11 09:00:00"));
            assertThat(deliveredAt(c, none)).isNull();
            assertThat(deliveredAt(c, returnLeg)).as("return legs are never backfilled").isNull();
        }
    }

    private static UUID order(Connection c, UUID tenant, UUID store) throws Exception {
        UUID id = UUID.randomUUID();
        exec(c, "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
                "VALUES (?, ?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now())",
                id, tenant, store, "gid://shopify/Order/" + id, "#" + id.toString().substring(0, 5));
        return id;
    }

    private static UUID shipment(Connection c, UUID tenant, UUID order, String leg, String createdAt) throws Exception {
        UUID id = UUID.randomUUID();
        exec(c, "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, created_at) " +
                "VALUES (?, ?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, ?, ?::timestamptz)",
                id, tenant, order, String.valueOf(1_000_000_000L + Math.abs(id.getMostSignificantBits() % 8_000_000_000L)),
                leg, createdAt);
        return id;
    }

    private static void history(Connection c, UUID tenant, UUID shipment, String state, String at) throws Exception {
        exec(c, "INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, provider_state, occurred_at) " +
                "VALUES (?, ?, ?, 45, ?::timestamptz)", tenant, shipment, state, at);
    }

    private static void pieceEvent(Connection c, UUID tenant, UUID variant, UUID order, String at) throws Exception {
        String piece = "DA" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        exec(c, "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id) " +
                "VALUES (?, ?, ?, ?, ?, 'delivered'::piece_status, ?)",
                piece, tenant, variant, "PC-" + piece, "P" + piece.substring(2, 8), order);
        exec(c, "INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, from_status, to_status, occurred_at) " +
                "VALUES (?, ?, 'courier_update', ?, 'with_courier', 'delivered', ?::timestamptz)",
                tenant, piece, order, at);
    }

    private static Timestamp deliveredAt(Connection c, UUID shipment) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT delivered_at FROM shipments WHERE id = ?")) {
            ps.setObject(1, shipment);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getTimestamp(1); }
        }
    }

    private Connection conn() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void exec(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }
}
