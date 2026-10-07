package com.traceability.onboarding;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.identity.AuthService;
import com.traceability.identity.MagicLinkService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyOAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Build D — onboarding inside the embedded Shopify app, for a shop no Traced tenant owns. The
 * shop always comes from the verified session token (principal.shopDomain()), never from input.
 *
 * <pre>
 * signup:       rate limit → shop still unlinked → AuthService signup rules → session-token exchange
 *               (expiring offline token; fails if the app was uninstalled → nothing created) → ONE
 *               transaction: tenant + owner + Main Warehouse (+ attribution utm_source
 *               shopify_app_store) + stores row (oauth, orders_ingest_from = now) → after commit:
 *               import + webhook registration + welcome email; the response carries the one-time
 *               "Open Traced" sign-in link — issued here and nowhere else.
 * pending link: rate limit → shop still unlinked → exchange → park the token under a 15-minute
 *               single-use nonce (PendingLinkStore); the top-level window then opens
 *               {app}/connect/shopify?link=<nonce>, where a signed-in owner confirms.
 * </pre>
 */
@Service
public class EmbeddedOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedOnboardingService.class);
    static final String ATTRIBUTION_SOURCE = "shopify_app_store";
    private static final String USERS_EMAIL_CONSTRAINT = "users_email_unique";   // V42

    private final AuthService authService;
    private final ShopifyOAuthService oauth;
    private final ShopifyGateway gateway;
    private final MagicLinkService magicLinks;
    private final PendingLinkStore pendingLinks;
    private final OnboardingRateLimiter rateLimiter;
    private final String appUrl;

    public EmbeddedOnboardingService(AuthService authService, ShopifyOAuthService oauth, ShopifyGateway gateway,
                                     MagicLinkService magicLinks, PendingLinkStore pendingLinks,
                                     OnboardingRateLimiter rateLimiter,
                                     @Value("${shopify.app-url}") String appUrl) {
        this.authService  = authService;
        this.oauth        = oauth;
        this.gateway      = gateway;
        this.magicLinks   = magicLinks;
        this.pendingLinks = pendingLinks;
        this.rateLimiter  = rateLimiter;
        this.appUrl       = appUrl;
    }

    public record Prefill(String shopDomain, String shopName, String email) {}

    public record SignupResult(String shopDomain, String email, String signInUrl, int signInValidMinutes) {}

    public record PendingLinkResult(String url) {}

    /** The store's name and contact email, to prefill the signup form (both editable there). */
    public Prefill prefill(String shop, String sessionToken, String clientIp) {
        rateLimiter.checkAndRecord(shop, clientIp);
        requireUnlinked(shop);
        ShopifyGateway.TokenResponse tokens = gateway.exchangeSessionToken(shop, sessionToken);
        ShopifyGateway.ShopInfo info = gateway.fetchShop(shop, tokens.accessToken());
        return new Prefill(shop, info.name(), info.email());
    }

    public SignupResult signup(String shop, String sessionToken, SignupRequest form,
                               String clientIp, String userAgent) {
        rateLimiter.checkAndRecord(shop, clientIp);
        requireUnlinked(shop);
        ObjectNode attribution = JsonNodeFactory.instance.objectNode().put("utmSource", ATTRIBUTION_SOURCE);
        SignupRequest req = new SignupRequest(form.tenantName(), form.name(), form.email(), form.phone(),
                form.password(), form.consent(), attribution);
        String phone = authService.validateSignup(req);

        ShopifyGateway.TokenResponse tokens = gateway.exchangeSessionToken(shop, sessionToken);

        AuthService.CreatedAccount account;
        try {
            account = authService.createAccount(req, phone, clientIp, userAgent,
                    tenantId -> oauth.insertStoreInCurrentTransaction(tenantId, shop, tokens));
        } catch (DuplicateKeyException e) {
            String msg = e.getMostSpecificCause().getMessage();
            if (msg != null && msg.contains(USERS_EMAIL_CONSTRAINT)) throw OnboardingException.emailTaken();
            throw ShopifyOAuthService.shopLinkedElsewhere();   // stores.shop_domain: a concurrent install won
        }
        oauth.afterStoreCreated(account.extraId(), account.tenantId(), shop);
        log.info("EMBEDDED_SIGNUP shop={} tenant={} store={}", shop, account.tenantId(), account.extraId());
        String signInUrl = magicLinks.issueSignInLink(account.userId(), account.tenantId());
        return new SignupResult(shop, req.email(), signInUrl, MagicLinkService.SIGN_IN_LINK_TTL_MINUTES);
    }

    public PendingLinkResult createPendingLink(String shop, String sessionToken, String clientIp) {
        rateLimiter.checkAndRecord(shop, clientIp);
        requireUnlinked(shop);
        ShopifyGateway.TokenResponse tokens = gateway.exchangeSessionToken(shop, sessionToken);
        String nonce = pendingLinks.create(shop, tokens);
        log.info("PENDING_LINK_CREATED shop={}", shop);
        return new PendingLinkResult(appUrl + "/connect/shopify?link=" + nonce);
    }

    private void requireUnlinked(String shop) {
        if (oauth.isShopLinked(shop)) throw ShopifyOAuthService.shopLinkedElsewhere();
    }
}
