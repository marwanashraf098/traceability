package com.traceability;

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
 * Guard (2026-10-26): two migrations with the same version can merge silently when their file names differ (two
 * branches each took V184), and Flyway then refuses to start. This scans src/main/resources/db/migration — the
 * SOURCE folder, never target/classes, which can hold a stale copy of a renamed file — and fails when:
 *   - two files share one version number, or
 *   - a version between V1 and the highest is missing (except the known gap below).
 * No database, no Spring: it runs in milliseconds and before anything else can trip over the duplicate.
 */
class MigrationVersionGuardTest {

    static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.+\\.sql$");

    /** V38 was never used (MigrationSmokeTest says so too). Any other gap is a mistake. */
    static final Set<Integer> KNOWN_GAPS = Set.of(38);

    @Test
    void every_migration_version_is_used_exactly_once_with_no_unexplained_gap() throws IOException {
        Map<Integer, List<String>> byVersion = new TreeMap<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            files.map(p -> p.getFileName().toString()).forEach(name -> {
                Matcher m = VERSIONED.matcher(name);
                if (m.matches()) byVersion.computeIfAbsent(Integer.parseInt(m.group(1)), v -> new ArrayList<>()).add(name);
            });
        }
        assertThat(byVersion).as("no versioned migration found in " + MIGRATIONS.toAbsolutePath()).isNotEmpty();

        List<String> duplicates = byVersion.entrySet().stream().filter(e -> e.getValue().size() > 1)
            .map(e -> "V" + e.getKey() + ": " + e.getValue()).toList();
        assertThat(duplicates).as("two migrations share a version — renumber the one that was never applied").isEmpty();

        int highest = ((TreeMap<Integer, List<String>>) byVersion).lastKey();
        List<Integer> missing = new ArrayList<>();
        for (int v = 1; v <= highest; v++) {
            if (!byVersion.containsKey(v) && !KNOWN_GAPS.contains(v)) missing.add(v);
        }
        assertThat(missing).as("a migration version is skipped (only " + KNOWN_GAPS + " is a known gap)").isEmpty();
    }
}
