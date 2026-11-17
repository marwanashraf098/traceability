package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.MarketShippingCoverage.Result;
import com.traceability.integrations.shopify.MarketShippingCoverage.Verdict;
import com.traceability.inventory.TracedMarketCheck;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static com.traceability.integrations.shopify.MarketShippingCoverageTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * L1b-2 fix (2026-11-17) — the live test on testl1location after its country moved to Egypt
 * (src/test/resources/shopify/market-shipping/coverage-egypt-uncovered.json): primary market Egypt, shipping on, one
 * active rate "Bosta Egypt" → Shop location only; Traced Main in no active rate of any market; every market's parent is
 * "Store default" (its own shipping block — never read as inherited).
 *
 *   e1 the rule: NOT_COVERED, "Not added for: Egypt, Canada, United States" (primary first)
 *   e2 the same through the gateway (one POST, the fixture as Shopify's answer)
 *   e3 Traced Main ticked on "Bosta Egypt" → COVERED "Egypt — Bosta Egypt", the other two as information
 *   e4 TracedMarketCheck — the words under the instruction: a stored verdict as is; read_markets granted + no verdict →
 *      no_verdict; granted + never read (or a stale 'no_access' from before the grant) → not_checked; only without the
 *      scope → no_access
 */
class MarketShippingEgyptTest {

    static ObjectNode egypt() {
        return (ObjectNode) fixture("coverage-egypt-uncovered.json").path("data").deepCopy();
    }

    @Test
    void e1_primaryEgypt_readInFull_uncovered_isNotCovered() {
        Result r = eval(egypt());
        assertThat(r.verdict()).isEqualTo(Verdict.NOT_COVERED);
        assertThat(r.reason()).isEqualTo("not_covered");
        assertThat(r.missing()).containsExactly("Egypt", "Canada", "United States");
        assertThat(r.coveredBy()).isNull();
    }

    @Test
    void e2_throughTheGateway_notCovered() throws Exception {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo(URL)).andRespond(withSuccess(
            JSON.writeValueAsString(fixture("coverage-egypt-uncovered.json")), MediaType.APPLICATION_JSON));
        Optional<Result> r = new ShopifyShippingModelGateway(b, JSON, true).readMarketCoverage("m.myshopify.com", "tok", TRACED);
        server.verify();
        assertThat(r).get().extracting(Result::verdict).isEqualTo(Verdict.NOT_COVERED);
        assertThat(r.get().missing()).containsExactly("Egypt", "Canada", "United States");
    }

    @Test
    void e3_tickedOnBostaEgypt_covered() {
        ObjectNode data = egypt();
        ((com.fasterxml.jackson.databind.node.ArrayNode) option(data, "Egypt", "Bosta Egypt")
            .path("includedLocations").path("nodes")).addObject().put("id", TRACED);
        Result r = eval(data);
        assertThat(r.verdict()).isEqualTo(Verdict.COVERED);
        assertThat(r.coveredBy()).isEqualTo("Egypt — Bosta Egypt");
        assertThat(r.missing()).containsExactly("Canada", "United States");
    }

    @Test
    void e4_marketCheck_words() {
        String granted = "read_products,write_locations,read_markets";
        assertThat(TracedMarketCheck.of("not_covered", "t", granted)).isEqualTo("not_covered");
        assertThat(TracedMarketCheck.of("covered", "t", granted)).isEqualTo("covered");
        assertThat(TracedMarketCheck.of(null, "t", granted)).isEqualTo("no_verdict");
        assertThat(TracedMarketCheck.of(null, null, granted)).isEqualTo("not_checked");
        assertThat(TracedMarketCheck.of("no_access", "t", granted)).isEqualTo("not_checked");
        assertThat(TracedMarketCheck.of(null, "t", "write_markets")).isEqualTo("no_verdict");
        assertThat(TracedMarketCheck.of(null, "t", "read_products")).isEqualTo("no_access");
        assertThat(TracedMarketCheck.of("no_access", "t", null)).isEqualTo("no_access");
        assertThat(TracedMarketCheck.of("not_covered", "t", "read_products")).isEqualTo("not_covered");
    }
}
