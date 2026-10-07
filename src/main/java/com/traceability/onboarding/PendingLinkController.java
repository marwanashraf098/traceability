package com.traceability.onboarding;

import com.traceability.identity.CustomUserDetails;
import com.traceability.integrations.shopify.ShopifyOAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Build D — the Traced side of "I already have a Traced account": {app}/connect/shopify?link=<nonce>
 * shows "Connect <shop> to <business>?" to a signed-in OWNER, who confirms. The nonce travels in
 * the request BODY (never a path or query of these API calls). Linking uses the existing rules:
 * the same-shop rule (409 SHOPIFY_SHOP_MISMATCH naming the linked shop) and one tenant per shop
 * (409 SHOP_LINKED_ELSEWHERE). Expired / used / unknown → 410 PENDING_LINK_INVALID. Preview never
 * consumes; confirm checks the rules first, then uses the link once, then links (import + webhooks).
 */
@RestController
@RequestMapping("/api/v1/shopify/pending-link")
@PreAuthorize("hasRole('OWNER')")
public class PendingLinkController {

    private static final Logger log = LoggerFactory.getLogger(PendingLinkController.class);

    private final PendingLinkStore store;
    private final ShopifyOAuthService oauth;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PendingLinkController(PendingLinkStore store, ShopifyOAuthService oauth, JdbcTemplate jdbc,
                                 PlatformTransactionManager txm) {
        this.store = store;
        this.oauth = oauth;
        this.jdbc  = jdbc;
        this.tx    = new TransactionTemplate(txm);
    }

    public record LinkBody(String link) {}

    public record Preview(String shopDomain, String businessName) {}

    public record Confirmed(String shopDomain, String redirectUrl) {}

    @PostMapping("/preview")
    public Preview preview(@AuthenticationPrincipal CustomUserDetails principal, @RequestBody LinkBody body) {
        String shop = store.peekShop(body == null ? null : body.link());
        oauth.assertPendingLinkAllowed(principal.tenantId(), shop);
        return new Preview(shop, businessName(principal));
    }

    @PostMapping("/confirm")
    public Confirmed confirm(@AuthenticationPrincipal CustomUserDetails principal, @RequestBody LinkBody body) {
        String nonce = body == null ? null : body.link();
        String shop = store.peekShop(nonce);
        oauth.assertPendingLinkAllowed(principal.tenantId(), shop);
        PendingLinkStore.Claimed claimed = store.claim(nonce, principal.tenantId());
        ShopifyOAuthService.LinkResult result = oauth.linkPendingShop(principal.tenantId(), claimed.shopDomain(), claimed.tokens());
        switch (result.outcome()) {
            case LINKED_NEW, LINKED_EXISTING -> { }
            // Only a race between the checks above and the link gets here; the link is used up.
            case REJECTED_SHOP_MISMATCH -> {
                oauth.assertPendingLinkAllowed(principal.tenantId(), claimed.shopDomain());   // names the shop
                throw PendingLinkStore.invalid();
            }
            default -> throw ShopifyOAuthService.shopLinkedElsewhere();
        }
        log.info("PENDING_LINK_CONFIRMED shop={} tenant={} outcome={}", claimed.shopDomain(), principal.tenantId(), result.outcome());
        return new Confirmed(claimed.shopDomain(), oauth.adminAppUrlAfterConnect(claimed.shopDomain()));
    }

    private String businessName(CustomUserDetails principal) {
        // The caller's own tenant row, under RLS (TenantContextFilter set the tenant).
        return tx.execute(s -> jdbc.queryForObject(
            "SELECT name FROM tenants WHERE id = ?", String.class, principal.tenantId()));
    }
}
