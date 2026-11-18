package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.ShopifyMoveSourceGateway.Level;
import com.traceability.integrations.shopify.ShopifyMoveSourceGateway.MoveSourceReadException;
import com.traceability.integrations.shopify.ShopifyMoveSourceGateway.ShopifyLocation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Receiving move M1 — the read-only gateway on Shopify's answer shapes (2026-04):
 *   w1 the location list follows the cursor past the first page (60 + 250 + 3 locations, not just 50), flags
 *      fulfillmentService, asks Shopify to leave inactive / legacy locations out
 *   w2 levels are read at the location the caller names (the source), 250 variant ids per call; an inactive or missing
 *      level is "no level"
 *   w3 products with stock: distinct products with available > 0, all pages
 *   w4 a GraphQL error → MoveSourceReadException with its codes only
 */
class ShopifyMoveSourceGatewayImplTest {

    final ObjectMapper om = new ObjectMapper();
    final ShopifyGateway shopify = mock(ShopifyGateway.class);
    final ShopifyMoveSourceGatewayImpl gw = new ShopifyMoveSourceGatewayImpl(shopify, om);

    ObjectNode locationsPage(int from, int count, boolean next, String cursor) {
        ObjectNode root = om.createObjectNode();
        ObjectNode locs = root.putObject("data").putObject("locations");
        ArrayNode nodes = locs.putArray("nodes");
        for (int i = from; i < from + count; i++) {
            ObjectNode n = nodes.addObject().put("id", "gid://shopify/Location/" + i).put("name", "L" + i).put("isActive", true);
            if (i % 7 == 0) n.putObject("fulfillmentService").put("id", "gid://shopify/FulfillmentService/" + i);
            else n.putNull("fulfillmentService");
        }
        locs.putObject("pageInfo").put("hasNextPage", next).put("endCursor", cursor);
        return root;
    }

    @Test
    void w1_locations_allPages_andAppManagedFlagged() {
        when(shopify.executeGraphQLResponsePublic(eq("s"), eq("t"), eq(ShopifyMoveSourceGatewayImpl.LOCATIONS_QUERY), any()))
            .thenReturn(locationsPage(1, 60, true, "c1"), locationsPage(61, 250, true, "c2"), locationsPage(311, 3, false, null));
        List<ShopifyLocation> all = gw.listLocations("s", "t");
        assertThat(all).hasSize(313);
        assertThat(all.get(312).gid()).isEqualTo("gid://shopify/Location/313");
        assertThat(all.stream().filter(ShopifyLocation::appManaged).map(ShopifyLocation::gid))
            .contains("gid://shopify/Location/7").doesNotContain("gid://shopify/Location/8");
        ArgumentCaptor<JsonNode> vars = ArgumentCaptor.forClass(JsonNode.class);
        verify(shopify, times(3)).executeGraphQLResponsePublic(eq("s"), eq("t"), anyString(), vars.capture());
        assertThat(vars.getAllValues().get(0).has("after")).isFalse();
        assertThat(vars.getAllValues().get(1).path("after").asText()).isEqualTo("c1");
        assertThat(vars.getAllValues().get(2).path("after").asText()).isEqualTo("c2");
        assertThat(vars.getAllValues().get(0).path("first").asInt()).isEqualTo(250);
        assertThat(ShopifyMoveSourceGatewayImpl.LOCATIONS_QUERY)
            .contains("includeInactive: false").contains("includeLegacy: false").contains("fulfillmentService { id }");
    }

