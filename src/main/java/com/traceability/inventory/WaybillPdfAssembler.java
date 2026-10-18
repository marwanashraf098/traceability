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
 *   1. The chunk PDFs are appended in send order.
 *   2. Each page's text is extracted and searched for its chunk's tracking numbers (digit runs,
 *      Arabic-Indic digits folded to ASCII, digit groups split by spaces re-joined).
 *   3. Per chunk (merged-PDF slice A): a chunk is verified when every page holds exactly one of its
 *      tracking numbers, each of them is on exactly one page, and page count == its waybill count.
 *      A verified chunk's pages go to their tracking numbers' places in the batch order; an
 *      unverified chunk (barcode-only page, two waybills on one page, a missing or extra page) stays
 *      together in Bosta's order, placed where its first waybill in batch order belongs. So one
 *      unreadable page only costs its own chunk the reordering.
 *   4. {@code orderGuaranteed} is true only when every chunk was verified.
 *
 * Pure: no database, no network, no logging of page text (waybills carry customer PII).
 */
public final class WaybillPdfAssembler {

    private WaybillPdfAssembler() {}

    /** pdf = the merged document; unmatchedPages = pages that didn't map to exactly one waybill. */
    public record Assembled(byte[] pdf, int pageCount, boolean orderGuaranteed, int unmatchedPages) {}

    /** One Bosta PDF and the tracking numbers that were sent for it (= the waybills it holds). */
    public record ChunkPdf(byte[] pdf, List<String> trackingNumbers) {}

    private static final Pattern DIGIT_RUN = Pattern.compile("\\d+");
    private static final Pattern SPACED_DIGITS = Pattern.compile("(?<=\\d)[ \\t\\u00A0]+(?=\\d)");

    /**
     * The PDFs are verified as ONE group (no per-chunk tracking numbers known): every page must map
     * to exactly one of {@code trackingsInOrder}, else the merged order is kept as is.
     *
     * @param chunkPdfs        Bosta's PDFs, in send order
     * @param trackingsInOrder every tracking number in those PDFs, in batch order
     */
    public static Assembled assemble(List<byte[]> chunkPdfs, List<String> trackingsInOrder) throws IOException {
        return assembleGroups(List.of(new Group(chunkPdfs, trackingsInOrder)), trackingsInOrder);
    }

    /**
     * Each chunk is verified and reordered on its own (see the class comment).
     *
     * @param chunks           Bosta's PDFs with the tracking numbers sent for each, in send order
     * @param trackingsInOrder every tracking number in those PDFs, in batch order
     */
    public static Assembled assembleChunks(List<ChunkPdf> chunks, List<String> trackingsInOrder) throws IOException {
        List<Group> groups = new ArrayList<>();
        for (ChunkPdf c : chunks) groups.add(new Group(List.of(c.pdf()), c.trackingNumbers()));
        return assembleGroups(groups, trackingsInOrder);
    }

    /** PDFs verified together against their own tracking numbers. */
    private record Group(List<byte[]> pdfs, List<String> trackings) {}

    /** Pages placed together at {@code key} (a batch position) — one page, or a whole unverified group. */
    private record Unit(int key, List<PDPage> pages) {}

    private static Assembled assembleGroups(List<Group> groups, List<String> trackingsInOrder) throws IOException {
        Map<String, Integer> position = new HashMap<>();
        for (int i = 0; i < trackingsInOrder.size(); i++) position.putIfAbsent(trackingsInOrder.get(i), i);

        List<PDDocument> sources = new ArrayList<>();
        try (PDDocument merged = new PDDocument()) {
            PDFMergerUtility merger = new PDFMergerUtility();
            int[] firstPage = new int[groups.size() + 1];
            for (int g = 0; g < groups.size(); g++) {
                firstPage[g] = merged.getNumberOfPages();
                for (byte[] bytes : groups.get(g).pdfs()) {
                    PDDocument src = Loader.loadPDF(bytes);
                    sources.add(src);
                    merger.appendDocument(merged, src);
                }
            }
            int pageCount = merged.getNumberOfPages();
            firstPage[groups.size()] = pageCount;

            List<PDPage> pages = new ArrayList<>();
            merged.getPages().forEach(pages::add);
            PDFTextStripper stripper = new PDFTextStripper();

            List<Unit> units = new ArrayList<>();
            boolean allVerified = true;
            int unmatched = 0;
            for (int g = 0; g < groups.size(); g++) {
                Group group = groups.get(g);
                Set<String> wanted = new HashSet<>(group.trackings());
                int from = firstPage[g], to = firstPage[g + 1];

                // page → the one tracking number of this group found on it (null = none or several)
                String[] onPage = new String[to - from];
                int groupUnmatched = 0;
                for (int p = from; p < to; p++) {
                    stripper.setStartPage(p + 1);
                    stripper.setEndPage(p + 1);
                    Set<String> found = trackingNumbersIn(stripper.getText(merged), wanted);
                    if (found.size() == 1) onPage[p - from] = found.iterator().next();
                    else groupUnmatched++;
                }
                unmatched += groupUnmatched;

                boolean bijective = groupUnmatched == 0 && onPage.length == group.trackings().size();
                Set<String> seen = new HashSet<>();
                if (bijective) {
                    for (String tn : onPage) if (!seen.add(tn)) { bijective = false; break; }
                    bijective = bijective && seen.equals(wanted);
                }

                if (bijective) {
                    for (int p = from; p < to; p++) {
                        units.add(new Unit(position.getOrDefault(onPage[p - from], Integer.MAX_VALUE),
                            List.of(pages.get(p))));
                    }
                } else {
                    allVerified = false;
                    int key = group.trackings().stream().map(position::get).filter(Objects::nonNull)
                        .min(Integer::compare).orElse(Integer.MAX_VALUE);
                    units.add(new Unit(key, List.copyOf(pages.subList(from, to))));
                }
            }

            // Stable: ties (and groups with no batch position) keep send order.
            units.sort(Comparator.comparingInt(Unit::key));
            List<PDPage> ordered = new ArrayList<>(pageCount);
            for (Unit u : units) ordered.addAll(u.pages());
            if (!ordered.equals(pages)) {
                for (PDPage page : pages) merged.getPages().remove(page);
                for (PDPage page : ordered) merged.getPages().add(page);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            merged.save(out);
            return new Assembled(out.toByteArray(), pageCount, allVerified, unmatched);
        } finally {
            for (PDDocument src : sources) src.close();
        }
    }

    /** The batch tracking numbers that appear on one page's text as whole digit runs. */
    static Set<String> trackingNumbersIn(String pageText, Set<String> wanted) {
        String text = ScanInputNormalizer.foldDigits(pageText);
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
}
