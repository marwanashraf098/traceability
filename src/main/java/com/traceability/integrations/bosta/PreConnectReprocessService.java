package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Instant;
import java.util.*;

/**
 * One-off repair (2026-11-16) of Bosta customer return pickups (25) / exchanges (30) dropped as
 * {@code ignored_pre_connect} by the old order-number rule although Bosta created them AFTER the connect (Storm
 * 2472106867 "#2739:#2739-R1"). Modelled on {@link BostaRateLimitedReprocessService}.
 *
 * Input: one tenant + an explicit tracking list, or one tenant + "open only" (no list). Candidates: the tenant's
 * processed webhook_events rows noted {@code ignored_pre_connect: <tn>} (the newest per tracking number). Per
 * candidate, re-read from Bosta (v0 fetchDelivery, the tenant's key, the shared rate limiter at BACKGROUND):
 *   SKIP          the tenant is excluded (bosta.reprocess-pre-connect.excluded-tenants, default Little Toes) or its
 *                 Bosta key is known bad; no active Bosta account; already handled since (a shipments / exchanges row
 *                 for the tracking number, or a later event with another outcome);
 *   NOT_FOUND     Bosta no longer returns it;
 *   NOT_ELIGIBLE  not a 25 / 30;
 *   NOT_OPEN      Bosta has picked it up already ({@link #OPEN_STATES}: 10 created, 11 awaiting route, 20 route
 *                 assigned) — delivered / closed / terminated / in flight stay ignored, in BOTH modes;
 *   STILL_IGNORED today's filter still ignores it (created before the cutoff);
 *   WOULD_REPROCESS (dry run, the default — writes nothing);
 *   apply → the row is claimed back to 'pending' (only from that processed ignored_pre_connect state — a second
 *   apply finds nothing) and run through BostaWebhookJob.process(): REPROCESSED with the new outcome
 *   ("exchange: …", linked, "unlinked: …"), RESCHEDULED, or FAILED. A Bosta rate limit stops the run (RATE_LIMITED).
 * Every row logs type, createdAt, state, reference and both item counts. Output: {@code BOSTA_PRECONNECT_REPROCESS
 * {json}} per row + {@code BOSTA_PRECONNECT_REPROCESS_SUMMARY}. Never scheduled: the ops startup trigger only
 * ({@link BostaVisibilityCheckTrigger}: bosta.reprocess-pre-connect.on-startup / .trackings / .apply). Each tenant's
 * rows are read and written under that tenant (RLS); nothing crosses tenants. No Shopify write; the only Shopify call
 * is the parent import's read inside process().
 */
@Service
public class PreConnectReprocessService {

    private static final Logger log = LoggerFactory.getLogger(PreConnectReprocessService.class);

    static final String NOTE_PREFIX = "ignored_pre_connect:";
    /** Not yet picked up by Bosta: 10 pickup requested / created, 11 waiting for a route, 20 route assigned. */
    static final Set<Integer> OPEN_STATES = Set.of(10, 11, 20);

    public record Row(String tenant, String trackingNumber, Long eventId, String verdict, Integer typeCode,
                      String createdAt, Integer state, String reference, Integer outItems, Integer inItems,
                      String decision, String detail) {}

    private record Candidate(long id, String trackingNumber) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BostaWebhookJob webhookJob;
    private final BostaGateway bosta;
    private final EncryptionService encryption;
    private final PreConnectDeliveryFilter filter;
    private final ObjectMapper mapper;
    private final Set<UUID> excluded;
    private BostaKeyHealth keyHealth;

    public PreConnectReprocessService(JdbcTemplate jdbc, PlatformTransactionManager txm, BostaWebhookJob webhookJob,
                                      BostaGateway bosta, EncryptionService encryption, PreConnectDeliveryFilter filter,
                                      ObjectMapper mapper,
                                      @Value("${bosta.reprocess-pre-connect.excluded-tenants:fbc7e946-12d9-4a3b-a396-387c0dacf9f5}")
                                      String excludedTenants) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.webhookJob = webhookJob;
        this.bosta = bosta;
        this.encryption = encryption;
        this.filter = filter;
        this.mapper = mapper;
        Set<UUID> ex = new HashSet<>();
        for (String s : excludedTenants.split(",")) if (!s.isBlank()) ex.add(UUID.fromString(s.trim()));
        this.excluded = Set.copyOf(ex);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKeyHealth(BostaKeyHealth keyHealth) { this.keyHealth = keyHealth; }

