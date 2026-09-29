package com.traceability.inventory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LabelTextFitter: width-measured wrap → shrink → "…", Arabic shaped in logical order.
 * Every case asserts each line's measured width ≤ the usable width, at both label widths
 * (50 mm → 130 pt of text, 40 mm → 102 pt).
 */
class LabelTextFitterTest {

    static final float W50 = (50f - 4f) * PieceLabelLayout.MM;
    static final float W40 = (40f - 4f) * PieceLabelLayout.MM;
    static final LabelFonts.Face R = LabelFonts.Face.REGULAR;

    static final String LONG_EN = "Organic Cotton Oversized Heavyweight Hoodie with Embroidered Logo — Limited Autumn Edition";
    static final String LONG_AR = "بلوزة قطنية واسعة بأكمام طويلة وتطريز يدوي — إصدار الخريف المحدود";

    @Test
    void t1_shortEnglish_oneLine_startSize_notCut() {
        for (float w : List.of(W50, W40)) {
            LabelTextFitter.Fit f = LabelTextFitter.fit("Linen Shirt", R, 6f, 5f, 2, w);
            assertThat(f.lines()).hasSize(1);
            assertThat(f.size()).isEqualTo(6f);
            assertThat(f.ellipsized()).isFalse();
            assertThat(f.lines().get(0).logical()).isEqualTo("Linen Shirt");
            assertWithin(f, w);
        }
    }

    @Test
    void t2_veryLongEnglish_wraps_shrinksToFloor_thenEllipsis() {
        // LONG_EN at 50 mm fits in two lines once shrunk to 5.5 pt — no cut needed.
        LabelTextFitter.Fit shrunk = LabelTextFitter.fit(LONG_EN, R, 6f, 5f, 2, W50);
        assertThat(shrunk.lines()).hasSize(2);
        assertThat(shrunk.size()).isLessThan(6f);
        assertThat(shrunk.ellipsized()).isFalse();
        assertWithin(shrunk, W50);

        for (float w : List.of(W50, W40)) {
            LabelTextFitter.Fit f = LabelTextFitter.fit(LONG_EN + " " + LONG_EN, R, 6f, 5f, 2, w);
            assertThat(f.lines()).hasSize(2);
            assertThat(f.size()).isEqualTo(5f);
            assertThat(f.ellipsized()).isTrue();
            assertThat(f.lines().get(0).logical()).startsWith("Organic Cotton");
            assertThat(f.lines().get(1).logical()).endsWith(LabelTextFitter.ELLIPSIS);
            assertWithin(f, w);
        }
    }

    @Test
    void t3_mediumEnglish_wrapsToTwoLines_beforeShrinking() {
        LabelTextFitter.Fit f = LabelTextFitter.fit("Organic Cotton Oversized Heavyweight Hoodie Grey", R, 6f, 5f, 2, W40);
        assertThat(f.lines()).hasSize(2);
        assertThat(f.size()).isEqualTo(6f);
        assertThat(f.ellipsized()).isFalse();
        assertWithin(f, W40);
    }

    @Test
    void t4_longArabic_ellipsisAtTheLogicalEnd_shapedConnectedForms() {
        for (float w : List.of(W50, W40)) {
            LabelTextFitter.Fit f = LabelTextFitter.fit(LONG_AR + " " + LONG_AR, R, 6f, 5f, 2, w);
            assertThat(f.ellipsized()).isTrue();
            assertThat(f.size()).isEqualTo(5f);
            LabelTextFitter.Line last = f.lines().get(f.lines().size() - 1);
            assertThat(last.logical()).endsWith(LabelTextFitter.ELLIPSIS);
            assertThat(last.rtl()).isTrue();
            assertThat(LONG_AR).startsWith(f.lines().get(0).logical());
            // Visual order: the "…" of a right-to-left line is its leftmost glyph.
            assertThat(last.runs().get(0).text()).startsWith(LabelTextFitter.ELLIPSIS);
            assertWithin(f, w);
        }
    }

    @Test
    void t5_arabicShaping_usesContextualForms_logicalOrder() {
        // beh + reh: beh must be INITIAL (U+FE91), reh FINAL (U+FEAE) — not the isolated
        // U+FE8F/U+FEAD the old VISUAL_LTR shaping flag produced (letters printed unjoined).
        String shown = LabelTextFitter.display("بر");
        assertThat(shown).isEqualTo("ﺮﺑ");
    }

    @Test
    void t6_mixedArabicEnglishDigits_fits_directionFromFirstStrongChar() {
        LabelTextFitter.Fit latinFirst = LabelTextFitter.fit("Vanilla Whey 1KG - بروتين واي", R, 6f, 5f, 2, W40);
        LabelTextFitter.Fit arabicFirst = LabelTextFitter.fit("بروتين واي 1000g", R, 5.5f, 5f, 1, W40);
        assertThat(latinFirst.lines().get(0).rtl()).isFalse();
        assertThat(latinFirst.lines().get(0).runs().get(0).text()).startsWith("Vanilla");
        assertThat(arabicFirst.lines().get(0).rtl()).isTrue();
        assertWithin(latinFirst, W40);
        assertWithin(arabicFirst, W40);
    }

    @Test
    void t7_arabicNextToSlashAmpersandEllipsis_noArabicRunHoldsThem() {
        for (float w : List.of(W50, W40)) {
            LabelTextFitter.Fit f = LabelTextFitter.fit("S / أحمر & أزرق… عطر ورد & عود", R, 5.5f, 5f, 1, w);
            for (LabelTextFitter.Line l : f.lines()) {
                for (LabelFonts.Run r : l.runs()) {
                    if (r.face() == LabelFonts.Face.ARABIC) {
                        assertThat(r.text()).doesNotContain("/", "&", LabelTextFitter.ELLIPSIS);
                    }
                }
            }
            assertWithin(f, w);
        }
    }

    @Test
    void t8_singleTokenWiderThanTheLine_brokenOrCut_neverOverflows() {
        String sku = "HOOD-ORG-CHAR-XXL-RELAXED-2026-EXTRA-LONG-SKU-VALUE";
        LabelTextFitter.Fit one = LabelTextFitter.fit(sku, R, 5f, 5f, 1, W40);
        assertThat(one.lines()).hasSize(1);
        assertThat(one.lines().get(0).logical()).endsWith(LabelTextFitter.ELLIPSIS).startsWith("HOOD-ORG");
        assertWithin(one, W40);

        LabelTextFitter.Fit two = LabelTextFitter.fit(sku + sku, R, 6f, 5f, 2, W40);
        assertThat(two.lines()).hasSize(2);
        assertWithin(two, W40);
    }

    @Test
    void t9_blankAndWhitespace_noLines() {
        assertThat(LabelTextFitter.fit("   ", R, 5f, 5f, 1, W50).lines()).isEmpty();
        assertThat(LabelTextFitter.fit(null, R, 5f, 5f, 1, W50).lines()).isEmpty();
    }

    static void assertWithin(LabelTextFitter.Fit f, float maxWidth) {
        for (LabelTextFitter.Line l : f.lines()) {
            float measured = LabelFonts.width(l.runs(), f.size());
            assertThat(measured).as("'%s' at %s pt", l.logical(), f.size()).isLessThanOrEqualTo(maxWidth + 0.001f);
            assertThat(l.width()).isEqualTo(measured);
        }
    }
}
