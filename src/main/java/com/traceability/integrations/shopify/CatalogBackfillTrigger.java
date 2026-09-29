package com.traceability.integrations.shopify;

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

/**
 * At startup, enqueues CatalogBackfillJob once when any connected store still has no
 * catalog_backfilled_at — under the fixed CatalogBackfillJob.JOB_ID, so repeated starts
 * collapse into one job: JobRunr 7.3 treats an enqueue under an id that already exists as a
 * no-op (AbstractJobScheduler.saveJob swallows the ConcurrentJobModificationException), in any
 * state — so after a job has ended FAILED, a later start doesn't re-enqueue it until that job is
 * deleted in the JobRunr dashboard. Kill switch: traced.catalog-backfill.enabled.
 *
 * FAIL-SOFT at startup (same pattern as BostaDistrictsRefreshJob): an exception escaping an
 * ApplicationReadyEvent listener aborts the whole application — everything here is caught.
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CatalogBackfillTrigger {

    private static final Logger log = LoggerFactory.getLogger(CatalogBackfillTrigger.class);

    private final JdbcTemplate ownerJdbc;
    private final JobScheduler jobScheduler;
    private final boolean enabled;

    @Autowired
    public CatalogBackfillTrigger(@FlywayDataSource DataSource ownerDs,
                                  JobScheduler jobScheduler,
                                  @Value("${traced.catalog-backfill.enabled:true}") boolean enabled) {
        this(new JdbcTemplate(ownerDs), jobScheduler, enabled);
    }

    CatalogBackfillTrigger(JdbcTemplate ownerJdbc, JobScheduler jobScheduler, boolean enabled) {
        this.ownerJdbc    = ownerJdbc;
        this.jobScheduler = jobScheduler;
        this.enabled      = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            enqueueIfNeeded();
        } catch (Exception e) {
            log.error("Could not check or enqueue the catalog backfill at startup — continuing", e);
        }
    }

    /** True when an enqueue was requested (a no-op in JobRunr if the id already exists). */
    boolean enqueueIfNeeded() {
        if (!enabled) {
            log.info("Catalog backfill disabled (traced.catalog-backfill.enabled=false) — not enqueued");
            return false;
        }
        Boolean pending = ownerJdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM stores WHERE status = 'connected' AND catalog_backfilled_at IS NULL)",
            Boolean.class);
        if (!Boolean.TRUE.equals(pending)) return false;
        jobScheduler.<CatalogBackfillJob>enqueue(CatalogBackfillJob.JOB_ID, job -> job.run());
        log.info("Catalog backfill enqueue requested (job {} — kept as is if it already exists)", CatalogBackfillJob.JOB_ID);
        return true;
    }
}
