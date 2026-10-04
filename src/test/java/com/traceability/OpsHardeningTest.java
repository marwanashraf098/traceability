package com.traceability;

import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.review.OpsSecretGuard;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review mode S7 — the two ops fixes, end to end:
 *   - the secret is checked BEFORE the body is read: a malformed body with a missing / wrong secret is
 *     403 (not 400 / 500)
 *   - with the right secret, a missing or unreadable body is 400 BAD_REQUEST_BODY (not the catch-all 500),
 *     and nothing the caller sent (a password) is echoed back or logged
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class OpsHardeningTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    static final String SECRET = "ops-" + UUID.randomUUID();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("traced.ops-secret",          () -> SECRET);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @MockBean  BostaGateway     bostaGateway;
    @MockBean  JobScheduler     jobScheduler;

    private ResponseEntity<String> postRaw(String body, String secret) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (secret != null) h.set(OpsSecretGuard.HEADER, secret);
        return rest.exchange("http://localhost:" + port + "/api/v1/ops/review-tenant", HttpMethod.POST,
            new HttpEntity<>(body, h), String.class);
    }

    @Test
    void malformedBody_withoutTheRightSecret_isRefusedBeforeParsing() {
        assertThat(postRaw("{not json", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postRaw("{not json", "wrong").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postRaw(null, "wrong").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rightSecret_missingOrUnreadableBody_is400_nothingEchoedOrLogged(CapturedOutput output) {
        String password = "pw-" + UUID.randomUUID();
        ResponseEntity<String> unreadable = postRaw("{\"password\":\"" + password + "\", oops", SECRET);
        assertThat(unreadable.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unreadable.getBody()).contains("BAD_REQUEST_BODY").doesNotContain(password);

        ResponseEntity<String> missing = postRaw(null, SECRET);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody()).contains("BAD_REQUEST_BODY");

        assertThat(output.getAll()).doesNotContain(password);
    }
}
