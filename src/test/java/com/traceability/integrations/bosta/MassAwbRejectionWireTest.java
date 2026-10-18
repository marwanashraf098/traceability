package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Deleted-in-Bosta slice 1 — how BostaHttpGateway reports a mass-awb error on the wire. A 400 becomes
 * BostaAwbRejectedException with Bosta's own message (the input BostaAwbService isolates on), the
 * exception message unchanged; 5xx stays BostaTransientException and other 4xx a plain BostaException,
 * so neither is ever isolated.
 */
class MassAwbRejectionWireTest {

    private static final String BASE = "https://bosta.test";
    private static final String URL = BASE + "/api/v0/deliveries/mass-awb";
    /** Bosta's body for the Snouts 2026-10-02 case; the part after "trackingNum" is reconstructed. */
    private static final String BODY =
        "{\"success\":false,\"message\":\"Couldn't find deliveries with ids: [] or trackingNumbers: [3927856238]\"}";

    private MockRestServiceServer server;
    private BostaHttpGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server  = MockRestServiceServer.bindTo(builder).build();
        gateway = new BostaHttpGateway(builder, new ObjectMapper(), BASE, "v0");
    }

    @Test
    void w1_400_typedWithBostaMessage_messageUnchanged_andIsolatable() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(BODY));

        Throwable t = catchThrowable(() -> gateway.printMassAwb("key", List.of("3927856238", "5010904177"), "A6", "ar"));

        assertThat(t).isInstanceOf(BostaAwbRejectedException.class);
        BostaAwbRejectedException e = (BostaAwbRejectedException) t;
        assertThat(e.httpStatus()).isEqualTo(400);
        assertThat(e.bostaMessage()).isEqualTo("Couldn't find deliveries with ids: [] or trackingNumbers: [3927856238]");
        assertThat(e.getMessage()).isEqualTo("Bosta mass-awb error (400 BAD_REQUEST): " + BODY);
        assertThat(BostaAwbService.isolationKind(e)).isEqualTo(BostaAwbService.Isolation.NOT_FOUND);
        assertThat(BostaAwbService.namedIn(e, List.of("3927856238", "5010904177"))).containsExactly("3927856238");
        server.verify();
    }

    @Test
    void w2_5xx_transient_notIsolatable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("bad gateway"));
        Throwable t = catchThrowable(() -> gateway.printMassAwb("key", List.of("3927856238"), "A6", "ar"));
        assertThat(t).isInstanceOf(BostaTransientException.class);
        assertThat(BostaAwbService.isolationKind((BostaException) t)).isNull();
    }

    @Test
    void w3_429_plainBostaException_notIsolatable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
            .body("{\"success\":false,\"errorCode\":429,\"retryAfter\":30}"));
        Throwable t = catchThrowable(() -> gateway.printMassAwb("key", List.of("3927856238"), "A6", "ar"));
        assertThat(t).isInstanceOf(BostaException.class).isNotInstanceOf(BostaAwbRejectedException.class);
        assertThat(BostaAwbService.isolationKind((BostaException) t)).isNull();
    }

    @Test
    void w4_namedIn_wholeDigitRunsOnly() {
        BostaAwbRejectedException e = new BostaAwbRejectedException("x", 400,
            "Couldn't find deliveries with ids: [] or trackingNumbers: [39278562381]", null);
        assertThat(BostaAwbService.namedIn(e, List.of("3927856238"))).isEmpty();   // a longer number isn't it
        assertThat(BostaAwbService.isolationKind(new BostaAwbRejectedException("x", 400,
            "The Following Orders can't be printed since they have been terminated", null)))
            .isEqualTo(BostaAwbService.Isolation.NOT_PRINTABLE);
    }
}
