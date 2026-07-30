package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * Part A — Traced-owned Shopify location provisioning (FR-17 v2 go-live).
 *
 * Called once per Shopify connect/reconnect (from ShopifyImportJob, which fires on every
 * outcome of ShopifyOAuthService.linkOrProvision). Handles BOTH onboarding cases without
 * needing to know which one occurred:
 *   - standalone signup: AuthRepository.createTenantWithOwner already seeded a Main
 *     Warehouse row with is_fulfillment=true — ensureInternalLocation() finds it.
 *   - Shopify-first (provision_tenant_from_shopify, V14): zero locations exist yet —
 *     ensureInternalLocation() creates one, mirroring AuthRepository's exact seed shape.
 *     This does NOT touch provision_tenant_from_shopify itself — a plain INSERT under
 *     TenantContext after the DEFINER call returns, same pattern already used in
 *     ShopifyOAuthService.provisionNewTenant() for the refresh-token fields.
 *
 * Idempotent and re-runnable: guarded on is_fulfillment existing at all, then on a
 * claim-before-call conditional UPDATE (not a SELECT-then-act check) — see
 * linkShopifyLocationIfNeeded() — so two overlapping calls (e.g. two concurrent
 * reconnects) can't both create a Shopify location. V61 additionally enforces one
 * is_fulfillment=true location per tenant at the DB level.
 */
@Service
public class ShopifyLocationProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ShopifyLocationProvisioningService.class);

    /** Internal DB location name — matches AuthRepository's standalone-signup seed exactly. */
    static final String INTERNAL_LOCATION_NAME = "Main Warehouse";
    /** Shopify-side location name — the spec's locked decision, distinct from the internal name. */
    static final String SHOPIFY_LOCATION_NAME = "Traced Main Warehouse";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyLocationGateway shopifyLocations;
    private final ShopifyTokenProvider tokenProvider;

    public ShopifyLocationProvisioningService(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                               ShopifyLocationGateway shopifyLocations,
                                               ShopifyTokenProvider tokenProvider) {
        this.jdbc             = jdbc;
        this.tx               = new TransactionTemplate(txm);
        this.shopifyLocations = shopifyLocations;
        this.tokenProvider    = tokenProvider;
    }

    /**
     * Ensures the tenant has an internal fulfillment location AND that it is linked to a
     * Shopify location. Must be called with TenantContext already set (caller's responsibility —
     * ShopifyImportJob wraps this in TenantContext.runAs).
     */
    public void ensureTracedWarehouse(UUID tenantId, UUID storeId, String shopDomain) {
        UUID locationId = ensureInternalLocation(tenantId);
        linkShopifyLocationIfNeeded(tenantId, storeId, shopDomain, locationId);
    }

    // ---- step 1: internal Main Warehouse row (both onboarding cases) -------

    private UUID ensureInternalLocation(UUID tenantId) {
        UUID existing = tx.execute(s -> jdbc.query(
            "SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment = true LIMIT 1",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            tenantId));
        if (existing != null) {
            return existing;
        }

        // Shopify-first gap (V14 comment claim is stale — no location exists yet).
        // Same shape as AuthRepository.createTenantWithOwner's standalone seed.
        UUID newId = UUID.randomUUID();
        tx.execute(s -> {
            jdbc.update(
                "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                "VALUES (?, ?, ?, 'warehouse', true, true) " +
                "ON CONFLICT (tenant_id, lower(trim(name))) DO NOTHING",
                newId, tenantId, INTERNAL_LOCATION_NAME);
            return null;
        });

        UUID resolved = tx.execute(s -> jdbc.query(
            "SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment = true LIMIT 1",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            tenantId));
        if (resolved != null) {
            return resolved;
        }

        // The ON CONFLICT fired against a same-named row that isn't flagged is_fulfillment yet
        // (e.g. a pre-existing "Main Warehouse" location from before V59). Promote it rather
        // than silently doing nothing — the tenant must end up with exactly one fulfillment
        // location out of this method.
        log.info("Promoting existing '{}' location to is_fulfillment=true for tenant {}",
            INTERNAL_LOCATION_NAME, tenantId);
        tx.execute(s -> {
            jdbc.update(
                "UPDATE locations SET is_fulfillment = true " +
                "WHERE tenant_id = ? AND lower(trim(name)) = lower(trim(?))",
                tenantId, INTERNAL_LOCATION_NAME);
            return null;
        });
        return tx.execute(s -> jdbc.queryForObject(
            "SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment = true LIMIT 1",
            UUID.class, tenantId));
    }

    // ---- step 2: link to a real Shopify location ---------------------------

    /**
     * Claim-before-call, not check-before-call: a prior SELECT-then-create()-then-UPDATE has
     * a race window where two concurrent callers (e.g. two overlapping ShopifyImportJob runs
     * on reconnect) can both pass the SELECT before either writes anything, and both would
     * then call Shopify's locationAdd — two "Traced Main Warehouse" locations. The conditional
     * UPDATE below is the actual guard: only one caller can flip the row from
     * unsynced/error to pending; the loser sees 0 affected rows and returns without ever
     * calling Shopify. V61's UNIQUE(tenant_id) WHERE is_fulfillment=true is a separate,
     * complementary invariant (at most one such row can ever exist per tenant at all) — it
     * doesn't by itself serialize the create() call, which is what this claim does.
     */
    private void linkShopifyLocationIfNeeded(UUID tenantId, UUID storeId, String shopDomain, UUID locationId) {
        Integer claimed = tx.execute(s -> jdbc.update(
            "UPDATE locations SET shopify_sync_status = 'pending' " +
            "WHERE id = ? AND tenant_id = ? AND shopify_location_id IS NULL " +
            "  AND shopify_sync_status IN ('unsynced', 'error')",
            locationId, tenantId));

        if (claimed == null || claimed == 0) {
            // Already linked, already pending (another caller has it in flight right now),
            // or not found — either way, never call Shopify.
            return;
        }

        try {
            String token = tokenProvider.getValidToken(storeId);

            // findByName first — reconnect/retry after a previous partially-completed sync
            // (Shopify location created, local UPDATE never landed) must not create a duplicate.
            Optional<ShopifyLocationGateway.LocationResult> found =
                shopifyLocations.findByName(shopDomain, token, SHOPIFY_LOCATION_NAME);
            ShopifyLocationGateway.LocationResult result = found.orElseGet(() ->
                shopifyLocations.create(shopDomain, token,
                    new ShopifyLocationGateway.LocationInput(SHOPIFY_LOCATION_NAME, null, null, "EG", true)));

            final String shopifyLocId = result.shopifyLocationId();
            tx.execute(s -> {
                jdbc.update(
                    "UPDATE locations SET shopify_location_id = ?, shopify_sync_status = 'linked', " +
                    "shopify_synced_at = now(), shopify_sync_error = NULL WHERE id = ? AND tenant_id = ?",
                    shopifyLocId, locationId, tenantId);
                return null;
            });
            log.info("Traced Main Warehouse linked: tenant={} location={} shopifyLocationId={}",
                tenantId, locationId, shopifyLocId);
        } catch (Exception e) {
            final String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            tx.execute(s -> {
                jdbc.update(
                    "UPDATE locations SET shopify_sync_status = 'error', shopify_sync_error = ? " +
                    "WHERE id = ? AND tenant_id = ?",
                    errMsg, locationId, tenantId);
                return null;
            });
            log.warn("Traced Main Warehouse provisioning failed: tenant={} location={} error={}",
                tenantId, locationId, errMsg);
        }
    }
}
