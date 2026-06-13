package com.traceability.integrations.shopify;

public class ShopifyException extends RuntimeException {
    public ShopifyException(String message) { super(message); }
    public ShopifyException(String message, Throwable cause) { super(message, cause); }
}
