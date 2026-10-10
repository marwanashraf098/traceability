package com.traceability.portal;

import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Returns portal P3 — photo housekeeping. Each run lists tenant ids only, on the owner pool (the
 * ReturnPickupBookingSweepJob / RefundDetailsPurgeJob pattern); the work runs per tenant in
 * TenantContext.runAs under RLS (PortalPhotoService). Logs counts only, never content.
 *   hourly — unclaimed uploads older than an hour are deleted (rows and bytes);
 *   daily  — claimed photos lose their bytes 90 days after the request ended.
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ReturnPhotoJobs {

    private static final Logger log = LoggerFactory.getLogger(ReturnPhotoJobs.class);

    private final PortalPhotoService photos;
    private final JdbcTemplate ownerJdbc;

    public ReturnPhotoJobs(PortalPhotoService photos, @FlywayDataSource DataSource ownerDs) {
        this.photos = photos;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
    }

    @Recurring(id = "return-photos-expire-unclaimed", cron = "17 * * * *")
    @Job(name = "Return photos: expire unclaimed uploads")
    public void expireUnclaimed() {
        run("expired", "SELECT DISTINCT tenant_id FROM return_request_photos WHERE request_id IS NULL", photos::expireUnclaimed);
    }

    @Recurring(id = "return-photos-retention", cron = "50 3 * * *", zoneId = "Africa/Cairo")
    @Job(name = "Return photos: 90-day retention")
    public void purgeEnded() {
        run("purged", "SELECT DISTINCT tenant_id FROM return_request_photos WHERE request_id IS NOT NULL AND asset_id IS NOT NULL",
            photos::purgeEnded);
    }

    private void run(String what, String tenantsSql, ToIntFunction<UUID> perTenant) {
        List<UUID> tenants = ownerJdbc.queryForList(tenantsSql, UUID.class);
        for (UUID tenantId : tenants) {
            try {
                int n = perTenant.applyAsInt(tenantId);
                if (n > 0) log.info("Return photos {} tenant={} photos={}", what, tenantId, n);
            } catch (Exception e) {
                log.warn("Return photos {} failed for tenant {}: {}", what, tenantId, e.getClass().getSimpleName());
            }
        }
    }
}
