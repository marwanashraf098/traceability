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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Returns portal fees F0 — the unit price a customer paid for each line of one order, for the
 * price-difference and percent-fee rules (F2 / F3). Three sources, per line, first that answers:
 * <ol>
 *   <li>{@link Source#STORED} — V208's {@code order_line_paid_unit_price(oi)}: the REST net price
 *       (price − discount allocations / quantity), else the {@code paid_unit_price} key the GraphQL
 *       import and the portal pre-connect lookup store. The ONE stored definition — never re-derive it.</li>
 *   <li>{@link Source#SHOPIFY} — the order read live from Shopify ({@code order(id:)}, unit price after all
 *       discounts; lines of one variant averaged by quantity). Only when a line has no stored price, the
 *       order is a Shopify order and its store is connected. Any failure falls through, never throws.</li>
 *   <li>{@link Source#CATALOG} — today's {@code variants.price}, no discounts: APPROXIMATE.</li>
 * </ol>
 * {@link Source#NONE} when nothing priced the line.
 *
 * <p><b>Never inside a transaction:</b> it may call Shopify, so it refuses to run while one is open
 * (IllegalStateException). Its own DB reads run under {@link TenantContext#runAs} in short transactions of
 * their own — the tenant is passed in because portal callers have no tenant context on the thread.
 * A read only: no Shopify, Bosta or DB write. Never logs a price, an order payload or a token.
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

    private record Row(UUID orderItemId, UUID variantId, String variantGid, BigDecimal stored, BigDecimal catalog) {}

    private record Order(String orderGid, UUID storeId, String shopDomain, boolean storeConnected, List<Row> rows) {}

    static final String LOAD_SQL =
        // MUST_INCLUDE: portal pre-connect orders (orders.origin = 'portal_pre_connect') are priced too.
        "SELECT oi.id, oi.variant_id, v.external_id AS variant_gid, order_line_paid_unit_price(oi) AS stored, " +
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
        Map<String, BigDecimal> live = anyMissing ? live(tenantId, order) : Map.of();

        List<LinePrice> out = new ArrayList<>(order.rows().size());
        for (Row r : order.rows()) {
            if (r.stored() != null) {
                out.add(new LinePrice(r.orderItemId(), r.variantId(), scale(r.stored()), Source.STORED));
            } else if (r.variantGid() != null && live.containsKey(r.variantGid())) {
                out.add(new LinePrice(r.orderItemId(), r.variantId(), scale(live.get(r.variantGid())), Source.SHOPIFY));
            } else if (r.catalog() != null) {
                out.add(new LinePrice(r.orderItemId(), r.variantId(), scale(r.catalog()), Source.CATALOG));
            } else {
                out.add(new LinePrice(r.orderItemId(), r.variantId(), null, Source.NONE));
            }
        }
        return out;
    }

    private Order load(UUID tenantId, UUID orderId) {
        TenantContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList(LOAD_SQL, orderId, tenantId);
        if (rows.isEmpty()) return null;
        Map<String, Object> first = rows.get(0);
        List<Row> out = new ArrayList<>(rows.size());
        for (Map<String, Object> m : rows) {
            out.add(new Row((UUID) m.get("id"), (UUID) m.get("variant_id"), (String) m.get("variant_gid"),
                            (BigDecimal) m.get("stored"), (BigDecimal) m.get("catalog")));
        }
        return new Order((String) first.get("order_gid"), (UUID) first.get("store_id"),
                         (String) first.get("shop_domain"), "connected".equals(first.get("store_status")), out);
    }

    /** Variant GID → unit price after all discounts (quantity-weighted across that variant's lines); empty on any failure. */
    private Map<String, BigDecimal> live(UUID tenantId, Order order) {
        String gid = order.orderGid();
        if (!order.storeConnected() || order.shopDomain() == null || order.storeId() == null
                || gid == null || !gid.startsWith("gid://shopify/Order/")) {
            return Map.of();
        }
        try {
            // The token read touches the stores row: under the tenant (RLS), no transaction of ours open.
            String token = TenantContext.runAs(tenantId, () -> tokens.getValidToken(order.storeId()));
            ShopifyOrderPriceGateway.OrderPrices prices = shopify.fetchOrderPrices(order.shopDomain(), token, gid);
            Map<String, BigDecimal> sum = new HashMap<>();
            Map<String, Integer> qty = new HashMap<>();
            for (ShopifyOrderPriceGateway.LinePrice lp : prices.lines()) {
                if (lp.variantGid() == null || lp.unitPriceAfterDiscounts() == null) continue;
                int q = Math.max(lp.quantity(), 1);
                sum.merge(lp.variantGid(), lp.unitPriceAfterDiscounts().multiply(BigDecimal.valueOf(q)), BigDecimal::add);
                qty.merge(lp.variantGid(), q, Integer::sum);
            }
            Map<String, BigDecimal> out = new HashMap<>();
            sum.forEach((v, total) -> out.put(v, total.divide(BigDecimal.valueOf(qty.get(v)), 4, RoundingMode.HALF_UP)));
            return out;
        } catch (RuntimeException e) {
            log.info("Line paid prices: Shopify order read unavailable for tenant {} ({}) — falling back",
                     tenantId, e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private static BigDecimal scale(BigDecimal p) {
        BigDecimal v = p.signum() < 0 ? BigDecimal.ZERO : p;
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
