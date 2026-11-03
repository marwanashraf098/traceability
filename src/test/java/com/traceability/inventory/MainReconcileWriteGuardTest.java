package com.traceability.inventory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Main reconcile R2 (V203, approved 2026-11-04; replaces R1's report-only guard): the two named writes live in
 * MainReconcileWriter only, and every one of its Shopify writes names the Traced Main location ({@code main.gid()});
 * the other reconciler classes reach Shopify only through the two reads and name no inventory mutation; the reads issue
 * no GraphQL mutation. NamedDecrementSetGuardTest pins the callers and the set (nine).
 */
class MainReconcileWriteGuardTest {

    static final Path INV = Path.of("src/main/java/com/traceability/inventory");
    static final List<String> READ_ONLY_CLASSES = List.of("MainReconcileService", "MainReconcileRules", "MainReconcileQueue", "MainReconcileJob");
    static final List<String> WRITE_WORDS = List.of("mutation", "inventoryAdjustQuantities", "inventorySetQuantities",
        "inventorySetOnHandQuantities", "inventoryMoveQuantities", "inventoryActivate", "adjustInventoryQuantities",
        "pushPiece", "pushTransferOut", "pushStockTakeWriteOff", "pushVoidCorrection", "pushHoldEnter", "pushExchangeDispatch",
        "pushPart1MainDecrement", "pushMainReconcile", "moveDamaged", "moveAvailable");

    @Test
    void reconcilerClasses_otherThanTheWriter_callOnlyShopifyReads() throws IOException {
        Set<String> calls = new TreeSet<>();
        for (String cls : READ_ONLY_CLASSES) {
            String s = Files.readString(INV.resolve(cls + ".java"));
            Matcher m = Pattern.compile("\\bshopify\\.(\\w+)\\(").matcher(s);
            while (m.find()) calls.add(m.group(1));
            for (String w : WRITE_WORDS) assertThat(s).as("%s mentions %s", cls, w).doesNotContain(w);
        }
        assertThat(calls).containsExactlyInAnyOrder("fetchMainLevels", "fetchMainLevelsPage");
    }

    @Test
    void theWriter_callsOnlyTheTwoNamedWritesAndTheRead_everyWriteAtMain() throws IOException {
        String s = Files.readString(INV.resolve("MainReconcileWriter.java"));
        Set<String> calls = new TreeSet<>();
        Matcher m = Pattern.compile("\\bshopify\\.(\\w+)\\(").matcher(s);
        while (m.find()) calls.add(m.group(1));
        assertThat(calls).containsExactlyInAnyOrder("fetchMainLevels", "pushPart1MainDecrement", "pushMainReconcile");
        // Every Shopify call's location argument (the 3rd for a read, the 4th for a write) is main.gid().
        Matcher w = Pattern.compile("shopify\\.(push\\w+)\\(([^;]*?)\\);", Pattern.DOTALL).matcher(s);
        int writes = 0;
        while (w.find()) {
            writes++;
            String[] args = w.group(2).split(",");
            assertThat(args[3].trim()).as(w.group(1) + " location").isEqualTo("main.gid()");
        }
        assertThat(writes).isEqualTo(2);
        assertThat(s).contains("shopify.fetchMainLevels(shop, token, main.gid(), ");
        for (String forbidden : List.of("inventorySetOnHandQuantities", "inventorySetQuantities", "adjustInventoryQuantities"))
            assertThat(s).doesNotContain(forbidden);
    }

    @Test
    void theMainLevelQueries_areReadsOnly() throws IOException {
        String gw = Files.readString(Path.of("src/main/java/com/traceability/integrations/shopify/ShopifyHttpGateway.java"));
        for (String q : List.of("MAIN_LEVELS_QUERY", "MAIN_LEVELS_PAGE_QUERY")) {
            Matcher m = Pattern.compile(q + " = \"\"\"(.*?)\"\"\"", Pattern.DOTALL).matcher(gw);
            assertThat(m.find()).as(q).isTrue();
            assertThat(m.group(1)).as(q).startsWith("\n            query ").doesNotContain("mutation");
        }
    }
}
