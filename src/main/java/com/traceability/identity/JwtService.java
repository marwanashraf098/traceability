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
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Signs and verifies HS256 JWTs.
 *
 * Access token claims: sub=user_id, tenant=tenant_id, role=role, exp=+15min.
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
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(userId.toString())
                    .claim("tenant", tenantId.toString())
                    .claim("role", role)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(accessTokenMinutes * 60)))
                    .build();
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

    public CustomUserDetails toUserDetails(JWTClaimsSet claims) {
        return new CustomUserDetails(
                UUID.fromString(claims.getSubject()),
                UUID.fromString((String) claims.getClaim("tenant")),
                (String) claims.getClaim("role"));
    }
}
