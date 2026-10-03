package com.traceability.review;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review mode S5 — o7: the review package never reaches past RLS. No owner pool, no
 * @FlywayDataSource, no BYPASSRLS connection, no raw writes to pieces / piece_events (those go through
 * InventoryLedger). Unlike DemoSeeder, which holds the one approved carve-out.
 */
class ReviewTenantSeederGuardTest {

    private static final Pattern FORBIDDEN = Pattern.compile(
        "ownerDataSource|ownerJdbc|FlywayDataSource|BYPASSRLS|@Qualifier\\(\"owner|" +
        "INSERT INTO pieces|UPDATE pieces|INSERT INTO piece_events|DELETE FROM");

    @Test
    void reviewPackage_neverUsesTheOwnerPool_norWritesPiecesRaw() throws Exception {
        Path dir = Path.of("src/main/java/com/traceability/review");
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) { files = s.filter(p -> p.toString().endsWith(".java")).toList(); }
        assertThat(files).isNotEmpty();
        for (Path f : files) {
            String code = Files.readString(f).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
            assertThat(FORBIDDEN.matcher(code).find()).as(f + " must stay app_user + RLS + InventoryLedger").isFalse();
        }
    }
}
