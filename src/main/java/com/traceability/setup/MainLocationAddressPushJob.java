package com.traceability.setup;

import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;

/**
 * Onboarding gate — pushes the saved main warehouse address to Shopify through
 * {@link MainLocationAddressPusher}. Enqueued by {@link SetupAddressService#save} only after the
 * address commits. Three short steps, no DB transaction around the pusher call:
 *   1. read the location's current address (and its address_updated_at) under the tenant;
 *   2. call the pusher;
 *   3. record the outcome — only while the address is still the one pushed (a newer save has its own
 *      job): PUSHED → 'pushed'; NOT_LINKED → NULL (nothing to push yet); an exception → 'failed' +
 *      the exception class (never a message or the address), then rethrown so JobRunr retries.
 * A 'failed' row is the LOW alert warehouse_address_push_failed until a later push succeeds. Never
 * blocks the merchant: the gate reads only the address columns, never the push status.
 */
@Component
public class MainLocationAddressPushJob {

    private static final Logger log = LoggerFactory.getLogger(MainLocationAddressPushJob.class);

    private final JdbcTemplate              jdbc;
    private final TransactionTemplate       tx;
    private final MainLocationAddressPusher pusher;

    public MainLocationAddressPushJob(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                      MainLocationAddressPusher pusher) {
        this.jdbc   = jdbc;
        this.tx     = new TransactionTemplate(txm);
        this.pusher = pusher;
    }

    @Job(name = "Push main warehouse address to Shopify — location %1", retries = 8)
    public void push(UUID tenantId, UUID locationId) throws Exception {
        Map<String, Object> row = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.query(
            "SELECT address_province_code, address_city, address_street, address_building, address_floor, " +
            "       address_updated_at " +
            "FROM locations WHERE id = ? AND tenant_id = ? AND address_province_code IS NOT NULL",
            rs -> rs.next() ? Map.<String, Object>of(
                "address", new WarehouseAddress(rs.getString(1), rs.getString(2), rs.getString(3),
                                                rs.getString(4), rs.getString(5)),
                "updatedAt", rs.getTimestamp(6)) : null,
            locationId, tenantId)));
        if (row == null) return;
        WarehouseAddress address = (WarehouseAddress) row.get("address");
        Timestamp updatedAt = (Timestamp) row.get("updatedAt");

        MainLocationAddressPusher.Outcome outcome;
        try {
            outcome = TenantContext.runAs(tenantId, () -> pusher.push(tenantId, locationId, address));
        } catch (RuntimeException e) {
            // runAs wraps a checked exception in a bare RuntimeException — name the real cause.
            Throwable cause = e.getClass() == RuntimeException.class && e.getCause() != null ? e.getCause() : e;
            record(tenantId, locationId, updatedAt, "failed", cause.getClass().getSimpleName());
            log.warn("main warehouse address push failed tenant={} location={} cause={}",
                     tenantId, locationId, cause.getClass().getSimpleName());
            throw e;
        }
        record(tenantId, locationId, updatedAt,
               outcome == MainLocationAddressPusher.Outcome.PUSHED ? "pushed" : null, null);
    }

    private void record(UUID tenantId, UUID locationId, Timestamp updatedAt, String status, String error) {
        TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.update(
            "UPDATE locations SET address_shopify_push_status = ?, address_shopify_push_error = ?, " +
            "       address_shopify_push_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND address_updated_at IS NOT DISTINCT FROM ?",
            status, error, locationId, tenantId, updatedAt)));
    }
}
