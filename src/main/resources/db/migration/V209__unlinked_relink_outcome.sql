-- V209 — why a stranded Bosta delivery was (or wasn't) re-linked (2026-11-05, BROEK BRK-47264-EG).
--
-- A Bosta SEND whose order already has a live Bosta forward leg is recorded unlinked with match_reason
-- ACTIVE_FORWARD_LEG (was NO_MATCH). Two automatic paths then try it again from the STORED raw (no Bosta
-- call, no re-ingest — so the webhook_events step-4 dedup never applies):
--   * ActiveForwardLegCheckJob: one not-found check of the order's old leg right away;
--   * BostaRelink.relinkStrandedUnlinked: after a confirmed forward-leg deletion, a Bosta 48 / 49 / 104 on a
--     forward leg, or the check above.
-- Each attempt writes its outcome here:
--   relink_outcome  'linked' | 'old_delivery_live' (the check found the old delivery in Bosta — the order has
--                   two Bosta waybills) | 'old_leg_moved' | 'check_inconclusive' | 'refused:<reason>'
--                   (frozen, pickup_session, packed, order_closed, identity, active_forward_leg, …)
--   relink_at       when it was written
-- Nullable, no default, no backfill: metadata-only ADD COLUMN (no table rewrite). app_user keeps its existing
-- table grants; the tenant_isolation policy (V6) already covers the new columns.

ALTER TABLE unlinked_bosta_deliveries
    ADD COLUMN relink_outcome text,
    ADD COLUMN relink_at      timestamptz;
