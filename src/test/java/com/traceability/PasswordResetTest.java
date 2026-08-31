package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.notifications.EmailGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Code-based forgot/reset password (V85, PasswordResetService) and the
 * credential-table lockdown (V86 — app_user restricted to INSERT on
 * magic_link_tokens / password_reset_codes; consume/throttle rerouted
 * through SECURITY DEFINER functions).
 *
 * Each test uses its own freshly signed-up user (unique email via nanoTime) — no shared
 * ordering, mirroring WelcomeEmailTest's style rather than AuthIntegrationTest's @Order chain,
 * since none of these scenarios depend on another test's state.
 *
 * EmailGateway is @MockBean so the raw 6-digit code (never persisted in plaintext anywhere
 * else — only its SHA-256 hash reaches the DB) can be captured from the outgoing body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PasswordResetTest {

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
    @Autowired JdbcTemplate jdbc;

    @MockBean EmailGateway emailGateway;

    private String base() { return "http://localhost:" + port; }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private AccessTokenResponse signup(String tenantName, String email, String password) {
        Map<String, Object> body = Map.of(
                "tenantName", tenantName, "name", "Owner", "email", email,
                "phone", "01012345678", "password", password, "consent", true);
        ResponseEntity<AccessTokenResponse> resp = rest.postForEntity(
                base() + "/api/v1/auth/signup", body, AccessTokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    /** Returns the raw refresh-token cookie value ("traced_refresh=...") from a login/signup response. */
    private String rawRefreshCookie(ResponseEntity<?> resp) {
        String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull().contains("traced_refresh=");
        return setCookie.split(";")[0];
    }

    private ResponseEntity<Map> requestReset(String email) {
        return rest.postForEntity(base() + "/api/v1/auth/forgot-password",
                Map.of("email", email), Map.class);
    }

    /** Calls forgot-password, captures the outgoing email body, and extracts the 6-digit code. */
    private String requestResetAndCaptureCode(String email) {
        reset(emailGateway);
        ResponseEntity<Map> resp = requestReset(email);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailGateway, times(1)).send(eq(email), anyString(), bodyCaptor.capture());

        Matcher m = Pattern.compile("\\b\\d{6}\\b").matcher(bodyCaptor.getValue());
        assertThat(m.find()).as("email body must contain the 6-digit code").isTrue();
        return m.group();
    }

    private ResponseEntity<String> resetPassword(String email, String code, String newPassword) {
        Map<String, Object> body = Map.of("email", email, "code", code, "newPassword", newPassword);
        return rest.postForEntity(base() + "/api/v1/auth/reset-password", body, String.class);
    }

    private ResponseEntity<AccessTokenResponse> refreshWith(String rawCookie) {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, rawCookie);
        return rest.exchange(base() + "/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(null, h), AccessTokenResponse.class);
    }

    private UUID userIdFor(String email) {
        return UUID.fromString(jdbc.queryForObject(
                "SELECT id::text FROM users WHERE email = ?", String.class, email));
    }

    private UUID tenantIdFor(String email) {
        return UUID.fromString(jdbc.queryForObject(
                "SELECT tenant_id::text FROM users WHERE email = ?", String.class, email));
    }

    // -----------------------------------------------------------------------
    // Enumeration-safe: existing vs non-existing email → identical status + body.
    // -----------------------------------------------------------------------
    @Test
    void requestReset_existingAndNonExistingEmail_identicalResponse() {
        String email = "enum-" + System.nanoTime() + "@reset.test";
        signup("Enum Co", email, "password123");
        reset(emailGateway);

        ResponseEntity<Map> existing    = requestReset(email);
        ResponseEntity<Map> nonExisting = requestReset("nobody-" + System.nanoTime() + "@reset.test");

        assertThat(existing.getStatusCode()).isEqualTo(nonExisting.getStatusCode());
        assertThat(existing.getBody()).isEqualTo(nonExisting.getBody());

        // Only the existing user actually got a code sent (the response gave no signal either way).
        verify(emailGateway, times(1)).send(eq(email), anyString(), anyString());
    }

    // -----------------------------------------------------------------------
    // Single-use [revert-to-confirm]: 2nd reset with the same code fails.
    // -----------------------------------------------------------------------
    @Test
    void resetPassword_sameCodeTwice_secondFails() {
        String email = "singleuse-" + System.nanoTime() + "@reset.test";
        signup("SingleUse Co", email, "password123");
        String code = requestResetAndCaptureCode(email);

        ResponseEntity<String> first = resetPassword(email, code, "newpassword1");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = resetPassword(email, code, "newpassword2");
        assertThat(second.getStatusCode())
                .as("a consumed code must not be usable a second time")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -----------------------------------------------------------------------
    // Expiry: code past expires_at is rejected.
    // -----------------------------------------------------------------------
    @Test
    void resetPassword_expiredCode_rejected() {
        String email = "expiry-" + System.nanoTime() + "@reset.test";
        signup("Expiry Co", email, "password123");
        String code = requestResetAndCaptureCode(email);

        jdbc.update("UPDATE password_reset_codes SET expires_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), userIdFor(email));

        ResponseEntity<String> resp = resetPassword(email, code, "newpassword1");
        assertThat(resp.getStatusCode())
                .as("an expired code must be rejected")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -----------------------------------------------------------------------
    // Lockout [revert-to-confirm]: 5 wrong submissions locks the code;
    // the 6th attempt — even with the CORRECT code — is still rejected.
    // -----------------------------------------------------------------------
    @Test
    void resetPassword_fiveWrongAttempts_locksEvenCorrectCode() {
        String email = "lockout-" + System.nanoTime() + "@reset.test";
        signup("Lockout Co", email, "password123");
        String code = requestResetAndCaptureCode(email);

        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> wrong = resetPassword(email, "000000", "newpassword1");
            assertThat(wrong.getStatusCode())
                    .as("wrong-code attempt %d must be 401", i + 1)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        Integer attempts = jdbc.queryForObject(
                "SELECT attempt_count FROM password_reset_codes WHERE user_id = ?",
                Integer.class, userIdFor(email));
        assertThat(attempts).as("attempt_count after 5 wrong submissions").isEqualTo(5);

        ResponseEntity<String> correctButLocked = resetPassword(email, code, "newpassword1");
        assertThat(correctButLocked.getStatusCode())
                .as("the correct code must still be rejected once locked")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -----------------------------------------------------------------------
    // Session invalidation [revert-to-confirm]: a pre-existing refresh token
    // is revoked after a successful reset.
    // -----------------------------------------------------------------------
    @Test
    void resetPassword_success_revokesExistingRefreshToken() {
        String email = "sessioninv-" + System.nanoTime() + "@reset.test";
        Map<String, Object> body = Map.of(
                "tenantName", "SessionInv Co", "name", "Owner", "email", email,
                "phone", "01012345678", "password", "password123", "consent", true);
        ResponseEntity<AccessTokenResponse> signupResp = rest.postForEntity(
                base() + "/api/v1/auth/signup", body, AccessTokenResponse.class);
        assertThat(signupResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String oldCookie = rawRefreshCookie(signupResp);

        // No pre-reset sanity call here: /auth/refresh rotates (single-use-revokes) whatever
        // cookie it's given (see CookieAuthTest), so using oldCookie once before the reset
        // would revoke it via rotation and make this test pass even if the reset's OWN
        // revocation were broken — a false positive. The freshly-issued signup cookie is
        // known-good by construction (CookieAuthTest CA1/CA2 already cover that).

        String code = requestResetAndCaptureCode(email);
        ResponseEntity<String> reset = resetPassword(email, code, "newpassword1");
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<AccessTokenResponse> afterReset = refreshWith(oldCookie);
        assertThat(afterReset.getStatusCode())
                .as("a refresh token issued before the reset must be revoked after it")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -----------------------------------------------------------------------
    // Invalidate-all: an older outstanding code is dead (consumed_at set) after a
    // successful reset with the current one.
    //
    // Note: verify_and_consume_reset_code only ever considers the NEWEST unconsumed,
    // unexpired row for a user (by design — see V85's migration comment), so an older
    // outstanding code was never independently submittable through /reset-password to
    // begin with. What "invalidate all other outstanding codes" actually guards against
    // is a dangling unconsumed row sitting there until its own TTL — this test seeds an
    // OLDER code (predating the one actually used) and asserts completePasswordReset's
    // cleanup swept it (consumed_at set), verified directly against the DB.
    // -----------------------------------------------------------------------
    @Test
    void resetPassword_success_invalidatesOlderOutstandingCode() {
        String email = "invalidateall-" + System.nanoTime() + "@reset.test";
        signup("InvalidateAll Co", email, "password123");
        UUID userId = userIdFor(email);
        UUID tenantId = tenantIdFor(email);

        // Seed an OLDER outstanding code first (predates the one actually issued/used below).
        String rawOlderCode = "654321";
        jdbc.update(
                "INSERT INTO password_reset_codes (tenant_id, user_id, code_hash, expires_at, created_at) " +
                "VALUES (?, ?, encode(sha256(?::bytea), 'hex'), now() + interval '15 minutes', now() - interval '1 minute')",
                tenantId, userId, rawOlderCode);

        String currentCode = requestResetAndCaptureCode(email);

        ResponseEntity<String> reset = resetPassword(email, currentCode, "newpassword1");
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);

        Integer stillOpen = jdbc.queryForObject(
                "SELECT COUNT(*) FROM password_reset_codes WHERE user_id = ? AND consumed_at IS NULL",
                Integer.class, userId);
        assertThat(stillOpen)
                .as("every outstanding code for the user must be consumed after a successful reset")
                .isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Throttle: a 4th request within the hour sends no new code.
    // -----------------------------------------------------------------------
    @Test
    void requestReset_fourthWithinHour_sendsNoNewCode() {
        String email = "throttle-" + System.nanoTime() + "@reset.test";
        signup("Throttle Co", email, "password123");
        UUID userId = userIdFor(email);
        UUID tenantId = tenantIdFor(email);

        // Seed 3 prior codes older than the 60s debounce window but within the last hour.
        for (int i = 0; i < 3; i++) {
            jdbc.update(
                    "INSERT INTO password_reset_codes (tenant_id, user_id, code_hash, expires_at, created_at) " +
                    "VALUES (?, ?, 'dummyhash" + i + "', now() + interval '15 minutes', now() - interval '10 minutes')",
                    tenantId, userId);
        }
        int countBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM password_reset_codes WHERE user_id = ?", Integer.class, userId);
        assertThat(countBefore).isEqualTo(3);

        reset(emailGateway);
        ResponseEntity<Map> resp = requestReset(email);
        assertThat(resp.getStatusCode())
                .as("the request itself must still look successful (enumeration-safe)")
                .isEqualTo(HttpStatus.OK);

        verify(emailGateway, never()).send(any(), any(), any());
        int countAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM password_reset_codes WHERE user_id = ?", Integer.class, userId);
        assertThat(countAfter).as("no new code row must be inserted once throttled").isEqualTo(3);
    }

    // -----------------------------------------------------------------------
    // Cross-tenant isolation WITH same-tenant positive control, in one test.
    // -----------------------------------------------------------------------
    @Test
    void resetPassword_crossTenantCodeRejected_sameTenantCodeAccepted() {
        String emailA = "crossA-" + System.nanoTime() + "@reset.test";
        String emailB = "crossB-" + System.nanoTime() + "@reset.test";
        signup("Cross Tenant A", emailA, "password123");
        signup("Cross Tenant B", emailB, "password123");

        String codeA = requestResetAndCaptureCode(emailA);

        // Negative: tenant A's code submitted against tenant B's email must fail.
        ResponseEntity<String> crossAttempt = resetPassword(emailB, codeA, "newpassword1");
        assertThat(crossAttempt.getStatusCode())
                .as("tenant A's code must not reset tenant B's user")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // Positive control: tenant A's code against tenant A's own email must succeed —
        // proves the negative result above is real isolation, not a broken endpoint.
        ResponseEntity<String> sameTenantAttempt = resetPassword(emailA, codeA, "newpassword1");
        assertThat(sameTenantAttempt.getStatusCode())
                .as("tenant A's code must still reset tenant A's own user")
                .isEqualTo(HttpStatus.OK);
    }

    // -----------------------------------------------------------------------
    // Path-2 null-password owner: forgot-password sends nothing, returns generic response.
    // -----------------------------------------------------------------------
    @Test
    void requestReset_passwordlessOwner_sendsNothing() {
        String email = "path2-" + System.nanoTime() + "@reset.test";
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantId, "Path2 Co");
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, ?, 'Provisioned Owner', ?, NULL, 'owner', true)",
                userId, tenantId, email);

        reset(emailGateway);
        ResponseEntity<Map> resp = requestReset(email);
        assertThat(resp.getStatusCode())
                .as("passwordless owner must still get the generic 200")
                .isEqualTo(HttpStatus.OK);

        verify(emailGateway, never()).send(any(), any(), any());
        Integer codeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM password_reset_codes WHERE user_id = ?", Integer.class, userId);
        assertThat(codeCount).as("no reset code should ever be issued for a passwordless owner").isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Isolation (V86): app_user has no direct SELECT on either token table —
    // only INSERT survives the REVOKE; every read/consume goes through a
    // SECURITY DEFINER function. Unlike every other test in this file (whose
    // @Autowired jdbc bean runs as plain postgres via this class's own
    // @DynamicPropertySource override — the standard "connect as postgres,
    // bypass RLS friction" pattern used throughout this test suite), these two
    // tests open a genuine app_user connection, mirroring
    // AuthIntegrationTest.crossTenantIsolationViaAppUserConnection. This is the
    // ONLY place in this file that actually exercises the REVOKE — the other
    // 9 tests' table reads/writes are unaffected by it regardless of whether
    // it's in place, because they never run as app_user.
    // -----------------------------------------------------------------------
    @Test
    void appUser_directSelectOnPasswordResetCodes_isDenied() throws Exception {
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "testpw")) {
            conn.setAutoCommit(false);
            assertThatThrownBy(() -> conn.createStatement().executeQuery("SELECT COUNT(*) FROM password_reset_codes"))
                    .as("app_user must not be able to SELECT password_reset_codes directly")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void appUser_directSelectOnMagicLinkTokens_isDenied() throws Exception {
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "testpw")) {
            conn.setAutoCommit(false);
            assertThatThrownBy(() -> conn.createStatement().executeQuery("SELECT COUNT(*) FROM magic_link_tokens"))
                    .as("app_user must not be able to SELECT magic_link_tokens directly")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }
}
