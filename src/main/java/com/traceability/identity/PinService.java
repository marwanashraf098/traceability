package com.traceability.identity;

import com.traceability.account.AuditService;
import com.traceability.identity.model.PinRequest;
import com.traceability.identity.model.TokenResponse;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Worker PIN switch with 5-failure/15-minute lockout.
 *
 * PINs are per-tenant unique (enforced at the application level) and stored
 * as argon2id hashes. The client identifies the target user first (name-picker,
 * Phase C), then submits {userId, pin} — matching is a single-row lookup, not a
 * guess-by-exclusion loop, so lockout attribution is correct with any number of
 * PIN-holding users in the tenant.
 *
 * On success:  revokes the incoming device's refresh token (if any) and mints a
 *              new one for the switched-in user, and issues a new 15-min access
 *              JWT attributed to them — both the access token AND the refresh
 *              cookie now identify the switched-in user, so a later /auth/refresh
 *              (page reload, idle timeout) does not silently revert to whoever
 *              originally logged in on this device.
 * On failure:  increments pin_fail_count for the identified user; at 5 failures
 *              locks for 15 minutes and writes one audit_log row (action=pin_locked)
 *              via AuditService — the single write path for privileged-action audit.
 * After 15-min idle: client's token expires naturally (15-min access token).
 */
@Service
public class PinService {

    private static final int  MAX_FAILURES    = 5;
    private static final long LOCKOUT_MINUTES = 15;

    private final JdbcTemplate    jdbc;
    private final JwtService      jwtService;
    private final PasswordEncoder encoder;
    private final AuthRepository  authRepo;
    private final AuditService    auditService;

    public PinService(JdbcTemplate jdbc, JwtService jwtService, PasswordEncoder encoder,
                      AuthRepository authRepo, AuditService auditService) {
        this.jdbc         = jdbc;
        this.jwtService   = jwtService;
        this.encoder      = encoder;
        this.authRepo     = authRepo;
        this.auditService = auditService;
    }

    /**
     * Validates {userId, pin} against the identified user in the current tenant.
     * TenantContext must already be set (request goes through JwtAuthenticationFilter
     * + TenantContextFilter before reaching this service).
     *
     * @param rawRefreshCookie the caller's current traced_refresh cookie value (device's
     *                         existing refresh token), or null/blank if none was sent.
     *                         Only read on a successful match — revoked and replaced with
     *                         a token minted for the switched-in user.
     */
    // noRollbackFor: ResponseStatusException is unchecked, so Spring would roll back the
    // pin_fail_count UPDATE (and, on lockout, the audit_log INSERT) before propagating the
    // error. We want both committed.
    @Transactional(noRollbackFor = org.springframework.web.server.ResponseStatusException.class)
    public TokenResponse switchPin(UUID callerTenantId, PinRequest req, String rawRefreshCookie) {
        if (req.userId() == null || req.userId().isBlank() ||
            req.pin() == null || req.pin().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId and pin required");
        }

        UUID targetUserId;
        try {
            targetUserId = UUID.fromString(req.userId());
        } catch (IllegalArgumentException e) {
            // Malformed userId — same generic surface as "no such PIN holder", no leak.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid PIN");
        }

        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(
                    "SELECT id, pin_code, pin_fail_count, pin_locked_until " +
                    "FROM users WHERE id = ? AND tenant_id = ? AND pin_code IS NOT NULL AND active = true",
                    targetUserId, callerTenantId);
        } catch (EmptyResultDataAccessException e) {
            // Not a PIN-holder in this tenant — same generic surface, no leak of which userIds exist.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid PIN");
        }

        // Check lockout before PIN matching — a locked account rejects all attempts,
        // including a correct PIN.
        Timestamp locked = (Timestamp) row.get("pin_locked_until");
        if (locked != null && locked.toInstant().isAfter(Instant.now())) {
            long secondsLeft = locked.toInstant().getEpochSecond() - Instant.now().getEpochSecond();
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    "PIN locked. Try again in " + (secondsLeft / 60 + 1) + " min");
        }

        if (encoder.matches(req.pin(), (String) row.get("pin_code"))) {
            jdbc.update(
                    "UPDATE users SET pin_fail_count = 0, pin_locked_until = NULL WHERE id = ?",
                    targetUserId);

            String role = (String) jdbc.queryForObject(
                    "SELECT role FROM users WHERE id = ?", String.class, targetUserId);

            // Rotate the device's refresh identity to the switched-in user: revoke whatever
            // token this device currently holds (the original login's, if any) and mint a
            // fresh one for targetUserId — mirrors AuthService.refresh()'s revoke-and-replace,
            // just targeting the PIN-matched user instead of the stored row's original owner.
            if (rawRefreshCookie != null && !rawRefreshCookie.isBlank()) {
                authRepo.revokeRefreshToken(rawRefreshCookie);
            }
            String newRefresh = authRepo.storeRefreshToken(targetUserId, callerTenantId);

            return new TokenResponse(
                    jwtService.issueAccessToken(targetUserId, callerTenantId, role), newRefresh);
        }

        // Wrong PIN — increment the IDENTIFIED user's failure count. No guess-by-exclusion:
        // correctness no longer depends on how many other PIN-holders exist in the tenant.
        int fails = ((Number) row.get("pin_fail_count")).intValue() + 1;
        if (fails >= MAX_FAILURES) {
            Timestamp lockUntil = Timestamp.from(Instant.now().plusSeconds(LOCKOUT_MINUTES * 60));
            jdbc.update(
                    "UPDATE users SET pin_fail_count = ?, pin_locked_until = ? WHERE id = ?",
                    fails, lockUntil, targetUserId);
            // Written exactly once, at the lock transition (not on every failed attempt).
            // Actor = the locked user themselves: target_type/target_id are already "user"/
            // their id, and AuditService.list()'s actorFilter is the only filter it exposes
            // for this shape — using the locked user as actor is what makes "show me
            // everything that happened to this worker" findable through the existing
            // audit-log query surface; a null (system) actor would only be visible by
            // scanning all pin_locked rows and matching target_id by hand.
            auditService.record(targetUserId, "pin_locked", "user", targetUserId.toString(),
                    Map.of("failCount", fails, "lockedUntil", lockUntil.toInstant().toString()));
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    "PIN locked for " + LOCKOUT_MINUTES + " minutes after " + MAX_FAILURES + " failures");
        }
        jdbc.update("UPDATE users SET pin_fail_count = ? WHERE id = ?", fails, targetUserId);
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid PIN");
    }
}
