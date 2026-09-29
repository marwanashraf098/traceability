package com.traceability.inventory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-glyph font fallback: Arabic script goes to NotoSansArabic, everything else — Latin,
 * digits, "/", "&", "…", Cyrillic — to NotoSans; a code point no font has becomes "?".
 */
class LabelFontsTest {

    @Test
    void f1_slashAmpersandEllipsisNextToArabic_neverInAnArabicRun() {
        String visual = LabelService.shapeForDisplay("S / أحمر & أزرق…");
        List<LabelFonts.Run> runs = LabelFonts.runs(visual, LabelFonts.Face.REGULAR);

        for (LabelFonts.Run r : runs) {
            if (r.face() == LabelFonts.Face.ARABIC) {
                assertThat(r.text().codePoints().allMatch(LabelFonts::isArabicScript))
                    .as("Arabic run holds Arabic script only: '%s'", r.text()).isTrue();
            }
            assertThat(r.text().codePoints().allMatch(cp -> LabelFonts.has(r.face(), cp)))
                .as("every glyph exists in the run's font: '%s' %s", r.text(), r.face()).isTrue();
        }
        String latin = runs.stream().filter(r -> r.face() == LabelFonts.Face.REGULAR)
            .map(LabelFonts.Run::text).reduce("", String::concat);
        assertThat(latin).contains("/").contains("&").contains("…").contains("S");
        assertThat(runs).anyMatch(r -> r.face() == LabelFonts.Face.ARABIC);
    }

    @Test
    void f2_latinDigitsCyrillic_stayInTheLatinFace_boldToo() {
        for (LabelFonts.Face face : List.of(LabelFonts.Face.REGULAR, LabelFonts.Face.BOLD)) {
            List<LabelFonts.Run> runs = LabelFonts.runs("Платье 1KG / Red & Blue…", face);
            assertThat(runs).hasSize(1);
            assertThat(runs.get(0).face()).isEqualTo(face);
            assertThat(runs.get(0).text()).isEqualTo("Платье 1KG / Red & Blue…");
        }
    }

    @Test
    void f3_codePointNoFontHas_becomesQuestionMark_neverThrows() {
        List<LabelFonts.Run> runs = LabelFonts.runs("A中B", LabelFonts.Face.REGULAR);
        assertThat(runs).containsExactly(new LabelFonts.Run("A?B", LabelFonts.Face.REGULAR));
    }

    @Test
    void f4_arabicDigitsAndArabicComma_goToTheArabicFont() {
        List<LabelFonts.Run> runs = LabelFonts.runs("١٢،", LabelFonts.Face.REGULAR);
        assertThat(runs).containsExactly(new LabelFonts.Run("١٢،", LabelFonts.Face.ARABIC));
    }

    @Test
    void f5_widthMatchesPdfBoxsOwnStringWidth_forEachFace() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            LabelFonts.Loaded fonts = new LabelFonts.Loaded(doc);
            for (var c : List.of(new String[]{"Organic Hoodie 1KG / XXL", "REGULAR"}, new String[]{"P002224", "BOLD"},
                                 new String[]{LabelService.shapeForDisplay("بلوزة قطنية"), "ARABIC"})) {
                LabelFonts.Face face = LabelFonts.Face.valueOf(c[1]);
                float ours = LabelFonts.width(List.of(new LabelFonts.Run(c[0], face)), 10f);
                float pdfbox = fonts.font(face).getStringWidth(c[0]) / 1000f * 10f;
                assertThat(ours).as(c[1]).isGreaterThan(0f).isCloseTo(pdfbox, org.assertj.core.data.Offset.offset(0.01f));
            }
        }
    }
}
