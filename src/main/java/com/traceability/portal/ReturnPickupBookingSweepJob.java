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
 * Returns portal Step 4c-3 — every 10 minutes, {@link ReturnPickupBookingService#sweepTenant}
 * for each tenant with Bosta pickup booking switched on. The tenant list is a cross-tenant
 * read of tenant ids only, on the owner pool — the same pattern BostaStatusPollJob uses for
 * its active-account list; all per-tenant work then runs in TenantContext.runAs under RLS.
 * Same background-server gate as the other recurring jobs (tests call sweepTenant directly).
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ReturnPickupBookingSweepJob {

    private static final Logger log = LoggerFactory.getLogger(ReturnPickupBookingSweepJob.class);

    private final ReturnPickupBookingService booking;
    private final JdbcTemplate ownerJdbc;

    public ReturnPickupBookingSweepJob(ReturnPickupBookingService booking, @FlywayDataSource DataSource ownerDs) {
        this.booking   = booking;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
    }

    @Recurring(id = "return-pickup-booking-sweep", cron = "*/10 * * * *")
    @Job(name = "Return pickup booking sweep")
    public void run() {
        List<UUID> tenants = ownerJdbc.queryForList(
            "SELECT id FROM tenants WHERE portal_pickup_booking", UUID.class);
        for (UUID tenantId : tenants) {
            try {
                ReturnPickupBookingService.SweepResult r = booking.sweepTenant(tenantId);
                if (r.enqueued() + r.markedAmbiguous() + r.verifyAttempts() > 0) {
                    log.info("Return pickup sweep tenant={} enqueued={} markedAmbiguous={} verifyAttempts={}",
                        tenantId, r.enqueued(), r.markedAmbiguous(), r.verifyAttempts());
                }
            } catch (Exception e) {
                log.warn("Return pickup sweep failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }
}
