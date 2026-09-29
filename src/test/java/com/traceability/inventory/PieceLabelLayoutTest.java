package com.traceability.inventory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PieceLabelLayout at 50×25 and 40×25 for the whole fixture set: no two boxes intersect (text
 * rows and the barcode), every box is inside the page, every line fits the text width, and the
 * rows / sizes / omissions follow the table. The vertical-fit order is exercised with taller
 * barcodes, where the rows stop fitting.
 */
class PieceLabelLayoutTest {

    static final List<PieceLabelLayout.Spec> SIZES = List.of(PieceLabelLayout.Spec.of(50, 25), PieceLabelLayout.Spec.of(40, 25));

    /** code, product, variant, sku */
    static final String[][] FIXTURES = {
        {"P000001", "Linen Shirt", "White / M", "LIN-W-M"},
        {"P002224", LabelTextFitterTest.LONG_EN, "Charcoal Grey / XXL / Relaxed Fit", "HOOD-ORG-CHAR-XXL-RELAXED-2026"},
        {"P002221", LabelTextFitterTest.LONG_AR, "أحمر / مقاس كبير", "BLZ-AR-RED-L"},
        {"P002222", "Vanilla Whey 1KG - بروتين واي", "بروتين واي 1000g", "WHEY-VW-1000"},
        {"P002223", "عطر ورد & عود", "S / أحمر…", "PERF-ROSE-S"},
        {"P000100", "Canvas Tote", "Default Title", null},
        {"P000101", "Платье летнее", "Синий / M", "DRESS-RU-M"},
        {"P999999", LabelTextFitterTest.LONG_EN + " " + LabelTextFitterTest.LONG_EN,
            LabelTextFitterTest.LONG_AR, "SKU-" + "X".repeat(80)},
    };

    @Test
    void l1_everyFixture_bothSizes_noOverlap_insidePage_withinTextWidth() {
        for (PieceLabelLayout.Spec spec : SIZES) {
            for (String[] f : FIXTURES) {
                PieceLabelLayout.Layout l = PieceLabelLayout.layout(spec, f[0], f[1], f[2], f[3]);
                assertSound(l, spec + " " + f[1]);
            }
        }
    }

    @Test
    void l2_rowsAndSizes_followTheTable_atTenMillimetres_nothingDropped() {
        for (PieceLabelLayout.Spec spec : SIZES) {
            PieceLabelLayout.Layout l = PieceLabelLayout.layout(spec, "P000001", "Linen Shirt", "White / M", "LIN-W-M");
            assertThat(l.lines()).extracting(PieceLabelLayout.TextLine::row).containsExactly(
                PieceLabelLayout.Row.CODE, PieceLabelLayout.Row.TITLE, PieceLabelLayout.Row.VARIANT, PieceLabelLayout.Row.SKU);
            assertThat(l.lines()).extracting(PieceLabelLayout.TextLine::size).containsExactly(7.5f, 6f, 5.5f, 5f);
            assertThat(l.lines().get(0).runs()).allMatch(r -> r.face() == LabelFonts.Face.BOLD);
            assertThat(l.dropped()).isEmpty();
            assertThat(l.barcode().h()).isCloseTo(10f * PieceLabelLayout.MM, org.assertj.core.data.Offset.offset(0.01f));
            assertThat(l.barcode().x()).isCloseTo(3f * PieceLabelLayout.MM, org.assertj.core.data.Offset.offset(0.01f));
            assertThat(l.barcode().w()).isCloseTo((spec.widthMm() - 6f) * PieceLabelLayout.MM, org.assertj.core.data.Offset.offset(0.01f));
        }
    }

    @Test
    void l3_worstCaseAtStartingSizes_twoLineTitle_variant_sku_allFitAtTenMillimetres() {
        String twoLines = "Organic Cotton Oversized Heavyweight Hoodie Grey";
        PieceLabelLayout.Layout l = PieceLabelLayout.layout(PieceLabelLayout.Spec.of(40, 25), "P000002", twoLines, "Grey / XL", "HOOD-G-XL");
        assertThat(l.lines().stream().filter(t -> t.row() == PieceLabelLayout.Row.TITLE)).hasSize(2)
            .allMatch(t -> t.size() == 6f);
        assertThat(l.dropped()).isEmpty();
        assertSound(l, "worst case");
    }

