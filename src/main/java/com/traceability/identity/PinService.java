package com.traceability.identity;

import com.traceability.identity.model.PinRequest;
import com.traceability.identity.model.TokenResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Worker PIN switch with 5-failure/15-minute lockout.
 *
 * PINs are per-tenant unique (enforced at the application level) and stored
 * as argon2id hashes. Because argon2 uses per-password salts, PIN lookup is
 * O(n) over active users with pins — acceptable for pilot scale (~5–20 users).
 *
 * On success:  issues a new 15-min access JWT attributed to the PIN user;
 *              the session attribution changes for the lifetime of that token.
 * On failure:  increments pin_fail_count; at 5 failures locks for 15 minutes
 *              and logs a manager-visible event (notification wired in later sprints).
 * After 15-min idle: client's token expires naturally (15-min access token).
 */
@Service
public class PinService {

    private static final int  MAX_FAILURES    = 5;
    private static final long LOCKOUT_MINUTES = 15;

    private final JdbcTemplate   jdbc;
    private final JwtService     jwtService;
    private final PasswordEncoder encoder;

    public PinService(JdbcTemplate jdbc, JwtService jwtService, PasswordEncoder encoder) {
        this.jdbc       = jdbc;
        this.jwtService = jwtService;
        this.encoder    = encoder;
    }

    /**
     * Validates the PIN against all active users in the current tenant.
     * TenantContext must already be set (request goes through JwtAuthenticationFilter
     * + TenantContextFilter before reaching this service).
     */
    // noRollbackFor: ResponseStatusException is unchecked, so Spring would roll back the
    // pin_fail_count UPDATE before propagating the error. We want the counter committed.
    @Transactional(noRollbackFor = org.springframework.web.server.ResponseStatusException.class)
    public TokenResponse switchPin(UUID callerTenantId, PinRequest req) {
        if (req.pin() == null || req.pin().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pin required");
        }

        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT id, pin_code, pin_fail_count, pin_locked_until " +
                "FROM users WHERE tenant_id = ? AND pin_code IS NOT NULL AND active = true",
                callerTenantId);

        for (Map<String, Object> row : candidates) {
            UUID      uid    = (UUID) row.get("id");
            Timestamp locked = (Timestamp) row.get("pin_locked_until");

            // Check lockout before PIN matching — a locked account rejects all attempts.
            if (locked != null && locked.toInstant().isAfter(Instant.now())) {
                long secondsLeft = locked.toInstant().getEpochSecond() - Instant.now().getEpochSecond();
                throw new ResponseStatusException(HttpStatus.LOCKED,
                        "PIN locked. Try again in " + (secondsLeft / 60 + 1) + " min");
            }

            if (!encoder.matches(req.pin(), (String) row.get("pin_code"))) {
                continue;
            }
            // Match found and not locked — reset failure counter.
            jdbc.update(
                    "UPDATE users SET pin_fail_count = 0, pin_locked_until = NULL WHERE id = ?", uid);

            String role = (String) jdbc.queryForObject(
                    "SELECT role FROM users WHERE id = ?", String.class, uid);
            return new TokenResponse(
                    jwtService.issueAccessToken(uid, callerTenantId, role), null);
        }

        // No match — if exactly one user has a PIN, attribute the failure to them.
        // For proper attribution with multi-PIN tenants, client should pass a user hint in future.
        if (candidates.size() == 1) {
            Map<String, Object> only = candidates.get(0);
            UUID uid  = (UUID) only.get("id");
            int fails = ((Number) only.get("pin_fail_count")).intValue() + 1;
            if (fails >= MAX_FAILURES) {
                Timestamp lockUntil = Timestamp.from(
                        Instant.now().plusSeconds(LOCKOUT_MINUTES * 60));
                jdbc.update(
                        "UPDATE users SET pin_fail_count = ?, pin_locked_until = ? WHERE id = ?",
                        fails, lockUntil, uid);
                throw new ResponseStatusException(HttpStatus.LOCKED,
                        "PIN locked for " + LOCKOUT_MINUTES + " minutes after " + MAX_FAILURES + " failures");
            }
            jdbc.update("UPDATE users SET pin_fail_count = ? WHERE id = ?", fails, uid);
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid PIN");
    }
}
