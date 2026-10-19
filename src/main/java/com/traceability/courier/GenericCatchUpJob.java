package com.traceability.courier;

import com.traceability.integrations.shopify.FulfillmentTrackingCapture;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Non-Bosta couriers G2 — the opt-in switch-on catch-up ({@link GenericCatchUp}, last N days) as a JobRunr job,
 * enqueued by {@link CourierSettingsService} after the switch-on commits — only when the owner chose a window. Runs as app_user under the tenant (RLS), in one
 * transaction; does nothing if the tenant was switched off again before it ran. Idempotent — a retry or a
 * second switch-on creates nothing twice. One audit_log row (system actor) with the count.
 */
@Component
public class GenericCatchUpJob {

    private static final Logger log = LoggerFactory.getLogger(GenericCatchUpJob.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public GenericCatchUpJob(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    @Job(name = "Generic courier catch-up — %0, last %1 days", retries = 2)
    public int run(UUID tenantId, int days) {
        Integer created = TenantContext.runAs(tenantId, () -> tx.execute(s -> {
            if (!GenericShipments.enabled(jdbc, tenantId)) return 0;   // switched off again meanwhile
            int n = GenericCatchUp.run(jdbc, tenantId, days, FulfillmentTrackingCapture::carrierClass);
            jdbc.update(
                "INSERT INTO audit_log (tenant_id, actor_user_id, action, target_type, target_id, metadata) " +
                "VALUES (?, NULL, 'generic_courier_catch_up', 'tenant', ?, ?::jsonb)",
                tenantId, tenantId.toString(), "{\"created\":" + n + ",\"days\":" + days + "}");
            return n;
        }));
        log.info("Generic courier catch-up: tenant {} — {} shipment(s) created", tenantId, created);
        return created == null ? 0 : created;
    }
}
