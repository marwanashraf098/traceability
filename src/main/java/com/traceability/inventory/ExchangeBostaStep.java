package com.traceability.inventory;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * S3b follow-up (2026-11-22) — THE reading of a Bosta exchange (type 30) timeline for COMMIT time: which recognised step
 * is the furthest one Bosta reached, and whether Bosta moved the exchange past "Pickup requested" without any step Traced
 * recognises past it. Used by the advance-at-commit path (ExchangeStateInterpreter.interpretFurthestForwardLeg), by every
 * commit entry that isn't Traced's own booking (auto-map, map, map-items → {@link ExchangeBostaStepUnknownException}) and
 * by the multi-item catch-up (SKIP "unknown Bosta step"). The LIVE type-30 interpreter (latest completed step) is NOT this
 * rule and is unchanged.
 *
 * <p>Why: prod 2026-11-22, 14 open multi-item rows whose latest completed step is "exchanged" (code 23) or "rescheduled"
 * (code 47) — values the interpreter doesn't know — so the latest-step reading left the new replacement at 'created' and
 * pickable. Every one of them has picked_up → in_transit → out_for_exchange completed before it.
 *
 * <p>A completed step = a timeline entry with {@code done:true} and a parsable ISO {@code date} (the template's
 * not-yet-reached placeholders carry none), its {@code value} trimmed and lowercased.
 */
public final class ExchangeBostaStep {

    private ExchangeBostaStep() {}

    /** Bosta's "Pickup requested" step. */
    public static final String PICKUP_REQUESTED = "new";

    /** Bosta's state / timeline code for "Pickup requested". */
    public static final int PICKUP_REQUESTED_CODE = 10;

    /**
     * The recognised forward-leg steps in the order an exchange moves through them — the same vocabulary as
     * ExchangeStateInterpreter's map (its static check fails startup if the two ever differ).
     */
    public static final List<String> PROGRESS = List.of(
        PICKUP_REQUESTED, "picked_up", "in_transit", "out_for_exchange", "exchanged_returned", "out_for_return");

    /** The furthest recognised completed step (by {@link #PROGRESS} order, not date), or null when none. */
    public static String furthestRecognised(JsonNode raw) {
        int best = -1;
        for (JsonNode e : completed(raw)) {
            int i = PROGRESS.indexOf(value(e));
            if (i > best) best = i;
        }
        return best < 0 ? null : PROGRESS.get(best);
    }

    /**
     * True when Bosta's state isn't "Pickup requested" (code 10), its latest completed step is past "Pickup requested"
     * (a step with code 10 counts as "Pickup requested") and NO recognised step past it was completed —
     * Traced can't tell where the exchange is, so it must not create a replacement (it would be pickable).
     */
    public static boolean unknownPastPickup(JsonNode raw) {
        // Bosta's own state still "Pickup requested" (code 10) → not past pickup, whatever the timeline's text says.
        if (raw == null || raw.path("state").path("code").asInt(-1) == PICKUP_REQUESTED_CODE) return false;
        String latest = null;
        Instant latestAt = null;
        boolean recognisedPastPickup = false;
        for (JsonNode e : completed(raw)) {
            String v = value(e);
            Instant at = Instant.parse(e.path("date").asText());
            if (e.path("code").asInt(-1) == PICKUP_REQUESTED_CODE) v = PICKUP_REQUESTED;
            if (latestAt == null || at.isAfter(latestAt)) { latestAt = at; latest = v; }
            if (PROGRESS.contains(v) && !PICKUP_REQUESTED.equals(v)) recognisedPastPickup = true;
        }
        return latest != null && !PICKUP_REQUESTED.equals(latest) && !recognisedPastPickup;
    }

    private static List<JsonNode> completed(JsonNode raw) {
        if (raw == null || !raw.path("timeline").isArray()) return List.of();
        List<JsonNode> out = new java.util.ArrayList<>();
        for (JsonNode e : raw.path("timeline")) {
            if (!e.path("done").asBoolean(false) || value(e) == null) continue;
            String d = e.path("date").asText(null);
            if (d == null) continue;
            try {
                Instant.parse(d);
            } catch (Exception ex) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    private static String value(JsonNode e) {
        String v = e.path("value").asText(null);
        return v == null ? null : v.trim().toLowerCase(Locale.ROOT);
    }
}
