package com.traceability.integrations.shopify;

/**
 * Thrown when a Shopify token refresh fails permanently — the refresh token is
 * invalid, expired, or revoked (Shopify returns 4xx with an invalid_grant-style body).
 * ShopifyTokenProvider marks the store status as 'needs_reauth' before throwing.
 * The merchant must reinstall the app to issue a new token pair.
 */
public class ShopifyStoreNeedsReauthException extends ShopifyException {
    private final String shopDomain;

    public ShopifyStoreNeedsReauthException(String shopDomain, String reason) {
        super("Store " + shopDomain + " requires reauth: " + reason);
        this.shopDomain = shopDomain;
    }

    public String getShopDomain() { return shopDomain; }
}
