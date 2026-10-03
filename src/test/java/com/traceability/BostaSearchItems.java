package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * Items of Bosta's v2 delivery search (POST /api/v2/deliveries/search) in the shape the prod probe
 * saw (2026-10-03): trackingNumber, state {code}, type {code, value}, creationTimestamp (epoch ms),
 * createdAt, updatedAt, businessReference, uniqueBusinessReference, shopifyInfo.orderId.
 */
final class BostaSearchItems {

    private static final ObjectMapper M = new ObjectMapper();
    static final String UPDATED_AT = "2026-10-03T14:20:00.000Z";

    private BostaSearchItems() {}

    /** A complete SEND item — discovery ingests it without a fetch. createdAt may be null (no time). */
    static ObjectNode item(String tracking, int state, Instant createdAt) {
        return item(tracking, state, createdAt, UPDATED_AT, "REF-" + tracking);
    }

    static ObjectNode item(String tracking, int state, Instant createdAt, String updatedAt, String reference) {
        ObjectNode d = M.createObjectNode();
        d.put("trackingNumber", tracking);
        d.putObject("state").put("code", state);
        d.putObject("type").put("code", 10).put("value", "Send");
        if (createdAt != null) d.put("creationTimestamp", createdAt.toEpochMilli());
        if (updatedAt != null) d.put("updatedAt", updatedAt);
        if (reference != null) d.put("businessReference", reference);
        return d;
    }

    /** An item without updatedAt — not enough for ingest, so discovery falls back to one fetch. */
    static ObjectNode needsFetch(String tracking, int state, Instant createdAt) {
        return item(tracking, state, createdAt, null, "REF-" + tracking);
    }

    static java.util.List<JsonNode> page(JsonNode... items) {
        return java.util.List.of(items);
    }
}
