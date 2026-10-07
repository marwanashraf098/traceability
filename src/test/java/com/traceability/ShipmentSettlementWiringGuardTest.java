package com.traceability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Analytics slice 3 — every SQL statement in src/main that writes shipments.raw (an INSERT INTO
 * shipments whose column list has raw, or an UPDATE shipments that assigns raw) must be followed,
 * in the same file within {@value #WINDOW} lines of the statement's end, by ShipmentSettlement.apply(...) — otherwise a new
 * payload path would store Bosta's wallet without the settlement columns ever seeing it.
 */
class ShipmentSettlementWiringGuardTest {

    static final int WINDOW = 25;

    static final Pattern INSERT = Pattern.compile("INSERT\\s+INTO\\s+shipments\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    static final Pattern UPDATE = Pattern.compile("UPDATE\\s+shipments(\\s+\\w+)?\\s+SET\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern RAW_COLUMN = Pattern.compile("(^|[\\s,])raw([\\s,]|$)");
    static final Pattern RAW_ASSIGN = Pattern.compile("(^|[\\s,.])raw\\s*=[^=]");

    record Writer(Path file, int line) {}

    @Test
    void everyShipmentsRawWriter_callsTheSettlementExtractor() throws IOException {
        List<Writer> writers = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                String flat = flatten(src);
                List<String> lines = List.of(src.split("\n", -1));
                for (int start : rawWrites(flat)) {
                    int line = lineOf(flat, start);
                    writers.add(new Writer(f, line));
                    boolean applied = false;
                    for (int i = line - 1; i < Math.min(lines.size(), line - 1 + WINDOW); i++) {
                        if (lines.get(i).contains("ShipmentSettlement.apply")) applied = true;
                    }
                    if (!applied) missing.add(f + ":" + line);
                }
            }
        }
        // The scanner must actually see the known writers (webhook ×2, raw refresher, link ×2).
        assertThat(writers).as("shipments.raw writers found: %s", writers).hasSizeGreaterThanOrEqualTo(5);
        assertThat(missing).as("shipments.raw written without ShipmentSettlement.apply within %d lines", WINDOW)
                .isEmpty();
    }

    /** Offsets (in the flattened source) where each statement that writes shipments.raw ends. */
    static List<Integer> rawWrites(String flat) {
        List<Integer> out = new ArrayList<>();
        Matcher ins = INSERT.matcher(flat);
        while (ins.find()) {
            if (RAW_COLUMN.matcher(ins.group(1)).find()) out.add(statementEnd(flat, ins.end()));
        }
        Matcher upd = UPDATE.matcher(flat);
        while (upd.find()) {
            int end = statementEnd(flat, upd.end());
            if (RAW_ASSIGN.matcher(flat.substring(upd.end(), end)).find()) out.add(end);
        }
        return out;
    }

    /** The end of the string literal / text block the statement sits in. */
    static int statementEnd(String flat, int from) {
        int best = flat.length();
        for (String end : new String[] {"\"\"\"", "\",", "\")", "\";"}) {
            int i = flat.indexOf(end, from);
            if (i >= 0 && i < best) best = i;
        }
        return best;
    }

    /**
     * Joins "..." + "..." concatenations so a statement split across lines reads as one, keeping
     * the newlines so offsets still map to the original line numbers.
     */
    static String flatten(String src) {
        return Pattern.compile("\"\\s*\\+\\s*\\n\\s*\"").matcher(src)
                .replaceAll(m -> "\n".repeat((int) m.group().chars().filter(c -> c == '\n').count()));
    }

    static int lineOf(String flat, int offset) {
        return 1 + (int) flat.substring(0, offset).chars().filter(c -> c == '\n').count();
    }
}
