package com.traceability.integrations.bosta;

import com.traceability.inventory.ShipmentLinkService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

/**
 * Tier 3 — Order Reconcile: detects Shopify orders that have no linked Bosta delivery.
 *
 * Runs every 5 minutes. For each tenant with an active Bosta account it finds orders
 * that are:
 *   - not yet flagged (bosta_link_status IS NULL)
 *   - placed within the lookback window (default 30 days)
 *   - not in a terminal status (delivered / returned / lost / cancelled)
 *   - not already linked to an active shipment
 *   - outside the per-order cooldown (last_check more than 4 minutes ago)
 *
 * For each eligible order it searches unlinked_bosta_deliveries by the order number
 * variants (raw, stripped, hashed) and external_id. If a match is found it calls
 * ShipmentLinkService.manualLink() to link it. If no match is found the attempt
 * counter is incremented; after max-attempts cycles the order is flagged
 * bosta_link_status = 'not_created' so the merchant sees a distinct badge.
 *
 * The flag is cleared automatically whenever any path (webhook, backfill, reconcile,
 * AWB scan) creates a shipment for the order via ShipmentLinkService.createOrFindShipment().
 *
 * Note: this job does NOT make any Bosta API calls. It works entirely against the
 * local unlinked_bosta_deliveries table, which is populated by the Discovery Poll
 * and Backfill jobs.
 *
 * TenantContext ordering: TenantContext.runAs() is always the OUTER wrapper;
 * TransactionTemplate.execute() is always the INNER wrapper. This ensures the
 * GUC is set before the transaction starts and TenantAwareConnection.afterBegin()
 * can read it correctly.
 */
