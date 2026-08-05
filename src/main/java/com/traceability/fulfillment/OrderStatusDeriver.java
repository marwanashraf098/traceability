package com.traceability.fulfillment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FR-7 / FR-11 — the ONE place display order status is decided. Both
 * {@link OrderController#list()} and {@link OrderController#detail(java.util.UUID)} must
 * route through {@link #derive}; no other code reads raw pipeline/shipment status for
 * display. Guarded the same way {@code PICKABLE_ORDERS_FILTER} is shared in
 * {@code FulfillService} — a single source future readers are pointed back to, not two
 * call sites that can quietly drift apart.
 *
 * Keyed on the REAL 9-value {@code shipment_internal_state} enum (created, with_courier,
 * delivered, returning, returned, lost, exception, terminated, cancelled) — confirmed
 * against V1__baseline.sql and the §8.3 seed (V2__bosta_seed.sql). There is no
 * in_transit/out_for_delivery/preparing/return_in_transit value; Bosta's granular codes
 * collapse into this set before they ever reach {@code internal_state}, so the ladder
 * below is deliberately coarser than the raw Bosta vocabulary.
 *
 * Pure function: every input is a primitive already fetched by the caller (no DB access
 * here). {@code maxProgressRank} in particular MUST be computed the same way by every
 * caller — see {@link #maxProgressRank} for the Java-side reducer used by the detail path,
 * and the mirrored SQL CASE in {@code OrderController.list()}'s LATERAL for the list path.
 * {@code OrderStatusListDetailParityTest} asserts the two never disagree.
 */
public final class OrderStatusDeriver {

    private OrderStatusDeriver() {}

    public enum Tone { NEUTRAL, INFO, SUCCESS, WARN, DANGER }

    public record Chip(String key, Tone tone, Integer count) {
        public static Chip of(String key, Tone tone) { return new Chip(key, tone, null); }
    }

    public record HistoricalNote(String key, int count) {}

    public record DerivedOrderStatus(
        String primaryKey,
        Tone tone,
        List<Chip> healthChips,
        HistoricalNote historicalNote,
        boolean notTraced
    ) {}

    // ── progress_rank ladder ─────────────────────────────────────────────────
    // Only the 3 non-terminal, non-exception internal_state values carry a rank.
    // 'exception' contributes 0 (never wins maxRank over real progress).
    // Terminal states are handled by the `terminal` branch directly, not via rank.
    public static final Map<String, Integer> PROGRESS_RANK = Map.of(
        "created",      1,
        "with_courier", 2,
        "returning",    3,
        "exception",    0
    );

    public static final Set<String> TERMINAL_STATES =
        Set.of("delivered", "returned", "lost", "terminated", "cancelled");

    /**
     * Java-side reducer for the detail path: MAX(progress_rank) over the shipment's
     * fetched history states, falling back to the shipment's OWN current internal_state
     * when history is empty. That fallback is not defensive filler — V40's migration
     * note is explicit that pre-V40 shipments have zero history rows and the current
     * internal_state is the sole source of truth for those until a new transition
     * arrives, so a naive MAX-over-history-only would silently rank every legacy
     * shipment at 0.
     */
    public static Integer maxProgressRank(List<String> historyStates, String currentInternalState) {
        Integer max = null;
        for (String s : historyStates) {
            Integer rank = PROGRESS_RANK.get(s);
            if (rank != null && (max == null || rank > max)) max = rank;
        }
        if (max == null) max = PROGRESS_RANK.get(currentInternalState);
        return max;
    }

    // ── pipeline (no shipment) labels — full 13-value order_status map ─────────
    private static final Map<String, String> PIPELINE_KEY = Map.ofEntries(
        Map.entry("new",                  "status.new"),
        Map.entry("confirmed",            "status.confirmed"),
        Map.entry("ready_to_pick",        "status.ready_to_pick"),
        Map.entry("picking",              "status.picking"),
        Map.entry("packed",               "status.packed"),
        Map.entry("awaiting_pickup",      "status.awaiting_courier"),
        Map.entry("with_courier",         "status.in_transit"),
        Map.entry("delivered",            "status.delivered"),
        Map.entry("returning",            "status.returning"),
        Map.entry("returned",             "status.returned"),
        Map.entry("lost",                 "status.lost"),
        Map.entry("cancelled",            "status.cancelled"),
        Map.entry("self_pickup_pending",  "status.self_pickup_pending")
    );

    private static final Map<String, Tone> PIPELINE_TONE = Map.ofEntries(
        Map.entry("new",                  Tone.NEUTRAL),
        Map.entry("confirmed",            Tone.NEUTRAL),
        Map.entry("ready_to_pick",        Tone.INFO),
        Map.entry("picking",              Tone.INFO),
        Map.entry("packed",               Tone.INFO),
        Map.entry("awaiting_pickup",      Tone.INFO),
        Map.entry("with_courier",         Tone.INFO),
        Map.entry("delivered",            Tone.SUCCESS),
        Map.entry("returning",            Tone.WARN),
        Map.entry("returned",             Tone.WARN),
        Map.entry("lost",                 Tone.DANGER),
        Map.entry("cancelled",            Tone.NEUTRAL),
        Map.entry("self_pickup_pending",  Tone.INFO)
    );

    // ── shipment-linked terminal labels ─────────────────────────────────────
    private static final Map<String, String> TERMINAL_KEY = Map.of(
        "delivered",  "status.delivered",
        "returned",   "status.returned",
        "lost",       "status.lost",
        "terminated", "status.terminated",
        "cancelled",  "status.cancelled"
    );

    private static final Map<String, Tone> TERMINAL_TONE = Map.of(
        "delivered",  Tone.SUCCESS,
        "returned",   Tone.WARN,
        "lost",       Tone.DANGER,
        "terminated", Tone.DANGER,
        // Shipment-side 'cancelled' (Bosta code 49/104) while order.status is NOT
        // cancelled is abnormal — Bosta dropped the parcel without Traced knowing —
        // so it gets a warier tone than the deliberate order.status==cancelled branch.
        "cancelled",  Tone.WARN
    );

    // ── furthest-progress (non-terminal, shipment-linked) labels ───────────
    private static final Map<Integer, String> PROGRESS_KEY = Map.of(
        1, "status.awaiting_courier",
        2, "status.in_transit",
        3, "status.returning"
    );

    private static final Map<Integer, Tone> PROGRESS_TONE = Map.of(
        1, Tone.INFO,
        2, Tone.INFO,
        3, Tone.WARN
    );

    // ── §8.4 NDR exception_code → chip map ──────────────────────────────────
    // Only codes 1/3/8 (+ their return-side "postponed" synonyms 21/22) have
    // dedicated A5 copy today. Every other seeded NDR code (V2__bosta_seed.sql)
    // falls back to a generic chip, DANGER-toned for the 5 courier-side-evidence
    // codes (26-30: damaged/empty/incomplete/doesn't-belong/opened) and WARN for
    // the rest. This is a deliberate simplification, not full 1:1 §8.4 coverage —
    // flagged for review rather than inventing ~15 more copy keys unasked.
    private static final Map<Integer, String> NDR_CHIP_KEY = Map.of(
        1,  "chip.not_at_address",
        3,  "chip.postponed",
        8,  "chip.customer_refused",
        21, "chip.postponed",
        22, "chip.postponed"
    );

    private static final Set<Integer> NDR_CRITICAL_CODES = Set.of(26, 27, 28, 29, 30);

    // ── the derivation ───────────────────────────────────────────────────────

    public static DerivedOrderStatus derive(
            String orderStatus,
            String shipmentInternalState,   // null = no forward shipment linked
            Integer maxProgressRank,
            int numberOfAttempts,
            int failedDeliveryAttempts,
            Integer exceptionCode,
            Boolean isDelayed,
            Boolean slaBreached,
            boolean notTraced) {

        boolean shipmentLinked = shipmentInternalState != null;
        boolean terminal = shipmentLinked && TERMINAL_STATES.contains(shipmentInternalState);

        String primaryKey;
        Tone tone;

        if ("cancelled".equals(orderStatus)) {
            // A3 (conflict flag) is a later step; this is the label only.
            primaryKey = "status.cancelled";
            tone = Tone.NEUTRAL;
        } else if (shipmentLinked) {
            if (terminal) {
                primaryKey = TERMINAL_KEY.get(shipmentInternalState);
                tone = TERMINAL_TONE.get(shipmentInternalState);
            } else if (failedDeliveryAttempts >= 1) {
                primaryKey = "status.delivery_failed";
                tone = Tone.WARN;
            } else if ("exception".equals(shipmentInternalState)) {
                primaryKey = "status.needs_attention";
                tone = Tone.WARN;
            } else {
                int rank = maxProgressRank != null ? maxProgressRank : 1;
                primaryKey = PROGRESS_KEY.getOrDefault(rank, "status.awaiting_courier");
                tone = PROGRESS_TONE.getOrDefault(rank, Tone.INFO);
            }
        } else {
            primaryKey = PIPELINE_KEY.getOrDefault(orderStatus, "status.new");
            tone = PIPELINE_TONE.getOrDefault(orderStatus, Tone.NEUTRAL);
        }

        List<Chip> chips = new ArrayList<>();
        HistoricalNote note = null;

        if (shipmentLinked && !terminal) {
            if (failedDeliveryAttempts >= 1) {
                chips.add(new Chip("chip.failed_attempts", Tone.DANGER, failedDeliveryAttempts));
            }
            if (exceptionCode != null) {
                String chipKey = NDR_CHIP_KEY.get(exceptionCode);
                Tone chipTone = NDR_CRITICAL_CODES.contains(exceptionCode) ? Tone.DANGER : Tone.WARN;
                chips.add(Chip.of(chipKey != null ? chipKey : "chip.exception", chipTone));
            }
            if (Boolean.TRUE.equals(isDelayed) || Boolean.TRUE.equals(slaBreached)) {
                chips.add(Chip.of("chip.delayed", Tone.WARN));
            }
        } else if (shipmentLinked && terminal) {
            if ("delivered".equals(shipmentInternalState) && numberOfAttempts > 1) {
                note = new HistoricalNote("note.delivered_attempt", numberOfAttempts);
            } else if ("returned".equals(shipmentInternalState) && failedDeliveryAttempts >= 1) {
                note = new HistoricalNote("note.returned_after", failedDeliveryAttempts);
            }
        }

        return new DerivedOrderStatus(primaryKey, tone, chips, note, notTraced);
    }
}