    @Test
    void w2_levels_atTheNamedLocation_chunked_noLevelWhenInactiveOrMissing() {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 260; i++) ids.add("gid://shopify/ProductVariant/" + i);
        when(shopify.executeGraphQLResponsePublic(any(), any(), eq(ShopifyMoveSourceGatewayImpl.LEVELS_QUERY), any()))
            .thenAnswer(inv -> {
                JsonNode v = inv.getArgument(3);
                ObjectNode root = om.createObjectNode();
                ArrayNode nodes = root.putObject("data").putArray("nodes");
                for (JsonNode id : v.path("ids")) {
                    String gid = id.asText();
                    int n = Integer.parseInt(gid.replaceAll("\\D", ""));
                    ObjectNode node = nodes.addObject().put("id", gid);
                    ObjectNode item = node.putObject("inventoryItem").put("id", "gid://shopify/InventoryItem/" + n);
                    if (n == 2) { item.putNull("inventoryLevel"); continue; }
                    ObjectNode level = item.putObject("inventoryLevel").put("id", "lvl" + n).put("isActive", n != 3);
                    ArrayNode qs = level.putArray("quantities");
                    qs.addObject().put("name", "available").put("quantity", n * 2);
                    qs.addObject().put("name", "committed").put("quantity", 1);
                    qs.addObject().put("name", "on_hand").put("quantity", n * 2 + 1);
                }
                return root;
            });
        Map<String, Level> out = gw.fetchLocationLevels("s", "t", "gid://shopify/Location/555", ids);
        assertThat(out).hasSize(260);
        assertThat(out.get("gid://shopify/ProductVariant/1")).isEqualTo(new Level("gid://shopify/ProductVariant/1", true, 2, 1, 3));
        assertThat(out.get("gid://shopify/ProductVariant/2").level()).isFalse();
        assertThat(out.get("gid://shopify/ProductVariant/3").level()).isFalse();
        ArgumentCaptor<JsonNode> vars = ArgumentCaptor.forClass(JsonNode.class);
        verify(shopify, times(2)).executeGraphQLResponsePublic(any(), any(), anyString(), vars.capture());
        assertThat(vars.getAllValues()).allSatisfy(v -> assertThat(v.path("loc").asText()).isEqualTo("gid://shopify/Location/555"));
        assertThat(vars.getAllValues().get(0).path("ids")).hasSize(250);
        assertThat(vars.getAllValues().get(1).path("ids")).hasSize(10);
    }

    ObjectNode stockPage(int[][] productAvailable, boolean next, String cursor) {
        ObjectNode root = om.createObjectNode();
        ObjectNode levels = root.putObject("data").putObject("location").put("id", "gid://shopify/Location/9")
            .putObject("inventoryLevels");
        ArrayNode nodes = levels.putArray("nodes");
        for (int[] pa : productAvailable) {
            ObjectNode n = nodes.addObject();
            n.putArray("quantities").addObject().put("name", "available").put("quantity", pa[1]);
            n.putObject("item").putObject("variants").putArray("nodes").addObject().putObject("product")
                .put("id", "gid://shopify/Product/" + pa[0]);
        }
        levels.putObject("pageInfo").put("hasNextPage", next).put("endCursor", cursor);
        return root;
    }

    @Test
    void w3_productsWithStock_distinct_availablePositive_allPages() {
        when(shopify.executeGraphQLResponsePublic(any(), any(), eq(ShopifyMoveSourceGatewayImpl.STOCK_QUERY), any()))
            .thenReturn(stockPage(new int[][] {{1, 5}, {1, 2}, {2, 0}, {3, -1}}, true, "p2"),
                        stockPage(new int[][] {{4, 1}, {1, 9}}, false, null));
        assertThat(gw.countProductsWithStock("s", "t", "gid://shopify/Location/9")).isEqualTo(2);
        verify(shopify, times(2)).executeGraphQLResponsePublic(any(), any(), anyString(), any());
    }

    @Test
    void w4_graphqlError_codesOnly() {
        ObjectNode err = om.createObjectNode();
        err.putArray("errors").addObject().put("message", "secret body").putObject("extensions").put("code", "ACCESS_DENIED");
        when(shopify.executeGraphQLResponsePublic(any(), any(), any(), any())).thenReturn(err);
        assertThatThrownBy(() -> gw.listLocations("s", "t"))
            .isInstanceOf(MoveSourceReadException.class)
            .satisfies(e -> assertThat(((MoveSourceReadException) e).reason()).isEqualTo("ACCESS_DENIED"))
            .hasMessageNotContaining("secret body");
    }
}
