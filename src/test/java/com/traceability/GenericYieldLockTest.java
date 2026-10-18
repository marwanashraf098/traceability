package com.traceability;

import com.traceability.courier.GenericShipments;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V169 — the Bosta-wins locked read (GenericShipments.YIELD_SQL), which now runs before every Bosta
 * forward-shipment INSERT for every tenant:
 *   l1 index-backed: on a few thousand shipments the plan reaches the order through an order_id index,
 *      never a sequential scan of shipments;
 *   l2 an order with no generic leg locks nothing and never waits — not even while another transaction
 *      holds that order's Bosta row (lock_timeout 300 ms would fire);
 *   l3 an order WITH a generic leg locks only that order's generic row — other orders' rows (generic or
 *      Bosta) and the same order's Bosta row stay lockable (NOWAIT).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericYieldLockTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    JdbcTemplate jdbc;
    UUID tenant;
    UUID bostaOnlyOrder, genericOrder, otherGenericOrder;
    UUID bostaRowOfGenericOrder;

    @BeforeAll
    void setup() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres")
            .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
        tenant = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, generic_courier_enabled) VALUES (?, 'lock', true)", tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'lock.myshopify.com', 'connected')",
            store, tenant);
        // 3000 Bosta orders, each with a forward leg — enough that a sequential scan would be the planner's choice
        // if no order_id index applied.
        jdbc.update("WITH o AS (INSERT INTO orders (tenant_id, store_id, external_id, number, status) " +
            "  SELECT ?, ?, 'gid://shopify/Order/' || g, 'L-' || g, 'new' FROM generate_series(1, 3000) g RETURNING id, number) " +
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state) " +
            "SELECT ?, o.id, 'bosta', (9000000000 + substr(o.number, 3)::bigint)::text, 'created' FROM o",
            tenant, store, tenant);
        bostaOnlyOrder = jdbc.queryForObject("SELECT id FROM orders WHERE number = 'L-1'", UUID.class);
        genericOrder = order(store, "G-1");
        otherGenericOrder = order(store, "G-2");
        generic(genericOrder, "GK1");
        generic(otherGenericOrder, "GK2");
        // A cancelled Bosta leg on the generic order: the same order's non-generic row must stay unlocked.
        bostaRowOfGenericOrder = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "internal_state) VALUES (?, ?, 'bosta', '9900000001', 'cancelled') RETURNING id", UUID.class, tenant, genericOrder);
        jdbc.execute("ANALYZE shipments");
        jdbc.execute("ANALYZE orders");
        jdbc.execute("ANALYZE allocations");
        jdbc.execute("ANALYZE order_items");
    }

    @Test
    void l1_indexBacked_neverSeqScanOfShipments() {
        String sql = GenericShipments.YIELD_SQL.replaceFirst("\\?", "'" + tenant + "'").replaceFirst("\\?", "'" + genericOrder + "'");
        List<String> plan = jdbc.queryForList("EXPLAIN " + sql, String.class);
        String text = String.join("\n", plan);
        System.out.println("YIELD_SQL plan:\n" + text);
        assertThat(text).containsAnyOf("Index Scan using shipments_order_idx", "Index Scan using ux_active_forward_shipment_per_order",
            "Bitmap Index Scan on shipments_order_idx", "Bitmap Index Scan on ux_active_forward_shipment_per_order");
        assertThat(text).doesNotContain("Seq Scan on shipments");
    }

    @Test
    void l2_noGenericLeg_locksNothing_neverWaits() throws Exception {
        try (Connection holder = conn(); Connection linker = conn()) {
            holder.setAutoCommit(false);
            // Another transaction holds the order's Bosta row (e.g. a concurrent Bosta webhook UPDATE).
            holder.createStatement().execute("SELECT 1 FROM shipments WHERE order_id = '" + bostaOnlyOrder + "' FOR UPDATE");

            linker.setAutoCommit(false);
            linker.createStatement().execute("SET LOCAL lock_timeout = '300ms'");
            JdbcTemplate lj = new JdbcTemplate(new SingleConnectionDataSource(linker, true));
            long t0 = System.nanoTime();
            assertThat(GenericShipments.yieldToBosta(lj, tenant, bostaOnlyOrder)).isEqualTo(GenericShipments.BostaLink.NO_GENERIC);
            assertThat((System.nanoTime() - t0) / 1_000_000).as("ms — no wait on the held Bosta row").isLessThan(300);
            linker.rollback();
            holder.rollback();
        }
    }

    @Test
    void l3_genericLeg_locksOnlyThatOrdersGenericRow() throws Exception {
        try (Connection linker = conn(); Connection probe = conn()) {
            linker.setAutoCommit(false);
            JdbcTemplate lj = new JdbcTemplate(new SingleConnectionDataSource(linker, true));
            assertThat(lj.queryForList(GenericShipments.YIELD_SQL, tenant, genericOrder)).hasSize(1);   // takes the lock

            probe.setAutoCommit(false);
            JdbcTemplate pj = new JdbcTemplate(new SingleConnectionDataSource(probe, true));
            // That order's generic row: locked.
            assertThatThrownBy(() -> pj.queryForList("SELECT id FROM shipments WHERE order_id = ? AND provider = 'generic' " +
                "FOR UPDATE NOWAIT", genericOrder)).hasMessageContaining("could not obtain lock");
            probe.rollback();
            // Everything else: free.
            assertThat(pj.queryForList("SELECT id FROM shipments WHERE id = ? FOR UPDATE NOWAIT", bostaRowOfGenericOrder)).hasSize(1);
            assertThat(pj.queryForList("SELECT id FROM shipments WHERE order_id = ? FOR UPDATE NOWAIT", otherGenericOrder)).hasSize(1);
            assertThat(pj.queryForList("SELECT id FROM shipments WHERE order_id = ? FOR UPDATE NOWAIT", bostaOnlyOrder)).hasSize(1);
            assertThat(pj.queryForList("SELECT id FROM orders WHERE id = ? FOR UPDATE NOWAIT", genericOrder))
                .as("the order row itself is not locked").hasSize(1);
            probe.rollback();
            linker.rollback();
        }
    }

    private UUID order(UUID store, String number) {
        return jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status) " +
            "VALUES (?, ?, ?, ?, 'new') RETURNING id", UUID.class, tenant, store, "gid://shopify/Order/" + number, number);
    }

    private void generic(UUID order, String key) {
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, internal_state, source) " +
            "VALUES (?, ?, 'generic', ?, ?, 'created', 'shopify_fulfillment')", tenant, order, key, key);
    }

    private static Connection conn() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
    }
}
