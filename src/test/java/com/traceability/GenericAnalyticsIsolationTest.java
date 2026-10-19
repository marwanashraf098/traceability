package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.bosta.ShipmentSettlement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-Bosta couriers G2, item 6 — analytics never count a generic (non-Bosta courier) leg as Bosta.
 *
 * (a) Bosta isolation, golden: a Bosta tenant with no generic rows (AnalyticsGoldenTest's seed, plus piece
 *     trips) — every analytics and overview endpoint returns byte-identical JSON to the files in
 *     src/test/resources/generic-analytics-golden/. Those files were recorded on origin/main cd6441b, BEFORE
 *     the G2 analytics changes (-Dgolden.record=true re-records — only for an approved output change).
 * (b) A switched-on tenant ("Femine-like": Bosta account + generic legs): generic orders are in the existing
 *     'wijha' outcome ("Shipped outside Bosta"), never delivered / refused / in transit, never in a Bosta city,
 *     a Bosta fee, the not-fulfilled stage or a city success rate; a piece trip on a generic leg reads 'wijha'
 *     with no fee and no city. Its JSON is also written to target/generic-tenant/ for the report.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GenericAnalyticsIsolationTest {

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

    static final ZoneId CAIRO = AnalyticsGoldenTest.CAIRO;
    static final Instant NOW = AnalyticsGoldenTest.NOW;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, CAIRO);
        }
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @MockBean BostaV2Client bostaV2;

    static final Path GOLDEN = Path.of("src/test/resources/generic-analytics-golden");
    static final ObjectMapper JSON = new ObjectMapper();

    static boolean seeded;
    static String bostaToken, genericToken;
    static UUID bostaJ30, bostaJ32, genVariant;

    synchronized void seed() {
        if (seeded) return;
        AnalyticsGoldenTest outer = new AnalyticsGoldenTest();
        outer.jdbc = jdbc;
        outer.jwt = jwt;
        AnalyticsGoldenTest.Seed s = outer.new Seed();
        s.build();
        bostaToken = s.ownerToken;
        bostaJ30 = jdbc.queryForObject("SELECT v.id FROM variants v WHERE v.tenant_id = ? AND v.sku = 'J-30'", UUID.class, s.tenant);
        bostaJ32 = jdbc.queryForObject("SELECT v.id FROM variants v WHERE v.tenant_id = ? AND v.sku = 'J-32'", UUID.class, s.tenant);
        bostaTrips(s.tenant);
        new GenericSeed().build();
        seeded = true;
    }

    /** Two pieces with trips on the Bosta tenant's legs (delivered, refused) — the piece-history golden. */
    void bostaTrips(UUID tenant) {
        List<Map<String, Object>> legs = jdbc.queryForList(
            "SELECT s.id, s.order_id, s.internal_state::text AS st FROM shipments s WHERE s.tenant_id = ? " +
            "AND s.shipment_leg = 'forward' AND s.internal_state IN ('delivered', 'returned') " +
            "ORDER BY s.created_at, s.tracking_number", tenant);
        Map<String, Object> delivered = legs.stream().filter(l -> "delivered".equals(l.get("st"))).findFirst().orElseThrow();
        Map<String, Object> returned = legs.stream().filter(l -> "returned".equals(l.get("st"))).findFirst().orElseThrow();
        Timestamp received = Timestamp.from(AnalyticsGoldenTest.NOW.minusSeconds(60 * 86400L));
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, created_at) VALUES " +
                    "('GTRIP1', ?, ?, 'PC-GT1', 'GT001', 'delivered', ?), ('GTRIP2', ?, ?, 'PC-GT2', 'GT002', 'available', ?)",
                    tenant, bostaJ30, received, tenant, bostaJ30, received);
        tripEvent(tenant, "GTRIP1", "packed", "with_courier", delivered, AnalyticsGoldenTest.NOW.minusSeconds(40 * 86400L));
        tripEvent(tenant, "GTRIP1", "with_courier", "delivered", delivered, AnalyticsGoldenTest.NOW.minusSeconds(39 * 86400L));
        tripEvent(tenant, "GTRIP2", "awaiting_pickup", "with_courier", returned, AnalyticsGoldenTest.NOW.minusSeconds(30 * 86400L));
    }

    void tripEvent(UUID tenant, String piece, String from, String to, Map<String, Object> leg, Instant at) {
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, shipment_id, from_status, to_status, occurred_at) " +
                    "VALUES (?, ?, 'courier_update', ?, ?, ?::piece_status, ?::piece_status, ?)",
                    tenant, piece, leg.get("order_id"), leg.get("id"), from, to, Timestamp.from(at));
    }

    static final String SEPT = AnalyticsGoldenTest.SEPT;

    /** Every analytics + overview GET for the Bosta tenant: AnalyticsGoldenTest's list plus the rest. */
    Map<String, String> bostaEndpoints() {
        Map<String, String> m = new LinkedHashMap<>(AnalyticsGoldenTest.ENDPOINTS);
        for (String[] p : new String[][] {{"sept", SEPT}, {"30d", "period=30d"}}) {
            String k = p[0], q = p[1];
            m.put("customers-summary-" + k, "/api/v1/analytics/customers/summary?" + q);
            m.put("profit-summary-" + k, "/api/v1/analytics/profit/summary?" + q);
            m.put("profit-by-product-type-" + k, "/api/v1/analytics/profit/by-product-type?" + q);
            m.put("profit-skus-" + k, "/api/v1/analytics/profit/skus?" + q);
            m.put("orders-" + k, "/api/v1/analytics/orders?size=200&" + q);
            m.put("orders-export-" + k, "/api/v1/analytics/orders/export.csv?" + q);
            m.put("alerts-" + k, "/api/v1/analytics/alerts?" + q);
            m.put("stock-summary-" + k, "/api/v1/analytics/stock/summary?" + q);
            m.put("sales-variants-daily-" + k, "/api/v1/analytics/sales/variants/daily?ids=" + bostaJ30 + "," + bostaJ32 + "&" + q);
        }
        m.put("customers-top", "/api/v1/analytics/customers/top");
        m.put("customers-by-governorate", "/api/v1/analytics/customers/by-governorate");
        m.put("customers-cohorts", "/api/v1/analytics/customers/cohorts");
        m.put("customers-watch", "/api/v1/analytics/customers/watch");
        m.put("cash-forecast", "/api/v1/analytics/cash-forecast");
        m.put("stock-variants", "/api/v1/analytics/stock/variants");
        m.put("stock-restock", "/api/v1/analytics/stock/restock");
        m.put("settings", "/api/v1/analytics/settings");
        m.put("pieces", "/api/v1/analytics/pieces?minTrips=1");
        m.put("piece-history-trip1", "/api/v1/analytics/pieces/GTRIP1/history");
        m.put("piece-history-trip2", "/api/v1/analytics/pieces/GTRIP2/history");
        m.put("variant-pieces-j30", "/api/v1/analytics/variants/" + bostaJ30 + "/pieces");
        m.put("variant-orders-j30", "/api/v1/analytics/variants/" + bostaJ30 + "/orders");
        m.put("inventory-sync-status", "/api/v1/analytics/inventory-sync/status");
        m.put("overview-trends", "/api/v1/overview/trends");
        m.put("overview-trends-sept", "/api/v1/overview/trends?from=2026-09-01&to=2026-09-30");
        m.put("overview-late-to-pack", "/api/v1/overview/late-to-pack");
        m.put("overview-top-skus", "/api/v1/overview/top-skus");
        return m;
    }

    String get(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<String> r = rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(r.getStatusCode()).as("%s: %s", path, r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    JsonNode json(String token, String path) throws Exception {
        return JSON.readTree(get(token, path));
    }

    // ── (a) Bosta tenant: byte-identical to the pre-G2 recording ───────────────────────────────────

    @Test
    void bostaTenant_everyAnalyticsAndOverviewEndpoint_matchesThePreG2Json() throws Exception {
        seed();
        boolean record = Boolean.getBoolean("golden.record");
        Map<String, String> endpoints = bostaEndpoints();
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, String> e : endpoints.entrySet()) {
            String body = get(bostaToken, e.getValue()) + "\n";
            Path f = GOLDEN.resolve(e.getKey() + ".json");
            if (record) {
                Files.createDirectories(GOLDEN);
                Files.writeString(f, body, StandardCharsets.UTF_8);
            } else {
                assertThat(f).as("golden file %s (record with -Dgolden.record=true)", f).exists();
                if (!Files.readString(f, StandardCharsets.UTF_8).equals(body)) {
                    mismatches.add(e.getKey());
                    Path actual = Path.of("target/generic-golden-actual").resolve(e.getKey() + ".json");
                    Files.createDirectories(actual.getParent());
                    Files.writeString(actual, body, StandardCharsets.UTF_8);
                }
            }
        }
        assertThat(record).as("recorded %d golden files — re-run without -Dgolden.record", endpoints.size()).isFalse();
        assertThat(endpoints).hasSize(97);
        assertThat(mismatches).as("endpoints whose JSON changed for a Bosta tenant").isEmpty();
    }

    // ── (b) Switched-on tenant: generic legs never count as Bosta ──────────────────────────────────

    final class GenericSeed {
        int n = 0;
        final UUID tenant = id(), owner = id(), store = id();
        int tn = 7700000;

        UUID id() {
            return UUID.nameUUIDFromBytes(("generic-analytics-" + (n++)).getBytes(StandardCharsets.UTF_8));
        }

        Instant cairo(int m, int d, int h) {
            return LocalDateTime.of(2026, m, d, h, 0).atZone(CAIRO).toInstant();
        }

        UUID order(String number, Instant placed, String carrierClass, String carrierName) {
            UUID o = id();
            jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw, " +
                        "shipping_carrier_class, shipping_carrier_name) VALUES (?, ?, ?, ?, ?, 'new', ?, ?::jsonb, ?, ?)",
                        o, tenant, store, "gid://shopify/Order/" + o, number, Timestamp.from(placed),
                        "{\"source_name\":\"web\",\"payment_gateway_names\":[\"Cash on Delivery (COD)\"],\"shipping_address\":{\"province_code\":\"C\"}}",
                        carrierClass, carrierName);
            jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity, external_id, raw) " +
                        "VALUES (?, ?, ?, ?, 1, ?, ?::jsonb)", id(), tenant, o, genVariant, "gid://shopify/LineItem/" + (++tn),
                        "{\"id\":" + tn + ",\"price\":\"1000.00\",\"quantity\":1,\"current_quantity\":1,\"discount_allocations\":[]}");
            return o;
        }

        UUID bostaLeg(UUID order, String state, int code, Instant created) {
            UUID s = id();
            String raw = "{\"type\":{\"code\":10,\"value\":\"Send\"},\"state\":{\"code\":" + code + "},\"cod\":1000,\"shipmentFees\":60," +
                         "\"dropOffAddress\":{\"city\":{\"_id\":\"g-cairo\",\"name\":\"Cairo\"}}}";
            jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw, " +
                        "provider_state, created_at) VALUES (?, ?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward', ?::jsonb, ?, ?)",
                        s, tenant, order, String.valueOf(++tn), state, raw, code, Timestamp.from(created));
            ShipmentSettlement.apply(jdbc, s, ShipmentSettlementTest.json(raw));
            return s;
        }

        UUID genericLeg(UUID order, String tracking, String carrier, String state, Instant created) {
            UUID s = id();
            jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, tracking_key, carrier_name, " +
                        "source, shopify_fulfillment_id, internal_state, shipment_leg, created_at) VALUES " +
                        "(?, ?, ?, 'generic', ?, ?, ?, 'shopify_fulfillment', ?, ?::shipment_internal_state, 'forward', ?)",
                        s, tenant, order, tracking, tracking.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT), carrier,
                        "gid://shopify/Fulfillment/" + s, state, Timestamp.from(created));
            return s;
        }

        void history(UUID s, String state, Instant at) {
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) " +
                        "VALUES (?, ?, ?::shipment_internal_state, ?)", tenant, s, state, Timestamp.from(at));
        }

        void build() {
            jdbc.update("INSERT INTO tenants (id, name, generic_courier_enabled) VALUES (?, 'Femine-like', true)", tenant);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', " +
                        "'generic-analytics-owner@g2.test', 'x', 'owner')", owner, tenant);
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                        "VALUES (?, ?, 'shopify', 'femine-like.myshopify.com', 'connected', ?)", store, tenant,
                        Timestamp.from(cairo(6, 1, 0)));
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                        "VALUES (?, 'bosta', 'x', 'generic-analytics-h', 'active')", tenant);
            genericToken = jwt.issueAccessToken(owner, tenant, "owner");
            UUID product = id();
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, raw) VALUES (?, ?, ?, ?, 'Dress', " +
                        "'{\"product_type\":\"Dresses\"}'::jsonb)", product, tenant, store, "P-" + product);
            genVariant = id();
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title, price, raw) " +
                        "VALUES (?, ?, ?, ?, 'D-1', 'M', 1000.00, '{}'::jsonb)", genVariant, tenant, product, "V-" + genVariant);

            // Bosta, delivered (the only Bosta-decided order in September).
            UUID b1 = order("#F1", cairo(9, 10, 12), "bosta", "Bosta");
            UUID b1s = bostaLeg(b1, "delivered", 45, cairo(9, 10, 13));
            jdbc.update("UPDATE shipments SET delivered_at = ? WHERE id = ?", Timestamp.from(cairo(9, 12, 12)), b1s);
            history(b1s, "with_courier", cairo(9, 11, 9));
            history(b1s, "delivered", cairo(9, 12, 12));
            // Generic, delivered — Wijha (other_known).
            UUID g1 = order("#F2", cairo(9, 11, 12), "other_known", "Wijha");
            UUID g1s = genericLeg(g1, "WJ-1001", "Wijha", "delivered", cairo(9, 11, 13));
            history(g1s, "with_courier", cairo(9, 12, 9));
            history(g1s, "delivered", cairo(9, 13, 12));
            // Generic, with the carrier — an unnamed carrier (unknown class).
            UUID g2 = order("#F3", cairo(9, 12, 12), "unknown", "Other");
            UUID g2s = genericLeg(g2, "QP-2002", "Other", "with_courier", cairo(9, 12, 13));
            history(g2s, "with_courier", cairo(9, 13, 9));
            // Generic, came back.
            UUID g3 = order("#F4", cairo(9, 13, 12), "other_known", "Mylerz");
            UUID g3s = genericLeg(g3, "MY3003", "Mylerz", "returned", cairo(9, 13, 13));
            history(g3s, "with_courier", cairo(9, 14, 9));
            history(g3s, "returning", cairo(9, 15, 9));
            // Open window (now − 2 days): one Bosta 'created', one generic 'created' (unknown class).
            UUID b2 = order("#F5", NOW.minusSeconds(2 * 86400L), "bosta", "Bosta");
            bostaLeg(b2, "created", 10, NOW.minusSeconds(2 * 86400L - 3600));
            UUID g4 = order("#F6", NOW.minusSeconds(2 * 86400L + 600), "unknown", "Other");
            genericLeg(g4, "JT0004", "Other", "created", NOW.minusSeconds(2 * 86400L - 3000));
            // A piece trip on the generic delivered leg.
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, created_at) VALUES " +
                        "('GENTRIP1', ?, ?, 'PC-GEN1', 'GE001', 'delivered', ?)", tenant, genVariant, Timestamp.from(cairo(8, 1, 12)));
            jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, shipment_id, from_status, to_status, occurred_at) " +
                        "VALUES (?, 'GENTRIP1', 'courier_update', ?, ?, 'packed', 'with_courier', ?)",
                        tenant, g1, g1s, Timestamp.from(cairo(9, 12, 9)));
        }
    }

    @Test
    void switchedOnTenant_genericLegsNeverCountAsBosta() throws Exception {
        seed();
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("revenue-summary", "/api/v1/analytics/revenue/summary?" + SEPT);
        paths.put("sales-cities", "/api/v1/analytics/sales/cities?" + SEPT);
        paths.put("delivery-summary", "/api/v1/analytics/delivery/summary?" + SEPT);
        paths.put("orders", "/api/v1/analytics/orders?size=200&" + SEPT);
        paths.put("money-pipeline", "/api/v1/analytics/money/pipeline?period=30d");
        paths.put("money-fees", "/api/v1/analytics/money/fees?" + SEPT);
        paths.put("profit-summary", "/api/v1/analytics/profit/summary?" + SEPT);
        paths.put("piece-history", "/api/v1/analytics/pieces/GENTRIP1/history");
        Map<String, JsonNode> out = new LinkedHashMap<>();
        Path dump = Path.of("target/generic-tenant");
        Files.createDirectories(dump);
        for (Map.Entry<String, String> e : paths.entrySet()) {
            String body = get(genericToken, e.getValue());
            Files.writeString(dump.resolve(e.getKey() + ".json"), body + "\n", StandardCharsets.UTF_8);
            out.put(e.getKey(), JSON.readTree(body));
        }
        if (Boolean.getBoolean("generic.dumpOnly")) return;
        GenericAssertions.check(out);
    }

    /** The (b) assertions — in their own class so the pre-G2 dump run (-Dgeneric.dumpOnly=true) can skip them. */
    static final class GenericAssertions {
        static void check(Map<String, JsonNode> out) {
            // filled in after the pre-G2 dump showed the response shapes
        }
    }

    static BigDecimal dec(JsonNode n) {
        return n == null || n.isNull() ? null : new BigDecimal(n.asText());
    }
}
