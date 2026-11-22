package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.inventory.ExchangeMultiItem;
import com.traceability.security.EncryptionService;
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
import java.util.function.Supplier;

/**
 * Exchange reference S3b B3 (2026-11-19) — one-off backfill: the tenant's open {@code EXCHANGE_MULTI_ITEM} unlinked
 * deliveries (recorded before multi-item exchanges were ingested) are brought in through the SAME path as a new
 * webhook (ExchangeIngestService → ExchangeService.tryAutoMap → the reference / pre-connect matchers). Never scheduled:
 * the ops startup trigger only ({@link BostaVisibilityCheckTrigger}: bosta.exchange-multi.catch-up.on-startup =
 * tenant ids, .apply). One job per tenant, every DB touch under that tenant (RLS). No Bosta write; the reads are v0
 * fetchDelivery with the tenant's key through the shared rate limiter (BACKGROUND), one per row in a dry run (plus the
 * webhook's own verify-by-fetch on apply). A Bosta rate limit stops the run.
 *
 * <pre>
 *   SKIP(reason)              no store / store not connected / no active Bosta account / Bosta refuses the key (every
 *                             row of the tenant), not an exchange, pre-connect (today's filter ignores it), counts
 *                             unreadable, Bosta no longer returns it (NOT_FOUND)
 *   WOULD_RESOLVE_EXISTING /  an exchanges row already exists for the AWB — apply only resolves the unlinked row
 *   RESOLVED_EXISTING
 *   WOULD_HOLD / HELD         Bosta hasn't told the inbound leg yet (no returnSpecs) — nothing written either way
 *   WOULD_LINK_SHADOW         an -R reference with a live Shopify-exchange shadow whose units match both ways
 *   WOULD_CREATE              {out, in, labelParses, wouldAutoCommit} — a needs_mapping row (auto-committed when the
 *                             SKU label rule holds)
 *   apply: LINKED_SHADOW / AUTO_COMMITTED / CREATED (+ the unlinked row resolved), or FAILED with the webhook's note
 * </pre>
 * Idempotent: candidates are the OPEN unlinked rows; apply resolves each (same transaction as the exchanges row), so a
 * rerun finds nothing. The backfill event's idempotency key carries a "|multi-catch-up" marker (never collides with the
 * event that recorded the delivery unlinked); a repeated apply of the same state finds the event already there. Output:
 * {@code EXCHANGE_MULTI_CATCHUP {json}} per row + {@code EXCHANGE_MULTI_CATCHUP_SUMMARY}.
 */
