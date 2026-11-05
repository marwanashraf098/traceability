package com.traceability.places;

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
 * Warehouse places S5 — hourly (minute 25): cancels one-place counts with no scan for {@link PlaceCountService#IDLE_HOURS}
 * h (summary {"reason":"idle"}). A cancelled count changes nothing on any piece. Same shape as PutAwayIdleCloseJob:
 * elapsed time on timestamptz, a cross-tenant read of tenant ids only on the owner pool, every per-tenant write in
 * TenantContext.runAs under RLS (PlaceCountService.cancelIdle).
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PlaceCountIdleCancelJob {

    private static final Logger log = LoggerFactory.getLogger(PlaceCountIdleCancelJob.class);

    private final PlaceCountService counts;
    private final JdbcTemplate ownerJdbc;

    public PlaceCountIdleCancelJob(PlaceCountService counts, @FlywayDataSource DataSource ownerDs) {
        this.counts = counts;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
    }

    @Recurring(id = "place-count-idle-cancel", cron = "25 * * * *", zoneId = "UTC")
    @Job(name = "Cancel idle place counts")
    public void run() {
        List<UUID> tenants = ownerJdbc.queryForList(
            "SELECT DISTINCT tenant_id FROM place_counts WHERE status = 'open' " +
            "AND last_activity_at < now() - (interval '1 hour' * ?)", UUID.class, PlaceCountService.IDLE_HOURS);
        for (UUID tenantId : tenants) processTenant(tenantId);
    }

    /** One tenant's pass (public for the tests; run() is the scheduled entry). */
    public List<UUID> processTenant(UUID tenantId) {
        try {
            List<UUID> cancelled = TenantContext.runAs(tenantId, counts::cancelIdle);
            if (!cancelled.isEmpty()) {
                log.info("Cancelled {} idle place count(s) for tenant {} (idle > {} h)",
                    cancelled.size(), tenantId, PlaceCountService.IDLE_HOURS);
            }
            return cancelled;
        } catch (Exception e) {
            log.warn("Place count idle cancel failed for tenant {}: {}", tenantId, e.getMessage());
            return List.of();
        }
    }
}
