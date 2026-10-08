package com.traceability.integrations.shopify;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Shopify returned an offline token that does not expire (no refresh token / no expiry). Shopify
 * rejects such tokens for the Admin API ("Non-expiring access tokens are no longer accepted"), so
 * Traced never stores one: the response is refused before anything is written. 502 — the upstream
 * answer was unusable. See {@link ShopifyStoredToken#requireExpiring}.
 */
public class ShopifyNonExpiringTokenException extends ApiException {

    public ShopifyNonExpiringTokenException(String shopDomain, String what) {
        super("SHOPIFY_TOKEN_NOT_EXPIRING",
            "Shopify returned a token Traced can't use for " + shopDomain + " (" + what + "). Nothing was saved — try again.",
            "أعاد Shopify رمزًا لا يمكن لـ Traced استخدامه للمتجر " + shopDomain + ". لم يتم حفظ أي شيء — حاول مرة أخرى.",
            HttpStatus.BAD_GATEWAY);
    }
}
