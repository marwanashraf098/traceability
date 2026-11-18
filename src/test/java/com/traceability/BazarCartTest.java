package com.traceability;

import com.traceability.bazar.BazarDayService;
import com.traceability.bazar.BazarException;
import com.traceability.bazar.BazarReportService;
import com.traceability.bazar.BazarSaleService;
import com.traceability.bazar.BazarService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar POS B6c — cart mode: preview (read-only), complete (all or nothing, naming the refused piece), the discount
 * spread (proportional, whole pounds, remainder on the dearest line, always exact), one payment row on the receipt,
 * returns / exchanges of a cart line at its net price, whole-cart undo (window; blocked after a line return), the
 * cart's method change (void + new on the cart's day), totals / close / report with a cart and a discount, idempotent
 * complete, a piece in two concurrent carts, no Shopify rows, and RLS on bazar_receipts (app_user, positive control).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarCartTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
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

    @Autowired BazarService       bazar;
    @Autowired BazarSaleService   sales;
    @Autowired BazarDayService    days;
    @Autowired BazarReportService report;
    @Autowired JdbcTemplate       jdbc;

    UUID tenantId, otherTenantId, ownerId, workerId, mainId;
    UUID vA, vB, vC;          // Linen Shirt M 500, Linen Shirt L 800, Scarf 300
    BazarSaleService.Actor owner, worker;
    int seq = 0;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        otherTenantId = UUID.randomUUID();
        mainId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID(), shirt = UUID.randomUUID(), scarf = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Bazar Cart Co', true)", tenantId);
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Other Cart Co', true)", otherTenantId);
        ownerId  = user("owner", "Owner Omar");
        workerId = user("worker", "Seller Sara");
        owner  = new BazarSaleService.Actor(ownerId, "owner");
        worker = new BazarSaleService.Actor(workerId, "worker");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'bazar-cart.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-BC-1', 'Linen Shirt', 'active')", shirt, tenantId, storeId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-BC-2', 'Scarf', 'active')", scarf, tenantId, storeId);
        vA = variant(shirt, "M", "500.00");
        vB = variant(shirt, "L", "800.00");
        vC = variant(scarf, "Default Title", "300.00");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", mainId, tenantId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clear() { TenantContext.clear(); }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private UUID user(String role, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, ?, ?, 'h', ?::user_role)",
            id, tenantId, name, role + "-" + id + "@bazar-cart-test.com", role);
        return id;
    }

    private UUID variant(UUID productId, String title, String price) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) VALUES (?, ?, ?, ?, ?, ?::numeric)",
            id, tenantId, productId, "VAR-BC-" + title + "-" + id, title, price);
        return id;
    }

    private String piece(UUID variant) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'C' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 7, '0'), 'available', ?)",
            id, tenantId, variant, "PC-" + id, id, mainId);
        return id;
    }

    private String code(String pieceId) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private record Fixture(UUID eventId, UUID transferId, List<String> pieces) {
        String p(int i) { return pieces.get(i); }
    }

    private Fixture live(UUID... variants) {
        Map<String, Object> ev = bazar.create(new BazarService.CreateEvent("Cart Bazar " + (++seq),
            LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3), null, "Cart Hall " + seq,
            List.of("cash", "card", "instapay"), List.of("owner", "manager", "worker"), null, null, null), ownerId);
        UUID eventId = (UUID) ev.get("id");
        List<String> pieces = new ArrayList<>();
        for (UUID v : variants) {
            String p = piece(v);
            assertThat(bazar.scanOut(eventId, "PC-" + p, ownerId, false).success()).isTrue();
            pieces.add(p);
        }
        bazar.markSent(eventId, ownerId);
        bazar.start(eventId, ownerId);
        return new Fixture(eventId, (UUID) ev.get("transfer_id"), pieces);
    }

    private static String crid() { return "req-" + UUID.randomUUID(); }

    private List<String> codes(Fixture f, int... idx) {
        List<String> out = new ArrayList<>();
        for (int i : idx) out.add(code(f.p(i)));
        return out;
    }

    private Map<String, Object> cart(Fixture f, List<String> codes, int discount, String method,
                                     BazarSaleService.Actor who) {
        return sales.completeCart(f.eventId(), codes, BigDecimal.valueOf(discount), method, null, null, crid(), who);
    }

    private static BazarException err(Runnable r) {
        try { r.run(); } catch (BazarException e) { return e; }
        throw new AssertionError("expected a BazarException");
    }

    private String pieceStatus(String id) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, id);
    }

    private String outcome(Fixture f, String id) {
        return jdbc.queryForObject("SELECT outcome FROM transfer_pieces WHERE transfer_id = ? AND piece_id = ?",
            String.class, f.transferId(), id);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Map<String, Object> receipt) {
        return (List<Map<String, Object>>) receipt.get("lines");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, BigDecimal> byMethod(Map<String, Object> m) {
        return (Map<String, BigDecimal>) m.get("by_method");
    }

    private static List<BigDecimal> spread(String discount, String... prices) {
        try {
            Method m = BazarSaleService.class.getDeclaredMethod("spreadDiscount", List.class, BigDecimal.class);
            m.setAccessible(true);
            List<BigDecimal> ps = new ArrayList<>();
            for (String p : prices) ps.add(new BigDecimal(p));
            @SuppressWarnings("unchecked")
            List<BigDecimal> out = (List<BigDecimal>) m.invoke(null, ps, new BigDecimal(discount));
            return out;
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    private static List<String> plain(List<BigDecimal> l) {
        return l.stream().map(b -> b.stripTrailingZeros().toPlainString()).toList();
    }

    // ── The discount spread ──────────────────────────────────────────────────

    @Test
    void discountSpread_proportional_wholePounds_remainderOnTheDearest_alwaysExact() {
        // 100 on 500 / 800 / 300 (1,600): 31.25 → 31, 50, 18.75 → 18 = 99; the 1 left goes on 800 → 51.
        assertThat(plain(spread("100", "500", "800", "300"))).containsExactly("31", "51", "18");
        // Odd split: 10 on 333 / 333 / 334 → 3, 3, 3 = 9; the 1 left on the dearest (334).
        assertThat(plain(spread("10", "333", "333", "334"))).containsExactly("3", "3", "4");
        // Equal prices: the remainder goes on the first of the dearest.
        assertThat(plain(spread("10", "100", "100", "100"))).containsExactly("4", "3", "3");
        assertThat(plain(spread("0", "500", "800"))).containsExactly("0", "0");
        // The whole total off: every line goes to exactly 0.
        assertThat(plain(spread("1600", "500", "800", "300"))).containsExactly("500", "800", "300");
        // A line is never discounted below 0, even when whole pounds don't fit (fractional prices).
        assertThat(plain(spread("3", "1.50", "1.50"))).containsExactly("1.5", "1.5");

        Random rnd = new Random(7);
        for (int run = 0; run < 2000; run++) {
            int n = 1 + rnd.nextInt(8);
            String[] prices = new String[n];
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 0; i < n; i++) {
                BigDecimal p = BigDecimal.valueOf(rnd.nextInt(200_000), 2);
                prices[i] = p.toPlainString();
                sum = sum.add(p);
            }
            int d = sum.intValue() == 0 ? 0 : rnd.nextInt(sum.intValue() + 1);
            List<BigDecimal> shares = spread(String.valueOf(d), prices);
            assertThat(shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(BigDecimal.valueOf(d));
            for (int i = 0; i < n; i++) {
                assertThat(shares.get(i)).isBetween(BigDecimal.ZERO, new BigDecimal(prices[i]));
            }
        }
    }

    // ── Preview ──────────────────────────────────────────────────────────────

    @Test
    void preview_isReadOnly_linesWithPricesAndPerLineErrors() {
        Fixture f = live(vA, vB);
        String stranger = code(piece(vC));                       // never sent to this bazar
        List<String> list = new ArrayList<>(codes(f, 0, 1));
        list.add(stranger);
        list.add(code(f.p(0)));                                  // scanned twice
        Map<String, Object> pv = sales.previewCart(f.eventId(), list, worker);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> l = (List<Map<String, Object>>) pv.get("lines");
        assertThat(l).hasSize(4);
        assertThat((BigDecimal) l.get(0).get("price")).isEqualByComparingTo("500");
        assertThat(l.get(1).get("variant_title")).isEqualTo("L");
        assertThat(l.get(0).get("error")).isNull();
        assertThat(((Map<?, ?>) l.get(2).get("error")).get("code")).isEqualTo("BAZAR_PIECE_NOT_HERE");
        assertThat(((Map<?, ?>) l.get(3).get("error")).get("code")).isEqualTo("BAZAR_CART_DUPLICATE_PIECE");
        assertThat((BigDecimal) pv.get("subtotal")).isEqualByComparingTo("1300");
        assertThat(pv.get("ok")).isEqualTo(false);
        // Nothing sold or reserved.
        assertThat(pieceStatus(f.p(0))).isEqualTo("out_on_transfer");
        assertThat(count("SELECT COUNT(*) FROM bazar_sales WHERE event_id = ?", f.eventId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM bazar_receipts WHERE event_id = ?", f.eventId())).isZero();
    }

    // ── Complete ─────────────────────────────────────────────────────────────

    @Test
    void complete_onePaymentForTheTotal_linesAddUp_customerOnEveryLine_numberSharedWithQuickSales() {
        Fixture f = live(vA, vB, vC, vA);
        Map<String, Object> quick = sales.sell(f.eventId(), code(f.p(3)), crid(), "cash", owner);
        Map<String, Object> r = sales.completeCart(f.eventId(), codes(f, 0, 1, 2), new BigDecimal("100"), "card",
            "  Mona  ", "010 1234 5678", crid(), worker);

        assertThat(r.get("kind")).isEqualTo("receipt");
        assertThat((BigDecimal) r.get("subtotal")).isEqualByComparingTo("1600");
        assertThat((BigDecimal) r.get("discount")).isEqualByComparingTo("100");
        assertThat((BigDecimal) r.get("total")).isEqualByComparingTo("1500");
        assertThat(r.get("method")).isEqualTo("card");
        // B-number from the same counter: the quick sale before it took the previous one.
        int quickNo = Integer.parseInt(((String) quick.get("sale_number")).substring(2));
        assertThat(r.get("sale_number")).isEqualTo(String.format("B-%04d", quickNo + 1));
        List<Map<String, Object>> l = lines(r);
        assertThat(l).extracting(x -> ((BigDecimal) x.get("net_price")).stripTrailingZeros().toPlainString())
            .containsExactly("469", "749", "282");
        assertThat(l).allSatisfy(x -> {
            assertThat(x.get("sale_number")).isEqualTo(r.get("sale_number"));
            assertThat(x.get("customer_name")).isEqualTo("Mona");
            assertThat(x.get("customer_phone")).isEqualTo("010*****678");   // a worker sees it masked
            assertThat(x.get("method")).isEqualTo("card");
        });
        for (int i = 0; i < 3; i++) {
            assertThat(pieceStatus(f.p(i))).isEqualTo("sold");
            assertThat(outcome(f, f.p(i))).isEqualTo("sold");
        }
        assertThat(jdbc.queryForObject("SELECT customer_phone FROM bazar_sales WHERE id = ?", String.class,
            l.get(2).get("id"))).isEqualTo("01012345678");
        // ONE payment row, on the receipt, for the total.
        assertThat(jdbc.queryForList("SELECT method, amount, kind FROM bazar_payments WHERE receipt_id = ?", r.get("id")))
            .singleElement().satisfies(p -> {
                assertThat(p.get("kind")).isEqualTo("payment");
                assertThat((BigDecimal) p.get("amount")).isEqualByComparingTo("1500");
            });
        assertThat(count("SELECT COUNT(*) FROM bazar_payments p JOIN bazar_sales s ON s.id = p.sale_id " +
            "WHERE s.receipt_id = ?", r.get("id"))).isZero();
        // The sales list: the cart is ONE row with its lines, beside the quick sale.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) sales.list(f.eventId(), 50, null, owner).get("items");
        assertThat(items).extracting(x -> x.get("kind")).containsExactly("receipt", "sale");
        assertThat(lines(items.get(0))).hasSize(3);
        assertThat(lines(items.get(0)).get(0).get("customer_phone")).isEqualTo("01012345678");   // owner sees it
    }

    @Test
    void complete_isAllOrNothing_andNamesTheRefusedPiece() {
        Fixture f = live(vA, vB, vC);
        sales.sell(f.eventId(), code(f.p(2)), crid(), null, owner);      // C is already sold
        int counter = count("SELECT last_number FROM bazar_sale_counters WHERE tenant_id = ?", tenantId);

        BazarException e = err(() -> cart(f, codes(f, 0, 1, 2), 50, "cash", worker));
        assertThat(e.code()).isEqualTo(BazarException.Code.BAZAR_ALREADY_SOLD);
        assertThat(e.details()).containsEntry("line_index", 2).containsEntry("line_code", code(f.p(2)));
        assertThat(e.messageEn()).startsWith(code(f.p(2)) + ": Already sold");

        // Nothing of the cart was written: A and B still on the rail, no receipt, no money, no number used.
        assertThat(pieceStatus(f.p(0))).isEqualTo("out_on_transfer");
        assertThat(outcome(f, f.p(1))).isNull();
        assertThat(count("SELECT COUNT(*) FROM bazar_receipts WHERE event_id = ?", f.eventId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM bazar_sales WHERE event_id = ?", f.eventId())).isEqualTo(1);
        assertThat(count("SELECT last_number FROM bazar_sale_counters WHERE tenant_id = ?", tenantId)).isEqualTo(counter);

        // The same for a piece scanned twice, and a piece not at this bazar.
        BazarException dup = err(() -> cart(f, List.of(code(f.p(0)), code(f.p(1)), code(f.p(0))), 0, null, worker));
        assertThat(dup.code()).isEqualTo(BazarException.Code.BAZAR_CART_DUPLICATE_PIECE);
        assertThat(dup.details()).containsEntry("line_index", 2);
        BazarException away = err(() -> cart(f, List.of(code(f.p(0)), code(piece(vA))), 0, null, worker));
        assertThat(away.code()).isEqualTo(BazarException.Code.BAZAR_PIECE_NOT_HERE);
        assertThat(away.details()).containsEntry("line_index", 1);
        assertThat(pieceStatus(f.p(0))).isEqualTo("out_on_transfer");
        assertThat(count("SELECT COUNT(*) FROM bazar_receipts WHERE event_id = ?", f.eventId())).isZero();
    }

    @Test
    void complete_refusesABadDiscount() {
        Fixture f = live(vA, vB);
        for (String d : List.of("1301", "-1", "10.50")) {
            assertThat(err(() -> sales.completeCart(f.eventId(), codes(f, 0, 1), new BigDecimal(d), null, null, null,
                crid(), owner)).code()).as(d).isEqualTo(BazarException.Code.BAZAR_INVALID_DISCOUNT);
        }
        assertThat(err(() -> sales.completeCart(f.eventId(), List.of(), BigDecimal.ZERO, null, null, null, crid(), owner))
            .code()).isEqualTo(BazarException.Code.BAZAR_INVALID_INPUT);
        assertThat(pieceStatus(f.p(0))).isEqualTo("out_on_transfer");
        // The whole total off is allowed: the customer pays 0.
        Map<String, Object> free = cart(f, codes(f, 0, 1), 1300, null, owner);
        assertThat((BigDecimal) free.get("total")).isEqualByComparingTo("0");
        assertThat(lines(free)).allSatisfy(x -> assertThat((BigDecimal) x.get("net_price")).isEqualByComparingTo("0"));
    }

    @Test
    void complete_sameRequestIdTwice_theFirstReceipt() {
        Fixture f = live(vA, vB);
        String req = crid();
        Map<String, Object> a = sales.completeCart(f.eventId(), codes(f, 0, 1), BigDecimal.TEN, null, null, null, req, worker);
        Map<String, Object> b = sales.completeCart(f.eventId(), codes(f, 0, 1), BigDecimal.TEN, null, null, null, req, worker);
        assertThat(a.get("replayed")).isEqualTo(false);
        assertThat(b.get("replayed")).isEqualTo(true);
        assertThat(b.get("id")).isEqualTo(a.get("id"));
        assertThat(count("SELECT COUNT(*) FROM bazar_receipts WHERE event_id = ?", f.eventId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM bazar_payments WHERE receipt_id = ?", a.get("id"))).isEqualTo(1);
    }

    /**
     * One piece in two carts completed at the same moment: an outside transaction holds the piece so both carts are
     * in flight; on release one cart sells, the other gets BAZAR_ALREADY_SOLD naming the piece, and nothing of it
     * (its other piece, a receipt, money) is written.
     */
    @Test
    void aPieceInTwoCarts_concurrently_oneWins_theOtherWritesNothing() throws Exception {
        Fixture f = live(vA, vB, vC);
        String shared = f.p(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection hold = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres")) {
            hold.setAutoCommit(false);
            try (PreparedStatement ps = hold.prepareStatement("SELECT 1 FROM pieces WHERE id = ? FOR UPDATE")) {
                ps.setString(1, shared);
                ps.executeQuery();
            }
            List<String> c1 = List.of(code(f.p(0)), code(shared)), c2 = List.of(code(f.p(1)), code(shared));
            Callable<Object> one = () -> run(f.eventId(), c1);
            Callable<Object> two = () -> run(f.eventId(), c2);
            Future<Object> a = pool.submit(one), b = pool.submit(two);
            long deadline = System.currentTimeMillis() + 20_000;
            while (count("SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'") < 2) {
                if (System.currentTimeMillis() > deadline) throw new AssertionError("carts never both in flight");
                Thread.onSpinWait();
            }
            hold.commit();
            Object ra = a.get(30, TimeUnit.SECONDS), rb = b.get(30, TimeUnit.SECONDS);
            List<Object> results = List.of(ra, rb);
            assertThat(results).filteredOn(x -> x instanceof UUID).hasSize(1);
            assertThat(results).filteredOn(x -> x instanceof BazarException).singleElement().satisfies(x -> {
                BazarException e = (BazarException) x;
                assertThat(e.code()).isEqualTo(BazarException.Code.BAZAR_ALREADY_SOLD);
                assertThat(e.details()).containsEntry("line_index", 1);
            });
            String loserOther = ra instanceof UUID ? f.p(1) : f.p(0);
            assertThat(pieceStatus(loserOther)).isEqualTo("out_on_transfer");
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT COUNT(*) FROM bazar_receipts WHERE event_id = ?", f.eventId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM bazar_sales WHERE event_id = ? AND status = 'active'", f.eventId()))
            .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM bazar_payments WHERE event_id = ?", f.eventId())).isEqualTo(1);
    }

    private Object run(UUID eventId, List<String> codes) {
        TenantContext.set(tenantId);
        try {
            return sales.completeCart(eventId, codes, BigDecimal.ZERO, null, null, null, crid(), worker).get("id");
        } catch (BazarException e) {
            return e;
        } finally {
            TenantContext.clear();
        }
    }

    // ── Return / exchange of a cart line ─────────────────────────────────────

    @Test
    void returnOfACartLine_refundsItsNetShare_onlyThatPiece() {
        Fixture f = live(vA, vB, vC);
        Map<String, Object> r = cart(f, codes(f, 0, 1, 2), 100, "card", worker);   // nets 469 / 749 / 282
        Map<String, Object> back = sales.returnSale(f.eventId(), code(f.p(1)), null, "good", crid(), worker);
        @SuppressWarnings("unchecked")
        Map<String, Object> refund = (Map<String, Object>) back.get("refund");
        assertThat((BigDecimal) refund.get("amount")).isEqualByComparingTo("749");
        assertThat(refund.get("method")).isEqualTo("card");                       // the cart was paid by card
        assertThat(pieceStatus(f.p(1))).isEqualTo("out_on_transfer");
        assertThat(pieceStatus(f.p(0))).isEqualTo("sold");
        assertThat(byMethod(sales.todaySummary(f.eventId(), owner)).get("card")).isEqualByComparingTo("751");
        assertThat(r.get("id")).isNotNull();
    }

    @Test
    void exchangeOfACartLine_creditsItsNetPrice() {
        Fixture f = live(vA, vB, vB);
        // 100 on 500 / 800 (1,300): 38.46 → 38, 61.53 → 61 = 99; +1 on 800 → 62. Nets 462 / 738.
        cart(f, codes(f, 0, 1), 100, "instapay", worker);
        Map<String, Object> x = sales.exchange(f.eventId(), code(f.p(0)), code(f.p(2)), null, null, crid(), worker);
        assertThat((BigDecimal) x.get("difference")).isEqualByComparingTo("338");    // 800 − 462
        @SuppressWarnings("unchecked")
        Map<String, Object> money = (Map<String, Object>) x.get("money");
        assertThat(money.get("kind")).isEqualTo("payment");
        assertThat((BigDecimal) money.get("amount")).isEqualByComparingTo("338");
        assertThat(money.get("method")).isEqualTo("instapay");
    }

    // ── Undo ─────────────────────────────────────────────────────────────────

    @Test
    void undo_wholeCart_sellerWithinTheWindow_everyPieceBackAndTheMoneyVoided() {
        Fixture f = live(vA, vB);
        Map<String, Object> r = cart(f, codes(f, 0, 1), 20, "cash", worker);
        // A line can't be undone on its own.
        UUID lineId = (UUID) lines(r).get(0).get("id");
        BazarException alone = err(() -> sales.undo(f.eventId(), lineId, worker));
        assertThat(alone.code()).isEqualTo(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED);
        assertThat(alone.details()).containsEntry("receipt_id", r.get("id"));

        Map<String, Object> u = sales.undoReceipt(f.eventId(), (UUID) r.get("id"), worker);
        assertThat(u.get("status")).isEqualTo("undone");
        assertThat(lines(u)).allSatisfy(l -> assertThat(l.get("status")).isEqualTo("undone"));
        assertThat(pieceStatus(f.p(0))).isEqualTo("out_on_transfer");
        assertThat(outcome(f, f.p(1))).isNull();
        assertThat(count("SELECT qty_sold FROM transfer_lines WHERE transfer_id = ? ORDER BY qty_sold DESC LIMIT 1",
            f.transferId())).isZero();
        Map<String, Object> today = sales.todaySummary(f.eventId(), owner);
        assertThat((BigDecimal) today.get("total")).isEqualByComparingTo("0");
        assertThat(today.get("sale_count")).isEqualTo(0);
        assertThat(err(() -> sales.undoReceipt(f.eventId(), (UUID) r.get("id"), owner)).code())
            .isEqualTo(BazarException.Code.BAZAR_SALE_UNDONE);
        // The pieces can be sold again.
        assertThat(sales.sell(f.eventId(), code(f.p(0)), crid(), null, worker).get("status")).isEqualTo("active");
    }

    @Test
    void undo_afterTheWindow_onlyOwnerOrManager_andNeverAfterALineReturn() {
        Fixture f = live(vA, vB, vA, vB);
        Map<String, Object> late = cart(f, codes(f, 0, 1), 0, null, worker);
        jdbc.update("UPDATE bazar_receipts SET sold_at = now() - interval '5 minutes' WHERE id = ?", late.get("id"));
        assertThat(err(() -> sales.undoReceipt(f.eventId(), (UUID) late.get("id"), worker)).code())
            .isEqualTo(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED);
        assertThat(sales.undoReceipt(f.eventId(), (UUID) late.get("id"), owner).get("status")).isEqualTo("undone");

        Map<String, Object> r = cart(f, codes(f, 2, 3), 0, null, worker);
        sales.returnSale(f.eventId(), code(f.p(3)), null, "good", crid(), worker);
        BazarException e = err(() -> sales.undoReceipt(f.eventId(), (UUID) r.get("id"), owner));
        assertThat(e.code()).isEqualTo(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED);
        assertThat(e.messageEn()).contains("returned or exchanged");
        assertThat(pieceStatus(f.p(2))).isEqualTo("sold");
    }

    // ── Method change on the cart ────────────────────────────────────────────

    @Test
    void methodChange_onTheCart_voidPlusNewForTheWholeTotal_onTheCartsDay() {
        Fixture f = live(vA, vB, vC);
        Map<String, Object> r = cart(f, codes(f, 0, 1), 100, "card", worker);           // total 1,200
        UUID lineId = (UUID) lines(r).get(0).get("id");
        assertThat(err(() -> sales.changePayment(f.eventId(), lineId, "cash", worker)).code())
            .isEqualTo(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED);

        Map<String, Object> changed = sales.changeReceiptPayment(f.eventId(), (UUID) r.get("id"), "cash", worker);
        assertThat(changed.get("method")).isEqualTo("cash");
        assertThat(lines(changed)).allSatisfy(l -> assertThat(l.get("method")).isEqualTo("cash"));
        assertThat(jdbc.queryForList("SELECT kind, method, amount FROM bazar_payments WHERE receipt_id = ? " +
            "ORDER BY created_at, id", r.get("id"))).extracting(p -> p.get("kind") + ":" + p.get("method") + ":"
            + ((BigDecimal) p.get("amount")).stripTrailingZeros().toPlainString())
            .containsExactly("payment:card:1200", "void:card:1200", "payment:cash:1200");
        Map<String, BigDecimal> m = byMethod(sales.todaySummary(f.eventId(), owner));
        assertThat(m.get("cash")).isEqualByComparingTo("1200");
        assertThat(m.get("card")).isEqualByComparingTo("0");

        // The B4 rule: a correction stays on the cart's day. Move the cart's money to yesterday, change it again:
        // the new rows count yesterday, nothing moves today.
        jdbc.update("UPDATE bazar_payments SET business_day = business_day - 1 WHERE receipt_id = ?", r.get("id"));
        sales.changeReceiptPayment(f.eventId(), (UUID) r.get("id"), "instapay", owner);
        assertThat(count("SELECT COUNT(*) FROM bazar_payments WHERE receipt_id = ? " +
            "AND business_day = (now() AT TIME ZONE 'Africa/Cairo')::date", r.get("id"))).isZero();
        assertThat((BigDecimal) sales.todaySummary(f.eventId(), owner).get("total")).isEqualByComparingTo("0");
    }

    // ── Totals / close / report ──────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void totalsCloseAndReport_countCartLinesLikeQuickSales_plusDiscounts() {
        Fixture f = live(vA, vB, vC, vA);
        cart(f, codes(f, 0, 1, 2), 100, "card", owner);          // nets 469 / 749 / 282 = 1,500 card
        sales.sell(f.eventId(), code(f.p(3)), crid(), "cash", owner);          // 500 cash
        Map<String, Object> t = sales.todaySummary(f.eventId(), owner);
        assertThat(t.get("sale_count")).isEqualTo(2);                          // a receipt + a quick sale
        assertThat(t.get("pieces")).isEqualTo(4);
        assertThat((BigDecimal) t.get("total")).isEqualByComparingTo("2000");
        assertThat((BigDecimal) t.get("revenue")).isEqualByComparingTo("2000");
        assertThat((BigDecimal) t.get("discounts")).isEqualByComparingTo("100");

        // Return the scarf (net 282, by card): 1,718 left; its 18 of discount is no longer given.
        sales.returnSale(f.eventId(), code(f.p(2)), null, "good", crid(), owner);
        t = sales.todaySummary(f.eventId(), owner);
        assertThat(t.get("sale_count")).isEqualTo(2);
        assertThat(t.get("pieces")).isEqualTo(3);
        assertThat((BigDecimal) t.get("total")).isEqualByComparingTo("1718");
        assertThat((BigDecimal) t.get("revenue")).isEqualByComparingTo("1718");
        assertThat((BigDecimal) t.get("discounts")).isEqualByComparingTo("82");
        assertThat(byMethod(t).get("card")).isEqualByComparingTo("1218");
        assertThat(byMethod(t).get("cash")).isEqualByComparingTo("500");
        List<Map<String, Object>> perSeller = (List<Map<String, Object>>) t.get("per_seller");
        assertThat(perSeller).singleElement().satisfies(s -> {
            assertThat(s.get("sale_count")).isEqualTo(2);
            assertThat(s.get("pieces")).isEqualTo(3);
            assertThat((BigDecimal) s.get("total")).isEqualByComparingTo("1718");
        });

        // Close the day: expected money = the same 500 cash / 1,218 card.
        LocalDate day = jdbc.queryForObject("SELECT (now() AT TIME ZONE 'Africa/Cairo')::date", LocalDate.class);
        Map<String, Object> closed = days.close(f.eventId(), day, new BazarDayService.Entered(new BigDecimal("500"),
            new BigDecimal("1218"), true, null, BigDecimal.ZERO), null, crid(), owner);
        assertThat(String.valueOf(closed)).contains("1218");
        List<Map<String, Object>> list = (List<Map<String, Object>>) days.days(f.eventId(), owner).get("days");
        Map<String, Object> money = (Map<String, Object>) list.get(list.size() - 1).get("money");
        assertThat((BigDecimal) money.get("total")).isEqualByComparingTo("1718");
        assertThat(list.get(list.size() - 1).get("status")).isEqualTo("closed_balanced");

        Map<String, Object> rep = report.report(f.eventId(), owner);
        assertThat((BigDecimal) rep.get("revenue")).isEqualByComparingTo("1718");
        assertThat(rep.get("pieces_sold")).isEqualTo(3);
        assertThat(rep.get("sales")).isEqualTo(2);
        assertThat((BigDecimal) rep.get("average_sale")).isEqualByComparingTo("859");   // 1,718 / 2 sales
        assertThat((BigDecimal) rep.get("discounts")).isEqualByComparingTo("82");
        assertThat((List<Map<String, Object>>) rep.get("per_seller")).singleElement()
            .satisfies(s -> assertThat((BigDecimal) s.get("total")).isEqualByComparingTo("1718"));
        // Linen Shirt: M 469 (cart) + M 500 (quick) + L 749 (cart) = 1,718 at their net prices.
        assertThat((List<Map<String, Object>>) rep.get("top_products")).first()
            .satisfies(p -> assertThat((BigDecimal) p.get("revenue")).isEqualByComparingTo("1718"));
        List<Map<String, Object>> hours = (List<Map<String, Object>>) rep.get("busiest_hours");
        assertThat(hours.stream().mapToInt(h -> (Integer) h.get("sales")).sum()).isEqualTo(2);
    }

    // ── No Shopify, RLS ──────────────────────────────────────────────────────

    @Test
    void noShopifyRows_fromAnyCartAction() {
        Fixture f = live(vA, vB, vC);
        int adj = count("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?", tenantId);
        int syncs = count("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE tenant_id = ?", tenantId);
        Map<String, Object> r = cart(f, codes(f, 0, 1), 10, "card", owner);
        sales.changeReceiptPayment(f.eventId(), (UUID) r.get("id"), "cash", owner);
        sales.returnSale(f.eventId(), code(f.p(1)), null, "damaged", crid(), owner);
        Map<String, Object> r2 = cart(f, codes(f, 2), 0, null, owner);
        sales.undoReceipt(f.eventId(), (UUID) r2.get("id"), owner);
        assertThat(count("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?", tenantId)).isEqualTo(adj);
        assertThat(count("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE tenant_id = ?", tenantId)).isEqualTo(syncs);
    }

    @Test
    void rls_appUser_seesOnlyItsOwnReceipts_andCannotDeleteThem() {
        Fixture f = live(vA);
        Map<String, Object> r = cart(f, codes(f, 0), 0, null, owner);
        TenantAwareDataSource ds = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        String sql = "SELECT COUNT(*) FROM bazar_receipts WHERE id = ?";
        String pay = "SELECT COUNT(*) FROM bazar_payments WHERE receipt_id = ?";

        TenantContext.set(otherTenantId);
        assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, r.get("id")))).isZero();
        assertThat((Integer) tx.execute(s -> app.queryForObject(pay, Integer.class, r.get("id")))).isZero();
        TenantContext.clear();
        assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, r.get("id")))).as("no tenant").isZero();
        TenantContext.set(tenantId);   // same-tenant positive control
        assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, r.get("id")))).isEqualTo(1);
        assertThat((Integer) tx.execute(s -> app.queryForObject(pay, Integer.class, r.get("id")))).isEqualTo(1);
        Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
            () -> tx.execute(s -> app.update("DELETE FROM bazar_receipts WHERE id = ?", r.get("id"))));
        Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
            () -> tx.execute(s -> app.update("UPDATE bazar_payments SET amount = 0 WHERE receipt_id = ?", r.get("id"))));
    }
}
