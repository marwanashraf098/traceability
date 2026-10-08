package com.traceability.integrations.shopify;

import com.traceability.security.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * The ONE way an OAuth / session-token-exchange token becomes stored columns: access token,
 * refresh token (both AES-encrypted) and both expiry times. Every writer uses it — OAuth callback
 * (insert / re-link), embedded token refresh, embedded signup, pending links — so none of them can
 * store a non-expiring token: {@link #of} refuses a response without a refresh token or expiries
 * BEFORE anything is encrypted or written. (Custom-app client-credentials tokens are a different
 * kind — 24 h, no refresh by design — and never go through here.)
 */
public record ShopifyStoredToken(String accessTokenEncrypted, Timestamp accessTokenExpiresAt,
                                 String refreshTokenEncrypted, Timestamp refreshTokenExpiresAt,
                                 String scopes) {

    private static final Logger log = LoggerFactory.getLogger(ShopifyStoredToken.class);

    public static ShopifyStoredToken of(String shopDomain, ShopifyGateway.TokenResponse t, EncryptionService encryption) {
        requireExpiring(shopDomain, t);
        Instant now = Instant.now();
        return new ShopifyStoredToken(
            encryption.encrypt(t.accessToken()),
            Timestamp.from(now.plusSeconds(t.expiresIn())),
            encryption.encrypt(t.refreshToken()),
            Timestamp.from(now.plusSeconds(t.refreshTokenExpiresIn())),
            t.grantedScopes());
    }

    /**
     * @throws ShopifyNonExpiringTokenException unless the token is an expiring offline token: access
     *         token, refresh token, and positive expiries for both. Logged at ERROR — loud on purpose.
     */
    public static void requireExpiring(String shopDomain, ShopifyGateway.TokenResponse t) {
        String problem = t == null ? "no token"
            : t.accessToken() == null || t.accessToken().isBlank() ? "no access token"
            : t.refreshToken() == null || t.refreshToken().isBlank() ? "no refresh token — a non-expiring token"
            : t.expiresIn() <= 0 ? "no access-token expiry"
            : t.refreshTokenExpiresIn() <= 0 ? "no refresh-token expiry"
            : null;
        if (problem != null) {
            log.error("SHOPIFY_NON_EXPIRING_TOKEN shop={} refused: {} — nothing stored", shopDomain, problem);
            throw new ShopifyNonExpiringTokenException(shopDomain, problem);
        }
    }
}
