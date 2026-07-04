package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Backfills historical Bosta deliveries that existed before the webhook was configured
 * or that were missed while the webhook was down.
 *
 * Design: backfilled deliveries route through the EXISTING webhook pipeline.
 * The job synthesizes a webhook-compatible JsonNode payload, inserts it into
 * webhook_events as 'pending', and enqueues BostaWebhookJob. This means there is
 * ONE ingestion code path — matching, state mapping, piece transitions, and
 * unlinked recording are all identical to live webhook processing.
 *
 * Mode B preserved: this job only reads from Bosta (list + fetch) and writes
 * to our own DB. No delivery creation or modification on Bosta's side.
 *
 * Idempotency: the synthesized payload includes updatedAt from the fetched delivery,
 * which makes the idem key (sha256(trackingNumber:stateCode:updatedAt)) identical to
 * what BostaWebhookJob would produce for the same live webhook event. If a real webhook
 * already processed this (tracking, state, updatedAt) tuple, the dedup check in
 * BostaWebhookJob step 4 marks the backfill event as duplicate — no double-processing.
 * Running the backfill twice produces new webhook_events rows but they are all dedup'd
 * on processing.
 *
 * TenantContext: the ENTIRE job (including all TransactionTemplate calls) runs inside
 * TenantContext.runAs(tenantId, ...) so TenantAwareConnection fires set_config(
 * 'app.current_tenant', tenantId) for every transaction — RLS applies correctly.
 * Never call runAs inside processOneDelivery; it inherits the outer context.
 *
 * Known limitation: order.status is NOT advanced by backfill (consistent with the
 * webhook flow, which also does not update orders.status). A backfilled delivered
 * shipment may sit on a packed order — visible seam, acceptable for pilot.
 */
