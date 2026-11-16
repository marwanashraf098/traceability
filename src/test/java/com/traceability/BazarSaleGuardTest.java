package com.traceability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar POS B2 — the bazar sale edges and tables have ONE owner, BazarSaleService (source scan of src/main/java):
 * the undo edge sold → out_on_transfer, the 'bazar_sold' / 'bazar_sale_undone' events, and every write to
 * bazar_sales / bazar_payments / bazar_sale_counters. And BazarSaleService reaches no Shopify writer.
 */
class BazarSaleGuardTest {

    private static List<String> filesMatching(Pattern p) throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            return files.filter(f -> f.toString().endsWith(".java"))
                .filter(f -> {
                    try { return p.matcher(Files.readString(f)).find(); }
                    catch (IOException e) { throw new RuntimeException(e); }
                })
                .map(f -> f.getFileName().toString())
                .sorted()
                .toList();
        }
    }

    @Test
    void undoEdge_onlyInBazarSaleService() throws IOException {
        assertThat(filesMatching(Pattern.compile("PieceStatus\\.SOLD\\s*,\\s*PieceStatus\\.OUT_ON_TRANSFER")))
            .containsExactly("BazarSaleService.java");
    }

    @Test
    void bazarSaleEvents_onlyInBazarSaleService() throws IOException {
        assertThat(filesMatching(Pattern.compile("\"bazar_sold\"|\"bazar_sale_undone\"")))
            .containsExactly("BazarSaleService.java");
    }

    @Test
    void bazarSaleTables_writtenOnlyByBazarSaleService() throws IOException {
        assertThat(filesMatching(Pattern.compile(
            "(?i)(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+bazar_(sales|payments|sale_counters)\\b")))
            .containsExactly("BazarSaleService.java");
    }

    @Test
    void bazarSaleService_reachesNoShopifyWriter() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/traceability/bazar/BazarSaleService.java"));
        assertThat(src).doesNotContainPattern("import\\s+com\\.traceability\\.integrations\\.shopify");
        assertThat(src).doesNotContainPattern("Shopify\\w*(Service|Gateway|Sync)\\b");
    }
}
