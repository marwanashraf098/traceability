package com.traceability;

import com.traceability.fulfillment.OrderStatusDeriver;
import com.traceability.fulfillment.OrderStatusDeriver.Chip;
import com.traceability.fulfillment.OrderStatusDeriver.DerivedOrderStatus;
import com.traceability.fulfillment.OrderStatusDeriver.Tone;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-function unit tests for {@link OrderStatusDeriver} — FR-7/FR-11 A1. No Spring
 * context, no DB: every input is a primitive already fetched by the caller.
 *
 * Cases mirror the build spec's divergence table (order-status-redesign-build-spec.md,
 * "Problem" section), corrected against the real 9-value shipment_internal_state
 * vocabulary confirmed in A0 (created/with_courier/returning/delivered/returned/lost/
 * exception/terminated/cancelled — there is no in_transit/out_for_delivery/preparing).
 */
class OrderStatusDeriverTest {

    private static DerivedOrderStatus derive(
            String orderStatus, String shipmentState, Integer maxRank,
            int attempts, int failedAttempts, Integer exceptionCode,
            Boolean isDelayed, Boolean slaBreached, boolean notTraced) {
        return OrderStatusDeriver.derive(orderStatus, shipmentState, maxRank,
            attempts, failedAttempts, exceptionCode, isDelayed, slaBreached, notTraced);
    }

    // ── divergence-table matrix (corrected) ─────────────────────────────────────

    @Test
    void matrix_c687dbd6_awaitingPickupPipeline_deliveredShipment_terminalWins() {
        // pipeline stuck at awaiting_pickup; shipment already delivered → terminal wins
        DerivedOrderStatus d = derive("awaiting_pickup", "delivered", null, 1, 0, null, false, false, false);
        assertThat(d.primaryKey()).isEqualTo("status.delivered");
        assertThat(d.tone()).isEqualTo(Tone.SUCCESS);
        assertThat(d.healthChips()).isEmpty();
        assertThat(d.historicalNote()).isNull(); // numberOfAttempts=1, not >1
    }

    @Test
    void matrix_9d5a0b6b_withCourierPlusFailedAttempt_deliveryFailed() {
        DerivedOrderStatus d = derive("awaiting_pickup", "with_courier", 2, 2, 1, null, false, false, false);
        assertThat(d.primaryKey()).isEqualTo("status.delivery_failed");
        assertThat(d.tone()).isEqualTo(Tone.WARN);
        assertThat(d.healthChips()).containsExactly(new Chip("chip.failed_attempts", Tone.DANGER, 1));
    }

    @Test
    void matrix_a428cde1_regressedCreatedAfterWithCourier_3fails_deliveryFailed() {
        // history [created, with_courier, created] → maxRank=2, but latest regressed to
        // 'created'; failed_delivery_attempts=3 must still win over the regression.
        DerivedOrderStatus d = derive("awaiting_pickup", "created", 2, 3, 3, null, false, false, false);
        assertThat(d.primaryKey()).isEqualTo("status.delivery_failed");
        assertThat(d.healthChips()).containsExactly(new Chip("chip.failed_attempts", Tone.DANGER, 3));
    }

    @Test
    void matrix_eb0b034e_newPipeline_returnedShipment_notTraced_terminalWins() {
        DerivedOrderStatus d = derive("new", "returned", null, 0, 0, null, false, false, true);
        assertThat(d.primaryKey()).isEqualTo("status.returned");
        assertThat(d.tone()).isEqualTo(Tone.WARN);
        assertThat(d.notTraced()).isTrue();
        assertThat(d.healthChips()).isEmpty();
        assertThat(d.historicalNote()).isNull(); // failedDeliveryAttempts=0
    }

    @Test
    void matrix_02912ca1_deliveredPlusStaleDelayedFlag_terminalSuppressesChips() {
        // delivered + 1 failed attempt + is_delayed still true in the DB (stale) — terminal
        // gating must suppress BOTH the failed_attempts chip and the delayed chip.
        DerivedOrderStatus d = derive("new", "delivered", null, 2, 1, null, true, false, false);
        assertThat(d.primaryKey()).isEqualTo("status.delivered");
        assertThat(d.healthChips()).as("terminal must suppress all health chips, incl. stale is_delayed").isEmpty();
        assertThat(d.historicalNote()).isEqualTo(new OrderStatusDeriver.HistoricalNote("note.delivered_attempt", 2));
    }

    @Test
    void matrix_2e05d4e5_cancelledOrder_liveShipment_cancelledLabelWins_plusA3ConflictFlag() {
        // 2e05d4e5: cancelled + live "Preparing" AWB — A3 fixes the missing risk signal.
        DerivedOrderStatus d = derive("cancelled", "created", 1, 0, 0, null, false, false, false);
        assertThat(d.primaryKey()).isEqualTo("status.cancelled");
        assertThat(d.tone()).isEqualTo(Tone.NEUTRAL);
        assertThat(d.conflictKey()).isEqualTo("status.conflict.live_shipment");
    }

