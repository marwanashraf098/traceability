package com.traceability;

import com.traceability.bazar.BazarPricing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Bazar POS B1 — the event price rule (pure math, no DB). */
class BazarPricingTest {

    private static BigDecimal d(String s) { return s == null ? null : new BigDecimal(s); }

    private static String ruled(String base, String pct, String roundTo) {
        return BazarPricing.ruled(d(base), d(pct), d(roundTo)).toPlainString();
    }

    @Test
    void percentOff_withoutRounding_keepsTwoDecimalsHalfUp() {
        assertThat(ruled("349.00", "20", null)).isEqualTo("279.20");
        assertThat(ruled("99.99", "15", null)).isEqualTo("84.99");    // 84.9915 → 84.99
        assertThat(ruled("10.01", "50", null)).isEqualTo("5.01");     // 5.005 → 5.01 (half up)
    }

    @Test
    void roundTo_isNearestMultiple_halfUp() {
        assertThat(ruled("349.00", "20", "10")).isEqualTo("280.00");  // 279.2 → 280
        assertThat(ruled("349.00", "20", "50")).isEqualTo("300.00");  // 279.2 → 300 (5.58 → 6 steps)
        assertThat(ruled("312.50", "20", "50")).isEqualTo("250.00");  // 250 exactly
        assertThat(ruled("343.75", "20", "50")).isEqualTo("300.00");  // 275 = 5.5 steps → 6 (half up)
        assertThat(ruled("330.00", "20", "5")).isEqualTo("265.00");   // 264 → 265
        assertThat(ruled("330.00", "20", "0.5")).isEqualTo("264.00");
    }

    @Test
    void roundTo_alone_roundsTheShopifyPrice() {
        assertThat(ruled("347.00", null, "10")).isEqualTo("350.00");
        assertThat(ruled("344.99", null, "10")).isEqualTo("340.00");
    }

    @Test
    void aPositivePrice_isNeverRoundedDownToFree() {
        assertThat(ruled("20.00", "10", "50")).isEqualTo("50.00");    // 18 → 0 steps → one round_to
        assertThat(ruled("0.00", "10", "50")).isEqualTo("0.00");      // free stays free
    }

    @Test
    void forVariant_sourceIsDefaultWithoutRule_bulkWithRule_nullWithoutShopifyPrice() {
        BazarPricing.Priced none = BazarPricing.forVariant(d("349"), null, null);
        assertThat(none.price().toPlainString()).isEqualTo("349.00");
        assertThat(none.source()).isEqualTo("default");

        BazarPricing.Priced bulk = BazarPricing.forVariant(d("349"), d("20"), d("10"));
        assertThat(bulk.price().toPlainString()).isEqualTo("280.00");
        assertThat(bulk.source()).isEqualTo("bulk");

        assertThat(BazarPricing.forVariant(null, d("20"), d("10"))).isNull();
    }
}
