package com.traceability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar POS B5 — the event report stays apart from Analytics / Overview (source scan of src/main/java):
 * the report and leftovers services read only bazar tables, the event's transfer and the catalog / users they name;
 * they import nothing from analytics / overview; and no Analytics / Overview source mentions bazar at all (their
 * queries are untouched by the bazar slices).
 */
class BazarReportGuardTest {

    private static final Set<String> ALLOWED_TABLES = Set.of(
        "bazar_events", "bazar_sales", "bazar_payments", "bazar_day_closes", "bazar_receipts",
        "transfers", "transfer_pieces", "transfer_lines",
        // Top-up (V245, approved 2026-11-27): End event reads whether a send batch is still open (read only).
        "transfer_send_batches",
        "pieces", "variants", "products", "users", "locations", "piece_events", "sent", "sold");

    private static final Pattern TABLE = Pattern.compile("\\b(?:FROM|JOIN)\\s+([a-z_]+)\\b(?!\\.)");

    private static Set<String> tablesIn(String file) throws IOException {
        Set<String> out = new TreeSet<>();
        Matcher m = TABLE.matcher(Files.readString(Path.of(file)));
        while (m.find()) out.add(m.group(1).toLowerCase());
        return out;
    }

    @Test
    void reportAndLeftovers_readOnlyBazarTransferAndCatalogTables() throws IOException {
        for (String f : new String[] {
                "src/main/java/com/traceability/bazar/BazarReportService.java",
                "src/main/java/com/traceability/bazar/BazarEndService.java"}) {
            assertThat(tablesIn(f)).as(f).isSubsetOf(ALLOWED_TABLES);
        }
    }

    @Test
    void bazarImportsNothingFromAnalyticsOrOverview() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/traceability/bazar"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(f)).as(f.toString())
                    .doesNotContainPattern("import\\s+com\\.traceability\\.(analytics|overview)\\.");
            }
        }
    }

    @Test
    void analyticsAndOverview_neverMentionBazar() throws IOException {
        for (String dir : new String[] {"src/main/java/com/traceability/analytics", "src/main/java/com/traceability/overview"}) {
            try (Stream<Path> files = Files.walk(Path.of(dir))) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    assertThat(Files.readString(f).toLowerCase()).as(f.toString()).doesNotContain("bazar");
                }
            }
        }
    }
}
