package com.traceability.analytics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Analytics slice 10 — the Shopify cost / stock READ can never become an inventory WRITE:
 *   - the reader, the sync pass, its job and the webhook handler issue no GraphQL mutation except
 *     bulkOperationRunQuery (it starts a read export) and name no inventory / product write;
 *   - the reader talks to Shopify only through ShopifyGateway.executeGraphQLPublic;
 *   - nothing outside the analytics package references them — except the webhook processor's
 *     dispatch of the two inventory topics — and nothing outside it reads the read columns (so the
 *     FR-17 v2 write path, ShopifyInventoryService and the named decrement set, never sees them).
 */
class ShopifyInventoryReadGuardTest {

    static final Path MAIN = Path.of("src/main/java/com/traceability");
    static final List<String> READ_CLASSES = List.of("ShopifyInventoryReader", "AnalyticsInventorySyncService",
        "AnalyticsInventorySyncJob", "AnalyticsInventoryWebhookHandler", "HttpBulkDownloader", "AnalyticsInventorySyncController");
    static final List<String> WRITE_WORDS = List.of("inventoryAdjustQuantities", "inventorySetOnHandQuantities",
        "inventorySetQuantities", "inventoryMoveQuantities", "inventoryActivate", "inventoryDeactivate", "inventoryItemUpdate",
        "productUpdate", "productVariantUpdate", "productVariantsBulkUpdate", "productSet");

    static String src(String cls) throws IOException {
        return Files.readString(MAIN.resolve("analytics/" + cls + ".java"));
    }

    @Test
    void theReadClasses_haveNoWriteMutation() throws IOException {
        for (String cls : READ_CLASSES) {
            String s = src(cls);
            Matcher m = Pattern.compile("\\bmutation\\b[^{]*\\{\\s*(\\w+)").matcher(s);
            while (m.find()) assertThat(m.group(1)).as("%s: a mutation", cls).isEqualTo("bulkOperationRunQuery");
            for (String w : WRITE_WORDS) assertThat(s).as("%s mentions %s", cls, w).doesNotContain(w);
        }
    }

    @Test
    void theReader_usesOnlyTheGenericGraphqlPath() throws IOException {
        Matcher m = Pattern.compile("\\bgateway\\.(\\w+)\\(").matcher(src("ShopifyInventoryReader"));
        Set<String> calls = new TreeSet<>();
        while (m.find()) calls.add(m.group(1));
        assertThat(calls).containsExactly("executeGraphQLPublic");
    }

    @Test
    void nothingOutsideAnalytics_referencesTheReadPathOrItsColumns() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.startsWith(MAIN.resolve("analytics"))) continue;
                String s = Files.readString(f);
                boolean processor = f.getFileName().toString().equals("ShopifyWebhookProcessorJob.java");
                for (String cls : READ_CLASSES) {
                    if (Pattern.compile("\\b" + cls + "\\b").matcher(s).find()
                            && !(processor && cls.equals("AnalyticsInventoryWebhookHandler"))) offenders.add(f + " → " + cls);
                }
                for (String col : List.of("stock_available_shopify", "stock_available_shopify_traced", "variant_shopify_levels", "analytics_inventory_sync",
                                          "cost_source", "shopify_cost_flag", "stock_inventory_item_id")) {
                    if (Pattern.compile("\\b" + col + "\\b").matcher(s).find()) offenders.add(f + " → " + col);
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}
