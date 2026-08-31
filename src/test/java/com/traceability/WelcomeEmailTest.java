package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.identity.model.SignupRequest;
import com.traceability.notifications.EmailGateway;
import com.traceability.notifications.WelcomeEmailJob;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Welcome email — standard email/password signup only (Shopify Path-2 is out of scope,
 * exercised separately by ShopifyMagicLinkTest / ShopifyOAuthDay* tests).
 *
 * WelcomeEmailJob is enqueued via JobScheduler (@MockBean — no real background job server,
 * see ShopifyMagicLinkTest for the same idiom), so this test invokes the job directly to
 * verify the enqueue call's captured lambda, mirroring how other tests treat JobRunr's
 * lambda-based enqueue as a black box: we assert on EmailGateway.send() being called with
 * the right recipient/body, not on JobScheduler internals.
 *
 * Body now comes from the approved classpath template (emails/welcome.html) with the
 * two heading tokens substituted — see WelcomeEmailJob.run(). The template has no CTA
 * link (de-linked) — just a plain "Log in to your dashboard" line.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class WelcomeEmailTest {

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

    @MockBean EmailGateway emailGateway;
    @MockBean JobScheduler jobScheduler;

    private String base() { return "http://localhost:" + port; }

    @BeforeEach
    void resetMocks() {
        reset(emailGateway, jobScheduler);
        // Run the enqueued JobLambda synchronously so this test doesn't need a live
        // background-job-server (org.jobrunr.background-job-server.enabled=false in tests).
        doAnswer(invocation -> {
            JobLambda job = invocation.getArgument(0);
            job.run();
            return null;
        }).when(jobScheduler).enqueue(any(JobLambda.class));
    }

    private SignupRequest signupRequest(String email, String tenantName, String ownerName) {
        return new SignupRequest(tenantName, ownerName, email, "01012345678", "password123", true);
    }

    private String captureWelcomeBody(String email, String tenantName, String ownerName) {
        ResponseEntity<AccessTokenResponse> resp = new RestTemplate().postForEntity(
                base() + "/api/v1/auth/signup",
                signupRequest(email, tenantName, ownerName),
                AccessTokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailGateway, times(1)).send(eq(email), anyString(), bodyCaptor.capture());
        return bodyCaptor.getValue();
    }

    // -----------------------------------------------------------------------
    // (a) Standard signup, name present → EN + AR markers, name substituted with a
    //     comma in both languages, and every {{...}} token resolved.
    // -----------------------------------------------------------------------
    @Test
    void standardSignup_withOwnerName_sendsWelcomeEmail_nameCommaSubstitutedBothLanguages() {
        String email = "owner-" + System.nanoTime() + "@welcome.test";

        String body = captureWelcomeBody(email, "Welcome Co", "Owner Name");

        assertThat(body).contains("Welcome to Traced");         // EN marker
        assertThat(body).contains("مرحبًا بك في Traced");        // AR marker
        assertThat(body).contains("Welcome to Traced, Owner Name");
        assertThat(body).contains("مرحبًا بك في Traced، Owner Name");
        assertThat(body).doesNotContain("{{");
        // The old CTA anchor text — not a blanket "Open Traced" check, which would
        // also match the unrelated hidden preheader sentence ("...Open Traced to
        // connect your Shopify store."), left untouched by the de-link.
        assertThat(body).doesNotContain(">Open Traced<");
        assertThat(body).doesNotContain(">افتح Traced<");
        assertThat(body).doesNotContain("APP_URL");
        assertThat(body).contains("Log in to your dashboard to get started.");
    }

    // -----------------------------------------------------------------------
    // (logo) rendered body carries the app-parity wordmark — dark "traced" +
    // blue dot span — NOT the old all-blue "traced•".
    // -----------------------------------------------------------------------
    @Test
    void welcomeBody_containsCorrectedWordmark_notOldAllBlueTraced() {
        String email = "wordmark-" + System.nanoTime() + "@welcome.test";

        String body = captureWelcomeBody(email, "Wordmark Co", "Owner Name");

        assertThat(body).contains("letter-spacing:-0.4px; color:#1F2937;");
        assertThat(body).contains("border-radius:50%; background-color:#2563EB;");
        assertThat(body).doesNotContain("traced<span style=\"color:#2563EB;\">&#8226;</span>");
    }

    // -----------------------------------------------------------------------
    // (a2) Standard signup, blank owner name → generic heading, no dangling comma
    //      or stray-space artifact in either language, every token still resolved.
    // -----------------------------------------------------------------------
    @Test
    void standardSignup_blankOwnerName_sendsWelcomeEmail_noCommaArtifact() {
        String email = "noname-" + System.nanoTime() + "@welcome.test";

        String body = captureWelcomeBody(email, "Blank Name Co", "");

        assertThat(body).contains("Welcome to Traced");
        assertThat(body).contains("مرحبًا بك في Traced");
        assertThat(body).doesNotContain("Welcome to Traced,");
        assertThat(body).doesNotContain("مرحبًا بك في Traced،");
        assertThat(body).doesNotContain("{{");
    }

    // -----------------------------------------------------------------------
    // (b) Rollback (duplicate email) → zero welcome sends — enqueue is unreachable.
    // -----------------------------------------------------------------------
    @Test
    void rolledBackSignup_sendsNoWelcomeEmail() {
        String email = "dup-" + System.nanoTime() + "@welcome.test";

        // First signup succeeds.
        ResponseEntity<AccessTokenResponse> first = new RestTemplate().postForEntity(
                base() + "/api/v1/auth/signup",
                signupRequest(email, "Dup Co", "First Owner"),
                AccessTokenResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(emailGateway, times(1)).send(eq(email), anyString(), anyString());

        reset(emailGateway);

        // Second signup with the SAME email violates users_email_unique inside
        // createTenantWithOwner's @Transactional method — the whole insert (tenant + user
        // + location) rolls back and DataIntegrityViolationException propagates out of
        // TenantContext.runAs, so AuthService.signup()'s enqueue line is never reached.
        RestTemplate noThrow = new RestTemplate();
        noThrow.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
        });
        ResponseEntity<String> second = noThrow.postForEntity(
                base() + "/api/v1/auth/signup",
                signupRequest(email, "Dup Co 2", "Second Owner"),
                String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        verify(emailGateway, never()).send(any(), any(), any());
    }
}
