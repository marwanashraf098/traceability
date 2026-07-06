package com.traceability.integrations.bosta;

import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tier 1 — Status Poll: keeps known in-flight Bosta shipments current.
 *
 * Runs on a short interval (default 3 minutes). For each tenant with an active Bosta
 * courier_account, fetches every non-terminal shipment individually from Bosta's
 * fetchDelivery endpoint and routes the result through the shared ingest pipeline
 * (BostaIngestionHelper → BostaWebhookJob). Idempotency ensures unchanged shipments
 * are a cheap no-op; a changed state produces a new idem key and is processed.
 *
 * Terminal states (delivered, returned, lost, terminated, cancelled) are excluded —
 * they can no longer change and drop naturally out of the poll set as they arrive.
 *
 * Rotation: shipments are ordered by last_polled_at ASC NULLS FIRST so that if
 * in-flight count > max-per-cycle, every shipment is visited in round-robin order
 * and none is starved.
 *
 * TenantContext: each tenant's work runs inside TenantContext.runAs(tenantId) so
 * TenantAwareConnection fires SET LOCAL app.current_tenant for every transaction.
 * The cross-tenant ownerJdbc query to list active tenants bypasses RLS (ownerDs is
 * the Flyway/JobRunr postgres-role datasource).
 *
 * Coexistence: Tier 1 + Tier 2 discovery + manual backfill + live webhook all feed
 * the same idempotent pipeline — no double-processing regardless of source.
 */
@Service
public class BostaStatusPollJob {

    private static final Logger log = LoggerFactory.getLogger(BostaStatusPollJob.class);

    // Non-terminal states that the status poll must watch. Keep in sync with
    // the shipment_internal_state enum and the V35 partial index predicate.
    private static final String TERMINAL_IN_CLAUSE =
        "'delivered','returned','lost','terminated','cancelled'";

    private static final String ACTIVE_BOSTA_TENANTS =
        "SELECT ca.tenant_id, ca.api_key_encrypted " +
        "FROM courier_accounts ca " +
        "WHERE ca.provider = 'bosta' AND ca.status = 'active'";

    private final JdbcTemplate        ownerJdbc;
    private final JdbcTemplate        jdbc;
    private final TransactionTemplate  tx;
    private final EncryptionService    encryptionService;
    private final BostaIngestionHelper ingestionHelper;
    private final int                  maxPerCycle;
    private final long                 interFetchDelayMs;
    private final boolean              statusEnabled;

    // Per-tenant rate-limit backoff: epoch-ms after which the tenant's poll resumes.
    // Rate limits are per API key, so each tenant has its own backoff timer.
    // Reset to empty on app restart (acceptable — the 429 window is short).
    private final Map<UUID, Long> rateLimitRetryUntilByTenant = new ConcurrentHashMap<>();

    public BostaStatusPollJob(
            @FlywayDataSource DataSource ownerDs,
            JdbcTemplate jdbc,
            PlatformTransactionManager txm,
            EncryptionService encryptionService,
            BostaIngestionHelper ingestionHelper,
            @Value("${bosta.poll.status-max-per-cycle:200}") int maxPerCycle,
            @Value("${bosta.poll.inter-fetch-delay-ms:100}") long interFetchDelayMs,
            @Value("${bosta.poll.status-enabled:true}") boolean statusEnabled) {
        this.ownerJdbc      = new JdbcTemplate(ownerDs);
        this.jdbc           = jdbc;
        this.tx             = new TransactionTemplate(txm);
        this.encryptionService = encryptionService;
        this.ingestionHelper = ingestionHelper;
        this.maxPerCycle    = maxPerCycle;
        this.interFetchDelayMs = interFetchDelayMs;
        this.statusEnabled  = statusEnabled;
    }

