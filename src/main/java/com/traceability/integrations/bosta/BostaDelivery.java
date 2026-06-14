package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Authoritative delivery state fetched from the Bosta API.
 *
 * Field naming matches Bosta API (§8 payload shape):
 *   trackingNumber — camelCase, always treated as String regardless of JSON type
 *   stateCode      — numeric integer (e.g. 45 = Delivered, 41 = type-dependent)
 *   type           — "SEND" (forward) or "RTO" (return); used to disambiguate code 41
 *   numberOfAttempts — delivery attempt count
 *   businessReference — merchant's reference (order number / external id)
 *   raw            — full Bosta response for audit / future use
 */
public record BostaDelivery(
        String   trackingNumber,
        int      stateCode,
        String   type,
        int      numberOfAttempts,
        String   businessReference,
        JsonNode raw) {
}
