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
 * Warehouse places S2 — hourly (minute 20): closes put-away sessions with no scan or resume for
 * {@link PutAwayService#IDLE_HOURS} h, close_reason 'idle', closed_by NULL (the system), with the session's summary
 * recorded. Placed pieces stay placed — closing a session never moves a piece. The idle test is elapsed time on
 * timestamptz (now() - interval), never a wall-clock hour, so it is the same in Africa/Cairo, across DST and in UTC.
 *
 * The tenant list is a cross-tenant read of tenant ids only, on the owner pool (TransferShopifySweepJob's pattern);
 * every per-tenant read and write runs in TenantContext.runAs under RLS (PutAwayService.closeIdle).
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PutAwayIdleCloseJob {

    private static final Logger log = LoggerFactory.getLogger(PutAwayIdleCloseJob.class);

    private final PutAwayService putAway;
    private final JdbcTemplate ownerJdbc;

    public PutAwayIdleCloseJob(PutAwayService putAway, @FlywayDataSource DataSource ownerDs) {
        this.putAway = putAway;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
    }

    @Recurring(id = "put-away-idle-close", cron = "20 * * * *")
    @Job(name = "Close idle put-away sessions")
    public void run() {
        List<UUID> tenants = ownerJdbc.queryForList(
            "SELECT DISTINCT tenant_id FROM put_away_sessions WHERE status = 'open' " +
            "AND last_activity_at < now() - (interval '1 hour' * ?)", UUID.class, PutAwayService.IDLE_HOURS);
        for (UUID tenantId : tenants) processTenant(tenantId);
    }

    /** One tenant's pass (public for the tests; run() is the scheduled entry). */
    public List<UUID> processTenant(UUID tenantId) {
        try {
            List<UUID> closed = TenantContext.runAs(tenantId, putAway::closeIdle);
            if (!closed.isEmpty()) {
                log.info("Closed {} idle put-away session(s) for tenant {} (idle > {} h)",
                    closed.size(), tenantId, PutAwayService.IDLE_HOURS);
            }
            return closed;
        } catch (Exception e) {
            log.warn("Put-away idle close failed for tenant {}: {}", tenantId, e.getMessage());
            return List.of();
        }
    }
}
