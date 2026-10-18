package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
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
 * Merged-PDF slice B — every minute: fails print jobs not updated for 10 minutes
 * ({@link PrintJobStore#sweepStuck}) and deletes PDF bytes past their 24 h expiry
 * ({@link PrintJobStore#deleteExpiredPdfs} — the parts and the final PDF). The tenant list is a cross-tenant read of tenant ids only,
 * on the owner pool — the RefundDetailsPurgeJob pattern; every write runs per tenant in
 * TenantContext.runAs under RLS. Logs counts only.
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PrintJobSweeper {

    private static final Logger log = LoggerFactory.getLogger(PrintJobSweeper.class);

    private final PrintJobStore store;
    private final JdbcTemplate  ownerJdbc;

    public PrintJobSweeper(PrintJobStore store, @FlywayDataSource DataSource ownerDs) {
        this.store     = store;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
    }

    @Recurring(id = "print-jobs-sweep", cron = "* * * * *")
    @Job(name = "Print jobs sweep")
    public void run() {
        List<UUID> tenants = ownerJdbc.queryForList(
            "SELECT DISTINCT j.tenant_id FROM pack_print_jobs j " +
            "WHERE (j.status IN ('queued', 'running') " +
            "       AND j.updated_at < now() - make_interval(mins => " + PrintJobStore.STUCK_AFTER_MINUTES + ")) " +
            "   OR (j.expires_at < now() AND (j.pdf IS NOT NULL OR EXISTS " +
            "       (SELECT 1 FROM pack_print_job_parts p WHERE p.job_id = j.id)))", UUID.class);
        for (UUID tenantId : tenants) {
            try {
                sweepTenant(tenantId);
            } catch (Exception e) {
                log.warn("Print jobs sweep failed for tenant {}: {}", tenantId, e.getClass().getSimpleName());
            }
        }
    }

    /** One tenant's stuck jobs and expired PDFs (tests call this directly). */
    public void sweepTenant(UUID tenantId) {
        int stuck = TenantContext.runAs(tenantId, store::sweepStuck);
        int expired = TenantContext.runAs(tenantId, store::deleteExpiredPdfs);
        if (stuck + expired > 0) log.info("Print jobs sweep tenant={} stuck={} pdfsDeleted={}", tenantId, stuck, expired);
    }
}
