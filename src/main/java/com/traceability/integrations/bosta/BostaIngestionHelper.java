package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final ObjectMapper        mapper;
    private final JobScheduler        jobScheduler;
    private final BostaWebhookJob     webhookJob;

    public BostaIngestionHelper(JdbcTemplate jdbc,
                                 PlatformTransactionManager txm,
                                 BostaGateway bostaGateway,
                                 ObjectMapper mapper,
                                 JobScheduler jobScheduler,
                                 BostaWebhookJob webhookJob) {
        this.jdbc        = jdbc;
        this.tx          = new TransactionTemplate(txm);
        this.bostaGateway = bostaGateway;
        this.mapper      = mapper;
        this.jobScheduler = jobScheduler;
        this.webhookJob  = webhookJob;
    }

    /**
     * Fetches one delivery, synthesizes a payload, inserts into webhook_events, and
     * enqueues BostaWebhookJob. The source tag distinguishes the origin in webhook_events.
     *
     * Must be called inside TenantContext.runAs(tenantId) — the caller owns the context.
     *
     * @return true if enqueued; false if delivery not found (404)
     */
    public boolean ingestDelivery(UUID tenantId, String apiKey,
                                   String trackingNumber, String source) {
        BostaDelivery delivery = bostaGateway.fetchDelivery(apiKey, trackingNumber);
        if (delivery == null) {
            log.debug("{}: {} not found (404) — skipping", source, trackingNumber);
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
        payload.put("updatedAt",      updatedAt);

        String payloadJson;
        try {
            payloadJson = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize payload for " + trackingNumber, e);
        }

        final String fPayload = payloadJson;
        Long webhookEventId = tx.execute(s -> jdbc.query(
            "INSERT INTO webhook_events " +
            "    (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES (?::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now()) " +
            "RETURNING id",
            rs -> rs.next() ? rs.getLong("id") : null,
            source, tenantId, fPayload));

        if (webhookEventId == null) {
            log.error("{}: webhook_events INSERT returned no id for {}", source, trackingNumber);
            return false;
        }

        final long eventId = webhookEventId;
        jobScheduler.enqueue(() -> webhookJob.process(eventId, tenantId));
        log.debug("{}: enqueued event {} for {}", source, webhookEventId, trackingNumber);
        return true;
    }
}
