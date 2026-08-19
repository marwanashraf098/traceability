package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * FR-EXCHANGE Phase 3/4 — per-leg state interpreter for {@code type.code=30} (EXCHANGE)
 * deliveries. Bypasses the generic {@link BostaStateMapper} for these deliveries because
 * a bare {@code state.code} cannot disambiguate legs (Trap B, FR-EXCHANGE v2 spec §2):
 * code 41 means "out for delivery" on the forward leg and "out for return" on the inbound
 * leg of the SAME delivery. This interpreter keys off {@code raw.state.value} — the
 * string Bosta returns alongside {@code state.code} (confirmed shape:
 * {@code {code:N, value:"..."}}, same node {@link BostaHttpGateway} already reads
 * {@code state.code} from) — never the bare numeric code.
 *
 * FAIL-SAFE BY DEFAULT: {@link #interpretForwardLeg} only recognizes the entries in
 * {@link #FORWARD_LEG_STATES} — conservative, low-stakes early-lifecycle values chosen by
 * analogy to the generic mapper's own type-independent early codes (10/21/24/30 in
 * {@code bosta_state_mappings}, none of which are leg-ambiguous). Any other value —
 * including forward-delivered, whole-exchange-RTO, {@code exchanged_returned}, and
 * {@code out_for_return} — is deliberately UNMAPPED this pass, pending confirmed Bosta
 * vocabulary from the 4 backfilled fleet deliveries (see Step 0 §0c). Callers MUST treat
 * an empty result as "no transition" — never guess, never write
 * {@code returned}/{@code returned_at} on the forward leg for an unmapped value.
 *
 * {@link #interpretReturnLeg} is a structural hook only — it always returns empty this
 * pass. {@code exchanges.status} terminal transitions (return_pending, reconciled, ...)
 * are HELD pending the same confirmed vocabulary; wiring the hook now (rather than adding
 * it later) means the call site in {@link com.traceability.inventory.ExchangeIngestService}
 * never needs to change shape when the vocabulary lands — only this map gets entries.
 */
@Component
public class ExchangeStateInterpreter {

    // raw.state.value (lowercased, trimmed) → forward-leg MappedState. Confirmed
    // low-stakes only — see class javadoc. Deliberately does NOT include any value that
    // could mean "delivered" or "returned" for either leg.
    private static final Map<String, BostaStateMapper.MappedState> FORWARD_LEG_STATES = Map.of(
        "new",        BostaStateMapper.MappedState.of("created", null),
        "picked_up",  BostaStateMapper.MappedState.of("with_courier", "with_courier"),
        "in_transit", BostaStateMapper.MappedState.of("with_courier", null)
    );

    /**
     * @return the forward-leg internal state to apply, or empty if {@code raw.state.value}
     *         is missing or not one of the confirmed low-stakes values — the fail-safe
     *         case the caller must route to "unmapped exchange state" handling.
     */
    public Optional<BostaStateMapper.MappedState> interpretForwardLeg(JsonNode raw) {
        String value = stateValue(raw);
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

    private String stateValue(JsonNode raw) {
        if (raw == null) return null;
        String v = raw.path("state").path("value").asText(null);
        return v == null ? null : v.trim().toLowerCase(Locale.ROOT);
    }
}
