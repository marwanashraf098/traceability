package com.traceability.integrations.shopify;

import com.traceability.identity.MagicLinkService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * OAuth state lifecycle + resolve-or-create decision tree.
 *
 * State table (shopify_oauth_state) is not under tenant RLS — see V13 migration.
 * The provisioning function (provision_tenant_from_shopify) is a SECURITY DEFINER
 * hatch — see V14 migration and blueprint.md §16.
 *
 * Critical ordering in linkOrProvision:
 *   resolve_tenant_by_shop_domain is called BEFORE any tenant GUC is set.
 *   A tenant-scoped SELECT under the intended tenant's RLS would hide a store
 *   owned by a different tenant, causing a confusing 23505 instead of a clean
 *   SHOPIFY_STORE_ALREADY_CONNECTED redirect. The DEFINER function sees all tenants.
 */
@Service
public class ShopifyOAuthService {

    private static final Logger log = LoggerFactory.getLogger(ShopifyOAuthService.class);

    private static final int STATE_TTL_SECONDS = 600; // 10 minutes

    private static final String INSERT_STORE = """
            INSERT INTO stores (tenant_id, shop_domain, platform,
                                access_token_encrypted, access_token_expires_at,
                                refresh_token_encrypted, refresh_token_expires_at,
                                status, import_status)
            VALUES (?, ?, 'shopify', ?, ?, ?, ?, 'connected', 'pending')
            RETURNING id
            """;

    private static final String UPDATE_STORE_TOKEN = """
            UPDATE stores
               SET access_token_encrypted  = ?,
                   access_token_expires_at  = ?,
                   refresh_token_encrypted  = ?,
                   refresh_token_expires_at = ?,
                   status                   = 'connected',
                   import_status            = 'pending'
             WHERE shop_domain = ?
               AND tenant_id   = ?
            RETURNING id
            """;

    /**
     * Token-exchange write path: updates token columns + resets status to 'connected'.
     * Does NOT touch import_status unconditionally — uses a CASE to set it to 'pending'
     * only when the store was in needs_reauth or idle/failed state (i.e., when jobs will
     * be enqueued). Leaves import_status unchanged for healthy stores refreshing a near-
     * expiry token, so a running/completed import is not disrupted.
     */
    private static final String EXCHANGE_SESSION_TOKEN_UPDATE = """
            UPDATE stores
               SET access_token_encrypted  = ?,
                   access_token_expires_at  = ?,
                   refresh_token_encrypted  = ?,
                   refresh_token_expires_at = ?,
                   status                   = 'connected',
                   import_status            = CASE
                       WHEN status = 'needs_reauth' OR import_status IN ('idle','failed')
                           THEN 'pending'::store_import_status
                       ELSE import_status
                   END
             WHERE shop_domain = ?
               AND tenant_id   = ?
            RETURNING id
            """;

    private static final String UPDATE_PROVISION_REFRESH_FIELDS = """
            UPDATE stores
               SET refresh_token_encrypted  = ?,
                   access_token_expires_at  = ?,
                   refresh_token_expires_at = ?
             WHERE id = ?
            """;

