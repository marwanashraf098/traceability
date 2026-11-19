package com.traceability.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.traceability.integrations.bosta.BostaLabelLines;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Exchange reference S3b (2026-11-19) — THE rules for a multi-item Bosta exchange (type 30 with more than one item on a
 * leg). Not a bean: every caller passes its own JdbcTemplate (its transaction, its tenant / RLS). One definition each,
 * shared by ingest (ExchangeIngestService / ExchangeService.tryAutoMap), the mapping screen's prefill
 * (ExchangeService.detail) and the backfill's dry run (ExchangeMultiCatchUpService):
 * <ul>
 *   <li>{@link #counts}: Bosta's itemsCount per leg ({@code specs} out, {@code returnSpecs} in; -1 when absent).
 *       {@link #isMulti}: either leg &gt; 1.</li>
 *   <li>{@link #autoLines}: the SKU-label auto-commit — ONLY when the outbound description parses as a label
 *       ({@link BostaLabelLines}: every line, one format), every SKU names exactly one variant of the tenant, and the
 *       label's quantities sum to Bosta's outbound itemsCount (4226201959: Bosta's count can disagree with the label —
 *       then never). Else null → prefilled and left for a person. The inbound leg is never committed by it.</li>
 *   <li>{@link #shadowFor}: an -R reference's live Shopify-exchange shadow whose live units equal the outbound count
 *       AND whose Shopify Return's units equal the inbound count (else null → needs_mapping).</li>
 *   <li>{@link #prefill}: the mapping screen's rows — one per label line (variant suggested by exact SKU when exactly
 *       one matches), else one row of Bosta's count with Bosta's text as its hint. Never written.</li>
 * </ul>
 */
public final class ExchangeMultiItem {

    private ExchangeMultiItem() {}

    public record Counts(int out, int in, boolean hasReturnSpecs) {
        public boolean multi() { return out > 1 || in > 1; }
    }

    /** One outbound line to commit: an exactly-matched variant, its quantity and the label's name. */
    public record Line(UUID variantId, int quantity, String name) {}

    /** A prefilled mapping row: a suggested variant (or null), its quantity and the courier text kept as hint. */
    public record PrefillRow(UUID variantId, String productTitle, String variantTitle, String sku, int quantity,
                             String descriptionHint) {}

    public record Prefill(boolean labelParsed, List<PrefillRow> outbound, List<PrefillRow> inbound) {}

    public static Counts counts(JsonNode raw) {
        if (raw == null) return new Counts(-1, -1, false);
        JsonNode rs = raw.path("returnSpecs");
        boolean hasRs = !rs.isMissingNode() && !rs.path("packageDetails").isMissingNode();
        return new Counts(raw.path("specs").path("packageDetails").path("itemsCount").asInt(-1),
            hasRs ? rs.path("packageDetails").path("itemsCount").asInt(-1) : -1, hasRs);
    }

    public static boolean isMulti(JsonNode raw) {
        return counts(raw).multi();
    }

    static String outboundDescription(JsonNode raw) {
        return raw == null ? null : raw.path("specs").path("packageDetails").path("description").asText(null);
    }

    static String inboundDescription(JsonNode raw) {
        return raw == null ? null : raw.path("returnSpecs").path("packageDetails").path("description").asText(null);
    }

    /** The auto-commit lines, or null (see class javadoc). Caller's transaction and tenant. */
    public static List<Line> autoLines(JdbcTemplate jdbc, UUID tenantId, JsonNode raw) {
        Counts c = counts(raw);
        if (c.out() < 1) return null;
        BostaLabelLines.Label label = BostaLabelLines.parse(outboundDescription(raw));
        if (label == null || label.total() != c.out()) return null;
        List<Line> out = new ArrayList<>();
        for (BostaLabelLines.Line l : label.lines()) {
            List<UUID> ids = variantsBySku(jdbc, tenantId, l.sku());
            if (ids.size() != 1) return null;
            out.add(new Line(ids.get(0), l.quantity(), l.name()));
        }
        return out;
    }

    /** True when the outbound description parses as a label (for the backfill's report). */
    public static boolean labelParses(JsonNode raw) {
        return BostaLabelLines.parse(outboundDescription(raw)) != null;
    }

    /**
     * The live shadow of the reference's Shopify Return when its units match Bosta's counts both ways, else null.
     * Caller's transaction and tenant.
     */
    public static UUID shadowFor(JdbcTemplate jdbc, UUID tenantId, JsonNode raw) {
        String ref = raw == null ? null : raw.path("businessReference").asText(null);
        UUID shadow = ShopifyShadowOrders.shadowForReturnReference(jdbc, tenantId, ref);
        if (shadow == null) return null;
        Counts c = counts(raw);
        Map<String, Object> units = jdbc.queryForMap(
            "SELECT (SELECT COALESCE(SUM(oi.quantity), 0) FROM order_items oi " +
            "        WHERE oi.order_id = s.order_id AND oi.tenant_id = s.tenant_id AND oi.quantity > 0) AS out_units, " +
            "       (SELECT COALESCE(SUM(rl.quantity), 0) FROM shopify_return_lines rl " +
            "        WHERE rl.return_id = s.return_id AND rl.tenant_id = s.tenant_id) AS in_units " +
            "FROM shopify_exchange_shadows s WHERE s.order_id = ? AND s.tenant_id = ?", shadow, tenantId);
        int outUnits = ((Number) units.get("out_units")).intValue();
        int inUnits = ((Number) units.get("in_units")).intValue();
        return outUnits == c.out() && inUnits == c.in() ? shadow : null;
    }

    /** The mapping screen's prefill (see class javadoc). Caller's transaction and tenant. */
    public static Prefill prefill(JdbcTemplate jdbc, UUID tenantId, JsonNode raw) {
        Counts c = counts(raw);
        BostaLabelLines.Label outLabel = BostaLabelLines.parse(outboundDescription(raw));
        return new Prefill(outLabel != null,
            rows(jdbc, tenantId, outLabel, c.out(), outboundDescription(raw)),
            rows(jdbc, tenantId, BostaLabelLines.parse(inboundDescription(raw)), c.in(), inboundDescription(raw)));
    }

    private static List<PrefillRow> rows(JdbcTemplate jdbc, UUID tenantId, BostaLabelLines.Label label, int count,
                                         String text) {
        List<PrefillRow> out = new ArrayList<>();
        if (label != null) {
            for (BostaLabelLines.Line l : label.lines()) {
                List<UUID> ids = variantsBySku(jdbc, tenantId, l.sku());
                if (ids.size() == 1) {
                    Map<String, Object> v = jdbc.queryForMap(
                        "SELECT v.title AS variant_title, pr.title AS product_title FROM variants v " +
                        "JOIN products pr ON pr.id = v.product_id WHERE v.id = ?", ids.get(0));
                    out.add(new PrefillRow(ids.get(0), (String) v.get("product_title"), (String) v.get("variant_title"),
                        l.sku(), l.quantity(), hint(l.name() + " - SKU: " + l.sku())));
                } else {
                    out.add(new PrefillRow(null, null, null, l.sku(), l.quantity(), hint(l.name() + " - SKU: " + l.sku())));
                }
            }
            return out;
        }
        if (count < 1 && (text == null || text.isBlank())) return out;
        out.add(new PrefillRow(null, null, null, null, Math.max(1, Math.min(99, count)), hint(text)));
        return out;
    }

    private static String hint(String s) {
        if (s == null) return null;
        String t = s.strip();
        if (t.isEmpty()) return null;
        return t.length() > 500 ? t.substring(0, 500) : t;
    }

    private static List<UUID> variantsBySku(JdbcTemplate jdbc, UUID tenantId, String sku) {
        if (sku == null || sku.isBlank()) return List.of();
        return jdbc.queryForList("SELECT id FROM variants WHERE tenant_id = ? AND sku = ? LIMIT 2",
            UUID.class, tenantId, sku.trim());
    }

    /** JSON-friendly prefill (detail()). */
    static Map<String, Object> toMap(Prefill p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("labelParsed", p.labelParsed());
        m.put("outbound", p.outbound().stream().map(ExchangeMultiItem::row).toList());
        m.put("inbound", p.inbound().stream().map(ExchangeMultiItem::row).toList());
        return m;
    }

    private static Map<String, Object> row(PrefillRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("variantId", r.variantId() == null ? null : r.variantId().toString());
        m.put("productTitle", r.productTitle());
        m.put("variantTitle", r.variantTitle());
        m.put("sku", r.sku());
        m.put("quantity", r.quantity());
        m.put("descriptionHint", r.descriptionHint());
        return m;
    }
}
