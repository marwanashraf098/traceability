package com.traceability.receiving;

import com.traceability.inventory.ReceivingService;
import com.traceability.labels.FlexibleLabeling;
import com.traceability.labels.UntaggedException;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Receiving import (slice 2b) — fill an OPEN receiving session's lines from a Shopify inventory export.
 *
 * <ul>
 *   <li>{@link #preview}: reads the file, writes NOTHING. Quantity = "On hand (new)" as-is. One location per
 *       file (several distinct Location values → the whole file is refused); the Location value itself is
 *       ignored. Empty / "not stocked" / 0 are skipped; negative, non-integer and too-large values are
 *       unmatched rows. Matching, first rule with exactly one variant wins: SKU (non-empty and unique in
 *       the tenant) → Handle + Option1/2/3 values ("Default Title" = a single-variant product) → product
 *       Title + variant title (the option values joined " / ", the way Shopify builds a variant title).
 *       Several candidates → unmatched "ambiguous", never a guess. Two rows for one variant → both are
 *       unmatched "duplicate". A matched row whose "On hand (new)" equals "On hand (current)" is flagged.</li>
 *   <li>"On hand (new)" empty on every row (a raw Shopify export — the stock is in "On hand (current)"):
 *       {@code newColumnEmpty} tells the screen so; only an explicit second preview with
 *       {@code column = current} reads "On hand (current)" as the quantity received (same matching, skipping
 *       and validation; the same-as-Shopify flag is off, since the quantity IS Shopify's number). Never automatic.</li>
 *   <li>{@link #apply}: adds the chosen quantities to the session's lines (to an existing line of the
 *       variant, else a new line) — exactly what typing them in would do. Nothing is received until the
 *       normal finalize; finalize, labels and Shopify +N are unchanged.</li>
 *   <li>{@link #template}: every active variant in Shopify's export layout (CSV or .xlsx), Location = the main
 *       warehouse, On hand (current) = Traced's available pieces there (reference only), On hand (new) empty.</li>
 * </ul>
 * Every tenant may import into a print-and-stick session; a count-only session only while the tenant has
 * flexible labeling (as for creating one).
 */
@Service
public class ReceivingImportService {

    public static final int MAX_QUANTITY = 100_000;
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+(\\.0+)?");
    private static final Pattern NUMBER  = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)");

    public record MatchedRow(int rowNumber, UUID variantId, String productTitle, String variantTitle, String sku,
                             String imageUrl, int quantity, Integer onHandCurrent, boolean sameAsShopify, String matchedBy) {}
    public record UnmatchedRow(int rowNumber, String handle, String title, String options, String sku, String value,
                               String reason) {}
    /**
     * quantityColumn: "new" (On hand (new), the default) or "current" (On hand (current), only when asked for).
     * newColumnEmpty: every row that isn't "not stocked" has an empty On hand (new) (and there is at least one).
     */
    public record Preview(String location, int units, List<MatchedRow> matched, List<UnmatchedRow> unmatched, int skipped,
                          String quantityColumn, boolean newColumnEmpty) {}

    public static final String COLUMN_NEW = "new", COLUMN_CURRENT = "current";
    public record ApplyLine(UUID variantId, Integer quantity) {}
    public record Applied(int linesAdded, int linesUpdated, int units) {}
    public record Template(byte[] bytes, String contentType, String filename) {}

    /** One tenant variant, as the matcher sees it. */
    record CatalogVariant(UUID id, UUID productId, String productTitle, String handle, String variantTitle,
                          List<String> options, String sku, String imageUrl, boolean giftCard, boolean notTracked,
                          int productVariantCount) {}

    private final JdbcTemplate jdbc;

    public ReceivingImportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── preview ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Preview preview(UUID sessionId, String filename, byte[] bytes) {
        return preview(sessionId, filename, bytes, COLUMN_NEW);
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID sessionId, String filename, byte[] bytes, String column) {
        UUID tenantId = TenantContext.require();
        boolean current = COLUMN_CURRENT.equals(column);
        if (column != null && !current && !COLUMN_NEW.equals(column)) throw ImportException.badColumn();
        String quantityHeader = current ? ShopifyInventoryFile.ON_HAND_CURRENT : ShopifyInventoryFile.ON_HAND_NEW;
        requireImportable(sessionId, tenantId);
        List<ShopifyInventoryFile.FileRow> rows = ShopifyInventoryFile.parse(filename, bytes);

        Set<String> locations = rows.stream().map(r -> r.get(ShopifyInventoryFile.LOCATION))
            .filter(s -> !s.isBlank()).collect(Collectors.toCollection(TreeSet::new));
        if (locations.size() > 1) throw ImportException.severalLocations();

        Matcher matcher = new Matcher(loadCatalog(tenantId));
        List<UnmatchedRow> unmatched = new ArrayList<>();
        Map<UUID, List<MatchedRow>> byVariant = new LinkedHashMap<>();
        int skipped = 0;
        boolean anyStocked = false, anyNewValue = false;
        for (ShopifyInventoryFile.FileRow r : rows) {
            String newValue = r.get(ShopifyInventoryFile.ON_HAND_NEW);
            if (!newValue.equalsIgnoreCase("not stocked")) {
                anyStocked = true;
                if (!newValue.isBlank()) anyNewValue = true;
            }
            String value = r.get(quantityHeader);
            String reason = null;
            Integer qty = null;
            if (value.isBlank() || value.equalsIgnoreCase("not stocked")) { skipped++; continue; }
            if (!NUMBER.matcher(value).matches()) reason = "not_integer";
            else if (!INTEGER.matcher(value).matches()) reason = "not_integer";
            else {
                long q = Long.parseLong(value.replaceFirst("\\.0+$", "").replace("+", ""));
                if (q == 0) { skipped++; continue; }
                if (q < 0) reason = "negative";
                else if (q > MAX_QUANTITY) reason = "too_large";
                else qty = (int) q;
            }
            if (reason != null) { unmatched.add(unmatched(r, reason, value)); continue; }

            Matcher.Result m = matcher.match(r);
            if (m.variant() == null) { unmatched.add(unmatched(r, m.reason(), value)); continue; }
            CatalogVariant v = m.variant();
            if (v.giftCard())   { unmatched.add(unmatched(r, "gift_card", value)); continue; }
            if (v.notTracked()) { unmatched.add(unmatched(r, "not_tracked", value)); continue; }
            Integer onHandCurrent = parseIntOrNull(r.get(ShopifyInventoryFile.ON_HAND_CURRENT));
            byVariant.computeIfAbsent(v.id(), k -> new ArrayList<>()).add(new MatchedRow(r.rowNumber(), v.id(),
                v.productTitle(), v.variantTitle(), v.sku(), v.imageUrl(), qty, onHandCurrent,
                !current && onHandCurrent != null && onHandCurrent.intValue() == qty, m.by()));
        }

        List<MatchedRow> matched = new ArrayList<>();
        for (List<MatchedRow> group : byVariant.values()) {
            if (group.size() == 1) { matched.add(group.get(0)); continue; }
            Set<Integer> dup = group.stream().map(MatchedRow::rowNumber).collect(Collectors.toSet());
            for (ShopifyInventoryFile.FileRow r : rows) {
                if (dup.contains(r.rowNumber())) unmatched.add(unmatched(r, "duplicate", r.get(quantityHeader)));
            }
        }
        matched.sort(Comparator.comparingInt(MatchedRow::rowNumber));
        unmatched.sort(Comparator.comparingInt(UnmatchedRow::rowNumber));
        int units = matched.stream().mapToInt(MatchedRow::quantity).sum();
        return new Preview(locations.isEmpty() ? null : locations.iterator().next(), units, matched, unmatched, skipped,
            current ? COLUMN_CURRENT : COLUMN_NEW, anyStocked && !anyNewValue);
    }

    private static UnmatchedRow unmatched(ShopifyInventoryFile.FileRow r, String reason, String value) {
        String options = Stream3.join(r.get(ShopifyInventoryFile.OPT1), r.get(ShopifyInventoryFile.OPT2), r.get(ShopifyInventoryFile.OPT3));
        return new UnmatchedRow(r.rowNumber(), r.get(ShopifyInventoryFile.HANDLE), r.get(ShopifyInventoryFile.TITLE),
            options, r.get(ShopifyInventoryFile.SKU), value, reason);
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || !INTEGER.matcher(s.strip()).matches()) return null;
        try { return Integer.parseInt(s.strip().replaceFirst("\\.0+$", "").replace("+", "")); }
        catch (NumberFormatException e) { return null; }
    }

    // ── apply ─────────────────────────────────────────────────────────────────

    @Transactional
    public Applied apply(UUID sessionId, List<ApplyLine> lines) {
        UUID tenantId = TenantContext.require();
        requireImportable(sessionId, tenantId);
        if (lines == null || lines.isEmpty()) throw ImportException.nothingToAdd();
        if (lines.size() > ShopifyInventoryFile.MAX_ROWS) throw ImportException.tooManyRows();
        Map<UUID, Integer> qty = new LinkedHashMap<>();
        for (ApplyLine l : lines) {
            if (l == null || l.variantId() == null || l.quantity() == null
                || l.quantity() < 1 || l.quantity() > MAX_QUANTITY) throw ImportException.badQuantity();
            qty.merge(l.variantId(), l.quantity(), Integer::sum);
        }
        if (qty.values().stream().anyMatch(q -> q > MAX_QUANTITY)) throw ImportException.badQuantity();
        Integer known = jdbc.queryForObject(
            "SELECT count(*)::int FROM variants WHERE tenant_id = ? AND id = ANY(?::uuid[])",
            Integer.class, tenantId, qty.keySet().toArray(new UUID[0]));
        if (known == null || known != qty.size()) throw ImportException.unknownVariant();

        int added = 0, updated = 0, units = 0;
        for (Map.Entry<UUID, Integer> e : qty.entrySet()) {
            // Added to the variant's existing line (its oldest, like the grid's own edits); else a new line.
            int n = jdbc.update(
                "UPDATE receipt_lines SET quantity = quantity + ? WHERE id = (" +
                "  SELECT id FROM receipt_lines WHERE receipt_id = ? AND variant_id = ? AND tenant_id = ? " +
                "  ORDER BY created_at, id LIMIT 1)",
                e.getValue(), sessionId, e.getKey(), tenantId);
            if (n == 0) {
                jdbc.update("INSERT INTO receipt_lines (id, tenant_id, receipt_id, variant_id, quantity) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), tenantId, sessionId, e.getKey(), e.getValue());
                added++;
            } else {
                updated++;
            }
            units += e.getValue();
        }
        return new Applied(added, updated, units);
    }

    /** Open inbound session of this tenant; a count-only one only while the tenant has flexible labeling. */
    private void requireImportable(UUID sessionId, UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT status, mode FROM receipts WHERE id = ? AND tenant_id = ? AND kind = 'inbound'", sessionId, tenantId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
        if (!"open".equals(rows.get(0).get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is not open");
        }
        if (ReceivingService.MODE_COUNT_ONLY.equals(rows.get(0).get("mode")) && !FlexibleLabeling.enabled(jdbc, tenantId)) {
            throw UntaggedException.countOnlyUnavailable();
        }
    }

    // ── catalog + matching ───────────────────────────────────────────────────

    private List<CatalogVariant> loadCatalog(UUID tenantId) {
        return jdbc.query(
            "SELECT v.id, v.product_id, p.title AS product_title, p.raw ->> 'handle' AS handle, v.title AS variant_title, " +
            "       v.option1, v.option2, v.option3, v.sku, p.image_url, " +
            "       (p.raw ->> 'gift_card' = 'true' OR lower(btrim(coalesce(p.raw ->> 'product_type', ''))) = 'gift card') AS gift_card, " +
            "       (v.raw -> 'inventory_management' IS NOT NULL AND coalesce(v.raw ->> 'inventory_management', '') = '') AS not_tracked, " +
            "       count(*) OVER (PARTITION BY v.product_id)::int AS product_variant_count " +
            "FROM variants v JOIN products p ON p.id = v.product_id AND p.tenant_id = v.tenant_id " +
            "WHERE v.tenant_id = ?",
            (rs, i) -> new CatalogVariant(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getString("product_title"), rs.getString("handle"), rs.getString("variant_title"),
                optionValues(rs.getString("option1"), rs.getString("option2"), rs.getString("option3"), rs.getString("variant_title")),
                rs.getString("sku"), rs.getString("image_url"), rs.getBoolean("gift_card"), rs.getBoolean("not_tracked"),
                rs.getInt("product_variant_count")),
            tenantId);
    }

    /** option1..3 when stored, else the variant title split on " / " (how Shopify builds it). */
    static List<String> optionValues(String o1, String o2, String o3, String variantTitle) {
        if (o1 != null && !o1.isBlank()) {
            List<String> out = new ArrayList<>();
            for (String o : new String[] { o1, o2, o3 }) if (o != null && !o.isBlank()) out.add(o.strip());
            return out;
        }
        if (variantTitle == null || variantTitle.isBlank()) return List.of();
        return Arrays.stream(variantTitle.split(" / ")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    static final class Stream3 {
        static String join(String a, String b, String c) {
            return java.util.stream.Stream.of(a, b, c).filter(s -> s != null && !s.isBlank()).map(String::strip)
                .collect(Collectors.joining(" / "));
        }
    }

    static final class Matcher {
        record Result(CatalogVariant variant, String by, String reason) {}

        private final Map<String, List<CatalogVariant>> bySku = new HashMap<>();
        private final Map<String, List<CatalogVariant>> byHandle = new HashMap<>();
        private final Map<String, List<CatalogVariant>> byTitle = new HashMap<>();

        Matcher(List<CatalogVariant> catalog) {
            for (CatalogVariant v : catalog) {
                if (v.sku() != null && !v.sku().isBlank()) bySku.computeIfAbsent(v.sku().strip(), k -> new ArrayList<>()).add(v);
                if (v.handle() != null && !v.handle().isBlank()) byHandle.computeIfAbsent(key(v.handle()), k -> new ArrayList<>()).add(v);
                if (v.productTitle() != null) byTitle.computeIfAbsent(key(v.productTitle()), k -> new ArrayList<>()).add(v);
            }
        }

        Result match(ShopifyInventoryFile.FileRow r) {
            boolean ambiguous = false;
            String sku = r.get(ShopifyInventoryFile.SKU);
            if (!sku.isBlank()) {
                List<CatalogVariant> c = bySku.getOrDefault(sku.strip(), List.of());
                if (c.size() == 1) return new Result(c.get(0), "sku", null);
                ambiguous |= c.size() > 1;
            }
            List<String> opts = new ArrayList<>();
            for (String h : List.of(ShopifyInventoryFile.OPT1, ShopifyInventoryFile.OPT2, ShopifyInventoryFile.OPT3)) {
                String o = r.get(h);
                if (!o.isBlank()) opts.add(o.strip());
            }
            String handle = r.get(ShopifyInventoryFile.HANDLE);
            if (!handle.isBlank()) {
                List<CatalogVariant> c = byOptions(byHandle.getOrDefault(key(handle), List.of()), opts);
                if (c.size() == 1) return new Result(c.get(0), "handle", null);
                ambiguous |= c.size() > 1;
            }
            String title = r.get(ShopifyInventoryFile.TITLE);
            if (!title.isBlank()) {
                List<CatalogVariant> c = byOptions(byTitle.getOrDefault(key(title), List.of()), opts);
                if (c.size() == 1) return new Result(c.get(0), "title", null);
                ambiguous |= c.size() > 1;
            }
            return new Result(null, null, ambiguous ? "ambiguous" : "not_in_traced");
        }

        /** "Default Title" (alone) = the product's only variant; otherwise every option value, in order. */
        private static List<CatalogVariant> byOptions(List<CatalogVariant> candidates, List<String> opts) {
            boolean defaultTitle = opts.size() == 1 && opts.get(0).equalsIgnoreCase("Default Title");
            List<CatalogVariant> out = new ArrayList<>();
            for (CatalogVariant v : candidates) {
                if (defaultTitle) {
                    if (v.productVariantCount() == 1) out.add(v);
                } else if (sameOptions(v.options(), opts)) {
                    out.add(v);
                }
            }
            return out;
        }

        private static boolean sameOptions(List<String> a, List<String> b) {
            if (a.size() != b.size() || a.isEmpty()) return false;
            for (int i = 0; i < a.size(); i++) if (!key(a.get(i)).equals(key(b.get(i)))) return false;
            return true;
        }

        private static String key(String s) {
            return s.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        }
    }

    // ── template ──────────────────────────────────────────────────────────────

    /**
     * Every ACTIVE variant of the tenant in Shopify's inventory export layout, sorted by product title then
     * option values. Handle / option names come from the stored Shopify product when Traced has them
     * (otherwise empty — the import still matches by SKU or by title + options).
     */
    @Transactional(readOnly = true)
    public Template template(String format) {
        UUID tenantId = TenantContext.require();
        boolean xlsx = !"csv".equalsIgnoreCase(format);
        List<Map<String, Object>> main = jdbc.queryForList(
            "SELECT id, name FROM locations WHERE tenant_id = ? AND is_fulfillment", tenantId);
        UUID mainId = main.isEmpty() ? null : (UUID) main.get(0).get("id");
        String mainName = main.isEmpty() ? "" : (String) main.get(0).get("name");

        record T(String handle, String title, List<String> names, List<String> values, String sku, Integer available) {}
        List<T> rows = jdbc.query(
            "SELECT p.raw ->> 'handle' AS handle, p.title, " +
            "       p.raw -> 'options' -> 0 ->> 'name' AS n1, p.raw -> 'options' -> 1 ->> 'name' AS n2, " +
            "       p.raw -> 'options' -> 2 ->> 'name' AS n3, " +
            "       v.option1, v.option2, v.option3, v.title AS variant_title, v.sku, " +
            "       (SELECT count(*)::int FROM pieces pc WHERE pc.tenant_id = v.tenant_id AND pc.variant_id = v.id " +
            "          AND pc.status = 'available' AND pc.current_location_id = ?) AS available " +
            "FROM variants v JOIN products p ON p.id = v.product_id AND p.tenant_id = v.tenant_id " +
            "WHERE v.tenant_id = ? AND p.status = 'active'",
            (rs, i) -> {
                List<String> values = optionValues(rs.getString("option1"), rs.getString("option2"),
                    rs.getString("option3"), rs.getString("variant_title"));
                List<String> names = new ArrayList<>(Arrays.asList(rs.getString("n1"), rs.getString("n2"), rs.getString("n3")));
                if (values.size() == 1 && "Default Title".equalsIgnoreCase(values.get(0)) && names.get(0) == null) names.set(0, "Title");
                return new T(rs.getString("handle"), rs.getString("title"), names, values, rs.getString("sku"),
                    mainId == null ? null : rs.getInt("available"));
            },
            mainId, tenantId);
        rows = new ArrayList<>(rows);
        rows.sort(Comparator.comparing((T t) -> t.title() == null ? "" : t.title().toLowerCase(Locale.ROOT))
            .thenComparing(t -> String.join(" / ", t.values()).toLowerCase(Locale.ROOT)));

        List<List<String>> table = new ArrayList<>();
        table.add(ShopifyInventoryFile.SHOPIFY_HEADERS);
        for (T t : rows) {
            String[] cells = new String[ShopifyInventoryFile.SHOPIFY_HEADERS.size()];
            Arrays.fill(cells, "");
            cells[0] = nz(t.handle());
            cells[1] = nz(t.title());
            for (int o = 0; o < 3; o++) {
                if (o < t.values().size()) {
                    cells[2 + 2 * o] = nz(t.names().get(o));
                    cells[3 + 2 * o] = t.values().get(o);
                }
            }
            cells[8] = nz(t.sku());
            cells[11] = mainName;
            cells[17] = t.available() == null ? "" : String.valueOf(t.available());
            table.add(List.of(cells));
        }
        return xlsx
            ? new Template(ShopifyInventoryFile.writeXlsx(table, Set.of(17)),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "traced-receiving-template.xlsx")
            : new Template(ShopifyInventoryFile.writeCsv(table), "text/csv; charset=utf-8", "traced-receiving-template.csv");
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
