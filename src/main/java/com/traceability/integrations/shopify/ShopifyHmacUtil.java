package com.traceability.integrations.shopify;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shared HMAC verification for the Shopify OAuth flow.
 *
 * OAuth param HMAC (install + callback query params):
 *   - Drop the "hmac" key
 *   - Sort remaining keys alphabetically
 *   - Join as "key=value&key=value" using URL-decoded values
 *   - HMAC-SHA256 with the app client secret
 *   - Hex-encode the digest
 *   - Constant-time compare with the provided "hmac" parameter
 *
 * This is DIFFERENT from the webhook HMAC (raw request body, base64-encoded),
 * which is verified in ShopifyWebhookController using a separate code path.
 *
 * Note: Spring's @RequestParam Map<String,String> provides URL-decoded values.
 * Shopify percent-encodes '&', '%', and '=' in param values, but none of the
 * OAuth params (shop domain, base64url nonce, integer timestamp, auth code)
 * contain these characters, so decoded values produce the correct canonical string.
 */
public final class ShopifyHmacUtil {

    private ShopifyHmacUtil() {}

    /**
     * Verifies the "hmac" query parameter on a Shopify OAuth request.
     * Uses constant-time comparison to prevent timing attacks.
     *
     * @param params       all query parameters (including "hmac")
     * @param clientSecret the Shopify app's client secret
     * @return true if the HMAC is valid
     */
    public static boolean verifyOAuthParams(Map<String, String> params, String clientSecret) {
        String provided = params.get("hmac");
        if (provided == null || provided.isBlank()) return false;

        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.remove("hmac");

        StringBuilder canonical = new StringBuilder();
        sorted.forEach((k, v) -> {
            if (!canonical.isEmpty()) canonical.append('&');
            canonical.append(k).append('=').append(v);
        });

        String computed = hmacHex(canonical.toString(), clientSecret);
        return MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }
}
