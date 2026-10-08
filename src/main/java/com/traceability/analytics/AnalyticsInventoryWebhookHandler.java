package com.traceability.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
 * Runs inside the webhook processor's TenantContext.
 */
@Component
public class AnalyticsInventoryWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsInventoryWebhookHandler.class);

    private final JdbcTemplate jdbc;

    public AnalyticsInventoryWebhookHandler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** inventory_levels/update: {inventory_item_id, location_id, available, updated_at}. */
    public void onInventoryLevelUpdate(JsonNode payload) {
        UUID tid = TenantContext.require();
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
        String traced = ShopifyInventoryReader.numeric(jdbc.query(
            "SELECT shopify_location_id FROM locations WHERE tenant_id = ? AND is_fulfillment = true LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : null, tid));
        jdbc.update("UPDATE variants v SET " +
                    "  stock_available_shopify = (SELECT COALESCE(SUM(l.available), 0) FROM variant_shopify_levels l WHERE l.variant_id = v.id), " +
                    "  stock_available_shopify_traced = CASE WHEN ?::text IS NULL THEN NULL ELSE " +
                    "      COALESCE((SELECT l.available FROM variant_shopify_levels l WHERE l.variant_id = v.id AND l.location_id = ?), 0) END, " +
                    "  stock_synced_at = now() " +
                    "WHERE v.id = ? AND v.tenant_id = ?", traced, traced, variant, tid);
    }

    /** inventory_items/update: {id, cost, updated_at, …} — cost has no currency: the shop's, from the last pass. */
    public void onInventoryItemUpdate(JsonNode payload) {
        UUID tid = TenantContext.require();
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
            "SELECT id FROM variants WHERE tenant_id = ? AND regexp_replace(shopify_inventory_item_id, '^.*/', '') = ? LIMIT 1",
            UUID.class, tid, inventoryItemId);
        return v.isEmpty() ? null : v.get(0);
    }
}
