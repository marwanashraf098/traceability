package com.traceability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unpack-order decision 1 (approved by Marawan 2026-10-31): an order's status moves BACK to a pickable value
 * (new / confirmed / ready_to_pick / picking) only in FulfillService.unpackOrder. Two pre-existing sites are listed:
 * PickErrorReversalService — the 2026-08-23 one-shot reversal, hardcoded to one order of one tenant — and
 * LookupExchangeService.create — the replacement order it has just created goes new → 'confirmed' (C2, 2026-10-28;
 * never from a packed status).
 *
 * A SOURCE-TEXT SCAN of src/main/java (run from the project root, like NamedDecrementSetGuardTest): Java string
 * concatenations are joined first, then every {@code UPDATE orders | merchant_orders … SET … status = '<pickable>'}
 * is found. A parameterised status write ({@code status = ?}) is also listed, so a backward write can't hide behind a
 * bind: the only one is FulfillService.complete() (packed / self_pickup_pending — forward). Known limits: a
 * status written through a CASE expression, a stored procedure or a migration is not seen.
 */
class OrderStatusBackwardWriteGuardTest {

    private static final Path SRC_MAIN = Paths.get("src/main/java");

    /** A string concatenation ("…" + "…", comment lines between allowed) — joined so a statement reads as one. */
    private static final Pattern CONCAT = Pattern.compile("\"\\s*\\+\\s*(?://[^\\n]*\\n\\s*)*\"");
    static final Pattern BACKWARD = Pattern.compile(
        "(?is)UPDATE\\s+(?:merchant_)?orders\\b[^;\"]*?\\bSET\\b[^\"]*?\\bstatus\\s*=\\s*'(new|confirmed|ready_to_pick|picking)'");
    static final Pattern PARAMETERISED = Pattern.compile(
        "(?is)UPDATE\\s+(?:merchant_)?orders\\b[^;\"]*?\\bSET\\b[^\"]*?\\bstatus\\s*=\\s*\\?");

    /** file → the method that may hold the write (null = anywhere in the file). */
    private static final Map<String, String> BACKWARD_ALLOWED = Map.of(
        "FulfillService.java", "public UnpackOrderResult unpackOrder(",
        // 2026-08-23 incident one-shot (#2212102474) — hardcoded tenant / order / piece, refuses anything else.
        "PickErrorReversalService.java", "public void reverse(",
        // C2 Lookup exchange: the replacement order created in the same method, new → confirmed.
        "LookupExchangeService.java", "public Created create(");
    private static final Map<String, String> PARAMETERISED_ALLOWED = Map.of(
        "FulfillService.java", "public int complete(");

    record Hit(String file, String method) {}

    @Test
    void backwardStatusWrites_onlyInUnpackOrder_andTheListedOneShot() throws IOException {
        List<Hit> backward = scan(BACKWARD, BACKWARD_ALLOWED);
        assertThat(backward).as("every backward order-status write and the method it sits in")
            .containsExactlyInAnyOrder(
                new Hit("FulfillService.java", "public UnpackOrderResult unpackOrder("),
                new Hit("PickErrorReversalService.java", "public void reverse("),
                new Hit("LookupExchangeService.java", "public Created create("));
    }

    @Test
    void parameterisedStatusWrites_onlyComplete() throws IOException {
        assertThat(scan(PARAMETERISED, PARAMETERISED_ALLOWED))
            .containsExactly(new Hit("FulfillService.java", "public int complete("));
    }

    @Test
    void theScanCatchesAConcatenatedWrite() {
        String src = "class X { void f() { jdbc.update(\"UPDATE merchant_orders \" +\n" +
                     "    // comment\n    \"SET locked_by = NULL, status = 'new'::order_status WHERE id = ?\"); } }";
        assertThat(BACKWARD.matcher(join(src)).find()).isTrue();
        assertThat(BACKWARD.matcher(join("\"UPDATE orders SET status = 'packed' WHERE id = ?\"")).find()).isFalse();
        assertThat(BACKWARD.matcher(join("\"UPDATE allocations SET status = 'released'\"")).find()).isFalse();
    }

    /** Every match: the file, and the allowed method it sits in when there is one, else "<outside>". */
    private static List<Hit> scan(Pattern p, Map<String, String> allowed) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(SRC_MAIN)) {
            files = s.filter(f -> f.toString().endsWith(".java")).toList();
        }
        List<Hit> hits = new ArrayList<>();
        for (Path f : files) {
            String text = join(Files.readString(f));
            Matcher m = p.matcher(text);
            while (m.find()) {
                String name = f.getFileName().toString();
                String method = allowed.get(name);
                hits.add(new Hit(name, method != null && inside(text, method, m.start()) ? method : "<outside>"));
            }
        }
        return hits;
    }

    private static boolean inside(String text, String signature, int at) {
        int start = text.indexOf(signature);
        if (start < 0 || at < start) return false;
        int end = text.indexOf("\n    }\n", start);
        return end > at;
    }

    static String join(String source) {
        return CONCAT.matcher(source).replaceAll("");
    }
}
