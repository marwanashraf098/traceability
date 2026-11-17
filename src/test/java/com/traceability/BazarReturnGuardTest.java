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
 * Bazar POS B3 — the return events ('bazar_returned', 'bazar_return_damaged') have ONE owner, BazarSaleService
 * (source scan of src/main/java), and the return / exchange code reaches no Shopify writer.
 */
class BazarReturnGuardTest {

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
    void returnEvents_onlyInBazarSaleService() throws IOException {
        assertThat(filesMatching(Pattern.compile("\"bazar_returned\"|\"bazar_return_damaged\"")))
            .containsExactly("BazarSaleService.java");
    }

    /** Return / exchange live in BazarSaleService: it reaches no Shopify writer (the piece is still away). */
    @Test
    void returnAndExchange_reachNoShopifyWriter() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/traceability/bazar/BazarSaleService.java"));
        assertThat(src)
            .doesNotContainPattern("Shopify\\w*(Service|Gateway|Sync)\\b")
            .doesNotContainPattern("\\bpush[A-Z]\\w*\\(")
            .doesNotContainPattern("import\\s+com\\.traceability\\.integrations");
    }
}
