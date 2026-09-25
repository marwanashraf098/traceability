package com.traceability.integrations.bosta;

import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Returns portal Step 4c-2 — schedules {@link BostaDistrictsRefreshService#refresh()}: daily,
 * plus once at startup when bosta_districts is empty (enqueued, so startup never waits on
 * Bosta). Same background-server gate as the other recurring jobs, so tests (server off)
 * call the service directly instead.
 *
 * FAIL-SOFT at startup: an exception escaping an ApplicationReadyEvent listener aborts the
 * whole application (see DemoBootstrapStartupListener) — everything here is caught and logged.
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class BostaDistrictsRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(BostaDistrictsRefreshJob.class);

    private final BostaDistrictsRefreshService refresh;
    private final JobScheduler jobScheduler;

    public BostaDistrictsRefreshJob(BostaDistrictsRefreshService refresh, JobScheduler jobScheduler) {
        this.refresh      = refresh;
        this.jobScheduler = jobScheduler;
    }

    @Recurring(id = "bosta-districts-refresh", cron = "0 4 * * *", zoneId = "Africa/Cairo")
    @Job(name = "Bosta districts refresh")
    public void run() {
        refresh.refresh();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            if (refresh.isEmpty()) {
                jobScheduler.<BostaDistrictsRefreshJob>enqueue(job -> job.run());
                log.info("Bosta districts table is empty — refresh enqueued");
            }
        } catch (Exception e) {
            log.error("Could not check or enqueue the Bosta districts refresh at startup — continuing", e);
        }
    }
}
