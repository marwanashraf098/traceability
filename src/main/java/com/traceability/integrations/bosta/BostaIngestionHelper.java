package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Shared per-delivery ingest logic used by BostaBackfillJob, BostaStatusPollJob,
 * and BostaDiscoveryPollJob.
 *
 * All three callers follow the same pipeline:
 *   1. fetch delivery from Bosta
 *   2. synthesize a webhook-compatible payload (trackingNumber + state + updatedAt)
 *   3. insert into webhook_events with the caller's source tag
 *   4. enqueue BostaWebhookJob for idempotent processing
 *
 * Callers MUST call this method inside TenantContext.runAs(tenantId) so that the GUC
 * is active when the webhook_events INSERT fires under RLS.
 *
 * Idempotency: the synthesized payload's updatedAt makes the idem key
 * (sha256(trackingNumber:stateCode:updatedAt)) identical to a real webhook for the
 * same event — dedup in BostaWebhookJob prevents double-processing across all sources.
 *
 * Returns true if a webhook_event was enqueued; false if the delivery was not found.
 */
@Component
public class BostaIngestionHelper {

    private static final Logger log = LoggerFactory.getLogger(BostaIngestionHelper.class);

    private final JdbcTemplate       jdbc;
    private final TransactionTemplate tx;
    private final BostaGateway        bostaGateway;
    private final BostaStateMapper    stateMapper;
    private final ObjectMapper        mapper;
    private final JobScheduler        jobScheduler;
    private final BostaWebhookJob     webhookJob;
    private final MatcherVersionHolder matcherVersionHolder;
    private final PreConnectDeliveryFilter preConnectFilter;

    public BostaIngestionHelper(JdbcTemplate jdbc,
                                 PlatformTransactionManager txm,
                                 BostaGateway bostaGateway,
                                 BostaStateMapper stateMapper,
                                 ObjectMapper mapper,
                                 JobScheduler jobScheduler,
                                 BostaWebhookJob webhookJob,
                                 MatcherVersionHolder matcherVersionHolder,
                                 PreConnectDeliveryFilter preConnectFilter) {
        this.jdbc                = jdbc;
        this.tx                  = new TransactionTemplate(txm);
        this.bostaGateway        = bostaGateway;
        this.stateMapper         = stateMapper;
        this.mapper              = mapper;
        this.jobScheduler        = jobScheduler;
        this.webhookJob          = webhookJob;
        this.matcherVersionHolder = matcherVersionHolder;
        this.preConnectFilter     = preConnectFilter;
    }

    /**
     * Convenience overload for callers (backfill, discovery) that don't have a stored
     * provider_state to compare against — state-change detection is skipped.
     */
    public boolean ingestDelivery(UUID tenantId, String apiKey,
                                   String trackingNumber, String source) {
        return ingestDelivery(tenantId, apiKey, trackingNumber, source, null);
    }

    /**
     * Fetches one delivery, synthesizes a payload, inserts into webhook_events, and
     * enqueues BostaWebhookJob. The source tag distinguishes the origin in webhook_events.
     *
     * Callers MUST call this inside TenantContext.runAs(tenantId).
     *
     * @param currentProviderState the stored Bosta numeric state code already on the shipment
     *                             row, or null if unknown/not yet stored. When non-null,
     *                             ingest is skipped if the fetched state equals this value
     *                             (no change → no enqueue). Pass null from backfill/discovery
     *                             paths that don't have the stored state readily available.
     * @return true if enqueued; false if skipped (404, unmappable state, or no state change)
     */
    public boolean ingestDelivery(UUID tenantId, String apiKey,
                                   String trackingNumber, String source,
                                   @Nullable Integer currentProviderState) {
        BostaDelivery delivery = bostaGateway.fetchDelivery(apiKey, trackingNumber);
        if (delivery == null) {
            log.debug("{}: {} not found (404) — skipping", source, trackingNumber);
            return false;
        }

        int fetchedState = delivery.stateCode();

        // Guard 1: never enqueue on an unmappable / extraction-failed state.
        // -1 means the state.code extraction failed (unexpected response shape); any other
        // code that has no mapping row in bosta_state_mappings is equally unusable.
        // An unmappable fetch is a fetch ERROR, not a state change — skip and warn.
        // Without this guard, every cycle would re-enqueue the same shipment, the
        // BostaWebhookJob would mark the event 'failed', and the loop would never end.
        BostaStateMapper.MappedState mapped = stateMapper.map(fetchedState, delivery.type());
        if (mapped.unknownCode()) {
            log.warn("{}: {} fetched unmappable state={} type='{}' — not enqueuing " +
                     "(extraction error, not a real state change)",
                source, trackingNumber, fetchedState, delivery.type());
            return false;
        }

        // Guard 2: skip if the state hasn't changed since the last processed cycle.
        // Avoids creating a webhook_event row + JobRunr job when Bosta confirms the
        // same state the shipment already has. The dedup key in BostaWebhookJob is a
        // second backstop, but catching it here is cheaper.
        if (currentProviderState != null && fetchedState == currentProviderState) {
            log.debug("{}: {} state unchanged ({}={}) — skipping",
                source, trackingNumber, fetchedState, currentProviderState);
            return false;
        }

        // "backfill-epoch" is stable for deliveries missing updatedAt: deduplicates
        // repeated runs for the same (tracking, state) without colliding with real
        // webhooks (which always carry an updatedAt timestamp).
        String updatedAt = (delivery.raw() != null)
            ? delivery.raw().path("updatedAt").asText("backfill-epoch")
            : "backfill-epoch";

        ObjectNode payload = mapper.createObjectNode();
        payload.put("trackingNumber", delivery.trackingNumber());
        payload.put("state",          delivery.stateCode());
        // Include type so the synthesized payload matches real webhook shape (flat string).
        // BostaWebhookJob re-fetches the delivery and uses delivery.type() for mapping,
        // but storing type here keeps the payload consistent and aids debugging.
        String type = (delivery.type() != null && !delivery.type().isBlank())
            ? delivery.type() : "SEND";
        payload.put("type",           type);
        payload.put("updatedAt",      updatedAt);

        return insertAndEnqueue(tenantId, delivery.trackingNumber(), fetchedState, updatedAt, payload, source);
    }

