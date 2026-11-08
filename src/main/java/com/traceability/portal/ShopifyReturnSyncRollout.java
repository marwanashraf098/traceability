package com.traceability.portal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Shopify-native returns S5a — which tenants may see (and switch on) "Create returns in Shopify":
 * {@code TRACED_SHOPIFY_RETURNS_SYNC_TENANTS} ({@code traced.shopify-returns-sync.tenants}, comma-separated tenant
 * ids; same parsing as PortalFeesRollout — an entry that isn't a tenant id stops startup). Not listed = the feature
 * doesn't exist for the tenant: no switch, no drawer state, no job, nothing written (golden). Listed = the owner's
 * switch ({@code tenants.shopify_returns_sync_enabled}, default OFF) decides.
 */
@Component
public class ShopifyReturnSyncRollout {

    private final Set<UUID> tenants;

    public ShopifyReturnSyncRollout(@Value("${traced.shopify-returns-sync.tenants:}") String tenants) {
        try {
            this.tenants = PortalFeesRollout.parse(tenants);
        } catch (IllegalStateException e) {
            throw new IllegalStateException("TRACED_SHOPIFY_RETURNS_SYNC_TENANTS has an entry that isn't a tenant id");
        }
    }

    public boolean listed(UUID tenantId) {
        return tenantId != null && tenants.contains(tenantId);
    }

    public boolean anyListed() {
        return !tenants.isEmpty();
    }

    public Set<UUID> listedTenants() {
        return tenants;
    }
}
