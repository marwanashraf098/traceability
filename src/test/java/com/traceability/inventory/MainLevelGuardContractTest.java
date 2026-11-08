package com.traceability.inventory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Part D P2 (2026-11-08) — contract guard for the inactive-level guard (extends Part D's test 10, AdjustTransportGuardTest).
 * Source-level, like NamedDecrementSetGuardTest:
 *
 *   g1 — every inventory write call in application code ({@code shopify.push*}, {@code shopify.move*},
 *        {@code shopify.resendInventoryAdjustment}) sits in a method that checks the level at the Traced location
 *        BEFORE the call: {@code inactiveMainLevels(} — or, in MainReconcileWriter (which may only use the Main read,
 *        MainReconcileWriteGuardTest), {@code fetchMainLevels(} + {@code stocked()}. A new write path without the
 *        check fails here.
 *   g2 — the two gateway writes with their own pre-write read refuse on no active level: adjustInventoryQuantities
 *        and moveAvailableToDamaged throw ShopifyMainLevelInactiveException; the read itself answers null for a
 *        missing level (it no longer goes through fetchAvailableQuantities, which reads a null level as 0)
 *   g3 — every activation sends a FRESH key: no application code passes the old deterministic catalog key
 *        ({@code activationKey(}) to Shopify; every activateInventoryItem / ActivationRequest uses freshActivationKey()
 */
class MainLevelGuardContractTest {

    static final Path MAIN = Path.of("src/main/java/com/traceability");
    static final Path GATEWAY = MAIN.resolve("integrations/shopify/ShopifyHttpGateway.java");
    static final Pattern WRITE_CALL =
        Pattern.compile("\\bshopify\\.(push\\w+|move\\w+|resendInventoryAdjustment)\\(");
    /** A method declaration at class-member indentation (4 spaces). */
    static final Pattern METHOD_DECL =
        Pattern.compile("\\n    (?:public |private |protected |static |final |synchronized )*[\\w<>\\[\\],.? ]+ \\w+\\([^;{]*\\)\\s*(?:throws [\\w., ]+)?\\{");

    @Test
    void g1_everyInventoryWriteCall_isPrecededByTheLevelCheck() throws IOException {
        List<String> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                Matcher m = WRITE_CALL.matcher(src);
                while (m.find()) {
                    String before = enclosingMethodUpTo(src, m.start());
                    String where = f.getFileName() + " " + m.group(1);
                    found.add(where);
                    boolean checked = f.getFileName().toString().equals("MainReconcileWriter.java")
                        ? before.contains("shopify.fetchMainLevels(") && before.contains("stocked()")
                        : before.contains("shopify.inactiveMainLevels(");
                    if (!checked) missing.add(where);
                }
            }
        }
        // The known write paths are all seen (so the scan itself can't silently find nothing).
        assertThat(found).contains("ShopifyInventoryService.java pushVoidCorrection",
            "ShopifyInventoryService.java pushHoldEnter", "ShopifyInventoryService.java moveAvailableToDamaged",
            "ShopifyInventoryService.java pushPieceWriteOff", "ShopifyInventoryService.java pushPieceSold",
            "ShopifyInventoryService.java moveDamagedToAvailable", "ShopifyInventoryService.java pushPieceIncrement",
            "ShopifyInventoryService.java pushExchangeDispatch", "ShopifyInventoryService.java resendInventoryAdjustment",
            "TransferShopifySync.java pushTransferOut", "StockTakeShopifyPushJob.java pushStockTakeWriteOff",
            "MainReconcileWriter.java pushPart1MainDecrement", "MainReconcileWriter.java pushMainReconcile");
        assertThat(missing).as("write calls with no level check before them").isEmpty();
    }

    @Test
    void g2_gatewayWritesWithTheirOwnRead_refuseOnNoActiveLevel() throws IOException {
        String src = Files.readString(GATEWAY);
        for (String sig : List.of("public void adjustInventoryQuantities(", "public JsonNode moveAvailableToDamaged(")) {
            String body = methodBody(src, sig);
            int read = body.indexOf("currentAvailableQuantityOrNull(");
            int refuse = body.indexOf("throw new ShopifyMainLevelInactiveException(");
            int send = Math.max(body.indexOf("sendAdjust("), body.indexOf("sendPieceMove("));
            assertThat(read).as(sig + " reads first").isGreaterThanOrEqualTo(0);
            assertThat(refuse).as(sig + " refuses a null level before sending").isBetween(read, send);
        }
        String read = methodBody(src, "private Integer currentAvailableQuantityOrNull(");
        assertThat(read).doesNotContain("fetchAvailableQuantities(").contains("return null;");
    }

    @Test
    void g3_everyActivation_sendsAFreshKey() throws IOException {
        Pattern activation = Pattern.compile(
            "(shopify\\.activateInventoryItem\\(|new ShopifyGateway\\.ActivationRequest\\()([^;]*?)\\);", Pattern.DOTALL);
        int calls = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                assertThat(src.replace("public static String activationKey(", ""))
                    .as(f.getFileName() + " passes the old catalog key").doesNotContain("activationKey(");
                Matcher m = activation.matcher(src);
                while (m.find()) {
                    calls++;
                    assertThat(m.group(2)).as(f.getFileName() + ": " + m.group(0)).contains("freshActivationKey()");
                }
            }
        }
        assertThat(calls).as("catalog batch, seed, increment, piece +1").isGreaterThanOrEqualTo(4);
    }

    /** The enclosing method's text from its declaration up to {@code pos}. */
    private static String enclosingMethodUpTo(String src, int pos) {
        Matcher d = METHOD_DECL.matcher(src);
        int start = 0;
        while (d.find() && d.start() < pos) start = d.start();
        return src.substring(start, pos);
    }

    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        assertThat(start).as("found " + signature).isGreaterThanOrEqualTo(0);
        int open = src.indexOf('{', src.indexOf(')', start));
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(start, i + 1);
        }
        throw new AssertionError("unbalanced " + signature);
    }
}
