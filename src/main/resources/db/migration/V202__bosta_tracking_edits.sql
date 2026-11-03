-- V202 — Shopify tracking-number edits follow the forward leg, S3: Bosta (2026-11-03).
--
-- A Bosta forward leg whose Shopify fulfillment now carries a different Bosta number (edit / strict
-- cancel-and-refulfill, or — D2 — a leg with no anchoring fulfillment once its old delivery is confirmed ended in
-- Bosta) is REPLACED: inside the link transaction the old leg becomes internal_state 'cancelled' with NO
-- shipment_status_history row (the BostaDeletionService pattern — no freeze, no ALREADY_SHIPPED) and the new
-- delivery is linked as the order's forward leg. Traced never writes to Bosta (Mode B): the old delivery may stay
-- live there.
--
-- 1. shipments:
--      superseded_at              when this leg was replaced (NULL = never)
--      superseded_by_shipment_id  the leg that replaced it
--      superseded_reason          'shopify_tracking_edit'
--      superseded_last_state      the internal state Bosta's latest event for the OLD delivery maps to — written only
--                                 by the fence (BostaWebhookJob), which stores raw / provider_state and this, and
--                                 never a history row, a piece move or internal_state
--      superseded_moved_at        first fenced event that is real movement (with_courier … exception): HIGH
--                                 superseded_waybill_moving until Bosta ends it (terminated / cancelled), its deletion
--                                 is confirmed, or it is resolved
--    A replaced leg is watched by the status poll (lowest priority) until the old delivery is terminal, not found
--    or deleted in Bosta, or 14 days after the switch (BostaSupersede.watchedSql).
-- 2. shipment_tracking_changes.reason gains 'old_delivery_live' (D2: Shopify shows a different waybill but the old
--    delivery isn't confirmed ended in Bosta — MEDIUM, no switch).

ALTER TABLE shipments
    ADD COLUMN superseded_at             timestamptz,
    ADD COLUMN superseded_by_shipment_id uuid REFERENCES shipments(id),
    ADD COLUMN superseded_reason         text CHECK (superseded_reason IN ('shopify_tracking_edit')),
    ADD COLUMN superseded_last_state     text,
    ADD COLUMN superseded_moved_at       timestamptz,
    ADD CONSTRAINT shipments_superseded_bosta_forward
        CHECK (superseded_at IS NULL OR (provider = 'bosta' AND shipment_leg = 'forward'
                                         AND internal_state = 'cancelled' AND superseded_reason IS NOT NULL));

CREATE INDEX shipments_superseded_idx ON shipments (tenant_id, superseded_at) WHERE superseded_at IS NOT NULL;

ALTER TABLE shipment_tracking_changes DROP CONSTRAINT shipment_tracking_changes_reason_check;
ALTER TABLE shipment_tracking_changes ADD CONSTRAINT shipment_tracking_changes_reason_check
    CHECK (reason IN ('frozen', 'pickup_session', 'ambiguous', 'key_taken', 'old_delivery_live'));
