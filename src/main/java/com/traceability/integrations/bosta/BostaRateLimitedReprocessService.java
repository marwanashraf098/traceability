package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-time re-process of Bosta webhook events that FAILED on a Bosta rate limit (2026-10-04). Before
 * V135 the webhook job marked such an event failed; its idem key then kept the delivery from ever
 * being ingested again at that (state, updatedAt) — 78 new deliveries on 2026-10-04 00:28, plus older
 * ones from every source.
 *
 * Candidates: this tenant's webhook_events with status 'failed' and an error starting
 * {@code Bosta fetch error: Bosta rate limit}. Per row:
 *   SKIP   no active Bosta account, or superseded — a later event for the same tracking number is
 *          processed or pending (the delivery was handled since);
 *   dry run (the default; writes nothing) → WOULD_REPROCESS;
 *   apply → the row is claimed back to 'pending' (only from that failed state — a second apply finds
 *          nothing) and run through the normal path, BostaWebhookJob.process(): verdict REPROCESSED
 *          with the outcome (linked / unlinked / ignored_pre_connect / …), RESCHEDULED when Bosta is
 *          still rate limiting (the job now reschedules instead of failing), or FAILED with the error.
 * Pacing is the shared Bosta limiter's. Each tenant's rows are read and written under that tenant
 * (RLS); nothing crosses tenants.
 *
 * Never scheduled: owner POST /api/v1/bosta/reprocess-rate-limited?apply=…, or the ops startup trigger
 * (bosta.reprocess-rate-limited.on-startup + .apply). Output: one {@code BOSTA_REPROCESS {json}} line
 * per row and a {@code BOSTA_REPROCESS_SUMMARY} line.
 */
@Service
public class BostaRateLimitedReprocessService {

    private static final Logger log = LoggerFactory.getLogger(BostaRateLimitedReprocessService.class);

    static final String RATE_LIMITED_ERROR_PREFIX = "Bosta fetch error: Bosta rate limit";

    public record Row(String tenant, String trackingNumber, long eventId, String source, String verdict, String detail) {}

    private record Candidate(long id, String trackingNumber, String source) {}

    private final JdbcTemplate        jdbc;
    private final TransactionTemplate tx;
    private final BostaWebhookJob     webhookJob;
    private final ObjectMapper        mapper;

    public BostaRateLimitedReprocessService(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                            BostaWebhookJob webhookJob, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.webhookJob = webhookJob;
        this.mapper = mapper;
    }

    @Job(name = "Bosta rate-limited events re-process — tenant %0 (apply=%1)", retries = 0)
    public void runAndLog(UUID tenantId, boolean apply) {
        List<Row> rows = run(tenantId, apply);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Row r : rows) {
            log.info("BOSTA_REPROCESS {}", json(r));
            counts.merge(r.verdict(), 1, Integer::sum);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tenantId", tenantId.toString());
        summary.put("tenant", rows.isEmpty() ? null : rows.get(0).tenant());
        summary.put("apply", apply);
        summary.put("rows", rows.size());
        summary.put("verdicts", counts);
        log.info("BOSTA_REPROCESS_SUMMARY {}", json(summary));
    }

    /**
     * Runs the re-process for one tenant. Every DB touch is its own TenantContext.runAs block — never
     * nested around BostaWebhookJob.process(), which sets and clears the tenant itself.
     */
    public List<Row> run(UUID tenantId, boolean apply) {
        String tenantName = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.query(
            "SELECT name FROM tenants WHERE id = ?", rs -> rs.next() ? rs.getString(1) : tenantId.toString(), tenantId)));
        boolean active = Boolean.TRUE.equals(TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active')",
            Boolean.class, tenantId))));
        List<Candidate> candidates = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.query(
            "SELECT id, payload->>'trackingNumber' AS tn, source::text AS source FROM webhook_events " +
            "WHERE tenant_id = ? AND status = 'failed' AND error LIKE ? ORDER BY received_at, id",
            (rs, i) -> new Candidate(rs.getLong("id"), rs.getString("tn"), rs.getString("source")),
            tenantId, RATE_LIMITED_ERROR_PREFIX + "%")));

        List<Row> out = new ArrayList<>();
        for (Candidate c : candidates) {
            try {
                out.add(one(tenantId, tenantName, active, c, apply));
            } catch (Exception e) {
                out.add(new Row(tenantName, c.trackingNumber(), c.id(), c.source(), "ERROR",
                    e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "")));
            }
        }
        return out;
    }

    private Row one(UUID tenantId, String tenantName, boolean active, Candidate c, boolean apply) {
        if (!active) return new Row(tenantName, c.trackingNumber(), c.id(), c.source(), "SKIP", "no active Bosta account");
        Long later = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.query(
            "SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? AND id > ? " +
            "  AND status IN ('processed', 'pending') ORDER BY id DESC LIMIT 1",
            rs -> rs.next() ? rs.getLong(1) : null, tenantId, c.trackingNumber(), c.id())));
        if (later != null) {
            return new Row(tenantName, c.trackingNumber(), c.id(), c.source(), "SKIP", "superseded by event " + later);
        }
        if (!apply) return new Row(tenantName, c.trackingNumber(), c.id(), c.source(), "WOULD_REPROCESS", null);

        Integer claimed = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.update(
            "UPDATE webhook_events SET status = 'pending', error = NULL, processed_at = NULL, " +
            "  rate_limit_retries = 0, rate_limited_until = NULL " +
            "WHERE id = ? AND tenant_id = ? AND status = 'failed' AND error LIKE ?",
            c.id(), tenantId, RATE_LIMITED_ERROR_PREFIX + "%")));
        if (claimed == null || claimed == 0) {
            return new Row(tenantName, c.trackingNumber(), c.id(), c.source(), "SKIP", "no longer a failed rate-limited event");
        }

        webhookJob.process(c.id(), tenantId);   // the normal path; sets / clears the tenant itself

        Map<String, Object> after = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.queryForMap(
            "SELECT status::text AS status, error FROM webhook_events WHERE id = ?", c.id())));
        String status = (String) after.get("status");
        String error  = (String) after.get("error");
        return switch (status) {
            case "processed" -> new Row(tenantName, c.trackingNumber(), c.id(), c.source(), "REPROCESSED",
                error == null ? "linked / applied" : error);
            case "pending"   -> new Row(tenantName, c.trackingNumber(), c.id(), c.source(), "RESCHEDULED", error);
            default          -> new Row(tenantName, c.trackingNumber(), c.id(), c.source(), "FAILED", error);
        };
    }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }
}
