package com.traceability.inventory;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flexible labeling slice 1 — the PIECE label is unchanged by the blank-label work.
 *
 * The PDF bytes themselves differ run to run (embedded font subsets get a random tag), so the
 * golden is a structural fingerprint: per page the media box, the decoded content stream (every
 * drawing operator, position, font size and glyph) and the barcode image's pixels. Recorded on
 * origin/main bcf908a BEFORE any slice-1 change, at both supported sizes.
 *
 *   g1 — 50×25 (today's every label) and 40×25 match the recorded fingerprints
 */
class PieceLabelGoldenTest {

    static final String GOLDEN_50x25 = "048326eae89a68e1b69952e7f3b4a2e10449a9d2475a623d72476af83a66b8fa";
    static final String GOLDEN_40x25 = "8f93d6a7c6edc1ea067416177dca9bd9b6a45c9f7be5057ef92b8c94544d8ef5";

    static final List<LabelPdfRenderer.Piece> PIECES = List.of(
        new LabelPdfRenderer.Piece("P000123", "Linen Shirt", "M / Blue", "LS-M-BL"),
        new LabelPdfRenderer.Piece("P1000000", "قميص كتان", "Default Title", null));

    @Test
    void g1_piece_labels_match_the_pre_slice_1_fingerprints() throws Exception {
        assertThat(fingerprint(LabelPdfRenderer.render(PIECES, PieceLabelLayout.Spec.of(50f, 25f)))).isEqualTo(GOLDEN_50x25);
        assertThat(fingerprint(LabelPdfRenderer.render(PIECES, PieceLabelLayout.Spec.of(40f, 25f)))).isEqualTo(GOLDEN_40x25);
    }

    static String fingerprint(byte[] pdf) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                md.update(page.getMediaBox().toString().getBytes());
                md.update(page.getContents().readAllBytes());
                for (COSName n : page.getResources().getXObjectNames()) {
                    if (page.getResources().getXObject(n) instanceof PDImageXObject img) {
                        BufferedImage bi = img.getImage();
                        md.update((bi.getWidth() + "x" + bi.getHeight()).getBytes());
                        int[] px = bi.getRGB(0, 0, bi.getWidth(), bi.getHeight(), null, 0, bi.getWidth());
                        for (int p : px) { md.update((byte) p); md.update((byte) (p >> 8)); md.update((byte) (p >> 16)); }
                    }
                }
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