    // Cron: every 3 minutes (5-field standard cron: minute hour dom month dow).
    // Setting bosta.poll.status-enabled=false makes pollAll() a no-op WITHOUT removing the
    // bean — keeping the bean present ensures JobRunr's recurring-job registry stays up-to-date
    // and doesn't re-fire stale entries from its own database with a different cron.
    @Recurring(id = "bosta-status-poll", cron = "*/3 * * * *")
    @Job(name = "Bosta status poll")
    public void pollAll() {
        if (!statusEnabled) {
            log.info("Status poll disabled via bosta.poll.status-enabled=false — skipping");
            return;
        }
        List<Map<String, Object>> accounts = ownerJdbc.queryForList(ACTIVE_BOSTA_TENANTS);
        if (accounts.isEmpty()) {
            log.debug("Status poll: no active Bosta accounts");
            return;
        }

        for (Map<String, Object> row : accounts) {
            UUID tenantId       = (UUID) row.get("tenant_id");
            String encryptedKey = (String) row.get("api_key_encrypted");

            // Per-tenant rate-limit backoff: skip this tenant until the window clears.
            Long retryUntil = rateLimitRetryUntilByTenant.get(tenantId);
            if (retryUntil != null && System.currentTimeMillis() < retryUntil) {
                long waitSecs = (retryUntil - System.currentTimeMillis()) / 1000;
                log.info("Status poll tenant {}: rate-limited — backing off {}s more", tenantId, waitSecs);
                continue;
            }

            try {
                String apiKey = encryptionService.decrypt(encryptedKey);
                pollTenant(tenantId, apiKey);
            } catch (Exception e) {
                log.warn("Status poll failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    private void pollTenant(UUID tenantId, String apiKey) {
        TenantContext.runAs(tenantId, (Runnable) () -> {

            // Load non-terminal shipments, oldest-checked first (rotation).
            // provider_state is the last known numeric Bosta code — used to skip re-enqueuing
            // if the fetched state hasn't changed (prevents unnecessary webhook_events rows).
            // Runs inside tx.execute() so TenantAwareConnection fires the GUC → RLS applies.
            List<Map<String, Object>> shipments = tx.execute(s -> jdbc.queryForList(
                "SELECT id, tracking_number, provider_state " +
                "FROM shipments " +
                "WHERE tenant_id = ? " +
                "  AND provider = 'bosta' " +
                "  AND tracking_number IS NOT NULL " +
                "  AND internal_state NOT IN (" + TERMINAL_IN_CLAUSE + ") " +
                "ORDER BY last_polled_at ASC NULLS FIRST " +
                "LIMIT ?",
                tenantId, maxPerCycle));

            if (shipments == null || shipments.isEmpty()) {
                log.debug("Status poll tenant {}: no in-flight shipments", tenantId);
                return;
            }

            int seen = 0, enqueued = 0;
            for (Map<String, Object> row : shipments) {
                UUID    shipmentId           = (UUID)    row.get("id");
                String  trackingNumber       = (String)  row.get("tracking_number");
                // provider_state is nullable (NULL = never polled successfully before)
                Integer currentProviderState = (Integer) row.get("provider_state");
                seen++;

                try {
                    if (ingestionHelper.ingestDelivery(
                            tenantId, apiKey, trackingNumber, "bosta_poll",
                            currentProviderState)) {
                        enqueued++;
                    }
                } catch (BostaRateLimitException e) {
                    // Rate-limited: stop fetching ALL remaining shipments for this tenant
                    // (more calls would extend the blackout window). Record the backoff
                    // and return immediately — do NOT update last_polled_at for this
                    // shipment (it will be retried first-in-rotation on the next cycle).
                    long resumeAt = System.currentTimeMillis() + (e.getRetryAfterSeconds() + 10) * 1000L;
                    rateLimitRetryUntilByTenant.put(tenantId, resumeAt);
                    log.warn("Status poll tenant {}: rate-limited by Bosta (retryAfter={}s) — " +
                             "aborting cycle, next attempt after {}s",
                        tenantId, e.getRetryAfterSeconds(), e.getRetryAfterSeconds() + 10);
                    if (seen > 1) {
                        log.info("Status poll tenant {}: {} of {} shipments checked before rate limit",
                            tenantId, seen - 1, shipments.size());
                    }
                    return; // exit the TenantContext.runAs Runnable — breaks out of shipment loop
                } catch (BostaTransientException e) {
                    log.warn("Status poll tenant {}: transient error on {} — skipping: {}",
                        tenantId, trackingNumber, e.getMessage());
                } catch (Exception e) {
                    log.error("Status poll tenant {}: unexpected error on {} — skipping",
                        tenantId, trackingNumber, e);
                }

                // Update last_polled_at regardless of outcome (for rotation).
                final UUID fShipmentId = shipmentId;
                tx.execute(s -> {
                    jdbc.update("UPDATE shipments SET last_polled_at = now() WHERE id = ?",
                        fShipmentId);
                    return null;
                });

                try {
                    if (interFetchDelayMs > 0) Thread.sleep(interFetchDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (enqueued > 0) {
                log.info("Status poll tenant {}: {} checked, {} state changes enqueued",
                    tenantId, seen, enqueued);
            } else {
                log.debug("Status poll tenant {}: {} checked, no changes", tenantId, seen);
            }
        });
    }
}
