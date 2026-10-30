package com.traceability;

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
 * Non-Bosta couriers G4a — scripts/generic_courier_snapshot.sql section 6, through real psql:
 *   1. A Bosta tenant with return requests, once at V176 (before G4a) and once after V177, same as_of → identical
 *      output, with its g4 request lines.
 *   2. A switched-on tenant after V177 → its own lines: the switch, the generic order a customer could return now
 *      (not the one still with the courier, not one delivered outside the window), its 'manual' request and its
 *      portal-request return legs by state; the Bosta tenant's lines unchanged by it.
 */
@Testcontainers
class GenericG4aSnapshotScriptTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static final String AS_OF = "2026-10-21T12:00:00Z";

    @Test
    void s1_bostaOnly_beforeAndAfterV177_identical_s2_switchedOnTenantLines() throws Exception {
        migrate("176");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
        UUID b = tenant(jdbc, "Bosta-like");
        UUID bOrder = order(jdbc, b, "#B1");
        leg(jdbc, b, bOrder, "bosta", "forward", "delivered", "5100000001", null, "'2026-10-19T12:00:00Z'");
        request(jdbc, b, bOrder, "RR-BBBBBB", "pickup_booked", "booked");
        request(jdbc, b, order(jdbc, b, "#B2"), "RR-CCCCCC", "requested", null);

        ExecResult before = psql();
        assertThat(before.getExitCode()).as(before.getStderr()).isZero();
        migrate(null);
        ExecResult after = psql();
        assertThat(after.getExitCode()).as(after.getStderr()).isZero();
        assertThat(before.getStdout()).contains(
            "g4|Bosta-like|" + b + "|requests/refund/booked|1", "g4|Bosta-like|" + b + "|requests/refund/-|1");
        assertThat(after.getStdout()).isEqualTo(before.getStdout());

        // 2. a switched-on tenant (V177 schema)
        UUID g = tenant(jdbc, "Generic-like");
        jdbc.update("UPDATE tenants SET generic_courier_enabled = true, portal_generic_returns_enabled = true, " +
            "portal_generic_returns_since = '2026-10-20T00:00:00Z' WHERE id = ?", g);
        UUID eligible = order(jdbc, g, "#G1");
        leg(jdbc, g, eligible, "generic", "forward", "delivered", "AR-1", "AR1", "'2026-10-19T12:00:00Z'");
        leg(jdbc, g, order(jdbc, g, "#G2"), "generic", "forward", "with_courier", "AR-2", "AR2", "NULL");
        leg(jdbc, g, order(jdbc, g, "#G3"), "generic", "forward", "delivered", "AR-3", "AR3", "'2026-08-01T12:00:00Z'");
        UUID rr = request(jdbc, g, eligible, "RR-DDDDDD", "pickup_booked", "manual");
        UUID ret = leg(jdbc, g, eligible, "generic", "return", "created", "RET-1", "RET1", "NULL");
        jdbc.update("UPDATE return_requests SET return_shipment_id = ?, link_source = 'merchant_entered', " +
            "manual_carrier_name = 'Aramex' WHERE id = ?", ret, rr);
        leg(jdbc, g, eligible, "generic", "return", "returned", "RET-2", "RET2", "NULL");

        ExecResult withGeneric = psql();
        assertThat(withGeneric.getExitCode()).as(withGeneric.getStderr()).isZero();
        String out = withGeneric.getStdout();
        assertThat(out).contains(
            "g4|Generic-like|" + g + "|switch/portal_generic_returns|1",
            "g4|Generic-like|" + g + "|portal_eligible_generic|1",
            "g4|Generic-like|" + g + "|requests/refund/manual|1",
            "g4|Generic-like|" + g + "|generic_return_legs/created|1",
            "g4|Generic-like|" + g + "|generic_return_legs/returned|1");
        assertThat(out.lines().filter(l -> l.contains("|Bosta-like|")).toList())
            .isEqualTo(after.getStdout().lines().filter(l -> l.contains("|Bosta-like|")).toList());
        assertThat(out).doesNotContain("g4|Bosta-like|" + b + "|switch/");
    }

    private static UUID store;

    private static UUID tenant(JdbcTemplate jdbc, String name) {
        UUID t = UUID.randomUUID();
        store = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", t, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            store, t, name.toLowerCase() + ".myshopify.com");
        return t;
    }

    private static UUID order(JdbcTemplate jdbc, UUID t, String number) {
        UUID s = jdbc.queryForObject("SELECT id FROM stores WHERE tenant_id = ?", UUID.class, t);
        return jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, '2026-10-10T09:00:00Z') RETURNING id",
            UUID.class, t, s, "gid://shopify/Order/" + t + number, number);
    }

    private static UUID leg(JdbcTemplate jdbc, UUID t, UUID order, String provider, String leg, String state, String tn,
                            String key, String deliveredAtSql) {
        return jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, " +
            "    internal_state, shipment_leg, delivered_at, created_at) " +
            "VALUES (?, ?, ?::courier_provider, ?, ?, ?::shipment_internal_state, ?, " + deliveredAtSql + ", " +
            "    '2026-10-12T09:00:00Z') RETURNING id", UUID.class, t, order, provider, tn, key, state, leg);
    }

    private static UUID request(JdbcTemplate jdbc, UUID t, UUID order, String ref, String status, String booking) {
        return jdbc.queryForObject("INSERT INTO return_requests (tenant_id, order_id, status, reference, booking_status, created_at) " +
            "VALUES (?, ?, ?::return_request_status, ?, ?, '2026-10-20T09:00:00Z') RETURNING id",
            UUID.class, t, order, status, ref, booking);
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
