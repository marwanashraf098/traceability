package com.traceability;

import com.traceability.identity.model.LoginRequest;
import com.traceability.identity.model.PinRequest;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day-2 integration tests through the full HTTP filter chain.
 *
 * TenantProbeService / TenantProbeController / TestSetup are picked up automatically
 * by @SpringBootTest component scan (same package, test source root).
 * TestSetup fires ALTER USER app_user after Flyway creates the role, enabling
 * the RLS isolation test to open a direct app_user JDBC connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    private String base() { return "http://localhost:" + port; }

    // Shared across ordered tests
    private static String accessTokenA;
    private static String accessTokenB;
    private static UUID   tenantAId;
    private static UUID   tenantBId;

    // -----------------------------------------------------------------------
    // (e) GUC is set correctly inside the real filter chain
    // -----------------------------------------------------------------------
    @Test
    @Order(1)
    void signupCreatesTokenAndGucIsSetOnProbeRequest() {
        TokenResponse tokens = signup("Acme Corp", "alice@acme.com", "password123");
        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        accessTokenA = tokens.accessToken();

        Map<String, Object> probe = probe(accessTokenA);
        String guc = (String) probe.get("guc");

        // Test (e): GUC must be a valid UUID — proves filter → wrapper → SET LOCAL chain works.
        assertThat(guc)
                .as("GUC must be a valid UUID (test e: filter chain sets app.current_tenant)")
                .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

        // Signup must have created Main Warehouse.
        assertThat((Number) probe.get("locationCount"))
                .extracting(Number::intValue)
                .isEqualTo(1);

        tenantAId = UUID.fromString(guc);
    }

    // -----------------------------------------------------------------------
    // Login with no GUC set — auth_lookup_user SECURITY DEFINER handles it
    // -----------------------------------------------------------------------
    @Test
    @Order(2)
    void loginReturnsTokensWithoutGucPreset() {
        TokenResponse tokens = login("alice@acme.com", "password123");
        assertThat(tokens.accessToken()).isNotBlank();
        Map<String, Object> probe = probe(tokens.accessToken());
        assertThat((String) probe.get("guc")).isEqualTo(tenantAId.toString());
    }

    // -----------------------------------------------------------------------
    // Cross-tenant isolation via a direct app_user JDBC connection (no BYPASSRLS)
    // -----------------------------------------------------------------------
    @Test
    @Order(3)
    void crossTenantIsolationViaAppUserConnection() throws Exception {
        TokenResponse tokensB = signup("Beta LLC", "bob@beta.com", "password456");
        accessTokenB = tokensB.accessToken();
        Map<String, Object> probeB = probe(accessTokenB);
        tenantBId = UUID.fromString((String) probeB.get("guc"));

        assertThat(tenantAId).isNotEqualTo(tenantBId);

        // Open connection as app_user (password set by TestSetup; no BYPASSRLS → RLS enforced).
        // Use a fresh connection per block — after ROLLBACK PostgreSQL resets SET LOCAL GUC to ''
        // and ''::uuid is an invalid cast, so reusing the same connection after rollback would fail.

        // GUC = tenant A → sees only 1 location.
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "testpw")) {
            conn.setAutoCommit(false);
            conn.prepareStatement(
                    "SELECT set_config('app.current_tenant', '" + tenantAId + "', true)").execute();
            int countA = queryCount(conn, "SELECT COUNT(*) FROM locations");
            assertThat(countA).as("Tenant A sees only their own location").isEqualTo(1);
        }

        // GUC = tenant B → sees only 1 location (not tenant A's).
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "testpw")) {
            conn.setAutoCommit(false);
            conn.prepareStatement(
                    "SELECT set_config('app.current_tenant', '" + tenantBId + "', true)").execute();
            int countB = queryCount(conn, "SELECT COUNT(*) FROM locations");
            assertThat(countB).as("Tenant B sees only their own location, not A's").isEqualTo(1);
        }

        // No GUC → zero rows (safe default).
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "testpw")) {
            conn.setAutoCommit(false);
            int countNone = queryCount(conn, "SELECT COUNT(*) FROM locations");
            assertThat(countNone).as("Without GUC app_user sees 0 rows").isEqualTo(0);
        }
    }

    // -----------------------------------------------------------------------
    // Unauthenticated request → 401
    // -----------------------------------------------------------------------
    @Test
    @Order(4)
    void unauthenticatedRequestReturns401() {
        ResponseEntity<String> resp = rest.getForEntity(base() + "/api/v1/test/probe", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -----------------------------------------------------------------------
    // PIN lockout: 5 wrong PINs → 423 LOCKED; correct PIN also locked
    // -----------------------------------------------------------------------
    @Test
    @Order(5)
    void pinLockoutAfterFiveFailures() throws Exception {
        // Set pin_code = password_hash via postgres (BYPASSRLS) — app JdbcTemplate has no TenantContext
        // at this point so RLS WITH CHECK would block the UPDATE.
        try (Connection pgConn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            pgConn.createStatement().execute(
                    "UPDATE users SET pin_code = password_hash WHERE email = 'alice@acme.com'");
        }

        HttpHeaders headers = bearerHeaders(accessTokenA);

        // Verify first attempt reaches PinService (JWT valid, endpoint reachable).
        ResponseEntity<String> first = rest.postForEntity(base() + "/api/v1/auth/pin",
                new HttpEntity<>(new PinRequest("0000"), headers), String.class);
        assertThat(first.getStatusCode().value()).as("1st wrong PIN should return 401").isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT pin_fail_count FROM users WHERE email = 'alice@acme.com'",
                Integer.class)).as("fail_count after 1st attempt").isEqualTo(1);

        ResponseEntity<String> fifth = null;
        for (int i = 1; i < 5; i++) {
            fifth = rest.postForEntity(base() + "/api/v1/auth/pin",
                    new HttpEntity<>(new PinRequest("0000"), headers), String.class);
        }
        assertThat(fifth.getStatusCode().value()).as("5th attempt must trigger lockout (423)").isEqualTo(423);

        assertThat(jdbc.queryForObject("SELECT pin_fail_count FROM users WHERE email = 'alice@acme.com'",
                Integer.class)).as("fail_count after all 5 attempts").isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT pin_locked_until FROM users WHERE email = 'alice@acme.com'",
                java.sql.Timestamp.class)).as("pin_locked_until must be set after 5th attempt").isNotNull();

        // Any PIN now rejected with 423
        ResponseEntity<String> locked = rest.postForEntity(base() + "/api/v1/auth/pin",
                new HttpEntity<>(new PinRequest("0000"), headers), String.class);
        assertThat(locked.getStatusCode().value())
                .as("PIN locked after 5 failures (body: %s)", locked.getBody())
                .isEqualTo(423);

        // Even the correct PIN is locked
        ResponseEntity<String> correct = rest.postForEntity(base() + "/api/v1/auth/pin",
                new HttpEntity<>(new PinRequest("password123"), headers), String.class);
        assertThat(correct.getStatusCode().value()).as("Correct PIN also rejected while locked").isEqualTo(423);
    }

    // -----------------------------------------------------------------------
    // Refresh token rotation: old token rejected on second use
    // -----------------------------------------------------------------------
    @Test
    @Order(6)
    void refreshTokenRotates() {
        TokenResponse initial = login("bob@beta.com", "password456");

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.TEXT_PLAIN);
        HttpEntity<String> req = new HttpEntity<>(initial.refreshToken(), h);

        ResponseEntity<TokenResponse> resp = rest.postForEntity(
                base() + "/api/v1/auth/refresh", req, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().accessToken()).isNotBlank();

        // Second use of same refresh token must be rejected
        ResponseEntity<String> reuse = rest.postForEntity(
                base() + "/api/v1/auth/refresh", req, String.class);
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -----------------------------------------------------------------------
    // Consent gate: signup without consent rejected
    // -----------------------------------------------------------------------
    @Test
    @Order(7)
    void signupWithoutConsentIsRejected() {
        SignupRequest noConsent = new SignupRequest(
                "No-Consent Corp", "nc", "noconsent@example.com", "password123", false);
        ResponseEntity<String> resp = rest.postForEntity(
                base() + "/api/v1/auth/signup", noConsent, String.class);
        assertThat(resp.getStatusCode().value())
                .as("Signup without consent must be rejected (422)")
                .isEqualTo(422);
    }

    // -----------------------------------------------------------------------
    // Consent gate: with consent → versions + timestamp persisted
    // -----------------------------------------------------------------------
    @Test
    @Order(8)
    void signupWithConsentPersistsVersionsAndTimestamp() {
        signup("Consent Corp", "consent@corp.example.com", "password123");

        // BYPASSRLS connection — verify columns directly
        Map<String, Object> row = jdbc.queryForObject(
                "SELECT accepted_privacy_version, accepted_terms_version, accepted_at " +
                "FROM users WHERE email = 'consent@corp.example.com'",
                (rs, rn) -> {
                    var m = new java.util.LinkedHashMap<String, Object>();
                    m.put("privacy",  rs.getString("accepted_privacy_version"));
                    m.put("terms",    rs.getString("accepted_terms_version"));
                    m.put("acceptedAt", rs.getTimestamp("accepted_at"));
                    return m;
                });

        assertThat(row.get("privacy"))
                .as("accepted_privacy_version must equal PolicyVersions.PRIVACY")
                .isEqualTo("1.0");
        assertThat(row.get("terms"))
                .as("accepted_terms_version must equal PolicyVersions.TERMS")
                .isEqualTo("1.0");
        assertThat(row.get("acceptedAt"))
                .as("accepted_at must be set")
                .isNotNull();
    }

    // -----------------------------------------------------------------------
    // Consent gate: consent columns respect tenant RLS
    // -----------------------------------------------------------------------
    @Test
    @Order(9)
    void consentColumnsRespectTenantRls() throws Exception {
        // Find the tenant for the consent user created in test 8
        UUID consentTenantId = UUID.fromString(jdbc.queryForObject(
                "SELECT tenant_id::text FROM users WHERE email = 'consent@corp.example.com'",
                String.class));

        // No GUC → app_user sees 0 rows with accepted_at set
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "testpw")) {
            conn.setAutoCommit(false);
            int count = queryCount(conn,
                    "SELECT COUNT(*) FROM users WHERE accepted_at IS NOT NULL");
            assertThat(count)
                    .as("Without GUC, app_user must see 0 rows with consent (RLS)")
                    .isEqualTo(0);
        }

        // Correct GUC → app_user sees exactly 1 row
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "testpw")) {
            conn.setAutoCommit(false);
            conn.prepareStatement(
                    "SELECT set_config('app.current_tenant', '" + consentTenantId + "', true)").execute();
            int count = queryCount(conn,
                    "SELECT COUNT(*) FROM users WHERE accepted_at IS NOT NULL");
            assertThat(count)
                    .as("With correct GUC, app_user must see 1 row with consent")
                    .isEqualTo(1);
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private TokenResponse signup(String tenantName, String email, String password) {
        SignupRequest body = new SignupRequest(tenantName, email.split("@")[0], email, password, true);
        ResponseEntity<TokenResponse> resp = rest.postForEntity(
                base() + "/api/v1/auth/signup", body, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    private TokenResponse login(String email, String password) {
        ResponseEntity<TokenResponse> resp = rest.postForEntity(
                base() + "/api/v1/auth/login", new LoginRequest(email, password), TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> probe(String accessToken) {
        HttpEntity<Void> req = new HttpEntity<>(bearerHeaders(accessToken));
        ResponseEntity<Map> resp = rest.exchange(
                base() + "/api/v1/test/probe", HttpMethod.GET, req, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private static HttpHeaders bearerHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private static int queryCount(Connection conn, String sql) throws Exception {
        var rs = conn.createStatement().executeQuery(sql);
        rs.next();
        return rs.getInt(1);
    }
}
