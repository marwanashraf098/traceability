package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.portal.PreConnectParentImport;
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

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Exchange reference S2 (2026-11-18) — the exchange drawer's "Link to order" over the REAL HTTP path, the WHOLE app
 * on app_user + RLS (the way prod runs). Fixtures through a separate postgres connection only.
 *
 * <pre>
 *   x1  number search: "#LT-22267", "#OD2394", "OD2394", "lt-22267", the plugin shape "wjwy1a-ia:#5126", "13313";
 *       never an internal order (EXC replacement); each hit carries its items
 *   x2  phone search: +20 / 0020 / 0 / no-zero / Arabic-Indic / spaced forms → the order; another phone → nothing
 *   x3  Arabic name search: "احمد محمد" finds "أحمد مُحمّد"; "محمد" too; ILIKE on English names
 *   x4  empty q: suggestions by Bosta's receiver phone — newest first (created_at DESC), max 20, the exchange's own
 *       replacement left out, the receiver phone never echoed
 *   x5  attach from needs_mapping: status stays needs_mapping (match_method manual); mapping the replacement then
 *       makes it matched. unmatched → matched. dismissed / bare_return / return_received / an already-linked
 *       needs_mapping → 409; a portal pre-connect order id → 400 (attach never takes one)
 *   x6  import offer: only for a clean number below the lowest ingested one with a connected store; never for a
 *       number above it or with the store disconnected; import → FOUND (items, not linked) → link → LINKED through
 *       the S1 path (one Shopify read); Shopify NOT_FOUND → a 200 NOT_FOUND answer, nothing linked
 *   x7  a worker gets 403 on search, attach and import
 *   x8  isolation (app_user): tenant B can't search / attach A's exchange, A's orders never show in B's search;
 *       same-tenant positive control
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeOrderLinkHttpAppUserTest {

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

    static final Instant CUTOFF = Instant.parse("2026-10-27T10:00:00Z");
    static final String RECEIVER_PHONE = "+20 100 555 7788";
    static final AtomicLong IDS = new AtomicLong(6_300_000_000L);

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PreConnectParentImport parentImport;
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

    @BeforeEach
    void stubs() {
        reset(shopify, tokens);
        parentImport.clearCaches();
        when(tokens.getValidToken(any())).thenReturn("shpat-test");
    }

    // ── x1 ───────────────────────────────────────────────────────────────────

    @Test
    void x1_numberSearch_everyShape() {
        Shop s = new Shop("x1");
        UUID lt = s.order("#LT-22267", "Mona Adel", "01011112222");
        UUID od = s.order("#OD2394", "Sara Ali", "01011113333");
        UUID plugin = s.order("#5126", "Nour Hassan", "01011114444");
        UUID bare = s.order("13313", "Omar Said", "01011115555");
        s.item(lt, 2);
        String awb = s.exchange("unmatched", "+201099990000");
        // The exchange's own replacement (internal) never shows, even by its number.
        pg.update("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, now())", s.tenant, s.store, "internal:exchange:" + awb, "EXC-" + awb);

        assertThat(ids(s.search(s.owner, awb, "#LT-22267"))).containsExactly(lt.toString());
        assertThat(ids(s.search(s.owner, awb, "lt-22267"))).containsExactly(lt.toString());
        assertThat(ids(s.search(s.owner, awb, "#OD2394"))).containsExactly(od.toString());
        assertThat(ids(s.search(s.owner, awb, "OD2394"))).containsExactly(od.toString());
        assertThat(ids(s.search(s.owner, awb, "wjwy1a-ia:#5126"))).containsExactly(plugin.toString());
        assertThat(ids(s.search(s.owner, awb, "13313"))).containsExactly(bare.toString());
        assertThat(ids(s.search(s.owner, awb, "EXC-" + awb))).isEmpty();

        JsonNode hit = s.search(s.owner, awb, "#LT-22267").path("orders").get(0);
        assertThat(hit.path("number").asText()).isEqualTo("#LT-22267");
        assertThat(hit.path("customerName").asText()).isEqualTo("Mona Adel");
        assertThat(hit.has("customerPhone")).as("no phone in a hit").isFalse();
        assertThat(hit.path("items").get(0).path("productTitle").asText()).isEqualTo("Linen dress");
        assertThat(hit.path("items").get(0).path("variantTitle").asText()).isEqualTo("M / Blue");
        assertThat(hit.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
    }

    // ── x2 ───────────────────────────────────────────────────────────────────

    @Test
    void x2_phoneSearch_egyptianForms() {
        Shop s = new Shop("x2");
        UUID o = s.order("#2001", "Hana Mostafa", "+20 101 234 5678");
        s.order("#2002", "Other", "01099998888");
        String awb = s.exchange("unmatched", "+201099990000");
        for (String q : List.of("01012345678", "+201012345678", "00201012345678", "1012345678",
                                "٠١٠١٢٣٤٥٦٧٨", "+20 101 234 5678", "0101-234-5678")) {
            assertThat(ids(s.search(s.owner, awb, q))).as(q).containsExactly(o.toString());
        }
        assertThat(ids(s.search(s.owner, awb, "01277776666"))).isEmpty();
    }

    // ── x3 ───────────────────────────────────────────────────────────────────

    @Test
    void x3_arabicNameSearch() {
        Shop s = new Shop("x3");
        UUID ar = s.order("#3001", "أحمد مُحمّد", "01022223333");
        UUID en = s.order("#3002", "Yasmine Farouk", "01022224444");
        String awb = s.exchange("unmatched", "+201099990000");
        assertThat(ids(s.search(s.owner, awb, "احمد محمد"))).containsExactly(ar.toString());
        assertThat(ids(s.search(s.owner, awb, "محمد"))).containsExactly(ar.toString());
        assertThat(ids(s.search(s.owner, awb, "أحمد"))).containsExactly(ar.toString());
        assertThat(ids(s.search(s.owner, awb, "yasmine"))).containsExactly(en.toString());
        assertThat(ids(s.search(s.owner, awb, "خالد"))).isEmpty();
    }

    // ── x4 ───────────────────────────────────────────────────────────────────

    @Test
    void x4_suggestions_byReceiverPhone_newestFirst_max20() {
        Shop s = new Shop("x4");
        List<UUID> mine = new ArrayList<>();
        for (int i = 0; i < 22; i++) {
            mine.add(s.orderAt("#40" + String.format("%02d", i), "Rana", "01005557788", CUTOFF.plusSeconds(3600L * (i + 1))));
        }
        s.order("#4999", "Someone else", "01200001111");
        String awb = s.exchange("needs_mapping", RECEIVER_PHONE);
        UUID exc = s.excOrder(awb, "01005557788");

        JsonNode res = s.search(s.owner, awb, "");
        assertThat(res.path("suggested").asBoolean()).isTrue();
        List<String> got = ids(res);
        assertThat(got).hasSize(20);
        assertThat(got.get(0)).isEqualTo(mine.get(21).toString());
        assertThat(got.get(19)).isEqualTo(mine.get(2).toString());
        assertThat(got).doesNotContain(exc.toString());
        String body = res.toString();
        assertThat(body).doesNotContain("5557788").doesNotContain("555 7788");
        assertThat(res.path("importOption").isNull()).isTrue();

        // No receiver phone → no suggestions (not an error).
        String none = s.exchange("unmatched", null);
        assertThat(ids(s.search(s.owner, none, ""))).isEmpty();
    }

    // ── x5 ───────────────────────────────────────────────────────────────────

    @Test
    void x5_attach_fromNeedsMapping_thenMapped_isMatched() {
        Shop s = new Shop("x5");
        UUID original = s.order("#5001", "Laila", "01033334444");
        String awb = s.exchange("needs_mapping", "+201033334444");
        UUID exId = s.exchangeId(awb);

        ResponseEntity<String> r = s.attach(s.manager, exId, original);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(s, awb).get("status")).isEqualTo("needs_mapping");
        assertThat(exchange(s, awb).get("matched_order_id")).isEqualTo(original);
        assertThat(exchange(s, awb).get("match_method")).isEqualTo("manual");
        assertThat(exchange(s, awb).get("inbound_variant_id")).as("ExchangeInbound.fill: the original's single line")
            .isEqualTo(s.variant);

        // A second link while it already has its original → refused.
        assertThat(s.attach(s.manager, exId, s.order("#5002", "X", "01033335555")).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> mapped = s.post(s.manager, "/api/v1/exchanges/" + exId + "/map",
            Map.of("outboundVariantId", s.variant2.toString(), "inboundVariantId", s.variant.toString()));
        assertThat(mapped.getStatusCode()).as(mapped.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(s, awb).get("status")).isEqualTo("matched");
        assertThat(exchange(s, awb).get("matched_order_id")).isEqualTo(original);

        // unmatched → matched at once.
        String un = s.exchange("unmatched", null);
        assertThat(s.attach(s.owner, s.exchangeId(un), original).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(s, un).get("status")).isEqualTo("matched");

        for (String closed : List.of("dismissed", "bare_return", "return_received")) {
            String tn = s.exchange(closed, null);
            assertThat(s.attach(s.owner, s.exchangeId(tn), original).getStatusCode()).as(closed).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exchange(s, tn).get("matched_order_id")).as(closed).isNull();
        }

        // A portal pre-connect row is never attached by id.
        UUID portal = pg.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at, origin, " +
            "shopify_order_gid) VALUES (?, ?, ?, '#4100', 'delivered'::order_status, ?, 'portal_pre_connect', ?) RETURNING id",
            UUID.class, s.tenant, s.store, "internal:portal:" + UUID.randomUUID(), Timestamp.from(CUTOFF.minusSeconds(86_400)),
            "gid://shopify/Order/" + IDS.incrementAndGet());
        String pc = s.exchange("unmatched", null);
        assertThat(s.attach(s.owner, s.exchangeId(pc), portal).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(s, pc).get("matched_order_id")).isNull();
    }

    // ── x6 ───────────────────────────────────────────────────────────────────

    @Test
    void x6_importOption_andTheS1ImportPath() {
        Shop s = new Shop("x6");
        s.order("#5139", "Lowest", "01044440000");
        s.order("#5300", "Higher", "01044440001");
        String awb = s.exchange("needs_mapping", "+201044449999");
        UUID exId = s.exchangeId(awb);

        assertThat(s.search(s.owner, awb, "#5126").path("importOption").path("number").asText()).isEqualTo("5126");
        assertThat(s.search(s.owner, awb, "wjwy1a-ia:#5126").path("importOption").path("number").asText()).isEqualTo("5126");
        assertThat(s.search(s.owner, awb, "#5299").path("importOption").isNull()).as("above the lowest").isTrue();
        assertThat(s.search(s.owner, awb, "#5300").path("importOption").isNull()).as("found").isTrue();
        assertThat(s.search(s.owner, awb, "Rana").path("importOption").isNull()).as("not a number").isTrue();
        verify(shopify, never()).findOrdersByName(anyString(), anyString(), anyString());

        shopifyAnswers(s, "#5126");
        JsonNode found = s.importParent(s.owner, exId, "#5126", false);
        assertThat(found.path("status").asText()).isEqualTo("FOUND");
        assertThat(found.path("items").get(0).path("productTitle").asText()).isEqualTo("Linen dress");
        assertThat(found.has("orderId")).isFalse();
        assertThat(exchange(s, awb).get("matched_order_id")).as("not linked before the confirm").isNull();

        JsonNode linked = s.importParent(s.owner, exId, "#5126", true);
        assertThat(linked.path("status").asText()).isEqualTo("LINKED");
        assertThat(linked.path("exchangeStatus").asText()).isEqualTo("needs_mapping");
        UUID parent = pg.queryForObject("SELECT id FROM orders WHERE tenant_id = ? AND number = '#5126' " +
            "AND origin = 'portal_pre_connect'", UUID.class, s.tenant);
        assertThat(exchange(s, awb).get("matched_order_id")).isEqualTo(parent);
        verify(shopify, times(1)).findOrdersByName(eq(s.shop), anyString(), eq("5126"));

        // Shopify NOT_FOUND (prod MAVIS #3333) → an answer, not an error; nothing linked.
        when(shopify.findOrdersByName(eq(s.shop), anyString(), eq("3333")))
            .thenReturn(new ShopifyGateway.PortalOrderSearch(List.of(), true));
        String other = s.exchange("unmatched", "+201044448888");
        JsonNode miss = s.importParent(s.owner, s.exchangeId(other), "#3333", false);
        assertThat(miss.path("status").asText()).isEqualTo("NOT_FOUND");
        assertThat(miss.path("reason").asText()).isEqualTo("NOT_FOUND");
        assertThat(exchange(s, other).get("matched_order_id")).isNull();

        // A number above the lowest is never imported (no Shopify call).
        assertThat(s.importParent(s.owner, s.exchangeId(other), "#5299", true).path("status").asText())
            .isEqualTo("NOT_PRE_CONNECT");
        verify(shopify, never()).findOrdersByName(anyString(), anyString(), eq("5299"));

        // Store disconnected → no offer.
        pg.update("UPDATE stores SET status = 'disconnected' WHERE id = ?", s.store);
        assertThat(s.search(s.owner, other, "#4888").path("importOption").isNull()).isTrue();
    }

    // ── x7 ───────────────────────────────────────────────────────────────────

    @Test
    void x7_worker_forbidden() {
        Shop s = new Shop("x7");
        UUID o = s.order("#7001", "W", "01055556666");
        String awb = s.exchange("unmatched", null);
        UUID exId = s.exchangeId(awb);
        assertThat(s.get(s.worker, "/api/v1/exchanges/" + exId + "/order-search?q=7001").getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(s.attach(s.worker, exId, o).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(s.post(s.worker, "/api/v1/exchanges/" + exId + "/import-parent", Map.of("number", "7001"))
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(s, awb).get("matched_order_id")).isNull();
    }

    // ── x8 ───────────────────────────────────────────────────────────────────

    @Test
    void x8_isolation_appUser() {
        Shop a = new Shop("x8a");
        Shop b = new Shop("x8b");
        UUID origA = a.order("#8001", "Amira", "01066667777");
        UUID origB = b.order("#8002", "Bassem", "01066668888");
        String exA = a.exchange("unmatched", "+201066667777");
        String exB = b.exchange("unmatched", "+201066667777");
        UUID exAId = a.exchangeId(exA);

        assertThat(b.get(b.owner, "/api/v1/exchanges/" + exAId + "/order-search?q=8001").getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ids(b.search(b.owner, exB, "#8001"))).as("A's order never shows to B").isEmpty();
        assertThat(ids(b.search(b.owner, exB, "Amira"))).isEmpty();
        assertThat(ids(b.search(b.owner, exB, ""))).as("same receiver phone, A's order hidden").isEmpty();
        assertThat(b.attach(b.owner, exAId, origA).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(b.attach(b.owner, exAId, origB).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(b.post(b.owner, "/api/v1/exchanges/" + exAId + "/import-parent", Map.of("number", "8001"))
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(a, exA).get("matched_order_id")).isNull();

        // Positive control: A, as itself.
        assertThat(ids(a.search(a.owner, exA, ""))).containsExactly(origA.toString());
        assertThat(ids(a.search(a.owner, exA, "#8001"))).containsExactly(origA.toString());
        assertThat(a.attach(a.owner, exAId, origA).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(a, exA).get("matched_order_id")).isEqualTo(origA);
        assertThat(exchange(b, exB).get("matched_order_id")).isNull();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID();
        final String shop;
        final String owner, manager, worker;
        final UUID variant, variant2;
        final String variantGid;

        Shop(String name) {
            shop = "eol-" + name + "-" + tenant.toString().substring(0, 8) + ".myshopify.com";
            pg.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "EOL-" + name);
            pg.update("INSERT INTO locations (tenant_id, name, is_fulfillment) VALUES (?, 'Main Warehouse', true)", tenant);
            pg.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                "VALUES (?, ?, 'shopify', ?, 'connected', ?)", store, tenant, shop, Timestamp.from(CUTOFF));
            owner = token(name + "-owner", "owner");
            manager = token(name + "-manager", "manager");
            worker = token(name + "-worker", "worker");
            UUID product = UUID.randomUUID();
            pg.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, ?, 'Linen dress', 'active')", product, tenant, store, "gid://shopify/Product/" + IDS.incrementAndGet());
            variant = UUID.randomUUID();
            variantGid = "gid://shopify/ProductVariant/" + IDS.incrementAndGet();
            pg.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'M / Blue', ?)",
                variant, tenant, product, variantGid, "LD-M-" + variant.toString().substring(0, 6));
            variant2 = UUID.randomUUID();
            pg.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'L / Blue', ?)",
                variant2, tenant, product, "gid://shopify/ProductVariant/" + IDS.incrementAndGet(),
                "LD-L-" + variant2.toString().substring(0, 6));
        }

        private String token(String name, String role) {
            UUID user = pg.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role) " +
                "VALUES (?, ?, ?, 'x', ?::user_role) RETURNING id", UUID.class, tenant, name,
                name + "-" + UUID.randomUUID() + "@test.com", role);
            return jwt.issueAccessToken(user, tenant, role);
        }

        UUID order(String number, String name, String phone) {
            return orderAt(number, name, phone, CUTOFF.plusSeconds(3600));
        }

        /** A Shopify order with one line of the dress (M / Blue); created_at = placed_at = {@code at}. */
        UUID orderAt(String number, String name, String phone, Instant at) {
            UUID id = pg.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at, " +
                "created_at, customer_name, customer_phone) VALUES (?, ?, ?, ?, 'delivered'::order_status, ?, ?, ?, ?) RETURNING id",
                UUID.class, tenant, store, "gid://shopify/Order/" + IDS.incrementAndGet(), number,
                Timestamp.from(at), Timestamp.from(at), name, phone);
            item(id, 1);
            return id;
        }

        void item(UUID order, int qty) {
            pg.update("DELETE FROM order_items WHERE order_id = ?", order);
            pg.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?)",
                tenant, order, variant, qty);
        }

        /** A dashboard exchanges row (type 30) with Bosta's receiver phone (null → none). */
        String exchange(String status, String receiverPhone) {
            String tn = String.valueOf(IDS.incrementAndGet());
            ObjectNode raw = mapper.createObjectNode();
            raw.put("trackingNumber", tn);
            raw.putNull("businessReference");
            raw.put("createdAt", "2026-10-30T09:00:00.000Z");
            raw.putObject("type").put("code", 30).put("value", "Exchange");
            raw.putObject("state").put("code", 10).put("value", "Pickup requested");
            ObjectNode receiver = raw.putObject("receiver").put("fullName", "Customer " + tn);
            if (receiverPhone != null) receiver.put("phone", receiverPhone);
            raw.putObject("dropOffAddress").put("firstLine", "1 Street");
            raw.putObject("specs").putObject("packageDetails").put("itemsCount", 1).put("description", "Exchange item");
            raw.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", 1).put("description", "Old item");
            pg.update("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_description, inbound_description, raw) " +
                "VALUES (?, ?, ?, 'Out', 'In', ?::jsonb)", tenant, tn, status, raw.toString());
            return tn;
        }

        UUID excOrder(String awb, String phone) {
            UUID exc = pg.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at, " +
                "customer_phone) VALUES (?, ?, ?, ?, 'new'::order_status, now(), ?) RETURNING id", UUID.class,
                tenant, store, "internal:exchange:" + awb, "EXC-" + awb, phone);
            pg.update("UPDATE exchanges SET outbound_order_id = ? WHERE tenant_id = ? AND tracking_number = ?", exc, tenant, awb);
            return exc;
        }

        UUID exchangeId(String tn) {
            return pg.queryForObject("SELECT id FROM exchanges WHERE tenant_id = ? AND tracking_number = ?", UUID.class, tenant, tn);
        }

        JsonNode search(String token, String awb, String q) {
            ResponseEntity<String> r = get(token, "/api/v1/exchanges/" + exchangeId(awb) + "/order-search?q="
                + URLEncoder.encode(q, StandardCharsets.UTF_8));
            assertThat(r.getStatusCode()).as(q + " → " + r.getBody()).isEqualTo(HttpStatus.OK);
            return read(r.getBody());
        }

        ResponseEntity<String> attach(String token, UUID exchangeId, UUID orderId) {
            return post(token, "/api/v1/exchanges/" + exchangeId + "/attach", Map.of("orderId", orderId.toString()));
        }

        JsonNode importParent(String token, UUID exchangeId, String number, boolean link) {
            ResponseEntity<String> r = post(token, "/api/v1/exchanges/" + exchangeId + "/import-parent",
                Map.of("number", number, "link", link));
            assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
            return read(r.getBody());
        }

        ResponseEntity<String> get(String token, String path) {
            return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
        }

        ResponseEntity<String> post(String token, String path, Object body) {
            return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers(token)), String.class);
        }
    }

    java.net.URI url(String path) {
        return java.net.URI.create("http://localhost:" + port + path);
    }

    static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    JsonNode read(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static List<String> ids(JsonNode res) {
        List<String> out = new ArrayList<>();
        res.path("orders").forEach(o -> out.add(o.path("id").asText()));
        return out;
    }

    Map<String, Object> exchange(Shop s, String tn) {
        return pg.queryForMap("SELECT status, matched_order_id, match_method, inbound_variant_id FROM exchanges " +
            "WHERE tenant_id = ? AND tracking_number = ?", s.tenant, tn);
    }

    /** Shopify answers findOrdersByName with one pre-connect order {@code name}: one line of the dress. */
    void shopifyAnswers(Shop s, String name) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("name", name);
        ArrayNode edges = raw.putObject("lineItems").putArray("edges");
        ObjectNode node = edges.addObject().putObject("node");
        node.put("id", "gid://shopify/LineItem/" + IDS.incrementAndGet());
        node.put("quantity", 1);
        node.put("currentQuantity", 1);
        node.putObject("variant").put("id", s.variantGid);
        ShopifyGateway.Order order = new ShopifyGateway.Order(
            "gid://shopify/Order/" + IDS.incrementAndGet(), name, null, null, null, "PENDING", List.of("Cash on Delivery (COD)"),
            new BigDecimal("500.00"), List.of(), CUTOFF.minusSeconds(86_400 * 5), raw);
        when(shopify.findOrdersByName(eq(s.shop), anyString(), anyString()))
            .thenReturn(new ShopifyGateway.PortalOrderSearch(List.of(order), true));
    }
}
