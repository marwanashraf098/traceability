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
 * Main reconcile R1 is REPORT-ONLY (V200): its classes reach Shopify only through the two read methods (and the
 * item-id read the seed uses), name no inventory mutation, and those reads issue no GraphQL mutation. The named
 * decrement set is untouched (NamedDecrementSetGuardTest still counts seven — pushMainReconcile is R2).
 */
class MainReconcileReportOnlyGuardTest {

    static final Path INV = Path.of("src/main/java/com/traceability/inventory");
    static final List<String> CLASSES = List.of("MainReconcileService", "MainReconcileRules", "MainReconcileQueue", "MainReconcileJob");
    static final List<String> WRITE_WORDS = List.of("mutation", "inventoryAdjustQuantities", "inventorySetQuantities",
        "inventorySetOnHandQuantities", "inventoryMoveQuantities", "inventoryActivate", "adjustInventoryQuantities",
        "pushPiece", "pushTransferOut", "pushStockTakeWriteOff", "pushVoidCorrection", "pushHoldEnter", "pushExchangeDispatch",
        "moveDamaged", "moveAvailable");

    @Test
    void reconcilerClasses_callOnlyShopifyReads_andNameNoMutation() throws IOException {
        Set<String> calls = new TreeSet<>();
        for (String cls : CLASSES) {
            String s = Files.readString(INV.resolve(cls + ".java"));
            Matcher m = Pattern.compile("\\bshopify\\.(\\w+)\\(").matcher(s);
            while (m.find()) calls.add(m.group(1));
            for (String w : WRITE_WORDS) assertThat(s).as("%s mentions %s", cls, w).doesNotContain(w);
        }
        assertThat(calls).containsExactlyInAnyOrder("fetchMainLevels", "fetchMainLevelsPage");
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
