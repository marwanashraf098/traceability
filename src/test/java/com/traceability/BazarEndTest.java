package com.traceability;

import com.traceability.bazar.BazarDayService;
import com.traceability.bazar.BazarEndService;
import com.traceability.bazar.BazarException;
import com.traceability.bazar.BazarReportService;
import com.traceability.bazar.BazarSaleService;
import com.traceability.bazar.BazarService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar POS B5 — end the event (blocked by an open / changed day; idempotent; selling, returns, exchanges and edits
 * refused after it), leftovers on the SAME transfer (scan back good → available at main; damaged → brought home
 * still damaged; replay by request id; mark lost only outstanding pieces; close refused until sent = sold + back +
 * damaged + lost), and the report on a seeded event whose numbers are computed by hand below (a return, an exchange
 * up and one down, a method correction across days, a closed day with shortfalls), plus the customers CSV (owner /
 * manager only, audit-logged). Shopify's side is in BazarEndShopifyTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarEndTest {

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
    @Autowired BazarEndService    end;
    @Autowired BazarReportService report;
    @Autowired JdbcTemplate       jdbc;

    UUID tenantId, ownerId, workerId, mainId;
    UUID vA, vB, vC;          // Linen Shirt M 500, Linen Shirt L 800, Scarf (Default Title) 300
    BazarSaleService.Actor owner, worker;
    int seq = 0;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        mainId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID(), shirt = UUID.randomUUID(), scarf = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Bazar End Co', true)", tenantId);
        ownerId  = user("owner", "Owner Omar");
        workerId = user("worker", "Seller Sara");
        owner  = new BazarSaleService.Actor(ownerId, "owner");
        worker = new BazarSaleService.Actor(workerId, "worker");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'bazar-end.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-BE-1', 'Linen Shirt', 'active')", shirt, tenantId, storeId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-BE-2', 'Scarf', 'active')", scarf, tenantId, storeId);
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
            id, tenantId, name, role + "-" + id + "@bazar-end-test.com", role);
        return id;
    }

    private UUID variant(UUID productId, String title, String price) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) VALUES (?, ?, ?, ?, ?, ?::numeric)",
            id, tenantId, productId, "VAR-BE-" + title + "-" + id, title, price);
        return id;
    }

    private String piece(UUID variant) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 7, '0'), 'available', ?)",
            id, tenantId, variant, "PC-" + id, id, mainId);
        return id;
    }

    private String code(String pieceId) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private record Fixture(UUID eventId, UUID transferId, UUID hallId, List<String> pieces) {
        String p(int i) { return pieces.get(i); }
    }

    private Fixture live(UUID... variants) {
        Map<String, Object> ev = bazar.create(new BazarService.CreateEvent("End Bazar " + (++seq),
            LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3), null, "End Hall " + seq,
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
        return new Fixture(eventId, (UUID) ev.get("transfer_id"), (UUID) ev.get("location_id"), pieces);
    }

    private static String crid() { return "req-" + UUID.randomUUID(); }

    private UUID sell(Fixture f, int i, String method, BazarSaleService.Actor who) {
        return (UUID) sales.sell(f.eventId(), code(f.p(i)), crid(), method, who).get("id");
    }

    private LocalDate today() {
        return jdbc.queryForObject("SELECT (now() AT TIME ZONE 'Africa/Cairo')::date", LocalDate.class);
    }

    private void closeDay(Fixture f, LocalDate day, String cash, String card, String flt) {
        days.close(f.eventId(), day, new BazarDayService.Entered(new BigDecimal(cash), new BigDecimal(card), true, null,
            flt == null ? null : new BigDecimal(flt)), null, crid(), owner);
    }

    /** Moves everything of the event one Cairo day back (its start, its sales, its money rows). */
    private void shiftBackOneDay(Fixture f) {
        jdbc.update("UPDATE bazar_events SET started_at = started_at - interval '1 day' WHERE id = ?", f.eventId());
        jdbc.update("UPDATE bazar_sales SET sold_at = sold_at - interval '1 day', created_at = created_at - interval '1 day', " +
            "business_day = business_day - 1 WHERE event_id = ?", f.eventId());
        jdbc.update("UPDATE bazar_payments SET created_at = created_at - interval '1 day', business_day = business_day - 1 " +
            "WHERE sale_id IN (SELECT id FROM bazar_sales WHERE event_id = ?)", f.eventId());
    }

    private static BazarException err(Runnable r) {
        try { r.run(); } catch (BazarException e) { return e; }
        throw new AssertionError("expected a BazarException");
    }

    private static BazarException.Code codeOf(Runnable r) { return err(r).code(); }

    private String pieceStatus(String id) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, id);
    }

    private UUID pieceLocation(String id) {
        return jdbc.queryForObject("SELECT current_location_id FROM pieces WHERE id = ?", UUID.class, id);
    }

    private String outcome(Fixture f, String id) {
        return jdbc.queryForObject("SELECT outcome FROM transfer_pieces WHERE transfer_id = ? AND piece_id = ?",
            String.class, f.transferId(), id);
    }

    private String eventStatus(Fixture f) {
        return jdbc.queryForObject("SELECT status FROM bazar_events WHERE id = ?", String.class, f.eventId());
    }

    private String transferStatus(Fixture f) {
        return jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, f.transferId());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> counts(Map<String, Object> leftovers) {
        return (Map<String, Object>) leftovers.get("counts");
    }

    private static void money(Object actual, String expected) {
        assertThat(new BigDecimal(String.valueOf(actual))).isEqualByComparingTo(expected);
    }

    /** A live event with 1 piece sold (closed day) and the rest still out — ready to end. */
    private Fixture endable(UUID... variants) {
        Fixture f = live(variants);
        sell(f, 0, "cash", owner);
        closeDay(f, today(), "500", "0", "0");
        return f;
    }

    // ── End ──────────────────────────────────────────────────────────────────

    @Test
    void end_blockedByAnOpenDay_thenByAChangedDay_namesTheDays() {
        Fixture f = live(vA, vA);
        sell(f, 0, "cash", owner);
        BazarException e = err(() -> end.end(f.eventId(), owner));
        assertThat(e.code()).isEqualTo(BazarException.Code.BAZAR_DAYS_NOT_CLOSED);
        @SuppressWarnings("unchecked") List<Map<String, Object>> d = (List<Map<String, Object>>) e.details().get("days");
        assertThat(d).extracting(x -> x.get("business_day")).containsExactly(today().toString());
        assertThat(d.get(0).get("status")).isEqualTo("open");

        closeDay(f, today(), "500", "0", "0");
        sell(f, 1, "cash", owner);                                               // changed after close
        BazarException again = err(() -> end.end(f.eventId(), owner));
        assertThat(again.code()).isEqualTo(BazarException.Code.BAZAR_DAYS_NOT_CLOSED);
        @SuppressWarnings("unchecked") List<Map<String, Object>> d2 = (List<Map<String, Object>>) again.details().get("days");
        assertThat(d2.get(0).get("status")).isEqualTo("changed_after_close");
        assertThat(eventStatus(f)).isEqualTo("live");
        assertThat(transferStatus(f)).isEqualTo("sent");

        closeDay(f, today(), "1000", "0", null);
        assertThat(end.end(f.eventId(), owner).get("replayed")).isEqualTo(false);
        assertThat(eventStatus(f)).isEqualTo("ended");
        assertThat(transferStatus(f)).isEqualTo("reconciling");
    }

    @Test
    void end_aDayWithNoMoney_doesNotBlock_andEndIsIdempotent() {
        Fixture f = live(vA);
        Map<String, Object> first = end.end(f.eventId(), owner);
        Map<String, Object> second = end.end(f.eventId(), owner);
        assertThat(first.get("replayed")).isEqualTo(false);
        assertThat(second.get("replayed")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT ended_by FROM bazar_events WHERE id = ?", UUID.class, f.eventId()))
            .isEqualTo(ownerId);
    }

    @Test
    void afterEnd_sellingReturnsExchangesAndEditsAreRefused_withEventEnded() {
        Fixture f = live(vA, vA, vB);
        UUID sale = sell(f, 0, "cash", owner);
        closeDay(f, today(), "500", "0", "0");
        end.end(f.eventId(), owner);
        BazarException.Code ended = BazarException.Code.BAZAR_EVENT_ENDED;
        assertThat(codeOf(() -> sales.sell(f.eventId(), code(f.p(1)), crid(), null, owner))).isEqualTo(ended);
        assertThat(codeOf(() -> sales.returnSale(f.eventId(), code(f.p(0)), null, "good", crid(), owner))).isEqualTo(ended);
        assertThat(codeOf(() -> sales.exchange(f.eventId(), code(f.p(0)), code(f.p(2)), null, null, crid(), owner)))
            .isEqualTo(ended);
        assertThat(codeOf(() -> sales.changePayment(f.eventId(), sale, "card", owner))).isEqualTo(ended);
        assertThat(codeOf(() -> sales.undo(f.eventId(), sale, owner))).isEqualTo(ended);
    }

    @Test
    void workersCantEnd_norBringLeftoversBack_norReadTheReport() {
        Fixture f = live(vA);
        assertThat(codeOf(() -> end.end(f.eventId(), worker))).isEqualTo(BazarException.Code.BAZAR_MANAGERS_ONLY);
        assertThat(codeOf(() -> end.leftovers(f.eventId(), worker))).isEqualTo(BazarException.Code.BAZAR_MANAGERS_ONLY);
        assertThat(codeOf(() -> report.report(f.eventId(), worker))).isEqualTo(BazarException.Code.BAZAR_MANAGERS_ONLY);
        assertThat(codeOf(() -> report.customersCsv(f.eventId(), worker))).isEqualTo(BazarException.Code.BAZAR_MANAGERS_ONLY);
        assertThat(eventStatus(f)).isEqualTo("live");
    }

    // ── Leftovers ────────────────────────────────────────────────────────────

    @Test
    void scanBack_beforeEnd_isRefused() {
        Fixture f = live(vA);
        assertThat(codeOf(() -> end.scanBack(f.eventId(), code(f.p(0)), crid(), owner)))
            .isEqualTo(BazarException.Code.BAZAR_EVENT_NOT_ENDED);
    }

    @Test
    void scanBackGood_availableAtMain_sameRequestIdReplays_anotherScanSaysAlreadyBack() {
        Fixture f = endable(vA, vA);
        end.end(f.eventId(), owner);
        String id = crid();
        Map<String, Object> r = end.scanBack(f.eventId(), code(f.p(1)), id, owner);
        assertThat(r.get("result")).isEqualTo("back_good");
        assertThat(r.get("replayed")).isEqualTo(false);
        assertThat(pieceStatus(f.p(1))).isEqualTo("available");
        assertThat(pieceLocation(f.p(1))).isEqualTo(mainId);
        assertThat(outcome(f, f.p(1))).isEqualTo("returned_good");

        Map<String, Object> again = end.scanBack(f.eventId(), code(f.p(1)), id, owner);
        assertThat(again.get("replayed")).isEqualTo(true);
        assertThat(again.get("result")).isEqualTo("back_good");
        assertThat(codeOf(() -> end.scanBack(f.eventId(), code(f.p(1)), crid(), owner)))
            .isEqualTo(BazarException.Code.BAZAR_ALREADY_BACK);
        assertThat(codeOf(() -> end.scanBack(f.eventId(), code(f.p(0)), crid(), owner)))
            .isEqualTo(BazarException.Code.BAZAR_ALREADY_SOLD);
        assertThat(jdbc.queryForObject("SELECT qty_returned_good FROM transfer_lines WHERE transfer_id = ?",
            Integer.class, f.transferId())).isEqualTo(1);
    }

    @Test
    void scanBackDamaged_arrivesAtMainStillDamaged_countsUnchanged() {
        Fixture f = live(vA, vA);
        sell(f, 0, "cash", owner);
        sales.returnSale(f.eventId(), code(f.p(0)), null, "damaged", crid(), owner);
        sell(f, 1, "cash", owner);
        closeDay(f, today(), "500", "0", "0");
        end.end(f.eventId(), owner);
        assertThat(pieceLocation(f.p(0))).isEqualTo(f.hallId());
        assertThat(counts(end.leftovers(f.eventId(), owner)).get("damaged_at_bazar")).isEqualTo(1);

        Map<String, Object> r = end.scanBack(f.eventId(), code(f.p(0)), crid(), owner);
        assertThat(r.get("result")).isEqualTo("back_damaged");
        assertThat(pieceStatus(f.p(0))).isEqualTo("damaged");
        assertThat(pieceLocation(f.p(0))).isEqualTo(mainId);
        assertThat(outcome(f, f.p(0))).isEqualTo("condemned");
        @SuppressWarnings("unchecked") Map<String, Object> c = counts((Map<String, Object>) r.get("leftovers"));
        assertThat(c.get("damaged")).isEqualTo(1);
        assertThat(c.get("damaged_at_bazar")).isEqualTo(0);
        assertThat(jdbc.queryForList("SELECT event_type FROM piece_events WHERE piece_id = ? ORDER BY occurred_at, id",
            String.class, f.p(0))).last().isEqualTo("bazar_damaged_brought_back");
        assertThat(codeOf(() -> end.scanBack(f.eventId(), code(f.p(0)), crid(), owner)))
            .isEqualTo(BazarException.Code.BAZAR_ALREADY_BACK);
    }

    @Test
    void markLost_onlyOutstandingPieces_soldNeverLost() {
        Fixture f = endable(vA, vA, vA);
        end.end(f.eventId(), owner);
        assertThat(codeOf(() -> end.markLost(f.eventId(), List.of(f.p(1), f.p(0)), owner)))
            .isEqualTo(BazarException.Code.BAZAR_NOT_OUTSTANDING);
        assertThat(outcome(f, f.p(1))).isNull();
        assertThat(pieceStatus(f.p(0))).isEqualTo("sold");

        Map<String, Object> l = end.markLost(f.eventId(), List.of(f.p(2)), owner);
        assertThat(pieceStatus(f.p(2))).isEqualTo("lost");
        assertThat(outcome(f, f.p(2))).isEqualTo("lost");
        assertThat(outcome(f, f.p(1))).isNull();                               // the exact piece, not oldest of all
        assertThat(counts(l).get("lost")).isEqualTo(1);
        assertThat(counts(l).get("outstanding")).isEqualTo(1);
    }

    @Test
    void closeTransfer_refusedUntil_sentEqualsSoldBackDamagedLost_thenIdempotent() {
        Fixture f = endable(vA, vA, vA);
        end.end(f.eventId(), owner);
        end.scanBack(f.eventId(), code(f.p(1)), crid(), owner);
        BazarException e = err(() -> end.closeTransfer(f.eventId(), owner));
        assertThat(e.code()).isEqualTo(BazarException.Code.BAZAR_LEFTOVERS_OUTSTANDING);
        assertThat(e.details().get("outstanding")).isEqualTo(1);
        assertThat(transferStatus(f)).isEqualTo("reconciling");

        end.markLost(f.eventId(), List.of(f.p(2)), owner);
        Map<String, Object> closed = end.closeTransfer(f.eventId(), owner);
        assertThat(closed.get("balanced")).isEqualTo(true);
        assertThat(closed.get("transfer_status")).isEqualTo("closed");
        Map<String, Object> c = counts(closed);
        assertThat(c.get("sent")).isEqualTo(3);
        assertThat((Integer) c.get("sold") + (Integer) c.get("back") + (Integer) c.get("damaged") + (Integer) c.get("lost"))
            .isEqualTo(3);
        assertThat(end.closeTransfer(f.eventId(), owner).get("replayed")).isEqualTo(true);
        assertThat(pieceStatus(f.p(0))).isEqualTo("sold");                     // sold stays sold
    }

    // ── Report ───────────────────────────────────────────────────────────────

    /**
     * Sent: shirt M ×4, shirt L ×2, scarf ×3 = 9.
     * Yesterday: s1 M 500 cash (worker), s2 M 500 card (owner), s3 scarf 300 cash (worker, Laila 01012345678),
     *   s4 L 800 cash (owner). Closed: float 1,000, cash 2,550 of 2,600 (−50), card 480 of 500 (−20) → 70 not received.
     * Today: s1 returned (cash −500); exchange UP s3 → L 800 (InstaPay +500); exchange DOWN s2 → scarf 300 (cash −200);
     *   s4's method corrected cash → InstaPay (stays on yesterday: cash −800, InstaPay +800); s5 M 500 cash (worker, Omar).
     * Money: cash 1,600 − 800 (yesterday) − 500 − 200 + 500 (today) = 600; card 500; InstaPay 800 + 500 = 1,300 → 2,400.
     * Revenue: sold 500+500+300+800+800+300+500 = 3,700 − returned 500+300+500 = 2,400. Pieces sold (active) 4 →
     * sell-through 4/9, average 600. Returns 1, exchanges 2. Yesterday 2,100, today 300.
     */
    @Test
    @SuppressWarnings("unchecked")
    void report_onASeededEvent_matchesHandComputedValues() {
        Fixture f = live(vA, vA, vA, vA, vB, vB, vC, vC, vC);
        UUID s1 = sell(f, 0, "cash", worker);
        UUID s2 = sell(f, 1, "card", owner);
        UUID s3 = sell(f, 6, "cash", worker);
        sales.setCustomer(f.eventId(), s3, "Laila", "01012345678", owner);
        UUID s4 = sell(f, 4, "cash", owner);
        shiftBackOneDay(f);
        LocalDate yesterday = today().minusDays(1);
        closeDay(f, yesterday, "2550", "480", "1000");

        sales.returnSale(f.eventId(), code(f.p(0)), null, "good", crid(), owner);
        sales.exchange(f.eventId(), code(f.p(6)), code(f.p(5)), "instapay", null, crid(), owner);   // up +500
        sales.exchange(f.eventId(), code(f.p(1)), code(f.p(7)), "cash", null, crid(), owner);       // down −200
        sales.changePayment(f.eventId(), s4, "instapay", owner);                                    // across days
        UUID s5 = sell(f, 2, "cash", worker);
        sales.setCustomer(f.eventId(), s5, "Omar", null, owner);

        Map<String, Object> r = report.report(f.eventId(), owner);
        money(r.get("revenue"), "2400");
        money(r.get("money_in"), "2400");
        assertThat(r.get("pieces_sold")).isEqualTo(4);
        assertThat(r.get("pieces_sent")).isEqualTo(9);
        money(r.get("sell_through"), "0.4444");
        money(r.get("average_sale"), "600");
        assertThat(r.get("returns")).isEqualTo(1);
        assertThat(r.get("exchanges")).isEqualTo(2);

        List<Map<String, Object>> byDay = (List<Map<String, Object>>) r.get("sales_by_day");
        assertThat(byDay).hasSize(2);
        money(byDay.get(0).get("revenue"), "2100");
        assertThat(byDay.get(0).get("status")).isEqualTo("changed_after_close");
        money(byDay.get(1).get("revenue"), "300");

        List<Map<String, Object>> split = (List<Map<String, Object>>) r.get("payment_split");
        Map<String, BigDecimal> byMethod = new java.util.HashMap<>();
        split.forEach(x -> byMethod.put((String) x.get("method"), new BigDecimal(String.valueOf(x.get("amount")))));
        money(byMethod.get("cash"), "600");
        money(byMethod.get("card"), "500");
        money(byMethod.get("instapay"), "1300");

        Map<String, Object> nr = (Map<String, Object>) r.get("money_not_received");
        money(nr.get("cash_short"), "50");
        money(nr.get("card_short"), "20");
        money(nr.get("instapay_not_received"), "0");
        money(nr.get("total"), "70");
        assertThat(nr.get("days_closed")).isEqualTo(1);
        assertThat(nr.get("days_not_closed")).isEqualTo(2);

        List<Map<String, Object>> products = (List<Map<String, Object>>) r.get("top_products");
        assertThat(products.get(0).get("product_title")).isEqualTo("Linen Shirt");
        assertThat(products.get(0).get("sold")).isEqualTo(3);
        assertThat(products.get(0).get("sent")).isEqualTo(6);
        money(products.get(0).get("sell_through"), "0.5");
        money(products.get(0).get("revenue"), "2100");
        assertThat(products.get(1).get("product_title")).isEqualTo("Scarf");
        assertThat(products.get(1).get("sold")).isEqualTo(1);
        money(products.get(1).get("sell_through"), "0.3333");

        List<Map<String, Object>> sizes = (List<Map<String, Object>>) r.get("top_sizes");
        assertThat(sizes).extracting(x -> x.get("variant_title")).containsExactly("L", "M");   // Default Title hidden
        assertThat(sizes.get(0).get("sold")).isEqualTo(2);
        money(sizes.get(0).get("sell_through"), "1");

        List<Map<String, Object>> sellers = (List<Map<String, Object>>) r.get("per_seller");
        assertThat(sellers).extracting(x -> x.get("seller_name")).containsExactly("Owner Omar", "Seller Sara");
        assertThat(sellers.get(0).get("pieces")).isEqualTo(3);
        money(sellers.get(0).get("total"), "1900");
        assertThat(sellers.get(1).get("pieces")).isEqualTo(1);
        money(sellers.get(1).get("total"), "500");

        assertThat(r.get("customers_saved")).isEqualTo(2);
        List<Map<String, Object>> hours = (List<Map<String, Object>>) r.get("busiest_hours");
        assertThat(hours.stream().mapToInt(h -> (Integer) h.get("sales")).sum()).isEqualTo(7);
        Map<String, Object> tr = (Map<String, Object>) r.get("transfer");
        assertThat(tr.get("sent")).isEqualTo(9);
        assertThat(tr.get("sold")).isEqualTo(4);
        assertThat(s1).isNotNull();
    }

    @Test
    void customersCsv_ownerOnly_auditLogged_oneRowPerCustomer_formulaSafe() {
        Fixture f = live(vA, vB, vA);
        UUID a = sell(f, 0, "cash", owner);
        sales.setCustomer(f.eventId(), a, "=HYPERLINK(\"x\")", "01012345678", owner);
        UUID b = sell(f, 1, "cash", owner);
        sales.setCustomer(f.eventId(), b, "Nour", "01012345678", owner);           // same phone → same customer
        sell(f, 2, "cash", owner);                                               // no customer → no row
        int audits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'bazar_customers_export'",
            Integer.class, tenantId);

        String csv = report.customersCsv(f.eventId(), owner);
        String[] lines = csv.split("\n");
        assertThat(lines[0]).isEqualTo("name,phone,pieces,total,last_visit");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).startsWith("Nour,01012345678,2,1300.00,");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'bazar_customers_export'",
            Integer.class, tenantId)).isEqualTo(audits + 1);
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM audit_log WHERE tenant_id = ? AND action = " +
            "'bazar_customers_export' ORDER BY created_at DESC LIMIT 1", UUID.class, tenantId)).isEqualTo(ownerId);
        assertThat(BazarReportService.class).isNotNull();
        assertThat(cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
    }

    private static String cell(String v) {
        try {
            var m = BazarReportService.class.getDeclaredMethod("cell", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, v);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    /**
     * The damaged bring-home moves the piece only through InventoryLedger (the one writer of piece changes): the
     * bazar method in TransferService has no raw UPDATE of pieces and calls ledger.bringBackDamaged, and that ledger
     * method has exactly one caller.
     */
    @Test
    void damagedBringHome_movesThePieceOnlyThroughInventoryLedger() throws java.io.IOException {
        String ts = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/java/com/traceability/inventory/TransferService.java"));
        int start = ts.indexOf("public boolean bringBackDamagedForBazar(");
        assertThat(start).isPositive();
        int end = ts.indexOf("\n    }\n", start);
        String body = ts.substring(start, end);
        assertThat(body).doesNotContainIgnoringCase("UPDATE pieces");
        assertThat(body).contains("ledger.bringBackDamaged(");

        String ledger = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/java/com/traceability/inventory/InventoryLedger.java"));
        int ls = ledger.indexOf("public boolean bringBackDamaged(");
        String lbody = ledger.substring(ls, ledger.indexOf("\n    }\n", ls));
        assertThat(lbody).contains("UPDATE pieces SET current_location_id").contains("bazar_damaged_brought_back");

        List<String> callers;
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            callers = files.filter(f -> f.toString().endsWith(".java")).filter(f -> {
                try { return java.nio.file.Files.readString(f).contains(".bringBackDamaged("); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            }).map(f -> f.getFileName().toString()).toList();
        }
        assertThat(callers).containsExactly("TransferService.java");
    }
}
