package com.traceability.identity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Signs and verifies HS256 JWTs.
 *
 * Access token claims: sub=user_id, tenant=tenant_id, role=role, exp=+15min, and — on a token
 * minted together with a refresh token — sid=that refresh token's row id (V142): the device's
 * session, so "log out" and the PIN switch can end exactly this device's refresh token even
 * though the refresh cookie is only ever sent to /auth/refresh.
 * The JWT secret must be ≥32 bytes; use JWT_SECRET env var in production.
 */
@Service
public class JwtService {

    private final byte[] secret;
    private final long accessTokenMinutes;

    public JwtService(
            @Value("${app.jwt.secret}") String base64Secret,
            @Value("${app.jwt.access-token-minutes:15}") long accessTokenMinutes) {
        this.secret = Base64.getDecoder().decode(base64Secret);
        this.accessTokenMinutes = accessTokenMinutes;
    }

    public String issueAccessToken(UUID userId, UUID tenantId, String role) {
        return issueAccessToken(userId, tenantId, role, Duration.ofMinutes(accessTokenMinutes));
    }

    /**
     * Same as {@link #issueAccessToken(UUID, UUID, String)} but with a caller-supplied TTL
     * instead of the {@code app.jwt.access-token-minutes} default — e.g. the demo session's
     * longer-lived, access-only (no refresh) token (FR-DEMO Day 2).
     */
    public String issueAccessToken(UUID userId, UUID tenantId, String role, Duration ttl) {
        return issueAccessToken(userId, tenantId, role, ttl, null);
    }

    /** An access token for the device session whose refresh token is row {@code sessionId}. */
    public String issueAccessToken(UUID userId, UUID tenantId, String role, UUID sessionId) {
        return issueAccessToken(userId, tenantId, role, Duration.ofMinutes(accessTokenMinutes), sessionId);
    }

    private String issueAccessToken(UUID userId, UUID tenantId, String role, Duration ttl, UUID sessionId) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .subject(userId.toString())
                    .claim("tenant", tenantId.toString())
                    .claim("role", role)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(ttl)));
            if (sessionId != null) builder.claim("sid", sessionId.toString());
            JWTClaimsSet claims = builder.build();
            JWSSigner signer = new MACSigner(secret);
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException("JWT signing failed", e);
        }
    }

    /** Returns the validated claims, or throws if the token is invalid or expired. */
    public JWTClaimsSet verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            JWSVerifier verifier = new MACVerifier(secret);
            if (!jwt.verify(verifier)) {
                throw new IllegalArgumentException("JWT signature invalid");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (claims.getExpirationTime().before(new Date())) {
                throw new IllegalArgumentException("JWT expired");
            }
            return claims;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("JWT verification failed: " + e.getMessage(), e);
        }
    }

    /**
     * The {@code sid} of a valid Bearer access token in {@code authorizationHeader}, or null (no
     * header, invalid token, or a token minted without a session — demo, pre-V142).
     */
    public UUID sessionIdOf(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) return null;
        try {
            Object sid = verify(authorizationHeader.substring(7)).getClaim("sid");
            return sid instanceof String s ? UUID.fromString(s) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public CustomUserDetails toUserDetails(JWTClaimsSet claims) {
        return new CustomUserDetails(
                UUID.fromString(claims.getSubject()),
                UUID.fromString((String) claims.getClaim("tenant")),
                (String) claims.getClaim("role"),
                null); // shopDomain — only set for SHOPIFY_EMBEDDED principals
    }
}
