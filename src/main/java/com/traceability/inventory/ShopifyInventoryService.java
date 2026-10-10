package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.ShopifyAdjustFailedException;
import com.traceability.integrations.shopify.ShopifyAmbiguousException;
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
 * PIECE SYNC (Lookup adjustments ↔ Shopify, approved 2026-10-10 — supersedes the per-trigger notes
 * above for void_correction / hold_enter / hold_exit / damage_move): every per-piece Lookup write is a
 * claim on this table, decided by PieceShopifyRules (countedAtMain for a departure, departureReached
 * for a return), written 'queued' or 'skipped' + skip_reason INSIDE the adjust transaction and sent ONCE
 * after commit by pushPieceClaimNow — classified like the transfer push; failed_ambiguous is never
 * re-sent; the sweep re-sends definite failures (≤ 5 attempts). Adds piece_write_off (−1,
 * pushPieceWriteOff — the sixth named decrement), piece_write_off_return (+1, Found it), and Back to
 * good: damage_restore (move damaged → available) or damaged_restore_increment (+1).
 *
 * LOCATION-TARGET GUARD: the Shopify locationGid used in every mutation call is read
 * directly off the SAME location row that passed the is_fulfillment=true AND
 * shopify_sync_status='linked' checks for the triggering event — there is no code path
 * that resolves one location's eligibility and then uses a different location's GID.
 * A non-fulfillment (or unlinked) triggering location never reaches the Shopify call at all.
 */
@Service
public class ShopifyInventoryService {

