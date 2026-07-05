package com.traceability.account;

import com.traceability.identity.CustomUserDetails;
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

    private final JdbcTemplate       jdbc;
    private final TransactionTemplate tx;

    public ConnectionsController(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.tx   = new TransactionTemplate(txm);
    }

    /**
     * GET /api/v1/connections
     *
     * Returns connection status for all integration types the tenant has configured.
     * Response shape:
     * {
     *   "shopify": { "connected": bool, "shopDomain": str|null, "importStatus": str|null, "lastSyncAt": str|null },
     *   "bosta":   { "connected": bool, "businessName": str|null, "pickupMode": str|null }
     * }
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> status(@AuthenticationPrincipal CustomUserDetails principal) {
        UUID tenantId = principal.tenantId();

        return TenantContext.runAs(tenantId, () -> tx.execute(s -> {
            // Shopify — take the most recently connected store
            Map<String, Object> shopify = jdbc.query(
                "SELECT shop_domain, status, import_status::text, last_sync_at " +
                "FROM stores WHERE tenant_id = ? ORDER BY last_sync_at DESC NULLS LAST LIMIT 1",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    if (!rs.next()) {
                        m.put("connected",    false);
                        m.put("shopDomain",   null);
                        m.put("importStatus", null);
                        m.put("lastSyncAt",   null);
                    } else {
                        boolean connected = "connected".equals(rs.getString("status"));
                        m.put("connected",    connected);
                        m.put("shopDomain",   rs.getString("shop_domain"));
                        m.put("importStatus", rs.getString("import_status"));
                        m.put("lastSyncAt",   rs.getTimestamp("last_sync_at"));
                    }
                    return m;
                }, tenantId);

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

            // shopifyCustomApp status — custom_app and custom_app_cc connection_type stores
            Map<String, Object> shopifyCustomApp = jdbc.query(
                "SELECT shop_domain, status, import_status::text, last_sync_at " +
                "FROM stores WHERE tenant_id = ? AND connection_type IN ('custom_app', 'custom_app_cc') " +
                "ORDER BY last_sync_at DESC NULLS LAST LIMIT 1",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    if (!rs.next()) {
                        m.put("connected",    false);
                        m.put("shopDomain",   null);
                        m.put("importStatus", null);
                        m.put("lastSyncAt",   null);
                    } else {
                        boolean connected = "connected".equals(rs.getString("status"));
                        m.put("connected",    connected);
                        m.put("shopDomain",   rs.getString("shop_domain"));
                        m.put("importStatus", rs.getString("import_status"));
                        m.put("lastSyncAt",   rs.getTimestamp("last_sync_at"));
                    }
                    return m;
                }, tenantId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("shopify",          shopify);
            result.put("bosta",            bosta);
            result.put("shopifyCustomApp", shopifyCustomApp);
            result.put("customAppAvailable", customAppConnectEnabled);
            return result;
        }));
    }
}
