package com.traceability;

import com.traceability.analytics.AnalyticsFloorOverrides;
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
 * Analytics payout fixes (2026-11-27) — slice 1: the payout check shows Traced's net per payout and its
 * breakdown (COD collected, Bosta fees, negative deposits), never Bosta's batch total. Realistic
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
}
