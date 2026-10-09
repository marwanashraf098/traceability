package com.traceability.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Analytics slice 10 — keeps the READ columns (V157) fresh between daily passes, from the
 * inventory_items/update and inventory_levels/update webhooks. Writes ONLY variants.unit_cost (under
 * the cost rules: EGP only — the shop currency the last sync pass saw —, a manual cost never
 * overwritten), variant_shopify_levels and the stock_available_shopify* columns. Nothing on the
 * inventory write path reads any of them. Our own Traced writes to Shopify come back here as
 * inventory_levels/update too — harmless: they only refresh the read figure.
 * Runs inside the webhook processor's TenantContext; each webhook is ONE transaction here (the
 * tenant GUC is only set when a transaction begins — outside one, RLS would match nothing).
 *
 * Cost per webhook (inventory_levels/update fires on EVERY Shopify stock change, our own writes
 * included): no Shopify call; one indexed variant lookup (tenant_id, stock_inventory_item_id — the
 * write path's gid column only as a fallback for a variant the read pass hasn't seen yet), one
 * single-row upsert (or delete) on variant_shopify_levels, one single-row UPDATE of the variant.
 */
@Component
public class AnalyticsInventoryWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsInventoryWebhookHandler.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AnalyticsInventoryWebhookHandler(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    /** inventory_levels/update: {inventory_item_id, location_id, available, updated_at}. */
    public void onInventoryLevelUpdate(JsonNode payload) {
        UUID tid = TenantContext.require();
        tx.executeWithoutResult(s -> levelUpdate(tid, payload));
    }

    private void levelUpdate(UUID tid, JsonNode payload) {
        String item = ShopifyInventoryReader.numeric(payload.path("inventory_item_id").asText(null));
        String location = ShopifyInventoryReader.numeric(payload.path("location_id").asText(null));
        UUID variant = variantOf(tid, item);
        if (variant == null || location == null) {
            log.debug("inventory_levels/update: no variant for inventory item {} — ignored", item);
            return;
        }
        JsonNode available = payload.path("available");
        if (available.isNumber()) {
            jdbc.update("INSERT INTO variant_shopify_levels (tenant_id, variant_id, location_id, available, synced_at) " +
                        "VALUES (?, ?, ?, ?, now()) ON CONFLICT (variant_id, location_id) " +
                        "DO UPDATE SET available = EXCLUDED.available, synced_at = now()", tid, variant, location, available.asInt());
        } else {
            jdbc.update("DELETE FROM variant_shopify_levels WHERE variant_id = ? AND location_id = ?", variant, location);
        }
        // The Traced Main Warehouse's numeric Shopify location id (null when unlinked → traced figure null).
        jdbc.update("WITH traced AS (" +
                    "  SELECT regexp_replace(shopify_location_id, '^.*/', '') AS loc FROM locations " +
                    "  WHERE tenant_id = ? AND is_fulfillment = true AND shopify_location_id IS NOT NULL LIMIT 1) " +
                    "UPDATE variants v SET " +
                    "  stock_available_shopify = (SELECT COALESCE(SUM(l.available), 0) FROM variant_shopify_levels l WHERE l.variant_id = v.id), " +
                    "  stock_available_shopify_traced = CASE WHEN (SELECT loc FROM traced) IS NULL THEN NULL ELSE " +
                    "      COALESCE((SELECT l.available FROM variant_shopify_levels l WHERE l.variant_id = v.id " +
                    "                AND l.location_id = (SELECT loc FROM traced)), 0) END, " +
                    "  stock_synced_at = now() " +
                    "WHERE v.id = ? AND v.tenant_id = ?", tid, variant, tid);
    }

    /** inventory_items/update: {id, cost, updated_at, …} — cost has no currency: the shop's, from the last pass. */
    public void onInventoryItemUpdate(JsonNode payload) {
        UUID tid = TenantContext.require();
        tx.executeWithoutResult(s -> itemUpdate(tid, payload));
    }

    private void itemUpdate(UUID tid, JsonNode payload) {
        String item = ShopifyInventoryReader.numeric(payload.path("id").asText(null));
        UUID variant = variantOf(tid, item);
        if (variant == null) {
            log.debug("inventory_items/update: no variant for inventory item {} — ignored", item);
            return;
        }
        List<String> cur = jdbc.queryForList("SELECT shop_currency FROM analytics_inventory_sync WHERE tenant_id = ?", String.class, tid);
        String currency = cur.isEmpty() ? null : cur.get(0);
        if (currency == null) return;                                        // unknown currency: wait for the next pass
        if (!AnalyticsInventorySyncService.EGP.equalsIgnoreCase(currency)) {
            jdbc.update("UPDATE variants SET shopify_cost_flag = ?, cost_synced_at = now() WHERE id = ?", "non_egp:" + currency, variant);
            return;
        }
        BigDecimal cost = null;
        JsonNode c = payload.path("cost");
        if (c.isValueNode() && !c.isNull() && !c.asText().isBlank()) {
            try {
                cost = new BigDecimal(c.asText());
            } catch (NumberFormatException ignored) {
                return;                                                      // malformed: change nothing
            }
        }
        jdbc.update("UPDATE variants SET unit_cost = ?, cost_source = ?, cost_synced_at = now(), shopify_cost_flag = NULL " +
                    "WHERE id = ? AND tenant_id = ? AND (cost_source = 'shopify' OR unit_cost IS NULL)",
                    cost, cost == null ? null : "shopify", variant, tid);
    }

    private UUID variantOf(UUID tid, String inventoryItemId) {
        if (inventoryItemId == null) return null;
        List<UUID> v = jdbc.queryForList(
            "SELECT id FROM variants WHERE tenant_id = ? AND stock_inventory_item_id = ? LIMIT 1", UUID.class, tid, inventoryItemId);
        if (v.isEmpty()) {
            v = jdbc.queryForList("SELECT id FROM variants WHERE tenant_id = ? AND shopify_inventory_item_id = ? LIMIT 1",
                UUID.class, tid, "gid://shopify/InventoryItem/" + inventoryItemId);
        }
        return v.isEmpty() ? null : v.get(0);
    }
}