@Service
public class ExchangeMultiCatchUpService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeMultiCatchUpService.class);

    public record Row(String tenant, String trackingNumber, String verdict, Integer outItems, Integer inItems,
                      Boolean labelParses, Boolean wouldAutoCommit, String reference, String detail) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BostaWebhookJob webhookJob;
    private final BostaGateway bosta;
    private final EncryptionService encryption;
    private final PreConnectDeliveryFilter filter;
    private final ObjectMapper mapper;
    private BostaKeyHealth keyHealth;

    public ExchangeMultiCatchUpService(JdbcTemplate jdbc, PlatformTransactionManager txm, BostaWebhookJob webhookJob,
                                       BostaGateway bosta, EncryptionService encryption, PreConnectDeliveryFilter filter,
                                       ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.webhookJob = webhookJob;
        this.bosta = bosta;
        this.encryption = encryption;
        this.filter = filter;
        this.mapper = mapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKeyHealth(BostaKeyHealth keyHealth) { this.keyHealth = keyHealth; }

    @Job(name = "Bosta multi-item exchange catch-up — tenant %0 (apply=%1)", retries = 0)
    public void runAndLog(UUID tenantId, boolean apply) {
        Map<String, Integer> byVerdict = new LinkedHashMap<>();
        String error = null;
        List<Row> rows = List.of();
        try {
            rows = run(tenantId, apply);
            for (Row r : rows) {
                log.info("EXCHANGE_MULTI_CATCHUP {}", json(r));
                byVerdict.merge(r.verdict(), 1, Integer::sum);
            }
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            log.error("Multi-item exchange catch-up tenant {} stopped: {}", tenantId, error, e);
        } finally {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("tenantId", tenantId.toString());
            summary.put("apply", apply);
            summary.put("rows", rows.size());
            summary.put("verdicts", byVerdict);
            if (error != null) summary.put("error", error);
            log.info("EXCHANGE_MULTI_CATCHUP_SUMMARY {}", json(summary));
        }
    }

    private record Candidate(String trackingNumber, String reference) {}

    public List<Row> run(UUID tenantId, boolean apply) {
        String tenantName = inTenant(tenantId, () -> jdbc.query("SELECT name FROM tenants WHERE id = ?",
            rs -> rs.next() ? rs.getString(1) : tenantId.toString(), tenantId));
        List<Candidate> candidates = inTenant(tenantId, () -> jdbc.query(
            "SELECT tracking_number, business_reference FROM unlinked_bosta_deliveries " +
            "WHERE tenant_id = ? AND resolved = false AND match_reason = 'EXCHANGE_MULTI_ITEM' " +
            "ORDER BY first_seen_at, tracking_number",
            (rs, i) -> new Candidate(rs.getString(1), rs.getString(2)), tenantId));
        if (candidates.isEmpty()) return List.of();

        String tenantSkip = tenantSkip(tenantId);
        List<Row> out = new ArrayList<>();
        if (tenantSkip != null) {
            for (Candidate c : candidates) out.add(skip(tenantName, c, tenantSkip));
            return out;
        }
        String apiKey = encryption.decrypt(inTenant(tenantId, () -> jdbc.queryForObject(
            "SELECT api_key_encrypted FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
            String.class, tenantId)));
        for (Candidate c : candidates) {
            Row r;
            try {
                r = one(tenantId, tenantName, apiKey, c, apply);
            } catch (RuntimeException e) {
                r = new Row(tenantName, c.trackingNumber(), "ERROR", null, null, null, null, c.reference(),
                    e.getClass().getSimpleName());
            }
            out.add(r);
            if ("RATE_LIMITED".equals(r.verdict())) break;
        }
        return out;
    }

    /** Null when the tenant can be caught up; else why every row is skipped. */
    private String tenantSkip(UUID tenantId) {
        Map<String, Object> st = inTenant(tenantId, () -> jdbc.queryForMap(
            "SELECT COUNT(*) AS stores, COUNT(*) FILTER (WHERE status = 'connected') AS connected FROM stores WHERE tenant_id = ?",
            tenantId));
        if (((Number) st.get("stores")).intValue() == 0) return "no store";
        if (((Number) st.get("connected")).intValue() == 0) return "store not connected";
        Boolean account = inTenant(tenantId, () -> jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active')",
            Boolean.class, tenantId));
        if (!Boolean.TRUE.equals(account)) return "no active Bosta account";
        if (keyHealth != null && keyHealth.knownBad(tenantId)) return "Bosta refuses this tenant's key";
        return null;
    }

    private Row one(UUID tenantId, String tenantName, String apiKey, Candidate c, boolean apply) {
        String tn = c.trackingNumber();
        Boolean exists = inTenant(tenantId, () -> jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM exchanges WHERE tenant_id = ? AND tracking_number = ?)", Boolean.class, tenantId, tn));
        if (Boolean.TRUE.equals(exists)) {
            if (!apply) return verdict(tenantName, c, "WOULD_RESOLVE_EXISTING", null, null);
            int n = inTenant(tenantId, () -> resolveUnlinked(tenantId, tn));
            return verdict(tenantName, c, n > 0 ? "RESOLVED_EXISTING" : "SKIP", null, n > 0 ? null : "already resolved");
        }

        BostaDelivery d;
        try {
            d = bosta.fetchDelivery(apiKey, tn);
        } catch (BostaRateLimitException e) {
            return verdict(tenantName, c, "RATE_LIMITED", null, "retry after " + e.getRetryAfterSeconds() + " s — run stopped");
        } catch (DeliveryNotFoundException e) {
            return skip(tenantName, c, "Bosta no longer returns it");
        }
        if (d == null) return skip(tenantName, c, "Bosta no longer returns it");
        if (d.typeCode() != 30) return skip(tenantName, c, "not an exchange (type " + d.typeCode() + ")");
        if (Boolean.TRUE.equals(inTenant(tenantId, () -> filter.shouldIgnore(tenantId, tn, d)))) {
            return skip(tenantName, c, "pre-connect (ignored by today's filter)");
        }
        JsonNode raw = d.raw();
        ExchangeMultiItem.Counts counts = ExchangeMultiItem.counts(raw);
        boolean labelParses = ExchangeMultiItem.labelParses(raw);
        if (counts.out() < 1) return skip(tenantName, c, "outbound count unreadable");
        if (!counts.hasReturnSpecs()) {
            return new Row(tenantName, tn, apply ? "HELD" : "WOULD_HOLD", counts.out(), null, labelParses, false,
                d.businessReference(), "Bosta hasn't told the inbound leg yet");
        }
        if (counts.in() < 1) return skip(tenantName, c, "inbound count unreadable");

        boolean liveShadow = inTenant(tenantId, () -> ShopifyLiveShadow.exists(jdbc, tenantId, raw));
        boolean shadowMatches = liveShadow && inTenant(tenantId, () -> ExchangeMultiItem.shadowFor(jdbc, tenantId, raw)) != null;
        boolean autoCommit = !liveShadow && inTenant(tenantId, () -> ExchangeMultiItem.autoLines(jdbc, tenantId, raw)) != null;
        if (!apply) {
            String v = shadowMatches ? "WOULD_LINK_SHADOW" : "WOULD_CREATE";
            return new Row(tenantName, tn, v, counts.out(), counts.in(), labelParses, autoCommit, d.businessReference(),
                liveShadow && !shadowMatches ? "live Shopify exchange, units differ — left for a person" : null);
        }

        Long eventId = inTenant(tenantId, () -> insertEvent(tenantId, tn, d));
        if (eventId == null) return verdict(tenantName, c, "SKIP", counts, "an identical catch-up event already exists");
        webhookJob.process(eventId, tenantId);   // the normal path; sets / clears the tenant itself

        Map<String, Object> after = inTenant(tenantId, () -> jdbc.queryForList(
            "SELECT e.status, e.auto_matched, " +
            "  EXISTS (SELECT 1 FROM shopify_exchange_shadows sx WHERE sx.order_id = e.outbound_order_id " +
            "          AND sx.tenant_id = e.tenant_id) AS to_shadow, " +
            "  (SELECT bool_and(u.resolved) FROM unlinked_bosta_deliveries u WHERE u.tenant_id = e.tenant_id " +
            "     AND u.tracking_number = e.tracking_number AND u.match_reason = 'EXCHANGE_MULTI_ITEM') AS unlinked_resolved " +
            "FROM exchanges e " +
            "WHERE e.tenant_id = ? AND e.tracking_number = ?", tenantId, tn).stream().findFirst().orElse(null));
        if (after == null) {
            String note = inTenant(tenantId, () -> jdbc.queryForObject(
                "SELECT coalesce(error, status::text) FROM webhook_events WHERE id = ?", String.class, eventId));
            return new Row(tenantName, tn, "FAILED", counts.out(), counts.in(), labelParses, autoCommit, d.businessReference(), note);
        }
        String v = Boolean.TRUE.equals(after.get("to_shadow")) ? "LINKED_SHADOW"
            : Boolean.TRUE.equals(after.get("auto_matched")) ? "AUTO_COMMITTED" : "CREATED";
        return new Row(tenantName, tn, v, counts.out(), counts.in(), labelParses, autoCommit, d.businessReference(),
            "status " + after.get("status") + (Boolean.TRUE.equals(after.get("unlinked_resolved")) ? ", unlinked resolved" : ", unlinked still open"));
    }

    private int resolveUnlinked(UUID tenantId, String tn) {
        return jdbc.update(
            "UPDATE unlinked_bosta_deliveries SET resolved = true, last_seen_at = now() " +
            "WHERE tenant_id = ? AND tracking_number = ? AND resolved = false AND match_reason = 'EXCHANGE_MULTI_ITEM'",
            tenantId, tn);
    }

    /**
     * A 'bosta_backfill' event (always verified by fetch) whose updatedAt carries the catch-up marker, so its idempotency
     * key never collides with the event that recorded the delivery unlinked. ON CONFLICT → null: it exists already.
     */
    private Long insertEvent(UUID tenantId, String tn, BostaDelivery d) {
        String updatedAt = d.raw().path("updatedAt").asText("backfill-epoch") + "|multi-catch-up";
        int state = d.stateCode();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("trackingNumber", tn);
        payload.put("state", state);
        payload.put("type", "EXCHANGE");
        payload.put("updatedAt", updatedAt);
        String idemKey = BostaWebhookJob.sha256(tn + ":" + state + ":" + updatedAt);
        return jdbc.query(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at, external_event_id) " +
            "VALUES ('bosta_backfill'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now(), ?) " +
            "ON CONFLICT (source, external_event_id) WHERE external_event_id IS NOT NULL DO NOTHING RETURNING id",
            rs -> rs.next() ? rs.getLong(1) : null, tenantId, payload.toString(), idemKey);
    }

    private static Row skip(String tenant, Candidate c, String why) {
        return new Row(tenant, c.trackingNumber(), "SKIP", null, null, null, null, c.reference(), why);
    }

    private static Row verdict(String tenant, Candidate c, String v, ExchangeMultiItem.Counts counts, String detail) {
        return new Row(tenant, c.trackingNumber(), v, counts == null ? null : counts.out(), counts == null ? null : counts.in(),
            null, null, c.reference(), detail);
    }

    private <T> T inTenant(UUID tenantId, Supplier<T> body) {
        return TenantContext.runAs(tenantId, () -> tx.execute(s -> body.get()));
    }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }

    /** Is there a live Shopify-exchange shadow for this delivery's -R reference (units aside)? */
    static final class ShopifyLiveShadow {
        private ShopifyLiveShadow() {}
        static boolean exists(JdbcTemplate jdbc, UUID tenantId, JsonNode raw) {
            String ref = raw == null ? null : raw.path("businessReference").asText(null);
            return com.traceability.inventory.ShopifyShadowOrders.shadowForReturnReference(jdbc, tenantId, ref) != null;
        }
    }
}
