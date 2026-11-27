package com.traceability.analytics;

import java.math.BigDecimal;

/**
 * THE "cost known" rule (decision (e), approved 2026-11-27): a variant's unit_cost counts only when it is > 0.
 * NULL (Shopify gave none) and 0 (Shopify's default when the merchant never filled "Cost per item") are both
 * UNKNOWN — never a 100 % margin, never zero-cost revenue in profit, never 0 in stock value at cost. Every cost
 * figure (Profit, Stock value at cost, the cost sync's costed count) asks this rule.
 */
final class UnitCost {

    private UnitCost() {}

    static boolean known(BigDecimal unitCost) {
        return unitCost != null && unitCost.signum() > 0;
    }

    /** SQL true when the unit cost expression {@code expr} is known (NULL → not true). Padded with spaces. */
    static String knownSql(String expr) {
        return " (" + expr + " > 0) ";
    }
}
