package com.traceability.inventory;

import com.google.zxing.NotFoundException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.traceability.inventory.RatTailLabelTest.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Rat-tail Flag B, second layout (2026-11-23): the name wraps to 2 lines (bold 6 pt; when it needs more, the whole name
 * shrinks in 0.25 pt steps to 5 pt, then "…"), then "variant | SKU" (5.5 pt), the lines centred in 1–11 mm — on the
 * piece label AND the separator.
 * Pure: no Spring, no database.
 */
class RatTailFlagBTest {

    static final String SNOWBOARD = "The Collection Snowboard: Liquid";
    static final String HYDROGEN = "The Collection Snowboard: Hydrogen";
    static final String ARABIC_LONG = "خَاتَمٌ مُذَهَّبٌ بِحَجَرٍ كَرِيمٍ أَزْرَقَ";
    static final String ARABIC_VARIANT = "فِضَّةٌ / ١٦";

    record Case(String what, String name, String variant, String sku) {}

    static final List<Case> CASES = List.of(
        new Case("long name", SNOWBOARD, "Default Title", "SB-LIQ-01"),
        new Case("latin full", "Gold Hoop Earrings", "Gold / Small", "GHE-S"),
        new Case("arabic 2-line + tashkeel", ARABIC_LONG, ARABIC_VARIANT, "RG-16"),
        new Case("no variant no sku", "Ring", "Default Title", null),
        new Case("variant only", "Ring", "Gold", null),
        new Case("sku only", "Ring", null, "RG-1"),
        new Case("three-line name", "Sterling Silver Handcrafted Infinity Charm Bracelet With Extender", "S", "X"));

    // ── wrapping ──────────────────────────────────────────────────────────────

    @Test
    void f1_long_name_wraps_to_two_lines_on_label_and_separator() {
        for (RatTailLabel.Layout l : List.of(
                RatTailLabel.piece("P008870", SNOWBOARD, "Default Title", "SB-LIQ-01"),
                RatTailLabel.separator(4, SNOWBOARD, "Default Title", "SB-LIQ-01"))) {
            List<RatTailLabel.Line> name = rows(l, RatTailLabel.Row.NAME);
            // "Snowboard: Liquid" is 19.5 mm at 6 pt (> 17.2): the whole name shrinks until it fits 2 lines, in full.
            assertThat(name).extracting(RatTailLabel.Line::logical).containsExactly("The Collection", "Snowboard: Liquid");
            assertThat(name.get(0).size()).isLessThanOrEqualTo(6f).isGreaterThanOrEqualTo(5f).isEqualTo(name.get(1).size());
            assertThat(l.ellipsized()).isFalse();
            for (RatTailLabel.Line n : name) assertThat(n.width()).isLessThanOrEqualTo(17.2f * MM + 1e-3f);
            assertThat(rows(l, RatTailLabel.Row.DETAIL)).singleElement()
                .satisfies(d -> assertThat(d.logical()).isEqualTo("SB-LIQ-01"));
            assertNoOverlap(l, "snowboard");
        }
    }

    @Test
    void f1b_hydrogen_ends_in_an_ellipsis_at_5_pt_on_label_and_separator() {
        for (RatTailLabel.Layout l : List.of(
                RatTailLabel.piece("P008870", HYDROGEN, "Default Title", "SB-HYD"),
                RatTailLabel.separator(4, HYDROGEN, "Default Title", "SB-HYD"))) {
            List<RatTailLabel.Line> name = rows(l, RatTailLabel.Row.NAME);
            assertThat(name).hasSize(2).allSatisfy(n -> assertThat(n.size()).isEqualTo(5f));
            assertThat(name.get(1).logical()).endsWith(LabelTextFitter.ELLIPSIS);
            assertThat(l.ellipsized()).isTrue();
            for (RatTailLabel.Line n : name) assertThat(n.width()).isLessThanOrEqualTo(17.2f * MM + 1e-3f);
        }
    }

    @Test
    void f2_shrinks_in_quarter_points_only_when_needed_and_ellipsizes_at_5() {
        RatTailLabel.Line normal = rows(RatTailLabel.piece("P1", "Gold Hoop Earrings", null, null), RatTailLabel.Row.NAME).get(0);
        assertThat(normal.size()).isEqualTo(6f);

        RatTailLabel.Layout word = RatTailLabel.piece("P1", "Supercalifragilisticexpi Ring", null, null);
        assertThat(rows(word, RatTailLabel.Row.NAME).get(0).size()).isLessThan(6f).isGreaterThanOrEqualTo(5f);
        // every size is a whole number of 0.25 pt steps below 6
        float liquid = rows(RatTailLabel.piece("P1", SNOWBOARD, null, null), RatTailLabel.Row.NAME).get(0).size();
        assertThat((6f - liquid) / 0.25f).isCloseTo(Math.round((6f - liquid) / 0.25f), within(1e-4f));

        RatTailLabel.Layout three = RatTailLabel.piece("P1",
            "Sterling Silver Handcrafted Infinity Charm Bracelet With Extender", "S", "X");
        List<RatTailLabel.Line> name = rows(three, RatTailLabel.Row.NAME);
        assertThat(name).hasSize(2);
        assertThat(name.get(1).logical()).endsWith(LabelTextFitter.ELLIPSIS);
        assertThat(name).allSatisfy(n -> assertThat(n.size()).isEqualTo(5f));
        assertThat(three.ellipsized()).isTrue();
        for (RatTailLabel.Line n : name) assertThat(n.width()).isLessThanOrEqualTo(17.2f * MM + 1e-3f);
    }

