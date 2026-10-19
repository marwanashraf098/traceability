package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Merged-PDF slice B — Bosta's 401 / 403 on mass-awb is about the API key, not the waybills:
 * BostaAuthException (same message as before), which the print reports BOSTA_AUTH, marks nobody and stops on.
 */
class MassAwbAuthWireTest {

    private static final String BASE = "https://bosta.test";

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void a1_401and403_areAuth_notIsolatable_stopThePrint(int status) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/api/v0/deliveries/mass-awb")).andRespond(withStatus(HttpStatus.valueOf(status))
            .contentType(MediaType.APPLICATION_JSON).body("{\"success\":false,\"message\":\"Unauthorized\"}"));
        BostaHttpGateway gw = new BostaHttpGateway(builder, new ObjectMapper(), BASE, "v0");

        Throwable t = catchThrowable(() -> gw.printMassAwb("key", List.of("3927856238"), "A6", "ar"));

        assertThat(t).isInstanceOf(BostaAuthException.class);
        assertThat(((BostaAuthException) t).httpStatus()).isEqualTo(status);
        assertThat(t.getMessage()).startsWith("Bosta mass-awb error (" + status);
        assertThat(BostaAwbService.isolationKind((BostaException) t)).isNull();
        assertThat(BostaAwbService.temporaryReason((BostaException) t)).isEqualTo(BostaAwbService.BOSTA_AUTH);
        assertThat(BostaAwbService.stopsPrint(BostaAwbService.BOSTA_AUTH)).isTrue();
        server.verify();
    }
}
