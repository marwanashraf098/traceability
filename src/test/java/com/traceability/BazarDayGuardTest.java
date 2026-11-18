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
 * Bazar POS B4 — {@code bazar_day_closes} has ONE writer, BazarDayService (source scan of src/main/java), and nothing
 * ever UPDATEs or DELETEs it (append-only: a re-close is a new row). BazarDayService writes no sale or money row and
 * reaches no Shopify writer.
 */
class BazarDayGuardTest {

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
    void dayCloses_insertedOnlyByBazarDayService() throws IOException {
        assertThat(filesMatching(Pattern.compile("(?i)INSERT\\s+INTO\\s+bazar_day_closes\\b")))
            .containsExactly("BazarDayService.java");
    }

    @Test
    void dayCloses_neverUpdatedOrDeleted() throws IOException {
        assertThat(filesMatching(Pattern.compile("(?i)(UPDATE|DELETE\\s+FROM)\\s+bazar_day_closes\\b"))).isEmpty();
    }

    @Test
    void bazarDayService_writesNoSaleOrMoney_andReachesNoShopifyWriter() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/traceability/bazar/BazarDayService.java"));
        assertThat(src).doesNotContainPattern("(?i)(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+bazar_(sales|payments)\\b");
        assertThat(src).doesNotContainPattern("import\\s+com\\.traceability\\.integrations\\.shopify");
        assertThat(src).doesNotContainPattern("Shopify\\w*(Service|Gateway|Sync)\\b");
        assertThat(src).doesNotContain("InventoryLedger");
    }
}
