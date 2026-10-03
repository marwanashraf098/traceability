package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.shopify.FulfillmentTrackingCapture;
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
 * One-off catch-up for fulfillment-driven linking (2026-10-03): every order with a non-cancelled
 * Bosta fulfillment (an order_fulfillment_tracking 'bosta' row, or a REST-shaped fulfillments[]
 * entry in orders.raw) and no forward shipment with that tracking number goes through
 * {@link BostaFulfillmentLinkService#attempt} — the exact Part 2 decision.
 *
 * Dry run (the default) writes nothing: verdict WOULD_LINK or SKIP + reason (or ERROR + message when a
 * row throws — the run continues and the summary is always logged), fetched with the
 * tenant's own key. Apply first stores a raw-only fulfillment as a tracking row (upsert only, no
 * job), then runs the same attempt: LINKED or SKIP + reason. Rerunning apply is idempotent — a
 * linked order is no longer a candidate, a repeated attempt finds the shipment.
 *
 * Never scheduled: owner POST /api/v1/bosta/fulfillment-link/catch-up?apply=…, or the ops startup
 * trigger (bosta.fulfillment-link.catch-up.on-startup + .apply). Output: one
 * {@code BOSTA_CATCHUP {json}} log line per row and a {@code BOSTA_CATCHUP_SUMMARY} line.
 */
@Service
public class BostaFulfillmentCatchUpService {

    private static final Logger log = LoggerFactory.getLogger(BostaFulfillmentCatchUpService.class);

    public record Row(String tenant, String orderNumber, String trackingNumber, String source,
                      Integer typeCode, Integer state, String verdict, String reason) {}

    record Candidate(UUID orderId, String orderNumber, UUID storeId, String externalId,
                     String trackingNumber, String source, JsonNode raw) {}

    private final JdbcTemplate                 jdbc;
    private final TransactionTemplate          tx;
    private final BostaFulfillmentLinkService  linkService;
    private final FulfillmentTrackingCapture   capture;
    private final ObjectMapper                 mapper;
    private final long                         delayMs;

