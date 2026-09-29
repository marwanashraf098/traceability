package com.traceability.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.inventory.VariantStockService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Step 5b — what a delivered line could be exchanged for: the OTHER variants of the same
 * product, each with its option values and whether it's in stock (available > 0 from
 * {@link VariantStockService}, the one stock derivation — no new stock term).
 *
 * Option values: Traced stores no option columns. When the variant was ingested from a Shopify
 * webhook, {@code variants.raw} is the REST variant (option1..option3) and
 * {@code products.raw.options[].name} names the axes — those are used. Otherwise (the GraphQL
 * import stores only id / sku / title / price) the values are parsed from the title, split on
 * " / " — Shopify's own variant-title format — and the axis kind is guessed: an axis whose values
 * all look like sizes is "size"; with two axes, the other one is "colour"; anything else "option".
 *
 * Not a bean: built from the caller's own JdbcTemplate (like PickupAreaService) so an app_user
 * instance reads under RLS. Tenant-scoped by explicit tenant_id filters as well.
 */
public class ExchangeOptions {

    private static final Pattern SIZE_LIKE = Pattern.compile(
        "(?i)^(xxs|xs|s|m|l|xl|xxl|xxxl|[2-5]xl|\\d{1,3}(\\.\\d)?|one size|free size|small|medium|large)$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public ExchangeOptions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Variant(UUID id, String title, List<String> values, JsonNode productRaw) {}

    /** The line's exchange data: {optionAxes, currentOptions, exchangeOptions}. */
    public Map<String, Object> forVariant(UUID tenantId, UUID variantId, Map<UUID, VariantStockService.VariantStock> stock) {
        // Only an ACTIVE product is offered for exchange — draft / archived / unlisted products are
        // imported (and still refundable) but never offered to a customer as a replacement.
        List<String> status = jdbc.queryForList(
            "SELECT p.status FROM variants v JOIN products p ON p.id = v.product_id AND p.tenant_id = v.tenant_id " +
            "WHERE v.id = ? AND v.tenant_id = ?", String.class, variantId, tenantId);
        boolean offerable = !status.isEmpty() && "active".equals(status.get(0));
        List<Variant> family = jdbc.query(
            "SELECT v.id, v.title, v.raw::text AS raw, p.raw::text AS product_raw " +
            "FROM variants v JOIN products p ON p.id = v.product_id AND p.tenant_id = v.tenant_id " +
            "WHERE v.tenant_id = ? AND v.product_id = (SELECT product_id FROM variants WHERE id = ? AND tenant_id = ?) " +
            "ORDER BY v.title, v.id",
            (rs, i) -> new Variant(rs.getObject("id", UUID.class), rs.getString("title"),
                values(parse(rs.getString("raw")), rs.getString("title")), parse(rs.getString("product_raw"))),
            tenantId, variantId, tenantId);

        List<Map<String, Object>> axes = axes(family);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("optionAxes", axes);
        List<String> current = family.stream().filter(v -> v.id().equals(variantId)).findFirst()
            .map(Variant::values).orElse(List.of());
        out.put("currentOptions", current);
        List<Map<String, Object>> options = new ArrayList<>();
        for (Variant v : family) {
            if (!offerable || v.id().equals(variantId)) continue;
            VariantStockService.VariantStock s = stock.get(v.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("variantId", v.id().toString());
            m.put("title", v.title());
            m.put("options", v.values());
            m.put("inStock", s != null && s.available() > 0);
            options.add(m);
        }
        out.put("exchangeOptions", options);
        return out;
    }

    /** Option values of one variant: REST option1..3 when stored, else the title split on " / ". */
    static List<String> values(JsonNode raw, String title) {
        List<String> out = new ArrayList<>();
        if (raw != null && raw.hasNonNull("option1")) {
            for (String k : List.of("option1", "option2", "option3")) {
                String v = raw.path(k).asText(null);
                if (v != null && !v.isBlank()) out.add(v.trim());
            }
        } else if (title != null && !title.isBlank() && !"Default Title".equalsIgnoreCase(title.trim())) {
            for (String part : title.split(" / ")) if (!part.isBlank()) out.add(part.trim());
        }
        if (out.size() == 1 && "Default Title".equalsIgnoreCase(out.get(0))) out.clear();
        return out;
    }

    /** [{name, kind}] per axis — name from products.raw.options when stored, kind colour / size / option. */
    static List<Map<String, Object>> axes(List<Variant> family) {
        int count = family.stream().mapToInt(v -> v.values().size()).max().orElse(0);
        JsonNode productOptions = family.isEmpty() || family.get(0).productRaw() == null
            ? null : family.get(0).productRaw().path("options");
        List<String> kinds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = productOptions != null && productOptions.isArray() && productOptions.size() > i
                ? productOptions.get(i).path("name").asText(null) : null;
            names.add(name);
            kinds.add(kindFromName(name));
        }
        for (int i = 0; i < count; i++) {
            if (kinds.get(i) != null) continue;
            final int axis = i;
            boolean sizeLike = family.stream().filter(v -> v.values().size() > axis)
                .allMatch(v -> SIZE_LIKE.matcher(v.values().get(axis)).matches());
            kinds.set(i, sizeLike ? "size" : null);
        }
        if (count == 2 && kinds.contains("size")) {
            for (int i = 0; i < 2; i++) if (kinds.get(i) == null) kinds.set(i, "colour");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", names.get(i));
            m.put("kind", kinds.get(i) == null ? "option" : kinds.get(i));
            out.add(m);
        }
        return out;
    }

    private static String kindFromName(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.equals("color") || n.equals("colour") || n.contains("لون")) return "colour";
        if (n.equals("size") || n.contains("مقاس") || n.contains("المقاس")) return "size";
        return null;
    }

    private static JsonNode parse(String json) {
        if (json == null) return null;
        try { return JSON.readTree(json); } catch (Exception e) { return null; }
    }
}
