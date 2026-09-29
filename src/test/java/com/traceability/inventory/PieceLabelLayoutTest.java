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
    void l5_verticalFitOrder_titleShrinks_thenVariantShrinks_thenVariantDropped_thenTitleOneLine_thenSkuDropped() {
        String twoLines = "Organic Cotton Oversized Heavyweight Hoodie Grey";
        // 12 mm barcode: budget ≈ 27.3 pt; 2-line title at 5 pt + code + sku doesn't leave room for the variant.
        PieceLabelLayout.Layout at12 = PieceLabelLayout.layout(new PieceLabelLayout.Spec(40, 25, 12), "P1", twoLines, "Grey / XL", "SKU-1");
        assertThat(at12.dropped()).containsExactly(PieceLabelLayout.Row.VARIANT);
        assertThat(at12.lines()).filteredOn(t -> t.row() == PieceLabelLayout.Row.TITLE).hasSize(2).allMatch(t -> t.size() == 5f);
        assertSound(at12, "12 mm");

        // 14 mm barcode: the title is cut to one line with "…" before the SKU goes.
        PieceLabelLayout.Layout at14 = PieceLabelLayout.layout(new PieceLabelLayout.Spec(40, 25, 14), "P1", twoLines, "Grey / XL", "SKU-1");
        assertThat(at14.dropped()).containsExactly(PieceLabelLayout.Row.VARIANT);
        assertThat(at14.lines()).filteredOn(t -> t.row() == PieceLabelLayout.Row.TITLE).hasSize(1)
            .allMatch(t -> t.logical().endsWith(LabelTextFitter.ELLIPSIS));
        assertThat(at14.lines()).anyMatch(t -> t.row() == PieceLabelLayout.Row.SKU);
        assertSound(at14, "14 mm");

        // 16 mm barcode: last resort — the SKU goes; piece code + one title line always kept.
        PieceLabelLayout.Layout at16 = PieceLabelLayout.layout(new PieceLabelLayout.Spec(40, 25, 16), "P1", twoLines, "Grey / XL", "SKU-1");
        assertThat(at16.dropped()).containsExactly(PieceLabelLayout.Row.VARIANT, PieceLabelLayout.Row.SKU);
        assertThat(at16.lines()).extracting(PieceLabelLayout.TextLine::row)
            .containsExactly(PieceLabelLayout.Row.CODE, PieceLabelLayout.Row.TITLE);
        assertSound(at16, "16 mm");
    }

    @Test
    void l6_variantShrinksToFloor_beforeItIsDropped() {
        // Budget ≈ 25.0 pt: code 8.25 + title 5.5 (at 5 pt) + SKU 5.5 leave room for the variant at
        // 5 pt (5.5) but not at 5.5 pt (6.05) — so step 2 applies and step 3 doesn't.
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
    void l7_arabicLinesRightAligned_latinLeftAligned_codeCentred() {
        PieceLabelLayout.Layout l = PieceLabelLayout.layout(PieceLabelLayout.Spec.of(50, 25), "P002221", "بلوزة قطنية", "Red / M", "BLZ-1");
        float right = l.pageWidth() - 2f * PieceLabelLayout.MM;
        PieceLabelLayout.TextLine code = l.lines().get(0), title = l.lines().get(1), variant = l.lines().get(2);
        assertThat(code.x() + code.width() / 2f).isCloseTo(l.pageWidth() / 2f, org.assertj.core.data.Offset.offset(0.01f));
        assertThat(title.x() + title.width()).isCloseTo(right, org.assertj.core.data.Offset.offset(0.01f));
        assertThat(variant.x()).isCloseTo(2f * PieceLabelLayout.MM, org.assertj.core.data.Offset.offset(0.01f));
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
