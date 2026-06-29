package com.traceability.embedded;

import com.traceability.identity.CustomUserDetails;
import com.traceability.integrations.shopify.ShopifyOAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Acquires or refreshes the Shopify offline access token via session-token exchange.
 * Called by the embedded app on every mount in parallel with the dashboard data fetches.
 *
 * Security: behind ShopifySessionTokenFilter — SHOPIFY_EMBEDDED principal is set only
 * after full HMAC verification of the session token. The shop domain is read from the
 * verified principal (not from any request param or body), so this endpoint cannot be
 * redirected toward a different shop by any caller input.
 *
 * EmbeddedReadOnlyGuardTest exemption: this is an auth/credential POST, not a data
 * mutation. It writes only to stores.access_token_* columns (credentials). No inventory
 * or business data is created, modified, or deleted. The guard allowlist carries this
 * class+method name — see EmbeddedReadOnlyGuardTest.TOKEN_EXCHANGE_EXEMPTIONS.
 */
@RestController
@RequestMapping("/api/v1/embedded")
public class EmbeddedTokenExchangeController {

    private final ShopifyOAuthService oauthService;

    public EmbeddedTokenExchangeController(ShopifyOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    /**
     * POST /api/v1/embedded/token-exchange
     *
     * Responses:
     *   204 — token is fresh (skipped) or exchange succeeded.
     *   401 — session token rejected by ShopifySessionTokenFilter (never reaches this method).
     *   502 — Shopify rejected the exchange (4xx); handled by ApiExceptionHandler.
     *         The dashboard still renders; connection-status card shows the store state.
     *   503 — Shopify 5xx or timeout; handled by ApiExceptionHandler. Retry on next open.
     */
    @PostMapping("/token-exchange")
    @PreAuthorize("hasRole('SHOPIFY_EMBEDDED')")
    public ResponseEntity<Void> tokenExchange(
            @AuthenticationPrincipal CustomUserDetails principal,
            HttpServletRequest request) {

        // Raw session token from Authorization: Bearer — already HMAC-verified by the filter.
        // We pass it to Shopify's token-exchange endpoint; no re-verification needed here.
        String rawToken = request.getHeader("Authorization").substring(7);

        // shopDomain comes from the HMAC-verified principal, not from any request parameter.
        // This is the structural cross-shop confinement guarantee: a caller cannot redirect
        // this endpoint to act on a different shop by supplying a different shop param.
        oauthService.acquireOrRefreshViaSessionToken(
                principal.tenantId(), principal.shopDomain(), rawToken);

        return ResponseEntity.noContent().build(); // 204
    }
}
