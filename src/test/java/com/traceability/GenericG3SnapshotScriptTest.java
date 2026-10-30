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
 * Non-Bosta couriers G3 — scripts/generic_courier_snapshot.sql section 5 (and section 1's "Delivered · carrier"),
 * through real psql:
 *   1. Bosta tenants only, once at V171 (before G3) and once after V173, same as_of → identical output, and the
 *      g3 lines are there (pickup scans, handovers, delivered, returned, return scans, portal eligible).
 *   2. A switched-on tenant's generic legs after V173 → their own lines (generic / source), and its delivered
 *      generic order is not counted portal eligible while its Bosta order is.
 */
@Testcontainers
class GenericG3SnapshotScriptTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static final String AS_OF = "2026-10-18T12:00:00Z";
    static final Timestamp DAY_AGO = Timestamp.from(Instant.parse("2026-10-17T12:00:00Z"));

    @Test
    void s1_bostaOnly_beforeAndAfterV173_identical_s2_genericTenantLines() throws Exception {
        migrate("171");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
        seed(jdbc, "Bosta-like", false);

        ExecResult before = psql();
        assertThat(before.getExitCode()).as(before.getStderr()).isZero();
        migrate(null);
        ExecResult after = psql();
        assertThat(after.getExitCode()).as(after.getStderr()).isZero();

        assertThat(before.getStdout()).contains(
            "|pickup_open_scans/bosta|1", "|handed_over/bosta/-|1", "|delivered/bosta/-|2", "|returned/bosta|1",
            "|return_scans/bosta|1", "|portal_eligible|2");
        assertThat(after.getStdout()).isEqualTo(before.getStdout());

        // 2. a switched-on tenant (V173 schema)
        UUID g = seed(jdbc, "Generic-like", true);
        ExecResult withGeneric = psql();
        assertThat(withGeneric.getExitCode()).as(withGeneric.getStderr()).isZero();
        String out = withGeneric.getStdout();
        assertThat(out).contains(
            "g3|Generic-like|" + g + "|pickup_open_scans/bosta|1",
            "g3|Generic-like|" + g + "|pickup_open_scans/generic|1",
            "g3|Generic-like|" + g + "|handed_over/generic/pickup_scan|1",
            "g3|Generic-like|" + g + "|delivered/generic/shopify|1",
            "g3|Generic-like|" + g + "|returned/generic|1",
            "g3|Generic-like|" + g + "|return_scans/generic|1",
            "g3|Generic-like|" + g + "|portal_eligible|2");      // the two Bosta orders; the generic one isn't
        assertThat(out).contains("status.delivered_with_carrier");
        // the Bosta tenant's lines are untouched by the other tenant's generic data
        assertThat(out.lines().filter(l -> l.contains("|Bosta-like|")).toList())
            .isEqualTo(after.getStdout().lines().filter(l -> l.contains("|Bosta-like|")).toList());
    }

    private static UUID variant;

    /** A tenant: Bosta orders (open pickup scan, handed over, delivered ×2, returned + scanned back); generic ones too when asked. */
    private static UUID seed(JdbcTemplate jdbc, String name, boolean generic) {
        UUID t = UUID.randomUUID(), store = UUID.randomUUID(), user = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", t, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            store, t, name.toLowerCase() + ".myshopify.com");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'W', ?, 'x', 'worker')",
            user, t, "w-" + user + "@t.local");
        UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'P', 'active') RETURNING id", UUID.class, t, store, "gid://shopify/Product/" + t);
        variant = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'V', 'SKU') RETURNING id", UUID.class, t, product, "gid://shopify/ProductVariant/" + t);
        long tn = generic ? 9_460_000_001L : 9_450_000_001L;

        UUID pickupOpen = jdbc.queryForObject("INSERT INTO pickups (tenant_id, session_status, scheduled_date, opened_by_user_id) " +
            "VALUES (?, 'open', '2026-10-18', ?) RETURNING id", UUID.class, t, user);
        UUID packed = leg(jdbc, t, store, name + "-1", "packed", "bosta", String.valueOf(tn++), "created", null);
        jdbc.update("INSERT INTO pickup_shipments (pickup_id, shipment_id, tenant_id, scanned_by_user_id) VALUES (?, ?, ?, ?)",
            pickupOpen, packed, t, user);

        UUID handed = leg(jdbc, t, store, name + "-2", "packed", "bosta", String.valueOf(tn++), "with_courier", null);
        history(jdbc, t, handed, "with_courier", null);
        UUID d1 = leg(jdbc, t, store, name + "-3", "packed", "bosta", String.valueOf(tn++), "delivered", DAY_AGO);
        history(jdbc, t, d1, "delivered", null);
        UUID d2 = leg(jdbc, t, store, name + "-4", "packed", "bosta", String.valueOf(tn++), "delivered", DAY_AGO);
        history(jdbc, t, d2, "delivered", null);
        String rtoTn = String.valueOf(tn++);
        UUID rto = leg(jdbc, t, store, name + "-5", "packed", "bosta", rtoTn, "returned", null);
        jdbc.update("UPDATE shipments SET returned_at = ? WHERE id = ?", DAY_AGO, rto);
        UUID session = jdbc.queryForObject("INSERT INTO return_sessions (tenant_id, opened_by) VALUES (?, ?) RETURNING id",
            UUID.class, t, user);
        jdbc.update("INSERT INTO return_session_shipments (tenant_id, session_id, awb, linked_at) VALUES (?, ?, ?, ?)",
            t, session, rtoTn, DAY_AGO);

        if (generic) {
            jdbc.update("UPDATE tenants SET generic_courier_enabled = true WHERE id = ?", t);
            UUID gOpen = leg(jdbc, t, store, name + "-G1", "packed", "generic", "AR-1001", "created", null);
            UUID pickup2 = jdbc.queryForObject("INSERT INTO pickups (tenant_id, session_status, scheduled_date, opened_by_user_id) " +
                "VALUES (?, 'open', '2026-10-18', ?) RETURNING id", UUID.class, t, user);
            jdbc.update("INSERT INTO pickup_shipments (pickup_id, shipment_id, tenant_id, scanned_by_user_id) VALUES (?, ?, ?, ?)",
                pickup2, gOpen, t, user);
            UUID gDelivered = leg(jdbc, t, store, name + "-G2", "packed", "generic", "AR-1002", "delivered", DAY_AGO);
            history(jdbc, t, gDelivered, "with_courier", "pickup_scan");
            history(jdbc, t, gDelivered, "delivered", "shopify");
            UUID gReturned = leg(jdbc, t, store, name + "-G3", "packed", "generic", "AR-1003", "returned", null);
            jdbc.update("UPDATE shipments SET returned_at = ? WHERE id = ?", DAY_AGO, gReturned);
            jdbc.update("INSERT INTO return_session_shipments (tenant_id, session_id, awb, linked_at, shipment_id) VALUES (?, ?, ?, ?, ?)",
                t, session, "AR-1003", DAY_AGO, gReturned);
        }
        return t;
    }

    private static UUID leg(JdbcTemplate jdbc, UUID t, UUID store, String number, String status, String provider,
                            String tn, String state, Timestamp deliveredAt) {
        UUID order = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, ?::order_status, '2026-10-10T09:00:00Z') RETURNING id",
            UUID.class, t, store, "gid://shopify/Order/" + number, number, status);
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)", t, order, variant);
        return jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, internal_state, " +
            "delivered_at, created_at) VALUES (?, ?, ?::courier_provider, ?, ?, ?::shipment_internal_state, ?, '2026-10-12T09:00:00Z') RETURNING id",
            UUID.class, t, order, provider, tn, "generic".equals(provider) ? tn.replace("-", "") : null, state, deliveredAt);
    }

    private static void history(JdbcTemplate jdbc, UUID t, UUID shipment, String state, String source) {
        if (source == null) {
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) VALUES (?, ?, ?, ?)",
                t, shipment, state, DAY_AGO);
        } else {
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at, source) VALUES (?, ?, ?, ?, ?)",
                t, shipment, state, DAY_AGO, source);
        }
    }

    private static void migrate(String target) {
        var cfg = FlywayTestSupport.configure().dataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres")
            .locations("classpath:db/migration");
        if (target != null) cfg = cfg.target(target);
        assertThat(cfg.load().migrate().success).isTrue();
    }

    private static ExecResult psql() throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath("scripts/generic_courier_snapshot.sql"), "/tmp/snap.sql");
        return POSTGRES.execInContainer("psql", "-U", "postgres", "-d", "traceability_test",
            "-X", "-q", "-A", "-t", "-v", "as_of=" + AS_OF, "-f", "/tmp/snap.sql");
    }
}
