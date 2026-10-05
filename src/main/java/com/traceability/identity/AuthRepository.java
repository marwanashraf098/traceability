package com.traceability.identity;

import com.traceability.identity.model.SignupAttribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * All DB-writing auth operations. Every public method is @Transactional so
 * TenantAwareConnection fires SET LOCAL app.current_tenant before any query.
 *
 * INVARIANT: callers must set TenantContext before calling any method here.
 * A missing context means RLS returns zero rows — writes will violate the
 * WITH CHECK policy and throw a constraint error.
 */
@Repository
public class AuthRepository {

    private static final Logger log = LoggerFactory.getLogger(AuthRepository.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final int refreshTokenDays;
    private final byte[] rotationKey;

    /** How long a just-rotated token presented again is answered with its successor (V142). */
    static final int ROTATION_GRACE_SECONDS = 30;

    public AuthRepository(JdbcTemplate jdbc,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${app.jwt.refresh-token-days:30}") int refreshTokenDays,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${app.jwt.secret}") String base64JwtSecret) {
        this.jdbc = jdbc;
        this.refreshTokenDays = refreshTokenDays;
        // A key of its own for rotation, derived from the JWT secret (never used to sign anything else).
        this.rotationKey = hmac(Base64.getDecoder().decode(base64JwtSecret), "traced-refresh-rotation-v1");
    }

    /** A refresh token as minted: its row id (the access token's {@code sid}) and the raw value. */
    public record IssuedRefresh(UUID id, String raw) {}

    /** Outcome of {@link #rotate}: the successor to hand out, or why the presented token is refused. */
    public record Rotation(IssuedRefresh successor, String rejectReason) {
        static Rotation ok(IssuedRefresh s)       { return new Rotation(s, null); }
        static Rotation rejected(String reason)   { return new Rotation(null, reason); }
        public boolean ok()                       { return successor != null; }
    }

    /**
     * Creates tenant + owner user + default Main Warehouse in one transaction, plus the
     * tenant's Meta ad attribution row when there is one (null = none).
     */
    @Transactional
    public UUID createTenantWithOwner(UUID tenantId, String tenantName,
                                      UUID userId, String name, String email, String phone,
                                      String passwordHash,
                                      String privacyVersion, String termsVersion,
                                      java.sql.Timestamp acceptedAt,
                                      SignupAttribution attribution) {
        jdbc.update(
                "INSERT INTO tenants (id, name, plan, status) VALUES (?, ?, 'trial', 'trial')",
                tenantId, tenantName);
        jdbc.update(
                "INSERT INTO users " +
                "(id, tenant_id, name, email, phone, password_hash, role, " +
                " accepted_privacy_version, accepted_terms_version, accepted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'owner', ?, ?, ?)",
                userId, tenantId, name, email, phone, passwordHash,
                privacyVersion, termsVersion, acceptedAt);
        jdbc.update(
                "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                "VALUES (gen_random_uuid(), ?, 'Main Warehouse', 'warehouse', true, true)",
                tenantId);
        if (attribution != null) insertAttribution(tenantId, attribution);
        return userId;
    }

    /**
     * Behind a savepoint: attribution data must never fail a signup, so a failed insert is
     * rolled back on its own and the tenant/user/location above still commit.
     */
    private void insertAttribution(UUID tenantId, SignupAttribution a) {
        TransactionStatus tx = TransactionAspectSupport.currentTransactionStatus();
        Object savepoint = tx.createSavepoint();
        try {
            jdbc.update(
                    "INSERT INTO tenant_ad_attribution " +
                    "(tenant_id, fbp, fbc, fbclid, utm_source, utm_medium, utm_campaign, utm_term, utm_content, " +
                    " client_ip, client_user_agent) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    tenantId, a.fbp(), a.fbc(), a.fbclid(), a.utmSource(), a.utmMedium(), a.utmCampaign(),
                    a.utmTerm(), a.utmContent(), a.clientIp(), a.clientUserAgent());
            tx.releaseSavepoint(savepoint);
        } catch (RuntimeException e) {
            tx.rollbackToSavepoint(savepoint);
            log.warn("Signup ad attribution not stored for tenant {}: {}", tenantId, e.getClass().getSimpleName());
        }
    }

