package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Merged-PDF slice A — how a rate limit reaches the print on the wire. Bosta's 429 on mass-awb (HTTP 429,
 * or a 200 whose body says errorCode 429) and the shared limiter's time-out are all BostaRateLimitException,
 * which BostaAwbService treats as "Bosta is busy": the print stops and nobody is marked. A real 429 also
 * blocks the limiter for its retry-after, like every other Bosta call. Each request logs one timing line.
 */
@ExtendWith(OutputCaptureExtension.class)
class MassAwbRateLimitWireTest {

    private static final String BASE = "https://bosta.test";
    private static final String URL = BASE + "/api/v0/deliveries/mass-awb";
    private static final String KEY = "rate-limit-key";

    @AfterEach
    void clear() { TenantContext.clear(); }

    private static BostaHttpGateway gateway(RestClient.Builder builder, BostaRateLimiter limiter) {
        return new BostaHttpGateway(builder, new ObjectMapper(), BASE, "v0", limiter);
    }

    @Test
    void r1_http429_isRateLimit_andBlocksTheLimiter() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BostaRateLimiter limiter = new BostaRateLimiter(1, 2, 60_000, 0.75, 3, 0.2);
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
            .contentType(MediaType.APPLICATION_JSON).body("{\"success\":false,\"errorCode\":429,\"retryAfter\":30}"));

        Throwable t = catchThrowable(() -> gateway(builder, limiter).printMassAwb(KEY, List.of("3927856238"), "A6", "ar"));

        assertThat(t).isInstanceOf(BostaRateLimitException.class);
        assertThat(((BostaRateLimitException) t).getRetryAfterSeconds()).isEqualTo(30);
        assertThat(BostaAwbService.temporaryReason((BostaException) t)).isEqualTo(BostaAwbService.BOSTA_BUSY);
        assertThat(limiter.hasHeadroom(KEY, 5_000)).as("blocked for retry-after").isFalse();
        server.verify();
    }

    @Test
    void r2_200WithErrorCode429_isRateLimit_notTheEmailPath() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withSuccess(
            "{\"success\":false,\"errorCode\":429,\"retryAfter\":12}", MediaType.APPLICATION_JSON));

        Throwable t = catchThrowable(() -> gateway(builder, BostaRateLimiter.unlimited())
            .printMassAwb(KEY, List.of("3927856238"), "A6", "ar"));

        assertThat(t).isInstanceOf(BostaRateLimitException.class);
        assertThat(((BostaRateLimitException) t).getRetryAfterSeconds()).isEqualTo(12);
    }

    @Test
    void r3_limiterTimeOut_isRateLimit_andNoRequestIsSent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // One token, refilled every 100 s, and no waiting: the second request can't get a token.
        BostaRateLimiter limiter = new BostaRateLimiter(0.01, 1, 0);
        server.expect(once(), requestTo(URL)).andRespond(withSuccess(pdfBody(), MediaType.APPLICATION_JSON));
        BostaHttpGateway gw = gateway(builder, limiter);

        assertThat(gw.printMassAwb(KEY, List.of("3927856238"), "A6", "ar").isInline()).isTrue();
        Throwable t = catchThrowable(() -> gw.printMassAwb(KEY, List.of("5010904177"), "A6", "ar"));

        assertThat(t).isInstanceOf(BostaRateLimitException.class);
        assertThat(BostaAwbService.temporaryReason((BostaException) t)).isEqualTo(BostaAwbService.BOSTA_BUSY);
        server.verify();                                           // exactly one HTTP request went out
    }

    @Test
    void r4_eachRequestLogsOneTimingLine_withTenant_noTrackingNumbers(CapturedOutput output) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withSuccess(pdfBody(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("bad gateway"));
        BostaHttpGateway gw = gateway(builder, BostaRateLimiter.unlimited());
        UUID tenant = UUID.randomUUID();

        TenantContext.runAs(tenant, () -> {
            gw.printMassAwb(KEY, List.of("3927856238", "5010904177"), "A6", "ar");
            catchThrowable(() -> gw.printMassAwb(KEY, List.of("7012345678"), "A4", "ar"));
        });

        List<String> lines = output.getAll().lines().filter(l -> l.contains("mass-awb request:")).toList();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("tenant=" + tenant, "labels=2", "outcome=pdf", "durationMs=");
        assertThat(lines.get(1)).contains("tenant=" + tenant, "labels=1", "outcome=http_502", "durationMs=");
        assertThat(lines).noneSatisfy(l -> assertThat(l).containsAnyOf("3927856238", "5010904177", "7012345678"));
    }

    private static String pdfBody() {
        return "{\"success\":true,\"data\":\"" + Base64.getEncoder().encodeToString("%PDF-1.4 fake".getBytes()) + "\"}";
    }
}
