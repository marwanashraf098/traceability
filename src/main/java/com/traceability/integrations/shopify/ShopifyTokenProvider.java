package com.traceability.integrations.shopify;

import com.traceability.security.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Single choke point for obtaining a live Shopify access token for a store.
 *
 * Two-phase strategy:
 *   1. Quick read (no row lock) inside a transaction — if the token is fresh (> 5 min
 *      from expiry), decrypt and return immediately. This is the hot path.
 *   2. If near-expiry or expired — SELECT FOR UPDATE on the stores row, re-check inside
 *      the lock (another thread may have refreshed while we waited), then call Shopify's
 *      refresh endpoint and write back all four token fields in the same transaction.
 *
 * For connection_type='custom_app_cc': the CC re-exchange path branches BEFORE the
 * refreshToken null-check because CC stores have null refresh tokens by design. The CC
 * path uses exchangeClientCredentials (clientId + clientSecret → short-lived access token)
 * and only writes back access_token_encrypted + access_token_expires_at (no refresh token).
 *
 * Concurrency guard: SELECT FOR UPDATE ensures only one thread per store enters the
 * Shopify refresh call. Because refresh tokens are single-use rotating (Shopify
 * invalidates the old token and issues a new one), concurrent rotation would produce
 * one valid pair and one permanently-rejected refresh attempt. The row lock collapses
 * concurrent refreshers into a single network call; the second thread, after acquiring
 * the lock, re-checks expiry and returns the fresh token written by the first.
 *
 * TenantContext must be set by the caller (both job types use TenantContext.runAs,
 * which keeps the ThreadLocal active for the duration of the job body). This service
 * does not set or clear TenantContext itself.
 */
