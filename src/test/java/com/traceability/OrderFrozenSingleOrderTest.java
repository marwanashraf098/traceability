package com.traceability;

import com.traceability.inventory.OrderLineFreeze;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Incident 2026-11-25 — the single-order freeze readers (OrderLineFreeze.isFrozen / hasShippedPastCreated /
 * hasLeftCustody) return exactly what the set-based rule (OrderLineFreeze.frozenSql, unchanged — what isFrozen ran
 * before) returns, on a fixture per branch:
 *
 *   c1  the forward leg's history moved past 'created' (with_courier)             → frozen
 *   c2  history only 'created'                                                    → not
 *   c3  cancelled before it ever moved (created → cancelled)                      → not (D7)
 *   c4  terminated after it moved (with_courier → terminated)                     → frozen
 *   c5  a cancelled leg that never moved + a second forward leg that moved        → frozen
 *   c6  only a RETURN leg moved                                                   → not
 *   a1  a packed allocation whose piece is delivered                              → frozen (clause 2)
 *   a2  a packed allocation, piece still 'packed'                                 → not
 *   a3  a released allocation, piece delivered, no event                          → not
 *   e1  piece event into with_courier on this order's forward leg                 → frozen (clause 3)
 *   e2  piece event into delivered, shipment_id NULL (self-pickup handover)        → frozen
 *   e3  s_lw: event on a RETURN leg of this order                                 → not
 *   e4  s_lw: event on ANOTHER order's forward leg                                → not
 *   e5  the event belongs to another order (piece reused)                         → not
 *   e6  return-intake event only (packed → return_pending_inspection)             → not (D1)
 *   w1  portal pre-connect order, otherwise frozen                                → not (not in warehouse_orders)
 *   w2  Shopify-native exchange shadow order, frozen                              → frozen (warehouse order)
 *   w3  an id that is no order                                                    → not
 *   t1  tenant isolation: a frozen order asked with another tenant id             → not
 *   t2  as app_user under RLS: own tenant → frozen, another tenant's context → not
 *   p1  the statement is never server-prepared: after 10 calls on one pooled app_user connection
 *       pg_prepared_statements holds none of them (each call planned with its own values and the current tenant —
 *       a generic plan made under another tenant is what scanned the whole tenant on prod)
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderFrozenSingleOrderTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    JdbcTemplate jdbc;
    HikariDataSource appPool;
    PackFixtures f, other;
    UUID actor, variant;
    final List<Object[]> cases = new ArrayList<>();   // {name, orderId, expectedFrozen}

    @BeforeAll
    void setUp() {
        FlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute("ALTER ROLE app_user WITH LOGIN PASSWORD 'app_user'");
        f = new PackFixtures(jdbc, "freeze-one");
        other = new PackFixtures(jdbc, "freeze-other");
        actor = f.user("Mona", "worker");
        variant = f.variant("Tee", "TEE-1", null);
        build();

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(POSTGRES.getJdbcUrl());
        hc.setUsername("app_user");
        hc.setPassword("app_user");
        hc.setMaximumPoolSize(1);
        appPool = new HikariDataSource(hc);
    }

    @AfterAll
    void tearDown() { if (appPool != null) appPool.close(); }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    private UUID order(String n) { UUID o = f.order(n, 0); return o; }

    private UUID item(UUID order) {
        return jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) " +
            "VALUES (?, ?, ?, 1) RETURNING id", UUID.class, f.tenant, order, variant);
    }

    private UUID leg(UUID order, String leg) {
        return jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "internal_state, shipment_leg) SELECT tenant_id, id, 'bosta', ?, 'created', ? FROM orders WHERE id = ? " +
            "RETURNING id", UUID.class, PackFixtures.nextTracking(), leg, order);
    }

    private void history(UUID shipment, String... states) {
        for (String s : states) {
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) " +
                "SELECT tenant_id, id, ? FROM shipments WHERE id = ?", s, shipment);
        }
    }

    /** A piece of the variant set to {@code status}, allocated to the order's line with {@code alloc}. */
    private String allocated(UUID order, String alloc, String status) {
        String piece = f.pieceId(f.piece(variant, actor));
        jdbc.update("UPDATE pieces SET status = ?::piece_status WHERE id = ?", status, piece);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) " +
            "VALUES (?, ?, ?, ?::allocation_status)", f.tenant, item(order), piece, alloc);
        return piece;
    }

    private void event(String piece, UUID order, UUID shipment, String from, String to) {
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, order_id, shipment_id, " +
            "from_status, to_status) VALUES (?, ?, 'test', ?, ?, ?, ?::piece_status, ?::piece_status)",
            f.tenant, piece, actor, order, shipment, from, to);
    }

    private void expect(String name, UUID order, boolean frozen) { cases.add(new Object[]{name, order, frozen}); }

    private void build() {
        UUID o;
        o = order("c1"); history(leg(o, "forward"), "created", "with_courier"); expect("c1", o, true);
        o = order("c2"); history(leg(o, "forward"), "created", "created"); expect("c2", o, false);
        o = order("c3"); history(leg(o, "forward"), "created", "cancelled"); expect("c3", o, false);
        o = order("c4"); history(leg(o, "forward"), "created", "with_courier", "terminated"); expect("c4", o, true);
        o = order("c5");
        UUID dead = leg(o, "forward"); history(dead, "created", "cancelled");
        jdbc.update("UPDATE shipments SET internal_state = 'cancelled' WHERE id = ?", dead);
        history(leg(o, "forward"), "with_courier"); expect("c5", o, true);
        o = order("c6"); leg(o, "forward"); history(leg(o, "return"), "returning", "returned"); expect("c6", o, false);

        o = order("a1"); allocated(o, "packed", "delivered"); expect("a1", o, true);
        o = order("a2"); allocated(o, "packed", "packed"); expect("a2", o, false);
        o = order("a3"); allocated(o, "released", "delivered"); expect("a3", o, false);

        o = order("e1");
        UUID fwd = leg(o, "forward");
        event(allocated(o, "released", "available"), o, fwd, "packed", "with_courier"); expect("e1", o, true);
        o = order("e2"); event(allocated(o, "released", "available"), o, null, "packed", "delivered"); expect("e2", o, true);
        o = order("e3");
        UUID ret = leg(o, "return");
        event(allocated(o, "released", "available"), o, ret, "with_courier", "delivered"); expect("e3", o, false);
        UUID elsewhere = order("e4-other");
        UUID otherFwd = leg(elsewhere, "forward");
        o = order("e4"); event(allocated(o, "released", "available"), o, otherFwd, "packed", "with_courier");
        expect("e4", o, false);
        o = order("e5"); event(allocated(o, "released", "available"), elsewhere, null, "packed", "delivered");
        expect("e5", o, false);
        o = order("e6");
        UUID e6leg = leg(o, "forward");
        event(allocated(o, "released", "available"), o, e6leg, "packed", "return_pending_inspection");
        expect("e6", o, false);

        o = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, origin, shopify_order_gid, " +
            "placed_at) VALUES (?, ?, ?, '#w1', 'delivered', 'portal_pre_connect', ?, now()) RETURNING id", UUID.class,
            f.tenant, f.store, "internal:portal:" + UUID.randomUUID(), "gid://shopify/Order/" + System.nanoTime());
        history(leg(o, "forward"), "with_courier"); expect("w1", o, false);
        o = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, origin, placed_at) " +
            "VALUES (?, ?, ?, '#1-X1', 'confirmed', 'shopify_exchange', now()) RETURNING id", UUID.class,
            f.tenant, f.store, "internal:shopify-exchange:" + UUID.randomUUID());
        allocated(o, "packed", "with_courier"); expect("w2", o, true);
        expect("w3", UUID.randomUUID(), false);
    }

    // ---- the old readers (what the code ran before) ----------------------------------------------------------------

    private boolean oldIsFrozen(JdbcTemplate j, UUID order, UUID tenant) {
        List<Boolean> r = j.queryForList("SELECT " + OrderLineFreeze.frozenSql("o.id", "o.tenant_id") +
            " FROM warehouse_orders o WHERE o.id = ? AND o.tenant_id = ?", Boolean.class, order, tenant);
        return !r.isEmpty() && Boolean.TRUE.equals(r.get(0));
    }

    private boolean oldShipped(UUID order, UUID tenant) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT " + OrderLineFreeze.shippedPastCreatedSql("?", "?"),
            Boolean.class, order, tenant));
    }

    private boolean oldLeftCustody(UUID order, UUID tenant) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT " + OrderLineFreeze.shippedPastCreatedSql("?", "?") + " OR EXISTS (" +
            "  SELECT 1 FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id JOIN pieces p ON p.id = a.piece_id " +
            "  WHERE oi.order_id = ? AND oi.tenant_id = ? AND a.status = 'packed' " +
            "    AND p.status::text IN ('with_courier', 'delivered', 'return_in_transit', 'return_pending_inspection'))",
            Boolean.class, order, tenant, order, tenant));
    }

    // ---- tests -----------------------------------------------------------------------------------------------------

    @Test
    void everyBranch_newEqualsOld() {
        for (Object[] c : cases) {
            String name = (String) c[0];
            UUID o = (UUID) c[1];
            boolean expected = (Boolean) c[2];
            assertThat(oldIsFrozen(jdbc, o, f.tenant)).as(name + " old isFrozen").isEqualTo(expected);
            assertThat(OrderLineFreeze.isFrozen(jdbc, o, f.tenant)).as(name + " new isFrozen").isEqualTo(expected);
            assertThat(OrderLineFreeze.hasShippedPastCreated(jdbc, o, f.tenant)).as(name + " shippedPastCreated")
                .isEqualTo(oldShipped(o, f.tenant));
            assertThat(OrderLineFreeze.hasLeftCustody(jdbc, o, f.tenant)).as(name + " leftCustody")
                .isEqualTo(oldLeftCustody(o, f.tenant));
        }
    }

    @Test
    void t1_anotherTenantId_neverFrozen() {
        for (Object[] c : cases) {
            UUID o = (UUID) c[1];
            assertThat(OrderLineFreeze.isFrozen(jdbc, o, other.tenant)).as(c[0] + " isFrozen").isFalse();
            assertThat(oldIsFrozen(jdbc, o, other.tenant)).as(c[0] + " old").isFalse();
            assertThat(OrderLineFreeze.hasShippedPastCreated(jdbc, o, other.tenant)).as(c[0] + " shipped").isFalse();
            assertThat(OrderLineFreeze.hasLeftCustody(jdbc, o, other.tenant)).as(c[0] + " left").isFalse();
        }
    }

    private <T> T asAppUser(UUID tenantContext, java.util.function.Function<JdbcTemplate, T> body) {
        JdbcTemplate app = new JdbcTemplate(appPool);
        return new TransactionTemplate(new DataSourceTransactionManager(appPool)).execute(s -> {
            app.queryForObject("SELECT set_config('app.current_tenant', ?, true)", String.class, tenantContext.toString());
            return body.apply(app);
        });
    }

    private boolean appUser(UUID tenantContext, java.util.function.Function<JdbcTemplate, Boolean> body) {
        return Boolean.TRUE.equals(asAppUser(tenantContext, body));
    }

    @Test
    void t2_appUserUnderRls() {
        for (Object[] c : cases) {
            UUID o = (UUID) c[1];
            boolean expected = (Boolean) c[2];
            assertThat(appUser(f.tenant, app -> OrderLineFreeze.isFrozen(app, o, f.tenant)))
                .as(c[0] + " own tenant").isEqualTo(expected);
            assertThat(appUser(f.tenant, app -> oldIsFrozen(app, o, f.tenant)))
                .as(c[0] + " old, own tenant").isEqualTo(expected);
            assertThat(appUser(other.tenant, app -> OrderLineFreeze.isFrozen(app, o, f.tenant)))
                .as(c[0] + " another tenant's context").isFalse();
            assertThat(appUser(other.tenant, app -> OrderLineFreeze.hasShippedPastCreated(app, o, f.tenant)))
                .as(c[0] + " shipped, another tenant's context").isFalse();
        }
    }

    @Test
    void p1_neverServerPrepared() {
        UUID frozen = (UUID) cases.get(0)[1];
        Long prepared = asAppUser(f.tenant, app -> {
            for (int i = 0; i < 10; i++) {
                assertThat(OrderLineFreeze.isFrozen(app, frozen, f.tenant)).isTrue();
                assertThat(OrderLineFreeze.hasShippedPastCreated(app, frozen, f.tenant)).isTrue();
                assertThat(OrderLineFreeze.hasLeftCustody(app, frozen, f.tenant)).isTrue();
            }
            // Control: an ordinary statement on the same connection IS server-prepared after pgjdbc's threshold (5).
            for (int i = 0; i < 10; i++) app.queryForObject("SELECT count(*) FROM orders WHERE id = ?", Long.class, frozen);
            assertThat(app.queryForObject("SELECT count(*) FROM pg_prepared_statements " +
                "WHERE statement LIKE 'SELECT count(*) FROM orders WHERE id = %'", Long.class))
                .as("control: pgjdbc prepares an ordinary statement").isEqualTo(1L);
            return app.queryForObject("SELECT count(*) FROM pg_prepared_statements " +
                "WHERE statement LIKE '%legs AS MATERIALIZED%'", Long.class);
        });
        assertThat(prepared).as("the freeze readers are never server-prepared").isZero();
    }
}
