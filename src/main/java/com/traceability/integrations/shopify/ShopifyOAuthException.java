package com.traceability.integrations.shopify;

import org.springframework.http.HttpStatus;

/**
 * Thrown for OAuth flow failures. ApiExceptionHandler maps this to an HTTP
 * response with body {code, message_en, message_ar}. All error codes are defined
 * upfront from the spec so i18n keys are present from day one.
 *
 * SHOPIFY_STATE_INVALID intentionally covers expired/consumed/shop-mismatch cases —
 * the caller must not leak which specific sub-condition failed.
 */
public class ShopifyOAuthException extends RuntimeException {

    public enum Code {
        SHOPIFY_HMAC_INVALID,
        SHOPIFY_STATE_INVALID,
        SHOPIFY_TOKEN_EXCHANGE_FAILED,
        SHOPIFY_PATH2_NOT_YET,
        // Day 2 codes
        SHOPIFY_REQUEST_EXPIRED,        // stale timestamp on install/callback
        SHOPIFY_STORE_ALREADY_CONNECTED, // shop owned by a different tenant (redirect, not JSON)
        SHOPIFY_SHOP_EMAIL_MISSING,      // Shopify shop resource returned no owner email
        // Day 4 codes
        MAGIC_LINK_INVALID               // not-found / expired / consumed — no oracle (all sub-conditions identical)
    }

    private final Code       code;
    private final String     messageEn;
    private final String     messageAr;
    private final HttpStatus httpStatus;

    public ShopifyOAuthException(Code code, String messageEn, String messageAr, HttpStatus httpStatus) {
        super(code.name() + ": " + messageEn);
        this.code       = code;
        this.messageEn  = messageEn;
        this.messageAr  = messageAr;
        this.httpStatus = httpStatus;
    }

    public Code       code()       { return code; }
    public String     messageEn()  { return messageEn; }
    public String     messageAr()  { return messageAr; }
    public HttpStatus httpStatus() { return httpStatus; }
}
