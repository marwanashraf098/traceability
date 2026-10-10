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
 * pushStockTakeWriteOff, pushVoidCorrection, pushHoldEnter, (Step 5a, approved 2026-09-26)
 * pushExchangeDispatch, (Issue 2, 2026-10-08) pushTransferOut and (D1, 2026-10-10) pushPieceWriteOff —
 * each called from exactly ONE approved site. Adding a fourth decrement method, or a second caller of any of these three,
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
        new Rule("shopify.pushHoldEnter(",          "ShopifyInventoryService.java"),
        new Rule("shopify.pushExchangeDispatch(",   "ShopifyInventoryService.java"),
        // Issue 2 (2026-10-08) — the fifth.
        new Rule("shopify.pushTransferOut(",        "TransferShopifySync.java"),
        // D1 (2026-10-10) — the sixth: Lookup lost / destroyed of a Shopify-counted piece.
        new Rule("shopify.pushPieceWriteOff(",      "ShopifyInventoryService.java")
    );

    /** The closed set, by name — every ShopifyGateway method that takes a negative delta. */
    private static final java.util.Set<String> NAMED_DECREMENTS = java.util.Set.of(
        "pushStockTakeWriteOff", "pushVoidCorrection", "pushHoldEnter", "pushExchangeDispatch",
        "pushTransferOut", "pushPieceWriteOff");

    /**
     * D11: the Back-to-good reverse move (moveDamagedToAvailable, trigger damage_restore) is a MOVE, not
     * a decrement — it is not in the named set, takes a positive quantity, and refuses a non-positive one
     * before any network call. The gateway's decrement surface is exactly the six named methods: every
     * ShopifyGateway method named push* is one of them except the +1 (pushPieceIncrement).
     */
    @Test
    void damageRestore_isAMove_notADecrement_andTheSetIsExactlySix() {
        assertThat(NAMED_DECREMENTS).hasSize(6).doesNotContain("moveDamagedToAvailable");
        java.util.Set<String> pushMethods = new java.util.TreeSet<>();
        for (java.lang.reflect.Method m : com.traceability.integrations.shopify.ShopifyGateway.class.getDeclaredMethods()) {
            if (m.getName().startsWith("push")) pushMethods.add(m.getName());
        }
        pushMethods.remove("pushPieceIncrement");
        assertThat(pushMethods).containsExactlyInAnyOrderElementsOf(NAMED_DECREMENTS);
        // Its positive-only guard is asserted on the real gateway in PieceSyncClassificationWireTest.d3.
    }

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