    @Test
    void f3_detail_line_is_variant_and_sku_joined() {
        assertThat(detail(RatTailLabel.piece("P1", "Ring", "Gold / 16", "RG-16"))).isEqualTo("Gold / 16 | RG-16");
        assertThat(detail(RatTailLabel.piece("P1", "Ring", "Gold", null))).isEqualTo("Gold");
        assertThat(detail(RatTailLabel.piece("P1", "Ring", "Default Title", "RG-1"))).isEqualTo("RG-1");
        assertThat(detail(RatTailLabel.piece("P1", "Ring", "  ", "RG-1"))).isEqualTo("RG-1");
        RatTailLabel.Line d = rows(RatTailLabel.piece("P1", "Ring", "Gold", "RG-1"), RatTailLabel.Row.DETAIL).get(0);
        assertThat(d.size()).isEqualTo(5.5f);

        RatTailLabel.Layout longDetail = RatTailLabel.piece("P1", "Ring",
            "Rose Gold Plated / Extra Large / Engraved", "RG-ROSE-XL-ENGRAVED");
        RatTailLabel.Line ld = rows(longDetail, RatTailLabel.Row.DETAIL).get(0);
        assertThat(ld.size()).isEqualTo(4.75f);
        assertThat(ld.logical()).endsWith(LabelTextFitter.ELLIPSIS);
        assertThat(ld.width()).isLessThanOrEqualTo(17.2f * MM + 1e-3f);
    }

    @Test
    void f4_no_variant_no_sku_is_the_name_centred_with_no_line_3() {
        RatTailLabel.Layout l = RatTailLabel.piece("P1", "Ring", "Default Title", null);
        assertThat(l.lines()).extracting(RatTailLabel.Line::row)
            .containsExactly(RatTailLabel.Row.CODE, RatTailLabel.Row.NAME);
        assertCentred(l);
        RatTailLabel.Layout sep = RatTailLabel.separator(2, "Ring", "Default Title", null);
        assertThat(sep.lines()).extracting(RatTailLabel.Line::row)
            .containsExactly(RatTailLabel.Row.COUNT, RatTailLabel.Row.NAME);
        assertCentred(sep);
    }

    @Test
    void f5_every_layout_is_centred_in_the_safe_area() {
        for (Case c : CASES) {
            assertCentred(RatTailLabel.piece("P008870", c.name(), c.variant(), c.sku()));
            assertCentred(RatTailLabel.separator(3, c.name(), c.variant(), c.sku()));
        }
    }

    // ── direction ─────────────────────────────────────────────────────────────

    @Test
    void f6_arabic_two_line_name_with_tashkeel_right_aligned_no_overlap() {
        for (RatTailLabel.Layout l : List.of(
                RatTailLabel.piece("P008870", ARABIC_LONG, ARABIC_VARIANT, "RG-16"),
                RatTailLabel.separator(5, ARABIC_LONG, ARABIC_VARIANT, "RG-16"))) {
            assertThat(rows(l, RatTailLabel.Row.NAME)).hasSize(2).allSatisfy(n -> assertThat(n.rtl()).isTrue());
            for (RatTailLabel.Line x : flagB(l)) {
                assertThat(x.x() + x.width()).as(x.logical()).isCloseTo(58.6f * MM, within(1e-3f));
            }
            RatTailLabel.Line d = rows(l, RatTailLabel.Row.DETAIL).get(0);
            assertThat(d.logical()).isEqualTo(ARABIC_VARIANT + " | RG-16");
            // the SKU stays Latin, in Latin order, at the visual left of the line
            String visual = String.join("", d.runs().stream().map(LabelFonts.Run::text).toList());
            assertThat(visual).startsWith("RG-16");
            assertNoOverlap(l, "arabic");
        }
    }

    @Test
    void f7_latin_lines_start_at_41_4() {
        RatTailLabel.Layout l = RatTailLabel.piece("P1", SNOWBOARD, "Gold", "SB-1");
        for (RatTailLabel.Line x : flagB(l)) assertThat(x.x()).isCloseTo(41.4f * MM, within(1e-3f));
    }

    // ── separators ────────────────────────────────────────────────────────────

