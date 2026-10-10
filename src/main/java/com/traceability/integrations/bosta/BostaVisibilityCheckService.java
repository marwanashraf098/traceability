package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.shopify.FulfillmentTrackingCapture;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only Bosta visibility check (2026-10-02). For one tenant: every order that Shopify
 * says was fulfilled with a Bosta tracking number but that has no forward shipment in Traced
 * — is that tracking number visible to THIS tenant's own Bosta API key?
 *
 * Candidates come from order_fulfillment_tracking (carrier_class 'bosta', not cancelled) and,
 * for orders not covered there, from orders.raw when it is a REST payload carrying
 * fulfillments[] (tracking_company or tracking URL containing "bosta", not cancelled).
 *
 * Writes nothing: no shipments, unlinked rows, orders, tracking rows, webhook_events. The
 * result is returned and logged, one {@code BOSTA_VISIBILITY {json}} line per order plus a
 * {@code BOSTA_VISIBILITY_SUMMARY} line. Since 2026-10-03 a FOUND row also carries
 * {@code details} (createdAt / updatedAt / creationSrc / sender / pickup location …, never customer
 * PII), and the run ends with page 1 of the delivery list exactly as discovery fetches it:
 * {@code BOSTA_VISIBILITY_LIST {json}} per item + {@code BOSTA_VISIBILITY_LIST_SUMMARY}. Never scheduled — run by the owner endpoint
 * (POST /api/v1/bosta/visibility-check) or the ops startup trigger
 * (bosta.visibility-check.on-startup). Only ever uses the tenant's own decrypted key.
 */
@Service
public class BostaVisibilityCheckService {

    private static final Logger log = LoggerFactory.getLogger(BostaVisibilityCheckService.class);

    public record Candidate(String orderNumber, String trackingNumber, String carrierRaw, String source) {}

    public record Row(String tenant, String orderNumber, String trackingNumber, String carrierRaw,
                      String source, String result, Integer typeCode, Integer state,
                      String businessReference, String shopifyOrderId, String error,
                      Map<String, Object> details) {}

    /**
     * Page 1 of the delivery list exactly as discovery fetches it (pageNumber=1, discovery's page
     * size): Bosta's reported total and, per item, the same fields as {@link Row#details()}.
     */
    public record ListSample(Integer reportedCount, List<Map<String, Object>> items, String error) {}

    public record Report(UUID tenantId, String tenant, Map<String, Integer> candidatesBySource, List<Row> rows,
                         ListSample listSample) {}

    private final JdbcTemplate        jdbc;
    private final TransactionTemplate tx;
    private final BostaGateway        bostaGateway;
    private final EncryptionService   encryptionService;
    private final ObjectMapper        mapper;
    private final long                delayMs;
    private final long                maxBackoffMs;
    private final int                 maxRateLimitRetries;
    private final int                 listPageSize;
    private BostaSearchProbe          searchProbe;   // optional (2026-10-03 v2 search probe)

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSearchProbe(BostaSearchProbe searchProbe) {
        this.searchProbe = searchProbe;
    }

