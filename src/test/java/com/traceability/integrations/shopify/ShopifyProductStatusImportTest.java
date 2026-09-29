package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Catalog import of every product status, end to end from the wire to the products table.
 *
 * The ShopifyGateway bean's product fetch is served by a REAL ShopifyHttpGateway whose HTTP layer
 * is a fake Shopify: it holds an ACTIVE, a DRAFT and an ARCHIVED product and — like Shopify —
 * honours a {@code status:active} search filter when the query carries one, and serves the
 * active product's variants over two pages. i1 and i2 are revert-checked against the old query
 * (filter restored → only the active product is stored; no follow-up → 2 of 4 variants).
 *
 * w1 is a regression guard for the products/update webhook: the stored status follows archive
 * → reactivate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyProductStatusImportTest {

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

    private static final String SHOP = "status-import.myshopify.com";
    private static final ObjectMapper M = new ObjectMapper();

    @Autowired JdbcTemplate jdbc;
    @Autowired ShopifySyncService sync;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean JobScheduler jobScheduler;

    UUID tenantId, storeId;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Status Import Co')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            storeId, tenantId, SHOP);
    }

    @BeforeEach
    void stub() {
        ShopifyHttpGateway real = fakeShopify();
        when(shopifyGateway.fetchProductsPage(anyString(), anyString(), any()))
            .thenAnswer(inv -> real.fetchProductsPage(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(shopifyGateway.fetchOrdersPage(anyString(), anyString(), any(), anyString()))
            .thenReturn(new ShopifyGateway.OrderPage(List.of(), false, null));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM variants WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM products WHERE tenant_id = ?", tenantId);
    }

    @Test
    void i1_activeDraftArchived_allStored_lowercase() {
        TenantContext.runAs(tenantId, () -> sync.runImport(storeId, tenantId, SHOP, "tok"));

        Map<String, String> statusByGid = jdbc.queryForList(
                "SELECT external_id, status FROM products WHERE tenant_id = ?", tenantId).stream()
            .collect(Collectors.toMap(r -> (String) r.get("external_id"), r -> (String) r.get("status")));
        assertThat(statusByGid).containsExactlyInAnyOrderEntriesOf(Map.of(
            "gid://shopify/Product/1", "active",
            "gid://shopify/Product/2", "draft",
            "gid://shopify/Product/3", "archived"));
    }

    @Test
    void i2_productWithTwoVariantPages_everyVariantStored() {
        TenantContext.runAs(tenantId, () -> sync.runImport(storeId, tenantId, SHOP, "tok"));

        List<String> variants = jdbc.queryForList(
            "SELECT v.external_id FROM variants v JOIN products p ON p.id = v.product_id " +
            "WHERE p.tenant_id = ? AND p.external_id = 'gid://shopify/Product/1' ORDER BY v.external_id",
            String.class, tenantId);
        assertThat(variants).containsExactly(
            "gid://shopify/ProductVariant/11", "gid://shopify/ProductVariant/12",
            "gid://shopify/ProductVariant/13", "gid://shopify/ProductVariant/14");
    }

    @Test
    void i3_reImport_idempotent_noDuplicates() {
        TenantContext.runAs(tenantId, () -> sync.runImport(storeId, tenantId, SHOP, "tok"));
        TenantContext.runAs(tenantId, () -> sync.runImport(storeId, tenantId, SHOP, "tok"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE tenant_id = ?", Integer.class, tenantId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM variants WHERE tenant_id = ?", Integer.class, tenantId)).isEqualTo(6);
    }

    @Test
    void w1_productsUpdateWebhook_archivedThenActive_storedStatusFollows() {
        sync.ingestProductWebhook(storeId, tenantId, webhook("archived"));
        assertThat(status()).isEqualTo("archived");
        sync.ingestProductWebhook(storeId, tenantId, webhook("active"));
        assertThat(status()).isEqualTo("active");
        sync.ingestProductWebhook(storeId, tenantId, webhook("draft"));
        assertThat(status()).isEqualTo("draft");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE tenant_id = ?", Integer.class, tenantId)).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String status() {
        return jdbc.queryForObject("SELECT status FROM products WHERE tenant_id = ? AND external_id = 'gid://shopify/Product/77'",
            String.class, tenantId);
    }

    private static JsonNode webhook(String status) {
        ObjectNode p = M.createObjectNode()
            .put("id", 77).put("admin_graphql_api_id", "gid://shopify/Product/77")
            .put("title", "Linen Shirt").put("status", status);
        p.putArray("variants").addObject()
            .put("id", 770).put("admin_graphql_api_id", "gid://shopify/ProductVariant/770")
            .put("sku", "LIN-M").put("title", "M").put("price", "450.00");
        return p;
    }

    /** A fake Shopify behind a real ShopifyHttpGateway — it honours "status:active" like Shopify does. */
    private static ShopifyHttpGateway fakeShopify() {
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            JsonNode req = M.readTree(new String(body, StandardCharsets.UTF_8));
            String query = req.path("query").asText();
            String json;
            if (query.contains("ProductVariantsPage")) {
                json = "{\"data\":{\"product\":{\"variants\":{\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null},\"edges\":["
                    + variant("13") + "," + variant("14") + "]}}}}";
            } else {
                boolean activeOnly = query.contains("status:active");
                StringBuilder edges = new StringBuilder();
                edges.append(product("1", "ACTIVE", true, variant("11") + "," + variant("12")));
                if (!activeOnly) {
                    edges.append(',').append(product("2", "DRAFT", false, variant("21")));
                    edges.append(',').append(product("3", "ARCHIVED", false, variant("31")));
                }
                json = "{\"data\":{\"products\":{\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null},\"edges\":[" + edges + "]}}}";
            }
            MockClientHttpResponse response = new MockClientHttpResponse(json.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        };
        return new ShopifyHttpGateway(RestClient.builder().requestInterceptor(interceptor), M,
            "2026-04", "test-client-id", "test-client-secret");
    }

    private static String product(String id, String status, boolean moreVariants, String variantEdges) {
        return "{\"node\":{\"id\":\"gid://shopify/Product/" + id + "\",\"title\":\"P" + id + "\",\"status\":\"" + status + "\"," +
            "\"featuredImage\":null,\"variants\":{\"pageInfo\":{\"hasNextPage\":" + moreVariants + ",\"endCursor\":"
            + (moreVariants ? "\"vc1\"" : "null") + "},\"edges\":[" + variantEdges + "]}}}";
    }

    private static String variant(String id) {
        return "{\"node\":{\"id\":\"gid://shopify/ProductVariant/" + id + "\",\"sku\":\"S" + id + "\",\"title\":\"V" + id
            + "\",\"price\":\"100.00\"}}";
    }
}
