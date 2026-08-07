package com.traceability.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Queue-gating-not-traced spec: tags an order "Not Traced" (internal, badge-only — never
 * pushed to Shopify, no Exceptions entry) when it was sent to Bosta without ever passing
 * through the Traced pick/pack flow.
 *
 * Self-contained by design: re-derives the order's LATEST shipment_leg='forward' shipment
 * from the DB rather than trusting whatever state the caller just wrote. This is required,
 * not just tidy — a webhook update to a CRP RETURN-leg shipment must never trip this tag
 * (that leg completing has nothing to do with "sent to Bosta without picking"), and this
 * method's own forward-leg lookup makes that impossible regardless of which shipment
 * triggered the call.
 *
 * The "latest forward shipment via created_at DESC, id DESC LIMIT 1" shape here MUST stay
 * identical to the LEFT JOIN LATERAL in FulfillService.getQueue() and to the V68 migration's
 * corrective backfill — all three select "latest forward shipment" the same way so backfill,
 * detector, and queue removal can never disagree on which orders are affected. (V57's own
 * original backfill UPDATE used a bare `id DESC` tie-break — UUIDv4 is not time-ordered, so
 * that could silently disagree with this method on a tied order; V57's file is frozen
 * as-applied per Flyway checksum rules, and V68 re-runs the same backfill with the corrected
 * ordering to bring already-migrated data in line with this method and getQueue().)
 *
 * Call sites (both required — see build spec pre-work finding A):
 *   - BostaWebhookJob.process(), unconditionally after the piece-transition step. Covers
 *     every state-changing webhook/poll/discovery/backfill path (they all funnel into
 *     process()).
 *   - ShipmentLinkService.manualLink(), after the shipment is linked. A shipment can be
 *     CREATED already in a terminal state (delivered/returned) when an operator — or the
 *     5-minute BostaOrderReconcileJob — manually links a delivery that was already terminal
 *     at discovery time. That path never calls process(), and a terminal shipment is never
 *     polled again, so without this second call site the order would silently never be
 *     tagged going forward even though its shape is identical to the backfilled population.
 *
 * Idempotent via the `not_traced_at IS NULL` guard — safe to call on every state change,
 * including no-op re-deliveries.
 */
@Service
public class NotTracedTagger {

    private final JdbcTemplate jdbc;

    public NotTracedTagger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void maybeTagNotTraced(UUID orderId, UUID tenantId) {
        jdbc.update("""
            UPDATE orders o
            SET not_traced_at = now()
            WHERE o.id = ? AND o.tenant_id = ?
              AND o.not_traced_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM allocations a
                  JOIN order_items oi ON oi.id = a.order_item_id
                  WHERE oi.order_id = o.id AND a.status IN ('active', 'packed')
              )
              AND EXISTS (
                  SELECT 1 FROM shipments s
                  WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id
                    AND s.shipment_leg = 'forward'
                    AND s.internal_state IS NOT NULL
                    AND s.internal_state <> 'created'
                    AND s.id = (
                        SELECT s2.id FROM shipments s2
                        WHERE s2.order_id = o.id AND s2.tenant_id = o.tenant_id
                          AND s2.shipment_leg = 'forward'
                        -- UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
                        ORDER BY s2.created_at DESC, s2.id DESC LIMIT 1
                    )
              )
            """, orderId, tenantId);
    }
}
