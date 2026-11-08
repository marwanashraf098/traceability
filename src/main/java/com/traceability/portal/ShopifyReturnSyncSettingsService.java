package com.traceability.portal;

import com.traceability.account.AuditService;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shopify-native returns S5a — the owner's "Create returns in Shopify" switch (Settings → Returns portal). Visible
 * (and writable) only for a tenant listed in TRACED_SHOPIFY_RETURNS_SYNC_TENANTS: GET answers {visible:false} and PUT
 * 404 otherwise. Switching it ON stamps {@code shopify_returns_sync_since = now()} — only requests decided from then
 * on are created in Shopify (no backfill). One audit_log row per real change.
 */
@Service
public class ShopifyReturnSyncSettingsService {

    private final JdbcTemplate jdbc;
    private final ShopifyReturnSyncRollout rollout;
    private final AuditService audit;

    public ShopifyReturnSyncSettingsService(JdbcTemplate jdbc, ShopifyReturnSyncRollout rollout, AuditService audit) {
        this.jdbc = jdbc;
        this.rollout = rollout;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get() {
        UUID tenantId = TenantContext.require();
        Map<String, Object> out = new LinkedHashMap<>();
        if (!rollout.listed(tenantId)) {
            out.put("visible", false);
            return out;
        }
        Map<String, Object> t = jdbc.queryForMap(
            "SELECT shopify_returns_sync_enabled, shopify_returns_sync_since FROM tenants WHERE id = ?", tenantId);
        List<String> scopes = jdbc.queryForList(
            "SELECT access_token_scopes FROM stores WHERE tenant_id = ? AND status = 'connected' ORDER BY id",
            String.class, tenantId);
        out.put("visible", true);
        out.put("enabled", Boolean.TRUE.equals(t.get("shopify_returns_sync_enabled")));
        Timestamp since = (Timestamp) t.get("shopify_returns_sync_since");
        out.put("since", since == null ? null : since.toInstant().toString());
        out.put("permissionGranted", !scopes.isEmpty() && scopes.stream().allMatch(s ->
            com.traceability.integrations.shopify.ShopifyGateway.isScopeGranted("write_returns", s)));
        return out;
    }

    @Transactional
    public Map<String, Object> set(boolean enabled, UUID actor) {
        UUID tenantId = TenantContext.require();
        if (!rollout.listed(tenantId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        int changed = jdbc.update(
            "UPDATE tenants SET shopify_returns_sync_enabled = ?, " +
            "    shopify_returns_sync_since = CASE WHEN ? THEN now() ELSE shopify_returns_sync_since END " +
            "WHERE id = ? AND shopify_returns_sync_enabled IS DISTINCT FROM ?",
            enabled, enabled, tenantId, enabled);
        if (changed == 1) {
            audit.record(actor, "shopify_returns_sync_" + (enabled ? "on" : "off"), "tenant", tenantId.toString(), null);
        }
        return get();
    }
}
