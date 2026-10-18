package com.traceability;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-Bosta couriers G1 — scripts/generic_courier_snapshot.sql, through real psql, the way Marawan runs
 * it around the deploy: once on a database at V167 (before) and once after V168 + V169 (after), same
 * as_of. Bosta-shaped data, no tenant switched on → the two outputs must be identical, and the script
 * must run read-only on both schemas.
 */
@Testcontainers
class GenericCourierSnapshotScriptTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static final String AS_OF = "2026-10-18T12:00:00Z";

    @Test
    void snapshot_beforeAndAfterMigration_identical() throws Exception {
        migrate("167");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
        seedBostaTenant(jdbc, "Femine-like", true);
        seedBostaTenant(jdbc, "Crockera-like", false);

        ExecResult before = psql();
        assertThat(before.getExitCode()).as(before.getStderr()).isZero();

        migrate(null);
        ExecResult after = psql();
        assertThat(after.getExitCode()).as(after.getStderr()).isZero();

        assertThat(before.getStdout()).contains("order|Femine-like|F-1|", "committed|", "stuck|", "tenant|");
        assertThat(before.getStdout()).contains("shipped_elsewhere/Wijha", "linked/Bosta", "pickable=yes", "not_booked_overdue/4", "badge=none");
        assertThat(after.getStdout()).isEqualTo(before.getStdout());
    }

    private static void migrate(String target) {
        var cfg = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres")
            .locations("classpath:db/migration");
        if (target != null) cfg = cfg.target(target);
        assertThat(cfg.load().migrate().success).isTrue();
    }

    private static ExecResult psql() throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath("scripts/generic_courier_snapshot.sql"), "/tmp/snap.sql");
        return POSTGRES.execInContainer("psql", "-U", "postgres", "-d", "traceability_test",
            "-X", "-q", "-A", "-t", "-v", "as_of=" + AS_OF, "-f", "/tmp/snap.sql");
    }

    private static void seedBostaTenant(JdbcTemplate jdbc, String name, boolean bosta) {
        UUID t = UUID.randomUUID(), store = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        String p = name.substring(0, 1);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", t, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            store, t, name.toLowerCase() + ".myshopify.com");
        if (bosta) {
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', 'x', ?, 'active')", t, "wh-" + t);
        }
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, t, store, "gid://shopify/Product/" + p);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'V', ?)",
            variant, t, product, "gid://shopify/ProductVariant/" + p, "SKU-" + p);
        Timestamp placed = Timestamp.from(Instant.parse("2026-10-14T09:00:00Z"));
        String[][] orders = {
            // number, status, forward state (null = none), carrier class, carrier name, fulfillment company
            {p + "-1", "new", "created", "bosta", "Bosta", null},
            {p + "-2", "new", null, "other_known", "Wijha", "Wijha"},
            {p + "-3", "new", null, "unknown", "Other", "Other"},
            {p + "-4", "packed", "with_courier", "bosta", "Bosta", null},
            {p + "-5", "new", null, null, null, null},
        };
        long tn = bosta ? 9_430_000_001L : 9_440_000_001L;
        for (String[] o : orders) {
            UUID id = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at, " +
                "shipping_carrier_class, shipping_carrier_name) VALUES (?, ?, ?, ?, ?::order_status, ?, ?, ?) RETURNING id",
                UUID.class, t, store, "gid://shopify/Order/" + o[0], o[0], o[1], placed, o[3], o[4]);
            jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 2)",
                UUID.randomUUID(), t, id, variant);
            if (o[2] != null) {
                UUID sh = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
                    "internal_state, created_at) VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, ?) RETURNING id",
                    UUID.class, t, id, String.valueOf(tn++), o[2], Timestamp.from(Instant.parse("2026-10-01T09:00:00Z")));
                jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) VALUES (?, ?, ?)",
                    t, sh, o[2]);
            }
            if (o[5] != null) {
                jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, " +
                    "carrier_class, fulfillment_status, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, ?, 'success', ?, ?)",
                    t, id, "WJ-" + o[0], o[5], o[3], placed, placed);
            }
        }
    }
}
