package com.traceability.inventory;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pick &amp; Pack S2 — merges the PDFs Bosta returned for one print batch into a single PDF and
 * puts the pages in the batch's order when it can prove which waybill is on which page.
 *
 * Bosta's page order inside a mass-awb PDF is not known, so it is never assumed:
 *   1. The chunk PDFs are appended in send (= batch) order.
 *   2. Each page's text is extracted and searched for the batch's tracking numbers (digit
 *      runs, Arabic-Indic digits folded to ASCII, digit groups split by spaces re-joined).
 *   3. Verified only when every page holds exactly one batch tracking number, each tracking
 *      number is on exactly one page, and page count == waybill count. Then the pages are
 *      reordered to the batch order and {@code orderGuaranteed = true}.
 *   4. Otherwise (barcode-only page, two waybills on one page, a missing or extra page) the
 *      merged order is kept as is and {@code orderGuaranteed = false}.
 *
 * Pure: no database, no network, no logging of page text (waybills carry customer PII).
 */
public final class WaybillPdfAssembler {

    private WaybillPdfAssembler() {}

    /** pdf = the merged document; unmatchedPages = pages that didn't map to exactly one waybill. */
    public record Assembled(byte[] pdf, int pageCount, boolean orderGuaranteed, int unmatchedPages) {}

    private static final Pattern DIGIT_RUN = Pattern.compile("\\d+");
    private static final Pattern SPACED_DIGITS = Pattern.compile("(?<=\\d)[ \\t\\u00A0]+(?=\\d)");

    /**
     * @param chunkPdfs        Bosta's PDFs, in send order
     * @param trackingsInOrder every tracking number in those PDFs, in batch order
     */
    public static Assembled assemble(List<byte[]> chunkPdfs, List<String> trackingsInOrder) throws IOException {
        List<PDDocument> sources = new ArrayList<>();
        try (PDDocument merged = new PDDocument()) {
            PDFMergerUtility merger = new PDFMergerUtility();
            for (byte[] bytes : chunkPdfs) {
                PDDocument src = Loader.loadPDF(bytes);
                sources.add(src);
                merger.appendDocument(merged, src);
            }

            int pageCount = merged.getNumberOfPages();
            Set<String> wanted = new HashSet<>(trackingsInOrder);
            PDFTextStripper stripper = new PDFTextStripper();

            // page index → the one tracking number found on it (null = none or several)
            String[] onPage = new String[pageCount];
            int unmatched = 0;
            for (int p = 0; p < pageCount; p++) {
                stripper.setStartPage(p + 1);
                stripper.setEndPage(p + 1);
                Set<String> found = trackingNumbersIn(stripper.getText(merged), wanted);
                if (found.size() == 1) onPage[p] = found.iterator().next();
                else unmatched++;
            }

            Map<String, Integer> pageOf = new HashMap<>();
            boolean bijective = unmatched == 0 && pageCount == trackingsInOrder.size();
            if (bijective) {
                for (int p = 0; p < pageCount; p++) {
                    if (pageOf.put(onPage[p], p) != null) { bijective = false; break; }
                }
                bijective = bijective && pageOf.keySet().equals(wanted);
            }

            if (bijective) {
                List<PDPage> pages = new ArrayList<>();
                merged.getPages().forEach(pages::add);
                for (PDPage page : pages) merged.getPages().remove(page);
                for (String tn : trackingsInOrder) merged.getPages().add(pages.get(pageOf.get(tn)));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            merged.save(out);
            return new Assembled(out.toByteArray(), pageCount, bijective, unmatched);
        } finally {
            for (PDDocument src : sources) src.close();
        }
    }

    /** The batch tracking numbers that appear on one page's text as whole digit runs. */
    static Set<String> trackingNumbersIn(String pageText, Set<String> wanted) {
        String text = foldDigits(pageText);
        Set<String> runs = new HashSet<>();
        collectRuns(text, runs);
        collectRuns(SPACED_DIGITS.matcher(text).replaceAll(""), runs);
        runs.retainAll(wanted);
        return runs;
    }

    private static void collectRuns(String text, Set<String> into) {
        Matcher m = DIGIT_RUN.matcher(text);
        while (m.find()) into.add(m.group());
    }

    /** Arabic-Indic (U+0660..) and Extended Arabic-Indic (U+06F0..) digits → ASCII. */
    private static String foldDigits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '٠' && c <= '٩') b.append((char) ('0' + (c - '٠')));
            else if (c >= '۰' && c <= '۹') b.append((char) ('0' + (c - '۰')));
            else b.append(c);
        }
        return b.toString();
    }
}
