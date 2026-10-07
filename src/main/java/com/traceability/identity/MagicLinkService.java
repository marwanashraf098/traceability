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
 * Magic-link sign-in: one-time links consumed by GET /auth/magic (consume_magic_link DEFINER).
 * Build D issues one with a successful embedded signup ({@link #issueSignInLink}).
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

    /** Build D: the "Open Traced" link issued with a successful embedded signup lives at most this long. */
    public static final int SIGN_IN_LINK_TTL_MINUTES = 10;

    /**
     * Generates a magic-link token, persists its SHA-256 hash, and emails the raw token
     * to the owner. The raw token never touches the database.
     *
     * No application caller since Build D removed Shopify-first auto-provisioning (the
     * passwordless owner it served no longer exists); kept with its tests (ShopifyMagicLinkTest).
     */
    public void issueMagicLink(UUID userId, UUID tenantId) {
        String rawToken = storeToken(userId, tenantId, ttlMinutes);

        // Look up owner email under tenant context (users is RLS-protected). Inside a transaction:
        // the tenant GUC is set only when one begins (TenantAwareConnection) — without it app_user
        // sees no users row.
        String email = TenantContext.runAs(tenantId, () -> tx.execute(s ->
            jdbc.queryForObject(
                "SELECT email FROM users WHERE id = ? AND tenant_id = ?",
                String.class, userId, tenantId)));

        String link = appUrl + "/auth/magic?token=" + rawToken;
        emailGateway.sendMagicLink(email, link);
        log.info("Magic link issued userId={} tenantId={}", userId, tenantId);
    }

    /**
     * Build D: the one-time "Open Traced" sign-in link for the owner an embedded signup just
     * created — returned to that signup response ONLY (EmbeddedOnboardingService.signup), never
     * emailed and never issued anywhere else. Same table, same hash-at-rest, same single-use
     * consume (/auth/magic → consume_magic_link) as {@link #issueMagicLink}; TTL
     * {@value #SIGN_IN_LINK_TTL_MINUTES} minutes. Never log the returned URL.
     */
    public String issueSignInLink(UUID userId, UUID tenantId) {
        String rawToken = storeToken(userId, tenantId, SIGN_IN_LINK_TTL_MINUTES);
        log.info("Sign-in link issued after embedded signup userId={} tenantId={}", userId, tenantId);
        return appUrl + "/auth/magic?token=" + rawToken;
    }

    /** CSPRNG 128-bit token; only its SHA-256 is stored. magic_link_tokens is not under RLS. */
    private String storeToken(UUID userId, UUID tenantId, int ttl) {
        byte[] bytes = new byte[16]; // 128 bits
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = AuthRepository.sha256(rawToken);
        Instant expiresAt = Instant.now().plus(ttl, ChronoUnit.MINUTES);
        tx.execute(s -> {
            jdbc.update(
                "INSERT INTO magic_link_tokens (tenant_id, user_id, token_hash, expires_at) " +
                "VALUES (?, ?, ?, ?)",
                tenantId, userId, hash, Timestamp.from(expiresAt));
            return null;
        });
        return rawToken;
    }

    /**
     * Validates and consumes a raw token, issues access + refresh tokens.
     *
     * The SECURITY DEFINER function is the only reader of magic_link_tokens — as of V86,
     * this is enforced at the grant level (app_user has INSERT only), not just by code
     * discipline.
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

        // Get current role under tenant RLS context — inside a transaction, so the tenant GUC is set
        // (Build D found this ran with no GUC: on app_user the role read empty and every link failed).
        String role = TenantContext.runAs(row.tenantId(), () -> tx.execute(s -> {
            try {
                return jdbc.queryForObject(
                    "SELECT role FROM users WHERE id = ? AND tenant_id = ? AND active = true",
                    String.class, row.userId(), row.tenantId());
            } catch (org.springframework.dao.EmptyResultDataAccessException e) {
                return null;
            }
        }));

        if (role == null) {
            throw magicLinkInvalid();
        }

        return TenantContext.runAs(row.tenantId(), () -> {
            AuthRepository.IssuedRefresh issued =
                authRepository.issueRefreshToken(row.userId(), row.tenantId(), "magic_link", null);
            String access  = jwtService.issueAccessToken(row.userId(), row.tenantId(), role, issued.id());
            String refresh = issued.raw();
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
