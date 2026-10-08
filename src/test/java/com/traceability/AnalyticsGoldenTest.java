package com.traceability;

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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Analytics slice 8 — golden test: every analytics endpoint, on one seeded tenant with fixed ids,
 * absolute dates and a frozen clock, must return byte-identical JSON to the recorded files in
 * src/test/resources/analytics-golden/. The files were recorded from the queries BEFORE slice 8
 * moved them onto generated columns (run with -Dgolden.record=true to re-record — only when an
 * output change is intended and approved).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalyticsGoldenTest {

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
    static final Instant NOW = LocalDateTime.of(2026, 10, 8, 12, 0).atZone(CAIRO).toInstant();

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
    @MockBean BostaV2Client bostaV2;      // no Bosta reference-data fetch at startup — districts are seeded here

    static final Path GOLDEN = Path.of("src/test/resources/analytics-golden");

    static final String SEPT = "from=2026-09-01&to=2026-09-30";
    static final String YEAR = "from=2025-10-08&to=2026-10-08";

    static final Map<String, String> ENDPOINTS = new LinkedHashMap<>();
    static {
        for (String[] p : new String[][] {{"sept", SEPT}, {"year", YEAR}, {"30d", "period=30d"}}) {
            String k = p[0], q = p[1];
            ENDPOINTS.put("sales-variants-" + k, "/api/v1/analytics/sales/variants?" + q);
            ENDPOINTS.put("sales-products-units-" + k, "/api/v1/analytics/sales/products?" + q);
            ENDPOINTS.put("sales-products-revenue-" + k, "/api/v1/analytics/sales/products?sort=revenue&" + q);
            ENDPOINTS.put("sales-cities-" + k, "/api/v1/analytics/sales/cities?" + q);
            ENDPOINTS.put("money-pipeline-" + k, "/api/v1/analytics/money/pipeline?" + q);
            ENDPOINTS.put("money-fees-" + k, "/api/v1/analytics/money/fees?" + q);
            ENDPOINTS.put("money-fees-extra-awb-" + k, "/api/v1/analytics/money/fees/extra?groupBy=awb&" + q);
            ENDPOINTS.put("money-fees-extra-sku-" + k, "/api/v1/analytics/money/fees/extra?groupBy=sku&" + q);
            ENDPOINTS.put("money-payouts-" + k, "/api/v1/analytics/money/payouts?" + q);
            ENDPOINTS.put("revenue-summary-" + k, "/api/v1/analytics/revenue/summary?" + q);
            for (String by : List.of("channel", "payment", "governorate", "productType")) {
                ENDPOINTS.put("revenue-breakdown-" + by + "-" + k, "/api/v1/analytics/revenue/breakdown?by=" + by + "&" + q);
            }
            ENDPOINTS.put("revenue-discounts-" + k, "/api/v1/analytics/revenue/discounts?" + q);
            ENDPOINTS.put("revenue-heatmap-" + k, "/api/v1/analytics/revenue/heatmap?" + q);
            ENDPOINTS.put("delivery-summary-" + k, "/api/v1/analytics/delivery/summary?" + q);
            ENDPOINTS.put("delivery-failure-reasons-" + k, "/api/v1/analytics/delivery/failure-reasons?" + q);
            ENDPOINTS.put("products-extras-" + k, "/api/v1/analytics/products/extras?" + q);
        }
        ENDPOINTS.put("money-stuck", "/api/v1/analytics/money/stuck");
        // A year against the year before only on request (> 92 days, slice 8).
        ENDPOINTS.put("revenue-summary-year-compare", "/api/v1/analytics/revenue/summary?compare=true&" + YEAR);
        ENDPOINTS.put("delivery-summary-year-compare", "/api/v1/analytics/delivery/summary?compare=true&" + YEAR);
        ENDPOINTS.put("products-extras-year-compare", "/api/v1/analytics/products/extras?compare=true&" + YEAR);
    }

    @Test
    void everyAnalyticsEndpoint_matchesTheRecordedJson() throws Exception {
        Seed s = new Seed();
        s.build();
        boolean record = Boolean.getBoolean("golden.record");
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, String> e : ENDPOINTS.entrySet()) {
            HttpHeaders h = new HttpHeaders();
            h.setBearerAuth(s.ownerToken);
            ResponseEntity<String> r = rest.exchange("http://localhost:" + port + e.getValue(), HttpMethod.GET,
                new HttpEntity<>(h), String.class);
            assertThat(r.getStatusCode()).as("%s: %s", e.getKey(), r.getBody()).isEqualTo(HttpStatus.OK);
            String body = r.getBody() + "\n";               // the raw body: byte-identical means these bytes
            Path f = GOLDEN.resolve(e.getKey() + ".json");
            if (record) {
                Files.createDirectories(GOLDEN);
                Files.writeString(f, body, StandardCharsets.UTF_8);
            } else {
                assertThat(f).as("golden file %s (record with -Dgolden.record=true)", f).exists();
                if (!Files.readString(f, StandardCharsets.UTF_8).equals(body)) {
                    mismatches.add(e.getKey());
                    Path actual = Path.of("target/golden-actual").resolve(e.getKey() + ".json");
                    Files.createDirectories(actual.getParent());
                    Files.writeString(actual, body, StandardCharsets.UTF_8);
                }
            }
        }
        assertThat(record).as("recorded %d golden files — re-run without -Dgolden.record", ENDPOINTS.size()).isFalse();
        assertThat(mismatches).as("endpoints whose JSON changed").isEmpty();
    }

    /** One tenant with every shape the analytics read. Fixed ids (name-based), absolute dates. */
    final class Seed {
        int n = 0;
        final UUID tenant = id(), owner = id(), store = id();
        String ownerToken;

        UUID id() {
            return UUID.nameUUIDFromBytes(("golden-" + (n++)).getBytes(StandardCharsets.UTF_8));
        }

        Instant cairo(int m, int d, int h, int min) {
            return LocalDateTime.of(2026, m, d, h, min).atZone(CAIRO).toInstant();
        }

        Timestamp ts(Instant i) {
            return Timestamp.from(i);
        }

        UUID product(String title, String raw) {
            UUID p = id();
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, image_url, raw) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)", p, tenant, store, "P-" + p, title, "https://img/" + title, raw);
            return p;
        }

        UUID variant(UUID product, String title, String sku, String price, String raw) {
            UUID v = id();
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title, price, raw) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?::numeric, ?::jsonb)", v, tenant, product, "V-" + v, sku, title, price, raw);
            return v;
        }

        UUID order(Instant placed, String status, String carrier, String raw) {
            UUID o = id();
            jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw, " +
                        "shipping_carrier_class) VALUES (?, ?, ?, ?, ?, ?::order_status, ?, ?::jsonb, ?)",
                        o, tenant, store, "EXT-" + o, "#G" + n, status, ts(placed), raw, carrier);
            return o;
        }

        long lineId = 9000;

        UUID line(UUID order, UUID variant, long li, int qty, int current, String price, String allocations) {
            UUID oi = id();
            String raw = price == null ? "{\"id\":" + li + ",\"quantity\":" + qty + "}"
                : "{\"id\":" + li + ",\"price\":\"" + price + "\",\"quantity\":" + qty + ",\"current_quantity\":" + current +
                  ",\"discount_allocations\":" + (allocations == null ? "[]" : allocations) + "}";
            jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity, external_id, raw) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)", oi, tenant, order, variant, qty, "gid://shopify/LineItem/" + li, raw);
            return oi;
        }

        UUID line(UUID order, UUID variant, int qty, String price) {
            return line(order, variant, ++lineId, qty, qty, price, null);
        }

        int tn = 5500000;

        UUID leg(UUID order, String leg, String state, String raw, Instant created) {
            UUID s = id();
            jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state, shipment_leg, raw, " +
                        "provider_state, created_at) VALUES (?, ?, ?, ?, ?::shipment_internal_state, ?, ?::jsonb, " +
                        "(?::jsonb #>> '{state,code}')::int, ?)", s, tenant, order, String.valueOf(++tn), state, leg, raw, raw, ts(created));
            ShipmentSettlement.apply(jdbc, s, ShipmentSettlementTest.json(raw));
            return s;
        }

        void history(UUID s, String state, Instant at) {
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) " +
                        "VALUES (?, ?, ?::shipment_internal_state, ?)", tenant, s, state, ts(at));
        }

        String city(String id, String name) {
            return "\"dropOffAddress\":{\"city\":{\"_id\":\"" + id + "\",\"name\":\"" + name + "\"}}";
        }

        String send(int state, String cityId, String cityName, String extra) {
            return "{\"type\":{\"code\":10,\"value\":\"Send\"},\"state\":{\"code\":" + state + "},\"cod\":500,\"shipmentFees\":50," +
                   city(cityId, cityName) + (extra == null ? "" : "," + extra) + "}";
        }

        void build() {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Golden')", tenant);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', " +
                        "'golden-owner@an8.test', 'x', 'owner')", owner, tenant);
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                        "VALUES (?, ?, 'shopify', 'golden.myshopify.com', 'connected', ?)", store, tenant,
                        ts(cairo(6, 1, 0, 0)));
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                        "VALUES (?, 'bosta', 'x', 'golden-h', 'active')", tenant);
            jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, district_name) VALUES " +
                        "('g-d1', 'g-cairo', 'Cairo', 'القاهرة', 'Maadi'), ('g-d2', 'g-giza', 'Giza', 'الجيزة', 'Dokki'), " +
                        "('g-d3', 'g-alx', 'Alexandria', 'الإسكندرية', 'Smouha') ON CONFLICT DO NOTHING");
            ownerToken = jwt.issueAccessToken(owner, tenant, "owner");

            UUID jeans = product("Jeans", "{\"product_type\":\"Jeans\",\"options\":[{\"name\":\"Size\",\"position\":1}]}");
            UUID j30 = variant(jeans, "30", "J-30", "800.00", "{\"option1\":\"30\"}");
            UUID j32 = variant(jeans, "32", "J-32", "800.00", "{\"option1\":\"32\"}");
            UUID jxl = variant(jeans, "XL-XXL", "J-XLXXL", "850.00", "{\"option1\":\"XL-XXL\"}");
            UUID shoe = product("Shoe", "{\"product_type\":\"Shoes\"}");
            UUID s38 = variant(shoe, "Black / 38", "S-38", "1200.00", "{}");
            UUID s39 = variant(shoe, "Black / 39", "S-39", "1200.00", "{}");
            UUID tee = product("Tee", "{\"product_type\":\" \",\"options\":[{\"name\":\"Size\",\"position\":1}]}");
            UUID tm = variant(tee, "m", "T-M", "300.00", "{\"option1\":\"m\"}");
            UUID t2xl = variant(tee, "2XL", "T-2XL", "300.00", "{\"option1\":\"2XL\"}");
            UUID gql = product("Gql Hoodie", "{\"productType\":\"Hoodies\"}");
            UUID gh = variant(gql, "Default", "G-1", "650.00", "{}");

            String codeApps = "\"discount_applications\":[{\"type\":\"discount_code\",\"code\":\"save10\"}," +
                              "{\"type\":\"automatic\",\"title\":\"B2G1\"},{\"type\":\"manual\",\"title\":\"\"}]";

            // ── September: one order per outcome / channel / payment shape ────────
            for (int i = 0; i < 24; i++) {
                int day = 1 + (i % 28);
                int hour = (i * 5) % 24;
                Instant placed = cairo(9, day, hour, 15);
                String[] channels = {
                    "\"source_name\":\"web\",\"referring_site\":\"https://instagram.com/\",\"landing_site\":\"/?utm_source=facebook\"",
                    "\"source_name\":\"web\",\"referring_site\":null,\"landing_site\":\"/?utm_source=faceb\"",
                    "\"source_name\":\"shopify_draft_order\"",
                    "\"source_name\":\"web\",\"referring_site\":\"https://www.google.com/\"",
                    "\"source_name\":\"web\",\"referring_site\":\"https://l.wl.co/x\"",
                    "\"source_name\":\"web\",\"referring_site\":\"https://golden.myshopify.com/cart\"",
                };
                String[] payments = {"[\"Cash on Delivery (COD)\"]", "[\"Paymob - Native Checkout for Debit/Credit Cards\",\"Paymob\"]",
                                     "[\"manual\"]", "[\"Cash on Delivery (COD)\",\"manual\"]", "[]"};
                String[] provinces = {"C", "GZ", "ALX", "XX"};
                String fulfilledLine = String.valueOf(lineId + 1);
                boolean fulfilled = i % 3 == 0;
                boolean refund = i % 4 == 0;
                String raw = "{" + channels[i % channels.length] + ",\"payment_gateway_names\":" + payments[i % payments.length] +
                    ",\"shipping_address\":{\"province_code\":\"" + provinces[i % provinces.length] + "\"}," + codeApps +
                    (fulfilled ? ",\"fulfillments\":[{\"created_at\":\"2026-09-" + String.format("%02d", day) +
                        "T20:00:00+03:00\",\"status\":\"success\",\"line_items\":[{\"id\":" + fulfilledLine + "}]}]" : "") +
                    (refund ? ",\"refunds\":[{\"created_at\":\"2026-09-" + String.format("%02d", Math.min(day + 1, 30)) +
                        "T10:00:00+03:00\",\"refund_line_items\":[{\"line_item_id\":" + fulfilledLine +
                        ",\"quantity\":1,\"restock_type\":\"" + (i % 8 == 0 ? "return" : "no_restock") + "\"}]}]" : "") +
                    (i == 23 ? ",\"cancelled_at\":\"2026-09-30T10:00:00+03:00\"" : "") + "}";
                String carrier = i % 7 == 5 ? "other_known" : i % 7 == 6 ? null : "bosta";
                UUID o = order(placed, i == 22 ? "cancelled" : "new", carrier, raw);
                UUID[] vs = {j30, j32, jxl, s38, s39, tm, t2xl, gh};
                UUID v = vs[i % vs.length];
                String price = v.equals(gh) ? null : (i % 2 == 0 ? "800.00" : "300.00");
                int qty = 1 + (i % 3);
                line(o, v, ++lineId, qty, refund ? qty - 1 : qty, price,
                     "[{\"amount\":\"" + (10 + i) + ".00\",\"discount_application_index\":" + (i % 3) + "}]");
                if (i % 2 == 1) line(o, vs[(i + 3) % vs.length], 1, "1200.00");      // a second line: bought together
                if (i % 5 == 0) line(o, j32, 1, "800.00");
                if (carrier == null || "other_known".equals(carrier)) continue;
                String[] cities = {"g-cairo", "g-giza", "g-alx", null};
                String[] names = {"Cairo", "Giza", "Alexandria", null};
                String cid = cities[i % 4], cname = names[i % 4];
                String cityPart = cid == null ? "\"dropOffAddress\":{}" : city(cid, cname);
                Instant created = placed.plusSeconds(3600);
                switch (i % 6) {
                    case 0 -> {   // delivered, collected + delivered, settled and paid
                        UUID s = leg(o, "forward", "delivered", "{\"type\":{\"code\":10,\"value\":\"Send\"},\"state\":{\"code\":45}," +
                            "\"cod\":900,\"shipmentFees\":80," + cityPart + ",\"collectedFromBusiness\":\"" +
                            placed.plusSeconds(6 * 3600) + "\",\"wallet\":{\"cashCycle\":{\"_id\":" + (8000 + i) +
                            ",\"deposited_at\":\"2026-09-" + String.format("%02d", Math.min(day + 2, 30)) + "T08:00:00Z\"," +
                            "\"deposited_amt\":\"808.80\",\"cod\":\"900.00\",\"bosta_fees\":\"91.20\",\"shipping_fees\":\"80.00\"," +
                            "\"vat\":\"11.20\"},\"cashout\":{\"transaction_id\":\"WEDCOD16SEP26\",\"amount\":\"5000.00\"}}}", created);
                        jdbc.update("UPDATE shipments SET delivered_at = ? WHERE id = ?", ts(placed.plusSeconds(30 * 3600)), s);
                        history(s, "with_courier", placed.plusSeconds(6 * 3600));
                        history(s, "delivered", placed.plusSeconds(30 * 3600));
                    }
                    case 1 -> {   // refused (Return to Origin) with a reason, deposited (negative)
                        UUID s = leg(o, "forward", "returned", "{\"type\":{\"code\":20,\"value\":\"Return to Origin\"},\"state\":{\"code\":46}," +
                            "\"shipmentFees\":68," + cityPart + ",\"wallet\":{\"cashCycle\":{\"_id\":" + (8000 + i) +
                            ",\"deposited_at\":\"2026-09-25T08:00:00Z\",\"deposited_amt\":-77.52,\"cod\":\"0.00\",\"bosta_fees\":\"77.52\"," +
                            "\"shipping_fees\":\"68.00\",\"vat\":\"9.52\"},\"cashout\":{\"next_cashout_date\":\"2026-10-14T00:00:00Z\"}}}", created);
                        jdbc.update("UPDATE shipments SET returned_at = ?, last_failure_reason = ? WHERE id = ?",
                            ts(placed.plusSeconds(50 * 3600)), "Cancellation - the customer refuses to receive the shipment.", s);
                        history(s, "with_courier", placed.plusSeconds(20 * 3600));
                        history(s, "returning", placed.plusSeconds(40 * 3600));
                    }
                    case 2 -> {   // in transit, picked up via history only
                        UUID s = leg(o, "forward", "with_courier", send(24, cid, cname, null), created);
                        history(s, "with_courier", placed.plusSeconds(26 * 3600));
                    }
                    case 3 -> {   // lost, reason on the exception only
                        UUID s = leg(o, "forward", "lost", send(100, cid, cname, null), created);
                        jdbc.update("UPDATE shipments SET exception_reason = ? WHERE id = ?",
                            "Postponed - the customer requested postponement for another day.", s);
                    }
                    case 4 -> {   // delivered (estimate, not settled), then a customer return pickup leg
                        UUID s = leg(o, "forward", "delivered", send(45, cid, cname, null), created);
                        jdbc.update("UPDATE shipments SET delivered_at = ? WHERE id = ?", ts(placed.plusSeconds(52 * 3600)), s);
                        history(s, "with_courier", placed.plusSeconds(28 * 3600));
                        UUID crp = leg(o, "return", "delivered", "{\"type\":{\"code\":25},\"state\":{\"code\":46},\"shipmentFees\":93}",
                            placed.plusSeconds(5 * 86400));
                        jdbc.update("UPDATE shipments SET delivered_at = ? WHERE id = ?", ts(placed.plusSeconds(7 * 86400)), crp);
                    }
                    default -> {  // booked, never picked up
                        leg(o, "forward", "created", send(10, cid, cname, null), created);
                    }
                }
            }

            // A portal refund return + a dashboard exchange on delivered orders, and a scanned piece return.
            UUID ro = order(cairo(9, 12, 13, 0), "new", "bosta", "{\"source_name\":\"web\"}");
            UUID roi = line(ro, tm, ++lineId, 2, 2, "300.00", null);
            UUID rs = leg(ro, "forward", "delivered", send(45, "g-cairo", "Cairo", null), cairo(9, 12, 14, 0));
            history(rs, "delivered", cairo(9, 14, 10, 0));
            String piece = "GPIECE1";
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) VALUES (?, ?, ?, 'PC-G1', 'G0001', 'return_pending_inspection')",
                        piece, tenant, tm);
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')", tenant, roi, piece);
            jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, from_status, to_status, metadata) " +
                        "VALUES (?, ?, 'return_received', ?, 'delivered', 'return_pending_inspection', '{\"return_kind\":\"customer_after_delivery\"}'::jsonb)",
                        tenant, piece, ro);
            UUID req = id();
            jdbc.update("INSERT INTO return_requests (id, tenant_id, order_id, type, status, reference) VALUES (?, ?, ?, 'refund', 'received', 'RR-G2LDEN')",
                        req, tenant, ro);
            jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, order_item_id, unit_no, variant_id, reason_code, active, item_status) " +
                        "VALUES (?, ?, ?, 2, ?, 'wrong_size', true, 'arrived')", tenant, req, roi, tm);
            jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, inbound_variant_id, matched_order_id, raw) " +
                        "VALUES (?, 'GEX1', 'matched', ?, ?, '{}'::jsonb)", tenant, j32, ro);

            // ── August: the previous period ────────────────────────────────────────
            for (int i = 0; i < 6; i++) {
                UUID o = order(cairo(8, 5 + i * 4, 11, 0), "new", "bosta", "{\"source_name\":\"web\",\"payment_gateway_names\":[\"Cash on Delivery (COD)\"]}");
                line(o, i % 2 == 0 ? j30 : s38, 1, "800.00");
                UUID s = leg(o, "forward", i < 4 ? "delivered" : "returned",
                    i < 4 ? send(45, "g-cairo", "Cairo", null) : "{\"type\":{\"code\":20},\"state\":{\"code\":46}," + city("g-giza", "Giza") + "}",
                    cairo(8, 5 + i * 4, 12, 0));
                jdbc.update("UPDATE shipments SET delivered_at = ? WHERE id = ? AND internal_state = 'delivered'",
                    ts(cairo(8, 6 + i * 4, 12, 0)), s);
            }

            // ── Pre-floor (May) — never counted ────────────────────────────────────
            UUID pre = order(cairo(5, 20, 12, 0), "new", "bosta", "{}");
            line(pre, j30, 3, "800.00");
            leg(pre, "forward", "delivered", send(45, "g-cairo", "Cairo", null), cairo(5, 20, 13, 0));

            // ── Stuck / not-paid, relative to the frozen clock ─────────────────────
            UUID st1 = order(NOW.minusSeconds(12 * 86400), "new", "bosta", "{}");
            line(st1, s39, 1, "1200.00");
            UUID stuck = leg(st1, "forward", "with_courier", send(24, "g-giza", "Giza", null), NOW.minusSeconds(12 * 86400));
            history(stuck, "with_courier", NOW.minusSeconds(9 * 86400));
            UUID st2 = order(NOW.minusSeconds(25 * 86400), "new", "bosta", "{}");
            line(st2, s38, 1, "1200.00");
            UUID np = leg(st2, "forward", "delivered", send(45, "g-cairo", "Cairo", null), NOW.minusSeconds(25 * 86400));
            jdbc.update("UPDATE shipments SET settlement_status = 'deposited', deposited_at = ?, deposited_amt = 1100, " +
                        "settlement_refreshed_at = ? WHERE id = ?", ts(NOW.minusSeconds(20 * 86400)), ts(NOW.minusSeconds(3600)), np);
            for (int w = 1; w <= 2; w++) {      // two Wednesdays of payouts → payout weekday 3
                LocalDate wed = LocalDate.of(2026, 10, 7).minusWeeks(w);
                UUID po = order(cairo(9, 1, 9, w), "new", "bosta", "{}");
                line(po, tm, 1, "300.00");
                UUID p = leg(po, "forward", "delivered", send(45, "g-alx", "Alexandria", null), cairo(9, 1, 10, 0));
                jdbc.update("UPDATE shipments SET settlement_status = 'paid', cashout_txn_id = ?, cashout_date = ?, deposited_amt = 270, " +
                            "deposited_at = ?, bosta_fees = 30 WHERE id = ?", "WEDCOD" + wed, java.sql.Date.valueOf(wed),
                            ts(cairo(9, 2, 8, 0)), p);
            }
        }
    }
}
