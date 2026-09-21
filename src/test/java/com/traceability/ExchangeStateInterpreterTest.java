package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaStateMapper;
import com.traceability.integrations.bosta.ExchangeStateInterpreter;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-EXCHANGE Step 2 Part A — unit tests for ExchangeStateInterpreter.interpretForwardLeg().
 * Pure unit test, no Spring context: the interpreter has no collaborators.
 *
 * Payload shapes are copied verbatim from the two real stuck Snouts exchange trackings
 * confirmed in Step 1 diagnosis §2a (184907356, 877468285) — both stuck at
 * internal_state='created' despite being forward-leg-complete, because the old code read
 * the generic raw.state.value ("Returned to business" for the whole code-46 family)
 * instead of the disambiguating raw.timeline[].value entries.
 *
 * (a) 184907356's real timeline → delivered (RED on pre-fix code: empty/no-transition,
 *     since raw.state.value alone is the generic "Returned to business" label).
 * (b) state.value="Returned to business" but timeline lacks the granular strings →
 *     still fail-safe unmapped — proves timeline is read, not the generic label.
 * (c) 877468285's real timeline (dates NOT in array order — out_for_return sits before
 *     exchanged_returned in the array despite a later date) → delivered.
 * (d) Early-stage real timeline (Jumi 4818277658 shape: only new/picked_up/in_transit
 *     done, out_for_exchange/out_for_return/exchanged_returned still done:false with no
 *     date) → with_courier, no piece transition — not a regression on the pre-existing
 *     low-stakes mappings.
 * (e) timeline entirely absent → fail-safe unmapped.
 */
class ExchangeStateInterpreterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ExchangeStateInterpreter interpreter = new ExchangeStateInterpreter();

    // ── (a) 184907356 real shape → delivered ─────────────────────────────────

    @Test
    void a_realTimeline184907356_mostRecentIsOutForReturn_mapsToDelivered() {
        ObjectNode raw = mapper.createObjectNode();
        raw.putObject("state").put("code", 46).put("value", "Returned to business");
        ArrayNode timeline = raw.putArray("timeline");
        doneEntry(timeline, 10, "new", "2026-08-15T11:36:34.368Z");
        doneEntry(timeline, 21, "picked_up", "2026-08-18T13:44:08.456Z");
        doneEntry(timeline, 30, "in_transit", "2026-08-20T14:06:58.136Z");
        doneEntry(timeline, 41, "out_for_exchange", "2026-08-19T05:41:59.465Z");
        doneEntry(timeline, 46, "out_for_return", "2026-08-23T07:35:59.939Z");
        doneEntry(timeline, 46, "exchanged_returned", "2026-08-22T13:44:13.236Z");

        Optional<BostaStateMapper.MappedState> result = interpreter.interpretForwardLeg(raw);

        assertThat(result).as("must resolve, not fail-safe-empty").isPresent();
        assertThat(result.get().shipmentInternalState()).isEqualTo("delivered");
        assertThat(result.get().pieceStatusAfter()).isEqualTo("delivered");
    }

    // ── (b) generic state.value alone must never disambiguate ────────────────

    @Test
    void b_genericStateValue_withoutGranularTimeline_staysFailSafeUnmapped() {
        ObjectNode raw = mapper.createObjectNode();
        raw.putObject("state").put("code", 46).put("value", "Returned to business");
        // timeline present but no recognized/dated value — proves state.value is never consulted.
        ArrayNode timeline = raw.putArray("timeline");
        pendingEntry(timeline, 46, "some_unrecognized_milestone");

        Optional<BostaStateMapper.MappedState> result = interpreter.interpretForwardLeg(raw);

        assertThat(result).as("generic label alone must never resolve a mapping").isEmpty();
    }

    // ── (c) 877468285 real shape, non-chronological array order → delivered ──

    @Test
    void c_realTimeline877468285_arrayOrderNotChronological_stillPicksLatestByDate() {
        ObjectNode raw = mapper.createObjectNode();
        raw.putObject("state").put("code", 46).put("value", "Returned to business");
        ArrayNode timeline = raw.putArray("timeline");
        doneEntry(timeline, 10, "new", "2026-08-14T23:50:54.252Z");
        doneEntry(timeline, 21, "picked_up", "2026-08-18T13:44:08.456Z");
        doneEntry(timeline, 30, "in_transit", "2026-08-30T17:34:23.688Z");
        doneEntry(timeline, 41, "out_for_exchange", "2026-08-19T08:10:11.051Z");
        // out_for_return (later date) sits BEFORE exchanged_returned (earlier date) in the
        // array — real Bosta shape confirmed in 2a. Must still resolve by date, not position.
        doneEntry(timeline, 46, "out_for_return", "2026-09-09T09:50:18.374Z");
        doneEntry(timeline, 46, "exchanged_returned", "2026-08-31T17:32:15.963Z");

        Optional<BostaStateMapper.MappedState> result = interpreter.interpretForwardLeg(raw);

        assertThat(result).isPresent();
        assertThat(result.get().shipmentInternalState()).isEqualTo("delivered");
        assertThat(result.get().pieceStatusAfter()).isEqualTo("delivered");
    }

    // ── (d) early-stage real shape (Jumi 4818277658) → with_courier, no regression ───

    @Test
    void d_earlyStageRealTimeline_undoneMilestonesExcluded_mapsToWithCourierNoPieceMove() {
        ObjectNode raw = mapper.createObjectNode();
        raw.putObject("state").put("code", 20).put("value", "Route Assigned");
        ArrayNode timeline = raw.putArray("timeline");
        doneEntry(timeline, 10, "new", "2026-09-16T16:30:14.723Z");
        doneEntry(timeline, 21, "picked_up", "2026-09-17T15:30:50.070Z");
        doneEntry(timeline, 30, "in_transit", "2026-09-20T15:26:23.863Z");
        doneEntry(timeline, 41, "out_for_exchange", "2026-09-19T06:48:15.164Z");
        // done:true but NO date — a Bosta template quirk seen live; must be excluded, not
        // treated as the latest just because done=true.
        ObjectNode outForReturnNoDate = timeline.addObject();
        outForReturnNoDate.put("code", 41).put("done", true).put("value", "out_for_return");
        pendingEntry(timeline, 46, "exchanged_returned");

        Optional<BostaStateMapper.MappedState> result = interpreter.interpretForwardLeg(raw);

        assertThat(result).isPresent();
        assertThat(result.get().shipmentInternalState()).isEqualTo("with_courier");
        assertThat(result.get().pieceStatusAfter())
            .as("in_transit does not itself move any piece").isNull();
    }

    // ── (e) timeline entirely absent → fail-safe unmapped ─────────────────────

    @Test
    void e_timelineAbsent_failSafeUnmapped() {
        ObjectNode raw = mapper.createObjectNode();
        raw.putObject("state").put("code", 46).put("value", "Returned to business");

        Optional<BostaStateMapper.MappedState> result = interpreter.interpretForwardLeg(raw);

        assertThat(result).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void doneEntry(ArrayNode timeline, int code, String value, String isoDate) {
        ObjectNode entry = timeline.addObject();
        entry.put("code", code).put("done", true).put("value", value).put("date", isoDate);
    }

    private void pendingEntry(ArrayNode timeline, int code, String value) {
        ObjectNode entry = timeline.addObject();
        entry.put("code", code).put("done", false).put("value", value);
    }
}
