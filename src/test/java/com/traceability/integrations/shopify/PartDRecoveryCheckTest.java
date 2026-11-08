package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.TestPostgres;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.IncrementRecoveryRules;
import com.traceability.inventory.ShopifyInventoryService;
import com.traceability.tenancy.TenantContext;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Part D fix P1 (2026-11-08) — the recovery check (the identical re-send of an ambiguous increment) end to end:
 * the REAL ShopifyInventoryService on the REAL ShopifyHttpGateway, against a fake Shopify that behaves as Move S0
 * measured it: with changeFromQuantity set, Shopify checks the baseline BEFORE it looks up the idempotency key — so
 * an identical re-send of an adjust that LANDED answers CHANGE_FROM_QUANTITY_STALE; a key already used with other
 * parameters answers IDEMPOTENCY_KEY_PARAMETER_MISMATCH; a userError stores no key.
 *
 *   k0 — positive control: the first send never arrived → the recovery check applies it, once
 *   k1 — the first send landed (read timeout): ONE physical request; the recovery check answers STALE → the claim
 *        stays failed / ambiguous, probeStale, next_attempt_at NULL; Shopify counted the +3 once
 *   k2 — after k1, two more retry passes (even with next_attempt_at forced due) send nothing; the gave-up alert
 *        (inventory_increment_sync_failed) is raised; Shopify still +3 once
 *   k3 — manual repush of a probeStale claim: 409 CONFIRMATION_REQUIRED without confirmOld; with it, a NEW key and a
 *        fresh baseline
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PartDRecoveryCheckTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
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

    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";
    static final ObjectMapper M = new ObjectMapper();

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired ExceptionService exceptions;
    @MockBean ShopifyTokenProvider tokenProvider;

    record T(UUID tenant, UUID store, String shop, UUID location, String traced) {}
    record V(UUID id, String item) {}

    /** A fake Shopify with Move S0's semantics for inventoryAdjustQuantities. */
    static final class FakeShopify {
        int available;
        int adjustRequests;
        int lostAnswerOn = -1;       // this adjust request (1-based): applied, but the answer never arrives
        int neverArrivesOn = -1;     // this adjust request (1-based): read timeout, NOT applied
        final Map<String, String> keys = new HashMap<>();   // idempotency key → the change it was used with
        final List<JsonNode> adjustBodies = new ArrayList<>();
        final List<String> unexpected = new ArrayList<>();

        ClientHttpRequestInterceptor interceptor() {
            return (request, body, execution) -> {
                JsonNode req = M.readTree(new String(body, StandardCharsets.UTF_8));
                String q = req.path("query").asText();
                String json;
                if (q.contains("inventoryActivate(")) {
                    json = "{\"data\":{\"inventoryActivate\":{\"inventoryLevel\":{\"id\":\"lvl\"},\"userErrors\":[]}}}";
                } else if (q.contains("InventoryLevelsAtLocation")) {
                    String item = req.path("variables").path("ids").get(0).asText();
                    json = "{\"data\":{\"nodes\":[{\"id\":\"" + item + "\",\"inventoryLevel\":{\"quantities\":" +
                        "[{\"name\":\"available\",\"quantity\":" + available + "}]}}]}}";
                } else if (q.contains("TracedMainLevelActive")) {
                    // Part D P2: the level check before a recovery check — the level is active.
                    String item = req.path("variables").path("ids").get(0).asText();
                    json = "{\"data\":{\"nodes\":[{\"id\":\"" + item + "\",\"inventoryLevel\":{\"id\":\"lvl\"}}]}}";
                } else if (q.contains("inventoryAdjustQuantities")) {
                    json = adjust(req);
                } else {
                    unexpected.add(q);
                    json = "{\"errors\":[{\"message\":\"unexpected request in test\"}]}";
                }
                MockClientHttpResponse resp = new MockClientHttpResponse(json.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return resp;
            };
        }

        private String adjust(JsonNode req) throws IOException {
            adjustRequests++;
            adjustBodies.add(req);
            if (adjustRequests == neverArrivesOn) throw new java.net.SocketTimeoutException("Read timed out");
            JsonNode vars = req.path("variables");
            String key = vars.path("idempotencyKey").asText();
            JsonNode change = vars.path("input").path("changes").get(0);
            JsonNode cfq = change.path("changeFromQuantity");
            // S0: the compare-and-set check runs BEFORE the idempotency lookup.
            if (!cfq.isNull() && cfq.asInt() != available) {
                return userError("The specified compare quantity does not match the current quantity", "CHANGE_FROM_QUANTITY_STALE");
            }
            String params = change.toString() + vars.path("input").path("reason").asText();
            if (keys.containsKey(key)) {
                if (keys.get(key).equals(params)) return ok();   // a true replay — nothing applied again
                return userError("The idempotency key was used with different parameters", "IDEMPOTENCY_KEY_PARAMETER_MISMATCH");
            }
            available += change.path("delta").asInt();
            keys.put(key, params);
            if (adjustRequests == lostAnswerOn) throw new java.net.SocketTimeoutException("Read timed out");
            return ok();
        }

        private static String ok() {
            return "{\"data\":{\"inventoryAdjustQuantities\":{\"inventoryAdjustmentGroup\":" +
                "{\"createdAt\":\"2026-11-08T00:00:00Z\"},\"userErrors\":[]}}}";
        }

        private static String userError(String message, String code) {
            return "{\"data\":{\"inventoryAdjustQuantities\":{\"inventoryAdjustmentGroup\":null," +
                "\"userErrors\":[{\"field\":[\"input\"],\"message\":\"" + message + "\",\"code\":\"" + code + "\"}]}}}";
        }
    }

    FakeShopify shopify;
    ShopifyInventoryService inventory;

    @BeforeEach
    void setUp() {
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        shopify = new FakeShopify();
        shopify.available = 10;
        ShopifyHttpGateway gateway = new ShopifyHttpGateway(RestClient.builder().requestInterceptor(shopify.interceptor()),
            M, "2026-04", "id", "secret");
        inventory = new ShopifyInventoryService(jdbc, txm, gateway, tokenProvider, M, new StoreRepository(jdbc, txm));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        assertThat(shopify.unexpected).as("no unexpected Shopify request").isEmpty();
    }

    @Test
    void k0_firstSendNeverArrived_recoveryCheckAppliesItOnce() throws Exception {
        T t = tenant("k0"); V v = variant(t, "k0");
        shopify.neverArrivesOn = 1;
        UUID s = receive(t, v, 3);
        assertThat(row(t, s, v)).containsEntry("status", "failed").containsEntry("failure_class", "ambiguous")
            .containsEntry("change_from_quantity", 10);
        assertThat(shopify.available).isEqualTo(10);

        makeDue(t, s, v);
        retry(t);

        assertThat(row(t, s, v).get("status")).isEqualTo("applied");
        assertThat(shopify.available).isEqualTo(13);
        assertThat(shopify.adjustRequests).isEqualTo(2);
        assertThat(shopify.adjustBodies.get(1).path("variables").path("idempotencyKey").asText())
            .as("the recovery check is an identical re-send")
            .isEqualTo(shopify.adjustBodies.get(0).path("variables").path("idempotencyKey").asText());
    }

    @Test
    void k1_firstSendLanded_recoveryCheckStale_staysAmbiguous_neverRejected() throws Exception {
        T t = tenant("k1"); V v = variant(t, "k1");
        UUID s = landedThenRecoveryCheckStale(t, v);

        Map<String, Object> r = row(t, s, v);
        assertThat(r).containsEntry("status", "failed").containsEntry("failure_class", "ambiguous")
            .containsEntry("next_attempt_at", null);
        assertThat(r.get("probe_stale")).isEqualTo("true");
        assertThat((String) r.get("error")).contains("CHANGE_FROM_QUANTITY_STALE");
        assertThat(shopify.available).as("Shopify counted the +3 exactly once").isEqualTo(13);
    }

    @Test
    void k2_afterAStaleRecoveryCheck_noFurtherSend_gaveUpAlert() throws Exception {
        T t = tenant("k2"); V v = variant(t, "k2");
        UUID s = landedThenRecoveryCheckStale(t, v);

        for (int pass = 0; pass < 2; pass++) {
            // Even forced due, a probeStale claim is never picked by the automatic retry.
            jdbc.update("UPDATE shopify_inventory_adjustments SET next_attempt_at = now() - interval '1 minute' " +
                "WHERE tenant_id = ? AND trigger_id = ?", t.tenant(), s.toString());
            retry(t);
        }

        assertThat(shopify.adjustRequests).as("no send after the stale recovery check").isEqualTo(2);
        assertThat(shopify.available).isEqualTo(13);
        Map<String, Object> alert = TenantContext.runAs(t.tenant(), () -> exceptions.detectAllOpen()).stream()
            .filter(e -> "inventory_increment_sync_failed".equals(e.get("type")) && "gave_up".equals(e.get("kind")))
            .findFirst().orElse(null);
        assertThat(alert).as("a person is asked to check Shopify").isNotNull();
        assertThat(((Number) alert.get("units")).intValue()).isEqualTo(3);
    }

    @Test
    void k3_manualRepushOfAStaleRecoveryCheck_needsConfirmOld_thenANewKey() throws Exception {
        T t = tenant("k3"); V v = variant(t, "k3");
        UUID s = landedThenRecoveryCheckStale(t, v);

        TenantContext.set(t.tenant());
        assertThatThrownBy(() -> inventory.repushFailedIncrement("receiving_session", s.toString(), v.id(), false))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("CONFIRMATION_REQUIRED");
        assertThat(shopify.adjustRequests).isEqualTo(2);

        // The person checked Shopify and confirms: a deliberate NEW send (new key, fresh baseline).
        inventory.repushFailedIncrement("receiving_session", s.toString(), v.id(), true);
        TenantContext.clear();

        assertThat(shopify.adjustRequests).isEqualTo(3);
        JsonNode vars = shopify.adjustBodies.get(2).path("variables");
        assertThat(vars.path("idempotencyKey").asText()).isEqualTo(IncrementRecoveryRules.retryKey(
            t.tenant(), "receiving_session", s.toString(), v.id(), t.location(), 3));
        assertThat(vars.path("input").path("changes").get(0).path("changeFromQuantity").asInt())
            .as("a fresh baseline").isEqualTo(13);
        assertThat(row(t, s, v).get("status")).isEqualTo("applied");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Receive +3; the first adjust lands but its answer is lost; one retry pass sends the recovery check. */
    private UUID landedThenRecoveryCheckStale(T t, V v) throws Exception {
        shopify.lostAnswerOn = 1;
        UUID s = receive(t, v, 3);
        assertThat(shopify.adjustRequests).as("ONE physical send — no transport retry").isEqualTo(1);
        assertThat(shopify.available).isEqualTo(13);
        assertThat(row(t, s, v)).containsEntry("failure_class", "ambiguous").containsEntry("change_from_quantity", 10);

        makeDue(t, s, v);
        retry(t);
        assertThat(shopify.adjustRequests).as("the recovery check was sent").isEqualTo(2);
        assertThat(shopify.adjustBodies.get(1).path("variables").path("input").path("changes").get(0)
            .path("changeFromQuantity").asInt()).isEqualTo(10);
        return s;
    }

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String traced = "gid://shopify/Location/" + shop;
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?)", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", location, tenant, traced);
        return new T(tenant, store, shop, location, traced);
    }

    private V variant(T t, String key) {
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        String item = "gid://shopify/InventoryItem/" + key + "-" + variant.toString().substring(0, 6);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, t.tenant(), t.store(), "gid://shopify/Product/" + t.shop() + "-" + key);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", variant, t.tenant(), product,
            "gid://shopify/ProductVariant/" + t.shop() + "-" + key, "SKU-" + key, item);
        return new V(variant, item);
    }

    private UUID receive(T t, V v, int qty) throws Exception {
        UUID session = UUID.randomUUID();
        inventory.onReceivingSessionClose(t.tenant(), session, t.location(), Map.of(v.id(), qty)).get(5, TimeUnit.SECONDS);
        return session;
    }

    private void makeDue(T t, UUID session, V v) {
        jdbc.update("UPDATE shopify_inventory_adjustments SET next_attempt_at = now() - interval '1 second' " +
            "WHERE tenant_id = ? AND trigger_id = ? AND variant_id = ? AND next_attempt_at IS NOT NULL",
            t.tenant(), session.toString(), v.id());
    }

    private void retry(T t) {
        TenantContext.runAs(t.tenant(), inventory::retryDueIncrements);
    }

    private Map<String, Object> row(T t, UUID session, V v) {
        return jdbc.queryForMap("SELECT status, failure_class, change_from_quantity, attempt_count, next_attempt_at, " +
            "error, payload->>'probeStale' AS probe_stale FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_id = ? AND variant_id = ?", t.tenant(), session.toString(), v.id());
    }
}