@Component
public class BostaBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(BostaBackfillJob.class);

    private final JdbcTemplate       jdbc;
    private final TransactionTemplate tx;
    private final BostaGateway        bostaGateway;
    private final EncryptionService   encryptionService;
    private final ObjectMapper        mapper;
    private final JobScheduler        jobScheduler;
    private final BostaWebhookJob     webhookJob;
    private final int                 defaultMaxPages;
    private final int                 pageSize;
    private final long                interFetchDelayMs;

    public BostaBackfillJob(JdbcTemplate jdbc,
                             PlatformTransactionManager txm,
                             BostaGateway bostaGateway,
                             EncryptionService encryptionService,
                             ObjectMapper mapper,
                             JobScheduler jobScheduler,
                             BostaWebhookJob webhookJob,
                             @Value("${bosta.backfill.max-pages:20}") int defaultMaxPages,
                             @Value("${bosta.backfill.page-size:50}") int pageSize,
                             @Value("${bosta.backfill.inter-fetch-delay-ms:100}") long interFetchDelayMs) {
        this.jdbc              = jdbc;
        this.tx                = new TransactionTemplate(txm);
        this.bostaGateway      = bostaGateway;
        this.encryptionService = encryptionService;
        this.mapper            = mapper;
        this.jobScheduler      = jobScheduler;
        this.webhookJob        = webhookJob;
        this.defaultMaxPages   = defaultMaxPages;
        this.pageSize          = pageSize;
        this.interFetchDelayMs = interFetchDelayMs;
    }

    @Job(name = "Bosta delivery backfill — tenant %0")
    public void run(UUID tenantId, int maxPages) {
        TenantContext.runAs(tenantId, (Runnable) () -> {

            // 1. Load API key (same pattern as BostaWebhookJob step 5).
            String[] accountInfo = tx.execute(s -> jdbc.query(
                "SELECT api_key_encrypted FROM courier_accounts " +
                "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
                rs -> rs.next() ? new String[]{rs.getString(1)} : null,
                tenantId));

            if (accountInfo == null) {
                log.warn("Backfill: no active Bosta account for tenant {} — skipping", tenantId);
                return;
            }

            String rawApiKey = encryptionService.decrypt(accountInfo[0]);
            int total = 0, enqueued = 0;

            // 2. Page loop — page cap prevents unbounded pulls.
            outer:
            for (int page = 1; page <= maxPages; page++) {
                List<BostaGateway.SlimDelivery> items;
                try {
                    items = bostaGateway.listDeliveriesPage(rawApiKey, page, pageSize);
                } catch (BostaTransientException e) {
                    log.warn("Backfill tenant {}: transient error on page {} — stopping early: {}",
                        tenantId, page, e.getMessage());
                    break;
                }

                if (items.isEmpty()) break;  // past last page

                for (BostaGateway.SlimDelivery slim : items) {
                    total++;
                    try {
                        if (processOneDelivery(tenantId, rawApiKey, slim.trackingNumber())) {
                            enqueued++;
                        }
                    } catch (BostaTransientException e) {
                        log.warn("Backfill tenant {}: transient error on {} — skipping this item: {}",
                            tenantId, slim.trackingNumber(), e.getMessage());
                    } catch (Exception e) {
                        log.error("Backfill tenant {}: unexpected error on {} — skipping",
                            tenantId, slim.trackingNumber(), e);
                    }

                    try {
                        if (interFetchDelayMs > 0) Thread.sleep(interFetchDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break outer;
                    }
                }
            }

            // 3. Update backfill counters on courier_accounts (under tenant context).
            final int fTotal = total, fEnqueued = enqueued;
            tx.execute(s -> {
                jdbc.update(
                    "UPDATE courier_accounts " +
                    "SET last_backfill_at = now(), last_backfill_total = ?, last_backfill_enqueued = ? " +
                    "WHERE tenant_id = ? AND provider = 'bosta'",
                    fTotal, fEnqueued, tenantId);
                return null;
            });

            log.info("Backfill complete for tenant {}: {} seen, {} enqueued", tenantId, total, enqueued);
        });
    }

    /**
     * Fetches one delivery by tracking number, synthesizes a webhook-compatible payload,
     * inserts it into webhook_events, and enqueues BostaWebhookJob for processing.
     *
     * Returns true if an event was enqueued; false if the delivery was not found (404).
     *
     * The synthesized payload contains trackingNumber + state + updatedAt.
     * BostaWebhookJob reads these same three fields to compute the idem key and then
     * re-fetches the delivery (verify-by-fetch) — so type and businessReference do NOT
     * need to be in the payload.
     *
     * The updatedAt field is critical for idem-key correctness: using the fetched
     * delivery's updatedAt means sha256(trackingNumber:stateCode:updatedAt) matches
     * the key a real webhook for the same event would produce, so backfill and
     * live webhook dedup against each other automatically.
     */
    private boolean processOneDelivery(UUID tenantId, String rawApiKey, String trackingNumber) {
        BostaDelivery delivery = bostaGateway.fetchDelivery(rawApiKey, trackingNumber);
        if (delivery == null) {
            log.debug("Backfill: {} not found (404) — skipping", trackingNumber);
            return false;
        }

        // Extract updatedAt for idem key derivation.
        // "backfill-epoch" is a stable sentinel for deliveries without updatedAt:
        // it deduplicates repeated backfills for the same (tracking, state) without
        // interfering with real webhooks (which always have an updatedAt timestamp).
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
            throw new RuntimeException("Failed to serialize backfill payload for " + trackingNumber, e);
        }

        Long webhookEventId = tx.execute(s -> jdbc.query(
            "INSERT INTO webhook_events " +
            "    (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta_backfill', ?, 'delivery_update', ?::jsonb, 'pending', now()) " +
            "RETURNING id",
            rs -> rs.next() ? rs.getLong("id") : null,
            tenantId, payloadJson));

        if (webhookEventId == null) {
            log.error("Backfill: webhook_events INSERT returned no id for {}", trackingNumber);
            return false;
        }

        final long eventId = webhookEventId;
        jobScheduler.enqueue(() -> webhookJob.process(eventId, tenantId));
        log.debug("Backfill: enqueued event {} for {}", webhookEventId, trackingNumber);
        return true;
    }
}
