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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * fix/embedded-expiring-token — the exact /admin/oauth/access_token requests, on the wire.
 *
 *   t1 session-token exchange (embedded signup, pending link, embedded open) asks for an EXPIRING
 *      offline token: expiring=1, requested_token_type offline — the flag whose absence made Shopify
 *      issue a non-expiring token in prod (test-oaozdwro, 2026-10-08).
 *   t2 a response without a refresh token / expiry is refused (no 3600 default any more).
 *   t3 the OAuth code exchange still sends expiring=1 and is held to the same rule.
 */
class ShopifyTokenRequestTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String SHOP = "tokens.myshopify.com";

    private final List<JsonNode> sent = new ArrayList<>();

    private ShopifyHttpGateway gateway(String responseJson) {
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            assertThat(request.getURI().toString()).isEqualTo("https://" + SHOP + "/admin/oauth/access_token");
            sent.add(M.readTree(new String(body, StandardCharsets.UTF_8)));
            MockClientHttpResponse r = new MockClientHttpResponse(responseJson.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            r.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return r;
        };
        return new ShopifyHttpGateway(RestClient.builder(), RestClient.builder().requestInterceptor(interceptor), M,
            "2026-04", "cid", "csecret");
    }

    private static final String EXPIRING = "{\"access_token\":\"shpat_a\",\"expires_in\":3599,\"refresh_token\":\"shprt_r\"," +
        "\"refresh_token_expires_in\":7775999,\"scope\":\"read_orders\"}";
    private static final String NON_EXPIRING = "{\"access_token\":\"shpat_forever\",\"scope\":\"read_orders\"}";

    @Test
    void t1_sessionTokenExchange_requestsAnExpiringOfflineToken() {
        ShopifyGateway.TokenResponse t = gateway(EXPIRING).exchangeSessionToken(SHOP, "id.token.jwt");
        JsonNode body = sent.get(0);
        assertThat(body.path("grant_type").asText()).isEqualTo("urn:ietf:params:oauth:grant-type:token-exchange");
        assertThat(body.path("subject_token").asText()).isEqualTo("id.token.jwt");
        assertThat(body.path("subject_token_type").asText()).isEqualTo("urn:ietf:params:oauth:token-type:id_token");
        assertThat(body.path("requested_token_type").asText()).isEqualTo("urn:shopify:params:oauth:token-type:offline-access-token");
        assertThat(body.path("expiring").asText()).isEqualTo("1");
        assertThat(t).isEqualTo(new ShopifyGateway.TokenResponse("shpat_a", "shprt_r", 3599, 7775999, "read_orders"));
    }

    @Test
    void t2_aResponseWithoutRefreshTokenOrExpiry_isRefused() {
        assertThatThrownBy(() -> gateway(NON_EXPIRING).exchangeSessionToken(SHOP, "id.token.jwt"))
            .isInstanceOf(ShopifyNonExpiringTokenException.class);
        String noExpiry = "{\"access_token\":\"shpat_a\",\"refresh_token\":\"shprt_r\"}";
        assertThatThrownBy(() -> gateway(noExpiry).exchangeSessionToken(SHOP, "id.token.jwt"))
            .isInstanceOf(ShopifyNonExpiringTokenException.class);
        String nullRefresh = "{\"access_token\":\"shpat_a\",\"expires_in\":3599,\"refresh_token\":null,\"refresh_token_expires_in\":7775999}";
        assertThatThrownBy(() -> gateway(nullRefresh).exchangeSessionToken(SHOP, "id.token.jwt"))
            .isInstanceOf(ShopifyNonExpiringTokenException.class);
    }

    @Test
    void t3_codeExchange_sendsExpiring_andIsHeldToTheSameRule() {
        ShopifyGateway.TokenResponse t = gateway(EXPIRING).exchangeCode(SHOP, "auth-code");
        assertThat(sent.get(0).path("expiring").asText()).isEqualTo("1");
        assertThat(sent.get(0).path("code").asText()).isEqualTo("auth-code");
        assertThat(t.refreshToken()).isEqualTo("shprt_r");
        assertThatThrownBy(() -> gateway(NON_EXPIRING).exchangeCode(SHOP, "auth-code"))
            .isInstanceOf(ShopifyNonExpiringTokenException.class);
    }
}
