package com.traceability.integrations.shopify;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown for OAuth flow failures. ApiExceptionHandler maps this to an HTTP
 * response with body {code, message_en, message_ar}. All error codes are defined
 * upfront from the spec so i18n keys are present from day one.
 *
 * SHOPIFY_STATE_INVALID intentionally covers expired/consumed/shop-mismatch cases —
 * the caller must not leak which specific sub-condition failed.
 *
 * Extends {@link ApiException} (extracted FR-DEMO Day 2) — messageEn/messageAr/httpStatus
 * are now inherited, not redeclared; code() keeps its own enum-typed accessor (no rename,
 * no behavior change) rather than colliding with the base's plain-String errorCode().
 */
public class ShopifyOAuthException extends ApiException {

    public enum Code {
        SHOPIFY_HMAC_INVALID,
        SHOPIFY_STATE_INVALID,
        SHOPIFY_TOKEN_EXCHANGE_FAILED,
        SHOPIFY_PATH2_NOT_YET,
        // Day 2 codes
        SHOPIFY_REQUEST_EXPIRED,        // stale timestamp on install/callback
        SHOPIFY_STORE_ALREADY_CONNECTED, // shop owned by a different tenant (redirect, not JSON)
        // Day 4 codes
        MAGIC_LINK_INVALID,              // not-found / expired / consumed — no oracle (all sub-conditions identical)
        // Disconnect/reconnect hard rule: a tenant is permanently bound to its original
        // shop_domain — initiate() rejects a different shop pre-consent, before any write.
        SHOPIFY_SHOP_MISMATCH,
        // "Find your store": the input isn't a .myshopify.com address or a Shopify admin link
        // (ShopDomainNormalizer), and — before initiate — Shopify clearly says no such store exists.
        NOT_SHOPIFY_ADDRESS,
        STORE_NOT_FOUND,
        // Build D (embedded onboarding / pending link): the shop is already linked to another tenant
        // (same wording as the callback's SHOP_LINKED_ELSEWHERE redirect), and a pending link that is
        // unknown / expired / used (one code for all three — no oracle).
        SHOP_LINKED_ELSEWHERE,
        PENDING_LINK_INVALID
    }

    private final Code code;

    public ShopifyOAuthException(Code code, String messageEn, String messageAr, HttpStatus httpStatus) {
        super(code.name(), messageEn, messageAr, httpStatus);
        this.code = code;
    }

    public Code code() { return code; }
}
