package com.traceability.analytics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Analytics slice 5 — the channel / payment / failure-reason / size mapping tables. */
class AnalyticsMappingsTest {

    static final Set<String> OWN = AnalyticsMappings.ownHosts("https://broek-eg.com/123/orders/abc/authenticate?key=x",
        "broek-eg.myshopify.com");

    static String channel(String source, String referrer, String landing) {
        return AnalyticsMappings.channel(source, true, referrer, landing, OWN);
    }

    @ParameterizedTest(name = "{0} | {1} | {2} → {3}")
    @CsvSource(delimiter = ';', nullValues = "NULL", value = {
        // draft orders are the merchant's own manual / DM sales
        "shopify_draft_order; https://instagram.com/; /?utm_source=facebook; Manual / DM",
        // the referrer host decides first
        "web; https://instagram.com/; /?utm_source=facebook; Instagram",
        "web; https://l.instagram.com/; NULL; Instagram",
        "web; android-app://com.instagram.android/; NULL; Instagram",
        "web; https://m.facebook.com/; NULL; Facebook",
        "web; https://l.facebook.com/l.php?u=x; NULL; Facebook",
        "web; https://www.tiktok.com/; NULL; TikTok",
        "web; https://www.google.com/; NULL; Google",
        "web; https://www.google.com.eg/search?q=x; NULL; Google",
        // then utm_source, truncated Meta values included; campaign names in utm_source are ignored
        "web; NULL; /products/x?utm_source=facebook&utm_medium=paid; Facebook",
        "web; NULL; /?utm_source=fa; Facebook",
        "web; NULL; /?utm_source=fac; Facebook",
        "web; NULL; /?utm_source=faceb; Facebook",
        "web; NULL; /?utm_source=FB; Facebook",
        "web; NULL; /?utm_source=ig; Instagram",
        "web; NULL; /?utm_source=tiktok; TikTok",
        "web; NULL; /?utm_source=google; Google",
        "web; NULL; /?utm_source=jeans%20-%20summer%20collection%20%7C%206%2F23; Direct",
        "web; NULL; /?utm_source=f; Direct",
        // another site → Other referral; the store's own domain → Direct
        "web; https://l.wl.co/abc; NULL; Other referral",
        "web; https://broek-eg.com/collections/all; NULL; Direct",
        "web; https://www.broek-eg.com/; NULL; Direct",
        "web; NULL; NULL; Direct",
        "web; ''; /; Direct",
    })
    void channel_table(String source, String referrer, String landing, String expected) {
        assertThat(channel(source, referrer, landing)).isEqualTo(expected);
    }

    @Test
    void channel_noSourceFields_isUnknown_butDraftOrdersStillManual() {
        assertThat(AnalyticsMappings.channel(null, false, null, null, OWN)).isEqualTo("Unknown");
        assertThat(AnalyticsMappings.channel("shopify_draft_order", false, null, null, OWN)).isEqualTo("Manual / DM");
    }

    @Test
    void payment_table() {
        assertThat(AnalyticsMappings.payment(List.of("Cash on Delivery (COD)"))).isEqualTo("COD");
        assertThat(AnalyticsMappings.payment(List.of("Paymob - Native Checkout for Debit/Credit Cards", "Paymob")))
            .isEqualTo("Card");
        assertThat(AnalyticsMappings.payment(List.of("Pay with Card, Wallet and Installment via Kashier"))).isEqualTo("Card");
        assertThat(AnalyticsMappings.payment(List.of("manual"))).isEqualTo("Manual");
        assertThat(AnalyticsMappings.payment(List.of("Cash on Delivery (COD)", "manual"))).isEqualTo("Mixed");
        assertThat(AnalyticsMappings.payment(List.of("Paymob - Native Checkout for Debit/Credit Cards",
            "Cash on Delivery (COD)"))).isEqualTo("Mixed");
        assertThat(AnalyticsMappings.payment(List.of())).isEqualTo("Other");
        assertThat(AnalyticsMappings.payment(null)).isEqualTo("Other");
        assertThat(AnalyticsMappings.payment(List.of("gift_card"))).isEqualTo("Other");
        assertThat(AnalyticsMappings.payment(List.of("bank transfer"))).isEqualTo("Other");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = ';', value = {
        "Cancellation - the customer refuses to receive the shipment.; Customer refused",
        "Retry delivery - the customer is not in the address.; Phone unreachable / not home",
        "Customer phone is switched off; Phone unreachable / not home",
        "Waiting for data modification - address not clear; Wrong or incomplete address",
        "Postponed - the customer requested postponement for another day.; Postponed / rescheduled",
        "Cancellation - product issue; Product issue",
        "Something new from Bosta; Other",
    })
    void failureReason_table(String reason, String expected) {
        assertThat(AnalyticsMappings.failureReason(reason)).isEqualTo(expected);
    }

    @Test
    void failureReason_none() {
        assertThat(AnalyticsMappings.failureReason(null)).isNull();
        assertThat(AnalyticsMappings.failureReason("  ")).isNull();
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = ';', nullValues = "NULL", value = {
        "m; M", "XL; XL", "xl; XL", "2XL; XXL", "2xl; XXL", "3XL; XXXL", "Small; S", "X-Large; XL",
        "32; 32", "37; 37", "37.5; 37.5", "40,5; 40.5", " 38 ; 38",
        "XL-XXL; NULL", "One size; NULL", "Default Title; NULL", "61; NULL", "0; NULL", "; NULL",
    })
    void size_table(String raw, String expected) {
        assertThat(AnalyticsMappings.normaliseSize(raw)).isEqualTo(expected);
    }

    @Test
    void size_curveOrder_lettersThenNumbers() {
        List<String> sizes = new java.util.ArrayList<>(List.of("40", "XL", "S", "38", "XXL", "M", "37.5"));
        sizes.sort(java.util.Comparator.comparingInt(AnalyticsMappings::sizeOrder));
        assertThat(sizes).containsExactly("S", "M", "XL", "XXL", "37.5", "38", "40");
    }
}
