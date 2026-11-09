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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
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
    /**
     * The DB reads run in a read-only transaction on jdbc's own DataSource (joins a caller's transaction on it).
     * Without one, app.current_tenant is never set (TenantAwareConnection sets it at transaction start) and RLS hid
     * the request: GET …/refund-suggestion answered 404 on every request in prod (found 2026-11-09). The Shopify read
     * stays outside it.
     */
    private final TransactionTemplate      readTx;

    public RefundSuggestionService(JdbcTemplate jdbc, ShopifyOrderPriceGateway shopify, ShopifyTokenProvider tokens) {
        this.jdbc    = jdbc;
        this.shopify = shopify;
        this.tokens  = tokens;
        this.readTx  = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        this.readTx.setReadOnly(true);
    }

    /** What the DB part decided: a finished body (exchange / frozen), or the request + lines to price. */
    private record Loaded(Map<String, Object> body, Map<String, Object> rr, List<Line> lines) {}

    private record Line(UUID variantId, String variantGid, String productTitle, String variantTitle,
                        BigDecimal catalogPrice, int quantity) {}

    public Map<String, Object> suggest(UUID requestId) {
        UUID tenantId = TenantContext.require();
        Loaded loaded = readTx.execute(st -> suggest(tenantId, requestId));
        if (loaded.body() != null) return loaded.body();
        Map<String, Object> rr = loaded.rr();
        List<Line> lines = loaded.lines();

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

    /**
     * The DB part of suggest — inside readTx. Kept under the name {@code suggest}: its read of the orders table is
     * the member OrdersTableAccessGuardTest classifies (MUST_INCLUDE, portal pre-connect orders included).
     */
    private Loaded suggest(UUID tenantId, UUID requestId) {
        Map<String, Object> rr = jdbc.queryForList(
            // P4b: a portal pre-connect order's Shopify order is its shopify_order_gid (external_id is internal:portal:…).
            "SELECT rr.id, rr.type, o.id AS order_id, COALESCE(o.shopify_order_gid, o.external_id) AS external_id, " +
            "       o.store_id, o.raw::text AS raw, s.shop_domain, " +
            "       s.status::text AS store_status " +
            "FROM return_requests rr JOIN orders o ON o.id = rr.order_id AND o.tenant_id = rr.tenant_id " +
            "LEFT JOIN stores s ON s.id = o.store_id AND s.tenant_id = o.tenant_id " +
            "WHERE rr.id = ? AND rr.tenant_id = ?", requestId, tenantId)
            .stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Return request not found"));
        // Step 5c: an exchange has no refund.
        if ("exchange".equals(rr.get("type"))) {
            // F3: an exchange that leaves the customer owed money suggests exactly that (frozen at submit).
            BigDecimal owed = new RequestAmounts(jdbc).customerOwed(tenantId, requestId);
            if (owed.signum() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "An exchange has no refund.");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("amount", owed.setScale(2, RoundingMode.HALF_UP).toPlainString());
            body.put("currency", PortalAmounts.CURRENCY);
            body.put("source", "frozen");
            body.put("approximate", false);
            body.put("customerOwed", owed.setScale(2, RoundingMode.HALF_UP).toPlainString());
            body.put("lines", List.of());
            return new Loaded(body, null, null);
        }

        // Items that came back; before anything arrived, the items still expected.
        String scope = "('arrived', 'done')";
        List<Line> lines = lines(tenantId, requestId, scope);
        if (lines.isEmpty()) lines = lines(tenantId, requestId, scope = "('awaiting')");

        // Returns portal fees F2: a request with frozen refund amounts is suggested from them — the paid prices
        // frozen at submit minus the request's fee, floored at 0. No Shopify read. Every other request: as before.
        Map<String, Object> frozen = frozen(tenantId, requestId, scope);
        if (frozen != null) return new Loaded(frozen, null, null);
        return new Loaded(null, rr, lines);
    }

    private record Priced(String source, String currency, List<BigDecimal> units, BigDecimal total) {}

    /** F2 — the suggestion from the request's frozen amounts, or null when it has none (or they are an exchange's). */
    private Map<String, Object> frozen(UUID tenantId, UUID requestId, String statuses) {
        List<Map<String, Object>> items = jdbc.queryForList(
            "SELECT i.id, i.variant_id, pr.title AS product_title, v.title AS variant_title " +
            "FROM return_request_items i JOIN variants v ON v.id = i.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE i.request_id = ? AND i.tenant_id = ? AND i.item_status IN " + statuses + " " +
            "ORDER BY pr.title, v.title, i.id", requestId, tenantId);
        RequestAmounts.Net net = new RequestAmounts(jdbc).net(tenantId, requestId,
            items.stream().map(m -> (UUID) m.get("id")).toList());
        if (net == null) return null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", net.amount().toPlainString());
        body.put("currency", PortalAmounts.CURRENCY);
        body.put("source", "frozen");
        body.put("approximate", net.approximate());
        body.put("itemValue", net.itemValue().toPlainString());
        body.put("fee", net.fee().toPlainString());
        body.put("feeWaived", net.feeWaived());
        Map<UUID, Map<String, Object>> byVariant = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            BigDecimal unit = net.unitByItem().get(it.get("id").toString());
            Map<String, Object> m = byVariant.computeIfAbsent((UUID) it.get("variant_id"), v -> {
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("variantId", v.toString());
                l.put("productTitle", it.get("product_title"));
                l.put("variantTitle", it.get("variant_title"));
                l.put("quantity", 0);
                l.put("unitPrice", unit == null ? null : unit.setScale(2, RoundingMode.HALF_UP).toPlainString());
                l.put("lineTotal", BigDecimal.ZERO);
                return l;
            });
            m.put("quantity", (Integer) m.get("quantity") + 1);
            if (unit != null) m.put("lineTotal", ((BigDecimal) m.get("lineTotal")).add(unit));
        }
        for (Map<String, Object> m : byVariant.values()) {
            m.put("lineTotal", ((BigDecimal) m.get("lineTotal")).setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        body.put("lines", new ArrayList<>(byVariant.values()));
        return body;
    }

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
                // A deleted-variant line (variant null) is kept by the gateway since 2026-11-06 — skipped here as before.
                if (lp.variantGid() != null) byVariant.putIfAbsent(lp.variantGid(), lp.unitPriceAfterDiscounts());
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
