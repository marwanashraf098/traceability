-- Schedule pickup fix (2026-11-04) — READ ONLY: SELECTs only, no writes. Run as the owner, all tenants.
--
-- Every forward shipment the OLD rule would put in a tenant's next scheduled pickup (order stored as
-- 'awaiting_pickup', not already in a non-cancelled pickup) that the NEW rule leaves out (it must be the order's
-- NEWEST forward leg, internal_state 'created', not deleted in Bosta, and never moved —
-- OrderLineFreeze.prePickupWaybillSql). bosta_active = the tenant can schedule a Bosta pickup at all.

SET statement_timeout = '30s';

WITH cand AS (
    SELECT s.*, o.number AS order_number, o.status::text AS order_status
    FROM shipments s
    JOIN orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id
    WHERE s.shipment_leg = 'forward'
      AND o.status = 'awaiting_pickup'::order_status
      AND o.external_id NOT LIKE 'internal:portal:%'
      AND NOT EXISTS (SELECT 1 FROM pickup_shipments ps JOIN pickups p ON p.id = ps.pickup_id
                      WHERE ps.shipment_id = s.id AND ps.tenant_id = s.tenant_id
                        AND p.session_status NOT IN ('cancelled'))),
judged AS (
    SELECT c.*,
           c.id = (SELECT x.id FROM shipments x WHERE x.order_id = c.order_id AND x.tenant_id = c.tenant_id
                     AND x.shipment_leg = 'forward' ORDER BY x.created_at DESC, x.id DESC LIMIT 1) AS is_newest,
           (c.internal_state::text NOT IN ('created', 'cancelled', 'terminated')
            OR EXISTS (SELECT 1 FROM shipment_status_history h WHERE h.shipment_id = c.id
                       AND (h.internal_state NOT IN ('created', 'cancelled', 'terminated')
                            OR (h.internal_state IN ('cancelled', 'terminated') AND EXISTS (
                                SELECT 1 FROM shipment_status_history h2 WHERE h2.shipment_id = h.shipment_id
                                  AND h2.internal_state NOT IN ('created', 'cancelled', 'terminated')))))) AS moved
    FROM cand c)
SELECT t.name AS tenant, j.order_number, j.tracking_number, j.internal_state::text AS state,
       j.is_newest, j.moved, (j.provider_deleted_at IS NOT NULL) AS deleted_in_bosta, j.cod_amount,
       EXISTS (SELECT 1 FROM courier_accounts ca WHERE ca.tenant_id = j.tenant_id AND ca.provider = 'bosta'
               AND ca.status = 'active') AS bosta_active,
       j.created_at AS leg_created_at
FROM judged j JOIN tenants t ON t.id = j.tenant_id
WHERE NOT (j.is_newest AND j.internal_state::text = 'created' AND j.provider_deleted_at IS NULL AND NOT j.moved)
ORDER BY t.name, j.order_number, j.created_at;
