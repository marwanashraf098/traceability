package com.traceability.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.fulfillment.OrderController.AllocatedPiece;
import com.traceability.fulfillment.OrderController.OrderDetail;
import com.traceability.inventory.*;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Returned pieces stay on their order (2026-10-30). A piece that came back through Scan returns keeps showing on
 * its order — "Returned · pending inspection / restocked / damaged" — after restock released its allocation
 * (OrderController.ORDER_PIECES_SQL); the order is no longer tagged Not Traced by a later Bosta event
 * (NotTracedTagger); and Back to good frees a piece graded damaged on return for a new order
 * (PieceAdjustService.restore).
 *
 *   t1  intake → pending_inspection; restock → still listed, restocked; damaged → damaged.      RED on main (restock)
 *   t2  restocked order + a later Bosta event → not tagged; no-allocation and unscanned-then-
 *       shipped orders are still tagged.                                                         RED on main
 *   t3  returned orders never pickable, never demand, scan refuses ALREADY_SHIPPED.              GREEN before and after
 *   t4  Bosta exchange: the old piece comes back on the original order (restocked); the
 *       replacement order keeps its own piece, with no return state, and is not pickable.        RED on main
 *   t5  untracked unit Arrived: no allocation written, the order's lines unchanged.              GREEN before and after
 *   t6  damaged on return → Back to good → the piece can be picked for a new order.             RED on main
 *   t7  app_user: the new read returns nothing across tenants or without a tenant; same-tenant
 *       positive control returns the restocked piece.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnedPiecesOnOrderTest {

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
    @Autowired NotTracedTagger            tagger;
    @Autowired VariantStockService        stock;
    @MockBean  JobScheduler               jobScheduler;

    private OrderController     controller;
    private TransactionTemplate tx;
    private JdbcTemplate        appUserJdbc;
    private TransactionTemplate appUserTx;

    private static final Random RND = new Random();

    @BeforeAll
    void setup() {
        controller = new OrderController(jdbc, mapper, txm, new OrderNotesService(jdbc), fulfill);
        tx = new TransactionTemplate(txm);
        DriverManagerDataSource rawDs = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(rawDs);
        appUserJdbc = new JdbcTemplate(appDs);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(appDs));
    }

    @AfterEach void clear() { TenantContext.clear(); }

    // ── fixture ──────────────────────────────────────────────────────────────

    /** A merchant: tenant, owner, main warehouse, a disconnected store, one variant. */
    record Shop(UUID id, UUID owner, UUID warehouse, UUID store, UUID variant) {}

    private Shop shop(String name) {
        UUID t = UUID.randomUUID(), owner = UUID.randomUUID(), wh = UUID.randomUUID(), store = UUID.randomUUID();
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", t, name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Nour Hassan', ?, 'h', 'owner')", owner, t, "owner-" + t + "@example.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main Warehouse', true)",
                    wh, t);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', ?, 'disconnected')", store, t, "rp-" + store + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, ?, 'Linen Shirt', 'active')", product, t, store, "gid://shopify/Product/" + RND.nextInt(1_000_000));
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, ?, 'White / M', 'LIN-WHT-M')", variant, t, product,
                    "gid://shopify/ProductVariant/" + RND.nextInt(1_000_000));
        return new Shop(t, owner, wh, store, variant);
    }

    private static String tracking() { return String.valueOf(4_000_000_000L + RND.nextInt(900_000_000)); }

    private UUID order(Shop s, String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, " +
            "    payment_method, cod_amount, placed_at) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'Mona Said', '01022334455', 'cod', 540, now()) RETURNING id",
            UUID.class, s.id(), s.store(), "gid://shopify/Order/" + RND.nextInt(1_000_000_000),
            "#" + (1000 + RND.nextInt(9000)), status);
    }

    private UUID line(Shop s, UUID orderId, int qty) {
        return jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?) RETURNING id",
            UUID.class, s.id(), orderId, s.variant(), qty);
    }

    private UUID shipment(Shop s, UUID orderId, String leg, String state) {
        UUID id = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, ?) RETURNING id",
            UUID.class, s.id(), orderId, tracking(), state, leg);
        // Real legs carry history; FulfillService's ALREADY_SHIPPED guard reads it.
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) " +
                    "VALUES (?, ?, ?)", s.id(), id, state);
        return id;
    }

    private String trackingOf(UUID shipmentId) {
        return jdbc.queryForObject("SELECT tracking_number FROM shipments WHERE id = ?", String.class, shipmentId);
    }

    /** A piece of the shop's variant at {@code status}, tagged, on {@code orderId} (null = on the shelf). */
    private String piece(Shop s, String status, UUID orderId) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, " +
            "    current_location_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, ?, now())",
            id, s.id(), s.variant(), "PC-" + id, "P" + (100000 + RND.nextInt(899999)), status, orderId,
            orderId == null ? s.warehouse() : null);
        return id;
    }

    private void allocate(Shop s, UUID itemId, String pieceId, String status) {
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) " +
                    "VALUES (?, ?, ?, ?::allocation_status)", s.id(), itemId, pieceId, status);
    }

    /** A delivered order: one line, {@code pieces} delivered pieces packed on it, a delivered forward leg. */
    record Delivered(UUID order, UUID item, UUID forward, List<String> pieces) {}

    private Delivered delivered(Shop s, int pieces) {
        UUID o = order(s, "awaiting_pickup");
        UUID item = line(s, o, pieces);
        UUID fwd = shipment(s, o, "forward", "delivered");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < pieces; i++) {
            String p = piece(s, "delivered", o);
            allocate(s, item, p, "packed");
            ids.add(p);
        }
        return new Delivered(o, item, fwd, ids);
    }

    private <T> T as(Shop s, Supplier<T> body) {
        return TenantContext.runAs(s.id(), () -> tx.execute(st -> body.get()));
    }

    private void run(Shop s, Runnable body) { as(s, () -> { body.run(); return null; }); }

    private UUID openSession(Shop s) { return as(s, () -> returns.createSession(null, s.owner())); }

    private void scanBack(Shop s, UUID session, String pieceId) {
        run(s, () -> returns.scan(session, "PC-" + pieceId, s.warehouse(), s.owner()));
    }

    private void grade(Shop s, UUID session, String pieceId, String disposition) {
        run(s, () -> returns.disposition(session, pieceId, disposition,
            "damaged".equals(disposition) ? "torn seam" : null, s.warehouse(), s.owner(), "owner"));
    }

    private List<AllocatedPiece> piecesOn(Shop s, UUID orderId) {
        OrderDetail d = as(s, () -> controller.detail(orderId));
        return d.items().stream().flatMap(i -> i.allocatedPieces().stream()).toList();
    }

    private Map<String, String> returnStates(Shop s, UUID orderId) {
        Map<String, String> m = new LinkedHashMap<>();
        for (AllocatedPiece p : piecesOn(s, orderId)) m.put(p.pieceId(), String.valueOf(p.returnState()));
        return m;
    }

    private String status(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    // ── t1: the returned piece stays on its order ────────────────────────────

    @Test
    void t1_returnedPiece_staysOnOrder_pendingThenRestockedOrDamaged() {
        Shop s = shop("RP-t1");
        Delivered d = delivered(s, 2);
        String kept = d.pieces().get(0), back = d.pieces().get(1);
        assertThat(returnStates(s, d.order())).containsEntry(kept, "null").containsEntry(back, "null");

        UUID session = openSession(s);
        scanBack(s, session, back);
        assertThat(status(back)).isEqualTo("return_pending_inspection");
        assertThat(returnStates(s, d.order()))
            .containsEntry(back, "pending_inspection").containsEntry(kept, "null").hasSize(2);

        grade(s, session, back, "restock");
        assertThat(status(back)).isEqualTo("available");
        assertThat(jdbc.queryForObject("SELECT status::text FROM allocations WHERE piece_id = ?", String.class, back))
            .isEqualTo("released");
        // Before the fix the restocked piece vanished here — the line read "No pieces allocated yet".
        assertThat(returnStates(s, d.order()))
            .containsEntry(back, "restocked").containsEntry(kept, "null").hasSize(2);

        Delivered d2 = delivered(s, 1);
        String dmg = d2.pieces().get(0);
        scanBack(s, session, dmg);
        grade(s, session, dmg, "damaged");
        assertThat(returnStates(s, d2.order())).containsExactly(Map.entry(dmg, "damaged"));

        // Nothing historical rewritten: one return_received + one restocked event, the allocation row kept.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE piece_id = ? " +
            "AND event_type IN ('return_received', 'restocked')", Integer.class, back)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM allocations WHERE piece_id = ?", Integer.class, back))
            .isEqualTo(1);
    }

    @Test
    void t1b_releasedWithoutReturn_staysOff_andAPieceListedOnce() {
        Shop s = shop("RP-t1b");
        Delivered d = delivered(s, 1);
        String p = d.pieces().get(0);
        // A second, released allocation of the same piece on the same order (picked, unscanned, picked again).
        allocate(s, d.item(), p, "released");
        UUID session = openSession(s);
        scanBack(s, session, p);
        grade(s, session, p, "restock");
        assertThat(piecesOn(s, d.order())).extracting(AllocatedPiece::pieceId).containsExactly(p);

        // A piece unscanned before it ever left: released, never came back → not listed (as before).
        UUID o = order(s, "new");
        UUID item = line(s, o, 1);
        String unscanned = piece(s, "available", null);
        allocate(s, item, unscanned, "released");
        assertThat(piecesOn(s, o)).isEmpty();
    }

    // ── t2: Not Traced ───────────────────────────────────────────────────────

    private boolean tagged(UUID orderId) {
        return jdbc.queryForObject("SELECT not_traced_at IS NOT NULL FROM orders WHERE id = ?", Boolean.class, orderId);
    }

    @Test
    void t2_restockedOrder_notTaggedByALaterBostaEvent_realNotTracedStillTagged() {
        Shop s = shop("RP-t2");
        Delivered d = delivered(s, 1);
        shipment(s, d.order(), "return", "returning");   // the customer's return pickup (CRP)
        UUID session = openSession(s);
        scanBack(s, session, d.pieces().get(0));
        grade(s, session, d.pieces().get(0), "restock");
        // The return leg's next Bosta event runs the tagger (BostaWebhookJob step 10.5).
        run(s, () -> tagger.maybeTagNotTraced(d.order(), s.id()));
        assertThat(tagged(d.order())).isFalse();

        // Control 1: shipped by Bosta with no allocation at all — fulfilled outside Traced.
        UUID outside = order(s, "new");
        line(s, outside, 1);
        shipment(s, outside, "forward", "delivered");
        run(s, () -> tagger.maybeTagNotTraced(outside, s.id()));
        assertThat(tagged(outside)).isTrue();

        // Control 2: picked, unscanned (released), then shipped outside Traced — no return scan.
        UUID unscanned = order(s, "new");
        UUID item = line(s, unscanned, 1);
        allocate(s, item, piece(s, "available", null), "released");
        shipment(s, unscanned, "forward", "with_courier");
        run(s, () -> tagger.maybeTagNotTraced(unscanned, s.id()));
        assertThat(tagged(unscanned)).isTrue();
    }

    // ── t3: pickability / demand guard (green before and after) ──────────────

    @Test
    void t3_returnedOrders_neverPickable_neverDemand_scanRefusesAlreadyShipped() {
        Shop s = shop("RP-t3");
        long committedBefore = as(s, () -> stock.forVariant(stock.computeAll(), s.variant()).committed());

        Delivered restocked = delivered(s, 1);
        Delivered damaged = delivered(s, 1);
        UUID session = openSession(s);
        scanBack(s, session, restocked.pieces().get(0));
        grade(s, session, restocked.pieces().get(0), "restock");
        scanBack(s, session, damaged.pieces().get(0));
        grade(s, session, damaged.pieces().get(0), "damaged");

        List<Object> queue = as(s, () -> fulfill.getQueue().stream().map(r -> r.get("id")).toList());
        assertThat(queue).doesNotContain(restocked.order(), damaged.order());
        assertThat(as(s, () -> stock.forVariant(stock.computeAll(), s.variant()).committed()))
            .isEqualTo(committedBefore);

        String shelf = piece(s, "available", null);
        FulfillService.ScanResult r = as(s, () -> fulfill.scan(restocked.order(), "PC-" + shelf, s.owner()));
        assertThat(r.success()).isFalse();
        assertThat(r.code()).isEqualTo("ALREADY_SHIPPED");
        assertThat(status(shelf)).isEqualTo("available");
    }

    // ── t4: Bosta exchange (type 30) ─────────────────────────────────────────

    @Test
    void t4_exchange_oldPieceRestockedOnOriginal_replacementUntouchedAndNotPickable() {
        Shop s = shop("RP-t4");
        Delivered original = delivered(s, 1);
        String old = original.pieces().get(0);

        // The replacement: an internal exchange order, its piece already with the courier on the type-30 leg.
        UUID repl = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, 'awaiting_pickup', 'cod', now()) RETURNING id",
            UUID.class, s.id(), s.store(), "internal:exchange:" + tracking(), "EX-" + RND.nextInt(100000));
        UUID replItem = line(s, repl, 1);
        UUID exLeg = shipment(s, repl, "forward", "with_courier");
        String newPiece = piece(s, "with_courier", repl);
        allocate(s, replItem, newPiece, "packed");
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, matched_order_id, outbound_order_id, raw) " +
                    "VALUES (?, ?, 'matched', ?, ?, '{}'::jsonb)", s.id(), trackingOf(exLeg), original.order(), repl);

        UUID session = openSession(s);
        scanBack(s, session, old);
        assertThat(jdbc.queryForObject("SELECT metadata->>'return_kind' FROM piece_events " +
            "WHERE piece_id = ? AND event_type = 'return_received'", String.class, old)).isEqualTo("exchange_match");
        grade(s, session, old, "restock");

        assertThat(returnStates(s, original.order())).containsExactly(Map.entry(old, "restocked"));
        assertThat(returnStates(s, repl)).containsExactly(Map.entry(newPiece, "null"));
        assertThat(jdbc.queryForObject("SELECT status::text FROM allocations WHERE piece_id = ?", String.class, newPiece))
            .isEqualTo("packed");
        List<Object> queue = as(s, () -> fulfill.getQueue().stream().map(r -> r.get("id")).toList());
        assertThat(queue).doesNotContain(original.order(), repl);
    }

    // ── t5: untracked unit Arrived (green before and after) ──────────────────

    @Test
    void t5_untrackedUnitArrived_writesNoAllocation_orderLinesUnchanged() {
        Shop s = shop("RP-t5");
        UUID o = order(s, "awaiting_pickup");
        UUID item = line(s, o, 1);                       // untracked: shipped outside the pick flow, no allocation
        shipment(s, o, "forward", "delivered");
        UUID crp = shipment(s, o, "return", "returned");  // a courier return no request holds
        var before = as(s, () -> controller.detail(o)).items();

        UUID session = openSession(s);
        run(s, () -> returns.scan(session, trackingOf(crp), s.warehouse(), s.owner()));
        run(s, () -> returns.unitArrived(session, crp, item, 1, "sellable", s.owner()));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM untracked_unit_intakes WHERE order_id = ? " +
            "AND undone_at IS NULL", Integer.class, o)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE oi.order_id = ?", Integer.class, o)).isZero();
        assertThat(as(s, () -> controller.detail(o)).items()).isEqualTo(before);
        assertThat(before.get(0).allocatedPieces()).isEmpty();
    }

    // ── t6: Back to good frees a piece damaged on return ─────────────────────

    @Test
    void t6_damagedOnReturn_backToGood_canBePickedForANewOrder() {
        Shop s = shop("RP-t6");
        Delivered d = delivered(s, 1);
        String p = d.pieces().get(0);
        UUID session = openSession(s);
        scanBack(s, session, p);
        grade(s, session, p, "damaged");
        run(s, () -> adjust.restore(p, "mis_graded", null, s.owner()));
        assertThat(status(p)).isEqualTo("available");
        assertThat(jdbc.queryForObject("SELECT current_order_id FROM pieces WHERE id = ?", UUID.class, p)).isNull();
        assertThat(jdbc.queryForObject("SELECT status::text FROM allocations WHERE piece_id = ?", String.class, p))
            .isEqualTo("released");

        UUID next = order(s, "new");
        line(s, next, 1);
        shipment(s, next, "forward", "created");
        FulfillService.ScanResult r = as(s, () -> fulfill.scan(next, "PC-" + p, s.owner()));
        assertThat(r.code()).isNotEqualTo("ALREADY_RESERVED");
        assertThat(r.success()).isTrue();
        assertThat(status(p)).isEqualTo("reserved");

        // The returned order still shows it, as it came back.
        assertThat(returnStates(s, d.order())).containsExactly(Map.entry(p, "damaged"));
    }

    // ── t7: RLS on the new read ──────────────────────────────────────────────

    private List<Map<String, Object>> readAsAppUser(UUID tenantOrNull, UUID orderId) {
        Supplier<List<Map<String, Object>>> q = () -> appUserTx.execute(st ->
            appUserJdbc.queryForList(OrderController.ORDER_PIECES_SQL, orderId));
        return tenantOrNull == null ? q.get() : TenantContext.runAs(tenantOrNull, q::get);
    }

    @Test
    void t7_appUser_newRead_isTenantScoped_withSameTenantPositiveControl() {
        Shop a = shop("RP-t7-A");
        Shop b = shop("RP-t7-B");
        Delivered d = delivered(a, 1);
        String p = d.pieces().get(0);
        UUID session = openSession(a);
        scanBack(a, session, p);
        grade(a, session, p, "restock");

        List<Map<String, Object>> own = readAsAppUser(a.id(), d.order());
        assertThat(own).hasSize(1);
        assertThat(own.get(0)).containsEntry("piece_id", p).containsEntry("return_state", "restocked");

        assertThat(readAsAppUser(b.id(), d.order())).isEmpty();
        assertThat(readAsAppUser(null, d.order())).isEmpty();
    }
}
