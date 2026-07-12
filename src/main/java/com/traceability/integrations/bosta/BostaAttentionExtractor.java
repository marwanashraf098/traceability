package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Single extraction point for all attention-field values derived from a Bosta raw payload.
 * Called at every raw-write site (BostaWebhookJob step 9, createOrFindShipment,
 * createOrFindReturnShipment) so columns always stay in sync with raw.
 *
 * Derived from attempts[] array only — Bosta's flat numberOfAttempts,
 * deliveryAttemptsLength, pickupAttemptsLength are unreliable and not used here.
 */
public final class BostaAttentionExtractor {

    private BostaAttentionExtractor() {}

    public record AttemptEntry(
        String  attemptDate,
        String  type,
        boolean succeeded,
        String  courierName,
        String  courierPhone,
        String  failureReason
    ) {}

    public record AttentionFields(
        int             totalAttempts,
        int             failedDeliveryAttempts,
        Instant         lastAttemptAt,
        String          lastFailureReason,
        Boolean         isDelayed,
        Boolean         slaBreached,
        String          scheduledAt,
        String          courierName,
        String          courierPhone,
        List<AttemptEntry> attempts
    ) {
        public static final AttentionFields EMPTY =
            new AttentionFields(0, 0, null, null, null, null, null, null, null, List.of());
    }

    /** Extract from a parsed JsonNode (preferred — avoids re-parse). */
    public static AttentionFields extract(JsonNode raw) {
        if (raw == null || raw.isNull() || raw.isMissingNode()) return AttentionFields.EMPTY;

        JsonNode attArray = raw.path("attempts");
        int total = attArray.isArray() ? attArray.size() : 0;
        int failedDelivery = 0;
        Instant lastAttemptAt = null;
        String  lastFailureReason = null;
        Instant lastFailureAt = null;
        List<AttemptEntry> entries = new ArrayList<>();

        if (attArray.isArray()) {
            for (JsonNode a : attArray) {
                String type = a.path("type").asText(null);

                // succeededAt presence is the primary signal; state == 3 is secondary.
                String succeededAtStr = a.path("succeededAt").asText(null);
                boolean succeeded = (succeededAtStr != null && !succeededAtStr.isBlank())
                    || a.path("state").asInt(-1) == 3;

                // Attempt timestamp
                String  dateStr    = a.path("attemptDate").asText(null);
                Instant attemptTs  = parseInstantSilent(dateStr);
                if (attemptTs != null && (lastAttemptAt == null || attemptTs.isAfter(lastAttemptAt))) {
                    lastAttemptAt = attemptTs;
                }

                // Attempt-level courier (star within attempt)
                JsonNode aStar    = a.path("star");
                String  aCourierName  = nullIfBlank(aStar.path("name").asText(null));
                String  aCourierPhone = nullIfBlank(aStar.path("phone").asText(null));

                // Failure reason from nested exception object
                String reason = nullIfBlank(a.path("exception").path("reason").asText(null));

                if ("delivery".equals(type) && !succeeded) {
                    failedDelivery++;
                    if (reason != null) {
                        if (lastFailureAt == null
                                || (attemptTs != null && attemptTs.isAfter(lastFailureAt))) {
                            lastFailureAt     = attemptTs;
                            lastFailureReason = reason;
                        }
                    }
                }

                entries.add(new AttemptEntry(dateStr, type, succeeded,
                    aCourierName, aCourierPhone, reason));
            }
        }

        // SLA breach — NULL when no sla key is present.
        JsonNode sla = raw.path("sla");
        Boolean slaBreached = null;
        if (!sla.isMissingNode() && !sla.isNull()) {
            boolean orderSla = sla.path("orderSla").path("isExceededOrderSla").asBoolean(false);
            boolean e2eSla   = sla.path("e2eSla").path("isExceededE2ESla").asBoolean(false);
            slaBreached = orderSla || e2eSla;
        }

        // isDelayed — NULL when key absent.
        JsonNode isDelayedNode = raw.path("isDelayed");
        Boolean isDelayed = (!isDelayedNode.isMissingNode() && !isDelayedNode.isNull())
            ? isDelayedNode.asBoolean() : null;

        // scheduledAt — kept as ISO string; SQL casts on write.
        String scheduledAt = nullIfBlank(raw.path("scheduledAt").asText(null));

        // Top-level current courier (star at root level).
        String courierName  = nullIfBlank(raw.path("star").path("name").asText(null));
        String courierPhone = nullIfBlank(raw.path("star").path("phone").asText(null));

        return new AttentionFields(
            total, failedDelivery, lastAttemptAt, lastFailureReason,
            isDelayed, slaBreached, scheduledAt, courierName, courierPhone,
            List.copyOf(entries));
    }

    /** Convenience overload: parse JSON string then extract. Silently returns EMPTY on error. */
    public static AttentionFields extractFromString(String rawJson, ObjectMapper mapper) {
        if (rawJson == null || rawJson.isBlank()) return AttentionFields.EMPTY;
        try {
            return extract(mapper.readTree(rawJson));
        } catch (Exception e) {
            return AttentionFields.EMPTY;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Instant parseInstantSilent(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception ignored) { return null; }
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
