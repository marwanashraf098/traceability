package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.account.AuditService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifySameShopGuard;
import com.traceability.integrations.shopify.ShopifySyncService;
import com.traceability.inventory.BlocklistService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fix/order-payload-downgrade (V156): the GraphQL order import never makes a stored payload poorer
 * in customer data (shopify_order_raw_keep_customer); webhook payloads still replace raw; the
 * one-off restore puts the customer / shipping / billing groups back from the latest stored
 * orders/* webhook, never on a redacted order, and a second run changes nothing. The sync service
 * runs as app_user (RLS), like ShopifyPiiIngestTest.
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderPayloadDowngradeTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EncryptionService encryption;
    @Autowired AuditService auditService;
    @Autowired ShopifySameShopGuard sameShopGuard;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean BostaGateway bostaGateway;

    UUID tenant, store;
    ShopifySyncService appSync;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID();
        store = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Downgrade Co')", tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, access_token_encrypted, " +
                    "    access_token_expires_at) VALUES (?, ?, 'shopify', 'downgrade-co.myshopify.com', 'connected', ?, " +
                    "    now() + interval '876000 hours')", store, tenant, encryption.encrypt("shpat_dg"));
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(ds);
        DataSourceTransactionManager txm = new DataSourceTransactionManager(ds);
        appSync = new ShopifySyncService(appJdbc, shopifyGateway, encryption, mapper, txm,
                new BlocklistService(appJdbc, auditService, txm), sameShopGuard, 30);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM shopify_webhook_events");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
    }

    // ── the import never downgrades ──────────────────────────────────────────

    @Test
    void reImportAtAReducedTier_keepsTheWebhooksCustomerShippingAndBilling() throws Exception {
        String gid = gid();
        TenantContext.runAs(tenant, () -> appSync.ingestOrderWebhook(store, tenant, rest(gid, "#D101")));
        // The pre-Build-B / NONE-tier GraphQL node: no customer, no addresses.
        importOrder(gid, "#D101", "{\"id\":\"%s\",\"name\":\"#D101\",\"displayFinancialStatus\":\"PAID\"}");

        JsonNode raw = raw(gid);
        assertThat(raw.path("displayFinancialStatus").asText()).isEqualTo("PAID");          // the import's fields
        assertThat(raw.path("customer").path("first_name").asText()).isEqualTo("Mona");     // kept
        assertThat(raw.path("shipping_address").path("phone").asText()).isEqualTo("+201001112222");
        assertThat(raw.path("billing_address").path("name").asText()).isEqualTo("Bill Name");
        assertThat(raw.path("phone").asText()).isEqualTo("+201000000001");
        assertThat(customerKey(gid)).isEqualTo("c:4242");                                     // V149 key survives
    }

    @Test
    void import_freshGroupsWin_missingOrNullGroupsAreKept_acrossRestAndGraphqlSpellings() throws Exception {
        String gid = gid();
        importOrder(gid, "#D201", """
            {"id":"%s","shippingAddress":{"city":"Giza","phone":"+201000000010"},
             "billingAddress":{"name":"Old Bill"},
             "customer":{"id":"gid://shopify/Customer/7","firstName":"Nour","lastName":"Ali"}}""");
        // ADDRESSES tier: fresh shipping (Cairo), customer null → stored customer kept; no billing → kept.
        importOrder(gid, "#D201", "{\"id\":\"%s\",\"shippingAddress\":{\"city\":\"Cairo\"},\"customer\":null}");
        JsonNode raw = raw(gid);
        assertThat(raw.path("shippingAddress").path("city").asText()).isEqualTo("Cairo");
        assertThat(raw.path("customer").path("firstName").asText()).isEqualTo("Nour");
        assertThat(raw.path("billingAddress").path("name").asText()).isEqualTo("Old Bill");

        // A stored REST shipping_address never comes back next to a fresh GraphQL shippingAddress.
        String mixed = gid();
        TenantContext.runAs(tenant, () -> appSync.ingestOrderWebhook(store, tenant, rest(mixed, "#D202")));
        importOrder(mixed, "#D202", "{\"id\":\"%s\",\"shippingAddress\":{\"city\":\"Aswan\"}}");
        JsonNode m = raw(mixed);
        assertThat(m.has("shipping_address")).isFalse();
        assertThat(m.path("shippingAddress").path("city").asText()).isEqualTo("Aswan");
        assertThat(m.path("customer").path("first_name").asText()).isEqualTo("Mona");
    }

    @Test
    void aWebhookStillReplacesTheWholePayload() throws Exception {
        String gid = gid();
        importOrder(gid, "#D301", "{\"id\":\"%s\",\"customer\":{\"id\":\"gid://shopify/Customer/9\",\"firstName\":\"Z\"}}");
        ObjectNode later = rest(gid, "#D301");
        later.remove("customer");
        TenantContext.runAs(tenant, () -> appSync.ingestOrderWebhook(store, tenant, later));
        JsonNode raw = raw(gid);
        assertThat(raw.has("customer")).isFalse();                                            // webhook wins, as before
        assertThat(raw.has("lineItems") || raw.has("id")).isFalse();
        assertThat(raw.path("admin_graphql_api_id").asText()).isEqualTo(gid);
    }

    @Test
    void aRedactedOrder_neverGetsCustomerDataBackFromAnImport() throws Exception {
        String gid = gid();
        TenantContext.runAs(tenant, () -> appSync.ingestOrderWebhook(store, tenant, rest(gid, "#D401")));
        jdbc.update("UPDATE orders SET raw = shopify_order_raw_redacted(raw), customer_name = NULL, customer_phone = NULL, " +
                    "shopify_address = NULL, pii_redacted_at = now() WHERE external_id = ?", gid);
        importOrder(gid, "#D401", "{\"id\":\"%s\"}");
        JsonNode raw = raw(gid);
        assertThat(raw.has("customer")).isFalse();
        assertThat(raw.has("shipping_address")).isFalse();
        assertThat(jdbc.queryForObject("SELECT customer_phone FROM orders WHERE external_id = ?", String.class, gid)).isNull();
    }

    // ── the one-off restore ──────────────────────────────────────────────────

    @Test
    void restore_putsTheGroupsBackFromTheLatestWebhook_fillOnly_neverRedacted_idempotent() throws Exception {
        String hit = gid(), redacted = gid(), own = gid(), noWebhook = gid();
        // Affected: a GraphQL payload without customer data; its webhooks are stored (latest = phone 222).
        seed(hit, "{\"id\":\"%s\",\"displayFinancialStatus\":\"PAID\"}".formatted(hit), null, false);
        webhook(rest(hit, "#R1").put("note", "older"), "2026-09-01T10:00:00Z");
        ObjectNode latest = rest(hit, "#R1");
        ((ObjectNode) latest.get("shipping_address")).put("phone", "+201002220000");
        webhook(latest, "2026-09-02T10:00:00Z");
        // Redacted: never restored.
        seed(redacted, "{\"id\":\"%s\"}".formatted(redacted), null, true);
        webhook(rest(redacted, "#R2"), "2026-09-02T10:00:00Z");
        // Has its own (fresher) shipping: only the missing groups come back.
        seed(own, "{\"id\":\"%s\",\"shippingAddress\":{\"city\":\"Luxor\"}}".formatted(own), "Kept Name", false);
        webhook(rest(own, "#R3"), "2026-09-02T10:00:00Z");
        // No stored webhook: nothing to restore from.
        seed(noWebhook, "{\"id\":\"%s\"}".formatted(noWebhook), null, false);

        int changed = restore();
        assertThat(changed).isEqualTo(2);
        JsonNode h = raw(hit);
        assertThat(h.path("displayFinancialStatus").asText()).isEqualTo("PAID");
        assertThat(h.path("shipping_address").path("phone").asText()).isEqualTo("+201002220000");   // the latest webhook
        assertThat(h.path("customer").path("first_name").asText()).isEqualTo("Mona");
        Map<String, Object> hc = jdbc.queryForMap("SELECT customer_name, customer_phone, pii_source FROM orders WHERE external_id = ?", hit);
        assertThat(hc).containsEntry("customer_name", "Mona Ship").containsEntry("customer_phone", "+201002220000")
            .containsEntry("pii_source", "shopify");
        assertThat(raw(redacted).has("customer")).isFalse();
        assertThat(jdbc.queryForMap("SELECT customer_name, customer_phone, shopify_address FROM orders WHERE external_id = ?", redacted))
            .containsEntry("customer_name", null).containsEntry("customer_phone", null).containsEntry("shopify_address", null);
        JsonNode o = raw(own);
        assertThat(o.path("shippingAddress").path("city").asText()).isEqualTo("Luxor");
        assertThat(o.has("shipping_address")).isFalse();
        assertThat(o.path("customer").path("first_name").asText()).isEqualTo("Mona");
        assertThat(jdbc.queryForObject("SELECT customer_name FROM orders WHERE external_id = ?", String.class, own))
            .isEqualTo("Kept Name");                                                                  // fill-only
        assertThat(raw(noWebhook).has("customer")).isFalse();
        assertThat(customerKey(hit)).isEqualTo("c:4242");

        List<Map<String, Object>> before = jdbc.queryForList("SELECT external_id, raw::text, customer_name, customer_phone FROM orders ORDER BY external_id");
        assertThat(restore()).isZero();                                                               // idempotent
        assertThat(jdbc.queryForList("SELECT external_id, raw::text, customer_name, customer_phone FROM orders ORDER BY external_id"))
            .isEqualTo(before);
    }

    @Test
    void keepCustomer_isTotal() {
        assertThat(jdbc.queryForObject("SELECT shopify_order_raw_keep_customer(NULL, '{\"a\":1}'::jsonb)::text", String.class))
            .isEqualTo("{\"a\": 1}");
        assertThat(jdbc.queryForObject("SELECT shopify_order_raw_keep_customer('[1]'::jsonb, '{\"a\":1}'::jsonb)::text", String.class))
            .isEqualTo("{\"a\": 1}");
        assertThat(jdbc.queryForObject("SELECT shopify_order_raw_keep_customer('{\"customer\":{\"id\":1}}'::jsonb, NULL)", String.class))
            .isNull();
        assertThat(jdbc.queryForObject("SELECT shopify_order_raw_keep_customer('{\"customer\":null}'::jsonb, '{}'::jsonb)::text", String.class))
            .isEqualTo("{}");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The restore statement of V156 (everything from its WITH), run again. */
    private int restore() throws Exception {
        String sql = new String(new ClassPathResource("db/migration/V156__order_payload_keep_customer.sql")
            .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return jdbc.update(sql.substring(sql.indexOf("WITH wh AS")));
    }

    private static String gid() {
        return "gid://shopify/Order/" + ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L);
    }

    /** A REST (webhook) order payload with full customer data. */
    private ObjectNode rest(String gid, String name) {
        ObjectNode n = mapper.createObjectNode();
        n.put("admin_graphql_api_id", gid).put("name", name).put("financial_status", "pending")
         .put("current_total_price", "250.00").put("created_at", Instant.now().toString()).put("phone", "+201000000001");
        n.putObject("customer").put("id", 4242).put("first_name", "Mona").put("last_name", "Cust").put("phone", "+201000000009");
        n.putObject("shipping_address").put("name", "Mona Ship").put("phone", "+201001112222").put("address1", "12 Nile St")
         .put("city", "Cairo").put("country", "Egypt");
        n.putObject("billing_address").put("name", "Bill Name").put("phone", "+201000000008");
        n.putArray("line_items");
        return n;
    }

    private void importOrder(String gid, String number, String nodeTemplate) throws Exception {
        JsonNode node = mapper.readTree(nodeTemplate.formatted(gid));
        ShopifyGateway.Order o = new ShopifyGateway.Order(gid, number, null, null, null, "paid", List.of(),
            new BigDecimal("100.00"), List.of(), Instant.now(), node);
        TenantContext.runAs(tenant, () -> appSync.ingestMissingOrder(store, tenant, o));
    }

    private void seed(String gid, String raw, String name, boolean redacted) {
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
                    "    customer_name, pii_redacted_at, raw) " +
                    "VALUES (?, ?, ?, ?, 'new', 'cod', now(), ?, " + (redacted ? "now()" : "NULL") + ", ?::jsonb)",
                    tenant, store, gid, "#S" + gid.substring(gid.length() - 6), name, raw);
    }

    private void webhook(ObjectNode payload, String receivedAt) {
        jdbc.update("INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw, received_at) " +
                    "VALUES (?, 'orders/updated', 'downgrade-co.myshopify.com', ?, ?::jsonb, ?::timestamptz)",
                    tenant, UUID.randomUUID().toString(), payload.toString(), receivedAt);
    }

    private JsonNode raw(String gid) throws Exception {
        return mapper.readTree(jdbc.queryForObject("SELECT raw::text FROM orders WHERE external_id = ?", String.class, gid));
    }

    private String customerKey(String gid) {
        return jdbc.queryForObject("SELECT customer_key FROM orders WHERE external_id = ?", String.class, gid);
    }
}
