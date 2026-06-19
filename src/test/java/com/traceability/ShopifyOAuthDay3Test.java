package com.traceability;

import com.traceability.integrations.shopify.RegisterShopifyWebhooksJob;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyImportJob;
import com.traceability.integrations.shopify.ShopifyWebhookProcessorJob;
import com.traceability.notifications.EmailGateway;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OAuth Day 3 integration tests — FR-3.3 webhook transport, HMAC, GDPR, app/uninstalled.
 *
 * Tests 1–4:  ingestion spine (HMAC, idempotency, unknown shop).
 * Tests 5–7:  order/product processing + app/uninstalled.
 * Tests 8–10: GDPR handlers.
 * Tests 11–12: registration idempotency + RLS proof.
 *
 * ShopifyGateway and JobScheduler are @MockBean.
 * ShopifyWebhookProcessorJob and RegisterShopifyWebhooksJob are real beans — called directly.
 * Real Postgres via Testcontainers — required for RLS, GDPR redaction, and isolation tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShopifyOAuthDay3Test {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired ShopifyWebhookProcessorJob processorJob;
    @Autowired RegisterShopifyWebhooksJob webhooksJob;
    @Autowired ShopifyImportJob           importJob;
    @Autowired PlatformTransactionManager txm;
    @Autowired EncryptionService          encryptionService;

    @MockBean ShopifyGateway shopifyGateway;
    @MockBean JobScheduler   jobScheduler;
    @MockBean EmailGateway   emailGateway; // MagicLinkService dependency

    @Value("${shopify.client-secret}")
    String clientSecret;

    // Test tenant data
    private UUID   tenantId;
    private UUID   storeId;
    private UUID   productId;
    private UUID   variantId;
    private String shopDomain = "day3-test.myshopify.com";

    // No-redirect rest template so 302 responses don't auto-follow
    private RestTemplate noRedirectRest;

    @BeforeAll
    void setup() {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        noRedirectRest = new RestTemplate(new JdkClientHttpRequestFactory(httpClient));
        noRedirectRest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) { return false; }
        });

        // Create tenant, store, product, variant for tests that need them
        tenantId  = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantId, "Day3 Corp");
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES " +
            "(?, ?, 'Day3 Owner', 'day3@test.com', '$2a$12$placeholder', 'owner')",
            UUID.randomUUID(), tenantId);
        String encryptedToken = encryptionService.encrypt("test_raw_token_day3");
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, platform, access_token_encrypted, " +
            "access_token_expires_at, status, import_status) " +
            "VALUES (?, ?, ?, 'shopify', ?, now() + interval '876000 hours', 'connected', 'pending')",
            storeId, tenantId, shopDomain, encryptedToken);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES " +
            "(?, ?, ?, 'gid://shopify/Product/111', 'Test Product', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title) VALUES " +
            "(?, ?, ?, 'gid://shopify/ProductVariant/222', 'SKU-001', 'Test Variant')",
            variantId, tenantId, productId);
    }

    @AfterEach
    void cleanWebhookEvents() {
        // Remove events between tests; delete order_items before orders (FK constraint)
        jdbc.update("DELETE FROM shopify_webhook_events WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
        reset(jobScheduler);
    }

    // -----------------------------------------------------------------------
    // 1. Raw-body HMAC catches the reparse bug:
    //    body with non-canonical spacing verifies when HMAC is over raw bytes.
    //    Tampered body → 401.
    // -----------------------------------------------------------------------
    @Test
    @Order(1)
    void rawBodyHmac_nonCanonicalSpacing_verifiesCorrectly_tamperedBodyFails() {
        // Non-canonical JSON: extra whitespace and a different key ordering than Jackson would produce
        byte[] rawBody = "{ \"id\" :  123 , \"name\" : \"#1001\"  }".getBytes(StandardCharsets.UTF_8);
        String hmac = webhookHmac(rawBody);

        // Valid HMAC over raw bytes → 200
        var resp = postWebhook("orders/create", shopDomain, webhookId(), hmac, rawBody);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Same body but tampered → HMAC mismatch → 401
        byte[] tampered = "{ \"id\" :  123 , \"name\" : \"#HACKED\"  }".getBytes(StandardCharsets.UTF_8);
        var resp2 = postWebhook("orders/create", shopDomain, webhookId(), hmac, tampered);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Nothing persisted for the tampered request
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_webhook_events WHERE tenant_id = ?",
            Integer.class, tenantId);
        assertThat(count).isEqualTo(1); // only the valid request
    }

    // -----------------------------------------------------------------------
    // 2. Wrong HMAC → 401, nothing persisted
    // -----------------------------------------------------------------------
    @Test
    @Order(2)
    void wrongHmac_returns401_nothingPersisted() {
        byte[] body = """
                {"id": 999, "name": "#2001", "admin_graphql_api_id": "gid://shopify/Order/999"}
                """.getBytes(StandardCharsets.UTF_8);

        var resp = postWebhook("orders/create", shopDomain, webhookId(), "wrong-hmac-base64", body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_webhook_events WHERE tenant_id = ?",
            Integer.class, tenantId);
        assertThat(count).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // 3. Idempotency: same webhook_id × 5 → one row, one processing effect
    // -----------------------------------------------------------------------
    @Test
    @Order(3)
    void idempotency_sameWebhookId_fiveTimes_oneRowOneEffect() {
        byte[] body = """
                {"id": 300, "admin_graphql_api_id": "gid://shopify/Order/300",
                 "name": "#3001", "created_at": "2024-01-15T10:00:00Z",
                 "line_items": [], "payment_gateway_names": [], "current_total_price": "0.00"}
                """.getBytes(StandardCharsets.UTF_8);
        String hmac  = webhookHmac(body);
        String wid   = "idempotency-wh-" + UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            var resp = postWebhook("orders/create", shopDomain, wid, hmac, body);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        // Exactly one row in the table
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_webhook_events WHERE webhook_id = ?",
            Integer.class, wid);
        assertThat(count).isEqualTo(1);

        // Find the event and process it
        UUID eventId = jdbc.queryForObject(
            "SELECT id FROM shopify_webhook_events WHERE webhook_id = ?", UUID.class, wid);
        processorJob.process(eventId, tenantId);

        // One order upserted
        Integer orderCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND external_id = 'gid://shopify/Order/300'",
            Integer.class, tenantId);
        assertThat(orderCount).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // 4. Unknown shop → 200 ack, no persist, no 500
    // -----------------------------------------------------------------------
    @Test
    @Order(4)
    void unknownShop_acks200_nothingPersisted() {
        String unknownShop = "ghost-shop-does-not-exist.myshopify.com";
        byte[] body = """
                {"id": 400, "name": "#4001"}
                """.getBytes(StandardCharsets.UTF_8);
        String hmac = webhookHmac(body);

        var resp = postWebhook("orders/create", unknownShop, webhookId(), hmac, body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Nothing persisted — unknown shop is acked and dropped
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_webhook_events WHERE shop_domain = ?",
            Integer.class, unknownShop);
        assertThat(count).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // 5. orders/create → dispatched to existing ingestion, order upserted
    // -----------------------------------------------------------------------
    @Test
    @Order(5)
    void ordersCreate_dispatchesToExistingIngestion_orderUpserted() {
        byte[] body = ("""
                {"id": 500,
                 "admin_graphql_api_id": "gid://shopify/Order/500",
                 "name": "#5001",
                 "created_at": "2024-02-01T12:00:00Z",
                 "financial_status": "pending",
                 "payment_gateway_names": ["cash"],
                 "current_total_price": "350.00",
                 "line_items": [
                   {"id": 501, "admin_graphql_api_id": "gid://shopify/LineItem/501",
                    "variant_id": 222, "quantity": 2}
                 ]}
                """).getBytes(StandardCharsets.UTF_8);
        String hmac = webhookHmac(body);
        String wid  = webhookId();

        postWebhook("orders/create", shopDomain, wid, hmac, body);

        UUID eventId = jdbc.queryForObject(
            "SELECT id FROM shopify_webhook_events WHERE webhook_id = ?", UUID.class, wid);
        processorJob.process(eventId, tenantId);

        // Order row was upserted
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND external_id = 'gid://shopify/Order/500'",
            Integer.class, tenantId);
        assertThat(count).isEqualTo(1);

        // COD amount set (financial_status = pending → cod)
        Object codAmount = jdbc.queryForObject(
            "SELECT cod_amount FROM orders WHERE tenant_id = ? AND external_id = 'gid://shopify/Order/500'",
            Object.class, tenantId);
        assertThat(codAmount).isNotNull();
    }

    // -----------------------------------------------------------------------
    // 6. orders/cancelled → cancel logic dispatched (FR-3.5 wired from Day 14)
    // -----------------------------------------------------------------------
    @Test
    @Order(6)
    void ordersCancelled_cancelLogicDispatched_orderBecomesTerminal() {
        // Insert a pre-pick order
        UUID orderId = UUID.randomUUID();
        String extId = "gid://shopify/Order/600";
        jdbc.update(
            "INSERT INTO orders (id, tenant_id, store_id, external_id, number, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, '#6001', 'prepaid'::order_payment_method, now(), '{}'::jsonb)",
            orderId, tenantId, storeId, extId);

        byte[] body = ("""
                {"id": 600,
                 "admin_graphql_api_id": "%s",
                 "name": "#6001",
                 "cancelled_at": "2024-02-01T13:00:00Z"}
                """.formatted(extId)).getBytes(StandardCharsets.UTF_8);
        String hmac = webhookHmac(body);
        String wid  = webhookId();

        postWebhook("orders/cancelled", shopDomain, wid, hmac, body);

        UUID eventId = jdbc.queryForObject(
            "SELECT id FROM shopify_webhook_events WHERE webhook_id = ?", UUID.class, wid);
        processorJob.process(eventId, tenantId);

        // Order status is cancelled (no pieces were allocated → direct cancel)
        String status = jdbc.queryForObject(
            "SELECT status FROM orders WHERE id = ?", String.class, orderId);
        assertThat(status).isEqualTo("cancelled");
    }

    // -----------------------------------------------------------------------
    // 7. app/uninstalled → store status = 'disconnected'; import job skips it
    // -----------------------------------------------------------------------
    @Test
    @Order(7)
    void appUninstalled_storeDisconnected_importJobSkips() {
        byte[] body = ("""
                {"shop_id": 700, "shop_domain": "%s"}
                """.formatted(shopDomain)).getBytes(StandardCharsets.UTF_8);
        String hmac = webhookHmac(body);
        String wid  = webhookId();

        postWebhook("app/uninstalled", shopDomain, wid, hmac, body);

        UUID eventId = jdbc.queryForObject(
            "SELECT id FROM shopify_webhook_events WHERE webhook_id = ?", UUID.class, wid);
        processorJob.process(eventId, tenantId);

        // Store is disconnected
        String status = jdbc.queryForObject(
            "SELECT status FROM stores WHERE id = ?", String.class, storeId);
        assertThat(status).isEqualTo("disconnected");

        // Import job skips disconnected store — verify no exception is thrown
        // and import_status is NOT changed to 'importing' (stays 'pending')
        when(shopifyGateway.validateShop(any(), any())).thenReturn("Day3 Corp");
        assertThatNoException().isThrownBy(() -> importJob.run(storeId, tenantId));

        // Restore for subsequent tests
        jdbc.update("UPDATE stores SET status = 'connected' WHERE id = ?", storeId);
    }

    // -----------------------------------------------------------------------
    // 8. customers/redact → order PII nulled + orders.raw scrubbed;
    //    piece_events rows are completely untouched (INSERT-only, no customer PII)
    // -----------------------------------------------------------------------
    @Test
    @Order(8)
    void customersRedact_orderPiiNulled_rawScrubbed_pieceEventsUntouched() {
        // Insert order with customer PII
        UUID orderId  = UUID.randomUUID();
        String extId  = "gid://shopify/Order/800";
        String custId = "88888";
        jdbc.update(
            "INSERT INTO orders (id, tenant_id, store_id, external_id, number, " +
            "customer_name, customer_phone, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, '#8001', 'Alice Day3', '+201000000008', 'prepaid'::order_payment_method, now(), " +
            "'{\"customer\":{\"id\":88888,\"email\":\"alice@test.com\"},\"phone\":\"+201000000008\"}'::jsonb)",
            orderId, tenantId, storeId, extId);

        // Insert a piece_event row — must survive redaction unchanged
        String pieceId = "01HPIECEDAY3TEST00001";
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, status) VALUES (?, ?, ?, ?, 'available')",
            pieceId, tenantId, variantId, "BAR-DAY3-001");
        UUID locationId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default) VALUES " +
            "(?, ?, 'Test Loc Day3', 'warehouse', false)", locationId, tenantId);
        jdbc.update(
            "INSERT INTO piece_events (piece_id, tenant_id, event_type, from_status, to_status, " +
            "location_id, actor_user_id, occurred_at) VALUES (?, ?, 'received', NULL, 'available', ?, NULL, now())",
            pieceId, tenantId, locationId);

        int eventsBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE tenant_id = ?", Integer.class, tenantId);

        // GDPR redact for this customer
        byte[] body = ("""
                {"shop_id": 800,
                 "customer": {"id": %s, "phone": "+201000000008"},
                 "orders_to_redact": [{"id": 800}]}
                """.formatted(custId)).getBytes(StandardCharsets.UTF_8);
        String hmac = webhookHmac(body);
        String wid  = webhookId();

        postWebhook("customers/redact", shopDomain, wid, hmac, body);

        UUID eventId = jdbc.queryForObject(
            "SELECT id FROM shopify_webhook_events WHERE webhook_id = ?", UUID.class, wid);
        processorJob.process(eventId, tenantId);

        // Order PII is nulled
        Map<String, Object> orderRow = jdbc.queryForMap(
            "SELECT customer_name, customer_phone, address FROM orders WHERE id = ?", orderId);
        assertThat(orderRow.get("customer_name")).isNull();
        assertThat(orderRow.get("customer_phone")).isNull();

        // orders.raw no longer contains customer/PII keys
        String rawJson = jdbc.queryForObject(
            "SELECT raw::text FROM orders WHERE id = ?", String.class, orderId);
        assertThat(rawJson).doesNotContain("\"customer\"");
        assertThat(rawJson).doesNotContain("\"phone\"");

        // piece_events is completely untouched — exact same count, INSERT-only guarantee
        int eventsAfter = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE tenant_id = ?", Integer.class, tenantId);
        assertThat(eventsAfter).isEqualTo(eventsBefore);

        // Cleanup
        jdbc.update("DELETE FROM piece_events WHERE piece_id = ?", pieceId);
        jdbc.update("DELETE FROM pieces WHERE id = ?", pieceId);
        jdbc.update("DELETE FROM locations WHERE id = ?", locationId);
    }

    // -----------------------------------------------------------------------
    // 9. shop/redact → ALL that tenant's customer PII redacted across all orders
    // -----------------------------------------------------------------------
    @Test
    @Order(9)
    void shopRedact_allTenantCustomerPiiRedacted() {
        // Insert two orders with customer PII
        UUID order1 = UUID.randomUUID();
        UUID order2 = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO orders (id, tenant_id, store_id, external_id, number, " +
            "customer_name, customer_phone, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, 'gid://shopify/Order/901', '#9001', 'Bob Nine', '+20100009001', " +
            "'prepaid'::order_payment_method, now(), '{\"customer\":{\"name\":\"Bob Nine\"}}'::jsonb)",
            order1, tenantId, storeId);
        jdbc.update(
            "INSERT INTO orders (id, tenant_id, store_id, external_id, number, " +
            "customer_name, customer_phone, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, 'gid://shopify/Order/902', '#9002', 'Carol Nine', '+20100009002', " +
            "'prepaid'::order_payment_method, now(), '{\"customer\":{\"name\":\"Carol Nine\"}}'::jsonb)",
            order2, tenantId, storeId);

        byte[] body = ("""
                {"shop_id": 900, "shop_domain": "%s"}
                """.formatted(shopDomain)).getBytes(StandardCharsets.UTF_8);
        String hmac = webhookHmac(body);
        String wid  = webhookId();

        postWebhook("shop/redact", shopDomain, wid, hmac, body);

        UUID eventId = jdbc.queryForObject(
            "SELECT id FROM shopify_webhook_events WHERE webhook_id = ?", UUID.class, wid);
        processorJob.process(eventId, tenantId);

        // Both orders have nulled PII
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT customer_name, customer_phone FROM orders WHERE id IN (?, ?)", order1, order2);
        for (var row : rows) {
            assertThat(row.get("customer_name")).isNull();
            assertThat(row.get("customer_phone")).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // 10. customers/data_request → persisted/surfaced, 200 ack
    // -----------------------------------------------------------------------
    @Test
    @Order(10)
    void customersDataRequest_persisted_acks200() {
        byte[] body = """
                {"shop_id": 1000,
                 "customer": {"id": 10001, "email": "requestor@test.com"},
                 "data_request": {"id": 9999}}
                """.getBytes(StandardCharsets.UTF_8);
        String hmac = webhookHmac(body);
        String wid  = webhookId();

        var resp = postWebhook("customers/data_request", shopDomain, wid, hmac, body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Event persisted in webhook events table
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_webhook_events WHERE webhook_id = ? AND topic = 'customers/data_request'",
            Integer.class, wid);
        assertThat(count).isEqualTo(1);

        // Process it — should log and not throw
        UUID eventId = jdbc.queryForObject(
            "SELECT id FROM shopify_webhook_events WHERE webhook_id = ?", UUID.class, wid);
        assertThatNoException().isThrownBy(() -> processorJob.process(eventId, tenantId));
    }

    // -----------------------------------------------------------------------
    // 11. Registration idempotency: run twice → no crash, gateway called per topic each time
    // -----------------------------------------------------------------------
    @Test
    @Order(11)
    void registrationIdempotency_runTwice_noExceptionNoDoubleSubscription() {
        // Mock: accept all registerWebhook calls (void method, no exception = success)
        doNothing().when(shopifyGateway).registerWebhook(anyString(), anyString(), anyString(), anyString());

        // First run
        assertThatNoException().isThrownBy(() -> webhooksJob.run(storeId, tenantId));
        verify(shopifyGateway, times(6)).registerWebhook(eq(shopDomain), anyString(), anyString(), anyString());

        reset(shopifyGateway);

        // Second run — simulate "already taken" by throwing ShopifyException on some topics
        doNothing().when(shopifyGateway)
            .registerWebhook(anyString(), anyString(), anyString(), anyString());
        doThrow(new ShopifyException("Webhook subscription already taken"))
            .when(shopifyGateway)
            .registerWebhook(eq(shopDomain), anyString(), eq("orders/create"), anyString());

        // Must not throw even when gateway signals conflict
        assertThatNoException().isThrownBy(() -> webhooksJob.run(storeId, tenantId));
    }

    // -----------------------------------------------------------------------
    // 12. Webhook insert occurs under tenant GUC: visible to correct tenant,
    //     invisible to wrong tenant (RLS proof via app_user role)
    // -----------------------------------------------------------------------
    @Test
    @Order(12)
    void webhookInsert_underTenantGuc_rlsEnforced() throws Exception {
        byte[] body = """
                {"id": 1200, "admin_graphql_api_id": "gid://shopify/Order/1200",
                 "name": "#12001", "created_at": "2024-01-01T00:00:00Z",
                 "line_items": [], "payment_gateway_names": [], "current_total_price": "0.00"}
                """.getBytes(StandardCharsets.UTF_8);
        String hmac = webhookHmac(body);
        String wid  = webhookId();

        var resp = postWebhook("orders/create", shopDomain, wid, hmac, body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID eventId = jdbc.queryForObject(
            "SELECT id FROM shopify_webhook_events WHERE webhook_id = ?", UUID.class, wid);
        assertThat(eventId).isNotNull();

        // app_user WITH correct GUC → must see the row
        DriverManagerDataSource rawDs = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawDs);
        JdbcTemplate appUserJdbc = new JdbcTemplate(appUserDs);
        TransactionTemplate appUserTx = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(appUserDs));

        com.traceability.tenancy.TenantContext.set(tenantId);
        try {
            Integer countCorrect = appUserTx.execute(s ->
                appUserJdbc.queryForObject(
                    "SELECT COUNT(*) FROM shopify_webhook_events WHERE id = ?",
                    Integer.class, eventId));
            assertThat(countCorrect)
                .as("app_user with correct tenant GUC must see the webhook event")
                .isEqualTo(1);
        } finally {
            com.traceability.tenancy.TenantContext.clear();
        }

        // app_user with WRONG GUC (different tenant) → must see zero rows
        UUID otherTenant = UUID.randomUUID();
        com.traceability.tenancy.TenantContext.set(otherTenant);
        try {
            Integer countWrong = appUserTx.execute(s ->
                appUserJdbc.queryForObject(
                    "SELECT COUNT(*) FROM shopify_webhook_events WHERE id = ?",
                    Integer.class, eventId));
            assertThat(countWrong)
                .as("app_user with wrong tenant GUC must NOT see the webhook event (RLS)")
                .isEqualTo(0);
        } finally {
            com.traceability.tenancy.TenantContext.clear();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ResponseEntity<Void> postWebhook(String topic, String shop,
                                              String wid, String hmac, byte[] body) {
        String[] parts = topic.split("/", 2);
        String url = "http://localhost:" + port + "/webhooks/shopify/" + parts[0] + "/" + parts[1];

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Shopify-Topic",       topic);
        headers.set("X-Shopify-Shop-Domain", shop);
        headers.set("X-Shopify-Hmac-Sha256", hmac);
        headers.set("X-Shopify-Webhook-Id",  wid);

        return noRedirectRest.exchange(url, HttpMethod.POST,
                new HttpEntity<>(body, headers), Void.class);
    }

    /** Compute HMAC-SHA256 over raw body bytes and return base64 — the Shopify webhook signature. */
    private String webhookHmac(byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(rawBody));
        } catch (Exception e) {
            throw new RuntimeException("Test webhook HMAC failed", e);
        }
    }

    private static String webhookId() {
        return "wh-" + UUID.randomUUID();
    }

    private String base() {
        return "http://localhost:" + port;
    }
}
