package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;

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
}
