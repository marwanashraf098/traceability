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
 * Concurrency guard: SELECT FOR UPDATE ensures only one thread per store enters the
 * Shopify refresh call. Because refresh tokens are single-use rotating (Shopify
 * invalidates the old token and issues a new one), concurrent rotation would produce
 * one valid pair and one permanently-rejected refresh attempt. The row lock collapses
 * concurrent refreshers into a single network call; the second thread, after acquiring
 * the lock, re-checks expiry and returns the fresh token written by the first.
 *
 * The Shopify refresh call happens inside the held transaction (= held row lock). The
 * call is bounded to 10 s via ShopifyHttpGateway's tokenRestClient. Hikari pool = 5;
 * pilot stores ≤ 3, so at most 3 connections are occupied ≤ 10 s simultaneously.
 * Crash-mid-refresh: if the JVM dies after Shopify responds but before COMMIT, the
 * old refresh token may have been consumed; on restart the store is marked needs_reauth
 * on the next attempt. This edge case is acceptable at current scale (F1 means JobRunr
 * is disabled anyway, so concurrent refresh barely exists yet).
 *
 * TenantContext must be set by the caller (both job types use TenantContext.runAs,
 * which keeps the ThreadLocal active for the duration of the job body). This service
 * does not set or clear TenantContext itself.
 */
@Service
public class ShopifyTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ShopifyTokenProvider.class);

    // Refresh when the access token has ≤ 5 minutes remaining.
    private static final long REFRESH_BUFFER_SECONDS = 300;

    private static final String SELECT_QUICK = """
            SELECT shop_domain, access_token_encrypted, access_token_expires_at,
                   refresh_token_encrypted, refresh_token_expires_at, status
            FROM stores WHERE id = ?
            """;

    private static final String SELECT_FOR_UPDATE = """
            SELECT shop_domain, access_token_encrypted, access_token_expires_at,
                   refresh_token_encrypted, refresh_token_expires_at, status
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
     * refreshing via the Shopify token endpoint if the current token is near-expiry.
     *
     * TenantContext must be active (set by caller via TenantContext.runAs or TenantContext.set).
     *
     * @throws ShopifyStoreNeedsReauthException if the store has no refresh token (legacy install),
     *         the refresh token is expired, or Shopify permanently rejected the refresh.
     * @throws ShopifyTransientException if Shopify returned 5xx or the connection timed out —
     *         the refresh token is still valid and the caller may retry later.
     */
    public String getValidToken(UUID storeId) {
        StoreTokenRow row = quickRead(storeId);

        if (row == null) {
            throw new ShopifyStoreNeedsReauthException(storeId.toString(),
                "Store not found or not visible under current tenant");
        }
        if ("needs_reauth".equals(row.status())) {
            throw new ShopifyStoreNeedsReauthException(row.shopDomain(),
                "Store is marked needs_reauth — merchant must reinstall");
        }

        // Fresh token: has an expiry set and it's > 5 min away.
        if (isFresh(row.accessTokenExpiresAt())) {
            return encryptionService.decrypt(row.accessTokenEncrypted());
        }

        // Legacy non-expiring token (expiresAt null) OR expired with no refresh token.
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

        // Refresh path — acquire row lock.
        return refreshWithLock(storeId, row.shopDomain());
    }

    // ---- private: quick read (no lock) ----------------------------------

    private StoreTokenRow quickRead(UUID storeId) {
        return tx.execute(s ->
            jdbc.query(SELECT_QUICK, rs -> rs.next() ? mapRow(rs) : null, storeId));
    }

    // ---- private: lock + refresh + write-back ---------------------------

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
            rs.getString("status"));
    }

    private record StoreTokenRow(
            String  shopDomain,
            String  accessTokenEncrypted,
            Instant accessTokenExpiresAt,
            String  refreshTokenEncrypted,
            Instant refreshTokenExpiresAt,
            String  status) {}
}
