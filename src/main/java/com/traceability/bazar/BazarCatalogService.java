package com.traceability.bazar;

import com.traceability.portal.ExchangeOptions;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Bazar POS B7a / B7b — what's for sale at an event without scanning: products grouped by product + colour, each
 * with its short number (#12), sizes, bazar prices and how many pieces of each size are still at the bazar. Feeds the
 * phone's Pick-a-product sheet, the computer's product grid, "12" / "#12" in the scan field, and the A4 price sheet.
 *
 * <ul>
 *   <li>The event's variants: the ones on its linked transfer or with a bazar price (the same set as the prices
 *       list). "Left" = the variant's pieces still open on the transfer (no outcome) and {@code out_on_transfer} —
 *       exactly what a tap-sale can pick ({@link BazarSaleService} picks one of them).</li>
 *   <li>Grouping: per product, {@link ExchangeOptions#colourAndSize} — a size-axis value is the size; the other
 *       option values are the colour. No colour → one group per product.</li>
 *   <li>Numbers ({@link #ensureNumbers}): bazar_event_product_numbers (V239), the ONE writer is here — first by
 *       product title, then colour; a group that appears later gets the next free number; never renumbered.</li>
 *   <li>Who: the event's sellers (role in seller_roles, not removed) and owners / managers. Read-only for stock and
 *       money; the only write is assigning missing numbers.</li>
 * </ul>
 */
@Service
public class BazarCatalogService {

    static final int MAX_NUMBER = 999;
    private static final Set<String> MANAGERS = Set.of("owner", "manager");

    private final JdbcTemplate jdbc;
    private final com.traceability.inventory.VariantStockService stock;

    public BazarCatalogService(JdbcTemplate jdbc, com.traceability.inventory.VariantStockService stock) {
        this.stock = stock;
        this.jdbc = jdbc;
    }

    /** One size of a product group. */
    public record Size(UUID variantId, String title, String size, BigDecimal price, int left) {}

    /** A product + colour group with its number. */
    public record Group(Integer number, UUID productId, String title, String colour, String imageUrl, String category,
                        List<Size> sizes) {
        int left() { return sizes.stream().mapToInt(Size::left).sum(); }
    }

    /** The event header + its groups (ordered by number). */
    public record Catalog(UUID eventId, String eventName, String status, String locationName, String startsOn,
                          String endsOn, List<Group> groups) {}

    /** GET /bazar-events/{id}/catalog. */
    @Transactional
    public Map<String, Object> catalogView(UUID eventId, BazarSaleService.Actor actor) {
        return view(catalog(eventId, actor), null);
    }

    /** The catalog's response shape; {@code committed} (B7d send stock) adds each size's online committed demand. */
    private static Map<String, Object> view(Catalog c, Map<UUID, Long> committed) {
        Map<String, Integer> categories = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<Map<String, Object>> products = new ArrayList<>();
        for (Group g : c.groups()) {
            if (g.category() != null) categories.merge(g.category(), 1, Integer::sum);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("number", g.number());
            p.put("product_id", g.productId());
            p.put("title", g.title());
            p.put("colour", g.colour());
            p.put("image_url", g.imageUrl());
            p.put("category", g.category());
            p.put("left", g.left());
            List<BigDecimal> prices = g.sizes().stream().map(Size::price).filter(Objects::nonNull).toList();
            p.put("price_min", prices.isEmpty() ? null : prices.stream().min(BigDecimal::compareTo).get().setScale(2));
            p.put("price_max", prices.isEmpty() ? null : prices.stream().max(BigDecimal::compareTo).get().setScale(2));
            List<Map<String, Object>> sizes = new ArrayList<>();
            for (Size s : g.sizes()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("variant_id", s.variantId());
                m.put("title", s.title());
                m.put("size", s.size());
                m.put("price", s.price() == null ? null : s.price().setScale(2));
                m.put("left", s.left());
                if (committed != null) m.put("committed", committed.getOrDefault(s.variantId(), 0L));
                sizes.add(m);
            }
            p.put("sizes", sizes);
            products.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("event_id", c.eventId());
        out.put("event_name", c.eventName());
        out.put("event_status", c.status());
        List<Map<String, Object>> cats = new ArrayList<>();
        categories.forEach((name, count) -> cats.add(Map.of("name", name, "count", count)));
        out.put("categories", cats);
        out.put("products", products);
        return out;
    }

    /** The catalog (numbers assigned first). Sellers of the event and owners / managers. */
    @Transactional
    public Catalog catalog(UUID eventId, BazarSaleService.Actor actor) {
        UUID tenantId = BazarEndService.requireEnabledTenant(jdbc);
        List<Map<String, Object>> evs = jdbc.queryForList(
            "SELECT e.id, e.name, e.status, e.seller_roles, e.transfer_id, e.starts_on::text AS starts_on, " +
            "       e.ends_on::text AS ends_on, l.name AS location_name " +
            "FROM bazar_events e LEFT JOIN locations l ON l.id = e.location_id AND l.tenant_id = e.tenant_id " +
            "WHERE e.id = ? AND e.tenant_id = ?", eventId, tenantId);
        if (evs.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_EVENT_NOT_FOUND,
                "Bazar not found.", "لم يتم العثور على البازار.", HttpStatus.NOT_FOUND);
        }
        Map<String, Object> ev = evs.get(0);
        requireSellerOrManager(ev, actor);
        return build(tenantId, eventId, ev);
    }

    /** B7a: Start assigns the event's numbers (first by product title, then colour); later reads only add. */
    void assignAtStart(UUID tenantId, UUID eventId) {
        build(tenantId, eventId, loadEvent(tenantId, eventId));
    }

    private Map<String, Object> loadEvent(UUID tenantId, UUID eventId) {
        return jdbc.queryForMap(
            "SELECT e.id, e.name, e.status, e.seller_roles, e.transfer_id, e.starts_on::text AS starts_on, " +
            "       e.ends_on::text AS ends_on, l.name AS location_name " +
            "FROM bazar_events e LEFT JOIN locations l ON l.id = e.location_id AND l.tenant_id = e.tenant_id " +
            "WHERE e.id = ? AND e.tenant_id = ?", eventId, tenantId);
    }

    private Catalog build(UUID tenantId, UUID eventId, Map<String, Object> ev) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT v.id AS variant_id, v.title AS variant_title, v.raw::text AS variant_raw, " +
            "       pr.id AS product_id, pr.title AS product_title, pr.raw::text AS product_raw, pr.image_url, " +
            "       pr.product_type_norm AS category, bp.price, " +
            "       (SELECT count(*) FROM transfer_pieces tp JOIN pieces pc ON pc.id = tp.piece_id AND pc.tenant_id = tp.tenant_id " +
            "         WHERE tp.transfer_id = ? AND tp.tenant_id = ? AND tp.outcome IS NULL " +
            "           AND pc.status = 'out_on_transfer' AND pc.variant_id = v.id)::int AS left_count " +
            "FROM variants v JOIN products pr ON pr.id = v.product_id AND pr.tenant_id = v.tenant_id " +
            "LEFT JOIN bazar_event_prices bp ON bp.event_id = ? AND bp.variant_id = v.id AND bp.tenant_id = v.tenant_id " +
            "WHERE v.tenant_id = ? AND v.id IN (" + BazarService.eventVariantsSql() + ") " +
            "ORDER BY pr.title, pr.id, v.title, v.id",
            ev.get("transfer_id"), tenantId, eventId, tenantId, eventId, tenantId, eventId, tenantId);

        Map<String, Group> byKey = groupRows(rows);
        List<Group> groups = new ArrayList<>();
        Map<String, Integer> numbers = ensureNumbers(tenantId, eventId, new ArrayList<>(byKey.values()));
        for (Map.Entry<String, Group> e : byKey.entrySet()) {
            Group g = e.getValue();
            groups.add(new Group(numbers.get(e.getKey()), g.productId(), g.title(), g.colour(), g.imageUrl(),
                g.category(), g.sizes()));
        }
        groups.sort(Comparator.comparing((Group g) -> g.number() == null ? Integer.MAX_VALUE : g.number())
            .thenComparing(Group::title, String.CASE_INSENSITIVE_ORDER));
        return new Catalog(eventId, (String) ev.get("name"), (String) ev.get("status"),
            (String) ev.get("location_name"), (String) ev.get("starts_on"), (String) ev.get("ends_on"), groups);
    }

    /**
     * Rows (variant_id, variant_title, variant_raw, product_id, product_title, product_raw, image_url, category, price,
     * left_count) → groups per product, then per colour (ExchangeOptions' colour / size split), sizes in size order;
     * keyed product|colour, no numbers yet.
     */
    private static Map<String, Group> groupRows(List<Map<String, Object>> rows) {
        // Group per product, then per colour.
        Map<UUID, List<Map<String, Object>>> byProduct = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            byProduct.computeIfAbsent((UUID) r.get("product_id"), k -> new ArrayList<>()).add(r);
        }
        Map<String, Group> byKey = new LinkedHashMap<>();
        for (List<Map<String, Object>> family : byProduct.values()) {
            List<ExchangeOptions.FamilyMember> members = family.stream()
                .map(r -> new ExchangeOptions.FamilyMember((UUID) r.get("variant_id"), (String) r.get("variant_title"),
                    (String) r.get("variant_raw"))).toList();
            Map<UUID, ExchangeOptions.ColourSize> split =
                ExchangeOptions.colourAndSize(members, (String) family.get(0).get("product_raw"));
            Map<String, List<Size>> sizesByColour = new LinkedHashMap<>();
            for (Map<String, Object> r : family) {
                ExchangeOptions.ColourSize cs = split.get((UUID) r.get("variant_id"));
                String colourKey = cs.colour() == null ? "" : cs.colour();
                sizesByColour.computeIfAbsent(colourKey, k -> new ArrayList<>()).add(new Size(
                    (UUID) r.get("variant_id"), (String) r.get("variant_title"), cs.size(),
                    (BigDecimal) r.get("price"), ((Number) r.get("left_count")).intValue()));
            }
            Map<String, Object> head = family.get(0);
            for (Map.Entry<String, List<Size>> e : sizesByColour.entrySet()) {
                List<Size> sizes = new ArrayList<>(e.getValue());
                sizes.sort(SIZE_ORDER);
                Group g = new Group(null, (UUID) head.get("product_id"), (String) head.get("product_title"),
                    e.getKey().isEmpty() ? null : e.getKey(), (String) head.get("image_url"),
                    blankToNull((String) head.get("category")), sizes);
                byKey.put(key(g.productId(), e.getKey()), g);
            }
        }
        return byKey;
    }

    /**
     * B7d — "Send by count": what the main warehouse can send to this bazar, as the catalog's shape — every size
     * with available pieces at Main (labelled and untagged — status available, located at the main warehouse; the
     * same count Inventory's on hand uses), its bazar price (the event's row, else the event's rule on the Shopify
     * price) and {@code committed}: online orders' demand for it, derived at read time by
     * {@link com.traceability.inventory.VariantStockService} exactly as Inventory shows it. The event's existing
     * #numbers are shown; none is assigned here. Owners / managers (the controller).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> sendStockView(UUID eventId) {
        UUID tenantId = BazarEndService.requireEnabledTenant(jdbc);
        List<Map<String, Object>> evs = jdbc.queryForList(
            "SELECT e.id, e.name, e.status, e.discount_pct, e.round_to, e.starts_on::text AS starts_on, " +
            "       e.ends_on::text AS ends_on, l.name AS location_name " +
            "FROM bazar_events e LEFT JOIN locations l ON l.id = e.location_id AND l.tenant_id = e.tenant_id " +
            "WHERE e.id = ? AND e.tenant_id = ?", eventId, tenantId);
        if (evs.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_EVENT_NOT_FOUND,
                "Bazar not found.", "لم يتم العثور على البازار.", HttpStatus.NOT_FOUND);
        }
        Map<String, Object> ev = evs.get(0);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT v.id AS variant_id, v.title AS variant_title, v.raw::text AS variant_raw, v.price AS shopify_price, " +
                "       pr.id AS product_id, pr.title AS product_title, pr.raw::text AS product_raw, pr.image_url, " +
                "       pr.product_type_norm AS category, bp.price, a.n AS left_count " +
                "FROM (SELECT p.variant_id, count(*)::int AS n FROM pieces p " +
                "      WHERE p.tenant_id = ? AND p.status = 'available' " +
                "        AND p.current_location_id IN (SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment) " +
                "      GROUP BY p.variant_id) a " +
                "JOIN variants v ON v.id = a.variant_id AND v.tenant_id = ? " +
                "JOIN products pr ON pr.id = v.product_id AND pr.tenant_id = v.tenant_id " +
                "LEFT JOIN bazar_event_prices bp ON bp.event_id = ? AND bp.variant_id = v.id AND bp.tenant_id = v.tenant_id " +
                "ORDER BY pr.title, pr.id, v.title, v.id",
                tenantId, tenantId, tenantId, eventId)) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            if (m.get("price") == null && m.get("shopify_price") != null) {
                BazarPricing.Priced p = BazarPricing.forVariant((BigDecimal) m.get("shopify_price"),
                    (BigDecimal) ev.get("discount_pct"), (BigDecimal) ev.get("round_to"));
                if (p != null) m.put("price", p.price());
            }
            rows.add(m);
        }
        Map<String, Group> byKey = groupRows(rows);
        Map<String, Integer> numbers = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT product_id, colour_key, number FROM bazar_event_product_numbers WHERE event_id = ? AND tenant_id = ?",
                eventId, tenantId)) {
            numbers.put(key((UUID) r.get("product_id"), (String) r.get("colour_key")), ((Number) r.get("number")).intValue());
        }
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, Group> e : byKey.entrySet()) {
            Group g = e.getValue();
            groups.add(new Group(numbers.get(e.getKey()), g.productId(), g.title(), g.colour(), g.imageUrl(),
                g.category(), g.sizes()));
        }
        groups.sort(Comparator.comparing(Group::title, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(g -> g.colour() == null ? "" : g.colour(), String.CASE_INSENSITIVE_ORDER));
        Map<UUID, Long> committed = new HashMap<>();
        stock.computeAll().forEach((v, st) -> committed.put(v, st.committed()));
        return view(new Catalog(eventId, (String) ev.get("name"), (String) ev.get("status"),
            (String) ev.get("location_name"), (String) ev.get("starts_on"), (String) ev.get("ends_on"), groups), committed);
    }

    /**
     * The groups' numbers: existing ones kept; missing ones get the next free numbers, first by product title then
     * colour. Serialised per event (advisory lock), so two first readers never assign twice. Past 999 a group stays
     * unnumbered (it is still in the catalog, tappable by name).
     */
    Map<String, Integer> ensureNumbers(UUID tenantId, UUID eventId, List<Group> groups) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", tenantId + ":bazar-numbers:" + eventId);
        Map<String, Integer> numbers = new HashMap<>();
        int max = 0;
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT product_id, colour_key, number FROM bazar_event_product_numbers WHERE event_id = ? AND tenant_id = ?",
                eventId, tenantId)) {
            int n = ((Number) r.get("number")).intValue();
            numbers.put(key((UUID) r.get("product_id"), (String) r.get("colour_key")), n);
            max = Math.max(max, n);
        }
        List<Group> missing = groups.stream()
            .filter(g -> !numbers.containsKey(key(g.productId(), g.colour() == null ? "" : g.colour())))
            .sorted(Comparator.comparing(Group::title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(g -> g.colour() == null ? "" : g.colour(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(g -> g.productId().toString()))
            .toList();
        for (Group g : missing) {
            if (max >= MAX_NUMBER) break;
            max++;
            String colourKey = g.colour() == null ? "" : g.colour();
            jdbc.update(
                "INSERT INTO bazar_event_product_numbers (tenant_id, event_id, product_id, colour_key, colour, number) " +
                "VALUES (?, ?, ?, ?, ?, ?)", tenantId, eventId, g.productId(), colourKey, g.colour(), max);
            numbers.put(key(g.productId(), colourKey), max);
        }
        return numbers;
    }

    /** The event's sellers (role in seller_roles, not removed — the selling rule) and owners / managers. */
    private void requireSellerOrManager(Map<String, Object> ev, BazarSaleService.Actor actor) {
        if (actor != null && actor.role() != null && MANAGERS.contains(actor.roleKey())) return;
        if (actor == null || actor.role() == null
                || !BazarSaleService.toList(ev.get("seller_roles")).contains(actor.roleKey())
                || BazarSellers.removed(jdbc, (UUID) ev.get("id"), actor.userId())) {
            throw notASeller();
        }
    }

    private static BazarException notASeller() {
        return new BazarException(BazarException.Code.BAZAR_NOT_A_SELLER,
            "Your role can't do this at this bazar.", "دورك لا يسمح بذلك في هذا البازار.", HttpStatus.FORBIDDEN);
    }

    private static String key(UUID productId, String colourKey) {
        return productId + "|" + colourKey;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static final List<String> LETTER_SIZES =
        List.of("XXS", "XS", "S", "M", "L", "XL", "XXL", "2XL", "XXXL", "3XL", "4XL", "5XL");

    /** Letter sizes in their order, then numeric sizes ascending, then anything else by title. */
    static final Comparator<Size> SIZE_ORDER = Comparator
        .comparingInt((Size s) -> sizeRank(s.size()))
        .thenComparingDouble(s -> numeric(s.size()))
        .thenComparing(s -> s.title() == null ? "" : s.title(), String.CASE_INSENSITIVE_ORDER);

    private static int sizeRank(String size) {
        if (size == null) return 3000;
        int i = LETTER_SIZES.indexOf(size.trim().toUpperCase(Locale.ROOT));
        if (i >= 0) return i;
        return Double.isNaN(numeric(size)) ? 2000 : 1000;
    }

    private static double numeric(String size) {
        if (size == null) return Double.NaN;
        try { return Double.parseDouble(size.trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }
}
