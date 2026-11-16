package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ExchangeService;
import com.traceability.inventory.FulfillService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-11-16 — the pre-connect exchange / CRP ingest and the Pick &amp; Pack reads, with the WHOLE app on app_user
 * (RLS on, no BYPASSRLS): the webhook job, the parent import (its own short transactions under the tenant), the exchange
 * match, the CRP link, GET /fulfill/exchanges-to-map, the mapping and the queue. Fixtures and assertions on postgres.
 *
 *   a1  a Storm-shape exchange: the parent is imported and matched, listed to map for its own tenant (positive control)
 *       and never for the other (negative control); mapped → the queue row with replacementFor, own tenant only
 *   a2  a Storm-shape CRP: its return leg links to the imported parent
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreConnectPickPackAppUserTest {

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

    static final Instant CUTOFF = Instant.parse("2026-11-13T17:15:08Z");
    static final String AFTER = "Sun Nov 15 2026 22:43:11 GMT+0000 (Coordinated Universal Time)";

    @Autowired ObjectMapper mapper;
    @Autowired EncryptionService encryption;
    @Autowired BostaWebhookJob webhookJob;
    @Autowired ExchangeService exchangeService;
    @Autowired FulfillService fulfill;
    @MockBean BostaGateway bostaGateway;
    @MockBean BostaV2Client bostaV2;
    @MockBean JobScheduler jobScheduler;
    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyTokenProvider tokens;

    /** Fixtures and assertions only (postgres); the app never uses it. */
    JdbcTemplate pg;

    @BeforeAll
    void setUp() {
        pg = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void stubs() {
        reset(bostaGateway, shopify, tokens);
        when(tokens.getValidToken(any())).thenReturn("shpat-test");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    @SuppressWarnings("unchecked")
    void a1_exchange_importedMatchedListedMapped_ownTenantOnly() {
        Shop a = new Shop("a1a");
        Shop b = new Shop("a1b");
        a.parentInShopify("#2739");
        ingest(a, delivery("2472106867", 30, "#2739:#2739-R1"));

        UUID parent = pg.queryForObject("SELECT id FROM orders WHERE tenant_id = ? AND number = '#2739' " +
            "AND origin = 'portal_pre_connect'", UUID.class, a.tenant);
        assertThat(pg.queryForObject("SELECT matched_order_id FROM exchanges WHERE tracking_number = '2472106867'", UUID.class))
            .isEqualTo(parent);

        List<Map<String, Object>> mine = TenantContext.runAs(a.tenant, () -> fulfill.getExchangesToMap());
        assertThat(mine).extracting(r -> r.get("trackingNumber")).containsExactly("2472106867");
        assertThat(TenantContext.runAs(b.tenant, () -> fulfill.getExchangesToMap())).isEmpty();

        UUID ex = pg.queryForObject("SELECT id FROM exchanges WHERE tracking_number = '2472106867'", UUID.class);
        Map<String, Object> mapped = TenantContext.runAs(a.tenant, () -> exchangeService.map(ex, a.jeans32, a.jeans30));
        UUID exc = UUID.fromString((String) mapped.get("orderId"));
        Map<String, Object> row = TenantContext.runAs(a.tenant, () -> fulfill.getQueue()).stream()
            .filter(r -> exc.equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(((Map<String, Object>) row.get("replacementFor")).get("number")).isEqualTo("#2739");
        assertThat(TenantContext.runAs(b.tenant, () -> fulfill.getQueue())).extracting(r -> r.get("id")).doesNotContain(exc);
    }

    @Test
    void a2_crp_returnLegLinksToTheImportedParent() {
        Shop s = new Shop("a2");
        s.parentInShopify("#2948");
        ingest(s, delivery("3741531660", 25, "#2948:#2948-R1"));
        UUID parent = pg.queryForObject("SELECT id FROM orders WHERE tenant_id = ? AND number = '#2948'", UUID.class, s.tenant);
        assertThat(pg.queryForObject("SELECT order_id FROM shipments WHERE tracking_number = '3741531660' AND shipment_leg = 'return'",
            UUID.class)).isEqualTo(parent);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID();
        final String shop;
        final UUID jeans30 = UUID.randomUUID(), jeans32 = UUID.randomUUID();
        final String jeans30Gid = "gid://shopify/ProductVariant/" + UUID.randomUUID();
        final java.util.Map<String, ShopifyGateway.Order> orders = new java.util.HashMap<>();

        Shop(String name) {
            shop = "au-" + name + "-" + tenant.toString().substring(0, 8) + ".myshopify.com";
            pg.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Storm-" + name);
            pg.update("INSERT INTO locations (tenant_id, name, is_fulfillment) VALUES (?, 'Main Warehouse', true)", tenant);
            pg.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', ?, 'h', 'active')", tenant, encryption.encrypt("key-" + tenant));
            pg.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                "VALUES (?, ?, 'shopify', ?, 'connected', ?)", store, tenant, shop, Timestamp.from(CUTOFF));
            pg.update("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
                "VALUES (?, ?, ?, '#3018', 'new'::order_status, now())", tenant, store, "gid://shopify/Order/" + UUID.randomUUID());
            UUID product = UUID.randomUUID();
            pg.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Jeans', 'active')",
                product, tenant, store, "gid://shopify/Product/" + UUID.randomUUID());
            pg.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, '30', ?)",
                jeans30, tenant, product, jeans30Gid, "JN-30-" + name);
            pg.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, '32', ?)",
                jeans32, tenant, product, "gid://shopify/ProductVariant/" + UUID.randomUUID(), "JN-32-" + name);
            when(shopify.findOrdersByName(eq(shop), anyString(), anyString())).thenAnswer(inv -> {
                ShopifyGateway.Order o = orders.get("#" + inv.getArgument(2, String.class));
                return new ShopifyGateway.PortalOrderSearch(o == null ? List.of() : List.of(o), true);
            });
        }

        void parentInShopify(String name) {
            ObjectNode raw = mapper.createObjectNode();
            raw.put("name", name);
            ArrayNode edges = raw.putObject("lineItems").putArray("edges");
            ObjectNode node = edges.addObject().putObject("node");
            node.put("id", "gid://shopify/LineItem/" + UUID.randomUUID());
            node.put("quantity", 1);
            node.put("currentQuantity", 1);
            node.putObject("variant").put("id", jeans30Gid);
            orders.put(name, new ShopifyGateway.Order("gid://shopify/Order/" + UUID.randomUUID(), name, null, null, null,
                "PENDING", List.of("Cash on Delivery (COD)"), new BigDecimal("950.00"), List.of(),
                Instant.parse("2026-11-01T10:00:00Z"), raw));
        }
    }

    ObjectNode delivery(String tracking, int type, String reference) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "bosta-" + tracking);
        raw.put("trackingNumber", tracking);
        raw.put("businessReference", reference);
        raw.put("createdAt", AFTER);
        raw.put("updatedAt", "2026-11-15T22:43:11.000Z");
        raw.putObject("type").put("code", type).put("value", type == 30 ? "Exchange" : "Customer Return Pickup");
        raw.putObject("state").put("code", 10).put("value", "Pickup requested");
        raw.putObject("specs").putObject("packageDetails").put("itemsCount", 1).put("description", "Exchange item");
        if (type == 30) raw.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", 1).put("description", "Back");
        raw.putObject("receiver").put("fullName", "Test Customer").put("phone", "01000000000");
        return raw;
    }

    void ingest(Shop s, ObjectNode raw) {
        String tracking = raw.path("trackingNumber").asText();
        when(bostaGateway.fetchDelivery(eq("key-" + s.tenant), eq(tracking))).thenReturn(BostaDelivery.fromRaw(tracking, raw));
        ObjectNode payload = mapper.createObjectNode();
        payload.put("trackingNumber", tracking);
        payload.put("state", 10);
        payload.put("updatedAt", raw.path("updatedAt").asText());
        Long id = pg.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, s.tenant, payload.toString());
        webhookJob.process(id, s.tenant);
        assertThat(pg.queryForObject("SELECT status::text FROM webhook_events WHERE id = ?", String.class, id)).isEqualTo("processed");
    }
}
