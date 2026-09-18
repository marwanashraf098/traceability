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
 * custom-app CC): a tenant with a NON-DISCONNECTED stores row (connected, needs_reauth,
 * or error) may only connect a shop_domain it already owns. A tenant whose stores are
 * ALL status='disconnected' (or has none at all) may connect any valid shop — this is
 * the disconnect-then-switch case: a merchant who connected the wrong store and
 * disconnected it must be able to connect the right one, not be permanently 409'd.
 * Re-submitting the SAME shop_domain is always allowed regardless of status (the
 * legitimate reconnect / CC re-exchange case) — a disconnected row for that exact
 * domain doesn't even reach the mismatch check below, since it's excluded from
 * activeShopDomains and an empty list short-circuits to "allowed" either way.
 *
 * FR-3.1 follow-up (approved): originally this only excluded nothing (ANY existing row,
 * including disconnected, blocked a different shop) — a merchant who disconnected a
 * wrong store stayed permanently bound to it. Relaxed to ignore disconnected rows.
 * Cross-tenant ownership of a shop_domain is unaffected — that's still a straight DB
 * UNIQUE(shop_domain) conflict at the upsert, unrelated to this guard.
 *
 * KNOWN RACE (documented, not fixed here — same exposure OAuth's assertBoundShop had
 * before this class existed): the existence check and the eventual write are not
 * atomic. Two concurrent connect requests for two DIFFERENT shop_domains, from a tenant
 * with zero active rows, could both pass this guard before either has written its row —
 * both would then succeed, leaving the tenant with two active stores. Narrowing this
 * would need a DB-level exclusion (e.g. a partial unique index on tenant_id WHERE status
 * <> 'disconnected', or an advisory lock scoped to tenant_id) — out of scope here.
 *
 * Originally OAuth-only (ShopifyOAuthService.assertBoundShop); extracted so the
 * custom-app and custom-app-CC connect paths (ShopifySyncService.connect(),
 * connectCustomApp(), connectCustomAppCC()) enforce the identical invariant — those
 * paths had no equivalent guard, so a tenant could accumulate a second stores row for a
 * different shop_domain via /custom-connect even though the mockup and the rest of the
 * product model exactly one ACTIVE Shopify connection per tenant.
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
     *                                owns a different, non-disconnected shop_domain than
     *                                requestedShop.
     */
    public void assertBoundShop(UUID tenantId, String requestedShop) {
        List<String> activeShopDomains = tx.execute(s -> jdbc.query(
            "SELECT shop_domain FROM stores WHERE tenant_id = ? AND status <> 'disconnected'",
            (rs, rowNum) -> rs.getString("shop_domain"), tenantId));

        if (!activeShopDomains.isEmpty() && !activeShopDomains.contains(requestedShop)) {
            throw new ShopifyOAuthException(
                ShopifyOAuthException.Code.SHOPIFY_SHOP_MISMATCH,
                "This account is connected to " + activeShopDomains.get(0) +
                    " and can only reconnect that store.",
                "هذا الحساب متصل بـ " + activeShopDomains.get(0) +
                    " ولا يمكن إلا إعادة الاتصال بنفس المتجر.",
                HttpStatus.CONFLICT);
        }
    }
}
