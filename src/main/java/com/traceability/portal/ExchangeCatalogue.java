package com.traceability.portal;

import com.traceability.inventory.VariantStockService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Returns portal fees F3 — what a customer can exchange an item for when the store allows "a different product": every
 * ACTIVE product other than the item's own, its priced variants that are IN STOCK now (VariantStockService, the one
 * stock derivation), with the product image and each variant's current price and its difference from the price paid.
 * Variants the store's caps forbid ("same price or lower only", the maximum difference) are left out. A NEW query — the
 * same-product picker ({@link ExchangeOptions}) is untouched. Never trusted at submit: the replacement is re-checked
 * there and stock again at approval.
 *
 * Not a bean: built on the caller's JdbcTemplate (its transaction, its tenant context).
 */
public class ExchangeCatalogue {

    static final int MAX_PRODUCTS = 60;
    static final int QUERY_MAX = 80;

    private final JdbcTemplate jdbc;

    public ExchangeCatalogue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Products (title order) with their offerable variants; {@code paid} is the price paid for the item exchanged. */
    public List<Map<String, Object>> list(UUID tenantId, UUID originalVariant, BigDecimal paid,
                                          PortalAmounts.ExchangePricing pricing, String query) {
        String q = query == null || query.isBlank() ? null : query.trim();
        if (q != null && q.length() > QUERY_MAX) q = q.substring(0, QUERY_MAX);
        String like = q == null ? null : "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        Map<UUID, VariantStockService.VariantStock> stock = new VariantStockService(jdbc).computeAll();
        Map<UUID, Map<String, Object>> products = new LinkedHashMap<>();
        jdbc.query(
            "SELECT p.id AS product_id, p.title, p.image_url, v.id AS variant_id, v.title AS variant_title, v.price, " +
            "       v.raw::text AS raw " +
            "FROM products p JOIN variants v ON v.product_id = p.id AND v.tenant_id = p.tenant_id " +
            "WHERE p.tenant_id = ? AND p.status = 'active' AND v.price IS NOT NULL " +
            "  AND p.id <> (SELECT o.product_id FROM variants o WHERE o.id = ? AND o.tenant_id = ?) " +
            "  AND (?::text IS NULL OR p.title ILIKE ?) " +
            "ORDER BY p.title, p.id, v.title, v.id",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                UUID variant = rs.getObject("variant_id", UUID.class);
                VariantStockService.VariantStock s = stock.get(variant);
                if (s == null || s.available() <= 0) return;
                BigDecimal price = rs.getBigDecimal("price").setScale(2, RoundingMode.HALF_UP);
                BigDecimal diff = price.subtract(paid).setScale(2, RoundingMode.HALF_UP);
                if (pricing.sameOrLowerOnly() && diff.signum() > 0) return;
                if (pricing.maxDifference() != null && diff.compareTo(pricing.maxDifference()) > 0) return;
                if (diff.compareTo(PortalAmounts.COD_LIMIT) > 0) return;
                UUID productId = rs.getObject("product_id", UUID.class);
                if (!products.containsKey(productId) && products.size() >= MAX_PRODUCTS) return;
                Map<String, Object> p = products.computeIfAbsent(productId, id -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    try {
                        m.put("productId", id.toString());
                        m.put("title", rs.getString("title"));
                        m.put("imageUrl", rs.getString("image_url"));
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException(e);
                    }
                    m.put("variants", new ArrayList<Map<String, Object>>());
                    return m;
                });
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("variantId", variant.toString());
                v.put("title", rs.getString("variant_title"));
                v.put("options", ExchangeOptions.values(ExchangeOptions.parse(rs.getString("raw")), rs.getString("variant_title")));
                v.put("price", price.toPlainString());
                v.put("priceDifference", diff.toPlainString());
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> vs = (List<Map<String, Object>>) p.get("variants");
                vs.add(v);
            },
            tenantId, originalVariant, tenantId, q, like);
        return new ArrayList<>(products.values());
    }
}
