package com.traceability.courier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Non-Bosta couriers G4b (V180) — the replacement of a GENERIC exchange (Option B, approved 2026-10-21): no
 * exchanges row (that table is Bosta type-30 shaped — one tracking number both ways, raw from Bosta) and nothing
 * of ExchangeService / linkAtMapTime. The replacement is an internal order
 * {@code internal:exchange:rr:<request id>} that the request names in {@code return_requests.replacement_order_id}.
 *
 * The 'internal:exchange:' prefix keeps every existing reader treating it as an exchange replacement (analytics
 * leave it out of sales, never a Shopify order); the Step-5a decrement applies because the request names it
 * (ShopifyInventoryService.processExchangeDispatch). It is not pickable until the merchant enters its outbound
 * waybill (GenericLegTransitions.createReplacementForwardLeg) — then it packs and ships like any generic order.
 *
 * Not a bean: callers pass their own JdbcTemplate (their connection, tenant context and transaction).
 */
public final class GenericExchanges {

    private static final Logger log = LoggerFactory.getLogger(GenericExchanges.class);

    private GenericExchanges() {}

    public static final String EXTERNAL_ID_PREFIX = "internal:exchange:rr:";

    /**
     * The replacement order for an approved generic exchange request: one order line of the replacement variant
     * (quantity 1), the customer's name, phone and Shopify shipping address copied from the original order (never
     * a Bosta raw — there is none), number "EXC-" + the request reference. Idempotent: a request that already names
     * its replacement gets that one back; the order's external id is unique per store.
     *
     * @return the replacement order id, or null when the request isn't an exchange with a replacement variant
     */
    public static UUID attachGenericForRequest(JdbcTemplate jdbc, UUID tenantId, UUID requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT rr.order_id, rr.reference, rr.replacement_order_id, i.replacement_variant_id, p.store_id " +
            "FROM return_requests rr " +
            "JOIN return_request_items i ON i.request_id = rr.id AND i.tenant_id = rr.tenant_id " +
            "JOIN variants v ON v.id = i.replacement_variant_id AND v.tenant_id = rr.tenant_id " +
            "JOIN products p ON p.id = v.product_id " +
            "WHERE rr.id = ? AND rr.tenant_id = ? AND rr.type = 'exchange' " +
            "ORDER BY i.created_at, i.id LIMIT 1 FOR UPDATE OF rr", requestId, tenantId);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        if (r.get("replacement_order_id") != null) return (UUID) r.get("replacement_order_id");

        String externalId = EXTERNAL_ID_PREFIX + requestId;
        UUID orderId = jdbc.queryForList(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, placed_at, raw, customer_name, customer_phone, " +
            "    shopify_address, pii_source) " +
            "SELECT ?, ?, ?, ?, now(), '{}'::jsonb, " +
            "       CASE WHEN o.pii_redacted_at IS NULL THEN o.customer_name END, " +
            "       CASE WHEN o.pii_redacted_at IS NULL THEN o.customer_phone END, " +
            "       CASE WHEN o.pii_redacted_at IS NULL THEN o.shopify_address END, " +
            "       CASE WHEN o.pii_redacted_at IS NULL THEN o.pii_source END " +
            "FROM merchant_orders o WHERE o.id = ? AND o.tenant_id = ? " +
            "ON CONFLICT (store_id, external_id) DO NOTHING RETURNING id",
            UUID.class, tenantId, r.get("store_id"), externalId, "EXC-" + r.get("reference"),
            r.get("order_id"), tenantId).stream().findFirst().orElse(null);
        if (orderId == null) {
            orderId = jdbc.queryForObject("SELECT id FROM merchant_orders WHERE tenant_id = ? AND external_id = ?",
                UUID.class, tenantId, externalId);
        } else {
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)",
                tenantId, orderId, r.get("replacement_variant_id"));
        }
        jdbc.update("UPDATE return_requests SET replacement_order_id = ? WHERE id = ? AND tenant_id = ? " +
            "AND replacement_order_id IS NULL", orderId, requestId, tenantId);
        log.info("Generic exchange request {} — replacement order {}", requestId, orderId);
        return orderId;
    }
}
