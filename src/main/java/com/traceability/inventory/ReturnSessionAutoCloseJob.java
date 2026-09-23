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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

/**
 * Hourly: closes idle open return sessions that have NOTHING pending, so intake completion
 * (ReturnSessionService.close() stamps shipments.return_intake_completed_at) isn't held
 * hostage by a session someone forgot to close.
 *
 * Eligible = status 'open', zero items with disposition 'pending' (empty sessions included),
 * and last activity more than {@link #IDLE_HOURS} ago — last activity is the latest of
 * opened_at, any piece scan (return_session_items.scanned_at), any AWB scan
 * (return_session_shipments.linked_at) and any disposition (disposition_at).
 *
 * Closes through the SAME ReturnSessionService.close() path (so its preconditions and the
 * intake stamp both apply — never bypassed). If close() refuses (a piece was scanned between
 * the eligibility check and the close, or someone closed it first), the session is left
 * exactly as it is. Actor = system: closed_by NULL, the codebase's convention for
 * system-initiated writes (same as BostaWebhookJob's ledger transitions and the reconcile
 * job's manualLink()). Sessions with pending items are never touched.
 *
 * Tenants are iterated on the owner connection (same as ExceptionDigestJob); everything
 * per-tenant runs under TenantContext.runAs() + app_user/RLS.
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ReturnSessionAutoCloseJob {

    private static final Logger log = LoggerFactory.getLogger(ReturnSessionAutoCloseJob.class);

    static final int IDLE_HOURS = 12;

    private final JdbcTemplate         ownerJdbc;
    private final JdbcTemplate         jdbc;
    private final TransactionTemplate  tx;
    private final ReturnSessionService sessionService;

    public ReturnSessionAutoCloseJob(@FlywayDataSource DataSource ownerDs,
                                     JdbcTemplate jdbc,
                                     PlatformTransactionManager txm,
                                     ReturnSessionService sessionService) {
        this.ownerJdbc      = new JdbcTemplate(ownerDs);
        this.jdbc           = jdbc;
        this.tx             = new TransactionTemplate(txm);
        this.sessionService = sessionService;
    }

    @Recurring(id = "return-session-auto-close", cron = "0 * * * *")
    @Job(name = "Auto-close idle return sessions")
    public void run() {
        List<UUID> tenantIds = ownerJdbc.queryForList("SELECT id FROM tenants", UUID.class);
        for (UUID tenantId : tenantIds) {
            try {
                processTenant(tenantId);
            } catch (Exception e) {
                log.warn("Return session auto-close failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    /** One tenant's pass (public for the per-tenant isolation test; run() is the scheduled entry). */
    public void processTenant(UUID tenantId) {
        List<UUID> idle = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.queryForList(
            "SELECT rs.id FROM return_sessions rs " +
            "WHERE rs.tenant_id = ? AND rs.status = 'open' " +
            "  AND NOT EXISTS (SELECT 1 FROM return_session_items i " +
            "                  WHERE i.session_id = rs.id AND i.disposition = 'pending') " +
            "  AND GREATEST(rs.opened_at, " +
            "        (SELECT MAX(GREATEST(i.scanned_at, COALESCE(i.disposition_at, i.scanned_at))) " +
            "           FROM return_session_items i WHERE i.session_id = rs.id), " +
            "        (SELECT MAX(sh.linked_at) FROM return_session_shipments sh WHERE sh.session_id = rs.id)) " +
            "      < now() - (interval '1 hour' * ?)",
            UUID.class, tenantId, IDLE_HOURS)));

        for (UUID sessionId : idle) {
            try {
                TenantContext.runAs(tenantId, () -> sessionService.close(sessionId, null));
                log.info("Auto-closed idle return session {} (tenant {}) — idle > {}h, nothing pending",
                    sessionId, tenantId, IDLE_HOURS);
            } catch (Exception e) {
                // close() refused (became non-empty/pending, or closed concurrently) — leave it.
                log.info("Auto-close skipped for return session {} (tenant {}): {}",
                    sessionId, tenantId, e.getMessage());
            }
        }
    }
}
