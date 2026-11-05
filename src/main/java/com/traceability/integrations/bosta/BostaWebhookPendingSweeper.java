package com.traceability.integrations.bosta;

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
 * Re-enqueues Bosta webhook events left 'pending' (2026-11-05, V205), every 5 minutes (UTC).
 *
 * <p>A row is due when it is still 'pending' more than {@code stale-minutes} (10) after it was received,
 * within {@code window-hours} (48), re-enqueued fewer than {@code max-sweeps} (3) times, not leased in the
 * last 30 min, and not waiting on a rate-limit reschedule (rate_limited_until in the future, or within the
 * last {@code stale-minutes} — BostaWebhookJob's own JobRunr schedule owns those). Such a row's enqueue was
 * lost: the endpoint's 2 s guard timed out or failed, or JobRunr's server was down.
 *
 * <p>Same shape as ShopifyWebhookRetrySweeper: due rows are listed on the owner pool (ids and tenant ids
 * only), each is claimed under its own tenant (RLS) with a conditional UPDATE that takes the lease and counts
 * the sweep, and only then is the job enqueued. BostaWebhookJob.process reads the row only while it is still
 * 'pending' and dedups by the payload's idempotency key, so a late original enqueue landing too is a no-op.
 */
@Component
public class BostaWebhookPendingSweeper {

    private static final Logger log = LoggerFactory.getLogger(BostaWebhookPendingSweeper.class);

    static final String DUE_PREDICATE =
        "status = 'pending' AND tenant_id IS NOT NULL " +
        "AND received_at < now() - (? * INTERVAL '1 minute') " +
        "AND received_at > now() - (? * INTERVAL '1 hour') " +
        "AND sweep_count < ? " +
        "AND (sweep_enqueued_at IS NULL OR sweep_enqueued_at < now() - INTERVAL '30 minutes') " +
        "AND (rate_limited_until IS NULL OR rate_limited_until < now() - (? * INTERVAL '1 minute'))";

    private final JdbcTemplate ownerJdbc;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JobScheduler jobScheduler;
    private final BostaWebhookJob webhookJob;
    private final int staleMinutes;
    private final int windowHours;
    private final int maxSweeps;

    public BostaWebhookPendingSweeper(@FlywayDataSource DataSource ownerDs, JdbcTemplate jdbc,
                                      PlatformTransactionManager txm, JobScheduler jobScheduler,
                                      BostaWebhookJob webhookJob,
                                      @Value("${bosta.webhook.pending-sweep.stale-minutes:10}") int staleMinutes,
                                      @Value("${bosta.webhook.pending-sweep.window-hours:48}") int windowHours,
                                      @Value("${bosta.webhook.pending-sweep.max-sweeps:3}") int maxSweeps) {
        this.ownerJdbc = new JdbcTemplate(ownerDs);
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.jobScheduler = jobScheduler;
        this.webhookJob = webhookJob;
        this.staleMinutes = staleMinutes;
        this.windowHours = windowHours;
        this.maxSweeps = maxSweeps;
    }

    @Recurring(id = "bosta-webhook-pending-sweep", cron = "*/5 * * * *", zoneId = "UTC")
    @Job(name = "Bosta webhook pending sweep")
    public void sweep() {
        List<Map<String, Object>> due = ownerJdbc.queryForList(
            "SELECT id, tenant_id FROM webhook_events WHERE " + DUE_PREDICATE + " ORDER BY received_at LIMIT 200",
            staleMinutes, windowHours, maxSweeps, staleMinutes);
        int enqueued = 0;
        for (Map<String, Object> row : due) {
            long id = ((Number) row.get("id")).longValue();
            UUID tenantId = (UUID) row.get("tenant_id");
            try {
                if (claim(id, tenantId)) {
                    jobScheduler.enqueue(() -> webhookJob.process(id, tenantId));
                    enqueued++;
                }
            } catch (RuntimeException e) {
                log.warn("Bosta webhook pending sweep: could not re-enqueue event {}: {}", id, e.toString());
            }
        }
        if (enqueued > 0) log.info("Bosta webhook pending sweep: {} event(s) re-enqueued", enqueued);
    }

    /** Takes the 30-min lease and counts the sweep, under the row's tenant. True when this call claimed it. */
    boolean claim(long id, UUID tenantId) {
        Integer n = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.update(
            "UPDATE webhook_events SET sweep_count = sweep_count + 1, sweep_enqueued_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND " + DUE_PREDICATE,
            id, tenantId, staleMinutes, windowHours, maxSweeps, staleMinutes)));
        return n != null && n > 0;
    }
}
