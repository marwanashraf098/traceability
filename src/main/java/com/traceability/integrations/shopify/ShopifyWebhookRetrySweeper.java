package com.traceability.integrations.shopify;

import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Retries Shopify webhook events (2026-10-04, V136), every 2 minutes:
 *   - failed events whose next_retry_at is due (set by ShopifyWebhookProcessorJob on a failure, with
 *     backoff, until shopify.webhook.retry.max-attempts);
 *   - events never processed and never failed (their enqueue was lost or timed out, or JobRunr's own
 *     retries of a no-connection failure ran out) once older than shopify.webhook.retry.stale-minutes
 *     (3 since the 2026-11-21 incident; was 30) and within shopify.webhook.retry.window-hours (48) — and only when
 *     no JobRunr job for the event is still live (enqueued / scheduled / processing: {@link #NO_LIVE_JOB}), so a
 *     busy queue is never double-enqueued.
 * The watchdog calls {@link #sweepNow()} too, off JobRunr, when a few events are stuck while jobs still run.
 * Legacy failures from before V136 (next_retry_at NULL) are not swept — the explicit re-process
 * (ShopifyWebhookReprocessService, dry run first) handles them.
 *
 * Due rows are listed on the owner pool — ids and tenant ids only, the same pattern as the
 * fulfillment-link retry sweeper — then each is claimed under its own tenant (RLS) with a conditional
 * UPDATE that leases it for 30 minutes (next_retry_at), and the processor job is enqueued. The
 * processor applies its ordering safety, so a retried old payload never overwrites newer data.
 */
@Component
public class ShopifyWebhookRetrySweeper {

    private static final Logger log = LoggerFactory.getLogger(ShopifyWebhookRetrySweeper.class);

    static final String DUE_PREDICATE =
        "processed_at IS NULL AND superseded_at IS NULL AND retry_count < ? AND (" +
        "  (process_error IS NOT NULL AND next_retry_at IS NOT NULL AND next_retry_at <= now()) OR " +
        "  (process_error IS NULL AND received_at < now() - (? * INTERVAL '1 minute') " +
        "     AND received_at > now() - (? * INTERVAL '1 hour') " +
        "     AND (next_retry_at IS NULL OR next_retry_at <= now())))";

    /**
     * No live JobRunr job for event {@code e} (owner pool only — app_user can't read jobrunr_jobs). The processor's
     * job JSON carries the event id as its first argument; live = not finished. Only the few unfinished rows are
     * read (JobRunr's state index), never the history.
     */
    static final String NO_LIVE_JOB =
        "NOT EXISTS (SELECT 1 FROM jobrunr_jobs j WHERE j.state IN ('AWAITING', 'SCHEDULED', 'ENQUEUED', 'PROCESSING') " +
        "  AND j.jobsignature LIKE 'com.traceability.integrations.shopify.ShopifyWebhookProcessorJob.process(%' " +
        "  AND strpos(j.jobasjson, e.id::text) > 0)";

    private final JdbcTemplate ownerJdbc;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JobScheduler jobScheduler;
    private final ShopifyWebhookProcessorJob processor;
    private final int maxAttempts;
    private final int staleMinutes;
    private final int windowHours;

    public ShopifyWebhookRetrySweeper(@FlywayDataSource DataSource ownerDs, JdbcTemplate jdbc,
                                      PlatformTransactionManager txm, JobScheduler jobScheduler,
                                      ShopifyWebhookProcessorJob processor,
                                      @Value("${shopify.webhook.retry.max-attempts:5}") int maxAttempts,
                                      @Value("${shopify.webhook.retry.stale-minutes:3}") int staleMinutes,
                                      @Value("${shopify.webhook.retry.window-hours:48}") int windowHours) {
        this.ownerJdbc = new JdbcTemplate(ownerDs);
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.jobScheduler = jobScheduler;
        this.processor = processor;
        this.maxAttempts = maxAttempts;
        this.staleMinutes = staleMinutes;
        this.windowHours = windowHours;
    }

    @Recurring(id = "shopify-webhook-retry", cron = "*/2 * * * *", zoneId = "UTC")
    @Job(name = "Shopify webhook retry sweep")
    public void sweep() {
        sweepNow();
    }

    /** One sweep; the number of events re-enqueued. Also called by the watchdog (its own thread, not JobRunr). */
    public int sweepNow() {
        List<Map<String, Object>> due = ownerJdbc.queryForList(
            "SELECT e.id, e.tenant_id FROM shopify_webhook_events e WHERE " + DUE_PREDICATE + " AND " + NO_LIVE_JOB +
            " ORDER BY e.received_at LIMIT 200", maxAttempts, staleMinutes, windowHours);
        int enqueued = 0;
        for (Map<String, Object> row : due) {
            UUID id = (UUID) row.get("id");
            UUID tenantId = (UUID) row.get("tenant_id");
            try {
                if (claim(id, tenantId)) {
                    jobScheduler.enqueue(() -> processor.process(id, tenantId));
                    enqueued++;
                }
            } catch (RuntimeException e) {
                log.warn("Shopify webhook retry: could not re-enqueue event {}: {}", id, e.toString());
            }
        }
        if (enqueued > 0) log.info("Shopify webhook retry sweep: {} event(s) re-enqueued", enqueued);
        return enqueued;
    }

    /**
     * Claims one due event under its tenant: leases it (30 min for a failed one; 10 min for a never-processed one —
     * the live-job check already keeps a queued one from being taken twice) so the next sweep doesn't take it again
     * while it's queued, and counts an attempt for a never-processed one (a failed one was counted when it failed).
     * True when this call claimed it.
     */
    boolean claim(UUID id, UUID tenantId) {
        Integer n = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.update(
            "UPDATE shopify_webhook_events SET " +
            "  retry_count = retry_count + CASE WHEN process_error IS NULL THEN 1 ELSE 0 END, " +
            "  next_retry_at = now() + CASE WHEN process_error IS NULL THEN INTERVAL '10 minutes' ELSE INTERVAL '30 minutes' END " +
            "WHERE id = ? AND tenant_id = ? AND " + DUE_PREDICATE,
            id, tenantId, maxAttempts, staleMinutes, windowHours)));
        return n != null && n > 0;
    }
}
