package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.CustomUserDetails;
import org.jobrunr.jobs.JobId;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shopify")
public class ShopifyController {

    @Value("${app.custom-app-connect-enabled:false}")
    private boolean customAppConnectEnabled;

    private final ShopifySyncService        syncService;
    private final ShopifyGateway            shopifyGateway;
    private final ShopifyImportJob          importJob;
    private final RegisterShopifyWebhooksJob webhooksJob;
    private final ProductImageBackfillJob   productImageBackfillJob;
    private final JobScheduler              jobScheduler;
    private final JdbcTemplate              jdbc;
    private final ObjectMapper              mapper;
    private final TransactionTemplate       tx;
    private final ShopifyTokenProvider      tokenProvider;

    public ShopifyController(ShopifySyncService syncService,
                              ShopifyGateway shopifyGateway,
                              ShopifyImportJob importJob,
                              RegisterShopifyWebhooksJob webhooksJob,
                              ProductImageBackfillJob productImageBackfillJob,
                              JobScheduler jobScheduler,
                              JdbcTemplate jdbc,
                              ObjectMapper mapper,
                              PlatformTransactionManager txm,
                              ShopifyTokenProvider tokenProvider) {
        this.syncService     = syncService;
        this.shopifyGateway  = shopifyGateway;
        this.importJob       = importJob;
        this.webhooksJob     = webhooksJob;
        this.productImageBackfillJob = productImageBackfillJob;
        this.jobScheduler    = jobScheduler;
        this.jdbc            = jdbc;
        this.mapper          = mapper;
        this.tx              = new TransactionTemplate(txm);
        this.tokenProvider   = tokenProvider;
    }

    public record ConnectRequest(String shopDomain, String adminToken) {}
    public record CustomConnectRequest(String shopDomain, String clientId, String clientSecret) {}
    public record ConnectResponse(String storeId, String importStatus) {}
    public record StoreStatusResponse(String storeId, String importStatus, Object importSummary) {}

