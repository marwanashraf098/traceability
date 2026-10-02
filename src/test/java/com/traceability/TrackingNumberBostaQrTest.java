package com.traceability;

import com.traceability.inventory.TrackingNumberNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S6 — Bosta's waybill QR holds "BOSTA_&lt;tracking number&gt;" (camera spike 2026-10-02). The
 * normalizer drops a case-insensitive BOSTA_ prefix and requires the rest to be all digits.
 * Existing cases stay in TrackingNumberNormalizerTest / TrackingNumberSpacesTest, unchanged.
 */
class TrackingNumberBostaQrTest {

    @Test
    void bostaQr_yieldsTheDigits() {
        assertThat(TrackingNumberNormalizer.normalize("BOSTA_8484805699")).isEqualTo("8484805699");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bosta_8484805699", "Bosta_8484805699", "BoStA_8484805699", " BOSTA_8484805699\n", "BOSTA_ 8484 805699"})
    void bostaQr_prefixCaseInsensitive_afterWhitespaceRemoval(String raw) {
        assertThat(TrackingNumberNormalizer.normalize(raw)).isEqualTo("8484805699");
    }

    @ParameterizedTest
    @ValueSource(strings = {"BOSTA_", "BOSTA_84848A5699", "BOSTA_abc", "BOSTA_D-07-8484805699", "BOSTA__8484805699", "BOSTA-8484805699X"})
    void bostaQr_withAnythingButDigits_isRejected(String raw) {
        assertThat(TrackingNumberNormalizer.normalize(raw)).isNull();
    }

    @Test
    void otherForms_unchanged() {
        assertThat(TrackingNumberNormalizer.normalize("G-02-8484805699")).isEqualTo("8484805699");   // camera, top barcode
        assertThat(TrackingNumberNormalizer.normalize("8484805699")).isEqualTo("8484805699");
        assertThat(TrackingNumberNormalizer.normalize("BOSTA8484805699")).isNull();                  // no underscore: not the QR form
    }
}
