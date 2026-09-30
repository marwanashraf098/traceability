-- V122 — Return-to-origin (RTO) progress maps to 'returning', not a false 'exception'.
--
-- Bosta relabels a SEND as type 20 "Return to Origin" when it starts coming back, keeping the
-- tracking number (it stays the order's forward leg). During the return trip Bosta sends state 20
-- ("Route assigned") and state 41 ("Out for return").
--   * 20 fell through to 20:ALL → 'created'; the monotonic-vs-created guard in
--     BostaWebhookJob.applyMappedState() then turned it into 'exception' (Needs attention) on every
--     RTO leg, until state 46 arrived.
--   * 41 matched nothing: the seeded 41:RTO row is keyed on "RTO", but the mapper key is the
--     verify-by-fetch type.value uppercased — "RETURN TO ORIGIN" (BostaHttpGateway.fetchDelivery).
--     No 41:ALL row exists, so RTO@41 was an unknown code and the webhook failed.
-- An RTO has by definition been dispatched, so both are return progress. Keyed on the real type
-- string, the SEND rows are untouched: SEND@20 still maps to 'created' and the guard still turns a
-- post-dispatch SEND@20 (and CRP@20) into 'exception'.
--
-- piece_status_after is NULL on both rows: no piece moves on these states. (The dead 41:RTO row's
-- return_in_transit move is deliberately NOT copied; that row is left in place, unreachable.)
-- 'returning' already exists in shipment_internal_state — no enum or CHECK change.

INSERT INTO bosta_state_mappings
    (state_code, applies_to_order_type, bosta_state,
     internal_shipment_state, piece_status_after, notes)
VALUES
(20, 'RETURN TO ORIGIN', 'Route assigned (return to origin)',
     'returning', NULL,
     'RTO: route assigned for the trip back to the merchant. Return progress, never pre-dispatch. No piece move.'),
(41, 'RETURN TO ORIGIN', 'Out for return (return to origin)',
     'returning', NULL,
     'RTO: out for return. Replaces the unreachable 41:RTO key for this type string. No piece move.');

-- One-time repair of forward legs a false RTO@20 exception left stuck (prod at 2026-09-30: 2 legs,
-- both Jumi Worldwide). The shipment's stored raw is Bosta's latest verify-by-fetch payload, so
-- provider_state = 20 with raw type.code = 20 is exactly "the latest event was RTO@20".
-- Guarded and idempotent: once repaired a leg is no longer 'exception', so a re-run touches nothing.
-- shipment_status_history is not touched — it keeps recording what Bosta sent.
-- Flyway runs as the owner (BYPASSRLS), so this spans every tenant in one pass (same as V68).
UPDATE shipments
SET internal_state = 'returning'
WHERE shipment_leg = 'forward'
  AND internal_state = 'exception'
  AND provider_state = 20
  AND raw -> 'type' ->> 'code' = '20';
