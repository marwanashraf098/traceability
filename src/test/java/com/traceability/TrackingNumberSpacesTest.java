package com.traceability;

import com.traceability.inventory.TrackingNumberNormalizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bosta's TOP waybill barcode encodes a space between every character (production, Jumi
 * 2026-10-01: raw scan "G - 0 2 - 8 4 8 4 8 0 5 6 9 9" was rejected as NOT_A_WAYBILL while the
 * bottom barcode "8484805699" packed fine). TrackingNumberNormalizer now removes all whitespace
 * before its unchanged prefix strip and digits-only check.
 */
class TrackingNumberSpacesTest {

    @Test
    void spacedTopBarcode_normalizesToTheTrackingNumber() {
        assertThat(TrackingNumberNormalizer.normalize("G - 0 2 - 8 4 8 4 8 0 5 6 9 9")).isEqualTo("8484805699");
    }

    @Test
    void prefixedAndBareForms_unchanged() {
        assertThat(TrackingNumberNormalizer.normalize("G-02-8484805699")).isEqualTo("8484805699");
        assertThat(TrackingNumberNormalizer.normalize("8484805699")).isEqualTo("8484805699");
    }

    @Test
    void nonBreakingSpaceTabNewlineAndOtherUnicodeSpaces_removed() {
        assertThat(TrackingNumberNormalizer.normalize("G - 02 - 8484805699")).isEqualTo("8484805699");
        assertThat(TrackingNumberNormalizer.normalize("G\t-\t0\t2\t-\t8484805699")).isEqualTo("8484805699");
        assertThat(TrackingNumberNormalizer.normalize("8484\n805699\r\n")).isEqualTo("8484805699");
        assertThat(TrackingNumberNormalizer.normalize("8484 805 699　")).isEqualTo("8484805699");
    }

    @Test
    void lettersAfterThePrefix_stillRejected() {
        assertThat(TrackingNumberNormalizer.normalize("G - 0 2 - 8 4 8 4 A B 6 9 9")).isNull();
        assertThat(TrackingNumberNormalizer.normalize("D-07-ABC123")).isNull();
    }

    @Test
    void pieceCodes_stillRejected() {
        assertThat(TrackingNumberNormalizer.normalize("P123456")).isNull();
        assertThat(TrackingNumberNormalizer.normalize("P 1 2 3 4 5 6")).isNull();
        assertThat(TrackingNumberNormalizer.normalize("PC-01HZX4K9T2B7QW3M5N8R6Y1V0D")).isNull();
        assertThat(TrackingNumberNormalizer.normalize("01HZX4K9T2B7QW3M5N8R6Y1V0D")).isNull();
    }

    @Test
    void onlyWhitespace_isNull() {
        assertThat(TrackingNumberNormalizer.normalize("  \t  ")).isNull();
        assertThat(TrackingNumberNormalizer.normalize("G - 0 2 - ")).isNull();
    }
}
