package com.traceability.integrations.shopify;

/** Shopify answered ACCESS_DENIED to a returns / fulfillment-order read: the store hasn't approved the scopes yet. */
public class ShopifyReturnsAccessDeniedException extends ShopifyException {
    public ShopifyReturnsAccessDeniedException(String message) { super(message); }
}
