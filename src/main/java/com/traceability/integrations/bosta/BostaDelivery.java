package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * Authoritative delivery state fetched from the Bosta API.
 *
 * Field naming matches Bosta API (§8 payload shape):
 *   trackingNumber — camelCase, always treated as String regardless of JSON type
 *   stateCode      — numeric integer (e.g. 45 = Delivered, 41 = type-dependent)
 *   type           — type.value.toUpperCase() from Bosta: "SEND", "RTO",
 *                    "CUSTOMER RETURN PICKUP", "EXCHANGE", "FXF_SEND", etc.
 *                    Used as the state-mapper key (e.g. "41:SEND", "41:CUSTOMER RETURN PICKUP").
 *                    NOT a canonical code string — use typeCode() for CRP detection.
 *   numberOfAttempts — delivery attempt count
 *   businessReference — merchant's reference (order number / external id)
 *   shopifyOrderId — Shopify numeric order ID from raw.shopifyOrderId (plugin-created
 *                    deliveries only); used as fallback match via external_id GID format
 *   raw            — full Bosta response for audit / future use
 *
 * typeCode() — derived from raw.type.code. Use this (not type()) for CRP detection:
 *   type.code=25 → CRP (Customer Return Pickup). The normalized string value is
 *   "CUSTOMER RETURN PICKUP" — never "CRP". String equality on type() is silently broken.
 */
public record BostaDelivery(
        String   trackingNumber,
        int      stateCode,
        String   type,
        int      numberOfAttempts,
        String   businessReference,
        String   shopifyOrderId,
        JsonNode raw) {

    /** Returns raw.type.code, or -1 if raw is null or type is not a code-bearing object. */
    public int typeCode() {
        if (raw == null) return -1;
        return raw.path("type").path("code").asInt(-1);
    }

    /**
     * Reconstructs a BostaDelivery from an already-fetched raw payload — the SAME field
     * extraction {@link BostaHttpGateway#fetchDelivery} uses (state.code, type.value
     * uppercased, numberOfAttempts, businessReference, shopifyInfo.orderId with the
     * legacy shopifyOrderId fallback), kept here so a caller re-deriving state from
     * STORED raw (no fresh API call) reads the payload identically to a live fetch.
     * Deliberately NOT wired into fetchDelivery() itself in this pass — that method is
     * untouched, live production code; this factory only has one caller so far
     * (BostaWebhookJob's admin re-interpret action).
     */
    public static BostaDelivery fromRaw(String trackingNumber, JsonNode data) {
        String tn = data.path("trackingNumber").asText(trackingNumber);

        JsonNode stateNode = data.path("state");
        int code = stateNode.isObject() ? stateNode.path("code").asInt(-1) : stateNode.asInt(-1);

        JsonNode typeNode = data.path("type");
        String type = typeNode.isObject()
            ? typeNode.path("value").asText("SEND").toUpperCase(Locale.ROOT)
            : typeNode.asText("SEND").toUpperCase(Locale.ROOT);

        int attempts = data.path("numberOfAttempts").asInt(0);
        String ref   = data.path("businessReference").asText(null);

        String shopifyOrderId = data.path("shopifyInfo").path("orderId").asText(null);
        if (shopifyOrderId == null || shopifyOrderId.isBlank()) {
            shopifyOrderId = data.path("shopifyOrderId").asText(null);
        }
        if (shopifyOrderId != null && shopifyOrderId.isBlank()) shopifyOrderId = null;

        return new BostaDelivery(tn, code, type, attempts, ref, shopifyOrderId, data);
    }
}
