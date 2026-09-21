package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * FR-EXCHANGE Phase 3/4 — per-leg state interpreter for {@code type.code=30} (EXCHANGE)
 * deliveries. Bypasses the generic {@link BostaStateMapper} for these deliveries because
 * a bare {@code state.code} cannot disambiguate legs (Trap B, FR-EXCHANGE v2 spec §2):
 * code 41 means "out for delivery" on the forward leg and "out for return" on the inbound
 * leg of the SAME delivery.
 *
 * Confirmed live (Step 1 diagnosis §2a, Snouts 184907356/877468285): {@code raw.state.value}
 * is generic across the WHOLE code-46 family — always the string "Returned to business"
 * for exchanged_returned AND out_for_return alike. It never disambiguates and must never
 * be read for this purpose. The granular per-milestone strings live in {@code raw.timeline}
 * instead: a FIXED TEMPLATE of every milestone for this delivery type, most entries
 * {@code done:false} with no {@code date} at all until reached, {@code done:true} entries
 * carrying an ISO {@code date} once completed. Array order is NOT chronological (confirmed:
 * an earlier-completed milestone can sit after a later one in the array — Bosta reuses the
 * template's fixed ordering, not arrival order). So this interpreter takes the
 * {@code done:true} entry with the MAX {@code date} — the most-recently-COMPLETED
 * milestone — never the array's last element and never a {@code done:false} placeholder
 * (which has no date to compare and must be excluded, not treated as "unknown → skip").
 *
 * FAIL-SAFE BY DEFAULT: {@link #interpretForwardLeg} only recognizes the entries in
 * {@link #FORWARD_LEG_STATES}. Any other value — including forward-delivered-but-failed
 * (whole-exchange RTO, no confirmed vocabulary in the data seen so far) — is deliberately
 * UNMAPPED, pending confirmed Bosta vocabulary. Callers MUST treat an empty result as "no
 * transition" — never guess, never write {@code returned}/{@code returned_at} on the
 * forward leg for an unmapped value.
 *
 * {@link #interpretReturnLeg} is a structural hook only — it always returns empty this
 * pass. {@code exchanges.status} terminal transitions (return_pending, reconciled, ...)
 * are HELD pending the same confirmed vocabulary; wiring the hook now (rather than adding
 * it later) means the call site in {@link com.traceability.inventory.ExchangeIngestService}
 * never needs to change shape when the vocabulary lands — only this map gets entries.
 */
@Component
public class ExchangeStateInterpreter {

    // timeline[].value (lowercased, trimmed) → forward-leg MappedState.
    //
    // out_for_exchange (code 41): courier heading to the door to perform the doorstep
    // swap — same shape as the generic mapper's code-41/SEND "Out for delivery" row
    // (bosta_state_mappings: with_courier, no piece transition) and as this map's own
    // pre-existing "in_transit" entry. Not yet delivered.
    //
    // exchanged_returned / out_for_return (both code 46): confirmed live (2a) as the
    // SUCCESS path — deliveryTime/pickedUpTime populated, no exhausted-attempts/RTO
    // marker. exchanged_returned = the doorstep swap completed (new item handed over,
    // old item collected). out_for_return = the already-collected old item now moving
    // on from the hub — a LATER milestone in the SAME successful exchange, not a step
    // backward. Either way the forward leg's own job (deliver the new item) is done:
    // internal_state=delivered, piece_status_after=delivered — mirrors the generic
    // mapper's code-45 "Delivered" row exactly. The old item's inbound journey is
    // return-leg business (Part C), never decided here — no piece is ever marked
    // returned by this method.
    private static final Map<String, BostaStateMapper.MappedState> FORWARD_LEG_STATES = Map.of(
        "new",                BostaStateMapper.MappedState.of("created", null),
        "picked_up",          BostaStateMapper.MappedState.of("with_courier", "with_courier"),
        "in_transit",         BostaStateMapper.MappedState.of("with_courier", null),
        "out_for_exchange",   BostaStateMapper.MappedState.of("with_courier", null),
        "exchanged_returned", BostaStateMapper.MappedState.of("delivered", "delivered"),
        "out_for_return",     BostaStateMapper.MappedState.of("delivered", "delivered")
    );

    /**
     * @return the forward-leg internal state to apply, or empty if the delivery's most
     *         recently completed {@code timeline} milestone is missing or not one of the
     *         confirmed values — the fail-safe case the caller must route to "unmapped
     *         exchange state" handling.
     */
    public Optional<BostaStateMapper.MappedState> interpretForwardLeg(JsonNode raw) {
        String value = latestCompletedTimelineValue(raw);
        if (value == null) return Optional.empty();
        return Optional.ofNullable(FORWARD_LEG_STATES.get(value));
    }

    /**
     * Structural hook for deriving {@code exchanges.status} from return-leg timeline
     * progress. Always empty this pass — HELD pending confirmed vocabulary for
     * {@code out_for_return} / {@code exchanged_returned} (Step 0 §0c).
     */
    public Optional<String> interpretReturnLeg(JsonNode raw) {
        return Optional.empty();
    }

    /**
     * The {@code value} of the {@code done:true} timeline entry with the latest
     * {@code date} — see class javadoc for why this, and not array position or the
     * generic {@code state.value}, is the correct signal. {@code done:false} entries
     * (Bosta's not-yet-reached template placeholders) carry no date and are excluded,
     * not treated as ties.
     */
    private String latestCompletedTimelineValue(JsonNode raw) {
        if (raw == null) return null;
        JsonNode timeline = raw.path("timeline");
        if (!timeline.isArray()) return null;

        Instant latestDate  = null;
        String  latestValue = null;
        for (JsonNode entry : timeline) {
            if (!entry.path("done").asBoolean(false)) continue;
            String dateStr = entry.path("date").asText(null);
            if (dateStr == null) continue;
            Instant date;
            try {
                date = Instant.parse(dateStr);
            } catch (Exception e) {
                continue;
            }
            if (latestDate == null || date.isAfter(latestDate)) {
                latestDate = date;
                latestValue = entry.path("value").asText(null);
            }
        }
        return latestValue == null ? null : latestValue.trim().toLowerCase(Locale.ROOT);
    }
}
