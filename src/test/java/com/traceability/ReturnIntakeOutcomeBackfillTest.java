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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V101 — already-stamped return legs get outcome 'scanned' (by/session NULL); unstamped rows untouched. */
@Testcontainers
class ReturnIntakeOutcomeBackfillTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    void v101Backfill_stampedRowsBecomeScanned_unstampedUntouched() throws Exception {
        assertThat(Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("100").load().migrate().success).isTrue();

        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID();
        UUID stamped, unstamped;
        try (Connection c = conn()) {
            exec(c, "INSERT INTO tenants (id, name) VALUES (?, 'IO')", tenant);
            exec(c, "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'io.myshopify.com', 'disconnected')", store, tenant);
            stamped   = leg(c, tenant, store, "6136538746", true);
            unstamped = leg(c, tenant, store, "9730639058", false);
        }

        MigrateResult r = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("101").load().migrate();
        assertThat(r.success).isTrue();
        assertThat(r.migrationsExecuted).as("V101 only").isEqualTo(1);

        try (Connection c = conn()) {
            assertThat(read(c, stamped)).isEqualTo(new String[]{"scanned", null, null});
            assertThat(read(c, unstamped)).isEqualTo(new String[]{null, null, null});
        }
    }

    private static UUID leg(Connection c, UUID tenant, UUID store, String awb, boolean stamped) throws Exception {
        UUID order = UUID.randomUUID(), id = UUID.randomUUID();
        exec(c, "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
                "VALUES (?, ?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now())",
                order, tenant, store, "gid://shopify/Order/" + order, "#" + awb);
        exec(c, "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, " +
                "    return_intake_completed_at) " +
                "VALUES (?, ?, ?, 'bosta', ?, 'returned'::shipment_internal_state, 'return', CASE WHEN ? THEN now() END)",
                id, tenant, order, awb, stamped);
        return id;
    }

    private static String[] read(Connection c, UUID id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT return_intake_outcome, return_intake_by::text, return_intake_session_id::text FROM shipments WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}; }
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
