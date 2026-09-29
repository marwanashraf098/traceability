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

    @Test
    void t10_shortMixedArabicLatin_noEllipsis_fullTextRendered_bothWidths() {
        for (float w : List.of(W50, W40)) {
            for (String v : List.of("S / أحمر", "Red / أحمر", "أحمر / S", "M / أزرق & أخضر")) {
                LabelTextFitter.Fit f = LabelTextFitter.fit(v, R, 5.5f, 5f, 1, w);
                assertThat(f.ellipsized()).as(v).isFalse();
                assertThat(f.size()).isEqualTo(5.5f);
                assertThat(f.lines()).singleElement().satisfies(l -> {
                    assertThat(l.logical()).isEqualTo(v);
                    String drawn = l.runs().stream().map(LabelFonts.Run::text).reduce("", String::concat);
                    assertThat(drawn).isEqualTo(LabelTextFitter.display(v)).doesNotContain(LabelTextFitter.ELLIPSIS);
                });
            }
        }
    }

    @Test
    void t11_ellipsisInTheDataIsKept_noneAdded() {
        // The sample fixture "S / أحمر…" carries its own U+2026 — nothing is cut, nothing is added.
        LabelTextFitter.Fit f = LabelTextFitter.fit("S / أحمر…", R, 5.5f, 5f, 1, W40);
        assertThat(f.ellipsized()).isFalse();
        assertThat(f.lines().get(0).logical()).isEqualTo("S / أحمر…");
    }

    @Test
    void t12_addedEllipsis_displaysAtTheVisualEndOfItsLine() {
        LabelTextFitter.Fit ltr = LabelTextFitter.fit(LONG_EN + " " + LONG_EN, R, 6f, 5f, 2, W40);
        LabelTextFitter.Fit rtl = LabelTextFitter.fit(LONG_AR + " " + LONG_AR, R, 6f, 5f, 2, W40);
        List<LabelFonts.Run> l = ltr.lines().get(1).runs(), r = rtl.lines().get(1).runs();
        assertThat(l.get(l.size() - 1).text()).as("LTR: rightmost").endsWith(LabelTextFitter.ELLIPSIS);
        assertThat(r.get(0).text()).as("RTL: leftmost").startsWith(LabelTextFitter.ELLIPSIS);
    }

    @Test
    void t13_longTitles_arabicAndEnglish_cutAfterAWholeWord_bothSizes_andInTheLayout() {
        for (float w : List.of(W50, W40)) {
            for (String src : List.of(LONG_EN + " " + LONG_EN, LONG_AR + " " + LONG_AR)) {
                LabelTextFitter.Fit f = LabelTextFitter.fit(src, R, 6f, 5f, 2, w);
                assertThat(f.ellipsized()).isTrue();
                assertEndsOnWholeWord(src, f);
                LabelTextFitter.Fit one = LabelTextFitter.fit(src, R, 5f, 5f, 1, w);
                assertEndsOnWholeWord(src, one);
            }
        }
        for (PieceLabelLayout.Spec spec : List.of(PieceLabelLayout.Spec.of(50, 25), PieceLabelLayout.Spec.of(40, 25))) {
            PieceLabelLayout.Layout l = PieceLabelLayout.layout(spec, "P000003", LONG_AR, "أحمر / مقاس كبير", "BLZ-AR-RED-L");
            String title = l.lines().get(1).logical();
            assertThat(title).endsWith(ELL);
            String kept = title.substring(0, title.length() - 1);
            assertThat(LONG_AR).startsWith(kept);
            assertThat(Character.isWhitespace(LONG_AR.charAt(kept.length()))).as("%s: next char after the cut is a space", spec).isTrue();
        }
    }

    @Test
    void t14_trailingDashAndSeparators_strippedBeforeTheEllipsis() {
        for (String sep : List.of(" —", " -", ",", " /", " &", "،")) {
            String kept = "إصدار الخريف" + sep;
            String src = kept + " المحدود جدا جدا جدا";
            float width = LabelTextFitter.measure(kept + " " + ELL, R, 5f) + 0.2f;   // room for the separator, not the next word
            String out = LabelTextFitter.ellipsize(src, R, 5f, width, LabelTextFitter.Cut.WORD);
            assertThat(out).as("sep '%s'", sep).isEqualTo("إصدار الخريف" + ELL);
        }
        String en = LabelTextFitter.ellipsize("Organic Cotton — Limited Edition Autumn", R, 5f,
            LabelTextFitter.measure("Organic Cotton — " + ELL, R, 5f) + 0.2f, LabelTextFitter.Cut.WORD);
        assertThat(en).isEqualTo("Organic Cotton" + ELL);
    }

    @Test
    void t15_firstWordAloneTooWide_fallsBackToAGraphemeCut() {
        String word = "Supercalifragilisticexpialidocious";
        String out = LabelTextFitter.ellipsize(word + " Hoodie", R, 5f, LabelTextFitter.measure("Supercali" + ELL, R, 5f) + 0.2f,
            LabelTextFitter.Cut.WORD);
        assertThat(out).isEqualTo("Supercali" + ELL);
    }

    @Test
    void t16_skuUsesGraphemeCut_notWord() {
        String sku = "ABCD EFGHIJKLMNOP";
        float width = LabelTextFitter.measure("ABCD EFG" + ELL, R, 5f) + 0.2f;
        assertThat(LabelTextFitter.ellipsize(sku, R, 5f, width, LabelTextFitter.Cut.GRAPHEME)).isEqualTo("ABCD EFG" + ELL);
        assertThat(LabelTextFitter.ellipsize(sku, R, 5f, width, LabelTextFitter.Cut.WORD)).isEqualTo("ABCD" + ELL);
        PieceLabelLayout.Layout l = PieceLabelLayout.layout(PieceLabelLayout.Spec.of(40, 25), "P1", "Tee", null,
            "HOOD-ORG-CHAR-XXL-RELAXED-2026-EXTRA LONG SKU VALUE-2");
        String skuLine = l.lines().get(l.lines().size() - 1).logical();
        assertThat(skuLine).endsWith(ELL);
        assertThat(skuLine.charAt(skuLine.length() - 2)).as("cut inside a token, not at a space").isNotEqualTo(' ');
    }

    static final String ELL = LabelTextFitter.ELLIPSIS;

    static void assertEndsOnWholeWord(String src, LabelTextFitter.Fit f) {
        String joined = String.join(" ", f.lines().stream().map(LabelTextFitter.Line::logical).toList());
        assertThat(joined).endsWith(ELL);
        String kept = joined.substring(0, joined.length() - 1);
        assertThat(src).as("kept text is a prefix of the source").startsWith(kept);
        char next = src.charAt(kept.length());
        assertThat(Character.isWhitespace(next) || "—-,،/&".indexOf(next) >= 0)
            .as("cut after a whole word, next char '%s' in '%s'", next, kept).isTrue();
        char lastKept = kept.charAt(kept.length() - 1);
        assertThat(Character.isWhitespace(lastKept) || "—-,،/&".indexOf(lastKept) >= 0)
            .as("no trailing separator before the …: '%s'", kept).isFalse();
    }

    static void assertWithin(LabelTextFitter.Fit f, float maxWidth) {
        for (LabelTextFitter.Line l : f.lines()) {
            float measured = LabelFonts.width(l.runs(), f.size());
            assertThat(measured).as("'%s' at %s pt", l.logical(), f.size()).isLessThanOrEqualTo(maxWidth + 0.001f);
            assertThat(l.width()).isEqualTo(measured);
        }
    }
}