@Service
@ConditionalOnProperty(name = "bosta.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class BostaOrderReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(BostaOrderReconcileJob.class);

    private final JdbcTemplate        ownerJdbc;
    private final JdbcTemplate        jdbc;
    private final TransactionTemplate tx;
    private final ShipmentLinkService shipmentLinkService;
    private final int                 maxAttempts;
    private final int                 lookbackDays;
    private final int                 batchSize;

    public BostaOrderReconcileJob(
            @FlywayDataSource DataSource ownerDs,
            JdbcTemplate jdbc,
            PlatformTransactionManager txm,
            ShipmentLinkService shipmentLinkService,
            @Value("${bosta.reconcile.max-attempts:10}")  int maxAttempts,
            @Value("${bosta.reconcile.lookback-days:30}") int lookbackDays,
            @Value("${bosta.reconcile.batch-size:50}")    int batchSize) {
        this.ownerJdbc          = new JdbcTemplate(ownerDs);
        this.jdbc               = jdbc;
        this.tx                 = new TransactionTemplate(txm);
        this.shipmentLinkService = shipmentLinkService;
        this.maxAttempts        = maxAttempts;
        this.lookbackDays       = lookbackDays;
        this.batchSize          = batchSize;
    }

    @Recurring(id = "bosta-order-reconcile", cron = "*/5 * * * *")
    @Job(name = "Bosta order reconcile — detect unlinked orders")
    public void reconcileAll() {
        List<UUID> tenantIds = ownerJdbc.query(
            "SELECT DISTINCT tenant_id FROM courier_accounts " +
            "WHERE provider = 'bosta' AND status = 'active'",
            (rs, i) -> rs.getObject("tenant_id", UUID.class));

        if (tenantIds.isEmpty()) {
            log.debug("BostaOrderReconcileJob: no active Bosta accounts");
            return;
        }

        for (UUID tenantId : tenantIds) {
            try {
                reconcileTenant(tenantId);
            } catch (Exception e) {
                log.error("BostaOrderReconcileJob: tenant {} failed: {}", tenantId, e.getMessage(), e);
            }
        }
    }

    private void reconcileTenant(UUID tenantId) {
        TenantContext.runAs(tenantId, () -> {
            List<OrderRow> orders = tx.execute(txs -> jdbc.query(
                "SELECT o.id, o.number, o.external_id, o.bosta_link_attempts " +
                "FROM orders o " +
                "WHERE o.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid " +
                "  AND o.bosta_link_status IS NULL " +
                "  AND o.placed_at >= NOW() - (? * INTERVAL '1 day') " +
                "  AND o.status NOT IN ('delivered','returned','lost','cancelled') " +
                "  AND (o.bosta_link_last_check IS NULL " +
                "    OR o.bosta_link_last_check < NOW() - INTERVAL '4 minutes') " +
                "  AND NOT EXISTS ( " +
                "      SELECT 1 FROM shipments s " +
                "      WHERE s.order_id  = o.id " +
                "        AND s.tenant_id = o.tenant_id " +
                "        AND s.internal_state NOT IN ('terminated','cancelled') " +
                "  ) " +
                "ORDER BY o.placed_at ASC " +
                "LIMIT ?",
                (rs, i) -> new OrderRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("number"),
                    rs.getString("external_id"),
                    rs.getInt("bosta_link_attempts")),
                lookbackDays, batchSize));

            if (orders == null || orders.isEmpty()) {
                log.debug("BostaOrderReconcileJob: tenant {} — no eligible orders", tenantId);
                return;
            }

            log.debug("BostaOrderReconcileJob: tenant {} — {} eligible order(s)", tenantId, orders.size());
            for (OrderRow order : orders) {
                try {
                    processOrder(tenantId, order);
                } catch (Exception e) {
                    log.error("BostaOrderReconcileJob: error on order {}: {}", order.number(), e.getMessage(), e);
                }
            }
        });
    }

    private void processOrder(UUID tenantId, OrderRow order) {
        // Build order number variants: Bosta businessReference may be raw (#12345),
        // stripped (12345), hashed (#12345), or the Shopify external_id (GID).
        String num      = order.number();
        String stripped = (num != null && num.startsWith("#")) ? num.substring(1) : num;
        String hashed   = (stripped != null) ? "#" + stripped : null;

        Long unlinkedId = tx.execute(txs -> jdbc.query(
            "SELECT id FROM unlinked_bosta_deliveries " +
            "WHERE tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid " +
            "  AND resolved = false " +
            "  AND (business_reference = ? OR business_reference = ? " +
            "    OR business_reference = ? OR business_reference = ?) " +
            "ORDER BY first_seen_at ASC LIMIT 1",
            rs -> rs.next() ? rs.getLong("id") : null,
            num, stripped, hashed, order.externalId()));

        if (unlinkedId != null) {
            log.info("BostaOrderReconcileJob: tenant {} order {} matched unlinked_bosta_deliveries id={}",
                tenantId, order.number(), unlinkedId);
            // manualLink is @Transactional and calls TenantContext.require() —
            // safe because we are inside TenantContext.runAs(). It also calls
            // createOrFindShipment() which clears bosta_link_status.
            shipmentLinkService.manualLink(unlinkedId, order.id(), null);
        } else {
            int newAttempts = order.attempts() + 1;
            tx.execute(txs -> {
                if (newAttempts >= maxAttempts) {
                    jdbc.update(
                        "UPDATE orders " +
                        "SET bosta_link_attempts = ?, " +
                        "    bosta_link_last_check = NOW(), " +
                        "    bosta_link_status = 'not_created' " +
                        "WHERE id = ? " +
                        "  AND tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid " +
                        "  AND bosta_link_status IS NULL",
                        newAttempts, order.id());
                    log.info("BostaOrderReconcileJob: flagged order {} as not_created after {} attempts",
                        order.number(), newAttempts);
                } else {
                    jdbc.update(
                        "UPDATE orders " +
                        "SET bosta_link_attempts = ?, " +
                        "    bosta_link_last_check = NOW() " +
                        "WHERE id = ? " +
                        "  AND tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid",
                        newAttempts, order.id());
                    log.debug("BostaOrderReconcileJob: order {} attempt {}/{}",
                        order.number(), newAttempts, maxAttempts);
                }
                return null;
            });
        }
    }

    private record OrderRow(UUID id, String number, String externalId, int attempts) {}
}
