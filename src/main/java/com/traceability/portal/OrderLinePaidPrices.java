package com.traceability.portal;

import com.traceability.integrations.shopify.ShopifyOrderPriceGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Returns portal fees F0 — the unit price a customer paid for each line of one order, for the
 * price-difference and percent-fee rules (F2 / F3). Three sources, per line, first that answers:
 * <ol>
 *   <li>{@link Source#STORED} — {@code order_line_paid_unit_price(oi)} (V208, V218): the line's REST net price
 *       (price − discount allocations / quantity), else its {@code paid_unit_price} key (GraphQL import, portal
 *       pre-connect lookup, and this resolver's own write-back), else the ORDER's REST line (V218). The ONE stored
 *       definition — never re-derive it.</li>
 *   <li>{@link Source#SHOPIFY} — the order read live from Shopify ({@code order(id:)}, unit price after all
 *       discounts): matched by the line's own GID first, else by variant (lines of one variant averaged by
 *       quantity). Only when a line has no stored price, the order is a Shopify order and its store is
 *       connected. Any failure falls through, never throws, and is logged at WARN with its cause (2026-11-06:
 *       a silent INFO hid a prod failure). A price read this way is written back to the line's
 *       {@code paid_unit_price} key (never {@code raw->>'price'}), so the next resolve is STORED.</li>
 *   <li>{@link Source#CATALOG} — today's {@code variants.price}, no discounts: APPROXIMATE.</li>
 * </ol>
 * {@link Source#NONE} when nothing priced the line.
 *
 * <p><b>Never inside a transaction:</b> it may call Shopify, so it refuses to run while one is open
 * (IllegalStateException). Its own DB reads run under {@link TenantContext#runAs} in short transactions of
 * their own — the tenant is passed in because portal callers have no tenant context on the thread.
 * No Shopify or Bosta write. Its one DB write is that write-back: only on a line with no stored price, in its own
 * short transaction, after the Shopify call. Never logs a price, an order payload, a Shopify message or a token.
 */
@Service
public class OrderLinePaidPrices {

    private static final Logger log = LoggerFactory.getLogger(OrderLinePaidPrices.class);

    public enum Source { STORED, SHOPIFY, CATALOG, NONE }

    /** One order line's unit price paid; {@code unitPrice} null only for {@link Source#NONE}. */
    public record LinePrice(UUID orderItemId, UUID variantId, BigDecimal unitPrice, Source source) {
        /** True when the price is today's catalogue price, not what the customer paid. */
        public boolean approximate() { return source == Source.CATALOG; }
    }

    private record Row(UUID orderItemId, UUID variantId, String lineGid, String variantGid, BigDecimal stored,
                       BigDecimal catalog) {}

    /** What the live read priced: by the line's own GID (exact) and by variant GID (quantity-weighted average). */
    private record Live(Map<String, BigDecimal> byLine, Map<String, BigDecimal> byVariant) {
        static final Live NONE = new Live(Map.of(), Map.of());

        BigDecimal priceOf(Row r) {
            if (r.lineGid() != null && byLine.containsKey(r.lineGid())) return byLine.get(r.lineGid());
            return r.variantGid() == null ? null : byVariant.get(r.variantGid());
        }
    }

    /** The write-back: the paid key, only while the line still has no stored price (never raw->>'price'). */
    static final String STORE_SQL =
        "UPDATE order_items SET raw = COALESCE(raw, '{}'::jsonb) || jsonb_build_object('paid_unit_price', ?::text) " +
        "WHERE id = ? AND tenant_id = ? AND order_line_paid_unit_price(order_items) IS NULL";

    private record Order(String orderGid, UUID storeId, String shopDomain, boolean storeConnected, List<Row> rows) {}

    static final String LOAD_SQL =
        // MUST_INCLUDE: portal pre-connect orders (orders.origin = 'portal_pre_connect') are priced too.
        "SELECT oi.id, oi.variant_id, oi.external_id AS line_gid, v.external_id AS variant_gid, order_line_paid_unit_price(oi) AS stored, " +
        "       v.price AS catalog, COALESCE(o.shopify_order_gid, o.external_id) AS order_gid, o.store_id, " +
        "       s.shop_domain, s.status::text AS store_status " +
        "FROM order_items oi " +
        "JOIN orders o ON o.id = oi.order_id AND o.tenant_id = oi.tenant_id " +
        "JOIN variants v ON v.id = oi.variant_id AND v.tenant_id = oi.tenant_id " +
        "LEFT JOIN stores s ON s.id = o.store_id AND s.tenant_id = o.tenant_id " +
        // One order, one tenant. Never a Shopify-native exchange shadow order (S2+): it isn't what the
        // customer paid for.
        "WHERE oi.order_id = ? AND oi.tenant_id = ? " +
        "  AND o.external_id NOT LIKE 'internal:shopify-exchange:%' " +
        "ORDER BY oi.external_id NULLS LAST, oi.id";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyOrderPriceGateway shopify;
    private final ShopifyTokenProvider tokens;

    public OrderLinePaidPrices(JdbcTemplate jdbc, PlatformTransactionManager txm,
                               ShopifyOrderPriceGateway shopify, ShopifyTokenProvider tokens) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.shopify = shopify;
        this.tokens = tokens;
    }

    /** Every line of the order, in a stable order; empty when the order isn't the tenant's or has no lines. */
    public List<LinePrice> resolve(UUID tenantId, UUID orderId) {
        if (tenantId == null || orderId == null) throw new IllegalArgumentException("tenantId and orderId are required");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OrderLinePaidPrices.resolve may call Shopify — call it with no transaction open");
        }
        Order order = TenantContext.runAs(tenantId, () -> tx.execute(s -> load(tenantId, orderId)));
        if (order == null || order.rows().isEmpty()) return List.of();

        boolean anyMissing = order.rows().stream().anyMatch(r -> r.stored() == null);
        Live live = anyMissing ? live(tenantId, orderId, order) : Live.NONE;

        List<LinePrice> out = new ArrayList<>(order.rows().size());
        Map<UUID, BigDecimal> fetched = new LinkedHashMap<>();
        int unmatched = 0;
        for (Row r : order.rows()) {
            BigDecimal fromShopify = r.stored() == null ? live.priceOf(r) : null;
            if (r.stored() != null) {
                out.add(new LinePrice(r.orderItemId(), r.variantId(), scale(r.stored()), Source.STORED));
            } else if (fromShopify != null) {
                out.add(new LinePrice(r.orderItemId(), r.variantId(), scale(fromShopify), Source.SHOPIFY));
                fetched.put(r.orderItemId(), scale(fromShopify));
            } else {
                if (live != Live.NONE) unmatched++;
                if (r.catalog() != null) {
                    out.add(new LinePrice(r.orderItemId(), r.variantId(), scale(r.catalog()), Source.CATALOG));
                } else {
                    out.add(new LinePrice(r.orderItemId(), r.variantId(), null, Source.NONE));
                }
            }
        }
        if (unmatched > 0) {
            log.warn("Line paid prices: Shopify answered for tenant {} order {} but {} line(s) matched no Shopify line "
                     + "(by line id or variant) — falling back", tenantId, orderId, unmatched);
        }
        if (!fetched.isEmpty()) store(tenantId, orderId, fetched);
        return out;
    }

    /** Writes the live prices back as the lines' paid key — never fails the read. */
    private void store(UUID tenantId, UUID orderId, Map<UUID, BigDecimal> fetched) {
        try {
            TenantContext.runAs(tenantId, () -> tx.execute(s -> {
                fetched.forEach((item, price) -> jdbc.update(STORE_SQL, price.toPlainString(), item, tenantId));
                return null;
            }));
        } catch (RuntimeException e) {
            log.warn("Line paid prices: couldn't store the Shopify price for tenant {} order {} ({})",
                     tenantId, orderId, e.getClass().getSimpleName());
        }
    }

    private Order load(UUID tenantId, UUID orderId) {
        TenantContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList(LOAD_SQL, orderId, tenantId);
        if (rows.isEmpty()) return null;
        Map<String, Object> first = rows.get(0);
        List<Row> out = new ArrayList<>(rows.size());
        for (Map<String, Object> m : rows) {
            out.add(new Row((UUID) m.get("id"), (UUID) m.get("variant_id"), (String) m.get("line_gid"),
                            (String) m.get("variant_gid"), (BigDecimal) m.get("stored"), (BigDecimal) m.get("catalog")));
        }
        return new Order((String) first.get("order_gid"), (UUID) first.get("store_id"),
                         (String) first.get("shop_domain"), "connected".equals(first.get("store_status")), out);
    }

    /**
     * The order's prices read live from Shopify; {@link Live#NONE} on any failure, which is logged at WARN with its
     * cause only (exception class, HTTP status, Shopify's GraphQL error codes) — never a message, body or token.
     */
    private Live live(UUID tenantId, UUID orderId, Order order) {
        String gid = order.orderGid();
        if (gid == null || !gid.startsWith("gid://shopify/Order/")) {
            return Live.NONE;   // an internal order (exchange replacement…) — never a Shopify order, nothing to say
        }
        if (order.storeId() == null || order.shopDomain() == null) {
            log.warn("Line paid prices: no live read for tenant {} order {} — the order has no store", tenantId, orderId);
            return Live.NONE;
        }
        if (!order.storeConnected()) {
            log.warn("Line paid prices: no live read for tenant {} order {} — store not connected", tenantId, orderId);
            return Live.NONE;
        }
        String token;
        try {
            // The token read touches the stores row: under the tenant (RLS), no transaction of ours open.
            token = TenantContext.runAs(tenantId, () -> tokens.getValidToken(order.storeId()));
        } catch (RuntimeException e) {
            log.warn("Line paid prices: no Shopify token for tenant {} order {} ({}) — falling back",
                     tenantId, orderId, e.getClass().getSimpleName());
            return Live.NONE;
        }
        ShopifyOrderPriceGateway.OrderPrices prices;
        try {
            prices = shopify.fetchOrderPrices(order.shopDomain(), token, gid);
        } catch (ShopifyOrderPriceGateway.OrderPriceReadException e) {
            log.warn("Line paid prices: Shopify order read failed for tenant {} order {} ({}) — falling back",
                     tenantId, orderId, e.reason());
            return Live.NONE;
        } catch (RuntimeException e) {
            log.warn("Line paid prices: Shopify order read failed for tenant {} order {} ({}{}) — falling back",
                     tenantId, orderId, e.getClass().getSimpleName(), httpStatus(e));
            return Live.NONE;
        }
        if (prices == null || prices.lines() == null) return new Live(Map.of(), Map.of());
        Map<String, BigDecimal> byLine = new HashMap<>();
        Map<String, BigDecimal> sum = new HashMap<>();
        Map<String, Integer> qty = new HashMap<>();
        for (ShopifyOrderPriceGateway.LinePrice lp : prices.lines()) {
            if (lp.unitPriceAfterDiscounts() == null) continue;
            if (lp.lineItemGid() != null) byLine.put(lp.lineItemGid(), lp.unitPriceAfterDiscounts());
            if (lp.variantGid() == null) continue;
            int q = Math.max(lp.quantity(), 1);
            sum.merge(lp.variantGid(), lp.unitPriceAfterDiscounts().multiply(BigDecimal.valueOf(q)), BigDecimal::add);
            qty.merge(lp.variantGid(), q, Integer::sum);
        }
        Map<String, BigDecimal> byVariant = new HashMap<>();
        sum.forEach((v, total) -> byVariant.put(v, total.divide(BigDecimal.valueOf(qty.get(v)), 4, RoundingMode.HALF_UP)));
        return new Live(byLine, byVariant);
    }

    /** ", http NNN" when an HTTP status is somewhere in the cause chain; empty otherwise. */
    private static String httpStatus(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.web.client.RestClientResponseException r) {
                return ", http " + r.getStatusCode().value();
            }
        }
        return "";
    }

    private static BigDecimal scale(BigDecimal p) {
        BigDecimal v = p.signum() < 0 ? BigDecimal.ZERO : p;
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