    @Test
    void l4_blankSku_andDefaultTitle_rowsOmitted() {
        for (PieceLabelLayout.Spec spec : SIZES) {
            PieceLabelLayout.Layout l = PieceLabelLayout.layout(spec, "P000100", "Canvas Tote", "Default Title", "  ");
            assertThat(l.lines()).extracting(PieceLabelLayout.TextLine::row)
                .containsExactly(PieceLabelLayout.Row.CODE, PieceLabelLayout.Row.TITLE);
            PieceLabelLayout.Layout noVariant = PieceLabelLayout.layout(spec, "P000100", "Canvas Tote", null, "TOTE-1");
            assertThat(noVariant.lines()).extracting(PieceLabelLayout.TextLine::row)
                .containsExactly(PieceLabelLayout.Row.CODE, PieceLabelLayout.Row.TITLE, PieceLabelLayout.Row.SKU);
        }
    }

    @Test
    void l5_verticalFitOrder_titleShrinks_variantShrinks_titleOneLine_thenSkuDropped_variantLast() {
        String twoLines = "Organic Cotton Oversized Heavyweight Hoodie Grey";
        // 12 mm barcode: budget ≈ 27.3 pt — cutting the title to one line is enough; the variant stays.
        PieceLabelLayout.Layout at12 = PieceLabelLayout.layout(new PieceLabelLayout.Spec(40, 25, 12), "P1", twoLines, "Grey / XL", "SKU-1");
        assertThat(at12.dropped()).isEmpty();
        assertThat(at12.lines()).extracting(PieceLabelLayout.TextLine::row).containsExactly(
            PieceLabelLayout.Row.CODE, PieceLabelLayout.Row.TITLE, PieceLabelLayout.Row.VARIANT, PieceLabelLayout.Row.SKU);
        assertThat(at12.lines()).filteredOn(t -> t.row() == PieceLabelLayout.Row.TITLE).singleElement()
            .satisfies(t -> assertThat(t.logical()).endsWith(LabelTextFitter.ELLIPSIS));
        assertSound(at12, "12 mm");

        // 14 mm barcode: the SKU goes next — the variant is still kept.
        PieceLabelLayout.Layout at14 = PieceLabelLayout.layout(new PieceLabelLayout.Spec(40, 25, 14), "P1", twoLines, "Grey / XL", "SKU-1");
        assertThat(at14.dropped()).containsExactly(PieceLabelLayout.Row.SKU);
        assertThat(at14.lines()).extracting(PieceLabelLayout.TextLine::row).containsExactly(
            PieceLabelLayout.Row.CODE, PieceLabelLayout.Row.TITLE, PieceLabelLayout.Row.VARIANT);
        assertSound(at14, "14 mm");

        // 16 mm barcode: last resort — the variant goes; piece code + one title line always kept.
        PieceLabelLayout.Layout at16 = PieceLabelLayout.layout(new PieceLabelLayout.Spec(40, 25, 16), "P1", twoLines, "Grey / XL", "SKU-1");
        assertThat(at16.dropped()).containsExactly(PieceLabelLayout.Row.SKU, PieceLabelLayout.Row.VARIANT);
        assertThat(at16.lines()).extracting(PieceLabelLayout.TextLine::row)
            .containsExactly(PieceLabelLayout.Row.CODE, PieceLabelLayout.Row.TITLE);
        assertSound(at16, "16 mm");
    }

    @Test
    void l12_variantDroppedOnlyAfterSku_andAfterTheTitleIsOneLine_acrossBarcodeHeightsAndFixtures() {
        int variantDrops = 0;
        for (float w : new float[]{50f, 40f}) {
            for (float bh = 10f; bh <= 17f; bh += 0.25f) {
                for (String[] f : FIXTURES) {
                    PieceLabelLayout.Layout l = PieceLabelLayout.layout(new PieceLabelLayout.Spec(w, 25, bh), f[0], f[1], f[2], f[3]);
                    if (!l.dropped().contains(PieceLabelLayout.Row.VARIANT)) continue;
                    variantDrops++;
                    String what = w + "x25 @" + bh + " mm " + f[1];
                    assertThat(l.steps()).as(what).endsWith(PieceLabelLayout.Step.DROP_VARIANT);
                    if (f[3] != null) {
                        assertThat(l.dropped()).as(what + ": SKU went first").containsExactly(PieceLabelLayout.Row.SKU, PieceLabelLayout.Row.VARIANT);
                    }
                    assertThat(l.lines()).as(what + ": title already one line")
                        .filteredOn(t -> t.row() == PieceLabelLayout.Row.TITLE).hasSize(1);
                    assertThat(l.steps()).as(what).contains(PieceLabelLayout.Step.TITLE_ONE_LINE);
                    assertSound(l, what);
                }
            }
        }
        assertThat(variantDrops).as("the sweep reaches the variant-drop case").isGreaterThan(0);
    }

