-- ============================================================
-- V98 — Return legs: scan-as-truth
--
-- Before this change a CRP (Bosta type 25) return leg reaching state 46
-- ("Returned to business") moved EVERY delivered piece on the order to
-- return_pending_inspection — BostaWebhookJob.applyMappedState()'s piece
-- query is order-scoped, not leg-scoped, so items the customer kept were
-- moved too. From this change on, return-leg courier states only update the
-- shipment row; only an intake scan moves a delivered piece.
--
-- That leaves a new question the shipment row must answer: "has this
-- returned parcel actually been scanned in yet?" — internal_state='returned'
-- can no longer imply it.
--
-- 1. shipments.return_intake_completed_at — stamped by
--    ReturnSessionService.close() (closed, never abandoned) on every return
--    leg of an order that had a piece legally scanned in that session.
--    Always NULL on forward legs.
-- 2. tenants.return_unscanned_window_days — grace period before a returned
--    but unscanned leg raises the return_leg_unscanned exception.
-- 3. Evidence-based backfill for return legs already in a terminal state:
--    stamp only where a return_received piece_event exists for a piece of
--    that order at or after the leg's created_at (earliest such event).
--    Everything else stays NULL — genuinely unscanned. A piece moved by the
--    old state-46 webhook path carries event_type='courier_update', not
--    'return_received', so it is NOT evidence of an intake scan.
--
-- No new table: both columns inherit their table's existing RLS policy.
-- ============================================================

ALTER TABLE shipments
    ADD COLUMN return_intake_completed_at timestamptz;

ALTER TABLE tenants
    ADD COLUMN return_unscanned_window_days integer NOT NULL DEFAULT 3;

UPDATE shipments s
SET    return_intake_completed_at = ev.first_scan_at
FROM (
    SELECT s2.id AS shipment_id, MIN(pe.occurred_at) AS first_scan_at
    FROM   shipments s2
    JOIN   piece_events pe
           ON  pe.order_id   = s2.order_id
           AND pe.tenant_id  = s2.tenant_id
           AND pe.event_type = 'return_received'
           AND pe.occurred_at >= s2.created_at
    WHERE  s2.shipment_leg = 'return'
      AND  s2.internal_state IN ('returned', 'lost', 'exception', 'terminated', 'cancelled')
    GROUP  BY s2.id
) ev
WHERE  s.id = ev.shipment_id
  AND  s.return_intake_completed_at IS NULL;
