package com.traceability.integrations.shopify;

/**
 * Thrown when a Shopify token refresh fails due to a transient condition
 * (5xx response, connection timeout, network reset). The refresh token is
 * still valid — the caller should NOT mark the store as needs_reauth.
 * The job should fail and rely on retry or manual re-trigger.
 */
public class ShopifyTransientException extends RuntimeException {
    public ShopifyTransientException(String message, Throwable cause) { super(message, cause); }
}