    /** Claim status set by the initial seed (V123) — never retried, repushed or reclaimed. */
    public static final String SUPERSEDED_BY_SEED = "superseded_by_seed";

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
     * Called once ReceivingService.finalize()'s transaction has committed (registered through
     * {@link #afterCommit}) — the claim this creates never predates its pieces, which the initial
     * seed's claim cutoff relies on.
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
     * Called once ReturnService.restock()'s transaction has committed ({@link #afterCommit}) —
     * piece transitioned to AVAILABLE.
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

    // ── Piece sync (Lookup adjustments ↔ Shopify, 2026-10-10, D3/D7) ─────────

    /**
     * Claim-before-call for a piece LEAVING the sellable pool — void_correction (−1), hold_enter (−1),
     * damage_move (available → damaged). Runs in the CALLER's transaction (the one that writes the
     * piece event), before the transition: PieceShopifyRules.countedAtMain decides, and the claim row
     * is written 'queued' (send it after commit — see {@link #pushPieceClaim}) or 'skipped' with its
     * reason. Returns the claim id to push, or null when nothing is to be sent.
     */
    public Long claimPieceDeparture(UUID tenantId, String triggerType, String pieceId, String triggerId) {
        if (!PieceShopifyRules.DEPARTURE_TRIGGERS.contains(triggerType)) {
            throw new IllegalArgumentException("not a piece departure trigger: " + triggerType);
        }
        PieceShopifyRules.Verdict v = PieceShopifyRules.countedAtMain(jdbc, tenantId, pieceId);
        int delta = "damage_move".equals(triggerType) ? 0 : -1;
        return insertPieceClaim(tenantId, pieceId, v, delta, triggerType, triggerId);
    }

    /**
     * Claim-before-call for a piece coming BACK to available — hold_exit (+1). Same transaction rule
     * as {@link #claimPieceDeparture}, called before the transition: PieceShopifyRules.departureReached
     * decides (and cancels the departure claim if it was never sent).
     */
    public Long claimPieceReturn(UUID tenantId, String triggerType, String pieceId, String triggerId) {
        if (!"hold_exit".equals(triggerType) && !"piece_write_off_return".equals(triggerType)) {
            throw new IllegalArgumentException("not a piece return trigger: " + triggerType);
        }
        PieceShopifyRules.Verdict v = PieceShopifyRules.departureReached(jdbc, tenantId, pieceId);
        return insertPieceClaim(tenantId, pieceId, v, 1, triggerType, triggerId);
    }

    /**
     * Back to good (D11) — damaged → available, claimed in the caller's transaction before the
     * transition, keyed piece:restore_event_id. departureReached decides the write:
     *   the damage move applied → damage_restore (move damaged → available, on_hand unchanged);
     *   the damage never reached Shopify's available count — damaged at return inspection (never
     *   restocked), or damaged before the seed, or after a hold whose −1 applied → +1
     *   (damaged_restore_increment); return-inspection damage goes through the same Shopify-refund
     *   double-count guard as a restock;
     *   anything else → a skipped row (failed / skipped damage move: Shopify still counts it).
     */
    public Long claimPieceRestore(UUID tenantId, String pieceId, String restoreEventId) {
        String triggerId = pieceId + ":" + restoreEventId;
        PieceShopifyRules.Verdict v = PieceShopifyRules.departureReached(jdbc, tenantId, pieceId);
        if (v.write() && "damage_move".equals(v.via())) {
            return insertPieceClaim(tenantId, pieceId, v, 0, "damage_restore", triggerId);
        }
        if (v.write() && "inspection".equals(v.via())) {
            return claimInspectionRestore(tenantId, pieceId, v, triggerId);
        }
        // Not written: the skipped row is a damaged_restore_increment (the +1 Back to good would have sent).
        return insertPieceClaim(tenantId, pieceId, v, 1, "damaged_restore_increment", triggerId);
    }

    /**
     * Case (a) — a piece damaged at return inspection: Shopify never counted it back, so Back to good is
     * +1 — unless the merchant already restocked it through a Shopify refund. The SAME guard as a
     * restock (processReturnInspection): per (order, variant) advisory lock; while the order's refund
     * restocks of the variant outnumber the units Traced already counted for it (restocks and earlier
     * inspection restores), record 'skipped_shopify_restocked' and send nothing. A refund restock that
     * arrives after Traced's +1 raises restocked_twice (ExceptionService counts both trigger types).
     */
    private Long claimInspectionRestore(UUID tenantId, String pieceId, PieceShopifyRules.Verdict v, String triggerId) {
        UUID variantId = jdbc.queryForObject(
            "SELECT variant_id FROM pieces WHERE id = ? AND tenant_id = ?", UUID.class, pieceId, tenantId);
        // The order it came back from: the piece's current order, else its latest event naming one.
        UUID orderId = jdbc.query(
            "SELECT COALESCE(p.current_order_id, (SELECT e.order_id FROM piece_events e " +
            "    WHERE e.piece_id = p.id AND e.tenant_id = p.tenant_id AND e.order_id IS NOT NULL " +
            "    ORDER BY e.occurred_at DESC, e.id DESC LIMIT 1)) " +
            "FROM pieces p WHERE p.id = ? AND p.tenant_id = ?",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null, pieceId, tenantId);
        if (orderId != null) {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> null, "restock:" + tenantId + ":" + orderId + ":" + variantId);
            Map<String, Object> counts = jdbc.queryForMap(
                "SELECT COALESCE((SELECT shopify_refund_restocked_units(o.raw, v.external_id) " +
                "                 FROM orders o, variants v " +
                "                 WHERE o.id = ? AND o.tenant_id = ? AND v.id = ? AND v.tenant_id = ?), 0) AS shopify_units, " +
                "       (SELECT COUNT(*) FROM shopify_inventory_adjustments " +
                "        WHERE tenant_id = ? AND trigger_type IN ('return_inspection', 'damaged_restore_increment') " +
                "          AND source_order_id = ? AND variant_id = ? AND trigger_id <> ?) AS traced_units",
                orderId, tenantId, variantId, tenantId, tenantId, orderId, variantId, triggerId);
            long shopifyUnits = ((Number) counts.get("shopify_units")).longValue();
            long tracedUnits  = ((Number) counts.get("traced_units")).longValue();
            if (shopifyUnits > tracedUnits) {
                if (isReviewFixtureVariant(tenantId, variantId, "damaged_restore_increment", triggerId)) return null;
                jdbc.update(
                    "INSERT INTO shopify_inventory_adjustments " +
                    "(tenant_id, batch_id, variant_id, location_id, delta, trigger_type, trigger_id, payload, status, " +
                    " skip_reason, error, source_order_id) " +
                    "VALUES (?, ?, ?, ?, 1, 'damaged_restore_increment', ?, ?::jsonb, 'skipped_shopify_restocked', ?, ?, ?) " +
                    "ON CONFLICT (trigger_type, trigger_id, variant_id, location_id) DO NOTHING",
                    tenantId, UUID.randomUUID(), variantId, v.locationId(), triggerId,
                    mapper.createObjectNode().put("piece_id", pieceId).put("delta", 1).put("via", "inspection").toString(),
                    PieceShopifyRules.SHOPIFY_RESTOCKED,
                    "Already restocked in Shopify by a refund (" + shopifyUnits + " unit(s) restocked, "
                        + tracedUnits + " counted by Traced before this one) — nothing sent", orderId);
                log.info("Back to good +1 skipped — the merchant already restocked this unit through a Shopify refund " +
                         "(piece={} order={} variant={})", pieceId, orderId, variantId);
                return null;
            }
        }
        Long id = insertPieceClaim(tenantId, pieceId, v, 1, "damaged_restore_increment", triggerId);
        if (orderId != null) {
            jdbc.update("UPDATE shopify_inventory_adjustments SET source_order_id = ? " +
                "WHERE tenant_id = ? AND trigger_type = 'damaged_restore_increment' AND trigger_id = ?",
                orderId, tenantId, triggerId);
        }
        return id;
    }

    /**
     * The 2026-10-10 Lookup-adjust repair (D10 — LookupAdjustRepairService, two named pieces only): a
     * NEW departure claim under a repair key, decided by the same countedAtMain rule as a live
     * adjustment. In the caller's transaction; returns the claim id to send, or null.
     */
    Long claimRepairDeparture(UUID tenantId, String triggerType, String pieceId, String triggerId) {
        return claimPieceDeparture(tenantId, triggerType, pieceId, triggerId);
    }

