package com.traceability.integrations.shopify;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

/**
 * Shopify OAuth — install stub + callback (Path-1 and Path-2).
 *
 * Path-1: authenticated Owner hits POST /api/v1/shopify/oauth/initiate → receives consent URL →
 *   browser goes to Shopify → Shopify redirects to GET /auth/shopify/callback.
 *
 * Path-2: merchant clicks "Add app" in App Store → Shopify hits GET /auth/shopify/install →
 *   controller creates null-tenant state → redirects to consent.
 *   Callback with null-tenant state uses the resolve-or-create decision tree in
 *   ShopifyOAuthService.linkOrProvision (Day 2).
 *
 * Security:
 *   - initiate: JWT-authenticated (Owner only). tenant_id from JWT, never from query param.
 *   - install / callback: unauthenticated. HMAC + state are the only trust anchors.
 *   - HMAC is verified before ANY DB read or write.
 *   - Timestamp freshness (300 s) checked after HMAC on both install and callback.
 *   - TenantContext is managed inside ShopifyOAuthService — controller does not touch it.
 */
@RestController
public class ShopifyOAuthController {

    private static final String SHOP_DOMAIN_PATTERN = "[a-zA-Z0-9][a-zA-Z0-9-]*\\.myshopify\\.com";
    private static final int    TIMESTAMP_WINDOW_SECONDS = 300;

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
     * Path-2 install: Shopify redirects merchants here from the App Store.
     * Verifies HMAC + timestamp freshness, generates state with tenant_id=NULL,
     * redirects to Shopify consent URL.
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
        checkTimestampFreshness(allParams);

        String nonce = oauthService.initiateOAuth(null, shop);
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(oauthService.buildConsentUrl(shop, nonce)))
            .build();
    }

    /**
     * OAuth callback: HMAC → freshness → state → linkOrProvision → redirect.
     *
     * Param trust order:
     *   1. HMAC verified first (before any DB read).
     *   2. Timestamp freshness verified (300 s window, inside HMAC so tamper-proof).
     *   3. State consumed (atomic SELECT FOR UPDATE).
     *   4. linkOrProvision runs the decision tree and manages TenantContext internally.
     */
    @GetMapping("/auth/shopify/callback")
    public ResponseEntity<Void> callback(@RequestParam Map<String, String> allParams) {
        String shop  = allParams.getOrDefault("shop", "");
        String state = allParams.getOrDefault("state", "");
        String code  = allParams.getOrDefault("code", "");

        if (!ShopifyHmacUtil.verifyOAuthParams(allParams, oauthService.getClientSecret())) {
            throw new ShopifyOAuthException(
                ShopifyOAuthException.Code.SHOPIFY_HMAC_INVALID,
                "HMAC validation failed",
                "فشل التحقق من HMAC",
                HttpStatus.UNAUTHORIZED);
        }
        checkTimestampFreshness(allParams);

        ShopifyOAuthService.StateRecord stateRec = oauthService.consumeState(state, shop);

        ShopifyOAuthService.LinkResult result = oauthService.linkOrProvision(stateRec, shop, code);

        return switch (result.outcome()) {
            case LINKED_NEW, LINKED_EXISTING -> ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(oauthService.getAppUrl()))
                .header("X-Store-Id", result.tenantId() != null ? result.tenantId().toString() : "")
                .build();
            case PROVISIONED -> ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(oauthService.getAppUrl() + "/connect/setup-pending"))
                .build();
            case REJECTED_CROSS_TENANT -> ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(oauthService.getAppUrl() +
                    "/connect/error?code=SHOPIFY_STORE_ALREADY_CONNECTED"))
                .build();
        };
    }

    // ---- private helpers -----------------------------------------------

    /**
     * Rejects requests with a Shopify timestamp older than 300 seconds.
     * The timestamp is inside the HMAC (tamper-proof), so freshness is meaningful
     * only after HMAC verification passes. Call this method AFTER verifyOAuthParams.
     */
    private void checkTimestampFreshness(Map<String, String> params) {
        String ts = params.get("timestamp");
        if (ts == null) {
            throw new ShopifyOAuthException(
                ShopifyOAuthException.Code.SHOPIFY_REQUEST_EXPIRED,
                "Missing timestamp parameter",
                "معامل الطابع الزمني مفقود",
                HttpStatus.BAD_REQUEST);
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            throw new ShopifyOAuthException(
                ShopifyOAuthException.Code.SHOPIFY_REQUEST_EXPIRED,
                "Invalid timestamp parameter",
                "معامل الطابع الزمني غير صالح",
                HttpStatus.BAD_REQUEST);
        }
        long ageSeconds = System.currentTimeMillis() / 1000 - epochSeconds;
        if (ageSeconds > TIMESTAMP_WINDOW_SECONDS || ageSeconds < -TIMESTAMP_WINDOW_SECONDS) {
            throw new ShopifyOAuthException(
                ShopifyOAuthException.Code.SHOPIFY_REQUEST_EXPIRED,
                "Request has expired — initiate OAuth again",
                "انتهت صلاحية الطلب — يرجى بدء OAuth من جديد",
                HttpStatus.BAD_REQUEST);
        }
    }
}
