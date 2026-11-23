package com.traceability.inventory;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.oned.Code128Reader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * The jewelry rat-tail label (60 × 12 mm) — RatTailLabel geometry and LabelPdfRenderer's rat-tail pages. Pure: no
 * Spring, no DB. Pixels are read from the real PDF rendered by PDFBox at 300 dpi (the printer's resolution).
 *
 *   e1  encoding: P0000123 → 101 modules, codewords Start B, P, 0, CODE C, 00, 01, 23 (+ check 6, stop); P008870 →
 *       90; P + 8 → 101; P + 9 → 112 (documented: doesn't fit the 101-module budget)
 *   e2  bars: whole modules of 0.48 pt, start on the 0.24 pt dot grid, centred in Flag A by module count, y 4.3–10.7
 *   r1  rendered at 300 dpi the barcode decodes to the exact P-code (P + 6, P + 7, Latin and Arabic labels)
 *   r2  at 300 dpi every bar is a whole multiple of 2 px and the symbol is exactly modules × 2 px wide
 *   r3  nothing drawn in the tail (x 20–40 mm): no rectangle or glyph in the content stream, every pixel white
 *   r4  every inked pixel is ≥ 1 mm inside a flag (Flag A x 1–19, Flag B x 41–59, y 1–11)
 *   r5  Flag B lines' ink never overlaps — Latin, Arabic with tashkeel, mixed, no variant
 *   r6  truncation: a long name → 2 lines at 5 pt + "…", a long "variant | SKU" → 4.75 pt + "…"; all within
 *       17.2 mm; a short name stays 6 pt, untouched
 *   r7  direction: Latin lines start at 41.4 mm; with Arabic in the name or variant every Flag B line ends at 58.6 mm
 *       (the SKU stays Latin, at the visual left of line 3); the lines are centred in 1–11 mm; "Default Title" hidden
 *       → name + SKU; the code row unchanged
 *   r8  one 60 × 12 page per label, vector bars (no image), Flag A text exactly the code, Flag B exactly name +
 *       "variant | SKU" (no price)
 *   s1  separator: no barcode (no rectangle, the decoder finds nothing at 300 dpi); Flag A text exactly "× N"; Flag B
 *       the group's name + "variant | SKU"; centred; Arabic right-aligned; tail empty; safe area
 *   g1  at 50 × 25 a separator with a count draws exactly what the count-less separator draws (header pages
 *       unchanged); a rat-tail page refuses a place sticker and a count-less separator
 */
class RatTailLabelTest {

    static final float MM = RatTailLabel.MM;
    static final PieceLabelLayout.Spec RAT_TAIL = PieceLabelLayout.Spec.of(60, 12);
    static final float PX_PER_MM = 300f / 25.4f;

    static final String LONG_NAME = "Sterling Silver Handcrafted Infinity Charm Bracelet With Extender";
    static final String TASHKEEL_NAME = "خَاتَمٌ مُذَهَّبٌ";
    static final String TASHKEEL_VARIANT = "قِلَادَةٌ فِضِّيَّةٌ";

    record Sample(String code, String name, String variant, String sku) {}

    static final List<Sample> SAMPLES = List.of(
        new Sample("P008870", "Gold Hoop Earrings", "Small / Yellow Gold", "EAR-HOOP-S-YG"),
        new Sample("P0000123", "Gold Hoop Earrings", "Small / Yellow Gold", "EAR-HOOP-S-YG"),
        new Sample("P008871", "خاتم فضة", "مقاس 7", "RING-925-7"),
        new Sample("P008872", TASHKEEL_NAME, TASHKEEL_VARIANT, "NK-ÅÉ-01"),
        new Sample("P008873", "Ring خاتم", "Size 7", "RING-MIX-7"),
        new Sample("P008874", LONG_NAME, "Rose Gold Plated Medium Adjustable Extra Long Chain", "BRC-INF-RG-M-XL-2026"),
        new Sample("P008875", "Ågjpq Pendant", "Default Title", "PEN-01"),
        new Sample("P008876", "سوار ذهب", "Default Title", "BR-01"),
        new Sample("P008877", "Anklet", "", null));

    // ── e: encoding + bars ────────────────────────────────────────────────────

    @Test
    void e1_encoding_is_the_compact_pattern() throws Exception {
        boolean[] seven = RatTailLabel.modules("P0000123");
        assertThat(seven).hasSize(101);
        assertThat(codewords(seven)).containsExactly(104, 48, 16, 99, 0, 1, 23, 6, 106);   // StartB P 0 CodeC 00 01 23 chk stop
        assertThat(RatTailLabel.modules("P008870")).hasSize(90);
        assertThat(codewords(RatTailLabel.modules("P008870"))).startsWith(104, 48, 99, 0, 88, 70);
        assertThat(RatTailLabel.modules("P00000123")).hasSize(101);
        assertThat(RatTailLabel.modules("P000000123")).as("P + 9 digits — over the 101-module budget").hasSize(112);
    }

    @Test
    void e2_bars_whole_modules_on_the_dot_grid_centred() {
        for (String code : List.of("P008870", "P0000123", "P00000123")) {
            RatTailLabel.Bars b = RatTailLabel.bars(code);
            float dots = b.x() / RatTailLabel.DOT_PT;
            assertThat(Math.abs(dots - Math.round(dots))).as(code + " start on the dot grid").isLessThan(1e-3f);
            for (RatTailLabel.Bar bar : b.bars()) {
                float mods = bar.w() / RatTailLabel.MODULE_PT;
                assertThat(Math.abs(mods - Math.round(mods))).isLessThan(1e-3f);
                float off = (bar.x() - b.x()) / RatTailLabel.MODULE_PT;
                assertThat(Math.abs(off - Math.round(off))).isLessThan(1e-3f);
            }
            float left = b.x(), right = 20 * MM - (b.x() + b.width());
            assertThat(Math.abs(left - right)).as(code + " centred").isLessThanOrEqualTo(RatTailLabel.DOT_PT * 1.001f);
            assertThat(b.x()).isGreaterThanOrEqualTo(1 * MM);
            assertThat(b.x() + b.width()).isLessThanOrEqualTo(19 * MM);
            assertThat(b.y()).isCloseTo(4.3f * MM, within(1e-3f));
            assertThat(b.y() + b.h()).isCloseTo(10.7f * MM, within(1e-3f));
        }
        assertThat(RatTailLabel.bars("P008870").width()).isCloseTo(90 * 0.48f, within(1e-3f));
        assertThat(RatTailLabel.bars("P0000123").width()).isCloseTo(101 * 0.48f, within(1e-3f));
    }

    // ── r: rendered pages ─────────────────────────────────────────────────────

    @Test
    void r1_decodes_to_the_exact_code_at_300_dpi() throws Exception {
        for (Sample s : SAMPLES) {
            BufferedImage img = image(pdf(s), 0);
            assertThat(decode(img).getText()).as(s.toString()).isEqualTo(s.code());
        }
    }

    @Test
    void r2_every_bar_is_a_whole_multiple_of_2_px() throws Exception {
        for (String code : List.of("P008870", "P0000123")) {
            BufferedImage img = image(pdf(new Sample(code, "Ring", "Size 7", "R-7")), 0);
            int row = rowAt(7.5f);
            List<int[]> runs = new ArrayList<>();          // {start, length, dark?}
            int x = 0, w = px(20);
            while (x < w) {
                boolean dark = gray(img, x, row) < 128;
                int start = x;
                while (x < w && (gray(img, x, row) < 128) == dark) x++;
                runs.add(new int[]{start, x - start, dark ? 1 : 0});
            }
            int first = runs.stream().filter(r -> r[2] == 1).findFirst().orElseThrow()[0];
            int[] last = runs.stream().filter(r -> r[2] == 1).reduce((a, b) -> b).orElseThrow();
            for (int[] r : runs) {
                if (r[0] >= first && r[0] < last[0] + last[1]) assertThat(r[1] % 2).as(code + " run at " + r[0]).isZero();
            }
            assertThat(last[0] + last[1] - first).isEqualTo(RatTailLabel.modules(code).length * 2);
            for (int y = rowAt(10.6f); y <= rowAt(4.4f); y++) {
                for (int xx = 0; xx < w; xx++) {
                    int g = gray(img, xx, y);
                    assertThat(g == 0 || g == 255).as(code + " no resampled grey at " + xx + "," + y).isTrue();
                }
            }
        }
    }

    @Test
    void r3_nothing_drawn_in_the_tail() throws Exception {
        for (Sample s : SAMPLES) assertTailEmpty(pdf(s), s.toString());
        assertTailEmpty(separatorPdf(12, LONG_NAME, "Rose Gold Plated Medium Adjustable"), "separator");
        assertTailEmpty(separatorPdf(3, TASHKEEL_NAME, TASHKEEL_VARIANT), "separator ar");
    }

    @Test
    void r4_ink_stays_inside_the_safe_area() throws Exception {
        for (Sample s : SAMPLES) assertSafeArea(image(pdf(s), 0), s.toString());
        assertSafeArea(image(separatorPdf(9999, LONG_NAME, "Rose Gold Plated Medium Adjustable"), 0), "separator");
        assertSafeArea(image(separatorPdf(1, TASHKEEL_NAME, TASHKEEL_VARIANT), 0), "separator ar");
    }

    @Test
    void r5_flag_b_lines_never_overlap() {
        for (Sample s : SAMPLES) {
            assertNoOverlap(RatTailLabel.piece(s.code(), s.name(), s.variant(), s.sku()), s.toString());
        }
        assertNoOverlap(RatTailLabel.separator(4, TASHKEEL_NAME, TASHKEEL_VARIANT), "separator ar");
        assertNoOverlap(RatTailLabel.separator(4, LONG_NAME, "Ågjpq"), "separator");
    }

    @Test
    void r6_truncation() {
        RatTailLabel.Layout l = RatTailLabel.piece("P008874", LONG_NAME,
            "Rose Gold Plated Medium Adjustable Extra Long Chain", "BRC-INF-RG-M-XL-2026-SPECIAL-EDITION");
        List<RatTailLabel.Line> name = rows(l, RatTailLabel.Row.NAME);
        RatTailLabel.Line detail = line(l, RatTailLabel.Row.DETAIL);
        assertThat(name).hasSize(2).allSatisfy(n -> assertThat(n.size()).isEqualTo(5f));
        assertThat(name.get(0).logical()).startsWith("Sterling Silver");
        assertThat(name.get(1).logical()).endsWith("…");
        assertThat(detail.size()).isEqualTo(4.75f);
        assertThat(detail.logical()).startsWith("Rose Gold").endsWith("…");
        assertThat(l.ellipsized()).isTrue();
        for (RatTailLabel.Line x : flagB(l)) assertThat(x.width()).isLessThanOrEqualTo(17.2f * MM + 1e-3f);

        RatTailLabel.Layout shortName = RatTailLabel.piece("P1", "Ring", "S", "X");
        assertThat(rows(shortName, RatTailLabel.Row.NAME)).singleElement()
            .satisfies(n -> assertThat(n.size()).isEqualTo(6f))
            .satisfies(n -> assertThat(n.logical()).isEqualTo("Ring"));
        assertThat(shortName.ellipsized()).isFalse();
    }

    @Test
    void r7_direction_alignment_and_centring() {
        RatTailLabel.Layout ltr = RatTailLabel.piece("P008870", "Gold Hoop", "Small", "EAR-S");
        assertThat(ltr.lines()).extracting(RatTailLabel.Line::row)
            .containsExactly(RatTailLabel.Row.CODE, RatTailLabel.Row.NAME, RatTailLabel.Row.DETAIL);
        for (RatTailLabel.Line x : flagB(ltr)) assertThat(x.x()).isCloseTo(41.4f * MM, within(1e-3f));
        assertThat(line(ltr, RatTailLabel.Row.DETAIL).logical()).isEqualTo("Small | EAR-S");
        RatTailFlagBTest.assertCentred(ltr);

        RatTailLabel.Layout rtl = RatTailLabel.piece("P008871", "خاتم فضة", "مقاس 7", "RING-925-7");
        for (RatTailLabel.Line x : flagB(rtl)) {
            assertThat(x.x() + x.width()).as(x.row() + " right edge").isCloseTo(58.6f * MM, within(1e-3f));
        }
        assertThat(line(rtl, RatTailLabel.Row.NAME).rtl()).isTrue();
        RatTailLabel.Line detail = line(rtl, RatTailLabel.Row.DETAIL);
        assertThat(detail.logical()).isEqualTo("مقاس 7 | RING-925-7");
        assertThat(detail.rtl()).isTrue();
        assertThat(detail.runs().get(0).text()).as("the SKU stays Latin, at the visual left").startsWith("RING-925-7");
        RatTailFlagBTest.assertCentred(rtl);

        // Arabic only in the variant → an Arabic label: Arabic line spacing, every line right-aligned.
        RatTailLabel.Layout mixed = RatTailLabel.piece("P1", "Gold Ring", "مقاس 7", "R7");
        for (RatTailLabel.Line x : flagB(mixed)) assertThat(x.x() + x.width()).isCloseTo(58.6f * MM, within(1e-3f));
        RatTailFlagBTest.assertCentred(mixed);

        RatTailLabel.Layout noVariant = RatTailLabel.piece("P008875", "Pendant", "Default Title", "PEN-01");
        assertThat(noVariant.lines()).extracting(RatTailLabel.Line::row)
            .containsExactly(RatTailLabel.Row.CODE, RatTailLabel.Row.NAME, RatTailLabel.Row.DETAIL);
        assertThat(line(noVariant, RatTailLabel.Row.DETAIL).logical()).isEqualTo("PEN-01");
        RatTailFlagBTest.assertCentred(noVariant);

        RatTailLabel.Line code = line(ltr, RatTailLabel.Row.CODE);
        assertThat(code.size()).isEqualTo(5.5f);
        assertThat(code.baseline()).isCloseTo(1.75f * MM, within(1e-3f));
        assertThat(code.x() + code.width() / 2).isCloseTo(10 * MM, within(1e-3f));
        assertThat(code.runs()).allMatch(r -> r.face() == LabelFonts.Face.REGULAR);
    }

    @Test
    void r8_one_60x12_page_per_label_vector_bars_no_price() throws Exception {
        List<LabelPdfRenderer.Piece> pieces = List.of(
            new LabelPdfRenderer.Piece("P008870", "Gold Hoop Earrings", "Small", "EAR-S"),
            new LabelPdfRenderer.Piece("P008871", "Gold Hoop Earrings", "Large", "EAR-L"),
            new LabelPdfRenderer.Piece("P008872", "Anklet", "Default Title", "ANK"));
        byte[] pdf = LabelPdfRenderer.render(pieces, RAT_TAIL);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(3);
            for (PDPage page : doc.getPages()) {
                assertThat(page.getMediaBox().getWidth()).isCloseTo(60 * MM, within(0.01f));
                assertThat(page.getMediaBox().getHeight()).isCloseTo(12 * MM, within(0.01f));
                for (var name : page.getResources().getXObjectNames()) {
                    assertThat(page.getResources().isImageXObject(name)).as("vector bars, no bitmap").isFalse();
                }
            }
            assertThat(areaText(doc, 0, 0, 20)).isEqualTo("P008870");
            assertThat(areaText(doc, 0, 40, 60)).isEqualTo("Gold Hoop Earrings Small | EAR-S");
            assertThat(areaText(doc, 2, 0, 20)).isEqualTo("P008872");
            assertThat(areaText(doc, 2, 40, 60)).isEqualTo("Anklet ANK");
        }
    }

    // ── s: separator ──────────────────────────────────────────────────────────

    @Test
    void s1_separator_count_only_no_barcode() throws Exception {
        byte[] pdf = separatorPdf(12, "Gold Hoop Earrings", "Small / Yellow Gold");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            assertThat(ops(doc.getPage(0))).as("no rectangle at all").doesNotContain("re");
            assertThat(flagAText(doc)).isEqualTo("× 12");
            assertThat(areaText(doc, 0, 0, 20)).isEqualTo("× 12");
            assertThat(areaText(doc, 0, 40, 60)).isEqualTo("Gold Hoop Earrings Small / Yellow Gold");
        }
        BufferedImage img = image(pdf, 0);
        assertThatThrownBy(() -> decode(img)).isInstanceOf(NotFoundException.class);

        try (PDDocument doc = Loader.loadPDF(LabelPdfRenderer.renderPages(
                List.of(new LabelPdfRenderer.Separator("Gold Hoop Earrings", "Small", 4, "EAR-S")), RAT_TAIL))) {
            assertThat(areaText(doc, 0, 0, 20)).isEqualTo("× 4");
            assertThat(areaText(doc, 0, 40, 60)).as("the group's own line 3").isEqualTo("Gold Hoop Earrings Small | EAR-S");
        }

        RatTailLabel.Layout ltr = RatTailLabel.separator(12, "Gold Hoop Earrings", "Small");
        assertThat(ltr.bars()).isNull();
        assertThat(ltr.lines()).extracting(RatTailLabel.Line::row).first().isEqualTo(RatTailLabel.Row.COUNT);
        assertThat(ltr.lines()).extracting(RatTailLabel.Line::row).last().isEqualTo(RatTailLabel.Row.DETAIL);
        RatTailLabel.Line count = line(ltr, RatTailLabel.Row.COUNT);
        assertThat(count.logical()).isEqualTo("× 12");
        assertThat(count.size()).isEqualTo(9f);
        assertThat(count.runs()).allMatch(r -> r.face() == LabelFonts.Face.BOLD);
        assertThat(count.baseline()).isCloseTo(4.8f * MM, within(1e-3f));
        assertThat(count.x() + count.width() / 2).isCloseTo(10 * MM, within(1e-3f));
        RatTailFlagBTest.assertCentred(ltr);

        RatTailLabel.Layout rtl = RatTailLabel.separator(3, "خاتم فضة", "مقاس 7");
        for (RatTailLabel.Line x : flagB(rtl)) assertThat(x.x() + x.width()).isCloseTo(58.6f * MM, within(1e-3f));
        RatTailFlagBTest.assertCentred(rtl);

        try (PDDocument doc = Loader.loadPDF(separatorPdf(7, "Ring", "Default Title"))) {
            assertThat(areaText(doc, 0, 0, 20)).isEqualTo("× 7");
            assertThat(areaText(doc, 0, 40, 60)).isEqualTo("Ring");
        }
        assertThatThrownBy(() -> RatTailLabel.separator(0, "Ring", null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ── g: other sizes untouched ──────────────────────────────────────────────

    @Test
    void g1_other_sizes_ignore_the_count_and_rat_tail_refuses_other_pages() throws Exception {
        for (PieceLabelLayout.Spec spec : List.of(PieceLabelLayout.Spec.of(50, 25), PieceLabelLayout.Spec.of(40, 25))) {
            byte[] counted = LabelPdfRenderer.renderPages(List.of(
                new LabelPdfRenderer.Separator("Linen Shirt", "Blue", 3),
                new LabelPdfRenderer.Piece("P000001", "Linen Shirt", "Blue", "SKU")), spec);
            byte[] plain = LabelPdfRenderer.renderPages(List.of(
                new LabelPdfRenderer.Separator("Linen Shirt", "Blue"),
                new LabelPdfRenderer.Piece("P000001", "Linen Shirt", "Blue", "SKU")), spec);
            try (PDDocument a = Loader.loadPDF(counted); PDDocument b = Loader.loadPDF(plain)) {
                for (int i = 0; i < 2; i++) {
                    assertThat(a.getPage(i).getMediaBox().getWidth()).isCloseTo(spec.widthMm() * MM, within(0.01f));
                    assertThat(a.getPage(i).getContents().readAllBytes()).isEqualTo(b.getPage(i).getContents().readAllBytes());
                }
                assertThat(new PDFTextStripper().getText(a)).doesNotContain("×");
            }
        }
        assertThatThrownBy(() -> LabelPdfRenderer.renderPages(
            List.of(new LabelPdfRenderer.Location("Q000001", "A-1", "Shelf A")), RAT_TAIL))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LabelPdfRenderer.renderPages(
            List.of(new LabelPdfRenderer.Separator("#1001", null)), RAT_TAIL))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(RatTailLabel.is(PieceLabelLayout.Spec.of(60, 12))).isTrue();
        assertThat(RatTailLabel.is(PieceLabelLayout.Spec.of(50, 25))).isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static byte[] pdf(Sample s) throws IOException {
        return LabelPdfRenderer.render(List.of(new LabelPdfRenderer.Piece(s.code(), s.name(), s.variant(), s.sku())), RAT_TAIL);
    }

    static byte[] separatorPdf(int count, String name, String variant) throws IOException {
        return LabelPdfRenderer.renderPages(List.of(new LabelPdfRenderer.Separator(name, variant, count)), RAT_TAIL);
    }

    static BufferedImage image(byte[] pdf, int page) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFRenderer(doc).renderImageWithDPI(page, 300, ImageType.GRAY);
        }
    }

    static Result decode(BufferedImage img) throws NotFoundException {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.CODE_128));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        return new MultiFormatReader().decode(
            new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img))), hints);
    }

    static int gray(BufferedImage img, int x, int y) {
        return img.getRaster().getSample(x, y, 0);
    }

    static int px(float mm) { return Math.round(mm * PX_PER_MM); }

    /** Image row whose centre is {@code mmFromBottom} above the page bottom. */
    static int rowAt(float mmFromBottom) { return (int) Math.floor((12 - mmFromBottom) * PX_PER_MM); }

    static void assertTailEmpty(byte[] pdf, String what) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            float lo = 20 * MM, hi = 40 * MM;
            List<Object> tokens = tokens(doc.getPage(0));
            for (int i = 0; i < tokens.size(); i++) {
                if (tokens.get(i) instanceof Operator op && op.getName().equals("re")) {
                    float x = ((COSNumber) tokens.get(i - 4)).floatValue(), w = ((COSNumber) tokens.get(i - 2)).floatValue();
                    assertThat(x + w <= lo + 1e-3f || x >= hi - 1e-3f).as(what + ": rectangle in the tail").isTrue();
                }
            }
            List<TextPosition> glyphs = new ArrayList<>();
            PDFTextStripper strip = new PDFTextStripper() {
                @Override protected void processTextPosition(TextPosition t) { glyphs.add(t); }
            };
            strip.getText(doc);
            assertThat(glyphs).as(what + " has text").isNotEmpty();
            for (TextPosition t : glyphs) {
                assertThat(t.getX() + t.getWidth() <= lo + 1e-3f || t.getX() >= hi - 1e-3f)
                    .as(what + ": glyph '" + t.getUnicode() + "' in the tail").isTrue();
            }
        }
        BufferedImage img = image(pdf, 0);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = (int) Math.ceil(20 * PX_PER_MM); x < (int) Math.floor(40 * PX_PER_MM); x++) {
                assertThat(gray(img, x, y)).as(what + ": tail pixel " + x + "," + y).isEqualTo(255);
            }
        }
    }

    /** Every pixel with any ink lies within a flag, ≥ 1 mm (less one pixel of anti-aliasing) from its edges. */
    static void assertSafeArea(BufferedImage img, String what) {
        int inked = 0;
        float onePx = 1f / PX_PER_MM;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (gray(img, x, y) == 255) continue;
                inked++;
                float x0 = x / PX_PER_MM, x1 = (x + 1) / PX_PER_MM;
                float yTop = 12 - y / PX_PER_MM, yBottom = 12 - (y + 1) / PX_PER_MM;
                boolean flagA = x0 >= 1 - onePx && x1 <= 19 + onePx;
                boolean flagB = x0 >= 41 - onePx && x1 <= 59 + onePx;
                assertThat(flagA || flagB).as(what + ": ink at x " + x0 + " mm").isTrue();
                assertThat(yBottom >= 1 - onePx && yTop <= 11 + onePx).as(what + ": ink at y " + yBottom + " mm").isTrue();
            }
        }
        assertThat(inked).isPositive();
    }

    static void assertNoOverlap(RatTailLabel.Layout l, String what) {
        List<RatTailLabel.Line> flagB = l.lines().stream()
            .filter(x -> x.row() != RatTailLabel.Row.CODE && x.row() != RatTailLabel.Row.COUNT).toList();
        for (int i = 0; i + 1 < flagB.size(); i++) {
            float[] above = flagB.get(i).inkY(), below = flagB.get(i + 1).inkY();
            assertThat(below[1]).as(what + ": " + flagB.get(i + 1).row() + " ink top under " + flagB.get(i).row() + " ink bottom")
                .isLessThan(above[0]);
        }
        for (RatTailLabel.Line x : l.lines()) {
            float[] ink = x.inkY();
            assertThat(ink[0]).as(what + " " + x.row() + " bottom").isGreaterThanOrEqualTo(1 * MM - 1e-3f);
            assertThat(ink[1]).as(what + " " + x.row() + " top").isLessThanOrEqualTo(11 * MM + 1e-3f);
            if (l.bars() != null && x.row() == RatTailLabel.Row.CODE) assertThat(ink[1]).isLessThan(l.bars().y());
        }
    }

    static List<RatTailLabel.Line> rows(RatTailLabel.Layout l, RatTailLabel.Row row) {
        return l.lines().stream().filter(x -> x.row() == row).toList();
    }

    static List<RatTailLabel.Line> flagB(RatTailLabel.Layout l) {
        return l.lines().stream().filter(x -> x.row() == RatTailLabel.Row.NAME || x.row() == RatTailLabel.Row.DETAIL).toList();
    }

    /** The text drawn between x0 and x1 mm (full height) of page {@code page}, its lines joined by one space. */
    static String areaText(PDDocument doc, int page, float x0Mm, float x1Mm) throws IOException {
        PDFTextStripperByArea strip = new PDFTextStripperByArea();
        strip.addRegion("r", new java.awt.geom.Rectangle2D.Float(x0Mm * MM, 0, (x1Mm - x0Mm) * MM, 12 * MM));
        strip.extractRegions(doc.getPage(page));
        return String.join(" ", strip.getTextForRegion("r").strip().lines().map(String::strip).filter(x -> !x.isEmpty()).toList());
    }

    static RatTailLabel.Line line(RatTailLabel.Layout l, RatTailLabel.Row row) {
        return l.lines().stream().filter(x -> x.row() == row).findFirst().orElseThrow(() -> new AssertionError("no " + row));
    }

    static List<Object> tokens(PDPage page) throws IOException {
        return new PDFStreamParser(page).parse();
    }

    static List<String> ops(PDPage page) throws IOException {
        return tokens(page).stream().filter(t -> t instanceof Operator).map(t -> ((Operator) t).getName()).toList();
    }

    /** The glyphs drawn on Flag A (x < 20 mm), in x order. */
    static String flagAText(PDDocument doc) throws IOException {
        List<TextPosition> glyphs = new ArrayList<>();
        PDFTextStripper strip = new PDFTextStripper() {
            @Override protected void processTextPosition(TextPosition t) { glyphs.add(t); }
        };
        strip.getText(doc);
        StringBuilder b = new StringBuilder();
        glyphs.stream().filter(t -> t.getX() < 20 * MM).sorted(Comparator.comparing(TextPosition::getX))
            .forEach(t -> b.append(t.getUnicode()));
        return b.toString();
    }

    /** Code 128 symbol values of a module row (11 modules each; the stop is 13). */
    static List<Integer> codewords(boolean[] modules) throws Exception {
        Field f = Code128Reader.class.getDeclaredField("CODE_PATTERNS");
        f.setAccessible(true);
        int[][] patterns = (int[][]) f.get(null);
        List<Integer> runs = new ArrayList<>();
        for (int i = 0; i < modules.length; ) {
            int j = i;
            while (j < modules.length && modules[j] == modules[i]) j++;
            runs.add(j - i);
            i = j;
        }
        List<Integer> out = new ArrayList<>();
        int r = 0;
        while (r < runs.size()) {
            int width = runs.size() - r == 7 ? 7 : 6;
            int[] p = runs.subList(r, r + width).stream().mapToInt(Integer::intValue).toArray();
            int found = -1;
            for (int v = 0; v < patterns.length; v++) {
                if (Arrays.equals(patterns[v], p)) { found = v; break; }
            }
            if (found < 0 && width == 7) found = 106;      // the stop pattern (2331112)
            out.add(found);
            r += width;
        }
        return out;
    }
}
