package com.traceability.integrations.shopify;

/**
 * An inventoryAdjustQuantities attempt that did not apply, classified by whether it could have
 * reached Shopify (failed-increment recovery, Part D):
 *   NEVER_SENT — the mutation never left Traced (a pre-write read failed, the connection was never
 *                made): retrying with the SAME idempotency key is safe;
 *   REJECTED   — Shopify answered and did not apply it (4xx, GraphQL error, userError): a retry needs
 *                a NEW key — Shopify may keep the failed response under the old one;
 *   AMBIGUOUS  — the request may have been processed (5xx, read timeout, reset, unexpected error):
 *                only an identical resend (same key, same changeFromQuantity) is safe, and only while
 *                Shopify still remembers the key (24 h).
 * changeFromQuantity is the baseline that was SENT (null when nothing was sent or the level had none).
 *
 * probeStale (Part D fix P1, 2026-11-08) is set only on a RECOVERY CHECK (the identical re-send of an ambiguous
 * claim) whose answer means Shopify did not replay a success — CHANGE_FROM_QUANTITY_STALE, an idempotency code, any
 * other userError, a non-THROTTLED top-level error, no data. With changeFromQuantity set, an identical re-send of an
 * adjust that LANDED answers STALE (Move S0), so this is never "not applied": the claim stays AMBIGUOUS, is never sent
 * again automatically, and a person checks Shopify (inventory_increment_sync_failed).
 */
public class ShopifyAdjustFailedException extends ShopifyException {

    public enum FailureClass {
        NEVER_SENT("never_sent"), REJECTED("rejected"), AMBIGUOUS("ambiguous");

        private final String db;
        FailureClass(String db) { this.db = db; }
        public String db() { return db; }

        public static FailureClass fromDb(String value) {
            for (FailureClass f : values()) if (f.db.equals(value)) return f;
            return null;
        }
    }

    private final FailureClass failureClass;
    private final Integer changeFromQuantity;
    private final boolean probeStale;

    public ShopifyAdjustFailedException(FailureClass failureClass, Integer changeFromQuantity,
                                        String message, Throwable cause) {
        this(failureClass, changeFromQuantity, message, cause, false);
    }

    public ShopifyAdjustFailedException(FailureClass failureClass, Integer changeFromQuantity,
                                        String message, Throwable cause, boolean probeStale) {
        super(message, cause);
        if (probeStale && failureClass != FailureClass.AMBIGUOUS) {
            throw new IllegalArgumentException("probeStale is only ever AMBIGUOUS");
        }
        this.failureClass = failureClass;
        this.changeFromQuantity = changeFromQuantity;
        this.probeStale = probeStale;
    }

    public FailureClass failureClass() { return failureClass; }
    public Integer changeFromQuantity() { return changeFromQuantity; }
    /** A recovery check answered without replaying a success — stop; a person checks Shopify. */
    public boolean probeStale() { return probeStale; }
}
