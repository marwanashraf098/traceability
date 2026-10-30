package com.traceability.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.fulfillment.OrderController.OrderPage;
import com.traceability.fulfillment.OrderController.OrderTabCounts;
import com.traceability.inventory.*;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Orders tabs & filters — backend slice (2026-10-30). OrderListFilters / OrderReturnState are the ONE definition
 * the list, the tab counts and the drawer share.
 *
 *   f1 every order is in at most one shipping tab, each fixture order in the expected one.
 *   f2 count == list total for every tab × every filter value (and days = all).
 *   f3 Cairo day boundary: 23:59 the day before the window is out, 00:01 the first day is in; days ignored while
 *      searching and on tab=attention; a caller without tab keeps the all-time list.
 *   f4 list and drawer agree on return state: pending, restocked, damaged, back_to_good, untracked sellable /
 *      damaged; a restocked piece later damaged on the shelf and repaired stays "restocked"; Returns filter.
 *   f5 the alert check runs ≤ 1 time per request (list + withCounts → 1; counts?attention=false → 0).
 *   f6 every order-linked alert type has a reason group; the reason filter / counts / row group.
 *   f7 app_user: another tenant's orders never appear (list rows, counts, return filters); same-tenant control.
 *   f8 timing (reported, not asserted): detectAllOpen / list / counts on a 2,000-order fixture.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrdersFiltersTest {

    @Container
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

    @Autowired JdbcTemplate               jdbc;
    @Autowired ObjectMapper               mapper;
    @Autowired PlatformTransactionManager txm;
    @Autowired FulfillService             fulfill;
    @Autowired ReturnSessionService       returns;
    @Autowired PieceAdjustService         adjust;
    @SpyBean   ExceptionService           exceptions;
    @MockBean  JobScheduler               jobScheduler;

    private OrderController     controller;
    private TransactionTemplate tx;
    private OrderController     appUserController;

    private static final Random RND = new Random();
    private static final List<String> SHIPPING = List.of("toShip", "inTransit", "delivered", "returned", "cancelled");

    @BeforeAll
    void setup() {
        controller = new OrderController(jdbc, mapper, txm, new OrderNotesService(jdbc), fulfill, exceptions);
        tx = new TransactionTemplate(txm);
        DriverManagerDataSource rawDs = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(rawDs);
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        appUserController = new OrderController(appJdbc, mapper, new DataSourceTransactionManager(appDs),
            new OrderNotesService(appJdbc), fulfill);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    // ── fixture ──────────────────────────────────────────────────────────────

    record Shop(UUID id, UUID owner, UUID warehouse, UUID store, UUID variant) {}

    private Shop shop(String name) {
        UUID t = UUID.randomUUID(), owner = UUID.randomUUID(), wh = UUID.randomUUID(), store = UUID.randomUUID();
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", t, name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Nour Hassan', ?, 'h', 'owner')", owner, t, "owner-" + t + "@example.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main Warehouse', true)", wh, t);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', ?, 'disconnected')", store, t, "of-" + store + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, ?, 'Linen Shirt', 'active')", product, t, store, "gid://shopify/Product/" + RND.nextInt(1_000_000));
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, ?, 'White / M', 'LIN-WHT-M')", variant, t, product,
                    "gid://shopify/ProductVariant/" + RND.nextInt(1_000_000));
        return new Shop(t, owner, wh, store, variant);
    }

    /** An order. raw carries a fulfillment when shopifyFulfilled (orders.shopify_fulfilled is generated from raw). */
    private UUID order(Shop s, String number, String status, String payment, Instant placedAt, boolean notTraced,
                       String carrierClass, boolean shopifyFulfilled) {
        String raw = shopifyFulfilled ? "{\"fulfillments\":[{\"status\":\"success\"}]}" : "{}";
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, " +
            "    payment_method, cod_amount, placed_at, not_traced_at, shipping_carrier_class, raw) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'Mona Said', '01022334455', ?::order_payment_method, 540, ?, ?, ?, ?::jsonb) " +
            "RETURNING id",
            UUID.class, s.id(), s.store(), "gid://shopify/Order/" + RND.nextInt(1_000_000_000), number, status, payment,
            placedAt == null ? null : Timestamp.from(placedAt), notTraced ? Timestamp.from(Instant.now()) : null,
            carrierClass, raw);
    }

    private UUID order(Shop s, String number, String status) {
        return order(s, number, status, "cod", Instant.now().minus(Duration.ofDays(1)), false, null, false);
    }

    private static String tn() { return String.valueOf(4_000_000_000L + RND.nextInt(900_000_000)); }

    private UUID leg(Shop s, UUID orderId, String provider, String state, boolean deletedInBosta) {
        String t = tn();
        UUID id = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, internal_state, " +
            "    shipment_leg, carrier_name, provider_deleted_at, provider_deleted_reason) " +
            "VALUES (?, ?, ?::courier_provider, ?, ?, ?::shipment_internal_state, 'forward', ?, ?, ?) RETURNING id",
            UUID.class, s.id(), orderId, provider, t, "generic".equals(provider) ? "MY" + t : null, state,
            "generic".equals(provider) ? "Mylerz" : null, deletedInBosta ? Timestamp.from(Instant.now()) : null,
            deletedInBosta ? "deleted_in_bosta" : null);
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) VALUES (?, ?, ?)",
            s.id(), id, state);
        return id;
    }

    private UUID returnLeg(Shop s, UUID orderId, String state) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'return') RETURNING id",
            UUID.class, s.id(), orderId, tn(), state);
    }

    private UUID line(Shop s, UUID orderId, int qty) {
        return jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?) RETURNING id",
            UUID.class, s.id(), orderId, s.variant(), qty);
    }

    private String piece(Shop s, String status, UUID orderId) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, " +
                    "    current_location_id, last_event_at) VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, ?, now())",
            id, s.id(), s.variant(), "PC-" + id, "P" + (100000 + RND.nextInt(899999)), status, orderId,
            orderId == null ? s.warehouse() : null);
        return id;
    }

    private void allocate(Shop s, UUID item, String piece, String status) {
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, ?::allocation_status)",
            s.id(), item, piece, status);
    }

    private <T> T as(Shop s, Supplier<T> body) { return TenantContext.runAs(s.id(), () -> tx.execute(st -> body.get())); }
    private void run(Shop s, Runnable body) { as(s, () -> { body.run(); return null; }); }

    private OrderPage list(Shop s, String tab, String traced, String returnsF, String courier, String payment,
                           String days, String reason, String q, boolean withCounts) {
        return as(s, () -> controller.list(null, null, q, null, 0, 100, null, false,
            tab, traced, returnsF, courier, payment, days, reason, withCounts));
    }

    private Set<String> idsOf(OrderPage p) {
        Set<String> out = new HashSet<>();
        p.items().forEach(i -> out.add(i.id()));
        return out;
    }

    private int count(OrderTabCounts c, String tab) {
        return switch (tab) {
            case "all" -> c.all(); case "attention" -> c.attention();
            case "toShip" -> c.toShip(); case "inTransit" -> c.inTransit(); case "delivered" -> c.delivered();
            case "returned" -> c.returned(); case "cancelled" -> c.cancelled();
            default -> throw new IllegalArgumentException(tab);
        };
    }

    // ── f1 + f2: exclusive tabs, count == list ───────────────────────────────

    record Expect(UUID id, String tab) {}

    private List<Expect> tabFixture(Shop s) {
        List<Expect> e = new ArrayList<>();
        Instant old = Instant.now().minus(Duration.ofDays(45));
        // cancelled — whatever the leg
        UUID c1 = order(s, "#C1", "cancelled"); leg(s, c1, "bosta", "delivered", false); e.add(new Expect(c1, "cancelled"));
        UUID c2 = order(s, "#C2", "cancelled", "prepaid", old, false, null, false); e.add(new Expect(c2, "cancelled"));
        // to ship
        UUID t1 = order(s, "#T1", "new"); e.add(new Expect(t1, "toShip"));
        UUID t2 = order(s, "#T2", "awaiting_pickup", "prepaid", Instant.now().minus(Duration.ofDays(2)), false, "bosta", false);
        leg(s, t2, "bosta", "created", false); e.add(new Expect(t2, "toShip"));
        UUID t3 = order(s, "#T3", "self_pickup_pending"); e.add(new Expect(t3, "toShip"));
        UUID t4 = order(s, "#T4", "new", "cod", old, true, null, false);  // old, not traced
        e.add(new Expect(t4, "toShip"));
        // shipped outside Traced: no leg, Shopify fulfilled → no shipping tab
        UUID n1 = order(s, "#N1", "new", "prepaid", Instant.now(), false, "other_known", true); e.add(new Expect(n1, null));
        // in transit — with the courier either way
        UUID i1 = order(s, "#I1", "awaiting_pickup"); leg(s, i1, "bosta", "with_courier", false); e.add(new Expect(i1, "inTransit"));
        UUID i2 = order(s, "#I2", "awaiting_pickup"); leg(s, i2, "bosta", "exception", false); e.add(new Expect(i2, "inTransit"));
        UUID i3 = order(s, "#I3", "awaiting_pickup"); leg(s, i3, "bosta", "returning", false); e.add(new Expect(i3, "inTransit"));
        UUID i4 = order(s, "#I4", "with_courier", "prepaid", Instant.now(), true, null, false);
        leg(s, i4, "generic", "with_courier", false); e.add(new Expect(i4, "inTransit"));
        // delivered — the leg, or a handed-over self-pickup
        UUID d1 = order(s, "#D1", "awaiting_pickup"); leg(s, d1, "bosta", "delivered", false); e.add(new Expect(d1, "delivered"));
        UUID d2 = order(s, "#D2", "delivered"); e.add(new Expect(d2, "delivered"));
        // returned
        UUID r1 = order(s, "#R1", "awaiting_pickup", "cod", old, false, null, false);
        leg(s, r1, "bosta", "returned", false); e.add(new Expect(r1, "returned"));
        // a dead leg → no shipping tab
        UUID l1 = order(s, "#L1", "awaiting_pickup"); leg(s, l1, "bosta", "lost", false); e.add(new Expect(l1, null));
        // the only leg deleted in Bosta → no live leg → to ship
        UUID x1 = order(s, "#X1", "awaiting_pickup"); leg(s, x1, "bosta", "with_courier", true); e.add(new Expect(x1, "toShip"));
        // an internal exchange order — no payment method
        UUID ex = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, placed_at, raw) " +
            "VALUES (?, ?, ?, 'EXC-1', now(), '{}'::jsonb) RETURNING id", UUID.class, s.id(), s.store(), "internal:exchange:" + tn());
        e.add(new Expect(ex, "toShip"));
        return e;
    }

    @Test
    void f1_everyOrderInAtMostOneShippingTab_theExpectedOne() {
        Shop s = shop("OF-f1");
        List<Expect> expect = tabFixture(s);
        Map<String, Set<String>> byTab = new HashMap<>();
        for (String t : SHIPPING) byTab.put(t, idsOf(list(s, t, null, null, null, null, "all", null, null, false)));
        for (Expect e : expect) {
            List<String> in = SHIPPING.stream().filter(t -> byTab.get(t).contains(e.id().toString())).toList();
            assertThat(in).as("order %s", e.id()).hasSizeLessThanOrEqualTo(1);
            assertThat(in.isEmpty() ? null : in.get(0)).as("order %s tab", e.id()).isEqualTo(e.tab());
        }
        assertThat(idsOf(list(s, "all", null, null, null, null, "all", null, null, false))).hasSize(expect.size());
    }

    @Test
    void f2_countEqualsListTotal_everyTabTimesEveryFilter() {
        Shop s = shop("OF-f2");
        tabFixture(s);
        List<String[]> filters = new ArrayList<>();
        filters.add(new String[] {null, null, null, null, "all"});
        for (String v : List.of("traced", "not_traced")) filters.add(new String[] {v, null, null, null, "all"});
        for (String v : List.of("pending", "inspected")) filters.add(new String[] {null, v, null, null, "all"});
        for (String v : List.of("bosta", "other"))       filters.add(new String[] {null, null, v, null, "all"});
        for (String v : List.of("cod", "prepaid"))       filters.add(new String[] {null, null, null, v, "all"});
        for (String v : List.of("7", "30", "90"))        filters.add(new String[] {null, null, null, null, v});
        filters.add(new String[] {"traced", null, "bosta", "cod", "30"});
        int checked = 0;
        for (String[] f : filters) {
            OrderTabCounts c = as(s, () -> controller.counts(f[0], f[1], f[2], f[3], f[4], true));
            for (String tab : List.of("all", "attention", "toShip", "inTransit", "delivered", "returned", "cancelled")) {
                OrderPage p = list(s, tab, f[0], f[1], f[2], f[3], f[4], null, null, true);
                assertThat(p.total()).as("tab %s filters %s", tab, Arrays.toString(f)).isEqualTo(count(c, tab));
                assertThat(count(p.counts(), tab)).as("withCounts %s %s", tab, Arrays.toString(f)).isEqualTo(count(c, tab));
                checked++;
            }
        }
        assertThat(checked).isEqualTo(filters.size() * 7);
        // The fixture actually discriminates: the filters are not all equal.
        OrderTabCounts all = as(s, () -> controller.counts(null, null, null, null, "all", true));
        assertThat(all.all()).isEqualTo(17);
        assertThat(all.toShip()).isEqualTo(6);
        assertThat(all.inTransit()).isEqualTo(4);
        assertThat(as(s, () -> controller.counts("not_traced", null, null, null, "all", true)).all()).isEqualTo(2);
        assertThat(as(s, () -> controller.counts(null, null, "other", null, "all", true)).all()).isEqualTo(2);
        assertThat(as(s, () -> controller.counts(null, null, null, "prepaid", "all", true)).all()).isEqualTo(4);
        assertThat(as(s, () -> controller.counts(null, null, null, null, "30", true)).all()).isEqualTo(14);
    }

    // ── f3: Cairo day boundary ───────────────────────────────────────────────

    @Test
    void f3_cairoDayBoundary_andWhereDaysIsIgnored() {
        Shop s = shop("OF-f3");
        Instant cutoff = OrderListFilters.cutoff(7, Instant.now());   // 00:00 Cairo, six days ago
        UUID before = order(s, "#B2359", "new", "cod", cutoff.minus(Duration.ofMinutes(1)), false, null, false);
        UUID after  = order(s, "#A0001", "new", "cod", cutoff.plus(Duration.ofMinutes(1)), false, null, false);
        assertThat(cutoff.atZone(OrderListFilters.CAIRO).toLocalTime().toString()).isEqualTo("00:00");
        assertThat(before.equals(after)).isFalse();

        assertThat(idsOf(list(s, "all", null, null, null, null, "7", null, null, false))).containsExactly(after.toString());
        assertThat(as(s, () -> controller.counts(null, null, null, null, "7", false)).all()).isEqualTo(1);
        assertThat(idsOf(list(s, "all", null, null, null, null, "30", null, null, false))).hasSize(2);
        // days ignored while searching
        assertThat(idsOf(list(s, "all", null, null, null, null, "7", null, "#B2359", false))).containsExactly(before.toString());
        // a caller without tab keeps the all-time list (no default days)
        assertThat(as(s, () -> controller.list(null, null, null, null, 0, 100, null, false)).total()).isEqualTo(2);
        // tab given, days omitted → the 30-day default
        UUID old = order(s, "#OLD", "new", "cod", Instant.now().minus(Duration.ofDays(40)), false, null, false);
        assertThat(idsOf(list(s, "all", null, null, null, null, null, null, null, false))).doesNotContain(old.toString());
        // invalid values → 400
        assertThatThrownBy(() -> list(s, "all", null, null, null, null, "14", null, null, false))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> list(s, "inTransit", null, null, null, null, "all", "cant_ship", null, false))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void f3b_attentionTabIgnoresDays() {
        Shop s = shop("OF-f3b");
        UUID old = order(s, "#OLD-ATT", "new", "cod", Instant.now().minus(Duration.ofDays(60)), false, null, false);
        jdbc.update("UPDATE orders SET on_hold = true, hold_reason = 'blocked' WHERE id = ?", old);
        OrderPage p = list(s, "attention", null, null, null, null, "7", null, null, true);
        assertThat(idsOf(p)).contains(old.toString());
        assertThat(p.counts().attention()).isEqualTo((int) p.total());
    }

    // ── f4: return state — list and drawer agree ─────────────────────────────

    record Delivered(UUID order, UUID item, String piece) {}

    private Delivered delivered(Shop s, String number) {
        UUID o = order(s, number, "awaiting_pickup");
        UUID item = line(s, o, 1);
        leg(s, o, "bosta", "delivered", false);
        String p = piece(s, "delivered", o);
        allocate(s, item, p, "packed");
        return new Delivered(o, item, p);
    }

    private void scanBack(Shop s, UUID session, String piece) {
        run(s, () -> returns.scan(session, "PC-" + piece, s.warehouse(), s.owner()));
    }

    private void grade(Shop s, UUID session, String piece, String d) {
        run(s, () -> returns.disposition(session, piece, d, "damaged".equals(d) ? "torn seam" : null, s.warehouse(), s.owner(), "owner"));
    }

    private String drawerState(Shop s, UUID orderId, String piece) {
        return as(s, () -> controller.detail(orderId)).items().stream().flatMap(i -> i.allocatedPieces().stream())
            .filter(p -> p.pieceId().equals(piece)).findFirst()
            .map(p -> p.returnState() == null ? "NONE" : p.returnState()).orElse("ABSENT");
    }

    private OrderController.ReturnStateSummary rowState(Shop s, UUID orderId) {
        return list(s, "all", null, null, null, null, "all", null, null, false).items().stream()
            .filter(i -> i.id().equals(orderId.toString())).findFirst().orElseThrow().returnState();
    }

    @Test
    void f4_listAndDrawerAgreeOnReturnState() {
        Shop s = shop("OF-f4");
        Delivered pending = delivered(s, "#P1"), restocked = delivered(s, "#P2"), damaged = delivered(s, "#P3"),
                  btg = delivered(s, "#P4"), shelf = delivered(s, "#P5");
        UUID session = as(s, () -> returns.createSession(null, s.owner()));
        for (Delivered d : List.of(pending, restocked, damaged, btg, shelf)) scanBack(s, session, d.piece());
        grade(s, session, restocked.piece(), "restock");
        grade(s, session, damaged.piece(), "damaged");
        grade(s, session, btg.piece(), "damaged");
        grade(s, session, shelf.piece(), "restock");
        run(s, () -> adjust.restore(btg.piece(), "repaired", null, s.owner()));
        // restocked, then damaged on the shelf and repaired — still "restocked" for this order
        run(s, () -> adjust.adjustPiece(shelf.piece(), "damaged", "damaged_in_storage", null, s.owner()));
        run(s, () -> adjust.restore(shelf.piece(), "repaired", null, s.owner()));

        assertThat(drawerState(s, pending.order(), pending.piece())).isEqualTo("pending_inspection");
        assertThat(rowState(s, pending.order())).isEqualTo(new OrderController.ReturnStateSummary("pending", Map.of()));
        assertThat(drawerState(s, restocked.order(), restocked.piece())).isEqualTo("restocked");
        assertThat(rowState(s, restocked.order()).grades()).isEqualTo(Map.of("restocked", 1));
        assertThat(drawerState(s, damaged.order(), damaged.piece())).isEqualTo("damaged");
        assertThat(rowState(s, damaged.order()).grades()).isEqualTo(Map.of("damaged", 1));
        assertThat(drawerState(s, btg.order(), btg.piece())).isEqualTo("back_to_good");
        assertThat(rowState(s, btg.order()).grades()).isEqualTo(Map.of("backToGood", 1));
        assertThat(drawerState(s, shelf.order(), shelf.piece())).isEqualTo("restocked");
        assertThat(rowState(s, shelf.order()).grades()).isEqualTo(Map.of("restocked", 1));
        assertThat(rowState(s, restocked.order()).state()).isEqualTo("inspected");
        assertThat(as(s, () -> controller.detail(btg.order())).items().get(0).allocatedPieces().get(0).shortCode())
            .startsWith("P");

        // untracked units: graded at arrival
        UUID u = order(s, "#U1", "awaiting_pickup");
        UUID uItem = line(s, u, 2);
        leg(s, u, "bosta", "delivered", false);
        UUID crp = returnLeg(s, u, "returned");
        String crpTn = jdbc.queryForObject("SELECT tracking_number FROM shipments WHERE id = ?", String.class, crp);
        run(s, () -> returns.scan(session, crpTn, s.warehouse(), s.owner()));
        run(s, () -> returns.unitArrived(session, crp, uItem, 1, "sellable", s.owner()));
        run(s, () -> returns.unitArrived(session, crp, uItem, 2, "damaged", s.owner()));
        assertThat(rowState(s, u)).isEqualTo(new OrderController.ReturnStateSummary("inspected", Map.of("damaged", 1, "sellable", 1)));
        assertThat(as(s, () -> controller.detail(u)).items().get(0).returnedUnits()).containsExactly(
            new OrderController.ReturnedUnit(1, "sellable"), new OrderController.ReturnedUnit(2, "damaged"));

        // the Returns filter = the row state
        Set<String> pendingIds = idsOf(list(s, "all", null, "pending", null, null, "all", null, null, false));
        Set<String> inspectedIds = idsOf(list(s, "all", null, "inspected", null, null, "all", null, null, false));
        assertThat(pendingIds).containsExactly(pending.order().toString());
        assertThat(inspectedIds).containsExactlyInAnyOrder(restocked.order().toString(), damaged.order().toString(),
            btg.order().toString(), shelf.order().toString(), u.toString());
        // nothing came back → no state
        Delivered none = delivered(s, "#P6");
        assertThat(rowState(s, none.order())).isNull();
        assertThat(drawerState(s, none.order(), none.piece())).isEqualTo("NONE");
    }

    // ── f5: one alert check per request ──────────────────────────────────────

    @Test
    void f5_alertCheckRunsAtMostOncePerRequest() {
        Shop s = shop("OF-f5");
        tabFixture(s);
        clearInvocations(exceptions);
        list(s, "all", null, null, null, null, "30", null, null, true);
        verify(exceptions, times(1)).openAttentionOrderIds();
        clearInvocations(exceptions);
        list(s, "attention", null, null, null, null, null, "other", null, true);
        verify(exceptions, times(1)).openAttentionOrderIds();
        clearInvocations(exceptions);
        OrderTabCounts c = as(s, () -> controller.counts(null, null, null, null, "30", false));
        verify(exceptions, never()).openAttentionOrderIds();
        assertThat(c.attention()).isNull();
        assertThat(c.reasonCounts()).isNull();
        clearInvocations(exceptions);
        as(s, () -> controller.counts(null, null, null, null, "30", true));
        verify(exceptions, times(1)).openAttentionOrderIds();
    }

    // ── f6: reason groups ────────────────────────────────────────────────────

    @Test
    void f6_everyOrderLinkedAlertTypeHasAReasonGroup() {
        List<String> missing = new ArrayList<>();
        ExceptionService.ORDER_LINK.forEach((key, link) -> {
            String type = key.substring(0, key.indexOf('/'));
            if (link != ExceptionService.OrderLink.NONE && !ExceptionService.REASON_GROUP.containsKey(type)) missing.add(type);
        });
        assertThat(missing).isEmpty();
        assertThat(ExceptionService.REASON_GROUPS).containsAll(new HashSet<>(ExceptionService.REASON_GROUP.values()));
        // every mapped type is a real, order-linked one (no stale entries)
        Set<String> linked = new HashSet<>();
        ExceptionService.ORDER_LINK.forEach((k, l) -> { if (l != ExceptionService.OrderLink.NONE) linked.add(k.substring(0, k.indexOf('/'))); });
        assertThat(linked).containsAll(ExceptionService.REASON_GROUP.keySet());
    }

    @Test
    void f6b_reasonFilter_counts_andRowGroup() {
        Shop s = shop("OF-f6b");
        UUID blocked = order(s, "#BLK", "new");
        jdbc.update("UPDATE orders SET on_hold = true, hold_reason = 'blocked' WHERE id = ?", blocked);
        OrderPage all = list(s, "attention", null, null, null, null, null, null, null, true);
        assertThat(all.total()).isPositive();
        OrderController.AttentionGroup g = all.items().stream().filter(i -> i.id().equals(blocked.toString()))
            .findFirst().orElseThrow().attentionGroup();
        assertThat(g.group()).isEqualTo(ExceptionService.reasonGroupOf(g.reason()));
        int sum = 0;
        for (String group : ExceptionService.REASON_GROUPS) {
            OrderPage p = list(s, "attention", null, null, null, null, null, group, null, false);
            assertThat((int) p.total()).as(group).isEqualTo(all.counts().reasonCounts().get(group).intValue());
            sum += (int) p.total();
        }
        assertThat(sum).isGreaterThanOrEqualTo((int) all.total());
    }

    // ── f7: app_user isolation ───────────────────────────────────────────────

    @Test
    void f7_appUser_otherTenantsOrdersNeverAppear_sameTenantControl() {
        Shop a = shop("OF-f7-A"), b = shop("OF-f7-B");
        tabFixture(a);
        Delivered d = delivered(a, "#ISO");
        UUID session = as(a, () -> returns.createSession(null, a.owner()));
        scanBack(a, session, d.piece());

        Supplier<OrderPage> listAll = () -> appUserController.list(null, null, null, null, 0, 100, null, false,
            "all", null, null, null, null, "all", null, true);
        Supplier<OrderPage> listPending = () -> appUserController.list(null, null, null, null, 0, 100, null, false,
            "all", null, "pending", null, null, "all", null, false);
        OrderPage ownAll = TenantContext.runAs(a.id(), listAll::get);
        assertThat(ownAll.total()).isEqualTo(18);
        assertThat(ownAll.counts().all()).isEqualTo(18);
        assertThat(TenantContext.runAs(a.id(), listPending::get).items()).extracting(OrderController.OrderSummary::id)
            .containsExactly(d.order().toString());
        assertThat(ownAll.items().stream().filter(i -> i.id().equals(d.order().toString())).findFirst().orElseThrow()
            .returnState().state()).isEqualTo("pending");

        OrderPage otherAll = TenantContext.runAs(b.id(), listAll::get);
        assertThat(otherAll.total()).isZero();
        assertThat(otherAll.counts().all()).isZero();
        assertThat(TenantContext.runAs(b.id(), listPending::get).total()).isZero();
        assertThat(TenantContext.runAs(b.id(), () -> appUserController.counts(null, "pending", null, null, "all", false)).all()).isZero();
    }

    // ── f8: timing ───────────────────────────────────────────────────────────

    @Test
    void f8_timing_onA2000OrderFixture() {
        Shop s = shop("OF-f8");
        jdbc.update(
            "WITH o AS (INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
            "  SELECT ?, ?, 'gid://shopify/Order/f8-' || g, '#F' || g, 'awaiting_pickup', " +
            "         CASE WHEN g % 3 = 0 THEN 'prepaid' ELSE 'cod' END::order_payment_method, " +
            "         now() - (g % 120) * interval '1 day', '{}'::jsonb FROM generate_series(1, 2000) g RETURNING id, number) " +
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, created_at, last_synced_at) " +
            "SELECT ?, o.id, 'bosta', '77' || substr(o.number, 3), " +
            "       (ARRAY['created','with_courier','delivered','returned','exception'])[1 + (substr(o.number, 3)::int % 5)]::shipment_internal_state, " +
            "       'forward', now() - interval '20 days', now() - interval '20 days' FROM o",
            s.id(), s.store(), s.id());
        // Real tables have planner statistics; a bulk-inserted fixture has none until ANALYZE (without it the
        // latest-leg LATERAL walks the tenant's shipments by created_at instead of shipments_order_idx).
        jdbc.execute("ANALYZE orders; ANALYZE shipments; ANALYZE shipment_status_history; ANALYZE piece_events; ANALYZE pieces; ANALYZE allocations; ANALYZE order_items");
        for (int i = 0; i < 2; i++) {   // warm-up: every call once, so the timings below are warm
            as(s, () -> exceptions.openAttentionOrderIds());
            list(s, "all", null, null, null, null, "30", null, null, true);
            as(s, () -> controller.counts(null, null, null, null, "30", false));
            list(s, "all", null, "inspected", null, null, "all", null, null, false);
        }
        long t0 = System.nanoTime();
        Map<UUID, List<String>> att = as(s, () -> exceptions.openAttentionOrderIds());
        long t1 = System.nanoTime();
        OrderPage page = list(s, "all", null, null, null, null, "30", null, null, true);
        long t2 = System.nanoTime();
        as(s, () -> controller.counts(null, null, null, null, "30", false));
        long t3 = System.nanoTime();
        list(s, "all", null, "inspected", null, null, "all", null, null, false);
        long t4 = System.nanoTime();
        System.out.printf("ORDERS-FILTERS TIMING (2,000 orders, %d with alerts): openAttentionOrderIds %d ms; " +
                "list(withCounts) %d ms; counts(attention=false) %d ms; list(returns=inspected, all time) %d ms%n",
            att.size(), (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, (t4 - t3) / 1_000_000);
        assertThat(page.counts().all()).isPositive();
    }
}
