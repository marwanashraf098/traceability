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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Portal on-demand lookup diagnostics on the REAL wire: the search reports which PII tiers answered
 * ACCESS_DENIED, and a GraphQL error surfaces its extensions.code values — never the message.
 */
class ShopifyPortalLookupDiagnosticsWireTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void d1_everyTierDenied_reportsTheDeniedTiers() {
        ShopifyHttpGateway gw = gateway(b -> {
            if (isScopeQuery(b)) return SCOPES_WITH_CUSTOMERS;
            return b.path("query").asText().contains("shippingAddress") ? DENIED : ORDER;
        });

        ShopifyGateway.PortalOrderSearch s = gw.findOrdersByName("shop.myshopify.com", "tok", "1047");

        assertThat(s.customerPhoneReadable()).isFalse();
        assertThat(s.deniedTiers()).containsExactly("FULL", "ADDRESSES");
    }

    @Test
    void d2_noDenial_reportsNoDeniedTiers() {
        ShopifyHttpGateway gw = gateway(b -> isScopeQuery(b) ? SCOPES_WITH_CUSTOMERS : ORDER);

        ShopifyGateway.PortalOrderSearch s = gw.findOrdersByName("shop.myshopify.com", "tok", "1047");

        assertThat(s.customerPhoneReadable()).isTrue();
        assertThat(s.deniedTiers()).isEmpty();
    }

    @Test
    void d3_graphQLError_carriesCodesOnly_neverTheMessage() {
        ShopifyHttpGateway gw = gateway(b -> isScopeQuery(b) ? SCOPES_WITH_CUSTOMERS : """
            {"errors":[{"message":"Field 'secretField' doesn't exist on type 'Order'",
                        "extensions":{"code":"undefinedField"}},
                       {"message":"no code here"}],"data":null}""");

        assertThatThrownBy(() -> gw.findOrdersByName("shop.myshopify.com", "tok", "1047"))
            .isInstanceOf(ShopifyGraphQLErrorException.class)
            .satisfies(e -> assertThat(((ShopifyGraphQLErrorException) e).codes()).containsExactly("undefinedField", "NO_CODE"))
            .hasMessageNotContaining("secretField");
    }

    private static final String SCOPES_WITH_CUSTOMERS =
        "{\"data\":{\"currentAppInstallation\":{\"accessScopes\":[{\"handle\":\"read_orders\"},{\"handle\":\"read_customers\"}]}}}";

    private static final String ORDER = """
        {"data":{"orders":{"edges":[{"node":{"id":"gid://shopify/Order/1047","name":"#1047",
          "createdAt":"2026-07-02T10:00:00Z","lineItems":{"edges":[]},"fulfillments":[]}}]}}}""";

    private static final String DENIED = """
        {"errors":[{"message":"Access denied for shippingAddress field.","extensions":{"code":"ACCESS_DENIED"}}],"data":null}""";

    private boolean isScopeQuery(JsonNode body) {
        return body.path("query").asText().contains("currentAppInstallation");
    }

    private ShopifyHttpGateway gateway(Function<JsonNode, String> responder) {
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            JsonNode parsed = mapper.readTree(new String(body, StandardCharsets.UTF_8));
            MockClientHttpResponse response = new MockClientHttpResponse(
                responder.apply(parsed).getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        };
        return new ShopifyHttpGateway(RestClient.builder().requestInterceptor(interceptor), mapper,
            "2026-04", "test-client-id", "test-client-secret");
    }
}
