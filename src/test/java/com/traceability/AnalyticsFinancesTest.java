package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.analytics.AnalyticsFloorOverrides;
import com.traceability.analytics.AnalyticsPeriod;
import com.traceability.analytics.OrderFinanceService;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.ShipmentSettlement;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Analytics slice 7 — order finances (/orders, /orders/export.csv, /variants/{id}/orders), the
 * Summary alerts (/alerts) and the cash forecast (/cash-forecast): every financial-status branch,
 * filters / chips / search / pagination, the CSV (columns, BOM, Arabic, formula guard, cap, audit,
 * no PII beyond the display name), alert thresholds, forecast with known / unknown cadence, roles
 * and tenant isolation. Times are relative to now (the s3 rules are clock-bound); the export cap is
 * lowered to 4 rows for this class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalyticsFinancesTest {

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
        r.add("analytics.orders.export-max-rows", () -> "4");
    }

    static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired AnalyticsFloorOverrides floorOverrides;
    final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    static Instant ago(Duration d) {
        return Instant.now().minus(d);
    }

    static Instant daysAgo(int d) {
        return ago(Duration.ofDays(d));
    }

    static LocalDate today() {
        return LocalDate.now(CAIRO);
    }

    /** The last 40 Cairo days, today included. */
    static String period() {
        return "from=" + today().minusDays(39) + "&to=" + today();
    }

    static final String COD = "[\"Cash on Delivery (COD)\"]";
    static final String CARD = "[\"paymob\"]";

    final class T {
        final UUID id = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        final UUID store = UUID.randomUUID();
        final UUID product = UUID.randomUUID();
        final String ownerToken;
        UUID location;

        T(String name) {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                        "VALUES (?, ?, 'Owner', ?, 'x', 'owner')", owner, id, "owner+" + owner + "@an7.test");
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                        "VALUES (?, ?, 'shopify', ?, 'connected', ?)",
                        store, id, "an7-" + id + ".myshopify.com", Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")));
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

        /** An order whose raw carries only the payment gateways. */
        O order(Instant placedAt, String gateways, String carrierClass, String customerName) {
            UUID o = UUID.randomUUID();
            String number = "#" + SEQ.incrementAndGet();
            jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw, " +
                        "shipping_carrier_class, customer_name, customer_phone) " +
                        "VALUES (?, ?, ?, ?, ?, 'new'::order_status, ?, ?::jsonb, ?, ?, ?)",
                        o, id, store, "EXT-" + o, number, Timestamp.from(placedAt),
                        "{\"payment_gateway_names\":" + gateways + "}", carrierClass, customerName, "01099887766");
            return new O(o, number);
        }

        O order(Instant placedAt) {
            return order(placedAt, COD, null, "Mona Adel");
        }

        UUID line(O order, UUID variant, int qty, String price) {
            long lineId = SEQ.incrementAndGet();
            UUID oi = UUID.randomUUID();
            jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity, external_id, raw) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)", oi, id, order.id(), variant, qty,
                        "gid://shopify/LineItem/" + lineId,
                        "{\"id\":" + lineId + ",\"price\":\"" + price + "\",\"quantity\":" + qty +
                        ",\"current_quantity\":" + qty + ",\"discount_allocations\":[]}");
            return oi;
        }

        /** A Bosta forward leg (type 10) in {@code city} with a COD and a fee quote. */
        Leg leg(O order, String state, String city, int cod, int quote) {
            UUID s = UUID.randomUUID();
            String tn = "8" + SEQ.incrementAndGet();
            String raw = "{\"type\":{\"code\":10,\"value\":\"Send\"},\"cod\":" + cod + ",\"shipmentFees\":" + quote +
                         ",\"dropOffAddress\":{\"city\":{\"_id\":\"id-" + city + "\",\"name\":\"" + city + "\"}}}";
            jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state, shipment_leg, raw) " +
                        "VALUES (?, ?, ?, ?, ?::shipment_internal_state, 'forward', ?::jsonb)",
                        s, id, order == null ? null : order.id(), tn, state, raw);
            ShipmentSettlement.apply(jdbc, s, ShipmentSettlementTest.json(raw));
            return new Leg(s, tn);
        }

        void history(Leg l, String state, Instant at) {
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) " +
                        "VALUES (?, ?, ?::shipment_internal_state, ?)", id, l.id(), state, Timestamp.from(at));
        }

        /** Two paid legs with cashouts on the last two Wednesdays → payout weekday 3 (orders with no lines). */
        void wednesdayPayouts(boolean withDeliveredAt) {
            LocalDate wed = today().minusDays(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY));
            for (LocalDate d : List.of(wed, wed.minusWeeks(1))) {
                Leg l = leg(order(daysAgo(60)), "delivered", "Nowhere", 0, 0);
                set(l, "settlement_status = 'paid', cashout_txn_id = ?, cashout_date = ?, deposited_amt = 100, " +
                       "deposited_at = ?, delivered_at = ?", "WEDCOD" + d, java.sql.Date.valueOf(d),
                    Timestamp.from(daysAgo(25)),
                    withDeliveredAt ? Timestamp.from(d.minusDays(3).atTime(12, 0).atZone(CAIRO).toInstant()) : null);
            }
        }

        UUID refundRequest(O order, UUID variant, UUID orderItem, String itemStatus) {
            UUID r = UUID.randomUUID();
            jdbc.update("INSERT INTO return_requests (id, tenant_id, order_id, type, status, reference) " +
                        "VALUES (?, ?, ?, 'refund', 'received', ?)", r, id, order.id(), reference());
            jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, order_item_id, unit_no, variant_id, " +
                        "reason_code, active, item_status) VALUES (?, ?, ?, 1, ?, 'wrong_size', ?, ?)",
                        id, r, orderItem, variant, itemStatus.equals("arrived"), itemStatus);
            return r;
        }

        UUID refund(UUID request, String amount) {
            UUID f = UUID.randomUUID();
            jdbc.update("INSERT INTO return_refunds (id, tenant_id, request_id, kind, method, amount, refunded_on) " +
                        "VALUES (?, ?, ?, 'refund', 'cash', ?::numeric, current_date)", f, id, request, amount);
            return f;
        }

        void voidRefund(UUID request, UUID refund) {
            jdbc.update("INSERT INTO return_refunds (tenant_id, request_id, kind, voids_refund_id) VALUES (?, ?, 'void', ?)",
                        id, request, refund);
        }

        void fulfillmentLocation() {
            location = UUID.randomUUID();
            jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        }

        void pieces(UUID variant, int n) {
            for (int i = 0; i < n; i++) {
                String p = "AN7" + SEQ.incrementAndGet();
                jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                            "VALUES (?, ?, ?, ?, ?, 'available', ?)", p, id, variant, "PC-" + p, p, location);
            }
        }

        String token(String role) {
            UUID u = UUID.randomUUID();
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'U', ?, 'x', ?::user_role)",
                        u, id, role + "+" + u + "@an7.test", role);
            return jwt.issueAccessToken(u, id, role);
        }
    }

    record O(UUID id, String number) {}

    /** A return request reference in the RR-XXXXXX alphabet. */
    static String reference() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        long n = SEQ.incrementAndGet();
        StringBuilder sb = new StringBuilder("RR-");
        for (int i = 0; i < 6; i++) {
            sb.append(alphabet.charAt((int) (n % alphabet.length())));
            n /= alphabet.length();
        }
        return sb.toString();
    }

    record Leg(UUID id, String tn) {}

    void set(Leg l, String assignments, Object... args) {
        Object[] all = Arrays.copyOf(args, args.length + 1);
        all[args.length] = l.id();
        jdbc.update("UPDATE shipments SET " + assignments + " WHERE id = ?", all);
    }

    /** Bosta settled the leg: deposited (no payout) or paid (txn), with its own fee. */
    void deposited(Leg l, String amt, String fees, Instant at) {
        set(l, "settlement_status = 'deposited', deposited_amt = ?::numeric, bosta_fees = ?::numeric, deposited_at = ?, " +
               "settlement_refreshed_at = ?", amt, fees, Timestamp.from(at), Timestamp.from(ago(Duration.ofHours(1))));
    }

    void paid(Leg l, String amt, String fees) {
        set(l, "settlement_status = 'paid', cashout_txn_id = ?, deposited_amt = ?::numeric, bosta_fees = ?::numeric, " +
               "deposited_at = ?", "TXN" + SEQ.incrementAndGet(), amt, fees, Timestamp.from(daysAgo(2)));
    }

    private String base() { return "http://localhost:" + port; }

    private ResponseEntity<Map> get(String token, String pathAndQuery) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(base() + pathAndQuery, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    private ResponseEntity<byte[]> getBytes(String token, String pathAndQuery) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(base() + pathAndQuery, HttpMethod.GET, new HttpEntity<>(h), byte[].class);
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

    static long n(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).longValue();
    }

    static BigDecimal dec(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : new BigDecimal(v.toString());
    }

    Map<String, Map<String, Object>> byName(Map<String, Object> body) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : list(body, "orders")) out.put((String) r.get("name"), r);
        return out;
    }

    // ── financial status ─────────────────────────────────────────────────────

    @Test
    void financialStatus_everyBranch_firstMatchWins_withNetToYou() {
        T t = new T("An7-Status");
        UUID v = t.variant("S");
        t.wednesdayPayouts(false);                                  // payout weekday: Wednesday

        O wijha = t.order(daysAgo(5), COD, "other_known", "Ali Hassan");
        t.line(wijha, v, 1, "300.00");

        O lost = t.order(daysAgo(6));                               // refused, Bosta's own fee
        t.line(lost, v, 1, "400.00");
        deposited(t.leg(lost, "returned", "Cairo", 400, 50), "-60.00", "60.00", daysAgo(3));

        O refundKnown = t.order(daysAgo(7));                        // paid AND returned → refunded wins
        UUID rkLine = t.line(refundKnown, v, 1, "500.00");
        paid(t.leg(refundKnown, "delivered", "Cairo", 500, 50), "440.00", "60.00");
        UUID rkReq = t.refundRequest(refundKnown, v, rkLine, "arrived");
        UUID voided = t.refund(rkReq, "50.00");
        t.voidRefund(rkReq, voided);                                // a voided refund never counts
        t.refund(rkReq, "200.00");

        O refundUnknown = t.order(daysAgo(8));
        UUID ruLine = t.line(refundUnknown, v, 1, "300.00");
        t.leg(refundUnknown, "delivered", "Cairo", 300, 50);
        t.refundRequest(refundUnknown, v, ruLine, "done");

        O paidCod = t.order(daysAgo(9));
        t.line(paidCod, v, 1, "600.00");
        paid(t.leg(paidCod, "delivered", "Giza", 600, 50), "540.00", "60.00");

        O paidPrepaid = t.order(daysAgo(10), CARD, null, "Sara Nabil");   // no cashout needed
        t.line(paidPrepaid, v, 1, "700.00");
        t.leg(paidPrepaid, "delivered", "Giza", 0, 50);

        O overdueNotPaid = t.order(daysAgo(30));                    // deposited 20 days ago, 2+ Wednesdays passed
        t.line(overdueNotPaid, v, 1, "250.00");
        deposited(t.leg(overdueNotPaid, "delivered", "Cairo", 250, 50), "200.00", "50.00", daysAgo(20));

        O overdueStuck = t.order(daysAgo(12));                      // with courier, no change for 10 days
        t.line(overdueStuck, v, 1, "150.00");
        Leg st = t.leg(overdueStuck, "with_courier", "Cairo", 150, 50);
        t.history(st, "with_courier", daysAgo(10));

        O awaiting = t.order(daysAgo(3));                           // delivered COD, Bosta hasn't settled
        t.line(awaiting, v, 1, "350.00");
        t.leg(awaiting, "delivered", "Cairo", 350, 50);

        O notShipped = t.order(daysAgo(2));
        t.line(notShipped, v, 1, "120.00");

        O inTransit = t.order(daysAgo(1));
        t.line(inTransit, v, 1, "130.00");
        t.history(t.leg(inTransit, "with_courier", "Cairo", 130, 50), "with_courier", daysAgo(1));

        O booked = t.order(daysAgo(1));
        t.line(booked, v, 2, "70.00");
        t.leg(booked, "created", "Cairo", 140, 50);

        Map<String, Object> body = ok(t, "/api/v1/analytics/orders?" + period() + "&size=200");
        Map<String, Map<String, Object>> rows = byName(body);
        assertThat(rows).hasSize(12);

        assertStatus(rows.get(wijha.number()), "other_carrier", null);
        assertThat(rows.get(wijha.number()).get("bostaFees")).isNull();
        assertThat(m(rows.get(wijha.number()), "deliveryStatus").get("label")).isEqualTo("Other carrier");

        assertStatus(rows.get(lost.number()), "lost", "-60.00");
        assertThat(dec(rows.get(lost.number()), "bostaFees")).isEqualByComparingTo("60.00");
        assertThat(rows.get(lost.number()).get("feesEstimated")).isEqualTo(false);
        assertThat(m(rows.get(lost.number()), "deliveryStatus").get("label")).isEqualTo("Refused");

        assertStatus(rows.get(refundKnown.number()), "refunded", "240.00");      // 500 − 60 − 200
        assertThat(rows.get(refundKnown.number()).get("refundAmountUnknown")).isEqualTo(false);
        assertStatus(rows.get(refundUnknown.number()), "refunded", null);
        assertThat(rows.get(refundUnknown.number()).get("refundAmountUnknown")).isEqualTo(true);

        assertStatus(rows.get(paidCod.number()), "paid", "540.00");               // deposited_amt
        assertStatus(rows.get(paidPrepaid.number()), "paid", "643.00");           // 700 − 50 × 1.14
        assertThat(dec(rows.get(paidPrepaid.number()), "bostaFees")).isEqualByComparingTo("57.00");
        assertThat(rows.get(paidPrepaid.number()).get("feesEstimated")).isEqualTo(true);
        assertThat(rows.get(paidPrepaid.number()).get("paymentGroup")).isEqualTo("Card");

        assertStatus(rows.get(overdueNotPaid.number()), "overdue", null);
        assertStatus(rows.get(overdueStuck.number()), "overdue", null);
        assertStatus(rows.get(awaiting.number()), "awaiting_payout", null);
        assertStatus(rows.get(notShipped.number()), "expected", null);
        assertThat(m(rows.get(notShipped.number()), "deliveryStatus").get("label")).isEqualTo("Not shipped");
        assertStatus(rows.get(inTransit.number()), "expected", null);
        assertThat(m(rows.get(inTransit.number()), "deliveryStatus").get("label")).isEqualTo("With courier");
        assertStatus(rows.get(booked.number()), "expected", null);
        assertThat(m(rows.get(booked.number()), "deliveryStatus").get("label")).isEqualTo("Booked");
        assertThat(n(rows.get(booked.number()), "items")).isEqualTo(2);
        assertThat(dec(rows.get(booked.number()), "total")).isEqualByComparingTo("140.00");

        // Display name only: first name + last initial.
        assertThat(rows.get(paidPrepaid.number()).get("customer")).isEqualTo("Sara N.");
        assertThat(rows.get(lost.number()).get("customer")).isEqualTo("Mona A.");
        Map<String, Object> gov = m(rows.get(paidCod.number()), "governorate");
        assertThat(gov.get("key")).isEqualTo("id-Giza");
        assertThat(gov.get("label")).isEqualTo("Giza");

        Map<String, Object> counts = m(body, "counts");
        assertThat(counts).containsExactly(
            Map.entry("other_carrier", 1), Map.entry("lost", 1), Map.entry("refunded", 2), Map.entry("paid", 2),
            Map.entry("overdue", 2), Map.entry("awaiting_payout", 1), Map.entry("expected", 3));
    }

    private static void assertStatus(Map<String, Object> row, String status, String net) {
        assertThat(row.get("financialStatus")).as("%s", row.get("name")).isEqualTo(status);
        if (net == null) assertThat(row.get("netToYou")).as("%s net", row.get("name")).isNull();
        else assertThat(dec(row, "netToYou")).as("%s net", row.get("name")).isEqualByComparingTo(net);
    }

    @Test
    void overdue_withoutAKnownPayoutWeekday_waitsFourteenDays() {
        T t = new T("An7-NoWeekday");
        UUID v = t.variant("S");
        O tenDays = t.order(daysAgo(15));
        t.line(tenDays, v, 1, "100.00");
        deposited(t.leg(tenDays, "delivered", "Cairo", 100, 50), "80.00", "20.00", daysAgo(10));
        O twentyDays = t.order(daysAgo(25));
        t.line(twentyDays, v, 1, "100.00");
        deposited(t.leg(twentyDays, "delivered", "Cairo", 100, 50), "80.00", "20.00", daysAgo(20));
        Map<String, Map<String, Object>> rows = byName(ok(t, "/api/v1/analytics/orders?" + period()));
        assertStatus(rows.get(tenDays.number()), "awaiting_payout", null);
        assertStatus(rows.get(twentyDays.number()), "overdue", null);
    }

    // ── filters, chips, search, pagination ───────────────────────────────────

    @Test
    void filters_chipCountsIgnoreOnlyTheStatus_searchByNameOrTracking_pagesAreStable() {
        T t = new T("An7-List");
        UUID v1 = t.variant("V1"), v2 = t.variant("V2");
        Instant tie = daysAgo(4);
        List<O> cairo = new ArrayList<>();
        Map<UUID, String> tracking = new HashMap<>();
        for (int i = 0; i < 5; i++) {                                // five orders at the same instant
            O o = t.order(tie);
            t.line(o, v1, 1, "100.00");
            Leg l = t.leg(o, i < 2 ? "delivered" : "with_courier", "Cairo", 100, 50);
            tracking.put(o.id(), l.tn());
            cairo.add(o);
        }
        List<O> giza = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            O o = t.order(daysAgo(2 + i));
            t.line(o, v2, 1, "100.00");
            t.leg(o, "delivered", "Giza", 100, 50);
            giza.add(o);
        }
        O unknown = t.order(daysAgo(6));
        t.line(unknown, v1, 1, "100.00");
        t.line(unknown, v2, 1, "100.00");

        String p = "/api/v1/analytics/orders?" + period();
        Map<String, Object> all = ok(t, p + "&size=200");
        assertThat(n(all, "total")).isEqualTo(9);

        // Default order: placed_at DESC, id DESC (ties broken by id, descending).
        List<Map<String, Object>> rows = list(all, "orders");
        for (int i = 1; i < rows.size(); i++) {
            Instant a = Instant.parse((String) rows.get(i - 1).get("placedAt"));
            Instant b = Instant.parse((String) rows.get(i).get("placedAt"));
            assertThat(a).isAfterOrEqualTo(b);
            if (a.equals(b)) {
                assertThat(((String) rows.get(i - 1).get("orderId")).compareTo((String) rows.get(i).get("orderId"))).isPositive();
            }
        }

        // Pages of 4 put back together = the full list, in order, no duplicates.
        List<Object> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            Map<String, Object> pg = ok(t, p + "&size=4&page=" + page);
            assertThat(n(pg, "total")).isEqualTo(9);
            list(pg, "orders").forEach(r -> paged.add(r.get("orderId")));
        }
        assertThat(paged).containsExactlyElementsOf(rows.stream().map(r -> r.get("orderId")).toList());

        // Governorate filter narrows the rows AND the chip counts; status narrows rows only.
        Map<String, Object> cairoOnly = ok(t, p + "&governorate=id-Cairo");
        assertThat(n(cairoOnly, "total")).isEqualTo(5);
        assertThat(m(cairoOnly, "counts")).containsEntry("awaiting_payout", 2).containsEntry("expected", 3);
        Map<String, Object> cairoExpected = ok(t, p + "&governorate=id-Cairo&status=expected");
        assertThat(n(cairoExpected, "total")).isEqualTo(3);
        assertThat(m(cairoExpected, "counts")).isEqualTo(m(cairoOnly, "counts"));
        assertThat(n(ok(t, p + "&governorate=unknown"), "total")).isEqualTo(1);

        // Variant filter.
        assertThat(n(ok(t, p + "&variantId=" + v2), "total")).isEqualTo(4);          // 3 Giza + the mixed one

        // q: part of the order name (with or without '#'), or a tracking number.
        O target = cairo.get(3);
        String digits = target.number().substring(1);
        assertThat(byName(ok(t, p + "&q=" + digits)).keySet()).containsExactly(target.number());
        assertThat(byName(ok(t, p + "&q=" + tracking.get(target.id()))).keySet()).containsExactly(target.number());
        assertThat(n(ok(t, p + "&q=nothing-like-this"), "total")).isZero();

        // Bad input.
        assertThat(get(t.ownerToken, p + "&status=bogus").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(t.ownerToken, p + "&size=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(t.ownerToken, p + "&size=201").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(t.ownerToken, p + "&page=-1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── CSV export ───────────────────────────────────────────────────────────

    @Test
    void export_csv_bomArabicFormulaGuard_capAndOneAuditRowWithNoRowData() throws Exception {
        T t = new T("An7-Csv");
        UUID v = t.variant("S");
        List<O> orders = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            O o = t.order(daysAgo(10 + i));
            t.line(o, v, 1, "100.00");
            orders.add(o);
        }
        O arabic = t.order(daysAgo(2), COD, null, "منى عبد الله");
        t.line(arabic, v, 1, "250.00");
        O formula = t.order(daysAgo(1), COD, null, "=HYPERLINK(\"x\") Evil, Name");
        t.line(formula, v, 1, "90.00");

        ResponseEntity<byte[]> r = getBytes(t.ownerToken, "/api/v1/analytics/orders/export.csv?" + period());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().toString()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(r.getHeaders().getFirst("Content-Disposition")).startsWith("attachment; filename=\"orders-");
        assertThat(r.getHeaders().getFirst("X-Export-Truncated")).isEqualTo("true");          // 6 rows, cap 4
        byte[] bytes = r.getBody();
        assertThat(Arrays.copyOf(bytes, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        String csv = new String(bytes, StandardCharsets.UTF_8).substring(1);
        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).isEqualTo("order,placed_at,customer,governorate,governorate_ar,items,total,payment," +
            "delivery_status,financial_status,bosta_fees,fees_estimated,net_to_you,refund_amount_unknown");
        assertThat(lines).hasSize(5);                                                         // header + 4
        assertThat(lines[1]).startsWith(formula.number() + ",").contains(",\"'=HYPERLINK(\"\"x\"\") N.\",");
        assertThat(lines[2]).startsWith(arabic.number() + ",").contains(",منى ا.,").contains(",غير معروف,");
        assertThat(lines[2]).contains(",1,250.00,COD,Not shipped,expected,,false,,false");
        assertThat(csv).doesNotContain("عبد").doesNotContain("الله").doesNotContain("Evil").doesNotContain("01099887766");
        assertThat(csv).doesNotContain(orders.get(3).number());                              // the oldest is cut

        ResponseEntity<byte[]> filtered = getBytes(t.ownerToken,
            "/api/v1/analytics/orders/export.csv?" + period() + "&status=expected&q=" + arabic.number().substring(1));
        assertThat(filtered.getHeaders().getFirst("X-Export-Truncated")).isEqualTo("false");
        assertThat(new String(filtered.getBody(), StandardCharsets.UTF_8).split("\r\n")).hasSize(2);

        List<Map<String, Object>> audit = jdbc.queryForList(
            "SELECT actor_user_id, action, target_type, target_id, metadata::text AS meta FROM audit_log " +
            "WHERE tenant_id = ? ORDER BY id", t.id);
        assertThat(audit).hasSize(2);
        assertThat(audit).allSatisfy(a -> {
            assertThat(a.get("action")).isEqualTo("analytics_orders_export");
            assertThat(a.get("actor_user_id")).isEqualTo(t.owner);
            assertThat(a.get("target_id")).isNull();
            String meta = (String) a.get("meta");
            for (O o : List.of(arabic, formula, orders.get(0))) assertThat(meta).doesNotContain(o.number().substring(1));
            assertThat(meta).doesNotContain("منى").doesNotContain("Evil");
        });
        Map<String, Object> first = mapper.readValue((String) audit.get(0).get("meta"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(first).containsEntry("q", false).containsEntry("rows", 4).containsEntry("truncated", true)
            .containsEntry("from", today().minusDays(39).toString());
        Map<String, Object> second = mapper.readValue((String) audit.get(1).get("meta"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(second).containsEntry("q", true).containsEntry("status", "expected").containsEntry("rows", 1);
    }

    // ── alerts ───────────────────────────────────────────────────────────────

    @Test
    void alerts_thresholds_stuckNeverPickedUpNotPaid_lowSuccessGovernorates_noSellsOutWithoutPieces() {
        T t = new T("An7-Alerts");
        UUID v = t.variant("S");
        t.wednesdayPayouts(false);
        cityOrders(t, v, "Cairo", 6, 4);          // 10 orders, 60 % → listed
        cityOrders(t, v, "Giza", 0, 9);           // 9 orders → too few
        cityOrders(t, v, "Alexandria", 13, 7);    // 20 orders, exactly 65 % → not under

        O stuck = t.order(daysAgo(12));
        t.line(stuck, v, 1, "123.00");
        t.history(t.leg(stuck, "with_courier", "Luxor", 123, 50), "with_courier", daysAgo(10));
        O never = t.order(daysAgo(9));
        t.line(never, v, 1, "77.00");
        Leg nl = t.leg(never, "created", "Luxor", 77, 50);
        set(nl, "created_at = ?", Timestamp.from(daysAgo(8)));
        O notPaid = t.order(daysAgo(25));
        t.line(notPaid, v, 1, "88.00");
        deposited(t.leg(notPaid, "delivered", "Luxor", 100, 50), "88.00", "12.00", daysAgo(20));

        Map<String, Object> body = ok(t, "/api/v1/analytics/alerts?" + period());
        Map<String, Map<String, Object>> alerts = new LinkedHashMap<>();
        for (Map<String, Object> a : list(body, "alerts")) alerts.put((String) a.get("key"), a);
        assertThat(alerts.keySet()).containsExactly("stuck_with_bosta", "never_picked_up", "delivered_not_paid",
            "low_success_governorates");

        assertThat(n(alerts.get("stuck_with_bosta"), "count")).isEqualTo(1);
        assertThat(dec(alerts.get("stuck_with_bosta"), "amount")).isEqualByComparingTo("123.00");
        assertThat(n(alerts.get("never_picked_up"), "count")).isEqualTo(1);
        assertThat(dec(alerts.get("never_picked_up"), "amount")).isEqualByComparingTo("77.00");
        assertThat(n(alerts.get("delivered_not_paid"), "count")).isEqualTo(1);
        assertThat(dec(alerts.get("delivered_not_paid"), "amount")).isEqualByComparingTo("88.00");
        assertThat(alerts.values()).allSatisfy(a -> assertThat((String) a.get("link")).startsWith("/analytics/"));

        Map<String, Object> gov = alerts.get("low_success_governorates");
        assertThat(n(gov, "count")).isEqualTo(1);
        assertThat(dec(gov, "amount")).isEqualByComparingTo("400.00");                     // 4 refused × 100
        Map<String, Object> cairo = list(gov, "details").get(0);
        assertThat(cairo.get("key")).isEqualTo("id-Cairo");
        assertThat(n(cairo, "orders")).isEqualTo(10);
        assertThat(dec(cairo, "successRate")).isEqualByComparingTo("0.6");
    }

    private void cityOrders(T t, UUID v, String city, int delivered, int refused) {
        for (int i = 0; i < delivered + refused; i++) {
            O o = t.order(daysAgo(5));
            t.line(o, v, 1, "100.00");
            Leg l = t.leg(o, i < delivered ? "delivered" : "returned", city, 100, 50);
            if (i < delivered) paid(l, "90.00", "10.00");
        }
    }

    @Test
    void alerts_sellsOutSoon_onlyWhenTheTenantHasPieces_atMostSevenDaysLeft() {
        T t = new T("An7-SellsOut");
        t.fulfillmentLocation();
        UUID fast = t.variant("FAST"), slow = t.variant("SLOW");
        for (int i = 0; i < 30; i++) {                     // 30 units in 30 days = 1 a day, 3 left → 3 days
            O o = t.order(daysAgo(i % 29));
            t.line(o, fast, 1, "100.00");
            paid(t.leg(o, "delivered", "Cairo", 100, 50), "90.00", "10.00");
        }
        O s = t.order(daysAgo(3));
        t.line(s, slow, 1, "100.00");
        paid(t.leg(s, "delivered", "Cairo", 100, 50), "90.00", "10.00");
        t.pieces(fast, 3);
        t.pieces(slow, 100);

        Map<String, Object> sells = list(ok(t, "/api/v1/analytics/alerts?period=30d"), "alerts").stream()
            .filter(a -> "sells_out_soon".equals(a.get("key"))).findFirst().orElseThrow();
        assertThat(n(sells, "count")).isEqualTo(1);
        assertThat(sells.get("amount")).isNull();
    }

    // ── cash forecast ────────────────────────────────────────────────────────

    @Test
    void cashForecast_nullWithReason_untilCadenceAndLagAreKnown_thenBucketsByPayoutWeekday() {
        T t = new T("An7-Forecast");
        UUID v = t.variant("S");
        Map<String, Object> unknown = ok(t, "/api/v1/analytics/cash-forecast");
        assertThat(unknown.get("reason")).isEqualTo("payout_cadence_unknown");
        assertThat(unknown.get("forecast")).isNull();

        t.wednesdayPayouts(false);
        assertThat(ok(t, "/api/v1/analytics/cash-forecast").get("reason")).isEqualTo("payout_lag_unknown");

        jdbc.update("UPDATE shipments SET delivered_at = ((cashout_date - 3)::timestamp + interval '12 hours') " +
                    "AT TIME ZONE 'Africa/Cairo' WHERE tenant_id = ? AND cashout_date IS NOT NULL", t.id);

        // Giza: 3 delivered + 1 refused in the last 90 days → 75 % (paid without a payout date: no awaiting, no lag).
        for (int i = 0; i < 4; i++) {
            O o = t.order(daysAgo(20));
            t.line(o, v, 1, "100.00");
            Leg l = t.leg(o, i < 3 ? "delivered" : "returned", "Giza", 100, 50);
            if (i < 3) paid(l, "90.00", "10.00");
        }
        O dep = t.order(daysAgo(6));
        t.line(dep, v, 1, "120.00");
        deposited(t.leg(dep, "delivered", "Aswan", 120, 50), "100.00", "20.00", daysAgo(1));
        O unsettled = t.order(daysAgo(2));
        t.line(unsettled, v, 1, "200.00");
        Leg ul = t.leg(unsettled, "delivered", "Aswan", 200, 50);
        set(ul, "delivered_at = ?", Timestamp.from(Instant.now()));
        O moving = t.order(daysAgo(1));
        t.line(moving, v, 1, "300.00");
        t.leg(moving, "with_courier", "Giza", 300, 50);

        Map<String, Object> body = ok(t, "/api/v1/analytics/cash-forecast");
        assertThat(body.get("reason")).isNull();
        Map<String, Object> f = m(body, "forecast");
        Map<String, Object> method = m(f, "method");
        assertThat(n(method, "payoutWeekday")).isEqualTo(3);
        assertThat(dec(method, "medianLagDays")).isEqualByComparingTo("3.0");
        assertThat(n(method, "lagSample")).isEqualTo(2);
        assertThat(n(method, "lagDaysUsed")).isEqualTo(3);
        assertThat(m(method, "awaitingDeposited")).containsEntry("count", 1);
        assertThat(dec(m(method, "awaitingDeposited"), "amount")).isEqualByComparingTo("100.00");
        assertThat(m(method, "awaitingNotSettled")).containsEntry("count", 1);
        assertThat(dec(m(method, "awaitingNotSettled"), "amount")).isEqualByComparingTo("143.00");   // 200 − 50 × 1.14
        Map<String, Object> transit = m(method, "inTransit");
        assertThat(n(transit, "count")).isEqualTo(1);
        assertThat(dec(transit, "expected")).isEqualByComparingTo("225.00");                         // 300 × 75 %

        LocalDate today = today();
        LocalDate nextWed = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY));
        LocalDate laggedWed = today.plusDays(3).with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY));
        assertThat(method.get("nextPayoutDate")).isEqualTo(nextWed.toString());
        assertThat(transit.get("expectedPayoutDate")).isEqualTo(laggedWed.toString());
        BigDecimal[] expected = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        expected[bucket(today, nextWed)] = expected[bucket(today, nextWed)].add(new BigDecimal("100.00"));
        expected[bucket(today, laggedWed)] = expected[bucket(today, laggedWed)].add(new BigDecimal("368.00"));   // 143 + 225
        List<Map<String, Object>> buckets = list(f, "buckets");
        assertThat(buckets).extracting(b -> b.get("key")).containsExactly("next7", "days8to14", "days15to30");
        for (int i = 0; i < 3; i++) assertThat(dec(buckets.get(i), "amount")).isEqualByComparingTo(expected[i]);
        assertThat(dec(f, "later")).isEqualByComparingTo("0");
    }

    static int bucket(LocalDate today, LocalDate day) {
        long d = java.time.temporal.ChronoUnit.DAYS.between(today, day);
        return d <= 7 ? 0 : d <= 14 ? 1 : 2;
    }

    // ── SKU drawer ───────────────────────────────────────────────────────────

    @Test
    void variantOrders_theTenNewestOrdersOfTheVariant_withTheirFinancialStatus() {
        T t = new T("An7-Drawer");
        UUID v = t.variant("V"), other = t.variant("W"), few = t.variant("F");
        List<O> vOrders = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            O o = t.order(daysAgo(i * 20));                        // spans 240 days
            t.line(o, v, 1, "100.00");
            if (i % 2 == 0) t.leg(o, "delivered", "Cairo", 100, 50);
            vOrders.add(o);
        }
        O otherOrder = t.order(daysAgo(1));
        t.line(otherOrder, other, 1, "100.00");
        O fewOrder = t.order(daysAgo(400));
        t.line(fewOrder, few, 1, "100.00");

        Map<String, Object> body = ok(t, "/api/v1/analytics/variants/" + v + "/orders");
        List<Map<String, Object>> rows = list(body, "orders");
        assertThat(rows).extracting(r -> r.get("name"))
            .containsExactlyElementsOf(vOrders.subList(0, 10).stream().map(O::number).toList());
        assertThat(rows.get(0).get("financialStatus")).isEqualTo("expected");
        assertThat(rows.get(1).get("financialStatus")).isEqualTo("awaiting_payout");

        assertThat(list(ok(t, "/api/v1/analytics/variants/" + few + "/orders"), "orders"))
            .extracting(r -> r.get("name")).containsExactly(fewOrder.number());
        assertThat(list(ok(t, "/api/v1/analytics/variants/" + UUID.randomUUID() + "/orders"), "orders")).isEmpty();
    }

    // ── roles + isolation ────────────────────────────────────────────────────

    @Test
    void ownerOnly_everyEndpoint() {
        T t = new T("An7-Roles");
        UUID v = t.variant("S");
        String manager = t.token("manager"), worker = t.token("worker");
        for (String path : List.of("/api/v1/analytics/orders?period=30d",
                                   "/api/v1/analytics/orders/export.csv?period=30d",
                                   "/api/v1/analytics/variants/" + v + "/orders",
                                   "/api/v1/analytics/alerts?period=30d",
                                   "/api/v1/analytics/cash-forecast")) {
            assertThat(getBytes(t.ownerToken, path).getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
            assertThat(getBytes(manager, path).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(getBytes(worker, path).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
        // A refused export writes no audit row.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id = ?", Long.class, t.id)).isEqualTo(1);
    }

    @Test
    void appUser_rls_eachTenantSeesOnlyItsOwnOrders() {
        T a = new T("An7-IsoA");
        T b = new T("An7-IsoB");
        UUID va = a.variant("A"), vb = b.variant("B");
        O ao = a.order(daysAgo(3));
        a.line(ao, va, 1, "100.00");
        paid(a.leg(ao, "delivered", "Cairo", 100, 50), "90.00", "10.00");
        O bo = b.order(daysAgo(3));
        b.line(bo, vb, 1, "100.00");

        TenantAwareDataSource appUserDs = new TenantAwareDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
        OrderFinanceService svc = new OrderFinanceService(new JdbcTemplate(appUserDs), Clock.system(CAIRO), floorOverrides);
        AnalyticsPeriod p = new AnalyticsPeriod(today().minusDays(39), today());
        OrderFinanceService.Filters none = new OrderFinanceService.Filters(null, null, null, null);

        TenantContext.set(b.id);
        assertThat(tx.execute(s -> svc.orders(p, none, 0, 50)).orders())
            .extracting(OrderFinanceService.OrderRow::name).containsExactly(bo.number());
        TenantContext.set(a.id);
        OrderFinanceService.OrdersPage aPage = tx.execute(s -> svc.orders(p, none, 0, 50));
        assertThat(aPage.orders()).extracting(OrderFinanceService.OrderRow::name).containsExactly(ao.number());
        assertThat(aPage.orders().get(0).financialStatus()).isEqualTo("paid");
        assertThat(tx.execute(s -> svc.variantOrders(vb)).orders()).isEmpty();          // B's variant, A's context

        TenantContext.clear();
        assertThatThrownBy(() -> tx.execute(s -> svc.orders(p, none, 0, 50))).isInstanceOf(RuntimeException.class);
        Long visible = tx.execute(s -> new JdbcTemplate(appUserDs).queryForObject(
            "SELECT COUNT(*) FROM return_refunds", Long.class));
        assertThat(visible).isZero();

        // Over the API, B's owner never sees A's order.
        assertThat(byName(ok(b, "/api/v1/analytics/orders?" + period())).keySet()).containsExactly(bo.number());
    }
}
