package com.traceability.places;

import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Warehouse places S3 — Pick &amp; Pack places (PickPlacesService), display only.
 *
 *   c1  counts: only AVAILABLE pieces of that variant at the MAIN warehouse; another location, reserved / damaged /
 *       on_hold and other variants left out; largest first; noPlaceCount = available there with no place; each
 *       place's code, kind, path, zone, parent and walking-order key
 *   c2  one order's lines (the pack screen), keyed by order line; a line's variant shared by two lines; an order
 *       outside merchant_orders (unknown, a portal pre-connect row) → 404 / left out of the orders batch
 *   q1  the batch counts every variant in ONE statement on pieces (no per-variant query)
 *   f1  places switched off: every endpoint 404
 *   x1  app_user under RLS: another tenant's variants / orders read as nothing, same-tenant positive control
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PickPlacesTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
    }

    @MockBean JobScheduler jobScheduler;

    @Autowired JdbcTemplate      jdbc;
    @Autowired PickPlacesService places;
    @Autowired DataSource        dataSource;

    /** Main: Z1 › Z1-R01 › S1 / S2, BOX-01 on the Z1 floor, BOX-02 on S2. Other location: its own Z1-R01-S1. */
    record T(UUID tenant, UUID store, UUID main, UUID other, UUID variant, UUID variant2, UUID variant3,
             UUID zone, UUID rack, UUID s1, UUID s2, UUID floorBox, UUID shelfBox, UUID otherShelf) {}

    final AtomicInteger seq = new AtomicInteger();

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void c1_countsAvailableAtMainOnly_largestFirst_noPlaceCount_andPlaceDetails() {
        T t = tenant("c1", true);
        pieces(t, t.variant(), t.main(), t.s1(), "available", 2);
        pieces(t, t.variant(), t.main(), t.floorBox(), "available", 3);
        pieces(t, t.variant(), t.main(), t.shelfBox(), "available", 1);
        pieces(t, t.variant(), t.main(), null, "available", 4);
        // left out: another location (placed and unplaced), other statuses at main, another variant
        pieces(t, t.variant(), t.other(), t.otherShelf(), "available", 5);
        pieces(t, t.variant(), t.other(), null, "available", 6);
        pieces(t, t.variant(), t.main(), t.s1(), "damaged", 7);
        pieces(t, t.variant(), t.main(), t.s2(), "on_hold", 8);
        pieces(t, t.variant(), t.main(), null, "reserved", 9);
        pieces(t, t.variant(), t.main(), null, "packed", 10);
        pieces(t, t.variant2(), t.main(), t.s2(), "available", 11);

        var r = as(t, () -> places.variants(List.of(t.variant(), t.variant3())));
        assertThat(r.variants()).extracting(PickPlacesService.VariantPlaces::variantId)
            .as("every variant asked for, in the order asked").containsExactly(t.variant(), t.variant3());

        var v = r.variants().get(0);
        assertThat(v.noPlaceCount()).as("available at main with no place").isEqualTo(4);
        assertThat(v.places()).extracting(PickPlacesService.PlaceCount::code, PickPlacesService.PlaceCount::count)
            .as("largest first").containsExactly(
                org.assertj.core.groups.Tuple.tuple("BOX-01", 3),
                org.assertj.core.groups.Tuple.tuple("Z1-R01-S1", 2),
                org.assertj.core.groups.Tuple.tuple("BOX-02", 1));

        var floor = v.places().get(0);
        assertThat(floor.binId()).isEqualTo(t.floorBox());
        assertThat(floor.kind()).isEqualTo("box");
        assertThat(floor.zoneCode()).isEqualTo("Z1");
        assertThat(floor.parentCode()).isEqualTo("Z1");
        assertThat(floor.parentKind()).isEqualTo("zone");
        assertThat(floor.path()).containsExactly("Z1", "BOX-01");
        var shelf = v.places().get(1);
        assertThat(shelf.kind()).isEqualTo("shelf");
        assertThat(shelf.path()).containsExactly("Z1", "R01", "S1");
        assertThat(shelf.parentCode()).isEqualTo("Z1-R01");
        var onShelf = v.places().get(2);
        assertThat(onShelf.parentCode()).isEqualTo("Z1-R01-S2");
        assertThat(onShelf.parentKind()).isEqualTo("shelf");
        assertThat(onShelf.path()).containsExactly("Z1", "R01", "S2", "BOX-02");
        assertThat(shelf.sortKey()).as("walking order: shelf, box on a later shelf, floor box last")
            .isLessThan(onShelf.sortKey());
        assertThat(onShelf.sortKey()).isLessThan(floor.sortKey());

        var nothing = r.variants().get(1);
        assertThat(nothing.places()).isEmpty();
        assertThat(nothing.noPlaceCount()).isZero();

        // an equal count falls back to walking order
        T u = tenant("c1b", true);
        pieces(u, u.variant(), u.main(), u.s2(), "available", 1);
        pieces(u, u.variant(), u.main(), u.s1(), "available", 1);
        assertThat(as(u, () -> places.variants(List.of(u.variant()))).variants().get(0).places())
            .extracting(PickPlacesService.PlaceCount::code).containsExactly("Z1-R01-S1", "Z1-R01-S2");
    }

    @Test
    void c2_orderLines_keyedByLine_and_ordersOutsideMerchantOrdersRefused() {
        T t = tenant("c2", true);
        pieces(t, t.variant(), t.main(), t.s1(), "available", 2);
        pieces(t, t.variant2(), t.main(), null, "available", 1);
        UUID order = order(t, "#C2-1", "ext-c2-1", null);
        UUID a = item(t, order, t.variant(), 2), b = item(t, order, t.variant2(), 1), c = item(t, order, t.variant(), 1);
        UUID empty = order(t, "#C2-2", "ext-c2-2", null);
        UUID portal = order(t, "#C2-P", "internal:portal:" + UUID.randomUUID(), "portal_pre_connect");
        item(t, portal, t.variant(), 1);

        var o = as(t, () -> places.order(order));
        assertThat(o.orderId()).isEqualTo(order);
        Map<UUID, PickPlacesService.LinePlaces> byLine = new HashMap<>();
        o.lines().forEach(l -> byLine.put(l.orderItemId(), l));
        assertThat(byLine).containsOnlyKeys(a, b, c);
        assertThat(byLine.get(a).variantId()).isEqualTo(t.variant());
        assertThat(byLine.get(a).quantity()).isEqualTo(2);
        assertThat(byLine.get(a).places()).extracting(PickPlacesService.PlaceCount::code).containsExactly("Z1-R01-S1");
        assertThat(byLine.get(c).places()).as("two lines of one variant read the same places").isEqualTo(byLine.get(a).places());
        assertThat(byLine.get(b).places()).isEmpty();
        assertThat(byLine.get(b).noPlaceCount()).isEqualTo(1);

        assertThat(as(t, () -> places.order(empty)).lines()).isEmpty();
        assertThatThrownBy(() -> as(t, () -> places.order(UUID.randomUUID())))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> as(t, () -> places.order(portal)))
            .as("a portal pre-connect order is not in merchant_orders")
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));

        var batch = as(t, () -> places.orders(List.of(empty, portal, order, UUID.randomUUID())));
        assertThat(batch.orders()).extracting(PickPlacesService.OrderPlaces::orderId)
            .as("in the order asked; unknown and portal orders left out").containsExactly(empty, order);
    }

    @Test
    void q1_batch_countsEveryVariantInOneStatement() {
        T t = tenant("q1", true);
        UUID v4 = variant(t, "XL");
        pieces(t, t.variant(), t.main(), t.s1(), "available", 2);
        pieces(t, t.variant2(), t.main(), t.s2(), "available", 1);
        pieces(t, t.variant3(), t.main(), null, "available", 3);
        pieces(t, v4, t.main(), t.floorBox(), "available", 1);
        UUID order = order(t, "#Q1", "ext-q1", null);
        for (UUID v : List.of(t.variant(), t.variant2(), t.variant3(), v4)) item(t, order, v, 1);

        List<String> statements = new CopyOnWriteArrayList<>();
        PickPlacesService counted = new PickPlacesService(new JdbcTemplate(counting(dataSource, statements)));

        var r = as(t, () -> counted.variants(List.of(t.variant(), t.variant2(), t.variant3(), v4)));
        assertThat(r.variants()).hasSize(4);
        assertThat(statements.stream().filter(s -> s.contains("FROM pieces")).count())
            .as("four variants, one statement on pieces: %s", statements).isEqualTo(1);
        assertThat(statements).as("the places switch + the counts, nothing per variant").hasSize(2);

        statements.clear();
        var o = as(t, () -> counted.orders(List.of(order)));
        assertThat(o.orders().get(0).lines()).hasSize(4);
        assertThat(statements.stream().filter(s -> s.contains("FROM pieces")).count()).isEqualTo(1);
        assertThat(statements).as("switch + order lines + counts").hasSize(3);
    }

    @Test
    void f1_placesSwitchedOff_everyEndpoint404() {
        T t = tenant("f1", false);
        UUID order = order(t, "#F1", "ext-f1", null);
        for (Callable<?> call : List.<Callable<?>>of(
                () -> places.variants(List.of(t.variant())),
                () -> places.order(order),
                () -> places.orders(List.of(order)))) {
            assertThatThrownBy(() -> as(t, call))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        }
    }

    @Test
    void x1_appUser_otherTenantReadsAsNothing_withSameTenantPositiveControl() {
        T mine = tenant("x1a", true), theirs = tenant("x1b", true);
        pieces(mine, mine.variant(), mine.main(), mine.s1(), "available", 2);
        pieces(mine, mine.variant(), mine.main(), null, "available", 1);
        pieces(theirs, theirs.variant(), theirs.main(), theirs.s1(), "available", 5);
        pieces(theirs, theirs.variant(), theirs.main(), null, "available", 4);
        UUID myOrder = order(mine, "#X1-M", "ext-x1-m", null);
        item(mine, myOrder, mine.variant(), 1);
        UUID theirOrder = order(theirs, "#X1-T", "ext-x1-t", null);
        item(theirs, theirOrder, theirs.variant(), 1);

        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        PickPlacesService app = new PickPlacesService(new JdbcTemplate(ds));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        TenantContext.set(mine.tenant());
        try {
            var r = tx.execute(s -> app.variants(List.of(mine.variant(), theirs.variant())));
            assertThat(r.variants().get(0).places()).as("same-tenant positive control")
                .extracting(PickPlacesService.PlaceCount::code, PickPlacesService.PlaceCount::count)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Z1-R01-S1", 2));
            assertThat(r.variants().get(0).noPlaceCount()).isEqualTo(1);
            assertThat(r.variants().get(1).places()).as("their pieces are invisible").isEmpty();
            assertThat(r.variants().get(1).noPlaceCount()).isZero();

            assertThat(tx.execute(s -> app.order(myOrder)).lines()).as("same-tenant positive control").hasSize(1);
            assertThatThrownBy(() -> tx.execute(s -> app.order(theirOrder)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
            assertThat(tx.execute(s -> app.orders(List.of(theirOrder, myOrder))).orders())
                .extracting(PickPlacesService.OrderPlaces::orderId).containsExactly(myOrder);
        } finally {
            TenantContext.clear();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private <R> R as(T t, Callable<R> call) {
        return TenantContext.runAs(t.tenant(), call);
    }

    /** A DataSource whose connections record the SQL of every statement they prepare. */
    private static DataSource counting(DataSource target, List<String> statements) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
            (proxy, m, args) -> {
                Object out = m.invoke(target, args);
                if (!(out instanceof Connection con)) return out;
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (p2, m2, a2) -> {
                        if ((m2.getName().equals("prepareStatement") || m2.getName().equals("prepareCall"))
                                && a2 != null && a2[0] instanceof String sql) statements.add(sql);
                        try {
                            return m2.invoke(con, a2);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            });
    }

    private T tenant(String name, boolean placesOn) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), product = UUID.randomUUID();
        UUID main = UUID.randomUUID(), other = UUID.randomUUID();
        String tag = name + "-" + tenant.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, places_enabled) VALUES (?, ?, ?)", tenant, "PickPlaces " + name, placesOn);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, tag + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Tee', 'active')",
            product, tenant, store, "P-" + tag);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_default, is_fulfillment) VALUES (?, ?, 'Main Warehouse', true, true)",
            main, tenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Second Store')", other, tenant);
        T base = new T(tenant, store, main, other, null, null, null, null, null, null, null, null, null, null);
        UUID v1 = variant(base, "M"), v2 = variant(base, "L"), v3 = variant(base, "S");
        UUID zone = bin(base, main, "zone", null, null, "Z1");
        UUID rack = bin(base, main, "rack", zone, "zone", "Z1-R01");
        UUID s1 = bin(base, main, "shelf", rack, "rack", "Z1-R01-S1");
        UUID s2 = bin(base, main, "shelf", rack, "rack", "Z1-R01-S2");
        UUID floorBox = bin(base, main, "box", zone, "zone", "BOX-01");
        UUID shelfBox = bin(base, main, "box", s2, "shelf", "BOX-02");
        UUID oz = bin(base, other, "zone", null, null, "Z1");
        UUID or = bin(base, other, "rack", oz, "zone", "Z1-R01");
        UUID os = bin(base, other, "shelf", or, "rack", "Z1-R01-S1");
        return new T(tenant, store, main, other, v1, v2, v3, zone, rack, s1, s2, floorBox, shelfBox, os);
    }

    private UUID variant(T t, String title) {
        UUID id = UUID.randomUUID();
        UUID product = jdbc.queryForObject("SELECT id FROM products WHERE tenant_id = ?", UUID.class, t.tenant());
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            id, t.tenant(), product, "V-" + id, title, "SKU-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID bin(T t, UUID location, String kind, UUID parent, String parentKind, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, t.tenant(), location, kind, parent, parentKind, code, String.format("Q%07d", 5_000_000 + seq.incrementAndGet()));
        return id;
    }

    private void pieces(T t, UUID variant, UUID location, UUID bin, String status, int n) {
        for (int i = 0; i < n; i++) {
            int k = seq.incrementAndGet();
            String id = "01PKP" + UUID.randomUUID().toString().replace("-", "").substring(0, 21).toUpperCase();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, bin_id) " +
                    "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?, ?)",
                id, t.tenant(), variant, status, "PC-" + id, String.format("P%07d", 6_000_000 + k), location, bin);
        }
    }

    private UUID order(T t, String number, String externalId, String origin) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, origin, shopify_order_gid) " +
                "VALUES (?, ?, ?, ?, ?, 'new', COALESCE(?, 'shopify'), ?)",
            id, t.tenant(), t.store(), externalId, number, origin,
            origin == null ? null : "gid://shopify/Order/" + seq.incrementAndGet());
        return id;
    }

    private UUID item(T t, UUID order, UUID variant, int qty) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, ?)",
            id, t.tenant(), order, variant, qty);
        return id;
    }
}
