package com.traceability.integrations.shopify;

import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hourly sweep: deletes shopify_oauth_state rows older than 1 hour.
 *
 * shopify_oauth_state is intentionally NOT under tenant RLS (see V13 migration).
 * This job runs with NO TenantContext set — do not wrap it in TenantContext.runAs().
 * The table is non-RLS so no GUC is needed; the TenantAwareDataSource simply skips
 * the set_config call when TenantContext.get() is null.
 *
 * The 1-hour window is a conservative retention ceiling: the application enforces
 * a 10-minute TTL on nonce consumption (consumeState), so any row older than 1h
 * is guaranteed to be either consumed or irrecoverably expired.
 *
 * As of V87, the DELETE itself goes through purge_expired_shopify_oauth_state
 * (SECURITY DEFINER, 10th hatch) — app_user has no direct DELETE on this table any
 * more (INSERT only, like the other two locked-down credential tables). Deliberately
 * unscoped by tenant, same as before: one sweep for every tenant's (and every Path-2
 * null-tenant's) stale rows, no per-tenant GUC exists for this job to filter by anyway.
 */
// Only registered as a Spring bean (and thus as a JobRunr recurring job) when the
// background-job-server is enabled. Tests set enabled=false, which prevents the
// RecurringJobPostProcessor from trying to save the recurring job definition to the
// storage layer — a call that fails with NPE in contexts that don't mock the scheduler.
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ShopifyStateCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ShopifyStateCleanupJob.class);

    private final JdbcTemplate jdbc;

    public ShopifyStateCleanupJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Recurring(id = "shopify-state-cleanup", cron = "0 * * * *")
    @Job(name = "Shopify OAuth state cleanup")
    public void purgeExpiredStates() {
        Integer deleted = jdbc.queryForObject("SELECT purge_expired_shopify_oauth_state()", Integer.class);
        log.info("Shopify OAuth state cleanup: {} stale nonce(s) deleted", deleted);
    }
}
