package com.traceability.integrations.shopify;

/**
 * Thrown by {@link ShopifyGateway#pushStockTakeWriteOff} when no confirmed response reached
 * this process (connect/read timeout, connection reset) — genuinely unknown whether Shopify
 * applied the decrement before the connection dropped.
 *
 * Distinct from {@link ShopifyException} (a definitive rejection — Shopify responded, even
 * if with an error) on purpose: the negative-delta write-off is NOT idempotent at the
 * business level (-2 applied twice = -4), so callers must NEVER auto-retry on this exception.
 * The claim row is marked 'failed_ambiguous' and surfaced for manual verification via
 * referenceDocumentUri — a human checks Shopify, then marks resolved or re-pushes
 * deliberately. See StockTakeShopifyPushJob.
 */
public class ShopifyAmbiguousException extends RuntimeException {
    public ShopifyAmbiguousException(String message) { super(message); }
    public ShopifyAmbiguousException(String message, Throwable cause) { super(message, cause); }
}