    /** Validates credentials + enqueues background import. Returns 202 immediately. */
    @PostMapping("/connect")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ConnectResponse> connect(
            @RequestBody ConnectRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {

        ShopifySyncService.ConnectResult result =
            syncService.connect(principal.tenantId(), req.shopDomain(), req.adminToken());

        UUID storeId  = result.storeId();
        UUID tenantId = principal.tenantId();
        jobScheduler.enqueue(() -> importJob.run(storeId, tenantId));

        return ResponseEntity.accepted()
            .body(new ConnectResponse(storeId.toString(), "pending"));
    }

    /**
     * POST /api/v1/shopify/custom-connect — DEV/pilot custom-app CC connection path.
     *
     * Guarded by the app.custom-app-connect-enabled feature flag (default: false).
     * The existing OAuth path (/connect) is NOT modified.
     *
     * Uses Shopify's client-credentials grant: POST /admin/oauth/access_token with
     * grant_type=client_credentials. Token lifetime ~24h; re-exchanged on expiry by
     * ShopifyTokenProvider using the stored clientId + clientSecret.
     *
     * Required scopes on the Dev Dashboard custom app:
     *   read_orders, read_products, read_fulfillments, write_webhooks.
     * The app MUST be installed on the store (Install in Dev Dashboard) before connecting.
     */
    @PostMapping("/custom-connect")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ConnectResponse> customConnect(
            @RequestBody CustomConnectRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {

        if (!customAppConnectEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Custom-app connection is not available in this environment");
        }

        if (req.shopDomain() == null || !req.shopDomain().matches("[a-zA-Z0-9][a-zA-Z0-9\\-]*\\.myshopify\\.com")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid shop domain format");
        }
        if (req.clientId() == null || req.clientId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientId is required");
        }
        if (req.clientSecret() == null || req.clientSecret().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientSecret is required");
        }

        String shopDomain    = req.shopDomain().trim();
        String clientId      = req.clientId().trim();
        String clientSecret  = req.clientSecret().trim();

        // Exchange Client ID + Client Secret for a short-lived access token.
        ShopifyGateway.TokenResponse tokens;
        try {
            tokens = shopifyGateway.exchangeClientCredentials(shopDomain, clientId, clientSecret);
        } catch (ShopifyStoreNeedsReauthException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("shop_not_permitted")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This app and store are not in the same Shopify organization. " +
                    "Create the custom app from your store's own Dev Dashboard.");
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid Client ID or Client Secret — check your Dev Dashboard API credentials.");
        } catch (ShopifyTransientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Could not reach Shopify — try again shortly.");
        }

        // Validate the token by fetching the shop resource.
        try {
            shopifyGateway.fetchShop(shopDomain, tokens.accessToken());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Could not verify store access with the obtained token");
        }

        // Persist CC store row.
        ShopifySyncService.ConnectResult result = syncService.connectCustomAppCC(
            principal.tenantId(), shopDomain, clientId, clientSecret,
            tokens.accessToken(), tokens.expiresIn());

        UUID storeId  = result.storeId();
        UUID tenantId = principal.tenantId();
        jobScheduler.enqueue(() -> importJob.run(storeId, tenantId));
        jobScheduler.enqueue(() -> webhooksJob.run(storeId, tenantId));

        return ResponseEntity.accepted()
            .body(new ConnectResponse(storeId.toString(), "pending"));
    }

    /** Lists all connected stores for the current tenant. */
    @GetMapping("/stores")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Object listStores(@AuthenticationPrincipal CustomUserDetails principal) {
        return tx.execute(s -> jdbc.queryForList(
            "SELECT id, shop_domain, status, import_status, last_sync_at FROM stores WHERE tenant_id = ?",
            principal.tenantId()));
    }

    /**
     * One-time backfill: fetches and fills products.image_url for this tenant's
     * already-synced products (rows that predate image capture). Not needed for stores
     * connected after this feature shipped, and not run automatically anywhere — new/updated
     * products capture the image at the two live sync points (catalog import,
     * products/create|update webhook) instead. Safe to re-trigger; idempotent.
     */
    @PostMapping("/backfill-product-images")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Map<String, Object>> backfillProductImages(
            @AuthenticationPrincipal CustomUserDetails principal) {
        UUID tenantId = principal.tenantId();
        JobId jobId = jobScheduler.enqueue(() -> productImageBackfillJob.run(tenantId));
        return ResponseEntity.ok(Map.of(
            "jobId",   jobId != null ? jobId.asUUID().toString() : "enqueued",
            "message", "Product image backfill enqueued"));
    }

    /** Re-runs the Shopify import synchronously for an already-connected store. */
    @PostMapping("/stores/{storeId}/sync")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> sync(
            @PathVariable UUID storeId,
            @AuthenticationPrincipal CustomUserDetails principal) {
        UUID tenantId = resolveStoreTenant(storeId, principal);
        importJob.run(storeId, tenantId);
        return ResponseEntity.ok().build();
    }

    /**
     * Registers Shopify webhooks synchronously for an already-connected store.
     * Use this after a needs_reauth recovery where webhooks were dropped — avoids
     * requiring an uninstall/reinstall to re-register order sync topics.
     */
    @PostMapping("/stores/{storeId}/register-webhooks")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> registerWebhooks(
            @PathVariable UUID storeId,
            @AuthenticationPrincipal CustomUserDetails principal) {
        UUID tenantId = resolveStoreTenant(storeId, principal);
        webhooksJob.run(storeId, tenantId);
        return ResponseEntity.ok().build();
    }

    /**
     * Forces an immediate CC token re-exchange + real granted-scope read for one
     * connection_type=custom_app_cc store — without requiring the merchant to re-enter
     * Client ID/Secret through /custom-connect. Use this after updating the custom app's
     * Admin API access scopes in the store admin: a fresh client-credentials exchange always
     * reflects the app's CURRENTLY configured scopes, and this triggers that exchange right
     * now instead of waiting up to ~24h for the natural near-expiry refresh cycle.
     *
     * Not applicable to public-OAuth or legacy custom_app (non-CC) stores — those refresh
     * scopes through their own respective flows (OAuth reconnect / token exchange).
     */
    @PostMapping("/stores/{storeId}/refresh-cc-scopes")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Map<String, Object>> refreshCcScopes(
            @PathVariable UUID storeId,
            @AuthenticationPrincipal CustomUserDetails principal) {
        resolveStoreTenant(storeId, principal);

        String connectionType = tx.execute(s -> jdbc.query(
            "SELECT connection_type FROM stores WHERE id = ?",
            rs -> rs.next() ? rs.getString(1) : null,
            storeId));
        if (!"custom_app_cc".equals(connectionType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "This action is only valid for custom_app_cc stores (was: " + connectionType + ")");
        }

        tokenProvider.forceReExchangeNow(storeId);

        String grantedScopes = tx.execute(s -> jdbc.query(
            "SELECT access_token_scopes FROM stores WHERE id = ?",
            rs -> rs.next() ? rs.getString(1) : null,
            storeId));

        return ResponseEntity.ok(Map.of(
            "storeId", storeId.toString(),
            "accessTokenScopes", grantedScopes != null ? grantedScopes : ""));
    }

    /**
     * Resolves the tenant for a store, confirming it belongs to the caller's tenant.
     * Throws 404 if the store doesn't exist under the principal's tenant.
     *
     * Note on the two-tenant problem: stores installed via Shopify OAuth are provisioned
     * under a separate tenant from the owner's regular login account. If these don't match,
     * the caller gets a 404 with a hint to use the embedded app path instead.
     */
    private UUID resolveStoreTenant(UUID storeId, CustomUserDetails principal) {
        UUID found = tx.execute(s -> jdbc.query(
            "SELECT tenant_id FROM stores WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? rs.getObject("tenant_id", UUID.class) : null,
            storeId, principal.tenantId()));
        if (found == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Store not found or does not belong to this account. " +
                "If installed via Shopify OAuth, use the embedded app to authenticate as that tenant.");
        }
        return found;
    }

    /** Returns the current import status for a store (owner or manager). */
    @GetMapping("/stores/{storeId}/status")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public StoreStatusResponse storeStatus(
            @PathVariable UUID storeId,
            @AuthenticationPrincipal CustomUserDetails principal) {

        return tx.execute(s -> jdbc.query(
            "SELECT id, import_status, import_summary FROM stores WHERE id = ?",
            rs -> {
                if (!rs.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found");
                String summaryJson = rs.getString("import_summary");
                Object summary = null;
                if (summaryJson != null) {
                    try { summary = mapper.readValue(summaryJson, Object.class); }
                    catch (Exception ignored) { summary = summaryJson; }
                }
                return new StoreStatusResponse(
                    rs.getObject("id", UUID.class).toString(),
                    rs.getString("import_status"),
                    summary);
            }, storeId));
    }
}
