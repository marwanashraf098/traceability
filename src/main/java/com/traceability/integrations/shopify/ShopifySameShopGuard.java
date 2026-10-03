package com.traceability.integrations.shopify;

import com.traceability.integrations.bosta.CourierSimulation;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Shop binding rule, shared by EVERY Shopify connect path (OAuth initiate, the OAuth callback
 * backstop in ShopifyOAuthService.path1(), custom-app, custom-app CC). The ONE definition of
 * which shop_domains bind a tenant — {@link #boundShopDomains}:
 *
 *   - Real tenant: EVERY stores row it has ever had, whatever its status (connected,
 *     needs_reauth, error, disconnected). A tenant is bound to its shop for good — it may
 *     reconnect that same shop_domain, never connect a different one, not even after
 *     disconnecting. (Decision 2026-10-03, review mode S1: this reverses the FR-3.1 follow-up
 *     that let a merchant disconnect one store and connect another. A store switch for a real
 *     merchant is a manual ops script, on request — there is no in-app path, by design.)
 *   - Simulated-courier tenant (review mode, a tenant_courier_simulation row, V130): only its
 *     NON-disconnected rows bind it, so its placeholder store and the previous review rounds'
 *     (uninstalled → disconnected) stores never block the next reviewer's shop. A connected
 *     different shop still does.
 *
 * Re-submitting the bound shop_domain is allowed (reconnect / CC re-exchange) as long as no
 * binding row is for a different shop. No binding rows → first connect, any shop.
 * Cross-tenant ownership of a shop_domain is unaffected —
 * that's still a straight DB UNIQUE(shop_domain) conflict at the upsert, unrelated to this
 * guard.
 *
 * KNOWN RACE (documented, not fixed here): the check and the eventual write are not atomic.
 * Two concurrent connect requests for two DIFFERENT shop_domains, from a tenant with no
 * binding rows, could both pass before either has written its row. Narrowing this would need
 * a DB-level exclusion or an advisory lock scoped to tenant_id — out of scope here.
 *
 * TenantContext contract: this class runs its own dedicated transaction (own
 * TransactionTemplate) so SET LOCAL app.current_tenant reliably fires for its queries
 * regardless of caller — but it does NOT itself call TenantContext.set/clear. Callers
 * are responsible for having TenantContext already correctly set on the calling thread:
 *   - ShopifySyncService's connect paths rely on the request-filter-set AMBIENT context
 *     (see that class's javadoc) and must keep it set for their own subsequent upsert —
 *     this guard must never clear it out from under them.
 *   - ShopifyOAuthService.initiateOAuth() / tenantOwnsDifferentShop() have no ambient
 *     context and set/clear their own scope around the call.
 * With no / another tenant set, RLS hides the flag row, so the tenant reads as REAL — the
 * strict case.
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
     * The shop_domains that bind this tenant (see the class comment). Empty = nothing binds
     * it (first connect). Ordered active-first, then most recently synced, then by domain
     * (stores has no created_at) — so the message names the store the tenant most likely
     * thinks of as "theirs"; a real tenant has at most one row anyway.
     */
    public List<String> boundShopDomains(UUID tenantId) {
        return tx.execute(s -> {
            boolean simulated = CourierSimulation.isSimulated(jdbc, tenantId);
            return jdbc.query(
                "SELECT shop_domain FROM stores WHERE tenant_id = ? " +
                (simulated ? "AND status <> 'disconnected' " : "") +
                "ORDER BY (status <> 'disconnected') DESC, last_sync_at DESC NULLS LAST, shop_domain",
                (rs, rowNum) -> rs.getString("shop_domain"), tenantId);
        });
    }

    /**
     * True when {@code requestedShop} may NOT be connected by this tenant: some binding row is
     * for a different shop_domain. For a real tenant that is exactly the OAuth callback
     * backstop's original predicate (EXISTS a row with shop_domain &lt;&gt; requested).
     */
    public boolean isDifferentShop(UUID tenantId, String requestedShop) {
        return otherBoundShop(boundShopDomains(tenantId), requestedShop) != null;
    }

    /**
     * @throws ShopifyOAuthException Code.SHOPIFY_SHOP_MISMATCH (409), naming the linked shop,
     *                                if the tenant is bound to a different shop_domain.
     */
    public void assertBoundShop(UUID tenantId, String requestedShop) {
        String linked = otherBoundShop(boundShopDomains(tenantId), requestedShop);
        if (linked != null) {
            throw new ShopifyOAuthException(
                ShopifyOAuthException.Code.SHOPIFY_SHOP_MISMATCH,
                "This account is linked to " + linked + ". It can only reconnect that store.",
                "هذا الحساب مرتبط بالمتجر " + linked + ". لا يمكن إلا إعادة ربط هذا المتجر.",
                HttpStatus.CONFLICT);
        }
    }

    /** The first binding shop_domain that isn't {@code requestedShop}, or null. */
    private static String otherBoundShop(List<String> bound, String requestedShop) {
        return bound.stream().filter(d -> !d.equals(requestedShop)).findFirst().orElse(null);
    }
}