    public BostaFulfillmentCatchUpService(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                          BostaFulfillmentLinkService linkService,
                                          FulfillmentTrackingCapture capture, ObjectMapper mapper,
                                          @Value("${bosta.fulfillment-link.catch-up.delay-ms:3000}") long delayMs) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.linkService = linkService;
        this.capture = capture;
        this.mapper = mapper;
        this.delayMs = delayMs;
    }

    @Job(name = "Bosta fulfillment-link catch-up — tenant %0 (apply=%1)", retries = 0)
    public void runAndLog(UUID tenantId, boolean apply) {
        Map<String, Integer> byVerdict = new LinkedHashMap<>();
        int[] count = {0};
        String error = null;
        try {
            run(tenantId, apply, r -> {
                log.info("BOSTA_CATCHUP {}", json(r));   // each row as soon as it's decided
                byVerdict.merge(r.verdict(), 1, Integer::sum);
                count[0]++;
            });
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            log.error("Bosta fulfillment-link catch-up tenant {} stopped: {}", tenantId, error, e);
        } finally {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("tenantId", tenantId.toString());
            summary.put("apply", apply);
            summary.put("rows", count[0]);
            summary.put("verdicts", byVerdict);
            if (error != null) summary.put("error", error);
            log.info("BOSTA_CATCHUP_SUMMARY {}", json(summary));
        }
    }

    public List<Row> run(UUID tenantId, boolean apply) {
        List<Row> out = new ArrayList<>();
        run(tenantId, apply, out::add);
        return out;
    }

    /**
     * Each row is isolated: an exception on one row becomes verdict ERROR with its message and the
     * run continues with the next row.
     */
    private void run(UUID tenantId, boolean apply, java.util.function.Consumer<Row> sink) {
        String tenantName = TenantContext.runAs(tenantId, () -> tx.execute(s ->
            jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenantId)));
        List<Candidate> candidates = TenantContext.runAs(tenantId, () -> candidates(tenantId));
        boolean first = true;
        for (Candidate c : candidates) {
            if (!first) sleep(delayMs);
            first = false;
            try {
                if (apply && "orders_raw".equals(c.source())) {
                    // Own transaction: the tenant GUC is applied at transaction begin (TenantAwareDataSource).
                    TenantContext.runAs(tenantId, () ->
                        tx.execute(s -> capture.upsertOnly(c.storeId(), c.externalId(), c.raw())));
                }
                BostaFulfillmentLinkService.Result r = linkService.attempt(tenantId, c.orderId(), c.trackingNumber(), !apply);
                sink.accept(new Row(tenantName, c.orderNumber(), c.trackingNumber(), c.source(),
                    r.typeCode(), r.state(), r.verdict().name(), r.reason()));
            } catch (RuntimeException e) {
                log.warn("Catch-up row {} / {} failed: {}", c.orderNumber(), c.trackingNumber(), e.toString());
                sink.accept(new Row(tenantName, c.orderNumber(), c.trackingNumber(), c.source(), null, null,
                    "ERROR", e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "")));
            }
        }
    }

    /** Orders with a non-cancelled Bosta fulfillment and no forward shipment with that number. */
    List<Candidate> candidates(UUID tenantId) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<UUID> tracked = new LinkedHashSet<>();

        tx.execute(s -> {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT o.id, o.number, o.store_id, o.external_id, t.tracking_number " +
                    "FROM order_fulfillment_tracking t JOIN orders o ON o.id = t.order_id AND o.tenant_id = t.tenant_id " +
                    "WHERE t.tenant_id = ? AND t.carrier_class = 'bosta' " +
                    "  AND coalesce(t.fulfillment_status, '') <> 'cancelled' " +
                    "  AND NOT EXISTS (SELECT 1 FROM shipments s WHERE s.tenant_id = t.tenant_id " +
                    "                    AND s.order_id = t.order_id AND s.tracking_number = t.tracking_number " +
                    "                    AND s.shipment_leg = 'forward') " +
                    "ORDER BY o.placed_at, t.tracking_number", tenantId)) {
                UUID orderId = (UUID) r.get("id");
                tracked.add(orderId);
                if (seen.add(orderId + "|" + r.get("tracking_number"))) {
                    out.add(new Candidate(orderId, (String) r.get("number"), (UUID) r.get("store_id"),
                        (String) r.get("external_id"), (String) r.get("tracking_number"), "order_fulfillment_tracking", null));
                }
            }
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT o.id, o.number, o.store_id, o.external_id, o.raw::text AS raw " +
                    "FROM orders o " +
                    "WHERE o.tenant_id = ? AND jsonb_typeof(o.raw->'fulfillments') = 'array' " +
                    "  AND EXISTS (SELECT 1 FROM jsonb_array_elements(o.raw->'fulfillments') f " +
                    "              WHERE (f->>'tracking_company' ILIKE '%bosta%' OR f->>'tracking_url' ILIKE '%bosta%') " +
                    "                AND coalesce(f->>'status', '') <> 'cancelled') " +
                    "ORDER BY o.placed_at", tenantId)) {
                UUID orderId = (UUID) r.get("id");
                if (tracked.contains(orderId)) continue;
                JsonNode raw;
                try { raw = mapper.readTree((String) r.get("raw")); } catch (Exception e) { continue; }
                for (JsonNode f : raw.path("fulfillments")) {
                    String company = text(f, "tracking_company");
                    String url = text(f, "tracking_url");
                    boolean bosta = (company != null && company.toLowerCase().contains("bosta"))
                        || (url != null && url.toLowerCase().contains("bosta"));
                    if (!bosta || "cancelled".equalsIgnoreCase(text(f, "status"))) continue;
                    Set<String> tns = new LinkedHashSet<>();
                    for (JsonNode n : f.path("tracking_numbers")) addBare(tns, n.asText(null));
                    addBare(tns, text(f, "tracking_number"));
                    for (String tn : tns) {
                        boolean shipped = Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM shipments WHERE tenant_id = ? AND order_id = ? " +
                            "  AND tracking_number = ? AND shipment_leg = 'forward')",
                            Boolean.class, tenantId, orderId, tn));
                        if (!shipped && seen.add(orderId + "|" + tn)) {
                            out.add(new Candidate(orderId, (String) r.get("number"), (UUID) r.get("store_id"),
                                (String) r.get("external_id"), tn, "orders_raw", raw));
                        }
                    }
                }
            }
            return null;
        });
        return out;
    }

    private static void addBare(Set<String> out, String raw) {
        String b = FulfillmentTrackingCapture.bareTrackingNumber(raw);
        if (b != null) out.add(b);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
