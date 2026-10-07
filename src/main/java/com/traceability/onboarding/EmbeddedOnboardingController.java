package com.traceability.onboarding;

import com.traceability.identity.CustomUserDetails;
import com.traceability.identity.model.SignupRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Build D — /api/v1/embedded/onboarding/**. Reached only with a verified Shopify session token:
 * ShopifySessionTokenFilter gives these paths (and only these) a tenantless SHOPIFY_ONBOARDING
 * principal whose shopDomain comes from the token's signed dest claim. The shop is never read from
 * the request. Kept outside com.traceability.embedded on purpose: that package is read-only
 * (EmbeddedReadOnlyGuardTest); these POSTs create an account or a pending link.
 *
 * Errors: 409 SHOP_LINKED_ELSEWHERE (the shop is linked), 409 EMAIL_TAKEN, 400 / 422 signup rules,
 * 429 RATE_LIMITED, 502 / 503 Shopify token exchange (nothing created).
 */
@RestController
@RequestMapping("/api/v1/embedded/onboarding")
@PreAuthorize("hasRole('SHOPIFY_ONBOARDING')")
public class EmbeddedOnboardingController {

    private final EmbeddedOnboardingService service;

    public EmbeddedOnboardingController(EmbeddedOnboardingService service) {
        this.service = service;
    }

    @GetMapping("/prefill")
    public EmbeddedOnboardingService.Prefill prefill(@AuthenticationPrincipal CustomUserDetails principal,
                                                     HttpServletRequest request) {
        return service.prefill(principal.shopDomain(), sessionToken(request), request.getRemoteAddr());
    }

    /** Body: tenantName, name, email, phone, password, consent — no shop, no attribution (set here). */
    @PostMapping("/signup")
    public EmbeddedOnboardingService.SignupResult signup(@AuthenticationPrincipal CustomUserDetails principal,
                                                         @RequestBody SignupRequest form,
                                                         HttpServletRequest request) {
        return service.signup(principal.shopDomain(), sessionToken(request), form,
                request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
    }

    @PostMapping("/pending-link")
    public EmbeddedOnboardingService.PendingLinkResult pendingLink(@AuthenticationPrincipal CustomUserDetails principal,
                                                                   HttpServletRequest request) {
        return service.createPendingLink(principal.shopDomain(), sessionToken(request), request.getRemoteAddr());
    }

    /** Already HMAC-verified by the filter; only forwarded to Shopify's token exchange. */
    private static String sessionToken(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.AUTHORIZATION).substring(7);
    }
}
