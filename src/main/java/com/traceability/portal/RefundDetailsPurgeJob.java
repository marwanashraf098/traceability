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

/**
 * Returns portal P2 — daily, {@link RefundDetailsPurgeService#purgeTenant} for each tenant that
 * still holds refund details. The tenant list is a cross-tenant read of tenant ids only, on the
 * owner pool — the same pattern as ReturnPickupBookingSweepJob; all per-tenant work then runs in
 * TenantContext.runAs under RLS. Logs counts only, never content.
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RefundDetailsPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RefundDetailsPurgeJob.class);

    private final RefundDetailsPurgeService purge;
    private final JdbcTemplate ownerJdbc;

    public RefundDetailsPurgeJob(RefundDetailsPurgeService purge, @FlywayDataSource DataSource ownerDs) {
        this.purge = purge;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
    }

    @Recurring(id = "refund-details-purge", cron = "45 3 * * *", zoneId = "Africa/Cairo")
    @Job(name = "Refund details purge")
    public void run() {
        List<UUID> tenants = ownerJdbc.queryForList(
            "SELECT DISTINCT tenant_id FROM return_requests WHERE refund_details_encrypted IS NOT NULL", UUID.class);
        for (UUID tenantId : tenants) {
            try {
                int n = purge.purgeTenant(tenantId);
                if (n > 0) log.info("Refund details purged tenant={} requests={}", tenantId, n);
            } catch (Exception e) {
                log.warn("Refund details purge failed for tenant {}: {}", tenantId, e.getClass().getSimpleName());
            }
        }
    }
}
