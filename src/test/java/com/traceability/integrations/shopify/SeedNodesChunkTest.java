package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.account.AuditService;
import com.traceability.inventory.ShopifyInventoryReconcileService;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seed fix (activation perf, Part B) through the REAL ShopifyHttpGateway (fake network).
 *
 * 600 candidate variants (one sellable piece each at the fulfillment location) + 200 with no
 * Traced stock, Shopify already non-zero for all → nothing is written, and:
 *   - item ids are resolved in 3 nodes(ids:) reads of ≤250 (not 600 single reads), and while they
 *     are resolved no advisory lock is held (resolution is BEFORE the lock + transaction);
 *   - Shopify's live "available" is read in 3 nodes(ids:) reads of ≤250 covering exactly the 600
 *     candidates, under the tenant's advisory lock;
 *   - the 200 variants with no Traced stock are never read;
 *   - on a second run the item ids come from the column: zero resolve reads, 3 level reads.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class SeedNodesChunkTest {

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

    private static final ObjectMapper M = new ObjectMapper();

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired AuditService auditService;
    @Autowired StoreRepository storeRepository;

    record Req(String name, int ids, long advisoryLocks) {}

    @Test
    void sixHundredCandidates_threeResolveReads_threeLevelReads_resolveOutsideTheLock() {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), product = UUID.randomUUID(), main = UUID.randomUUID();
        String shop = "seed-chunk.myshopify.com", traced = "gid://shopify/Location/seed-chunk";
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Seed chunk')", tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status) VALUES (?, ?, ?, 'connected')", store, tenant, shop);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'gid://shopify/Product/sc', 'P', 'active')",
            product, tenant, store);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", main, tenant, traced);
        List<Object[]> variants = new ArrayList<>(), pieces = new ArrayList<>();
        for (int i = 0; i < 800; i++) {
            UUID v = UUID.randomUUID();
            variants.add(new Object[]{v, tenant, product, "gid://shopify/ProductVariant/" + i, "V" + i});
            if (i < 600) {
                String id = String.format("01SEEDCHUNK%015d", i);
                pieces.add(new Object[]{id, tenant, v, "SC-" + i, String.format("C%06d", i), main});
            }
        }
        jdbc.batchUpdate("INSERT INTO variants (id, tenant_id, product_id, external_id, title) VALUES (?, ?, ?, ?, ?)", variants);
        jdbc.batchUpdate("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
            "VALUES (?, ?, ?, 'available'::piece_status, ?, ?, ?)", pieces);

        List<Req> requests = Collections.synchronizedList(new ArrayList<>());
        List<String> levelIds = Collections.synchronizedList(new ArrayList<>());
        ClientHttpRequestInterceptor fakeShopify = (request, body, execution) -> {
            JsonNode req = M.readTree(new String(body, StandardCharsets.UTF_8));
            String query = req.path("query").asText();
            String name = query.replaceAll("(?s)^\\s*(query|mutation)\\s+(\\w+).*", "$2");
            JsonNode vars = req.path("variables");
            long locks = jdbc.queryForObject("SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory' AND granted", Long.class);
            requests.add(new Req(name, vars.path("ids").size(), locks));
            String json;
            if (name.equals("VariantInventoryItem")) {            // single resolve (pre-change shape)
                String v = vars.path("id").asText();
                json = "{\"data\":{\"productVariant\":{\"inventoryItem\":{\"id\":\"item-" + v.substring(v.lastIndexOf('/') + 1) + "\"}}}}";
            } else if (name.equals("VariantInventoryItems")) {    // batch resolve
                StringBuilder n = new StringBuilder();
                for (JsonNode id : vars.path("ids")) {
                    String v = id.asText();
                    if (n.length() > 0) n.append(',');
                    n.append("{\"id\":\"").append(v).append("\",\"inventoryItem\":{\"id\":\"item-")
                     .append(v.substring(v.lastIndexOf('/') + 1)).append("\"}}");
                }
                json = "{\"data\":{\"nodes\":[" + n + "]}}";
            } else if (name.equals("InventoryLevelsAtLocation")) {
                StringBuilder n = new StringBuilder();
                for (JsonNode id : vars.path("ids")) {
                    levelIds.add(id.asText());
                    if (n.length() > 0) n.append(',');
                    n.append("{\"id\":\"").append(id.asText())
                     .append("\",\"inventoryLevel\":{\"quantities\":[{\"name\":\"available\",\"quantity\":1}]}}");
                }
                json = "{\"data\":{\"nodes\":[" + n + "]}}";
            } else {
                json = "{\"errors\":[{\"message\":\"unexpected request " + name + "\"}]}";
            }
            MockClientHttpResponse resp = new MockClientHttpResponse(json.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return resp;
        };
        ShopifyGateway gateway = new ShopifyHttpGateway(RestClient.builder().requestInterceptor(fakeShopify), M,
            "2026-04", "test-client-id", "test-client-secret");
        ShopifyTokenProvider tokens = Mockito.mock(ShopifyTokenProvider.class);
        Mockito.when(tokens.getValidToken(store)).thenReturn("tok");
        ShopifyInventoryReconcileService seed = new ShopifyInventoryReconcileService(
            jdbc, txm, gateway, tokens, M, auditService, storeRepository);

        ShopifyInventoryReconcileService.ApplyResult result = runAs(tenant, () -> seed.apply(null));

        List<Req> resolves = requests.stream().filter(r -> r.name().startsWith("VariantInventoryItem")).toList();
        List<Req> levels = requests.stream().filter(r -> r.name().equals("InventoryLevelsAtLocation")).toList();
        assertThat(resolves).as("item ids: 3 nodes reads of ≤250").hasSize(3)
            .allSatisfy(r -> assertThat(r.ids()).isBetween(1, 250));
        assertThat(resolves).as("resolved before the advisory lock is taken").allSatisfy(r -> assertThat(r.advisoryLocks()).isZero());
        assertThat(levels).as("available: 3 nodes reads of ≤250").hasSize(3)
            .allSatisfy(r -> assertThat(r.ids()).isBetween(1, 250));
        assertThat(levels).as("available is read under the lock").allSatisfy(r -> assertThat(r.advisoryLocks()).isEqualTo(1));
        assertThat(levelIds).as("exactly the 600 candidates are read").hasSize(600).doesNotHaveDuplicates()
            .allSatisfy(id -> assertThat(Integer.parseInt(id.substring("item-".length()))).isLessThan(600));
        assertThat(requests).as("Shopify already non-zero → no write").hasSize(6);
        assertThat(result.seeded()).isZero();
        assertThat(result.skippedNonZero()).isEqualTo(600);

        // Second run: the ids were written back — no resolve at all.
        requests.clear();
        levelIds.clear();
        runAs(tenant, () -> seed.apply(null));
        assertThat(requests.stream().filter(r -> r.name().startsWith("VariantInventoryItem"))).isEmpty();
        assertThat(requests.stream().filter(r -> r.name().equals("InventoryLevelsAtLocation"))).hasSize(3);
    }

    private static <T> T runAs(UUID tenant, java.util.function.Supplier<T> body) {
        TenantContext.set(tenant);
        try {
            return body.get();
        } finally {
            TenantContext.clear();
        }
    }
}
