package com.traceability.integrations.bosta;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BostaReferenceParser (exchange reference S1, 2026-11-17) — the prod shapes, one by one.
 *
 * <pre>
 *   p1  candidates: as sent, after ':', before ':'; -R&lt;n&gt; stripped only when asked
 *   p2  orderNumber — the ONE clean number of every prod shape (revert check: drop the -R strip or the
 *       after-':' rule and the (b) rows go red)
 *   p3  orderNumber — anything unclear is null (never ignored on it)
 *   p4  numberVariants — '#'±, upper / lower case
 *   p5  names — the portal check: either side, any case, no -R strip
 * </pre>
 */
class BostaReferenceParserTest {

    @Test
    void p1_candidates_orderAndSuffix() {
        assertThat(BostaReferenceParser.candidates("jamilatheboutique:#64968-R1", false))
            .containsExactly("jamilatheboutique:#64968-R1", "#64968-R1", "jamilatheboutique");
        assertThat(BostaReferenceParser.candidates("jamilatheboutique:#64968-R1", true))
            .containsExactly("jamilatheboutique:#64968-R1", "jamilatheboutique:#64968", "#64968-R1", "#64968",
                "jamilatheboutique");
        assertThat(BostaReferenceParser.candidates("BRK-47472-EG:BRK-47472-EG-R2", true))
            .containsExactly("BRK-47472-EG:BRK-47472-EG-R2", "BRK-47472-EG:BRK-47472-EG", "BRK-47472-EG-R2",
                "BRK-47472-EG");
        assertThat(BostaReferenceParser.candidates("#LT-22267", true)).containsExactly("#LT-22267");
        assertThat(BostaReferenceParser.candidates("13313", true)).containsExactly("13313");
        assertThat(BostaReferenceParser.candidates(null, true)).isEmpty();
        assertThat(BostaReferenceParser.candidates("   ", true)).isEmpty();
        assertThat(BostaReferenceParser.candidates(" wjwy1a-ia : #5126 ", false))
            .containsExactly("wjwy1a-ia : #5126", "#5126", "wjwy1a-ia");
    }

    @Test
    void p2_orderNumber_everyProdShape() {
        assertThat(BostaReferenceParser.orderNumber("jamilatheboutique:#64968-R1")).isEqualTo("64968");
        assertThat(BostaReferenceParser.orderNumber("overdue-9573:#OD2394-R1")).isEqualTo("OD2394");
        assertThat(BostaReferenceParser.orderNumber("wjwy1a-ia:#5126")).isEqualTo("5126");
        assertThat(BostaReferenceParser.orderNumber("#LT-22267")).isEqualTo("LT-22267");
        assertThat(BostaReferenceParser.orderNumber("BRK-47472-EG:BRK-47472-EG-R2")).isEqualTo("BRK-47472-EG");
        assertThat(BostaReferenceParser.orderNumber("13313")).isEqualTo("13313");
        assertThat(BostaReferenceParser.orderNumber("#4092")).isEqualTo("4092");
        assertThat(BostaReferenceParser.orderNumber("#2739:#2739-R1")).as("Storm").isEqualTo("2739");
        assertThat(BostaReferenceParser.orderNumber("blncoeg:#515956")).isEqualTo("515956");
        assertThat(BostaReferenceParser.orderNumber(null)).isNull();
    }

    @Test
    void p3_orderNumber_unclearIsNull() {
        assertThat(BostaReferenceParser.orderNumber("1234:5678")).as("two different plain values").isNull();
        assertThat(BostaReferenceParser.orderNumber("a:b:#1")).as("more than one ':'").isNull();
        assertThat(BostaReferenceParser.orderNumber("order 12 34")).as("spaces").isNull();
        assertThat(BostaReferenceParser.orderNumber("#")).isNull();
        assertThat(BostaReferenceParser.orderNumber(" : ")).isNull();
        assertThat(BostaReferenceParser.orderNumber("#5126:")).as("an empty side leaves the other").isEqualTo("5126");
    }

    @Test
    void p4_numberVariants_hashAndCase() {
        assertThat(BostaReferenceParser.numberVariants("#lt-22267", false))
            .contains("#lt-22267", "lt-22267", "#LT-22267", "LT-22267");
        assertThat(BostaReferenceParser.numberVariants("jamilatheboutique:#64968-R1", true))
            .contains("#64968", "64968")
            .doesNotContain("#64968-r1x");
        assertThat(BostaReferenceParser.numberVariants("jamilatheboutique:#64968-R1", false))
            .as("no strip → never the bare order").doesNotContain("64968", "#64968");
        assertThat(BostaReferenceParser.numberVariants(null, true)).isEmpty();
    }

    @Test
    void p5_names_portalCheck() {
        assertThat(BostaReferenceParser.names("blncoeg:#515956", "#515956", false)).isTrue();
        assertThat(BostaReferenceParser.names("BRK-1-EG:BRK-1-EG-R1", "BRK-1-EG", false)).isTrue();
        assertThat(BostaReferenceParser.names("#lt-22267", "#LT-22267", false)).isTrue();
        assertThat(BostaReferenceParser.names("shop:#1234-R1", "#1234", false)).as("a Return's trip isn't the delivery").isFalse();
        assertThat(BostaReferenceParser.names(null, "#1", false)).isFalse();
        assertThat(BostaReferenceParser.names("#1", null, false)).isFalse();
    }
}
