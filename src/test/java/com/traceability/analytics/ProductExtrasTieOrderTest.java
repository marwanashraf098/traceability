package com.traceability.analytics;

import com.traceability.analytics.ProductExtrasAnalyticsService.AbcRow;
import com.traceability.analytics.ProductExtrasAnalyticsService.Extras;
import com.traceability.analytics.ProductExtrasAnalyticsService.FailedProduct;
import com.traceability.analytics.ProductExtrasAnalyticsService.Pair;
import com.traceability.analytics.ProductExtrasAnalyticsService.VariantRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exact ties in the products-extras rankings (2026-10-21). The sorts ended on titles, which aren't unique, and Java's
 * sort is stable — so tied rows kept the order the query happened to return, and the top-10 cut / the A/B/C line
 * depended on it. Each now ends on an id. Every test feeds the SAME rows in two different orders and expects one
 * answer.
 *
 * xt1 — most failed products: twelve tied (rate, orders, title) → the ten lowest product ids.
 * xt2 — bought together: twelve tied pairs (orders, both titles) → the ten lowest (variantA, variantB).
 * xt3 — ABC: ten variants with equal realized revenue → ordered by variant id, so the same variants are A / B / C.
 */
class ProductExtrasTieOrderTest {

    static final BigDecimal RATE = new BigDecimal("0.5000");

    @Test
    void xt1_mostFailed_tieAtTheTop10Cut_lowestProductIds() {
        List<FailedProduct> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) rows.add(new FailedProduct(UUID.randomUUID(), "Same", 40, 20, RATE));

        List<FailedProduct> a = extras(List.of(), shuffled(rows, 1), List.of()).mostFailed();
        List<FailedProduct> b = extras(List.of(), shuffled(rows, 2), List.of()).mostFailed();

        assertThat(a).isEqualTo(b);
        assertThat(a).extracting(FailedProduct::productId)
            .containsExactlyElementsOf(sortedIds(rows, FailedProduct::productId).subList(0, 10));
    }

    @Test
    void xt2_boughtTogether_tieAtTheTop10Cut_lowestVariantIds() {
        UUID shared = UUID.randomUUID();
        List<Pair> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) rows.add(new Pair(UUID.randomUUID(), "Same — x", UUID.randomUUID(), "Same — y", 5));
        // Same variantA, different variantB: the second key decides.
        for (int i = 0; i < 6; i++) rows.add(new Pair(shared, "Same — x", UUID.randomUUID(), "Same — y", 5));

        List<Pair> a = extras(List.of(), List.of(), shuffled(rows, 1)).boughtTogether();
        List<Pair> b = extras(List.of(), List.of(), shuffled(rows, 2)).boughtTogether();

        assertThat(a).isEqualTo(b);
        List<Pair> expected = new ArrayList<>(rows);
        expected.sort((x, y) -> x.variantA().equals(y.variantA())
            ? x.variantB().compareTo(y.variantB()) : x.variantA().compareTo(y.variantA()));
        assertThat(a).containsExactlyElementsOf(expected.subList(0, 10));
    }

    @Test
    void xt3_abc_equalRealized_orderedByVariantId_sameClasses() {
        List<VariantRow> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(new VariantRow(UUID.randomUUID(), 1, 1, 0, new BigDecimal("100.00"), 0,
                "SKU", "Same", "Same", null, false));
        }

        List<AbcRow> a = ProductExtrasAnalyticsService.abc(shuffled(rows, 1));
        List<AbcRow> b = ProductExtrasAnalyticsService.abc(shuffled(rows, 2));

        assertThat(a).isEqualTo(b);
        assertThat(a).extracting(AbcRow::variantId).containsExactlyElementsOf(sortedIds(rows, VariantRow::variantId));
        assertThat(a).extracting(AbcRow::abcClass)
            .as("each variant is 10 %%: A while the share before it is under 80 %%, B under 95 %%")
            .containsExactly("A", "A", "A", "A", "A", "A", "A", "A", "B", "B");
    }

    private static Extras extras(List<VariantRow> v, List<FailedProduct> f, List<Pair> p) {
        return ProductExtrasAnalyticsService.extrasOf(v, f, p);
    }

    private static <T> List<T> shuffled(List<T> rows, long seed) {
        List<T> out = new ArrayList<>(rows);
        Collections.shuffle(out, new Random(seed));
        return out;
    }

    private static <T> List<UUID> sortedIds(List<T> rows, Function<T, UUID> id) {
        return rows.stream().map(id).sorted().toList();
    }
}
