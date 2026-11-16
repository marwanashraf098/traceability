package com.traceability.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifySyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ONE writer of a pre-connect order row (origin 'portal_pre_connect', external_id 'internal:portal:…', V163):
 * the orders row + its order_items mapped to catalog variants. Shared by the returns portal's on-demand lookup
 * ({@link PortalOnDemandLookup}, which also stores the original Bosta delivery) and the courier-side parent import
 * ({@link PreConnectParentImport}, 2026-11-16 — a Bosta exchange / customer return pickup created after the connect
 * for an order placed before it; no delivery known, so {@code portal_delivery} / {@code portal_delivered_at} stay
 * NULL).
 *
 * Lines are untracked (no allocation, no piece is ever inferred). The row stays out of merchant_orders and
 * warehouse_orders (their external_id predicate). Idempotent: a concurrent insert of the same Shopify order, or a
 * second import, returns the existing row (UNIQUE (tenant_id, shopify_order_gid)). Not a bean — it runs on the
 * caller's JdbcTemplate, inside the caller's transaction and tenant context. Never logs the order, the customer or
 * a Shopify body.
 */
public final class PreConnectOrderStore {

    private static final Logger log = LoggerFactory.getLogger(PreConnectOrderStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PreConnectOrderStore() {}

    /**
     * Stores the order; returns its id, the existing row's id, or null when none of its lines is in the catalog.
     * {@code portalDelivery} / {@code deliveredAt} may be null (parent import).
     */
    public static UUID store(JdbcTemplate jdbc, UUID tenantId, UUID storeId, ShopifyGateway.Order order,
                             ObjectNode portalDelivery, Instant deliveredAt) {
        // Lines → catalog variants. A line whose variant Traced doesn't know is left out (it couldn't be
        // returned or exchanged through the portal anyway); with no known line at all nothing is stored.
        Map<String, Integer> quantities = new LinkedHashMap<>();
        Map<String, Integer> current = new HashMap<>();
        Map<String, String> paid = PortalOnDemandLookup.paidUnitPriceByVariant(order);
        for (JsonNode e : order.raw().path("lineItems").path("edges")) {
            JsonNode n = e.path("node");
            String gid = n.path("variant").path("id").asText(null);
            int qty = n.path("quantity").asInt(0);
            if (gid == null || qty <= 0) continue;
            quantities.merge(gid, qty, Integer::sum);
            if (n.hasNonNull("currentQuantity")) current.merge(gid, n.path("currentQuantity").asInt(0), Integer::sum);
        }
        Map<String, UUID> variants = new HashMap<>();
        if (!quantities.isEmpty()) {
            jdbc.query("SELECT external_id, id FROM variants WHERE tenant_id = ? AND external_id = ANY(?::text[])",
                rs -> { variants.put(rs.getString(1), rs.getObject(2, UUID.class)); },
                tenantId, quantities.keySet().toArray(new String[0]));
        }
        if (variants.isEmpty()) return null;
        int unmapped = quantities.size() - variants.size();
        if (unmapped > 0) log.info("Pre-connect order store: tenant {} — {} order line(s) not in the catalog, left out", tenantId, unmapped);

        String payment = ShopifySyncService.inferPaymentMethod(order.displayFinancialStatus(),
            order.paymentGateways() == null ? List.of() : order.paymentGateways());
        BigDecimal cod = "cod".equals(payment) ? order.totalPrice() : null;

        List<UUID> inserted = jdbc.queryForList(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, customer_name, customer_phone, shopify_address, " +
            "    pii_source, payment_method, cod_amount, placed_at, raw, status, origin, shopify_order_gid, " +
            "    portal_fetched_at, portal_delivered_at, portal_delivery) " +
            "SELECT ?, ?, 'internal:portal:' || gen_random_uuid(), ?, shopify_order_pii_name(r.raw), " +
            "       shopify_order_pii_phone(r.raw), shopify_order_pii_address(r.raw), 'shopify', ?::order_payment_method, ?, ?, " +
            "       r.raw, 'delivered', 'portal_pre_connect', ?, now(), ?, ?::jsonb " +
            "FROM (SELECT ?::jsonb AS raw) r " +
            "WHERE NOT EXISTS (SELECT 1 FROM orders x WHERE x.tenant_id = ? AND x.external_id = ?) " +
            "ON CONFLICT (tenant_id, shopify_order_gid) WHERE shopify_order_gid IS NOT NULL DO NOTHING " +
            "RETURNING id",
            UUID.class, tenantId, storeId, order.name(), payment, cod, Timestamp.from(order.createdAt()),
            order.gid(), deliveredAt == null ? null : Timestamp.from(deliveredAt),
            portalDelivery == null ? null : portalDelivery.toString(), order.raw().toString(),
            tenantId, order.gid());
        if (inserted.isEmpty()) {
            // A concurrent insert stored it first (or Shopify's own ingest has it now): use that row.
            return existing(jdbc, tenantId, order.gid());
        }
        UUID orderId = inserted.get(0);
        for (Map.Entry<String, Integer> line : quantities.entrySet()) {
            UUID variant = variants.get(line.getKey());
            if (variant == null) continue;
            ObjectNode raw = MAPPER.createObjectNode();
            if (current.containsKey(line.getKey())) raw.put("current_quantity", current.get(line.getKey()));
            if (paid.containsKey(line.getKey())) raw.put("paid_unit_price", paid.get(line.getKey()));
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, raw) VALUES (?, ?, ?, ?, ?::jsonb)",
                tenantId, orderId, variant, line.getValue(), raw.toString());
        }
        return orderId;
    }

    private static UUID existing(JdbcTemplate jdbc, UUID tenantId, String gid) {
        return jdbc.queryForList(
            "SELECT id FROM orders WHERE tenant_id = ? AND shopify_order_gid = ? AND origin = 'portal_pre_connect'",
            UUID.class, tenantId, gid).stream().findFirst().orElse(null);
    }
}
