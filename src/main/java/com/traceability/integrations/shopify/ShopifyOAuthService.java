package com.traceability.integrations.shopify;

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
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * OAuth state lifecycle + resolve-or-create decision tree.
 *
 * State table (shopify_oauth_state) is not under tenant RLS — see V13 migration.
 * As of V87, app_user has INSERT only on it; consume goes through
 * consume_shopify_oauth_state (SECURITY DEFINER, 9th hatch) — enforced at the grant
 * level, not just by code discipline. No path here creates a tenant: a merchant without a
 * Traced account signs up inside the embedded app (Build D, com.traceability.onboarding), which
 * creates tenant + owner + store under app_user + RLS — the V14 provision_tenant_from_shopify
 * hatch was dropped in V147.
 *
 * Critical ordering in linkOrProvision:
 *   resolve_tenant_by_shop_domain is called BEFORE any tenant GUC is set.
 *   A tenant-scoped SELECT under the intended tenant's RLS would hide a store
 *   owned by a different tenant, causing a confusing 23505 instead of a clean
 *   SHOP_LINKED_ELSEWHERE redirect. The DEFINER function sees all tenants.
 */
@Service
public class ShopifyOAuthService {

    private static final Logger log = LoggerFactory.getLogger(ShopifyOAuthService.class);

    private static final String INSERT_STORE = """
            INSERT INTO stores (tenant_id, shop_domain, platform,
                                access_token_encrypted, access_token_expires_at,
                                refresh_token_encrypted, refresh_token_expires_at,
                                access_token_scopes, orders_ingest_from,
                                status, import_status)
            VALUES (?, ?, 'shopify', ?, ?, ?, ?, ?, now(), 'connected', 'pending')
            RETURNING id
            """;

