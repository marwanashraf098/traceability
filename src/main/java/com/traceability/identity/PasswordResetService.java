package com.traceability.identity;

import com.traceability.notifications.EmailGateway;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Code-based forgot/reset password.
 *
 * Security model (mirrors MagicLinkService throughout):
 *   - requestReset(): auth_lookup_user(email) — same pre-session, cross-tenant basis login
 *     uses. Every non-issue path (no such user, Path-2 passwordless owner, throttled) returns
 *     silently — no sub-condition is ever distinguishable from the caller's side.
 *   - Code: 6 random digits, SHA-256 hash at rest (AuthRepository.sha256(), same as refresh
 *     tokens / magic links — a DB leak must not yield a usable code).
 *   - Consumption: verify_and_consume_reset_code SECURITY DEFINER (7th hatch, approved) —
 *     atomic FOR UPDATE lockout-check + hash-compare + consume, identical outcome shape to
 *     consume_magic_link for every failure sub-condition.
 *   - On success: password hash + refresh-token revocation ("logout everywhere") + consuming
 *     every other outstanding code for the user all happen in one AuthRepository transaction
 *     (completePasswordReset), inside TenantContext.runAs(tenantId) so the RLS-protected
 *     writes (users, refresh_tokens) fire SET LOCAL first. tenantId comes ONLY from the
 *     DEFINER function's result, never the request body.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int CODE_TTL_MINUTES         = 15;
    private static final int MAX_CODES_PER_HOUR        = 3;
    private static final int MIN_SECONDS_BETWEEN_CODES = 60;

    private static final String TEMPLATE_PATH = "emails/password-reset.html";
    private static final String CODE_TOKEN    = "{{RESET_CODE}}";
    private static final String EXPIRY_TOKEN  = "{{EXPIRY_MINUTES}}";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EmailGateway emailGateway;
    private final PasswordEncoder encoder;
    private final AuthRepository authRepository;

    private volatile String templateCache;

    public PasswordResetService(JdbcTemplate jdbc,
                                PlatformTransactionManager txm,
                                EmailGateway emailGateway,
                                PasswordEncoder encoder,
                                AuthRepository authRepository) {
        this.jdbc           = jdbc;
        this.tx             = new TransactionTemplate(txm);
        this.emailGateway   = emailGateway;
        this.encoder        = encoder;
        this.authRepository = authRepository;
    }

    /**
     * Always returns normally (200 at the controller) whether or not the email matched an
     * active, password-having user — enumeration-safe by construction, not by a caught
     * exception. Silently no-ops for: no such active user, a Path-2 passwordless owner
     * (nothing to reset), or a throttled request.
     */
    public void requestReset(String email) {
        if (email == null || email.isBlank()) return;
        String trimmed = email.trim();

        UserLookup user;
        try {
            user = jdbc.queryForObject(
                    "SELECT user_id, tenant_id, password_hash FROM auth_lookup_user(?)",
                    (rs, rn) -> new UserLookup(
                            UUID.fromString(rs.getString("user_id")),
                            UUID.fromString(rs.getString("tenant_id")),
                            rs.getString("password_hash")),
                    trimmed);
        } catch (EmptyResultDataAccessException e) {
            return;
        }

        if (user.passwordHash() == null) {
            return; // Path-2 owner — passwordless, nothing to reset
        }
        if (isThrottled(user.userId())) {
            return;
        }

        String rawCode = generateCode();
        String hash = AuthRepository.sha256(rawCode);
        Instant expiresAt = Instant.now().plus(CODE_TTL_MINUTES, ChronoUnit.MINUTES);

        // password_reset_codes is not under RLS — plain insert, no TenantContext needed
        // (same access path MagicLinkService.issueMagicLink() uses for magic_link_tokens).
        tx.execute(s -> {
            jdbc.update(
                    "INSERT INTO password_reset_codes (tenant_id, user_id, code_hash, expires_at) " +
                    "VALUES (?, ?, ?, ?)",
                    user.tenantId(), user.userId(), hash, Timestamp.from(expiresAt));
            return null;
        });

        String subject = "Reset your password · إعادة تعيين كلمة المرور";
        String body = template()
                .replace(CODE_TOKEN, rawCode)
                .replace(EXPIRY_TOKEN, String.valueOf(CODE_TTL_MINUTES));
        // Synchronous — the user is actively waiting on the code, unlike the welcome email.
        // Never enqueued via JobRunr: the plaintext code must not be persisted to a job store.
        emailGateway.send(trimmed, subject, body);
        log.info("Password reset code issued userId={} tenantId={}", user.userId(), user.tenantId());
    }

    /**
     * Verifies {email, code}, and on success sets newPassword, revokes all refresh tokens,
     * and consumes any other outstanding codes for the user. Any failure — bad email, bad
     * code, expired, locked — surfaces as the SAME generic 401, mirroring MAGIC_LINK_INVALID.
     */
    public void resetPassword(String email, String code, String newPassword) {
        if (email == null || email.isBlank() || code == null || code.isBlank()
                || newPassword == null) {
            throw resetInvalid();
        }
        if (newPassword.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be ≥8 chars");
        }

        String codeHash = AuthRepository.sha256(code.trim());

        record Row(UUID userId, UUID tenantId) {}
        Row row = tx.execute(s ->
            jdbc.query(
                "SELECT user_id, tenant_id FROM verify_and_consume_reset_code(?, ?)",
                rs -> rs.next()
                    ? new Row(rs.getObject("user_id", UUID.class), rs.getObject("tenant_id", UUID.class))
                    : null,
                email.trim(), codeHash));

        if (row == null) {
            throw resetInvalid();
        }

        String hash = encoder.encode(newPassword);
        TenantContext.runAs(row.tenantId(), () -> {
            authRepository.completePasswordReset(row.userId(), hash);
            return null;
        });
        log.info("Password reset completed userId={} tenantId={}", row.userId(), row.tenantId());
    }

    // ---- throttle ----

    /**
     * Refuses (silently) a new code if 3+ have been issued to this user in the last hour, or
     * the most recent one is younger than 60s (debounce against double-click / resend spam).
     * Plain app_user read — password_reset_codes carries no RLS or DEFINER-only restriction
     * (see V85's migration comment), so this matches the access path issuance itself uses.
     */
    private boolean isThrottled(UUID userId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COUNT(*) FILTER (WHERE created_at > now() - interval '1 hour') AS hour_count, " +
                "       MAX(created_at) AS latest " +
                "FROM password_reset_codes WHERE user_id = ?",
                userId);
        long hourCount = ((Number) row.get("hour_count")).longValue();
        if (hourCount >= MAX_CODES_PER_HOUR) return true;

        Timestamp latest = (Timestamp) row.get("latest");
        return latest != null
                && latest.toInstant().isAfter(Instant.now().minusSeconds(MIN_SECONDS_BETWEEN_CODES));
    }

    // ---- helpers ----

    private static String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private String template() {
        String t = templateCache;
        if (t == null) {
            synchronized (this) {
                t = templateCache;
                if (t == null) {
                    try {
                        t = new ClassPathResource(TEMPLATE_PATH).getContentAsString(StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to load " + TEMPLATE_PATH, e);
                    }
                    templateCache = t;
                }
            }
        }
        return t;
    }

    private static ResponseStatusException resetInvalid() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired reset code");
    }

    private record UserLookup(UUID userId, UUID tenantId, String passwordHash) {}
}
