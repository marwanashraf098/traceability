package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * L1b-1 — ShopifyShippingModelGateway on the wire: ONE POST to /admin/api/2026-07/graphql.json carrying only the
 * read-only shop.features query; true / false → MARKET_DRIVEN / LEGACY; anything else → unknown (never throws);
 * disabled → LEGACY with no request.
 */
class ShippingModelGatewayWireTest {

    static final String URL = "https://w.myshopify.com/admin/api/2026-07/graphql.json";

    private record Wired(ShopifyShippingModelGateway gw, MockRestServiceServer server) {}

    private static Wired wired(boolean enabled) {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        return new Wired(new ShopifyShippingModelGateway(b, new ObjectMapper(), enabled), server);
    }

    private static Optional<ShopifyShippingModelGateway.Model> answer(String body) {
        Wired w = wired(true);
        w.server().expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Shopify-Access-Token", "tok"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("marketDrivenShipping")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("mutation"))))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        Optional<ShopifyShippingModelGateway.Model> m = w.gw().read("w.myshopify.com", "tok");
        w.server().verify();
        return m;
    }

    @Test
    void w1_true_isMarketDriven() {
        assertThat(answer("{\"data\":{\"shop\":{\"features\":{\"marketDrivenShipping\":true}}}}"))
            .contains(ShopifyShippingModelGateway.Model.MARKET_DRIVEN);
    }

    @Test
    void w2_false_isLegacy() {
        assertThat(answer("{\"data\":{\"shop\":{\"features\":{\"marketDrivenShipping\":false}}}}"))
            .contains(ShopifyShippingModelGateway.Model.LEGACY);
    }

    @Test
    void w3_missingField_errors_areUnknown() {
        assertThat(answer("{\"data\":{\"shop\":{\"features\":{}}}}")).isEmpty();
        assertThat(answer("{\"errors\":[{\"message\":\"Field 'marketDrivenShipping' doesn't exist\",\"extensions\":{\"code\":\"undefinedField\"}}]}")).isEmpty();
        assertThat(answer("not json")).isEmpty();
    }

    @Test
    void w4_httpError_isUnknown_neverThrows() {
        Wired w = wired(true);
        w.server().expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(w.gw().read("w.myshopify.com", "tok")).isEmpty();
        w.server().verify();
    }

    @Test
    void w5_disabled_isLegacy_noRequest() {
        Wired w = wired(false);
        assertThat(w.gw().read("w.myshopify.com", "tok")).contains(ShopifyShippingModelGateway.Model.LEGACY);
        w.server().verify();                                                 // no request expected, none sent
    }
}
