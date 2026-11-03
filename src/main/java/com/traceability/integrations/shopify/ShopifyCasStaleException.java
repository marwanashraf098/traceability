package com.traceability.integrations.shopify;

/**
 * Main reconcile (V203, R2): Shopify refused a compare-and-set adjust because the quantity changed since Traced read
 * it (userError CHANGE_FROM_QUANTITY_STALE). Definite — nothing was applied; the next run reads again and decides again.
 */
public class ShopifyCasStaleException extends ShopifyException {
    public ShopifyCasStaleException(String message) { super(message); }
}
