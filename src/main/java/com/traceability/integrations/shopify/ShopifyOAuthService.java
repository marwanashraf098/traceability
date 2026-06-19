package com.traceability.integrations.shopify;

import com.traceability.security.EncryptionService;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * OAuth Day 1 — state lifecycle + callback handling for Path-1 (tenant-linked installs).
 *
 * State table is not under RLS — see V13 migration for the security rationale.
 * Token exchange delegates to ShopifyGateway (mockable in tests).
 * Encryption delegates to EncryptionService (AES-256-GCM).
 * Import job is enqueued via JobScheduler after successful store upsert.
 */
@Service
public class ShopifyOAuthService {

    private static final Logger log = LoggerFactory.getLogger(ShopifyOAuthService.class);

    private static final int STATE_TTL_SECONDS = 600; // 10 minutes

    private static final String UPSERT_STORE = """
            INSERT INTO stores (tenant_id, shop_domain, platform, access_token_encrypted, status, import_status)
            VALUES (?, ?, 'shopify', ?, 'connected', 'pending')
            ON CONFLICT (shop_domain) DO UPDATE SET
                access_token_encrypted = EXCLUDED.access_token_encrypted,
                status                 = 'connected',
                import_status          = 'pending'
            WHERE stores.tenant_id = EXCLUDED.tenant_id
            RETURNING id
            """;

    private final JdbcTemplate       jdbc;
    private final ShopifyGateway     shopifyGateway;
    private final EncryptionService  encryptionService;
    private final JobScheduler       jobScheduler;
    private final ShopifyImportJob   importJob;
    private final TransactionTemplate tx;
    private final SecureRandom       rng = new SecureRandom();

    private final String clientId;
    private final String clientSecret;
    private final String scopes;
    private final String redirectUri;
    private final String appUrl;

    public ShopifyOAuthService(
            JdbcTemplate jdbc,
            ShopifyGateway shopifyGateway,
            EncryptionService encryptionService,
            JobScheduler jobScheduler,
            ShopifyImportJob importJob,
            PlatformTransactionManager txm,
            @Value("${shopify.client-id}") String clientId,
            @Value("${shopify.client-secret}") String clientSecret,
            @Value("${shopify.scopes}") String scopes,
            @Value("${shopify.redirect-uri}") String redirectUri,
            @Value("${shopify.app-url}") String appUrl) {
        this.jdbc              = jdbc;
        this.shopifyGateway    = shopifyGateway;
        this.encryptionService = encryptionService;
        this.jobScheduler      = jobScheduler;
        this.importJob         = importJob;
        this.tx                = new TransactionTemplate(txm);
        this.clientId          = clientId;
        this.clientSecret      = clientSecret;
        this.scopes            = scopes;
        this.redirectUri       = redirectUri;
        this.appUrl            = appUrl;
    }

    // ---- public records -------------------------------------------------

    /** Carries the tenant and shop resolved from a consumed state nonce. */
    public record StateRecord(UUID tenantId, String shopDomain) {}

    // ---- state lifecycle ------------------------------------------------

    /**
     * Generates a CSPRNG nonce (≥128 bits, base64url), persists it in
     * shopify_oauth_state, and returns it for inclusion in the consent URL.
     *
     * @param tenantId  the authenticated owner's tenant (null for Path-2)
     * @param shopDomain the shop domain to bind to this state
     */
    public String initiateOAuth(UUID tenantId, String shopDomain) {
        byte[] nonceBytes = new byte[16]; // 128 bits
        rng.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);

