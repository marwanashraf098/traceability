package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
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
 * anymore. shopify_inventory_adjustments is now a CLAIM-before-call table, not a
 * check-then-insert audit log: a row is INSERTed with status='pending' (or reclaimed from
 * 'failed') BEFORE Shopify is ever called, gated by the UNIQUE(trigger_type, trigger_id,
 * variant_id, location_id) constraint from V48 — that INSERT, not a prior SELECT, is the
 * actual concurrency guard (see claim()). The row is then updated to 'applied' or 'failed'
 * after the Shopify call returns (see markResult()), in its own transaction so no DB
 * transaction is ever held open across the HTTP call.
 *
 * INVARIANT (never relax without explicit approval — FR-17 v2, CLAUDE.md):
 *   Traced NEVER writes absolute on_hand (inventorySetOnHandQuantities is FORBIDDEN — never
 *   called anywhere in this codebase). As of FR-21 §7 (extended 2026-08-23 for FR-13.x),
 *   decrements are sanctioned ONLY through a named, closed set of dedicated gateway methods —
 *   this class's void/hold-enter triggers below, plus stock-take write-off (a separate class,
 *   StockTakeReconciliationService/StockTakeShopifyPushJob) — never a shared decrement helper.
 *   Write shapes used here, all targeting the Traced Main Warehouse GID only:
 *     - inventoryAdjustQuantities with a POSITIVE delta (triggers 1, 2, hold_exit).
 *     - inventoryMoveQuantities available->damaged (trigger 3).
 *     - pushVoidCorrection / pushHoldEnter — dedicated single-attempt NEGATIVE-delta gateway
 *       methods (FR-13.x; see CLAUDE.md FR-21 §7 extension for the full named set).
 *   Trigger 1: receiving session close                → +N per variant.
 *   Trigger 2: return inspection → AVAILABLE           → +1 per piece.
 *              (return_pending_inspection → damaged does nothing — no call here.)
 *   Trigger 3: a currently-sellable piece damaged      → move 1 unit available->damaged.
 *   Trigger void_correction: available->voided piece   → -1, CONDITIONAL on the piece's
 *              originating receiving increment having actually applied; skipped (not failed)
 *              otherwise — the count is already correct.
 *   Trigger hold_enter: available->on_hold piece        → -1, scoped per hold-cycle (piece_id +
 *              hold_event_id), not just piece_id — a piece can be held/released/held again.
 *   Trigger hold_exit: on_hold->available piece          → +1 via the EXISTING positive path
 *              (applyIncrementAdjustment) — not a decrement, needs no new gateway method.
 *   on_hold->damaged|lost|destroyed makes NO Shopify call — the piece already left the
 *              sellable pool at hold_enter; a second decrement here would double-count.
 *   No courier/loss/order-driven decrement trigger — Shopify owns those.
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

    // ── Trigger: FR-13.x void correction (named decrement set — CLAUDE.md) ──

    /**
     * Called after PieceAdjustService.voidPiece() commits an available→voided transition.
     * Decrements only if the piece's originating receiving increment actually applied
     * (checked against shopify_inventory_adjustments for that piece's receipt session) —
     * see processVoidCorrection() for the exact query. If the increment never fired, the
     * on_hand count is already correct: no Shopify call, but still recorded ('skipped') here
     * for audit.
     */
    @Async
    public CompletableFuture<Void> onPieceVoided(UUID tenantId, String pieceId, UUID locationId) {
        TenantContext.runAs(tenantId, () -> {
            try {
                processVoidCorrection(pieceId, locationId);
            } catch (Exception e) {
                log.error("Shopify inventory sync failed: trigger=void_correction piece={}", pieceId, e);
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    // ── Trigger: FR-13.x hold enter (named decrement set — CLAUDE.md) ───────

    /**
     * Called after PieceAdjustService.hold() commits an available→on_hold transition.
     * holdEventId scopes the trigger to THIS hold cycle — a piece can be held, released, and
     * held again, so piece_id alone would collide with a prior cycle's already-'applied' claim
     * row (the UNIQUE(trigger_type, trigger_id, variant_id, location_id) constraint only
     * reclaims from 'failed' — see claim()'s javadoc).
     */
    @Async
    public CompletableFuture<Void> onHoldEnter(UUID tenantId, String pieceId, UUID locationId, UUID holdEventId) {
        TenantContext.runAs(tenantId, () -> {
            try {
                processHoldEnter(pieceId, locationId, holdEventId);
            } catch (Exception e) {
                log.error("Shopify inventory sync failed: trigger=hold_enter piece={}", pieceId, e);
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    // ── Trigger: FR-13.x hold exit — EXISTING positive path, not a decrement ─

    /**
     * Called after PieceAdjustService.unhold() commits an on_hold→available transition.
     * Reuses the SAME positive-delta path as receiving/return-inspection (applyIncrementAdjustment)
     * — this is an increment, not part of the named decrement set, needs no new gateway method.
     * holdEventId must be the SAME id used by the onHoldEnter() call for this cycle so the two
     * halves of one hold cycle claim distinct rows from any other cycle of the same piece.
     */
    @Async
    public CompletableFuture<Void> onHoldExit(UUID tenantId, String pieceId, UUID locationId, UUID holdEventId) {
        TenantContext.runAs(tenantId, () -> {
            try {
                UUID variantId = resolveVariantForPiece(pieceId);
                if (variantId == null) {
                    log.warn("Shopify inventory sync: piece not found piece={}", pieceId);
                    return;
                }
                UUID batchId = UUID.randomUUID();
                applyIncrementAdjustment(batchId, variantId, locationId, 1,
                                          "hold_exit", pieceId + ":" + holdEventId, "hold_exit");
            } catch (Exception e) {
                log.error("Shopify inventory sync failed: trigger=hold_exit piece={}", pieceId, e);
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    // ── Void correction processing ───────────────────────────────────────────

    private record PieceReceiptRow(UUID variantId, UUID receiptId) {}

    private void processVoidCorrection(String pieceId, UUID locationId) {
        UUID tenantId = TenantContext.require();
        UUID batchId = UUID.randomUUID();

        PieceReceiptRow row = tx.execute(status ->
            jdbc.query(
                "SELECT variant_id, receipt_id FROM pieces WHERE id = ? AND tenant_id = ?",
                rs -> rs.next() ? new PieceReceiptRow(
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)) : null,
                pieceId, tenantId));
        if (row == null) {
            log.warn("Shopify inventory sync: piece not found piece={}", pieceId);
            return;
        }

        if (!isFulfillmentLocation(tenantId, locationId, "void_correction", pieceId)) {
            return;
        }

        ObjectNode initialPayload = mapper.createObjectNode().put("reason", "void").put("delta", -1);
        if (!claim(tenantId, batchId, row.variantId(), locationId, -1, "void_correction", pieceId, initialPayload)) {
            log.debug("Shopify inventory: void_correction already claimed, skipping duplicate call piece={}", pieceId);
            return;
        }

        // Did this piece's originating receiving increment actually apply? Receiving syncs
        // per (session, variant, location) — not per piece — so this is the closest per-piece
        // signal available (see Step-0 diagnosis, section C.10): no per-piece flag exists.
        boolean incrementApplied = row.receiptId() != null && Boolean.TRUE.equals(tx.execute(status ->
            jdbc.query(
                "SELECT EXISTS (SELECT 1 FROM shopify_inventory_adjustments " +
                "WHERE tenant_id = ? AND trigger_type = 'receiving_session' AND trigger_id = ? " +
                "  AND variant_id = ? AND location_id = ? AND status = 'applied')",
                rs -> rs.next() && rs.getBoolean(1),
                tenantId, row.receiptId().toString(), row.variantId(), locationId)));

        if (!incrementApplied) {
            markResult(tenantId, "void_correction", pieceId, row.variantId(), locationId, null, null,
                       "skipped", "Receiving increment never applied for this piece's session — on_hand already correct");
            return;
        }

        Preconditions p = resolvePreconditions(tenantId, row.variantId(), locationId, "void_correction", pieceId);
        if (p.error() != null) {
            markResult(tenantId, "void_correction", pieceId, row.variantId(), locationId,
                       p.shopifyInventoryItemId(), p.shopifyLocationId(), "failed", p.error());
            return;
        }

        String status;
        String error = null;
        try {
            String idempotencyKey = ShopifyGateway.idempotencyKey(
                tenantId, "void_correction", pieceId, row.variantId(), locationId);
            shopify.pushVoidCorrection(p.shopDomain(), p.token(), p.shopifyInventoryItemId(),
                                        p.shopifyLocationId(), -1, "traced://piece/" + pieceId, idempotencyKey);
            status = "applied";
        } catch (Exception e) {
            status = "failed";
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Shopify inventory void correction failed: piece={} variant={} error={}",
                     pieceId, row.variantId(), error);
        }

        markResult(tenantId, "void_correction", pieceId, row.variantId(), locationId,
                   p.shopifyInventoryItemId(), p.shopifyLocationId(), status, error);
    }

    // ── Hold enter processing ────────────────────────────────────────────────

    private void processHoldEnter(String pieceId, UUID locationId, UUID holdEventId) {
        UUID tenantId = TenantContext.require();
        UUID batchId = UUID.randomUUID();
        String triggerId = pieceId + ":" + holdEventId;

        UUID variantId = resolveVariantForPiece(pieceId);
        if (variantId == null) {
            log.warn("Shopify inventory sync: piece not found piece={}", pieceId);
            return;
        }

        if (!isFulfillmentLocation(tenantId, locationId, "hold_enter", triggerId)) {
            return;
        }

        ObjectNode initialPayload = mapper.createObjectNode().put("reason", "hold").put("delta", -1);
        if (!claim(tenantId, batchId, variantId, locationId, -1, "hold_enter", triggerId, initialPayload)) {
            log.debug("Shopify inventory: hold_enter already claimed, skipping duplicate call piece={}", pieceId);
            return;
        }

        Preconditions p = resolvePreconditions(tenantId, variantId, locationId, "hold_enter", triggerId);
        if (p.error() != null) {
            markResult(tenantId, "hold_enter", triggerId, variantId, locationId,
                       p.shopifyInventoryItemId(), p.shopifyLocationId(), "failed", p.error());
            return;
        }

        String status;
        String error = null;
        try {
            String idempotencyKey = ShopifyGateway.idempotencyKey(
                tenantId, "hold_enter", triggerId, variantId, locationId);
            shopify.pushHoldEnter(p.shopDomain(), p.token(), p.shopifyInventoryItemId(),
                                   p.shopifyLocationId(), -1, "traced://piece/" + pieceId, idempotencyKey);
            status = "applied";
        } catch (Exception e) {
            status = "failed";
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Shopify inventory hold enter failed: piece={} variant={} error={}",
                     pieceId, variantId, error);
        }

        markResult(tenantId, "hold_enter", triggerId, variantId, locationId,
                   p.shopifyInventoryItemId(), p.shopifyLocationId(), status, error);
    }

    // ── Manual repush (FR-13.x exceptions center integration) ────────────────

    /**
     * Manual, synchronous, one-shot re-attempt of a FAILED void_correction or hold_enter
     * adjustment — a deliberate operator action after seeing the ExceptionService
     * 'void_hold_sync_failed' detector fire, not a hot path (same "manual action" reasoning
     * as ShopifyInventoryReconcileService.apply()). Full auto-repush parity (failed_ambiguous
     * classification, scheduled retry) is explicitly deferred — this is a single re-attempt.
     *
     * Reuses claim()'s EXISTING "ON CONFLICT ... WHERE status = 'failed'" reclaim branch — no
     * new claim mechanism, no new gateway call shape. Reads locationId off the STORED
     * adjustment row (not the piece's current_location_id, which may have moved since) so the
     * retry targets the exact same location the original attempt did.
     *
     * @throws ResponseStatusException 404 if no matching row; 409 if it is not currently 'failed'
     */
    public void repushFailedVoidOrHold(String triggerType, String triggerId) {
        UUID tenantId = TenantContext.require();
        if (!"void_correction".equals(triggerType) && !"hold_enter".equals(triggerType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "triggerType must be void_correction or hold_enter");
        }

        record FailedRow(UUID locationId, String status) {}
        FailedRow row = tx.execute(status ->
            jdbc.query(
                "SELECT location_id, status FROM shopify_inventory_adjustments " +
                "WHERE tenant_id = ? AND trigger_type = ? AND trigger_id = ?",
                rs -> rs.next() ? new FailedRow(rs.getObject(1, UUID.class), rs.getString(2)) : null,
                tenantId, triggerType, triggerId));
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No adjustment found for " + triggerType + "/" + triggerId);
        }
        if (!"failed".equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Adjustment is not in 'failed' status (current: " + row.status() + ")");
        }

        if ("void_correction".equals(triggerType)) {
            processVoidCorrection(triggerId, row.locationId());
        } else {
            String[] parts = triggerId.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalStateException("Malformed hold_enter trigger_id: " + triggerId);
            }
            processHoldEnter(parts[0], row.locationId(), UUID.fromString(parts[1]));
        }
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

    private Preconditions resolvePreconditions(UUID tenantId, UUID variantId, UUID locationId,
                                                String triggerType, String triggerId) {
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
                record StoreSnap(UUID id, String shopDomain, String grantedScopes, String connectionType) {}
                // ORDER BY last_sync_at DESC NULLS LAST — a tenant is schema-legal to have more
                // than one stores row (no UNIQUE(tenant_id) constraint exists), so a bare LIMIT 1
                // picks an undefined row. Matches ConnectionsController's "most recently connected
                // store" pattern. Without this, a stale/never-synced store row can beat the real
                // one, reading its (possibly empty) scopes/token instead — this was live and
                // undiagnosed during the 2026-07-31 read_products scope-check investigation.
                StoreSnap store = tx.execute(status ->
                    jdbc.query(
                        "SELECT id, shop_domain, access_token_scopes, connection_type FROM stores " +
                        "WHERE tenant_id = ? ORDER BY last_sync_at DESC NULLS LAST LIMIT 1",
                        rs -> rs.next() ? new StoreSnap(
                            rs.getObject(1, UUID.class),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4)) : null,
                        tenantId));

                if (store == null) {
                    variantError = "No store found for tenant";
                } else if (!ShopifyGateway.isScopeGranted("read_products", store.grantedScopes())) {
                    // TEMPORARY DIAGNOSTIC (2026-07-31) — tracing a live bug where the DB
                    // confirms correct scopes for tenant ab9af168 but this check still fails.
                    // Logs the ThreadLocal tenant alongside the tenantId parameter used for the
                    // query above (a divergence here would mean the wrong tenant is active on
                    // this thread despite the caller believing it's ab9af168), the exact store
                    // row read (id/shop_domain/raw scopes string, not just the parsed boolean),
                    // and the claim row's created_at to distinguish a fresh trigger from a
                    // retry of an older, possibly pre-reconnect claim. Remove once root-caused.
                    UUID threadLocalTenantId = TenantContext.get();
                    Instant claimRowCreatedAt = tx.execute(status ->
                        jdbc.query(
                            "SELECT created_at FROM shopify_inventory_adjustments " +
                            "WHERE tenant_id = ? AND trigger_type = ? AND trigger_id = ? " +
                            "  AND variant_id = ? AND location_id = ?",
                            rs -> rs.next() ? rs.getObject(1, java.time.OffsetDateTime.class).toInstant() : null,
                            tenantId, triggerType, triggerId, variantId, locationId));
                    log.warn("SCOPE-CHECK-DIAG read_products denied: paramTenantId={} " +
                             "threadLocalTenantId={} (MATCH={}) resolvedStore[id={}, shopDomain={}, " +
                             "rawAccessTokenScopes='{}'] claimRow[triggerType={}, triggerId={}, " +
                             "createdAt={}] now={}",
                        tenantId, threadLocalTenantId, tenantId.equals(threadLocalTenantId),
                        store.id(), store.shopDomain(), store.grantedScopes(),
                        triggerType, triggerId, claimRowCreatedAt, Instant.now());

                    variantError = ShopifyGateway.scopeGrantMessage(
                        store.connectionType(), "read_products", store.grantedScopes());
                } else if (!ShopifyGateway.isScopeGranted("write_inventory", store.grantedScopes())) {
                    variantError = ShopifyGateway.scopeGrantMessage(
                        store.connectionType(), "write_inventory", store.grantedScopes());
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

        if (!isFulfillmentLocation(tenantId, locationId, triggerType, triggerId)) {
            return;
        }

        // Claim BEFORE resolving preconditions or calling Shopify — see claim() for why a
        // prior SELECT check is not sufficient under concurrency.
        ObjectNode initialPayload = mapper.createObjectNode().put("reason", reason).put("delta", delta);
        if (!claim(tenantId, batchId, variantId, locationId, delta, triggerType, triggerId, initialPayload)) {
            log.debug("Shopify inventory: trigger already claimed, skipping duplicate call " +
                      "trigger={} triggerId={} variant={}", triggerType, triggerId, variantId);
            return;
        }

        Preconditions p = resolvePreconditions(tenantId, variantId, locationId, triggerType, triggerId);

        if (p.error() != null) {
            markResult(tenantId, triggerType, triggerId, variantId, locationId,
                       p.shopifyInventoryItemId(), p.shopifyLocationId(), "failed", p.error());
            return;
        }

        String status;
        String error = null;
        try {
            String idempotencyKey = ShopifyGateway.idempotencyKey(
                tenantId, triggerType, triggerId, variantId, locationId);
            shopify.adjustInventoryQuantities(p.shopDomain(), p.token(), p.shopifyInventoryItemId(),
                                              p.shopifyLocationId(), delta, reason, idempotencyKey);
            status = "applied";
        } catch (Exception e) {
            status = "failed";
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Shopify inventory adjust failed: trigger={} triggerId={} variant={} error={}",
                     triggerType, triggerId, variantId, error);
        }

        markResult(tenantId, triggerType, triggerId, variantId, locationId,
                   p.shopifyInventoryItemId(), p.shopifyLocationId(), status, error);
    }

    // ── Trigger 3 core: available→damaged move ───────────────────────────────

    private void applyDamageMove(UUID batchId, UUID variantId, UUID locationId, String pieceId) {
        UUID tenantId = TenantContext.require();

        if (!isFulfillmentLocation(tenantId, locationId, "damage_move", pieceId)) {
            return;
        }

        ObjectNode initialPayload = mapper.createObjectNode()
            .put("reason", "damaged").put("delta", 0).put("moveQuantity", 1);
        if (!claim(tenantId, batchId, variantId, locationId, 0, "damage_move", pieceId, initialPayload)) {
            log.debug("Shopify inventory: damage move already claimed, skipping duplicate call piece={}", pieceId);
            return;
        }

        Preconditions p = resolvePreconditions(tenantId, variantId, locationId, "damage_move", pieceId);

        if (p.error() != null) {
            markResult(tenantId, "damage_move", pieceId, variantId, locationId,
                       p.shopifyInventoryItemId(), p.shopifyLocationId(), "failed", p.error());
            return;
        }

        String status;
        String error = null;
        try {
            // on_hand unchanged — the unit leaves the sellable pool (available -> damaged).
            // Insufficient-available or any other Shopify userError fails cleanly here —
            // never forced, never retried automatically. See ShopifyGateway.moveAvailableToDamaged.
            String idempotencyKey = ShopifyGateway.idempotencyKey(
                tenantId, "damage_move", pieceId, variantId, locationId);
            shopify.moveAvailableToDamaged(p.shopDomain(), p.token(), p.shopifyInventoryItemId(),
                                            p.shopifyLocationId(), 1, "damaged", idempotencyKey);
            status = "applied";
        } catch (Exception e) {
            status = "failed";
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Shopify inventory damage move failed: piece={} variant={} error={}",
                     pieceId, variantId, error);
        }

        markResult(tenantId, "damage_move", pieceId, variantId, locationId,
                   p.shopifyInventoryItemId(), p.shopifyLocationId(), status, error);
    }

    // ── Shared guards / persistence ──────────────────────────────────────────

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

    /**
     * Atomically claims the right to call Shopify for this exact (trigger, variant, location) —
     * claim-before-call, not check-before-call. A prior plain SELECT-then-INSERT has a race
     * window: two concurrent callers (a JobRunr retry overlapping the original, a duplicate
     * webhook) can both pass a SELECT before either has written a row, and both would then
     * call Shopify. Here the INSERT itself, gated by the V48 UNIQUE(trigger_type, trigger_id,
     * variant_id, location_id) constraint, IS the guard: only one of two concurrent INSERTs
     * for the same key can create the row (the loser blocks on the unique index until the
     * winner's transaction commits, then re-evaluates its own ON CONFLICT clause against the
     * now-committed row).
     *
     * ON CONFLICT DO UPDATE ... WHERE status = 'failed' — a prior FAILURE is reclaimable
     * (nothing was actually applied by it), but a prior 'pending' (in-flight, possibly on
     * another node right now) or 'applied' (already succeeded) row is not: the WHERE clause
     * fails to match, the DO UPDATE doesn't fire, and jdbc.update() reports 0 affected rows —
     * exactly the signal the caller needs to skip without ever touching Shopify.
     *
     * The claim is committed in its own short transaction (does not span the Shopify HTTP
     * call that follows) — see markResult() for the corresponding follow-up write.
     *
     * Known limitation: if the process crashes after a successful claim but before
     * markResult() runs, the row is stuck at 'pending' forever (the WHERE clause only
     * reclaims 'failed'). These triggers are fire-and-forget calls from a single synchronous
     * call site each (receiving close, restock, damage) with no external retry mechanism
     * today, so this is an accepted, documented edge case rather than a silent bug — it needs
     * a stale-pending sweep only if/when these triggers grow a retry path.
     */
    private boolean claim(UUID tenantId, UUID batchId, UUID variantId, UUID locationId, int delta,
                          String triggerType, String triggerId, ObjectNode initialPayload) {
        String payloadJsonTmp;
        try { payloadJsonTmp = mapper.writeValueAsString(initialPayload); }
        catch (Exception e) { payloadJsonTmp = "{}"; }
        final String finalPayloadJson = payloadJsonTmp;

        Integer rows = tx.execute(status -> jdbc.update(
            "INSERT INTO shopify_inventory_adjustments " +
            "(tenant_id, batch_id, variant_id, location_id, delta, trigger_type, trigger_id, payload, status) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'pending') " +
            "ON CONFLICT (trigger_type, trigger_id, variant_id, location_id) DO UPDATE " +
            "  SET status = 'pending', batch_id = EXCLUDED.batch_id, payload = EXCLUDED.payload " +
            "  WHERE shopify_inventory_adjustments.status = 'failed'",
            tenantId, batchId, variantId, locationId, delta, triggerType, triggerId, finalPayloadJson));

        return rows != null && rows > 0;
    }

    /** Follow-up write after the Shopify call (or after a precondition failure) — a plain
     *  UPDATE by the same unique key the claim used, run in its own short transaction after
     *  the HTTP call has already returned. shopifyInventoryItemId/shopifyLocationId are
     *  COALESCEd so a later call never blanks out a value a concurrent/prior call resolved. */
    private void markResult(UUID tenantId, String triggerType, String triggerId,
                            UUID variantId, UUID locationId,
                            String shopifyInventoryItemId, String shopifyLocationId,
                            String status, String error) {
        tx.execute(txStatus -> {
            jdbc.update(
                "UPDATE shopify_inventory_adjustments SET " +
                "  status = ?, error = ?, " +
                "  shopify_inventory_item_id = COALESCE(?, shopify_inventory_item_id), " +
                "  shopify_location_id = COALESCE(?, shopify_location_id) " +
                "WHERE tenant_id = ? AND trigger_type = ? AND trigger_id = ? " +
                "  AND variant_id = ? AND location_id = ?",
                status, error, shopifyInventoryItemId, shopifyLocationId,
                tenantId, triggerType, triggerId, variantId, locationId);
            return null;
        });

        if ("failed".equals(status)) {
            log.warn("Shopify inventory adjustment recorded as failed: trigger={} triggerId={} variant={} error={}",
                     triggerType, triggerId, variantId, error);
        }
    }
}
