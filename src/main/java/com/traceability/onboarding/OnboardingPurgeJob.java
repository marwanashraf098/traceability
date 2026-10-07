package com.traceability.onboarding;

import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Build D — nightly purge of onboarding leftovers through purge_onboarding_artifacts()
 * (SECURITY DEFINER hatch #16, V147): pending links that are expired or consumed (never a live
 * one) and rate-limit rows older than 24 hours. The function takes no parameters, so nothing here
 * can widen its scope. No TenantContext — neither table is tenant-scoped.
 */
// Registered only when the background-job server runs (same reason as ShopifyStateCleanupJob).
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OnboardingPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(OnboardingPurgeJob.class);

    private final JdbcTemplate jdbc;

    public OnboardingPurgeJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Recurring(id = "onboarding-purge", cron = "30 3 * * *", zoneId = "Africa/Cairo")
    @Job(name = "Embedded onboarding purge")
    public void purge() {
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM purge_onboarding_artifacts()");
        log.info("Onboarding purge: {} pending link(s), {} rate-limit row(s) deleted",
            r.get("pending_links_deleted"), r.get("attempts_deleted"));
    }
}
