package com.traceability;

import com.traceability.inventory.UlidGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-Bosta couriers G4b — scripts/generic_courier_snapshot.sql section 6 (replacement orders, their dispatch
 * claims), through real psql:
 *   1. A Bosta tenant with a type-30 exchange's internal order and its exchange_dispatch claim, once at V179 (before
 *      G4b) and once after V180, same as_of → identical output; no replacement_* line (a Bosta replacement is
 *      'internal:exchange:<AWB>').
 *   2. A generic exchange after V180 → replacement_orders/<status>/<leg> and replacement_dispatch/<status> lines; the
 *      Bosta tenant's lines unchanged by it.
 */
@Testcontainers
class GenericG4bSnapshotScriptTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static final String AS_OF = "2026-10-22T12:00:00Z";

    @Test
    void s1_bostaExchange_beforeAndAfterV180_identical_s2_genericReplacementLines() throws Exception {
        migrate("179");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
        UUID b = tenant(jdbc, "Bosta-like");
        UUID bOrder = order(jdbc, b, "internal:exchange:5100000001", "EXC-5100000001", "awaiting_pickup");
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_order_id, raw) " +
            "VALUES (?, '5100000001', 'mapped', ?, '{}'::jsonb)", b, bOrder);
        leg(jdbc, b, bOrder, "bosta", "with_courier", "5100000001", null);
        claim(jdbc, b, piece(jdbc, b, bOrder));

        ExecResult before = psql();
        assertThat(before.getExitCode()).as(before.getStderr()).isZero();
        FlywayTestSupport.configure().dataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres")
            .locations("classpath:db/migration").load().migrate();
        ExecResult after = psql();
        assertThat(after.getExitCode()).as(after.getStderr()).isZero();
        assertThat(after.getStdout()).isEqualTo(before.getStdout());
        assertThat(after.getStdout()).doesNotContain("replacement_");

        UUID g = tenant(jdbc, "Generic-like");
        UUID original = order(jdbc, g, "gid://shopify/Order/" + g, "#G1", "delivered");
        UUID shipped = order(jdbc, g, "internal:exchange:rr:" + UUID.randomUUID(), "EXC-RR-AAAAAA", "awaiting_pickup");
        leg(jdbc, g, shipped, "generic", "with_courier", "AR-OUT-1", "AROUT1");
        claim(jdbc, g, piece(jdbc, g, shipped));
        UUID waiting = order(jdbc, g, "internal:exchange:rr:" + UUID.randomUUID(), "EXC-RR-BBBBBB", "new");
        jdbc.update("INSERT INTO return_requests (tenant_id, order_id, type, status, reference, booking_status, replacement_order_id, " +
            "    created_at) VALUES (?, ?, 'exchange', 'approved', 'RR-BBBBBB', 'manual', ?, '2026-10-21T09:00:00Z')", g, original, waiting);

        ExecResult withGeneric = psql();
        assertThat(withGeneric.getExitCode()).as(withGeneric.getStderr()).isZero();
        String out = withGeneric.getStdout();
        assertThat(out).contains(
            "g4|Generic-like|" + g + "|replacement_orders/awaiting_pickup/with_courier|1",
            "g4|Generic-like|" + g + "|replacement_orders/new/-|1",
            "g4|Generic-like|" + g + "|replacement_dispatch/applied|1",
            "g4|Generic-like|" + g + "|requests/exchange/manual|1");
        assertThat(out.lines().filter(l -> l.contains("|Bosta-like|")).toList())
            .isEqualTo(after.getStdout().lines().filter(l -> l.contains("|Bosta-like|")).toList());
    }

    private static UUID tenant(JdbcTemplate jdbc, String name) {
        UUID t = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", t, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (gen_random_uuid(), ?, 'shopify', ?, 'connected')",
            t, name.toLowerCase() + ".myshopify.com");
        jdbc.update("INSERT INTO locations (tenant_id, name, is_fulfillment) VALUES (?, 'Main', true)", t);
        UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
            "SELECT ?, id, ?, 'P', 'active' FROM stores WHERE tenant_id = ? RETURNING id", UUID.class, t, "gid://shopify/Product/" + t, t);
        jdbc.update("INSERT INTO variants (tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'V', 'SKU')",
            t, product, "gid://shopify/ProductVariant/" + t);
        return t;
    }

    private static UUID order(JdbcTemplate jdbc, UUID t, String externalId, String number, String status) {
        return jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at, created_at) " +
            "SELECT ?, id, ?, ?, ?::order_status, '2026-10-20T09:00:00Z', '2026-10-20T09:00:00Z' FROM stores WHERE tenant_id = ? RETURNING id",
            UUID.class, t, externalId, number, status, t);
    }

    private static void leg(JdbcTemplate jdbc, UUID t, UUID order, String provider, String state, String tn, String key) {
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, internal_state, shipment_leg, " +
            "    created_at) VALUES (?, ?, ?::courier_provider, ?, ?, ?::shipment_internal_state, 'forward', '2026-10-20T10:00:00Z')",
            t, order, provider, tn, key, state);
    }

    private static String piece(JdbcTemplate jdbc, UUID t, UUID order) {
        String id = UlidGenerator.generate();
        UUID variant = jdbc.queryForObject("SELECT id FROM variants WHERE tenant_id = ?", UUID.class, t);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id) " +
            "VALUES (?, ?, ?, ?, ?, 'with_courier', ?)", id, t, variant, "PC-" + id, "P" + id.substring(20), order);
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, t, order, variant);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')", t, item, id);
        return id;
    }

    private static void claim(JdbcTemplate jdbc, UUID t, String piece) {
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, trigger_type, " +
            "    trigger_id, status, created_at) " +
            "SELECT ?, gen_random_uuid(), p.variant_id, l.id, -1, 'exchange_dispatch', ?, 'applied', '2026-10-20T11:00:00Z' " +
            "FROM pieces p JOIN locations l ON l.tenant_id = p.tenant_id WHERE p.id = ?", t, piece, piece);
    }

    private static void migrate(String target) {
        assertThat(FlywayTestSupport.configure().dataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres")
            .locations("classpath:db/migration").target(target).load().migrate().success).isTrue();
    }

    private static ExecResult psql() throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath("scripts/generic_courier_snapshot.sql"), "/tmp/snap.sql");
        return POSTGRES.execInContainer("psql", "-U", "postgres", "-d", "traceability_test",
            "-X", "-q", "-A", "-t", "-v", "as_of=" + AS_OF, "-f", "/tmp/snap.sql");
    }
}
