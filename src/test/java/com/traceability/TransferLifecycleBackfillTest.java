package com.traceability;

import com.traceability.inventory.UlidGenerator;
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
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V118 — transfer statuses open | reconciling | closed become preparing | sent | reconciling |
 * closed | cancelled. Seeds every (mode, status, has-scans) shape on the pre-V118 schema, then
 * migrates and checks each row's new status and the new constraints / default.
 */
@Testcontainers
class TransferLifecycleBackfillTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    void v118Backfill_mapsEveryExistingShape_andEnforcesNewStatuses() throws Exception {
        assertThat(Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("116").load().migrate().success).isTrue();

        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), store = UUID.randomUUID();
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        UUID main = UUID.randomUUID(), away = UUID.randomUUID();
        UUID rtEmpty, rtScanned, rrEmpty, rrScanned, roEmpty, roScanned, reconciling, closed;
        try (Connection c = conn()) {
            exec(c, "INSERT INTO tenants (id, name) VALUES (?, 'TL')", tenant);
            exec(c, "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'U', 'u@tl-backfill.test', 'h', 'owner')", user, tenant);
            exec(c, "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'tl.myshopify.com', 'disconnected')", store, tenant);
            exec(c, "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-TL', 'W', 'active')", product, tenant, store);
            exec(c, "INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'V-TL', 'W')", variant, tenant, product);
            exec(c, "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                    "VALUES (?, ?, 'Main', 'warehouse', true, true)", main, tenant);
            exec(c, "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                    "VALUES (?, ?, 'Away', 'showroom', false, false)", away, tenant);

            rtEmpty     = transfer(c, tenant, user, "round_trip",      "open",        away, null);
            rtScanned   = transfer(c, tenant, user, "round_trip",      "open",        away, null);
            rrEmpty     = transfer(c, tenant, user, "relocate_return", "open",        main, away);
            rrScanned   = transfer(c, tenant, user, "relocate_return", "open",        main, away);
            roEmpty     = transfer(c, tenant, user, "relocate_out",    "open",        away, null);
            roScanned   = transfer(c, tenant, user, "relocate_out",    "open",        away, null);
            reconciling = transfer(c, tenant, user, "round_trip",      "reconciling", away, null);
            closed      = transfer(c, tenant, user, "round_trip",      "closed",      away, null);
            for (UUID t : new UUID[]{rtScanned, rrScanned, roScanned, reconciling, closed}) {
                scanned(c, tenant, variant, t);
            }
        }

        MigrateResult r = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("118").load().migrate();
        assertThat(r.success).isTrue();
        assertThat(r.migrationsExecuted).as("V118 only").isEqualTo(1);

        try (Connection c = conn()) {
            assertThat(status(c, rtEmpty)).as("open round trip, nothing scanned").isEqualTo("preparing");
            assertThat(status(c, rtScanned)).as("open round trip with pieces out").isEqualTo("sent");
            assertThat(status(c, rrEmpty)).as("open bring back, nothing scanned").isEqualTo("preparing");
            assertThat(status(c, rrScanned)).as("open bring back with pieces out").isEqualTo("sent");
            assertThat(status(c, roEmpty)).as("open move, nothing scanned").isEqualTo("preparing");
            assertThat(status(c, roScanned)).as("open move with pieces — a move has no sent state").isEqualTo("preparing");
            assertThat(status(c, reconciling)).isEqualTo("reconciling");
            assertThat(status(c, closed)).isEqualTo("closed");

            // No backfilled row gets invented timestamps.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM transfers WHERE tenant_id = ? AND (sent_at IS NOT NULL OR sent_by IS NOT NULL " +
                    "OR cancelled_at IS NOT NULL OR reconcile_started_at IS NOT NULL)")) {
                ps.setObject(1, tenant);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); assertThat(rs.getInt(1)).isZero(); }
            }

            // New default, and the old / invalid values are gone.
            UUID fresh = UUID.randomUUID();
            exec(c, "INSERT INTO transfers (id, tenant_id, transfer_type, destination_location_id, created_by) " +
                    "VALUES (?, ?, 'showroom', ?, ?)", fresh, tenant, away, user);
            assertThat(status(c, fresh)).isEqualTo("preparing");
            assertThatThrownBy(() -> exec(c, "UPDATE transfers SET status = 'open' WHERE id = ?", fresh))
                    .isInstanceOf(SQLException.class).hasMessageContaining("transfers_status_check");
            assertThatThrownBy(() -> exec(c, "UPDATE transfers SET status = 'cancelled' WHERE id = ?", fresh))
                    .isInstanceOf(SQLException.class).hasMessageContaining("transfers_cancelled_has_timestamp");
            exec(c, "UPDATE transfers SET status = 'cancelled', cancelled_at = now(), cancelled_by = ? WHERE id = ?",
                    user, fresh);
            assertThat(status(c, fresh)).isEqualTo("cancelled");
        }
    }

    private static UUID transfer(Connection c, UUID tenant, UUID user, String mode, String status,
                                 UUID destination, UUID source) throws Exception {
        UUID id = UUID.randomUUID();
        exec(c, "INSERT INTO transfers (id, tenant_id, transfer_type, transfer_mode, destination_location_id, " +
                "    source_location_id, status, created_by) VALUES (?, ?, 'other', ?, ?, ?, ?, ?)",
                id, tenant, mode, destination, source, status, user);
        return id;
    }

    /** One scanned piece on the transfer: a line + a transfer_pieces row (outcome NULL). */
    private static void scanned(Connection c, UUID tenant, UUID variant, UUID transfer) throws Exception {
        String piece = UlidGenerator.generate();
        exec(c, "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'out_on_transfer'::piece_status)",
                piece, tenant, variant, "PC-" + piece, piece);
        UUID line = UUID.randomUUID();
        exec(c, "INSERT INTO transfer_lines (id, tenant_id, transfer_id, variant_id, qty_out) VALUES (?, ?, ?, ?, 1)",
                line, tenant, transfer, variant);
        exec(c, "INSERT INTO transfer_pieces (id, tenant_id, transfer_id, line_id, piece_id) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), tenant, transfer, line, piece);
    }

    private static String status(Connection c, UUID id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT status FROM transfers WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
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