    @Job(name = "Bosta pre-connect 25/30 re-process — tenant %0 (apply=%2)", retries = 0)
    public void runAndLog(UUID tenantId, String trackingsCsv, boolean apply) {
        List<String> trackings = BostaVisibilityCheckTrigger.orderNumbers(trackingsCsv);
        List<Row> rows = run(tenantId, trackings, apply);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Row r : rows) {
            log.info("BOSTA_PRECONNECT_REPROCESS {}", json(r));
            counts.merge(r.verdict(), 1, Integer::sum);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tenantId", tenantId.toString());
        summary.put("apply", apply);
        summary.put("mode", trackings.isEmpty() ? "open_only" : "trackings");
        summary.put("rows", rows.size());
        summary.put("verdicts", counts);
        log.info("BOSTA_PRECONNECT_REPROCESS_SUMMARY {}", json(summary));
    }

    /**
     * {@code trackings} empty / null = every candidate of the tenant that is still open. Every DB touch is its own
     * TenantContext.runAs block — never nested around BostaWebhookJob.process(), which sets and clears the tenant.
     */
    public List<Row> run(UUID tenantId, List<String> trackings, boolean apply) {
        String tenantName = inTenant(tenantId, () -> jdbc.query(
            "SELECT name FROM tenants WHERE id = ?", rs -> rs.next() ? rs.getString(1) : tenantId.toString(), tenantId));
        Set<String> only = new LinkedHashSet<>();
        if (trackings != null) for (String t : trackings) if (t != null && !t.isBlank()) only.add(t.replaceAll("\\s+", ""));

        if (excluded.contains(tenantId)) return List.of(skip(tenantName, null, null, "tenant excluded"));
        if (keyHealth != null && keyHealth.knownBad(tenantId)) {
            return List.of(skip(tenantName, null, null, "Bosta refuses this tenant's key"));
        }
        String key = inTenant(tenantId, () -> jdbc.queryForList(
            "SELECT api_key_encrypted FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
            String.class, tenantId).stream().findFirst().orElse(null));
        if (key == null) return List.of(skip(tenantName, null, null, "no active Bosta account"));
        String apiKey = encryption.decrypt(key);

        List<Candidate> candidates = inTenant(tenantId, () -> jdbc.query(
            "SELECT DISTINCT ON (payload->>'trackingNumber') id, payload->>'trackingNumber' AS tn FROM webhook_events " +
            "WHERE tenant_id = ? AND status = 'processed' AND error LIKE ? " +
            "ORDER BY payload->>'trackingNumber', id DESC",
            (rs, i) -> new Candidate(rs.getLong("id"), rs.getString("tn")), tenantId, NOTE_PREFIX + "%"));
        if (!only.isEmpty()) candidates = candidates.stream().filter(c -> only.contains(c.trackingNumber())).toList();

        List<Row> out = new ArrayList<>();
        for (Candidate c : candidates) {
            try {
                Row r = one(tenantId, tenantName, apiKey, c, apply);
                out.add(r);
                if ("RATE_LIMITED".equals(r.verdict())) break;
            } catch (Exception e) {
                out.add(new Row(tenantName, c.trackingNumber(), c.id(), "ERROR", null, null, null, null, null, null, null,
                    e.getClass().getSimpleName()));
            }
        }
        if (!only.isEmpty()) {
            Set<String> seen = new HashSet<>();
            for (Candidate c : candidates) seen.add(c.trackingNumber());
            for (String t : only) if (!seen.contains(t)) out.add(skip(tenantName, t, null, "no ignored_pre_connect event"));
        }
        return out;
    }