    @Test
    void s1_separators_of_different_groups_read_differently() throws IOException {
        List<String[]> pairs = List.of(
            new String[]{SNOWBOARD, "Default Title", "SB-LIQ"}, new String[]{"The Collection Snowboard: Hydrogen", "Default Title", "SB-HYD"},
            new String[]{"Gold Ring", "Size 16", null}, new String[]{"Gold Ring", "Size 18", null},
            new String[]{"Gold Ring", "Default Title", "GR-16"}, new String[]{"Gold Ring", "Default Title", "GR-18"});
        for (int i = 0; i < pairs.size(); i += 2) {
            String a = flagBText(separator(3, pairs.get(i))), b = flagBText(separator(3, pairs.get(i + 1)));
            assertThat(a).as("separator Flag B").isNotBlank().isNotEqualTo(b);
        }
        assertThat(flagBText(separator(3, pairs.get(0)))).contains("The Collection").contains("SB-LIQ");
    }

    @Test
    void s2_flag_a_still_reads_exactly_times_n_and_no_barcode() throws IOException {
        byte[] pdf = separator(12, new String[]{SNOWBOARD, "Gold", "SB-1"});
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(flagAText(doc)).isEqualTo(RatTailLabel.countText(12));
            assertThat(ops(doc.getPage(0))).doesNotContain("re", "Do");
        }
        assertThatThrownBy(() -> decode(image(pdf, 0))).isInstanceOf(NotFoundException.class);
    }

    // ── regressions on every case ─────────────────────────────────────────────

    @Test
    void r1_tail_empty_safe_area_held_label_decodes_separator_does_not() throws Exception {
        for (Case c : CASES) {
            byte[] label = LabelPdfRenderer.render(List.of(new LabelPdfRenderer.Piece("P008870", c.name(), c.variant(), c.sku())), RAT_TAIL);
            assertTailEmpty(label, c.what());
            assertSafeArea(image(label, 0), c.what());
            assertThat(decode(image(label, 0)).getText()).isEqualTo("P008870");
            assertNoOverlap(RatTailLabel.piece("P008870", c.name(), c.variant(), c.sku()), c.what());

            byte[] sep = separator(7, new String[]{c.name(), c.variant(), c.sku()});
            assertTailEmpty(sep, c.what() + " separator");
            assertSafeArea(image(sep, 0), c.what() + " separator");
            assertThatThrownBy(() -> decode(image(sep, 0))).as(c.what()).isInstanceOf(NotFoundException.class);
            assertNoOverlap(RatTailLabel.separator(7, c.name(), c.variant(), c.sku()), c.what() + " separator");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static byte[] separator(int count, String[] t) throws IOException {
        return LabelPdfRenderer.renderPages(List.of(new LabelPdfRenderer.Separator(t[0], t[1], count, t[2])), RAT_TAIL);
    }

    static List<RatTailLabel.Line> rows(RatTailLabel.Layout l, RatTailLabel.Row row) {
        return l.lines().stream().filter(x -> x.row() == row).toList();
    }

    static List<RatTailLabel.Line> flagB(RatTailLabel.Layout l) {
        return l.lines().stream().filter(x -> x.row() == RatTailLabel.Row.NAME || x.row() == RatTailLabel.Row.DETAIL).toList();
    }

    static String detail(RatTailLabel.Layout l) {
        return rows(l, RatTailLabel.Row.DETAIL).stream().findFirst().map(RatTailLabel.Line::logical).orElse(null);
    }

    /** The Flag B lines' ink envelope block is centred on 6 mm and inside 1–11 mm. */
    static void assertCentred(RatTailLabel.Layout l) {
        List<RatTailLabel.Line> lines = flagB(l);
        boolean arabic = lines.stream().anyMatch(x -> LabelFonts.containsArabic(x.logical()));
        float[] env = arabic ? RatTailLabel.ARABIC_ENVELOPE : RatTailLabel.LATIN_ENVELOPE;
        RatTailLabel.Line first = lines.get(0), last = lines.get(lines.size() - 1);
        float top = first.baseline() + env[0] * first.size(), bottom = last.baseline() - env[1] * last.size();
        assertThat((top + bottom) / 2).as("block centre").isCloseTo(6 * MM, within(1e-2f));
        assertThat(top).isLessThanOrEqualTo(11 * MM + 1e-3f);
        assertThat(bottom).isGreaterThanOrEqualTo(1 * MM - 1e-3f);
    }

    /** The glyphs drawn on Flag B (x > 40 mm), top line first, each line in x order. */
    static String flagBText(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            List<TextPosition> glyphs = new ArrayList<>();
            PDFTextStripper strip = new PDFTextStripper() {
                @Override protected void processTextPosition(TextPosition t) { glyphs.add(t); }
            };
            strip.getText(doc);
            StringBuilder b = new StringBuilder();
            glyphs.stream().filter(t -> t.getX() > 40 * MM)
                .sorted(Comparator.comparing((TextPosition t) -> Math.round(t.getY())).thenComparing(TextPosition::getX))
                .forEach(t -> b.append(t.getUnicode()));
            return b.toString();
        }
    }
}
