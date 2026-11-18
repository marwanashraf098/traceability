package com.traceability;

import com.traceability.bazar.BazarDayService;
import com.traceability.bazar.BazarException;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar POS B4 — close the day: the mockup's example exactly (float 1,000; cash 18,200 / InstaPay 9,850 / card 3,400;
 * entered 19,150 / 3,332 / ticked → cash −50, card −68, 118 not received, take-home 18,150 with 1,000 kept), InstaPay
 * unticked, cash over, return / exchange money on the day, voided rows out, sale after close → changed + re-close
 * supersedes, the seller's method edit blocked after close (owner allowed, flags the day), float carry-over, first
 * close needs a float, workers can't close, idempotency, RLS (app_user, same-tenant control) and append-only.
 * Also (B4, from the B3 live test): prices changed while the event is live (the next sale and an exchange's difference
 * use the new price, past sales keep theirs) and BAZAR_PIECE_DAMAGED for a piece returned damaged (sell, exchange).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarDayCloseTest {

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

    @Autowired BazarService     bazar;
    @Autowired BazarSaleService sales;
    @Autowired BazarDayService  days;
    @Autowired JdbcTemplate     jdbc;

    UUID tenantId, otherTenantId, ownerId, workerId, mainId;
    UUID v9100, v9850, v3400, v200, v300, v500;
    BazarSaleService.Actor owner, worker;
    int seq = 0;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        otherTenantId = UUID.randomUUID();
        mainId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID(), productId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Bazar Close Co', true)", tenantId);
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Other Close Co', true)", otherTenantId);
        ownerId  = user("owner", "Owner Omar");
        workerId = user("worker", "Seller Sara");
        owner  = new BazarSaleService.Actor(ownerId, "owner");
        worker = new BazarSaleService.Actor(workerId, "worker");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'bazar-close.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-BC', 'Wool Coat', 'active')", productId, tenantId, storeId);
        v9100 = variant(productId, "9100", "9100.00");
        v9850 = variant(productId, "9850", "9850.00");
        v3400 = variant(productId, "3400", "3400.00");
        v200  = variant(productId, "200", "200.00");
        v300  = variant(productId, "300", "300.00");
        v500  = variant(productId, "500", "500.00");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", mainId, tenantId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clear() { TenantContext.clear(); }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private UUID user(String role, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, ?, ?, 'h', ?::user_role)",
            id, tenantId, name, role + "-" + id + "@bazar-close-test.com", role);
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
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 7, '0'), 'available', ?)",
            id, tenantId, variant, "PC-" + id, id, mainId);
        return id;
    }

    private String code(String pieceId) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, pieceId);
    }

    /** A LIVE event (cash / card / instapay, every role sells) with one piece per listed variant (repeat = more). */
    private record Fixture(UUID eventId, List<String> pieces) {
        String p(int i) { return pieces.get(i); }
    }

    private Fixture live(UUID... variants) {
        Map<String, Object> ev = bazar.create(new BazarService.CreateEvent("Close Bazar " + (++seq),
            LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3), null, "Close Hall " + seq,
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
        return new Fixture(eventId, pieces);
    }

    private static String crid() { return "req-" + UUID.randomUUID(); }

    private UUID sell(Fixture f, int i, String method, BazarSaleService.Actor who) {
        return (UUID) sales.sell(f.eventId(), code(f.p(i)), crid(), method, who).get("id");
    }

    private LocalDate today() {
        return jdbc.queryForObject("SELECT (now() AT TIME ZONE 'Africa/Cairo')::date", LocalDate.class);
    }

    private static BazarDayService.Entered entered(String cash, String card, Boolean insta, String kept, String flt) {
        return new BazarDayService.Entered(bd(cash), bd(card), insta, bd(kept), bd(flt));
    }

    private static BigDecimal bd(String v) { return v == null ? null : new BigDecimal(v); }

    private Map<String, Object> close(Fixture f, LocalDate day, BazarDayService.Entered e) {
        return days.close(f.eventId(), day, e, null, crid(), owner);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> preview) {
        return (Map<String, Object>) preview.get("result");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> closeResult(Map<String, Object> day) {
        return (Map<String, Object>) ((Map<String, Object>) day.get("close")).get("result");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> method(Map<String, Object> result, String m) {
        return ((List<Map<String, Object>>) result.get("methods")).stream()
            .filter(r -> m.equals(r.get("method"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> day(Fixture f, LocalDate d) {
        return ((List<Map<String, Object>>) days.days(f.eventId(), owner).get("days")).stream()
            .filter(x -> d.toString().equals(x.get("business_day"))).findFirst().orElseThrow();
    }

    private static void money(Object actual, String expected) {
        assertThat(new BigDecimal(String.valueOf(actual))).isEqualByComparingTo(expected);
    }

    private static BazarException.Code codeOf(Runnable r) {
        try { r.run(); } catch (BazarException e) { return e.code(); }
        throw new AssertionError("expected a BazarException");
    }

    private int closeRows(Fixture f) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM bazar_day_closes WHERE event_id = ?", Integer.class, f.eventId());
    }

    /** The mockup's day: cash 18,200 (2 × 9,100), InstaPay 9,850, card 3,400. */
    private Fixture mockupDay() {
        Fixture f = live(v9100, v9100, v9850, v3400);
        sell(f, 0, "cash", owner);
        sell(f, 1, "cash", owner);
        sell(f, 2, "instapay", owner);
        sell(f, 3, "card", owner);
        return f;
    }

    /** Moves everything of the event one Cairo day back (its start, its sales, its money rows). */
    private void shiftEventBackOneDay(Fixture f) {
        jdbc.update("UPDATE bazar_events SET started_at = started_at - interval '1 day' WHERE id = ?", f.eventId());
        jdbc.update("UPDATE bazar_sales SET sold_at = sold_at - interval '1 day', created_at = created_at - interval '1 day', " +
            "business_day = business_day - 1 WHERE event_id = ?", f.eventId());
        jdbc.update("UPDATE bazar_payments SET created_at = created_at - interval '1 day', business_day = business_day - 1 " +
            "WHERE sale_id IN (SELECT id FROM bazar_sales WHERE event_id = ?)", f.eventId());
    }

    // ── the mockup example ───────────────────────────────────────────────────

    @Test
    void mockupExample_preview_cashMinus50_cardMinus68_notReceived118_takeHome18150() {
        Fixture f = mockupDay();
        Map<String, Object> p = days.preview(f.eventId(), today(), entered("19150", "3332", true, null, "1000"), owner);
        Map<String, Object> r = result(p);

        money(method(r, "cash").get("expected"), "19200");
        money(method(r, "cash").get("difference"), "-50");
        assertThat(method(r, "cash").get("state")).isEqualTo("short");
        money(method(r, "card").get("expected"), "3400");
        money(method(r, "card").get("difference"), "-68");
        money(method(r, "instapay").get("expected"), "9850");
        money(method(r, "instapay").get("difference"), "0");
        assertThat(method(r, "instapay").get("state")).isEqualTo("even");
        money(r.get("not_received_total"), "118");
        @SuppressWarnings("unchecked") Map<String, Object> split = (Map<String, Object>) r.get("not_received");
        money(split.get("cash"), "50");
        money(split.get("card"), "68");
        money(split.get("instapay"), "0");
        money(r.get("cash_kept"), "1000");
        money(r.get("take_home"), "18150");
        money(r.get("over_total"), "0");
        assertThat(closeRows(f)).as("a preview writes nothing").isZero();
    }

    @Test
    void mockupExample_close_storesSnapshotAndResult_dayClosedShort() {
        Fixture f = mockupDay();
        Map<String, Object> d = close(f, today(), entered("19150", "3332", true, "1000", "1000"));
        assertThat(d.get("status")).isEqualTo("closed_short");
        money(closeResult(d).get("not_received_total"), "118");
        money(closeResult(d).get("take_home"), "18150");

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM bazar_day_closes WHERE event_id = ?", f.eventId());
        money(row.get("opening_float"), "1000");
        money(row.get("net_cash"), "18200");
        money(row.get("net_card"), "3400");
        money(row.get("net_instapay"), "9850");
        money(row.get("expected_cash"), "19200");
        money(row.get("cash_difference"), "-50");
        money(row.get("card_difference"), "-68");
        money(row.get("instapay_difference"), "0");
        money(row.get("not_received_total"), "118");
        money(row.get("take_home"), "18150");
        money(row.get("cash_kept"), "1000");
        assertThat(row.get("instapay_received")).isEqualTo(true);
        assertThat(row.get("supersedes_id")).isNull();
        assertThat(row.get("closed_by")).isEqualTo(ownerId);
    }

    @Test
    void instapayUnticked_wholeInstapayAmountIsNotReceived() {
        Fixture f = mockupDay();
        Map<String, Object> r = result(days.preview(f.eventId(), today(),
            entered("19150", "3332", false, null, "1000"), owner));
        money(r.get("not_received_total"), "9968");   // 118 + 9,850
        @SuppressWarnings("unchecked") Map<String, Object> split = (Map<String, Object>) r.get("not_received");
        money(split.get("instapay"), "9850");
        money(method(r, "instapay").get("difference"), "-9850");
        assertThat(method(r, "instapay").get("state")).isEqualTo("not_received");
    }

    @Test
    void cashOver_showsAsOver_neverHidden_andIsNotCountedAsReceivedShortfall() {
        Fixture f = mockupDay();
        Map<String, Object> r = result(days.preview(f.eventId(), today(),
            entered("19300", "3332", true, null, "1000"), owner));
        money(method(r, "cash").get("difference"), "100");
        assertThat(method(r, "cash").get("state")).isEqualTo("over");
        money(r.get("over_total"), "100");
        money(r.get("not_received_total"), "68");      // only the card fees — the cash over doesn't offset it
        money(r.get("take_home"), "18300");
    }

    // ── what counts as the day's money ───────────────────────────────────────

    @Test
    void returnAndExchangeMoney_onTheDay_countCorrectly() {
        Fixture f = live(v200, v300, v500);
        sell(f, 0, "cash", owner);                                     // +200 cash
        sell(f, 1, "cash", owner);                                     // +300 cash
        sales.returnSale(f.eventId(), code(f.p(0)), null, "good", crid(), owner);           // −200 cash (refund)
        sales.exchange(f.eventId(), code(f.p(1)), code(f.p(2)), "card", null, crid(), owner); // +200 card (difference)

        Map<String, Object> r = result(days.preview(f.eventId(), today(), entered("1300", "200", true, null, "1000"), owner));
        money(method(r, "cash").get("expected"), "1300");   // 1,000 float + 300
        money(method(r, "card").get("expected"), "200");
        money(r.get("not_received_total"), "0");
        assertThat(close(f, today(), entered("1300", "200", true, null, "1000")).get("status"))
            .isEqualTo("closed_balanced");
    }

    @Test
    void voidedRows_areExcluded_undoAndMethodChange() {
        Fixture f = live(v200, v300);
        UUID undone = sell(f, 0, "cash", owner);
        sales.undo(f.eventId(), undone, owner);                           // payment voided
        UUID changed = sell(f, 1, "cash", owner);
        sales.changePayment(f.eventId(), changed, "card", owner);         // cash voided, card written
        Map<String, Object> r = result(days.preview(f.eventId(), today(), entered("1000", "300", true, null, "1000"), owner));
        money(method(r, "cash").get("expected"), "1000");   // the float only
        money(method(r, "card").get("expected"), "300");
        money(r.get("not_received_total"), "0");
    }

    // ── after close ──────────────────────────────────────────────────────────

    @Test
    void saleAfterClose_flagsTheDay_reCloseSupersedes_appendOnly() {
        Fixture f = live(v200, v300);
        sell(f, 0, "cash", owner);
        close(f, today(), entered("1200", "0", true, null, "1000"));
        assertThat(day(f, today()).get("status")).isEqualTo("closed_balanced");
        UUID first = jdbc.queryForObject("SELECT id FROM bazar_day_closes WHERE event_id = ?", UUID.class, f.eventId());

        sell(f, 1, "cash", owner);                                        // selling goes on after the close
        assertThat(day(f, today()).get("status")).isEqualTo("changed_after_close");

        Map<String, Object> again = close(f, today(), entered("1500", "0", true, null, null));
        assertThat(again.get("status")).isEqualTo("closed_balanced");
        assertThat(again.get("close_count")).isEqualTo(2);
        money(closeResult(again).get("opening_float"), "1000");          // the day's own float, not asked again
        assertThat(closeRows(f)).isEqualTo(2);
        Map<String, Object> second = jdbc.queryForMap(
            "SELECT id, supersedes_id, net_cash FROM bazar_day_closes WHERE event_id = ? AND supersedes_id IS NOT NULL",
            f.eventId());
        assertThat(second.get("supersedes_id")).isEqualTo(first);
        money(second.get("net_cash"), "500");
        money(jdbc.queryForObject("SELECT net_cash FROM bazar_day_closes WHERE id = ?", BigDecimal.class, first), "200");
        @SuppressWarnings("unchecked") Map<String, Object> current = (Map<String, Object>) again.get("close");
        assertThat(current.get("id")).isEqualTo(second.get("id"));
    }

    @Test
    void paymentMethodEdit_afterClose_blockedForTheSeller_allowedForOwner_flagsTheDay() {
        Fixture f = live(v200);
        UUID sale = sell(f, 0, "cash", worker);
        sales.changePayment(f.eventId(), sale, "card", worker);          // before close: the seller may
        sales.changePayment(f.eventId(), sale, "cash", worker);
        close(f, today(), entered("1200", "0", true, null, "1000"));

        assertThat(codeOf(() -> sales.changePayment(f.eventId(), sale, "card", worker)))
            .isEqualTo(BazarException.Code.BAZAR_DAY_CLOSED);
        assertThat(day(f, today()).get("status")).isEqualTo("closed_balanced");

        sales.changePayment(f.eventId(), sale, "card", owner);
        assertThat(day(f, today()).get("status")).isEqualTo("changed_after_close");
    }

    @Test
    void floatCarriesOver_toTheNextDay_andAnEditOfAClosedEarlierDayFlagsIt() {
        Fixture f = live(v200, v300);
        UUID yesterdaySale = sell(f, 0, "cash", owner);
        shiftEventBackOneDay(f);
        LocalDate yesterday = today().minusDays(1);

        Map<String, Object> d1 = close(f, yesterday, entered("1200", "0", true, "800", "1000"));
        money(closeResult(d1).get("take_home"), "400");
        assertThat(d1.get("status")).isEqualTo("closed_balanced");

        @SuppressWarnings("unchecked")
        Map<String, Object> fl = (Map<String, Object>) day(f, today()).get("opening_float_default");
        money(fl.get("amount"), "800");
        assertThat(fl.get("source")).isEqualTo("previous_close");

        sell(f, 1, "cash", owner);
        Map<String, Object> d2 = close(f, today(), entered("1100", "0", true, null, null));
        money(closeResult(d2).get("opening_float"), "800");
        assertThat(d2.get("status")).isEqualTo("closed_balanced");
        assertThat(day(f, yesterday).get("status")).isEqualTo("closed_balanced");

        // The owner fixes yesterday's method today: yesterday's money changed after its close.
        sales.changePayment(f.eventId(), yesterdaySale, "card", owner);
        assertThat(day(f, yesterday).get("status")).isEqualTo("changed_after_close");
    }

    @Test
    void firstClose_needsAnOpeningFloat() {
        Fixture f = live(v200);
        sell(f, 0, "cash", owner);
        Map<String, Object> p = days.preview(f.eventId(), today(), entered("200", null, null, null, null), owner);
        assertThat(p.get("needs_opening_float")).isEqualTo(true);
        assertThat(codeOf(() -> close(f, today(), entered("200", "0", true, null, null))))
            .isEqualTo(BazarException.Code.BAZAR_OPENING_FLOAT_REQUIRED);
        assertThat(close(f, today(), entered("200", "0", true, "0", "0")).get("status")).isEqualTo("closed_balanced");
    }

    // ── who / idempotency / validation ───────────────────────────────────────

    @Test
    void workersCantClose_norSeeTheDays() {
        Fixture f = live(v200);
        assertThat(codeOf(() -> days.days(f.eventId(), worker))).isEqualTo(BazarException.Code.BAZAR_MANAGERS_ONLY);
        assertThat(codeOf(() -> days.preview(f.eventId(), today(), entered("0", "0", true, null, "0"), worker)))
            .isEqualTo(BazarException.Code.BAZAR_MANAGERS_ONLY);
        assertThat(codeOf(() -> days.close(f.eventId(), today(), entered("0", "0", true, null, "0"), null, crid(), worker)))
            .isEqualTo(BazarException.Code.BAZAR_MANAGERS_ONLY);
        assertThat(closeRows(f)).isZero();
    }

    @Test
    void sameRequestId_closesOnce() {
        Fixture f = live(v200);
        sell(f, 0, "cash", owner);
        String id = crid();
        Map<String, Object> a = days.close(f.eventId(), today(), entered("1200", "0", true, null, "1000"), null, id, owner);
        Map<String, Object> b = days.close(f.eventId(), today(), entered("9999", "0", true, null, "1000"), null, id, owner);
        assertThat(a.get("replayed")).isEqualTo(false);
        assertThat(b.get("replayed")).isEqualTo(true);
        assertThat(closeRows(f)).isEqualTo(1);
        money(closeResult(b).get("not_received_total"), "0");
    }

    @Test
    void notADayOfTheEvent_isRefused_andKeptMoreThanCounted_isRefused() {
        Fixture f = live(v200);
        sell(f, 0, "cash", owner);
        assertThat(codeOf(() -> close(f, today().plusDays(1), entered("0", "0", true, null, "0"))))
            .isEqualTo(BazarException.Code.BAZAR_DAY_NOT_CLOSABLE);
        assertThat(codeOf(() -> close(f, today().minusDays(1), entered("0", "0", true, null, "0"))))
            .isEqualTo(BazarException.Code.BAZAR_DAY_NOT_CLOSABLE);
        Map<String, Object> p = days.preview(f.eventId(), today(), entered("100", "0", true, "500", "1000"), owner);
        assertThat(p.get("problems")).isEqualTo(List.of("kept_more_than_counted"));
        assertThat(codeOf(() -> close(f, today(), entered("100", "0", true, "500", "1000"))))
            .isEqualTo(BazarException.Code.BAZAR_INVALID_INPUT);
        assertThat(codeOf(() -> close(f, today(), entered("-1", "0", true, null, "0"))))
            .isEqualTo(BazarException.Code.BAZAR_INVALID_INPUT);
        assertThat(closeRows(f)).isZero();
    }

    @Test
    void closing_writesNoMoneyRow_andNothingToShopify() {
        Fixture f = mockupDay();
        int money = jdbc.queryForObject("SELECT COUNT(*) FROM bazar_payments", Integer.class);
        int shopify = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?) " +
            "+ (SELECT COUNT(*) FROM transfer_shopify_syncs WHERE tenant_id = ?)", Integer.class, tenantId, tenantId);
        close(f, today(), entered("19150", "3332", true, null, "1000"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bazar_payments", Integer.class)).isEqualTo(money);
        assertThat(jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?) " +
            "+ (SELECT COUNT(*) FROM transfer_shopify_syncs WHERE tenant_id = ?)", Integer.class, tenantId, tenantId))
            .isEqualTo(shopify);
    }

    @Test
    void daysList_listsEveryDayOfTheEvent_withItsMoney() {
        Fixture f = live(v200, v300);
        sell(f, 0, "cash", owner);
        shiftEventBackOneDay(f);
        sell(f, 1, "card", owner);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) days.days(f.eventId(), owner).get("days");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("day_number")).isEqualTo(1);
        assertThat(list.get(0).get("business_day")).isEqualTo(today().minusDays(1).toString());
        assertThat(list.get(1).get("is_today")).isEqualTo(true);
        @SuppressWarnings("unchecked") Map<String, Object> m0 = (Map<String, Object>) list.get(0).get("money");
        @SuppressWarnings("unchecked") Map<String, Object> m1 = (Map<String, Object>) list.get(1).get("money");
        money(((Map<?, ?>) m0.get("by_method")).get("cash"), "200");
        money(((Map<?, ?>) m1.get("by_method")).get("card"), "300");
        assertThat(list).allMatch(d -> "open".equals(d.get("status")));
    }

    @Test
    void anotherTenantsEvent_isNotFound() {
        Fixture f = live(v200);
        TenantContext.set(otherTenantId);
        BazarSaleService.Actor otherOwner = new BazarSaleService.Actor(UUID.randomUUID(), "owner");
        assertThat(codeOf(() -> days.days(f.eventId(), otherOwner))).isEqualTo(BazarException.Code.BAZAR_EVENT_NOT_FOUND);
    }

    // ── RLS (app_user) + append-only ─────────────────────────────────────────

    @Test
    void rls_appUser_seesOnlyItsOwnCloses() {
        Fixture f = live(v200);
        sell(f, 0, "cash", owner);
        close(f, today(), entered("1200", "0", true, null, "1000"));
        TenantAwareDataSource ds = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setReadOnly(true);
        String sql = "SELECT COUNT(*) FROM bazar_day_closes WHERE event_id = ?";

        TenantContext.set(otherTenantId);
        assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, f.eventId()))).isZero();
        TenantContext.clear();
        assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, f.eventId()))).as("no tenant").isZero();
        TenantContext.set(tenantId);   // same-tenant positive control
        assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, f.eventId()))).isEqualTo(1);
    }

    @Test
    void appUser_cannotUpdateOrDeleteCloses_butCanAppend() {
        Fixture f = live(v200);
        sell(f, 0, "cash", owner);
        close(f, today(), entered("1200", "0", true, null, "1000"));
        UUID closeId = jdbc.queryForObject("SELECT id FROM bazar_day_closes WHERE event_id = ?", UUID.class, f.eventId());
        TenantAwareDataSource ds = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        for (String sql : List.of(
                "UPDATE bazar_day_closes SET cash_counted = 0 WHERE id = ?",
                "DELETE FROM bazar_day_closes WHERE id = ?")) {
            Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                () -> tx.execute(s -> app.update(sql, closeId)), sql);
        }
        // Positive control: app_user CAN append a superseding close for its own tenant (the service's write).
        TenantContext.set(tenantId);
        Map<String, Object> d = days.close(f.eventId(), today(), entered("1200", "0", true, null, null), "recount",
            crid(), owner);
        assertThat(d.get("close_count")).isEqualTo(2);
        money(jdbc.queryForObject("SELECT cash_counted FROM bazar_day_closes WHERE id = ?", BigDecimal.class, closeId), "1200");
        // A second close superseding the same row is refused by the DB (a close is superseded at most once).
        Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
            "INSERT INTO bazar_day_closes (tenant_id, event_id, business_day, opening_float, cash_counted, card_settlement, " +
            "instapay_received, cash_kept, net_cash, net_card, net_instapay, expected_cash, expected_card, expected_instapay, " +
            "cash_difference, card_difference, instapay_difference, not_received_total, over_total, take_home, closed_by, " +
            "client_request_id, supersedes_id) SELECT tenant_id, event_id, business_day, 0, 0, 0, true, 0, 0, 0, 0, 0, 0, 0, " +
            "0, 0, 0, 0, 0, 0, closed_by, 'dup-' || id, ? FROM bazar_day_closes WHERE id = ?", closeId, closeId));
    }

    // ── B4 (from the B3 live test): prices while live, damaged pieces ─────────

    private BigDecimal salePrice(UUID saleId) {
        return jdbc.queryForObject("SELECT price FROM bazar_sales WHERE id = ?", BigDecimal.class, saleId);
    }

    @Test
    void priceChangedWhileLive_nextSaleUsesIt_pastSalesKeepTheirs() {
        Fixture f = live(v200, v200, v200);
        UUID first = sell(f, 0, "cash", owner);
        bazar.setOverride(f.eventId(), v200, new BigDecimal("250"), ownerId);      // live: allowed
        UUID second = sell(f, 1, "cash", owner);
        money(salePrice(first), "200");
        money(salePrice(second), "250");
        bazar.setBulkRule(f.eventId(), null, null, ownerId);                     // a rule never touches an override
        bazar.clearOverride(f.eventId(), v200, ownerId);                         // back to the Shopify price
        UUID third = sell(f, 2, "cash", owner);
        money(salePrice(third), "200");
        money(salePrice(second), "250");
        money(jdbc.queryForObject("SELECT amount FROM bazar_payments WHERE sale_id = ? AND kind = 'payment'",
            BigDecimal.class, second), "250");
    }

    @Test
    void exchangeDifference_usesTheNewPiecesCurrentPrice() {
        Fixture f = live(v200, v300);
        sell(f, 0, "cash", owner);                                               // old piece sold at 200
        bazar.setOverride(f.eventId(), v300, new BigDecimal("450"), ownerId);    // new piece now 450 (was 300)
        @SuppressWarnings("unchecked")
        Map<String, Object> x = sales.exchange(f.eventId(), code(f.p(0)), code(f.p(1)), "card", null, crid(), owner);
        money(x.get("difference"), "250");
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) x.get("money");
        money(m.get("amount"), "250");
    }

    @Test
    void pieceReturnedDamaged_cantBeSold_norGivenInAnExchange_lookupSaysDamaged() {
        Fixture f = live(v200, v300, v500);
        sell(f, 0, "cash", owner);
        sales.returnSale(f.eventId(), code(f.p(0)), null, "damaged", crid(), owner);
        sell(f, 1, "cash", owner);
        sell(f, 2, "cash", owner);
        sales.returnSale(f.eventId(), code(f.p(2)), null, "good", crid(), owner);

        assertThat(codeOf(() -> sales.sell(f.eventId(), code(f.p(0)), crid(), null, owner)))
            .isEqualTo(BazarException.Code.BAZAR_PIECE_DAMAGED);
        int rows = jdbc.queryForObject("SELECT COUNT(*) FROM bazar_payments", Integer.class);
        assertThat(codeOf(() -> sales.exchange(f.eventId(), code(f.p(1)), code(f.p(0)), null, null, crid(), owner)))
            .isEqualTo(BazarException.Code.BAZAR_PIECE_DAMAGED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bazar_payments", Integer.class)).isEqualTo(rows);
        assertThat(jdbc.queryForObject("SELECT status FROM bazar_sales WHERE piece_id = ? AND status <> 'returned'",
            String.class, f.p(1))).isEqualTo("active");                        // the refused exchange changed nothing

        assertThat(sales.lookupPiece(f.eventId(), code(f.p(0)), owner).get("damaged")).isEqualTo(true);
        assertThat(sales.lookupPiece(f.eventId(), code(f.p(2)), owner).get("damaged")).isEqualTo(false);
        // A piece returned good is on sale again.
        assertThat(sales.sell(f.eventId(), code(f.p(2)), crid(), null, owner).get("status")).isEqualTo("active");
    }

    // ── B4 review: a payment-method correction stays on the sale's day ───────

    @SuppressWarnings("unchecked")
    private Map<String, Object> moneyOf(Fixture f, LocalDate d) {
        return (Map<String, Object>) ((Map<String, Object>) day(f, d).get("money")).get("by_method");
    }

    @Test
    void methodChangedToday_ofYesterdaysSale_movesYesterdaysMoney_notTodays() {
        Fixture f = live(v200);
        UUID sale = sell(f, 0, "cash", owner);
        shiftEventBackOneDay(f);
        LocalDate yesterday = today().minusDays(1);
        close(f, yesterday, entered("1200", "0", true, null, "1000"));
        assertThat(day(f, yesterday).get("status")).isEqualTo("closed_balanced");

        sales.changePayment(f.eventId(), sale, "instapay", owner);               // edited today

        money(moneyOf(f, yesterday).get("cash"), "0");                          // yesterday: cash −200 …
        money(moneyOf(f, yesterday).get("instapay"), "200");                    // … InstaPay +200
        assertThat(day(f, yesterday).get("status")).isEqualTo("changed_after_close");
        money(moneyOf(f, today()).get("cash"), "0");                             // today: nothing moved
        money(moneyOf(f, today()).get("instapay"), "0");
        money(sales.todaySummary(f.eventId(), owner).get("total"), "0");
        // Both new rows (void + replacement) count on yesterday.
        assertThat(jdbc.queryForList("SELECT DISTINCT business_day FROM bazar_payments WHERE sale_id = ?",
            LocalDate.class, sale)).containsExactly(yesterday);
    }

    @Test
    void refundToday_ofYesterdaysSale_countsToday() {
        Fixture f = live(v200);
        sell(f, 0, "cash", owner);
        shiftEventBackOneDay(f);
        LocalDate yesterday = today().minusDays(1);
        sales.returnSale(f.eventId(), code(f.p(0)), null, "good", crid(), owner);   // real money out, today
        money(moneyOf(f, yesterday).get("cash"), "200");
        money(moneyOf(f, today()).get("cash"), "-200");
        money(sales.todaySummary(f.eventId(), owner).get("total"), "-200");
    }
}
