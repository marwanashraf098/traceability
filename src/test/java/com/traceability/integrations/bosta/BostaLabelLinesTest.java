package com.traceability.integrations.bosta;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug 2 / 3 — BostaLabelLines on BROEK's real label texts (prod 2026-11-16). itemsCount is never read.
 */
class BostaLabelLinesTest {

    @Test
    void crp7139234160_oneLine() {
        BostaLabelLines.Label l = BostaLabelLines.parse("x1 Boxy Oversized Crewneck – Burgundy - M - SKU: BRK-RGC-2627-BRG-M");
        assertThat(l).isNotNull();
        assertThat(l.total()).isEqualTo(1);
        assertThat(l.lines()).containsExactly(
            new BostaLabelLines.Line("BRK-RGC-2627-BRG-M", 1, "Boxy Oversized Crewneck – Burgundy - M"));
    }

    @Test
    void crp4226201959_threeLines_itemsCountIgnored() {
        // Bosta's specs.itemsCount said 1 for this parcel; the label lists 3 and 3 arrived.
        BostaLabelLines.Label l = BostaLabelLines.parse(
            "x1 Washed Black Relaxed Fit Denim Jeans - 32 - SKU: BRK-JEANS-BLK-RF-002 // x1 Beige Relaxed Fit Denim Jeans - 32 - " +
            "SKU: BRK-JEANS-BGE-RF-002 // x1 White Relaxed Fit Denim Jeans - 32 - SKU: BRK-JEANS-WHT-RF-002");
        assertThat(l.total()).isEqualTo(3);
        assertThat(l.lines()).extracting(BostaLabelLines.Line::sku)
            .containsExactly("BRK-JEANS-BLK-RF-002", "BRK-JEANS-BGE-RF-002", "BRK-JEANS-WHT-RF-002");
        assertThat(l.quantityOf("BRK-JEANS-BGE-RF-002")).isEqualTo(1);
        assertThat(l.quantityOf("BROEK-Gray-RFD-1")).isZero();
    }

    @Test
    void forward5373943286_pluginFormat_perLine() {
        BostaLabelLines.Label l = BostaLabelLines.parse(
            "Boxy Oversized Crewneck – Burgundy - M x 1 (BRK-RGC-2627-BRG-M)\n" +
            "Gray Relaxed Fit Denim Jeans - 30 x 1 (BROEK-Gray-RFD-1)\n" +
            "Raw Denim Relaxed Fit Jeans - 30 x 1 (BROEK-Raw-Denim-RFD-1)");
        assertThat(l.total()).isEqualTo(3);
        assertThat(l.lines()).extracting(BostaLabelLines.Line::sku)
            .containsExactly("BRK-RGC-2627-BRG-M", "BROEK-Gray-RFD-1", "BROEK-Raw-Denim-RFD-1");
        assertThat(l.lines().get(1).name()).isEqualTo("Gray Relaxed Fit Denim Jeans - 30");
    }

    @Test
    void forward2109710882_fourLines_quantitiesAndRepeatsSummed() {
        BostaLabelLines.Label l = BostaLabelLines.parse(
            "Dark Washed Relaxed Fit Denim Jeans - 40 x 1 (BROEK-DARK-WASHED-RFD-006)\n" +
            "Gray Relaxed Fit Denim Jeans - 40 x 1 (BROEK-Gray-RFD-6)\n" +
            "Raw Denim Relaxed Fit Jeans - 40 x 1 (BROEK-Raw-Denim-RFD-6)\n" +
            "Smoked Basic Summer Sweatpants - XXXL x 1 (SmSSw3x)");
        assertThat(l.total()).isEqualTo(4);
        BostaLabelLines.Label twice = BostaLabelLines.parse("x2 Tee - L - SKU: T-1 // x1 Tee - L - SKU: T-1");
        assertThat(twice.lines()).hasSize(1);
        assertThat(twice.quantityOf("T-1")).isEqualTo(3);
    }

    @Test
    void unreadable_fallsBackToNull() {
        assertThat(BostaLabelLines.parse(null)).isNull();
        assertThat(BostaLabelLines.parse("  ")).isNull();
        assertThat(BostaLabelLines.parse("Clothes")).as("free text").isNull();
        assertThat(BostaLabelLines.parse("2 pieces, handle with care")).isNull();
        assertThat(BostaLabelLines.parse("x1 Tee - L - SKU: T-1 // a gift note")).as("one bad segment → unreadable").isNull();
        assertThat(BostaLabelLines.parse("Tee - L x 1 (T-1)\nTee - M (T-2)")).as("a line without quantity").isNull();
        assertThat(BostaLabelLines.parse("x0 Tee - L - SKU: T-1")).as("zero quantity").isNull();
    }
}
