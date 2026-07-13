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
 * FR-17 Phase 1 — Shopify inventory shadow sync.
 *
 * Entry points are @Async so they never block the calling HTTP thread.
 * TenantContext.runAs(tenantId, ...) is the OUTER wrapper inside every async method —
 * the ThreadLocal does not propagate across thread boundaries so it must be set
 * explicitly on the new thread.
 *
 * SHADOW MODE: rows are inserted with status='shadow'. No Shopify mutation is called.
 * Phase-2 flip: set stores.shopify_inventory_sync_enabled = true and change status
 * to 'pending' before triggering the actual inventoryAdjustQuantities mutation.
 *
 * INVARIANT (never relax without explicit approval):
 *   Traced only INCREMENTS Shopify inventory. Shopify owns decrements.
 *   Trigger 1: receiving session close  → reason='received'
 *   Trigger 2: return inspection → AVAILABLE → reason='restock'
 *   Damaged pieces MUST NOT trigger — explicit guard in onReturnInspectionAvailable.
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
     * Damaged pieces are NOT routed here (guard is in ReturnService.restock()).
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

    // ── Receiving session processing ─────────────────────────────────────────

    private void processReceivingSession(UUID sessionId, UUID locationId, Map<UUID, Integer> variantDeltaMap) {
        UUID batchId = UUID.randomUUID();

        // One row per variant in the session.
        for (Map.Entry<UUID, Integer> entry : variantDeltaMap.entrySet()) {
            UUID variantId = entry.getKey();
            int  delta     = entry.getValue();
            insertAdjustmentRow(batchId, variantId, locationId, delta,
                                "receiving_session", sessionId.toString(), "received");
        }
    }

    // ── Return inspection processing ─────────────────────────────────────────

    private void processReturnInspection(String pieceId, UUID locationId) {
        UUID batchId = UUID.randomUUID();

        UUID variantId = tx.execute(status ->
            jdbc.query(
                "SELECT variant_id FROM pieces WHERE id = ? AND tenant_id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                pieceId, TenantContext.require()));

        if (variantId == null) {
            log.warn("Shopify inventory sync: piece not found piece={}", pieceId);
            return;
        }

        insertAdjustmentRow(batchId, variantId, locationId, 1,
                            "return_inspection", pieceId, "restock");
    }

    // ── Core row insertion ────────────────────────────────────────────────────

    private void insertAdjustmentRow(UUID batchId, UUID variantId, UUID locationId,
                                     int delta, String triggerType, String triggerId,
                                     String reason) {
        UUID tenantId = TenantContext.require();

        // Evaluate BOTH preconditions independently so all resolved IDs are recorded
        // even when one precondition fails. This lets shadow rows validate resolution
        // while the location/scope question is still open.
        String shopifyInventoryItemId = null;
        String shopifyLocationId      = null;
        String locationError          = null;
        String variantError           = null;

        // ── Precondition 1: location must be linked ───────────────────────────
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

        // ── Precondition 2: variant must resolve to an inventoryItem GID ──────
        // Always attempted — a location failure must not mask a variant failure.
        try {
            String variantGid = tx.execute(status ->
                jdbc.query(
                    "SELECT external_id FROM variants WHERE id = ? AND tenant_id = ?",
                    rs -> rs.next() ? rs.getString(1) : null,
                    variantId, tenantId));

            if (variantGid == null || variantGid.isBlank()) {
                variantError = "Variant has no Shopify GID: " + variantId;
            } else {
                UUID storeId = tx.execute(status ->
                    jdbc.query(
                        "SELECT id FROM stores WHERE tenant_id = ? LIMIT 1",
                        rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                        tenantId));

                if (storeId == null) {
                    variantError = "No store found for tenant";
                } else {
                    String shopDomain = tx.execute(status ->
                        jdbc.query(
                            "SELECT shop_domain FROM stores WHERE id = ? AND tenant_id = ?",
                            rs -> rs.next() ? rs.getString(1) : null,
                            storeId, tenantId));
                    String token = tokenProvider.getValidToken(storeId);
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

        // Combine errors — both failures reported together so one doesn't mask the other.
        String errorMsg = null;
        if (locationError != null && variantError != null) {
            errorMsg = locationError + "; " + variantError;
        } else if (locationError != null) {
            errorMsg = locationError;
        } else if (variantError != null) {
            errorMsg = variantError;
        }

        String status = (errorMsg != null) ? "failed" : "shadow";

        // Build payload for audit.
        ObjectNode payload = mapper.createObjectNode()
            .put("reason", reason)
            .put("delta",  delta);
        if (shopifyInventoryItemId != null) payload.put("inventoryItemId", shopifyInventoryItemId);
        if (shopifyLocationId      != null) payload.put("locationId",      shopifyLocationId);

        String payloadJson;
        try { payloadJson = mapper.writeValueAsString(payload); }
        catch (Exception e) { payloadJson = "{}"; }

        final String finalInventoryItemId = shopifyInventoryItemId;
        final String finalLocationId      = shopifyLocationId;
        final String finalError           = errorMsg;
        final String finalStatus          = status;
        final String finalPayload         = payloadJson;

        tx.execute(txStatus -> {
            jdbc.update(
                "INSERT INTO shopify_inventory_adjustments " +
                "(tenant_id, batch_id, variant_id, location_id, " +
                " shopify_inventory_item_id, shopify_location_id, " +
                " delta, trigger_type, trigger_id, payload, status, error) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) " +
                "ON CONFLICT (trigger_type, trigger_id, variant_id, location_id) DO NOTHING",
                tenantId, batchId, variantId, locationId,
                finalInventoryItemId, finalLocationId,
                delta, triggerType, triggerId, finalPayload, finalStatus, finalError);
            return null;
        });

        if ("failed".equals(finalStatus)) {
            log.warn("Shopify inventory adjustment recorded as failed: trigger={} triggerId={} variant={} error={}",
                     triggerType, triggerId, variantId, finalError);
        }
    }
}
