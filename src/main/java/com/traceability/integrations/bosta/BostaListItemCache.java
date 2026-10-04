package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The v2 search item behind a webhook event Traced wrote itself (discovery / status poll), kept in
 * memory for {@code bosta.list-item.max-age-seconds} (600) so BostaWebhookJob can build the delivery
 * from it instead of fetching v0 again (2026-10-04). Never persisted: the item carries the customer's
 * address and phone, and webhook_events would be a new place for them. A miss (restart, a late retry,
 * an older event) just means the job fetches as before.
 *
 * Items are stored with a {@link #SHAPE_FIELD} marker; the marker travels into shipments.raw so readers
 * that need Bosta's full v0 shape know to fetch it (ShipmentRawRefresher).
 */
@Component
public class BostaListItemCache {

    public static final String SHAPE_FIELD = "_tracedRawShape";
    public static final String SHAPE_V2 = "v2-list";

    private record Entry(JsonNode item, long atMillis) {}

    private final Map<Long, Entry> items = new ConcurrentHashMap<>();
    private final long maxAgeMillis;

    public BostaListItemCache(@Value("${bosta.list-item.max-age-seconds:600}") long maxAgeSeconds) {
        this.maxAgeMillis = maxAgeSeconds * 1000L;
    }

    public void put(long eventId, JsonNode item) {
        long now = System.currentTimeMillis();
        items.values().removeIf(e -> now - e.atMillis() > maxAgeMillis);
        items.put(eventId, new Entry(item.deepCopy(), now));
    }

    /** The item for this event if still fresh — removed either way. */
    public JsonNode take(long eventId) {
        Entry e = items.remove(eventId);
        if (e == null || System.currentTimeMillis() - e.atMillis() > maxAgeMillis) return null;
        return e.item();
    }

    public static boolean isV2(JsonNode raw) {
        return raw != null && SHAPE_V2.equals(raw.path(SHAPE_FIELD).asText(null));
    }

    public static ObjectNode mark(ObjectNode raw) {
        raw.put(SHAPE_FIELD, SHAPE_V2);
        return raw;
    }
}
