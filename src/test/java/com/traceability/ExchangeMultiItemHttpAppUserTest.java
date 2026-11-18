package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exchange reference S3a (2026-11-18) — the N-row mapping endpoints over HTTP, the WHOLE app on app_user + RLS.
 *
 * <pre>
 *   h1  owner maps two outbound rows + one inbound row (POST /map-items) → 200, one order with two lines; the linked
 *       original's lines (GET /original-lines) list what the old items can be; the per-line override
 *       (POST /outbound-variant with orderItemId) changes that line
 *   h2  a worker gets 403 on map-items, original-lines and outbound-variant; nothing changes
 *   h4  waybill mode: the exchange AWB opens the 2-line EXC order with both lines on the card; one piece → the session
 *       never completes (scan 'scanned', Complete refused); the second piece completes it
 *   h3  isolation: tenant B can't map, read or override tenant A's exchange (409 / 404), and A's original lines never
 *       show to B; same-tenant positive control
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeMultiItemHttpAppUserTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
        try (var c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             var st = c.createStatement()) {
            st.execute("CREATE ROLE app_user LOGIN PASSWORD 'testpw'");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "app_user");
        r.add("spring.datasource.password", () -> "testpw");
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("analytics.settlement.refresh-enabled", () -> "false");
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
        r.add("traced.catalog-backfill.enabled", () -> "false");
    }

    static final AtomicLong IDS = new AtomicLong(7_300_000_000L);

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyTokenProvider tokens;
    @MockBean JobScheduler jobScheduler;
    @MockBean BostaGateway bostaGateway;
    @MockBean BostaV2Client bostaV2;

    JdbcTemplate pg;

    @BeforeAll
    void setUp() {
        pg = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
    }

    // ── h1 ───────────────────────────────────────────────────────────────────

    @Test
    void h1_owner_mapsTwoRows_readsOriginalLines_overridesOneLine() {
        Shop s = new Shop("h1");
        UUID original = s.order("#9001", s.black, s.beige);
        String awb = s.exchange(original);
        UUID ex = s.exchangeId(awb);

        JsonNode lines = read(s.get(s.owner, "/api/v1/exchanges/" + ex + "/original-lines"));
        assertThat(lines).hasSize(2);
        assertThat(lines.findValuesAsText("variantId")).containsExactlyInAnyOrder(s.black.toString(), s.beige.toString());

        ResponseEntity<String> mapped = s.post(s.owner, "/api/v1/exchanges/" + ex + "/map-items", Map.of(
            "outbound", List.of(Map.of("variantId", s.beige.toString(), "quantity", 1, "descriptionHint", "بيج"),
                                Map.of("variantId", s.navy.toString(), "quantity", 1, "descriptionHint", "كحلي")),
            "inbound", List.of(Map.of("variantId", s.black.toString(), "quantity", 2, "descriptionHint", "اسود"))));
        assertThat(mapped.getStatusCode()).as(mapped.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body = read(mapped);
        UUID order = UUID.fromString(body.path("orderId").asText());
        assertThat(body.path("items")).hasSize(3);
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = ?", Integer.class, order)).isEqualTo(2);
        assertThat(pg.queryForObject("SELECT status FROM exchanges WHERE id = ?", String.class, ex)).isEqualTo("matched");

        UUID navyLine = pg.queryForObject("SELECT id FROM order_items WHERE order_id = ? AND variant_id = ?", UUID.class, order, s.navy);
        ResponseEntity<String> over = s.post(s.manager, "/api/v1/exchanges/" + ex + "/outbound-variant",
            Map.of("variantId", s.black.toString(), "orderItemId", navyLine.toString()));
        // Override is gated to status 'mapped' (unchanged); this one is 'matched' → 409, nothing changes.
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        String unmatched = s.exchange(null);
        UUID ux = s.exchangeId(unmatched);
        assertThat(read(s.get(s.owner, "/api/v1/exchanges/" + ux + "/original-lines"))).isEmpty();
        UUID order2 = UUID.fromString(read(s.post(s.owner, "/api/v1/exchanges/" + ux + "/map-items", Map.of(
            "outbound", List.of(Map.of("variantId", s.beige.toString(), "quantity", 1),
                                Map.of("variantId", s.navy.toString(), "quantity", 1)),
            "inbound", List.of()))).path("orderId").asText());
        UUID navy2 = pg.queryForObject("SELECT id FROM order_items WHERE order_id = ? AND variant_id = ?", UUID.class, order2, s.navy);
        ResponseEntity<String> ok = s.post(s.manager, "/api/v1/exchanges/" + ux + "/outbound-variant",
            Map.of("variantId", s.black.toString(), "orderItemId", navy2.toString()));
        assertThat(ok.getStatusCode()).as(ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(pg.queryForObject("SELECT variant_id FROM order_items WHERE id = ?", UUID.class, navy2)).isEqualTo(s.black);
        assertThat(s.post(s.manager, "/api/v1/exchanges/" + ux + "/outbound-variant",
            Map.of("variantId", s.navy.toString())).getStatusCode()).as("two lines, none named").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── h2 ───────────────────────────────────────────────────────────────────

    @Test
    void h2_worker_forbidden() {
        Shop s = new Shop("h2");
        String awb = s.exchange(s.order("#9201", s.black));
        UUID ex = s.exchangeId(awb);
        assertThat(s.post(s.worker, "/api/v1/exchanges/" + ex + "/map-items", Map.of(
            "outbound", List.of(Map.of("variantId", s.beige.toString(), "quantity", 1)), "inbound", List.of()))
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(s.get(s.worker, "/api/v1/exchanges/" + ex + "/original-lines").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(s.post(s.worker, "/api/v1/exchanges/" + ex + "/outbound-variant", Map.of("variantId", s.beige.toString()))
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(pg.queryForObject("SELECT status FROM exchanges WHERE id = ?", String.class, ex)).isEqualTo("needs_mapping");
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM exchange_items WHERE exchange_id = ?", Integer.class, ex)).isZero();
    }

    // ── h3 ───────────────────────────────────────────────────────────────────

    @Test
    void h3_isolation_appUser() {
        Shop a = new Shop("h3a");
        Shop b = new Shop("h3b");
        String awbA = a.exchange(a.order("#9301", a.black));
        UUID exA = a.exchangeId(awbA);

        assertThat(b.post(b.owner, "/api/v1/exchanges/" + exA + "/map-items", Map.of(
            "outbound", List.of(Map.of("variantId", b.beige.toString(), "quantity", 1)), "inbound", List.of()))
            .getStatusCode()).isIn(HttpStatus.CONFLICT, HttpStatus.NOT_FOUND, HttpStatus.BAD_REQUEST);
        assertThat(b.get(b.owner, "/api/v1/exchanges/" + exA + "/original-lines").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(b.post(b.owner, "/api/v1/exchanges/" + exA + "/outbound-variant", Map.of("variantId", b.beige.toString()))
            .getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.BAD_REQUEST);
        // A's variant through B's own exchange: not B's catalog → refused.
        String awbB = b.exchange(null);
        assertThat(b.post(b.owner, "/api/v1/exchanges/" + b.exchangeId(awbB) + "/map-items", Map.of(
            "outbound", List.of(Map.of("variantId", a.beige.toString(), "quantity", 1)), "inbound", List.of()))
            .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(pg.queryForObject("SELECT status FROM exchanges WHERE id = ?", String.class, exA)).isEqualTo("needs_mapping");
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM exchange_items WHERE exchange_id = ?", Integer.class, exA)).isZero();
        assertThat(pg.queryForObject("SELECT status FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            String.class, b.tenant, awbB)).isEqualTo("needs_mapping");

        // Positive control: A maps its own.
        assertThat(read(a.get(a.owner, "/api/v1/exchanges/" + exA + "/original-lines"))).hasSize(1);
        ResponseEntity<String> own = a.post(a.owner, "/api/v1/exchanges/" + exA + "/map-items", Map.of(
            "outbound", List.of(Map.of("variantId", a.beige.toString(), "quantity", 1),
                                Map.of("variantId", a.navy.toString(), "quantity", 1)), "inbound", List.of()));
        assertThat(own.getStatusCode()).as(own.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM exchange_items WHERE exchange_id = ?", Integer.class, exA)).isEqualTo(2);
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM exchange_items WHERE tenant_id = ?", Integer.class, b.tenant)).isZero();
    }

    // ── h4 ───────────────────────────────────────────────────────────────────

    @Test
    void h4_waybillMode_twoLineExchange_needsBothPieces() {
        Shop s = new Shop("h4");
        pg.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", s.tenant);
        String awb = s.exchange(null);
        UUID ex = s.exchangeId(awb);
        JsonNode mapped = read(s.post(s.owner, "/api/v1/exchanges/" + ex + "/map-items", Map.of(
            "outbound", List.of(Map.of("variantId", s.beige.toString(), "quantity", 1),
                                Map.of("variantId", s.navy.toString(), "quantity", 1)), "inbound", List.of())));
        UUID order = UUID.fromString(mapped.path("orderId").asText());
        String beige = s.piece(s.beige), navy = s.piece(s.navy);

        String session = read(s.post(s.worker, "/api/v1/pack-sessions", Map.of())).path("id").asText();
        JsonNode opened = read(s.post(s.worker, "/api/v1/pack-sessions/" + session + "/waybill", Map.of("code", awb)));
        assertThat(opened.path("result").asText()).as(opened.toString()).isEqualTo("opened");
        assertThat(opened.path("order").path("id").asText()).isEqualTo(order.toString());
        JsonNode items = opened.path("order").path("items");
        assertThat(items).as("both lines on the card").hasSize(2);
        assertThat(items.findValuesAsText("variant_id").isEmpty() ? items.findValuesAsText("variantId") : items.findValuesAsText("variant_id"))
            .containsExactlyInAnyOrder(s.beige.toString(), s.navy.toString());

        JsonNode first = read(s.post(s.worker, "/api/v1/pack-sessions/" + session + "/orders/" + order + "/scan",
            Map.of("code", "PC-" + beige)));
        assertThat(first.path("status").asText()).as(first.toString()).isEqualTo("scanned");
        JsonNode early = read(s.post(s.worker, "/api/v1/pack-sessions/" + session + "/orders/" + order + "/complete", Map.of()));
        assertThat(early.path("status").asText()).as("one of two pieces: never completes").isNotEqualTo("completed");
        assertThat(pg.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, beige)).isNotEqualTo("awaiting_pickup");

        JsonNode last = read(s.post(s.worker, "/api/v1/pack-sessions/" + session + "/orders/" + order + "/scan",
            Map.of("code", "PC-" + navy)));
        assertThat(last.path("status").asText()).as(last.toString()).isEqualTo("completed");
        assertThat(pg.queryForList("SELECT status::text FROM pieces WHERE id IN (?, ?)", String.class, beige, navy))
            .containsOnly("awaiting_pickup");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID();
        final String owner, manager, worker;
        final UUID beige, navy, black;

        Shop(String name) {
            pg.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "EMH-" + name);
            pg.update("INSERT INTO locations (tenant_id, name, is_fulfillment) VALUES (?, 'Main Warehouse', true)", tenant);
            pg.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
                store, tenant, "emh-" + name + "-" + tenant.toString().substring(0, 8) + ".myshopify.com");
            owner = token(name + "-owner", "owner");
            manager = token(name + "-manager", "manager");
            worker = token(name + "-worker", "worker");
            UUID product = UUID.randomUUID();
            pg.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'وايد ليج', 'active')",
                product, tenant, store, "gid://shopify/Product/" + IDS.incrementAndGet());
            beige = variant(product, "بيج / 1");
            navy = variant(product, "كحلي / 1");
            black = variant(product, "اسود / 2");
        }

        private UUID variant(UUID product, String title) {
            UUID v = UUID.randomUUID();
            pg.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
                v, tenant, product, "gid://shopify/ProductVariant/" + IDS.incrementAndGet(), title, "WL-" + v.toString().substring(0, 6));
            return v;
        }

        private String token(String name, String role) {
            UUID user = pg.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role) " +
                "VALUES (?, ?, ?, 'x', ?::user_role) RETURNING id", UUID.class, tenant, name,
                name + "-" + UUID.randomUUID() + "@test.com", role);
            return jwt.issueAccessToken(user, tenant, role);
        }

        UUID order(String number, UUID... variants) {
            UUID o = pg.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
                "VALUES (?, ?, ?, ?, 'delivered'::order_status, now()) RETURNING id", UUID.class,
                tenant, store, "gid://shopify/Order/" + IDS.incrementAndGet(), number);
            for (UUID v : variants) {
                pg.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)", tenant, o, v);
            }
            return o;
        }

        String exchange(UUID original) {
            String tn = String.valueOf(IDS.incrementAndGet());
            ObjectNode raw = mapper.createObjectNode();
            raw.put("trackingNumber", tn);
            raw.putNull("businessReference");
            raw.putObject("type").put("code", 30).put("value", "Exchange");
            raw.putObject("receiver").put("fullName", "منى عادل").put("phone", "+201005557788");
            raw.putObject("specs").putObject("packageDetails").put("itemsCount", 2).put("description", "عدد-2 وايد ليج بيج وكحلي مقاس -1");
            raw.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", 2).put("description", "عدد-2 وايد ليج اسود مقاس -2");
            pg.update("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_description, inbound_description, raw, " +
                "matched_order_id, match_method) VALUES (?, ?, 'needs_mapping', 'Out', 'In', ?::jsonb, ?, ?)",
                tenant, tn, raw.toString(), original, original == null ? null : "manual");
            return tn;
        }

        /** An available piece at the tenant's main warehouse. */
        String piece(UUID variant) {
            String id = com.traceability.inventory.UlidGenerator.generate();
            UUID location = pg.queryForObject("SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment", UUID.class, tenant);
            pg.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available', ?)",
                id, tenant, variant, "PC-" + id, id, location);
            return id;
        }

        UUID exchangeId(String tn) {
            return pg.queryForObject("SELECT id FROM exchanges WHERE tenant_id = ? AND tracking_number = ?", UUID.class, tenant, tn);
        }

        ResponseEntity<String> get(String token, String path) {
            return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
        }

        ResponseEntity<String> post(String token, String path, Object body) {
            return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers(token)), String.class);
        }
    }

    java.net.URI url(String path) { return java.net.URI.create("http://localhost:" + port + path); }

    static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    JsonNode read(ResponseEntity<String> r) {
        try {
            return mapper.readTree(r.getBody());
        } catch (Exception e) {
            throw new IllegalStateException(r.getStatusCode() + " " + r.getBody(), e);
        }
    }
}
