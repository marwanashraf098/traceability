package com.traceability;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyImportJob;
import com.traceability.integrations.shopify.ShopifyWebhookProcessorJob;
import com.traceability.notifications.EmailGateway;
import com.traceability.security.EncryptionService;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Part A regression tests for the customers/redact touch-up (FR-3.3 fix).
 *
 * A1: redact is now scoped to orders_to_redact order IDs, not a broad customer match.
 * A2: client_details and note_attributes are also stripped from orders.raw.
 *
 * Critical regression: a customer with two orders where orders_to_redact lists only ONE
 * must leave the other order's customer_name/phone/address byte-for-byte intact.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShopifyRedactTouchupTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate           jdbc;
    @Autowired ShopifyWebhookProcessorJob processorJob;
    @Autowired EncryptionService       encryptionService;

    @MockBean ShopifyGateway  shopifyGateway;
    @MockBean JobScheduler    jobScheduler;
    @MockBean ShopifyImportJob importJob;
    @MockBean EmailGateway    emailGateway;

    @Value("${shopify.client-secret}")
    String clientSecret;

    private UUID   tenantId;
    private UUID   storeId;
    private String shopDomain = "redact-touchup.myshopify.com";

    // Two orders for the same customer
    private UUID   order1Id;   // will be in orders_to_redact
    private UUID   order2Id;   // must survive intact
    private static final long SHOPIFY_CUSTOMER_ID = 12345L;
    private static final String CUSTOMER_NAME  = "Test Customer";
    private static final String CUSTOMER_PHONE = "+201012345678";

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        order1Id = UUID.randomUUID();
        order2Id = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantId, "Redact Corp");
        String encToken = encryptionService.encrypt("redact_raw_token");
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, platform, access_token_encrypted, " +
            "status, import_status) VALUES (?, ?, ?, 'shopify', ?, 'connected', 'pending')",
            storeId, tenantId, shopDomain, encToken);
    }

    @BeforeEach
    void insertOrders() {
        // Raw jsonb includes customer, client_details, note_attributes — the full set
        String raw1 = "{\"id\":501,\"customer\":{\"id\":" + SHOPIFY_CUSTOMER_ID
            + ",\"email\":\"test@customer.com\"},\"client_details\":{\"user_agent\":\"Mozilla\",\"ip\":\"1.2.3.4\"},"
            + "\"note_attributes\":[{\"name\":\"delivery\",\"value\":\"Ring bell\"}]}";
        String raw2 = "{\"id\":502,\"customer\":{\"id\":" + SHOPIFY_CUSTOMER_ID
            + ",\"email\":\"test@customer.com\"},\"client_details\":{\"user_agent\":\"Mozilla\",\"ip\":\"5.6.7.8\"},"
            + "\"note_attributes\":[{\"name\":\"note\",\"value\":\"Some note\"}]}";

        jdbc.update(
            "INSERT INTO orders (id, tenant_id, store_id, external_id, number, customer_name, " +
            "customer_phone, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, '#501', ?, ?, 'prepaid'::order_payment_method, now(), ?::jsonb)",
            order1Id, tenantId, storeId, "gid://shopify/Order/501",
            CUSTOMER_NAME, CUSTOMER_PHONE, raw1);
        jdbc.update(
            "INSERT INTO orders (id, tenant_id, store_id, external_id, number, customer_name, " +
            "customer_phone, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, '#502', ?, ?, 'prepaid'::order_payment_method, now(), ?::jsonb)",
            order2Id, tenantId, storeId, "gid://shopify/Order/502",
            CUSTOMER_NAME, CUSTOMER_PHONE, raw2);
    }

    @AfterEach
    void cleanOrders() {
        jdbc.update("DELETE FROM shopify_webhook_events WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    // -----------------------------------------------------------------------
    // 1. REGRESSION: two orders, orders_to_redact lists only ONE → the OTHER
    //    order's customer_name/phone/address survive byte-for-byte intact.
    // -----------------------------------------------------------------------
    @Test
    @Order(1)
    void customersRedact_scopedToOrdersToRedact_otherOrderUntouched() {
        // Payload: orders_to_redact lists order 501 only; order 502 is active (not listed)
        String payloadJson = """
            {
              "shop_id": 999,
              "shop_domain": "%s",
              "customer": {"id": %d, "email": "test@customer.com", "phone": "%s"},
              "orders_to_redact": [{"id": 501, "name": "#501"}]
            }
            """.formatted(shopDomain, SHOPIFY_CUSTOMER_ID, CUSTOMER_PHONE);

        byte[] body = payloadJson.getBytes(StandardCharsets.UTF_8);
        UUID eventId = insertEvent("customers/redact", body);
        processorJob.process(eventId, tenantId);

        // Order 501: PII must be nulled
        Map<String, Object> order1 = jdbc.queryForMap(
            "SELECT customer_name, customer_phone, address FROM orders WHERE id = ?", order1Id);
        assertThat(order1.get("customer_name")).isNull();
        assertThat(order1.get("customer_phone")).isNull();
        assertThat(order1.get("address")).isNull();

        // Order 502: ALL PII must survive byte-for-byte — this is the critical regression assertion
        Map<String, Object> order2 = jdbc.queryForMap(
            "SELECT customer_name, customer_phone, address FROM orders WHERE id = ?", order2Id);
        assertThat(order2.get("customer_name")).isEqualTo(CUSTOMER_NAME);
        assertThat(order2.get("customer_phone")).isEqualTo(CUSTOMER_PHONE);
        assertThat(order2.get("address")).isNull(); // was already null, still null
    }

    // -----------------------------------------------------------------------
    // 2. Empty orders_to_redact → nothing changed
    // -----------------------------------------------------------------------
    @Test
    @Order(2)
    void customersRedact_emptyOrdersToRedact_noRowsChanged() {
        String payloadJson = """
            {
              "shop_id": 999,
              "shop_domain": "%s",
              "customer": {"id": %d},
              "orders_to_redact": []
            }
            """.formatted(shopDomain, SHOPIFY_CUSTOMER_ID);

        byte[] body = payloadJson.getBytes(StandardCharsets.UTF_8);
        UUID eventId = insertEvent("customers/redact", body);
        processorJob.process(eventId, tenantId);

        // Both orders: customer_name must still be set (nothing was redacted)
        Long untouched = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND customer_name = ?",
            Long.class, tenantId, CUSTOMER_NAME);
        assertThat(untouched).isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // 3. client_details and note_attributes are removed from orders.raw
    // -----------------------------------------------------------------------
    @Test
    @Order(3)
    void customersRedact_clientDetailsAndNoteAttributes_strippedFromRaw() {
        String payloadJson = """
            {
              "shop_id": 999,
              "shop_domain": "%s",
              "customer": {"id": %d},
              "orders_to_redact": [{"id": 501, "name": "#501"}]
            }
            """.formatted(shopDomain, SHOPIFY_CUSTOMER_ID);

        byte[] body = payloadJson.getBytes(StandardCharsets.UTF_8);
        UUID eventId = insertEvent("customers/redact", body);
        processorJob.process(eventId, tenantId);

        // Order 501's raw must not contain client_details or note_attributes
        String raw1 = jdbc.queryForObject(
            "SELECT raw::text FROM orders WHERE id = ?", String.class, order1Id);
        assertThat(raw1).doesNotContain("client_details");
        assertThat(raw1).doesNotContain("note_attributes");
        assertThat(raw1).doesNotContain("Mozilla");
        assertThat(raw1).doesNotContain("Ring bell");

        // Order 502's raw must still contain client_details and note_attributes (not listed)
        String raw2 = jdbc.queryForObject(
            "SELECT raw::text FROM orders WHERE id = ?", String.class, order2Id);
        assertThat(raw2).contains("client_details");
        assertThat(raw2).contains("note_attributes");
    }

    // -----------------------------------------------------------------------
    // 4. piece_events is INSERT-only and must not be touched
    // -----------------------------------------------------------------------
    @Test
    @Order(4)
    void customersRedact_pieceEventsUntouched() {
        // Insert a piece + event so there is something in piece_events to NOT touch
        String pieceId = "01HPIECEDAY4REDACT001";
        UUID locationId = UUID.randomUUID();
        UUID productId  = UUID.randomUUID();
        UUID variantId  = UUID.randomUUID();
        UUID receiptId  = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default) VALUES " +
            "(?, ?, 'Redact Loc', 'warehouse', false)", locationId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/401', 'Redact Product', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title) " +
            "VALUES (?, ?, ?, 'gid://shopify/ProductVariant/401', 'SKU-R01', 'Variant R')",
            variantId, tenantId, productId);
        jdbc.update(
            "INSERT INTO receipts (id, tenant_id, location_id, reference) " +
            "VALUES (?, ?, ?, 'REF-REDACT')", receiptId, tenantId, locationId);
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available')",
            pieceId, tenantId, variantId, "BAR-REDACT-001", pieceId);
        jdbc.update(
            "INSERT INTO piece_events (piece_id, tenant_id, from_status, to_status, event_type, " +
            "actor_user_id, location_id) VALUES (?, ?, NULL, 'available', 'received', NULL, ?)",
            pieceId, tenantId, locationId);

        long eventsBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE tenant_id = ?", Long.class, tenantId);

        String payloadJson = """
            {
              "shop_id": 999,
              "shop_domain": "%s",
              "customer": {"id": %d},
              "orders_to_redact": [{"id": 501, "name": "#501"}]
            }
            """.formatted(shopDomain, SHOPIFY_CUSTOMER_ID);
        byte[] body = payloadJson.getBytes(StandardCharsets.UTF_8);
        UUID eventId = insertEvent("customers/redact", body);
        processorJob.process(eventId, tenantId);

        long eventsAfter = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE tenant_id = ?", Long.class, tenantId);
        assertThat(eventsAfter).isEqualTo(eventsBefore);
    }

    // -----------------------------------------------------------------------
    // 5. shop/redact is still tenant-wide (unchanged in breadth)
    // -----------------------------------------------------------------------
    @Test
    @Order(5)
    void shopRedact_stillTenantWide_erasesAllOrders() {
        String payloadJson = """
            { "shop_id": 999, "shop_domain": "%s" }
            """.formatted(shopDomain);

        byte[] body = payloadJson.getBytes(StandardCharsets.UTF_8);
        UUID eventId = insertEvent("shop/redact", body);
        processorJob.process(eventId, tenantId);

        // Both orders must have PII nulled — shop/redact is tenant-wide
        Long nulledCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND customer_name IS NULL",
            Long.class, tenantId);
        assertThat(nulledCount).isEqualTo(2);

        // Also verify client_details and note_attributes are stripped from raw in both
        for (UUID orderId : List.of(order1Id, order2Id)) {
            String raw = jdbc.queryForObject(
                "SELECT raw::text FROM orders WHERE id = ?", String.class, orderId);
            assertThat(raw).doesNotContain("client_details");
            assertThat(raw).doesNotContain("note_attributes");
        }
    }

    // ---- helpers -----------------------------------------------------------

    private UUID insertEvent(String topic, byte[] body) {
        String[] parts = topic.split("/", 2);
        String type   = parts[0];
        String action = parts.length > 1 ? parts[1] : parts[0];

        String hmac = webhookHmac(body);
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Shopify-Hmac-Sha256", hmac);
        headers.set("X-Shopify-Shop-Domain", shopDomain);
        headers.set("X-Shopify-Webhook-Id", "redact-wh-" + UUID.randomUUID());
        headers.set("Content-Type", "application/json");
        headers.setContentLength(body.length);

        // POST directly to insert via controller path (populates shopify_webhook_events)
        var entity = new org.springframework.http.HttpEntity<>(body, headers);
        // Instead of going through HTTP, insert directly (same approach as Day 3 tests)
        // Insert directly for test isolation
        String webhookId = "redact-" + UUID.randomUUID();
        jdbc.update(
            "INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw) " +
            "VALUES (?, ?, ?, ?, ?::jsonb)",
            tenantId, topic, shopDomain, webhookId, new String(body, StandardCharsets.UTF_8));
        return jdbc.queryForObject(
            "SELECT id FROM shopify_webhook_events WHERE webhook_id = ?", UUID.class, webhookId);
    }

    private String webhookHmac(byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(rawBody));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
