package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * FR-17 v2 — Shopify inventory increment-only live sync.
 *
 * Entry points are @Async so they never block the calling HTTP thread.
 * TenantContext.runAs(tenantId, ...) is the OUTER wrapper inside every async method —
 * the ThreadLocal does not propagate across thread boundaries so it must be set
 * explicitly on the new thread.
 *
 * LIVE MODE: every call here issues a real Shopify mutation. There is no shadow mode
 * anymore — rows are inserted with status='applied' (mutation succeeded) or 'failed'
 * (mutation attempted or preconditions unmet; never thrown further, never retried
 * forced). shopify_inventory_adjustments remains the append-only per-trigger audit log
 * and idempotency guard (UNIQUE(trigger_type, trigger_id, variant_id, location_id)).
 *
 * INVARIANT (never relax without explicit approval — FR-17 v2, CLAUDE.md):
 *   Traced NEVER decrements Shopify on_hand and NEVER writes absolute on_hand.
 *   inventorySetOnHandQuantities is FORBIDDEN — it is never called anywhere in this
 *   codebase. Only two write shapes exist, both targeting the Traced Main Warehouse
 *   GID only:
 *     - inventoryAdjustQuantities with a POSITIVE delta (triggers 1 and 2).
 *     - inventoryMoveQuantities available->damaged (trigger 3).
 *   Trigger 1: receiving session close                → +N per variant.
 *   Trigger 2: return inspection → AVAILABLE           → +1 per piece.
 *              (return_pending_inspection → damaged does nothing — no call here.)
 *   Trigger 3: a currently-sellable piece damaged      → move 1 unit available→damaged.
 *   No fourth trigger. No courier/loss/order-driven decrement trigger — Shopify owns those.
 *
 * LOCATION-TARGET GUARD: the Shopify locationGid used in every mutation call is read
 * directly off the SAME location row that passed the is_fulfillment=true AND
 * shopify_sync_status='linked' checks for the triggering event — there is no code path
 * that resolves one location's eligibility and then uses a different location's GID.
 * A non-fulfillment (or unlinked) triggering location never reaches the Shopify call at all.
 */
@Service
public class ShopifyInventoryService {

    private static final Logger log = LoggerFactory.getLogger(ShopifyInventoryService.class);

    private final JdbcTemplate         jdbc;
    private final TransactionTemplate  tx;
    private final ShopifyGateway       shopify;
    private final ShopifyTokenProvider tokenProvider;
    private final ObjectMapper         mapper;

    public ShopifyInventoryService(JdbcTemplate jdbc,
                                   PlatformTransactionManager txm,
                                   ShopifyGateway shopify,
                                   ShopifyTokenProvider tokenProvider,
                                   ObjectMapper mapper) {
        this.jdbc          = jdbc;
        this.tx            = new TransactionTemplate(txm);
        this.shopify       = shopify;
        this.tokenProvider = tokenProvider;
        this.mapper        = mapper;
    }

    // ── Trigger 1: receiving session close ───────────────────────────────────

