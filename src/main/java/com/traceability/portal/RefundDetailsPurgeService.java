package com.traceability.portal;

import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Returns portal P2 — removes customers' refund details {@link #RETENTION_DAYS} days after their
 * request ended: refunded (refunded_at), closed (closed_at), rejected (decided_at) or exchanged
 * (its 'exchanged' event). Only the encrypted blob goes; the method and the hint stay and
 * refund_details_purged_at is stamped. Runs as app_user under the tenant (RLS), one transaction
 * per tenant; idempotent.
 */
@Service
public class RefundDetailsPurgeService {

    public static final int RETENTION_DAYS = 30;

    /** When a request stopped needing the details, by its final status; NULL while it's still open. */
    static final String ENDED_AT_SQL =
        "(CASE rr.status::text " +
        "   WHEN 'refunded' THEN rr.refunded_at " +
        "   WHEN 'closed'   THEN rr.closed_at " +
        "   WHEN 'rejected' THEN rr.decided_at " +
        "   WHEN 'exchanged' THEN (SELECT max(e.occurred_at) FROM return_request_events e " +
        "                          WHERE e.request_id = rr.id AND e.tenant_id = rr.tenant_id " +
        "                            AND e.event_type = 'exchanged') " +
        " END)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public RefundDetailsPurgeService(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    /** @return how many requests had their details removed. */
    public int purgeTenant(UUID tenantId) {
        Integer n = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.update(
            "UPDATE return_requests rr SET refund_details_encrypted = NULL, refund_details_purged_at = now() " +
            "WHERE rr.tenant_id = ? AND rr.refund_details_encrypted IS NOT NULL " +
            "  AND " + ENDED_AT_SQL + " < now() - (interval '1 day' * ?)",
            tenantId, RETENTION_DAYS)));
        return n == null ? 0 : n;
    }
}
