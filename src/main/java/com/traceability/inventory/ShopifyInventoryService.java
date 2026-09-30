package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.ShopifyAdjustFailedException;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
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
 *     - pushVoidCorrection / pushHoldEnter / pushExchangeDispatch — dedicated single-attempt
 *       NEGATIVE-delta gateway methods (FR-13.x, Step 5a; see CLAUDE.md FR-21 §7 extension for
 *       the full named set).
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
 *   Trigger exchange_dispatch (Step 5a, approved 2026-09-26): a replacement piece of an
 *              INTERNAL exchange order ('internal:exchange:%' + its exchanges row) first leaves
 *              Traced custody (packed/awaiting_pickup → with_courier or delivered) → -1, once per
 *              piece (trigger_id = piece_id). Those orders never exist in Shopify, so Shopify
 *              never saw the sale. If the replacement later comes back and is restocked, trigger
 *              2's +1 nets it to zero.
 *   No other courier/loss/order-driven decrement trigger — Shopify owns those.
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
    private final StoreRepository      storeRepository;
    private final InventoryItemIdService itemIds;

    public ShopifyInventoryService(JdbcTemplate jdbc,
                                   PlatformTransactionManager txm,
                                   ShopifyGateway shopify,
                                   ShopifyTokenProvider tokenProvider,
                                   ObjectMapper mapper,
                                   StoreRepository storeRepository) {
        this.jdbc          = jdbc;
        this.tx            = new TransactionTemplate(txm);
        this.shopify       = shopify;
        this.tokenProvider = tokenProvider;
        this.mapper        = mapper;
        this.storeRepository = storeRepository;
        this.itemIds       = new InventoryItemIdService(jdbc, txm, shopify);
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

    // ── Trigger: Step 5a exchange dispatch (named decrement set — CLAUDE.md) ──

    /**
     * True for the first move of a piece out of Traced custody: packed / awaiting_pickup →
     * with_courier (pickup handover, or Bosta "picked up") or straight to delivered (Bosta
     * reported the doorstep swap before any pickup event). The two writers
     * (PickupSessionService.closeSession, BostaWebhookJob.applyMappedState) call this only
     * after a ledger transition actually happened — never on their "already there" branches.
     */
    public static boolean leavesCustody(PieceStatus from, PieceStatus to) {
        return (from == PieceStatus.PACKED || from == PieceStatus.AWAITING_PICKUP)
            && (to == PieceStatus.WITH_COURIER || to == PieceStatus.DELIVERED);
    }

    /**
     * Runs {@code action} once the current transaction commits, or right away when no
     * transaction is active (the ledger's own transaction has already committed). A rolled-back
     * transition never reaches Shopify.
     */
    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    /**
     * Step 5a — called (after commit) when a piece first leaves Traced custody. Decrements
     * Shopify by 1 only when the piece is the replacement of an internal exchange order:
     * allocated (active/packed) to an order with external_id 'internal:exchange:%' that an
     * exchanges row names as its outbound_order_id, and the piece's current location is a
     * fulfillment location. Anything else — a Shopify order, a demo order — is a silent no-op.
     */
    @Async
    public CompletableFuture<Void> onExchangeReplacementDispatched(UUID tenantId, String pieceId) {
        TenantContext.runAs(tenantId, () -> {
            try {
                processExchangeDispatch(pieceId, null);
            } catch (Exception e) {
                log.error("Shopify inventory sync failed: trigger=exchange_dispatch piece={}", pieceId, e);
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    private record DispatchRow(UUID variantId, UUID locationId) {}

    /** {@code storedLocationId}: the re-push path passes the location of the original attempt. */
    private void processExchangeDispatch(String pieceId, UUID storedLocationId) {
        UUID tenantId = TenantContext.require();
        UUID batchId = UUID.randomUUID();

        // The live trigger needs the piece's current allocation (active/packed). A re-push may
        // run after the replacement came back and was restocked (allocation released) — the
        // original departure still happened, so any allocation to the internal order counts.
        String allocationStatuses = storedLocationId == null ? "('active', 'packed')" : "('active', 'packed', 'released')";
        DispatchRow row = tx.execute(status -> jdbc.query(
            "SELECT p.variant_id, p.current_location_id FROM pieces p " +
            "WHERE p.id = ? AND p.tenant_id = ? " +
            "  AND EXISTS (SELECT 1 FROM allocations a " +
            "              JOIN order_items oi ON oi.id = a.order_item_id " +
            "              JOIN orders o ON o.id = oi.order_id AND o.tenant_id = p.tenant_id " +
            "              JOIN exchanges e ON e.outbound_order_id = o.id AND e.tenant_id = o.tenant_id " +
            "              WHERE a.piece_id = p.id AND a.status IN " + allocationStatuses +
            "                AND o.external_id LIKE 'internal:exchange:%')",
            rs -> rs.next() ? new DispatchRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)) : null,
            pieceId, tenantId));
        if (row == null) return;   // not an internal exchange replacement — nothing to do

        UUID locationId = storedLocationId != null ? storedLocationId : row.locationId();
        if (locationId == null || !isFulfillmentLocation(tenantId, locationId, "exchange_dispatch", pieceId)) {
            return;
        }

        ObjectNode initialPayload = mapper.createObjectNode().put("reason", "exchange_dispatch").put("delta", -1);
        if (!claim(tenantId, batchId, row.variantId(), locationId, -1, "exchange_dispatch", pieceId, initialPayload)) {
            log.debug("Shopify inventory: exchange_dispatch already claimed, skipping duplicate call piece={}", pieceId);
            return;
        }

        Preconditions p = resolvePreconditions(tenantId, row.variantId(), locationId, "exchange_dispatch", pieceId);
        if (p.error() != null) {
            markResult(tenantId, "exchange_dispatch", pieceId, row.variantId(), locationId,
                       p.shopifyInventoryItemId(), p.shopifyLocationId(), "failed", p.error());
            return;
        }

        String status;
        String error = null;
        try {
            String idempotencyKey = ShopifyGateway.idempotencyKey(
                tenantId, "exchange_dispatch", pieceId, row.variantId(), locationId);
            shopify.pushExchangeDispatch(p.shopDomain(), p.token(), p.shopifyInventoryItemId(),
                                          p.shopifyLocationId(), -1, "traced://piece/" + pieceId, idempotencyKey);
            status = "applied";
        } catch (Exception e) {
            status = "failed";
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Shopify inventory exchange dispatch failed: piece={} variant={} error={}",
                     pieceId, row.variantId(), error);
        }

        markResult(tenantId, "exchange_dispatch", pieceId, row.variantId(), locationId,
                   p.shopifyInventoryItemId(), p.shopifyLocationId(), status, error);
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
     * Manual, synchronous, one-shot re-attempt of a FAILED void_correction, hold_enter or
     * (Step 5a) exchange_dispatch adjustment — a deliberate operator action after seeing the ExceptionService
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
        if (!"void_correction".equals(triggerType) && !"hold_enter".equals(triggerType)
                && !"exchange_dispatch".equals(triggerType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "triggerType must be void_correction, hold_enter or exchange_dispatch");
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
        } else if ("exchange_dispatch".equals(triggerType)) {
            // Same claim key → same deterministic idempotency key as the failed attempt.
            processExchangeDispatch(triggerId, row.locationId());
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
                // FR-3.1 follow-up — StoreRepository.findActiveStoreByTenant() is the single
                // canonical pick (never a disconnected row) shared by every job/service, so a
                // disconnect-then-switch tenant's stale old row can never beat the real one
                // here. Fixes the same class of bug the 2026-07-31 read_products scope-check
                // investigation diagnosed (an unordered/under-ordered pick reading a stale
                // store's possibly-empty scopes/token instead of the real one).
                StoreRepository.Store store = storeRepository.findActiveStoreByTenant(tenantId).orElse(null);

                if (store == null) {
                    variantError = "No store found for tenant";
                } else if (!ShopifyGateway.isScopeGranted("read_products", store.accessTokenScopes())) {
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
                        store.id(), store.shopDomain(), store.accessTokenScopes(),
                        triggerType, triggerId, claimRowCreatedAt, Instant.now());

                    variantError = ShopifyGateway.scopeGrantMessage(
                        store.connectionType(), "read_products", store.accessTokenScopes());
                } else if (!ShopifyGateway.isScopeGranted("write_inventory", store.accessTokenScopes())) {
                    variantError = ShopifyGateway.scopeGrantMessage(
                        store.connectionType(), "write_inventory", store.accessTokenScopes());
                } else {
                    shopDomain = store.shopDomain();
                    token = tokenProvider.getValidToken(store.id());
                    shopifyInventoryItemId = itemIds.resolve(tenantId, variantId, variantGid, shopDomain, token);
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

        attemptIncrement(tenantId, variantId, locationId, delta, triggerType, triggerId, reason,
            ShopifyGateway.idempotencyKey(tenantId, triggerType, triggerId, variantId, locationId), null, false);
    }

    /**
     * One increment attempt after a successful claim: preconditions, lazy activation, the adjust —
     * every outcome recorded with its failure class (Part D). keyToSend is the idempotency key for
     * this attempt; resendBaseline is used (identical resend) when resend is true, otherwise the
     * gateway reads a fresh baseline.
     */
    private void attemptIncrement(UUID tenantId, UUID variantId, UUID locationId, int delta,
                                  String triggerType, String triggerId, String reason,
                                  String keyToSend, Integer resendBaseline, boolean resend) {
        Preconditions p = resolvePreconditions(tenantId, variantId, locationId, triggerType, triggerId);

        if (p.error() != null) {
            markIncrementResult(tenantId, triggerType, triggerId, variantId, locationId,
                p.shopifyInventoryItemId(), p.shopifyLocationId(), "failed", p.error(),
                ShopifyAdjustFailedException.FailureClass.NEVER_SENT, null, null);
            return;
        }

        // Lazy activation: only ACTIVE products' variants are activated at connect, so a draft or
        // archived variant may reach its first increment without an inventory level at the Traced
        // location. Ensure it first — inventoryActivate carries no quantity (it only creates the
        // level at 0, "already active" tolerated), and it uses the same idempotency key as the
        // catalog activation for this variant + location. If it fails, the adjust is NOT sent and
        // the claim is 'failed' (the adjust never reached Shopify, so a later retry is safe).
        try {
            shopify.activateInventoryItem(p.shopDomain(), p.token(), p.shopifyInventoryItemId(),
                p.shopifyLocationId(),
                ShopifyCatalogActivationService.activationKey(tenantId, variantId, p.shopifyLocationId()));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Shopify inventory: activation before increment failed — adjust not sent " +
                     "trigger={} triggerId={} variant={} error={}", triggerType, triggerId, variantId, msg);
            markIncrementResult(tenantId, triggerType, triggerId, variantId, locationId,
                p.shopifyInventoryItemId(), p.shopifyLocationId(), "failed",
                "Activation at the Traced location failed (adjust not sent): " + msg,
                ShopifyAdjustFailedException.FailureClass.NEVER_SENT, null, null);
            return;
        }

        try {
            if (resend) {
                shopify.resendInventoryAdjustment(p.shopDomain(), p.token(), p.shopifyInventoryItemId(),
                    p.shopifyLocationId(), delta, reason, keyToSend, resendBaseline);
            } else {
                shopify.adjustInventoryQuantities(p.shopDomain(), p.token(), p.shopifyInventoryItemId(),
                    p.shopifyLocationId(), delta, reason, keyToSend);
            }
            markIncrementResult(tenantId, triggerType, triggerId, variantId, locationId,
                p.shopifyInventoryItemId(), p.shopifyLocationId(), "applied", null, null, null, keyToSend);
        } catch (Exception e) {
            String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            ShopifyAdjustFailedException.FailureClass cls;
            Integer sentBaseline = null;
            String sentKey = keyToSend;
            if (e instanceof ShopifyAdjustFailedException f) {
                cls = f.failureClass();
                sentBaseline = f.changeFromQuantity();
            } else if (e instanceof IllegalArgumentException) {
                cls = ShopifyAdjustFailedException.FailureClass.NEVER_SENT;   // rejected before any call
            } else if (e instanceof ShopifyException) {
                cls = ShopifyAdjustFailedException.FailureClass.REJECTED;
            } else {
                cls = ShopifyAdjustFailedException.FailureClass.AMBIGUOUS;    // unknown — assume it may have landed
            }
            if (cls == ShopifyAdjustFailedException.FailureClass.NEVER_SENT) sentKey = null;
            if (resend && sentBaseline == null) sentBaseline = resendBaseline;
            log.warn("Shopify inventory adjust failed: trigger={} triggerId={} variant={} class={} error={}",
                     triggerType, triggerId, variantId, cls.db(), error);
            markIncrementResult(tenantId, triggerType, triggerId, variantId, locationId,
                p.shopifyInventoryItemId(), p.shopifyLocationId(), "failed", error, cls, sentBaseline, sentKey);
        }
    }

    // ── Failed-increment recovery (Part D) ──────────────────────────────────────

    public record RetryResult(int due, int attempted, int applied, int skippedAmbiguousExpired,
                              IncrementRecoveryRules.SetupProblem blockedBy) {}

    private record FailedClaim(UUID variantId, UUID locationId, int delta, String triggerType, String triggerId,
                               String failureClass, Integer changeFromQuantity, String sentKey,
                               int attemptCount, boolean ambiguousExpired, boolean olderThanConfirm, boolean legacy) {}

    private static final String FAILED_CLAIM_COLUMNS =
        "SELECT sia.variant_id, sia.location_id, sia.delta, sia.trigger_type, sia.trigger_id, sia.failure_class, " +
        "       sia.change_from_quantity, sia.sent_idempotency_key, sia.attempt_count, " +
        "       COALESCE(" + IncrementRecoveryRules.AMBIGUOUS_EXPIRED_SQL + ", false) AS ambiguous_expired, " +
        "       COALESCE(sia.first_attempt_at, sia.created_at) < now() - interval '" +
                IncrementRecoveryRules.CONFIRM_AFTER_HOURS + " hours' AS older_than_confirm, sia.legacy " +
        "FROM shopify_inventory_adjustments sia ";

    private static FailedClaim failedClaim(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FailedClaim(rs.getObject("variant_id", UUID.class), rs.getObject("location_id", UUID.class),
            rs.getInt("delta"), rs.getString("trigger_type"), rs.getString("trigger_id"), rs.getString("failure_class"),
            (Integer) rs.getObject("change_from_quantity"), rs.getString("sent_idempotency_key"),
            rs.getInt("attempt_count"), rs.getBoolean("ambiguous_expired"), rs.getBoolean("older_than_confirm"),
            rs.getBoolean("legacy"));
    }

    /**
     * The retry job's per-tenant pass (TenantContext set by the caller): every failed, non-legacy
     * increment claim whose next attempt is due. A tenant-level setup problem blocks the whole pass —
     * no attempt is spent (the inventory_increment_sync_failed exception names the fix). Each retry
     * goes through the existing claim (failed → pending) and resends the claim's ORIGINAL positive
     * delta — never recomputed — to the Traced location:
     *   never_sent → same key, fresh baseline;
     *   rejected   → new key (claim key + attempt number), fresh baseline;
     *   ambiguous  → identical resend (same key, same sent baseline) while the key is < 20 h old;
     *                after that, no retry (alert only).
     */
    public RetryResult retryDueIncrements() {
        UUID tenantId = TenantContext.require();
        List<FailedClaim> due = tx.execute(st -> jdbc.query(
            FAILED_CLAIM_COLUMNS + "WHERE sia.tenant_id = ? AND " + IncrementRecoveryRules.DUE_SQL +
            " ORDER BY sia.next_attempt_at LIMIT 200",
            (rs, i) -> failedClaim(rs), tenantId));
        if (due == null || due.isEmpty()) return new RetryResult(0, 0, 0, 0, null);

        IncrementRecoveryRules.SetupProblem blocked = tx.execute(st -> IncrementRecoveryRules.setupProblem(jdbc, tenantId));
        if (blocked != null) {
            log.info("Increment retry: tenant={} {} due claim(s) blocked by {} — no attempt spent",
                tenantId, due.size(), blocked);
            return new RetryResult(due.size(), 0, 0, 0, blocked);
        }

        int attempted = 0, applied = 0, expired = 0;
        for (FailedClaim c : due) {
            if (c.ambiguousExpired()) {
                // Too late to resend identically — stop scheduling; the detector alerts.
                tx.execute(st -> jdbc.update(
                    "UPDATE shopify_inventory_adjustments SET next_attempt_at = NULL " +
                    "WHERE tenant_id = ? AND trigger_type = ? AND trigger_id = ? AND variant_id = ? AND location_id = ?",
                    tenantId, c.triggerType(), c.triggerId(), c.variantId(), c.locationId()));
                expired++;
                continue;
            }
            if (retryClaim(tenantId, c, false)) {
                attempted++;
                String status = tx.execute(st -> jdbc.queryForObject(
                    "SELECT status FROM shopify_inventory_adjustments " +
                    "WHERE tenant_id = ? AND trigger_type = ? AND trigger_id = ? AND variant_id = ? AND location_id = ?",
                    String.class, tenantId, c.triggerType(), c.triggerId(), c.variantId(), c.locationId()));
                if ("applied".equals(status)) applied++;
            }
        }
        return new RetryResult(due.size(), attempted, applied, expired, null);
    }

    /** Reclaims (failed → pending, the existing claim path) and attempts one retry; false when the
     *  claim was not reclaimable (someone else holds it, or it's no longer failed). */
    private boolean retryClaim(UUID tenantId, FailedClaim c, boolean manual) {
        ObjectNode payload = mapper.createObjectNode()
            .put("reason", reasonFor(c.triggerType())).put("delta", c.delta())
            .put("retryAttempt", c.attemptCount() + 1).put("manual", manual);
        if (!claim(tenantId, UUID.randomUUID(), c.variantId(), c.locationId(), c.delta(),
                   c.triggerType(), c.triggerId(), payload)) {
            return false;
        }
        int attemptNo = c.attemptCount() + 1;
        String claimKey = ShopifyGateway.idempotencyKey(tenantId, c.triggerType(), c.triggerId(), c.variantId(), c.locationId());
        ShopifyAdjustFailedException.FailureClass cls = ShopifyAdjustFailedException.FailureClass.fromDb(c.failureClass());

        if (cls == ShopifyAdjustFailedException.FailureClass.AMBIGUOUS && !c.ambiguousExpired() && c.sentKey() != null) {
            attemptIncrement(tenantId, c.variantId(), c.locationId(), c.delta(), c.triggerType(), c.triggerId(),
                reasonFor(c.triggerType()), c.sentKey(), c.changeFromQuantity(), true);
        } else if (cls == ShopifyAdjustFailedException.FailureClass.NEVER_SENT) {
            // Nothing ever reached Shopify under the key this claim last used — the same key is safe.
            attemptIncrement(tenantId, c.variantId(), c.locationId(), c.delta(), c.triggerType(), c.triggerId(),
                reasonFor(c.triggerType()), c.sentKey() != null ? c.sentKey() : claimKey, null, false);
        } else {
            // rejected — and, only through the confirmed manual repush, an expired ambiguous or a
            // legacy/unclassified claim: a NEW key, fresh baseline, the original delta.
            attemptIncrement(tenantId, c.variantId(), c.locationId(), c.delta(), c.triggerType(), c.triggerId(),
                reasonFor(c.triggerType()),
                IncrementRecoveryRules.retryKey(tenantId, c.triggerType(), c.triggerId(), c.variantId(), c.locationId(), attemptNo),
                null, false);
        }
        return true;
    }

    private static String reasonFor(String triggerType) {
        return switch (triggerType) {
            case "receiving_session" -> "received";
            case "hold_exit" -> "hold_exit";
            default -> "restock";
        };
    }

    /**
     * Manual repush of one failed increment claim (receiving_session / return_inspection / hold_exit),
     * legacy included — the reconciliation path. Requires confirmOld when the claim's first attempt is
     * more than 24 h old, or when it is ambiguous past the identical-resend window (the person has
     * checked Shopify). 404 no such claim, 409 not failed / needs confirmation / setup problem.
     */
    public void repushFailedIncrement(String triggerType, String triggerId, UUID variantId, boolean confirmOld) {
        UUID tenantId = TenantContext.require();
        if (!IncrementRecoveryRules.INCREMENT_TRIGGERS.contains(triggerType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "triggerType must be receiving_session, return_inspection or hold_exit");
        }
        List<FailedClaim> rows = tx.execute(st -> jdbc.query(
            FAILED_CLAIM_COLUMNS + "WHERE sia.tenant_id = ? AND sia.trigger_type = ? AND sia.trigger_id = ? " +
            "  AND sia.variant_id = ? AND sia.status = 'failed'",
            (rs, i) -> failedClaim(rs), tenantId, triggerType, triggerId, variantId));
        if (rows == null || rows.isEmpty()) {
            Integer exists = tx.execute(st -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = ? " +
                "AND trigger_id = ? AND variant_id = ?", Integer.class, tenantId, triggerType, triggerId, variantId));
            if (exists == null || exists == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No adjustment found for " + triggerType + "/" + triggerId);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Adjustment is not in 'failed' status");
        }
        FailedClaim c = rows.get(0);
        if ((c.olderThanConfirm() || c.ambiguousExpired()) && !confirmOld) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "CONFIRMATION_REQUIRED: this update is more than 24 hours old (or its first send may have landed) — " +
                "check the variant's quantity in Shopify, then confirm to send it");
        }
        IncrementRecoveryRules.SetupProblem blocked = tx.execute(st -> IncrementRecoveryRules.setupProblem(jdbc, tenantId));
        if (blocked != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, blocked.fixEn);
        }
        // A manual resend of an expired-ambiguous claim is a deliberate NEW send after the person's check.
        FailedClaim toSend = c.ambiguousExpired()
            ? new FailedClaim(c.variantId(), c.locationId(), c.delta(), c.triggerType(), c.triggerId(), "rejected",
                c.changeFromQuantity(), c.sentKey(), c.attemptCount(), true, c.olderThanConfirm(), c.legacy())
            : c;
        if (!retryClaim(tenantId, toSend, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Adjustment is already being retried");
        }
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

    /** markResult() for an increment attempt (Part D): also records the failure class, the baseline
     *  and key that were SENT (null when nothing was sent), and the attempt bookkeeping + backoff. */
    private void markIncrementResult(UUID tenantId, String triggerType, String triggerId,
                                     UUID variantId, UUID locationId,
                                     String shopifyInventoryItemId, String shopifyLocationId,
                                     String status, String error,
                                     ShopifyAdjustFailedException.FailureClass failureClass,
                                     Integer sentBaseline, String sentKey) {
        boolean failed = "failed".equals(status);
        tx.execute(txStatus -> jdbc.update(
            "UPDATE shopify_inventory_adjustments SET " +
            "  status = ?, error = ?, " +
            "  shopify_inventory_item_id = COALESCE(?, shopify_inventory_item_id), " +
            "  shopify_location_id = COALESCE(?, shopify_location_id), " +
            "  failure_class = ?, " +
            "  change_from_quantity = CASE WHEN ?::text IS NOT NULL THEN ?::int ELSE change_from_quantity END, " +
            "  sent_key_first_at = CASE WHEN ?::text IS NOT NULL AND ?::text IS DISTINCT FROM sent_idempotency_key " +
            "                           THEN now() ELSE sent_key_first_at END, " +
            "  sent_idempotency_key = COALESCE(?::text, sent_idempotency_key), " +
            "  first_attempt_at = COALESCE(first_attempt_at, now()), " +
            "  last_attempt_at = now(), " +
            "  next_attempt_at = " + (failed ? IncrementRecoveryRules.NEXT_ATTEMPT_AFTER_FAILURE_SQL : "NULL") + ", " +
            "  attempt_count = attempt_count + 1, " +
            "  applied_at = CASE WHEN ? = 'applied' THEN now() ELSE applied_at END " +
            "WHERE tenant_id = ? AND trigger_type = ? AND trigger_id = ? " +
            "  AND variant_id = ? AND location_id = ?",
            status, error, shopifyInventoryItemId, shopifyLocationId,
            failed && failureClass != null ? failureClass.db() : null,
            sentKey, sentBaseline, sentKey, sentKey, sentKey, status,
            tenantId, triggerType, triggerId, variantId, locationId));

        if (failed) {
            log.warn("Shopify inventory adjustment recorded as failed: trigger={} triggerId={} variant={} class={} error={}",
                     triggerType, triggerId, variantId, failureClass == null ? null : failureClass.db(), error);
        }
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
