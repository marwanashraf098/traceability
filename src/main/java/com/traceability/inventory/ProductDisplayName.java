package com.traceability.inventory;

/**
 * Shared "product + variant" display name composition. Used by both {@link LabelService}
 * (printed under the physical piece barcode) and {@link FulfillService}'s gather list
 * (FR-8.7), so the two can never render a different name for the same variant.
 *
 * Separator is " - " (not U+00B7 middle dot — that glyph isn't in the embedded
 * NotoSansArabic font LabelService uses for Arabic titles).
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
