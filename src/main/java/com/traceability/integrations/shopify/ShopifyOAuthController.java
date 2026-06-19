package com.traceability.integrations.shopify;

import com.traceability.identity.CustomUserDetails;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * Shopify OAuth Day 1 — install stub + callback (Path-1 happy path).
 *
 * Path-1: logged-in owner hits POST /api/v1/shopify/oauth/initiate → gets consent URL →
 *   browser goes to Shopify → Shopify redirects back to GET /auth/shopify/callback.
 *
 * Path-2: merchant clicks "Add app" in App Store → Shopify hits GET /auth/shopify/install →
 *   install stub creates null-tenant state, redirects to consent. Callback with null-tenant
 *   state returns SHOPIFY_PATH2_NOT_YET (Day 2 will wire the resolve-or-create decision tree).
 *
 * Security:
 *   - initiate: JWT-authenticated (Owner only). tenant_id from JWT, never from query param.
 *   - install / callback: unauthenticated. HMAC + state are the only trust anchors.
 *   - callback HMAC is verified before ANY DB read or write.
 *   - TenantContext is set/cleared around the store upsert block (the only RLS-touching write).
 */
@RestController
public class ShopifyOAuthController {

    private static final String SHOP_DOMAIN_PATTERN = "[a-zA-Z0-9][a-zA-Z0-9-]*\\.myshopify\\.com";

    private final ShopifyOAuthService oauthService;

    public ShopifyOAuthController(ShopifyOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    public record InitiateRequest(String shop) {}
    public record InitiateResponse(String consentUrl) {}

    /**
     * Path-1 initiate: authenticated Owner supplies shop domain, receives consent URL.
     * JWT supplies the tenant_id — it is NEVER read from the request body or query param.
     */
    @PostMapping("/api/v1/shopify/oauth/initiate")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<InitiateResponse> initiate(
            @RequestBody InitiateRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {

        String shop = req.shop();
        if (shop == null || !shop.matches(SHOP_DOMAIN_PATTERN)) {
            throw new ShopifyOAuthException(
                ShopifyOAuthException.Code.SHOPIFY_STATE_INVALID,
                "Invalid shop domain — must be *.myshopify.com",
                "نطاق المتجر غير صالح — يجب أن يكون *.myshopify.com",
                HttpStatus.BAD_REQUEST);
        }

        String nonce      = oauthService.initiateOAuth(principal.tenantId(), shop);
        String consentUrl = oauthService.buildConsentUrl(shop, nonce);
        return ResponseEntity.ok(new InitiateResponse(consentUrl));
    }

    /**
     * Path-2 install stub: Shopify redirects merchants here from the App Store.
     * Verifies HMAC, generates state with tenant_id=NULL, redirects to Shopify consent.
     * Callback with null-tenant state returns SHOPIFY_PATH2_NOT_YET until Day 2.
     */
    @GetMapping("/auth/shopify/install")
    public ResponseEntity<Void> install(@RequestParam Map<String, String> allParams) {
        String shop = allParams.getOrDefault("shop", "");
        if (!shop.matches(SHOP_DOMAIN_PATTERN)) {
            throw new ShopifyOAuthException(
                ShopifyOAuthException.Code.SHOPIFY_HMAC_INVALID,
                "Invalid or missing shop domain",
                "نطاق المتجر غير صالح أو مفقود",
                HttpStatus.BAD_REQUEST);
        }
        if (!ShopifyHmacUtil.verifyOAuthParams(allParams, oauthService.getClientSecret())) {
            throw new ShopifyOAuthException(
                ShopifyOAuthException.Code.SHOPIFY_HMAC_INVALID,
                "HMAC validation failed",
                "فشل التحقق من HMAC",
                HttpStatus.UNAUTHORIZED);
        }

        String nonce = oauthService.initiateOAuth(null, shop);
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(oauthService.buildConsentUrl(shop, nonce)))
            .build();
    }

    /**
     * OAuth callback: verifies HMAC → consumes state → exchanges code → upserts store → enqueues import.
     *
     * Param trust order:
     *   1. HMAC verified first (before any DB read).
     *   2. shop is bound from callback params (matched against state.shop_domain).
     *   3. tenant_id comes from the state record (set at initiate time from the JWT) — NEVER from params.
     */
    @GetMapping("/auth/shopify/callback")
    public ResponseEntity<Void> callback(@RequestParam Map<String, String> allParams) {
        String shop  = allParams.getOrDefault("shop", "");
        String state = allParams.getOrDefault("state", "");
        String code  = allParams.getOrDefault("code", "");

        // Step 1: verify HMAC before touching any state
        if (!ShopifyHmacUtil.verifyOAuthParams(allParams, oauthService.getClientSecret())) {
            throw new ShopifyOAuthException(
                ShopifyOAuthException.Code.SHOPIFY_HMAC_INVALID,
                "HMAC validation failed",
                "فشل التحقق من HMAC",
                HttpStatus.UNAUTHORIZED);
        }

        // Step 2: consume state (atomic; throws SHOPIFY_STATE_INVALID on any violation)
        ShopifyOAuthService.StateRecord stateRec = oauthService.consumeState(state, shop);

        // Step 3: Path-2 check — null tenant_id is not handled until Day 2
        if (stateRec.tenantId() == null) {
            throw new ShopifyOAuthException(
                ShopifyOAuthException.Code.SHOPIFY_PATH2_NOT_YET,
                "New-merchant registration is not yet available — check back soon",
                "تسجيل التجار الجدد غير متاح بعد — يرجى المحاولة لاحقاً",
                HttpStatus.SERVICE_UNAVAILABLE);
        }

        // Step 4: exchange code, encrypt token, upsert store, enqueue import
        TenantContext.set(stateRec.tenantId());
        try {
            UUID storeId = oauthService.handleCallback(stateRec.tenantId(), shop, code);
            return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(oauthService.getAppUrl()))
                .header("X-Store-Id", storeId.toString())
                .build();
        } finally {
            TenantContext.clear();
        }
    }
}
