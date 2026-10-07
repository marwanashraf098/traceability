package com.traceability;

import com.traceability.analytics.AnalyticsFloorOverrides;
import com.traceability.analytics.AnalyticsPeriod;
import com.traceability.analytics.SalesAnalyticsService;
import com.traceability.identity.JwtService;
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
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Analytics slice 2 — delivery outcomes, customer returns (GET /api/v1/analytics/sales/variants,
 * new fields) and cities (GET /api/v1/analytics/sales/cities).
 *
 * Each test builds its own tenant through the postgres connection and reads over HTTP with an
 * OWNER token; the isolation tests read as app_user under RLS.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalyticsOutcomesTest {

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
    }

    static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    static final String SEPT = "from=2026-09-01&to=2026-09-30";
    static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired AnalyticsFloorOverrides floorOverrides;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    static Instant cairo(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(y, m, d, h, min).atZone(CAIRO).toInstant();
    }

    static final Instant SEPT_10 = cairo(2026, 9, 10, 12, 0);

    final class T {
        final UUID id = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        final UUID store = UUID.randomUUID();
        final UUID product = UUID.randomUUID();
        final String ownerToken;

        T(String name) {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                        "VALUES (?, ?, 'Owner', ?, 'x', 'owner')", owner, id, "owner+" + owner + "@an2.test");
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                        "VALUES (?, ?, 'shopify', ?, 'connected', ?)",
                        store, id, "an2-" + id + ".myshopify.com", Timestamp.from(cairo(2026, 6, 1, 0, 0)));
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, 'Tee')",
                        product, id, store, "P-" + product);
            ownerToken = jwt.issueAccessToken(owner, id, "owner");
        }

        UUID variant(String title, String price) {
            UUID v = UUID.randomUUID();
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title, price) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)", v, id, product, "V-" + v, "SKU-" + title, title,
                        price == null ? null : new BigDecimal(price));
            return v;
        }

        UUID order(String carrierClass) {
            return order(carrierClass, "new", "{}");
        }

        UUID order(String carrierClass, String status, String rawJson) {
            UUID o = UUID.randomUUID();
            jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw, " +
                        "shipping_carrier_class) VALUES (?, ?, ?, ?, ?, ?::order_status, ?, ?::jsonb, ?)",
                        o, id, store, "EXT-" + o, "#" + SEQ.incrementAndGet(), status, Timestamp.from(SEPT_10),
                        rawJson, carrierClass);
            return o;
        }

        /** A REST line with id N (external_id gid://shopify/LineItem/N). Returns order_items.id. */
        UUID line(UUID order, UUID variant, long lineId, int quantity, int currentQty, String price, String... allocations) {
            StringBuilder alloc = new StringBuilder("[");
            for (int i = 0; i < allocations.length; i++) {
                if (i > 0) alloc.append(',');
                alloc.append("{\"amount\":\"").append(allocations[i]).append("\"}");
            }
            alloc.append(']');
            String raw = price == null
                ? "{\"id\":" + lineId + ",\"quantity\":" + quantity + "}"
                : "{\"id\":" + lineId + ",\"price\":\"" + price + "\",\"quantity\":" + quantity +
                  ",\"current_quantity\":" + currentQty + ",\"discount_allocations\":" + alloc + "}";
            UUID oi = UUID.randomUUID();
            jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity, external_id, raw) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
                        oi, id, order, variant, quantity, "gid://shopify/LineItem/" + lineId, raw);
            return oi;
        }

        UUID line(UUID order, UUID variant, int qty) {
            return line(order, variant, SEQ.incrementAndGet(), qty, qty, "100.00");
        }

        UUID shipment(UUID order, String leg, int typeCode, String state, String city, Instant createdAt) {
            UUID s = UUID.randomUUID();
            String raw = "{\"type\":{\"code\":" + typeCode + "},\"dropOffAddress\":" +
                         (city == null ? "{}" : "{\"city\":{\"_id\":\"" + cityId(city) + "\",\"name\":\"" + city + "\"}}") + "}";
            jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state, shipment_leg, " +
                        "raw, created_at) VALUES (?, ?, ?, ?, ?::shipment_internal_state, ?, ?::jsonb, ?)",
                        s, id, order, String.valueOf(SEQ.incrementAndGet()), state, leg, raw, Timestamp.from(createdAt));
            return s;
        }

        UUID forward(UUID order, int typeCode, String state, String city) {
            return shipment(order, "forward", typeCode, state, city, SEPT_10.plusSeconds(60));
        }

        void history(UUID shipment, String state, Instant at) {
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) " +
                        "VALUES (?, ?, ?::shipment_internal_state, ?)", id, shipment, state, Timestamp.from(at));
        }

        String piece(UUID variant, UUID orderItem) {
            String p = "AN2" + SEQ.incrementAndGet();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                        "VALUES (?, ?, ?, ?, ?, 'return_pending_inspection')", p, id, variant, "PC-" + p, p);
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
                        id, orderItem, p);
            return p;
        }

        /** A dashboard exchange (no request) matched to the order, its inbound (old) variant given. */
        void dashboardExchange(UUID order, UUID inboundVariant) {
            jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, inbound_variant_id, matched_order_id, raw) " +
                        "VALUES (?, ?, 'matched', ?, ?, '{}'::jsonb)",
                        id, String.valueOf(SEQ.incrementAndGet()), inboundVariant, order);
        }

        void returnReceived(String piece, UUID order, String fromStatus, String metadata) {
            jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, from_status, to_status, metadata) " +
                        "VALUES (?, ?, 'return_received', ?, ?::piece_status, 'return_pending_inspection', ?::jsonb)",
                        id, piece, order, fromStatus, metadata);
        }

        UUID request(UUID order, String type) {
            UUID r = UUID.randomUUID();
            jdbc.update("INSERT INTO return_requests (id, tenant_id, order_id, type, status, reference) " +
                        "VALUES (?, ?, ?, ?, 'received', ?)", r, id, order, type, reference());
            return r;
        }

        void trackedItem(UUID request, UUID variant, String piece, String itemStatus) {
            jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code, " +
                        "active, item_status) VALUES (?, ?, ?, ?, 'wrong_size', ?, ?)",
                        id, request, piece, variant, itemStatus.equals("arrived"), itemStatus);
        }

        void untrackedItem(UUID request, UUID variant, UUID orderItem, int unitNo, String itemStatus) {
            jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, order_item_id, unit_no, variant_id, " +
                        "reason_code, active, item_status) VALUES (?, ?, ?, ?, ?, 'wrong_size', ?, ?)",
                        id, request, orderItem, unitNo, variant, itemStatus.equals("arrived"), itemStatus);
        }
    }

    /** A stable Bosta-like city._id per city name, for fixtures. */
    static String cityId(String city) {
        return "city-" + city.toLowerCase().replace(' ', '-');
    }

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

    /** Order raw with one fulfillment of the given line ids and one refund of one line. */
    static String raw(String fulfilledAt, long[] fulfilledLines, String refundedAt, long refundLine, int refundQty,
                      String restock) {
        StringBuilder sb = new StringBuilder("{");
        if (fulfilledAt != null) {
            sb.append("\"fulfillments\":[{\"created_at\":\"").append(fulfilledAt).append("\",\"status\":\"success\",\"line_items\":[");
            for (int i = 0; i < fulfilledLines.length; i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"id\":").append(fulfilledLines[i]).append('}');
            }
            sb.append("]}],");
        }
        sb.append("\"refunds\":[{\"created_at\":\"").append(refundedAt).append("\",\"refund_line_items\":[{\"line_item_id\":")
          .append(refundLine).append(",\"quantity\":").append(refundQty).append(",\"restock_type\":\"").append(restock)
          .append("\"}]}]}");
        return sb.toString();
    }

    private String base() { return "http://localhost:" + port; }

    private ResponseEntity<Map> get(String token, String pathAndQuery) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(base() + pathAndQuery, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    private Map<String, Object> variants(T t) {
        ResponseEntity<Map> r = get(t.ownerToken, "/api/v1/analytics/sales/variants?" + SEPT);
        assertThat(r.getStatusCode()).as("body: %s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> totals(Map<String, Object> body) {
        return (Map<String, Object>) body.get("totals");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Map<String, Object> body, UUID variant) {
        return ((List<Map<String, Object>>) body.get("variants")).stream()
                .filter(r -> variant.toString().equals(r.get("variantId"))).findFirst().orElseThrow();
    }

    private static long n(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).longValue();
    }

    private static BigDecimal dec(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : new BigDecimal(v.toString());
    }

    private static final List<String> BUCKETS = List.of("deliveredUnits", "refusedUnits", "inTransitUnits",
            "wijhaUnits", "notShippedUnits", "otherTerminalUnits");

    private static void assertBucketsSumToSold(Map<String, Object> m) {
        long sum = BUCKETS.stream().mapToLong(k -> n(m, k)).sum();
        assertThat(sum).as("outcome buckets must sum to soldUnits: %s", m).isEqualTo(n(m, "soldUnits"));
    }

    // ── outcomes ─────────────────────────────────────────────────────────────

    @Test
    void everyOutcomeBucket_andBucketsSumToSoldUnits() {
        T t = new T("An2-Buckets");
        UUID v = t.variant("M", "100.00");

        UUID delivered = t.order("bosta");
        t.line(delivered, v, 1);
        t.forward(delivered, 10, "delivered", "Cairo");

        UUID deliveredByHistory = t.order("bosta");     // delivered, then an exception state
        t.line(deliveredByHistory, v, 1);
        UUID dh = t.forward(deliveredByHistory, 10, "exception", "Cairo");
        t.history(dh, "with_courier", SEPT_10.plusSeconds(3600));
        t.history(dh, "delivered", SEPT_10.plusSeconds(7200));

        UUID rto = t.order("bosta");                     // turned Return to Origin, still with courier
        t.line(rto, v, 2);
        t.forward(rto, 20, "with_courier", "Giza");

        UUID returning = t.order("bosta");               // history went returning, no delivery
        t.line(returning, v, 1);
        UUID rh = t.forward(returning, 10, "exception", "Giza");
        t.history(rh, "returning", SEPT_10.plusSeconds(3600));

        UUID inTransit = t.order("bosta");
        t.line(inTransit, v, 3);
        t.forward(inTransit, 10, "with_courier", "Alexandria");

        UUID wijha = t.order("other_known");
        t.line(wijha, v, 4);

        UUID notShipped = t.order(null);
        t.line(notShipped, v, 5);

        UUID lost = t.order("bosta");
        t.line(lost, v, 6);
        t.forward(lost, 10, "lost", "Cairo");

        Map<String, Object> body = variants(t);
        Map<String, Object> r = row(body, v);
        assertThat(n(r, "soldUnits")).isEqualTo(23);
        assertThat(n(r, "deliveredUnits")).isEqualTo(2);
        assertThat(n(r, "refusedUnits")).isEqualTo(3);
        assertThat(n(r, "inTransitUnits")).isEqualTo(3);
        assertThat(n(r, "wijhaUnits")).isEqualTo(4);
        assertThat(n(r, "notShippedUnits")).isEqualTo(5);
        assertThat(n(r, "otherTerminalUnits")).isEqualTo(6);
        assertBucketsSumToSold(r);
        assertThat(dec(r, "refusalRate")).isEqualByComparingTo("0.2727");    // 3 / (2 + 3 refused + 6 other terminal)
        assertThat(dec(r, "deliveredRevenue")).isEqualByComparingTo("200.00");

        Map<String, Object> tot = totals(body);
        assertBucketsSumToSold(tot);
        assertThat(n(tot, "deliveredOrders")).isEqualTo(2);
        assertThat(n(tot, "refusedOrders")).isEqualTo(2);
        assertThat(n(tot, "wijhaOrders")).isEqualTo(1);
    }

    @Test
    void forwardLegOnly_returnPickupAndExchangeLegsNeverDecide() {
        T t = new T("An2-ForwardOnly");
        UUID v = t.variant("L", "100.00");

        // In-transit forward leg + a returned customer-return-pickup leg: still in transit.
        UUID o1 = t.order("bosta");
        t.line(o1, v, 1);
        t.forward(o1, 10, "with_courier", "Cairo");
        t.shipment(o1, "return", 25, "returned", "Cairo", SEPT_10.plusSeconds(9000));

        // Only a CRP leg: no forward leg → not shipped, never refused/delivered.
        UUID o2 = t.order("bosta");
        t.line(o2, v, 2);
        t.shipment(o2, "return", 25, "returned", "Giza", SEPT_10.plusSeconds(9000));

        // Only a delivered type-30 (exchange) leg recorded as forward: it never decides → not shipped.
        UUID o3 = t.order("bosta");
        t.line(o3, v, 4);
        t.shipment(o3, "forward", 30, "delivered", "Giza", SEPT_10.plusSeconds(9000));

        Map<String, Object> r = row(variants(t), v);
        assertThat(n(r, "inTransitUnits")).isEqualTo(1);
        assertThat(n(r, "notShippedUnits")).isEqualTo(6);
        assertThat(n(r, "deliveredUnits")).isZero();
        assertThat(n(r, "refusedUnits")).isZero();
        assertBucketsSumToSold(r);
    }

    @Test
    void multipleForwardLegs_theActiveLegDecides_elseTheLatestEndedLeg_wijhaOverAnEndedLeg() {
        T t = new T("An2-MultiLeg");
        UUID v = t.variant("S", "100.00");

        // Cancelled leg created AFTER the delivered one: the active (delivered) leg still decides.
        UUID o1 = t.order("bosta");
        t.line(o1, v, 1);
        t.shipment(o1, "forward", 10, "delivered", "Cairo", SEPT_10.plusSeconds(60));
        t.shipment(o1, "forward", 10, "cancelled", "Cairo", SEPT_10.plusSeconds(600));

        // Older terminated leg + newer in-transit leg: the in-transit one decides.
        UUID o2 = t.order("bosta");
        t.line(o2, v, 2);
        t.shipment(o2, "forward", 10, "terminated", "Cairo", SEPT_10.plusSeconds(60));
        t.shipment(o2, "forward", 10, "with_courier", "Cairo", SEPT_10.plusSeconds(600));

        // Only ended legs: the latest decides → other_terminal.
        UUID o3 = t.order("bosta");
        t.line(o3, v, 4);
        t.shipment(o3, "forward", 10, "cancelled", "Cairo", SEPT_10.plusSeconds(60));
        t.shipment(o3, "forward", 10, "terminated", "Cairo", SEPT_10.plusSeconds(600));

        // Ended Bosta leg, then shipped with Wijha: wijha.
        UUID o4 = t.order("other_known");
        t.line(o4, v, 8);
        t.shipment(o4, "forward", 10, "cancelled", "Cairo", SEPT_10.plusSeconds(60));

        Map<String, Object> r = row(variants(t), v);
        assertThat(n(r, "deliveredUnits")).isEqualTo(1);
        assertThat(n(r, "inTransitUnits")).isEqualTo(2);
        assertThat(n(r, "otherTerminalUnits")).isEqualTo(4);
        assertThat(n(r, "wijhaUnits")).isEqualTo(8);
        assertBucketsSumToSold(r);
    }

    // ── returns ──────────────────────────────────────────────────────────────

    @Test
    void returnDedupe_unitInAllThreeSourcesCountsOnce_shopifyAddsOnlyUnidentifiedUnits() {
        T t = new T("An2-Dedupe");
        UUID v = t.variant("Dedupe", "100.00");

        // Line of 3, one unit refunded as 'return' in Shopify after fulfillment (current_quantity 2).
        long lineId = SEQ.incrementAndGet();
        UUID o = t.order("bosta", "new",
                raw("2026-09-11T10:00:00+03:00", new long[]{lineId}, "2026-09-15T10:00:00+03:00", lineId, 1, "return"));
        UUID oi = t.line(o, v, lineId, 3, 2, "100.00");
        t.forward(o, 10, "delivered", "Cairo");
        String p1 = t.piece(v, oi);
        t.returnReceived(p1, o, "delivered", "{\"return_kind\":\"customer_after_delivery\"}");   // source 1
        UUID req = t.request(o, "refund");
        t.trackedItem(req, v, p1, "done");                                                       // source 2

        Map<String, Object> r = row(variants(t), v);
        assertThat(n(r, "soldUnits")).as("the Shopify-refunded unit is added back").isEqualTo(3);
        assertThat(n(r, "deliveredUnits")).isEqualTo(3);
        assertThat(n(r, "returnedUnits")).as("same unit in piece + portal + Shopify counts once").isEqualTo(1);
        assertThat(n(r, "netSoldUnits")).isEqualTo(2);
        assertThat(dec(r, "returnRate")).isEqualByComparingTo("0.3333");

        // A second line: 2 untracked units arrived through the portal + 1 Shopify 'return' unit → 2.
        UUID v2 = t.variant("Untracked", "100.00");
        long line2 = SEQ.incrementAndGet();
        UUID o2 = t.order("bosta", "new",
                raw("2026-09-11T10:00:00+03:00", new long[]{line2}, "2026-09-15T10:00:00+03:00", line2, 1, "return"));
        UUID oi2 = t.line(o2, v2, line2, 3, 2, "100.00");
        t.forward(o2, 10, "delivered", "Cairo");
        UUID req2 = t.request(o2, "refund");
        t.untrackedItem(req2, v2, oi2, 1, "arrived");
        t.untrackedItem(req2, v2, oi2, 2, "done");

        // A third line: Shopify says 2 returned, scans identify only 1 → 2.
        UUID v3 = t.variant("ShopifyMore", "100.00");
        long line3 = SEQ.incrementAndGet();
        UUID o3 = t.order("bosta", "new",
                raw("2026-09-11T10:00:00+03:00", new long[]{line3}, "2026-09-15T10:00:00+03:00", line3, 2, "return"));
        UUID oi3 = t.line(o3, v3, line3, 3, 1, "100.00");
        t.forward(o3, 10, "delivered", "Cairo");
        t.returnReceived(t.piece(v3, oi3), o3, "delivered", "{\"return_kind\":\"customer_after_delivery\"}");

        Map<String, Object> body = variants(t);
        assertThat(n(row(body, v2), "returnedUnits")).isEqualTo(2);
        assertThat(n(row(body, v3), "returnedUnits")).isEqualTo(2);
        assertThat(n(totals(body), "returnedUnits")).isEqualTo(5);
    }

    @Test
    void exchangesAreNotReturns_exchangeMatchPieces_exchangeRequestPieces_exchangePortalItems() {
        T t = new T("An2-Exchanges");
        UUID v = t.variant("Swap", "100.00");
        UUID o = t.order("bosta");
        UUID oi = t.line(o, v, 3);
        t.forward(o, 10, "delivered", "Cairo");

        t.dashboardExchange(o, v);                     // the exchanged unit came back as exchange_match
        t.returnReceived(t.piece(v, oi), o, "delivered", "{\"return_kind\":\"exchange_match\"}");
        UUID exchangeReq = t.request(o, "exchange");
        String p2 = t.piece(v, oi);
        t.returnReceived(p2, o, "delivered",
                "{\"return_kind\":\"request_return\",\"request_id\":\"" + exchangeReq + "\"}");
        t.trackedItem(exchangeReq, v, p2, "done");
        t.untrackedItem(t.request(o, "exchange"), v, oi, 1, "arrived");
        // RTO intake (from return_pending_inspection) is refused territory, never a customer return.
        t.returnReceived(t.piece(v, oi), o, "return_pending_inspection", "{\"return_kind\":\"rto\"}");

        Map<String, Object> r = row(variants(t), v);
        assertThat(n(r, "deliveredUnits")).isEqualTo(3);
        assertThat(n(r, "returnedUnits")).isZero();
    }

    @Test
    void exchangeExclusion_onlyTheExchangedUnit_notEveryPieceOfTheOrder() {
        T t = new T("An2-ExchangedUnitOnly");

        // One exchanged unit + one genuinely returned unit of the same variant. The scan labels BOTH
        // 'exchange_match' (it's order-scoped); only one unit was exchanged → returned = 1.
        UUID v = t.variant("Same", "100.00");
        UUID o = t.order("bosta");
        UUID oi = t.line(o, v, 2);
        t.forward(o, 10, "delivered", "Cairo");
        t.dashboardExchange(o, v);
        t.returnReceived(t.piece(v, oi), o, "delivered", "{\"return_kind\":\"exchange_match\"}");
        t.returnReceived(t.piece(v, oi), o, "delivered", "{\"return_kind\":\"exchange_match\"}");

        // Different variants on one order: the exchange's inbound variant is the exchanged unit,
        // the other variant's returned piece counts.
        UUID exchanged = t.variant("ExchangedOld", "100.00");
        UUID kept = t.variant("ReturnedOther", "100.00");
        UUID o2 = t.order("bosta");
        UUID oiA = t.line(o2, exchanged, 1);
        UUID oiB = t.line(o2, kept, 1);
        t.forward(o2, 10, "delivered", "Giza");
        t.dashboardExchange(o2, exchanged);
        t.returnReceived(t.piece(kept, oiB), o2, "delivered", "{\"return_kind\":\"exchange_match\"}");
        t.returnReceived(t.piece(exchanged, oiA), o2, "delivered", "{\"return_kind\":\"exchange_match\"}");

        // A dismissed exchange excludes nothing.
        UUID dismissedVariant = t.variant("DismissedExchange", "100.00");
        UUID o3 = t.order("bosta");
        UUID oi3 = t.line(o3, dismissedVariant, 1);
        t.forward(o3, 10, "delivered", "Cairo");
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, inbound_variant_id, matched_order_id, raw) " +
                    "VALUES (?, ?, 'dismissed', ?, ?, '{}'::jsonb)", t.id, String.valueOf(SEQ.incrementAndGet()),
                    dismissedVariant, o3);
        t.returnReceived(t.piece(dismissedVariant, oi3), o3, "delivered", "{\"return_kind\":\"exchange_match\"}");

        Map<String, Object> body = variants(t);
        assertThat(n(row(body, v), "returnedUnits")).as("one exchanged, one returned").isEqualTo(1);
        assertThat(n(row(body, exchanged), "returnedUnits")).as("the exchanged variant").isZero();
        assertThat(n(row(body, kept), "returnedUnits")).as("the other variant's return").isEqualTo(1);
        assertThat(n(row(body, dismissedVariant), "returnedUnits")).isEqualTo(1);
        assertThat(n(totals(body), "returnedUnits")).isEqualTo(3);
    }

    @Test
    void returnsOnCancelledOrdersExcluded_returnsOnUndeliveredOrdersReportedApart() {
        T t = new T("An2-Undelivered");
        UUID v = t.variant("U", "100.00");

        UUID cancelled = t.order("bosta", "cancelled", "{}");
        UUID oic = t.line(cancelled, v, 1);
        t.forward(cancelled, 10, "delivered", "Cairo");
        t.returnReceived(t.piece(v, oic), cancelled, "delivered", "{\"return_kind\":\"customer_after_delivery\"}");

        // A Wijha order refunded as a return in Shopify after fulfillment: a sale, a return outside delivered.
        long lineId = SEQ.incrementAndGet();
        UUID wijha = t.order("other_known", "new",
                raw("2026-09-11T10:00:00+03:00", new long[]{lineId}, "2026-09-20T10:00:00+03:00", lineId, 1, "return"));
        t.line(wijha, v, lineId, 1, 0, "100.00");

        Map<String, Object> body = variants(t);
        Map<String, Object> r = row(body, v);
        assertThat(n(r, "soldUnits")).isEqualTo(1);
        assertThat(n(r, "wijhaUnits")).isEqualTo(1);
        assertThat(n(r, "returnedUnits")).isZero();
        assertThat(n(totals(body), "returnsOnUndeliveredOrders")).isEqualTo(1);
    }

    @Test
    void rates_nullWhenDenominatorIsZero() {
        T t = new T("An2-Rates");
        UUID notShipped = t.variant("NotShipped", "100.00");
        t.line(t.order(null), notShipped, 2);
        UUID refusedOnly = t.variant("RefusedOnly", "100.00");
        UUID o = t.order("bosta");
        t.line(o, refusedOnly, 1);
        t.forward(o, 20, "returned", "Cairo");
        UUID lostOnly = t.variant("LostOnly", "100.00");
        UUID lo = t.order("bosta");
        t.line(lo, lostOnly, 1);
        t.forward(lo, 10, "lost", "Cairo");

        Map<String, Object> body = variants(t);
        Map<String, Object> a = row(body, notShipped);
        assertThat(a.get("returnRate")).isNull();
        assertThat(a.get("refusalRate")).isNull();
        Map<String, Object> b = row(body, refusedOnly);
        assertThat(b.get("returnRate")).as("no delivered units").isNull();
        assertThat(dec(b, "refusalRate")).isEqualByComparingTo("1.0000");
        // Lost only: a failure, but not a refusal → 0 (not null: the denominator counts it).
        Map<String, Object> lostRow = row(body, lostOnly);
        assertThat(dec(lostRow, "refusalRate")).isEqualByComparingTo("0.0000");
        assertThat(totals(body).get("returnRate")).isNull();

        T empty = new T("An2-Empty");
        Map<String, Object> e = totals(variants(empty));
        assertThat(e.get("returnRate")).isNull();
        assertThat(e.get("refusalRate")).isNull();
        assertThat(n(e, "deliveredUnits")).isZero();
    }

    @Test
    void returnedRevenue_usesTheLinesNetUnitPrice_includingTheApproximateFallback() {
        T t = new T("An2-ReturnRevenue");
        UUID v = t.variant("Net", "999.00");
        UUID o = t.order("bosta");
        UUID oi = t.line(o, v, SEQ.incrementAndGet(), 2, 2, "500.00", "100.00");    // 450 per unit
        t.forward(o, 10, "delivered", "Cairo");
        t.returnReceived(t.piece(v, oi), o, "delivered", "{\"return_kind\":\"customer_after_delivery\"}");

        UUID approx = t.variant("Approx", "300.00");
        UUID o2 = t.order("bosta");
        UUID oi2 = t.line(o2, approx, SEQ.incrementAndGet(), 2, 2, null);            // no REST price → 300
        t.forward(o2, 10, "delivered", "Cairo");
        t.untrackedItem(t.request(o2, "refund"), approx, oi2, 1, "done");

        Map<String, Object> body = variants(t);
        Map<String, Object> r = row(body, v);
        assertThat(dec(r, "deliveredRevenue")).isEqualByComparingTo("900.00");
        assertThat(dec(r, "returnedRevenue")).isEqualByComparingTo("450.00");
        assertThat(dec(r, "netRevenue")).isEqualByComparingTo("450.00");
        Map<String, Object> a = row(body, approx);
        assertThat(dec(a, "returnedRevenue")).isEqualByComparingTo("300.00");
        assertThat(n(a, "approximateLines")).isEqualTo(1);
        assertThat(dec(totals(body), "returnedRevenue")).isEqualByComparingTo("750.00");
    }

    // ── Shopify refunds vs sold quantity ─────────────────────────────────────

    @Test
    void shopifyRefunds_beforeFulfillmentExcluded_afterFulfillmentAddedBack_noRestockNotAReturnUnlessConfirmed() {
        T t = new T("An2-Refunds");
        UUID v = t.variant("R", "200.00");

        // (a) 'return' and 'no_restock' refunds BEFORE the line was fulfilled: pre-ship → not sold.
        long a1 = SEQ.incrementAndGet();
        UUID oa = t.order("bosta", "new",
                raw("2026-09-12T10:00:00+03:00", new long[]{a1}, "2026-09-11T10:00:00+03:00", a1, 1, "no_restock"));
        t.line(oa, v, a1, 1, 0, "200.00");
        long a2 = SEQ.incrementAndGet();
        UUID oa2 = t.order("bosta", "new",
                raw("2026-09-12T10:00:00+03:00", new long[]{SEQ.incrementAndGet()}, "2026-09-13T10:00:00+03:00", a2, 1, "return"));
        t.line(oa2, v, a2, 1, 0, "200.00");    // fulfilled later, but never this line → not added back

        // (b) 'no_restock' AFTER fulfillment: sold again, delivered, not a return…
        long b = SEQ.incrementAndGet();
        UUID ob = t.order("bosta", "new",
                raw("2026-09-11T10:00:00+03:00", new long[]{b}, "2026-09-14T10:00:00+03:00", b, 1, "no_restock"));
        t.line(ob, v, b, 1, 0, "200.00");
        t.forward(ob, 10, "delivered", "Cairo");

        // (c) 'return' AFTER fulfillment: sold again AND returned.
        long c = SEQ.incrementAndGet();
        UUID oc = t.order("bosta", "new",
                raw("2026-09-11T10:00:00+03:00", new long[]{c}, "2026-09-14T10:00:00+03:00", c, 1, "return"));
        t.line(oc, v, c, 1, 0, "200.00");
        t.forward(oc, 10, "delivered", "Cairo");

        // (d) 'cancel' after fulfillment is never added back.
        long d = SEQ.incrementAndGet();
        UUID od = t.order("bosta", "new",
                raw("2026-09-11T10:00:00+03:00", new long[]{d}, "2026-09-14T10:00:00+03:00", d, 1, "cancel"));
        t.line(od, v, d, 1, 0, "200.00");

        Map<String, Object> body = variants(t);
        Map<String, Object> r = row(body, v);
        assertThat(n(r, "soldUnits")).as("b + c only").isEqualTo(2);
        assertThat(dec(r, "grossRevenue")).as("added-back units at the line's net price").isEqualByComparingTo("400.00");
        assertThat(n(r, "deliveredUnits")).isEqualTo(2);
        assertThat(n(r, "returnedUnits")).as("only the 'return' refund is a return").isEqualTo(1);

        // …until the portal confirms the no_restock unit came back.
        UUID obItem = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ?", UUID.class, ob);
        t.untrackedItem(t.request(ob, "refund"), v, obItem, 1, "arrived");
        assertThat(n(row(variants(t), v), "returnedUnits")).isEqualTo(2);
    }

    @Test
    void shopifyRefunds_missingFulfillmentData_returnAddedBack_noRestockCountedApart() {
        T t = new T("An2-NoFulfillments");
        UUID v = t.variant("F", "100.00");
        long a = SEQ.incrementAndGet();
        UUID oa = t.order("bosta", "new", raw(null, null, "2026-09-14T10:00:00+03:00", a, 1, "return"));
        t.line(oa, v, a, 1, 0, "100.00");
        t.forward(oa, 10, "delivered", "Cairo");
        long b = SEQ.incrementAndGet();
        UUID ob = t.order("bosta", "new", raw(null, null, "2026-09-14T10:00:00+03:00", b, 1, "no_restock"));
        t.line(ob, v, b, 1, 0, "100.00");

        Map<String, Object> body = variants(t);
        Map<String, Object> r = row(body, v);
        assertThat(n(r, "soldUnits")).isEqualTo(1);
        assertThat(n(r, "returnedUnits")).isEqualTo(1);
        assertThat(n(totals(body), "unverifiedNoRestockLines")).isEqualTo(1);
    }

    // ── cities ───────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void cities_countsPerDecidingLegCity_successRate_sortedByOrders_tenantIsolated() {
        T t = new T("An2-Cities");
        T other = new T("An2-CitiesOther");
        UUID v = t.variant("C", "100.00");
        for (String state : List.of("delivered", "delivered", "delivered", "returned")) {
            UUID o = t.order("bosta");
            t.line(o, v, 1);
            t.forward(o, state.equals("returned") ? 20 : 10, state, "Cairo");
        }
        UUID lostCairo = t.order("bosta");       // lost: a failure in the success rate
        t.line(lostCairo, v, 1);
        t.forward(lostCairo, 10, "lost", "Cairo");
        UUID g = t.order("bosta");
        t.line(g, v, 1);
        t.forward(g, 10, "with_courier", "Giza");
        UUID wijha = t.order("other_known");     // no Bosta leg → not listed
        t.line(wijha, v, 1);
        // Same Bosta city._id under another spelling → still the one Cairo row (keyed by id, not name).
        UUID respelled = t.order("bosta");
        t.line(respelled, v, 1);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, tracking_number, internal_state, shipment_leg, raw, created_at) " +
                    "VALUES (?, ?, ?, 'delivered', 'forward', ?::jsonb, ?)", t.id, respelled,
                    String.valueOf(SEQ.incrementAndGet()),
                    "{\"type\":{\"code\":10},\"dropOffAddress\":{\"city\":{\"_id\":\"" + cityId("Cairo") + "\",\"name\":\"Al Qahira\"}}}",
                    Timestamp.from(SEPT_10.plusSeconds(60)));
        UUID noCity = t.order("bosta");          // Bosta leg with no city → the "Unknown" row
        t.line(noCity, v, 1);
        t.forward(noCity, 10, "created", null);
        // Bosta reference data: Arabic name for Cairo only (Giza falls back to its English name).
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, district_name) " +
                    "VALUES (?, ?, 'Cairo', 'القاهرة', 'Maadi') ON CONFLICT DO NOTHING",
                    "d-" + UUID.randomUUID(), cityId("Cairo"));
        UUID ov = other.variant("C", "100.00");
        for (int i = 0; i < 6; i++) {
            UUID o = other.order("bosta");
            other.line(o, ov, 1);
            other.forward(o, 10, "delivered", "Cairo");
        }

        ResponseEntity<Map> r = get(t.ownerToken, "/api/v1/analytics/sales/cities?" + SEPT);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> cities = (List<Map<String, Object>>) r.getBody().get("cities");
        assertThat(cities).extracting(c -> c.get("nameEn")).containsExactly("Cairo", "Giza", "Unknown");
        assertThat(cities).extracting(c -> c.get("cityId")).containsExactly(cityId("Cairo"), cityId("Giza"), null);
        Map<String, Object> cairo = cities.get(0);
        assertThat(cairo.get("nameAr")).isEqualTo("القاهرة");
        assertThat(cities.get(1).get("nameAr")).as("no Arabic name → English").isEqualTo("Giza");
        Map<String, Object> unknown = cities.get(2);
        assertThat(unknown.get("nameAr")).isEqualTo("Unknown");
        assertThat(n(unknown, "orders")).isEqualTo(1);
        assertThat(n(unknown, "inTransitOrders")).isEqualTo(1);
        assertThat(n(cairo, "orders")).isEqualTo(6);
        assertThat(n(cairo, "otherTerminalOrders")).isEqualTo(1);
        assertThat(n(cairo, "deliveredOrders")).isEqualTo(4);
        assertThat(n(cairo, "refusedOrders")).isEqualTo(1);
        assertThat(dec(cairo, "successRate")).isEqualByComparingTo("0.6667");   // 4 / (4 + 1 refused + 1 lost)
        Map<String, Object> giza = cities.get(1);
        assertThat(n(giza, "inTransitOrders")).isEqualTo(1);
        assertThat(giza.get("successRate")).isNull();

        // app_user + RLS: each tenant sees only its own cities.
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
        SalesAnalyticsService svc = new SalesAnalyticsService(new JdbcTemplate(appUserDs), Clock.system(CAIRO), floorOverrides);
        AnalyticsPeriod sept = new AnalyticsPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        TenantContext.set(other.id);
        SalesAnalyticsService.CitySalesResponse asOther = tx.execute(s -> svc.cities(sept));
        assertThat(asOther.cities()).hasSize(1);
        assertThat(asOther.cities().get(0).orders()).isEqualTo(6);
        TenantContext.set(t.id);
        assertThat(tx.execute(s -> svc.cities(sept)).cities().get(0).orders()).isEqualTo(6);
    }

    @Test
    void outcomesAndReturns_asAppUser_otherTenantsShipmentsAndReturnsNeverCount() {
        T a = new T("An2-IsoA");
        T b = new T("An2-IsoB");
        UUID va = a.variant("A", "100.00");
        UUID oa = a.order("bosta");
        UUID oia = a.line(oa, va, 2);
        a.forward(oa, 10, "delivered", "Cairo");
        a.returnReceived(a.piece(va, oia), oa, "delivered", "{\"return_kind\":\"customer_after_delivery\"}");
        UUID vb = b.variant("B", "100.00");
        UUID ob = b.order("bosta");
        UUID oib = b.line(ob, vb, 5);
        b.forward(ob, 20, "returned", "Giza");
        b.returnReceived(b.piece(vb, oib), ob, "delivered", "{\"return_kind\":\"customer_after_delivery\"}");

        TenantAwareDataSource appUserDs = new TenantAwareDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
        SalesAnalyticsService svc = new SalesAnalyticsService(new JdbcTemplate(appUserDs), Clock.system(CAIRO), floorOverrides);
        AnalyticsPeriod sept = new AnalyticsPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        TenantContext.set(a.id);
        SalesAnalyticsService.VariantSalesResponse asA = tx.execute(s -> svc.variants(sept));
        assertThat(asA.variants()).hasSize(1);
        SalesAnalyticsService.VariantSales ra = asA.variants().get(0);
        assertThat(ra.variantId()).isEqualTo(va);
        assertThat(ra.deliveredUnits()).isEqualTo(2);
        assertThat(ra.returnedUnits()).isEqualTo(1);
        assertThat(asA.totals().refusedUnits()).isZero();

        TenantContext.set(b.id);
        SalesAnalyticsService.VariantSalesResponse asB = tx.execute(s -> svc.variants(sept));
        assertThat(asB.totals().refusedUnits()).isEqualTo(5);
        assertThat(asB.totals().deliveredUnits()).isZero();
    }

    @Test
    void cities_ownerOnly() {
        T t = new T("An2-Roles");
        UUID manager = UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'M', ?, 'x', 'manager')",
                    manager, t.id, "m+" + manager + "@an2.test");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'W', ?, 'x', 'worker')",
                    worker, t.id, "w+" + worker + "@an2.test");
        String path = "/api/v1/analytics/sales/cities?period=7d";
        assertThat(get(t.ownerToken, path).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(jwt.issueAccessToken(manager, t.id, "manager"), path).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(jwt.issueAccessToken(worker, t.id, "worker"), path).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
