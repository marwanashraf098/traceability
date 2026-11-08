package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Shopify-native returns S5a — the classified outcome of ONE attempt of a returns write mutation
 * ({@link ShopifyGateway#executeReturnsWriteOnce}). Classified exactly like the transfer-out push (approved
 * 2026-10-09), plus ACCESS_DENIED:
 * <ul>
 *   <li>OK — Shopify answered with the mutation's payload and no userErrors.</li>
 *   <li>DEFINITE — Shopify did NOT apply it: HTTP 4xx, userErrors, or top-level errors that are ONLY THROTTLED with no
 *       data. Safe to send again (within the caller's attempt limit).</li>
 *   <li>ACCESS_DENIED — the store hasn't approved write_returns (top-level ACCESS_DENIED, or HTTP 403). Not applied.</li>
 *   <li>AMBIGUOUS — everything else (timeout, connection error, 5xx, any other top-level error, no / unreadable body,
 *       no payload). NEVER sent again automatically.</li>
 * </ul>
 * {@code reason} is short and PII-free (an HTTP status, error / userError codes) — never a message or a body.
 */
public record ReturnsWriteResponse(Kind kind, JsonNode payload, JsonNode response, String reason, List<String> userErrorCodes) {

    public enum Kind { OK, DEFINITE, ACCESS_DENIED, AMBIGUOUS }

    public boolean ok()        { return kind == Kind.OK; }
    public boolean definite()  { return kind == Kind.DEFINITE; }
    public boolean ambiguous() { return kind == Kind.AMBIGUOUS; }
    public boolean denied()    { return kind == Kind.ACCESS_DENIED; }

    static ReturnsWriteResponse of(Kind kind, JsonNode payload, JsonNode response, String reason) {
        return new ReturnsWriteResponse(kind, payload, response, reason, List.of());
    }
}
