package com.traceability.demo;

import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * FR-DEMO — recurring re-seed of the shared demo tenant, every 30 minutes.
 *
 * ensureBootstrapped() is cheap (one SELECT) once the tenant exists, so calling it on
 * every tick rather than only at application startup keeps this job self-contained: the
 * very first tick bootstraps the tenant, owner, and workers, then reseed() populates the
 * golden fixture into what is otherwise an empty tenant. Every tick after that,
 * ensureBootstrapped() no-ops and reseed() clears whatever demo visitors mutated and
 * reloads pristine data — literally the same reseed() method both times.
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DemoReseedJob {

    private static final Logger log = LoggerFactory.getLogger(DemoReseedJob.class);

    private final DemoSeeder demoSeeder;

    public DemoReseedJob(DemoSeeder demoSeeder) {
        this.demoSeeder = demoSeeder;
    }

    @Recurring(id = "demo-tenant-reseed", cron = "*/30 * * * *", zoneId = "Africa/Cairo")
    @Job(name = "Demo tenant re-seed")
    public void run() {
        demoSeeder.ensureBootstrapped();
        demoSeeder.reseed();
        log.info("Demo tenant reseed tick complete");
    }
}
