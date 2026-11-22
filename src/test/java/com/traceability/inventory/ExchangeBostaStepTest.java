package com.traceability.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.bosta.ExchangeStateInterpreter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3b follow-up (2026-11-22) — the commit-time timeline reading on real prod timelines (open EXCHANGE_MULTI_ITEM rows):
 * the furthest recognised step, the "unknown past pickup" rule, and the live interpreter left as it was.
 */
class ExchangeBostaStepTest {

    static final ObjectMapper M = new ObjectMapper();
    final ExchangeStateInterpreter interpreter = new ExchangeStateInterpreter();

    static JsonNode raw(String timeline) throws Exception {
        return M.readTree("{\"timeline\":" + timeline + "}");
    }

    // Exact stored timelines of two open prod rows (read-only, 2026-11-22).
    static final String EXCHANGED = "[{\"code\":10,\"date\":\"2026-10-06T11:27:02.394Z\",\"done\":true,\"value\":\"new\"},"
        + "{\"code\":21,\"date\":\"2026-10-06T15:25:00.681Z\",\"done\":true,\"value\":\"picked_up\"},"
        + "{\"code\":30,\"date\":\"2026-10-07T03:13:56.587Z\",\"done\":true,\"value\":\"in_transit\"},"
        + "{\"code\":41,\"date\":\"2026-10-07T07:28:32.560Z\",\"desc\":\"1/3 attempts\",\"done\":true,\"value\":\"out_for_exchange\"},"
        + "{\"code\":23,\"date\":\"2026-10-07T09:27:02.643Z\",\"done\":true,\"value\":\"exchanged\"},"
        + "{\"code\":46,\"done\":false,\"value\":\"exchanged_returned\"}]";
    static final String RESCHEDULED = "[{\"code\":10,\"date\":\"2026-10-07T13:01:50.449Z\",\"done\":true,\"value\":\"new\"},"
        + "{\"code\":21,\"date\":\"2026-10-07T15:31:53.617Z\",\"done\":true,\"value\":\"picked_up\"},"
        + "{\"code\":30,\"date\":\"2026-10-08T04:45:11.583Z\",\"done\":true,\"value\":\"in_transit\"},"
        + "{\"code\":41,\"date\":\"2026-10-08T07:30:06.400Z\",\"desc\":\"1/3 attempts\",\"done\":true,\"value\":\"out_for_exchange\"},"
        + "{\"code\":47,\"date\":\"2026-10-08T14:24:28.639Z\",\"desc\":\"Retry delivery - the customer is not in the address.\",\"done\":true,\"value\":\"rescheduled\",\"exceptionCode\":1},"
        + "{\"code\":46,\"done\":false,\"value\":\"exchanged_returned\"}]";

    @Test
    void prodExchangedAndRescheduled_furthestIsOutForExchange_notUnknown_liveStillEmpty() throws Exception {
        for (String tl : new String[]{EXCHANGED, RESCHEDULED}) {
            JsonNode r = raw(tl);
            assertThat(ExchangeBostaStep.furthestRecognised(r)).isEqualTo("out_for_exchange");
            assertThat(ExchangeBostaStep.unknownPastPickup(r)).isFalse();
            assertThat(interpreter.interpretFurthestForwardLeg(r)).get()
                .satisfies(m -> assertThat(m.shipmentInternalState()).isEqualTo("with_courier"));
            assertThat(interpreter.interpretForwardLeg(r)).as("live path unchanged").isEmpty();
        }
    }

    @Test
    void unknownOnlyPastPickup_isUnknown_pickupRequestedAndEmptyAreNot() throws Exception {
        JsonNode unknownOnly = raw("[{\"date\":\"2026-10-06T11:27:02.394Z\",\"done\":true,\"value\":\"new\"},"
            + "{\"date\":\"2026-10-07T09:27:02.643Z\",\"done\":true,\"value\":\"exchanged\"},"
            + "{\"done\":false,\"value\":\"picked_up\"}]");
        assertThat(ExchangeBostaStep.unknownPastPickup(unknownOnly)).isTrue();
        assertThat(ExchangeBostaStep.furthestRecognised(unknownOnly)).isEqualTo("new");

        JsonNode pickupRequested = raw("[{\"date\":\"2026-10-06T11:27:02.394Z\",\"done\":true,\"value\":\"new\"},"
            + "{\"done\":false,\"value\":\"picked_up\"}]");
        assertThat(ExchangeBostaStep.unknownPastPickup(pickupRequested)).isFalse();
        assertThat(interpreter.interpretFurthestForwardLeg(pickupRequested)).get()
            .satisfies(m -> assertThat(m.shipmentInternalState()).isEqualTo("created"));

        // Bosta's state still "Pickup requested" (code 10): never unknown, whatever the timeline's text; a step coded 10 is
        // "Pickup requested" too.
        JsonNode stateTen = M.readTree("{\"state\":{\"code\":10},\"timeline\":[{\"date\":\"2026-10-22T09:00:00Z\","
            + "\"done\":true,\"value\":\"pickup_requested\"}]}");
        assertThat(ExchangeBostaStep.unknownPastPickup(stateTen)).isFalse();
        JsonNode codedTen = raw("[{\"code\":10,\"date\":\"2026-10-22T09:00:00Z\",\"done\":true,\"value\":\"pickup_requested\"}]");
        assertThat(ExchangeBostaStep.unknownPastPickup(codedTen)).isFalse();

        assertThat(ExchangeBostaStep.unknownPastPickup(raw("[]"))).isFalse();
        assertThat(ExchangeBostaStep.unknownPastPickup(null)).isFalse();
        assertThat(ExchangeBostaStep.furthestRecognised(null)).isNull();
    }

    @Test
    void furthestIsByProgressNotDate() throws Exception {
        // A re-route: out_for_exchange, then in_transit again later → the furthest is still out_for_exchange.
        JsonNode r = raw("[{\"date\":\"2026-10-01T10:00:00Z\",\"done\":true,\"value\":\"new\"},"
            + "{\"date\":\"2026-10-02T10:00:00Z\",\"done\":true,\"value\":\"out_for_exchange\"},"
            + "{\"date\":\"2026-10-03T10:00:00Z\",\"done\":true,\"value\":\"in_transit\"}]");
        assertThat(ExchangeBostaStep.furthestRecognised(r)).isEqualTo("out_for_exchange");
    }
}
