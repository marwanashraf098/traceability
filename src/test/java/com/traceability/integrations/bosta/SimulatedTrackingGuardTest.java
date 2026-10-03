package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Review mode S1 — a tracking number in the reserved simulated range (^777\d{10}$) is never sent
 * to Bosta: BostaHttpGateway refuses it before any HTTP request. The mock server has NO
 * expectations in sg1/sg2, so any request at all would fail the test with an AssertionError
 * instead of the BostaException asserted here; sg4 is the positive control (a real-looking
 * number does reach the server).
 */
class SimulatedTrackingGuardTest {

    private static final String BASE = "https://bosta.test";
    private static final String RESERVED = "7770000000001";

    private MockRestServiceServer server;
    private BostaHttpGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server  = MockRestServiceServer.bindTo(builder).build();
        gateway = new BostaHttpGateway(builder, new ObjectMapper(), BASE, "v0");
    }

    @Test
    void sg1_fetchDelivery_reserved_refusedWithNoHttpRequest() {
        assertThatThrownBy(() -> gateway.fetchDelivery("key", RESERVED))
            .isInstanceOf(BostaException.class)
            .hasMessageContaining("never sent to Bosta");
        server.verify();   // no request was made
    }

    @Test
    void sg2_printMassAwb_anyReservedInList_refusedWithNoHttpRequest() {
        assertThatThrownBy(() -> gateway.printMassAwb("key", List.of("8484805699", RESERVED), "A4", "ar"))
            .isInstanceOf(BostaException.class)
            .hasMessageContaining("never sent to Bosta");
        server.verify();
    }

    @Test
    void sg3_isReserved_boundaries() {
        assertThat(SimulatedTracking.isReserved("7770000000001")).isTrue();
        assertThat(SimulatedTracking.isReserved("7779999999999")).isTrue();
        assertThat(SimulatedTracking.isReserved("777000000001")).as("12 digits").isFalse();
        assertThat(SimulatedTracking.isReserved("77700000000001")).as("14 digits").isFalse();
        assertThat(SimulatedTracking.isReserved("7760000000001")).as("other prefix").isFalse();
        assertThat(SimulatedTracking.isReserved("8484805699")).as("real 10-digit").isFalse();
        assertThat(SimulatedTracking.isReserved("999000000001")).as("public demo 12-digit").isFalse();
        assertThat(SimulatedTracking.isReserved("777-0000000001")).isFalse();
        assertThat(SimulatedTracking.isReserved(null)).isFalse();
    }

    @Test
    void sg4_control_realTrackingNumber_doesReachBosta() {
        server.expect(requestTo(BASE + "/api/v0/deliveries/8484805699"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"success\":true,\"data\":{\"trackingNumber\":\"8484805699\",\"state\":{\"code\":10}}}",
                MediaType.APPLICATION_JSON));
        try {
            gateway.fetchDelivery("key", "8484805699");
        } catch (RuntimeException ignored) {
            // parsing details are not this test's concern — only that the request went out
        }
        server.verify();
    }
}
