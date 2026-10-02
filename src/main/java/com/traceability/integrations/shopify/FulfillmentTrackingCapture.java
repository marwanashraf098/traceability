package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.traceability.inventory.TrackingNumberNormalizer;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Captures the tracking numbers in a Shopify orders/updated REST payload's fulfillments[]
 * into order_fulfillment_tracking (V129). Store only — no Bosta call, no linking, no status
 * change; nothing reads the table yet.
 *
 * Called from ShopifyWebhookProcessorJob.handleOrderUpdated after the order upsert — the
 * GraphQL import / reconcile path never calls it. Idempotent: one row per
 * (tenant, order, tracking number), upserted; a fulfillment Shopify cancels updates its row's
 * fulfillment_status to 'cancelled' and the row is never deleted.
 */
@Component
public class FulfillmentTrackingCapture {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentTrackingCapture.class);

    /** Non-Bosta carriers named explicitly (case-insensitive). "Other" is NOT here — Jumi's Bosta says "Other". */
    static final Set<String> OTHER_KNOWN_CARRIERS = Set.of("wijha");

    private final JdbcTemplate jdbc;

    public FulfillmentTrackingCapture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the number of (order, tracking number) rows upserted. */
    @Transactional
    public int capture(UUID storeId, String orderExternalId, JsonNode payload) {
        UUID tenantId = TenantContext.require();
        JsonNode fulfillments = payload.path("fulfillments");
        if (!fulfillments.isArray() || fulfillments.isEmpty()) return 0;

        List<UUID> ids = jdbc.queryForList(
            "SELECT id FROM orders WHERE tenant_id = ? AND store_id = ? AND external_id = ?",
            UUID.class, tenantId, storeId, orderExternalId);
        if (ids.isEmpty()) return 0;   // order not ingested (e.g. before the FR-18 cutoff)
        UUID orderId = ids.get(0);

        // tracking number → the fulfillment that carries it; a live fulfillment wins over a
        // cancelled one carrying the same number.
        Map<String, JsonNode> byTracking = new LinkedHashMap<>();
        for (JsonNode f : fulfillments) {
            for (String tn : trackingNumbers(f)) {
                JsonNode prev = byTracking.get(tn);
                if (prev == null || (isCancelled(prev) && !isCancelled(f))) byTracking.put(tn, f);
            }
        }

        int rows = 0;
        for (Map.Entry<String, JsonNode> e : byTracking.entrySet()) {
            JsonNode f = e.getValue();
            String company = text(f, "tracking_company");
            String url     = firstUrl(f);
            rows += jdbc.update(
                "INSERT INTO order_fulfillment_tracking " +
                "    (tenant_id, order_id, tracking_number, carrier_raw, tracking_url, carrier_class, " +
                "     shopify_fulfillment_id, fulfillment_status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (tenant_id, order_id, tracking_number) DO UPDATE SET " +
                "    carrier_raw            = EXCLUDED.carrier_raw, " +
                "    tracking_url           = EXCLUDED.tracking_url, " +
                "    carrier_class          = EXCLUDED.carrier_class, " +
                "    shopify_fulfillment_id = EXCLUDED.shopify_fulfillment_id, " +
                "    fulfillment_status     = EXCLUDED.fulfillment_status, " +
                "    last_seen_at           = now()",
                tenantId, orderId, e.getKey(), company, url, carrierClass(company, url),
                text(f, "id"), text(f, "status"));
        }
        log.debug("Fulfillment tracking: order {} — {} tracking row(s) upserted", orderId, rows);
        return rows;
    }

    static String carrierClass(String company, String url) {
        String c = company == null ? "" : company.trim().toLowerCase(Locale.ROOT);
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (c.contains("bosta") || u.contains("bosta")) return "bosta";
        if (OTHER_KNOWN_CARRIERS.contains(c)) return "other_known";
        return "unknown";
    }

    /**
     * Tracking numbers from tracking_numbers[] and tracking_number, de-duplicated. Bosta numbers
     * are normalized (TrackingNumberNormalizer); every other carrier's are kept as sent, with
     * whitespace removed only ("WJ-12345" stays "WJ-12345").
     */
    static Set<String> trackingNumbers(JsonNode f) {
        boolean bosta = "bosta".equals(carrierClass(text(f, "tracking_company"), firstUrl(f)));
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode n : f.path("tracking_numbers")) add(out, n.asText(null), bosta);
        add(out, text(f, "tracking_number"), bosta);
        return out;
    }

    /**
     * A Bosta tracking number in its bare form: TrackingNumberNormalizer (hub prefix stripped,
     * digits only); a value it can't reduce to digits keeps its non-space characters.
     */
    public static String bareTrackingNumber(String raw) {
        if (raw == null) return null;
        String n = TrackingNumberNormalizer.normalize(raw.replaceAll("\\s+", ""));
        if (n != null) return n;
        return withoutWhitespace(raw);
    }

    private static String withoutWhitespace(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("\\s+", "");
        return s.isEmpty() ? null : s;
    }

    private static void add(Set<String> out, String raw, boolean bosta) {
        String b = bosta ? bareTrackingNumber(raw) : withoutWhitespace(raw);
        if (b != null) out.add(b);
    }

    private static boolean isCancelled(JsonNode f) {
        return "cancelled".equalsIgnoreCase(text(f, "status"));
    }

    private static String firstUrl(JsonNode f) {
        String u = text(f, "tracking_url");
        if (u != null) return u;
        for (JsonNode n : f.path("tracking_urls")) {
            String v = n.asText(null);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText();
        return s.isBlank() ? null : s;
    }
}