    /**
     * Discovery (2026-10-03): one item of the v2 delivery search, ingested from the list item itself —
     * no per-delivery fetch here — when it carries what the synthesized payload needs: trackingNumber,
     * state code, type and updatedAt, with a (state, type) the mapper knows. businessReference,
     * uniqueBusinessReference, shopifyInfo.orderId and creationTimestamp ride along in the payload.
     * Anything else falls back to {@link #ingestDelivery} (one fetch) — an unmappable list item is
     * never dropped on the list's word (the v2 list's type labels may differ from the v0 fetch's).
     * BostaWebhookJob still verifies by fetch when it processes the event, as for every source.
     *
     * Same guards, idem key and insert as {@link #ingestDelivery}. Callers MUST be inside
     * TenantContext.runAs(tenantId).
     *
     * @return true if enqueued
     */
    public boolean ingestListItem(UUID tenantId, String apiKey, JsonNode item, String source) {
        BostaDelivery d = BostaDelivery.fromRaw(null, item);
        String tn = d.trackingNumber();
        if (tn == null || tn.isBlank()) return false;
        String updatedAt = item.path("updatedAt").asText("");

        // Pre-connect filter on the list item itself (2026-10-04): the same rules as the webhook job's
        // (PreConnectDeliveryFilter — both sides of ':' in the reference, shopifyInfo.orderId, Bosta's
        // createdAt vs the cutoff, never for a NULL cutoff), decided here so an ignored delivery costs
        // no fetch at all. It gets ONE processed "ignored_pre_connect" row; once it has one, later
        // states of the same delivery write nothing more.
        if (Boolean.TRUE.equals(tx.execute(s -> preConnectFilter.shouldIgnore(tenantId, tn, d)))) {
            recordIgnoredPreConnect(tenantId, tn, d, updatedAt, source);
            return false;
        }
        boolean complete = d.stateCode() >= 0
            && item.hasNonNull("type")
            && !updatedAt.isBlank();
        if (complete && stateMapper.map(d.stateCode(), d.type()).unknownCode()) complete = false;
        if (!complete) {
            log.debug("{}: list item {} lacks what ingest needs (state/type/updatedAt or a mapped state) — fetching",
                source, tn);
            return ingestDelivery(tenantId, apiKey, tn, source, null);
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("trackingNumber", tn);
        payload.put("state",          d.stateCode());
        payload.put("type",           d.type());
        payload.put("updatedAt",      updatedAt);
        if (d.businessReference() != null) payload.put("businessReference", d.businessReference());
        String unique = item.path("uniqueBusinessReference").asText(null);
        if (unique != null && !unique.isBlank()) payload.put("uniqueBusinessReference", unique);
        if (d.shopifyOrderId() != null) payload.put("shopifyOrderId", d.shopifyOrderId());
        if (item.hasNonNull("creationTimestamp")) payload.set("creationTimestamp", item.get("creationTimestamp"));

        return insertAndEnqueue(tenantId, tn, d.stateCode(), updatedAt, payload, source);
    }

    /**
     * A pre-connect delivery seen on the list: one processed webhook_events row noting it (the same
     * 'ignored_pre_connect: tn' note the webhook job writes), with the idem key the event would have
     * had, and no job. Nothing at all when the delivery already has such a row (any source, any state).
     */
    private void recordIgnoredPreConnect(UUID tenantId, String tn, BostaDelivery d, String updatedAt, String source) {
        tx.execute(s -> {
            Boolean already = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
                "  AND status = 'processed' AND error LIKE 'ignored_pre_connect:%')",
                Boolean.class, tenantId, tn);
            if (Boolean.TRUE.equals(already)) return null;
            String upd = updatedAt.isBlank() ? "backfill-epoch" : updatedAt;
            ObjectNode payload = mapper.createObjectNode();
            payload.put("trackingNumber", tn);
            payload.put("state",          d.stateCode());
            payload.put("type",           d.type());
            payload.put("updatedAt",      upd);
            if (d.businessReference() != null) payload.put("businessReference", d.businessReference());
            if (d.shopifyOrderId() != null) payload.put("shopifyOrderId", d.shopifyOrderId());
            String idemKey = BostaWebhookJob.sha256(tn + ":" + d.stateCode() + ":" + upd);
            try {
                jdbc.update(
                    "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at, processed_at, " +
                    "    external_event_id, error, matcher_version) " +
                    "VALUES (?::webhook_source, ?, 'delivery_update', ?::jsonb, 'processed', now(), now(), ?, ?, ?) " +
                    "ON CONFLICT (source, external_event_id) WHERE external_event_id IS NOT NULL DO NOTHING",
                    source, tenantId, mapper.writeValueAsString(payload), idemKey,
                    "ignored_pre_connect: " + tn, matcherVersionHolder.get());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize payload for " + tn, e);
            }
            return null;
        });
        log.debug("{}: {} ignored — pre-connect (decided on the list item, no fetch)", source, tn);
    }

