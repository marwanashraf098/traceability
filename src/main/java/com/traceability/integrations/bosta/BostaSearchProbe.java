package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Read-only probe of Bosta's v2 delivery search (2026-10-03): does
 * POST /api/v2/deliveries/search page and sort reliably with OUR API key (the dashboard uses a
 * session token)? Run by the visibility check's startup trigger, per tenant:
 *   (a) sortBy "-createdAt", limit 50, pages 1 and 2
 *   (b) sortBy "-updatedAt", limit 50, pages 1 and 2
 *   (c) only if (a) failed: "createdAt:-1" and "-creationTimestamp", pages 1 and 2
 * One {@code BOSTA_SEARCH_PROBE {json}} log line per call: HTTP status, items, first / last tracking
 * number, first / last createdAt and creationTimestamp, whether the page is newest-created first,
 * overlap with page 1 (on page 2), response keys. Writes nothing. Every call goes through the
 * shared rate limiter (BostaV2Client).
 */
@Component
public class BostaSearchProbe {

    private static final Logger log = LoggerFactory.getLogger(BostaSearchProbe.class);
    static final int LIMIT = 50;

    private final BostaV2Client client;
    private final ObjectMapper mapper;

    public BostaSearchProbe(BostaV2Client client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /** @return one result map per call, in order (also logged). */
    public List<Map<String, Object>> probe(UUID tenantId, String tenantName, String apiKey) {
        List<Map<String, Object>> out = new ArrayList<>();
        boolean createdOk = runVariant(tenantId, tenantName, apiKey, "-createdAt", out);
        runVariant(tenantId, tenantName, apiKey, "-updatedAt", out);
        if (!createdOk) {
            runVariant(tenantId, tenantName, apiKey, "createdAt:-1", out);
            runVariant(tenantId, tenantName, apiKey, "-creationTimestamp", out);
        }
        return out;
    }

    /** Pages 1 and 2 of one sort; true when both answered 200 with items, page 1 sorted newest-created first. */
    private boolean runVariant(UUID tenantId, String tenantName, String apiKey, String sortBy,
                               List<Map<String, Object>> out) {
        Set<String> page1 = new LinkedHashSet<>();
        boolean ok = true;
        for (int page = 1; page <= 2; page++) {
            BostaV2Client.SearchResponse r = client.searchDeliveries(apiKey, page, LIMIT, sortBy);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tenantId", tenantId.toString());
            m.put("tenant", tenantName);
            m.put("sortBy", sortBy);
            m.put("page", page);
            m.put("limit", LIMIT);
            m.put("status", r.status());
            if (r.error() != null) m.put("error", r.error());
            JsonNode body = r.body();
            m.put("responseKeys", keys(body));
            JsonNode data = body == null ? null : body.path("data");
            m.put("dataKeys", keys(data));
            JsonNode items = data == null ? null : data.path("deliveries");
            if (items == null || !items.isArray()) items = body == null ? null : body.path("deliveries");
            int n = items != null && items.isArray() ? items.size() : 0;
            m.put("items", n);
            if (data != null) {
                if (data.has("count")) m.put("count", data.path("count").asText());
                if (data.has("page")) m.put("echoPage", data.path("page").asText());
                if (data.has("limit")) m.put("echoLimit", data.path("limit").asText());
            }
            if (n > 0) {
                JsonNode first = items.get(0), last = items.get(n - 1);
                m.put("firstTracking", first.path("trackingNumber").asText(null));
                m.put("lastTracking", last.path("trackingNumber").asText(null));
                m.put("firstCreatedAt", first.path("createdAt").asText(null));
                m.put("lastCreatedAt", last.path("createdAt").asText(null));
                m.put("firstCreationTimestamp", first.path("creationTimestamp").asText(null));
                m.put("lastCreationTimestamp", last.path("creationTimestamp").asText(null));
                m.put("firstUpdatedAt", first.path("updatedAt").asText(null));
                m.put("lastUpdatedAt", last.path("updatedAt").asText(null));
                m.put("newestCreatedFirst", newestCreatedFirst(items));
                m.put("itemKeys", keys(first));
                Set<String> tns = new LinkedHashSet<>();
                for (JsonNode it : items) tns.add(it.path("trackingNumber").asText());
                if (page == 1) {
                    page1.addAll(tns);
                } else {
                    Set<String> overlap = new LinkedHashSet<>(tns);
                    overlap.retainAll(page1);
                    m.put("overlapWithPage1", overlap.size());
                }
            }
            if (r.status() != 200 || n == 0 || (page == 1 && "-createdAt".equals(sortBy) && !Boolean.TRUE.equals(m.get("newestCreatedFirst")))) {
                ok = false;
            }
            log.info("BOSTA_SEARCH_PROBE {}", json(m));
            out.add(m);
        }
        return ok;
    }

    /** True when every item's creation time is ≤ the previous one's (unknown times are skipped). */
    static boolean newestCreatedFirst(JsonNode items) {
        Instant prev = null;
        for (JsonNode it : items) {
            Instant t = BostaHttpGateway.createdAt(it);
            if (t == null) continue;
            if (prev != null && t.isAfter(prev)) return false;
            prev = t;
        }
        return true;
    }

    private static List<String> keys(JsonNode n) {
        List<String> ks = new ArrayList<>();
        if (n != null && n.isObject()) n.fieldNames().forEachRemaining(ks::add);
        return ks;
    }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }
}