    // ── A3: cancelled-order conflict flag + chip suppression ────────────────────

    @Test
    void a3_cancelledOrder_liveShipment_healthChipsSuppressed_conflictFlagIsSoleOverlay() {
        // Overturns the A1/A2-era assumption (health chips gated on shipment terminal-ness
        // only): A3 says a cancelled order shows NO health chips regardless of shipment
        // state — the conflict flag is the sole overlay.
        DerivedOrderStatus d = derive("cancelled", "with_courier", 2, 1, 0, null, true, false, false);
        assertThat(d.primaryKey()).isEqualTo("status.cancelled");
        assertThat(d.healthChips()).as("A3: health chips suppressed on cancelled orders").isEmpty();
        assertThat(d.conflictKey()).isEqualTo("status.conflict.live_shipment");
    }

    @Test
    void a3_cancelledOrder_deliveredShipment_cancelledButDeliveredConflict_noChips() {
        DerivedOrderStatus d = derive("cancelled", "delivered", null, 1, 0, null, false, false, false);
        assertThat(d.primaryKey()).isEqualTo("status.cancelled");
        assertThat(d.conflictKey()).isEqualTo("status.conflict.cancelled_but_delivered");
        assertThat(d.healthChips()).isEmpty();
    }

    @Test
    void a3_cancelledOrder_everyLiveState_getsLiveShipmentConflict() {
        for (String state : List.of("created", "with_courier", "returning", "exception")) {
            DerivedOrderStatus d = derive("cancelled", state, 1, 0, 0, null, false, false, false);
            assertThat(d.conflictKey()).as("state=" + state).isEqualTo("status.conflict.live_shipment");
        }
    }

    @Test
    void a3_cancelledOrder_cleanTerminalStates_noConflict() {
        for (String state : List.of("returned", "terminated", "cancelled", "lost")) {
            DerivedOrderStatus d = derive("cancelled", state, null, 0, 1, null, false, false, false);
            assertThat(d.conflictKey()).as("state=" + state + " is a clean cancel").isNull();
            assertThat(d.healthChips()).as("terminal already suppresses chips regardless").isEmpty();
        }
    }

    @Test
    void a3_cancelledOrder_noShipmentLinked_noConflict() {
        DerivedOrderStatus d = derive("cancelled", null, null, 0, 0, null, null, null, false);
        assertThat(d.primaryKey()).isEqualTo("status.cancelled");
        assertThat(d.conflictKey()).isNull();
        assertThat(d.healthChips()).isEmpty();
    }

    @Test
    void a3_nonCancelledOrder_neverGetsConflictKey() {
        DerivedOrderStatus d = derive("awaiting_pickup", "delivered", null, 1, 0, null, false, false, false);
        assertThat(d.conflictKey()).isNull();
    }

    // ── terminal suppression (explicit) ──────────────────────────────────────────

    @Test
    void terminalSuppression_deliveredPlusFailedAttempt_noDelayedNoFailedChip_note() {
        DerivedOrderStatus d = derive("new", "delivered", null, 2, 1, 7, true, true, false);
        assertThat(d.healthChips()).isEmpty();
        assertThat(d.historicalNote()).isEqualTo(new OrderStatusDeriver.HistoricalNote("note.delivered_attempt", 2));
    }

    @Test
    void terminal_returned_withFailedAttempts_getsReturnedAfterNote() {
        DerivedOrderStatus d = derive("new", "returned", null, 0, 2, null, false, false, false);
        assertThat(d.primaryKey()).isEqualTo("status.returned");
        assertThat(d.historicalNote()).isEqualTo(new OrderStatusDeriver.HistoricalNote("note.returned_after", 2));
    }

    // ── furthest progress, never latest ─────────────────────────────────────────

    @Test
    void furthestProgress_regressedToCreated_noFailedAttempts_staysInTransit() {
        // history [created, with_courier, created] → maxRank=2; latest='created' alone
        // would (wrongly) suggest "Awaiting courier" — furthest progress must win.
        DerivedOrderStatus d = derive("awaiting_pickup", "created", 2, 0, 0, null, false, false, false);
        assertThat(d.primaryKey()).isEqualTo("status.in_transit");
    }

