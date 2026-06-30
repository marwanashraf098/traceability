package com.traceability.identity;

import com.traceability.identity.model.LoginRequest;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.tenancy.TenantContext;
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

    private final JdbcTemplate jdbc;
    private final AuthRepository repo;
    private final JwtService jwt;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public AuthService(JdbcTemplate jdbc, AuthRepository repo,
                       JwtService jwt, PasswordEncoder encoder, Clock clock) {
        this.jdbc    = jdbc;
        this.repo    = repo;
        this.jwt     = jwt;
        this.encoder = encoder;
        this.clock   = clock;
    }

    // ---- signup ----

    public TokenResponse signup(SignupRequest req) {
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
        UUID tenantId  = UUID.randomUUID();
        UUID userId    = UUID.randomUUID();
        String hash    = encoder.encode(req.password());
        Timestamp acceptedAt = Timestamp.from(Instant.now(clock));

        // runAs sets TenantContext so the @Transactional createTenantWithOwner fires GUC.
        return TenantContext.runAs(tenantId, () -> {
            repo.createTenantWithOwner(tenantId, req.tenantName(), userId,
                    req.name(), req.email(), hash,
                    PolicyVersions.PRIVACY, PolicyVersions.TERMS, acceptedAt);
            String refresh = repo.storeRefreshToken(userId, tenantId);
            return new TokenResponse(jwt.issueAccessToken(userId, tenantId, "owner"), refresh);
        });
    }

    // ---- login ----

    public TokenResponse login(LoginRequest req) {
        // auth_lookup_user is SECURITY DEFINER — works with no GUC set.
        UserCredentials creds = lookupUser(req.email());
        if (!encoder.matches(req.password(), creds.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bad credentials");
        }
        return TenantContext.runAs(creds.tenantId(), () -> {
            String refresh = repo.storeRefreshToken(creds.userId(), creds.tenantId());
            return new TokenResponse(
                    jwt.issueAccessToken(creds.userId(), creds.tenantId(), creds.role()),
                    refresh);
        });
    }

    // ---- refresh ----

    public TokenResponse refresh(String rawToken) {
        // lookup_refresh_token is SECURITY DEFINER — works with no GUC.
        RefreshRow row = lookupRefreshToken(AuthRepository.sha256(rawToken));
        if (row.revokedAt() != null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token revoked");
        }
        if (row.expiresAt().before(new java.util.Date())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token expired");
        }

        // All three repo calls run inside TenantContext so TenantAwareConnection fires
        // SET LOCAL before each @Transactional method — findUserRole needs this for RLS.
        return TenantContext.runAs(row.tenantId(), () -> {
            String role;
            try {
                role = repo.findUserRole(row.userId());
            } catch (EmptyResultDataAccessException e) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found or inactive");
            }
            repo.revokeRefreshToken(rawToken);
            String newRefresh = repo.storeRefreshToken(row.userId(), row.tenantId());
            return new TokenResponse(
                    jwt.issueAccessToken(row.userId(), row.tenantId(), role),
                    newRefresh);
        });
    }

    // ---- logout-everywhere ----

    public void logout(UUID userId) {
        // TenantContext already set by TenantContextFilter (request is authenticated).
        repo.revokeAllRefreshTokens(userId);
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
