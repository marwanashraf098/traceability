package com.traceability.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shopify-native returns S3b — exchange SHADOW orders and their reservation from approval (V218, decisions 2026-11-06).
 *
 * For each Shopify Return with exchange lines on an order Traced holds, once the return is approved (status OPEN /
 * CLOSED), the store has returns access, and the original order has left Traced custody ({@link OrderLineFreeze} — Q2:
 * before that the original's own line carries the demand), Traced keeps the replacement aside on ONE shadow order:
 * external_id {@code internal:shopify-exchange:<return gid>}, origin 'shopify_exchange', number {@code #<original>-X1}
 * (-X2… per return of the order), placed_at = the return's approval time, the original's name / phone / Shopify address
 * (never email; nothing when the original is redacted), status 'confirmed' — outside every pick status, so it is never
 * packable before S3c. Its lines are the exchange lines (variant, LIVE quantity) and count as committed demand by count
 * ({@link VariantStockService}); never a pinned piece, never On Hold, never a Shopify write.
 *
 * <p>{@link #reconcileForOrder} is THE writer (shadow orders, their lines, V218 tables). Idempotent, one per-order
 * advisory lock, reads the mirror's CURRENT state (rows are never deleted, so existence means nothing). Per exchange line:
 * <pre>
 *   return DECLINED / CANCELED                         → 0, stop 'declined' / 'cancelled'
 *   return REQUESTED                                   → not approved yet: no shadow (an existing one is left as it is)
 *   no fulfillment order holds the line yet:  OPEN    → its quantity (unprocessed demand reserves from approval)
 *                                             CLOSED  → 0, stop 'closed_in_shopify'
 *   otherwise: live = remaining quantity on its OPEN / IN_PROGRESS / ON_HOLD / SCHEDULED fulfillment orders (a cancelled
 *     FO with a replacement FO stays reserved — re-keyed by the line); shipped = fulfilled units on non-cancelled FOs;
 *     live 0 → stop 'shipped_in_shopify' when Shopify fulfilled some, else 'fo_cancelled'
 *   a Shopify-native internal:exchange: replacement already exists for the original + variant → 0, stop 'stood_down'
 *     (no double demand; S3d removes the duplicate properly)
 * </pre>
 * Re-held after release keeps the reservation (ON_HOLD is live). A shadow whose lines are all 0 is 'ended' in V218 (it
 * reserves nothing: its lines are 0); one that comes back to life (a new FO) is 'live' again. The shadow ORDER's status
 * stays 'confirmed' in S3b — never written backward (OrderStatusBackwardWriteGuardTest); S3c owns its packing states. Alerts are read from these rows by
 * ExceptionService: {@code shopify_exchange_short} (per variant, oldest shadow first — {@link #shortages}) and
 * {@code shopify_exchange_shipped_in_shopify} (a line Shopify fulfilled while Traced still had it unpacked).
 */
@Component
public class ShopifyShadowOrders {

    private static final Logger log = LoggerFactory.getLogger(ShopifyShadowOrders.class);

    public static final String PREFIX = "internal:shopify-exchange:";
    /** Fulfillment order states in which the replacement is still to be sent. */
    static final Set<String> LIVE_FO = Set.of("OPEN", "IN_PROGRESS", "ON_HOLD", "SCHEDULED");
    static final Set<String> DEAD_FO = Set.of("CANCELLED", "INCOMPLETE");
    /** Shadow order statuses that reserve stock (S3b writes 'confirmed'; S3c's released states included). */
    public static final String LIVE_STATUSES_SQL = "('confirmed', 'new', 'ready_to_pick', 'self_pickup_pending')";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public ShopifyShadowOrders(JdbcTemplate jdbc, PlatformTransactionManager txm, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.mapper = mapper;
    }

    // ── hook entry points (never throw: the next signal / the backfill re-runs the reconcile) ──────────────────────

    /** After a return / fulfillment-order upsert or an order-side exchange-line link. Current tenant. */
    public void reconcileQuietly(UUID orderId) {
        try {
            reconcileForOrder(orderId);
        } catch (RuntimeException e) {
            log.warn("Shopify exchange shadow reconcile failed for order {} — the next signal re-runs it: {}", orderId, e.toString());
        }
    }

    /** The order-upsert side: by Shopify order GID; a cheap no-op for an order with no mirrored return. */
    public void reconcileForOrderGidQuietly(UUID storeId, String orderGid) {
        try {
            UUID tenantId = TenantContext.require();
            List<UUID> ids = tx.execute(st -> jdbc.queryForList(
                "SELECT DISTINCT r.order_id FROM shopify_returns r WHERE r.tenant_id = ? AND r.store_id = ? AND r.order_gid = ?",
                UUID.class, tenantId, storeId, orderGid));
            for (UUID id : ids) reconcileForOrder(id);
        } catch (RuntimeException e) {
            log.warn("Shopify exchange shadow reconcile failed for {} — the next signal re-runs it: {}", orderGid, e.toString());
        }
    }

    // ── the reconcile ──────────────────────────────────────────────────────────────────────────────────────────────

    /** created / updated / ended shadows; units = live units of the shadows created (or, dry run, to be created). */
    public record Outcome(int created, int updated, int ended, int units) {
        static final Outcome NONE = new Outcome(0, 0, 0, 0);
    }

    /** Reconciles every shadow of one ORIGINAL order with the mirror. Current tenant; own transaction. */
    public Outcome reconcileForOrder(UUID originalOrderId) {
        UUID tenantId = TenantContext.require();
        // Cheap exit for the common case (every imported / updated order passes here): no mirrored return.
        if (!Boolean.TRUE.equals(tx.execute(st -> jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM shopify_returns WHERE tenant_id = ? AND order_id = ?)",
                Boolean.class, tenantId, originalOrderId)))) {
            return Outcome.NONE;
        }
        return tx.execute(st -> {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)", rs -> null, "shopify-shadow:" + originalOrderId);
            return reconcileLocked(tenantId, originalOrderId, false);
        });
    }

    private record Original(UUID id, UUID storeId, String number, boolean frozen, boolean access) {}
    private record XLine(UUID id, UUID variantId, int quantity, List<String> orderLineGids) {}
    private record Plan(XLine line, int live, int shipped, String stop) {}

    Outcome reconcileLocked(UUID tenantId, UUID originalOrderId, boolean dryRun) {
        List<Original> os = jdbc.query(
            "SELECT o.id, o.store_id, o.number, " + OrderLineFreeze.frozenSql("o.id", "o.tenant_id") + " AS frozen, " +
            "       COALESCE(s.returns_access = 'granted', false) AS access " +
            "FROM merchant_orders o JOIN stores s ON s.id = o.store_id AND s.tenant_id = o.tenant_id " +
            "WHERE o.id = ? AND o.tenant_id = ? AND o.external_id LIKE 'gid://shopify/Order/%'",
            (rs, i) -> new Original(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getBoolean(4), rs.getBoolean(5)), originalOrderId, tenantId);
        if (os.isEmpty() || !os.get(0).access()) return Outcome.NONE;   // no returns access: today's flow, untouched
        Original o = os.get(0);

        List<Map<String, Object>> fos = jdbc.queryForList(
            "SELECT status, line_items::text AS line_items FROM shopify_fulfillment_orders WHERE tenant_id = ? AND order_id = ?",
            tenantId, o.id());
        int created = 0, updated = 0, ended = 0, units = 0;
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT id, return_gid, status, COALESCE(request_approved_at, shopify_created_at, created_at) AS approved_at " +
                "FROM shopify_returns WHERE tenant_id = ? AND order_id = ? " +
                "ORDER BY COALESCE(request_approved_at, shopify_created_at, created_at), id", tenantId, o.id())) {
            UUID returnId = (UUID) r.get("id");
            String status = (String) r.get("status");
            List<XLine> lines = jdbc.query(
                "SELECT id, variant_id, quantity, order_line_gids FROM shopify_exchange_lines " +
                "WHERE tenant_id = ? AND return_id = ? AND variant_id IS NOT NULL ORDER BY line_gid",
                (rs, i) -> new XLine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3),
                    List.of((String[]) rs.getArray(4).getArray())), tenantId, returnId);
            if (lines.isEmpty() || "REQUESTED".equals(status)) continue;

            List<Plan> plans = new ArrayList<>();
            for (XLine x : lines) plans.add(plan(tenantId, o.id(), status, x, fos));

            List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, order_id, state FROM shopify_exchange_shadows WHERE tenant_id = ? AND return_id = ?", tenantId, returnId);
            boolean anyLive = plans.stream().anyMatch(p -> p.live() > 0);
            if (existing.isEmpty()) {
                if (!anyLive || !o.frozen()) continue;          // Q2: the original's own line carries the demand
                created++;
                units += plans.stream().mapToInt(Plan::live).sum();
                if (dryRun) continue;
                createShadow(tenantId, o, r, plans);
            } else {
                if (dryRun) continue;
                Map<String, Object> s = existing.get(0);
                boolean wasLive = "live".equals(s.get("state"));
                updateShadow(tenantId, (UUID) s.get("id"), (UUID) s.get("order_id"), status, plans);
                updated++;
                if (wasLive && !anyLive) ended++;
            }
        }
        return new Outcome(created, updated, ended, units);
    }

    private Plan plan(UUID tenantId, UUID originalId, String returnStatus, XLine x, List<Map<String, Object>> fos) {
        if ("DECLINED".equals(returnStatus)) return new Plan(x, 0, 0, "declined");
        if ("CANCELED".equals(returnStatus)) return new Plan(x, 0, 0, "cancelled");
        int live = 0, shipped = 0;
        boolean matched = false;
        for (Map<String, Object> fo : fos) {
            String foStatus = (String) fo.get("status");
            for (JsonNode li : json((String) fo.get("line_items"))) {
                if (!x.orderLineGids().contains(li.path("lineItemGid").asText(""))) continue;
                matched = true;
                int remaining = li.path("remainingQuantity").asInt(0);
                int total = li.path("totalQuantity").asInt(0);
                if (LIVE_FO.contains(foStatus)) live += remaining;
                if (!DEAD_FO.contains(foStatus)) shipped += Math.max(0, total - remaining);
            }
        }
        Plan p;
        if (!matched) {
            p = "CLOSED".equals(returnStatus) ? new Plan(x, 0, 0, "closed_in_shopify") : new Plan(x, x.quantity(), 0, null);
        } else {
            live = Math.min(live, x.quantity());
            p = live > 0 ? new Plan(x, live, shipped, null)
                : new Plan(x, 0, shipped, shipped > 0 ? "shipped_in_shopify" : "fo_cancelled");
        }
        if (p.live() > 0 && nativeReplacementExists(tenantId, originalId, x.variantId())) {
            return new Plan(x, 0, p.shipped(), "stood_down");
        }
        return p;
    }

    /** A Shopify-native exchange Traced already turned into an internal:exchange: replacement (dashboard Bosta type 30). */
    private boolean nativeReplacementExists(UUID tenantId, UUID originalId, UUID variantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM exchanges e " +
            "  JOIN merchant_orders xo ON xo.id = e.outbound_order_id AND xo.tenant_id = e.tenant_id " +
            "  WHERE e.tenant_id = ? AND e.matched_order_id = ? AND e.outbound_variant_id = ? " +
            "    AND xo.external_id LIKE 'internal:exchange:%' AND xo.status <> 'cancelled' " +
            "    AND " + ShopifyNativeExchange.nativeExchangeSql("e.outbound_variant_id") + ")",
            Boolean.class, tenantId, originalId, variantId));
    }

    private void createShadow(UUID tenantId, Original o, Map<String, Object> r, List<Plan> plans) {
        int x = jdbc.queryForObject(
            "SELECT COALESCE(MAX(x_index), 0) + 1 FROM shopify_exchange_shadows WHERE tenant_id = ? AND original_order_id = ?",
            Integer.class, tenantId, o.id());
        String number = (o.number() == null ? "" : o.number()) + "-X" + x;
        Timestamp approvedAt = (Timestamp) r.get("approved_at");
        boolean anyLive = plans.stream().anyMatch(p -> p.live() > 0);
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw, origin, " +
            "    customer_name, customer_phone, shopify_address, pii_source, pii_redacted_at) " +
            "SELECT ?, o.store_id, ?, ?, ?::order_status, 'prepaid', ?, '{}'::jsonb, 'shopify_exchange', " +
            "       CASE WHEN o.pii_redacted_at IS NULL THEN o.customer_name END, " +
            "       CASE WHEN o.pii_redacted_at IS NULL THEN o.customer_phone END, " +
            "       CASE WHEN o.pii_redacted_at IS NULL THEN o.shopify_address END, " +
            "       CASE WHEN o.pii_redacted_at IS NULL THEN o.pii_source END, o.pii_redacted_at " +
            "FROM merchant_orders o WHERE o.id = ? AND o.tenant_id = ? RETURNING id",
            UUID.class, tenantId, PREFIX + r.get("return_gid"), number, "confirmed",
            approvedAt, o.id(), tenantId);
        UUID shadowId = jdbc.queryForObject(
            "INSERT INTO shopify_exchange_shadows (tenant_id, store_id, return_id, order_id, original_order_id, x_index, " +
            "    approved_at, state, end_reason, ended_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
            UUID.class, tenantId, o.storeId(), r.get("id"), orderId, o.id(), x, approvedAt,
            anyLive ? "live" : "ended", anyLive ? null : "all_lines_ended", anyLive ? null : new Timestamp(System.currentTimeMillis()));
        for (Plan p : plans) {
            UUID item = jdbc.queryForObject(
                "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class, tenantId, orderId, p.line().variantId(), p.live(), "exchange-line:" + p.line().id());
            jdbc.update(
                "INSERT INTO shopify_exchange_shadow_lines (tenant_id, shadow_id, exchange_line_id, order_item_id, variant_id, " +
                "    quantity, live_quantity, shipped_in_shopify, stop_reason, stopped_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ?::text IS NULL THEN NULL ELSE now() END)",
                tenantId, shadowId, p.line().id(), item, p.line().variantId(), p.line().quantity(), p.live(), p.shipped(),
                p.stop(), p.stop());
        }
        log.info("Shopify exchange shadow {} ({}) kept aside for original order {}", number, orderId, o.id());
    }

    private void updateShadow(UUID tenantId, UUID shadowId, UUID orderId, String returnStatus, List<Plan> plans) {
        for (Plan p : plans) {
            List<UUID> items = jdbc.queryForList(
                "SELECT order_item_id FROM shopify_exchange_shadow_lines WHERE tenant_id = ? AND shadow_id = ? AND exchange_line_id = ?",
                UUID.class, tenantId, shadowId, p.line().id());
            if (items.isEmpty()) {
                UUID item = jdbc.queryForObject(
                    "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) VALUES (?, ?, ?, ?, ?) RETURNING id",
                    UUID.class, tenantId, orderId, p.line().variantId(), p.live(), "exchange-line:" + p.line().id());
                jdbc.update(
                    "INSERT INTO shopify_exchange_shadow_lines (tenant_id, shadow_id, exchange_line_id, order_item_id, variant_id, " +
                    "    quantity, live_quantity, shipped_in_shopify, stop_reason, stopped_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ?::text IS NULL THEN NULL ELSE now() END)",
                    tenantId, shadowId, p.line().id(), item, p.line().variantId(), p.line().quantity(), p.live(), p.shipped(),
                    p.stop(), p.stop());
                continue;
            }
            UUID item = items.get(0);
            jdbc.update("UPDATE order_items SET quantity = ? WHERE id = ? AND tenant_id = ? AND quantity IS DISTINCT FROM ?",
                p.live(), item, tenantId, p.live());
            jdbc.update(
                "UPDATE shopify_exchange_shadow_lines SET quantity = ?, live_quantity = ?, shipped_in_shopify = ?, " +
                "    stop_reason = ?, stopped_at = CASE WHEN ?::text IS NULL THEN NULL ELSE COALESCE(stopped_at, now()) END, " +
                "    updated_at = now() " +
                "WHERE tenant_id = ? AND order_item_id = ? AND (quantity, live_quantity, shipped_in_shopify, stop_reason) " +
                "    IS DISTINCT FROM (?, ?, ?, ?)",
                p.line().quantity(), p.live(), p.shipped(), p.stop(), p.stop(), tenantId, item,
                p.line().quantity(), p.live(), p.shipped(), p.stop());
        }
        boolean live = Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM shopify_exchange_shadow_lines WHERE tenant_id = ? AND shadow_id = ? AND live_quantity > 0)",
            Boolean.class, tenantId, shadowId));
        String endReason = "DECLINED".equals(returnStatus) ? "declined" : "CANCELED".equals(returnStatus) ? "cancelled" : "all_lines_ended";
        if (live) {
            jdbc.update("UPDATE shopify_exchange_shadows SET state = 'live', end_reason = NULL, ended_at = NULL, updated_at = now() " +
                "WHERE id = ? AND tenant_id = ? AND state <> 'live'", shadowId, tenantId);
        } else {
            jdbc.update("UPDATE shopify_exchange_shadows SET state = 'ended', end_reason = ?, ended_at = COALESCE(ended_at, now()), " +
                "updated_at = now() WHERE id = ? AND tenant_id = ? AND (state <> 'ended' OR end_reason IS DISTINCT FROM ?)",
                endReason, shadowId, tenantId, endReason);
        }
    }

    // ── Short by N (per variant, oldest shadow first) ─────────────────────────────────────────────────────────────

    /** One live shadow's share of a variant's shortage. */
    public record ShadowShort(UUID orderId, String number, int shortBy) {}
    /** A variant whose live shadows can't all be covered: shortBy = MIN(shadow demand, −available). */
    public record VariantShort(UUID variantId, int shortBy, List<ShadowShort> shadows) {}

    /**
     * Per variant: the live shadows' unallocated demand that available stock can't cover (available = on hand −
     * committed, committed including the shadows — {@link VariantStockService}). Covered oldest approval first, so the
     * newest shadows are the short ones. Runs on the caller's connection and tenant context.
     */
    public static Map<UUID, VariantShort> shortages(JdbcTemplate jdbc) {
        UUID tenantId = TenantContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT sl.variant_id, s.order_id, o.number, s.approved_at, " +
            "       GREATEST(sl.live_quantity - (SELECT COUNT(*) FROM allocations a WHERE a.order_item_id = sl.order_item_id " +
            "                                    AND a.tenant_id = sl.tenant_id AND a.status = 'active'), 0) AS demand " +
            "FROM shopify_exchange_shadow_lines sl " +
            "JOIN shopify_exchange_shadows s ON s.id = sl.shadow_id AND s.tenant_id = sl.tenant_id AND s.state = 'live' " +
            "JOIN warehouse_orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id " +
            "  AND o.status::text IN " + LIVE_STATUSES_SQL + " " +
            "WHERE sl.tenant_id = ? AND sl.live_quantity > 0 " +
            "ORDER BY s.approved_at, s.id", tenantId);
        if (rows.isEmpty()) return Map.of();
        Map<UUID, VariantStockService.VariantStock> stock = new VariantStockService(jdbc).computeAll();
        Map<UUID, List<Map<String, Object>>> byVariant = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) byVariant.computeIfAbsent((UUID) r.get("variant_id"), k -> new ArrayList<>()).add(r);
        Map<UUID, VariantShort> out = new LinkedHashMap<>();
        byVariant.forEach((variantId, shadows) -> {
            long demand = shadows.stream().mapToLong(r -> ((Number) r.get("demand")).longValue()).sum();
            long available = stock.getOrDefault(variantId, new VariantStockService.VariantStock(0, 0, 0)).available();
            int shortBy = (int) Math.min(demand, Math.max(0, -available));
            if (shortBy <= 0) return;
            long covered = demand - shortBy;                    // oldest first
            Map<UUID, int[]> perOrder = new LinkedHashMap<>();
            Map<UUID, String> numbers = new HashMap<>();
            for (Map<String, Object> r : shadows) {
                int d = ((Number) r.get("demand")).intValue();
                int take = (int) Math.min(d, covered);
                covered -= take;
                UUID orderId = (UUID) r.get("order_id");
                numbers.put(orderId, (String) r.get("number"));
                perOrder.computeIfAbsent(orderId, k -> new int[1])[0] += d - take;
            }
            List<ShadowShort> list = new ArrayList<>();
            perOrder.forEach((id, n) -> { if (n[0] > 0) list.add(new ShadowShort(id, numbers.get(id), n[0])); });
            out.put(variantId, new VariantShort(variantId, shortBy, list));
        });
        return out;
    }

    // ── backfill (one-off, at deploy) ──────────────────────────────────────────────────────────────────────────────

    public record BackfillCounts(int orders, int shadowsToCreate, int unitsToKeepAside) {}

    /**
     * The originals with an approved return that has exchange lines in this tenant (current tenant). dryRun: counts
     * what reconcile WOULD create (nothing written); apply: reconciles each, one transaction per order.
     */
    public BackfillCounts backfill(boolean dryRun) {
        UUID tenantId = TenantContext.require();
        List<UUID> orders = tx.execute(st -> jdbc.queryForList(
            "SELECT DISTINCT r.order_id FROM shopify_returns r " +
            "JOIN shopify_exchange_lines x ON x.return_id = r.id AND x.tenant_id = r.tenant_id " +
            "WHERE r.tenant_id = ? AND r.status IN ('OPEN', 'CLOSED')", UUID.class, tenantId));
        int toCreate = 0, units = 0;
        for (UUID id : orders) {
            Outcome out = dryRun ? tx.execute(st -> reconcileLocked(tenantId, id, true)) : reconcileForOrder(id);
            toCreate += out.created();
            units += out.units();
        }
        return new BackfillCounts(orders.size(), toCreate, units);
    }

    private JsonNode json(String s) {
        try {
            JsonNode n = mapper.readTree(s == null ? "[]" : s);
            return n.isArray() ? n : mapper.createArrayNode();
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }
}
