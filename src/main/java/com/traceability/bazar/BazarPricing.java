package com.traceability.bazar;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Bazar POS — THE price rule for an event (never re-derive it). Prices are Traced-only: never written to Shopify.
 *
 * <ul>
 *   <li>No rule (no discount, no round-to): the event price is the variant's Shopify price ({@code variants.price}),
 *       source {@code default}.</li>
 *   <li>Rule: {@code base × (1 − discount_pct / 100)}, then rounded to the NEAREST multiple of {@code round_to}
 *       (half up) — or to 2 decimals when there is no round-to — source {@code bulk}. A positive price is never
 *       rounded down to 0: it becomes one {@code round_to} instead.</li>
 *   <li>An {@code override} row is set by hand and the rule never touches it; clearing it recomputes the row.</li>
 * </ul>
 */
public final class BazarPricing {

    private BazarPricing() {}

    public static boolean hasRule(BigDecimal discountPct, BigDecimal roundTo) {
        return discountPct != null || roundTo != null;
    }

    /** The rule's price for a Shopify price {@code base} (non-null, ≥ 0). */
    public static BigDecimal ruled(BigDecimal base, BigDecimal discountPct, BigDecimal roundTo) {
        BigDecimal p = base;
        if (discountPct != null) {
            p = p.multiply(BigDecimal.valueOf(100).subtract(discountPct)).divide(BigDecimal.valueOf(100));
        }
        if (roundTo == null) {
            return p.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal steps = p.divide(roundTo, 0, RoundingMode.HALF_UP);
        BigDecimal rounded = steps.multiply(roundTo);
        if (rounded.signum() == 0 && p.signum() > 0) {
            rounded = roundTo;
        }
        return rounded.setScale(2, RoundingMode.HALF_UP);
    }

    /** The non-override row for a variant: its price and source, or null when the variant has no Shopify price. */
    public static Priced forVariant(BigDecimal base, BigDecimal discountPct, BigDecimal roundTo) {
        if (base == null) return null;
        return hasRule(discountPct, roundTo)
            ? new Priced(ruled(base, discountPct, roundTo), "bulk")
            : new Priced(base.setScale(2, RoundingMode.HALF_UP), "default");
    }

    public record Priced(BigDecimal price, String source) {}
}
