package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.portal.ShopifyReturnSyncRules;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5b (2026-11-08) — an exchange's Shopify return, on the wire and in its note:
 * <ul>
 *   <li>w1 the returnCreate body of an exchange carries the old item only: NO exchangeLineItems, no fee, no
 *       notifyCustomer — exactly the refund shape; the note is "Traced RR-… · exchanged for … · collect … · warning"</li>
 *   <li>w2 a note over 255 characters is cut by code point (an emoji at the edge is never split)</li>
 *   <li>n1 the note: marker always whole, collect never cut, the warning dropped first, then the product shortened with
 *       "…"; own courier → "via courier"; nothing to collect → no collect part; never over 255 code points</li>
 *   <li>n2 money part: a price difference of 0 writes none (never "collect EGP 0"); customer owed → "refund EGP X to
 *       customer"; a negative or zero amount is never written</li>
 * </ul>
 */
class ExchangeReturnCreateWireTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final List<JsonNode> bodies = new ArrayList<>();

    private ShopifyReturnsWriteGatewayImpl gateway() {
        ClientHttpRequestInterceptor i = (request, body, execution) -> {
            bodies.add(M.readTree(new String(body, StandardCharsets.UTF_8)));
            MockClientHttpResponse resp = new MockClientHttpResponse(("{\"data\":{\"returnCreate\":{\"return\":{\"id\":" +
                "\"gid://shopify/Return/1\",\"name\":\"#1016-R1\",\"status\":\"OPEN\"},\"userErrors\":[]}}}")
                .getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return resp;
        };
        ShopifyHttpGateway http = new ShopifyHttpGateway(RestClient.builder().requestInterceptor(i), M, "2026-04", "id", "secret");
        return new ShopifyReturnsWriteGatewayImpl(http, M);
    }

    @Test
    void w1_exchangeReturn_oldItemOnly_noExchangeLineItems() {
        String note = ShopifyReturnSyncRules.exchangeNote("RR-ZMCAUG", "Linen Shirt / White / L", new BigDecimal("150.00"), false);
        gateway().returnCreate("s.myshopify.com", "t", "gid://shopify/Order/1016", Instant.parse("2026-11-08T10:00:00Z"),
            List.of(new ShopifyReturnsWriteGateway.CreateLine("gid://shopify/FulfillmentLineItem/7", 1, note,
                "gid://shopify/ReturnReasonDefinition/42")));
        assertThat(bodies).hasSize(1);
        JsonNode input = bodies.get(0).path("variables").path("returnInput");
        assertThat(input.has("exchangeLineItems")).isFalse();
        assertThat(input.has("returnShippingFee")).isFalse();
        assertThat(input.has("notifyCustomer")).isFalse();
        assertThat(bodies.get(0).path("query").asText()).doesNotContain("exchangeLineItems");
        JsonNode line = input.path("returnLineItems").get(0);
        assertThat(line.path("returnReasonNote").asText()).isEqualTo(
            "Traced RR-ZMCAUG · exchanged for Linen Shirt / White / L · collect EGP 150 via Bosta · " +
            "Exchanged via Traced — don't refund in Shopify");
        assertThat(input.path("returnLineItems")).hasSize(1);
    }

    @Test
    void w2_longNote_cutByCodePoint_neverSplitsAnEmoji() {
        String note = "x".repeat(254) + "😀😀";   // 254 + 2 code points, 258 UTF-16 units
        gateway().returnCreate("s.myshopify.com", "t", "gid://shopify/Order/1", null,
            List.of(new ShopifyReturnsWriteGateway.CreateLine("gid://shopify/FulfillmentLineItem/7", 1, note)));
        String sent = bodies.get(0).path("variables").path("returnInput").path("returnLineItems").get(0)
            .path("returnReasonNote").asText();
        assertThat(sent.codePointCount(0, sent.length())).isEqualTo(255);
        assertThat(sent).endsWith("x😀");
    }

    @Test
    void n1_noteBuilder_priorities_andLimit() {
        assertThat(ShopifyReturnSyncRules.exchangeNote("RR-AAAAAA", "Tee / M", null, true))
            .isEqualTo("Traced RR-AAAAAA · exchanged for Tee / M · Exchanged via Traced — don't refund in Shopify");
        assertThat(ShopifyReturnSyncRules.exchangeNote("RR-AAAAAA", "Tee / M", new BigDecimal("75.50"), true))
            .isEqualTo("Traced RR-AAAAAA · exchanged for Tee / M · collect EGP 75.5 via courier · " +
                "Exchanged via Traced — don't refund in Shopify");
        assertThat(ShopifyReturnSyncRules.exchangeNote("RR-AAAAAA", "Tee", BigDecimal.ZERO, false))
            .doesNotContain("collect");

        // The warning goes first …
        String mid = "P".repeat(170);
        String a = ShopifyReturnSyncRules.exchangeNote("RR-AAAAAA", mid, new BigDecimal("20"), false);
        assertThat(a).isEqualTo("Traced RR-AAAAAA · exchanged for " + mid + " · collect EGP 20 via Bosta");
        // … then the product is shortened; the marker and the collect stay whole.
        String longName = "منتج طويل جدا 😀 ".repeat(40);
        String b = ShopifyReturnSyncRules.exchangeNote("RR-AAAAAA", longName, new BigDecimal("30000"), false);
        assertThat(b.codePointCount(0, b.length())).isLessThanOrEqualTo(ShopifyReturnSyncRules.NOTE_LIMIT);
        assertThat(b).startsWith("Traced RR-AAAAAA · exchanged for ").endsWith("… · collect EGP 30000 via Bosta")
            .doesNotContain("don't refund");
        assertThat(b.codePointCount(0, b.length())).isEqualTo(ShopifyReturnSyncRules.NOTE_LIMIT);
        for (int k = 0; k < b.length(); k++) {
            if (Character.isHighSurrogate(b.charAt(k))) assertThat(Character.isLowSurrogate(b.charAt(k + 1))).isTrue();
        }
    }

    @Test
    void n2_moneyPart_zeroDropped_owedRefundToCustomer_neverNegative() {
        String w = " · Exchanged via Traced — don't refund in Shopify";
        assertThat(ShopifyReturnSyncRules.exchangeNote("RR-AAAAAA", "Tee / M", BigDecimal.ZERO, BigDecimal.ZERO, false))
            .isEqualTo("Traced RR-AAAAAA · exchanged for Tee / M" + w);
        assertThat(ShopifyReturnSyncRules.exchangeNote("RR-AAAAAA", "Tee / M", new BigDecimal("0.00"), null, false))
            .doesNotContain("EGP");
        assertThat(ShopifyReturnSyncRules.exchangeNote("RR-AAAAAA", "Tee / M", BigDecimal.ZERO, new BigDecimal("120.00"), false))
            .isEqualTo("Traced RR-AAAAAA · exchanged for Tee / M · refund EGP 120 to customer" + w);
        assertThat(ShopifyReturnSyncRules.exchangeNote("RR-AAAAAA", "Tee / M", null, new BigDecimal("-40"), true))
            .isEqualTo("Traced RR-AAAAAA · exchanged for Tee / M" + w);
        assertThat(ShopifyReturnSyncRules.exchangeNote("RR-AAAAAA", "Tee / M", new BigDecimal("-15"), null, true))
            .doesNotContain("EGP").doesNotContain("-15");
    }
}
