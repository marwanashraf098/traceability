package com.traceability;

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
 * S1 — the shared scan-normalization vectors ({@code shared/scan-normalization-vectors.json}; S2's
 * TypeScript tests load the same file). Every vector asserts {@link ScanInputNormalizer#normalizePiece};
 * a vector with a {@code tracking} key also asserts {@link TrackingNumberNormalizer#normalize}.
 */
class ScanInputNormalizerVectorsTest {

    static final Path VECTORS = Path.of("shared", "scan-normalization-vectors.json");

    @TestFactory
    List<DynamicTest> sharedVectors() throws IOException {
        JsonNode doc = new ObjectMapper().readTree(VECTORS.toFile());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode v : doc.get("vectors")) {
            String input = v.get("input").asText();
            tests.add(DynamicTest.dynamicTest(v.get("name").asText(), () -> {
                assertThat(ScanInputNormalizer.normalizePiece(input))
                    .as("normalizePiece(%s)", input).isEqualTo(v.get("piece").asText());
                if (v.has("tracking")) {
                    String expected = v.get("tracking").isNull() ? null : v.get("tracking").asText();
                    assertThat(TrackingNumberNormalizer.normalize(input))
                        .as("TrackingNumberNormalizer.normalize(%s)", input).isEqualTo(expected);
                }
            }));
        }
        assertThat(tests).hasSizeGreaterThanOrEqualTo(20);
        return tests;
    }

    @Test
    void normalizePiece_isIdempotent_onEveryVector() throws IOException {
        for (JsonNode v : new ObjectMapper().readTree(VECTORS.toFile()).get("vectors")) {
            String once = ScanInputNormalizer.normalizePiece(v.get("input").asText());
            assertThat(ScanInputNormalizer.normalizePiece(once)).as(v.get("name").asText()).isEqualTo(once);
        }
    }

    @Test
    void normalizePiece_null_isNull() {
        assertThat(ScanInputNormalizer.normalizePiece(null)).isNull();
    }

    @Test
    void foldDigits_mapsBothArabicDigitBlocks_andNothingElse() {
        assertThat(ScanInputNormalizer.foldDigits("٠١٢٣٤٥٦٧٨٩")).isEqualTo("0123456789");
        assertThat(ScanInputNormalizer.foldDigits("۰۱۲۳۴۵۶۷۸۹")).isEqualTo("0123456789");
        assertThat(ScanInputNormalizer.foldDigits("P-ab ٫؛")).isEqualTo("P-ab ٫؛");
    }
}
