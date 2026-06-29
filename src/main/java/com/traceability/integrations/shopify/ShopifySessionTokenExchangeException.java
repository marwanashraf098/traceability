package com.traceability.integrations.shopify;

/**
 * Thrown when Shopify returns 4xx on a session-token → access-token exchange call
 * (grant_type=urn:ietf:params:oauth:grant-type:token-exchange).
 *
 * Distinct from ShopifyStoreNeedsReauthException, which signals permanent refresh-token
 * invalidation. A session-token exchange rejection does NOT imply the refresh token is
 * invalid — do NOT mark the store as needs_reauth on this exception.
 */
public class ShopifySessionTokenExchangeException extends ShopifyException {
    private final String shopDomain;

    public ShopifySessionTokenExchangeException(String shopDomain, String reason) {
        super("Session token exchange rejected for " + shopDomain + ": " + reason);
        this.shopDomain = shopDomain;
    }

    public String getShopDomain() { return shopDomain; }
}
