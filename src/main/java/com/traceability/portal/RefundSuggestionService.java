package com.traceability.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Returns Step 4d-2 — GET /return-requests/{id}/refund-suggestion: a suggested refund amount
 * for the items that came back (or, before anything arrived, the items still expected).
 * Shipping is never included. Three sources, first that prices every item wins:
 *   1. 'shopify'      — the order read live from Shopify (unit price after all discounts);
 *   2. 'stored_order' — the Shopify REST payload stored in orders.raw (line price minus that
 *                       line's discount_allocations, per unit);
 *   3. 'catalog'      — variants.price, today's price without discounts → approximate.
 * No source → amount null (the refund form still works, empty). Never throws for a Shopify
 * failure, never logs the order payload. A read only: no Shopify or Bosta write.
 *
 * Built from its own JdbcTemplate so a test can construct it on an app_user connection.
 */
@Service
public class RefundSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(RefundSuggestionService.class);
    private static final String VARIANT_GID = "gid://shopify/ProductVariant/";

    private final JdbcTemplate             jdbc;
    private final ShopifyOrderPriceGateway shopify;
    private final ShopifyTokenProvider     tokens;
    private final ObjectMapper             mapper = new ObjectMapper();

    public RefundSuggestionService(JdbcTemplate jdbc, ShopifyOrderPriceGateway shopify, ShopifyTokenProvider tokens) {
        this.jdbc    = jdbc;
        this.shopify = shopify;
        this.tokens  = tokens;
    }

    private record Line(UUID variantId, String variantGid, String productTitle, String variantTitle,
                        BigDecimal catalogPrice, int quantity) {}

    public Map<String, Object> suggest(UUID requestId) {
        UUID tenantId = TenantContext.require();
        Map<String, Object> rr = jdbc.queryForList(
            "SELECT rr.id, o.id AS order_id, o.external_id, o.store_id, o.raw::text AS raw, s.shop_domain, " +
            "       s.status::text AS store_status " +
            "FROM return_requests rr JOIN orders o ON o.id = rr.order_id AND o.tenant_id = rr.tenant_id " +
            "LEFT JOIN stores s ON s.id = o.store_id AND s.tenant_id = o.tenant_id " +
            "WHERE rr.id = ? AND rr.tenant_id = ?", requestId, tenantId)
            .stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Return request not found"));

        // Items that came back; before anything arrived, the items still expected.
        List<Line> lines = lines(tenantId, requestId, "('arrived', 'done')");
        if (lines.isEmpty()) lines = lines(tenantId, requestId, "('awaiting')");

        JsonNode raw = parse((String) rr.get("raw"));
        String storedCurrency = raw == null ? null : text(raw.path("currency"));

        Priced priced = fromShopify(rr, lines);
        if (priced == null) priced = fromStoredOrder(raw, lines, storedCurrency);
        if (priced == null) priced = fromCatalog(lines, storedCurrency);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", priced == null ? null : priced.total().toPlainString());
        body.put("currency", priced != null && priced.currency() != null ? priced.currency()
            : storedCurrency != null ? storedCurrency : "EGP");
        body.put("source", priced == null ? null : priced.source());
        body.put("approximate", priced == null || "catalog".equals(priced.source()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("variantId", l.variantId().toString());
            m.put("productTitle", l.productTitle());
            m.put("variantTitle", l.variantTitle());
            m.put("quantity", l.quantity());
            BigDecimal unit = priced == null ? null : priced.units().get(i);
            m.put("unitPrice", unit == null ? null : unit.toPlainString());
            m.put("lineTotal", unit == null ? null : unit.multiply(BigDecimal.valueOf(l.quantity()))
                .setScale(2, RoundingMode.HALF_UP).toPlainString());
            out.add(m);
        }
        body.put("lines", out);
        return body;
    }

    private record Priced(String source, String currency, List<BigDecimal> units, BigDecimal total) {}

    private List<Line> lines(UUID tenantId, UUID requestId, String statuses) {
        return jdbc.query(
            "SELECT i.variant_id, v.external_id, pr.title AS product_title, v.title AS variant_title, v.price, " +
            "       COUNT(*) AS qty " +
            "FROM return_request_items i JOIN variants v ON v.id = i.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE i.request_id = ? AND i.tenant_id = ? AND i.item_status IN " + statuses + " " +
            "GROUP BY i.variant_id, v.external_id, pr.title, v.title, v.price ORDER BY pr.title, v.title",
            (rs, n) -> new Line(rs.getObject("variant_id", UUID.class), rs.getString("external_id"),
                rs.getString("product_title"), rs.getString("variant_title"), rs.getBigDecimal("price"), rs.getInt("qty")),
            requestId, tenantId);
    }

    /** Source 1 — live Shopify order. Any failure (no store, disconnected, token, HTTP, a variant missing) → null. */
    private Priced fromShopify(Map<String, Object> rr, List<Line> lines) {
        String orderGid = (String) rr.get("external_id");
        if (lines.isEmpty() || rr.get("shop_domain") == null || !"connected".equals(rr.get("store_status"))
                || orderGid == null || !orderGid.startsWith("gid://shopify/Order/")) {
            return null;
        }
        try {
            String token = tokens.getValidToken((UUID) rr.get("store_id"));
            ShopifyOrderPriceGateway.OrderPrices prices =
                shopify.fetchOrderPrices((String) rr.get("shop_domain"), token, orderGid);
            Map<String, BigDecimal> byVariant = new HashMap<>();
            for (ShopifyOrderPriceGateway.LinePrice lp : prices.lines()) {
                byVariant.putIfAbsent(lp.variantGid(), lp.unitPriceAfterDiscounts());
            }
            List<BigDecimal> units = new ArrayList<>();
            for (Line l : lines) {
                BigDecimal u = byVariant.get(l.variantGid());
                if (u == null) return null;
                units.add(u);
            }
            return priced("shopify", prices.currency(), lines, units);
        } catch (RuntimeException e) {
            log.info("Refund suggestion: Shopify order read unavailable ({}) — falling back", e.getClass().getSimpleName());
            return null;
        }
    }

    /** Source 2 — the stored REST payload: (price − Σ discount_allocations / quantity) per unit. */
    private Priced fromStoredOrder(JsonNode raw, List<Line> lines, String currency) {
        if (raw == null || !raw.path("line_items").isArray() || lines.isEmpty()) return null;
        Map<String, BigDecimal> byVariant = new HashMap<>();
        for (JsonNode li : raw.path("line_items")) {
            long variantId = li.path("variant_id").asLong(0);
            String price = text(li.path("price"));
            int qty = li.path("quantity").asInt(0);
            if (variantId == 0 || price == null || qty <= 0) continue;
            BigDecimal discounts = BigDecimal.ZERO;
            for (JsonNode d : li.path("discount_allocations")) {
                String a = text(d.path("amount"));
                if (a != null) discounts = discounts.add(new BigDecimal(a));
            }
            BigDecimal unit = new BigDecimal(price)
                .subtract(discounts.divide(BigDecimal.valueOf(qty), 4, RoundingMode.HALF_UP));
            byVariant.putIfAbsent(VARIANT_GID + variantId, unit.max(BigDecimal.ZERO));
        }
        List<BigDecimal> units = new ArrayList<>();
        for (Line l : lines) {
            BigDecimal u = byVariant.get(l.variantGid());
            if (u == null) return null;
            units.add(u);
        }
        return priced("stored_order", currency, lines, units);
    }

    /** Source 3 — today's catalog price (no discounts): approximate. */
    private Priced fromCatalog(List<Line> lines, String currency) {
        if (lines.isEmpty()) return null;
        List<BigDecimal> units = new ArrayList<>();
        for (Line l : lines) {
            if (l.catalogPrice() == null) return null;
            units.add(l.catalogPrice());
        }
        return priced("catalog", currency, lines, units);
    }

    private static Priced priced(String source, String currency, List<Line> lines, List<BigDecimal> units) {
        BigDecimal total = BigDecimal.ZERO;
        List<BigDecimal> scaled = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            BigDecimal u = units.get(i).setScale(2, RoundingMode.HALF_UP);
            scaled.add(u);
            total = total.add(u.multiply(BigDecimal.valueOf(lines.get(i).quantity())));
        }
        return new Priced(source, currency, scaled, total.setScale(2, RoundingMode.HALF_UP));
    }

    private JsonNode parse(String json) {
        if (json == null) return null;
        try { return mapper.readTree(json); } catch (Exception e) { return null; }
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