    /**
     * READ — the named Shopify inventory states of these variants at the tenant's main warehouse
     * (the repair's dry run). Keyed by variant id; empty when the store or location isn't usable.
     */
    Map<UUID, Map<String, Integer>> shopifyStates(UUID tenantId, List<UUID> variantIds, List<String> names) {
        Map<UUID, Map<String, Integer>> out = new java.util.LinkedHashMap<>();
        UUID mainId = jdbc.query("SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment = true",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null, tenantId);
        if (mainId == null) return out;
        Map<String, UUID> byItem = new java.util.LinkedHashMap<>();
        Preconditions last = null;
        for (UUID variantId : variantIds) {
            Preconditions p = resolvePreconditions(tenantId, variantId, mainId, "repair_read", variantId.toString());
            if (p.error() != null) throw new IllegalStateException("Shopify read not possible: " + p.error());
            byItem.put(p.shopifyInventoryItemId(), variantId);
            last = p;
        }
        if (last == null) return out;
        Map<String, Map<String, Integer>> states = shopify.fetchStateQuantities(last.shopDomain(), last.token(),
            last.shopifyLocationId(), List.copyOf(byItem.keySet()), names);
        states.forEach((item, q) -> out.put(byItem.get(item), q));
        return out;
    }

    /** Sends one piece claim after its transaction committed (registered via {@link #afterCommit}). */
    @Async
    public CompletableFuture<Void> pushPieceClaim(UUID tenantId, long claimId) {
        TenantContext.runAs(tenantId, () -> {
            try {
                pushPieceClaimNow(tenantId, claimId, false);
            } catch (Exception e) {
                log.error("Shopify piece sync push failed unexpectedly claim={}", claimId, e);
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    // ── Trigger 4: a piece found in a stock take (+1) ───────────────────────────

    /**
     * Called once stock-take finalize's lost → available transition has committed
     * ({@link #afterCommit}) — only for a piece whose earlier stock-take write-off was pushed to
     * Shopify (StockTakeReconciliationService decides; see foundIncrementEligible there). +1 at the
     * Traced location through the same claim path as every increment (approved 2026-10-01 as the
     * fourth increment trigger). trigger_id = piece + session, so a piece found again in a later
     * count claims its own row.
     */
    @Async
    public CompletableFuture<Void> onStockTakeFound(UUID tenantId, String pieceId, UUID sessionId, UUID locationId) {
        TenantContext.runAs(tenantId, () -> {
            try {
                UUID variantId = resolveVariantForPiece(pieceId);
                if (variantId == null) {
                    log.warn("Shopify inventory sync: piece not found piece={}", pieceId);
                    return;
                }
                applyIncrementAdjustment(UUID.randomUUID(), variantId, locationId, 1,
                    "stock_take_found", pieceId + ":" + sessionId, "correction");
            } catch (Exception e) {
                log.error("Shopify inventory sync failed: trigger=stock_take_found piece={}", pieceId, e);
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    // ── Trigger: a piece back at the main warehouse from a transfer (+1) — Issue 2 ──

    /**
     * Called once TransferService.reconcileScanBack()'s "came back good" transition has committed:
     * the piece is available at the main warehouse again. +1 there through the existing increment
     * path (trigger 'transfer_return', key piece_id:transfer_id) — ONLY when its departure left
     * Shopify's count (TransferShopifySync.RETURN_COUNTED_SQL: the outbound transfer's decrement was
     * pushed for it, or it left before the initial seed). Mode 'leave', a skipped / failed / ambiguous
     * send → Shopify still counts it → no write.
     */
    @Async
    public CompletableFuture<Void> onTransferReturn(UUID tenantId, String pieceId, UUID transferId, UUID locationId) {
        TenantContext.runAs(tenantId, () -> {
            try {
                processTransferReturn(pieceId, transferId, locationId);
            } catch (Exception e) {
                log.error("Shopify inventory sync failed: trigger=transfer_return piece={}", pieceId, e);
            }
        });
        return CompletableFuture.completedFuture(null);
    }

    private void processTransferReturn(String pieceId, UUID transferId, UUID locationId) {
        UUID tenantId = TenantContext.require();
        // The transfer that took the piece OUT of the main warehouse: this round trip itself, or —
        // for a bring-back — the latest permanent move that relocated it.
        Boolean counted = tx.execute(st -> jdbc.query(
            "SELECT " + TransferShopifySync.RETURN_COUNTED_SQL + " AS counted " +
            "FROM transfers t_in " +
            "JOIN transfer_pieces tp_out ON tp_out.piece_id = ? AND tp_out.tenant_id = t_in.tenant_id " +
            "JOIN transfers t_out ON t_out.id = tp_out.transfer_id " +
            "WHERE t_in.id = ? AND t_in.tenant_id = ? " +
            "  AND ((t_in.transfer_mode = 'round_trip' AND t_out.id = t_in.id) " +
            "    OR (t_in.transfer_mode = 'relocate_return' AND t_out.transfer_mode = 'relocate_out' " +
            "        AND tp_out.outcome = 'relocated' AND t_out.created_at <= t_in.created_at)) " +
            "  AND (tp_out.from_location_id IS NULL OR tp_out.from_location_id = ?) " +
            "ORDER BY t_out.created_at DESC, t_out.id DESC LIMIT 1",
            rs -> rs.next() && rs.getBoolean("counted"),
            pieceId, transferId, tenantId, locationId));
        if (!Boolean.TRUE.equals(counted)) {
            log.info("Transfer return +1 not needed — Shopify still counts piece {} (transfer {})", pieceId, transferId);
            return;
        }
        UUID variantId = resolveVariantForPiece(pieceId);
        if (variantId == null) return;
        applyIncrementAdjustment(UUID.randomUUID(), variantId, locationId, 1,
            "transfer_return", pieceId + ":" + transferId, "movement_received");
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

    // ── Piece sync engine ────────────────────────────────────────────────────

    /** Writes a piece claim — 'queued' when the verdict says send, 'skipped' + reason otherwise.
     *  ON CONFLICT DO NOTHING: a repeat of the same trigger never claims twice. */
    private Long insertPieceClaim(UUID tenantId, String pieceId, PieceShopifyRules.Verdict v, int delta,
                                  String triggerType, String triggerId) {
        UUID variantId = jdbc.queryForObject(
            "SELECT variant_id FROM pieces WHERE id = ? AND tenant_id = ?", UUID.class, pieceId, tenantId);
        if (isReviewFixtureVariant(tenantId, variantId, triggerType, triggerId)) return null;
        ObjectNode payload = mapper.createObjectNode().put("piece_id", pieceId).put("delta", delta);
        if (delta == 0) payload.put("moveQuantity", 1);
        if (v.via() != null) payload.put("via", v.via());
        List<Long> ids = jdbc.query(
            "INSERT INTO shopify_inventory_adjustments " +
            "(tenant_id, batch_id, variant_id, location_id, delta, trigger_type, trigger_id, payload, status, skip_reason) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) " +
            "ON CONFLICT (trigger_type, trigger_id, variant_id, location_id) DO NOTHING RETURNING id",
            (rs, i) -> rs.getLong(1),
            tenantId, UUID.randomUUID(), variantId, v.locationId(), delta, triggerType, triggerId,
            payload.toString(), v.write() ? "queued" : "skipped", v.skipReason());
        if (ids.isEmpty()) {
            log.debug("Shopify piece sync: {} {} already claimed", triggerType, triggerId);
            return null;
        }
        if (!v.write()) {
            log.info("Shopify piece sync skipped: trigger={} triggerId={} reason={}", triggerType, triggerId, v.skipReason());
            return null;
        }
        return ids.get(0);
    }

    /**
     * ONE attempt for one piece claim: queued / failed → pending (a conditional UPDATE — the
     * one-sender guard; failed only while attempts remain, unless {@code manual}), preconditions,
     * the call, the outcome — classified exactly like the transfer push (TransferShopifySync):
     *   applied | failed (definite — ShopifyException, or never sent) | failed_ambiguous (no confirmed
     *   answer — ShopifyAmbiguousException or anything unexpected after the call started; NEVER sent
     *   again, by the sweep or by hand). Legacy claims (before piece_sync_cutoff()) are never sent.
     */
    public void pushPieceClaimNow(UUID tenantId, long claimId, boolean manual) {
        Map<String, Object> c = tx.execute(st -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "UPDATE shopify_inventory_adjustments sia SET status = 'pending', send_started_at = now(), " +
                "    attempt_count = attempt_count + 1, first_attempt_at = COALESCE(first_attempt_at, now()), " +
                "    last_attempt_at = now() " +
                "WHERE sia.id = ? AND sia.tenant_id = ? AND sia.trigger_type IN " + PieceShopifyRules.PIECE_TRIGGERS_SQL +
                "  AND " + PieceShopifyRules.LIVE_SQL +
                "  AND (sia.status = 'queued' OR (sia.status = 'failed' AND (sia.attempt_count < ? OR ?))) " +
                "RETURNING trigger_type, trigger_id, variant_id, location_id, attempt_count",
                claimId, tenantId, PieceShopifyRules.MAX_ATTEMPTS, manual);
            return rows.isEmpty() ? null : rows.get(0);
        });
        if (c == null) return;   // sent / being sent / ambiguous / exhausted / legacy
        String triggerType = (String) c.get("trigger_type");
        String triggerId   = (String) c.get("trigger_id");
        UUID variantId     = (UUID) c.get("variant_id");
        UUID locationId    = (UUID) c.get("location_id");
        int attempt        = ((Number) c.get("attempt_count")).intValue();
        String pieceId     = triggerId.split(":", 2)[0];
        if (!PieceShopifyRules.PIECE_TRIGGERS.contains(triggerType)) return;

        Preconditions p;
        try {
            p = resolvePreconditions(tenantId, variantId, locationId, triggerType, triggerId);
        } catch (RuntimeException e) {
            markPieceResult(tenantId, claimId, "failed", "Not sent: " + messageOf(e), "never_sent", null, null);
            return;
        }
        if (p.error() != null) {
            markPieceResult(tenantId, claimId, "failed", "Not sent: " + p.error(), "never_sent",
                p.shopifyInventoryItemId(), p.shopifyLocationId());
            return;
        }
        // The claim key for the first attempt; after a definite rejection (nothing applied) a fresh key
        // per attempt, so Shopify never replays the rejected answer (IncrementRecoveryRules.retryKey).
        String key = attempt <= 1
            ? ShopifyGateway.idempotencyKey(tenantId, triggerType, triggerId, variantId, locationId)
            : IncrementRecoveryRules.retryKey(tenantId, triggerType, triggerId, variantId, locationId, attempt);
        String ref = "traced://piece/" + pieceId;

        boolean increment = "hold_exit".equals(triggerType) || "piece_write_off_return".equals(triggerType)
            || "damaged_restore_increment".equals(triggerType);
        if (increment) {
            // Lazy activation (no quantity) — if it fails, the +1 was never sent: definite.
            try {
                shopify.activateInventoryItem(p.shopDomain(), p.token(), p.shopifyInventoryItemId(), p.shopifyLocationId(),
                    ShopifyCatalogActivationService.activationKey(tenantId, variantId, p.shopifyLocationId()));
            } catch (Exception e) {
                markPieceResult(tenantId, claimId, "failed", "Not sent — activation at the Traced location failed: "
                    + messageOf(e), "never_sent", p.shopifyInventoryItemId(), p.shopifyLocationId());
                return;
            }
        }
        try {
            switch (triggerType) {
                case "void_correction" -> shopify.pushVoidCorrection(p.shopDomain(), p.token(),
                    p.shopifyInventoryItemId(), p.shopifyLocationId(), -1, ref, key);
                case "hold_enter" -> shopify.pushHoldEnter(p.shopDomain(), p.token(),
                    p.shopifyInventoryItemId(), p.shopifyLocationId(), -1, ref, key);
                case "damage_move" -> shopify.moveAvailableToDamaged(p.shopDomain(), p.token(),
                    p.shopifyInventoryItemId(), p.shopifyLocationId(), 1, "damaged", ref, key);
                case "piece_write_off" -> shopify.pushPieceWriteOff(p.shopDomain(), p.token(),
                    p.shopifyInventoryItemId(), p.shopifyLocationId(), -1, ref, key);
                case "damage_restore" -> shopify.moveDamagedToAvailable(p.shopDomain(), p.token(),
                    p.shopifyInventoryItemId(), p.shopifyLocationId(), 1, "correction", ref, key);
                case "hold_exit", "piece_write_off_return", "damaged_restore_increment" ->
                    shopify.pushPieceIncrement(p.shopDomain(), p.token(),
                        p.shopifyInventoryItemId(), p.shopifyLocationId(), 1, "correction", ref, key);
                default -> throw new IllegalArgumentException("no Shopify call for piece trigger " + triggerType);
            }
            markPieceResult(tenantId, claimId, "applied", null, null, p.shopifyInventoryItemId(), p.shopifyLocationId());
        } catch (ShopifyAmbiguousException e) {
            markPieceResult(tenantId, claimId, "failed_ambiguous", messageOf(e), "ambiguous",
                p.shopifyInventoryItemId(), p.shopifyLocationId());
            log.error("Shopify piece sync AMBIGUOUS trigger={} triggerId={} — verify in Shopify, NOT re-sending: {}",
                triggerType, triggerId, messageOf(e));
        } catch (ShopifyException e) {
            markPieceResult(tenantId, claimId, "failed", messageOf(e), "rejected",
                p.shopifyInventoryItemId(), p.shopifyLocationId());
        } catch (IllegalArgumentException e) {
            markPieceResult(tenantId, claimId, "failed", "Not sent: " + messageOf(e), "never_sent",
                p.shopifyInventoryItemId(), p.shopifyLocationId());
        } catch (RuntimeException e) {
            markPieceResult(tenantId, claimId, "failed_ambiguous", e.getClass().getSimpleName() + ": " + messageOf(e),
                "ambiguous", p.shopifyInventoryItemId(), p.shopifyLocationId());
        }
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** The outcome of one attempt. A failure is recorded only over 'pending' (the seed may have
     *  superseded the claim mid-flight); a success is always recorded — it is the truth. */
    private void markPieceResult(UUID tenantId, long claimId, String status, String error, String failureClass,
                                 String shopifyInventoryItemId, String shopifyLocationId) {
        Integer n = tx.execute(st -> jdbc.update(
            "UPDATE shopify_inventory_adjustments SET status = ?, error = ?, failure_class = ?, " +
            "    shopify_inventory_item_id = COALESCE(?, shopify_inventory_item_id), " +
            "    shopify_location_id = COALESCE(?, shopify_location_id), " +
            "    applied_at = CASE WHEN ? = 'applied' THEN now() ELSE applied_at END " +
            "WHERE id = ? AND tenant_id = ? AND (status = 'pending' OR ? = 'applied')",
            status, error, failureClass, shopifyInventoryItemId, shopifyLocationId, status,
            claimId, tenantId, status));
        if (!"applied".equals(status)) {
            log.warn("Shopify piece sync recorded as {}: claim={} error={}", status, claimId, error);
        } else if (n != null && n > 0) {
            log.info("Shopify piece sync applied: claim={}", claimId);
        }
    }

    /**
     * The piece-sync sweep (PieceShopifySweepJob, every 10 min; tests call it directly): a claim
     * 'pending' for 15+ minutes may have reached Shopify → failed_ambiguous (never re-sent); then
     * sends this tenant's queued claims (a crash before the after-commit push) and re-sends definite
     * failures while attempts remain. Legacy claims (before piece_sync_cutoff()) are never touched.
     */
    public int sweepPieceClaims(UUID tenantId) {
        tx.execute(st -> jdbc.update(
            "UPDATE shopify_inventory_adjustments sia SET status = 'failed_ambiguous', failure_class = 'ambiguous', " +
            "    error = 'Send started but never confirmed — verify in Shopify' " +
            "WHERE sia.tenant_id = ? AND sia.status = 'pending' AND sia.trigger_type IN " + PieceShopifyRules.PIECE_TRIGGERS_SQL +
            "  AND " + PieceShopifyRules.LIVE_SQL + " AND sia.send_started_at < now() - interval '15 minutes'",
            tenantId));
        List<Long> due = tx.execute(st -> jdbc.queryForList(
            "SELECT sia.id FROM shopify_inventory_adjustments sia " +
            "WHERE sia.tenant_id = ? AND sia.trigger_type IN " + PieceShopifyRules.PIECE_TRIGGERS_SQL +
            "  AND " + PieceShopifyRules.LIVE_SQL +
            "  AND ((sia.status = 'queued' AND sia.created_at < now() - interval '1 minute') " +
            "    OR (sia.status = 'failed' AND sia.attempt_count < ?)) " +
            "ORDER BY sia.created_at, sia.id LIMIT 50", Long.class, tenantId, PieceShopifyRules.MAX_ATTEMPTS));
        if (due == null) return 0;
        for (Long id : due) pushPieceClaimNow(tenantId, id, false);
        return due.size();
    }

    // ── Manual repush (exceptions center) ────────────────────────────────────

    /**
     * A person re-sends ONE failed claim after seeing the void_hold_sync_failed alert. Piece claims
     * (PieceShopifyRules.PIECE_TRIGGERS): only a DEFINITE failure ('failed') of a live claim is ever
     * re-sent — 'failed_ambiguous' is refused (Shopify may already hold it; a person checks Shopify),
     * and a legacy claim (before piece sync existed) is reconciled by hand. exchange_dispatch keeps its
     * Step 5a path (same claim key → same idempotency key).
     *
     * @throws ResponseStatusException 400 unknown trigger type; 404 no such claim; 409 not re-sendable
     */
    public void repushFailedVoidOrHold(String triggerType, String triggerId) {
        UUID tenantId = TenantContext.require();
        boolean piece = PieceShopifyRules.PIECE_TRIGGERS.contains(triggerType);
        if (!piece && !"exchange_dispatch".equals(triggerType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "triggerType must be a piece sync trigger or exchange_dispatch");
        }

        record FailedRow(long id, UUID locationId, String status, boolean live) {}
        FailedRow row = tx.execute(status ->
            jdbc.query(
                "SELECT id, location_id, status, created_at >= piece_sync_cutoff() AS live " +
                "FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = ? AND trigger_id = ?",
                rs -> rs.next() ? new FailedRow(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3),
                                                 rs.getBoolean(4)) : null,
                tenantId, triggerType, triggerId));
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No adjustment found for " + triggerType + "/" + triggerId);
        }
        if ("failed_ambiguous".equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Shopify never confirmed this update — it may already be applied. Check Shopify and fix it there; " +
                "it is never re-sent.");
        }
        if (!"failed".equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Adjustment is not in 'failed' status (current: " + row.status() + ")");
        }
        if (!piece) {
            // Same claim key → same deterministic idempotency key as the failed attempt.
            processExchangeDispatch(triggerId, row.locationId());
            return;
        }
        if (!row.live()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Recorded before automatic piece sync — reconcile it in Shopify by hand");
        }
        pushPieceClaimNow(tenantId, row.id(), true);
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

    /**
     * Trigger 2 (+1 per restock). Three things beyond a plain increment (2026-10-08):
     *   - key: trigger_id = piece_id + ':' + restock_event_id (from the piece's newest 'restocked'
     *     event), so a piece returned and restocked again claims its own row — like hold_enter.
     *     A piece with no such event (a claim made before V151) keeps the bare piece_id.
     *   - a non-fulfillment restock location is recorded ('skipped_not_fulfillment_location',
     *     WARN), never a silent, row-less skip.
     *   - the double-count guard: while the order's Shopify refunds restocked more units of this
     *     variant than Traced restocks already counted for the order, this unit was restocked by
     *     the merchant in Shopify — record 'skipped_shopify_restocked', send nothing. Decided and
     *     claimed in ONE transaction under a per (order, variant) advisory lock, so two concurrent
     *     restocks of the same order can't both read the same count.
     */
    private void processReturnInspection(String pieceId, UUID locationId) {
        UUID tenantId = TenantContext.require();
        UUID variantId = resolveVariantForPiece(pieceId);
        if (variantId == null) {
            log.warn("Shopify inventory sync: piece not found piece={}", pieceId);
            return;
        }
        RestockEvent ev = tx.execute(st -> jdbc.query(
            "SELECT metadata->>'restock_event_id' AS restock_event_id, metadata->>'order_id' AS order_id " +
            "FROM piece_events WHERE piece_id = ? AND tenant_id = ? AND event_type = 'restocked' " +
            "ORDER BY occurred_at DESC, id DESC LIMIT 1",
            rs -> rs.next() ? new RestockEvent(rs.getString("restock_event_id"), rs.getString("order_id")) : null,
            pieceId, tenantId));
        String restockEventId = ev == null ? null : ev.restockEventId();
        UUID orderId = ev == null || ev.orderId() == null ? null : UUID.fromString(ev.orderId());
        String triggerId = restockEventId != null ? pieceId + ":" + restockEventId : pieceId;
        UUID batchId = UUID.randomUUID();

        if (!isFulfillmentLocation(tenantId, locationId, "return_inspection", triggerId)) {
            log.warn("Shopify restock +1 NOT sent: location {} is not the main warehouse (piece={} triggerId={})",
                     locationId, pieceId, triggerId);
            if (locationId != null) {
                recordRestockSkip(tenantId, batchId, variantId, locationId, triggerId, orderId,
                                  "skipped_not_fulfillment_location",
                                  "Restocked into a location that isn't the main warehouse — nothing sent to Shopify");
            }
            return;
        }

        ObjectNode payload = mapper.createObjectNode().put("reason", "restock").put("delta", 1);
        if (orderId != null) payload.put("order_id", orderId.toString());
        String outcome = tx.execute(st -> {
            if (orderId != null) {
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    rs -> null, "restock:" + tenantId + ":" + orderId + ":" + variantId);
                Map<String, Object> counts = jdbc.queryForMap(
                    "SELECT COALESCE((SELECT shopify_refund_restocked_units(o.raw, v.external_id) " +
                    "                 FROM orders o, variants v " +
                    "                 WHERE o.id = ? AND o.tenant_id = ? AND v.id = ? AND v.tenant_id = ?), 0) AS shopify_units, " +
                    "       (SELECT COUNT(*) FROM shopify_inventory_adjustments " +
                    "        WHERE tenant_id = ? AND trigger_type IN ('return_inspection', 'damaged_restore_increment') " +
                    "          AND source_order_id = ? AND variant_id = ? AND trigger_id <> ?) AS traced_units",
                    orderId, tenantId, variantId, tenantId, tenantId, orderId, variantId, triggerId);
                long shopifyUnits = ((Number) counts.get("shopify_units")).longValue();
                long tracedUnits  = ((Number) counts.get("traced_units")).longValue();
                if (shopifyUnits > tracedUnits) {
                    insertRestockSkipRow(tenantId, batchId, variantId, locationId, triggerId, orderId,
                        "skipped_shopify_restocked",
                        "Already restocked in Shopify by a refund (" + shopifyUnits + " unit(s) restocked, "
                            + tracedUnits + " counted by Traced before this one) — nothing sent");
                    return "skipped";
                }
            }
            return claimInCurrentTx(tenantId, batchId, variantId, locationId, 1,
                                    "return_inspection", triggerId, payload, orderId) ? "claimed" : "not_claimed";
        });

        if ("skipped".equals(outcome)) {
            log.info("Shopify restock +1 skipped — the merchant already restocked this unit through a Shopify refund " +
                     "(piece={} order={} variant={})", pieceId, orderId, variantId);
            return;
        }
        if (!"claimed".equals(outcome)) {
            log.debug("Shopify inventory: restock already claimed, skipping duplicate call triggerId={}", triggerId);
            return;
        }
        attemptIncrement(tenantId, variantId, locationId, 1, "return_inspection", triggerId, "restock",
            ShopifyGateway.idempotencyKey(tenantId, "return_inspection", triggerId, variantId, locationId), null, false);
    }

    private record RestockEvent(String restockEventId, String orderId) {}

    private void recordRestockSkip(UUID tenantId, UUID batchId, UUID variantId, UUID locationId,
                                   String triggerId, UUID orderId, String status, String reason) {
        tx.execute(st -> {
            insertRestockSkipRow(tenantId, batchId, variantId, locationId, triggerId, orderId, status, reason);
            return null;
        });
    }

    /** A recorded, terminal "nothing was sent" restock claim. ON CONFLICT DO NOTHING — a repeat of
     *  the same restock trigger never rewrites an existing row. */
    private void insertRestockSkipRow(UUID tenantId, UUID batchId, UUID variantId, UUID locationId,
                                      String triggerId, UUID orderId, String status, String reason) {
        jdbc.update(
            "INSERT INTO shopify_inventory_adjustments " +
            "(tenant_id, batch_id, variant_id, location_id, delta, trigger_type, trigger_id, payload, status, error, source_order_id) " +
            "VALUES (?, ?, ?, ?, 1, 'return_inspection', ?, ?::jsonb, ?, ?, ?) " +
            "ON CONFLICT (trigger_type, trigger_id, variant_id, location_id) DO NOTHING",
            tenantId, batchId, variantId, locationId, triggerId,
            mapper.createObjectNode().put("reason", "restock").put("delta", 1).toString(),
            status, reason, orderId);
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
            // rejected — and, only through the confirmed manual repush, an expired ambiguous claim:
            // a NEW key, fresh baseline, the original delta.
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
            case "stock_take_found" -> "correction";
            case "transfer_return" -> "movement_received";
            default -> "restock";
        };
    }

    /**
     * Manual repush of one failed increment claim (receiving_session / return_inspection / hold_exit).
     * Legacy claims are NOT repushable (409 LEGACY_NOT_REPUSHABLE) — they are reconciled by hand and
     * cleared by resolving the exception. Requires confirmOld when the claim's first attempt is more
     * than 24 h old, or when it is ambiguous past the identical-resend window (the person has checked
     * Shopify). 404 no such claim, 409 not failed / legacy / needs confirmation / setup problem.
     */
    public void repushFailedIncrement(String triggerType, String triggerId, UUID variantId, boolean confirmOld) {
        UUID tenantId = TenantContext.require();
        if (!IncrementRecoveryRules.INCREMENT_TRIGGERS.contains(triggerType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "triggerType must be receiving_session, return_inspection, hold_exit, stock_take_found or transfer_return");
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
            Integer superseded = tx.execute(st -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = ? " +
                "AND trigger_id = ? AND variant_id = ? AND status = '" + SUPERSEDED_BY_SEED + "'",
                Integer.class, tenantId, triggerType, triggerId, variantId));
            if (superseded != null && superseded > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "SUPERSEDED_BY_SEED: the stock seed already pushed this variant's current quantity to Shopify " +
                    "— sending this update again would count those units twice");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Adjustment is not in 'failed' status");
        }
        FailedClaim c = rows.get(0);
        if (c.legacy()) {
            // The pre-recovery backlog is reconciled by hand, never replayed: Shopify may have been
            // corrected since, and the seed pushes CURRENT stock — a replay would double count.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "LEGACY_NOT_REPUSHABLE: this update failed before automatic recovery existed — reconcile it " +
                "manually in Shopify and resolve the exception; it is never replayed");
        }
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
                c.changeFromQuantity(), c.sentKey(), c.attemptCount(), true, c.olderThanConfirm(), false)
            : c;
        if (!retryClaim(tenantId, toSend, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Adjustment is already being retried");
        }
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
        return Boolean.TRUE.equals(tx.execute(status -> claimInCurrentTx(
            tenantId, batchId, variantId, locationId, delta, triggerType, triggerId, initialPayload, null)));
    }

    /** {@link #claim}'s body, on the caller's transaction — the restock path runs it under its
     *  per (order, variant) lock, in the same transaction as the double-count guard's read.
     *  sourceOrderId is set on the INSERT only (a reclaim never changes it). */
    private boolean claimInCurrentTx(UUID tenantId, UUID batchId, UUID variantId, UUID locationId, int delta,
                                     String triggerType, String triggerId, ObjectNode initialPayload,
                                     UUID sourceOrderId) {
        // Review mode S4 (V130): a simulated-courier tenant's own fixture variants (seeded, not from
        // Shopify — external_id isn't a gid://shopify/ id) are never synced: no claim row, so no
        // Shopify call and no failed claim / alert. Its real Shopify variants (the reviewer's store)
        // sync exactly as for any tenant. Callers treat "not claimed" as "nothing to do".
        if (isReviewFixtureVariant(tenantId, variantId, triggerType, triggerId)) return false;

        String payloadJsonTmp;
        try { payloadJsonTmp = mapper.writeValueAsString(initialPayload); }
        catch (Exception e) { payloadJsonTmp = "{}"; }

        int rows = jdbc.update(
            "INSERT INTO shopify_inventory_adjustments " +
            "(tenant_id, batch_id, variant_id, location_id, delta, trigger_type, trigger_id, payload, status, source_order_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'pending', ?) " +
            "ON CONFLICT (trigger_type, trigger_id, variant_id, location_id) DO UPDATE " +
            "  SET status = 'pending', batch_id = EXCLUDED.batch_id, payload = EXCLUDED.payload " +
            "  WHERE shopify_inventory_adjustments.status = 'failed'",
            tenantId, batchId, variantId, locationId, delta, triggerType, triggerId, payloadJsonTmp, sourceOrderId);

        return rows > 0;
    }

    private boolean isReviewFixtureVariant(UUID tenantId, UUID variantId, String triggerType, String triggerId) {
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM tenant_courier_simulation WHERE tenant_id = ?) " +
                "   AND EXISTS (SELECT 1 FROM variants WHERE id = ? AND tenant_id = ? " +
                "                 AND external_id NOT LIKE 'gid://shopify/%')",
                Boolean.class, tenantId, variantId, tenantId))) {
            log.info("Review mode: skipped Shopify inventory claim for non-Shopify variant {} " +
                     "(trigger={} triggerId={})", variantId, triggerType, triggerId);
            return true;
        }
        return false;
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
        Boolean recorded = tx.execute(txStatus -> {
            // The seed may have superseded this claim while its attempt was in flight (it was 'pending').
            // A late failure never brings it back to 'failed' (that would re-arm a retry the seed made
            // redundant); a late success is recorded as the truth, loudly, because Shopify may now count
            // those units twice.
            Map<String, Object> current = jdbc.query(
                "SELECT status, delta FROM shopify_inventory_adjustments " +
                "WHERE tenant_id = ? AND trigger_type = ? AND trigger_id = ? AND variant_id = ? AND location_id = ? " +
                "FOR UPDATE",
                rs -> rs.next() ? Map.<String, Object>of("status", rs.getString(1), "delta", rs.getInt(2)) : null,
                tenantId, triggerType, triggerId, variantId, locationId);
            if (current != null && SUPERSEDED_BY_SEED.equals(current.get("status"))) {
                if (failed) {
                    log.info("Shopify inventory: late failure ignored — claim already superseded by the seed " +
                             "trigger={} triggerId={} variant={}", triggerType, triggerId, variantId);
                    return false;
                }
                log.warn("Shopify inventory: applied after superseded by seed — possible double count of {} units " +
                         "for variant {} (trigger={} triggerId={} location={})",
                         current.get("delta"), variantId, triggerType, triggerId, locationId);
            }
            return jdbc.update(
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
                tenantId, triggerType, triggerId, variantId, locationId) > 0;
        });

        if (failed && Boolean.TRUE.equals(recorded)) {
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
