package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.account.AuditService;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaStateMapper;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifySameShopGuard;
import com.traceability.integrations.shopify.ShopifySyncService;
import com.traceability.inventory.BlocklistService;
import com.traceability.inventory.ExchangeMatchService;
import com.traceability.inventory.InventoryLedger;
import com.traceability.inventory.NotTracedTagger;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build B (V144/V145): customer name, phone and address on Shopify orders. Every ingest / link path runs
 * on an app_user connection (RLS ON, the tenant set only through TenantContext). The backfill procedure
 * is CALLed as the owner role, exactly as Flyway runs V145.
 *
 *   b1 — webhook: a new order gets name / phone / shopify_address from the shipping address, pii_source
 *        'shopify', orders.address untouched; no email in orders.raw nor in the stored payload_raw.
 *   b2 — import (GraphQL node), with read_customers (customer block present) and without it (no customer
 *        block): precedence shipping → customer → billing, both shapes.
 *   b3 — a Bosta-filled name / phone survives a later orders/updated; shopify_address still fills.
 *   b4 — a later Bosta link keeps a Shopify name / phone / pii_source and only fills the empty address.
 *   b5 — a redacted order is not refilled (webhook path), shopify_address included.
 *   b6 — the blocklist gate holds a blocked phone at order creation.
 *   b7 — the backfill fills only after-floor, non-redacted, REST-raw rows, fill-only, pii_source only on
 *        rows it filled; strips email from every order and stored webhook; a re-run changes nothing.
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyPiiIngestTest {

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

    static final String EMAIL = "mona.customer@example.com";

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EncryptionService encryption;
    @Autowired AuditService auditService;
    @Autowired ShopifySameShopGuard sameShopGuard;
    @Autowired InventoryLedger ledger;
    @Autowired BostaStateMapper stateMapper;
    @Autowired NotTracedTagger notTracedTagger;
    @Autowired ExchangeMatchService exchangeMatchService;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean BostaGateway bostaGateway;

    UUID tenant, store;
    JdbcTemplate appJdbc;
    TransactionTemplate appTx;
    ShopifySyncService appSync;
    ShipmentLinkService appLinks;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID();
        store = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'PII Co')", tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, access_token_encrypted, " +
                    "    access_token_expires_at) VALUES (?, ?, 'shopify', 'pii-co.myshopify.com', 'connected', ?, " +
                    "    now() + interval '876000 hours')", store, tenant, encryption.encrypt("shpat_pii"));
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(ds);
        DataSourceTransactionManager txm = new DataSourceTransactionManager(ds);
        appTx = new TransactionTemplate(txm);
        BlocklistService appBlocklist = new BlocklistService(appJdbc, auditService, txm);
        appSync = new ShopifySyncService(appJdbc, shopifyGateway, encryption, mapper, txm, appBlocklist, sameShopGuard, 30);
        appLinks = new ShipmentLinkService(appJdbc, ledger, stateMapper, bostaGateway, encryption, appBlocklist,
                mapper, notTracedTagger, exchangeMatchService);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM shopify_webhook_events");
        jdbc.update("DELETE FROM blocklist");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM stores WHERE id <> ?", store);
    }

    // ── b1 ────────────────────────────────────────────────────────────────────

    @Test
    void b1_webhook_newOrder_getsNamePhoneShopifyAddress_noEmailStored() {
        String gid = gid();
        TenantContext.runAs(tenant, () -> appSync.ingestOrderWebhook(store, tenant, rest(gid, "#B101")));

        Map<String, Object> o = order(gid);
        assertThat(o).containsEntry("customer_name", "Mona Ship").containsEntry("customer_phone", "+201001112222")
            .containsEntry("pii_source", "shopify").containsEntry("address", null)
            .containsEntry("email_in_raw", false);
        assertThat(json(o.get("shopify_address"))).isEqualTo(json(
            "{\"address1\":\"12 Nile St\",\"address2\":\"Flat 4\",\"city\":\"Cairo\",\"province\":\"Cairo\"," +
            "\"zip\":\"11511\",\"country\":\"Egypt\"}"));
        assertThat(jdbc.queryForObject("SELECT raw #>> '{customer,verified_email}' FROM orders WHERE external_id = ?",
            String.class, gid)).as("a boolean *_email flag is not an address — it stays").isEqualTo("true");

        // The stored webhook (written as app_user, after HMAC) keeps no email anywhere.
        String payload = rest(gid, "#B101").toString();
        UUID ev = TenantContext.runAs(tenant, () -> appTx.execute(s -> appJdbc.queryForObject(
            "INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw) " +
            "VALUES (?, 'orders/create', 'pii-co.myshopify.com', ?, ?::jsonb) RETURNING id",
            UUID.class, tenant, "wh-" + UUID.randomUUID(), payload)));
        String stored = jdbc.queryForObject("SELECT payload_raw::text FROM shopify_webhook_events WHERE id = ?", String.class, ev);
        assertThat(stored).doesNotContain(EMAIL).doesNotContain("\"email\"").doesNotContain("contact_email")
            .contains("\"verified_email\"").contains("Mona Ship");
    }

    // ── b2 ────────────────────────────────────────────────────────────────────

    @Test
    void b2_import_withAndWithoutReadCustomers_precedenceShippingCustomerBilling() throws Exception {
        // With read_customers: the shipping block has no name / phone → the customer block wins over billing.
        String full = gid();
        importOrder(full, "#B201", """
            {"id":"%s","shippingAddress":{"name":"  ","address1":"7 Tahrir","city":"Giza","country":"Egypt"},
             "billingAddress":{"name":"Bill Name","phone":"+201000000003"},
             "customer":{"firstName":"Mona","lastName":"Cust","defaultPhoneNumber":{"phoneNumber":"+201000000002"}}}""");
        assertThat(order(full)).containsEntry("customer_name", "Mona Cust").containsEntry("customer_phone", "+201000000002")
            .containsEntry("pii_source", "shopify");
        assertThat(json(order(full).get("shopify_address")))
            .isEqualTo(json("{\"address1\":\"7 Tahrir\",\"city\":\"Giza\",\"country\":\"Egypt\"}"));

        // Without read_customers (Jumi's token): no customer block → billing is next.
        String noCustomers = gid();
        importOrder(noCustomers, "#B202", """
            {"id":"%s","shippingAddress":{"address1":"9 Corniche","city":"Alexandria"},
             "billingAddress":{"name":"Bill Only","phone":"+201000000004"}}""");
        assertThat(order(noCustomers)).containsEntry("customer_name", "Bill Only").containsEntry("customer_phone", "+201000000004");

        // Shipping name / phone present → they win over everything else.
        String shipping = gid();
        importOrder(shipping, "#B203", """
            {"id":"%s","shippingAddress":{"name":"Ship Name","phone":"+201000000005"},
             "billingAddress":{"name":"Bill","phone":"+201000000006"},
             "customer":{"firstName":"C","lastName":"D","defaultPhoneNumber":{"phoneNumber":"+201000000007"}}}""");
        assertThat(order(shipping)).containsEntry("customer_name", "Ship Name").containsEntry("customer_phone", "+201000000005");

        // No PII at all (the pre-Build-B node) → nothing set, nothing fails.
        String none = gid();
        importOrder(none, "#B204", "{\"id\":\"%s\"}");
        assertThat(order(none)).containsEntry("customer_name", null).containsEntry("pii_source", null)
            .containsEntry("shopify_address", null);
    }

    // ── b3 ────────────────────────────────────────────────────────────────────

    @Test
    void b3_bostaFilledName_notOverwritten_byOrdersUpdated() {
        String gid = gid();
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
                    "    customer_name, customer_phone, address, pii_source) VALUES (?, ?, ?, '#B301', 'new', 'cod', now(), " +
                    "    'Bosta Receiver', '01099999999', '{\"city\":\"Tanta\"}'::jsonb, 'bosta')", tenant, store, gid);

        TenantContext.runAs(tenant, () -> appSync.ingestOrderWebhook(store, tenant, rest(gid, "#B301")));

        Map<String, Object> o = order(gid);
        assertThat(o).containsEntry("customer_name", "Bosta Receiver").containsEntry("customer_phone", "01099999999")
            .containsEntry("pii_source", "bosta");
        assertThat(json(o.get("address"))).as("orders.address stays Bosta's").isEqualTo(json("{\"city\":\"Tanta\"}"));
        assertThat(json(o.get("shopify_address")).path("city").asText()).as("an empty field still fills").isEqualTo("Cairo");
    }

    // ── b4 ────────────────────────────────────────────────────────────────────

    @Test
    void b4_laterBostaLink_keepsShopifyNamePhoneAndPiiSource_fillsOnlyTheEmptyAddress() throws Exception {
        String gid = gid();
        TenantContext.runAs(tenant, () -> appSync.ingestOrderWebhook(store, tenant, rest(gid, "#B401")));
        UUID orderId = (UUID) order(gid).get("id");

        JsonNode bosta = mapper.readTree("""
            {"type":{"code":10},"receiver":{"fullName":"Bosta Receiver","phone":"+201099999999"},
             "dropOffAddress":{"firstLine":"Bosta street","city":{"name":"Cairo"},"zone":{"name":"Maadi"}}}""");
        TenantContext.runAs(tenant, () -> appTx.execute(s -> {
            appLinks.populateConsigneePii(orderId, tenant, new BostaDelivery("1234567890", 24, "SEND", 0, "#B401", null, bosta));
            return null;
        }));

        Map<String, Object> o = order(gid);
        assertThat(o).containsEntry("customer_name", "Mona Ship").containsEntry("customer_phone", "+201001112222")
            .containsEntry("pii_source", "shopify");
        assertThat(json(o.get("address")).path("zone").asText()).as("Bosta still fills its own empty address")
            .isEqualTo("Maadi");

        // And on an order with nothing at all, Bosta's fill records 'bosta'.
        String bare = gid();
        importOrder(bare, "#B402", "{\"id\":\"%s\"}");
        UUID bareId = (UUID) order(bare).get("id");
        TenantContext.runAs(tenant, () -> appTx.execute(s -> {
            appLinks.populateConsigneePii(bareId, tenant, new BostaDelivery("1234567891", 24, "SEND", 0, "#B402", null, bosta));
            return null;
        }));
        assertThat(order(bare)).containsEntry("customer_name", "Bosta Receiver").containsEntry("pii_source", "bosta");
    }

    // ── b5 ────────────────────────────────────────────────────────────────────

    @Test
    void b5_redactedOrder_notRefilled_byWebhook() {
        String gid = gid();
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
                    "    pii_redacted_at, raw) VALUES (?, ?, ?, '#B501', 'delivered', 'cod', now(), now(), '{}'::jsonb)",
                    tenant, store, gid);

        TenantContext.runAs(tenant, () -> appSync.ingestOrderWebhook(store, tenant, rest(gid, "#B501")));

        assertThat(order(gid)).containsEntry("customer_name", null).containsEntry("customer_phone", null)
            .containsEntry("shopify_address", null).containsEntry("pii_source", null)
            .containsEntry("customer_in_raw", false).containsEntry("email_in_raw", false);
    }

    // ── b6 ────────────────────────────────────────────────────────────────────

    @Test
    void b6_blocklist_holdsBlockedPhone_atOrderCreation() {
        jdbc.update("INSERT INTO blocklist (tenant_id, phone_canonical, reason) VALUES (?, '01001112222', 'fraud')", tenant);
        String gid = gid();

        TenantContext.runAs(tenant, () -> appSync.ingestOrderWebhook(store, tenant, rest(gid, "#B601")));

        assertThat(jdbc.queryForMap("SELECT on_hold, hold_reason FROM orders WHERE external_id = ?", gid))
            .containsEntry("on_hold", true).containsEntry("hold_reason", "blocked_customer: fraud");
    }

    // ── b7 ────────────────────────────────────────────────────────────────────

    @Test
    void b7_backfill_afterFloorNonRedactedEmptyOnly_stripsEmail_idempotent() {
        UUID floored = UUID.randomUUID(), jumi = UUID.randomUUID(), noFloor = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                    "VALUES (?, ?, 'shopify', 'floored.myshopify.com', 'connected', '2026-08-01T00:00:00Z')", floored, tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                    "VALUES (?, ?, 'shopify', 'mmi24e-fx.myshopify.com', 'connected', NULL)", jumi, tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                    "VALUES (?, ?, 'shopify', 'nofloor.myshopify.com', 'connected', NULL)", noFloor, tenant);

        // Pre-V144 rows still hold email: seed with the strip trigger off, as they exist in prod today.
        jdbc.execute("ALTER TABLE orders DISABLE TRIGGER orders_strip_email_from_raw");
        jdbc.execute("ALTER TABLE shopify_webhook_events DISABLE TRIGGER shopify_webhook_events_strip_email");
        String fill, before, jumiAfter, jumiBefore, nofloorOrder, redacted, graphql, bosta;
        try {
            fill         = seed(floored, "#F1", "2026-09-01T10:00:00Z", null, null, null, false, rest(gid(), "#F1"));
            before       = seed(floored, "#F2", "2026-07-15T10:00:00Z", null, null, null, false, rest(gid(), "#F2"));
            jumiAfter    = seed(jumi,    "#J1", "2026-07-02T09:00:00+03:00", null, null, null, false, rest(gid(), "#J1"));
            jumiBefore   = seed(jumi,    "#J2", "2026-07-01T23:00:00+03:00", null, null, null, false, rest(gid(), "#J2"));
            nofloorOrder = seed(noFloor, "#N1", "2026-09-01T10:00:00Z", null, null, null, false, rest(gid(), "#N1"));
            redacted     = seed(floored, "#R1", "2026-09-01T10:00:00Z", null, null, null, true, rest(gid(), "#R1"));
            graphql      = seed(floored, "#G1", "2026-09-01T10:00:00Z", null, null, null, false,
                               mapper.createObjectNode().put("id", "x").put("email", EMAIL));
            bosta        = seed(floored, "#B1", "2026-09-01T10:00:00Z", "Bosta Name", "01099999999", "bosta", false,
                               rest(gid(), "#B1"));
            jdbc.update("INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw) " +
                        "VALUES (?, 'orders/create', 'floored.myshopify.com', 'wh-b7', ?::jsonb)", tenant, rest(gid(), "#E1").toString());
        } finally {
            jdbc.execute("ALTER TABLE orders ENABLE TRIGGER orders_strip_email_from_raw");
            jdbc.execute("ALTER TABLE shopify_webhook_events ENABLE TRIGGER shopify_webhook_events_strip_email");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE raw::text LIKE ?", Integer.class, "%" + EMAIL + "%"))
            .as("precondition: email stored").isEqualTo(8);

        jdbc.execute("CALL shopify_pii_backfill(2)");   // small batches exercise the keyset loop

        assertThat(order(fill)).containsEntry("customer_name", "Mona Ship").containsEntry("customer_phone", "+201001112222")
            .containsEntry("pii_source", "shopify");
        assertThat(order(fill).get("shopify_address")).isNotNull();
        assertThat(order(jumiAfter)).as("Jumi: on/after 2026-07-02 Cairo").containsEntry("customer_name", "Mona Ship");
        for (String skipped : List.of(before, jumiBefore, nofloorOrder, redacted, graphql)) {
            assertThat(order(skipped)).as(skipped).containsEntry("customer_name", null).containsEntry("customer_phone", null)
                .containsEntry("shopify_address", null).containsEntry("pii_source", null);
        }
        assertThat(order(bosta)).as("fill-only: Bosta's name/phone/source stay, the empty address fills")
            .containsEntry("customer_name", "Bosta Name").containsEntry("customer_phone", "01099999999")
            .containsEntry("pii_source", "bosta");
        assertThat(order(bosta).get("shopify_address")).isNotNull();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE raw::text LIKE ?", Integer.class, "%" + EMAIL + "%"))
            .as("email stripped from every order — before-floor and redacted included").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shopify_webhook_events WHERE payload_raw::text LIKE ?",
            Integer.class, "%" + EMAIL + "%")).isZero();

        // Idempotent: a second run changes nothing.
        List<Map<String, Object>> snapshot = jdbc.queryForList(
            "SELECT id, customer_name, customer_phone, shopify_address::text, pii_source, raw::text FROM orders ORDER BY id");
        jdbc.execute("CALL shopify_pii_backfill(2)");
        assertThat(jdbc.queryForList(
            "SELECT id, customer_name, customer_phone, shopify_address::text, pii_source, raw::text FROM orders ORDER BY id"))
            .isEqualTo(snapshot);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String gid() {
        return "gid://shopify/Order/" + ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L);
    }

    /** A REST (webhook) order payload with full PII — and email in three places. */
    private ObjectNode rest(String gid, String name) {
        ObjectNode n = mapper.createObjectNode();
        n.put("admin_graphql_api_id", gid).put("name", name).put("email", EMAIL).put("contact_email", EMAIL)
         .put("financial_status", "pending").put("current_total_price", "250.00").put("created_at", Instant.now().toString());
        n.putObject("customer").put("first_name", "Mona").put("last_name", "Cust").put("email", EMAIL)
         .put("verified_email", true).put("phone", "+201000000009");
        n.putObject("shipping_address").put("name", "  Mona Ship ").put("phone", "+201001112222").put("address1", "12 Nile St")
         .put("address2", "Flat 4").put("city", "Cairo").put("province", "Cairo").put("zip", "11511").put("country", "Egypt");
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

    private String seed(UUID storeId, String number, String placedAt, String name, String phone, String source,
                        boolean redacted, JsonNode raw) {
        String gid = raw.path("admin_graphql_api_id").asText(gid());
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
                    "    customer_name, customer_phone, pii_source, pii_redacted_at, raw) " +
                    "VALUES (?, ?, ?, ?, 'new', 'cod', ?::timestamptz, ?, ?, ?, " + (redacted ? "now()" : "NULL") + ", ?::jsonb)",
                    tenant, storeId, gid, number, placedAt, name, phone, source, raw.toString());
        return gid;
    }

    private Map<String, Object> order(String gid) {
        return jdbc.queryForMap(
            "SELECT id, customer_name, customer_phone, address::text AS address, shopify_address::text AS shopify_address, " +
            "       pii_source, jsonb_exists(raw, 'customer') AS customer_in_raw, " +
            "       (raw::text LIKE '%' || 'example.com' || '%') AS email_in_raw " +
            "FROM orders WHERE external_id = ?", gid);
    }

    private JsonNode json(Object text) {
        try { return mapper.readTree((String) text); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
