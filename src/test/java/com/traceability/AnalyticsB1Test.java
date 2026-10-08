package com.traceability;

import com.traceability.analytics.AnalyticsFloorOverrides;
import com.traceability.analytics.AnalyticsPeriod;
import com.traceability.analytics.SalesAnalyticsService;
import com.traceability.analytics.SettlementRefreshJob;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaIngestionHelper;
import com.traceability.integrations.bosta.ShipmentSettlement;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Analytics B1 — small backend fixes: sold units per variant per day (/sales/variants/daily), the
 * order list's search on the customer display name, ONE payout-lag definition (median delivered →
 * paid: /money/fees and the cash forecast agree), and zero cash cycles (Bosta settled for 0, no
 * cashout ever comes) never 'unresolved' / delivered-not-paid / awaiting payout, plus V150.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalyticsB1Test {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
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
    static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    /** Bosta settled a refused leg for exactly 0 (the two Snouts legs, 2026-10-08): no cashout ever comes. */
    static final String RTO_ZERO = """
        {"type":{"code":20,"value":"Return to Origin"},"state":{"code":46},"cod":null,"shipmentFees":68,
         "wallet":{"cashCycle":{"_id":81658877,"deposited_at":"2026-08-18T07:25:00.000Z","deposited_amt":"0.00",
                                "cod":"0.00","bosta_fees":"0.00","shipping_fees":"68.00","vat":"0.00"},
                   "cashout":{"next_cashout_date":"2026-08-26T00:00:00.000Z"}}}
        """;

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired JwtService jwt;
    @Autowired EncryptionService encryption;
    @Autowired AnalyticsFloorOverrides floorOverrides;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    static Instant daysAgo(int d) {
        return Instant.now().minus(Duration.ofDays(d));
    }

    static LocalDate today() {
        return LocalDate.now(CAIRO);
    }

    static Instant cairoNoon(LocalDate d) {
        return d.atTime(12, 0).atZone(CAIRO).toInstant();
    }

    static String last(int days) {
        return "from=" + today().minusDays(days - 1L) + "&to=" + today();
    }

    final class T {
        final UUID id = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        final UUID store = UUID.randomUUID();
        final UUID product = UUID.randomUUID();
        final String ownerToken;

        T(String name) {
            this(name, Instant.parse("2025-01-01T00:00:00Z"));
        }

        T(String name, Instant floor) {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                        "VALUES (?, ?, 'Owner', ?, 'x', 'owner')", owner, id, "owner+" + owner + "@b1.test");
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                        "VALUES (?, ?, 'shopify', ?, 'connected', ?)",
                        store, id, "b1-" + id + ".myshopify.com", Timestamp.from(floor));
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, 'Tee')",
                        product, id, store, "P-" + product);
            ownerToken = jwt.issueAccessToken(owner, id, "owner");
        }

        UUID variant(String sku) {
            UUID v = UUID.randomUUID();
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title, price) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 100)", v, id, product, "V-" + v, sku, sku);
            return v;
        }

        O order(Instant placedAt, String customer) {
            UUID o = UUID.randomUUID();
            String number = "#" + SEQ.incrementAndGet();
            jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw, customer_name) " +
                        "VALUES (?, ?, ?, ?, ?, 'new'::order_status, ?, " +
                        "'{\"payment_gateway_names\":[\"Cash on Delivery (COD)\"]}'::jsonb, ?)",
                        o, id, store, "EXT-" + o, number, Timestamp.from(placedAt), customer);
            return new O(o, number);
        }

        O order(Instant placedAt) {
            return order(placedAt, "Mona Adel");
        }

        void line(O order, UUID variant, int qty) {
            long lineId = SEQ.incrementAndGet();
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id, raw) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb)", id, order.id(), variant, qty, "gid://shopify/LineItem/" + lineId,
                        "{\"id\":" + lineId + ",\"price\":\"100.00\",\"quantity\":" + qty +
                        ",\"current_quantity\":" + qty + ",\"discount_allocations\":[]}");
        }

        /** A Bosta leg; its settlement columns are written by the real writer from {@code raw}. */
        Leg leg(O order, String legType, String state, String raw) {
            UUID s = UUID.randomUUID();
            String tn = "8" + SEQ.incrementAndGet();
            jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state, shipment_leg, raw, " +
                        "provider_state) VALUES (?, ?, ?, ?, ?::shipment_internal_state, ?, ?::jsonb, " +
                        "(?::jsonb #>> '{state,code}')::int)", s, id, order.id(), tn, state, legType, raw, raw);
            ShipmentSettlement.apply(jdbc, s, ShipmentSettlementTest.json(raw));
            return new Leg(s, tn);
        }

        Leg forward(O order, String state, int cod) {
            return leg(order, "forward", state,
                "{\"type\":{\"code\":10},\"cod\":" + cod + ",\"shipmentFees\":50," +
                "\"dropOffAddress\":{\"city\":{\"_id\":\"id-Cairo\",\"name\":\"Cairo\"}}}");
        }

        String token(String role) {
            UUID u = UUID.randomUUID();
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'U', ?, 'x', ?::user_role)",
                        u, id, role + "+" + u + "@b1.test", role);
            return jwt.issueAccessToken(u, id, role);
        }
    }

    record O(UUID id, String number) {}

    record Leg(UUID id, String tn) {}

    void set(Leg l, String assignments, Object... args) {
        Object[] all = Arrays.copyOf(args, args.length + 1);
        all[args.length] = l.id();
        jdbc.update("UPDATE shipments SET " + assignments + " WHERE id = ?", all);
    }

    /** A paid forward leg delivered on {@code delivered} and paid on {@code paidOn}, deposited 1 day after delivery. */
    void paidLeg(T t, LocalDate delivered, LocalDate paidOn, String legType) {
        Leg l = t.leg(t.order(cairoNoon(delivered.minusDays(2))), legType, "delivered",
            "{\"type\":{\"code\":" + ("return".equals(legType) ? 25 : 10) + "},\"cod\":100}");
        set(l, "settlement_status = 'paid', cashout_txn_id = ?, cashout_date = ?, deposited_amt = 90, bosta_fees = 10, " +
               "deposited_at = ?, delivered_at = ?", "TXN" + SEQ.incrementAndGet(), java.sql.Date.valueOf(paidOn),
            Timestamp.from(cairoNoon(delivered.plusDays(1))), Timestamp.from(cairoNoon(delivered)));
    }

    private String base() { return "http://localhost:" + port; }

    private ResponseEntity<Map> get(String token, String pathAndQuery) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(base() + pathAndQuery, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    private Map<String, Object> ok(T t, String pathAndQuery) {
        ResponseEntity<Map> r = get(t.ownerToken, pathAndQuery);
        assertThat(r.getStatusCode()).as("body: %s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) body.get(key);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> m(Map<String, Object> body, String key) {
        return (Map<String, Object>) body.get(key);
    }

    static BigDecimal dec(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : new BigDecimal(v.toString());
    }

    static long n(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).longValue();
    }

    // ── a. sold units per variant per day ───────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void variantsDaily_denseCairoDays_inTheOrderAsked_soldLineRule_unknownIdsZero() {
        T t = new T("B1-Daily");
        UUID a = t.variant("A"), b = t.variant("B"), quiet = t.variant("Q");
        LocalDate d3 = today().minusDays(3), d1 = today().minusDays(1);
        t.line(t.order(cairoNoon(d3)), a, 2);
        t.line(t.order(cairoNoon(d3)), a, 1);
        t.line(t.order(cairoNoon(d1)), a, 4);
        t.line(t.order(cairoNoon(d1)), b, 5);
        // Placed 00:30 Cairo on d1 = still d2 in UTC: counted on the Cairo day.
        t.line(t.order(d1.atTime(0, 30).atZone(CAIRO).toInstant()), b, 1);
        O cancelled = t.order(cairoNoon(d1));
        jdbc.update("UPDATE orders SET status = 'cancelled'::order_status WHERE id = ?", cancelled.id());
        t.line(cancelled, b, 9);                                    // cancelled: never sold

        Map<String, Object> body = ok(t, "/api/v1/analytics/sales/variants/daily?" + last(7) +
            "&ids=" + b + "," + a + "," + quiet + "," + UUID.randomUUID() + "," + a);
        List<String> days = (List<String>) body.get("days");
        assertThat(days).hasSize(7).startsWith(today().minusDays(6).toString()).endsWith(today().toString());
        List<Map<String, Object>> vs = list(body, "variants");
        assertThat(vs).extracting(v -> v.get("variantId")).containsExactly(b.toString(), a.toString(), quiet.toString(),
            vs.get(3).get("variantId"));                           // duplicates dropped, order kept
        Map<String, Object> av = vs.get(1);
        List<Integer> au = (List<Integer>) av.get("units");
        assertThat(au).hasSize(7);
        assertThat(au.get(3)).isEqualTo(3);                        // d3 = index 3 of a 7-day window ending today
        assertThat(au.get(5)).isEqualTo(4);
        assertThat(n(av, "totalUnits")).isEqualTo(7);
        assertThat(((List<Integer>) vs.get(0).get("units")).get(5)).isEqualTo(6);
        assertThat(n(vs.get(0), "totalUnits")).isEqualTo(6);
        assertThat(n(vs.get(2), "totalUnits")).isZero();
        assertThat((List<Integer>) vs.get(3).get("units")).containsOnly(0);

        // Bad input.
        String p = "/api/v1/analytics/sales/variants/daily?period=7d&ids=";
        String many = IntStream.range(0, 21).mapToObj(i -> UUID.randomUUID().toString()).collect(Collectors.joining(","));
        assertThat(get(t.ownerToken, p + many).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(t.ownerToken, p + "not-a-uuid").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(t.ownerToken, p + ",").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(t.token("manager"), p + a).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(t.token("worker"), p + a).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void variantsDaily_appUser_anotherTenantsVariantReadsAsZeros_cairoDayInAUtcSession() {
        T a = new T("B1-DailyIsoA");
        T b = new T("B1-DailyIsoB");
        UUID va = a.variant("A");
        a.line(a.order(daysAgo(1)), va, 3);

        TenantAwareDataSource appUserDs = new TenantAwareDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
        SalesAnalyticsService svc = new SalesAnalyticsService(new JdbcTemplate(appUserDs), Clock.system(CAIRO), floorOverrides);
        AnalyticsPeriod p = new AnalyticsPeriod(today().minusDays(6), today());

        TenantContext.set(a.id);
        assertThat(tx.execute(s -> svc.variantsDaily(p, List.of(va))).variants().get(0).totalUnits()).isEqualTo(3);

        // A UTC session (prod's): an order placed 00:30 Cairo still counts on its Cairo day.
        LocalDate d1 = today().minusDays(1);
        UUID late = a.variant("L");
        a.line(a.order(d1.atTime(0, 30).atZone(CAIRO).toInstant()), late, 1);
        SalesAnalyticsService.VariantDaily utc = tx.execute(s -> {
            new JdbcTemplate(appUserDs).execute("SET LOCAL TIME ZONE 'UTC'");
            return svc.variantsDaily(p, List.of(late)).variants().get(0);
        });
        assertThat(utc.units().get(5)).isEqualTo(1L);
        TenantContext.set(b.id);
        assertThat(tx.execute(s -> svc.variantsDaily(p, List.of(va))).variants().get(0).totalUnits()).isZero();
    }

    // ── b. search on the customer display name ──────────────────────────────

    @Test
    void ordersSearch_matchesTheDisplayNameOnly_neverTheHiddenSurname() {
        T t = new T("B1-Search");
        UUID v = t.variant("S");
        O mona = t.order(daysAgo(2), "Mona Adel Hassan");
        t.line(mona, v, 1);
        O omar = t.order(daysAgo(2), "Omar Said");
        t.line(omar, v, 1);
        String p = "/api/v1/analytics/orders?" + last(30) + "&q=";
        assertThat(names(ok(t, p + "mona"))).containsExactly(mona.number());
        assertThat(names(ok(t, p + "MONA H."))).containsExactly(mona.number());          // display: "Mona H."
        assertThat(names(ok(t, p + "omar  s"))).containsExactly(omar.number());        // spaces collapse
        assertThat(names(ok(t, p + "adel"))).isEmpty();                                      // middle name: not shown
        assertThat(names(ok(t, p + "hassan"))).isEmpty();                                    // surname: not shown
    }

    static List<Object> names(Map<String, Object> body) {
        return list(body, "orders").stream().map(r -> r.get("name")).toList();
    }

    // ── c. one payout-lag definition ────────────────────────────────────────

    @Test
    void payoutLag_medianDeliveredToPaid_feesAndForecastAgree() {
        T t = new T("B1-Lag");
        LocalDate wed = today().minusDays(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY));
        LocalDate prevWed = wed.minusWeeks(1);
        paidLeg(t, wed.minusDays(2), wed, "forward");             // lag 2
        paidLeg(t, prevWed.minusDays(4), prevWed, "forward");     // lag 4
        paidLeg(t, wed.minusDays(9), wed, "forward");             // lag 9 → median 4 (deposit → paid would be 1, 3, 8)
        paidLeg(t, wed.minusDays(30), wed, "return");             // not a forward leg: never measured

        Map<String, Object> fees = ok(t, "/api/v1/analytics/money/fees?" + last(30));
        assertThat(dec(fees, "payoutLagDays")).isEqualByComparingTo("4.0");
        assertThat(n(fees, "payoutLagShipments")).isEqualTo(3);
        Map<String, Object> method = m(m(ok(t, "/api/v1/analytics/cash-forecast"), "forecast"), "method");
        assertThat(dec(method, "medianLagDays")).isEqualByComparingTo("4.0");
        assertThat(n(method, "lagSample")).isEqualTo(3);

        // Pre-floor orders are never measured, in either.
        T late = new T("B1-LagFloor", Instant.now().minus(Duration.ofDays(5)));
        paidLeg(late, wed.minusDays(2), wed, "forward");
        paidLeg(late, prevWed.minusDays(4), prevWed, "forward");
        assertThat(n(ok(late, "/api/v1/analytics/money/fees?" + last(30)), "payoutLagShipments")).isZero();
        assertThat(ok(late, "/api/v1/analytics/cash-forecast").get("reason")).isEqualTo("payout_lag_unknown");
    }

    // ── d. zero cash cycles ─────────────────────────────────────────────────

    final BostaGateway bosta = mock(BostaGateway.class);
    final BostaIngestionHelper ingestion = mock(BostaIngestionHelper.class);

    SettlementRefreshJob job() {
        return new SettlementRefreshJob(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()),
            jdbc, txm, encryption, bosta, ingestion, floorOverrides, Clock.system(CAIRO), 100, true);
    }

    @Test
    void zeroCycle_neverReReadNeverUnresolved_whileANegativeDepositStillIs() {
        T t = new T("B1-ZeroJob");
        Leg zero = t.leg(t.order(daysAgo(70)), "forward", "returned", RTO_ZERO);
        // Read successfully 45+ days after it finished (as the two Snouts legs were before B1).
        set(zero, "returned_at = ?, settlement_verified_at = now(), settlement_refreshed_at = now()",
            Timestamp.from(daysAgo(60)));
        Leg negative = t.leg(t.order(daysAgo(70)), "forward", "returned", SettlementPayloads.RTO_DEPOSITED);
        set(negative, "returned_at = ?", Timestamp.from(daysAgo(60)));
        assertThat(jdbc.queryForObject("SELECT settlement_status FROM shipments WHERE id = ?", String.class, zero.id()))
            .isEqualTo("deposited");
        when(bosta.fetchDelivery(anyString(), anyString())).thenAnswer(inv -> {
            String tn = inv.getArgument(1);
            String raw = jdbc.queryForObject("SELECT raw::text FROM shipments WHERE tracking_number = ?", String.class, tn);
            return BostaDelivery.fromRaw(tn, ShipmentSettlementTest.json(raw));
        });

        SettlementRefreshJob.RefreshResult r = job().refreshTenant(t.id, "key");
        assertThat(r.selected()).isEqualTo(1);                                // only the negative deposit
        verify(bosta, never()).fetchDelivery(anyString(), eq(zero.tn()));
        assertThat(r.markedUnresolved()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT settlement_status FROM shipments WHERE id = ?", String.class, zero.id()))
            .isEqualTo("deposited");
        assertThat(jdbc.queryForObject("SELECT settlement_status FROM shipments WHERE id = ?", String.class, negative.id()))
            .isEqualTo("unresolved");
    }

    @Test
    void zeroCycle_isNotOwed_noAwaitingPayout_noDeliveredNotPaid_paidAtZeroOnTheOrderList() {
        T t = new T("B1-ZeroReaders");
        UUID v = t.variant("S");
        // A delivered COD whose fees ate the whole COD: settled for 0, refreshed recently, deposited 20 days ago.
        O zeroOrder = t.order(daysAgo(25));
        t.line(zeroOrder, v, 1);
        Leg zero = t.forward(zeroOrder, "delivered", 100);
        set(zero, "settlement_status = 'deposited', cash_cycle_id = '1', deposited_amt = 0, bosta_fees = 100, " +
                  "deposited_at = ?, settlement_refreshed_at = now()", Timestamp.from(daysAgo(20)));
        O owed = t.order(daysAgo(25));
        t.line(owed, v, 1);
        Leg owedLeg = t.forward(owed, "delivered", 100);
        set(owedLeg, "settlement_status = 'deposited', cash_cycle_id = '2', deposited_amt = 50, bosta_fees = 50, " +
                     "deposited_at = ?, settlement_refreshed_at = now()", Timestamp.from(daysAgo(20)));
        jdbc.update("UPDATE shipments SET settlement_status = 'unresolved', cash_cycle_id = '3', deposited_amt = 0, " +
                    "deposited_at = ? WHERE id = ?", Timestamp.from(daysAgo(60)),
                    t.forward(t.order(daysAgo(70)), "returned", 0).id());   // a zero leg marked before B1

        Map<String, Object> stuck = ok(t, "/api/v1/analytics/money/stuck");
        assertThat(list(stuck, "deliveredNotPaid")).extracting(x -> x.get("trackingNumber")).containsExactly(owedLeg.tn());
        assertThat(n(stuck, "unresolved")).isZero();
        Map<String, Object> awaiting = m(ok(t, "/api/v1/analytics/money/pipeline?" + last(30)), "awaitingPayout");
        assertThat(n(awaiting, "count")).isEqualTo(1);
        assertThat(dec(awaiting, "deposited")).isEqualByComparingTo("50.00");

        Map<String, Map<String, Object>> rows = new HashMap<>();
        for (Map<String, Object> r : list(ok(t, "/api/v1/analytics/orders?" + last(30)), "orders")) rows.put((String) r.get("name"), r);
        assertThat(rows.get(zeroOrder.number()).get("financialStatus")).isEqualTo("paid");
        assertThat(dec(rows.get(zeroOrder.number()), "netToYou")).isEqualByComparingTo("0.00");
        assertThat(rows.get(owed.number()).get("financialStatus")).isEqualTo("overdue");    // 14 days, no weekday known
    }

    @Test
    void v150_putsZeroCyclesMarkedUnresolvedBackToDeposited_andNothingElse() throws Exception {
        T t = new T("B1-V150");
        Leg zero = t.leg(t.order(daysAgo(70)), "forward", "returned", RTO_ZERO);
        Leg negative = t.leg(t.order(daysAgo(70)), "forward", "returned", SettlementPayloads.RTO_DEPOSITED);
        Leg none = t.forward(t.order(daysAgo(70)), "returned", 0);
        jdbc.update("UPDATE shipments SET settlement_status = 'unresolved' WHERE id IN (?, ?, ?)", zero.id(), negative.id(), none.id());

        String sql = new String(new ClassPathResource("db/migration/V150__settlement_zero_cycle_not_unresolved.sql")
            .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        jdbc.execute(sql);
        jdbc.execute(sql);                                                    // idempotent

        assertThat(status(zero)).isEqualTo("deposited");
        assertThat(status(negative)).isEqualTo("unresolved");
        assertThat(status(none)).isEqualTo("unresolved");
    }

    String status(Leg l) {
        return jdbc.queryForObject("SELECT settlement_status FROM shipments WHERE id = ?", String.class, l.id());
    }
}