    private static final String UPDATE_STORE_TOKEN = """
            UPDATE stores
               SET access_token_encrypted  = ?,
                   access_token_expires_at  = ?,
                   refresh_token_encrypted  = ?,
                   refresh_token_expires_at = ?,
                   access_token_scopes      = ?,
                   connection_type          = 'oauth',
                   status                   = 'connected',
                   import_status            = 'pending',
                   -- Build C: an OAuth store keeps no custom-app secret — webhook phase-B HMAC is then
                   -- impossible for the old app, so its app/uninstalled can never disconnect this store.
                   api_secret_encrypted     = NULL,
                   client_id_encrypted      = NULL,
                   -- Recorded only when this re-link upgraded a custom app (V146); untouched otherwise.
                   oauth_upgraded_from           = COALESCE(?, oauth_upgraded_from),
                   oauth_upgraded_at             = CASE WHEN ?::text IS NOT NULL THEN now() ELSE oauth_upgraded_at END,
                   legacy_webhook_cleanup_status = COALESCE(?, legacy_webhook_cleanup_status),
                   legacy_webhook_cleanup_detail = COALESCE(?, legacy_webhook_cleanup_detail),
                   legacy_webhook_cleanup_at     = CASE WHEN ?::text IS NOT NULL THEN now() ELSE legacy_webhook_cleanup_at END
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
                   access_token_scopes      = ?,
                   connection_type          = 'oauth',
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

    private final JdbcTemplate              jdbc;
    private final ShopifyGateway            shopifyGateway;
    private final EncryptionService         encryptionService;
    private final JobScheduler              jobScheduler;
    private final ShopifyImportJob          importJob;
    private final RegisterShopifyWebhooksJob webhooksJob;
    private final ShopifySameShopGuard      sameShopGuard;
    private final LegacyWebhookCleanup      legacyWebhookCleanup;
    private final TransactionTemplate       tx;
    private final SecureRandom              rng = new SecureRandom();

    private final String clientId;
    private final String clientSecret;
    private final String scopes;
    private final String redirectUri;
    private final String appUrl;
    private final String appHandle;

    public ShopifyOAuthService(
            JdbcTemplate jdbc,
            ShopifyGateway shopifyGateway,
            EncryptionService encryptionService,
            JobScheduler jobScheduler,
            ShopifyImportJob importJob,
            RegisterShopifyWebhooksJob webhooksJob,
            ShopifySameShopGuard sameShopGuard,
            LegacyWebhookCleanup legacyWebhookCleanup,
            PlatformTransactionManager txm,
            @Value("${shopify.client-id}") String clientId,
            @Value("${shopify.client-secret}") String clientSecret,
            @Value("${shopify.scopes}") String scopes,
            @Value("${shopify.redirect-uri}") String redirectUri,
            @Value("${shopify.app-url}") String appUrl,
            @Value("${shopify.app-handle}") String appHandle) {
        this.jdbc              = jdbc;
        this.shopifyGateway    = shopifyGateway;
        this.encryptionService = encryptionService;
        this.jobScheduler      = jobScheduler;
        this.importJob         = importJob;
        this.webhooksJob       = webhooksJob;
        this.sameShopGuard     = sameShopGuard;
        this.legacyWebhookCleanup = legacyWebhookCleanup;
        this.tx                = new TransactionTemplate(txm);
        this.clientId          = clientId;
        this.clientSecret      = clientSecret;
        this.scopes            = scopes;
        this.redirectUri       = redirectUri;
        this.appUrl            = appUrl;
        this.appHandle         = appHandle;
    }

    // ---- public records -----------------------------------------------

    /**
     * Carries the tenant and shop resolved from a consumed state nonce.
     * host is the Shopify `host` param captured at /auth/shopify/install time,
     * if it was present (NOT guaranteed — see buildAdminAppUrl()).
     */
    public record StateRecord(UUID tenantId, String shopDomain, String host) {}

    /** Outcome of the resolve-or-create decision tree on callback. */
    public enum LinkOutcome {
        LINKED_NEW,           // Path-1 or a confirmed pending link: first-time link for this shop
        LINKED_EXISTING,      // idempotent re-install (same tenant owns the shop)
        NOT_LINKED,            // Path-2, no owner found: cold install — nothing created
        REJECTED_CROSS_TENANT, // shop already owned by a different tenant
        // Write-site backstop to assertBoundShop() (initiate()-time guard): the intended tenant
        // already owns a DIFFERENT shop. Guards the write path if initiate() is ever bypassed.
        REJECTED_SHOP_MISMATCH
    }

    /** Result returned by linkOrProvision / linkPendingShop. */
    public record LinkResult(UUID tenantId, UUID ownerUserId, LinkOutcome outcome) {}

    // ---- state lifecycle ----------------------------------------------

    /**
     * Generates a CSPRNG nonce (≥128 bits, base64url), persists it in
     * shopify_oauth_state, and returns it for inclusion in the consent URL.
     *
     * @param tenantId   the authenticated owner's tenant (null for Path-2)
     * @param shopDomain the shop domain to bind to this state
     * @param host       Shopify's `host` param, if present at install time (null otherwise —
     *                   normal for a fresh/cold install; see buildAdminAppUrl()'s fallback)
     */
    /**
     * "Find your store" — the HTTP initiate path: same-shop rule (pre-consent, as before), then the store
     * typo check, then {@link #initiateOAuth}. {@code shopDomain} is already normalised
     * (ShopDomainNormalizer). Only Shopify's clear not-found blocks (STORE_NOT_FOUND); an inconclusive check
     * (timeout, any other answer, or a mocked gateway's null) lets the merchant through.
     */
    public String initiateChecked(UUID tenantId, String shopDomain, String host) {
        if (tenantId != null) assertBoundShop(tenantId, shopDomain);
        ShopifyStoreExistence.Result check = shopifyGateway.checkStoreExists(shopDomain);
        log.info("STORE_CHECK shop={} tenant={} result={}", shopDomain, tenantId,
            check == null ? ShopifyStoreExistence.Result.INCONCLUSIVE : check);
        if (check == ShopifyStoreExistence.Result.NOT_FOUND) {
            throw new ShopifyOAuthException(ShopifyOAuthException.Code.STORE_NOT_FOUND,
                "We couldn't find a store called " + shopDomain + ". Check the spelling.",
                "لم نجد متجرًا باسم " + shopDomain + ". تحقق من الإملاء.",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return initiateOAuth(tenantId, shopDomain, host);
    }

    public String initiateOAuth(UUID tenantId, String shopDomain, String host) {
        // Hard rule: a tenant is permanently bound to its original shop_domain. Path-1 only
        // (tenantId != null) — Path-2 install has no authenticated tenant yet, nothing to bind.
        // Checked BEFORE any state nonce is generated or written — pre-consent rejection.
        if (tenantId != null) {
            assertBoundShop(tenantId, shopDomain);
        }

        byte[] nonceBytes = new byte[16]; // 128 bits
        rng.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);

        tx.execute(s -> {
            jdbc.update(
                "INSERT INTO shopify_oauth_state (nonce, tenant_id, shop_domain, host) VALUES (?, ?, ?, ?)",
                nonce, tenantId, shopDomain, host);
            return null;
        });
        log.debug("OAuth state created: nonce={} shop={} tenant={} host={}", nonce, shopDomain, tenantId, host);
        return nonce;
    }

    /**
     * Layer 1 of the shop binding rule (ShopifySameShopGuard): a real tenant with ANY existing
     * stores row (regardless of status — connected, disconnected, needs_reauth) may only
     * initiate OAuth against a shop_domain it already owns; a simulated-courier (review mode)
     * tenant's disconnected rows don't bind it. Nothing binding means first connect — any
     * valid shop is allowed. Rejected BEFORE any state nonce is written or the merchant is
     * sent to Shopify. Own-tenant shop only, in the message — never leaks another tenant's
     * domain.
     *
     * Delegates the assertion itself to the shared ShopifySameShopGuard (also used by the
     * custom-app connect paths in ShopifySyncService) — this method's own job is just to
     * scope TenantContext around the call, since unlike ShopifySyncService's connect paths,
     * nothing has set an ambient tenant context by the time initiateOAuth() runs (the
     * controller deliberately does not touch TenantContext — see ShopifyOAuthController).
     */
    private void assertBoundShop(UUID tenantId, String requestedShop) {
        TenantContext.runAs(tenantId, () -> sameShopGuard.assertBoundShop(tenantId, requestedShop));
    }

    /**
     * Atomically loads, validates, and consumes a state nonce.
     *
     * As of V87, this goes entirely through consume_shopify_oauth_state (SECURITY
     * DEFINER, 9th hatch) — app_user has no direct SELECT/UPDATE on shopify_oauth_state
     * any more. All invalid-state sub-conditions (expired, consumed, shop-mismatch,
     * not-found) collapse to the same empty result there, surfaced here as the SAME
     * SHOPIFY_STATE_INVALID — the caller must not leak which case triggered.
     *
     * The 10-minute TTL now lives as a single hardcoded literal inside the DEFINER
     * function's SQL — no second Java constant that could drift from it.
     *
     * FOR UPDATE inside the function prevents concurrent replays: the second request
     * waits for the first to commit consumed_at, then sees it non-null and rejects.
     */
    public StateRecord consumeState(String nonce, String callbackShop) {
        return tx.execute(s -> {
            StateRecord record = jdbc.query(
                "SELECT tenant_id, shop_domain, host FROM consume_shopify_oauth_state(?, ?)",
                rs -> rs.next()
                    ? new StateRecord(rs.getObject("tenant_id", UUID.class), rs.getString("shop_domain"), rs.getString("host"))
                    : null,
                nonce, callbackShop);

            if (record == null) {
                throw stateInvalid();
            }
            return record;
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
     *   owner == null      → no write, nothing created         → NOT_LINKED
     *
     * A cold Shopify-first install (owner == null) creates NOTHING here (no tenant, user,
     * store) and enqueues NO jobs: the callback sends the merchant back into the embedded app,
     * where onboarding (Build D) either creates their Traced account with this store, or parks
     * a pending link that a signed-in owner confirms on Traced. Traced is currently free and
     * Shopify approved off-platform billing — neither path is a payment gate. See
     * ShopifyOAuthController.callback()'s NOT_LINKED case for the embedded re-entry redirect.
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

    private static final String MYSHOPIFY_SUFFIX = ".myshopify.com";

    /**
     * Builds the Shopify ADMIN url that frames this app, so the post-OAuth top-level
     * redirect hands the browser back to admin.shopify.com instead of our bare domain
     * (Fix 2.3.3 — the previous embeddedReturn assumed `host` would be present on the
     * OAuth callback, which is not guaranteed on a fresh/cold install).
     *
     * Preference order:
     *   1. host (captured at install time via StateRecord.host(), OR — belt and braces —
     *      present directly on the callback params) — base64-decoded to the exact admin
     *      URL Shopify itself handed us.
     *   2. shop-derived fallback: https://admin.shopify.com/store/{store-handle}/apps/{app-handle}
     *      — always available (shop is a required, HMAC-verified callback param), so this
     *      is the reliable path for the fresh-install case host is normally absent from.
     *
     * A malformed/undecodable host never throws — it falls through to the shop-derived URL
     * rather than risking a redirect to a broken location.
     */
    public String buildAdminAppUrl(String shop, String host) {
        if (host != null && !host.isBlank()) {
            String decoded = decodeHost(host);
            if (decoded != null) {
                return "https://" + decoded;
            }
        }
        String storeHandle = shop.toLowerCase().endsWith(MYSHOPIFY_SUFFIX)
            ? shop.substring(0, shop.length() - MYSHOPIFY_SUFFIX.length())
            : shop;
        return "https://admin.shopify.com/store/" + storeHandle + "/apps/" + appHandle;
    }

    /** Returns null (never throws) on anything that isn't valid base64 — caller falls back. */
    private static String decodeHost(String host) {
        try {
            return new String(Base64.getDecoder().decode(host), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            try {
                return new String(Base64.getUrlDecoder().decode(host), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e2) {
                log.warn("Could not base64-decode Shopify host param, falling back to shop-derived admin URL: {}", host);
                return null;
            }
        }
    }

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
            // Layer 2 backstop (symmetric to REJECTED_CROSS_TENANT below): guards the write
            // site directly in case initiate()'s assertBoundShop() is ever bypassed. No row is
            // inserted for a second shop_domain under a tenant that already owns a different one.
            if (tenantOwnsDifferentShop(intended, shop)) {
                log.warn("OAuth same-tenant shop-mismatch reject: shop={} tenant={}", shop, intended);
                return new LinkResult(null, null, LinkOutcome.REJECTED_SHOP_MISMATCH);
            }
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
            // Cold Shopify-first install, no owning tenant: creates nothing, enqueues nothing;
            // the exchanged token is discarded. The merchant lands back in the embedded app,
            // whose onboarding signs them up or parks a pending link for their existing account
            // (Build D) — a store is only ever linked to a tenant a person signed in to or created.
            log.info("OAuth Path-2 cold install, no linked tenant — nothing provisioned: shop={}", shop);
            return new LinkResult(null, null, LinkOutcome.NOT_LINKED);
        }
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

    // ---- Build D: onboarding inside the embedded app ----------------------

    /**
     * Pending link (an existing Traced account connects the store it installed from Shopify):
     * same rules as Path-1 — the same-shop rule (409 SHOPIFY_SHOP_MISMATCH naming the linked shop)
     * and "a shop is linked to one tenant" (409 SHOP_LINKED_ELSEWHERE). Read-only; the confirm
     * page calls it before anything is consumed, and {@link #linkPendingShop} re-checks at write.
     */
    public void assertPendingLinkAllowed(UUID tenantId, String shop) {
        UUID owner = resolveShopOwner(shop);
        if (owner != null && !owner.equals(tenantId)) throw shopLinkedElsewhere();
        if (owner == null) TenantContext.runAs(tenantId, () -> sameShopGuard.assertBoundShop(tenantId, shop));
    }

    /**
     * Links {@code shop} to {@code tenantId} with a token the embedded app already exchanged
     * (pending link) — Path-1's decision tree and race backstop, without an auth-code exchange.
     * Enqueues import + webhook registration exactly as Path-1 does.
     */
    public LinkResult linkPendingShop(UUID tenantId, String shop, ShopifyGateway.TokenResponse tokens) {
        UUID owner = resolveShopOwner(shop);
        try {
            return path1(tenantId, shop, tokens, owner);
        } catch (DuplicateKeyException ex) {
            return raceRelink(new StateRecord(tenantId, shop, null), shop, tokens, resolveShopOwner(shop));
        }
    }

    /** True when some tenant already owns {@code shop} (DEFINER lookup, no GUC). */
    public boolean isShopLinked(String shop) {
        return resolveShopOwner(shop) != null;
    }

    /**
     * Embedded signup: inserts the new tenant's stores row inside the CALLER's transaction (the
     * one creating tenant + owner, tenant GUC already set) — so the account and its store commit
     * or roll back together. A concurrent install of the same shop fails here with 23505.
     */
    public UUID insertStoreInCurrentTransaction(UUID tenantId, String shop, ShopifyGateway.TokenResponse tokens) {
        ShopifyStoredToken t = ShopifyStoredToken.of(shop, tokens, encryptionService);   // refuses a non-expiring token
        return jdbc.query(INSERT_STORE,
            rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
            tenantId, shop,
            t.accessTokenEncrypted(), t.accessTokenExpiresAt(),
            t.refreshTokenEncrypted(), t.refreshTokenExpiresAt(),
            t.scopes());
    }

    /** After the signup transaction committed: first import + webhook registration. */
    public void afterStoreCreated(UUID storeId, UUID tenantId, String shop) {
        shopifyGateway.forgetOrderPiiTier(shop);
        enqueueImport(storeId, tenantId);
    }

    /** The admin URL of this app for {@code shop}, flagged so the embedded app shows "connected". */
    public String adminAppUrlAfterConnect(String shop) {
        return buildAdminAppUrl(shop, null) + "?traced_connected=1";
    }

    public static ShopifyOAuthException shopLinkedElsewhere() {
        return new ShopifyOAuthException(
            ShopifyOAuthException.Code.SHOP_LINKED_ELSEWHERE,
            "This Shopify store is already connected to a different Traced account. Sign in to that account to manage it.",
            "متجر Shopify هذا مرتبط بالفعل بحساب Traced مختلف. سجّل الدخول إلى ذلك الحساب لإدارته.",
            HttpStatus.CONFLICT);
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

    /** Layer 2 backstop check — see path1()'s owner==null branch. */
    private boolean tenantOwnsDifferentShop(UUID tenantId, String shop) {
        // Same predicate as initiate (ShopifySameShopGuard.boundShopDomains): for a real
        // tenant every row binds — exactly as strict as this backstop always was; a
        // simulated-courier tenant's disconnected rows don't.
        return TenantContext.runAs(tenantId, () -> sameShopGuard.isDifferentShop(tenantId, shop));
    }

    private UUID insertStore(UUID tenantId, String shop, ShopifyGateway.TokenResponse tokens) {
        ShopifyStoredToken.requireExpiring(shop, tokens);   // before any side effect
        shopifyGateway.forgetOrderPiiTier(shop);   // Build C: a (re)connect starts from the token's real scopes
        return TenantContext.runAs(tenantId, () -> tx.execute(s ->
                insertStoreInCurrentTransaction(tenantId, shop, tokens)));
    }

    /**
     * Re-link an existing stores row to the new OAuth token (same row, same tenant).
     *
     * Build C: when the row is still a custom app (custom_app_cc / custom_app) this is the upgrade to the
     * official app. BEFORE the swap, the old app's webhook subscriptions to Traced are deleted with the old
     * app's own credentials ({@link LegacyWebhookCleanup}); the outcome is recorded on the row (V146). A
     * failed cleanup never blocks the upgrade. The swap clears the custom-app secrets, and the per-shop
     * scope cache is dropped so the next import asks for the tier the new token allows.
     */
    private UUID updateStoreToken(UUID tenantId, String shop, ShopifyGateway.TokenResponse tokens) {
        // Refuse a non-expiring token BEFORE the legacy cleanup deletes anything or the row is touched.
        ShopifyStoredToken t = ShopifyStoredToken.of(shop, tokens, encryptionService);
        LegacyWebhookCleanup.Result cleanup;
        try {
            cleanup = legacyWebhookCleanup.run(tenantId, shop);
        } catch (RuntimeException e) {   // defensive — run() reports failures itself
            log.warn("OAUTH_UPGRADE_CLEANUP shop={} tenant={} unexpected error — upgrade continues: {}", shop, tenantId, e.toString());
            cleanup = new LegacyWebhookCleanup.Result("custom_app_cc", "failed", "unexpected error: " + e.getClass().getSimpleName());
        }
        final LegacyWebhookCleanup.Result c = cleanup;
        UUID storeId = TenantContext.runAs(tenantId, () -> tx.execute(s ->
                jdbc.query(UPDATE_STORE_TOKEN,
                    rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                    t.accessTokenEncrypted(), t.accessTokenExpiresAt(),
                    t.refreshTokenEncrypted(), t.refreshTokenExpiresAt(),
                    t.scopes(),
                    c.previousType(), c.previousType(), c.status(), c.detail(), c.status(),
                    shop, tenantId)));
        shopifyGateway.forgetOrderPiiTier(shop);
        if (c.isUpgrade()) {
            log.info("OAUTH_UPGRADE shop={} tenant={} store={} from={} cleanup={}", shop, tenantId, storeId,
                c.previousType(), c.status());
        }
        return storeId;
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
     * 1b. If status=disconnected → throw ShopifyStoreDisconnectedException, no exchange,
     *     no write. Disconnect is a deliberate user pause (button, or app/uninstalled);
     *     an incidental embedded-app open merely proves the token still works — it carries
     *     no reconnect intent and must never clear the flag. Only a deliberate OAuth-consent
     *     reconnect (path1()'s LINKED_EXISTING branch) may.
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
     * @throws ShopifyStoreDisconnectedException if the store is disconnected — caller
     *         (ApiExceptionHandler) returns 409 SHOPIFY_STORE_DISCONNECTED
     */
    public boolean acquireOrRefreshViaSessionToken(UUID tenantId, String shopDomain, String rawSessionToken) {
        // Step 1: freshness check — needs TenantContext for RLS
        record StoreSnap(UUID id, Instant expiresAt, String status, String importStatus,
                         String accessTokenScopes, String connectionType, boolean hasRefreshToken) {}
        StoreSnap snap;
        snap = TenantContext.runAs(tenantId, () -> tx.execute(s ->
                jdbc.query(
                    "SELECT id, access_token_expires_at, status::text, import_status::text, " +
                    "       access_token_scopes, connection_type, " +
                    "       (refresh_token_encrypted IS NOT NULL) AS has_refresh " +
                    "FROM stores WHERE tenant_id = ? AND shop_domain = ?",
                    rs -> rs.next() ? new StoreSnap(
                        rs.getObject("id", UUID.class),
                        rs.getTimestamp("access_token_expires_at") != null
                            ? rs.getTimestamp("access_token_expires_at").toInstant() : null,
                        rs.getString("status"),
                        rs.getString("import_status"),
                        rs.getString("access_token_scopes"),
                        rs.getString("connection_type"),
                        rs.getBoolean("has_refresh")) : null,
                    tenantId, shopDomain)));

        if (snap == null) {
            log.warn("Token exchange: store not found tenant={} shop={}", tenantId, shopDomain);
            return false;
        }

        // Disconnect = pause, not uninstall. A disconnected store must stay disconnected
        // through any incidental auth (embedded app open / session-token exchange) — this
        // is exactly the entry point that previously flipped it back to 'connected' with a
        // fresh token as a side effect of merely opening the embedded app. Thrown BEFORE the
        // connType branch and BEFORE any network call or DB write, so nothing about a
        // disconnected store's token is touched here. Only a deliberate OAuth-consent
        // reconnect (ShopifyOAuthController.callback() → path1()'s LINKED_EXISTING branch)
        // may clear this flag.
        if ("disconnected".equals(snap.status())) {
            log.info("Token exchange skipped: shop={} tenant={} is disconnected — no re-link, no refresh",
                shopDomain, tenantId);
            throw new ShopifyStoreDisconnectedException(shopDomain,
                "Store is disconnected — reconnect via Traced settings to resume sync");
        }

        // Guard: session-token exchange is only valid for OAuth stores. custom_app and
        // custom_app_cc stores manage their tokens via ShopifyTokenProvider (CC re-exchange
        // or legacy API-secret path). If the session-token exchange ran on a CC store it would
        // silently overwrite the CC token with an OAuth token, leaving connection_type stale
        // and the token in an unknown state (this is exactly how tracedlocations ended up with
        // a non-expiring OAuth token that Shopify now rejects). Skip and return true — the
        // CC token is valid and managed independently.
        String connType = snap.connectionType() != null ? snap.connectionType() : "oauth";
        if (!"oauth".equals(connType)) {
            log.info("Token exchange skipped: shop={} uses connection_type={} (CC-managed token)",
                     shopDomain, connType);
            return true;
        }

        // Step 2: skip if token is comfortably fresh AND scopes already cover the app's declared list.
        // A fresh token issued BEFORE a scope was added to the app will pass the time check but fail
        // the scope check, forcing a re-exchange so the new token carries the full scope.
        // null stored scopes means pre-migration or unknown — always re-exchange.
        Instant threshold = Instant.now().plusSeconds(600); // 10 min
        boolean timeFresh  = snap.expiresAt() != null && snap.expiresAt().isAfter(threshold);
        boolean scopesFresh = scopesMatch(snap.accessTokenScopes(), this.scopes);
        // Repair (fix/embedded-expiring-token): an OAuth row with no refresh token holds a NON-expiring
        // token (Build D's exchange omitted expiring=1). Shopify rejects it, and it can never be refreshed,
        // so it is never "fresh": always re-exchange — the next embedded open stores an expiring pair.
        boolean repairing = !snap.hasRefreshToken();
        if (repairing) {
            log.warn("Token exchange forced: shop={} tenant={} has no refresh token (non-expiring) — repairing",
                     shopDomain, tenantId);
        }
        if (timeFresh && scopesFresh && !repairing && "connected".equals(snap.status())) {
            log.debug("Token exchange skipped: token fresh and scopes match for shop={}", shopDomain);
            return true;
        }
        if (timeFresh && !scopesFresh) {
            log.info("Token exchange forced: scope mismatch for shop={} stored=[{}] declared=[{}]",
                     shopDomain, snap.accessTokenScopes(), this.scopes);
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
        boolean shouldEnqueue = repairing   // the import failed / will fail on the old token — run it again
                || "needs_reauth".equals(snap.status())
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
        ShopifyStoredToken t = ShopifyStoredToken.of(shop, tokens, encryptionService);   // refuses a non-expiring token
        shopifyGateway.forgetOrderPiiTier(shop);   // Build C: new token, possibly new scopes
        return TenantContext.runAs(tenantId, () -> tx.execute(s ->
                jdbc.query(EXCHANGE_SESSION_TOKEN_UPDATE,
                    rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                    t.accessTokenEncrypted(), t.accessTokenExpiresAt(),
                    t.refreshTokenEncrypted(), t.refreshTokenExpiresAt(),
                    t.scopes(),
                    shop, tenantId)));
    }

    // ---- private: scope comparison ------------------------------------

    /**
     * Returns true if all scopes declared in the app config are present in the
     * stored token's granted-scope list. Order-insensitive.
     * null storedScopes means "issued before scope tracking" → always return false
     * to force a re-exchange.
     *
     * Uses ShopifyGateway.isScopeGranted per element to respect Shopify's implied-scope
     * rule: write_X implicitly satisfies read_X (Shopify never echoes read_X explicitly
     * when write_X is present). A plain containsAll would be permanently unsatisfiable.
     */
    private static boolean scopesMatch(String storedScopes, String declaredScopes) {
        if (storedScopes == null) return false;
        for (String req : declaredScopes.split(",")) {
            String r = req.strip();
            if (!r.isEmpty() && !ShopifyGateway.isScopeGranted(r, storedScopes)) return false;
        }
        return true;
    }

    private static ShopifyOAuthException stateInvalid() {
        return new ShopifyOAuthException(
            ShopifyOAuthException.Code.SHOPIFY_STATE_INVALID,
            "OAuth state is invalid, expired, or already used",
            "رمز OAuth غير صالح أو منتهي الصلاحية أو مستخدم مسبقاً",
            HttpStatus.BAD_REQUEST);
    }
}
