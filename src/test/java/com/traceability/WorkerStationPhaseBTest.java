package com.traceability;

import com.nimbusds.jwt.JWTClaimsSet;
import com.traceability.identity.JwtService;
import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.identity.model.PinRequest;
import com.traceability.identity.model.SignupRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Worker Station Phase B — revert-to-confirm proofs for the three fixes:
 *   FIX 1 — PIN switch rotates the traced_refresh cookie to the switched-in worker,
 *           so a later /auth/refresh derives the worker's identity, not the original
 *           login's.
 *   FIX 2 — PinRequest carries {userId, pin}; lockout attribution is a single-row
 *           lookup by userId, correct regardless of how many other PIN-holders exist
 *           in the tenant.
 *   FIX 3 — a lockout writes exactly one audit_log row (action=pin_locked) via
 *           AuditService, not on every failed attempt.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class WorkerStationPhaseBTest {

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
    @Autowired TestRestTemplate  rest;
    @Autowired JdbcTemplate      jdbc;
    @Autowired JwtService        jwtService;
    @Autowired PasswordEncoder   encoder;

    private String base() { return "http://localhost:" + port; }

    private static final String COOKIE_PREFIX = "traced_refresh=";

    // ─────────────────────────────────────────────────────────────────────────
    // FIX 1 — PIN switch rotates the refresh cookie to the worker
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void pinSwitch_rotatesRefreshCookieToWorker_soRefreshDerivesWorkerIdentity() {
        // Real signup over HTTP — we need a genuine server-set httpOnly cookie,
        // not a minted JWT, since this proves the COOKIE identity, not just the
        // access token.
        SignupRequest signupReq = new SignupRequest(
                "Wsb Co", "Owner One", "wsb-owner@test.com", "01011111111", "password123", true);
        ResponseEntity<AccessTokenResponse> signupResp = rest.postForEntity(
                base() + "/api/v1/auth/signup", signupReq, AccessTokenResponse.class);
        assertThat(signupResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String ownerAccessToken = signupResp.getBody().accessToken();
        String ownerRawCookie = extractRawCookie(signupResp);   // "traced_refresh=<value>"
        String ownerOldToken  = bareToken(ownerRawCookie);

        JWTClaimsSet ownerClaims = jwtService.verify(ownerAccessToken);
        UUID tenantId = UUID.fromString((String) ownerClaims.getClaim("tenant"));
        UUID ownerId  = UUID.fromString(ownerClaims.getSubject());

        UUID workerId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role, pin_code, active) " +
                "VALUES (?, ?, 'Worker One', 'wsb-worker1@test.com', 'x', 'worker', ?, true)",
                workerId, tenantId, encoder.encode("4471"));

        HttpHeaders switchHeaders = new HttpHeaders();
        switchHeaders.setBearerAuth(ownerAccessToken);
        switchHeaders.add(HttpHeaders.COOKIE, ownerRawCookie);
        switchHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<AccessTokenResponse> switchResp = rest.exchange(
                base() + "/api/v1/auth/pin", HttpMethod.POST,
                new HttpEntity<>(new PinRequest(workerId.toString(), "4471"), switchHeaders),
                AccessTokenResponse.class);
        assertThat(switchResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        String newRawCookie = extractRawCookie(switchResp);
        assertThat(newRawCookie)
                .as("PIN switch must set a NEW traced_refresh cookie")
                .isNotNull();
        String newToken = bareToken(newRawCookie);
        assertThat(newToken).isNotEqualTo(ownerOldToken);

        // Old (pre-switch) refresh token must now be revoked — no lingering
        // owner-identity token left usable on this device.
        java.sql.Timestamp oldRevokedAt = jdbc.queryForObject(
                "SELECT revoked_at FROM refresh_tokens WHERE token_hash = ?",
                java.sql.Timestamp.class, sha256(ownerOldToken));
        assertThat(oldRevokedAt)
                .as("the pre-switch (owner) refresh token must be revoked after PIN switch")
                .isNotNull();

        // New refresh token belongs to the worker, not the owner.
        UUID newTokenUserId = jdbc.queryForObject(
                "SELECT user_id FROM refresh_tokens WHERE token_hash = ? AND revoked_at IS NULL",
                UUID.class, sha256(newToken));
        assertThat(newTokenUserId).isEqualTo(workerId);

        // The core proof: /auth/refresh with the NEW cookie must derive the WORKER's
        // identity. On current (pre-fix) behavior this returns the OWNER's id (RED);
        // on the fix it returns the WORKER's id (GREEN).
        HttpHeaders refreshHeaders = new HttpHeaders();
        refreshHeaders.add(HttpHeaders.COOKIE, newRawCookie);
        ResponseEntity<AccessTokenResponse> refreshResp = rest.exchange(
                base() + "/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(null, refreshHeaders), AccessTokenResponse.class);
        assertThat(refreshResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        JWTClaimsSet refreshedClaims = jwtService.verify(refreshResp.getBody().accessToken());
        assertThat(refreshedClaims.getSubject())
                .as("access token minted by /auth/refresh after a PIN switch must be the WORKER's, not the original owner's")
                .isEqualTo(workerId.toString())
                .isNotEqualTo(ownerId.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FIX 2 — multi-worker lockout correctness (userId-hinted, no guess-by-exclusion)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void multiWorkerTenant_wrongPinLocksOnlyTheIdentifiedWorker() {
        UUID tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'WsbFix2Tenant')", tenantId);

        UUID ownerId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                "VALUES (?, ?, 'Owner', ?, 'x', 'owner')",
                ownerId, tenantId, "fix2-owner+" + ownerId + "@wsb.test");
        String ownerToken = jwtService.issueAccessToken(ownerId, tenantId, "owner");

        UUID workerA = UUID.randomUUID();
        UUID workerB = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role, pin_code, active) " +
                "VALUES (?, ?, 'Worker A', ?, 'x', 'worker', ?, true)",
                workerA, tenantId, "fix2-a+" + workerA + "@wsb.test", encoder.encode("1111"));
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role, pin_code, active) " +
                "VALUES (?, ?, 'Worker B', ?, 'x', 'worker', ?, true)",
                workerB, tenantId, "fix2-b+" + workerB + "@wsb.test", encoder.encode("2222"));

        HttpHeaders headers = bearerHeaders(ownerToken);

        // 5 wrong PINs against worker A specifically.
        ResponseEntity<String> fifth = null;
        for (int i = 0; i < 5; i++) {
            fifth = rest.exchange(base() + "/api/v1/auth/pin", HttpMethod.POST,
                    new HttpEntity<>(new PinRequest(workerA.toString(), "0000"), headers), String.class);
        }
        assertThat(fifth.getStatusCode().value())
                .as("5th wrong PIN against worker A (with worker B also PIN-holding) must lock A (423). " +
                    "On current code, candidates.size()!=1 so no counter moves and no lock ever fires.")
                .isEqualTo(423);

        Integer failCountA = jdbc.queryForObject(
                "SELECT pin_fail_count FROM users WHERE id = ?", Integer.class, workerA);
        java.sql.Timestamp lockedUntilA = jdbc.queryForObject(
                "SELECT pin_locked_until FROM users WHERE id = ?", java.sql.Timestamp.class, workerA);
        assertThat(failCountA).isEqualTo(5);
        assertThat(lockedUntilA).isNotNull();

        // Positive control: worker B, correct PIN, unaffected by A's failures.
        ResponseEntity<AccessTokenResponse> bSwitch = rest.exchange(
                base() + "/api/v1/auth/pin", HttpMethod.POST,
                new HttpEntity<>(new PinRequest(workerB.toString(), "2222"), headers),
                AccessTokenResponse.class);
        assertThat(bSwitch.getStatusCode())
                .as("worker B's correct PIN must still switch successfully — A's lockout is A's alone")
                .isEqualTo(HttpStatus.OK);
        Integer failCountB = jdbc.queryForObject(
                "SELECT pin_fail_count FROM users WHERE id = ?", Integer.class, workerB);
        assertThat(failCountB).isZero();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FIX 3 — exactly one audit_log row at the lock transition, none before it
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void lockout_writesExactlyOneAuditLogRow_onlyAtTheLockTransition() {
        UUID tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'WsbFix3Tenant')", tenantId);

        UUID ownerId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                "VALUES (?, ?, 'Owner', ?, 'x', 'owner')",
                ownerId, tenantId, "fix3-owner+" + ownerId + "@wsb.test");
        String ownerToken = jwtService.issueAccessToken(ownerId, tenantId, "owner");

        UUID workerA = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role, pin_code, active) " +
                "VALUES (?, ?, 'Worker A', ?, 'x', 'worker', ?, true)",
                workerA, tenantId, "fix3-a+" + workerA + "@wsb.test", encoder.encode("3333"));

        HttpHeaders headers = bearerHeaders(ownerToken);

        for (int i = 0; i < 4; i++) {
            rest.exchange(base() + "/api/v1/auth/pin", HttpMethod.POST,
                    new HttpEntity<>(new PinRequest(workerA.toString(), "0000"), headers), String.class);
        }
        Integer countBeforeLock = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'pin_locked' AND target_id = ?",
                Integer.class, tenantId, workerA.toString());
        assertThat(countBeforeLock)
                .as("no audit_log row before the lock transition (failures 1-4)")
                .isZero();

        ResponseEntity<String> fifth = rest.exchange(base() + "/api/v1/auth/pin", HttpMethod.POST,
                new HttpEntity<>(new PinRequest(workerA.toString(), "0000"), headers), String.class);
        assertThat(fifth.getStatusCode().value()).isEqualTo(423);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT actor_user_id, target_type, target_id, metadata::text AS metadata FROM audit_log " +
                "WHERE tenant_id = ? AND action = 'pin_locked' AND target_id = ?",
                tenantId, workerA.toString());
        assertThat(rows)
                .as("exactly one audit_log row written, at the lock transition — none written on current code")
                .hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("target_type")).isEqualTo("user");
        assertThat(row.get("target_id")).isEqualTo(workerA.toString());
        assertThat((String) row.get("metadata")).contains("failCount").contains("lockedUntil");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static HttpHeaders bearerHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private static String extractRawCookie(ResponseEntity<?> resp) {
        String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        if (setCookie == null) return null;
        return setCookie.split(";")[0]; // "traced_refresh=<value>"
    }

    private static String bareToken(String rawCookie) {
        return rawCookie.substring(COOKIE_PREFIX.length());
    }

    private static String sha256(String raw) {
        return com.traceability.identity.AuthRepository.sha256(raw);
    }
}
