package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyGateway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A variant's Shopify InventoryItem GID, read from variants.shopify_inventory_item_id (V120, filled
 * by the catalog import and the products webhook). On a miss it is resolved from Shopify and written
 * back, so each variant is resolved at most once. The single form keeps the exact call the callers
 * made before (resolveInventoryItemId, same exceptions); the batch form uses nodes(ids:) chunks.
 * Only a READ of Shopify — nothing here writes inventory. Not a bean: each caller builds it from its
 * own JdbcTemplate, so it joins the caller's transaction and connection role (same pattern as
 * ReturnRequestLifecycle).
 */
public class InventoryItemIdService {

    /** A variant as the caller already has it: id, Shopify variant GID, stored item GID (may be null). */
    public record VariantRef(UUID id, String variantGid, String inventoryItemGid) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyGateway shopify;

    public InventoryItemIdService(JdbcTemplate jdbc, PlatformTransactionManager txm, ShopifyGateway shopify) {
        this.jdbc    = jdbc;
        this.tx      = new TransactionTemplate(txm);
        this.shopify = shopify;
    }

    /** Stored item id, else resolve (throws like ShopifyGateway.resolveInventoryItemId) and write back. */
    public String resolve(UUID tenantId, UUID variantId, String variantGid, String shopDomain, String token) {
        String stored = tx.execute(s -> jdbc.query(
            "SELECT shopify_inventory_item_id FROM variants WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? rs.getString(1) : null, variantId, tenantId));
        if (stored != null && !stored.isBlank()) return stored;
        String itemGid = shopify.resolveInventoryItemId(shopDomain, token, variantGid);
        writeBack(tenantId, Map.of(variantId, itemGid));
        return itemGid;
    }

    /**
     * variantId → item GID for every variant that has one: stored ids as they are, misses resolved
     * together through nodes(ids:) (≤250 per call) and written back. A variant Shopify doesn't return
     * (deleted, wrong store, no inventoryItem) is absent from the result — the caller reports it.
     */
    public Map<UUID, String> resolveAll(UUID tenantId, List<VariantRef> variants, String shopDomain, String token) {
        Map<UUID, String> out = new LinkedHashMap<>();
        List<VariantRef> missing = new ArrayList<>();
        for (VariantRef v : variants) {
            if (v.inventoryItemGid() != null && !v.inventoryItemGid().isBlank()) out.put(v.id(), v.inventoryItemGid());
            else if (v.variantGid() != null && !v.variantGid().isBlank()) missing.add(v);
        }
        if (missing.isEmpty()) return out;

        List<String> gids = missing.stream().map(VariantRef::variantGid).distinct().toList();
        Map<String, String> byVariantGid = shopify.resolveInventoryItemIds(shopDomain, token, gids);
        Map<UUID, String> resolved = new LinkedHashMap<>();
        for (VariantRef v : missing) {
            String itemGid = byVariantGid == null ? null : byVariantGid.get(v.variantGid());
            if (itemGid != null) resolved.put(v.id(), itemGid);
        }
        writeBack(tenantId, resolved);
        out.putAll(resolved);
        return out;
    }

    private void writeBack(UUID tenantId, Map<UUID, String> itemIds) {
        if (itemIds.isEmpty()) return;
        List<Object[]> args = new ArrayList<>();
        for (Map.Entry<UUID, String> e : itemIds.entrySet()) {
            args.add(new Object[]{e.getValue(), e.getKey(), tenantId, e.getValue()});
        }
        tx.execute(s -> jdbc.batchUpdate(
            "UPDATE variants SET shopify_inventory_item_id = ? " +
            "WHERE id = ? AND tenant_id = ? AND shopify_inventory_item_id IS DISTINCT FROM ?", args));
    }
}
