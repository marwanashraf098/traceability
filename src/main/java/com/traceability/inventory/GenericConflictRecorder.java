package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Non-Bosta couriers G2 (V171) — the pack-time Bosta-over-generic conflict, kept.
 *
 * The queue's AWB link step refuses a Bosta AWB on an order whose generic shipment is past 'created'
 * (G1's Bosta-wins BLOCKED → 409). The refusal rolls the link transaction back, so the evidence is
 * written here, AFTER that rollback, in a transaction of its OWN (REQUIRES_NEW — same thread, tenant
 * context set explicitly, so RLS applies), and survives. ExceptionService derives a HIGH generic_bosta_conflict alert
 * from each row until it's resolved — the same type G1 raises for a delivery Traced refused to link.
 * A failed write never reaches the packer (they still get the 409) and is never swallowed: it is logged at ERROR with
 * tenant, order and Bosta AWB ({@link #recordAfterRollback}).
 */
@Component
public class GenericConflictRecorder {

    private static final Logger log = LoggerFactory.getLogger(GenericConflictRecorder.class);

    private final JdbcTemplate jdbc;

    public GenericConflictRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Called inside the link transaction right before it throws its 409. The write waits until that transaction
     * has ROLLED BACK — it holds the generic shipment FOR UPDATE (yieldToBosta), and the new row's foreign key to
     * it would wait on that lock from a second transaction: a self-deadlock no database detector sees. After the
     * rollback the lock is gone and {@link #record} runs in a transaction of its own. Outside a transaction it
     * records at once.
     */
    public void recordAfterRollback(UUID orderId, String bostaTrackingNumber, String rawScan, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        GenericConflictRecorder self = this.self != null ? this.self : this;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) return;   // the link went through after all: no conflict
                    try {
                        TenantContext.runAs(tenantId, () -> self.record(orderId, bostaTrackingNumber, rawScan, actorUserId));
                    } catch (RuntimeException e) {
                        // e.g. the REQUIRES_NEW commit itself failed — never swallowed: the alert is lost, say so loudly.
                        logLost(tenantId, orderId, bostaTrackingNumber, e);
                    }
                }
            });
        } else {
            try {
                self.record(orderId, bostaTrackingNumber, rawScan, actorUserId);
            } catch (RuntimeException e) {
                logLost(tenantId, orderId, bostaTrackingNumber, e);
            }
        }
    }

    /** The Spring proxy of this bean, so {@link #record}'s REQUIRES_NEW applies when called from here. */
    private GenericConflictRecorder self;

    @org.springframework.beans.factory.annotation.Autowired
    void setSelf(@org.springframework.context.annotation.Lazy GenericConflictRecorder self) {
        this.self = self;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID orderId, String bostaTrackingNumber, String rawScan, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        // A failure throws — this REQUIRES_NEW transaction rolls back and recordAfterRollback logs it at ERROR.
        int n = jdbc.update(
            "INSERT INTO generic_conflict_scans (tenant_id, order_id, shipment_id, tracking_number, raw_scan, actor_user_id) " +
            "SELECT ?, ?, s.id, ?, ?, ? FROM shipments s " +
            "WHERE s.tenant_id = ? AND s.order_id = ? AND s.provider = 'generic' AND s.shipment_leg = 'forward' " +
            "  AND s.internal_state NOT IN ('terminated', 'cancelled') " +
            "ORDER BY s.created_at DESC, s.id DESC LIMIT 1",
            tenantId, orderId, bostaTrackingNumber, rawScan, actorUserId, tenantId, orderId);
        if (n == 1) {
            log.warn("Bosta AWB {} scanned on order {} whose generic shipment is packed — HIGH alert recorded",
                bostaTrackingNumber, orderId);
        } else {
            log.error("Generic/Bosta pack-scan conflict NOT stored — no live generic shipment left: tenant={} order={} " +
                "bostaTracking={}", tenantId, orderId, bostaTrackingNumber);
        }
    }


    /** ERROR with tenant / order / Bosta AWB and the cause — the HIGH alert was NOT stored. */
    private static void logLost(UUID tenantId, UUID orderId, String bostaTrackingNumber, RuntimeException e) {
        log.error("Generic/Bosta pack-scan conflict NOT stored — HIGH alert lost: tenant={} order={} bostaTracking={}",
            tenantId, orderId, bostaTrackingNumber, e);
    }
}
