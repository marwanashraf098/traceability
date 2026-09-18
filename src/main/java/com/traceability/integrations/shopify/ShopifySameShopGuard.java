package com.traceability.integrations.shopify;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Same-shop-only guard, shared by EVERY Shopify connect path (OAuth, custom-app,
 * custom-app CC): a tenant with ANY existing stores row (any status — connected,
 * disconnected, needs_reauth, error) may only connect a shop_domain it already owns.
 * Zero existing rows means first connect — any valid shop is allowed. Re-submitting the
 * SAME shop_domain is always allowed (the legitimate reconnect / CC re-exchange case).
 *
 * Originally OAuth-only (ShopifyOAuthService.assertBoundShop); extracted so the
 * custom-app and custom-app-CC connect paths (ShopifySyncService.connect(),
 * connectCustomApp(), connectCustomAppCC()) enforce the identical invariant — those
 * paths had no equivalent guard, so a tenant could accumulate a second stores row for a
 * different shop_domain via /custom-connect even though the mockup and the rest of the
 * product model exactly one Shopify connection per tenant.
 *
 * TenantContext contract: this class runs its own dedicated transaction (own
 * TransactionTemplate) so SET LOCAL app.current_tenant reliably fires for its query
 * regardless of caller — but it does NOT itself call TenantContext.set/clear. Callers
 * are responsible for having TenantContext already correctly set on the calling thread
 * before invoking assertBoundShop:
 *   - ShopifySyncService's connect paths rely on the request-filter-set AMBIENT context
 *     (see that class's javadoc) and must keep it set for their own subsequent upsert —
 *     this guard must never clear it out from under them.
 *   - ShopifyOAuthService.initiateOAuth() has no ambient context at that point (the
 *     controller explicitly does not touch TenantContext) and sets/clears its own scope
 *     around the call to this guard, exactly as it did before extraction.
 */
@Component
public class ShopifySameShopGuard {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ShopifySameShopGuard(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.tx   = new TransactionTemplate(txm);
    }

    /**
     * @throws ShopifyOAuthException Code.SHOPIFY_SHOP_MISMATCH (409) if the tenant already
     *                                owns a different shop_domain than requestedShop.
     */
    public void assertBoundShop(UUID tenantId, String requestedShop) {
        List<String> shopDomains = tx.execute(s -> jdbc.query(
            "SELECT shop_domain FROM stores WHERE tenant_id = ?",
            (rs, rowNum) -> rs.getString("shop_domain"), tenantId));

        if (!shopDomains.isEmpty() && !shopDomains.contains(requestedShop)) {
            throw new ShopifyOAuthException(
                ShopifyOAuthException.Code.SHOPIFY_SHOP_MISMATCH,
                "This account is connected to " + shopDomains.get(0) +
                    " and can only reconnect that store.",
                "هذا الحساب متصل بـ " + shopDomains.get(0) +
                    " ولا يمكن إلا إعادة الاتصال بنفس المتجر.",
                HttpStatus.CONFLICT);
        }
    }
}