    private Row one(UUID tenantId, String tenantName, String apiKey, Candidate c, boolean apply) {
        String tn = c.trackingNumber();
        Boolean handled = inTenant(tenantId, () -> jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM shipments WHERE tenant_id = ? AND tracking_number = ?) " +
            "    OR EXISTS (SELECT 1 FROM exchanges WHERE tenant_id = ? AND tracking_number = ?) " +
            "    OR EXISTS (SELECT 1 FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? AND id > ? " +
            "               AND status IN ('processed', 'pending') AND COALESCE(error, '') NOT LIKE ?)",
            Boolean.class, tenantId, tn, tenantId, tn, tenantId, tn, c.id(), NOTE_PREFIX + "%"));
        if (Boolean.TRUE.equals(handled)) return skip(tenantName, tn, c.id(), "already handled since");

        BostaDelivery d;
        try {
            d = bosta.fetchDelivery(apiKey, tn);
        } catch (BostaRateLimitException e) {
            return new Row(tenantName, tn, c.id(), "RATE_LIMITED", null, null, null, null, null, null, null,
                "retry after " + e.getRetryAfterSeconds() + " s — run stopped");
        } catch (DeliveryNotFoundException e) {
            return new Row(tenantName, tn, c.id(), "NOT_FOUND", null, null, null, null, null, null, null, null);
        }
        if (d == null) return new Row(tenantName, tn, c.id(), "NOT_FOUND", null, null, null, null, null, null, null, null);

        JsonNode raw = d.raw();
        Instant created = PreConnectDeliveryFilter.parseCreatedAt(raw);
        Integer outItems = items(raw, "specs");
        Integer inItems = items(raw, "returnSpecs");
        boolean ignore = Boolean.TRUE.equals(inTenant(tenantId, () -> filter.shouldIgnore(tenantId, tn, d)));
        String decision = ignore ? "IGNORE" : "KEEP";
        java.util.function.Function<String, Row> row = verdict -> new Row(tenantName, tn, c.id(), verdict, d.typeCode(),
            created == null ? null : created.toString(), d.stateCode(), d.businessReference(), outItems, inItems, decision, null);

        if (!PreConnectDeliveryFilter.createdAtDecides(d)) return row.apply("NOT_ELIGIBLE");
        if (!OPEN_STATES.contains(d.stateCode())) return row.apply("NOT_OPEN");
        if (ignore) return row.apply("STILL_IGNORED");
        if (!apply) return row.apply("WOULD_REPROCESS");

        Integer claimed = inTenant(tenantId, () -> jdbc.update(
            "UPDATE webhook_events SET status = 'pending', error = NULL, processed_at = NULL, external_event_id = NULL " +
            "WHERE id = ? AND tenant_id = ? AND status = 'processed' AND error LIKE ?",
            c.id(), tenantId, NOTE_PREFIX + "%"));
        if (claimed == null || claimed == 0) return skip(tenantName, tn, c.id(), "no longer an ignored event");

        webhookJob.process(c.id(), tenantId);   // the normal path; sets / clears the tenant itself

        Map<String, Object> after = inTenant(tenantId, () -> jdbc.queryForMap(
            "SELECT status::text AS status, error FROM webhook_events WHERE id = ?", c.id()));
        String status = (String) after.get("status");
        String error = (String) after.get("error");
        Row base = row.apply(switch (status) {
            case "processed" -> "REPROCESSED";
            case "pending" -> "RESCHEDULED";
            default -> "FAILED";
        });
        return new Row(base.tenant(), base.trackingNumber(), base.eventId(), base.verdict(), base.typeCode(),
            base.createdAt(), base.state(), base.reference(), base.outItems(), base.inItems(), base.decision(),
            error == null ? "linked / applied" : error);
    }

    private static Integer items(JsonNode raw, String side) {
        if (raw == null) return null;
        JsonNode n = raw.path(side).path("packageDetails").path("itemsCount");
        return n.isNumber() ? n.asInt() : null;
    }

    private static Row skip(String tenant, String tn, Long eventId, String why) {
        return new Row(tenant, tn, eventId, "SKIP", null, null, null, null, null, null, null, why);
    }

    private <T> T inTenant(UUID tenantId, java.util.function.Supplier<T> body) {
        return TenantContext.runAs(tenantId, () -> tx.execute(s -> body.get()));
    }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }
}
