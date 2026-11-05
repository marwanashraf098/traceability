package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shopify-native returns S1 — the ONLY writer of the V205 mirror tables and of order_items.shopify_exchange_line_gid.
 * Every write is an upsert keyed by the Shopify GID (same input twice → same rows). Every method runs under the
 * caller's tenant (app_user + RLS) in its own transaction; no Shopify call happens here.
 *
 * Nothing here changes a quantity, an allocation, an order's status, committed demand or the pick gate — S1 is data
 * only. A row is written only for an order Traced already holds (merchant_orders: the store's ingested orders, so the
 * pre-connect cutoff applies as it does to every order).
 */
@Component
public class ShopifyReturnsStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ShopifyReturnsStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Traced's order for a Shopify order GID of this store, or null (pre-connect / never ingested / portal row). */
    @Transactional(readOnly = true)
    public UUID tracedOrderId(UUID storeId, String orderGid) {
        UUID tenantId = TenantContext.require();
        if (orderGid == null || orderGid.isBlank()) return null;
        List<UUID> ids = jdbc.queryForList(
            "SELECT id FROM merchant_orders WHERE tenant_id = ? AND store_id = ? AND external_id = ?",
            UUID.class, tenantId, storeId, orderGid);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** The store's returns access state, or null (never decided). */
    @Transactional(readOnly = true)
    public String returnsAccess(UUID storeId) {
        UUID tenantId = TenantContext.require();
        List<String> s = jdbc.queryForList("SELECT returns_access FROM stores WHERE id = ? AND tenant_id = ?",
            String.class, storeId, tenantId);
        return s.isEmpty() ? null : s.get(0);
    }

    /** Records the store's returns access; returns true only when it changed (so the caller logs once, not per call). */
    @Transactional
    public boolean markAccess(UUID storeId, boolean granted) {
        UUID tenantId = TenantContext.require();
        return jdbc.update(
            "UPDATE stores SET returns_access = ?, returns_access_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND returns_access IS DISTINCT FROM ?",
            granted ? "granted" : "not_granted", storeId, tenantId, granted ? "granted" : "not_granted") > 0;
    }

    // ── Return ──────────────────────────────────────────────────────────────────────────────────

    /** Upserts the Return, its lines, exchange lines and dispositions, then links its exchange lines to order_items. */
    @Transactional
    public UUID upsertReturn(UUID storeId, UUID orderId, JsonNode ret) {
        UUID tenantId = TenantContext.require();
        String returnGid = ret.path("id").asText();
        String orderGid = ret.path("order").path("id").asText();
        boolean truncated = ret.path("returnLineItems").path("pageInfo").path("hasNextPage").asBoolean(false)
            || ret.path("exchangeLineItems").path("pageInfo").path("hasNextPage").asBoolean(false);
        String status = ret.path("status").asText("UNKNOWN");
        UUID returnId = jdbc.queryForObject("""
            INSERT INTO shopify_returns (tenant_id, store_id, return_gid, order_id, order_gid, name, status, total_quantity,
                decline_reason, decline_note, shopify_created_at, request_approved_at, closed_at, cancelled_seen_at,
                lines_truncated, raw)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? = 'CANCELED' THEN now() END, ?, ?::jsonb)
            ON CONFLICT (tenant_id, return_gid) DO UPDATE SET
                store_id = EXCLUDED.store_id, order_id = EXCLUDED.order_id, order_gid = EXCLUDED.order_gid,
                name = EXCLUDED.name, status = EXCLUDED.status, total_quantity = EXCLUDED.total_quantity,
                decline_reason = EXCLUDED.decline_reason, decline_note = EXCLUDED.decline_note,
                shopify_created_at = EXCLUDED.shopify_created_at, request_approved_at = EXCLUDED.request_approved_at,
                closed_at = EXCLUDED.closed_at,
                cancelled_seen_at = COALESCE(shopify_returns.cancelled_seen_at, EXCLUDED.cancelled_seen_at),
                lines_truncated = EXCLUDED.lines_truncated, raw = EXCLUDED.raw,
                fetched_at = now(), updated_at = now()
            RETURNING id
            """, UUID.class,
            tenantId, storeId, returnGid, orderId, orderGid, text(ret.path("name")), status,
            ret.hasNonNull("totalQuantity") ? ret.path("totalQuantity").asInt() : null,
            text(ret.path("decline").path("reason")), text(ret.path("decline").path("note")),
            ts(ret.path("createdAt")), ts(ret.path("requestApprovedAt")), ts(ret.path("closedAt")), status,
            truncated, ret.toString());

        Map<String, String> returnLineByFulfillmentLine = new HashMap<>();
        for (JsonNode l : ret.path("returnLineItems").path("nodes")) {
            String lineGid = l.path("id").asText("");
            if (lineGid.isEmpty()) continue;
            JsonNode fli = l.path("fulfillmentLineItem");
            String fliGid = text(fli.path("id"));
            String variantGid = text(fli.path("lineItem").path("variant").path("id"));
            if (fliGid != null) returnLineByFulfillmentLine.put(fliGid, lineGid);
            jdbc.update("""
                INSERT INTO shopify_return_lines (tenant_id, return_id, line_gid, order_line_gid, fulfillment_line_gid,
                    variant_gid, variant_id, quantity, processed_quantity, reason, reason_note, customer_note, raw)
                VALUES (?, ?, ?, ?, ?, ?, (SELECT id FROM variants WHERE tenant_id = ? AND external_id = ?), ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (tenant_id, line_gid) DO UPDATE SET
                    return_id = EXCLUDED.return_id, order_line_gid = EXCLUDED.order_line_gid,
                    fulfillment_line_gid = EXCLUDED.fulfillment_line_gid, variant_gid = EXCLUDED.variant_gid,
                    variant_id = EXCLUDED.variant_id, quantity = EXCLUDED.quantity,
                    processed_quantity = EXCLUDED.processed_quantity, reason = EXCLUDED.reason,
                    reason_note = EXCLUDED.reason_note, customer_note = EXCLUDED.customer_note, raw = EXCLUDED.raw,
                    updated_at = now()
                """,
                tenantId, returnId, lineGid, text(fli.path("lineItem").path("id")), fliGid, variantGid,
                tenantId, variantGid, l.path("quantity").asInt(0), l.path("processedQuantity").asInt(0),
                text(l.path("returnReasonDefinition").path("handle")), text(l.path("returnReasonNote")),
                text(l.path("customerNote")), l.toString());
        }

        for (JsonNode x : ret.path("exchangeLineItems").path("nodes")) {
            String lineGid = x.path("id").asText("");
            if (lineGid.isEmpty()) continue;
            List<String> orderLines = new ArrayList<>();
            for (JsonNode li : x.path("lineItems")) {
                String g = li.path("id").asText("");
                if (!g.isEmpty()) orderLines.add(g);
            }
            String variantGid = text(x.path("variantId"));
            jdbc.update("""
                INSERT INTO shopify_exchange_lines (tenant_id, return_id, line_gid, variant_gid, variant_id, quantity,
                    processed_quantity, order_line_gids, raw)
                VALUES (?, ?, ?, ?, (SELECT id FROM variants WHERE tenant_id = ? AND external_id = ?), ?, ?, ?::text[], ?::jsonb)
                ON CONFLICT (tenant_id, line_gid) DO UPDATE SET
                    return_id = EXCLUDED.return_id, variant_gid = EXCLUDED.variant_gid, variant_id = EXCLUDED.variant_id,
                    quantity = EXCLUDED.quantity, processed_quantity = EXCLUDED.processed_quantity,
                    order_line_gids = EXCLUDED.order_line_gids, raw = EXCLUDED.raw, updated_at = now()
                """,
                tenantId, returnId, lineGid, variantGid, tenantId, variantGid, x.path("quantity").asInt(0),
                x.path("processedQuantity").asInt(0), orderLines.toArray(new String[0]), x.toString());
        }

        for (JsonNode rfo : ret.path("reverseFulfillmentOrders").path("nodes")) {
            String rfoGid = rfo.path("id").asText("");
            for (JsonNode rl : rfo.path("lineItems").path("nodes")) {
                String rfoLineGid = rl.path("id").asText("");
                String fliGid = text(rl.path("fulfillmentLineItem").path("id"));
                for (JsonNode d : rl.path("dispositions")) {
                    String dGid = d.path("id").asText("");
                    if (dGid.isEmpty()) continue;
                    jdbc.update("""
                        INSERT INTO shopify_rfo_dispositions (tenant_id, return_id, disposition_gid, rfo_gid, rfo_line_gid,
                            return_line_gid, fulfillment_line_gid, type, quantity, location_gid, raw)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        ON CONFLICT (tenant_id, disposition_gid) DO UPDATE SET
                            return_id = EXCLUDED.return_id, rfo_gid = EXCLUDED.rfo_gid, rfo_line_gid = EXCLUDED.rfo_line_gid,
                            return_line_gid = EXCLUDED.return_line_gid, fulfillment_line_gid = EXCLUDED.fulfillment_line_gid,
                            type = EXCLUDED.type, quantity = EXCLUDED.quantity, location_gid = EXCLUDED.location_gid,
                            raw = EXCLUDED.raw, updated_at = now()
                        """,
                        tenantId, returnId, dGid, rfoGid, rfoLineGid,
                        fliGid == null ? null : returnLineByFulfillmentLine.get(fliGid), fliGid,
                        d.path("type").asText("UNKNOWN"), d.path("quantity").asInt(0),
                        text(d.path("location").path("id")), d.toString());
                }
            }
        }

        linkExchangeLines(tenantId, orderId);
        return returnId;
    }

    // ── FulfillmentOrder ────────────────────────────────────────────────────────────────────────

    @Transactional
    public UUID upsertFulfillmentOrder(UUID storeId, UUID orderId, JsonNode fo) {
        UUID tenantId = TenantContext.require();
        List<String> holds = new ArrayList<>();
        for (JsonNode h : fo.path("fulfillmentHolds")) {
            String r = h.path("reason").asText("");
            if (!r.isEmpty()) holds.add(r);
        }
        ArrayNode lines = mapper.createArrayNode();
        for (JsonNode l : fo.path("lineItems").path("nodes")) {
            ObjectNode o = lines.addObject();
            o.put("foLineGid", l.path("id").asText(null));
            o.put("lineItemGid", l.path("lineItem").path("id").asText(null));
            o.put("variantGid", l.path("lineItem").path("variant").path("id").asText(null));
            o.put("remainingQuantity", l.path("remainingQuantity").asInt(0));
            o.put("totalQuantity", l.path("totalQuantity").asInt(0));
        }
        return jdbc.queryForObject("""
            INSERT INTO shopify_fulfillment_orders (tenant_id, store_id, fo_gid, order_id, order_gid, status, request_status,
                hold_reasons, line_items, lines_truncated, shopify_created_at, shopify_updated_at, raw)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::text[], ?::jsonb, ?, ?, ?, ?::jsonb)
            ON CONFLICT (tenant_id, fo_gid) DO UPDATE SET
                store_id = EXCLUDED.store_id, order_id = EXCLUDED.order_id, order_gid = EXCLUDED.order_gid,
                status = EXCLUDED.status, request_status = EXCLUDED.request_status, hold_reasons = EXCLUDED.hold_reasons,
                line_items = EXCLUDED.line_items, lines_truncated = EXCLUDED.lines_truncated,
                shopify_created_at = EXCLUDED.shopify_created_at, shopify_updated_at = EXCLUDED.shopify_updated_at,
                raw = EXCLUDED.raw, fetched_at = now(), updated_at = now()
            RETURNING id
            """, UUID.class,
            tenantId, storeId, fo.path("id").asText(), orderId, fo.path("order").path("id").asText(),
            fo.path("status").asText("UNKNOWN"), text(fo.path("requestStatus")), holds.toArray(new String[0]),
            lines.toString(), fo.path("lineItems").path("pageInfo").path("hasNextPage").asBoolean(false),
            ts(fo.path("createdAt")), ts(fo.path("updatedAt")), fo.toString());
    }

    // ── exchange line link (data only) ──────────────────────────────────────────────────────────

    /**
     * Marks the order's lines that came from a Shopify exchange line (order_items.external_id in
     * ExchangeLineItem.lineItems[].id). Either side can arrive first: called after a return upsert (the order line may
     * already be stored — orders/updated came first) and after an order upsert (the exchange line may already be
     * stored — returns/process came first). Touches nothing but shopify_exchange_line_gid.
     */
    @Transactional
    public int linkExchangeLinesForOrder(UUID orderId) {
        return linkExchangeLines(TenantContext.require(), orderId);
    }

    /** The order-upsert side: by Shopify order GID; a cheap no-op for an order with no stored return. */
    @Transactional
    public int linkExchangeLinesForOrderGid(UUID storeId, String orderGid) {
        UUID tenantId = TenantContext.require();
        List<UUID> orders = jdbc.queryForList(
            "SELECT DISTINCT r.order_id FROM shopify_returns r WHERE r.tenant_id = ? AND r.store_id = ? AND r.order_gid = ?",
            UUID.class, tenantId, storeId, orderGid);
        int n = 0;
        for (UUID o : orders) n += linkExchangeLines(tenantId, o);
        return n;
    }

    private int linkExchangeLines(UUID tenantId, UUID orderId) {
        return jdbc.update("""
            UPDATE order_items oi SET shopify_exchange_line_gid = x.line_gid
            FROM (SELECT el.line_gid, g.gid
                    FROM shopify_exchange_lines el
                    JOIN shopify_returns r ON r.id = el.return_id AND r.tenant_id = el.tenant_id
                    CROSS JOIN LATERAL unnest(el.order_line_gids) AS g(gid)
                   WHERE r.tenant_id = ? AND r.order_id = ?) x
            WHERE oi.tenant_id = ? AND oi.order_id = ? AND oi.external_id = x.gid
              AND oi.shopify_exchange_line_gid IS DISTINCT FROM x.line_gid
            """, tenantId, orderId, tenantId, orderId);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText();
        return s.isEmpty() ? null : s;
    }

    private static Timestamp ts(JsonNode n) {
        String s = text(n);
        if (s == null) return null;
        try {
            return Timestamp.from(OffsetDateTime.parse(s).toInstant());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
