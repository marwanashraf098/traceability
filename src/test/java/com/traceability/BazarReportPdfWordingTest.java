package com.traceability;

import com.traceability.bazar.BazarReportPdf;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar U1c — the report PDF's page-1 wording (no DB: {@link BazarReportPdf#pdf} on a built report).
 * <ul>
 *   <li>w1 — the Shopify tile: "VS SHOPIFY · 319 EGP · below Shopify prices" (whole pounds, 318.9 → 319).</li>
 *   <li>w2 — the Counted tile: "Counted 1,167 · Balanced" / "· 20 short" / "· 20 over" (never "· 0" / "−20").</li>
 *   <li>w3 — the Arabic page: "المعدود 1,167 · مطابق" and "أقل من أسعار Shopify". PDFBox gives the Arabic text back in
 *       logical order, but puts the Latin "Shopify" run first ("Shopify أقل من أسعار"), so the words are matched
 *       without that run's position.</li>
 * </ul>
 */
class BazarReportPdfWordingTest {

    private static Map<String, Object> report() {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("name", "Wording Bazar");
        ev.put("location_name", "Hall");
        ev.put("starts_on", "2026-12-01");
        ev.put("ends_on", "2026-12-01");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("event", ev);
        r.put("money_in", new BigDecimal("1187"));
        r.put("sell_through", new BigDecimal("0.5"));
        r.put("pieces_sold", 3);
        r.put("pieces_sent", 6);
        r.put("sales", 3);
        r.put("average_sale", new BigDecimal("395.67"));
        r.put("busiest_hours", List.of(Map.of("hour", 18, "sales", 3, "pieces", 3)));
        return r;
    }

    /** One closed day whose cash count differs from what was expected by {@code difference}. */
    private static List<Map<String, Object>> closedDay(String entered, String difference) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("revenue", new BigDecimal("1187"));
        money.put("total", new BigDecimal("1187"));
        money.put("by_method", Map.of("cash", new BigDecimal("1187")));
        Map<String, Object> cash = new LinkedHashMap<>();
        cash.put("method", "cash");
        cash.put("entered", new BigDecimal(entered));
        cash.put("difference", new BigDecimal(difference));
        Map<String, Object> close = new LinkedHashMap<>();
        close.put("result", Map.of("methods", List.of(cash)));
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("business_day", "2026-12-01");
        day.put("money", money);
        day.put("close", close);
        return List.of(day);
    }

    private static String text(List<Map<String, Object>> days, String below) throws Exception {
        return text(days, below, "en");
    }

    private static String text(List<Map<String, Object>> days, String below, String lang) throws Exception {
        byte[] pdf = BazarReportPdf.pdf(new BazarReportPdf.Data(report(), days, List.of("cash"), new BigDecimal(below),
            "TR-ABC123"), lang);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void w1_shopifyTile_wholePounds_belowShopifyPrices() throws Exception {
        String t = text(closedDay("1167", "0"), "318.9");
        assertThat(t).contains("VS SHOPIFY").contains("319 EGP").contains("below Shopify prices")
            .doesNotContain("BELOW SHOPIFY").doesNotContain("318.9");
        assertThat(text(closedDay("1167", "0"), "0")).contains("0 EGP");
        // Arabic renders too (its words are checked at the source: the same Words table).
        assertThat(BazarReportPdf.pdf(new BazarReportPdf.Data(report(), closedDay("1167", "0"), List.of("cash"),
            new BigDecimal("318.9"), "TR-ABC123"), "ar")).isNotEmpty();
    }

    @Test
    void w2_countedTile_balanced_short_over() throws Exception {
        assertThat(text(closedDay("1167", "0"), "0")).contains("Counted 1,167 · Balanced").doesNotContain("1,167 · 0");
        assertThat(text(closedDay("1167", "-20"), "0")).contains("Counted 1,167 · 20 short").doesNotContain("· −20");
        assertThat(text(closedDay("1187", "20"), "0")).contains("Counted 1,187 · 20 over").doesNotContain("· +20");
    }

    @Test
    void w3_arabic_balanced_andBelowShopifyPrices() throws Exception {
        String t = text(closedDay("1167", "0"), "318.9", "ar");
        assertThat(t).contains("المعدود 1,167 · مطابق").contains("319 EGP").contains("أقل من أسعار").contains("Shopify")
            .doesNotContain("أقل من Shopify");
        assertThat(text(closedDay("1167", "-20"), "0", "ar")).contains("المعدود 1,167 · ناقص 20");
    }
}
