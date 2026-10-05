package com.traceability.identity;

import com.traceability.identity.model.LoginRequest;
import com.traceability.identity.model.SignupAttribution;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.notifications.WelcomeEmailJob;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Business logic for signup, login, token refresh, and logout.
 *
 * Pattern for methods that need a DB write (refresh token INSERT):
 *   1. Do the cross-tenant credential lookup with no GUC (SECURITY DEFINER fn).
 *   2. Set TenantContext.
 *   3. Call AuthRepository method (@Transactional) — the wrapper fires SET LOCAL.
 *   4. TenantContext.runAs clears the context in finally.
 *
 * This split is necessary because @Transactional on a same-bean method is not
 * proxied by Spring AOP; AuthRepository is a separate bean so proxying works.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final JdbcTemplate jdbc;
    private final AuthRepository repo;
    private final JwtService jwt;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final JobScheduler jobScheduler;
    private final WelcomeEmailJob welcomeEmailJob;

    public AuthService(JdbcTemplate jdbc, AuthRepository repo,
                       JwtService jwt, PasswordEncoder encoder, Clock clock,
                       JobScheduler jobScheduler, WelcomeEmailJob welcomeEmailJob) {
        this.jdbc    = jdbc;
        this.repo    = repo;
        this.jwt     = jwt;
        this.encoder = encoder;
        this.clock   = clock;
        this.jobScheduler     = jobScheduler;
        this.welcomeEmailJob  = welcomeEmailJob;
    }

    // ---- signup ----

    public TokenResponse signup(SignupRequest req) {
        return signup(req, null, null);
    }

    /** clientIp / userAgent are only stored with Meta ad attribution (never for @tracedtech.com). */
    public TokenResponse signup(SignupRequest req, String clientIp, String userAgent) {
        if (req.email() == null || req.password() == null || req.tenantName() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantName, email, password required");
        }
        if (req.password().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be ≥8 chars");
        }
        if (!req.consent()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "You must accept the Privacy Policy and Terms of Service to create an account");
        }
        String phone = normalizeEgyptianPhoneToE164(req.phone());
        if (phone == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A valid Egyptian mobile number is required");
        }
        UUID tenantId  = UUID.randomUUID();
        UUID userId    = UUID.randomUUID();
        String hash    = encoder.encode(req.password());
        Timestamp acceptedAt = Timestamp.from(Instant.now(clock));
        // Our own team's accounts (App Store reviewers, internal tests) are never attributed.
        SignupAttribution attribution = isInternalEmail(req.email())
                ? null : SignupAttribution.from(req.attribution(), clientIp, userAgent);

        // runAs sets TenantContext so the @Transactional createTenantWithOwner fires GUC.
        // A rollback (e.g. duplicate email) throws out of runAs, so the enqueue below is
        // only reached once createTenantWithOwner has actually committed.
        TokenResponse tokens = TenantContext.runAs(tenantId, () -> {
            repo.createTenantWithOwner(tenantId, req.tenantName(), userId,
                    req.name(), req.email(), phone, hash,
                    PolicyVersions.PRIVACY, PolicyVersions.TERMS, acceptedAt, attribution);
            AuthRepository.IssuedRefresh refresh = repo.issueRefreshToken(userId, tenantId, "signup", userAgent);
            return new TokenResponse(jwt.issueAccessToken(userId, tenantId, "owner", refresh.id()), refresh.raw());
        });
        jobScheduler.enqueue(() -> welcomeEmailJob.run(req.email(), req.name()));
        return tokens;
    }

    static boolean isInternalEmail(String email) {
        return email != null && email.trim().toLowerCase(java.util.Locale.ROOT).endsWith("@tracedtech.com");
    }

    /**
     * Accepts +20/0020/0-prefixed or bare-10-digit Egyptian mobile input and normalizes to
     * canonical +20 E.164 (e.g. "+201012345678"). Mirrors the accepted input shapes of
     * ShipmentLinkService.normalizePhone(), but that helper's 01XXXXXXXXX local-form output
     * is a different convention (Bosta/order matching) — signup needs E.164 for the future
     * OTP target, so this is a separate, purpose-scoped normalizer rather than a shared util.
     */
    static String normalizeEgyptianPhoneToE164(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("0020") && digits.length() == 14) {
            digits = "0" + digits.substring(4);
        } else if (digits.startsWith("20") && digits.length() == 12) {
            digits = "0" + digits.substring(2);
        } else if (digits.length() == 10) {
            digits = "0" + digits;
        }
        if (digits.startsWith("01") && digits.length() == 11) {
            return "+20" + digits.substring(1);
        }
        return null;
    }

    // ---- login ----

    public TokenResponse login(LoginRequest req) {
        return login(req, null);
    }

    /** A login never touches the user's other sessions — it only adds this device's. */
    public TokenResponse login(LoginRequest req, String userAgent) {
        // auth_lookup_user is SECURITY DEFINER — works with no GUC set.
        UserCredentials creds = lookupUser(req.email());
        if (!encoder.matches(req.password(), creds.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bad credentials");
        }
        return TenantContext.runAs(creds.tenantId(), () -> {
            AuthRepository.IssuedRefresh refresh =
                    repo.issueRefreshToken(creds.userId(), creds.tenantId(), "login", userAgent);
            return new TokenResponse(
                    jwt.issueAccessToken(creds.userId(), creds.tenantId(), creds.role(), refresh.id()),
                    refresh.raw());
        });
    }

    // ---- refresh ----

    public TokenResponse refresh(String rawToken) {
        return refresh(rawToken, null);
    }

    /**
     * Rotates the device's refresh token (AuthRepository.rotate — a token presented again within
     * the 30 s grace gets the same successor). Every refusal writes one REFRESH_REJECTED line
     * with its reason and the token's id prefix (the hash prefix when the token is unknown).
     */
    public TokenResponse refresh(String rawToken, String userAgent) {
        String hash = AuthRepository.sha256(rawToken);
        RefreshRow row;
        try {
            // lookup_refresh_token is SECURITY DEFINER — works with no GUC.
            row = lookupRefreshToken(hash);
        } catch (ResponseStatusException e) {
            throw rejected("unknown_token", "hash:" + hash.substring(0, 8), null);
        }
        if (row.expiresAt().before(new java.util.Date())) {
            throw rejected("expired", shortId(row.id()), row.userId());
        }

        // All repo calls run inside TenantContext so TenantAwareConnection fires
        // SET LOCAL before each @Transactional method — findUserRole needs this for RLS.
        return TenantContext.runAs(row.tenantId(), () -> {
            String role;
            try {
                role = repo.findUserRole(row.userId());
            } catch (EmptyResultDataAccessException e) {
                throw rejected("user_inactive", shortId(row.id()), row.userId());
            }
            AuthRepository.Rotation rotation = repo.rotate(row.id(), rawToken, userAgent);
            if (!rotation.ok()) {
                throw rejected(rotation.rejectReason(), shortId(row.id()), row.userId());
            }
            return new TokenResponse(
                    jwt.issueAccessToken(row.userId(), row.tenantId(), role, rotation.successor().id()),
                    rotation.successor().raw());
        });
    }

    private static ResponseStatusException rejected(String reason, String token, UUID userId) {
        log.warn("REFRESH_REJECTED reason={} token={} user={}", reason, token,
                userId == null ? "-" : shortId(userId));
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh refused");
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    // ---- logout ----

    /**
     * "Log out" — THIS device only: revokes the refresh token of this device's session (the access
     * token's sid) and, when the cookie reached us, the token it carries. The user's other devices
     * — a warehouse tablet in station mode above all — keep working. Returns tokens revoked.
     */
    public int logoutDevice(UUID userId, UUID sessionId, String rawCookie) {
        // TenantContext already set by TenantContextFilter (request is authenticated).
        int revoked = 0;
        if (sessionId != null) revoked += repo.revokeRefreshTokenById(sessionId, userId, "logout_device");
        if (rawCookie != null && !rawCookie.isBlank()) revoked += repo.revokeRefreshToken(rawCookie.trim(), "logout_device");
        log.info("LOGOUT_DEVICE user={} session={} revoked={}", shortId(userId),
                sessionId == null ? "-" : shortId(sessionId), revoked);
        return revoked;
    }

    /** "Log out of all devices" — revokes every live refresh token of the user. */
    public int logoutAll(UUID userId) {
        int revoked = repo.revokeAllRefreshTokens(userId);
        log.info("LOGOUT_ALL user={} revoked={}", shortId(userId), revoked);
        return revoked;
    }

    // ---- private helpers ----

    private UserCredentials lookupUser(String email) {
        try {
            return jdbc.queryForObject(
                    "SELECT user_id, tenant_id, password_hash, role FROM auth_lookup_user(?)",
                    (rs, rn) -> new UserCredentials(
                            UUID.fromString(rs.getString("user_id")),
                            UUID.fromString(rs.getString("tenant_id")),
                            rs.getString("password_hash"),
                            rs.getString("role")),
                    email);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bad credentials");
        }
    }

    private RefreshRow lookupRefreshToken(String hash) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, tenant_id, user_id, expires_at, revoked_at FROM lookup_refresh_token(?)",
                    (rs, rn) -> new RefreshRow(
                            UUID.fromString(rs.getString("id")),
                            UUID.fromString(rs.getString("tenant_id")),
                            UUID.fromString(rs.getString("user_id")),
                            rs.getTimestamp("expires_at"),
                            rs.getTimestamp("revoked_at")),
                    hash);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }

    // Internal value types
    record UserCredentials(UUID userId, UUID tenantId, String passwordHash, String role) {}
    record RefreshRow(UUID id, UUID tenantId, UUID userId, Timestamp expiresAt, Timestamp revokedAt) {}
}
