package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.courier.GenericTrackingKey;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-Bosta couriers G1 — the shared GenericTrackingKey vectors ({@code shared/generic-tracking-key-vectors.json};
 * G2's scan path must load the same file).
 */
class GenericTrackingKeyVectorsTest {

    static final Path VECTORS = Path.of("shared", "generic-tracking-key-vectors.json");

    @TestFactory
    List<DynamicTest> vectors() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(VECTORS));
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode v : root.path("vectors")) {
            String input = v.path("input").asText();
            String expected = v.path("key").isNull() ? null : v.path("key").asText();
            tests.add(DynamicTest.dynamicTest(v.path("name").asText(),
                () -> assertThat(GenericTrackingKey.of(input)).as(input).isEqualTo(expected)));
        }
        assertThat(tests).hasSizeGreaterThanOrEqualTo(15);
        return tests;
    }

    @org.junit.jupiter.api.Test
    void nullInput() {
        assertThat(GenericTrackingKey.of(null)).isNull();
    }
}
