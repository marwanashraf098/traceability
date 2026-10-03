package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Part B — activates catalog inventory items at the Traced Main Warehouse GID. Required before any
 * on_hand write lands there (new Shopify locations start with no active inventory items).
 *
 * Connect-time policy: activateAll() covers variants of ACTIVE products only. Draft and archived
 * variants are activated lazily — right before their first increment
 * (ShopifyInventoryService.applyIncrementAdjustment) — so a store with a large archive isn't held for
 * an hour of activations it may never need.
 *
 * Batched: item ids come from variants.shopify_inventory_item_id, misses resolved together through
 * nodes(ids:) and written back (InventoryItemIdService); activation is 25 aliased inventoryActivate
 * per request (ShopifyGateway.activateInventoryItems). inventoryActivate never carries a quantity and
 * tolerates "already active", so calling this repeatedly, or against a partially active catalog, is
 * safe. One variant's error never fails the others; the outcome lists every failing variant.
 */
@Service
public class ShopifyCatalogActivationService {

    private static final Logger log = LoggerFactory.getLogger(ShopifyCatalogActivationService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyGateway shopify;
    private final ShopifyTokenProvider tokenProvider;
    private final StoreRepository storeRepository;
    private final InventoryItemIdService itemIds;

    public ShopifyCatalogActivationService(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                            ShopifyGateway shopify, ShopifyTokenProvider tokenProvider,
                                            StoreRepository storeRepository) {
        this.jdbc          = jdbc;
        this.tx            = new TransactionTemplate(txm);
        this.shopify       = shopify;
        this.tokenProvider = tokenProvider;
        this.storeRepository = storeRepository;
        this.itemIds       = new InventoryItemIdService(jdbc, txm, shopify);
    }

    public record ActivationOutcome(int total, int succeeded, int failed, List<Map<String, String>> failures) {}

    /** The @idempotent key for activating this variant at this location — shared by the catalog
     *  activation and the lazy activation before an increment (same operation, same parameters). */
    public static String activationKey(UUID tenantId, UUID variantId, String locationGid) {
        return ShopifyGateway.idempotencyKey(tenantId, "catalog_activation", variantId.toString(), variantId, locationGid);
    }

    /** Connect / backfill / manual activation: every variant of an ACTIVE product. */
    public ActivationOutcome activateAll() {
        UUID tenantId = TenantContext.require();
        return activate(tenantId, tx.execute(s -> jdbc.queryForList(
            "SELECT v.id, v.external_id, v.shopify_inventory_item_id FROM variants v " +
            "JOIN products p ON p.id = v.product_id " +
            "WHERE v.tenant_id = ? AND p.status = 'active' " +
            // Review mode S4 (V130): a simulated-courier tenant's fixture variants (external_id not a
            // Shopify gid) are left out BEFORE the batch item-id read, so they can never fail it for
            // the reviewer's real variants. Real tenants: no filter at all.
            "  AND NOT (EXISTS (SELECT 1 FROM tenant_courier_simulation sim WHERE sim.tenant_id = v.tenant_id) " +
            "           AND v.external_id NOT LIKE 'gid://shopify/%')", tenantId)));
    }

    /**
     * The same activation, for just these variants — the products webhook activates only the
     * variants its upsert newly inserted. Same call, same idempotency key, same error handling.
     */
    public ActivationOutcome activateVariants(java.util.Collection<UUID> variantIds) {
        UUID tenantId = TenantContext.require();
        if (variantIds.isEmpty()) return new ActivationOutcome(0, 0, 0, List.of());
        return activate(tenantId, tx.execute(s -> jdbc.query(con -> {
            var ps = con.prepareStatement(
                "SELECT id, external_id, shopify_inventory_item_id FROM variants WHERE tenant_id = ? AND id = ANY(?)");
            ps.setObject(1, tenantId);
            ps.setArray(2, con.createArrayOf("uuid", variantIds.toArray()));
            return ps;
        }, new org.springframework.jdbc.core.ColumnMapRowMapper())));
    }

    private ActivationOutcome activate(UUID tenantId, List<Map<String, Object>> variants) {

        // FR-3.1 follow-up — StoreRepository.findActiveStoreByTenant() is the single
        // canonical pick shared by every job/service (never a disconnected row).
        StoreRepository.Store store = storeRepository.findActiveStoreByTenant(tenantId).orElse(null);
        if (store == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No Shopify store connected");
        }

        String tracedGid = tx.execute(s -> jdbc.query(
            "SELECT shopify_location_id FROM locations " +
            "WHERE tenant_id = ? AND is_fulfillment = true AND shopify_sync_status = 'linked' LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : null,
            tenantId));
        if (tracedGid == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Traced Main Warehouse is not linked to Shopify yet");
        }

        String token = tokenProvider.getValidToken(store.id());

        List<Map<String, String>> failures = new ArrayList<>();
        List<InventoryItemIdService.VariantRef> refs = new ArrayList<>();
        for (Map<String, Object> v : variants) {
            refs.add(new InventoryItemIdService.VariantRef((UUID) v.get("id"),
                (String) v.get("external_id"), (String) v.get("shopify_inventory_item_id")));
        }

        // Item ids: stored first, misses resolved together. A failed batch read fails every
        // variant that needed it (counted per variant, so "every variant rejected" still holds).
        Map<UUID, String> itemByVariant;
        String resolveError = null;
        try {
            itemByVariant = itemIds.resolveAll(tenantId, refs, store.shopDomain(), token);
        } catch (Exception e) {
            resolveError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            itemByVariant = new LinkedHashMap<>();
            for (InventoryItemIdService.VariantRef r : refs) {
                if (r.inventoryItemGid() != null && !r.inventoryItemGid().isBlank()) itemByVariant.put(r.id(), r.inventoryItemGid());
            }
        }

        List<UUID> toActivate = new ArrayList<>();
        List<ShopifyGateway.ActivationRequest> requests = new ArrayList<>();
        for (InventoryItemIdService.VariantRef r : refs) {
            String itemGid = itemByVariant.get(r.id());
            if (itemGid == null) {
                addFailure(failures, tenantId, r.id(), resolveError != null
                    ? "Shopify API error resolving inventoryItem: " + resolveError
                    : "productVariant not found or has no inventoryItem: " + r.variantGid());
                continue;
            }
            toActivate.add(r.id());
            requests.add(new ShopifyGateway.ActivationRequest(itemGid, activationKey(tenantId, r.id(), tracedGid)));
        }

        int succeeded = 0;
        if (!requests.isEmpty()) {
            List<ShopifyGateway.ActivationResult> results =
                shopify.activateInventoryItems(store.shopDomain(), token, tracedGid, requests);
            for (int i = 0; i < toActivate.size(); i++) {
                ShopifyGateway.ActivationResult r = results != null && i < results.size() ? results.get(i) : null;
                if (r != null && r.ok()) {
                    succeeded++;
                } else {
                    addFailure(failures, tenantId, toActivate.get(i),
                        r == null ? "inventoryActivate returned no result" : r.error());
                }
            }
        }

        return new ActivationOutcome(variants.size(), succeeded, failures.size(), failures);
    }

    private static void addFailure(List<Map<String, String>> failures, UUID tenantId, UUID variantId, String msg) {
        Map<String, String> failure = new LinkedHashMap<>();
        failure.put("variantId", variantId.toString());
        failure.put("error", msg);
        failures.add(failure);
        log.warn("Activation failed: tenant={} variant={} error={}", tenantId, variantId, msg);
    }
}
