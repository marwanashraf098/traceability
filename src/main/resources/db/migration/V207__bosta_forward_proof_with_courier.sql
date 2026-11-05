-- V207 — the first forward state that proves Bosta has the parcel moves its pieces to with_courier.
--
-- Until now only state 21 ("Picked up from business") moved a piece to with_courier. When Bosta skips 21
-- (10 → 24, e.g. the parcel is scanned in at Bosta's warehouse) and no pickup session is closed, the pieces
-- stayed 'awaiting_pickup' / 'packed' while the parcel was out for delivery; a later 46 then moved them
-- awaiting_pickup → return_pending_inspection with no with_courier ever recorded (BROEK, 2026-11-05:
-- 52 pieces + 1 Nuvira piece on prod at that time).
--
-- Rows changed (piece_status_after NULL → 'with_courier'); the shipment state of each row is unchanged:
--   24:ALL       Received at warehouse
--   30:ALL       In transit between hubs
--   41:SEND      Out for delivery
--   41:FXF_SEND  Out for delivery (fulfillment)
-- Untouched: 10 / 11 / 20 (pre-dispatch codes — the monotonic-vs-created guard stays the only rule there),
-- 41 for RETURN TO ORIGIN / RTO / CUSTOMER RETURN PICKUP, and every other code.
--
-- The move goes through BostaWebhookJob.applyMappedState()'s existing piece loop, exactly like 21:
-- event 'courier_update', no actor (a Bosta status update, not a pickup handover). The loop already
-- makes it safe: packed / awaiting_pickup → with_courier are allowed ledger edges; a piece still being
-- picked ('reserved') or already past with_courier has no legal edge and is skipped; a piece already
-- with_courier is a no-op; return legs never move pieces. Type-30 exchange forward legs after pack are
-- interpreted by ExchangeStateInterpreter, not this table. No backfill: pieces already stuck move on
-- with the next 45 / 46 (awaiting_pickup → delivered / return_pending_inspection are allowed edges).

UPDATE bosta_state_mappings
SET piece_status_after = 'with_courier',
    notes = COALESCE(notes || ' ', '') ||
            'V207: proof Bosta has the parcel — pieces packed/awaiting_pickup move to with_courier (as on 21).'
WHERE piece_status_after IS NULL
  AND internal_shipment_state = 'with_courier'
  AND ((state_code = 24 AND applies_to_order_type = 'ALL')
    OR (state_code = 30 AND applies_to_order_type = 'ALL')
    OR (state_code = 41 AND applies_to_order_type IN ('SEND', 'FXF_SEND')));
