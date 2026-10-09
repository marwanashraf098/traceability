package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.analytics.AnalyticsInventorySyncJob;
import com.traceability.analytics.AnalyticsInventorySyncService;
import com.traceability.analytics.AnalyticsInventoryWebhookHandler;
import com.traceability.analytics.ShopifyInventoryReader;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.ShipmentSettlement;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.ShopifyWebhookTopicsBackfill;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Analytics slice 10 — the Shopify cost + stock READ pass (probe → bulk, else paged), the cost rules
 * (EGP only, manual never overwritten), access denied / errors as statuses (never thrown), the
 * inventory_items/update + inventory_levels/update handlers, the owner's rate-limited "run now" and
 * status, the stock trust on fresh Shopify stock, the profit figures with coverage, roles and RLS.
 * Shopify is a mock answering by query text; the bulk result is a mock download.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalyticsInventorySyncTest {

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
        r.add("analytics.inventory-sync.enabled", () -> "true");
        r.add("analytics.inventory-sync.bulk-poll-ms", () -> "5");
        r.add("analytics.inventory-sync.bulk-timeout-s", () -> "3");
    }

    static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired AnalyticsInventorySyncService sync;
    @Autowired AnalyticsInventoryWebhookHandler hooks;
    @Autowired AnalyticsInventorySyncJob syncJob;
    @Autowired ShopifyWebhookTopicsBackfill topicsBackfill;
    @MockBean ShopifyGateway gateway;
    @MockBean ShopifyTokenProvider tokens;
    @MockBean ShopifyInventoryReader.BulkDownloader downloader;

    /** What the mocked Shopify answers. */
    final class Shopify {
        RuntimeException costProbe, stockProbe;
        String currency = "EGP";
        boolean bulkBusy;
        List<String> jsonl = new ArrayList<>();
        List<JsonNode> pagedNodes = new ArrayList<>();
        final AtomicInteger bulkRuns = new AtomicInteger(), pagedCalls = new AtomicInteger();
    }

    Shopify shopify;

    @BeforeEach
    void mockShopify() throws Exception {
        shopify = new Shopify();
        when(tokens.getValidToken(any())).thenReturn("tok");
        when(gateway.executeGraphQLPublic(anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
            String q = inv.getArgument(2);
            if (q.contains("inventoryItem { id unitCost { amount currencyCode } } } } }") && q.contains("first: 1")) {
                if (shopify.costProbe != null) throw shopify.costProbe;
                return json("{\"productVariants\":{\"nodes\":[{\"id\":\"gid://shopify/ProductVariant/1\",\"inventoryItem\":{\"id\":\"x\"," +
                            "\"unitCost\":{\"amount\":\"1.00\",\"currencyCode\":\"" + shopify.currency + "\"}}}]}}");
            }
            if (q.contains("inventoryLevels(first: 1)")) {
                if (shopify.stockProbe != null) throw shopify.stockProbe;
                return json("{\"productVariants\":{\"nodes\":[{\"id\":\"v\",\"inventoryItem\":{\"id\":\"x\",\"inventoryLevels\":{\"nodes\":[]}}}]}}");
            }
            if (q.contains("bulkOperationRunQuery")) {
                shopify.bulkRuns.incrementAndGet();
                return shopify.bulkBusy
                    ? json("{\"bulkOperationRunQuery\":{\"bulkOperation\":null,\"userErrors\":[{\"message\":\"A bulk query operation for this app and shop is already in progress\"}]}}")
                    : json("{\"bulkOperationRunQuery\":{\"bulkOperation\":{\"id\":\"gid://shopify/BulkOperation/9\",\"status\":\"CREATED\"},\"userErrors\":[]}}");
            }
            if (q.contains("currentBulkOperation")) {
                return json("{\"currentBulkOperation\":{\"id\":\"gid://shopify/BulkOperation/9\",\"status\":\"COMPLETED\",\"url\":\"https://storage.example/r.jsonl\"}}");
            }
            if (q.contains("productVariants(first: 100")) {
                shopify.pagedCalls.incrementAndGet();
                return mapper.createObjectNode().set("productVariants", mapper.createObjectNode()
                    .<com.fasterxml.jackson.databind.node.ObjectNode>set("pageInfo", json("{\"hasNextPage\":false}"))
                    .set("nodes", mapper.valueToTree(shopify.pagedNodes)));
            }
            throw new IllegalStateException("unexpected query " + q);
        });
        when(downloader.lines(anyString())).thenAnswer(inv -> shopify.jsonl);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    final class T {
        final UUID id = UUID.randomUUID(), owner = UUID.randomUUID(), store = UUID.randomUUID(), product = UUID.randomUUID();
        final UUID location = UUID.randomUUID();
        final String ownerToken;

        T(String name) {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'O', ?, 'x', 'owner')",
                        owner, id, "o+" + owner + "@s10.test");
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                        "VALUES (?, ?, 'shopify', ?, 'connected', '2025-01-01')", store, id, "s10-" + id + ".myshopify.com");
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, raw) VALUES (?, ?, ?, ?, 'Tee', " +
                        "'{\"product_type\":\"Shirts\"}'::jsonb)", product, id, store, "P-" + product);
            jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment, shopify_location_id) " +
                        "VALUES (?, ?, 'Main', true, 'gid://shopify/Location/5')", location, id);
            ownerToken = jwt.issueAccessToken(owner, id, "owner");
        }

        /** A variant with Shopify ids: variant gid …/ProductVariant/n, inventory item …/InventoryItem/n+1000. */
        V variant(String title, String price, String cost, String costSource) {
            UUID v = UUID.randomUUID();
            long n = SEQ.incrementAndGet();
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title, price, unit_cost, cost_source, " +
                        "shopify_inventory_item_id) VALUES (?, ?, ?, ?, ?, ?, ?::numeric, ?::numeric, ?, ?)",
                        v, id, product, "gid://shopify/ProductVariant/" + n, "SKU-" + title, title, price, cost, costSource,
                        "gid://shopify/InventoryItem/" + (n + 1000));
            return new V(v, String.valueOf(n), String.valueOf(n + 1000));
        }

        Map<String, Object> variantRow(V v) {
            return jdbc.queryForMap("SELECT unit_cost, cost_source, shopify_cost_flag, stock_available_shopify AS total, " +
                                    "stock_available_shopify_traced AS traced, stock_synced_at FROM variants WHERE id = ?", v.id());
        }

        void run(String trigger) {
            TenantContext.runAs(id, () -> sync.run(trigger));
        }

        Map<String, Object> status() {
            return jdbc.queryForMap("SELECT * FROM analytics_inventory_sync WHERE tenant_id = ?", id);
        }

        String token(String role) {
            UUID u = UUID.randomUUID();
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'U', ?, 'x', ?::user_role)",
                        u, id, role + "+" + u + "@s10.test", role);
            return jwt.issueAccessToken(u, id, role);
        }
    }

    record V(UUID id, String vid, String itemId) {}

    /** A bulk JSONL variant line + its level lines. */
    List<String> bulkLines(V v, String cost, String currency, int atTraced, int elsewhere) {
        String costJson = cost == null ? "null" : "{\"amount\":\"" + cost + "\",\"currencyCode\":\"" + currency + "\"}";
        String gid = "gid://shopify/ProductVariant/" + v.vid();
        return List.of(
            "{\"id\":\"" + gid + "\",\"inventoryItem\":{\"id\":\"gid://shopify/InventoryItem/" + v.itemId() + "\",\"unitCost\":" + costJson + "}}",
            "{\"location\":{\"id\":\"gid://shopify/Location/5\"},\"quantities\":[{\"name\":\"available\",\"quantity\":" + atTraced + "}],\"__parentId\":\"" + gid + "\"}",
            "{\"location\":{\"id\":\"gid://shopify/Location/77\"},\"quantities\":[{\"name\":\"available\",\"quantity\":" + elsewhere + "}],\"__parentId\":\"" + gid + "\"}");
    }

    // ── the pass ─────────────────────────────────────────────────────────────

    @Test
    void bulkPass_storesEgpCost_neverOverwritesManual_storesStockPerLocationAndAtTraced() {
        T t = new T("S10-Bulk");
        V a = t.variant("A", "100", null, null), manual = t.variant("Manual", "100", "99", null),
          noCost = t.variant("NoCost", "100", null, null), madeByShopify = t.variant("Old", "100", "10", "shopify");
        shopify.jsonl.addAll(bulkLines(a, "40.00", "EGP", 5, 3));
        shopify.jsonl.addAll(bulkLines(manual, "50.00", "EGP", 1, 0));
        shopify.jsonl.addAll(bulkLines(noCost, null, null, 0, 0));
        shopify.jsonl.addAll(bulkLines(madeByShopify, "12.50", "EGP", 2, 2));

        t.run("manual");
        assertThat(t.variantRow(a)).containsEntry("cost_source", "shopify").containsEntry("total", 8).containsEntry("traced", 5);
        assertThat((BigDecimal) t.variantRow(a).get("unit_cost")).isEqualByComparingTo("40.00");
        assertThat((BigDecimal) t.variantRow(manual).get("unit_cost")).isEqualByComparingTo("99");       // manual kept
        assertThat(t.variantRow(manual).get("cost_source")).isNull();
        assertThat(t.variantRow(noCost)).containsEntry("unit_cost", null).containsEntry("total", 0);
        assertThat((BigDecimal) t.variantRow(madeByShopify).get("unit_cost")).isEqualByComparingTo("12.50");  // Shopify's own, refreshed
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM variant_shopify_levels WHERE variant_id = ?", Long.class, a.id())).isEqualTo(2);
        Map<String, Object> s = t.status();
        assertThat(s).containsEntry("mode", "bulk").containsEntry("cost_status", "ok").containsEntry("stock_status", "ok")
            .containsEntry("variants_seen", 4).containsEntry("cost_written", 2).containsEntry("cost_kept_manual", 1)
            .containsEntry("stock_written", 4).containsEntry("shop_currency", "EGP").containsEntry("trigger_kind", "manual");
        assertThat(shopify.pagedCalls.get()).isZero();

        // A second pass reads the levels again (no duplicates) and a removed location disappears.
        shopify.jsonl.clear();
        shopify.jsonl.addAll(bulkLines(a, "40.00", "EGP", 6, 0).subList(0, 2));
        t.run("daily");
        assertThat(t.variantRow(a)).containsEntry("total", 6).containsEntry("traced", 6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM variant_shopify_levels WHERE variant_id = ?", Long.class, a.id())).isEqualTo(1);
    }

    @Test
    void bulkBusy_fallsBackToPaged_sameResult() throws Exception {
        T t = new T("S10-Paged");
        V a = t.variant("A", "100", null, null);
        shopify.bulkBusy = true;
        shopify.pagedNodes.add(json("{\"id\":\"gid://shopify/ProductVariant/" + a.vid() + "\",\"inventoryItem\":{\"id\":\"i\"," +
            "\"unitCost\":{\"amount\":\"30\",\"currencyCode\":\"EGP\"},\"inventoryLevels\":{\"nodes\":[" +
            "{\"location\":{\"id\":\"gid://shopify/Location/5\"},\"quantities\":[{\"name\":\"available\",\"quantity\":4}]}]}}}"));
        t.run("manual");
        assertThat(t.status()).containsEntry("mode", "paged").containsEntry("cost_written", 1);
        assertThat(t.variantRow(a)).containsEntry("total", 4).containsEntry("traced", 4);
        assertThat((BigDecimal) t.variantRow(a).get("unit_cost")).isEqualByComparingTo("30");
    }

    @Test
    void nonEgpCost_isFlaggedNotStored() {
        T t = new T("S10-Usd");
        V a = t.variant("A", "100", null, null);
        shopify.currency = "USD";
        shopify.jsonl.addAll(bulkLines(a, "5.00", "USD", 1, 0));
        t.run("manual");
        assertThat(t.variantRow(a)).containsEntry("unit_cost", null).containsEntry("shopify_cost_flag", "non_egp:USD");
        assertThat(t.status()).containsEntry("cost_non_egp", 1).containsEntry("cost_written", 0).containsEntry("shop_currency", "USD");
    }

    @Test
    void accessDeniedOrErrors_becomeStatuses_neverThrow_costedStaysZero() {
        T t = new T("S10-Denied");
        V a = t.variant("A", "100", null, null);
        shopify.costProbe = new ShopifyException("Shopify GraphQL error: Access denied for unitCost field. Required access: `read_inventory`");
        shopify.jsonl.addAll(bulkLines(a, "40.00", "EGP", 5, 0));
        assertThatCode(() -> t.run("manual")).doesNotThrowAnyException();
        assertThat(t.status()).containsEntry("cost_status", "access_denied").containsEntry("stock_status", "ok").containsEntry("cost_written", 0);
        assertThat(t.variantRow(a)).containsEntry("unit_cost", null).containsEntry("total", 5);   // stock still read
        assertThat(String.valueOf(t.status().get("last_error"))).contains("Access denied");

        T both = new T("S10-Both");
        both.variant("B", "100", null, null);
        shopify.costProbe = new ShopifyException("Shopify GraphQL error: Access denied for unitCost field.");
        shopify.stockProbe = new ShopifyException("Shopify GraphQL error: something broke");
        int runs = shopify.bulkRuns.get();
        assertThatCode(() -> both.run("daily")).doesNotThrowAnyException();
        assertThat(both.status()).containsEntry("cost_status", "access_denied").containsEntry("stock_status", "error");
        assertThat(shopify.bulkRuns.get()).isEqualTo(runs);                                         // nothing read, no retry loop

        T noStore = new T("S10-NoStore");
        jdbc.update("UPDATE stores SET status = 'disconnected' WHERE id = ?", noStore.store);
        assertThatCode(() -> noStore.run("daily")).doesNotThrowAnyException();
        assertThat(noStore.status()).containsEntry("cost_status", "never").containsEntry("last_error", "no connected Shopify store");

        when(tokens.getValidToken(any())).thenThrow(new RuntimeException("token refresh failed"));
        T broken = new T("S10-Token");
        assertThatCode(() -> broken.run("daily")).doesNotThrowAnyException();
        assertThat(broken.status()).containsEntry("cost_status", "error").containsEntry("last_error", "token refresh failed");
    }

    // ── webhooks ─────────────────────────────────────────────────────────────

    @Test
    void webhooks_refreshOnlyTheReadColumns_costEgpOnly_manualKept() throws Exception {
        T t = new T("S10-Hooks");
        V a = t.variant("A", "100", null, null), manual = t.variant("M", "100", "77", "manual");
        TenantContext.runAs(t.id, () -> {
            try {
                hooks.onInventoryLevelUpdate(json("{\"inventory_item_id\":" + a.itemId() + ",\"location_id\":5,\"available\":7}"));
                hooks.onInventoryLevelUpdate(json("{\"inventory_item_id\":" + a.itemId() + ",\"location_id\":77,\"available\":2}"));
                hooks.onInventoryItemUpdate(json("{\"id\":" + a.itemId() + ",\"cost\":\"25.00\"}"));    // currency unknown yet
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertThat(t.variantRow(a)).containsEntry("total", 9).containsEntry("traced", 7).containsEntry("unit_cost", null);

        jdbc.update("INSERT INTO analytics_inventory_sync (tenant_id, shop_currency) VALUES (?, 'EGP')", t.id);
        TenantContext.runAs(t.id, () -> {
            try {
                hooks.onInventoryItemUpdate(json("{\"id\":" + a.itemId() + ",\"cost\":\"25.00\"}"));
                hooks.onInventoryItemUpdate(json("{\"id\":" + manual.itemId() + ",\"cost\":\"1.00\"}"));
                hooks.onInventoryLevelUpdate(json("{\"inventory_item_id\":" + a.itemId() + ",\"location_id\":77,\"available\":null}"));
                hooks.onInventoryItemUpdate(json("{\"id\":999999999,\"cost\":\"3.00\"}"));               // unknown item: ignored
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertThat((BigDecimal) t.variantRow(a).get("unit_cost")).isEqualByComparingTo("25.00");
        assertThat(t.variantRow(a)).containsEntry("cost_source", "shopify").containsEntry("total", 7);
        assertThat((BigDecimal) t.variantRow(manual).get("unit_cost")).isEqualByComparingTo("77");
    }

    // ── run now + status ─────────────────────────────────────────────────────

    private ResponseEntity<Map> call(HttpMethod m, String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange("http://localhost:" + port + path, m, new HttpEntity<>(h), Map.class);
    }

    @Test
    void runNow_ownerOnly_onceEvery10Minutes_statusPerField() {
        T t = new T("S10-Run");
        t.variant("A", "100", "5", "manual");
        ResponseEntity<Map> first = call(HttpMethod.POST, t.ownerToken, "/api/v1/analytics/inventory-sync/run");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(first.getBody()).containsEntry("queued", true);
        ResponseEntity<Map> again = call(HttpMethod.POST, t.ownerToken, "/api/v1/analytics/inventory-sync/run");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(again.getBody()).containsEntry("error", "RATE_LIMITED").containsKey("nextRunAllowedAt");
        jdbc.update("UPDATE analytics_inventory_sync SET requested_at = now() - interval '11 minutes' WHERE tenant_id = ?", t.id);
        assertThat(call(HttpMethod.POST, t.ownerToken, "/api/v1/analytics/inventory-sync/run").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        Map<String, Object> st = call(HttpMethod.GET, t.ownerToken, "/api/v1/analytics/inventory-sync/status").getBody();
        assertThat(st).containsEntry("costStatus", "never").containsEntry("stockStatus", "never")
            .containsEntry("variantsTotal", 1).containsEntry("variantsCosted", 1);
        for (String role : List.of("manager", "worker")) {
            String tok = t.token(role);
            assertThat(call(HttpMethod.POST, tok, "/api/v1/analytics/inventory-sync/run").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call(HttpMethod.GET, tok, "/api/v1/analytics/inventory-sync/status").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call(HttpMethod.GET, tok, "/api/v1/analytics/profit/summary").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ── stock trust on fresh Shopify stock ───────────────────────────────────

    @Test
    void stockTrust_comparesPiecesAtTracedWithShopifyAtTraced_whenSynced() {
        T t = new T("S10-Trust");
        V a = t.variant("A", "100", null, null);
        for (int i = 0; i < 4; i++) piece(t, a, t.location);
        UUID showroom = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_fulfillment) VALUES (?, ?, 'Show', 'showroom', false)", showroom, t.id);
        piece(t, a, showroom);                                          // not at the Traced location: not compared
        // The stale V153 figure would say 40 (mismatch); the fresh one at the Traced location says 4.
        jdbc.update("UPDATE variants SET raw = '{\"inventory_quantity\":40}'::jsonb, stock_available_shopify = 9, " +
                    "stock_available_shopify_traced = 4, stock_synced_at = now() WHERE id = ?", a.id());
        Map<String, Object> trust = m(call(HttpMethod.GET, t.ownerToken, "/api/v1/analytics/stock/summary").getBody(), "trust");
        assertThat(trust).containsEntry("tracedAvailableTotal", 4).containsEntry("shopifyAvailableTotal", 4)
            .containsEntry("variantsWithMismatch", 0);
        assertThat((String) trust.get("shopifySource")).startsWith("variants.stock_available_shopify_traced");
        // Low trust (no Traced packing) → the stock figure used is the fresh all-locations total.
        Map<String, Object> v = ((List<Map<String, Object>>) call(HttpMethod.GET, t.ownerToken, "/api/v1/analytics/stock/variants")
            .getBody().get("variants")).get(0);
        assertThat(v).containsEntry("shopifyAvailable", 9).containsEntry("stockUsed", 9).containsEntry("stockSource", "shopify");
    }

    private void piece(T t, V v, UUID location) {
        String p = "S10" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                    "VALUES (?, ?, ?, ?, ?, 'available', ?)", p, t.id, v.id(), "PC-" + p, p, location);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> m(Map<String, Object> body, String key) {
        return (Map<String, Object>) body.get(key);
    }

    // ── profit ───────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void profit_grossMarginContributionByTypeAndTrueNetPerSku_withCoverage() {
        T t = new T("S10-Profit");
        V costed = t.variant("C", "100", "40", "shopify"), uncosted = t.variant("U", "100", null, null);
        // Order 1: 2 × C (200) + 1 × U (100), delivered, Bosta fee settled 30 → C gets 20, U gets 10.
        UUID o1 = order(t, 3);
        line(t, o1, costed, 2, "100.00");
        line(t, o1, uncosted, 1, "100.00");
        leg(t, o1, "delivered", "30.00");
        // Order 2: 1 × C refused, fee settled 15 → all of it on C, no revenue.
        UUID o2 = order(t, 4);
        line(t, o2, costed, 1, "100.00");
        leg(t, o2, "returned", "15.00");

        Map<String, Object> s = call(HttpMethod.GET, t.ownerToken, "/api/v1/analytics/profit/summary?period=30d").getBody();
        Map<String, Object> cov = m(s, "coverage");
        assertThat(cov).containsEntry("variantsSold", 2).containsEntry("variantsCosted", 1).containsEntry("keptUnits", 3)
            .containsEntry("costedUnits", 2);
        assertThat(dec(cov, "realizedTotal")).isEqualByComparingTo("300.00");
        assertThat(dec(cov, "realizedCosted")).isEqualByComparingTo("200.00");
        assertThat(dec(s, "cogs")).isEqualByComparingTo("80.00");                    // 2 kept × 40
        assertThat(dec(s, "grossMargin")).isEqualByComparingTo("0.6");                // (200 − 80) / 200
        assertThat(dec(s, "feesTotal")).isEqualByComparingTo("45.00");
        assertThat(dec(s, "feesOnCosted")).isEqualByComparingTo("35.00");             // 20 + 15
        assertThat(dec(s, "contributionProfit")).isEqualByComparingTo("85.00");       // 200 − 80 − 35

        Map<String, Object> skus = call(HttpMethod.GET, t.ownerToken, "/api/v1/analytics/profit/skus?period=30d").getBody();
        Map<String, Map<String, Object>> by = new HashMap<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) skus.get("skus")) by.put((String) r.get("variantId"), r);
        assertThat(dec(by.get(costed.id().toString()), "trueNet")).isEqualByComparingTo("85.00");
        assertThat(dec(by.get(costed.id().toString()), "shippingFees")).isEqualByComparingTo("20.00");
        assertThat(dec(by.get(costed.id().toString()), "otherFees")).isEqualByComparingTo("15.00");   // the refused leg
        assertThat(by.get(uncosted.id().toString())).containsEntry("trueNet", null).containsEntry("costed", false);
        assertThat(dec(by.get(uncosted.id().toString()), "netBeforeCost")).isEqualByComparingTo("90.00");

        Map<String, Object> types = call(HttpMethod.GET, t.ownerToken, "/api/v1/analytics/profit/by-product-type?period=30d").getBody();
        Map<String, Object> shirts = ((List<Map<String, Object>>) types.get("types")).get(0);
        assertThat(shirts).containsEntry("productType", "Shirts").containsEntry("keptUnits", 3).containsEntry("costedUnits", 2);
        assertThat(dec(shirts, "grossMargin")).isEqualByComparingTo("0.6");
        assertThat(call(HttpMethod.GET, t.ownerToken, "/api/v1/analytics/profit/skus?sort=nope").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        T none = new T("S10-NoCost");
        V u = none.variant("U", "100", null, null);
        UUID o = order(none, 2);
        line(none, o, u, 1, "100.00");
        leg(none, o, "delivered", "10.00");
        Map<String, Object> ns = call(HttpMethod.GET, none.ownerToken, "/api/v1/analytics/profit/summary?period=30d").getBody();
        assertThat(ns).containsEntry("cogs", null).containsEntry("grossMargin", null).containsEntry("contributionProfit", null);
        assertThat(m(ns, "coverage")).containsEntry("variantsCosted", 0).containsEntry("variantsSold", 1);
    }

    static BigDecimal dec(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : new BigDecimal(v.toString());
    }

    private UUID order(T t, int daysAgo) {
        UUID o = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw) " +
                    "VALUES (?, ?, ?, ?, ?, 'new'::order_status, ?, '{}'::jsonb)", o, t.id, t.store, "EXT-" + o,
                    "#" + SEQ.incrementAndGet(), Timestamp.from(Instant.now().minus(Duration.ofDays(daysAgo))));
        return o;
    }

    private void line(T t, UUID order, V v, int qty, String price) {
        long lineId = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id, raw) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                    t.id, order, v.id(), qty, "gid://shopify/LineItem/" + lineId,
                    "{\"id\":" + lineId + ",\"price\":\"" + price + "\",\"quantity\":" + qty + ",\"current_quantity\":" + qty +
                    ",\"discount_allocations\":[]}");
    }

    private void leg(T t, UUID order, String state, String fee) {
        UUID s = UUID.randomUUID();
        String raw = "{\"type\":{\"code\":10},\"cod\":100,\"dropOffAddress\":{\"city\":{\"_id\":\"id-Cairo\",\"name\":\"Cairo\"}}}";
        jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state, shipment_leg, raw) " +
                    "VALUES (?, ?, ?, ?, ?::shipment_internal_state, 'forward', ?::jsonb)", s, t.id, order, "8" + SEQ.incrementAndGet(), state, raw);
        ShipmentSettlement.apply(jdbc, s, ShipmentSettlementTest.json(raw));
        jdbc.update("UPDATE shipments SET settlement_status = 'deposited', cash_cycle_id = '1', deposited_amt = 50, " +
                    "bosta_fees = ?::numeric, deposited_at = now() WHERE id = ?", fee, s);
    }

    // ── RLS ──────────────────────────────────────────────────────────────────

    @Test
    void appUser_rls_readColumnsAndSyncStatusAreTheTenantsOwn() {
        T a = new T("S10-IsoA");
        T b = new T("S10-IsoB");
        V va = a.variant("A", "100", null, null);
        shopify.jsonl.addAll(bulkLines(va, "40.00", "EGP", 5, 0));
        a.run("manual");
        TenantAwareDataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        JdbcTemplate app = new JdbcTemplate(ds);
        TenantContext.set(b.id);
        assertThat((Long) tx.execute(s -> app.queryForObject("SELECT COUNT(*) FROM variant_shopify_levels", Long.class))).isZero();
        assertThat((Long) tx.execute(s -> app.queryForObject("SELECT COUNT(*) FROM analytics_inventory_sync", Long.class))).isZero();
        TenantContext.set(a.id);
        assertThat((Long) tx.execute(s -> app.queryForObject("SELECT COUNT(*) FROM variant_shopify_levels", Long.class))).isEqualTo(2);
        TenantContext.clear();
        assertThat((Long) tx.execute(s -> app.queryForObject("SELECT COUNT(*) FROM analytics_inventory_sync", Long.class))).isZero();
    }
    // ── webhooks under app_user + RLS, item lookup, first pass, topic backfill ─────

    @Test
    void webhooks_underAppUserRls_writeOwnTenantOnly_byReadSideItemId() throws Exception {
        T a = new T("S10-HookRlsA");
        T b = new T("S10-HookRlsB");
        V va = a.variant("A", "100", null, null);
        jdbc.update("UPDATE variants SET shopify_inventory_item_id = NULL WHERE id = ?", va.id());   // the write path never set it
        shopify.jsonl.addAll(bulkLines(va, "40.00", "EGP", 5, 0));
        a.run("manual");                                                                              // stores the read-side item id
        assertThat(jdbc.queryForObject("SELECT stock_inventory_item_id FROM variants WHERE id = ?", String.class, va.id()))
            .isEqualTo(va.itemId());

        TenantAwareDataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        AnalyticsInventoryWebhookHandler appHooks =
            new AnalyticsInventoryWebhookHandler(new JdbcTemplate(ds), new DataSourceTransactionManager(ds));
        JsonNode level = json("{\"inventory_item_id\":" + va.itemId() + ",\"location_id\":5,\"available\":11}");
        TenantContext.runAs(b.id, () -> appHooks.onInventoryLevelUpdate(level));                    // another tenant: no row
        assertThat(a.variantRow(va)).containsEntry("traced", 5);
        TenantContext.runAs(a.id, () -> appHooks.onInventoryLevelUpdate(level));
        assertThat(a.variantRow(va)).containsEntry("traced", 11).containsEntry("total", 11);
        JsonNode item = json("{\"id\":" + va.itemId() + ",\"cost\":\"33.00\"}");
        TenantContext.runAs(a.id, () -> appHooks.onInventoryItemUpdate(item));
        assertThat((BigDecimal) a.variantRow(va).get("unit_cost")).isEqualByComparingTo("33.00");
    }

    @Test
    void firstPass_runsTenantsThatNeverSynced_once() {
        T t = new T("S10-First");
        jdbc.update("UPDATE stores SET import_status = 'completed' WHERE id = ?", t.store);
        V a = t.variant("A", "100", null, null);
        shopify.jsonl.addAll(bulkLines(a, "40.00", "EGP", 2, 0));
        syncJob.firstPass();
        assertThat(t.status()).containsEntry("trigger_kind", "startup").containsEntry("cost_status", "ok");
        Object finished = t.status().get("finished_at");
        int runs = shopify.bulkRuns.get();
        syncJob.firstPass();                                                                          // a restart: nothing new
        assertThat(t.status().get("finished_at")).isEqualTo(finished);
        assertThat(shopify.bulkRuns.get()).isEqualTo(runs);
    }

    @Test
    void topicBackfill_addsOnlyMissingTopics_neverDeletes_stampsOnce() {
        T t = new T("S10-Topics");
        jdbc.update("UPDATE stores SET status = 'disconnected' WHERE tenant_id <> ? AND webhook_topics_version = 0 " +
                    "AND shop_domain LIKE 's10-%'", t.id);                                            // only this test's store is behind
        String base = "http://localhost:8080/webhooks/shopify/";
        List<ShopifyGateway.WebhookSubscription> existing = new ArrayList<>();
        for (String topic : List.of("orders/create", "orders/updated", "orders/cancelled", "products/create",
                                    "products/update", "app/uninstalled")) {
            existing.add(new ShopifyGateway.WebhookSubscription("gid://x/" + topic, topic.replace("/", "_").toUpperCase(), base + topic));
        }
        when(gateway.listWebhookSubscriptions(anyString(), anyString())).thenReturn(existing);
        doThrow(new ShopifyException("boom")).when(gateway)
            .registerWebhook(anyString(), anyString(), eq("inventory_levels/update"), anyString());

        topicsBackfill.runAll();                                                                      // one topic fails: not stamped
        verify(gateway).registerWebhook(anyString(), anyString(), eq("inventory_items/update"), eq(base + "inventory_items/update"));
        verify(gateway).registerWebhook(anyString(), anyString(), eq("inventory_levels/update"), anyString());
        verify(gateway, never()).registerWebhook(anyString(), anyString(), eq("orders/create"), anyString());
        verify(gateway, never()).deleteWebhookSubscription(anyString(), anyString(), anyString());
        assertThat(jdbc.queryForObject("SELECT webhook_topics_version FROM stores WHERE id = ?", Integer.class, t.store)).isZero();

        reset(gateway);
        when(gateway.listWebhookSubscriptions(anyString(), anyString())).thenReturn(existing);
        topicsBackfill.runAll();                                                                      // next startup: both added
        verify(gateway, times(2)).registerWebhook(anyString(), anyString(), anyString(), anyString());
        assertThat(jdbc.queryForObject("SELECT webhook_topics_version FROM stores WHERE id = ?", Integer.class, t.store)).isEqualTo(1);

        reset(gateway);
        topicsBackfill.runAll();                                                                      // stamped: skipped
        verifyNoInteractions(gateway);
    }
}
