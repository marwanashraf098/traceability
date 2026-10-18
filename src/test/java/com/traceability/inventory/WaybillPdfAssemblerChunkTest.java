package com.traceability.inventory;

import com.traceability.inventory.WaybillPdfAssembler.ChunkPdf;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Merged-PDF slice A — page order is verified per chunk: one unreadable page costs only its own chunk
 * the reordering. Every chunk below comes back from "Bosta" with its pages reversed.
 */
class WaybillPdfAssemblerChunkTest {

    private static final String BLANK = "";

    @Test
    void c1_unreadablePageInChunk2_chunks1And3StillInPickListOrder() throws Exception {
        List<String> order = tns(12);
        List<String> c1 = order.subList(0, 4), c2 = order.subList(4, 8), c3 = order.subList(8, 12);
        // Chunk 2's last waybill comes back as a barcode-only page (no readable number).
        List<String> c2Pages = reversed(c2);
        c2Pages.set(0, BLANK);

        WaybillPdfAssembler.Assembled a = WaybillPdfAssembler.assembleChunks(List.of(
            new ChunkPdf(pdf(reversed(c1)), c1),
            new ChunkPdf(pdf(c2Pages), c2),
            new ChunkPdf(pdf(reversed(c3)), c3)), order);

        List<String> pages = pagesOf(a.pdf());
        assertThat(pages.subList(0, 4)).containsExactlyElementsOf(c1);        // reordered
        assertThat(pages.subList(4, 8)).containsExactlyElementsOf(c2Pages);   // kept as Bosta sent it, in its place
        assertThat(pages.subList(8, 12)).containsExactlyElementsOf(c3);       // reordered
        assertThat(a.orderGuaranteed()).isFalse();
        assertThat(a.unmatchedPages()).isEqualTo(1);
        assertThat(a.pageCount()).isEqualTo(12);
    }

    @Test
    void c2_everyChunkReadable_wholePdfInPickListOrder_evenWhenChunksWereSentOutOfOrder() throws Exception {
        List<String> order = tns(9);
        // Sent as slice 1's isolation does: a later part first, then the rest.
        List<String> late = List.of(order.get(6), order.get(7), order.get(8));
        List<String> early = order.subList(0, 6);

        WaybillPdfAssembler.Assembled a = WaybillPdfAssembler.assembleChunks(List.of(
            new ChunkPdf(pdf(reversed(late)), late),
            new ChunkPdf(pdf(reversed(early)), early)), order);

        assertThat(pagesOf(a.pdf())).containsExactlyElementsOf(order);
        assertThat(a.orderGuaranteed()).isTrue();
        assertThat(a.unmatchedPages()).isZero();
    }

    @Test
    void c3_unverifiedChunk_placedWhereItsFirstWaybillBelongs() throws Exception {
        List<String> order = tns(6);
        List<String> middle = order.subList(2, 4);
        List<String> rest = List.of(order.get(0), order.get(1), order.get(4), order.get(5));
        List<String> middlePages = List.of(middle.get(1), middle.get(1));     // the same waybill twice: not 1:1

        WaybillPdfAssembler.Assembled a = WaybillPdfAssembler.assembleChunks(List.of(
            new ChunkPdf(pdf(middlePages), middle),
            new ChunkPdf(pdf(reversed(rest)), rest)), order);

        assertThat(pagesOf(a.pdf())).containsExactly(
            order.get(0), order.get(1), middle.get(1), middle.get(1), order.get(4), order.get(5));
        assertThat(a.orderGuaranteed()).isFalse();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static List<String> tns(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(String.valueOf(7_100_000_000L + i * 7_919L));
        return out;
    }

    private static List<String> reversed(List<String> l) {
        List<String> r = new ArrayList<>(l);
        java.util.Collections.reverse(r);
        return r;
    }

    /** One page per entry; an empty entry is a page with no readable number. */
    private static byte[] pdf(List<String> pages) throws Exception {
        try (PDDocument d = new PDDocument()) {
            for (String tn : pages) {
                PDPage page = new PDPage();
                d.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(tn.isEmpty() ? "BARCODE ONLY" : "AWB " + tn);
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            d.save(out);
            return out.toByteArray();
        }
    }

    private static List<String> pagesOf(byte[] pdf) throws Exception {
        List<String> out = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            for (int p = 1; p <= d.getNumberOfPages(); p++) {
                s.setStartPage(p);
                s.setEndPage(p);
                String text = s.getText(d).trim();
                out.add(text.startsWith("AWB ") ? text.substring(4).trim() : BLANK);
            }
        }
        return out;
    }
}
