package com.traceability.inventory;

import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Feature A — at startup, enqueues {@link LocationAddressBackfillJob} when any tenant is still
 * eligible, under one deterministic job id per Cairo day and mode (same pattern as
 * CatalogBackfillTrigger). Mode {@code traced.location-address-backfill.mode}
 * (TRACED_LOCATION_ADDRESS_BACKFILL_MODE): {@code dry-run} (DEFAULT — reads only, never edits),
 * {@code write}, or {@code off}. Anything else reads as dry-run. Everything is caught: an
 * ApplicationReadyEvent listener that throws would abort the application.
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class LocationAddressBackfillTrigger {

    private static final Logger log = LoggerFactory.getLogger(LocationAddressBackfillTrigger.class);
    static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

    private final JdbcTemplate ownerJdbc;
    private final JobScheduler jobScheduler;
    private final String mode;
    private final Clock clock;

    @Autowired
    public LocationAddressBackfillTrigger(@FlywayDataSource DataSource ownerDs, JobScheduler jobScheduler,
                                          @Value("${traced.location-address-backfill.mode:dry-run}") String mode) {
        this(new JdbcTemplate(ownerDs), jobScheduler, mode, Clock.system(CAIRO));
    }

    LocationAddressBackfillTrigger(JdbcTemplate ownerJdbc, JobScheduler jobScheduler, String mode, Clock clock) {
        this.ownerJdbc    = ownerJdbc;
        this.jobScheduler = jobScheduler;
        this.mode         = mode == null ? "dry-run" : mode.trim().toLowerCase();
        this.clock        = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            enqueueIfNeeded();
        } catch (Exception e) {
            log.error("Could not check or enqueue the location address backfill at startup — continuing", e);
        }
    }

    /** True when an enqueue was requested (a no-op in JobRunr if the id already exists). */
    boolean enqueueIfNeeded() {
        if ("off".equals(mode)) {
            log.info("Location address backfill off (traced.location-address-backfill.mode=off) — not enqueued");
            return false;
        }
        boolean dryRun = !"write".equals(mode);
        Boolean pending = ownerJdbc.queryForObject(
            "SELECT EXISTS (" + LocationAddressBackfillJob.ELIGIBLE_TENANTS + ")", Boolean.class);
        if (!Boolean.TRUE.equals(pending)) return false;
        LocalDate today = LocalDate.now(clock.withZone(CAIRO));
        UUID jobId = LocationAddressBackfillJob.jobIdFor(today, dryRun);
        jobScheduler.<LocationAddressBackfillJob>enqueue(jobId, job -> job.run(dryRun));
        log.info("Location address backfill ({}) enqueue requested for {} (job {})",
            dryRun ? "DRY RUN" : "WRITE", today, jobId);
        return true;
    }
}
