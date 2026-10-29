package com.traceability.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.inventory.ScanInputNormalizer;
import com.traceability.inventory.TrackingNumberNormalizer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Warehouse places S2 — the place-sticker vectors ({@code shared/place-code-vectors.json}). Every vector asserts
 * {@link ScanInputNormalizer#normalizePlace}, and that the piece and tracking normalizers read it exactly as they
 * did before S2 (the piece / tracking vectors themselves stay in scan-normalization-vectors.json, untouched).
 */
class PlaceCodeVectorsTest {

    static final Path VECTORS = Path.of("shared", "place-code-vectors.json");

    @TestFactory
    List<DynamicTest> placeVectors() throws IOException {
        JsonNode doc = new ObjectMapper().readTree(VECTORS.toFile());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode v : doc.get("vectors")) {
            String input = v.get("input").asText();
            tests.add(DynamicTest.dynamicTest(v.get("name").asText(), () -> {
                String place = v.get("place").isNull() ? null : v.get("place").asText();
                assertThat(ScanInputNormalizer.normalizePlace(input)).as("normalizePlace(%s)", input).isEqualTo(place);
                assertThat(ScanInputNormalizer.normalizePiece(input)).as("normalizePiece(%s)", input)
                    .isEqualTo(v.get("piece").asText());
                String tracking = v.get("tracking").isNull() ? null : v.get("tracking").asText();
                assertThat(TrackingNumberNormalizer.normalize(input)).as("tracking(%s)", input).isEqualTo(tracking);
            }));
        }
        assertThat(tests).hasSizeGreaterThanOrEqualTo(20);
        return tests;
    }

    @Test
    void normalizePlace_isIdempotent_andNullSafe() throws IOException {
        for (JsonNode v : new ObjectMapper().readTree(VECTORS.toFile()).get("vectors")) {
            String once = ScanInputNormalizer.normalizePlace(v.get("input").asText());
            if (once != null) assertThat(ScanInputNormalizer.normalizePlace(once)).as(v.get("name").asText()).isEqualTo(once);
        }
        assertThat(ScanInputNormalizer.normalizePlace(null)).isNull();
    }

    /** As narrow as the P-key recovery: exactly the two Q-key glyphs, one leading glyph, then 6+ digits. */
    @Test
    @SuppressWarnings("unchecked")
    void qKeyRecovery_isTwoGlyphsOnly() throws Exception {
        java.lang.reflect.Field f = ScanInputNormalizer.class.getDeclaredField("Q_KEY_GLYPHS");
        f.setAccessible(true);
        assertThat((java.util.Set<Object>) f.get(null)).containsExactlyInAnyOrder((Object) 0x0636, (Object) 0x064E);
        for (int cp = 0x0600; cp <= 0x06FF; cp++) {
            String in = new String(Character.toChars(cp)) + "000107";
            String expected = (cp == 0x0636 || cp == 0x064E) ? "Q000107" : null;
            if (cp >= 0x0660 && cp <= 0x0669 || cp >= 0x06F0 && cp <= 0x06F9) expected = null;   // a digit, not a glyph
            assertThat(ScanInputNormalizer.normalizePlace(in)).as("U+%04X + 6 digits", cp).isEqualTo(expected);
        }
    }

    @Test
    void everyStickerTheCounterIssues_readsBack() {
        for (long v : new long[] {1, 107, 999_999, 1_000_000, 12_345_678}) {
            String code = PlaceCodes.formatScanCode(v);
            assertThat(ScanInputNormalizer.normalizePlace(code)).isEqualTo(code);
        }
    }
}
