package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyGateway;

/**
 * L1b-2 fix (2026-11-17) — THE answer to "what did Traced's market read say?", for the words under the market-driven
 * instruction (onboarding gate via /connections, connection card via /locations). Never re-derive it in a screen.
 *
 * <ul>
 *   <li>{@code covered} / {@code not_covered} — a stored verdict (MarketShippingCoverage), shown as is</li>
 *   <li>{@code no_verdict} — read_markets granted, Traced read the markets and could not decide (primary market not
 *       found / ambiguous, its shipping off, inherited, a list cut short): "couldn't find your main market's shipping"</li>
 *   <li>{@code not_checked} — read_markets granted, no read since (the hourly read or Check again will do it)</li>
 *   <li>{@code no_access} — read_markets not granted: "Traced can't check this from here yet"</li>
 * </ul>
 * With read_markets granted the answer is never {@code no_access} — a stale 'no_access' row (stored before the grant)
 * reads as {@code not_checked}. Bug 2026-11-17 (testl1location): the gate never received the stored 'not_covered' and
 * showed "can't check from here" + "I've added it" for a store Traced had read in full.
 */
public final class TracedMarketCheck {

    private TracedMarketCheck() {}

    /**
     * @param status    locations.shopify_market_shipping_status
     * @param checkedAt locations.shopify_market_shipping_checked_at (null = never read)
     * @param scopes    the store's access_token_scopes (null = no store)
     */
    public static String of(String status, Object checkedAt, String scopes) {
        if ("covered".equals(status) || "not_covered".equals(status)) return status;
        if (!ShopifyGateway.isScopeGranted("read_markets", scopes)) return "no_access";
        if (status == null && checkedAt != null) return "no_verdict";
        return "not_checked";
    }
}
