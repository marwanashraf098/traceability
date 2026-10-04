package com.traceability.inventory;

import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaRateLimitException;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * FR-4.6 prerequisite, after commit (2026-10-04, B1): fetches a newly linked shipment's Bosta {@code _id}
 * into shipments.provider_delivery_id. Before, ShipmentLinkService made this Bosta call inside the AWB-scan /
 * exchange-mapping transaction, holding a DB connection through the rate limiter and the HTTP call.
 *
 * Enqueued after the link commits. Already filled → nothing. A rate limit reschedules the job (backoff,
 * at most {@link #MAX_RATE_LIMIT_RESCHEDULES} times) — it never sleeps in the worker. Any other failure,
 * no _id, or no active account → provider_id_fetch_failed = true (the missing_provider_id exception, as before).
 */
@Component
public class ProviderDeliveryIdJob {

    private static final Logger log = LoggerFactory.getLogger(ProviderDeliveryIdJob.class);
    static final int MAX_RATE_LIMIT_RESCHEDULES = 6;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BostaGateway bostaGateway;
    private final EncryptionService encryptionService;
    private final JobScheduler jobScheduler;

    public ProviderDeliveryIdJob(JdbcTemplate jdbc, PlatformTransactionManager txm, BostaGateway bostaGateway,
                                 EncryptionService encryptionService, JobScheduler jobScheduler) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.bostaGateway = bostaGateway;
        this.encryptionService = encryptionService;
        this.jobScheduler = jobScheduler;
    }

    @Job(name = "Bosta provider id — %2", retries = 0)
    public void fetch(UUID tenantId, UUID shipmentId, String trackingNumber, int rateLimitReschedules) {
        TenantContext.runAs(tenantId, (Runnable) () -> {
            String existing = tx.execute(s -> jdbc.query(
                "SELECT provider_delivery_id FROM shipments WHERE id = ? AND tenant_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, shipmentId, tenantId));
            if (existing != null) return;
            String encryptedKey = tx.execute(s -> jdbc.query(
                "SELECT api_key_encrypted FROM courier_accounts " +
                "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, tenantId));
            if (encryptedKey == null) {
                log.warn("No active Bosta account for tenant {} — cannot fetch provider_delivery_id for {}", tenantId, trackingNumber);
                markFailed(tenantId, shipmentId);
                return;
            }
            BostaDelivery delivery;
            try {
                delivery = bostaGateway.fetchDelivery(encryptionService.decrypt(encryptedKey), trackingNumber);
            } catch (BostaRateLimitException e) {
                if (rateLimitReschedules < MAX_RATE_LIMIT_RESCHEDULES) {
                    long delay = Math.max(e.getRetryAfterSeconds(), 60L << Math.min(rateLimitReschedules, 5))
                        + ThreadLocalRandom.current().nextLong(31);
                    final int next = rateLimitReschedules + 1;
                    jobScheduler.schedule(Instant.now().plusSeconds(delay),
                        () -> fetch(tenantId, shipmentId, trackingNumber, next));
                    log.info("provider_delivery_id for {}: rate limited — rescheduled in {}s", trackingNumber, delay);
                } else {
                    markFailed(tenantId, shipmentId);
                }
                return;
            } catch (Exception e) {
                log.warn("fetchDelivery for {} failed — provider_delivery_id stays NULL: {}", trackingNumber, e.toString());
                markFailed(tenantId, shipmentId);
                return;
            }
            String bostaId = delivery == null || delivery.raw() == null ? null : delivery.raw().path("_id").asText(null);
            if (bostaId == null || bostaId.isBlank()) {
                log.warn("fetchDelivery for {} returned no _id — setting fetch-failed flag", trackingNumber);
                markFailed(tenantId, shipmentId);
                return;
            }
            tx.execute(s -> jdbc.update(
                "UPDATE shipments SET provider_delivery_id = ?, provider_id_fetch_failed = false WHERE id = ? AND tenant_id = ?",
                bostaId, shipmentId, tenantId));
        });
    }

    /** Sets the fetch-failed flag (the missing_provider_id exception). Also used when the enqueue itself fails. */
    public void markFailed(UUID tenantId, UUID shipmentId) {
        TenantContext.runAs(tenantId, (Runnable) () -> tx.execute(s -> jdbc.update(
            "UPDATE shipments SET provider_id_fetch_failed = true WHERE id = ? AND tenant_id = ?", shipmentId, tenantId)));
    }
}
