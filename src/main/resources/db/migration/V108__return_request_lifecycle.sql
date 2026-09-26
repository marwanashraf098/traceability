-- ============================================================
-- V108 — Returns Step 4d-1: link returned parcels to requests, attribute and
-- reconcile items, request lifecycle, request history.
--
-- 1. return_requests — lifecycle timestamps, close outcome, how the return leg was linked.
--    return_shipment_id becomes one request per leg (partial UNIQUE).
-- 2. return_request_items.item_status — awaiting → arrived → done, or not_coming.
--    `active` stays the one-live-item-per-piece flag (V100's partial unique index) and is
--    kept equal to item_status IN ('awaiting','arrived') by a CHECK, so a finished item
--    always releases its piece for future returns.
-- 3. return_session_items.request_item_id — which request item a scan was attributed to.
--    ON DELETE SET NULL: an audit pointer; cleanup that deletes request items first must
--    not be blocked by it.
-- 4. return_request_events — append-only request history (app_user: INSERT + SELECT only).
--    ON DELETE CASCADE from return_requests (requests are never deleted by the app; test
--    and fixture cleanup deletes them, and the history goes with them).
-- ============================================================

-- 1. return_requests
ALTER TABLE return_requests
    ADD COLUMN received_at       timestamptz,
    ADD COLUMN refund_pending_at timestamptz,
    ADD COLUMN closed_at         timestamptz,
    ADD COLUMN closed_by         uuid REFERENCES users(id),
    ADD COLUMN close_reason      text
        CONSTRAINT return_requests_close_reason_check
        CHECK (close_reason IN ('no_refund', 'rest_not_coming', 'other')),
    ADD COLUMN close_note        text
        CONSTRAINT return_requests_close_note_length CHECK (char_length(close_note) <= 300),
    ADD COLUMN link_source       text
        CONSTRAINT return_requests_link_source_check
        CHECK (link_source IN ('traced_booking', 'auto_matched', 'merchant_selected'));

-- Rows linked before this migration were all linked by Traced's own booking (4c-3).
UPDATE return_requests SET link_source = 'traced_booking'
WHERE return_shipment_id IS NOT NULL AND link_source IS NULL;

CREATE UNIQUE INDEX return_requests_one_per_return_shipment
    ON return_requests (return_shipment_id) WHERE return_shipment_id IS NOT NULL;

-- 2. return_request_items
ALTER TABLE return_request_items
    ADD COLUMN item_status text NOT NULL DEFAULT 'awaiting'
        CONSTRAINT return_request_items_item_status_check
        CHECK (item_status IN ('awaiting', 'arrived', 'done', 'not_coming')),
    ADD COLUMN arrived_at timestamptz,
    ADD COLUMN done_at    timestamptz;

UPDATE return_request_items SET item_status = CASE WHEN active THEN 'awaiting' ELSE 'not_coming' END;

ALTER TABLE return_request_items
    ADD CONSTRAINT return_request_items_active_matches_status
        CHECK (active = (item_status IN ('awaiting', 'arrived')));

-- 3. return_session_items
ALTER TABLE return_session_items
    ADD COLUMN request_item_id uuid REFERENCES return_request_items(id) ON DELETE SET NULL;

-- 4. return_request_events
CREATE TABLE return_request_events (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenants(id),
    request_id  uuid        NOT NULL REFERENCES return_requests(id) ON DELETE CASCADE,
    event_type  text        NOT NULL,
    actor       uuid        REFERENCES users(id),
    -- clock_timestamp(), not now(): several events are often written in ONE transaction
    -- (e.g. received + refund_pending) and now() would give them all the same instant; the
    -- id is a random UUID, so it can't order them either.
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    metadata    jsonb
);
CREATE INDEX return_request_events_request_idx
    ON return_request_events (request_id, occurred_at);

ALTER TABLE return_request_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE return_request_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON return_request_events
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

REVOKE UPDATE, DELETE, TRUNCATE ON return_request_events FROM app_user;

-- History for requests that already exist, from the columns that recorded it.
INSERT INTO return_request_events (tenant_id, request_id, event_type, actor, occurred_at, metadata)
SELECT tenant_id, id, 'requested', NULL, created_at, '{"backfilled":true}'::jsonb
FROM return_requests;

INSERT INTO return_request_events (tenant_id, request_id, event_type, actor, occurred_at, metadata)
SELECT tenant_id, id, status::text, decided_by, decided_at, '{"backfilled":true}'::jsonb
FROM return_requests
WHERE decided_at IS NOT NULL AND status::text IN ('approved', 'rejected');

INSERT INTO return_request_events (tenant_id, request_id, event_type, actor, occurred_at, metadata)
SELECT tenant_id, id, 'approved', decided_by, decided_at, '{"backfilled":true}'::jsonb
FROM return_requests
WHERE decided_at IS NOT NULL AND status::text = 'pickup_booked';

INSERT INTO return_request_events (tenant_id, request_id, event_type, actor, occurred_at, metadata)
SELECT tenant_id, id, 'pickup_booked', NULL, COALESCE(booking_attempted_at, decided_at, created_at),
       jsonb_build_object('backfilled', true, 'tracking_number', bosta_tracking_number)
FROM return_requests
WHERE status::text = 'pickup_booked';
