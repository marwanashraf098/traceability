package com.traceability.inventory;

/**
 * "product + variant" display name composition for {@link FulfillService}'s gather list
 * (FR-8.7). Piece labels no longer use it: since the label layout rework they print the
 * product and the variant on separate rows (PieceLabelLayout), with the same "Default Title"
 * rule.
 *
 * Separator is " - " (not U+00B7 middle dot).
 */
final class ProductDisplayName {

    private ProductDisplayName() {}

    /**
     * Composes "product - variant", collapsing to just one side when the other carries
     * no real information: an empty product title, or Shopify's "Default Title"
     * placeholder variant (single-variant products get this literal value upstream).
     */
    static String compose(String productTitle, String variantTitle) {
        String product = productTitle == null ? "" : productTitle;
        String variant = variantTitle == null ? "" : variantTitle;
        if (product.isEmpty()) return variant;
        if ("Default Title".equalsIgnoreCase(variant)) return product;
        return product + " - " + variant;
    }
}
