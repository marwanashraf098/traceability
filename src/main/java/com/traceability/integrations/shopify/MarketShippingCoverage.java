package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * L1b-2 (approved by Marawan 2026-11-16) — THE rule for "does the store's shipping cover the Traced Main Warehouse?"
 * on a market-driven store, from {@link ShopifyShippingModelGateway#MARKET_COVERAGE_QUERY}'s answer (never re-derive
 * it). Pure: no Shopify call, no database.
 *
 * <p>Meanings confirmed on a live market-driven store (testl1location, 2026-11-16; fixtures under
 * src/test/resources/shopify/market-shipping):
 * <ul>
 *   <li>A rate with no location limit lists EVERY location explicitly (a fulfillment-service location included) —
 *       never an empty list. An EMPTY {@code includedLocations} = the rate ships from no location: it covers nobody.</li>
 *   <li>{@code includedLocations} equals the union of the rate groups' {@code originLocations} on every rate, so the
 *       rate-level list alone decides (the rate-group query is a test cross-check only).</li>
 *   <li>Locations match by ID only — never by name. A rate limited to specific products ({@code includedCollections})
 *       still counts: it ships from the location, for those products.</li>
 *   <li>Carrier-calculated rates have no {@code name}.</li>
 * </ul>
 *
 * <p>The PRIMARY market = the one active market whose region conditions list the shop's own country
 * ({@code shop.shopAddress.countryCodeV2}; {@code Market.primary} and {@code Shop.billingAddress} are deprecated in
 * 2026-07 — on the live store the country rule agreed with {@code Market.primary}). Ready needs a rate of the PRIMARY
 * market; coverage only in other markets is not enough. Other active markets whose rates miss Traced Main are reported
 * as information ({@link Result#missing}), never a blocker.
 *
 * <p>No verdict — never "not covered" from a partial read: the primary market can't be identified (none / several /
 * the market or region list cut short), a market inherits its settings from a parent ({@code shipping} null — no
 * parent field exists in 2026-07), its shipping is off, or a list ran past one page without Traced Main.
 */
public final class MarketShippingCoverage {

    private MarketShippingCoverage() {}

    public enum Verdict { COVERED, NOT_COVERED, NO_VERDICT, NO_ACCESS }

    /**
     * @param coveredBy "&lt;primary market&gt; — &lt;rate&gt;" (market only for a rate without a name); COVERED only
     * @param missing   names of active markets whose rates were read in full and don't list Traced Main — the other
     *                  markets when COVERED, every such market (the primary first) when NOT_COVERED; else empty
     * @param reason    a short code for logs (never shop data)
     */
    public record Result(Verdict verdict, String coveredBy, List<String> missing, String reason) {
        static Result noVerdict(String reason) { return new Result(Verdict.NO_VERDICT, null, List.of(), reason); }
        public static Result noAccess(String reason) { return new Result(Verdict.NO_ACCESS, null, List.of(), reason); }
    }

    private enum Market { COVERED, NOT_COVERED, UNKNOWN, SHIPPING_OFF }

    private record Read(Market state, String rateName) {}

    /**
     * @param shopCountry     shop.shopAddress.countryCodeV2 (null = unknown)
     * @param markets         every market node read (the query asks for ACTIVE markets only)
     * @param marketsComplete false when the market list was cut short
     * @param locationGid     the Traced Main Warehouse's GID — the ONLY location matched
     */
    public static Result evaluate(String shopCountry, List<JsonNode> markets, boolean marketsComplete, String locationGid) {
        if (locationGid == null || locationGid.isBlank()) return Result.noVerdict("no_location");
        if (shopCountry == null || shopCountry.isBlank()) return Result.noVerdict("shop_country_unknown");
        if (!marketsComplete) return Result.noVerdict("markets_truncated");

        List<JsonNode> active = new ArrayList<>();
        for (JsonNode m : markets) if ("ACTIVE".equals(m.path("status").asText())) active.add(m);

        List<JsonNode> primary = new ArrayList<>();
        for (JsonNode m : active) {
            JsonNode regions = m.path("conditions").path("regionsCondition").path("regions");
            boolean listed = false;
            for (JsonNode r : regions.path("nodes")) {
                if (shopCountry.equalsIgnoreCase(r.path("code").asText(null))) listed = true;
            }
            if (listed) primary.add(m);
            else if (regions.path("pageInfo").path("hasNextPage").asBoolean(false)) {
                return Result.noVerdict("regions_truncated");
            }
        }
        if (primary.isEmpty()) return Result.noVerdict("primary_market_not_found");
        if (primary.size() > 1) return Result.noVerdict("primary_market_ambiguous");
        JsonNode main = primary.get(0);

        Read mainRead = read(main, locationGid);
        List<String> missing = new ArrayList<>();
        if (mainRead.state() == Market.NOT_COVERED) missing.add(name(main));
        for (JsonNode m : active) {
            if (m == main) continue;
            if (read(m, locationGid).state() == Market.NOT_COVERED) missing.add(name(m));
        }

        return switch (mainRead.state()) {
            case COVERED -> new Result(Verdict.COVERED,
                mainRead.rateName() == null ? name(main) : name(main) + " — " + mainRead.rateName(),
                List.copyOf(missing), "covered");
            case NOT_COVERED -> new Result(Verdict.NOT_COVERED, null, List.copyOf(missing), "not_covered");
            case SHIPPING_OFF -> Result.noVerdict("primary_shipping_off");
            case UNKNOWN -> Result.noVerdict("primary_not_read_in_full");
        };
    }

    /** One market's answer for the location. */
    private static Read read(JsonNode market, String locationGid) {
        JsonNode shipping = market.path("delivery").path("shipping");
        if (shipping.isMissingNode() || shipping.isNull()) return new Read(Market.UNKNOWN, null);   // inherits: no verdict
        if (!shipping.path("isEnabled").asBoolean(false)) return new Read(Market.SHIPPING_OFF, null);
        JsonNode options = shipping.path("optionDefinitions");
        boolean complete = !options.path("pageInfo").path("hasNextPage").asBoolean(false);
        for (JsonNode o : options.path("nodes")) {
            if (!o.path("isActive").asBoolean(false)) continue;
            JsonNode locs = o.path("includedLocations");
            if (locs.isMissingNode() || locs.isNull()) { complete = false; continue; }   // undocumented: never guess
            for (JsonNode l : locs.path("nodes")) {
                if (locationGid.equals(l.path("id").asText(null))) {
                    String rate = o.path("name").asText(null);
                    return new Read(Market.COVERED, rate == null || rate.isBlank() ? null : rate);
                }
            }
            if (locs.path("pageInfo").path("hasNextPage").asBoolean(false)) complete = false;
        }
        return new Read(complete ? Market.NOT_COVERED : Market.UNKNOWN, null);
    }

    private static String name(JsonNode market) {
        String n = market.path("name").asText(null);
        return n == null || n.isBlank() ? "?" : n;
    }
}
