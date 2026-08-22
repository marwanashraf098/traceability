package com.traceability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-13.x / FR-21 §7 extension (CLAUDE.md, approved by Marawan 2026-08-23): Shopify decrements
 * are sanctioned ONLY through a named, closed set of dedicated single-attempt gateway methods —
 * pushStockTakeWriteOff, pushVoidCorrection, pushHoldEnter — each called from exactly ONE
 * approved site. Adding a fourth decrement method, or a second caller of any of these three,
 * requires the same explicit approval this set itself required.
 *
 * This is a SOURCE-TEXT SCAN, not reflection: reflection (see ShopifyInventoryTest si11) proves
 * a method does or doesn't EXIST, but cannot enumerate its CALLERS. Known limits, noted rather
 * than hidden:
 *   - Assumes the test process's working directory is the project root (src/main/java resolves
 *     from CWD) — matches every other Gradle/Maven-run convention in this repo, but would need
 *     adjusting if that ever changes.
 *   - Matches the literal source text "shopify.<methodName>(" — a caller that aliases the
 *     ShopifyGateway field to something other than "shopify", or invokes it via reflection or a
 *     method reference, would not be caught. No such caller exists today (grep-verified against
 *     the whole src/main tree at the time this test was written); this test guards against the
 *     ordinary, direct-call way the set could quietly grow, not against deliberate evasion.
 */
class NamedDecrementSetGuardTest {

    private static final Path SRC_MAIN = Paths.get("src/main/java");

    private record Rule(String methodCallPattern, String expectedCallerFile) {}

    private static final List<Rule> RULES = List.of(
        new Rule("shopify.pushStockTakeWriteOff(", "StockTakeShopifyPushJob.java"),
        new Rule("shopify.pushVoidCorrection(",     "ShopifyInventoryService.java"),
        new Rule("shopify.pushHoldEnter(",          "ShopifyInventoryService.java")
    );

    @Test
    void namedDecrementMethods_calledOnlyFromApprovedSites() throws IOException {
        List<Path> javaFiles;
        try (Stream<Path> paths = Files.walk(SRC_MAIN)) {
            javaFiles = paths.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(javaFiles).as("sanity check: src/main/java must resolve from CWD").isNotEmpty();

        for (Rule rule : RULES) {
            List<String> callers = new ArrayList<>();
            for (Path file : javaFiles) {
                String content = Files.readString(file);
                if (content.contains(rule.methodCallPattern())) {
                    callers.add(file.getFileName().toString());
                }
            }
            assertThat(callers)
                .as("only " + rule.expectedCallerFile() + " may call " + rule.methodCallPattern()
                    + " — a second caller (or zero callers) means the named decrement set drifted")
                .containsExactly(rule.expectedCallerFile());
        }
    }
}
