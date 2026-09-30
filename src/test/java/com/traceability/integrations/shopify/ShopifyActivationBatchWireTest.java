package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Activation perf, wire level — the REAL ShopifyHttpGateway, a fake interceptor instead of the
 * network (same harness as ShopifyHttpGatewayInventoryTest.g5).
 *
 *   bw1 — 60 activations → 3 requests of 25 / 25 / 10 aliases; each alias its own item + key
 *         variable, one shared location variable; results in request order.
 *   bw2 — one alias's error (path-scoped GraphQL error) and one alias's userError fail only
 *         those two; "already active" is success; the rest succeed.
 *   bw3 — no quantity argument, ever: every inventoryActivate(...) on the wire (batch and single)
 *         has exactly inventoryItemId + locationId, and no request variable carries a quantity.
 *   bw4 — at most 2 requests in flight (and the batches do overlap).
 *   bw5 — resolveInventoryItemIds: 600 variant GIDs → 3 nodes(ids:) reads of ≤250.
 *   bw6 — fetchAvailableQuantities: 600 item GIDs → 3 nodes(ids:) reads of ≤250.
 *   bw7 — the products import query asks for inventoryItem { id } and the parsed Variant carries it
 *         (main query and the follow-up variants page).
 *   bw8 — a batch that exhausts the THROTTLED retries is sent again (same keys) instead of failing
 *         its 25 variants.
 */
class ShopifyActivationBatchWireTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String SHOP = "shop.myshopify.com";
    private static final String LOC = "gid://shopify/Location/traced";

    /** A fake Shopify: records every request body and answers with responder(body). */
    private static final class FakeShopify {
        final List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final Function<JsonNode, String> responder;
        final long latencyMs;

        FakeShopify(Function<JsonNode, String> responder, long latencyMs) {
            this.responder = responder;
            this.latencyMs = latencyMs;
        }

        ShopifyHttpGateway gateway() {
            ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
                int now = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(now, Math::max);
                try {
                    JsonNode req = M.readTree(new String(body, StandardCharsets.UTF_8));
                    requests.add(req);
                    if (latencyMs > 0) Thread.sleep(latencyMs);
                    MockClientHttpResponse resp = new MockClientHttpResponse(
                        responder.apply(req).getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                    resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return resp;
                } catch (InterruptedException e) {
                    throw new java.io.IOException(e);
                } finally {
                    inFlight.decrementAndGet();
                }
            };
            return new ShopifyHttpGateway(RestClient.builder().requestInterceptor(interceptor), M,
                "2026-04", "test-client-id", "test-client-secret");
        }
    }

    /** Answers a batch activation: every alias succeeds unless aliasOverride says otherwise. */
    private static String activationResponse(JsonNode req, Function<Integer, String> aliasOverride) {
        int n = aliasCount(req);
        StringBuilder data = new StringBuilder("{");
        for (int i = 0; i < n; i++) {
            if (i > 0) data.append(',');
            String o = aliasOverride.apply(i);
            data.append("\"a").append(i).append("\":").append(o != null ? o
                : "{\"inventoryLevel\":{\"id\":\"lvl" + i + "\"},\"userErrors\":[]}");
        }
        return "{\"data\":" + data.append('}') + "}";
    }

    private static int aliasCount(JsonNode req) {
        Matcher m = Pattern.compile("\\ba(\\d+): inventoryActivate\\(").matcher(req.path("query").asText());
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static List<ShopifyGateway.ActivationRequest> requests(int n) {
        List<ShopifyGateway.ActivationRequest> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new ShopifyGateway.ActivationRequest("gid://shopify/InventoryItem/" + i, "key-" + i));
        return out;
    }

    @Test
    void bw1_sixtyActivations_threeRequestsOf25_25_10() {
        FakeShopify fake = new FakeShopify(req -> activationResponse(req, i -> null), 0);
        List<ShopifyGateway.ActivationResult> results =
            fake.gateway().activateInventoryItems(SHOP, "tok", LOC, requests(60));

        assertThat(fake.requests).hasSize(3);
        List<Integer> sizes = fake.requests.stream().map(ShopifyActivationBatchWireTest::aliasCount).sorted().toList();
        assertThat(sizes).containsExactly(10, 25, 25);
        for (JsonNode req : fake.requests) {
            JsonNode vars = req.path("variables");
            int n = aliasCount(req);
            assertThat(vars.path("locationId").asText()).isEqualTo(LOC);
            for (int i = 0; i < n; i++) {
                assertThat(req.path("query").asText())
                    .contains("a" + i + ": inventoryActivate(inventoryItemId: $i" + i + ", locationId: $locationId) @idempotent(key: $k" + i + ")");
                String item = vars.path("i" + i).asText();
                String idx = item.substring(item.lastIndexOf('/') + 1);
                assertThat(vars.path("k" + i).asText()).as("each alias carries its own key").isEqualTo("key-" + idx);
            }
        }
        assertThat(results).hasSize(60).allMatch(ShopifyGateway.ActivationResult::ok);
        for (int i = 0; i < 60; i++) {
            assertThat(results.get(i).inventoryItemGid()).as("results in request order").isEqualTo("gid://shopify/InventoryItem/" + i);
        }
    }

    @Test
    void bw2_oneAliasError_othersSucceed_errorRecorded() {
        // One batch of 5: a1 fails with a path-scoped GraphQL error (data.a1 = null), a3 gets a
        // userError, a4 says "already active" — only a1 and a3 fail.
        FakeShopify fake = new FakeShopify(req -> {
            String base = activationResponse(req, i -> switch (i) {
                case 1 -> "null";
                case 3 -> "{\"inventoryLevel\":null,\"userErrors\":[{\"field\":[\"inventoryItemId\"],\"message\":\"Inventory item does not exist\"}]}";
                case 4 -> "{\"inventoryLevel\":null,\"userErrors\":[{\"field\":null,\"message\":\"Inventory item is already active at this location\"}]}";
                default -> null;
            });
            return base.substring(0, base.length() - 1)
                + ",\"errors\":[{\"message\":\"Internal error on a1\",\"path\":[\"a1\"]}]}";
        }, 0);

        List<ShopifyGateway.ActivationResult> results = fake.gateway().activateInventoryItems(SHOP, "tok", LOC, requests(5));

        assertThat(fake.requests).hasSize(1);
        assertThat(results).extracting(ShopifyGateway.ActivationResult::ok).containsExactly(true, false, true, false, true);
        assertThat(results.get(1).error()).contains("Internal error on a1");
        assertThat(results.get(3).error()).contains("Inventory item does not exist");
    }

    @Test
    void bw3_noQuantityArgument_everOnTheWire() throws Exception {
        FakeShopify fake = new FakeShopify(req -> req.path("query").asText().contains("BatchInventoryActivate")
            ? activationResponse(req, i -> null)
            : "{\"data\":{\"inventoryActivate\":{\"inventoryLevel\":{\"id\":\"x\"},\"userErrors\":[]}}}", 0);
        ShopifyHttpGateway gw = fake.gateway();
        gw.activateInventoryItems(SHOP, "tok", LOC, requests(30));
        gw.activateInventoryItem(SHOP, "tok", "gid://shopify/InventoryItem/single", LOC, "key-single");

        Field single = ShopifyHttpGateway.class.getDeclaredField("INVENTORY_ACTIVATE_MUTATION");
        single.setAccessible(true);
        List<String> documents = new ArrayList<>(fake.requests.stream().map(r -> r.path("query").asText()).toList());
        documents.add((String) single.get(null));
        documents.add(ShopifyHttpGateway.batchActivateMutation(ShopifyGateway.ACTIVATION_BATCH_SIZE));

        Pattern call = Pattern.compile("inventoryActivate\\(([^)]*)\\)");
        int calls = 0;
        for (String doc : documents) {
            Matcher m = call.matcher(doc);
            while (m.find()) {
                calls++;
                List<String> argNames = new ArrayList<>();
                for (String arg : m.group(1).split(",")) argNames.add(arg.split(":")[0].trim());
                assertThat(argNames).as("inventoryActivate arguments").containsExactly("inventoryItemId", "locationId");
            }
            assertThat(doc).doesNotContain("available:").doesNotContain("onHand:");
        }
        assertThat(calls).isEqualTo(30 + 1 + 1 + ShopifyGateway.ACTIVATION_BATCH_SIZE);
        for (JsonNode req : fake.requests) {
            req.path("variables").fieldNames().forEachRemaining(name ->
                assertThat(name).as("request variables").matches("locationId|i\\d+|k\\d+|inventoryItemId|idempotencyKey"));
        }
    }

    @Test
    void bw4_atMostTwoRequestsInFlight() {
        FakeShopify fake = new FakeShopify(req -> activationResponse(req, i -> null), 150);
        fake.gateway().activateInventoryItems(SHOP, "tok", LOC, requests(125));   // 5 batches

        assertThat(fake.requests).hasSize(5);
        assertThat(fake.maxInFlight.get()).as("concurrency ≤ 2, and actually used").isEqualTo(2);
    }

    @Test
    void bw5_resolveItemIds_600_threeNodesReadsOfAtMost250() {
        FakeShopify fake = new FakeShopify(req -> {
            StringBuilder nodes = new StringBuilder();
            for (JsonNode id : req.path("variables").path("ids")) {
                if (nodes.length() > 0) nodes.append(',');
                String v = id.asText();
                nodes.append("{\"id\":\"").append(v).append("\",\"inventoryItem\":{\"id\":\"item-")
                     .append(v.substring(v.lastIndexOf('/') + 1)).append("\"}}");
            }
            return "{\"data\":{\"nodes\":[" + nodes + "]}}";
        }, 0);
        List<String> gids = new ArrayList<>();
        for (int i = 0; i < 600; i++) gids.add("gid://shopify/ProductVariant/" + i);

        Map<String, String> out = fake.gateway().resolveInventoryItemIds(SHOP, "tok", gids);

        assertThat(fake.requests).hasSize(3);
        assertThat(fake.requests).allSatisfy(r -> {
            assertThat(r.path("query").asText()).contains("nodes(ids: $ids)");
            assertThat(r.path("variables").path("ids").size()).isLessThanOrEqualTo(250);
        });
        assertThat(out).hasSize(600).containsEntry("gid://shopify/ProductVariant/599", "item-599");
    }

    @Test
    void bw6_fetchAvailableQuantities_600_threeNodesReadsOfAtMost250() {
        FakeShopify fake = new FakeShopify(req -> {
            StringBuilder nodes = new StringBuilder();
            for (JsonNode id : req.path("variables").path("ids")) {
                if (nodes.length() > 0) nodes.append(',');
                nodes.append("{\"id\":\"").append(id.asText())
                     .append("\",\"inventoryLevel\":{\"quantities\":[{\"name\":\"available\",\"quantity\":2}]}}");
            }
            return "{\"data\":{\"nodes\":[" + nodes + "]}}";
        }, 0);
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 600; i++) items.add("gid://shopify/InventoryItem/" + i);

        List<ShopifyGateway.InventoryLevel> levels = fake.gateway().fetchAvailableQuantities(SHOP, "tok", LOC, items);

        assertThat(fake.requests).hasSize(3);
        assertThat(fake.requests.stream().map(r -> r.path("variables").path("ids").size()).toList())
            .containsExactly(250, 250, 100);
        assertThat(levels).hasSize(600).allMatch(l -> l.available() == 2);
    }

    @Test
    void bw7_importQueryAsksForInventoryItem_andTheVariantCarriesIt() {
        FakeShopify fake = new FakeShopify(req -> req.path("query").asText().contains("ProductVariantsPage")
            ? "{\"data\":{\"product\":{\"variants\":{\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null},\"edges\":[" +
              "{\"node\":{\"id\":\"gid://shopify/ProductVariant/2\",\"sku\":\"B\",\"title\":\"B\",\"price\":\"1.00\"," +
              "\"inventoryItem\":{\"id\":\"gid://shopify/InventoryItem/20\"}}}]}}}}"
            : "{\"data\":{\"products\":{\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null},\"edges\":[{\"node\":{" +
              "\"id\":\"gid://shopify/Product/1\",\"title\":\"P\",\"status\":\"DRAFT\",\"featuredImage\":null," +
              "\"variants\":{\"pageInfo\":{\"hasNextPage\":true,\"endCursor\":\"c1\"},\"edges\":[" +
              "{\"node\":{\"id\":\"gid://shopify/ProductVariant/1\",\"sku\":\"A\",\"title\":\"A\",\"price\":\"1.00\"," +
              "\"inventoryItem\":{\"id\":\"gid://shopify/InventoryItem/10\"}}}]}}}]}}}", 0);

        ShopifyGateway.ProductPage page = fake.gateway().fetchProductsPage(SHOP, "tok", null);

        assertThat(fake.requests).hasSize(2).allSatisfy(r ->
            assertThat(r.path("query").asText()).contains("inventoryItem { id }"));
        assertThat(page.products().get(0).variants()).extracting(ShopifyGateway.Variant::inventoryItemGid)
            .containsExactly("gid://shopify/InventoryItem/10", "gid://shopify/InventoryItem/20");
    }

    @Test
    void bw8_batchThrottledPastTheRetryCap_isResent_notFailed() {
        AtomicInteger calls = new AtomicInteger();
        String throttled = "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}]," +
            "\"extensions\":{\"cost\":{\"requestedQueryCost\":1,\"actualQueryCost\":0," +
            "\"throttleStatus\":{\"maximumAvailable\":1000,\"currentlyAvailable\":1000,\"restoreRate\":1000}}}}";
        // 6 THROTTLED answers = the first send exhausts its 5 throttle retries; the resend succeeds.
        FakeShopify fake = new FakeShopify(req -> calls.incrementAndGet() <= 6 ? throttled : activationResponse(req, i -> null), 0);

        List<ShopifyGateway.ActivationResult> results = fake.gateway().activateInventoryItems(SHOP, "tok", LOC, requests(3));

        assertThat(results).hasSize(3).allMatch(ShopifyGateway.ActivationResult::ok);
        assertThat(fake.requests).hasSize(7);
        assertThat(fake.requests.stream().map(r -> r.path("variables").toString()).distinct())
            .as("every send carries the same items and keys").hasSize(1);
    }
}
