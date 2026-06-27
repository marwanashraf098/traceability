package com.traceability.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final int refreshTokenDays;

    public AuthRepository(JdbcTemplate jdbc,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${app.jwt.refresh-token-days:30}") int refreshTokenDays) {
        this.jdbc = jdbc;
        this.refreshTokenDays = refreshTokenDays;
    }

    /** Creates tenant + owner user + default Main Warehouse in one transaction. */
    @Transactional
    public UUID createTenantWithOwner(UUID tenantId, String tenantName,
                                      UUID userId, String name, String email,
                                      String passwordHash,
                                      String privacyVersion, String termsVersion,
                                      java.sql.Timestamp acceptedAt) {
        jdbc.update(
                "INSERT INTO tenants (id, name, plan, status) VALUES (?, ?, 'trial', 'trial')",
                tenantId, tenantName);
        jdbc.update(
                "INSERT INTO users " +
                "(id, tenant_id, name, email, password_hash, role, " +
                " accepted_privacy_version, accepted_terms_version, accepted_at) " +
                "VALUES (?, ?, ?, ?, ?, 'owner', ?, ?, ?)",
                userId, tenantId, name, email, passwordHash,
                privacyVersion, termsVersion, acceptedAt);
        jdbc.update(
                "INSERT INTO locations (id, tenant_id, name, type, is_default) " +
                "VALUES (gen_random_uuid(), ?, 'Main Warehouse', 'warehouse', true)",
                tenantId);
        return userId;
    }

    /** Stores a new refresh token; returns the raw (un-hashed) token string. */
    @Transactional
    public String storeRefreshToken(UUID userId, UUID tenantId) {
        String raw = generateRawToken();
        String hash = sha256(raw);
        Instant expires = Instant.now().plus(refreshTokenDays, ChronoUnit.DAYS);
        jdbc.update(
                "INSERT INTO refresh_tokens (tenant_id, user_id, token_hash, expires_at) " +
                "VALUES (?, ?, ?, ?)",
                tenantId, userId, hash, Timestamp.from(expires));
        return raw;
    }

    /** Revokes one refresh token by its raw value (called on rotation). */
    @Transactional
    public void revokeRefreshToken(String rawToken) {
        jdbc.update(
                "UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = ?",
                sha256(rawToken));
    }

    /** Revokes ALL active refresh tokens for a user (logout-everywhere). */
    @Transactional
    public void revokeAllRefreshTokens(UUID userId) {
        jdbc.update(
                "UPDATE refresh_tokens SET revoked_at = now() " +
                "WHERE user_id = ? AND revoked_at IS NULL",
                userId);
    }

    // ---- helpers ----

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
