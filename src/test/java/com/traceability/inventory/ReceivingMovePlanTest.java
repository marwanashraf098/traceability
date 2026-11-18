package com.traceability.inventory;

import com.traceability.inventory.ReceivingMovePlan.Case;
import com.traceability.inventory.ReceivingMovePlan.Line;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Receiving move M1 test (b): Step 0 §6, the per-line math, as unit tests (A = source available, q = received; Traced
 * Main always +q). One test per table row, plus "default keep": no / unknown choice is KEEP — 'zero' only explicitly.
 */
class ReceivingMovePlanTest {

    static void row(Line l, Case kase, int sourceDelta, Integer sourceAfter, int totalChange, int mainDelta) {
        assertThat(l.kase()).isEqualTo(kase);
        assertThat(l.sourceDelta()).as("source delta").isEqualTo(sourceDelta);
        assertThat(l.sourceAfter()).as("source after").isEqualTo(sourceAfter);
        assertThat(l.totalChange()).as("Shopify total change").isEqualTo(totalChange);
        assertThat(l.mainDelta()).as("Traced Main gets +q").isEqualTo(mainDelta);
    }

    @Test
    void qEqualsA_allMoved() {
        row(ReceivingMovePlan.line(10, 10, "keep"), Case.ALL_MOVED, -10, 0, 0, 10);
        row(ReceivingMovePlan.line(10, 10, "zero"), Case.ALL_MOVED, -10, 0, 0, 10);
    }

    @Test
    void qLessThanA_keepThemThere() {
        row(ReceivingMovePlan.line(10, 12, "keep"), Case.KEEP_REST, -10, 2, 0, 10);
    }

    @Test
    void qLessThanA_setSourceToZero() {
        row(ReceivingMovePlan.line(10, 12, "zero"), Case.SET_TO_ZERO, -12, 0, -2, 10);
    }

    @Test
    void qMoreThanA_positiveA() {
        // Polo: R = 6, A = 4 → source −4, Main +6, total +2 (the choice doesn't apply).
        row(ReceivingMovePlan.line(6, 4, "keep"), Case.MORE_THAN_SHOPIFY, -4, 0, 2, 6);
        row(ReceivingMovePlan.line(6, 4, "zero"), Case.MORE_THAN_SHOPIFY, -4, 0, 2, 6);
    }

    @Test
    void aZeroOrNegativeOrNoLevel_sourceUnchanged() {
        row(ReceivingMovePlan.line(5, 0, "keep"), Case.NOTHING_AT_SOURCE, 0, 0, 5, 5);
        row(ReceivingMovePlan.line(5, -3, "zero"), Case.NOTHING_AT_SOURCE, 0, -3, 5, 5);
        row(ReceivingMovePlan.line(5, null, "zero"), Case.NOTHING_AT_SOURCE, 0, null, 5, 5);
    }

    @Test
    void qZero_lineNotAllowed() {
        row(ReceivingMovePlan.line(0, 7, "keep"), Case.NOT_ALLOWED, 0, null, 0, 0);
    }

    @Test
    void defaultIsKeep_zeroOnlyExplicit() {
        assertThat(ReceivingMovePlan.line(3, 9, null).kase()).isEqualTo(Case.KEEP_REST);
        assertThat(ReceivingMovePlan.line(3, 9, "ZERO").kase()).isEqualTo(Case.KEEP_REST);
        assertThat(ReceivingMovePlan.line(3, 9, "").kase()).isEqualTo(Case.KEEP_REST);
    }

    @Test
    void sourceDeltaIsNeverPositive_andNeverBelowZeroAvailable() {
        for (int q = 1; q <= 12; q++) {
            for (Integer a : new Integer[] {null, -2, 0, 1, 5, 12}) {
                for (String ch : new String[] {"keep", "zero"}) {
                    Line l = ReceivingMovePlan.line(q, a, ch);
                    assertThat(l.sourceDelta()).isLessThanOrEqualTo(0);
                    if (a != null && a > 0) assertThat(a + l.sourceDelta()).isGreaterThanOrEqualTo(0);
                    assertThat(l.mainDelta()).isEqualTo(q);
                }
            }
        }
    }
}