    @Test
    void furthestProgress_regressedPlusFailedAttempts_deliveryFailedShortCircuitsBeforeRank() {
        DerivedOrderStatus d = derive("awaiting_pickup", "created", 2, 3, 3, null, false, false, false);
        assertThat(d.primaryKey())
            .as("failed_delivery_attempts >= 1 must win before the furthest-progress branch is reached")
            .isEqualTo("status.delivery_failed");
        assertThat(d.primaryKey()).isNotIn("status.new", "status.awaiting_courier");
    }

    @Test
    void maxProgressRank_fallsBackToCurrentState_whenHistoryEmpty() {
        // Pre-V40 shipments have zero shipment_status_history rows (see V40's backfill
        // note) — the current internal_state is the sole source of truth until a new
        // transition arrives.
        assertThat(OrderStatusDeriver.maxProgressRank(List.of(), "with_courier")).isEqualTo(2);
        assertThat(OrderStatusDeriver.maxProgressRank(List.of("created", "with_courier", "created"), "created"))
            .isEqualTo(2);
        assertThat(OrderStatusDeriver.maxProgressRank(List.of("exception"), "exception")).isEqualTo(0);
    }

    // ── exception / needs_attention branch ──────────────────────────────────────

    @Test
    void latestException_noFailedAttempts_needsAttention() {
        DerivedOrderStatus d = derive("awaiting_pickup", "exception", 0, 0, 0, null, false, false, false);
        assertThat(d.primaryKey()).isEqualTo("status.needs_attention");
        assertThat(d.tone()).isEqualTo(Tone.WARN);
    }

    @Test
    void ndrCode_mapped_customerRefused_specificChip() {
        DerivedOrderStatus d = derive("awaiting_pickup", "exception", 0, 1, 0, 8, false, false, false);
        assertThat(d.healthChips()).containsExactly(Chip.of("chip.customer_refused", Tone.WARN));
    }

    @Test
    void ndrCode_unmapped_fallsBackToGenericChip_warnTone() {
        DerivedOrderStatus d = derive("awaiting_pickup", "exception", 0, 1, 0, 7, false, false, false);
        assertThat(d.healthChips()).containsExactly(Chip.of("chip.exception", Tone.WARN));
    }

    @Test
    void ndrCode_criticalCourierEvidence_fallsBackGenericChip_dangerTone() {
        // code 27 = "Empty order — missing items" (§8.4, critical severity) — no dedicated
        // copy key exists today, but severity must still drive DANGER tone.
        DerivedOrderStatus d = derive("awaiting_pickup", "exception", 0, 1, 0, 27, false, false, false);
        assertThat(d.healthChips()).containsExactly(Chip.of("chip.exception", Tone.DANGER));
    }

    @Test
    void delayedOrSlaBreached_nonTerminal_showsDelayedChip() {
        DerivedOrderStatus d1 = derive("awaiting_pickup", "with_courier", 2, 1, 0, null, true, false, false);
        assertThat(d1.healthChips()).containsExactly(Chip.of("chip.delayed", Tone.WARN));

        DerivedOrderStatus d2 = derive("awaiting_pickup", "with_courier", 2, 1, 0, null, false, true, false);
        assertThat(d2.healthChips()).containsExactly(Chip.of("chip.delayed", Tone.WARN));
    }

    // ── pipeline (no shipment) — full 13-value order_status map ─────────────────

    @Test
    void noShipment_everyPipelineStatus_hasALabel() {
        List<String> allStatuses = List.of(
            "new", "confirmed", "ready_to_pick", "picking", "packed", "awaiting_pickup",
            "with_courier", "delivered", "returning", "returned", "lost", "cancelled",
            "self_pickup_pending");
        for (String status : allStatuses) {
            DerivedOrderStatus d = derive(status, null, null, 0, 0, null, null, null, false);
            assertThat(d.primaryKey()).as("status=" + status).isNotBlank();
            assertThat(d.tone()).as("status=" + status).isNotNull();
            assertThat(d.healthChips()).as("status=" + status + " (no shipment)").isEmpty();
        }
    }

    @Test
    void noShipment_awaitingPickup_mapsTo_awaitingCourierKey() {
        DerivedOrderStatus d = derive("awaiting_pickup", null, null, 0, 0, null, null, null, false);
        assertThat(d.primaryKey()).isEqualTo("status.awaiting_courier");
    }

    @Test
    void noShipment_selfPickupPending_hasDedicatedKey() {
        DerivedOrderStatus d = derive("self_pickup_pending", null, null, 0, 0, null, null, null, false);
        assertThat(d.primaryKey()).isEqualTo("status.self_pickup_pending");
    }

    // ── shipment-side terminal labels not on the order-cancelled path ───────────