    private final JdbcTemplate              jdbc;
    private final ShopifyGateway            shopifyGateway;
    private final EncryptionService         encryptionService;
    private final JobScheduler              jobScheduler;
    private final ShopifyImportJob          importJob;
    private final RegisterShopifyWebhooksJob webhooksJob;
    private final MagicLinkService          magicLinkService;
    private final TransactionTemplate       tx;
    private final SecureRandom              rng = new SecureRandom();

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
            RegisterShopifyWebhooksJob webhooksJob,
            MagicLinkService magicLinkService,
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
        this.webhooksJob       = webhooksJob;
        this.magicLinkService  = magicLinkService;
        this.tx                = new TransactionTemplate(txm);
        this.clientId          = clientId;
        this.clientSecret      = clientSecret;
        this.scopes            = scopes;
        this.redirectUri       = redirectUri;
        this.appUrl            = appUrl;
    }

    // ---- public records -----------------------------------------------

    /** Carries the tenant and shop resolved from a consumed state nonce. */
    public record StateRecord(UUID tenantId, String shopDomain) {}

    /** Outcome of the resolve-or-create decision tree on callback. */
    public enum LinkOutcome {
        LINKED_NEW,           // Path-1 or Path-2: first-time link for this shop
        LINKED_EXISTING,      // idempotent re-install (same tenant owns the shop)
        PROVISIONED,          // Path-2-new: new tenant + owner + store created
        REJECTED_CROSS_TENANT // shop already owned by a different tenant
    }

    /** Result returned by linkOrProvision. */
    public record LinkResult(UUID tenantId, UUID ownerUserId, LinkOutcome outcome) {}

    // ---- state lifecycle ----------------------------------------------

    /**
     * Generates a CSPRNG nonce (≥128 bits, base64url), persists it in
     * shopify_oauth_state, and returns it for inclusion in the consent URL.
     *
     * @param tenantId   the authenticated owner's tenant (null for Path-2)
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
     * SELECT FOR UPDATE inside the transaction prevents concurrent replays:
     * the second request waits for the first to commit consumed_at, then sees it
     * non-null and rejects.
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
                    m.put("consumed_at", rs.getTimestamp("consumed_at"));
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

    // ---- resolve-or-create decision tree (Day 2) ----------------------

    /**
     * Exchanges the auth code then runs the resolve-or-create decision tree:
     *
     * <pre>
     * owner = resolve_tenant_by_shop_domain(shop)   // DEFINER, sees all tenants, no GUC needed
     *
     * Path-1 (state.tenantId != null — logged-in owner):
     *   owner == null      → INSERT store under intended tenant → LINKED_NEW
     *   owner == intended  → UPDATE store token (idempotent)   → LINKED_EXISTING
     *   owner != intended  → no write                          → REJECTED_CROSS_TENANT
     *
     * Path-2 (state.tenantId == null — Shopify-first):
     *   owner != null      → UPDATE store token (idempotent)   → LINKED_EXISTING
     *   owner == null      → provision_tenant_from_shopify     → PROVISIONED
     * </pre>
     *
     * Race backstop: on DuplicateKeyException (23505) we re-resolve (winner now
     * committed) and idempotently link to the winner.
     *
     * @param state    the consumed state record; tenantId is null for Path-2
     * @param shop     the shop domain from the callback params
     * @param authCode the authorization code — exchange happens here
     */
    public LinkResult linkOrProvision(StateRecord state, String shop, String authCode) {
        ShopifyGateway.TokenResponse tokens;
        try {
            tokens = shopifyGateway.exchangeCode(shop, authCode);
        } catch (Exception e) {
            log.error("Token exchange failed for shop {}", shop, e);
            throw new ShopifyOAuthException(
                ShopifyOAuthException.Code.SHOPIFY_TOKEN_EXCHANGE_FAILED,
                "Failed to exchange authorization code for access token",
                "فشل استبدال رمز التفويض للحصول على رمز الوصول",
                HttpStatus.BAD_GATEWAY);
        }

        // Resolve BEFORE setting any GUC — DEFINER function sees all tenants.
        UUID owner = resolveShopOwner(shop);

        try {
            return branch(state, shop, tokens, owner);
        } catch (DuplicateKeyException ex) {
            // Concurrent install won the INSERT between our resolve and our write.
            UUID winner = resolveShopOwner(shop);
            return raceRelink(state, shop, tokens, winner);
        }
    }

    // ---- URL building -------------------------------------------------

    public String buildConsentUrl(String shopDomain, String nonce) {
        return "https://" + shopDomain + "/admin/oauth/authorize" +
            "?client_id=" + clientId +
            "&scope=" + scopes +
            "&redirect_uri=" + redirectUri +
            "&state=" + nonce;
    }

    public String getAppUrl()        { return appUrl; }
    public String getClientSecret()  { return clientSecret; }

    // ---- private: decision tree ---------------------------------------

    private LinkResult branch(StateRecord state, String shop,
                               ShopifyGateway.TokenResponse tokens, UUID owner) {
        if (state.tenantId() != null) {
            return path1(state.tenantId(), shop, tokens, owner);
        } else {
            return path2(shop, tokens, owner);
        }
    }

    private LinkResult path1(UUID intended, String shop,
                              ShopifyGateway.TokenResponse tokens, UUID owner) {
        if (owner == null) {
            UUID storeId = insertStore(intended, shop, tokens);
            enqueueImport(storeId, intended);
            log.info("OAuth Path-1 new link: shop={} tenant={}", shop, intended);
            return new LinkResult(intended, null, LinkOutcome.LINKED_NEW);
        } else if (owner.equals(intended)) {
            UUID storeId = updateStoreToken(intended, shop, tokens);
            enqueueImport(storeId, intended);
            log.info("OAuth Path-1 re-link: shop={} tenant={}", shop, intended);
            return new LinkResult(intended, null, LinkOutcome.LINKED_EXISTING);
        } else {
            log.warn("OAuth cross-tenant reject: shop={} intended={} actual={}", shop, intended, owner);
            return new LinkResult(null, null, LinkOutcome.REJECTED_CROSS_TENANT);
        }
    }

    private LinkResult path2(String shop, ShopifyGateway.TokenResponse tokens, UUID owner) {
        if (owner != null) {
            UUID storeId = updateStoreToken(owner, shop, tokens);
            enqueueImport(storeId, owner);
            log.info("OAuth Path-2 existing link: shop={} tenant={}", shop, owner);
            return new LinkResult(owner, null, LinkOutcome.LINKED_EXISTING);
        } else {
            return provisionNewTenant(shop, tokens);
        }
    }

    private LinkResult provisionNewTenant(String shop, ShopifyGateway.TokenResponse tokens) {
        String rawToken      = tokens.accessToken();
        String encryptedToken = encryptionService.encrypt(rawToken);
        ShopifyGateway.ShopInfo shopInfo = shopifyGateway.fetchShop(shop, rawToken);
        if (shopInfo.email() == null || shopInfo.email().isBlank()) {
            throw new ShopifyOAuthException(
                ShopifyOAuthException.Code.SHOPIFY_SHOP_EMAIL_MISSING,
                "Shopify shop resource returned no owner email address",
                "لم يُعِد Shopify بريد المالك الإلكتروني",
                HttpStatus.BAD_GATEWAY);
        }

        // No TenantContext needed — SECURITY DEFINER bypasses RLS.
        record ProvRow(UUID tenantId, UUID ownerId, UUID storeId) {}
        ProvRow row = tx.execute(s ->
            jdbc.query(
                "SELECT tenant_id, owner_user_id, store_id " +
                "FROM provision_tenant_from_shopify(?,?,?,?,?)",
                rs -> rs.next()
                    ? new ProvRow(
                        rs.getObject("tenant_id",     UUID.class),
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getObject("store_id",      UUID.class))
                    : null,
                shop, shopInfo.email(), shopInfo.name(), shopInfo.timezone(), encryptedToken));

        if (row == null) {
            throw new ShopifyOAuthException(
                ShopifyOAuthException.Code.SHOPIFY_TOKEN_EXCHANGE_FAILED,
                "Tenant provisioning returned no result",
                "لم تُعِد وظيفة التهيئة أي نتيجة",
                HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // provision_tenant_from_shopify DEFINER receives only the access token — no change to its
        // signature (would require a new approved DEFINER escape hatch). Write the refresh token
        // fields via a normal UPDATE under tenant RLS immediately after provisioning.
        if (tokens.refreshToken() != null) {
            Timestamp accessExpiresAt  = Timestamp.from(Instant.now().plusSeconds(tokens.expiresIn()));
            Timestamp refreshExpiresAt = Timestamp.from(Instant.now().plusSeconds(tokens.refreshTokenExpiresIn()));
            TenantContext.set(row.tenantId());
            try {
                tx.execute(s -> {
                    jdbc.update(UPDATE_PROVISION_REFRESH_FIELDS,
                        encryptionService.encrypt(tokens.refreshToken()),
                        accessExpiresAt,
                        refreshExpiresAt,
                        row.storeId());
                    return null;
                });
            } finally {
                TenantContext.clear();
            }
        }

        magicLinkService.issueMagicLink(row.ownerId(), row.tenantId());
        enqueueImport(row.storeId(), row.tenantId());
        log.info("OAuth Path-2 provisioned: shop={} tenant={} owner={}", shop, row.tenantId(), row.ownerId());
        return new LinkResult(row.tenantId(), row.ownerId(), LinkOutcome.PROVISIONED);
    }

    /** After a 23505 race, re-resolve and idempotently link to the winner. */
    private LinkResult raceRelink(StateRecord state, String shop,
                                   ShopifyGateway.TokenResponse tokens, UUID winner) {
        if (winner == null) {
            throw stateInvalid(); // winner rolled back — treat as generic conflict
        }
        if (state.tenantId() != null && !winner.equals(state.tenantId())) {
            log.warn("OAuth cross-tenant race: shop={} intended={} winner={}", shop, state.tenantId(), winner);
            return new LinkResult(null, null, LinkOutcome.REJECTED_CROSS_TENANT);
        }
        UUID storeId = updateStoreToken(winner, shop, tokens);
        enqueueImport(storeId, winner);
        log.info("OAuth race re-link: shop={} winner={}", shop, winner);
        return new LinkResult(winner, null, LinkOutcome.LINKED_EXISTING);
    }

    // ---- private: DB helpers ------------------------------------------

    /** Calls DEFINER function — no TenantContext required; GUC is irrelevant to it. */
    private UUID resolveShopOwner(String shopDomain) {
        try {
            return jdbc.queryForObject(
                "SELECT resolve_tenant_by_shop_domain(?)", UUID.class, shopDomain);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private UUID insertStore(UUID tenantId, String shop, ShopifyGateway.TokenResponse tokens) {
        Timestamp accessExpiresAt  = Timestamp.from(Instant.now().plusSeconds(tokens.expiresIn()));
        Timestamp refreshExpiresAt = tokens.refreshToken() != null
            ? Timestamp.from(Instant.now().plusSeconds(tokens.refreshTokenExpiresIn())) : null;
        TenantContext.set(tenantId);
        try {
            return tx.execute(s ->
                jdbc.query(INSERT_STORE,
                    rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                    tenantId, shop,
                    encryptionService.encrypt(tokens.accessToken()),
                    accessExpiresAt,
                    tokens.refreshToken() != null ? encryptionService.encrypt(tokens.refreshToken()) : null,
                    refreshExpiresAt));
        } finally {
            TenantContext.clear();
        }
    }

    private UUID updateStoreToken(UUID tenantId, String shop, ShopifyGateway.TokenResponse tokens) {
        Timestamp accessExpiresAt  = Timestamp.from(Instant.now().plusSeconds(tokens.expiresIn()));
        Timestamp refreshExpiresAt = tokens.refreshToken() != null
            ? Timestamp.from(Instant.now().plusSeconds(tokens.refreshTokenExpiresIn())) : null;
        TenantContext.set(tenantId);
        try {
            return tx.execute(s ->
                jdbc.query(UPDATE_STORE_TOKEN,
                    rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                    encryptionService.encrypt(tokens.accessToken()),
                    accessExpiresAt,
                    tokens.refreshToken() != null ? encryptionService.encrypt(tokens.refreshToken()) : null,
                    refreshExpiresAt,
                    shop, tenantId));
        } finally {
            TenantContext.clear();
        }
    }

    private void enqueueImport(UUID storeId, UUID tenantId) {
        jobScheduler.enqueue(() -> importJob.run(storeId, tenantId));
        jobScheduler.enqueue(() -> webhooksJob.run(storeId, tenantId));
    }

    // ---- Modern install flow: session-token exchange -------------------------

    /**
     * Acquires or refreshes the Shopify access token via session-token exchange.
     * Called by EmbeddedTokenExchangeController on every embedded app load; skips
     * the network call when the stored token is comfortably fresh (>10 min remaining).
     *
     * <pre>
     * 1. SELECT store snap (id, expires_at, status, import_status) — RLS-guarded.
     * 2. If access_token_expires_at > now+10min AND status=connected → return (skip).
     * 3. Call gateway.exchangeSessionToken(shopDomain, rawSessionToken).
     *    - ShopifySessionTokenExchangeException (4xx) → propagate; caller returns 502.
     *      Do NOT mark needs_reauth — the refresh token may still be valid.
     *    - ShopifyTransientException (5xx/timeout) → propagate; caller returns 503.
     * 4. Write new tokens via EXCHANGE_SESSION_TOKEN_UPDATE (token cols only;
     *    import_status CASE resets to 'pending' iff jobs will be enqueued).
     * 5. Enqueue import + webhooks jobs if status was needs_reauth OR
     *    (connected AND import_status IN (idle, failed)).
     * </pre>
     *
     * Concurrent safety: two calls from two browser tabs will both pass the freshness
     * check if the token is stale, both call exchangeSessionToken (idempotent — no
     * single-use restriction like refresh tokens), and both issue UPDATE statements
     * (atomic at the row level; last write wins with an equally valid token pair).
     * No SELECT FOR UPDATE is needed here.
     *
     * @param tenantId       the authenticated tenant (from the SHOPIFY_EMBEDDED principal)
     * @param shopDomain     the verified shop domain (from principal.shopDomain(), NOT a request param)
     * @param rawSessionToken the raw HS256 session token from Authorization: Bearer
     * @return true if token is fresh or exchange succeeded; false if the store row is missing
     */
    public boolean acquireOrRefreshViaSessionToken(UUID tenantId, String shopDomain, String rawSessionToken) {
        // Step 1: freshness check — needs TenantContext for RLS
        record StoreSnap(UUID id, Instant expiresAt, String status, String importStatus) {}
        StoreSnap snap;
        TenantContext.set(tenantId);
        try {
            snap = tx.execute(s ->
                jdbc.query(
                    "SELECT id, access_token_expires_at, status::text, import_status::text " +
                    "FROM stores WHERE tenant_id = ? AND shop_domain = ?",
                    rs -> rs.next() ? new StoreSnap(
                        rs.getObject("id", UUID.class),
                        rs.getTimestamp("access_token_expires_at") != null
                            ? rs.getTimestamp("access_token_expires_at").toInstant() : null,
                        rs.getString("status"),
                        rs.getString("import_status")) : null,
                    tenantId, shopDomain));
        } finally {
            TenantContext.clear();
        }

        if (snap == null) {
            log.warn("Token exchange: store not found tenant={} shop={}", tenantId, shopDomain);
            return false;
        }

        // Step 2: skip if token is comfortably fresh
        Instant threshold = Instant.now().plusSeconds(600); // 10 min
        if (snap.expiresAt() != null && snap.expiresAt().isAfter(threshold)
                && "connected".equals(snap.status())) {
            log.debug("Token exchange skipped: token fresh for shop={}", shopDomain);
            return true;
        }

        // Step 3: exchange — ShopifySessionTokenExchangeException / ShopifyTransientException propagate
        ShopifyGateway.TokenResponse tokens =
            shopifyGateway.exchangeSessionToken(shopDomain, rawSessionToken);

        // Step 4: store — applyExchangedToken manages its own TenantContext
        UUID storeId = applyExchangedToken(tenantId, shopDomain, tokens);

        // Step 5: conditional job enqueue
        // 'pending' is included because it means "job was enqueued but never ran" —
        // JobRunr may not have been running when the prior enqueue happened, or the job
        // crashed before updating import_status. Excluding pending caused a permanent
        // stuck state: every exchange returned 204 success but never re-enqueued.
        // Both jobs are idempotent (webhooks: Shopify rejects duplicate topics silently;
        // import: ON CONFLICT DO UPDATE throughout), so duplicate enqueues are safe.
        boolean shouldEnqueue = "needs_reauth".equals(snap.status())
                || ("connected".equals(snap.status())
                    && ("idle".equals(snap.importStatus())
                        || "failed".equals(snap.importStatus())
                        || "pending".equals(snap.importStatus())));
        if (shouldEnqueue && storeId != null) {
            enqueueImport(storeId, tenantId);
            log.info("Token exchange: enqueued import+webhooks for shop={} tenant={}", shopDomain, tenantId);
        }

        log.info("Token exchange: succeeded for shop={} tenant={}", shopDomain, tenantId);
        return true;
    }

    private UUID applyExchangedToken(UUID tenantId, String shop, ShopifyGateway.TokenResponse tokens) {
        Timestamp accessExpiresAt  = Timestamp.from(Instant.now().plusSeconds(tokens.expiresIn()));
        Timestamp refreshExpiresAt = tokens.refreshToken() != null
            ? Timestamp.from(Instant.now().plusSeconds(tokens.refreshTokenExpiresIn())) : null;
        TenantContext.set(tenantId);
        try {
            return tx.execute(s ->
                jdbc.query(EXCHANGE_SESSION_TOKEN_UPDATE,
                    rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                    encryptionService.encrypt(tokens.accessToken()),
                    accessExpiresAt,
                    tokens.refreshToken() != null ? encryptionService.encrypt(tokens.refreshToken()) : null,
                    refreshExpiresAt,
                    shop, tenantId));
        } finally {
            TenantContext.clear();
        }
    }

    private static ShopifyOAuthException stateInvalid() {
        return new ShopifyOAuthException(
            ShopifyOAuthException.Code.SHOPIFY_STATE_INVALID,
            "OAuth state is invalid, expired, or already used",
            "رمز OAuth غير صالح أو منتهي الصلاحية أو مستخدم مسبقاً",
            HttpStatus.BAD_REQUEST);
    }
}
