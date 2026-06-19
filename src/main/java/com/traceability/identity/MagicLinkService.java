package com.traceability.identity;

import com.traceability.identity.model.TokenResponse;
import com.traceability.notifications.EmailGateway;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * Magic-link sign-in for Path-2 (Shopify-first) provisioned owners who have no password.
 *
 * Security model:
 *   - Token: CSPRNG 128 bits, base64url. Raw token goes ONLY in the email link.
 *   - At rest: SHA-256 hash only (same as refresh tokens — a DB leak must not yield usable links).
 *   - Single-use: consumed atomically in consume_magic_link SECURITY DEFINER (FOR UPDATE guard).
 *   - TTL: configurable, default 60 min (email delivery + user action needs slack).
 *   - Bound to user_id + tenant_id: issued JWT is built from those rows, never from request input.
 *   - All invalid sub-conditions (not-found / expired / consumed) return identical MAGIC_LINK_INVALID.
 */
@Service
public class MagicLinkService {

    private static final Logger log = LoggerFactory.getLogger(MagicLinkService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EmailGateway emailGateway;
    private final JwtService jwtService;
    private final AuthRepository authRepository;

    @Value("${app.magic-link.ttl-minutes:60}")
    private int ttlMinutes;

    @Value("${shopify.app-url:http://localhost:5173}")
    private String appUrl;

    public MagicLinkService(JdbcTemplate jdbc,
                            PlatformTransactionManager txm,
                            EmailGateway emailGateway,
                            JwtService jwtService,
                            AuthRepository authRepository) {
        this.jdbc           = jdbc;
        this.tx             = new TransactionTemplate(txm);
        this.emailGateway   = emailGateway;
        this.jwtService     = jwtService;
        this.authRepository = authRepository;
    }

    /**
     * Generates a magic-link token, persists its SHA-256 hash, and emails the raw token
     * to the provisioned owner. The raw token never touches the database.
     *
     * Called from ShopifyOAuthService after a Path-2-new provision.
     */
    public void issueMagicLink(UUID userId, UUID tenantId) {
        byte[] bytes = new byte[16]; // 128 bits
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = AuthRepository.sha256(rawToken);
        Instant expiresAt = Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES);

        // Insert hash only — magic_link_tokens is not under RLS, no GUC required.
        tx.execute(s -> {
            jdbc.update(
                "INSERT INTO magic_link_tokens (tenant_id, user_id, token_hash, expires_at) " +
                "VALUES (?, ?, ?, ?)",
                tenantId, userId, hash, Timestamp.from(expiresAt));
            return null;
        });

        // Look up owner email under tenant context (users is RLS-protected).
        String email = TenantContext.runAs(tenantId, () ->
            jdbc.queryForObject(
                "SELECT email FROM users WHERE id = ? AND tenant_id = ?",
                String.class, userId, tenantId));

        String link = appUrl + "/auth/magic?token=" + rawToken;
        emailGateway.sendMagicLink(email, link);
        log.info("Magic link issued userId={} tenantId={} expiresAt={}", userId, tenantId, expiresAt);
    }

    /**
     * Validates and consumes a raw token, issues access + refresh tokens.
     *
     * The SECURITY DEFINER function is the only reader of magic_link_tokens.
     * All invalid sub-conditions (not-found / expired / consumed) are indistinguishable
     * to the caller — MAGIC_LINK_INVALID in every case.
     */
    public TokenResponse consumeMagicLink(String rawToken) {
        String hash = AuthRepository.sha256(rawToken.trim());

        record Row(UUID userId, UUID tenantId) {}
        Row row = tx.execute(s ->
            jdbc.query(
                "SELECT user_id, tenant_id FROM consume_magic_link(?)",
                rs -> rs.next()
                    ? new Row(rs.getObject("user_id",  UUID.class),
                              rs.getObject("tenant_id", UUID.class))
                    : null,
                hash));

        if (row == null) {
            throw magicLinkInvalid();
        }

        // Get current role under tenant RLS context.
        String role = TenantContext.runAs(row.tenantId(), () -> {
            try {
                return jdbc.queryForObject(
                    "SELECT role FROM users WHERE id = ? AND tenant_id = ? AND active = true",
                    String.class, row.userId(), row.tenantId());
            } catch (org.springframework.dao.EmptyResultDataAccessException e) {
                return null;
            }
        });

        if (role == null) {
            throw magicLinkInvalid();
        }

        return TenantContext.runAs(row.tenantId(), () -> {
            String access  = jwtService.issueAccessToken(row.userId(), row.tenantId(), role);
            String refresh = authRepository.storeRefreshToken(row.userId(), row.tenantId());
            log.info("Magic link consumed userId={} tenantId={}", row.userId(), row.tenantId());
            return new TokenResponse(access, refresh);
        });
    }

    private static com.traceability.integrations.shopify.ShopifyOAuthException magicLinkInvalid() {
        return new com.traceability.integrations.shopify.ShopifyOAuthException(
            com.traceability.integrations.shopify.ShopifyOAuthException.Code.MAGIC_LINK_INVALID,
            "Magic link is invalid, expired, or already used",
            "رابط تسجيل الدخول غير صالح أو منتهي الصلاحية أو مستخدم مسبقاً",
            HttpStatus.UNAUTHORIZED);
    }
}