    /** Guard 3, the idem-keyed insert and the enqueue — shared by the fetch and list-item paths. */
    private boolean insertAndEnqueue(UUID tenantId, String trackingNumber, int fetchedState, String updatedAt,
                                     ObjectNode payload, String source) {
        String payloadJson;
        try {
            payloadJson = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize payload for " + trackingNumber, e);
        }

        // Guard 3 (version-aware): skip re-enqueue when an unlinked row exists at this state
        // AND the current matcher version. Two cases allow re-enqueue (Guard 3 passes):
        //   (a) matcher_version IS NULL — pre-V44 legacy row; NULL = ? evaluates to NULL (not true)
        //       so the COUNT predicate is not satisfied → Guard 3 passes → one retry on first V44 cycle.
        //   (b) matcher_version <> current — a different deploy stamped this row; retry eligible.
        // Both cases are handled by the strict equality predicate (AND matcher_version = ?):
        // NULL and any different value both fail to match, so COUNT = 0 → Guard 3 passes.
        // Strict = / <> avoids lexical ordering issues with Flyway version strings (e.g. "9" vs "44").
        //
        // Runs inside tx.execute() so TenantAwareConnection fires SET LOCAL app.current_tenant
        // and the RLS policy on unlinked_bosta_deliveries scopes the lookup to this tenant.
        Boolean blockedByGuard3 = tx.execute(s ->
            jdbc.queryForObject(
                "SELECT COUNT(*) > 0 FROM unlinked_bosta_deliveries " +
                "WHERE tracking_number = ? AND resolved = false " +
                "  AND bosta_state_code = ? " +
                "  AND matcher_version = ?",  // NULL or different version → not matched → Guard 3 passes
                Boolean.class, trackingNumber, fetchedState, matcherVersionHolder.get()));
        if (Boolean.TRUE.equals(blockedByGuard3)) {
            log.debug("{}: {} already unlinked at state {} (version={}) — skipping re-enqueue",
                source, trackingNumber, fetchedState, matcherVersionHolder.get());
            return false;
        }

        // Pre-compute the same idem key that BostaWebhookJob derives from the payload.
        // Setting external_event_id at INSERT time (instead of after processing) lets us use
        // ON CONFLICT DO NOTHING to dedup at creation: a second overlapping poll cycle that
        // fetches the same (tracking, state, updatedAt) hits the partial unique index
        // webhook_events_idem — (source, external_event_id) WHERE external_event_id IS NOT NULL —
        // and returns no row, so we skip enqueueing entirely. This prevents the race where both
        // events reach BostaWebhookJob concurrently and the second one's markProcessed() throws
        // DuplicateKeyException.
        String idemKey = BostaWebhookJob.sha256(
            trackingNumber + ":" + fetchedState + ":" + updatedAt);

        final String fPayload = payloadJson;
        final String fIdemKey = idemKey;
        Long webhookEventId = tx.execute(s -> jdbc.query(
            "INSERT INTO webhook_events " +
            "    (source, tenant_id, topic, payload, status, received_at, external_event_id) " +
            "VALUES (?::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now(), ?) " +
            "ON CONFLICT (source, external_event_id) WHERE external_event_id IS NOT NULL " +
            "DO NOTHING " +
            "RETURNING id",
            rs -> rs.next() ? rs.getLong("id") : null,
            source, tenantId, fPayload, fIdemKey));

        if (webhookEventId == null) {
            // ON CONFLICT DO NOTHING: a sibling event for this (source, idem key) is already
            // in flight or processed. The delivery will be (or already was) handled by that event.
            log.debug("{}: {} idem key already in flight or processed — skipping enqueue",
                source, trackingNumber);
            return false;
        }

        final long eventId = webhookEventId;
        jobScheduler.enqueue(() -> webhookJob.process(eventId, tenantId));
        log.debug("{}: enqueued event {} for {}", source, webhookEventId, trackingNumber);
        return true;
    }
}
