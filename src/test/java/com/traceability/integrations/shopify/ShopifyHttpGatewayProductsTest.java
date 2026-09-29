package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog import's REAL wire traffic (a real ShopifyHttpGateway, the request body inspected):
 * every product status is asked for, statuses come back lowercase, and a product with more
 * variants than the first page is completed with follow-up queries — never cut off.
 *
 * p1 and p2 are revert-checked: p1 fails with {@code query: "status:active"} restored, p2 fails
 * without the variant follow-up loop (only the first 2 variants come back).
 */
class ShopifyHttpGatewayProductsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void p1_productsQuery_hasNoStatusFilter() throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        ShopifyHttpGateway gateway = gateway(bodies, body -> page("""
            {"id":"gid://shopify/Product/1","title":"Shirt","status":"ACTIVE","featuredImage":null,
             "variants":{"pageInfo":{"hasNextPage":false,"endCursor":null},"edges":[]}}"""));

        gateway.fetchProductsPage("shop.myshopify.com", "tok", null);

        assertThat(bodies).hasSize(1);
        String query = bodies.get(0).path("query").asText();
        assertThat(query).contains("products(first: 50, after: $cursor)");
        assertThat(query).as("no status filter — draft, archived and unlisted products import too")
            .doesNotContain("status:").doesNotContain("query:");
    }

    @Test
    void p2_productWithTwoVariantPages_allVariantsReturned_followUpCarriesProductIdAndCursor() throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        ShopifyHttpGateway gateway = gateway(bodies, body -> {
            if (body.path("query").asText().contains("ProductVariantsPage")) {
                String cursor = body.path("variables").path("cursor").asText();
                return "c2".equals(cursor)
                    ? variantPage(true, "c3", "V3", "V4")
                    : variantPage(false, null, "V5");
            }
            return page("""
                {"id":"gid://shopify/Product/9","title":"Big","status":"ACTIVE","featuredImage":null,
                 "variants":{"pageInfo":{"hasNextPage":true,"endCursor":"c2"},"edges":[
                   {"node":{"id":"gid://shopify/ProductVariant/V1","sku":"S1","title":"One","price":"10.00"}},
                   {"node":{"id":"gid://shopify/ProductVariant/V2","sku":"S2","title":"Two","price":"10.00"}}]}}""");
        });

        ShopifyGateway.ProductPage page = gateway.fetchProductsPage("shop.myshopify.com", "tok", null);

        assertThat(page.products()).hasSize(1);
        assertThat(page.products().get(0).variants()).extracting(ShopifyGateway.Variant::gid).containsExactly(
            "gid://shopify/ProductVariant/V1", "gid://shopify/ProductVariant/V2", "gid://shopify/ProductVariant/V3",
            "gid://shopify/ProductVariant/V4", "gid://shopify/ProductVariant/V5");
        assertThat(bodies).as("one products page + two variant follow-ups").hasSize(3);
        assertThat(bodies.get(1).path("variables").path("id").asText()).isEqualTo("gid://shopify/Product/9");
        assertThat(bodies.get(1).path("variables").path("cursor").asText()).isEqualTo("c2");
        assertThat(bodies.get(2).path("variables").path("cursor").asText()).isEqualTo("c3");
    }

    @Test
    void p3_everyStatusStoredLowercase_unlistedIncluded_noFollowUpWhenVariantsComplete() throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        String empty = "\"variants\":{\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null},\"edges\":[]}";
        ShopifyHttpGateway gateway = gateway(bodies, body -> page(
            "{\"id\":\"gid://shopify/Product/1\",\"title\":\"A\",\"status\":\"ACTIVE\"," + empty + "}",
            "{\"id\":\"gid://shopify/Product/2\",\"title\":\"D\",\"status\":\"DRAFT\"," + empty + "}",
            "{\"id\":\"gid://shopify/Product/3\",\"title\":\"R\",\"status\":\"ARCHIVED\"," + empty + "}",
            "{\"id\":\"gid://shopify/Product/4\",\"title\":\"U\",\"status\":\"UNLISTED\"," + empty + "}"));

        ShopifyGateway.ProductPage page = gateway.fetchProductsPage("shop.myshopify.com", "tok", null);

        assertThat(page.products()).extracting(ShopifyGateway.Product::status)
            .containsExactly("active", "draft", "archived", "unlisted");
        assertThat(bodies).hasSize(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ShopifyHttpGateway gateway(List<JsonNode> bodies, Function<JsonNode, String> responder) {
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            JsonNode parsed = mapper.readTree(new String(body, StandardCharsets.UTF_8));
            bodies.add(parsed);
            MockClientHttpResponse response = new MockClientHttpResponse(
                responder.apply(parsed).getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        };
        return new ShopifyHttpGateway(RestClient.builder().requestInterceptor(interceptor), mapper,
            "2026-04", "test-client-id", "test-client-secret");
    }

    private static String page(String... productNodes) {
        StringBuilder edges = new StringBuilder();
        for (String n : productNodes) {
            if (!edges.isEmpty()) edges.append(',');
            edges.append("{\"node\":").append(n).append('}');
        }
        return "{\"data\":{\"products\":{\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null},\"edges\":[" + edges + "]}}}";
    }

    private static String variantPage(boolean hasNext, String endCursor, String... ids) {
        StringBuilder edges = new StringBuilder();
        for (String id : ids) {
            if (!edges.isEmpty()) edges.append(',');
            edges.append("{\"node\":{\"id\":\"gid://shopify/ProductVariant/").append(id)
                 .append("\",\"sku\":\"S-").append(id).append("\",\"title\":\"").append(id).append("\",\"price\":\"10.00\"}}");
        }
        return "{\"data\":{\"product\":{\"variants\":{\"pageInfo\":{\"hasNextPage\":" + hasNext + ",\"endCursor\":"
            + (endCursor == null ? "null" : "\"" + endCursor + "\"") + "},\"edges\":[" + edges + "]}}}}";
    }
}
