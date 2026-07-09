package com.traceability.integrations.bosta;

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

    public BostaIngestionHelper(JdbcTemplate jdbc,
                                 PlatformTransactionManager txm,
                                 BostaGateway bostaGateway,
                                 BostaStateMapper stateMapper,
                                 ObjectMapper mapper,
                                 JobScheduler jobScheduler,
                                 BostaWebhookJob webhookJob) {
        this.jdbc         = jdbc;
        this.tx           = new TransactionTemplate(txm);
        this.bostaGateway = bostaGateway;
        this.stateMapper  = stateMapper;
        this.mapper       = mapper;
        this.jobScheduler = jobScheduler;
        this.webhookJob   = webhookJob;
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

        String payloadJson;
        try {
            payloadJson = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize payload for " + trackingNumber, e);
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
            delivery.trackingNumber() + ":" + fetchedState + ":" + updatedAt);

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
