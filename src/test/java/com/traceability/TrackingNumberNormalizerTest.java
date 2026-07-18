package com.traceability;

import com.traceability.inventory.TrackingNumberNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TrackingNumberNormalizer}.
 *
 * Covers the example table from the AWB Scan Normalization build spec §2,
 * plus reject cases and the idempotence property.
 */
class TrackingNumberNormalizerTest {

    // ── Example table from spec §2 ────────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] ''{0}'' → ''{1}''")
    @CsvSource({
        "D-07-2944282510,    2944282510",
        "2944282510,         2944282510",
        "276495132,          276495132",
        "AB-12-314266180,    314266180",
    })
    void normalize_acceptedInputs(String raw, String expected) {
        assertThat(TrackingNumberNormalizer.normalize(raw.strip())).isEqualTo(expected.strip());
    }

    @Test
    void normalize_withSurroundingWhitespace() {
        assertThat(TrackingNumberNormalizer.normalize("  2944282510  ")).isEqualTo("2944282510");
    }

    // ── Reject cases ──────────────────────────────────────────────────────────

    @Test
    void normalize_null_returnsNull() {
        assertThat(TrackingNumberNormalizer.normalize(null)).isNull();
    }

    @Test
    void normalize_emptyString_returnsNull() {
        assertThat(TrackingNumberNormalizer.normalize("")).isNull();
    }

    @Test
    void normalize_blankString_returnsNull() {
        assertThat(TrackingNumberNormalizer.normalize("   ")).isNull();
    }

    @Test
    void normalize_trailingDash_returnsNull() {
        // "D-07-" → substring after last dash is "" → reject
        assertThat(TrackingNumberNormalizer.normalize("D-07-")).isNull();
    }

    @Test
    void normalize_prefixedAlpha_returnsNull() {
        // "D-07-ABC123" → "ABC123" is not all-digit → reject
        assertThat(TrackingNumberNormalizer.normalize("D-07-ABC123")).isNull();
    }

    @Test
    void normalize_bareAlpha_returnsNull() {
        assertThat(TrackingNumberNormalizer.normalize("ABCXYZ")).isNull();
    }

    @Test
    void normalize_mixedDigitsAndAlpha_returnsNull() {
        assertThat(TrackingNumberNormalizer.normalize("123ABC456")).isNull();
    }

    // ── Never strip all non-digits (spec §1 non-negotiable) ──────────────────

    @Test
    void normalize_doesNotStripAllNonDigits() {
        // "D-07-2944282510" must become "2944282510", NOT "072944282510"
        String result = TrackingNumberNormalizer.normalize("D-07-2944282510");
        assertThat(result).isEqualTo("2944282510");
        assertThat(result).doesNotContain("07"); // hub code must not bleed in
    }

    // ── Idempotence property: normalize(normalize(x)) == normalize(x) ─────────

    @ParameterizedTest
    @ValueSource(strings = {
        "D-07-2944282510",
        "2944282510",
        "  2944282510  ",
        "276495132",
        "AB-12-314266180",
        "D-07-",
        "D-07-ABC123",
        "",
        "BARE",
    })
    void normalize_isIdempotent(String input) {
        String once = TrackingNumberNormalizer.normalize(input);
        String twice = TrackingNumberNormalizer.normalize(once);
        assertThat(twice).isEqualTo(once);
    }
}
