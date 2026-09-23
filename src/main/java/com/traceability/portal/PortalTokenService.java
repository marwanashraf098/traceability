package com.traceability.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Stateless, HMAC-SHA256-signed returns-portal token issued by a successful order lookup.
 * Claims: tenantId, orderId, exp (now + 30 min). Format: base64url(json) "." base64url(mac).
 * No cookies, no server-side session — the token is the only proof a portal caller passed
 * lookup. {@link #verify} is what Step 4b's request endpoints use.
 *
 * Secret: portal.token-secret (env PORTAL_TOKEN_SECRET). Fails fast at startup when missing
 * or shorter than 32 bytes — there is deliberately no default.
 */
@Service
public class PortalTokenService {

    public static final Duration TTL = Duration.ofMinutes(30);
    private static final String HMAC = "HmacSHA256";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    public record Claims(UUID tenantId, UUID orderId, Instant expiresAt) {}

    private final byte[] secret;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PortalTokenService(@Value("${portal.token-secret:}") String secret,
                              ObjectMapper mapper, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                "PORTAL_TOKEN_SECRET must be set (at least 32 bytes) — refusing to start the returns portal without it");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
        this.clock  = clock;
    }

    public String issue(UUID tenantId, UUID orderId) {
        ObjectNode claims = mapper.createObjectNode();
        claims.put("tid", tenantId.toString());
        claims.put("oid", orderId.toString());
        claims.put("exp", clock.instant().plus(TTL).getEpochSecond());
        String payload = B64.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
        return payload + "." + B64.encodeToString(mac(payload));
    }

    /**
     * Verifies signature, expiry and — when {@code expectedTenantId} is non-null — that the token
     * was issued for that tenant (the slug the caller is using). Empty on any failure.
     */
    public Optional<Claims> verify(String token, UUID expectedTenantId) {
        if (token == null) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) return Optional.empty();
        String payload = token.substring(0, dot);
        try {
            byte[] given = B64D.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(given, mac(payload))) return Optional.empty();
            JsonNode c = mapper.readTree(B64D.decode(payload));
            Instant exp = Instant.ofEpochSecond(c.path("exp").asLong(0));
            if (!clock.instant().isBefore(exp)) return Optional.empty();
            UUID tenantId = UUID.fromString(c.path("tid").asText());
            UUID orderId  = UUID.fromString(c.path("oid").asText());
            if (expectedTenantId != null && !expectedTenantId.equals(tenantId)) return Optional.empty();
            return Optional.of(new Claims(tenantId, orderId, exp));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] mac(String payload) {
        try {
            Mac m = Mac.getInstance(HMAC);
            m.init(new SecretKeySpec(secret, HMAC));
            return m.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
