package com.traceability;

import com.traceability.analytics.AnalyticsFloorOverrides;
import com.traceability.analytics.SettlementRefreshJob;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaIngestionHelper;
import com.traceability.integrations.bosta.ShipmentSettlement;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Analytics payout fixes (2026-11-27) — slice 1: the payout check shows Traced's net per payout and its
 * breakdown (COD collected, Bosta fees, negative deposits), never Bosta's batch total. Slice 2: the
 * settlement refresh reads a deposited leg once its own next_cashout_date passed (no single payout
 * weekday), under a per-run cap; "delivered, not paid" and the cash forecast use every payout weekday.
 * Slice 3: expected payouts count Bosta's fees (in transit, prepaid not-fulfilled orders, fees still to be
 * deducted), the same numbers in the cash forecast. Realistic
 * fixtures: bare 10-digit tracking numbers, settlement columns written by the real writer from a
 * Bosta-shaped wallet.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalyticsPayoutFixesTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("analytics.settlement.refresh-enabled", () -> "false");
    }

    static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    static final AtomicLong SEQ = new AtomicLong(7_100_000_000L + System.nanoTime() % 100_000_000L);

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired EncryptionService encryption;
    @Autowired AnalyticsFloorOverrides floorOverrides;
    @Autowired PlatformTransactionManager txm;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    static Instant cairo(int y, int m, int d, int h) {
        return LocalDateTime.of(y, m, d, h, 0).atZone(CAIRO).toInstant();
    }

    static Instant ago(Duration d) {
        return Instant.now().minus(d);
    }

    /** A bare 10-digit Bosta tracking number, as Bosta prints it. */
    static String tracking() {
        return String.valueOf(SEQ.incrementAndGet());
    }

    final class T {
        final UUID id = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        final UUID store = UUID.randomUUID();
        final UUID product = UUID.randomUUID();
        final String ownerToken;

        T(String name) {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                        "VALUES (?, ?, 'Owner', ?, 'x', 'owner')", owner, id, "owner+" + owner + "@payout.test");
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                        "VALUES (?, ?, 'shopify', ?, 'connected', ?)",
                        store, id, "payout-" + id + ".myshopify.com", Timestamp.from(cairo(2026, 1, 1, 0)));
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, 'Tee')",
                        product, id, store, "P-" + product);
            ownerToken = jwt.issueAccessToken(owner, id, "owner");
        }

        void bostaAccount() {
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                        "VALUES (?, 'bosta', ?, ?, 'active')", id, encryption.encrypt("k-" + id), "h-" + id);
        }

        UUID variant(String sku) {
            UUID v = UUID.randomUUID();
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title, price) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 100)", v, id, product, "V-" + v, sku, sku);
            return v;
        }

        UUID order(Instant placedAt) {
            return order(placedAt, "new", null);
        }

        /** {@code gateway}: Shopify's payment gateway name ("Cash on Delivery (COD)", "Paymob" …), or null. */
        UUID order(Instant placedAt, String status, String gateway) {
            UUID o = UUID.randomUUID();
            String raw = gateway == null ? "{}" : "{\"payment_gateway_names\":[\"" + gateway + "\"]}";
            jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw) " +
                        "VALUES (?, ?, ?, ?, ?, ?::order_status, ?, ?::jsonb)",
                        o, id, store, "gid://shopify/Order/" + SEQ.incrementAndGet(), "#" + (SEQ.get() % 100000),
                        status, Timestamp.from(placedAt), raw);
            return o;
        }

        UUID orderRaw(Instant placedAt, String status, String raw) {
            UUID o = UUID.randomUUID();
            jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw) " +
                        "VALUES (?, ?, ?, ?, ?, ?::order_status, ?, ?::jsonb)",
                        o, id, store, "gid://shopify/Order/" + SEQ.incrementAndGet(), "#" + (SEQ.get() % 100000),
                        status, Timestamp.from(placedAt), raw);
            return o;
        }

        void line(UUID order, UUID variant, int qty, String price) {
            long lineId = SEQ.incrementAndGet();
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id, raw) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb)", id, order, variant, qty, "gid://shopify/LineItem/" + lineId,
                        "{\"id\":" + lineId + ",\"price\":\"" + price + "\",\"quantity\":" + qty +
                        ",\"current_quantity\":" + qty + ",\"discount_allocations\":[]}");
        }

        /** A Bosta leg; its settlement columns are written by the real writer from {@code raw}. */
        Leg leg(UUID order, String leg, String state, String raw) {
            UUID s = UUID.randomUUID();
            String tn = tracking();
            jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state, shipment_leg, raw, " +
                        "provider_state) VALUES (?, ?, ?, ?, ?::shipment_internal_state, ?, ?::jsonb, " +
                        "(?::jsonb #>> '{state,code}')::int)", s, id, order, tn, state, leg, raw, raw);
            ShipmentSettlement.apply(jdbc, s, ShipmentSettlementTest.json(raw));
            return new Leg(s, tn);
        }

        Leg forward(Instant placedAt, String state, String raw) {
            return leg(order(placedAt), "forward", state, raw);
        }
    }

    record Leg(UUID id, String tn) {}

    void set(Leg l, String assignments, Object... args) {
        Object[] all = Arrays.copyOf(args, args.length + 1);
        all[args.length] = l.id();
        jdbc.update("UPDATE shipments SET " + assignments + " WHERE id = ?", all);
    }

    static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    /**
     * A settled, paid Bosta payload (prod shape: numbers as strings, payout date in the transaction id,
     * Bosta's batch total in cashout.amount). deposited = cod − fees.
     */
    static String paid(int type, String cod, String fees, String txn, String batch) {
        BigDecimal dep = new BigDecimal(cod).subtract(new BigDecimal(fees));
        return "{\"type\":{\"code\":" + type + "},\"state\":{\"code\":" + (type == 20 || type == 25 ? 46 : 45) + "}," +
               "\"cod\":" + cod + ",\"wallet\":{\"cashCycle\":{\"_id\":" + SEQ.incrementAndGet() +
               ",\"deposited_at\":\"2026-11-15T08:00:00.000Z\",\"deposited_amt\":" + dep.toPlainString() +
               ",\"cod\":\"" + cod + "\",\"bosta_fees\":\"" + fees + "\"}," +
               "\"cashout\":{\"transaction_id\":\"" + txn + "\"" + (batch == null ? "" : ",\"amount\":\"" + batch + "\"") + "}}}";
    }

    private String base() { return "http://localhost:" + port; }

    private Map<String, Object> ok(T t, String pathAndQuery) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(t.ownerToken);
        ResponseEntity<Map> r = rest.exchange(base() + pathAndQuery, HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(r.getStatusCode()).as("body: %s", r.getBody()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked") Map<String, Object> body = r.getBody();
        return body;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> m(Map<String, Object> body, String key) {
        return (Map<String, Object>) body.get(key);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) body.get(key);
    }

    static long n(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).longValue();
    }

    static BigDecimal dec(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : new BigDecimal(v.toString());
    }

    static final String NOV = "from=2026-11-01&to=2026-11-30";

    // ── slice 1: payout check ────────────────────────────────────────────────

    @Test
    void s1_payout_netAndBreakdown_noBatchTotal() {
        T t = new T("Payout-Breakdown");
        Instant placed = cairo(2026, 11, 10, 12);
        String txn = "WEDCOD18NOV26";
        // COD Send, and a same-tenant second COD Send (positive control: both sum into the payout).
        t.forward(placed, "delivered", paid(10, "990.00", "104.88", txn, "67854.59"));
        t.forward(placed, "delivered", paid(10, "450.00", "79.80", txn, "67854.59"));
        // Prepaid Send: no COD, Bosta deducts the fee → a negative deposit (= −fee).
        t.forward(placed, "delivered", paid(10, "0.00", "70.68", txn, "67854.59"));
        // Refused → Return to Origin: fee only.
        t.forward(placed, "returned", paid(20, "0.00", "77.52", txn, "67854.59"));
        // Customer return pickup (type 25): fee only.
        t.leg(t.order(placed), "return", "delivered", paid(25, "0.00", "106.02", txn, "67854.59"));
        // Exchange trip (type 30) collecting a price difference.
        t.forward(placed, "delivered", paid(30, "150.00", "95.76", txn, "67854.59"));
        // Another payout in the month.
        t.forward(placed, "delivered", paid(10, "300.00", "68.40", "WEDCOD25NOV26", null));

        List<Map<String, Object>> rows = list(ok(t, "/api/v1/analytics/money/payouts?" + NOV), "payouts");
        assertThat(rows).extracting(r -> r.get("transactionId")).containsExactly("WEDCOD25NOV26", txn);
        Map<String, Object> p = rows.get(1);
        assertThat(p.get("date")).isEqualTo("2026-11-18");
        assertThat(n(p, "trackedShipments")).isEqualTo(6);
        assertThat(dec(p, "codCollected")).isEqualByComparingTo("1590.00");                 // 990 + 450 + 150
        assertThat(dec(p, "bostaFees")).isEqualByComparingTo("534.66");                     // Σ fees incl. fee-only legs
        assertThat(dec(p, "trackedDeposited")).isEqualByComparingTo("1055.34");             // net = COD − fees
        assertThat(dec(p, "codCollected").subtract(dec(p, "bostaFees"))).isEqualByComparingTo(dec(p, "trackedDeposited"));
        assertThat(n(p, "negativeDeposits")).isEqualTo(3);                                   // prepaid, RTO, CRP
        assertThat(dec(p, "negativeDepositsAmount")).isEqualByComparingTo("-254.22");
        // Bosta's batch total is never read or shown.
        assertThat(p.keySet()).containsExactlyInAnyOrder("transactionId", "date", "trackedShipments", "trackedDeposited",
            "codCollected", "bostaFees", "negativeDeposits", "negativeDepositsAmount");
        Map<String, Object> other = rows.get(0);
        assertThat(n(other, "negativeDeposits")).isZero();
        assertThat(dec(other, "negativeDepositsAmount")).isEqualByComparingTo("0");
        assertThat(dec(other, "trackedDeposited")).isEqualByComparingTo("231.60");
    }

    @Test
    void s1_payout_twoTenantsSharingOneTransactionId_eachSeesOnlyItsOwn() {
        T a = new T("Payout-A");
        T b = new T("Payout-B");
        Instant placed = cairo(2026, 11, 10, 12);
        String txn = "MONCOD16NOV26";               // Bosta reuses the same id for every business paid that day
        a.forward(placed, "delivered", paid(10, "990.00", "104.88", txn, "12000.00"));
        a.forward(placed, "delivered", paid(10, "0.00", "70.68", txn, "12000.00"));
        b.forward(placed, "delivered", paid(10, "2500.00", "120.00", txn, "98000.00"));
        b.forward(placed, "returned", paid(20, "0.00", "77.52", txn, "98000.00"));
        b.forward(placed, "delivered", paid(10, "640.00", "85.50", txn, "98000.00"));

        Map<String, Object> pa = list(ok(a, "/api/v1/analytics/money/payouts?" + NOV), "payouts").get(0);
        assertThat(n(pa, "trackedShipments")).isEqualTo(2);
        assertThat(dec(pa, "codCollected")).isEqualByComparingTo("990.00");
        assertThat(dec(pa, "bostaFees")).isEqualByComparingTo("175.56");
        assertThat(dec(pa, "trackedDeposited")).isEqualByComparingTo("814.44");
        assertThat(n(pa, "negativeDeposits")).isEqualTo(1);

        Map<String, Object> pb = list(ok(b, "/api/v1/analytics/money/payouts?" + NOV), "payouts").get(0);
        assertThat(n(pb, "trackedShipments")).isEqualTo(3);
        assertThat(dec(pb, "codCollected")).isEqualByComparingTo("3140.00");
        assertThat(dec(pb, "trackedDeposited")).isEqualByComparingTo("2856.98");
        assertThat(dec(pb, "negativeDepositsAmount")).isEqualByComparingTo("-77.52");
    }

    // ── slice 2: refresh cadence + payout weekdays ───────────────────────────

    final BostaGateway bosta = mock(BostaGateway.class);
    final BostaIngestionHelper ingestion = mock(BostaIngestionHelper.class);

    SettlementRefreshJob job(Clock clock, int perTenant, int perRun) {
        return new SettlementRefreshJob(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()),
            jdbc, txm, encryption, bosta, ingestion, floorOverrides, clock, perTenant, perRun, true);
    }

    /** Bosta answers with exactly what we hold (same state, type, wallet). */
    void bostaEchoesStored() {
        when(bosta.fetchDelivery(anyString(), anyString())).thenAnswer(inv -> {
            String tn = inv.getArgument(1);
            String raw = jdbc.queryForObject("SELECT raw::text FROM shipments WHERE tracking_number = ?", String.class, tn);
            return BostaDelivery.fromRaw(tn, ShipmentSettlementTest.json(raw));
        });
    }

    List<String> fetched() {
        List<String> tns = new ArrayList<>();
        mockingDetails(bosta).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("fetchDelivery"))
            .forEach(i -> tns.add((String) i.getArguments()[1]));
        return tns;
    }

    static LocalDate today() {
        return LocalDate.now(CAIRO);
    }

    /** A deposited COD Send (deposit = cod − fee) whose cash cycle says Bosta pays it on {@code next}. */
    static String deposited(LocalDate depositDay, LocalDate next) {
        return "{\"type\":{\"code\":10},\"state\":{\"code\":45},\"cod\":640,\"wallet\":{\"cashCycle\":{\"_id\":" + SEQ.incrementAndGet() +
               ",\"deposited_at\":\"" + depositDay.atTime(10, 0).atZone(CAIRO).toInstant() + "\",\"deposited_amt\":554.50," +
               "\"cod\":\"640.00\",\"bosta_fees\":\"85.50\"},\"cashout\":{\"next_cashout_date\":" +
               (next == null ? "null" : "\"" + next + "T00:00:00.000Z\"") + "}}}";
    }

    /**
     * Bosta pays this tenant on two weekdays: {@code top} (3 payouts in the last 90 days — the old single
     * "payout weekday") and {@code second} (2 payouts). Each paid leg was delivered 3 days before its payout.
     */
    void payoutsOn(T t, DayOfWeek top, DayOfWeek second) {
        LocalDate base = today().minusDays(1);
        List<LocalDate> days = new ArrayList<>();
        LocalDate d = base.with(java.time.temporal.TemporalAdjusters.previousOrSame(top));
        for (int i = 0; i < 3; i++) days.add(d.minusWeeks(i));
        d = base.with(java.time.temporal.TemporalAdjusters.previousOrSame(second));
        for (int i = 0; i < 2; i++) days.add(d.minusWeeks(i));
        for (LocalDate day : days) {
            String txn = day.getDayOfWeek().toString().substring(0, 3) + "COD" + day;
            Leg l = t.forward(day.minusDays(10).atTime(12, 0).atZone(CAIRO).toInstant(), "delivered",
                paid(10, "500.00", "70.00", txn, null));
            set(l, "cashout_date = ?, delivered_at = ?", java.sql.Date.valueOf(day),
                ts(day.minusDays(3).atTime(14, 0).atZone(CAIRO).toInstant()));
        }
    }

    @Test
    void s2_refresh_depositedReadOnceItsNextCashoutDatePassed_anyWeekday_plus8DaySafetyNet() {
        T t = new T("Refresh-NextCashout");
        // Paid on two weekdays; today is never "the day after" the top one, so the old rule read nothing daily.
        DayOfWeek top = today().plusDays(3).getDayOfWeek();
        payoutsOn(t, top, today().plusDays(1).getDayOfWeek());
        Instant placed = ago(Duration.ofDays(12));
        Leg passed = t.forward(placed, "delivered", deposited(today().minusDays(6), today().minusDays(1)));
        set(passed, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(21))));
        Leg justRead = t.forward(placed, "delivered", deposited(today().minusDays(6), today().minusDays(1)));
        set(justRead, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(3))));                      // once a day
        Leg future = t.forward(placed, "delivered", deposited(today().minusDays(2), today().plusDays(2)));
        set(future, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(21))));                       // not yet
        Leg payDayToday = t.forward(placed, "delivered", deposited(today().minusDays(3), today()));
        set(payDayToday, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(21))));                  // tomorrow
        Leg stale = t.forward(placed, "delivered", deposited(today().minusDays(12), today().plusDays(2)));
        set(stale, "settlement_refreshed_at = ?", ts(ago(Duration.ofDays(9))));                          // safety net
        Leg neverRead = t.forward(placed, "returned", deposited(today().minusDays(2), today().plusDays(2)));
        bostaEchoesStored();

        SettlementRefreshJob.RefreshResult r = job(Clock.system(CAIRO), 100, 300).refreshTenant(t.id, "key");
        assertThat(fetched()).containsExactlyInAnyOrder(passed.tn(), stale.tn(), neverRead.tn());
        assertThat(r.payoutWeekday()).isEqualTo(top.getValue());
        assertThat(r.stillDue()).isZero();
    }

    @Test
    void s2_refresh_runCap_drainsABacklogOverSeveralRuns_rotatingTenants() {
        T a = new T("Refresh-CapA");
        T b = new T("Refresh-CapB");
        a.bostaAccount();
        b.bostaAccount();
        List<String> all = new ArrayList<>();
        for (T t : List.of(a, b)) {
            for (int i = 0; i < 3; i++) {
                all.add(t.forward(ago(Duration.ofDays(9)), "delivered",
                    deposited(today().minusDays(5), today().minusDays(2))).tn());
            }
        }
        // Only these two tenants carry an active Bosta account in this class's database.
        jdbc.update("UPDATE courier_accounts SET status = 'disconnected' WHERE tenant_id NOT IN (?, ?)", a.id, b.id);
        bostaEchoesStored();
        SettlementRefreshJob job = job(Clock.system(CAIRO), 2, 3);

        job.refreshAll();                      // run 1: 3 legs at most over both tenants, ≤ 2 per tenant
        assertThat(fetched()).hasSize(3);
        job.refreshAll();                      // run 2: the rest
        job.refreshAll();                      // run 3: nothing left
        assertThat(fetched()).containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void s2_deliveredNotPaid_countsEveryPayoutWeekday() {
        T t = new T("NotPaid-TwoWeekdays");
        DayOfWeek top = today().plusDays(3).getDayOfWeek();        // last one: today − 4
        DayOfWeek second = today().plusDays(1).getDayOfWeek();     // last one: today − 6
        payoutsOn(t, top, second);
        // Deposited a week ago: one payout day of each weekday has passed since (two in all), still not paid.
        Leg late = t.forward(ago(Duration.ofDays(12)), "delivered", deposited(today().minusDays(7), today().minusDays(6)));
        set(late, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(1))));
        // Deposited three days ago: no payout day has passed yet.
        Leg recent = t.forward(ago(Duration.ofDays(6)), "delivered", deposited(today().minusDays(3), today().plusDays(1)));
        set(recent, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(1))));

        Map<String, Object> body = ok(t, "/api/v1/analytics/money/stuck");
        assertThat(list(body, "deliveredNotPaid")).extracting(r -> r.get("trackingNumber")).containsExactly(late.tn());
        assertThat(n(body, "payoutWeekday")).isEqualTo(top.getValue());
        assertThat(body.get("payoutWeekdays")).isEqualTo(List.of(top.getValue(), second.getValue()));
    }

    @Test
    void s2_deliveredNotPaid_fewerThanTwoPayouts_keeps14DayFallback() {
        T t = new T("NotPaid-Unknown");
        Leg one = t.forward(ago(Duration.ofDays(30)), "delivered", paid(10, "500.00", "70.00", "WEDCOD04NOV26", null));
        set(one, "cashout_date = ?", java.sql.Date.valueOf(today().minusDays(20)));
        Leg old = t.forward(ago(Duration.ofDays(25)), "delivered", deposited(today().minusDays(15), today().minusDays(10)));
        set(old, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(1))));
        Leg young = t.forward(ago(Duration.ofDays(15)), "delivered", deposited(today().minusDays(10), today().minusDays(5)));
        set(young, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(1))));

        Map<String, Object> body = ok(t, "/api/v1/analytics/money/stuck");
        assertThat(list(body, "deliveredNotPaid")).extracting(r -> r.get("trackingNumber")).containsExactly(old.tn());
        assertThat(body.get("payoutWeekday")).isNull();
        assertThat(body.get("payoutWeekdays")).isEqualTo(List.of());
        assertThat(ok(t, "/api/v1/analytics/cash-forecast").get("reason")).isEqualTo("payout_cadence_unknown");
    }

    @Test
    void s2_cashForecast_nextPayoutIsTheEarliestPayoutWeekday() {
        T t = new T("Forecast-TwoWeekdays");
        DayOfWeek top = today().plusDays(3).getDayOfWeek();
        DayOfWeek second = today().plusDays(1).getDayOfWeek();
        payoutsOn(t, top, second);
        t.forward(ago(Duration.ofDays(4)), "delivered", deposited(today().minusDays(2), today().plusDays(1)));

        Map<String, Object> method = m(m(ok(t, "/api/v1/analytics/cash-forecast"), "forecast"), "method");
        assertThat(method.get("nextPayoutDate")).isEqualTo(today().plusDays(1).toString());
        assertThat(n(method, "payoutWeekday")).isEqualTo(top.getValue());
        assertThat(method.get("payoutWeekdays")).isEqualTo(List.of(top.getValue(), second.getValue()));
    }

    // ── slice 3: expected payout counts fees ─────────────────────────────────

    /** An unsettled Bosta leg: type, COD, quoted shipmentFees (fee estimate = quote × 1.14), drop-off city. */
    static String quoted(int type, int cod, int quote, String city) {
        return "{\"type\":{\"code\":" + type + "},\"cod\":" + cod + ",\"shipmentFees\":" + quote +
               ",\"dropOffAddress\":{\"city\":{\"_id\":\"id-" + city + "\",\"name\":\"" + city + "\"}}," +
               "\"wallet\":{\"cashCycle\":null,\"cashout\":{\"next_cashout_date\":null}}}";
    }

    static final String CAIRO_COD = "{\"payment_gateway_names\":[\"Cash on Delivery (COD)\"],\"shipping_address\":{\"province_code\":\"C\"}}";
    static final String CAIRO_CARD = "{\"payment_gateway_names\":[\"Paymob\"],\"shipping_address\":{\"province_code\":\"C\"}}";

    /** Cairo's success rate 3 / 5 = 0.6 (3 delivered, 1 refused RTO, 1 lost); typical Send fee 57.00 (quote 50). */
    void cairoRate(T t) {
        Instant recent = ago(Duration.ofDays(3));
        for (int i = 0; i < 3; i++) {
            Leg d = t.forward(recent, "delivered", quoted(10, 500, 50, "Cairo"));
            set(d, "delivered_at = ?", ts(ago(Duration.ofDays(2))));
        }
        t.forward(recent, "returned", quoted(20, 0, 50, "Cairo"));          // finished, not settled: 57.00 to deduct
        t.forward(recent, "lost", quoted(10, 500, 50, "Cairo"));            // finished, not settled: 57.00 to deduct
    }

    @Test
    void s3_pipeline_inTransitNetsFees_prepaidNotFulfilledCountsOnlyTheFee_feesStillToBeDeducted() {
        T t = new T("Expected-Fees");
        t.bostaAccount();
        UUID v = t.variant("FEE-1");
        Instant recent = ago(Duration.ofDays(3));
        cairoRate(t);

        // In transit (Cairo, 0.6): a COD Send, a prepaid Send (cod 0) and an exchange trip collecting 150.
        t.forward(recent, "with_courier", quoted(10, 1000, 50, "Cairo"));   // 600 − 57.00
        t.forward(recent, "with_courier", quoted(10, 0, 60, "Cairo"));      //   0 − 68.40
        t.forward(recent, "with_courier", quoted(30, 150, 80, "Cairo"));    //  90 − 91.20

        // Not fulfilled: COD, no leg (200 × 0.6); Card, no leg (−typical fee 57.00); a booked leg with
        // COD 0 (prepaid by Bosta's own figure: −68.40); a Card order whose leg collects COD (Bosta decides: × 0.6).
        UUID cod = t.orderRaw(recent, "new", CAIRO_COD);
        t.line(cod, v, 1, "200.00");
        UUID card = t.orderRaw(recent, "new", CAIRO_CARD);
        t.line(card, v, 1, "300.00");
        UUID prepaidLeg = t.orderRaw(recent, "new", CAIRO_COD);
        t.line(prepaidLeg, v, 1, "250.00");
        t.leg(prepaidLeg, "forward", "created", quoted(10, 0, 60, "Cairo"));
        UUID cardWithCod = t.orderRaw(recent, "new", CAIRO_CARD);
        t.line(cardWithCod, v, 1, "250.00");
        t.leg(cardWithCod, "forward", "created", quoted(10, 250, 50, "Cairo"));

        // Fees still to be deducted (Alexandria — kept out of Cairo's rate):
        t.forward(recent, "with_courier", quoted(20, 0, 50, "Alexandria"));                // RTO in flight      57.00
        t.forward(recent, "returning", quoted(10, 700, 40, "Alexandria"));                 // returning           45.60
        t.leg(t.orderRaw(recent, "cancelled", "{}"), "forward", "with_courier",
              quoted(10, 900, 50, "Alexandria"));                                          // refused, order cancelled 57.00
        t.leg(t.order(recent), "return", "with_courier", quoted(25, 0, 93, "Alexandria")); // return pickup       106.02
        t.leg(t.order(recent), "return", "delivered", quoted(25, 0, 93, "Alexandria"));    // finished, unsettled 106.02
        // Not counted: settled legs, delivered forward legs awaiting payout, legs never picked up.
        t.forward(recent, "returned", SettlementPayloads.RTO_DEPOSITED);
        t.forward(recent, "created", quoted(10, 400, 50, "Alexandria"));

        Map<String, Object> body = ok(t, "/api/v1/analytics/money/pipeline?period=7d");
        Map<String, Object> it = m(body, "inTransit");
        assertThat(n(it, "count")).isEqualTo(3);
        assertThat(dec(it, "value")).isEqualByComparingTo("1150.00");
        assertThat(dec(it, "expected")).isEqualByComparingTo("473.40");
        assertThat(dec(it, "fees")).isEqualByComparingTo("216.60");
        assertThat(n(it, "feesEstimated")).isEqualTo(3);
        assertThat(n(it, "prepaid")).isEqualTo(1);

        Map<String, Object> nf = m(body, "notFulfilled");
        assertThat(n(nf, "count")).isEqualTo(4);
        assertThat(dec(nf, "value")).isEqualByComparingTo("1000.00");
        assertThat(dec(nf, "expected")).isEqualByComparingTo("144.60");      // 120 − 57 − 68.40 + 150
        assertThat(n(nf, "prepaid")).isEqualTo(2);
        assertThat(dec(nf, "fees")).isEqualByComparingTo("125.40");
        assertThat(n(nf, "feesEstimated")).isEqualTo(2);

        Map<String, Object> fd = m(body, "feesToDeduct");
        assertThat(n(m(fd, "returning"), "count")).isEqualTo(3);
        assertThat(dec(m(fd, "returning"), "amount")).isEqualByComparingTo("159.60");
        assertThat(n(m(fd, "customerReturns"), "count")).isEqualTo(1);
        assertThat(dec(m(fd, "customerReturns"), "amount")).isEqualByComparingTo("106.02");
        assertThat(n(m(fd, "finishedUnsettled"), "count")).isEqualTo(3);            // Cairo RTO, Cairo lost, the CRP
        assertThat(dec(m(fd, "finishedUnsettled"), "amount")).isEqualByComparingTo("220.02");
        assertThat(n(fd, "count")).isEqualTo(7);
        assertThat(dec(fd, "amount")).isEqualByComparingTo("485.64");
    }

    @Test
    void s3_inTransit_settledFeeIsUsed_notTheQuote_andIsNotEstimated() {
        T t = new T("Expected-SettledFee");
        t.bostaAccount();
        cairoRate(t);
        Leg l = t.forward(ago(Duration.ofDays(2)), "with_courier", quoted(10, 1000, 50, "Cairo"));
        set(l, "settlement_status = 'deposited', bosta_fees = 61.56");                // Bosta's own figure
        Map<String, Object> it = m(ok(t, "/api/v1/analytics/money/pipeline?period=7d"), "inTransit");
        assertThat(dec(it, "expected")).isEqualByComparingTo("538.44");
        assertThat(dec(it, "fees")).isEqualByComparingTo("61.56");
        assertThat(n(it, "feesEstimated")).isZero();
    }

    @Test
    void s3_cashForecast_usesTheSameFees() {
        T t = new T("Forecast-Fees");
        t.bostaAccount();
        payoutsOn(t, today().plusDays(3).getDayOfWeek(), today().plusDays(1).getDayOfWeek());
        cairoRate(t);                                                                  // + 2 finished unsettled: 114.00
        Instant recent = ago(Duration.ofDays(2));
        t.forward(recent, "with_courier", quoted(10, 1000, 50, "Cairo"));             // in transit 600 − 57
        t.forward(recent, "with_courier", quoted(20, 0, 50, "Alexandria"));           // to deduct 57.00

        Map<String, Object> pipeline = ok(t, "/api/v1/analytics/money/pipeline?period=7d");
        Map<String, Object> f = m(ok(t, "/api/v1/analytics/cash-forecast"), "forecast");
        Map<String, Object> method = m(f, "method");
        assertThat(dec(m(method, "inTransit"), "expected")).isEqualByComparingTo(dec(m(pipeline, "inTransit"), "expected"));
        assertThat(dec(m(method, "inTransit"), "expected")).isEqualByComparingTo("543.00");
        assertThat(dec(m(method, "inTransit"), "fees")).isEqualByComparingTo("57.00");
        assertThat(dec(m(method, "feesToDeduct"), "amount")).isEqualByComparingTo(dec(m(pipeline, "feesToDeduct"), "amount"));
        assertThat(dec(m(method, "feesToDeduct"), "amount")).isEqualByComparingTo("171.00");
        BigDecimal bucketFees = BigDecimal.ZERO;
        for (Map<String, Object> b : list(f, "buckets")) {
            bucketFees = bucketFees.add(dec(b, "fees"));
            assertThat(dec(b, "amount")).isEqualByComparingTo(dec(b, "awaitingPayout").add(dec(b, "inTransit")).add(dec(b, "fees")));
        }
        assertThat(bucketFees).isEqualByComparingTo("-171.00");                         // all within 30 days
    }

    @Test
    void s3_legWithoutAQuote_countsTheTypicalFee_estimated() {
        T t = new T("Expected-NoQuote");
        t.bostaAccount();
        cairoRate(t);                                                                  // typical Send fee 57.00
        String noQuote = "{\"type\":{\"code\":10},\"cod\":1000,\"dropOffAddress\":{\"city\":{\"_id\":\"id-Cairo\",\"name\":\"Cairo\"}}}";
        t.forward(ago(Duration.ofDays(2)), "with_courier", noQuote);                  // 600 − 57
        t.forward(ago(Duration.ofDays(2)), "with_courier",
                  "{\"type\":{\"code\":20},\"cod\":0,\"dropOffAddress\":{\"city\":{\"name\":\"Alexandria\"}}}"); // to deduct 57

        Map<String, Object> body = ok(t, "/api/v1/analytics/money/pipeline?period=7d");
        Map<String, Object> it = m(body, "inTransit");
        assertThat(dec(it, "expected")).isEqualByComparingTo("543.00");
        assertThat(dec(it, "fees")).isEqualByComparingTo("57.00");
        assertThat(n(it, "feesEstimated")).isEqualTo(1);
        assertThat(dec(m(m(body, "feesToDeduct"), "returning"), "amount")).isEqualByComparingTo("57.00");
    }
}
