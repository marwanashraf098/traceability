package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.portal.ReturnPickupBookingService;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Review mode S2 — a simulated-courier tenant (V130) never books a real Bosta trip.
 *   r1 book-now / retry / not-booked / confirm → 409 REVIEW_MODE_UNAVAILABLE ("Not available in
 *      review mode", EN + AR); nothing claimed, nothing enqueued, no Bosta call
 *   r2 the booking job itself (book()) is a no-op for a simulated tenant — no claim, no V2 call
 *   r3 a real tenant is unchanged: book-now on an approved, never-booked exchange is accepted and enqueued
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewModeBookingTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired JwtService       jwt;
    @Autowired ReturnPickupBookingService booking;
    @MockBean  BostaGateway     bostaGateway;
    @MockBean  BostaV2Client    bostaV2;
    @MockBean  JobScheduler     jobScheduler;

    private record Req(UUID tenant, UUID request, String token) {}

    private Req exchangeRequest(String name, boolean simulated, String bookingStatus) {
        PackFixtures f = new PackFixtures(jdbc, name);
        if (simulated) jdbc.update("INSERT INTO tenant_courier_simulation (tenant_id, note) VALUES (?, 'test')", f.tenant);
        UUID owner = f.user("Owner", "owner");
        UUID order = f.order("#" + name, 1);
        UUID request = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, type, status, reference, booking_status) " +
            "VALUES (?, ?, 'exchange', 'approved'::return_request_status, ?, ?) RETURNING id",
            UUID.class, f.tenant, order, "RR-TEST", bookingStatus);   // ^RR-[2-9A-HJ-NP-Z]{4,}$, unique per tenant
        return new Req(f.tenant, request, jwt.issueAccessToken(owner, f.tenant, "owner"));
    }

    @AfterEach
    void reset() { clearInvocations(bostaGateway, bostaV2, jobScheduler); }

    @Test
    void r1_everyManualBookingAction_refused_inReviewMode() {
        Req never  = exchangeRequest("R1a", true, null);
        Req failed = exchangeRequest("R1b", true, "failed");
        Req ambig  = exchangeRequest("R1c", true, "failed_ambiguous");

        assertRefused(post(never,  "/booking/book-now", null));
        assertRefused(post(failed, "/booking/retry", null));
        assertRefused(post(ambig,  "/booking/not-booked", null));
        assertRefused(post(ambig,  "/booking/confirm", Map.of("trackingNumber", "8484805699")));

        assertThat(status(never)).isNull();
        assertThat(status(failed)).isEqualTo("failed");
        assertThat(status(ambig)).isEqualTo("failed_ambiguous");
        verifyNoInteractions(jobScheduler, bostaGateway, bostaV2);
    }

    @Test
    void r2_bookingJob_isANoOp_forASimulatedTenant() {
        Req r = exchangeRequest("R2", true, null);
        booking.book(r.request(), r.tenant());
        assertThat(status(r)).as("nothing claimed").isNull();
        verifyNoInteractions(bostaV2, bostaGateway);
    }

    @Test
    void r3_realTenant_bookNow_stillAccepted_andEnqueued() {
        Req r = exchangeRequest("R3", false, null);
        ResponseEntity<Map> resp = post(r, "/booking/book-now", null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(jobScheduler).enqueue(any(org.jobrunr.jobs.lambdas.IocJobLambda.class));
    }

    private void assertRefused(ResponseEntity<Map> r) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("code", "REVIEW_MODE_UNAVAILABLE");
        assertThat(r.getBody()).containsEntry("message_en", "Not available in review mode.");
        assertThat(r.getBody()).containsEntry("message_ar", "غير متاح في وضع المراجعة.");
    }

    private String status(Req r) {
        return jdbc.queryForObject("SELECT booking_status FROM return_requests WHERE id = ?", String.class, r.request());
    }

    private ResponseEntity<Map> post(Req r, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(r.token());
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + "/api/v1/return-requests/" + r.request() + path,
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }
}