    /**
     * Called after ReceivingService.finalize() commits.
     * variantDeltaMap: variantId → total units received in this session.
     */
    @Async
    public CompletableFuture<Void> onReceivingSessionClose(UUID tenantId, UUID sessionId,
                                                           UUID locationId, Map<UUID, Integer> variantDeltaMap) {
        TenantContext.runAs(tenantId, () -> {
            try {
                processReceivingSession(sessionId, locationId, variantDeltaMap);
            } catch (Exception e) {
                log.error("Shopify inventory sync failed: trigger=receiving_session session={}", sessionId, e);
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    // ── Trigger 2: return inspection → AVAILABLE ────────────────────────────

    /**
     * Called after ReturnService.restock() — piece transitioned to AVAILABLE.
     * Damaged pieces are NOT routed here (guard is in ReturnService.markDamaged()).
     */
    @Async
    public CompletableFuture<Void> onReturnInspectionAvailable(UUID tenantId, String pieceId, UUID locationId) {
        TenantContext.runAs(tenantId, () -> {
            try {
                processReturnInspection(pieceId, locationId);
            } catch (Exception e) {
                log.error("Shopify inventory sync failed: trigger=return_inspection piece={}", pieceId, e);
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    // ── Trigger 3: currently-sellable piece damaged in the warehouse ────────

    /**
     * Called after PieceAdjustService.adjustPiece() commits an available→damaged
     * transition. NOT called for return_pending_inspection→damaged (ReturnService.markDamaged
     * has no call here — that verdict was never sellable in Shopify, so nothing moves).
     */
    @Async
    public CompletableFuture<Void> onSellablePieceDamaged(UUID tenantId, String pieceId, UUID locationId) {
        TenantContext.runAs(tenantId, () -> {
            try {
                processDamageMove(pieceId, locationId);
            } catch (Exception e) {
                log.error("Shopify inventory sync failed: trigger=damage_move piece={}", pieceId, e);
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    // ── Receiving session processing ─────────────────────────────────────────

    private void processReceivingSession(UUID sessionId, UUID locationId, Map<UUID, Integer> variantDeltaMap) {
        UUID batchId = UUID.randomUUID();
        for (Map.Entry<UUID, Integer> entry : variantDeltaMap.entrySet()) {
            applyIncrementAdjustment(batchId, entry.getKey(), locationId, entry.getValue(),
                                      "receiving_session", sessionId.toString(), "received");
        }
    }

    // ── Return inspection processing ─────────────────────────────────────────

    private void processReturnInspection(String pieceId, UUID locationId) {
        UUID batchId = UUID.randomUUID();
        UUID variantId = resolveVariantForPiece(pieceId);
        if (variantId == null) {
            log.warn("Shopify inventory sync: piece not found piece={}", pieceId);
            return;
        }
        applyIncrementAdjustment(batchId, variantId, locationId, 1,
                                  "return_inspection", pieceId, "restock");
    }

    // ── Damage move processing ───────────────────────────────────────────────

    private void processDamageMove(String pieceId, UUID locationId) {
        UUID batchId = UUID.randomUUID();
        UUID variantId = resolveVariantForPiece(pieceId);
        if (variantId == null) {
            log.warn("Shopify inventory sync: piece not found piece={}", pieceId);
            return;
        }
        applyDamageMove(batchId, variantId, locationId, pieceId);
    }

    private UUID resolveVariantForPiece(String pieceId) {
        return tx.execute(status ->
            jdbc.query(
                "SELECT variant_id FROM pieces WHERE id = ? AND tenant_id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                pieceId, TenantContext.require()));
    }

    // ── Shared preconditions ─────────────────────────────────────────────────

    /**
     * Everything needed to actually call Shopify for one (variant, location) pair, or the
     * combined error if any precondition failed. shopifyLocationId is read off the SAME
     * location row validated by isFulfillment()/the linked-status check below — this is the
     * only path in the class that produces a Shopify locationGid, so it is structurally
     * impossible to emit a write against a different location's GID.
     */
    private record Preconditions(
        String shopDomain, String token, String shopifyInventoryItemId,
        String shopifyLocationId, String error) {}

    private Preconditions resolvePreconditions(UUID tenantId, UUID variantId, UUID locationId) {
        String locationError = null;
        String shopifyLocationId = null;

        try {
            Map<String, Object> locRow = tx.execute(status ->
                jdbc.query(
                    "SELECT shopify_location_id, shopify_sync_status " +
                    "FROM locations WHERE id = ? AND tenant_id = ?",
                    rs -> rs.next() ?
                        Map.of("shopify_location_id", rs.getString(1) != null ? rs.getString(1) : "",
                               "shopify_sync_status",  rs.getString(2) != null ? rs.getString(2) : "") :
                        null,
                    locationId, tenantId));

            if (locRow == null) {
                locationError = "Location not found: " + locationId;
            } else if (!"linked".equals(locRow.get("shopify_sync_status"))) {
                locationError = "Location not linked to Shopify (status=" + locRow.get("shopify_sync_status") + ")";
            } else {
                shopifyLocationId = (String) locRow.get("shopify_location_id");
            }
        } catch (Exception e) {
            locationError = "Location lookup error: " + e.getMessage();
            log.warn("Shopify inventory: location lookup failed location={}", locationId, e);
        }

        String shopifyInventoryItemId = null;
        String variantError = null;
        String shopDomain = null;
        String token = null;

        try {
            String variantGid = tx.execute(status ->
                jdbc.query(
                    "SELECT external_id FROM variants WHERE id = ? AND tenant_id = ?",
                    rs -> rs.next() ? rs.getString(1) : null,
                    variantId, tenantId));

            if (variantGid == null || variantGid.isBlank()) {
                variantError = "Variant has no Shopify GID: " + variantId;
            } else {
                record StoreSnap(UUID id, String shopDomain, String grantedScopes) {}
                StoreSnap store = tx.execute(status ->
                    jdbc.query(
                        "SELECT id, shop_domain, access_token_scopes FROM stores WHERE tenant_id = ? LIMIT 1",
                        rs -> rs.next() ? new StoreSnap(
                            rs.getObject(1, UUID.class),
                            rs.getString(2),
                            rs.getString(3)) : null,
                        tenantId));

                if (store == null) {
                    variantError = "No store found for tenant";
                } else if (!ShopifyGateway.isScopeGranted("read_products", store.grantedScopes())) {
                    variantError = "Token lacks read_products scope (granted: "
                        + (store.grantedScopes() != null ? store.grantedScopes() : "none")
                        + ") — store must reconnect to grant the current scope list";
                } else if (!ShopifyGateway.isScopeGranted("write_inventory", store.grantedScopes())) {
                    variantError = "Token lacks write_inventory scope (granted: "
                        + (store.grantedScopes() != null ? store.grantedScopes() : "none")
                        + ") — store must reconnect to grant the current scope list";
                } else {
                    shopDomain = store.shopDomain();
                    token = tokenProvider.getValidToken(store.id());
                    shopifyInventoryItemId = shopify.resolveInventoryItemId(shopDomain, token, variantGid);
                }
            }
        } catch (ShopifyException e) {
            variantError = "Shopify API error resolving inventoryItem: " + e.getMessage();
            log.warn("Shopify inventory: inventoryItem resolution failed variant={} error={}", variantId, e.getMessage());
        } catch (Exception e) {
            variantError = "Variant resolution error: " + e.getMessage();
            log.warn("Shopify inventory: variant resolution error variant={}", variantId, e);
        }

        String errorMsg = null;
        if (locationError != null && variantError != null) {
            errorMsg = locationError + "; " + variantError;
        } else if (locationError != null) {
            errorMsg = locationError;
        } else if (variantError != null) {
            errorMsg = variantError;
        }

        return new Preconditions(shopDomain, token, shopifyInventoryItemId, shopifyLocationId, errorMsg);
    }

    // ── Trigger 1 & 2 core: positive-delta adjust ────────────────────────────

    private void applyIncrementAdjustment(UUID batchId, UUID variantId, UUID locationId,
                                          int delta, String triggerType, String triggerId, String reason) {
        UUID tenantId = TenantContext.require();

        // Idempotency: a prior SUCCESSFUL call for this exact (trigger, variant, location)
        // must never be repeated — retry/redeploy/duplicate-call must not double-apply a
        // positive delta twice. A prior failure is retried (nothing was actually applied).
        if (alreadyApplied(tenantId, triggerType, triggerId, variantId, locationId)) {
            log.debug("Shopify inventory: trigger already applied, skipping duplicate call " +
                      "trigger={} triggerId={} variant={}", triggerType, triggerId, variantId);
            return;
        }

        if (!isFulfillmentLocation(tenantId, locationId, triggerType, triggerId)) {
            return;
        }

        Preconditions p = resolvePreconditions(tenantId, variantId, locationId);

        if (p.error() != null) {
            recordAdjustment(tenantId, batchId, variantId, locationId, delta, triggerType, triggerId,
                              reason, null, p.shopifyInventoryItemId(), p.shopifyLocationId(), "failed", p.error());
            return;
        }

        String status;
        String error = null;
        try {
            shopify.adjustInventoryQuantities(p.shopDomain(), p.token(), p.shopifyInventoryItemId(),
                                              p.shopifyLocationId(), delta, reason);
            status = "applied";
        } catch (Exception e) {
            status = "failed";
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Shopify inventory adjust failed: trigger={} triggerId={} variant={} error={}",
                     triggerType, triggerId, variantId, error);
        }

        recordAdjustment(tenantId, batchId, variantId, locationId, delta, triggerType, triggerId,
                          reason, null, p.shopifyInventoryItemId(), p.shopifyLocationId(), status, error);
    }

    // ── Trigger 3 core: available→damaged move ───────────────────────────────

    private void applyDamageMove(UUID batchId, UUID variantId, UUID locationId, String pieceId) {
        UUID tenantId = TenantContext.require();

        // Idempotent per piece: a piece can only ever go available->damaged once (the
        // ledger's state machine makes damaged terminal), but guard the Shopify call
        // explicitly too — never move the same piece's unit twice.
        if (alreadyApplied(tenantId, "damage_move", pieceId, variantId, locationId)) {
            log.debug("Shopify inventory: damage move already applied, skipping duplicate call piece={}", pieceId);
            return;
        }

        if (!isFulfillmentLocation(tenantId, locationId, "damage_move", pieceId)) {
            return;
        }

        Preconditions p = resolvePreconditions(tenantId, variantId, locationId);

        if (p.error() != null) {
            recordAdjustment(tenantId, batchId, variantId, locationId, 0, "damage_move", pieceId,
                              "damaged", 1, p.shopifyInventoryItemId(), p.shopifyLocationId(), "failed", p.error());
            return;
        }

        String status;
        String error = null;
        try {
            // on_hand unchanged — the unit leaves the sellable pool (available -> damaged).
            // Insufficient-available or any other Shopify userError fails cleanly here —
            // never forced, never retried automatically. See ShopifyGateway.moveAvailableToDamaged.
            shopify.moveAvailableToDamaged(p.shopDomain(), p.token(), p.shopifyInventoryItemId(),
                                            p.shopifyLocationId(), 1, "damaged");
            status = "applied";
        } catch (Exception e) {
            status = "failed";
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Shopify inventory damage move failed: piece={} variant={} error={}",
                     pieceId, variantId, error);
        }

        recordAdjustment(tenantId, batchId, variantId, locationId, 0, "damage_move", pieceId,
                          "damaged", 1, p.shopifyInventoryItemId(), p.shopifyLocationId(), status, error);
    }

    // ── Shared guards / persistence ──────────────────────────────────────────

    /** True if this exact (trigger, variant, location) already has a successfully-applied
     *  audit row — the pre-Shopify-call idempotency guard (the ON CONFLICT on INSERT alone
     *  is not enough since the mutation call happens before that INSERT). */
    private boolean alreadyApplied(UUID tenantId, String triggerType, String triggerId,
                                    UUID variantId, UUID locationId) {
        Boolean exists = tx.execute(status -> jdbc.query(
            "SELECT 1 FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = ? AND trigger_id = ? " +
            "  AND variant_id = ? AND location_id = ? AND status = 'applied' LIMIT 1",
            java.sql.ResultSet::next,
            tenantId, triggerType, triggerId, variantId, locationId));
        return Boolean.TRUE.equals(exists);
    }

    /** Only is_fulfillment=true locations ever reach a Shopify call — any other location
     *  (showroom/branch/junk) is skipped entirely, not even a 'failed' row. */
    private boolean isFulfillmentLocation(UUID tenantId, UUID locationId, String triggerType, String triggerId) {
        Boolean isFulfillment = tx.execute(status ->
            jdbc.query(
                "SELECT is_fulfillment FROM locations WHERE id = ? AND tenant_id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null,
                locationId, tenantId));

        if (isFulfillment == null || !isFulfillment) {
            log.info("Shopify inventory sync skipped: location is not a fulfillment location " +
                      "trigger={} triggerId={} location={}", triggerType, triggerId, locationId);
            return false;
        }
        return true;
    }

    private void recordAdjustment(UUID tenantId, UUID batchId, UUID variantId, UUID locationId,
                                  int delta, String triggerType, String triggerId, String reason,
                                  Integer moveQuantity, String shopifyInventoryItemId,
                                  String shopifyLocationId, String status, String error) {
        ObjectNode payload = mapper.createObjectNode()
            .put("reason", reason)
            .put("delta",  delta);
        if (moveQuantity != null) payload.put("moveQuantity", moveQuantity);
        if (shopifyInventoryItemId != null) payload.put("inventoryItemId", shopifyInventoryItemId);
        if (shopifyLocationId      != null) payload.put("locationId",      shopifyLocationId);

        String payloadJsonTmp;
        try { payloadJsonTmp = mapper.writeValueAsString(payload); }
        catch (Exception e) { payloadJsonTmp = "{}"; }
        final String finalPayloadJson = payloadJsonTmp;

        tx.execute(txStatus -> {
            jdbc.update(
                "INSERT INTO shopify_inventory_adjustments " +
                "(tenant_id, batch_id, variant_id, location_id, " +
                " shopify_inventory_item_id, shopify_location_id, " +
                " delta, trigger_type, trigger_id, payload, status, error) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) " +
                "ON CONFLICT (trigger_type, trigger_id, variant_id, location_id) DO NOTHING",
                tenantId, batchId, variantId, locationId,
                shopifyInventoryItemId, shopifyLocationId,
                delta, triggerType, triggerId, finalPayloadJson, status, error);
            return null;
        });

        if ("failed".equals(status)) {
            log.warn("Shopify inventory adjustment recorded as failed: trigger={} triggerId={} variant={} error={}",
                     triggerType, triggerId, variantId, error);
        }
    }
}
