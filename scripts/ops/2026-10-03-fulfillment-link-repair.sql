-- =============================================================================================
-- Fulfillment-link hotfix repair (2026-10-03)
--
-- The 94c4a4c link job / catch-up linked these orders through BostaWebhookJob.process() (shipment,
-- status history, not-traced tag, reconcile flag, PII — all committed), then failed on the very
-- next query because process() had cleared TenantContext (RLS saw nothing). The only step lost was
-- markLinked: the order_fulfillment_tracking row still has link_status NULL. This sets exactly
-- that, for the 4 rows linked through this path whose row wasn't marked (BRK-44876-EG's row was
-- marked by the retry sweeper at 16:19 and is not touched).
--
-- Run MANUALLY as the DB owner, after the hotfix deploy or before — either is safe: a NULL row with a
-- shipment is never re-enqueued by the capture, and the catch-up skips orders that have a shipment.
-- Ends with ROLLBACK: run, compare every "expect", then change ROLLBACK to COMMIT.
-- =============================================================================================

BEGIN;

-- Dry run: the 4 targets, each with its forward shipment on the same order.
SELECT ft.id, ft.tenant_id, o.number, ft.tracking_number, ft.link_status, s.id AS shipment_id, s.created_at
FROM order_fulfillment_tracking ft
JOIN orders o    ON o.id = ft.order_id AND o.tenant_id = ft.tenant_id
JOIN shipments s ON s.order_id = ft.order_id AND s.tenant_id = ft.tenant_id
                AND s.tracking_number = ft.tracking_number AND s.shipment_leg = 'forward'
WHERE (ft.tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d' AND ft.id IN (386, 2, 9))
   OR (ft.tenant_id = 'a7d6fab9-828d-4b3a-8ab6-71e6bef3d197' AND ft.id = 44)
ORDER BY s.created_at;
-- expect 4 rows, link_status NULL:
--   44  Femine 70607         3148266112  shipment ef3896fa-5e6d-4d6b-a188-5f2bea38bc76
--   386 BROEK  BRK-44839-EG  5903442156  shipment f1bc1f47-0057-491c-bb3c-c2af226df7aa
--   2   BROEK  BRK-44889-EG  8287551986  shipment e180a3e7-1fba-4011-9cad-5f4322e321f8
--   9   BROEK  BRK-44866-EG  8948149267  shipment 115eac2a-08ad-44cb-8ab4-a8fded5ddfa0

UPDATE order_fulfillment_tracking ft
SET link_status = 'linked', link_reason = NULL, linked_at = s.created_at,
    link_checked_at = now(), link_next_retry_at = NULL
FROM shipments s
WHERE s.order_id = ft.order_id AND s.tenant_id = ft.tenant_id
  AND s.tracking_number = ft.tracking_number AND s.shipment_leg = 'forward'
  AND ft.link_status IS NULL
  AND ((ft.tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d' AND ft.id IN (386, 2, 9))
    OR (ft.tenant_id = 'a7d6fab9-828d-4b3a-8ab6-71e6bef3d197' AND ft.id = 44));
-- expect UPDATE 4

SELECT id, tracking_number, link_status, linked_at FROM order_fulfillment_tracking
WHERE id IN (386, 2, 9, 44) ORDER BY id;
-- expect 4 rows, all 'linked'

ROLLBACK;   -- ← change to COMMIT only after every "expect" above matched
