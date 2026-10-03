package com.traceability.integrations.bosta;

import com.traceability.inventory.TrackingNumberNormalizer;
import com.traceability.inventory.WaybillPdfAssembler;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review mode S2 — SimulatedWaybillRenderer.
 *   w0 the top barcode copies the real Bosta shape exactly: the production sample
 *      "G - 0 2 - 8 4 8 4 8 0 5 6 9 9" (Jumi 2026-10-01; AwbSpacedBarcodeTest.spaced(),
 *      TrackingNumberSpacesTest) — and normalizes back to the digits
 *   w1 one page per tracking number, in order
 *   w2 each page carries its tracking number as extractable text → WaybillPdfAssembler verifies
 *      the page order (orderGuaranteed)
 *   w3 the SIMULATED banner (EN + AR) and order / COD fields are on the page
 *   w4 an Arabic customer name and A6 paper render
 */
class SimulatedWaybillRendererTest {

    private static final String TN1 = "7770000000001";
    private static final String TN2 = "7770000000002";

    private static SimulatedWaybillRenderer.Waybill wb(String tn, String customer) {
        return new SimulatedWaybillRenderer.Waybill(tn, "#R-" + tn.substring(10), customer, "01012345678",
            "12 Tahrir St, Cairo", new BigDecimal("1250.00"));
    }

    @Test
    void w0_topBarcode_isTheRealBostaShape_andNormalizes() {
        // The exact string from the production sample (fixture: TrackingNumberSpacesTest line 18).
        assertThat(SimulatedWaybillRenderer.topBarcode("8484805699")).isEqualTo("G - 0 2 - 8 4 8 4 8 0 5 6 9 9");
        assertThat(TrackingNumberNormalizer.normalize(SimulatedWaybillRenderer.topBarcode(TN1))).isEqualTo(TN1);
        assertThat(TrackingNumberNormalizer.normalize(SimulatedWaybillRenderer.qrContent(TN1))).isEqualTo(TN1);
    }

    @Test
    void w1_w2_onePagePerTracking_textLayerMatchesEveryPage() throws Exception {
        byte[] pdf = SimulatedWaybillRenderer.render(List.of(wb(TN1, "Youssef Adel"), wb(TN2, "Mona Ali")), "A4");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
        }
        WaybillPdfAssembler.Assembled a = WaybillPdfAssembler.assemble(List.of(pdf), List.of(TN1, TN2));
        assertThat(a.orderGuaranteed()).as("each page holds exactly one batch tracking number").isTrue();
        assertThat(a.unmatchedPages()).isZero();
    }

    @Test
    void w3_bannerAndFields_onThePage() throws Exception {
        String text = text(SimulatedWaybillRenderer.render(List.of(wb(TN1, "Youssef Adel")), "A4"));
        assertThat(text).contains("SIMULATED — not a Bosta shipment");
        assertThat(text).contains("Tracking number: " + TN1);
        assertThat(text).contains("#R-" + TN1.substring(10));
        assertThat(text).contains("1250.00");
        assertThat(text).contains("Youssef Adel");
    }

    @Test
    void w4_arabicCustomer_andA6_render() throws Exception {
        byte[] pdf = SimulatedWaybillRenderer.render(List.of(wb(TN1, "محمد أحمد")), "A6");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            assertThat(doc.getPage(0).getMediaBox().getWidth()).isLessThan(300f);   // A6 = 297.6 pt
        }
        assertThat(text(pdf)).contains(TN1);
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
