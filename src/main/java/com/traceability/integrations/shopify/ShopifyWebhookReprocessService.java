package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-time re-process of Shopify webhook events that never got applied (2026-10-04) — the first run
 * of the automatic retry, for the events it doesn't sweep: legacy failures (process_error set before
 * V136) and events never processed at all (older than 10 minutes).
 *
 * Per row (tenant, topic, resource, verdict):
 *   dry run (the default; writes nothing) → SUPERSEDED + reason (a later event for the same order /
 *     product was applied, or Traced's stored copy is newer — it would NOT be applied) or WOULD_REPROCESS;
 *   apply → claimed (only while still unprocessed — a second apply finds nothing) and run through the
 *     normal path, ShopifyWebhookProcessorJob.process(), which applies the same ordering safety:
 *     REPROCESSED, SUPERSEDED, FAILED (its error; the sweeper retries it) or ERROR.
 * Each tenant's rows are read and written under that tenant (RLS).
 *
 * Never scheduled: owner POST /api/v1/shopify/webhooks/reprocess?apply=…, or the ops startup trigger
 * (shopify.webhook.reprocess.on-startup + .apply). Output: one {@code SHOPIFY_REPROCESS {json}} line
 * per row and a {@code SHOPIFY_REPROCESS_SUMMARY} line.
 */
@Service
public class ShopifyWebhookReprocessService {

    private static final Logger log = LoggerFactory.getLogger(ShopifyWebhookReprocessService.class);

    public record Row(String tenant, UUID eventId, String topic, String resource, String verdict, String detail) {}

    private record Candidate(UUID id, String topic, String payload, Timestamp receivedAt) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyWebhookProcessorJob processor;
    private final ObjectMapper mapper;

    public ShopifyWebhookReprocessService(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                          ShopifyWebhookProcessorJob processor, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.processor = processor;
        this.mapper = mapper;
    }

    @Job(name = "Shopify webhook re-process — tenant %0 (apply=%1)", retries = 0)
    public void runAndLog(UUID tenantId, boolean apply) {
        List<Row> rows = run(tenantId, apply);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Row r : rows) {
            log.info("SHOPIFY_REPROCESS {}", json(r));
            counts.merge(r.verdict(), 1, Integer::sum);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tenantId", tenantId.toString());
        summary.put("apply", apply);
        summary.put("rows", rows.size());
        summary.put("verdicts", counts);
        log.info("SHOPIFY_REPROCESS_SUMMARY {}", json(summary));
    }

    /** Every DB touch is its own TenantContext.runAs — never nested around process(), which sets / clears it. */
    public List<Row> run(UUID tenantId, boolean apply) {
        String tenantName = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.query(
            "SELECT name FROM tenants WHERE id = ?", rs -> rs.next() ? rs.getString(1) : tenantId.toString(), tenantId)));
        List<Candidate> candidates = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.query(
            "SELECT id, topic, payload_raw::text AS payload, received_at FROM shopify_webhook_events " +
            "WHERE tenant_id = ? AND processed_at IS NULL AND superseded_at IS NULL " +
            "  AND (process_error IS NOT NULL OR received_at < now() - INTERVAL '10 minutes') " +
            "ORDER BY received_at, id",
            (rs, i) -> new Candidate(rs.getObject("id", UUID.class), rs.getString("topic"), rs.getString("payload"),
                rs.getTimestamp("received_at")),
            tenantId)));

        List<Row> out = new ArrayList<>();
        for (Candidate c : candidates) {
            String resource = resource(c.payload());
            try {
                out.add(one(tenantId, tenantName, c, resource, apply));
            } catch (Exception e) {
                out.add(new Row(tenantName, c.id(), c.topic(), resource, "ERROR",
                    e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "")));
            }
        }
        return out;
    }

    private Row one(UUID tenantId, String tenantName, Candidate c, String resource, boolean apply) throws Exception {
        if (!apply) {
            JsonNode payload = mapper.readTree(c.payload());
            String superseded = TenantContext.runAs(tenantId,
                () -> processor.supersededReason(tenantId, c.id(), c.topic(), payload, c.receivedAt()));
            return superseded != null
                ? new Row(tenantName, c.id(), c.topic(), resource, "SUPERSEDED", superseded)
                : new Row(tenantName, c.id(), c.topic(), resource, "WOULD_REPROCESS", null);
        }
        Integer claimed = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.update(
            "UPDATE shopify_webhook_events SET retry_count = retry_count + 1, next_retry_at = now() + INTERVAL '30 minutes' " +
            "WHERE id = ? AND tenant_id = ? AND processed_at IS NULL AND superseded_at IS NULL", c.id(), tenantId)));
        if (claimed == null || claimed == 0) {
            return new Row(tenantName, c.id(), c.topic(), resource, "SKIP", "no longer unprocessed");
        }

        processor.process(c.id(), tenantId);   // the normal path; sets / clears the tenant itself

        Map<String, Object> after = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.queryForMap(
            "SELECT processed_at, superseded_at, process_error FROM shopify_webhook_events WHERE id = ?", c.id())));
        if (after.get("superseded_at") != null) return new Row(tenantName, c.id(), c.topic(), resource, "SUPERSEDED", null);
        if (after.get("processed_at") != null)  return new Row(tenantName, c.id(), c.topic(), resource, "REPROCESSED", null);
        return new Row(tenantName, c.id(), c.topic(), resource, "FAILED", (String) after.get("process_error"));
    }

    private String resource(String payload) {
        try {
            JsonNode p = mapper.readTree(payload);
            String gid = p.path("admin_graphql_api_id").asText(null);
            String name = p.path("name").asText(null);
            return gid == null ? null : (name == null ? gid : name + " " + gid);
        } catch (Exception e) {
            return null;
        }
    }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }
}
