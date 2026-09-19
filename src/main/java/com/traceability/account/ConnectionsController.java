package com.traceability.account;

import com.traceability.identity.CustomUserDetails;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.util.*;

/**
 * FR-1.2 / FR-4: Connection status for the onboarding connect-screen.
 *
 * Reports what ALREADY EXISTS:
 *   Shopify: POST /api/v1/shopify/connect (ShopifyController) — DEV-ONLY, public OAuth pending
 *            GET  /api/v1/shopify/stores  — lists connected stores
 *   Bosta:   POST /api/v1/bosta/connect  — wire API key
 *            PUT  /api/v1/bosta/settings — configure pickup/AWB settings
 *
 * This endpoint provides the UI-ready STATUS summary those connect actions produce.
 * No new connect/disconnect logic is added here — reuse the existing actions.
 */
@RestController
@RequestMapping("/api/v1/connections")
public class ConnectionsController {

    @Value("${app.custom-app-connect-enabled:false}")
    private boolean customAppConnectEnabled;

    @Value("${shopify.oauth-available:false}")
    private boolean oauthAvailable;

    @Value("${shopify.app-url}")
    private String shopifyAppUrl;

    @Value("${shopify.redirect-uri}")
    private String shopifyRedirectUrl;

    @Value("${shopify.api-version}")
    private String shopifyWebhookApiVersion;

    @Value("${shopify.scopes}")
    private String shopifyScopesCsv;

    private final JdbcTemplate       jdbc;
    private final TransactionTemplate tx;
    private final StoreRepository    storeRepository;

    public ConnectionsController(JdbcTemplate jdbc, PlatformTransactionManager txm, StoreRepository storeRepository) {
        this.jdbc = jdbc;
        this.tx   = new TransactionTemplate(txm);
        this.storeRepository = storeRepository;
    }

    /**
     * GET /api/v1/connections
     *
     * Returns connection status for all integration types the tenant has configured.
     * Response shape:
     * {
     *   "shopify": { "connected": bool, "storeId": str|null, "shopDomain": str|null,
     *                "connectionType": "oauth"|"custom_app"|"custom_app_cc"|null,
     *                "status": "connected"|"needs_reauth"|"error"|"disconnected",
     *                "importStatus": str|null, "lastSyncAt": str|null },
     *   "bosta":   { "connected": bool, "businessName": str|null, "pickupMode": str|null },
     *   "customAppAvailable": bool,
     *   "oauthAvailable": bool,
     *   "shopifySetup": { "appUrl": str, "redirectUrl": str, "webhookApiVersion": str, "scopes": [str] }
     * }
     *
     * FR-3.1: unified — previously "shopify" (picked without regard to connection_type)
     * and "shopifyCustomApp" (connection_type IN ('custom_app','custom_app_cc')) were two
     * independent queries that could both report the SAME store row when a tenant's only
     * store happened to be a custom-app connection, misrepresenting it as also OAuth-style
     * connected. This endpoint now picks the tenant's single row directly, in every
     * connection_type — with ShopifySameShopGuard as the write-side invariant.
     *
     * FR-3.1 follow-up: the guard now allows a distinct shop_domain once the tenant's
     * existing row(s) are all status='disconnected' (disconnect-then-switch — see
     * ShopifySameShopGuard), so a tenant can legitimately hold one disconnected (old) row
     * and one active (new) row at once. The pick below is ORDER BY (status <>
     * 'disconnected') DESC — i.e. any non-disconnected row wins outright over a
     * disconnected one — THEN last_sync_at DESC NULLS LAST as the tiebreak among rows of
     * the same "active-ness". This is no longer pure defense-in-depth (as it was when one
     * row per tenant was closer to guaranteed): it is the primary mechanism that keeps a
     * stale disconnected row from ever shadowing the real active connection.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> status(@AuthenticationPrincipal CustomUserDetails principal) {
        UUID tenantId = principal.tenantId();

        return TenantContext.runAs(tenantId, () -> tx.execute(s -> {
            // Shopify — the tenant's single row (StoreRepository.findByTenant: preferring
            // status='connected', then any other non-disconnected row, then a disconnected
            // one last — any connection_type). Uses the SAME pick as
            // StoreRepository.findActiveStoreByTenant(), which every job/service now goes
            // through, so the UI and background jobs always agree on "the store". Unlike
            // findActiveStoreByTenant(), this deliberately keeps a disconnected result
            // (rather than mapping it to empty) — the UI must still render the
            // disconnected/attention state with the stale domain when that's the only row.
            Map<String, Object> shopify = new LinkedHashMap<>();
            Optional<StoreRepository.Store> storeOpt = storeRepository.findByTenant(tenantId);
            if (storeOpt.isEmpty()) {
                shopify.put("connected",      false);
                shopify.put("storeId",        null);
                shopify.put("shopDomain",     null);
                shopify.put("connectionType", null);
                shopify.put("status",         "disconnected");
                shopify.put("importStatus",   null);
                shopify.put("lastSyncAt",     null);
            } else {
                StoreRepository.Store store = storeOpt.get();
                shopify.put("connected",      "connected".equals(store.status()));
                shopify.put("storeId",        store.id().toString());
                shopify.put("shopDomain",     store.shopDomain());
                shopify.put("connectionType", store.connectionType());
                shopify.put("status",         store.status());
                shopify.put("importStatus",   store.importStatus());
                shopify.put("lastSyncAt",     store.lastSyncAt() != null ? Timestamp.from(store.lastSyncAt()) : null);
            }

            // Bosta — active courier account
            Map<String, Object> bosta = jdbc.query(
                "SELECT business_ref, pickup_mode, awb_format, awb_lang, status " +
                "FROM courier_accounts " +
                "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    if (!rs.next()) {
                        m.put("connected",    false);
                        m.put("businessName", null);
                        m.put("pickupMode",   null);
                        m.put("awbFormat",    null);
                        m.put("awbLang",      null);
                    } else {
                        m.put("connected",    true);
                        m.put("businessName", rs.getString("business_ref"));
                        m.put("pickupMode",   rs.getString("pickup_mode"));
                        m.put("awbFormat",    rs.getString("awb_format"));
                        m.put("awbLang",      rs.getString("awb_lang"));
                    }
                    return m;
                }, tenantId);

            Map<String, Object> shopifySetup = new LinkedHashMap<>();
            shopifySetup.put("appUrl",            shopifyAppUrl);
            shopifySetup.put("redirectUrl",       shopifyRedirectUrl);
            shopifySetup.put("webhookApiVersion", shopifyWebhookApiVersion);
            shopifySetup.put("scopes",            List.of(shopifyScopesCsv.split(",")));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("shopify",            shopify);
            result.put("bosta",              bosta);
            result.put("customAppAvailable", customAppConnectEnabled);
            result.put("oauthAvailable",     oauthAvailable);
            result.put("shopifySetup",       shopifySetup);
            return result;
        }));
    }
}
