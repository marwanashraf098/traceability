package com.traceability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * P4a — finds every SQL reference to the {@code orders} TABLE in Java source: {@code FROM | JOIN |
 * UPDATE | INTO} followed by {@code orders} (or {@code public.orders}) as a whole word, inside string
 * literals and text blocks. Adjacent literals joined with {@code +} are read as one string, so
 * {@code "... FROM " + "orders o"} is caught; comments and code outside strings are ignored (prose
 * like "reads from orders.raw" in a Javadoc is not SQL). {@code merchant_orders}, {@code orders_*}
 * and {@code order_items} never match.
 *
 * Each hit is keyed by its file (relative to src/main/java) and its enclosing member: the method
 * name, or {@code field:NAME} for a constant / field initializer. Overloads share a key.
 *
 * Known limit: SQL assembled from a non-literal ({@code "FROM " + table}) is invisible — no such
 * code exists for orders today; the leak test is the runtime backstop.
 */
final class OrdersSqlScanner {

    static final Pattern ORDERS_REF = Pattern.compile(
        "\\b(FROM|JOIN|UPDATE|INTO)\\s+(?:public\\.)?orders\\b(?!\\.)", Pattern.CASE_INSENSITIVE);

    record Hit(String file, String member, int line, String keyword, String snippet) {
        String key() { return file + "#" + member; }
    }

    private OrdersSqlScanner() {}

