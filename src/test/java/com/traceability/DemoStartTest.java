package com.traceability;

import com.nimbusds.jwt.JWTClaimsSet;
import com.traceability.demo.DemoException;
import com.traceability.demo.DemoSeeder;
import com.traceability.demo.DemoStartRequest;
import com.traceability.demo.DemoStartResponse;
import com.traceability.demo.DemoStartService;
import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
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
 * FR-DEMO Day 2 — POST /api/v1/public/demo/start.
 *
 * background-job-server.enabled defaults true (matchIfMissing) so DemoReseedJob/
 * ExceptionDigestJob/ExceptionImmediateAlertJob beans exist to autowire; JobScheduler is
 * @MockBean so nothing queued elsewhere in the app context actually executes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoStartTest {

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
        r.add("shopify.api-version",        () -> "2024-10");
        r.add("shopify.client-id",          () -> "test-client-id");
        r.add("shopify.client-secret",      () -> "test-client-secret");
        r.add("shopify.scopes",             () -> "read_products");
        r.add("shopify.webhook-base-url",   () -> "https://test.example.com");
        r.add("bosta.api-base-url",         () -> "https://app.bosta.co");
    }

    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    @LocalServerPort int port;
    @Autowired TestRestTemplate  rest;
    @Autowired JdbcTemplate      jdbc;
    @Autowired JwtService        jwtService;
    @Autowired DemoSeeder        demoSeeder;
    @Autowired DemoStartService  demoStartService;

    private String base() { return "http://localhost:" + port; }

    @BeforeAll
    void setup() {
        demoSeeder.ensureBootstrapped();
        demoSeeder.reseed();
    }

    @BeforeEach
    void resetJobScheduler() {
        reset(jobScheduler);
    }

    private DemoStartRequest validRequest() {
        return new DemoStartRequest("Test Visitor", "visitor-" + UUID.randomUUID() + "@example.com",
                "+1-202-555-0100", true);
    }

    private ResponseEntity<DemoStartResponse> post(DemoStartRequest req) {
        return rest.postForEntity(base() + "/api/v1/public/demo/start", req, DemoStartResponse.class);
    }

    private ResponseEntity<Map> postForError(DemoStartRequest req) {
        return rest.postForEntity(base() + "/api/v1/public/demo/start", req, Map.class);
    }

    // -----------------------------------------------------------------------
    // (a) happy path: 200, accessToken decodes to demo owner + DEMO_TENANT_ID + 90-min exp,
    //     no Set-Cookie header, demo_leads row written.
    // -----------------------------------------------------------------------
    @Test
    @Order(1)
    void happyPath_returns200_accessTokenValid_noCookie_leadPersisted() throws Exception {
        jdbc.update("DELETE FROM demo_leads");
        DemoStartRequest req = validRequest();

        ResponseEntity<DemoStartResponse> resp = post(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        DemoStartResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.accessToken()).isNotBlank();
        assertThat(body.redirect()).isEqualTo("/overview");
        assertThat(resp.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();

        JWTClaimsSet claims = jwtService.verify(body.accessToken());
        assertThat(claims.getClaim("tenant")).isEqualTo(DemoSeeder.DEMO_TENANT_ID.toString());
        assertThat(claims.getClaim("role")).isEqualTo("owner");
        UUID subjectUserId = UUID.fromString(claims.getSubject());
        assertThat(subjectUserId).isEqualTo(demoSeeder.resolveOwnerId());

        long ttlSeconds = (claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()) / 1000;
        assertThat(ttlSeconds).isEqualTo(90 * 60);

        Long leadCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM demo_leads WHERE email = ?", Long.class, req.email());
        assertThat(leadCount).isEqualTo(1L);
    }

    // -----------------------------------------------------------------------
    // (b) per-IP rate limit trips after 5 requests from the same IP.
    // -----------------------------------------------------------------------
    @Test
    @Order(2)
    void perIpRateLimit_tripsOnSixthRequest() {
        jdbc.update("DELETE FROM demo_leads");
        for (int i = 0; i < 5; i++) {
            ResponseEntity<DemoStartResponse> resp = post(validRequest());
            assertThat(resp.getStatusCode()).as("request %d must succeed", i + 1).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<Map> sixth = postForError(validRequest());
        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(sixth.getBody().get("code")).isEqualTo("DEMO_RATE_LIMITED");
    }

    // -----------------------------------------------------------------------
    // (c) global 90-minute cap trips — tested at the service level with distinct
    //     synthetic IPs (TestRestTemplate always connects from the same loopback
    //     address, which would trip the per-IP limit first at HTTP level).
    // -----------------------------------------------------------------------
    @Test
    @Order(3)
    void globalCap_tripsAfterTwentyLeadsAcrossDistinctIps() {
        jdbc.update("DELETE FROM demo_leads");
        for (int i = 0; i < 20; i++) {
            DemoStartResponse resp = demoStartService.start(validRequest(), "10.0.0." + i);
            assertThat(resp).as("lead %d must succeed", i + 1).isNotNull();
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> demoStartService.start(validRequest(), "10.0.1.1"))
                .isInstanceOf(DemoException.class)
                .satisfies(e -> assertThat(((DemoException) e).code())
                        .isEqualTo(DemoException.Code.DEMO_RATE_LIMITED));
    }

    // -----------------------------------------------------------------------
    // (d) invalid input — blank name, bad email, consent=false → DEMO_INPUT_INVALID.
    // -----------------------------------------------------------------------
    @Test
    @Order(4)
    void invalidInput_returnsDemoInputInvalid() {
        jdbc.update("DELETE FROM demo_leads");

        ResponseEntity<Map> blankName = postForError(
                new DemoStartRequest("", "valid@example.com", "0100", true));
        assertThat(blankName.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(blankName.getBody().get("code")).isEqualTo("DEMO_INPUT_INVALID");

        ResponseEntity<Map> badEmail = postForError(
                new DemoStartRequest("Someone", "not-an-email", "0100", true));
        assertThat(badEmail.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(badEmail.getBody().get("code")).isEqualTo("DEMO_INPUT_INVALID");

        ResponseEntity<Map> noConsent = postForError(
                new DemoStartRequest("Someone", "valid2@example.com", "0100", false));
        assertThat(noConsent.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(noConsent.getBody().get("code")).isEqualTo("DEMO_INPUT_INVALID");
    }

    // -----------------------------------------------------------------------
    // (e) a non-Egyptian phone shape is ACCEPTED — proves no E.164/Egyptian
    //     normalization is applied (locked decision: phone captured unverified).
    // -----------------------------------------------------------------------
    @Test
    @Order(5)
    void nonEgyptianPhone_isAccepted() {
        jdbc.update("DELETE FROM demo_leads");
        DemoStartRequest req = new DemoStartRequest(
                "US Visitor", "us-visitor@example.com", "+1 (202) 555-0199", true);

        ResponseEntity<DemoStartResponse> resp = post(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String storedPhone = jdbc.queryForObject(
                "SELECT phone FROM demo_leads WHERE email = ?", String.class, req.email());
        assertThat(storedPhone).isEqualTo("+1 (202) 555-0199");
    }

    // -----------------------------------------------------------------------
    // (f) the minted token, used against a real endpoint, resolves ONLY the demo
    //     tenant/owner (application-level tenant scoping via TenantContext holds).
    // -----------------------------------------------------------------------
    @Test
    @Order(6)
    void mintedToken_resolvesOnlyDemoTenantOwner() {
        jdbc.update("DELETE FROM demo_leads");
        DemoStartResponse start = post(validRequest()).getBody();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(start.accessToken());
        ResponseEntity<Map> me = rest.exchange(
                base() + "/api/v1/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("role")).isEqualTo("owner");
        assertThat(me.getBody().get("email")).isEqualTo("demo-owner@tracedtech.invalid");
    }

    // -----------------------------------------------------------------------
    // (g) signup while holding a demo token still mints a fresh, isolated real
    //     tenant — no demo data carried, signup ignores any Authorization header.
    // -----------------------------------------------------------------------
    @Test
    @Order(7)
    void signupWhileHoldingDemoToken_mintsFreshRealTenant_noDemoDataCarried() throws Exception {
        jdbc.update("DELETE FROM demo_leads");
        DemoStartResponse start = post(validRequest()).getBody();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(start.accessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        SignupRequest signup = new SignupRequest(
                "Real Co " + UUID.randomUUID(), "Real Owner",
                "real-" + UUID.randomUUID() + "@example.com", "01012345678", "Password99!", true);

        ResponseEntity<TokenResponse> resp = rest.exchange(
                base() + "/api/v1/auth/signup", HttpMethod.POST,
                new HttpEntity<>(signup, headers), TokenResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JWTClaimsSet claims = jwtService.verify(resp.getBody().accessToken());
        UUID newTenantId = UUID.fromString((String) claims.getClaim("tenant"));

        assertThat(newTenantId).isNotEqualTo(DemoSeeder.DEMO_TENANT_ID);
        Boolean isDemo = jdbc.queryForObject(
                "SELECT is_demo FROM tenants WHERE id = ?", Boolean.class, newTenantId);
        assertThat(isDemo).isFalse();

        Long productCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE tenant_id = ?", Long.class, newTenantId);
        assertThat(productCount).as("fresh signup carries none of the demo golden fixture").isZero();
    }

    // -----------------------------------------------------------------------
    // (h) lead notification is enqueued (fire-and-forget, not tenant-iterating).
    // -----------------------------------------------------------------------
    @Test
    @Order(8)
    void leadNotification_isEnqueued() {
        jdbc.update("DELETE FROM demo_leads");
        DemoStartRequest req = validRequest();

        post(req);

        verify(jobScheduler, times(1)).enqueue(any(org.jobrunr.jobs.lambdas.JobLambda.class));
    }
}