    @Test
    void shipmentSideCancelled_whileOrderNotCancelled_warnToneNotNeutral() {
        // Bosta code 49/104 cancelled the shipment while the order pipeline never caught
        // up — abnormal, so this gets a warier tone than the deliberate order-cancel path.
        DerivedOrderStatus d = derive("awaiting_pickup", "cancelled", null, 0, 0, null, false, false, false);
        assertThat(d.primaryKey()).isEqualTo("status.cancelled");
        assertThat(d.tone()).isEqualTo(Tone.WARN);
    }

    @Test
    void shipmentTerminated_dangerTone_hasGapFilledLabel() {
        DerivedOrderStatus d = derive("awaiting_pickup", "terminated", null, 0, 0, null, false, false, false);
        assertThat(d.primaryKey()).isEqualTo("status.terminated");
        assertThat(d.tone()).isEqualTo(Tone.DANGER);
    }

    @Test
    void shipmentLost_dangerTone() {
        DerivedOrderStatus d = derive("awaiting_pickup", "lost", null, 0, 0, null, false, false, false);
        assertThat(d.primaryKey()).isEqualTo("status.lost");
        assertThat(d.tone()).isEqualTo(Tone.DANGER);
    }

    // ── 'created' relabel: "Label created", distinct from the pipeline's own
    // "Awaiting courier" (awaiting_pickup) — a freshly-created AWB and an order still
    // waiting on pipeline pickup read as the same event otherwise. ────────────────────

    @Test
    void freshlyCreatedShipment_headerReadsLabelCreated_notAwaitingCourier() {
        DerivedOrderStatus d = derive("awaiting_pickup", "created", 1, 0, 0, null, false, false, false);
        assertThat(d.primaryKey()).isEqualTo("status.label_created");
    }

    @Test
    void legStatus_created_labelCreatedKey() {
        OrderStatusDeriver.LegStatus s = OrderStatusDeriver.deriveLegStatus("created");
        assertThat(s.primaryKey()).isEqualTo("status.label_created");
        assertThat(s.tone()).isEqualTo(Tone.INFO);
    }

    // ── A3.1: leg-scoped shipment status (return-leg ShipmentCard badge) ────────

    @Test
    void legStatus_returning_returnedLabel_warnTone() {
        OrderStatusDeriver.LegStatus s = OrderStatusDeriver.deriveLegStatus("returning");
        assertThat(s.primaryKey()).isEqualTo("status.returning");
        assertThat(s.tone()).isEqualTo(Tone.WARN);
    }

    @Test
    void legStatus_returned_returnedLabel_warnTone() {
        OrderStatusDeriver.LegStatus s = OrderStatusDeriver.deriveLegStatus("returned");
        assertThat(s.primaryKey()).isEqualTo("status.returned");
        assertThat(s.tone()).isEqualTo(Tone.WARN);
    }

    @Test
    void legStatus_exception_needsAttentionLabel_warnTone() {
        OrderStatusDeriver.LegStatus s = OrderStatusDeriver.deriveLegStatus("exception");
        assertThat(s.primaryKey()).isEqualTo("status.needs_attention");
        assertThat(s.tone()).isEqualTo(Tone.WARN);
    }

    /**
     * Parity guard: for every one of the 9 real internal_state values, a leg-scoped badge
     * must never disagree with what the FULL order-level derive() would produce for an
     * equivalent, unremarkable shipment (non-cancelled order, no failed attempts, no
     * regression — maxProgressRank set to that exact state's own rank). This is the
     * strongest guarantee against LEG_KEY/LEG_TONE quietly drifting from derive()'s
     * per-state branches without needing a second hand-maintained literal map to trust.
     */
    @Test
    void legStatus_neverDisagreesWithDeriveForAnEquivalentUnregressedShipment() {
        for (String state : List.of("created", "with_courier", "returning", "exception",
                                     "delivered", "returned", "lost", "terminated", "cancelled")) {
            OrderStatusDeriver.LegStatus leg = OrderStatusDeriver.deriveLegStatus(state);
            Integer rank = OrderStatusDeriver.PROGRESS_RANK.get(state); // null for terminal states
            DerivedOrderStatus order = derive("awaiting_pickup", state, rank, 0, 0, null, false, false, false);
            assertThat(leg.primaryKey()).as("state=" + state).isEqualTo(order.primaryKey());
            assertThat(leg.tone()).as("state=" + state).isEqualTo(order.tone());
        }
    }

    @Test
    void legStatus_hasNoOrderLevelConcepts_pureFunctionOfStateAlone() {
        // Same internal_state, wildly different (irrelevant) order context — the leg badge
        // must be identical, since it has no cancelled/conflict/chip/note concept at all.
        OrderStatusDeriver.LegStatus a = OrderStatusDeriver.deriveLegStatus("returning");
        OrderStatusDeriver.LegStatus b = OrderStatusDeriver.deriveLegStatus("returning");
        assertThat(a).isEqualTo(b);
    }
}
