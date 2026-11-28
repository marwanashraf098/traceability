package com.traceability;

import com.traceability.identity.JwtService;
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

/**
 * Analytics slices 4–6 (approved 2026-11-27).
 * Slice 4 — shipping: orders.shipping_charged (V246, Shopify's current shipping price after shipping discounts
 * and refunds) makes Order finances' Total = lines + shipping (prepaid net and COD net both agree with it) and is
 * shipping income in Profit (delivered orders only).
 * Slice 5 — CancelledOrders: a cancelled order is left out only when cancelled BEFORE Bosta picked it up; a
 * refused → Return to Origin → cancelled-in-Shopify order counts as booked and refused (never realized, never
 * units sold), with its Bosta fee. Customers to watch read the COD Bosta asked for before the RTO switch.
 * Slice 6 — UnitCost: a unit cost of 0 is unknown, like none.
 * Realistic fixtures: bare 10-digit tracking numbers, Bosta- and Shopify-shaped raw, settlement columns written
 * by the real writer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalyticsShippingRefusedCostTest {

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
    static final AtomicLong SEQ = new AtomicLong(7_300_000_000L + System.nanoTime() % 100_000_000L);
    static final String NOV = "from=2026-11-01&to=2026-11-30";
    static final String COD_GW = "Cash on Delivery (COD)";
    static final String CARD_GW = "Paymob";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired EncryptionService encryption;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    static Instant cairo(int d, int h) {
        return LocalDateTime.of(2026, 11, d, h, 0).atZone(CAIRO).toInstant();
    }

    static String iso(Instant i) {
        return i.toString();
    }

    /** A bare 10-digit Bosta tracking number, as Bosta prints it. */
    static String tracking() {
        return String.valueOf(SEQ.incrementAndGet());
    }

    static String money(String amount) {
        return "{\"shop_money\":{\"amount\":\"" + amount + "\",\"currency_code\":\"EGP\"}}";
    }

    /** Shopify REST order raw. shipping = current_shipping_price_set (null = key missing). */
    static String orderRaw(String gateway, String customerId, String shipping, String extra) {
        StringBuilder b = new StringBuilder("{\"payment_gateway_names\":[\"").append(gateway).append("\"]");
        if (customerId != null) b.append(",\"customer\":{\"id\":").append(customerId).append("}");
        if (shipping != null) b.append(",\"current_shipping_price_set\":").append(money(shipping));
        if (extra != null) b.append(",").append(extra);
        return b.append("}").toString();
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
                        "VALUES (?, ?, 'Owner', ?, 'x', 'owner')", owner, id, "owner+" + owner + "@ship.test");
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                        "VALUES (?, ?, 'shopify', ?, 'connected', ?)",
                        store, id, "ship-" + id + ".myshopify.com", Timestamp.from(cairo(1, 0).minus(Duration.ofDays(60))));
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, 'Hoodie')",
                        product, id, store, "P-" + product);
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                        "VALUES (?, 'bosta', ?, ?, 'active')", id, encryption.encrypt("k-" + id), "h-" + id);
            ownerToken = jwt.issueAccessToken(owner, id, "owner");
        }

        UUID variant(String sku, String unitCost) {
            UUID v = UUID.randomUUID();
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title, price, unit_cost) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 650, ?::numeric)", v, id, product, "V-" + v, sku, sku, unitCost);
            return v;
        }

        UUID order(Instant placedAt, String status, String raw) {
            UUID o = UUID.randomUUID();
            jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw) " +
                        "VALUES (?, ?, ?, ?, ?, ?::order_status, ?, ?::jsonb)",
                        o, id, store, "gid://shopify/Order/" + SEQ.incrementAndGet(), "BRK-" + (SEQ.get() % 100000) + "-EG",
                        status, Timestamp.from(placedAt), raw);
            return o;
        }

        /** A Shopify line; currentQty differs from qty after a cancel (Shopify zeroes it). Returns the line id. */
        long line(UUID order, UUID variant, int qty, int currentQty, String price) {
            long lineId = SEQ.incrementAndGet();
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id, raw) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb)", id, order, variant, qty, "gid://shopify/LineItem/" + lineId,
                        "{\"id\":" + lineId + ",\"price\":\"" + price + "\",\"quantity\":" + qty +
                        ",\"current_quantity\":" + currentQty + ",\"discount_allocations\":[]}");
            return lineId;
        }

        /** A forward Bosta leg (settlement columns by the real writer) plus its status history. */
        String leg(UUID order, String state, String raw, String... history) {
            UUID s = UUID.randomUUID();
            String tn = tracking();
            jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state, shipment_leg, raw) " +
                        "VALUES (?, ?, ?, ?, ?::shipment_internal_state, 'forward', ?::jsonb)", s, id, order, tn, state, raw);
            ShipmentSettlement.apply(jdbc, s, ShipmentSettlementTest.json(raw));
            for (int i = 0; i + 1 < history.length; i += 2) {
                jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) " +
                            "VALUES (?, ?, ?, ?::timestamptz)", id, s, history[i], history[i + 1]);
            }
            return tn;
        }

        void piece(UUID variant) {
            String p = "S10" + SEQ.incrementAndGet();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                        "VALUES (?, ?, ?, ?, ?, 'available')", p, id, variant, "PC-" + p, p);
        }
    }

    /** A delivered Send Bosta settled and paid: deposited = cod − fees. */
    static String deliveredPaid(String cod, String fees, Instant collected) {
        BigDecimal dep = new BigDecimal(cod).subtract(new BigDecimal(fees));
        return "{\"type\":{\"code\":10,\"value\":\"SEND\"},\"state\":{\"code\":45},\"cod\":" + cod +
               ",\"collectedFromBusiness\":\"" + iso(collected) + "\"" +
               ",\"wallet\":{\"cashCycle\":{\"_id\":" + SEQ.incrementAndGet() +
               ",\"deposited_at\":\"2026-11-15T08:00:00.000Z\",\"deposited_amt\":" + dep.toPlainString() +
               ",\"cod\":\"" + cod + "\",\"bosta_fees\":\"" + fees + "\"}," +
               "\"cashout\":{\"transaction_id\":\"SUNCOD15NOV26\"}}}";
    }

    /**
     * A refused Send Bosta turned Return to Origin (type 20): Bosta zeroed the COD and kept the one it asked for in
     * originalCod (prod raw shape; originalCod null = not stored, a v2-list raw). Fee settled as a negative deposit.
     */
    static String rto(String originalCod, String fees, Instant collected) {
        return "{\"type\":{\"code\":20,\"value\":\"RTO\"},\"state\":{\"code\":46},\"cod\":0" +
               (originalCod == null ? ",\"_tracedRawShape\":\"v2-list\"" : ",\"originalCod\":" + originalCod) +
               (collected == null ? "" : ",\"collectedFromBusiness\":\"" + iso(collected) + "\"") +
               ",\"wallet\":{\"cashCycle\":{\"_id\":" + SEQ.incrementAndGet() +
               ",\"deposited_at\":\"2026-11-15T08:00:00.000Z\",\"deposited_amt\":-" + fees +
               ",\"cod\":\"0.00\",\"bosta_fees\":\"" + fees + "\"}," +
               "\"cashout\":{\"transaction_id\":\"SUNCOD15NOV26\"}}}";
    }

    /** A Send that Bosta never picked up, cancelled with the order. */
    static String neverPickedUp() {
        return "{\"type\":{\"code\":10,\"value\":\"SEND\"},\"state\":{\"code\":100},\"cod\":1385}";
    }

    /**
     * A refused → Return to Origin → cancelled-in-Shopify order: placed Nov 4, Bosta picks it up Nov 5, the
     * customer refuses, Bosta returns it, the merchant cancels in Shopify on Nov 9 (current_quantity 0, shipping
     * refunded → current shipping 0); its fulfillment carried {@code qty} units.
     */
    UUID refusedThenCancelled(T t, UUID variant, int qty, String customerId, String originalCod, String gateway) {
        Instant pickup = cairo(5, 10);
        UUID o = t.order(cairo(4, 12), "new", "{}");
        long line = t.line(o, variant, qty, 0, "650.00");
        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", orderRaw(gateway, customerId, "0.00",
            "\"cancelled_at\":\"2026-11-09T14:00:00+02:00\",\"cancel_reason\":\"customer\"," +
            "\"total_price_set\":" + money(String.valueOf(650 * qty + 85)) + "," +
            "\"fulfillments\":[{\"id\":" + SEQ.incrementAndGet() + ",\"status\":\"success\",\"line_items\":[{\"id\":" + line +
            ",\"quantity\":" + qty + "}]}]"), o);
        t.leg(o, "returned", rto(originalCod, "112.86", pickup),
            "with_courier", iso(pickup), "returning", iso(cairo(6, 15)), "returned", iso(cairo(8, 11)));
        return o;
    }

    private Map<String, Object> ok(T t, String pathAndQuery) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(t.ownerToken);
        ResponseEntity<Map> r = rest.exchange("http://localhost:" + port + pathAndQuery, HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(r.getStatusCode()).as("body: %s", r.getBody()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked") Map<String, Object> body = r.getBody();
        return body;
    }

    private String csv(T t, String pathAndQuery) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(t.ownerToken);
        ResponseEntity<String> r = rest.exchange("http://localhost:" + port + pathAndQuery, HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
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

    Map<String, Object> orderRow(T t, String name) {
        return list(ok(t, "/api/v1/analytics/orders?" + NOV + "&size=200"), "orders").stream()
            .filter(r -> name.equals(r.get("name"))).findFirst().orElseThrow();
    }

    String number(UUID order) {
        return jdbc.queryForObject("SELECT number FROM orders WHERE id = ?", String.class, order);
    }

    // ── slice 4: shipping ────────────────────────────────────────────────────

    @Test
    void s4_prepaidOrder_totalIsLinesPlusShipping_netIsTotalMinusFees() {
        T t = new T("Ship-Prepaid");
        UUID v = t.variant("HD-BLK-M", "300");
        // BRK-46818-EG: lines 1,299, shipping 85 paid online; Bosta collects nothing (cod 0) and charges 92.34.
        UUID o = t.order(cairo(10, 12), "new", orderRaw(CARD_GW, "501", "85.00", null));
        t.line(o, v, 1, 1, "1299.00");
        t.leg(o, "delivered", deliveredPaid("0.00", "92.34", cairo(11, 9)));

        Map<String, Object> r = orderRow(t, number(o));
        assertThat(r.get("financialStatus")).isEqualTo("paid");
        assertThat(dec(r, "lines")).isEqualByComparingTo("1299.00");
        assertThat(dec(r, "shipping")).isEqualByComparingTo("85.00");
        assertThat(dec(r, "total")).isEqualByComparingTo("1384.00");
        assertThat(dec(r, "netToYou")).isEqualByComparingTo("1291.66");
        assertThat(dec(r, "total").subtract(dec(r, "bostaFees"))).isEqualByComparingTo(dec(r, "netToYou"));
    }

    @Test
    void s4_codOrder_depositAgreesWithItsTotal() {
        T t = new T("Ship-COD");
        UUID v = t.variant("HD-GRY-L", "300");
        // BRK-46776-EG: lines 1,745 + 85 shipping; Bosta collected 1,830 and deposited 1,830 − 103.74.
        UUID o = t.order(cairo(10, 12), "new", orderRaw(COD_GW, "502", "85.00", null));
        t.line(o, v, 1, 1, "1745.00");
        t.leg(o, "delivered", deliveredPaid("1830.00", "103.74", cairo(11, 9)));

        Map<String, Object> r = orderRow(t, number(o));
        assertThat(dec(r, "total")).isEqualByComparingTo("1830.00");
        assertThat(dec(r, "netToYou")).isEqualByComparingTo("1726.26");
        assertThat(dec(r, "total").subtract(dec(r, "bostaFees"))).isEqualByComparingTo(dec(r, "netToYou"));
    }

    @Test
    void s4_shippingIsShopifysCurrentPrice_afterShippingDiscountAndRefund_unknownWhenMissing() {
        T t = new T("Ship-Current");
        UUID v = t.variant("HD-RED-S", "300");
        // Free-shipping code: Shopify's list price 85, discounted to 0 → current 0.
        UUID disc = t.order(cairo(10, 12), "new", orderRaw(COD_GW, "503", "0.00",
            "\"total_shipping_price_set\":" + money("85.00") +
            ",\"shipping_lines\":[{\"price\":\"85.00\",\"discounted_price\":\"0.00\"}]"));
        t.line(disc, v, 1, 1, "650.00");
        // Shipping refunded later (60 of 60): Shopify nets it out of the current price.
        UUID refunded = t.order(cairo(10, 13), "new", orderRaw(CARD_GW, "504", "0.00",
            "\"shipping_lines\":[{\"price\":\"60.00\",\"discounted_price\":\"60.00\"}]," +
            "\"refunds\":[{\"order_adjustments\":[{\"kind\":\"shipping_refund\",\"amount\":\"-60.00\"}]}]"));
        t.line(refunded, v, 1, 1, "650.00");
        // A GraphQL-imported raw: no shipping field at all → unknown, never 0.
        UUID unknown = t.order(cairo(10, 14), "new",
            "{\"name\":\"#9\",\"currentTotalPriceSet\":{\"shopMoney\":{\"amount\":\"735.00\"}}}");
        t.line(unknown, v, 1, 1, "650.00");
        // Positive control: shipping charged, nothing discounted.
        UUID full = t.order(cairo(10, 15), "new", orderRaw(COD_GW, "505", "85.00", null));
        t.line(full, v, 1, 1, "650.00");

        assertThat(dec(orderRow(t, number(disc)), "shipping")).isEqualByComparingTo("0");
        assertThat(dec(orderRow(t, number(refunded)), "shipping")).isEqualByComparingTo("0");
        Map<String, Object> u = orderRow(t, number(unknown));
        assertThat(u.get("shipping")).isNull();
        assertThat(dec(u, "total")).isEqualByComparingTo("650.00");
        assertThat(dec(orderRow(t, number(full)), "total")).isEqualByComparingTo("735.00");

        String[] csv = csv(t, "/api/v1/analytics/orders/export.csv?" + NOV).replace("﻿", "").split("\r\n");
        assertThat(csv[0]).endsWith(",lines,shipping");
        assertThat(Arrays.stream(csv).filter(l -> l.startsWith(number(full) + ",")).findFirst().orElseThrow())
            .contains(",735.00,").endsWith(",650.00,85.00");
        assertThat(Arrays.stream(csv).filter(l -> l.startsWith(number(unknown) + ",")).findFirst().orElseThrow())
            .endsWith(",650.00,");
    }

    @Test
    void s4_profit_shippingIncomeOnDeliveredOrdersOnly_inContributionProfit() {
        T t = new T("Ship-Profit");
        UUID v = t.variant("HD-NAV-M", "300");
        UUID delivered = t.order(cairo(10, 12), "new", orderRaw(COD_GW, "506", "85.00", null));
        t.line(delivered, v, 1, 1, "650.00");
        t.leg(delivered, "delivered", deliveredPaid("735.00", "100.00", cairo(11, 9)));
        // Refused, not cancelled: shipping is still on the Shopify order, but nobody paid it.
        UUID refused = t.order(cairo(10, 13), "new", orderRaw(COD_GW, "507", "85.00", null));
        t.line(refused, v, 1, 1, "650.00");
        t.leg(refused, "returned", rto("735.00", "60.00", cairo(11, 9)),
            "with_courier", iso(cairo(11, 9)), "returned", iso(cairo(13, 9)));

        Map<String, Object> p = ok(t, "/api/v1/analytics/profit/summary?" + NOV);
        assertThat(dec(p, "shippingIncome")).isEqualByComparingTo("85.00");
        assertThat(dec(p, "shippingIncomeOnCosted")).isEqualByComparingTo("85.00");
        // 650 realized − 300 cost + 85 shipping − (100 + 60) Bosta.
        assertThat(dec(p, "contributionProfit")).isEqualByComparingTo("275.00");
        assertThat(dec(p, "grossProfit")).isEqualByComparingTo("350.00");          // product only
        Map<String, Object> sku = list(ok(t, "/api/v1/analytics/profit/skus?" + NOV), "skus").get(0);
        assertThat(dec(sku, "shippingIncome")).isEqualByComparingTo("85.00");
        assertThat(dec(sku, "trueNet")).isEqualByComparingTo("275.00");
    }

    // ── slice 5: refused-then-cancelled orders come back ─────────────────────

    @Test
    void s5_refusedThenCancelled_isBookedAndRefused_neverRealizedOrSold_cancelledBeforePickupStaysOut() {
        T t = new T("Refused-Cancelled");
        UUID v = t.variant("HD-OLV-M", "300");
        // Delivered COD order (positive control).
        UUID ok = t.order(cairo(4, 10), "new", orderRaw(COD_GW, "601", "85.00", null));
        t.line(ok, v, 1, 1, "650.00");
        t.leg(ok, "delivered", deliveredPaid("735.00", "100.00", cairo(5, 9)),
            "with_courier", iso(cairo(5, 9)), "delivered", iso(cairo(6, 13)));
        // Refused → RTO → cancelled in Shopify: 2 units went out.
        UUID back = refusedThenCancelled(t, v, 2, "602", "1385.00", COD_GW);
        // Cancelled before Bosta picked it up: never left.
        UUID early = t.order(cairo(4, 11), "cancelled", orderRaw(COD_GW, "603", "0.00",
            "\"cancelled_at\":\"2026-11-04T15:00:00+02:00\""));
        t.line(early, v, 1, 0, "650.00");
        t.leg(early, "cancelled", neverPickedUp());
        // Cancelled in Shopify BEFORE the pickup that still happened: cancelled before pickup → out.
        UUID cancelledFirst = t.order(cairo(4, 12), "new", orderRaw(COD_GW, "604", "0.00",
            "\"cancelled_at\":\"2026-11-04T16:00:00+02:00\""));
        t.line(cancelledFirst, v, 1, 0, "650.00");
        t.leg(cancelledFirst, "returned", rto("735.00", "50.00", cairo(5, 10)),
            "with_courier", iso(cairo(5, 10)), "returned", iso(cairo(7, 10)));

        Map<String, Object> rev = m(ok(t, "/api/v1/analytics/revenue/summary?" + NOV), "current");
        assertThat(n(rev, "orders")).isEqualTo(2);
        assertThat(dec(rev, "booked")).isEqualByComparingTo("1950.00");            // 650 + 2 × 650
        assertThat(dec(rev, "realized")).isEqualByComparingTo("650.00");
        assertThat(dec(m(rev, "waterfall"), "refused")).isEqualByComparingTo("1300.00");

        Map<String, Object> del = m(ok(t, "/api/v1/analytics/delivery/summary?" + NOV), "current");
        assertThat(n(del, "delivered")).isEqualTo(1);
        assertThat(n(del, "failed")).isEqualTo(1);
        assertThat(dec(del, "successRate")).isEqualByComparingTo("0.5");

        Map<String, Object> totals = m(ok(t, "/api/v1/analytics/sales/variants?" + NOV), "totals");
        assertThat(n(totals, "soldUnits")).isEqualTo(1);                              // refused units are not sold
        assertThat(n(totals, "refusedUnits")).isEqualTo(2);
        assertThat(n(totals, "refusedOrders")).isEqualTo(1);
        assertThat(n(totals, "deliveredUnits")).isEqualTo(1);

        Map<String, Object> row = orderRow(t, number(back));
        assertThat(row.get("financialStatus")).isEqualTo("lost");
        assertThat(n(row, "items")).isEqualTo(2);
        assertThat(dec(row, "total")).isEqualByComparingTo("1300.00");             // shipping refunded at cancel → 0
        assertThat(dec(row, "netToYou")).isEqualByComparingTo("-112.86");
        List<String> names = list(ok(t, "/api/v1/analytics/orders?" + NOV + "&size=200"), "orders").stream()
            .map(r -> (String) r.get("name")).toList();
        assertThat(names).doesNotContain(number(early), number(cancelledFirst));

        Map<String, Object> p = ok(t, "/api/v1/analytics/profit/summary?" + NOV);
        assertThat(dec(p, "feesTotal")).isEqualByComparingTo("212.86");             // 100 + the refused order's 112.86
        assertThat(n(m(p, "coverage"), "keptUnits")).isEqualTo(1);
    }

    @Test
    void s5_refusedUnitsAreNeverSold_cancelledOrNot_onProductsAndSkus() {
        T t = new T("Refused-Not-Sold");
        UUID v = t.variant("HD-CRM-L", "300");
        // Delivered (positive control: sold).
        UUID delivered = t.order(cairo(10, 10), "new", orderRaw(COD_GW, "801", "85.00", null));
        t.line(delivered, v, 1, 1, "650.00");
        t.leg(delivered, "delivered", deliveredPaid("735.00", "100.00", cairo(11, 9)),
            "with_courier", iso(cairo(11, 9)), "delivered", iso(cairo(12, 13)));
        // Still with Bosta (positive control: booked units are sold until refused).
        UUID moving = t.order(cairo(10, 11), "new", orderRaw(COD_GW, "802", "85.00", null));
        t.line(moving, v, 2, 2, "650.00");
        t.leg(moving, "with_courier", "{\"type\":{\"code\":10},\"state\":{\"code\":24},\"cod\":1385,\"collectedFromBusiness\":\"" +
            iso(cairo(11, 9)) + "\"}", "with_courier", iso(cairo(11, 9)));
        // Refused, Return to Origin, NOT cancelled in Shopify: 3 units booked, never sold.
        UUID refused = t.order(cairo(10, 12), "new", orderRaw(COD_GW, "803", "85.00", null));
        t.line(refused, v, 3, 3, "650.00");
        t.leg(refused, "returned", rto("2035.00", "112.86", cairo(11, 9)),
            "with_courier", iso(cairo(11, 9)), "returning", iso(cairo(12, 9)), "returned", iso(cairo(14, 9)));
        // Refused then cancelled in Shopify: 1 unit, never sold either.
        refusedThenCancelled(t, v, 1, "804", "735.00", COD_GW);

        Map<String, Object> totals = m(ok(t, "/api/v1/analytics/sales/variants?" + NOV), "totals");
        assertThat(n(totals, "soldUnits")).isEqualTo(3);                              // 1 delivered + 2 moving
        assertThat(n(totals, "refusedUnits")).isEqualTo(4);                           // 3 + 1, still shown as refused
        assertThat(dec(totals, "grossRevenue")).isEqualByComparingTo("1950.00");
        Map<String, Object> product = list(ok(t, "/api/v1/analytics/sales/products?" + NOV), "products").get(0);
        assertThat(n(product, "soldUnits")).isEqualTo(3);
        Map<String, Object> daily = list(ok(t, "/api/v1/analytics/sales/variants/daily?" + NOV + "&ids=" + v), "variants").get(0);
        assertThat(n(daily, "totalUnits")).isEqualTo(3);
        // Booked revenue still counts every booked order (Revenue page).
        assertThat(dec(m(ok(t, "/api/v1/analytics/revenue/summary?" + NOV), "current"), "booked")).isEqualByComparingTo("4550.00");
    }

    @Test
    void s5_deliveredThenCancelledInShopify_countsDelivered_butIsNeverRealized() {
        T t = new T("Delivered-Cancelled");
        UUID v = t.variant("HD-SND-M", "300");
        UUID o = t.order(cairo(4, 12), "new", "{}");
        long line = t.line(o, v, 1, 0, "650.00");
        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", orderRaw(COD_GW, "651", "0.00",
            "\"cancelled_at\":\"2026-11-09T14:00:00+02:00\",\"fulfillments\":[{\"id\":1,\"status\":\"success\"," +
            "\"line_items\":[{\"id\":" + line + ",\"quantity\":1}]}]"), o);
        t.leg(o, "delivered", deliveredPaid("735.00", "100.00", cairo(5, 9)),
            "with_courier", iso(cairo(5, 9)), "delivered", iso(cairo(6, 13)));
        // Positive control: an ordinary delivered order.
        UUID plain = t.order(cairo(4, 13), "new", orderRaw(COD_GW, "652", "85.00", null));
        t.line(plain, v, 1, 1, "650.00");
        t.leg(plain, "delivered", deliveredPaid("735.00", "100.00", cairo(5, 9)),
            "with_courier", iso(cairo(5, 9)), "delivered", iso(cairo(6, 13)));

        Map<String, Object> rev = m(ok(t, "/api/v1/analytics/revenue/summary?" + NOV), "current");
        assertThat(dec(rev, "booked")).isEqualByComparingTo("1300.00");
        assertThat(dec(rev, "realized")).isEqualByComparingTo("650.00");          // the cancelled one is a full refund
        assertThat(n(m(ok(t, "/api/v1/analytics/delivery/summary?" + NOV), "current"), "delivered")).isEqualTo(2);
        assertThat(n(m(ok(t, "/api/v1/analytics/profit/summary?" + NOV), "coverage"), "keptUnits")).isEqualTo(1);
    }

    @Test
    void s5_customersToWatch_usePreRtoCod_shopifyTotalOnlyForCodGatewayOrders_perTenant() {
        T a = new T("Watch-A");
        UUID va = a.variant("HD-BLK-L", "300");
        // Customer 701: two refusals, Bosta zeroed the COD on the RTO but kept originalCod.
        refusedThenCancelled(a, va, 1, "701", "735.00", COD_GW);
        refusedThenCancelled(a, va, 1, "701", "735.00", COD_GW);
        // Customer 702: two refusals with no Bosta figure stored (v2-list raw), COD gateway → Shopify's total.
        refusedThenCancelled(a, va, 1, "702", null, COD_GW);
        refusedThenCancelled(a, va, 1, "702", null, COD_GW);
        // Customer 703: two refusals with no Bosta figure, paid online → never a COD refusal.
        refusedThenCancelled(a, va, 1, "703", null, CARD_GW);
        refusedThenCancelled(a, va, 1, "703", null, CARD_GW);
        // Customer 704: one refusal only (positive control of the minimum).
        refusedThenCancelled(a, va, 1, "704", "735.00", COD_GW);

        T b = new T("Watch-B");
        UUID vb = b.variant("HD-BLK-L", "300");
        refusedThenCancelled(b, vb, 1, "701", "900.00", COD_GW);                       // same Shopify customer id
        refusedThenCancelled(b, vb, 1, "701", "900.00", COD_GW);
        refusedThenCancelled(b, vb, 1, "701", "900.00", COD_GW);

        Map<String, Object> wa = ok(a, "/api/v1/analytics/customers/watch");
        assertThat(list(wa, "customers")).extracting(c -> n(c, "refusedCodOrders")).containsExactly(2L, 2L);
        assertThat(n(wa, "refusedCodFromShopify")).isEqualTo(2);
        assertThat(list(wa, "customers")).allSatisfy(c -> assertThat(dec(c, "refusedValue")).isEqualByComparingTo("1300.00"));

        Map<String, Object> wb = ok(b, "/api/v1/analytics/customers/watch");
        assertThat(list(wb, "customers")).extracting(c -> n(c, "refusedCodOrders")).containsExactly(3L);
        assertThat(n(wb, "refusedCodFromShopify")).isZero();
    }

    // ── slice 6: cost 0 = unknown ────────────────────────────────────────────

    @Test
    void s6_costZeroIsUnknown_likeNull_outOfMarginProfitAndStockValue() {
        T t = new T("Cost-Zero");
        UUID real = t.variant("HD-REAL", "300");
        UUID zero = t.variant("HD-ZERO", "0");
        UUID none = t.variant("HD-NONE", null);
        for (UUID v : List.of(real, zero, none)) {
            UUID o = t.order(cairo(10, 12), "new", orderRaw(COD_GW, String.valueOf(SEQ.incrementAndGet()), "0.00", null));
            t.line(o, v, 1, 1, "650.00");
            t.leg(o, "delivered", deliveredPaid("650.00", "0.00", cairo(11, 9)));
            t.piece(v);
        }

        Map<String, Object> p = ok(t, "/api/v1/analytics/profit/summary?" + NOV);
        Map<String, Object> cov = m(p, "coverage");
        assertThat(n(cov, "variantsSold")).isEqualTo(3);
        assertThat(n(cov, "variantsCosted")).isEqualTo(1);
        assertThat(n(cov, "costUnknownVariants")).isEqualTo(2);
        assertThat(n(cov, "costUnknownUnits")).isEqualTo(2);
        assertThat(dec(p, "grossMargin")).isEqualByComparingTo("0.5385");          // (650 − 300) ÷ 650, never 100 %
        assertThat(dec(p, "grossProfit")).isEqualByComparingTo("350.00");

        Map<String, Object> zeroSku = list(ok(t, "/api/v1/analytics/profit/skus?" + NOV), "skus").stream()
            .filter(s -> "HD-ZERO".equals(s.get("sku"))).findFirst().orElseThrow();
        assertThat(zeroSku.get("costed")).isEqualTo(false);
        assertThat(zeroSku.get("unitCost")).isNull();
        assertThat(zeroSku.get("trueNet")).isNull();

        Map<String, Object> stock = ok(t, "/api/v1/analytics/stock/summary?" + NOV);
        assertThat(dec(stock, "valueAtCost")).isEqualByComparingTo("300.00");
        assertThat(n(stock, "costedVariants")).isEqualTo(1);
    }
}
