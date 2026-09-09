package com.traceability.integrations.shopify;

/**
 * Thrown when a Shopify token is requested for a store whose status is 'disconnected'
 * (merchant-initiated soft disconnect, or app/uninstalled). Central short-circuit in
 * ShopifyTokenProvider.getValidToken() — thrown BEFORE any decrypt or refresh attempt,
 * so a disconnected store's token is never read or rotated.
 */
public class ShopifyStoreDisconnectedException extends ShopifyException {
    private final String shopDomain;

    public ShopifyStoreDisconnectedException(String shopDomain, String reason) {
        super("Store " + shopDomain + " is disconnected: " + reason);
        this.shopDomain = shopDomain;
    }

    public String getShopDomain() { return shopDomain; }
}