    static List<Hit> scanTree(Path root) throws IOException {
        List<Hit> hits = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                hits.addAll(scan(rel, Files.readString(p)));
            }
        }
        return hits;
    }

    /** One string chunk: the joined content of a run of literals, with the source line of each char. */
    private record Chunk(StringBuilder text, List<Integer> lines, List<Integer> codeOffsets) {}

    static List<Hit> scan(String file, String src) {
        // Pass 1: lex. Collect string chunks (adjacent literals joined by '+') and the code text
        // (strings and comments blanked) used to find the enclosing member.
        StringBuilder code = new StringBuilder(src.length());
        List<Chunk> chunks = new ArrayList<>();
        Chunk open = null;           // the chunk a following "+ literal" would extend
        boolean plusSeen = false;
        int line = 1;
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') { code.append(' '); i++; }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                code.append("  "); i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') { line++; code.append('\n'); } else code.append(' ');
                    i++;
                }
                code.append("  "); i += 2;
                continue;
            }
            if (c == '\'') {                       // char literal
                code.append(' '); i++;
                while (i < n && src.charAt(i) != '\'') {
                    if (src.charAt(i) == '\\') { code.append(' '); i++; }
                    code.append(' '); i++;
                }
                code.append(' '); i++;
                open = null; plusSeen = false;
                continue;
            }
            if (c == '"') {
                boolean textBlock = i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"';
                Chunk target = (open != null && plusSeen) ? open : new Chunk(new StringBuilder(), new ArrayList<>(), new ArrayList<>());
                if (target != open) chunks.add(target);
                int startOffset = code.length();
                if (textBlock) {
                    code.append("   "); i += 3;
                    while (i < n && !(src.startsWith("\"\"\"", i) && src.charAt(i - 1) != '\\')) {
                        char d = src.charAt(i);
                        if (d == '\\' && i + 1 < n) { target.text.append(' '); target.lines.add(line); target.codeOffsets.add(startOffset); code.append("  "); i += 2; continue; }
                        target.text.append(d); target.lines.add(line); target.codeOffsets.add(startOffset);
                        if (d == '\n') { line++; code.append('\n'); } else code.append(' ');
                        i++;
                    }
                    code.append("   "); i += 3;
                } else {
                    code.append(' '); i++;
                    while (i < n && src.charAt(i) != '"' && src.charAt(i) != '\n') {
                        char d = src.charAt(i);
                        if (d == '\\' && i + 1 < n) { target.text.append(' '); target.lines.add(line); target.codeOffsets.add(startOffset); code.append("  "); i += 2; continue; }
                        target.text.append(d); target.lines.add(line); target.codeOffsets.add(startOffset);
                        code.append(' '); i++;
                    }
                    code.append(' '); i++;
                }
                open = target; plusSeen = false;
                continue;
            }
            if (c == '+' && open != null && !plusSeen) { plusSeen = true; code.append(c); i++; continue; }
            if (c == '\n') { line++; code.append(c); i++; continue; }
            if (Character.isWhitespace(c)) { code.append(c); i++; continue; }
            // any other code token ends the literal run
            open = null; plusSeen = false;
            code.append(c); i++;
        }

        // Pass 2: member ranges from the blanked code.
        List<Member> members = members(code.toString());

        List<Hit> hits = new ArrayList<>();
        for (Chunk ch : chunks) {
            Matcher m = ORDERS_REF.matcher(ch.text);
            while (m.find()) {
                int at = m.start();
                int ln = ch.lines.get(at);
                int off = ch.codeOffsets.get(at);
                String member = memberAt(members, off);
                int s = Math.max(0, at - 20), e = Math.min(ch.text.length(), m.end() + 30);
                hits.add(new Hit(file, member, ln, m.group(1).toUpperCase(),
                    ch.text.substring(s, e).replaceAll("\\s+", " ").trim()));
            }
        }
        return hits;
    }

    private record Member(String name, int start, int end, int depth) {}

    private static final Pattern TYPE_HEADER = Pattern.compile("\\b(class|record|interface|enum)\\s+(\\w+)");
    private static final Pattern METHOD_HEADER = Pattern.compile("(\\w+)\\s*\\(");
    private static final java.util.Set<String> NOT_METHODS = java.util.Set.of(
        "if", "for", "while", "switch", "catch", "synchronized", "try", "else", "do", "return", "new",
        "throw", "case", "assert", "super", "this");
    private static final Pattern ANNOTATION = Pattern.compile("@[\\w.]+(\\s*\\([^()]*\\))?");
    private static final Pattern FIELD_NAME = Pattern.compile("(\\w+)\\s*=(?!=)");

    /**
     * Method bodies (by brace matching on the blanked code) and field initializers (statements
     * directly inside a type body). Lambdas and anonymous classes stay inside their method.
     */
    private static List<Member> members(String code) {
        List<Member> out = new ArrayList<>();
        // stack entries: kind 'T' type body, 'M' method body, 'B' other block; with start + name
        Deque<Object[]> stack = new ArrayDeque<>();
        int stmtStart = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                String header = ANNOTATION.matcher(code.substring(stmtStart, i)).replaceAll(" ");
                char kind;
                String name = null;
                boolean inMethod = stack.stream().anyMatch(e -> (char) e[0] == 'M');
                Object[] top = stack.peek();
                boolean directlyInType = top == null || (char) top[0] == 'T';
                Matcher tm = TYPE_HEADER.matcher(header);
                if (tm.find() && !header.contains("=") && !inMethod) {
                    kind = 'T';
                } else if (directlyInType && !header.contains("=") && !header.contains("->")) {
                    Matcher mm = METHOD_HEADER.matcher(header);
                    String found = null;
                    while (mm.find()) { if (!NOT_METHODS.contains(mm.group(1))) { found = mm.group(1); break; } }
                    if (found != null) { kind = 'M'; name = found; }
                    else kind = 'B';           // static / instance initializer, enum body …
                } else if (directlyInType && header.contains("=")) {
                    // a field initializer that opens a block (array initializer, anonymous class, lambda)
                    Matcher fm = FIELD_NAME.matcher(header);
                    kind = 'F';
                    name = fm.find() ? "field:" + fm.group(1) : "field:?";
                } else {
                    kind = 'B';
                }
                stack.push(new Object[]{kind, i, name, stmtStart});
                stmtStart = i + 1;
            } else if (c == '}') {
                Object[] e = stack.poll();
                if (e != null && ((char) e[0] == 'M' || (char) e[0] == 'F')) {
                    if ((char) e[0] == 'M') {
                        out.add(new Member((String) e[2], (int) e[1], i, stack.size()));
                        stmtStart = i + 1;
                    }
                    // a field's block ends the brace but not the statement (the ';' does)
                    if ((char) e[0] == 'F') {
                        // re-open a pseudo entry so the statement range is extended to the ';'
                        stack.push(new Object[]{'f', (int) e[3], e[2], (int) e[3]});
                    }
                    continue;
                }
                stmtStart = i + 1;
            } else if (c == ';') {
                Object[] top = stack.peek();
                if (top != null && (char) top[0] == 'f') {
                    stack.poll();
                    out.add(new Member((String) top[2], (int) top[3], i, stack.size()));
                } else if (top == null || (char) top[0] == 'T') {
                    String stmt = ANNOTATION.matcher(code.substring(stmtStart, i)).replaceAll(" ");
                    int eq = indexOfAssign(stmt);
                    if (eq >= 0) {
                        Matcher fm = FIELD_NAME.matcher(stmt);
                        if (fm.find()) out.add(new Member("field:" + fm.group(1), stmtStart, i, stack.size()));
                    }
                }
                stmtStart = i + 1;
            }
        }
        return out;
    }

    private static int indexOfAssign(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '=' && (i + 1 >= s.length() || s.charAt(i + 1) != '=')
                && (i == 0 || "!<>=".indexOf(s.charAt(i - 1)) < 0)) return i;
        }
        return -1;
    }

    private static String memberAt(List<Member> members, int offset) {
        Member best = null;
        for (Member m : members) {
            if (offset >= m.start && offset <= m.end && (best == null || m.start >= best.start)) best = m;
        }
        return best == null ? "?" : best.name;
    }
}