@Service
public class ShopifyTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ShopifyTokenProvider.class);

    // Refresh when the access token has <= 5 minutes remaining.
    private static final long REFRESH_BUFFER_SECONDS = 300;

    private static final String SELECT_QUICK = """
            SELECT shop_domain, access_token_encrypted, access_token_expires_at,
                   refresh_token_encrypted, refresh_token_expires_at, status,
                   connection_type, client_id_encrypted, api_secret_encrypted
            FROM stores WHERE id = ?
            """;

    private static final String SELECT_FOR_UPDATE = """
            SELECT shop_domain, access_token_encrypted, access_token_expires_at,
                   refresh_token_encrypted, refresh_token_expires_at, status,
                   connection_type, client_id_encrypted, api_secret_encrypted
            FROM stores WHERE id = ? FOR UPDATE
            """;

    private static final String UPDATE_TOKENS = """
            UPDATE stores SET
                access_token_encrypted  = ?,
                access_token_expires_at = ?,
                refresh_token_encrypted = ?,
                refresh_token_expires_at = ?
            WHERE id = ?
            """;

    private static final String UPDATE_ACCESS_ONLY = """
            UPDATE stores SET
                access_token_encrypted  = ?,
                access_token_expires_at = ?
            WHERE id = ?
            """;

    private static final String SET_NEEDS_REAUTH =
            "UPDATE stores SET status = 'needs_reauth' WHERE id = ?";

    private final JdbcTemplate jdbc;
    private final ShopifyGateway shopifyGateway;
    private final EncryptionService encryptionService;
    private final TransactionTemplate tx;

    public ShopifyTokenProvider(JdbcTemplate jdbc,
                                 ShopifyGateway shopifyGateway,
                                 EncryptionService encryptionService,
                                 PlatformTransactionManager txm) {
        this.jdbc              = jdbc;
        this.shopifyGateway    = shopifyGateway;
        this.encryptionService = encryptionService;
        this.tx                = new TransactionTemplate(txm);
    }

    /**
     * Returns a valid plaintext Shopify access token for the given store,
     * refreshing (or re-exchanging for CC stores) via Shopify's token endpoint
     * if the current token is near-expiry.
     *
     * TenantContext must be active (set by caller via TenantContext.runAs or TenantContext.set).
     *
     * @throws ShopifyStoreNeedsReauthException if the store needs to be reconnected:
     *         legacy install with no refresh token, expired refresh token, or
     *         Shopify permanently rejected the refresh/CC exchange (4xx).
     * @throws ShopifyTransientException if Shopify returned 5xx or the connection timed out —
     *         the credentials are still valid and the caller may retry later.
     */
    public String getValidToken(UUID storeId) {
        StoreTokenRow row = quickRead(storeId);

        if (row == null) {
            throw new ShopifyStoreNeedsReauthException(storeId.toString(),
                "Store not found or not visible under current tenant");
        }
        if ("needs_reauth".equals(row.status())) {
            throw new ShopifyStoreNeedsReauthException(row.shopDomain(),
                "Store is marked needs_reauth — merchant must reconnect");
        }

        // Fresh token: has an expiry set and it's > 5 min away.
        if (isFresh(row.accessTokenExpiresAt())) {
            return encryptionService.decrypt(row.accessTokenEncrypted());
        }

        // Branch on connection type BEFORE the refreshToken null check.
        // CC stores have null refresh tokens by design, not as an error condition.
        String connType = row.connectionType() != null ? row.connectionType() : "oauth";
        if ("custom_app_cc".equals(connType)) {
            if (row.clientIdEncrypted() == null || row.apiSecretEncrypted() == null) {
                markNeedsReauth(storeId);
                throw new ShopifyStoreNeedsReauthException(row.shopDomain(),
                    "CC credentials missing — reconnect via the custom-app card");
            }
            return reExchangeWithLock(storeId, row.shopDomain());
        }

        // OAuth path: refresh token required.
        if (row.refreshTokenEncrypted() == null) {
            markNeedsReauth(storeId);
            throw new ShopifyStoreNeedsReauthException(row.shopDomain(),
                "No refresh token available — legacy install must reinstall with expiring tokens");
        }
        if (row.refreshTokenExpiresAt() != null && row.refreshTokenExpiresAt().isBefore(Instant.now())) {
            markNeedsReauth(storeId);
            throw new ShopifyStoreNeedsReauthException(row.shopDomain(),
                "Refresh token expired (90-day limit) — merchant must reinstall");
        }

        // OAuth refresh path — acquire row lock.
        return refreshWithLock(storeId, row.shopDomain());
    }

    // ---- private: quick read (no lock) ----------------------------------

    private StoreTokenRow quickRead(UUID storeId) {
        return tx.execute(s ->
            jdbc.query(SELECT_QUICK, rs -> rs.next() ? mapRow(rs) : null, storeId));
    }

    // ---- private: CC re-exchange under row lock -------------------------

    /**
     * Re-exchanges the CC token under a row lock.
     *
     * When the gateway returns 4xx (credentials invalid), we need to commit a
     * needs_reauth flag AND propagate the exception. We can't do both inside a
     * single tx.execute — throwing causes the transaction to roll back, which
     * would lose any UPDATE within the same tx.
     *
     * Solution: use a holder to carry the exchange result (new token OR the
     * "needs_reauth required" signal) out of the lambda; commit the tx cleanly;
     * then mark needs_reauth or rethrow as appropriate after the tx commits.
     */
    private String reExchangeWithLock(UUID storeId, String shopDomain) {
        // Holder: either the new token string, or null if reauth is needed.
        // ShopifyTransientException is rethrown directly (inside tx) since
        // we don't need to mark reauth — the outer caller will handle it.
        final String[] tokenHolder = {null};
        final boolean[] needsReauth = {false};
        final ShopifyStoreNeedsReauthException[] reauthEx = {null};
        final ShopifyTransientException[] transientEx = {null};

        tx.execute(s -> {
            // Acquire row lock — blocks concurrent re-exchangers on the same store.
            StoreTokenRow row = jdbc.query(SELECT_FOR_UPDATE,
                rs -> rs.next() ? mapRow(rs) : null, storeId);

            if (row == null) {
                reauthEx[0] = new ShopifyStoreNeedsReauthException(shopDomain,
                    "Store not found under lock");
                return null;
            }

            // Double-checked locking: another thread may have re-exchanged while we waited.
            if (isFresh(row.accessTokenExpiresAt())) {
                tokenHolder[0] = encryptionService.decrypt(row.accessTokenEncrypted());
                return null;
            }

            // Re-check guard conditions inside the lock.
            if ("needs_reauth".equals(row.status())) {
                reauthEx[0] = new ShopifyStoreNeedsReauthException(row.shopDomain(),
                    "Store marked needs_reauth under lock");
                return null;
            }
            if (row.clientIdEncrypted() == null || row.apiSecretEncrypted() == null) {
                needsReauth[0] = true;
                reauthEx[0] = new ShopifyStoreNeedsReauthException(row.shopDomain(),
                    "CC credentials missing under lock — reconnect via the custom-app card");
                return null;
            }

            String clientId     = encryptionService.decrypt(row.clientIdEncrypted());
            String clientSecret = encryptionService.decrypt(row.apiSecretEncrypted());

            // Call Shopify CC exchange while holding the row lock.
            // Bounded to 10 s by tokenRestClient in ShopifyHttpGateway.
            ShopifyGateway.TokenResponse tokens;
            try {
                tokens = shopifyGateway.exchangeClientCredentials(row.shopDomain(), clientId, clientSecret);
            } catch (ShopifyStoreNeedsReauthException e) {
                // 4xx — credentials revoked or app removed from store.
                // We must mark needs_reauth, but cannot do it here inside this tx (the tx commits
                // cleanly, then we call markNeedsReauth separately after the tx returns).
                needsReauth[0] = true;
                reauthEx[0] = e;
                return null;
            } catch (ShopifyTransientException e) {
                // 5xx / network — do NOT mark needs_reauth; rethrow after tx.
                transientEx[0] = e;
                return null;
            }

            Timestamp newExpiresAt = Timestamp.from(Instant.now().plusSeconds(tokens.expiresIn()));
            jdbc.update(UPDATE_ACCESS_ONLY,
                encryptionService.encrypt(tokens.accessToken()),
                newExpiresAt,
                storeId);

            log.info("Shopify CC token re-exchanged for store {} ({})", storeId, row.shopDomain());
            tokenHolder[0] = tokens.accessToken();
            return null;
        });

        // After tx committed: handle outcomes.
        if (transientEx[0] != null) {
            throw transientEx[0];
        }
        if (needsReauth[0]) {
            markNeedsReauth(storeId);  // separate committed tx
        }
        if (reauthEx[0] != null) {
            throw reauthEx[0];
        }
        return tokenHolder[0];
    }

    // ---- private: OAuth lock + refresh + write-back ---------------------

    private String refreshWithLock(UUID storeId, String shopDomain) {
        return tx.execute(s -> {
            // Acquire row lock — blocks concurrent refreshers on the same store.
            StoreTokenRow row = jdbc.query(SELECT_FOR_UPDATE,
                rs -> rs.next() ? mapRow(rs) : null, storeId);

            if (row == null) {
                throw new ShopifyStoreNeedsReauthException(shopDomain,
                    "Store not found under row lock");
            }

            // Double-check inside lock: another thread may have refreshed while we waited.
            if (isFresh(row.accessTokenExpiresAt())) {
                return encryptionService.decrypt(row.accessTokenEncrypted());
            }

            // Re-check guard conditions inside the lock.
            if ("needs_reauth".equals(row.status())) {
                throw new ShopifyStoreNeedsReauthException(row.shopDomain(),
                    "Store marked needs_reauth under lock");
            }
            if (row.refreshTokenEncrypted() == null) {
                jdbc.update(SET_NEEDS_REAUTH, storeId);
                throw new ShopifyStoreNeedsReauthException(row.shopDomain(),
                    "No refresh token available under lock");
            }
            if (row.refreshTokenExpiresAt() != null && row.refreshTokenExpiresAt().isBefore(Instant.now())) {
                jdbc.update(SET_NEEDS_REAUTH, storeId);
                throw new ShopifyStoreNeedsReauthException(row.shopDomain(),
                    "Refresh token expired under lock");
            }

            String rawRefreshToken = encryptionService.decrypt(row.refreshTokenEncrypted());

            // Call Shopify refresh API while holding the row lock.
            // Bounded to 10 s by tokenRestClient in ShopifyHttpGateway.
            // ShopifyTransientException propagates without touching store status.
            // ShopifyStoreNeedsReauthException: mark needs_reauth then propagate.
            ShopifyGateway.TokenResponse newTokens;
            try {
                newTokens = shopifyGateway.refreshAccessToken(row.shopDomain(), rawRefreshToken);
            } catch (ShopifyStoreNeedsReauthException e) {
                jdbc.update(SET_NEEDS_REAUTH, storeId);
                throw e;
            }
            // ShopifyTransientException: do NOT mark needs_reauth, propagate as-is.

            Timestamp newAccessExpiresAt  = Timestamp.from(Instant.now().plusSeconds(newTokens.expiresIn()));
            Timestamp newRefreshExpiresAt = Timestamp.from(Instant.now().plusSeconds(newTokens.refreshTokenExpiresIn()));

            jdbc.update(UPDATE_TOKENS,
                encryptionService.encrypt(newTokens.accessToken()),
                newAccessExpiresAt,
                encryptionService.encrypt(newTokens.refreshToken()),
                newRefreshExpiresAt,
                storeId);

            log.info("Shopify token refreshed for store {} ({})", storeId, row.shopDomain());
            return newTokens.accessToken();
        });
    }

    // ---- private: helpers -----------------------------------------------

    private boolean isFresh(Instant expiresAt) {
        return expiresAt != null && expiresAt.isAfter(Instant.now().plusSeconds(REFRESH_BUFFER_SECONDS));
    }

    private void markNeedsReauth(UUID storeId) {
        tx.execute(s -> {
            jdbc.update(SET_NEEDS_REAUTH, storeId);
            return null;
        });
    }

    private static StoreTokenRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp accessExp  = rs.getTimestamp("access_token_expires_at");
        Timestamp refreshExp = rs.getTimestamp("refresh_token_expires_at");
        return new StoreTokenRow(
            rs.getString("shop_domain"),
            rs.getString("access_token_encrypted"),
            accessExp  != null ? accessExp.toInstant()  : null,
            rs.getString("refresh_token_encrypted"),
            refreshExp != null ? refreshExp.toInstant() : null,
            rs.getString("status"),
            rs.getString("connection_type"),
            rs.getString("client_id_encrypted"),
            rs.getString("api_secret_encrypted"));
    }

    private record StoreTokenRow(
            String  shopDomain,
            String  accessTokenEncrypted,
            Instant accessTokenExpiresAt,
            String  refreshTokenEncrypted,
            Instant refreshTokenExpiresAt,
            String  status,
            String  connectionType,
            String  clientIdEncrypted,
            String  apiSecretEncrypted) {}
}
