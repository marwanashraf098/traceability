package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.MarketShippingCoverage.Result;
import com.traceability.integrations.shopify.MarketShippingCoverage.Verdict;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * L1b-2 (approved by Marawan 2026-11-16) — MarketShippingCoverage, THE market-driven coverage rule, on the LIVE answers
 * of a market-driven store (testl1location, read once read-only 2026-11-16, sanitised:
 * src/test/resources/shopify/market-shipping). Location 1004 = Traced Main Warehouse, 1001 = Shop location,
 * 1002 = My Custom Location, 1003 = a fulfillment-service location. Market 2002 United States (primary: the shop's
 * country), 2001 Canada.
 *
 *   c1  live: primary covered ("L1 test - all locations" lists every location explicitly) + Canada's rate with an EMPTY
 *       list → COVERED, "Not added for: Canada" (info, not a blocker)
 *   c2  live minus the two L1 test rates (Standard / Express are Shop-location-only) → NOT_COVERED for both markets
 *   c3  primary uncovered + another market covered → NOT_COVERED (never Ready on another market's rate)
 *   c4  an empty includedLocations covers nobody; a rate naming Traced Main only lists 1004 → COVERED by it
 *   c5  match by location ID, never by name — a rate NAMED "Traced Main Warehouse" without 1004 covers nothing
 *   c6  a rate limited to specific products (includedCollections) still counts
 *   c7  a carrier-calculated rate (no name) → COVERED, covered_by is the market alone
 *   c8  no verdict, never "not covered", from a partial read: rate list / location list / market list cut short
 *   c9  no verdict when the primary market can't be identified: country in no market, in two, unknown country, a
 *       country list cut short
 *   c10 a market inheriting its settings (shipping null — doc-based, the live store has no parent) → no verdict; shipping
 *       off in the primary → no verdict
 *   c11 cross-checks on the live fixtures: every rate's includedLocations == the union of its rate groups'
 *       originLocations (the rate-group query is a test cross-check only); the country rule picks the market Shopify's
 *       deprecated Market.primary marks
 *   w1  the gateway: one POST at 2026-07 with the coverage query (no mutation), the live answer → COVERED
 *   w2  ACCESS_DENIED / HTTP 403 → NO_ACCESS; 5xx / other GraphQL errors → empty (nothing recorded); paging follows
 *       endCursor; disabled → empty, no request
 */
class MarketShippingCoverageTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String TRACED = "gid://shopify/Location/1004";
    static final String URL = "https://m.myshopify.com/admin/api/2026-07/graphql.json";

    static JsonNode fixture(String name) {
        try (InputStream in = MarketShippingCoverageTest.class.getResourceAsStream("/shopify/market-shipping/" + name)) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A fresh, mutable copy of the live coverage answer's data. */
    static ObjectNode live() {
        return (ObjectNode) fixture("coverage-live.json").path("data").deepCopy();
    }

    static Result eval(ObjectNode data) {
        List<JsonNode> markets = new ArrayList<>();
        data.path("markets").path("nodes").forEach(markets::add);
        boolean complete = !data.path("markets").path("pageInfo").path("hasNextPage").asBoolean(false);
        return MarketShippingCoverage.evaluate(
            data.path("shop").path("shopAddress").path("countryCodeV2").asText(null), markets, complete, TRACED);
    }

    static ObjectNode market(ObjectNode data, String name) {
        for (JsonNode m : data.path("markets").path("nodes")) if (name.equals(m.path("name").asText())) return (ObjectNode) m;
        throw new IllegalArgumentException(name);
    }

    static ArrayNode options(ObjectNode data, String market) {
        return (ArrayNode) market(data, market).path("delivery").path("shipping").path("optionDefinitions").path("nodes");
    }

    static ObjectNode option(ObjectNode data, String market, String rate) {
        for (JsonNode o : options(data, market)) if (rate.equals(o.path("name").asText())) return (ObjectNode) o;
        throw new IllegalArgumentException(rate);
    }

    static void dropL1Rates(ObjectNode data) {
        ArrayNode us = options(data, "United States");
        for (int i = us.size() - 1; i >= 0; i--) if (us.get(i).path("name").asText().startsWith("L1 test")) us.remove(i);
    }

    static void setLocations(ObjectNode option, String... ids) {
        ArrayNode nodes = JSON.createArrayNode();
        for (String id : ids) nodes.addObject().put("id", id);
        ((ObjectNode) option.path("includedLocations")).set("nodes", nodes);
    }

    // ── the rule ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void c1_live_primaryCovered_otherMarketMissing_isInfo() {
        Result r = eval(live());
        assertThat(r.verdict()).isEqualTo(Verdict.COVERED);
        assertThat(r.coveredBy()).isEqualTo("United States — L1 test - all locations");
        assertThat(r.missing()).containsExactly("Canada");
    }

    @Test
    void c2_live_withoutTheTestRates_notCovered() {
        ObjectNode d = live();
        dropL1Rates(d);
        Result r = eval(d);
        assertThat(r.verdict()).isEqualTo(Verdict.NOT_COVERED);
        assertThat(r.coveredBy()).isNull();
        assertThat(r.missing()).containsExactly("United States", "Canada");
    }

    @Test
    void c3_primaryUncovered_otherCovered_isNotCovered() {
        ObjectNode d = live();
        dropL1Rates(d);
        setLocations(option(d, "Canada", "International Shipping"), TRACED);
        Result r = eval(d);
        assertThat(r.verdict()).as("coverage only in another market is not enough").isEqualTo(Verdict.NOT_COVERED);
        assertThat(r.missing()).containsExactly("United States");
    }

    @Test
    void c4_emptyListCoversNobody_tracedOnlyRateCovers() {
        ObjectNode d = live();
        ArrayNode us = options(d, "United States");
        for (int i = us.size() - 1; i >= 0; i--) if (!us.get(i).path("name").asText().equals("L1 test - Traced Main only")) us.remove(i);
        Result r = eval(d);
        assertThat(r.verdict()).isEqualTo(Verdict.COVERED);
        assertThat(r.coveredBy()).isEqualTo("United States — L1 test - Traced Main only");

        setLocations(option(d, "United States", "L1 test - Traced Main only"));     // empty list
        assertThat(eval(d).verdict()).isEqualTo(Verdict.NOT_COVERED);
    }

    @Test
    void c5_matchesByIdNeverByName() {
        ObjectNode d = live();
        dropL1Rates(d);
        option(d, "United States", "Standard").put("name", "Traced Main Warehouse");
        assertThat(eval(d).verdict()).isEqualTo(Verdict.NOT_COVERED);
    }

    @Test
    void c6_productLimitedRate_stillCounts() {
        ObjectNode d = live();
        dropL1Rates(d);
        ObjectNode express = option(d, "United States", "Express");
        setLocations(express, "gid://shopify/Location/1001", TRACED);
        express.putObject("includedCollections").putArray("nodes").addObject().put("id", "gid://shopify/Collection/9");
        Result r = eval(d);
        assertThat(r.verdict()).isEqualTo(Verdict.COVERED);
        assertThat(r.coveredBy()).isEqualTo("United States — Express");
    }

    @Test
    void c7_carrierCalculatedRateWithoutName_counts() {
        ObjectNode d = live();
        dropL1Rates(d);
        ObjectNode carrier = options(d, "United States").addObject();
        carrier.put("__typename", "DeliveryCarrierCalculatedOptionDefinition").put("id", "gid://shopify/DeliveryCarrierCalculatedOptionDefinition/9")
            .put("isActive", true);
        ObjectNode locs = carrier.putObject("includedLocations");
        locs.putObject("pageInfo").put("hasNextPage", false);
        locs.putArray("nodes").addObject().put("id", TRACED);
        Result r = eval(d);
        assertThat(r.verdict()).isEqualTo(Verdict.COVERED);
        assertThat(r.coveredBy()).isEqualTo("United States");
    }

    @Test
    void c8_partialRead_isNoVerdict_neverNotCovered() {
        ObjectNode d = live();
        dropL1Rates(d);
        ((ObjectNode) market(d, "United States").path("delivery").path("shipping").path("optionDefinitions").path("pageInfo"))
            .put("hasNextPage", true);
        assertThat(eval(d).verdict()).as("rate list cut short").isEqualTo(Verdict.NO_VERDICT);

        d = live();
        dropL1Rates(d);
        ((ObjectNode) option(d, "United States", "Standard").path("includedLocations").path("pageInfo")).put("hasNextPage", true);
        assertThat(eval(d).verdict()).as("location list cut short").isEqualTo(Verdict.NO_VERDICT);

        d = live();
        dropL1Rates(d);
        option(d, "United States", "Standard").remove("includedLocations");
        assertThat(eval(d).verdict()).as("no location list at all").isEqualTo(Verdict.NO_VERDICT);

        d = live();
        ((ObjectNode) d.path("markets").path("pageInfo")).put("hasNextPage", true);
        assertThat(eval(d).verdict()).as("market list cut short").isEqualTo(Verdict.NO_VERDICT);

        // A location list cut short still decides when Traced Main is already on the page read.
        d = live();
        ((ObjectNode) option(d, "United States", "L1 test - all locations").path("includedLocations").path("pageInfo"))
            .put("hasNextPage", true);
        assertThat(eval(d).verdict()).isEqualTo(Verdict.COVERED);
    }

    @Test
    void c9_primaryNotIdentified_isNoVerdict() {
        ObjectNode d = live();
        ((ObjectNode) d.path("shop").path("shopAddress")).put("countryCodeV2", "EG");
        assertThat(eval(d).reason()).isEqualTo("primary_market_not_found");

        d = live();
        ((ArrayNode) market(d, "Canada").path("conditions").path("regionsCondition").path("regions").path("nodes"))
            .addObject().put("code", "US");
        assertThat(eval(d).reason()).isEqualTo("primary_market_ambiguous");

        d = live();
        ((ObjectNode) d.path("shop").path("shopAddress")).putNull("countryCodeV2");
        assertThat(eval(d).verdict()).isEqualTo(Verdict.NO_VERDICT);

        d = live();
        ((ObjectNode) d.path("shop").path("shopAddress")).put("countryCodeV2", "EG");
        ((ObjectNode) market(d, "Canada").path("conditions").path("regionsCondition").path("regions").path("pageInfo"))
            .put("hasNextPage", true);
        assertThat(eval(d).reason()).isEqualTo("regions_truncated");
    }

    @Test
    void c10_inheritedOrShippingOff_isNoVerdict() {
        ObjectNode d = live();
        ((ObjectNode) market(d, "United States").path("delivery")).putNull("shipping");   // docs: null = inherits
        assertThat(eval(d).verdict()).isEqualTo(Verdict.NO_VERDICT);

        d = live();
        ((ObjectNode) market(d, "United States").path("delivery").path("shipping")).put("isEnabled", false);
        assertThat(eval(d).verdict()).isEqualTo(Verdict.NO_VERDICT);

        // An OTHER market that inherits is simply not listed as missing.
        d = live();
        ((ObjectNode) market(d, "Canada").path("delivery")).putNull("shipping");
        Result r = eval(d);
        assertThat(r.verdict()).isEqualTo(Verdict.COVERED);
        assertThat(r.missing()).isEmpty();
    }

    @Test
    void c11_liveCrossChecks() {
        // Every rate's includedLocations == the union of its rate groups' originLocations (US and Canada).
        JsonNode markets = fixture("markets-live.json").path("data").path("markets").path("nodes");
        int checked = 0;
        for (String file : List.of("rategroups-us-live.json", "rategroups-ca-live.json")) {
            JsonNode m = fixture(file).path("data").path("market");
            for (JsonNode o : m.path("delivery").path("shipping").path("optionDefinitions").path("nodes")) {
                Set<String> fromGroups = new TreeSet<>();
                for (JsonNode g : o.path("rateGroups").path("nodes")) {
                    g.path("conditions").path("originLocations").path("nodes").forEach(l -> fromGroups.add(l.path("id").asText()));
                    assertThat(g.path("conditions").path("originLocationsCount").path("count").asInt())
                        .isEqualTo(g.path("conditions").path("originLocations").path("nodes").size());
                }
                Set<String> included = new TreeSet<>();
                for (JsonNode mk : markets) for (JsonNode o2 : mk.path("delivery").path("shipping").path("optionDefinitions").path("nodes")) {
                    if (o2.path("id").asText().equals(o.path("id").asText())) {
                        o2.path("includedLocations").path("nodes").forEach(l -> included.add(l.path("id").asText()));
                    }
                }
                assertThat(included).as(o.path("id").asText()).isEqualTo(fromGroups);
                checked++;
            }
        }
        assertThat(checked).isEqualTo(5);

        // The country rule picks the market Shopify's (deprecated) Market.primary marks.
        JsonNode primary = fixture("primary-live.json").path("data");
        String country = primary.path("shop").path("shopAddress").path("countryCodeV2").asText();
        for (JsonNode m : primary.path("markets").path("nodes")) {
            boolean listsCountry = false;
            for (JsonNode r : m.path("conditions").path("regionsCondition").path("regions").path("nodes")) {
                if (country.equals(r.path("code").asText())) listsCountry = true;
            }
            assertThat(listsCountry).as(m.path("name").asText()).isEqualTo(m.path("primary").asBoolean());
        }
    }

    // ── the gateway (wire) ────────────────────────────────────────────────────────────────────────────────────────

    private record Wired(ShopifyShippingModelGateway gw, MockRestServiceServer server) {}

    private static Wired wired(boolean enabled) {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        return new Wired(new ShopifyShippingModelGateway(b, JSON, enabled), server);
    }

    @Test
    void w1_onePost_onItsOwnVersion_liveAnswer_covered() throws IOException {
        Wired w = wired(true);
        w.server().expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Shopify-Access-Token", "tok"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("TracedMarketCoverage")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("mutation"))))
            .andRespond(withSuccess(JSON.writeValueAsString(fixture("coverage-live.json")), MediaType.APPLICATION_JSON));
        Optional<Result> r = w.gw().readMarketCoverage("m.myshopify.com", "tok", TRACED);
        w.server().verify();
        assertThat(r).isPresent();
        assertThat(r.get().verdict()).isEqualTo(Verdict.COVERED);
        assertThat(r.get().missing()).containsExactly("Canada");
    }

    @Test
    void w2_accessDenied_failures_paging_disabled() throws IOException {
        Wired w = wired(true);
        w.server().expect(requestTo(URL)).andRespond(withSuccess(
            "{\"errors\":[{\"message\":\"Access denied\",\"extensions\":{\"code\":\"ACCESS_DENIED\"}}]}", MediaType.APPLICATION_JSON));
        assertThat(w.gw().readMarketCoverage("m.myshopify.com", "tok", TRACED)).get()
            .extracting(Result::verdict).isEqualTo(Verdict.NO_ACCESS);

        w = wired(true);
        w.server().expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThat(w.gw().readMarketCoverage("m.myshopify.com", "tok", TRACED)).get()
            .extracting(Result::verdict).isEqualTo(Verdict.NO_ACCESS);

        w = wired(true);
        w.server().expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(w.gw().readMarketCoverage("m.myshopify.com", "tok", TRACED)).as("nothing recorded").isEmpty();

        w = wired(true);
        w.server().expect(requestTo(URL)).andRespond(withSuccess(
            "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}]}", MediaType.APPLICATION_JSON));
        assertThat(w.gw().readMarketCoverage("m.myshopify.com", "tok", TRACED)).isEmpty();

        // Paging: page 1 = Canada only (more to come), page 2 = United States → the same COVERED as one page.
        ObjectNode all = live();
        ObjectNode page1 = all.deepCopy();
        ArrayNode p1 = (ArrayNode) page1.path("markets").path("nodes");
        p1.remove(1);
        ((ObjectNode) page1.path("markets").path("pageInfo")).put("hasNextPage", true).put("endCursor", "c1");
        ObjectNode page2 = all.deepCopy();
        ((ArrayNode) page2.path("markets").path("nodes")).remove(0);
        w = wired(true);
        w.server().expect(requestTo(URL)).andExpect(content().string(org.hamcrest.Matchers.containsString("\"after\":null")))
            .andRespond(withSuccess(JSON.writeValueAsString(JSON.createObjectNode().set("data", page1)), MediaType.APPLICATION_JSON));
        w.server().expect(requestTo(URL)).andExpect(content().string(org.hamcrest.Matchers.containsString("\"after\":\"c1\"")))
            .andRespond(withSuccess(JSON.writeValueAsString(JSON.createObjectNode().set("data", page2)), MediaType.APPLICATION_JSON));
        Optional<Result> paged = w.gw().readMarketCoverage("m.myshopify.com", "tok", TRACED);
        w.server().verify();
        assertThat(paged).get().extracting(Result::verdict).isEqualTo(Verdict.COVERED);
        assertThat(paged.get().missing()).containsExactly("Canada");

        w = wired(false);
        assertThat(w.gw().readMarketCoverage("m.myshopify.com", "tok", TRACED)).isEmpty();
        w.server().verify();                                                 // no request expected, none sent
    }
}
