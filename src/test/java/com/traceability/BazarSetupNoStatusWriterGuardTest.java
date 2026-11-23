package com.traceability;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar B9b — the guided bazar setup receives ONLY through ReceivingService's public methods: InventoryLedger stays the
 * only piece-status writer (batchReceive inside finalize), and the setup never writes to Shopify itself (the existing
 * receiving +N at Traced Main is ReceivingService's). Source guard over the bazar setup's classes.
 */
class BazarSetupNoStatusWriterGuardTest {

    static final List<String> FILES = List.of(
        "src/main/java/com/traceability/bazar/BazarSetupService.java",
        "src/main/java/com/traceability/bazar/BazarSetupController.java");

    @Test
    void noPieceWriter_noLedgerCall_noShopifyWrite() throws Exception {
        for (String f : FILES) {
            String src = Files.readString(Path.of(f)).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
            String lower = src.toLowerCase().replaceAll("\\s+", " ");
            assertThat(src).as(f).doesNotContain("InventoryLedger").doesNotContain("batchReceive")
                .doesNotContain(".transition(").doesNotContain("ShopifyGateway ").doesNotContain("ShopifyInventoryService");
            assertThat(lower).as(f)
                .doesNotContain("update pieces").doesNotContain("insert into pieces")
                .doesNotContain("insert into piece_events").doesNotContain("update piece_events")
                .doesNotContain("insert into receipts").doesNotContain("insert into receipt_lines")
                .doesNotContain("update receipts").doesNotContain("shopify_inventory_adjustments");
        }
        String svc = Files.readString(Path.of(FILES.get(0)));
        assertThat(svc).contains("receiving.createSession(").contains("receiving.addLine(").contains("receiving.finalize(");
    }
}