    @Test
    void l13_P000003_longArabic_keepsCode_oneTitleLineWithEllipsis_variant_andSku_bothSizes() {
        for (PieceLabelLayout.Spec spec : SIZES) {
            PieceLabelLayout.Layout l = PieceLabelLayout.layout(spec, "P000003", LabelTextFitterTest.LONG_AR, "أحمر / مقاس كبير", "BLZ-AR-RED-L");
            assertThat(l.lines()).extracting(PieceLabelLayout.TextLine::row).as(spec.toString()).containsExactly(
                PieceLabelLayout.Row.CODE, PieceLabelLayout.Row.TITLE, PieceLabelLayout.Row.VARIANT, PieceLabelLayout.Row.SKU);
            assertThat(l.lines().get(1).logical()).endsWith(LabelTextFitter.ELLIPSIS);
            assertThat(l.lines().get(2).logical()).isEqualTo("أحمر / مقاس كبير");
            assertThat(l.dropped()).isEmpty();
            assertSound(l, spec.toString());
        }
    }

    @Test
    void l6_variantShrinksToFloor_beforeItIsDropped() {
        // Budget ≈ 25.0 pt: code 8.25 + title 5.5 (at 5 pt) + SKU 5.5 leave room for the variant at
        // 5 pt (5.5) but not at 5.5 pt (6.05) — so step 2 applies and nothing after it.
        PieceLabelLayout.Spec tight = new PieceLabelLayout.Spec(50, 25, 12.83f);
        PieceLabelLayout.Layout l = PieceLabelLayout.layout(tight, "P1", "Linen Shirt", "Grey / XL", "SKU-1");
        assertThat(l.dropped()).isEmpty();
        assertThat(l.lines()).filteredOn(t -> t.row() == PieceLabelLayout.Row.TITLE).singleElement()
            .satisfies(t -> assertThat(t.size()).isEqualTo(5f));
        assertThat(l.lines()).filteredOn(t -> t.row() == PieceLabelLayout.Row.VARIANT).singleElement()
            .satisfies(t -> assertThat(t.size()).isEqualTo(5f));
        assertSound(l, "tight");
    }

    @Test
    void l7_everyRowCentredWithinTheTextWidth_regardlessOfScript() {
        for (PieceLabelLayout.Spec spec : SIZES) {
            for (String[] f : FIXTURES) {
                PieceLabelLayout.Layout l = PieceLabelLayout.layout(spec, f[0], f[1], f[2], f[3]);
                float left = 2f * PieceLabelLayout.MM, right = l.pageWidth() - 2f * PieceLabelLayout.MM;
                for (PieceLabelLayout.TextLine t : l.lines()) {
                    float centre = t.box().x() + t.box().w() / 2f;
                    assertThat(centre).as("%s %s '%s'", spec, t.row(), t.logical())
                        .isCloseTo((left + right) / 2f, org.assertj.core.data.Offset.offset(0.5f));
                    assertThat(t.box().x()).isGreaterThanOrEqualTo(left - 0.001f);
                    assertThat(t.box().x() + t.box().w()).isLessThanOrEqualTo(right + 0.001f);
                }
            }
        }
    }

    @Test
    void l8_consecutiveArabicLines_glyphInkBoxes_includingDotsAndHamza_neverIntersect() {
        String[][] arabic = {
            {"P1", LabelTextFitterTest.LONG_AR, "أحمر / مقاس كبير", "BLZ-AR-RED-L"},
            {"P2", "إصدار الخريف المحدود — بلوزة قطنية واسعة بأكمام طويلة", "أزرق / صغير", "SKU-2"},
            {"P3", "بِسْمِ اللَّهِ الرَّحْمَنِ الرَّحِيمِ عطر", "ٱلْأَحْمَر", "SKU-3"},   // harakat above/below
        };
        for (PieceLabelLayout.Spec spec : SIZES) {
            for (String[] f : arabic) {
                PieceLabelLayout.Layout l = PieceLabelLayout.layout(spec, f[0], f[1], f[2], f[3]);
                assertSound(l, spec + " " + f[1]);
                for (int i = 0; i + 1 < l.lines().size(); i++) {
                    PieceLabelLayout.TextLine a = l.lines().get(i), b = l.lines().get(i + 1);
                    float aBottom = a.baseline() + LabelFonts.ink(a.runs())[1] * a.size();
                    float bTop = b.baseline() + LabelFonts.ink(b.runs())[0] * b.size();
                    assertThat(bTop).as("%s: ink of '%s' vs '%s'", spec, a.logical(), b.logical())
                        .isLessThanOrEqualTo(aBottom + 0.001f);
                }
            }
        }
    }

