package com.traceability.integrations.shopify;

import java.util.List;

/**
 * Onboarding O4 — READ ONLY: the store's variants ordered by Shopify's total stock (most first), for the guided
 * first receiving's "Start with one product" suggestion. One page per call (productVariants, sortKey
 * INVENTORY_QUANTITY, reverse). Scopes read_products + read_inventory (write_inventory implies it) — no scope
 * change. There is no write here and none may be added.
 */
public interface ShopifyVariantStockGateway {

    /** One variant: its GID, Shopify's total inventoryQuantity, and its on-hand at the given location (null when
     *  no location was asked for or Shopify has no level there). */
    record VariantStock(String variantGid, int inventoryQuantity, Integer onHandAtLocation) {}

    record Page(List<VariantStock> variants, boolean hasNextPage, String endCursor) {}

    /**
     * @param locationGid the Traced Main Warehouse's Shopify location GID, or null to skip the per-location level
     * @param after       the previous page's endCursor, or null for the first page
     * @throws VariantStockReadException on any GraphQL error (codes only) or an answer without data
     */
    Page topVariantsByStock(String shopDomain, String token, String locationGid, String after, int first);

    /** Carries only Shopify's error codes (extensions.code) — never a message or a body. */
    class VariantStockReadException extends RuntimeException {
        private final String reason;

        public VariantStockReadException(String reason) {
            super("variant stock read failed: " + reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
