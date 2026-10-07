package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.traceability.analytics.AnalyticsFloorOverrides;
import com.traceability.analytics.AnalyticsPeriod;
import com.traceability.analytics.MoneyAnalyticsService;
import com.traceability.analytics.SettlementRefreshJob;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaIngestionHelper;
import com.traceability.integrations.bosta.BostaRateLimitException;
import com.traceability.integrations.bosta.BostaRateLimiter;
import com.traceability.integrations.bosta.DeliveryNotFoundException;
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
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Analytics slice 3 — Bosta money: the settlement writer's monotonic rule, SettlementRefreshJob
 * (eligibility, cadence, cap, 45-day unresolved, limiter priority, 429 back-off) against a mock
 * gateway, and the /api/v1/analytics/money endpoints' math, roles and tenant isolation.
 *
 * The Spring-managed refresh job is switched off (refresh-enabled=false) so its hourly run never
 * touches these fixtures; the job under test is hand-built around a Mockito BostaGateway and a
 * fixed Clock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalyticsMoneyTest {

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
    static final String SEPT = "from=2026-09-01&to=2026-09-30";
    static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

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

    static Instant cairo(int y, int m, int d, int h) {
        return LocalDateTime.of(y, m, d, h, 0).atZone(CAIRO).toInstant();
    }

    static Instant ago(Duration d) {
        return Instant.now().minus(d);
    }

    static final Instant SEPT_10 = cairo(2026, 9, 10, 12);

    final class T {
        final UUID id = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        final UUID store = UUID.randomUUID();
        final UUID product = UUID.randomUUID();
        final String ownerToken;

        T(String name) {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                        "VALUES (?, ?, 'Owner', ?, 'x', 'owner')", owner, id, "owner+" + owner + "@an3.test");
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                        "VALUES (?, ?, 'shopify', ?, 'connected', ?)",
                        store, id, "an3-" + id + ".myshopify.com", Timestamp.from(cairo(2026, 1, 1, 0)));
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
            return order(placedAt, "new", null, "{}");
        }

        UUID order(Instant placedAt, String status, String carrierClass, String raw) {
            UUID o = UUID.randomUUID();
            jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw, " +
                        "shipping_carrier_class) VALUES (?, ?, ?, ?, ?, ?::order_status, ?, ?::jsonb, ?)",
                        o, id, store, "EXT-" + o, "#" + SEQ.incrementAndGet(), status, Timestamp.from(placedAt),
                        raw, carrierClass);
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
            String tn = "8" + SEQ.incrementAndGet();
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

    static String sendRaw(String city, int cod, int quote) {
        return "{\"type\":{\"code\":10,\"value\":\"Send\"},\"cod\":" + cod + ",\"shipmentFees\":" + quote +
               ",\"dropOffAddress\":{\"city\":{\"_id\":\"id-" + city + "\",\"name\":\"" + city + "\"}}," +
               "\"wallet\":{\"cashCycle\":null}}";
    }

    static String typedRaw(int type, String city, int quote) {
        return "{\"type\":{\"code\":" + type + "},\"shipmentFees\":" + quote +
               ",\"dropOffAddress\":{\"city\":{\"_id\":\"id-" + city + "\",\"name\":\"" + city + "\"}}}";
    }

    static String depositedRaw(String amt, String fees, LocalDate next) {
        return "{\"type\":{\"code\":10},\"cod\":0,\"wallet\":{\"cashCycle\":{\"_id\":" + SEQ.incrementAndGet() +
               ",\"deposited_at\":\"2026-09-20T08:00:00.000Z\",\"deposited_amt\":" + amt + ",\"bosta_fees\":\"" + fees +
               "\"},\"cashout\":{\"next_cashout_date\":" + (next == null ? "null" : "\"" + next + "T00:00:00.000Z\"") + "}}}";
    }

    static String paidRaw(String txn, String amt, String batch) {
        return "{\"type\":{\"code\":10},\"wallet\":{\"cashCycle\":{\"_id\":" + SEQ.incrementAndGet() +
               ",\"deposited_at\":\"2026-09-01T08:00:00.000Z\",\"deposited_amt\":\"" + amt + "\",\"bosta_fees\":\"10.00\"}," +
               "\"cashout\":{\"transaction_id\":\"" + txn + "\"" + (batch == null ? "" : ",\"amount\":\"" + batch + "\"") + "}}}";
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

    Map<String, Object> row(UUID shipmentId) {
        return jdbc.queryForMap("SELECT * FROM shipments WHERE id = ?", shipmentId);
    }

    /** The last two Wednesdays before today (Cairo) — gives the tenant a known payout weekday. */
    void wednesdayPayouts(T t) {
        LocalDate wed = LocalDate.now(CAIRO).minusDays(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY));
        for (LocalDate d : List.of(wed, wed.minusWeeks(1))) {
            Leg l = t.forward(ago(Duration.ofDays(20)), "delivered", "{\"type\":{\"code\":10}}");
            set(l, "settlement_status = 'paid', cashout_txn_id = ?, cashout_date = ?, deposited_amt = 100, " +
                   "deposited_at = ?", "WEDCOD" + d, java.sql.Date.valueOf(d), ts(ago(Duration.ofDays(25))));
        }
    }

    final BostaGateway bosta = mock(BostaGateway.class);
    final BostaIngestionHelper ingestion = mock(BostaIngestionHelper.class);

    /** Bosta answers with exactly what we already hold for that tracking number (same state, type). */
    BostaDelivery storedAnswer(String tn) {
        String raw = jdbc.queryForObject("SELECT raw::text FROM shipments WHERE tracking_number = ?", String.class, tn);
        return delivery(tn, raw);
    }

    SettlementRefreshJob job(Clock clock, int cap) {
        return new SettlementRefreshJob(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()),
            jdbc, txm, encryption, bosta, ingestion, floorOverrides, clock, cap, true);
    }

    static Clock clockOn(DayOfWeek day) {
        LocalDate d = LocalDate.now(CAIRO).with(TemporalAdjusters.nextOrSame(day));
        return Clock.fixed(d.atTime(10, 0).atZone(CAIRO).toInstant(), CAIRO);
    }

    static BostaDelivery delivery(String tn, String raw) {
        return BostaDelivery.fromRaw(tn, ShipmentSettlementTest.json(raw));
    }

    List<String> fetched() {
        List<String> tns = new ArrayList<>();
        mockingDetails(bosta).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("fetchDelivery"))
            .forEach(i -> tns.add((String) i.getArguments()[1]));
        return tns;
    }

    // ── the writer: monotonic ────────────────────────────────────────────────

    @Test
    void writer_monotonic_neverClearsAndOnlyMovesForward() {
        T t = new T("An3-Monotonic");
        Leg l = t.forward(SEPT_10, "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        assertThat(row(l.id()).get("settlement_status")).isEqualTo("none");
        assertThat((BigDecimal) row(l.id()).get("shipment_fees_quoted")).isEqualByComparingTo("50");

        ShipmentSettlement.apply(jdbc, l.id(), ShipmentSettlementTest.json(SettlementPayloads.DELIVERED_PAID));
        Map<String, Object> paid = row(l.id());
        assertThat(paid.get("settlement_status")).isEqualTo("paid");
        assertThat((BigDecimal) paid.get("deposited_amt")).isEqualByComparingTo("885.12");
        assertThat(paid.get("cashout_txn_id")).isEqualTo("WEDCOD09SEP26");

        // A thinner payload (v2 item: cashCycle null, next_cashout_date null) clears nothing.
        ShipmentSettlement.apply(jdbc, l.id(), ShipmentSettlementTest.json(SettlementPayloads.V2_ITEM_NULL_WALLET));
        Map<String, Object> after = row(l.id());
        assertThat(after.get("settlement_status")).isEqualTo("paid");
        assertThat((BigDecimal) after.get("deposited_amt")).isEqualByComparingTo("885.12");
        assertThat((BigDecimal) after.get("bosta_fees")).isEqualByComparingTo("104.88");
        assertThat(after.get("cashout_txn_id")).isEqualTo("WEDCOD09SEP26");
        assertThat(after.get("cashout_date").toString()).isEqualTo("2026-09-09");
        assertThat(after.get("cash_cycle_id")).isEqualTo("76483748");
        // ...while a newer non-null value does replace an older one (the quote).
        assertThat((BigDecimal) after.get("shipment_fees_quoted")).isEqualByComparingTo("59");

        // A deposited-only payload never moves paid back to deposited.
        ShipmentSettlement.apply(jdbc, l.id(), ShipmentSettlementTest.json(SettlementPayloads.RTO_DEPOSITED));
        assertThat(row(l.id()).get("settlement_status")).isEqualTo("paid");
        assertThat(row(l.id()).get("cashout_txn_id")).isEqualTo("WEDCOD09SEP26");

        // unresolved: stays unresolved on a payload without money; moves on when the money shows.
        Leg u = t.forward(SEPT_10, "returned", SettlementPayloads.DELIVERED_UNSETTLED);
        set(u, "settlement_status = 'unresolved'");
        ShipmentSettlement.apply(jdbc, u.id(), ShipmentSettlementTest.json(SettlementPayloads.V2_ITEM_NULL_WALLET));
        assertThat(row(u.id()).get("settlement_status")).isEqualTo("unresolved");
        ShipmentSettlement.apply(jdbc, u.id(), ShipmentSettlementTest.json(SettlementPayloads.RTO_DEPOSITED));
        assertThat(row(u.id()).get("settlement_status")).isEqualTo("deposited");
        assertThat((BigDecimal) row(u.id()).get("deposited_amt")).isEqualByComparingTo("-77.52");

        // apply() never stamps settlement_refreshed_at; only the refresh job's read does.
        assertThat(row(l.id()).get("settlement_refreshed_at")).isNull();
    }

    // ── refresh job ──────────────────────────────────────────────────────────

    @Test
    void refresh_eligibility_45dayUnresolved_appliesPayload_backgroundPriority() {
        T t = new T("An3-Eligible");
        Instant recent = ago(Duration.ofDays(5));
        Leg a = t.forward(recent, "delivered", SettlementPayloads.DELIVERED_UNSETTLED);          // none, never refreshed
        Leg b = t.forward(recent, "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        set(b, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(2))));                    // too soon
        Leg c = t.forward(recent, "returned", SettlementPayloads.DELIVERED_UNSETTLED);
        set(c, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(13))));                   // 12 h passed
        Leg d = t.forward(recent, "with_courier", SettlementPayloads.DELIVERED_UNSETTLED);       // not terminal
        Leg e = t.forward(recent, "delivered", SettlementPayloads.DELIVERED_PAID);               // paid
        Leg f = t.forward(recent, "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        set(f, "settlement_status = 'unresolved'");
        Leg g = t.forward(recent, "lost", SettlementPayloads.DELIVERED_UNSETTLED);
        set(g, "provider_not_found_at = now()");                                                 // Bosta forgot it
        Leg h = t.forward(cairo(2025, 12, 1, 12), "delivered", SettlementPayloads.DELIVERED_UNSETTLED); // pre-floor
        Leg i = t.forward(recent, "terminated", SettlementPayloads.RTO_DEPOSITED);               // deposited, never
        Leg j = t.forward(recent, "delivered", SettlementPayloads.RTO_DEPOSITED);
        set(j, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(21))));                   // daily (no weekday known)
        Leg k = t.forward(recent, "delivered", SettlementPayloads.RTO_DEPOSITED);
        set(k, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(3))));                    // too soon
        Leg l = t.forward(recent, "returned", SettlementPayloads.DELIVERED_UNSETTLED);
        set(l, "returned_at = ?", ts(ago(Duration.ofDays(50))));                                // 45+ days: read, then unresolved
        Leg l2 = t.forward(recent, "delivered", SettlementPayloads.RTO_DEPOSITED);
        set(l2, "delivered_at = ?", ts(ago(Duration.ofDays(46))));                              // deposited, 45+ days: same
        Leg l3 = t.forward(recent, "delivered", SettlementPayloads.DELIVERED_PAID);
        set(l3, "delivered_at = ?", ts(ago(Duration.ofDays(60))));                              // paid stays paid
        Leg mm = t.leg(t.order(recent, "cancelled", null, "{}"), "forward", "delivered",
                       SettlementPayloads.DELIVERED_UNSETTLED);                                  // cancelled order
        Leg crp = t.leg(t.order(recent), "return", "delivered",
                        "{\"type\":{\"code\":25},\"state\":{\"code\":45},\"shipmentFees\":93}"); // return legs count too

        List<BostaRateLimiter.Priority> priorities = new CopyOnWriteArrayList<>();
        when(bosta.fetchDelivery(anyString(), anyString())).thenAnswer(inv -> {
            priorities.add(BostaRateLimiter.priorityOverride());
            String tn = inv.getArgument(1);
            return tn.equals(a.tn()) ? delivery(tn, SettlementPayloads.DELIVERED_PAID) : storedAnswer(tn);
        });

        SettlementRefreshJob.RefreshResult r = job(Clock.system(CAIRO), 100).refreshTenant(t.id, "key");

        // The two legs finished 45+ days ago are read first, oldest first.
        assertThat(fetched()).hasSize(7);
        assertThat(fetched().subList(0, 2)).containsExactly(l.tn(), l2.tn());
        assertThat(fetched()).containsExactlyInAnyOrder(a.tn(), c.tn(), i.tn(), j.tn(), crp.tn(), l.tn(), l2.tn());
        assertThat(r.selected()).isEqualTo(7);
        assertThat(r.refreshed()).isEqualTo(7);
        assertThat(r.routed()).isZero();
        assertThat(r.newlyPaid()).isEqualTo(1);
        // Read successfully 45+ days after finishing, still unpaid → unresolved (on evidence, not stale raw).
        assertThat(r.markedUnresolved()).isEqualTo(2);
        assertThat(r.payoutWeekday()).isNull();
        verify(bosta, atLeastOnce()).fetchDelivery(eq("key"), anyString());
        verifyNoInteractions(ingestion);
        // BACKGROUND: never upgraded to USER_FACING around the call (the gateway's default is BACKGROUND).
        assertThat(priorities).hasSize(7).containsOnlyNulls();

        assertThat(row(a.id()).get("settlement_status")).isEqualTo("paid");
        assertThat(row(a.id()).get("settlement_refreshed_at")).isNotNull();
        assertThat(row(c.id()).get("settlement_refreshed_at")).isNotNull();
        assertThat(row(l.id()).get("settlement_status")).isEqualTo("unresolved");
        assertThat(row(l2.id()).get("settlement_status")).isEqualTo("unresolved");
        assertThat(row(l3.id()).get("settlement_status")).isEqualTo("paid");
        for (Leg x : List.of(b, d, e, f, g, h, k, mm)) {
            assertThat(fetched()).doesNotContain(x.tn());
        }

        // A second run right away selects nothing — everything just refreshed.
        clearInvocations(bosta);
        assertThat(job(Clock.system(CAIRO), 100).refreshTenant(t.id, "key").selected()).isZero();
    }

    @Test
    void refresh_deposited_dailyOnlyTheDayAfterThePayoutWeekday_with8DaySafetyNet() {
        T t = new T("An3-Cadence");
        wednesdayPayouts(t);
        Instant recent = ago(Duration.ofDays(10));
        Leg daily = t.forward(recent, "delivered", SettlementPayloads.RTO_DEPOSITED);
        set(daily, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(21))));
        Leg stale = t.forward(recent, "delivered", SettlementPayloads.RTO_DEPOSITED);
        set(stale, "settlement_refreshed_at = ?", ts(ago(Duration.ofDays(9))));
        Leg none = t.forward(recent, "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        set(none, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(13))));

        // Friday: not the day after Wednesday — only the 8-day safety net and 'none' (12 h) go.
        SettlementRefreshJob.RefreshResult fri = job(clockOn(DayOfWeek.FRIDAY), 100).refreshTenant(t.id, "key");
        assertThat(fri.payoutWeekday()).isEqualTo(3);
        assertThat(fetched()).containsExactlyInAnyOrder(stale.tn(), none.tn());

        // Thursday (the day after the payout weekday): the daily deposited leg goes.
        clearInvocations(bosta);
        job(clockOn(DayOfWeek.THURSDAY), 100).refreshTenant(t.id, "key");
        assertThat(fetched()).containsExactly(daily.tn());
    }

    @Test
    void refresh_capPerTenant_leastRecentlyRefreshedFirst() {
        T t = new T("An3-Cap");
        Instant recent = ago(Duration.ofDays(5));
        Leg l1 = t.forward(recent, "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        Leg l2 = t.forward(recent, "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        set(l2, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(17))));
        Leg l3 = t.forward(recent, "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        set(l3, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(16))));
        Leg l4 = t.forward(recent, "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        set(l4, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(15))));

        SettlementRefreshJob.RefreshResult r = job(Clock.system(CAIRO), 2).refreshTenant(t.id, "key");
        assertThat(r.selected()).isEqualTo(2);
        assertThat(fetched()).containsExactly(l1.tn(), l2.tn());
    }

    @Test
    void refresh_rateLimit_stopsTheTenant_backsOff_restUntouched_notFoundStamps() {
        T t = new T("An3-429");
        t.bostaAccount();
        Instant recent = ago(Duration.ofDays(5));
        Leg l1 = t.forward(recent, "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        Leg l2 = t.forward(recent, "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        set(l2, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(14))));
        Leg l3 = t.forward(recent, "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        set(l3, "settlement_refreshed_at = ?", ts(ago(Duration.ofHours(13))));
        Object l3Before = row(l3.id()).get("settlement_refreshed_at");

        when(bosta.fetchDelivery(anyString(), anyString())).thenAnswer(inv -> {
            String tn = inv.getArgument(1);
            if (tn.equals(l1.tn())) throw new DeliveryNotFoundException(tn);
            if (tn.equals(l2.tn())) throw new BostaRateLimitException(60);
            return null;
        });
        SettlementRefreshJob job = job(Clock.system(CAIRO), 100);
        SettlementRefreshJob.RefreshResult r = job.refreshTenant(t.id, "key");

        assertThat(r.rateLimited()).isTrue();
        assertThat(r.notFound()).isEqualTo(1);
        assertThat(r.refreshed()).isZero();
        assertThat(fetched()).containsExactly(l1.tn(), l2.tn());
        assertThat(row(l1.id()).get("settlement_refreshed_at")).as("not found: stamped, retried in 12 h").isNotNull();
        assertThat(row(l1.id()).get("settlement_status")).isEqualTo("none");
        assertThat(row(l1.id()).get("settlement_verified_at")).as("a not-found is never a successful read").isNull();
        assertThat(row(l3.id()).get("settlement_refreshed_at")).as("after the 429: untouched").isEqualTo(l3Before);

        // The hourly run skips the backed-off tenant until retry-after passes.
        clearInvocations(bosta);
        job.refreshAll();
        assertThat(fetched()).doesNotContain(l1.tn(), l2.tn(), l3.tn());
    }

    @Test
    void refresh_oldLegWithStaleRaw_becomesPaidAfterRefresh_notUnresolved() {
        T t = new T("An3-OldPaid");
        Leg old = t.forward(ago(Duration.ofDays(70)), "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        set(old, "delivered_at = ?", ts(ago(Duration.ofDays(60))));
        when(bosta.fetchDelivery(anyString(), anyString()))
            .thenAnswer(inv -> delivery(inv.getArgument(1), SettlementPayloads.DELIVERED_PAID));

        SettlementRefreshJob.RefreshResult r = job(Clock.system(CAIRO), 100).refreshTenant(t.id, "key");
        assertThat(row(old.id()).get("settlement_status")).isEqualTo("paid");
        assertThat(r.markedUnresolved()).isZero();
        assertThat(r.newlyPaid()).isEqualTo(1);
        assertThat(row(old.id()).get("settlement_verified_at")).isNotNull();
    }

    @Test
    void refresh_oldLegNeverReadSuccessfully_isNeverUnresolved() {
        T t = new T("An3-NeverRead");
        Leg notFound = t.forward(ago(Duration.ofDays(70)), "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        set(notFound, "delivered_at = ?", ts(ago(Duration.ofDays(60))));
        Leg failing = t.forward(ago(Duration.ofDays(70)), "returned", SettlementPayloads.DELIVERED_UNSETTLED);
        set(failing, "returned_at = ?", ts(ago(Duration.ofDays(55))));
        // Old, attempted before (stamped) but never read successfully, and not due this run.
        Leg attempted = t.forward(ago(Duration.ofDays(70)), "delivered", SettlementPayloads.RTO_DEPOSITED);
        set(attempted, "delivered_at = ?, settlement_refreshed_at = ?", ts(ago(Duration.ofDays(50))),
            ts(ago(Duration.ofHours(2))));
        when(bosta.fetchDelivery(anyString(), anyString())).thenAnswer(inv -> {
            String tn = inv.getArgument(1);
            if (tn.equals(notFound.tn())) throw new DeliveryNotFoundException(tn);
            throw new IllegalStateException("Bosta 500");
        });

        SettlementRefreshJob.RefreshResult r = job(Clock.system(CAIRO), 100).refreshTenant(t.id, "key");
        assertThat(r.markedUnresolved()).isZero();
        assertThat(fetched()).containsExactly(notFound.tn(), failing.tn());   // oldest finish first
        for (Leg l : List.of(notFound, failing, attempted)) {
            assertThat(row(l.id()).get("settlement_status")).as(l.tn()).isNotEqualTo("unresolved");
            assertThat(row(l.id()).get("settlement_verified_at")).as(l.tn()).isNull();
        }
    }

    @Test
    void refresh_oldLegsFirst_oldestFinishFirst_withinTheCap() {
        T t = new T("An3-OldFirst");
        Leg recent = t.forward(ago(Duration.ofDays(5)), "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        Leg old50 = t.forward(ago(Duration.ofDays(80)), "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        set(old50, "delivered_at = ?, settlement_refreshed_at = ?", ts(ago(Duration.ofDays(50))),
            ts(ago(Duration.ofHours(13))));
        Leg old60 = t.forward(ago(Duration.ofDays(80)), "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        set(old60, "delivered_at = ?", ts(ago(Duration.ofDays(60))));

        job(Clock.system(CAIRO), 2).refreshTenant(t.id, "key");
        assertThat(fetched()).containsExactly(old60.tn(), old50.tn());
        assertThat(fetched()).doesNotContain(recent.tn());
    }

    @Test
    void refresh_stateChanged_goesThroughThePollPipeline_unchanged_writesRawAndSettlementOnly() {
        T t = new T("An3-Route");
        Leg same = t.forward(ago(Duration.ofDays(5)), "delivered", SettlementPayloads.V2_ITEM_NULL_WALLET);
        Leg moved = t.forward(ago(Duration.ofDays(5)), "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        String rto = "{\"type\":{\"code\":20,\"value\":\"Return to Origin\"},\"state\":{\"code\":41},\"cod\":500," +
                     "\"shipmentFees\":50,\"wallet\":{\"cashCycle\":null}}";
        when(bosta.fetchDelivery(anyString(), anyString())).thenAnswer(inv -> {
            String tn = inv.getArgument(1);
            return tn.equals(moved.tn()) ? delivery(tn, rto) : delivery(tn, SettlementPayloads.DELIVERED_PAID);
        });
        Object movedRawBefore = jdbc.queryForObject("SELECT raw::text FROM shipments WHERE id = ?", String.class, moved.id());

        SettlementRefreshJob.RefreshResult r = job(Clock.system(CAIRO), 100).refreshTenant(t.id, "key");
        assertThat(r.routed()).isEqualTo(1);

        // Changed (delivered SEND → RTO): handed to the poll pipeline with the stored state; nothing
        // here writes its raw or state.
        verify(ingestion).ingestFetched(eq(t.id), argThat(d -> d.trackingNumber().equals(moved.tn())),
            eq("bosta_poll"), eq(45));
        verifyNoMoreInteractions(ingestion);
        assertThat(jdbc.queryForObject("SELECT raw::text FROM shipments WHERE id = ?", String.class, moved.id()))
            .isEqualTo(movedRawBefore);
        assertThat(row(moved.id()).get("internal_state").toString()).isEqualTo("delivered");

        // Unchanged (45 → 45): the fresh v0 raw replaces the v2 item, settlement written.
        String sameRaw = jdbc.queryForObject("SELECT raw::text FROM shipments WHERE id = ?", String.class, same.id());
        assertThat(sameRaw).doesNotContain("_tracedRawShape").contains("WEDCOD09SEP26");
        assertThat(row(same.id()).get("settlement_status")).isEqualTo("paid");
    }

    // ── /pipeline ────────────────────────────────────────────────────────────

    @Test
    void pipeline_stages_negativeDepositsNet_wijhaNeverInMoney() {
        T t = new T("An3-Pipeline");
        t.bostaAccount();
        UUID v = t.variant("PIPE");
        Instant recent = ago(Duration.ofDays(3));
        String cairoAddress = "{\"shipping_address\":{\"province_code\":\"C\"}}";

        // City rate, Cairo: 3 delivered, 1 refused → 0.75. Delivered legs not settled: estimate cod − quote×1.14.
        for (int i = 0; i < 3; i++) {
            UUID o = t.order(recent, "new", "bosta", cairoAddress);
            t.line(o, v, 1, "100.00");
            t.leg(o, "forward", "delivered", sendRaw("Cairo", 500, 50));
        }
        t.leg(t.order(recent), "forward", "returned", typedRaw(20, "Cairo", 50));

        // Not fulfilled: no leg (city from province code) + booked never picked up; Wijha left out.
        UUID unbooked = t.order(recent, "new", null, cairoAddress);
        t.line(unbooked, v, 2, "100.00");
        UUID booked = t.order(recent, "new", "bosta", "{}");
        t.line(booked, v, 1, "100.00");
        t.leg(booked, "forward", "created", sendRaw("Cairo", 100, 50));
        UUID wijha = t.order(recent, "new", "other_known", cairoAddress);
        t.line(wijha, v, 5, "100.00");

        // In transit: SEND with courier counts; a type-20 RTO with courier does not.
        t.leg(t.order(recent), "forward", "with_courier", sendRaw("Cairo", 1000, 50));
        t.leg(t.order(recent), "forward", "with_courier", typedRaw(20, "Giza", 50));

        // Awaiting payout: +450 and a refused leg's −77.52 net; only a future next_cashout_date shows.
        LocalDate next = LocalDate.now(CAIRO).plusDays(5);
        t.leg(t.order(recent), "forward", "delivered", depositedRaw("450.00", "50.00", next));
        t.leg(t.order(recent), "forward", "returned", SettlementPayloads.RTO_DEPOSITED);

        // In your bank (period Sept): paid on 09-09; an August payout is outside.
        t.leg(t.order(recent), "forward", "delivered", paidRaw("WEDCOD09SEP26", "885.12", null));
        t.leg(t.order(recent), "return", "delivered", SettlementPayloads.CRP_PAID_WITH_BATCH);

        Map<String, Object> body = ok(t, "/api/v1/analytics/money/pipeline?" + SEPT);
        Map<String, Object> nf = m(body, "notFulfilled");
        assertThat(n(nf, "count")).isEqualTo(2);
        assertThat(dec(nf, "value")).isEqualByComparingTo("300.00");
        assertThat(dec(nf, "expected")).isEqualByComparingTo("225.00");

        Map<String, Object> it = m(body, "inTransit");
        assertThat(n(it, "count")).isEqualTo(1);
        assertThat(dec(it, "value")).isEqualByComparingTo("1000.00");
        assertThat(dec(it, "expected")).isEqualByComparingTo("750.00");

        Map<String, Object> aw = m(body, "awaitingPayout");
        assertThat(n(aw, "count")).isEqualTo(2);
        assertThat(dec(aw, "deposited")).isEqualByComparingTo("372.48");
        assertThat(aw.get("nextCashoutDate")).isEqualTo(next.toString());
        assertThat(n(aw, "deliveredNotYetSettled")).isEqualTo(3);
        assertThat(dec(aw, "deliveredNotYetSettledEstimate")).isEqualByComparingTo("1329.00");

        Map<String, Object> bank = m(body, "inYourBank");
        assertThat(n(bank, "shipments")).isEqualTo(1);
        assertThat(dec(bank, "deposited")).isEqualByComparingTo("885.12");
        assertThat(n(bank, "payouts")).isEqualTo(1);
        assertThat(bank.get("lastTransferDate")).isEqualTo("2026-09-09");
    }

    @Test
    void pipeline_notFulfilled_zeroWithoutABostaAccount() {
        T t = new T("An3-NoBosta");
        UUID v = t.variant("NB");
        UUID o = t.order(ago(Duration.ofDays(2)));
        t.line(o, v, 1, "100.00");
        Map<String, Object> body = ok(t, "/api/v1/analytics/money/pipeline?period=7d");
        assertThat(n(m(body, "notFulfilled"), "count")).isZero();
    }

    // ── /fees + /fees/extra ──────────────────────────────────────────────────

    /** Sept: settled SEND, estimated SEND, RTO (failed), CRP (return), exchange (promo). */
    T feesTenant(UUID[] variants) {
        T t = new T("An3-Fees");
        UUID a = t.variant("SKU-A"), b = t.variant("SKU-B");
        variants[0] = a;
        variants[1] = b;

        Leg paid = t.forward(SEPT_10, "delivered", SettlementPayloads.DELIVERED_PAID);
        set(paid, "delivered_at = ?", ts(cairo(2026, 9, 5, 12)));
        Leg est = t.forward(SEPT_10, "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        set(est, "delivered_at = ?", ts(cairo(2026, 9, 6, 12)));

        UUID rtoOrder = t.order(SEPT_10);
        t.line(rtoOrder, a, 1, "300.00");
        t.line(rtoOrder, b, 1, "100.00");
        Leg rto = t.leg(rtoOrder, "forward", "returned", SettlementPayloads.RTO_DEPOSITED);
        set(rto, "returned_at = ?, last_failure_reason = 'Customer refused'", ts(cairo(2026, 9, 7, 12)));

        UUID crpOrder = t.order(SEPT_10);
        t.line(crpOrder, a, 1, "300.00");
        Leg crp = t.leg(crpOrder, "return", "delivered", SettlementPayloads.CRP_PAID_WITH_BATCH);
        set(crp, "delivered_at = ?", ts(cairo(2026, 9, 8, 12)));

        UUID exOrder = t.order(SEPT_10);
        t.line(exOrder, b, 1, "100.00");
        Leg ex = t.leg(exOrder, "forward", "delivered", SettlementPayloads.EXCHANGE_PROMO);
        set(ex, "delivered_at = ?", ts(cairo(2026, 9, 9, 12)));

        // Outside: October, still moving, pre-floor; Wijha has no Bosta leg at all.
        Leg oct = t.forward(SEPT_10, "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        set(oct, "delivered_at = ?", ts(cairo(2026, 10, 2, 12)));
        t.forward(SEPT_10, "with_courier", SettlementPayloads.DELIVERED_UNSETTLED);
        Leg pre = t.forward(cairo(2025, 12, 1, 12), "delivered", SettlementPayloads.DELIVERED_UNSETTLED);
        set(pre, "delivered_at = ?", ts(cairo(2026, 9, 5, 12)));
        UUID wijha = t.order(SEPT_10, "new", "other_known", "{}");
        t.line(wijha, a, 3, "300.00");
        return t;
    }

    @Test
    void fees_byKind_settledVsEstimated_components_costPerDelivery_payoutLag() {
        T t = feesTenant(new UUID[2]);
        Map<String, Object> body = ok(t, "/api/v1/analytics/money/fees?" + SEPT);

        Map<String, Object> ship = m(body, "shipping");
        assertThat(n(ship, "legs")).isEqualTo(2);
        assertThat(dec(ship, "amount")).isEqualByComparingTo("161.88");   // 104.88 settled + 50 × 1.14
        assertThat(n(ship, "estimatedCount")).isEqualTo(1);
        Map<String, Object> failed = m(body, "failed");
        assertThat(n(failed, "legs")).isEqualTo(1);
        assertThat(dec(failed, "amount")).isEqualByComparingTo("77.52");
        Map<String, Object> exchange = m(body, "exchange");
        assertThat(n(exchange, "legs")).isEqualTo(1);
        assertThat(dec(exchange, "amount")).isEqualByComparingTo("0.00");
        Map<String, Object> returned = m(body, "returned");
        assertThat(n(returned, "legs")).isEqualTo(1);
        assertThat(dec(returned, "amount")).isEqualByComparingTo("106.02");
        Map<String, Object> total = m(body, "total");
        assertThat(n(total, "legs")).isEqualTo(5);
        assertThat(dec(total, "amount")).isEqualByComparingTo("345.42");
        assertThat(n(total, "estimatedCount")).isEqualTo(1);

        Map<String, Object> c = m(body, "settledComponents");
        assertThat(n(c, "settledLegs")).isEqualTo(4);
        assertThat(dec(c, "shippingFees")).isEqualByComparingTo("340.00");
        assertThat(dec(c, "openingPackageFees")).isEqualByComparingTo("7.00");
        assertThat(dec(c, "vat")).isEqualByComparingTo("35.42");
        assertThat(dec(c, "promotionDiscount")).isEqualByComparingTo("-94.00");

        assertThat(dec(body, "costPerSuccessfulDelivery")).isEqualByComparingTo("80.94");
        assertThat(n(body, "deliveredCount")).isEqualTo(2);
        assertThat(dec(body, "costPerUnsuccessfulDelivery")).isEqualByComparingTo("77.52");
        assertThat(n(body, "refusedCount")).isEqualTo(1);
        // Paid on 09-09, deposited 09-01 → 8 days (the August payout is outside the period).
        assertThat(dec(body, "payoutLagDays")).isEqualByComparingTo("8.0");
        assertThat(n(body, "payoutLagShipments")).isEqualTo(1);
    }

    @Test
    void fees_estimateSwitchesToSettledWhenBostaSettles() {
        T t = new T("An3-Estimate");
        Leg l = t.forward(SEPT_10, "delivered", "{\"type\":{\"code\":10},\"shipmentFees\":100}");
        set(l, "delivered_at = ?", ts(cairo(2026, 9, 5, 12)));

        Map<String, Object> before = m(ok(t, "/api/v1/analytics/money/fees?" + SEPT), "shipping");
        assertThat(dec(before, "amount")).isEqualByComparingTo("114.00");
        assertThat(n(before, "estimatedCount")).isEqualTo(1);

        ShipmentSettlement.apply(jdbc, l.id(), ShipmentSettlementTest.json(
            "{\"wallet\":{\"cashCycle\":{\"deposited_at\":\"2026-09-12T08:00:00Z\",\"deposited_amt\":\"410.00\"," +
            "\"cod\":\"500.00\",\"bosta_fees\":\"90.00\"}}}"));
        Map<String, Object> after = m(ok(t, "/api/v1/analytics/money/fees?" + SEPT), "shipping");
        assertThat(dec(after, "amount")).isEqualByComparingTo("90.00");
        assertThat(n(after, "estimatedCount")).isZero();
    }

    @Test
    void feesExtra_byAwb_andBySku_splitByLineValue() {
        UUID[] v = new UUID[2];
        T t = feesTenant(v);

        Map<String, Object> awb = ok(t, "/api/v1/analytics/money/fees/extra?groupBy=awb&" + SEPT);
        List<Map<String, Object>> rows = list(awb, "shipments");
        assertThat(rows).extracting(r -> r.get("type")).containsExactly("exchange", "return", "failed");
        assertThat(dec(awb, "total")).isEqualByComparingTo("183.54");
        assertThat(n(awb, "estimatedCount")).isZero();
        Map<String, Object> failed = rows.get(2);
        assertThat(failed.get("reason")).isEqualTo("Customer refused");
        assertThat(failed.get("date")).isEqualTo("2026-09-07");
        assertThat((List<Object>) failed.get("skus")).containsExactly("SKU-A", "SKU-B");

        Map<String, Object> sku = ok(t, "/api/v1/analytics/money/fees/extra?groupBy=sku&" + SEPT);
        List<Map<String, Object>> skus = list(sku, "skus");
        Map<String, Object> a = skus.stream().filter(r -> v[0].toString().equals(r.get("variantId"))).findFirst().orElseThrow();
        Map<String, Object> b = skus.stream().filter(r -> v[1].toString().equals(r.get("variantId"))).findFirst().orElseThrow();
        assertThat(dec(a, "extraFees")).isEqualByComparingTo("164.16");   // 77.52 × 0.75 + 106.02
        assertThat(n(a, "failed")).isEqualTo(1);
        assertThat(n(a, "returns")).isEqualTo(1);
        assertThat(dec(b, "extraFees")).isEqualByComparingTo("19.38");    // 77.52 × 0.25 + 0
        assertThat(n(b, "exchanges")).isEqualTo(1);
        assertThat(dec(sku, "total")).isEqualByComparingTo("183.54");

        assertThat(get(t.ownerToken, "/api/v1/analytics/money/fees/extra?groupBy=city&" + SEPT).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── /stuck ───────────────────────────────────────────────────────────────

    @Test
    void stuck_neverPickedUp_stuckWithBosta_deliveredNotPaidPerShipment() {
        T t = new T("An3-Stuck");
        wednesdayPayouts(t);

        Leg never = t.forward(ago(Duration.ofDays(12)), "created", sendRaw("Giza", 700, 50));
        set(never, "created_at = ?", ts(ago(Duration.ofDays(10))));
        Leg fresh = t.forward(ago(Duration.ofDays(3)), "created", sendRaw("Giza", 700, 50));
        set(fresh, "created_at = ?", ts(ago(Duration.ofDays(2))));

        Leg stuck = t.forward(ago(Duration.ofDays(15)), "with_courier", sendRaw("Giza", 800, 50));
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) " +
                    "VALUES (?, ?, 'with_courier', ?)", t.id, stuck.id(), ts(ago(Duration.ofDays(9))));
        Leg moving = t.forward(ago(Duration.ofDays(15)), "with_courier", sendRaw("Giza", 800, 50));
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) " +
                    "VALUES (?, ?, 'with_courier', ?)", t.id, moving.id(), ts(ago(Duration.ofDays(1))));

        // Delivered, not paid: two Wednesdays since the deposit and refreshed within 24 h.
        Leg notPaid = t.forward(ago(Duration.ofDays(25)), "delivered", "{\"type\":{\"code\":10}}");
        set(notPaid, "settlement_status = 'deposited', deposited_at = ?, deposited_amt = 430, settlement_refreshed_at = ?",
            ts(ago(Duration.ofDays(20))), ts(ago(Duration.ofHours(1))));
        Leg recentDeposit = t.forward(ago(Duration.ofDays(5)), "delivered", "{\"type\":{\"code\":10}}");
        set(recentDeposit, "settlement_status = 'deposited', deposited_at = ?, deposited_amt = 430, settlement_refreshed_at = ?",
            ts(ago(Duration.ofDays(3))), ts(ago(Duration.ofHours(1))));
        Leg staleRead = t.forward(ago(Duration.ofDays(25)), "delivered", "{\"type\":{\"code\":10}}");
        set(staleRead, "settlement_status = 'deposited', deposited_at = ?, deposited_amt = 430, settlement_refreshed_at = ?",
            ts(ago(Duration.ofDays(20))), ts(ago(Duration.ofHours(30))));
        Leg unresolved = t.forward(ago(Duration.ofDays(80)), "returned", "{\"type\":{\"code\":10}}");
        set(unresolved, "settlement_status = 'unresolved'");

        Map<String, Object> body = ok(t, "/api/v1/analytics/money/stuck");
        assertThat(list(body, "neverPickedUp")).extracting(r -> r.get("trackingNumber")).containsExactly(never.tn());
        assertThat(n(list(body, "neverPickedUp").get(0), "days")).isEqualTo(10);
        assertThat(list(body, "neverPickedUp").get(0).get("lastStatus")).isEqualTo("Booked, never picked up");
        assertThat(list(body, "stuckWithBosta")).extracting(r -> r.get("trackingNumber")).containsExactly(stuck.tn());
        assertThat(n(list(body, "stuckWithBosta").get(0), "days")).isEqualTo(9);
        assertThat(list(body, "deliveredNotPaid")).extracting(r -> r.get("trackingNumber")).containsExactly(notPaid.tn());
        assertThat(n(body, "payoutWeekday")).isEqualTo(3);
        assertThat(n(body, "unresolved")).isEqualTo(1);
    }

    // ── /payouts ─────────────────────────────────────────────────────────────

    @Test
    void payouts_perTransaction_trackedNextToBatch_neverADifference() {
        T t = new T("An3-Payouts");
        t.forward(SEPT_10, "delivered", SettlementPayloads.DELIVERED_PAID);                        // WEDCOD09SEP26 +885.12
        t.leg(t.order(SEPT_10), "return", "delivered", paidRaw("WEDCOD09SEP26", "-106.02", "67854.59"));
        t.forward(SEPT_10, "delivered", paidRaw("WEDCOD16SEP26", "200.00", null));
        t.forward(SEPT_10, "delivered", SettlementPayloads.CRP_PAID_WITH_BATCH);                   // August: outside

        Map<String, Object> body = ok(t, "/api/v1/analytics/money/payouts?" + SEPT);
        List<Map<String, Object>> rows = list(body, "payouts");
        assertThat(rows).extracting(r -> r.get("transactionId")).containsExactly("WEDCOD16SEP26", "WEDCOD09SEP26");
        Map<String, Object> wed9 = rows.get(1);
        assertThat(wed9.get("date")).isEqualTo("2026-09-09");
        assertThat(n(wed9, "trackedShipments")).isEqualTo(2);
        assertThat(dec(wed9, "trackedDeposited")).isEqualByComparingTo("779.10");   // a negative deposit nets
        assertThat(dec(wed9, "bostaBatchTotal")).isEqualByComparingTo("67854.59");
        assertThat(rows.get(0).get("bostaBatchTotal")).isNull();
        // No shortfall / difference field — the batch covers deliveries Traced doesn't track.
        assertThat(wed9.keySet()).containsExactlyInAnyOrder(
            "transactionId", "date", "trackedShipments", "trackedDeposited", "bostaBatchTotal");

        // ...and the per-shipment rule: every tracked shipment of the batch is paid → nothing "not paid".
        assertThat(list(ok(t, "/api/v1/analytics/money/stuck"), "deliveredNotPaid")).isEmpty();
    }

    // ── roles + isolation ────────────────────────────────────────────────────

    @Test
    void money_ownerOnly() {
        T t = new T("An3-Roles");
        UUID manager = UUID.randomUUID(), worker = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'M', ?, 'x', 'manager')",
                    manager, t.id, "m+" + manager + "@an3.test");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'W', ?, 'x', 'worker')",
                    worker, t.id, "w+" + worker + "@an3.test");
        String managerToken = jwt.issueAccessToken(manager, t.id, "manager");
        String workerToken = jwt.issueAccessToken(worker, t.id, "worker");
        for (String path : List.of("/api/v1/analytics/money/pipeline?period=30d",
                                   "/api/v1/analytics/money/fees?period=30d",
                                   "/api/v1/analytics/money/fees/extra?period=30d&groupBy=awb",
                                   "/api/v1/analytics/money/fees/extra?period=30d&groupBy=sku",
                                   "/api/v1/analytics/money/stuck",
                                   "/api/v1/analytics/money/payouts?period=30d")) {
            assertThat(get(t.ownerToken, path).getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
            assertThat(get(managerToken, path).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get(workerToken, path).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void money_appUser_rls_eachTenantSeesOnlyItsOwnMoney() {
        T a = new T("An3-IsoA");
        T b = new T("An3-IsoB");
        a.forward(SEPT_10, "delivered", SettlementPayloads.DELIVERED_PAID);
        Leg aFee = a.forward(SEPT_10, "returned", SettlementPayloads.RTO_DEPOSITED);
        set(aFee, "returned_at = ?", ts(cairo(2026, 9, 7, 12)));
        b.forward(SEPT_10, "delivered", paidRaw("WEDCOD16SEP26", "200.00", null));

        TenantAwareDataSource appUserDs = new TenantAwareDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
        MoneyAnalyticsService svc = new MoneyAnalyticsService(new JdbcTemplate(appUserDs), Clock.system(CAIRO),
                floorOverrides, 60);
        AnalyticsPeriod sept = new AnalyticsPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        TenantContext.set(b.id);
        MoneyAnalyticsService.Payouts bPayouts = tx.execute(s -> svc.payouts(sept));
        assertThat(bPayouts.payouts()).extracting(MoneyAnalyticsService.Payout::transactionId)
            .containsExactly("WEDCOD16SEP26");
        MoneyAnalyticsService.Fees bFees = tx.execute(s -> svc.fees(sept));
        assertThat(bFees.failed().legs()).isZero();
        assertThat(tx.execute(s -> svc.pipeline(sept)).awaitingPayout().count()).isZero();

        TenantContext.set(a.id);
        assertThat(tx.execute(s -> svc.payouts(sept)).payouts()).extracting(MoneyAnalyticsService.Payout::transactionId)
            .containsExactly("WEDCOD09SEP26");
        assertThat(tx.execute(s -> svc.fees(sept)).failed().legs()).isEqualTo(1);

        // No tenant context: refused, never everyone's money.
        TenantContext.clear();
        assertThatThrownBy(() -> tx.execute(s -> svc.payouts(sept))).isInstanceOf(RuntimeException.class);

        // app_user with no tenant GUC reads zero settlement rows even with a raw query.
        Long visible = tx.execute(s -> new JdbcTemplate(appUserDs).queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE settlement_status <> 'none'", Long.class));
        assertThat(visible).isZero();
    }
}