    /** Stores a new refresh token; returns the raw (un-hashed) token string. */
    @Transactional
    public String storeRefreshToken(UUID userId, UUID tenantId) {
        return issueRefreshToken(userId, tenantId, null, null).raw();
    }

    /**
     * Stores a new refresh token minted by {@code createdVia} (login / refresh / pin / signup /
     * magic_link) for the device sending {@code userAgent}; returns its id and raw value.
     */
    @Transactional
    public IssuedRefresh issueRefreshToken(UUID userId, UUID tenantId, String createdVia, String userAgent) {
        return insertRefreshToken(userId, tenantId, generateRawToken(), createdVia, userAgent);
    }

    private IssuedRefresh insertRefreshToken(UUID userId, UUID tenantId, String raw,
                                             String createdVia, String userAgent) {
        Instant expires = Instant.now().plus(refreshTokenDays, ChronoUnit.DAYS);
        UUID id = jdbc.queryForObject(
                "INSERT INTO refresh_tokens (tenant_id, user_id, token_hash, expires_at, created_via, user_agent) " +
                "VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                UUID.class, tenantId, userId, sha256(raw), Timestamp.from(expires), createdVia,
                truncateUserAgent(userAgent));
        return new IssuedRefresh(id, raw);
    }

    /**
     * Rotates the presented refresh token (row {@code tokenId}, raw value {@code raw}).
     *
     * The claim is the conditional UPDATE: exactly one caller turns a live token into 'rotated' and
     * mints its successor, in the same transaction. A concurrent caller presenting the same token
     * blocks on the row lock, then finds it rotated. Within {@link #ROTATION_GRACE_SECONDS} of that
     * rotation it gets the SAME successor back (two tabs, or a rotation whose response was lost on
     * the way to the device) instead of a 401 — so one rotation always yields exactly one new pair.
     *
     * The successor's raw value is derived, not stored: HMAC(rotationKey, raw). Only a caller that
     * holds the predecessor's raw value can recompute it, only within the grace window, and only
     * while the successor itself is still live. Outside the window a reused token is refused.
     */
    @Transactional
    public Rotation rotate(UUID tokenId, String raw, String userAgent) {
        java.util.List<java.util.Map<String, Object>> claimed = jdbc.queryForList(
                "UPDATE refresh_tokens SET revoked_at = now(), revoked_reason = 'rotated' " +
                "WHERE id = ? AND revoked_at IS NULL AND expires_at > now() " +
                "RETURNING user_id, tenant_id",
                tokenId);
        if (!claimed.isEmpty()) {
            UUID userId   = (UUID) claimed.get(0).get("user_id");
            UUID tenantId = (UUID) claimed.get(0).get("tenant_id");
            IssuedRefresh successor = insertRefreshToken(userId, tenantId, successorOf(raw), "refresh", userAgent);
            jdbc.update("UPDATE refresh_tokens SET replaced_by = ? WHERE id = ?", successor.id(), tokenId);
            return Rotation.ok(successor);
        }

        java.util.List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT revoked_reason, replaced_by, " +
                "       (revoked_at IS NOT NULL) AS revoked, " +
                "       (revoked_at > now() - make_interval(secs => ?)) AS in_grace, " +
                "       (expires_at <= now()) AS expired " +
                "FROM refresh_tokens WHERE id = ?",
                ROTATION_GRACE_SECONDS, tokenId);
        if (rows.isEmpty()) return Rotation.rejected("unknown_token");
        java.util.Map<String, Object> r = rows.get(0);
        if (!Boolean.TRUE.equals(r.get("revoked"))) return Rotation.rejected("expired");
        String reason = (String) r.get("revoked_reason");
        if (!"rotated".equals(reason)) return Rotation.rejected("revoked:" + (reason == null ? "unknown" : reason));
        UUID replacedBy = (UUID) r.get("replaced_by");
        if (!Boolean.TRUE.equals(r.get("in_grace")) || replacedBy == null) return Rotation.rejected("reused_outside_grace");
        Integer live = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE id = ? AND revoked_at IS NULL AND expires_at > now()",
                Integer.class, replacedBy);
        if (live == null || live == 0) return Rotation.rejected("successor_ended");
        return Rotation.ok(new IssuedRefresh(replacedBy, successorOf(raw)));
    }

    /**
     * Looks up the role of an active user. Runs @Transactional so TenantAwareConnection
     * fires the SET LOCAL GUC — caller must have TenantContext set before calling.
     */
    @Transactional(readOnly = true)
    public String findUserRole(UUID userId) {
        return jdbc.queryForObject(
                "SELECT role FROM users WHERE id = ? AND active = true",
                String.class, userId);
    }

    /** Revokes one live refresh token by its raw value; returns rows revoked (0 or 1). */
    @Transactional
    public int revokeRefreshToken(String rawToken, String reason) {
        return jdbc.update(
                "UPDATE refresh_tokens SET revoked_at = now(), revoked_reason = ? " +
                "WHERE token_hash = ? AND revoked_at IS NULL",
                reason, sha256(rawToken));
    }

    /**
     * Revokes one live refresh token by its row id (an access token's {@code sid}), only when it
     * belongs to {@code userId}; returns rows revoked (0 or 1).
     */
    @Transactional
    public int revokeRefreshTokenById(UUID tokenId, UUID userId, String reason) {
        return jdbc.update(
                "UPDATE refresh_tokens SET revoked_at = now(), revoked_reason = ? " +
                "WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
                reason, tokenId, userId);
    }

    /** Stamps the minting device's User-Agent on a token issued without one (the PIN switch). */
    @Transactional
    public void stampUserAgent(String rawToken, String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return;
        jdbc.update("UPDATE refresh_tokens SET user_agent = ? WHERE token_hash = ? AND user_agent IS NULL",
                truncateUserAgent(userAgent), sha256(rawToken));
    }

    /** Revokes ALL active refresh tokens for a user ("Log out of all devices"); returns the count. */
    @Transactional
    public int revokeAllRefreshTokens(UUID userId) {
        return jdbc.update(
                "UPDATE refresh_tokens SET revoked_at = now(), revoked_reason = 'logout_all' " +
                "WHERE user_id = ? AND revoked_at IS NULL",
                userId);
    }

    /**
     * Completes a code-based password reset in one transaction: sets the new hash
     * and revokes every active refresh token (session invalidation). Mirrors
     * createTenantWithOwner's shape (several statements, one @Transactional
     * method) so both writes commit or roll back together.
     *
     * Invalidating every OTHER outstanding reset code for the user is NOT done
     * here — as of V86, app_user has no direct UPDATE on password_reset_codes.
     * That cleanup is folded into verify_and_consume_reset_code (SECURITY
     * DEFINER), atomically with the match itself, before this method is ever
     * called.
     */
    @Transactional
    public void completePasswordReset(UUID userId, String passwordHash) {
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordHash, userId);
        jdbc.update(
                "UPDATE refresh_tokens SET revoked_at = now(), revoked_reason = 'password_reset' " +
                "WHERE user_id = ? AND revoked_at IS NULL",
                userId);
    }

    // ---- helpers ----

    /** The successor a rotation mints for {@code raw} — recomputable only with the server key. */
    String successorOf(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(rotationKey, raw));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String truncateUserAgent(String ua) {
        if (ua == null || ua.isBlank()) return null;
        return ua.length() <= 512 ? ua : ua.substring(0, 512);
    }

    static String generateRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