    @Test
    void l9_arabicLineHeight_isTheLettersInkEnvelope_latinStays1point1() {
        assertThat(LabelFonts.ARABIC_LETTER_INK[0]).isCloseTo(1.010f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(LabelFonts.ARABIC_LETTER_INK[1]).isCloseTo(-0.421f, org.assertj.core.data.Offset.offset(0.001f));
        PieceLabelLayout.Layout l = PieceLabelLayout.layout(PieceLabelLayout.Spec.of(50, 25), "P1", "بلوزة قطنية", "Red / M", "SKU-1");
        PieceLabelLayout.TextLine ar = l.lines().get(1), lat = l.lines().get(2);
        assertThat(ar.height()).isCloseTo(1.431f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(lat.height()).isEqualTo(1.1f);
    }

    @Test
    void l10_stepsFired_areReported_inOrder() {
        String twoLines = "Organic Cotton Oversized Heavyweight Hoodie Grey";
        assertThat(PieceLabelLayout.layout(PieceLabelLayout.Spec.of(40, 25), "P1", "Linen Shirt", "White / M", "LIN").steps()).isEmpty();
        assertThat(PieceLabelLayout.layout(new PieceLabelLayout.Spec(40, 25, 12), "P1", twoLines, "Grey / XL", "SKU-1").steps())
            .containsExactly(PieceLabelLayout.Step.TITLE_TO_FLOOR, PieceLabelLayout.Step.VARIANT_TO_FLOOR, PieceLabelLayout.Step.TITLE_ONE_LINE);
        assertThat(PieceLabelLayout.layout(new PieceLabelLayout.Spec(40, 25, 16), "P1", twoLines, "Grey / XL", "SKU-1").steps())
            .containsExactly(PieceLabelLayout.Step.TITLE_TO_FLOOR, PieceLabelLayout.Step.VARIANT_TO_FLOOR,
                PieceLabelLayout.Step.TITLE_ONE_LINE, PieceLabelLayout.Step.DROP_SKU, PieceLabelLayout.Step.DROP_VARIANT);
    }

    @Test
    void l11_shortMixedVariant_fullTextNoEllipsis() {
        for (PieceLabelLayout.Spec spec : SIZES) {
            for (String variant : List.of("S / أحمر", "Red / أحمر", "أحمر / S", "S / أحمر…")) {
                PieceLabelLayout.Layout l = PieceLabelLayout.layout(spec, "P1", "عطر ورد & عود", variant, "PERF-ROSE-S");
                assertThat(l.lines()).filteredOn(t -> t.row() == PieceLabelLayout.Row.VARIANT).singleElement()
                    .satisfies(t -> assertThat(t.logical()).isEqualTo(variant));
                assertThat(l.ellipsized()).isFalse();
                assertThat(l.steps()).isEmpty();
            }
        }
    }

    static void assertSound(PieceLabelLayout.Layout l, String what) {
        float textW = l.pageWidth() - 4f * PieceLabelLayout.MM;
        List<PieceLabelLayout.Box> boxes = new ArrayList<>();
        boxes.add(l.barcode());
        for (PieceLabelLayout.TextLine t : l.lines()) {
            assertThat(t.width()).as("%s: '%s' width", what, t.logical()).isLessThanOrEqualTo(textW + 0.001f);
            assertThat(t.size()).as("%s: font floor", what).isGreaterThanOrEqualTo(5f);
            boxes.add(t.box());
        }
        for (PieceLabelLayout.Box b : boxes) {
            assertThat(b.inside(l.pageWidth(), l.pageHeight())).as("%s: %s inside page", what, b).isTrue();
        }
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                assertThat(boxes.get(i).intersects(boxes.get(j)))
                    .as("%s: %s overlaps %s", what, boxes.get(i), boxes.get(j)).isFalse();
            }
        }
        assertThat(l.lines()).as("%s: piece code and one title line always kept", what)
            .anyMatch(t -> t.row() == PieceLabelLayout.Row.CODE);
    }
}