        tx.execute(s -> {
            jdbc.update(
                "INSERT INTO shopify_oauth_state (nonce, tenant_id, shop_domain) VALUES (?, ?, ?)",
                nonce, tenantId, shopDomain);
            return null;
        });
        log.debug("OAuth state created: nonce={} shop={} tenant={}", nonce, shopDomain, tenantId);
        return nonce;
    }

    /**
     * Atomically loads, validates, and consumes a state nonce.
     *
     * All invalid-state sub-conditions (expired, consumed, shop-mismatch, not-found)
     * throw SHOPIFY_STATE_INVALID — the caller must not leak which case triggered.
     *
     * A SELECT FOR UPDATE inside the transaction prevents concurrent replays:
     * the second request waits for the first to commit consumed_at, then sees it
     * non-null and rejects.
     *
     * @param nonce       the state parameter from the callback
     * @param callbackShop the shop parameter from the callback
     * @return StateRecord with the bound tenant_id (null for Path-2)
     * @throws ShopifyOAuthException SHOPIFY_STATE_INVALID on any invalid condition
     */
    public StateRecord consumeState(String nonce, String callbackShop) {
        return tx.execute(s -> {
            Map<String, Object> row = jdbc.query(
                "SELECT tenant_id, shop_domain, created_at, consumed_at " +
                "FROM shopify_oauth_state WHERE nonce = ? FOR UPDATE",
                rs -> {
                    if (!rs.next()) return null;
                    Map<String, Object> m = new HashMap<>();
                    m.put("tenant_id",   rs.getObject("tenant_id", UUID.class));
                    m.put("shop_domain", rs.getString("shop_domain"));
                    m.put("created_at",  rs.getTimestamp("created_at").toInstant());
                    m.put("consumed_at", rs.getTimestamp("consumed_at")); // may be null
                    return m;
                }, nonce);

            if (row == null || row.get("consumed_at") != null) {
                throw stateInvalid();
            }
            Instant createdAt = (Instant) row.get("created_at");
            if (createdAt.isBefore(Instant.now().minusSeconds(STATE_TTL_SECONDS))) {
                throw stateInvalid();
            }
            String stateShop = (String) row.get("shop_domain");
            if (!stateShop.equals(callbackShop)) {
                throw stateInvalid();
            }

            jdbc.update("UPDATE shopify_oauth_state SET consumed_at = now() WHERE nonce = ?", nonce);

            return new StateRecord((UUID) row.get("tenant_id"), stateShop);
        });
    }

    // ---- callback handling ----------------------------------------------

    /**
     * Exchanges the authorization code for an offline access token, encrypts it,
     * upserts the store row, and enqueues the catalog+order import job.
     *
     * @param tenantId   the tenant to link the store to
     * @param shopDomain the merchant's shop domain
     * @param code       the authorization code from the callback
     * @return the new or existing store UUID
     * @throws ShopifyOAuthException SHOPIFY_TOKEN_EXCHANGE_FAILED on gateway error
     */
    public UUID handleCallback(UUID tenantId, String shopDomain, String code) {
        String rawToken;
        try {
            rawToken = shopifyGateway.exchangeCode(shopDomain, code);
        } catch (Exception e) {
            log.error("Token exchange failed for shop {}", shopDomain, e);
            throw new ShopifyOAuthException(
                ShopifyOAuthException.Code.SHOPIFY_TOKEN_EXCHANGE_FAILED,
                "Failed to exchange authorization code for access token",
                "فشل استبدال رمز التفويض للحصول على رمز الوصول",
                HttpStatus.BAD_GATEWAY);
        }

        String encrypted = encryptionService.encrypt(rawToken);

        UUID storeId = tx.execute(s ->
            jdbc.query(UPSERT_STORE, rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                       tenantId, shopDomain, encrypted));

        if (storeId == null) {
            log.warn("Shop {} is already connected to a different tenant — cross-tenant rejected", shopDomain);
            throw new ShopifyOAuthException(
                ShopifyOAuthException.Code.SHOPIFY_STATE_INVALID,
                "This shop is already connected to another account",
                "هذا المتجر متصل بحساب آخر بالفعل",
                HttpStatus.CONFLICT);
        }

        final UUID finalStoreId = storeId;
        jobScheduler.enqueue(() -> importJob.run(finalStoreId, tenantId));
        log.info("OAuth callback complete: shop={} tenant={} store={}", shopDomain, tenantId, storeId);
        return storeId;
    }

    // ---- URL building ---------------------------------------------------

    /** Builds the Shopify consent URL for redirecting the merchant. */
    public String buildConsentUrl(String shopDomain, String nonce) {
        return "https://" + shopDomain + "/admin/oauth/authorize" +
            "?client_id=" + clientId +
            "&scope=" + scopes +
            "&redirect_uri=" + redirectUri +
            "&state=" + nonce;
    }

    public String getAppUrl()      { return appUrl; }
    public String getClientSecret() { return clientSecret; }

    // ---- private --------------------------------------------------------

    private static ShopifyOAuthException stateInvalid() {
        return new ShopifyOAuthException(
            ShopifyOAuthException.Code.SHOPIFY_STATE_INVALID,
            "OAuth state is invalid, expired, or already used",
            "رمز OAuth غير صالح أو منتهي الصلاحية أو مستخدم مسبقاً",
            HttpStatus.BAD_REQUEST);
    }
}
