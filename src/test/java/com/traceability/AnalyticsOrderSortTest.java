package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.ShipmentSettlement;
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

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Order finances sort (group C fix): GET /api/v1/analytics/orders and /orders/export.csv take
 * sort=placedAt|total|bostaFees|netToYou|status and dir=asc|desc (whitelist; anything else 400).
 * Orders without a value sort last in both directions; equal keys break by order id in the same
 * direction, so pages never repeat or skip a row. The default is placedAt desc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalyticsOrderSortTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    static Instant daysAgo(int d) {
        return Instant.now().minus(Duration.ofDays(d));
    }

    record O(UUID id, String number) {}

    final class T {
        final UUID id = UUID.randomUUID(), owner = UUID.randomUUID(), store = UUID.randomUUID(), product = UUID.randomUUID();
        final UUID variant = UUID.randomUUID();
        final String token;

        T() {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Sort')", id);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'O', ?, 'x', 'owner')",
                        owner, id, "o+" + owner + "@sort.test");
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                        "VALUES (?, ?, 'shopify', ?, 'connected', ?)", store, id, "sort-" + id + ".myshopify.com",
                        Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")));
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, 'Tee')", product, id, store, "P-" + product);
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title, price) VALUES (?, ?, ?, ?, 'S', 'S', 100)",
                        variant, id, product, "V-" + variant);
            token = jwt.issueAccessToken(owner, id, "owner");
        }

        /** An order with one line of {@code price}; with a fee, a delivered Bosta leg Bosta paid out. */
        O order(UUID orderId, int daysAgo, String price, String paidAmount, String fee) {
            String number = "#S" + SEQ.incrementAndGet();
            jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw, customer_name) " +
                        "VALUES (?, ?, ?, ?, ?, 'new'::order_status, ?, '{\"payment_gateway_names\":[\"Cash on Delivery (COD)\"]}'::jsonb, 'Mona Adel')",
                        orderId, id, store, "EXT-" + orderId, number, Timestamp.from(daysAgo(daysAgo)));
            long lineId = SEQ.incrementAndGet();
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id, raw) VALUES (?, ?, ?, 1, ?, ?::jsonb)",
                        id, orderId, variant, "gid://shopify/LineItem/" + lineId,
                        "{\"id\":" + lineId + ",\"price\":\"" + price + "\",\"quantity\":1,\"current_quantity\":1,\"discount_allocations\":[]}");
            if (fee != null) {
                UUID s = UUID.randomUUID();
                String raw = "{\"type\":{\"code\":10,\"value\":\"Send\"},\"cod\":" + price + ",\"shipmentFees\":50," +
                             "\"dropOffAddress\":{\"city\":{\"_id\":\"id-Cairo\",\"name\":\"Cairo\"}}}";
                jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state, shipment_leg, raw) " +
                            "VALUES (?, ?, ?, ?, 'delivered', 'forward', ?::jsonb)", s, id, orderId, "8" + SEQ.incrementAndGet(), raw);
                ShipmentSettlement.apply(jdbc, s, ShipmentSettlementTest.json(raw));
                jdbc.update("UPDATE shipments SET settlement_status = 'paid', cashout_txn_id = ?, deposited_amt = ?::numeric, " +
                            "bosta_fees = ?::numeric, deposited_at = ? WHERE id = ?", "TXN" + SEQ.incrementAndGet(), paidAmount, fee,
                            Timestamp.from(daysAgo(1)), s);
            }
            return new O(orderId, number);
        }
    }

    String period() {
        LocalDate today = LocalDate.now(CAIRO);
        return "from=" + today.minusDays(20) + "&to=" + today;
    }

    ResponseEntity<Map> get(T t, String q) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(t.token);
        return rest.exchange("http://localhost:" + port + "/api/v1/analytics/orders?" + period() + q, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    @SuppressWarnings("unchecked")
    List<String> names(T t, String q) {
        ResponseEntity<Map> r = get(t, q);
        assertThat(r.getStatusCode()).as("body %s", r.getBody()).isEqualTo(HttpStatus.OK);
        return ((List<Map<String, Object>>) r.getBody().get("orders")).stream().map(o -> (String) o.get("name")).toList();
    }

    /** Five orders: two paid (net known), three not yet delivered (net null); B and C share a total. */
    record Fixture(T t, O a, O b, O c, O d, O e) {}

    static UUID id(String first) {
        return UUID.fromString(first + UUID.randomUUID().toString().substring(1));
    }

    Fixture fixture() {
        T t = new T();
        // The first character fixes the id order (the tiebreak): c's id sorts before b's. The rest is random
        // so every test gets its own orders in the shared database.
        O a = t.order(id("a"), 5, "300.00", "250.00", "50.00");   // paid, net 250
        O b = t.order(id("b"), 4, "500.00", "440.00", "60.00");   // paid, net 440
        O c = t.order(id("1"), 3, "500.00", null, null);          // expected
        O d = t.order(id("d"), 2, "120.00", null, null);          // expected
        O e = t.order(id("e"), 1, "800.00", null, null);          // expected
        return new Fixture(t, a, b, c, d, e);
    }

    @Test
    void default_isNewestFirst_andEqualsExplicitPlacedAtDesc() {
        Fixture f = fixture();
        List<String> expected = List.of(f.e().number(), f.d().number(), f.c().number(), f.b().number(), f.a().number());
        assertThat(names(f.t(), "")).isEqualTo(expected);
        assertThat(names(f.t(), "&sort=placedAt&dir=desc")).isEqualTo(expected);
        assertThat(names(f.t(), "&sort=placedAt&dir=asc")).isEqualTo(expected.reversed());
    }

    @Test
    void total_bothDirections_equalTotalsBreakByIdInTheSameDirection() {
        Fixture f = fixture();
        // b and c are both 500: c's id (1…) sorts before b's (b…).
        assertThat(names(f.t(), "&sort=total&dir=asc"))
            .isEqualTo(List.of(f.d().number(), f.a().number(), f.c().number(), f.b().number(), f.e().number()));
        assertThat(names(f.t(), "&sort=total&dir=desc"))
            .isEqualTo(List.of(f.e().number(), f.b().number(), f.c().number(), f.a().number(), f.d().number()));
    }

    @Test
    void sortRunsAcrossAllPages_pagesNeverRepeatOrSkip() {
        Fixture f = fixture();
        List<String> all = names(f.t(), "&sort=total&dir=desc");
        List<String> paged = new ArrayList<>();
        for (int p = 0; p < 5; p++) paged.addAll(names(f.t(), "&sort=total&dir=desc&size=1&page=" + p));
        assertThat(paged).isEqualTo(all);
        assertThat(names(f.t(), "&sort=total&dir=desc&size=2&page=0")).isEqualTo(all.subList(0, 2)); // not "this page" only
    }

    @Test
    void netToYou_unknownNetSortsLastInBothDirections() {
        Fixture f = fixture();
        List<String> desc = names(f.t(), "&sort=netToYou&dir=desc");
        assertThat(desc.subList(0, 2)).isEqualTo(List.of(f.b().number(), f.a().number()));
        assertThat(desc.subList(2, 5)).containsExactly(f.e().number(), f.d().number(), f.c().number()); // null net, id desc
        List<String> asc = names(f.t(), "&sort=netToYou&dir=asc");
        assertThat(asc.subList(0, 2)).isEqualTo(List.of(f.a().number(), f.b().number()));
        assertThat(asc.subList(2, 5)).containsExactly(f.c().number(), f.d().number(), f.e().number()); // null net, id asc
        List<String> fees = names(f.t(), "&sort=bostaFees&dir=asc");
        assertThat(fees.subList(0, 2)).isEqualTo(List.of(f.a().number(), f.b().number()));
    }

    @Test
    void status_followsTheMoneyOrder_paidFirst() {
        Fixture f = fixture();
        List<String> asc = names(f.t(), "&sort=status&dir=asc");
        assertThat(asc.subList(0, 2)).containsExactlyInAnyOrder(f.a().number(), f.b().number());
    }

    @Test
    void export_usesTheSameSort() {
        Fixture f = fixture();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(f.t().token);
        ResponseEntity<byte[]> r = rest.exchange("http://localhost:" + port + "/api/v1/analytics/orders/export.csv?" + period()
            + "&sort=total&dir=asc", HttpMethod.GET, new HttpEntity<>(h), byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> lines = new String(r.getBody(), StandardCharsets.UTF_8).lines().skip(1).toList();
        assertThat(lines.stream().map(l -> l.split(",")[0]).toList())
            .isEqualTo(List.of(f.d().number(), f.a().number(), f.c().number(), f.b().number(), f.e().number()));
    }

    @Test
    void unknownSortOrDirection_is400() {
        Fixture f = fixture();
        for (String q : List.of("&sort=placed_at", "&sort=name", "&sort=total;drop", "&dir=up", "&sort=total&dir=DESC")) {
            assertThat(get(f.t(), q).getStatusCode()).as(q).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(f.t().token);
        assertThat(rest.exchange("http://localhost:" + port + "/api/v1/analytics/orders/export.csv?" + period() + "&sort=nope",
            HttpMethod.GET, new HttpEntity<>(h), byte[].class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