    public BostaVisibilityCheckService(JdbcTemplate jdbc,
                                       PlatformTransactionManager txm,
                                       BostaGateway bostaGateway,
                                       EncryptionService encryptionService,
                                       ObjectMapper mapper,
                                       @Value("${bosta.visibility-check.delay-ms:3000}") long delayMs,
                                       @Value("${bosta.visibility-check.max-backoff-ms:60000}") long maxBackoffMs,
                                       @Value("${bosta.visibility-check.rate-limit-retries:3}") int maxRateLimitRetries,
                                       @Value("${bosta.backfill.page-size:50}") int listPageSize) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.tx.setReadOnly(true);
        this.bostaGateway = bostaGateway;
        this.encryptionService = encryptionService;
        this.mapper = mapper;
        this.delayMs = delayMs;
        this.maxBackoffMs = maxBackoffMs;
        this.maxRateLimitRetries = maxRateLimitRetries;
        this.listPageSize = listPageSize;
    }

    /** JobRunr entry point — logs the report. */
    @Job(name = "Bosta visibility check — tenant %0", retries = 0)
    public void runAndLog(UUID tenantId) {
        Report report = check(tenantId);
        for (Row row : report.rows()) {
            log.info("BOSTA_VISIBILITY {}", json(row));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tenantId", tenantId.toString());
        summary.put("tenant", report.tenant());
        summary.put("candidatesBySource", report.candidatesBySource());
        Map<String, Integer> byResult = new LinkedHashMap<>();
        for (Row r : report.rows()) byResult.merge(r.result(), 1, Integer::sum);
        summary.put("results", byResult);
        ListSample sample = report.listSample();
        if (sample != null) {
            for (Map<String, Object> item : sample.items()) log.info("BOSTA_VISIBILITY_LIST {}", json(item));
            Map<String, Object> listSummary = new LinkedHashMap<>();
            listSummary.put("tenantId", tenantId.toString());
            listSummary.put("tenant", report.tenant());
            listSummary.put("pageNumber", 1);
            listSummary.put("pageSize", listPageSize);
            listSummary.put("itemsReturned", sample.items().size());
            listSummary.put("reportedCount", sample.reportedCount());
            listSummary.put("error", sample.error());
            log.info("BOSTA_VISIBILITY_LIST_SUMMARY {}", json(listSummary));
        }
        log.info("BOSTA_VISIBILITY_SUMMARY {}", json(summary));

        // Read-only v2 search probe (BOSTA_SEARCH_PROBE lines): does the dashboard's paged search work
        // with this tenant's own API key?
        if (searchProbe != null) {
            try {
                String apiKey = TenantContext.runAs(tenantId, () -> {
                    String enc = tx.execute(s -> jdbc.query(
                        "SELECT api_key_encrypted FROM courier_accounts " +
                        "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
                        rs -> rs.next() ? rs.getString(1) : null, tenantId));
                    return enc == null ? null : encryptionService.decrypt(enc);
                });
                if (apiKey != null) searchProbe.probe(tenantId, report.tenant(), apiKey);
            } catch (RuntimeException e) {
                log.warn("BOSTA_SEARCH_PROBE tenant {} failed: {}", tenantId, e.toString());
            }
        }
    }

    public Report check(UUID tenantId) {
        return TenantContext.runAs(tenantId, () -> {
            String tenantName = tx.execute(s -> jdbc.queryForObject(
                "SELECT name FROM tenants WHERE id = ?", String.class, tenantId));
            String encryptedKey = tx.execute(s -> jdbc.query(
                "SELECT api_key_encrypted FROM courier_accounts " +
                "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, tenantId));

            List<Candidate> candidates = candidates(tenantId);
            Map<String, Integer> bySource = new LinkedHashMap<>();
            bySource.put("order_fulfillment_tracking", 0);
            bySource.put("orders_raw", 0);
            for (Candidate c : candidates) bySource.merge(c.source(), 1, Integer::sum);

            List<Row> rows = new ArrayList<>();
            if (encryptedKey == null) {
                for (Candidate c : candidates) {
                    rows.add(row(tenantName, c, "ERROR", null, "no active Bosta account for this tenant"));
                }
                return new Report(tenantId, tenantName, bySource, rows, null);
            }
            String apiKey = encryptionService.decrypt(encryptedKey);

            boolean first = true;
            for (Candidate c : candidates) {
                if (!first) sleep(delayMs);
                first = false;
                rows.add(fetch(tenantName, apiKey, c));
            }
            if (!candidates.isEmpty()) sleep(delayMs);
            return new Report(tenantId, tenantName, bySource, rows, listSample(apiKey));
        });
    }

    private Row fetch(String tenantName, String apiKey, Candidate c) {
        for (int attempt = 0; ; attempt++) {
            try {
                BostaDelivery d = bostaGateway.fetchDelivery(apiKey, c.trackingNumber());
                if (d == null) return row(tenantName, c, "NOT_FOUND", null, null);
                return new Row(tenantName, c.orderNumber(), c.trackingNumber(), c.carrierRaw(), c.source(),
                    "FOUND", d.typeCode(), d.stateCode(), d.businessReference(), d.shopifyOrderId(), null,
                    details(d.raw()));
            } catch (DeliveryNotFoundException e) {
                return row(tenantName, c, "NOT_FOUND", null, null);
            } catch (BostaRateLimitException e) {
                if (attempt >= maxRateLimitRetries) {
                    return row(tenantName, c, "ERROR", null, "rate limited (gave up after "
                        + (attempt + 1) + " tries)");
                }
                long backoff = Math.min(e.getRetryAfterSeconds() * 1000L, maxBackoffMs);
                log.warn("Bosta visibility check: rate limited on {} — backing off {} ms", c.trackingNumber(), backoff);
                sleep(backoff);
            } catch (RuntimeException e) {
                return row(tenantName, c, "ERROR", null, e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            }
        }
    }

    /** Orders with a Bosta tracking number from Shopify and no forward shipment. */
    List<Candidate> candidates(UUID tenantId) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        List<Map<String, Object>> tracked = tx.execute(s -> jdbc.queryForList(
            "SELECT o.number, t.tracking_number, t.carrier_raw " +
            "FROM order_fulfillment_tracking t JOIN merchant_orders o ON o.id = t.order_id AND o.tenant_id = t.tenant_id " +
            "WHERE t.tenant_id = ? AND t.carrier_class = 'bosta' " +
            "  AND coalesce(t.fulfillment_status, '') <> 'cancelled' " +
            "  AND NOT EXISTS (SELECT 1 FROM shipments s WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id " +
            "                    AND s.shipment_leg = 'forward') " +
            "ORDER BY o.placed_at, t.tracking_number", tenantId));
        Set<String> coveredOrders = new LinkedHashSet<>();
        for (Map<String, Object> r : tracked) {
            String number = (String) r.get("number");
            String tn = (String) r.get("tracking_number");
            coveredOrders.add(number);
            if (seen.add(number + "|" + tn)) {
                out.add(new Candidate(number, tn, (String) r.get("carrier_raw"), "order_fulfillment_tracking"));
            }
        }

        List<Map<String, Object>> raw = tx.execute(s -> jdbc.queryForList(
            "SELECT o.number, f->>'tracking_company' AS company, f->>'tracking_number' AS tn, " +
            "       f->'tracking_numbers' AS tns " +
            "FROM merchant_orders o, jsonb_array_elements(o.raw->'fulfillments') f " +
            "WHERE o.tenant_id = ? AND jsonb_typeof(o.raw->'fulfillments') = 'array' " +
            "  AND (f->>'tracking_company' ILIKE '%bosta%' OR f->>'tracking_url' ILIKE '%bosta%') " +
            "  AND coalesce(f->>'status', '') <> 'cancelled' " +
            "  AND NOT EXISTS (SELECT 1 FROM shipments s WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id " +
            "                    AND s.shipment_leg = 'forward') " +
            "ORDER BY o.placed_at", tenantId));
        for (Map<String, Object> r : raw) {
            String number = (String) r.get("number");
            if (coveredOrders.contains(number)) continue;
            Set<String> tns = new LinkedHashSet<>();
            Object arr = r.get("tns");
            if (arr != null) {
                try {
                    for (var n : mapper.readTree(arr.toString())) addBare(tns, n.asText(null));
                } catch (Exception ignored) {
                    // malformed array — fall back to tracking_number below
                }
            }
            addBare(tns, (String) r.get("tn"));
            for (String tn : tns) {
                if (seen.add(number + "|" + tn)) {
                    out.add(new Candidate(number, tn, (String) r.get("company"), "orders_raw"));
                }
            }
        }
        return out;
    }

    private static void addBare(Set<String> out, String raw) {
        String b = FulfillmentTrackingCapture.bareTrackingNumber(raw);
        if (b != null) out.add(b);
    }

    private static Row row(String tenant, Candidate c, String result, Integer type, String error) {
        return new Row(tenant, c.orderNumber(), c.trackingNumber(), c.carrierRaw(), c.source(),
            result, type, null, null, null, error, null);
    }

    /** Read-only: page 1 of the list, the call discovery makes. Never throws. */
    private ListSample listSample(String apiKey) {
        try {
            com.fasterxml.jackson.databind.JsonNode body = bostaGateway.listDeliveriesPageRaw(apiKey, 1, listPageSize);
            if (body == null) return new ListSample(null, List.of(), null);
            com.fasterxml.jackson.databind.JsonNode items = body.path("deliveries");
            if (!items.isArray()) {
                items = body.path("data");
                if (items.isObject()) items = items.path("data");
            }
            Integer count = body.has("count") ? body.path("count").asInt()
                : body.path("data").has("count") ? body.path("data").path("count").asInt() : null;
            List<Map<String, Object>> out = new ArrayList<>();
            if (items.isArray()) {
                int pos = 0;
                for (com.fasterxml.jackson.databind.JsonNode item : items) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("position", ++pos);
                    m.put("trackingNumber", item.path("trackingNumber").asText(null));
                    m.put("typeCode", item.path("type").isObject() ? item.path("type").path("code").asInt() : null);
                    m.put("state", item.path("state").isObject() ? item.path("state").path("code").asInt()
                        : item.path("state").isNumber() ? item.path("state").asInt() : null);
                    m.put("businessReference", item.path("businessReference").asText(null));
                    m.putAll(details(item));
                    out.add(m);
                }
            }
            return new ListSample(count, out, null);
        } catch (RuntimeException e) {
            return new ListSample(null, List.of(), e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    /**
     * When / how / from where Bosta created the delivery — never customer PII (no receiver, no
     * drop-off address). Absent fields are omitted.
     */
    static Map<String, Object> details(com.fasterxml.jackson.databind.JsonNode raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (raw == null) return m;
        put(m, "createdAt", raw.path("createdAt"));
        put(m, "updatedAt", raw.path("updatedAt"));
        put(m, "creationTimestamp", raw.path("creationTimestamp"));
        put(m, "creationSrc", raw.path("creationSrc"));
        put(m, "senderId", raw.path("sender").path("_id"));
        put(m, "senderName", raw.path("sender").path("name"));
        put(m, "businessId", raw.path("business").path("_id"));
        put(m, "businessLocationId", raw.path("businessLocationId"));
        put(m, "pickupAddressId", raw.path("pickupAddress").path("_id"));
        put(m, "pickupCity", raw.path("pickupAddress").path("city").path("name"));
        put(m, "pickupZone", raw.path("pickupAddress").path("zone").path("name"));
        put(m, "pickupDistrict", raw.path("pickupAddress").path("district").path("name"));
        put(m, "pickupLocationName", raw.path("pickupAddress").path("locationName"));
        put(m, "pickupRequestId", raw.path("pickupRequestId"));
        put(m, "pickupRequestType", raw.path("pickupRequestType"));
        put(m, "isExternalFulfillmentOrder", raw.path("isExternalFulfillmentOrder"));
        put(m, "assignedHub", raw.path("assignedHub").path("name"));
        if (raw.has("flexShippingInfo")) m.put("hasFlexShippingInfo", true);
        if (raw.has("shopifyInfo") || raw.has("shopifyOrderId")) m.put("hasShopifyInfo", true);
        return m;
    }

    private static void put(Map<String, Object> m, String key, com.fasterxml.jackson.databind.JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) return;
        m.put(key, v.isNumber() ? v.numberValue() : v.isBoolean() ? v.booleanValue() : v.asText());
    }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (Exception e) { return String.valueOf(o); }
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
