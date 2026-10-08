-- V152 — Scan returns: untracked parcel items, one row per unit (2026-10-08 diagnosis, Issue 1;
-- design signed off 2026-10-08, design/returns-parcel-states 3 / 3b / 3c / 6 / 8).
--
-- A parcel in a return session whose order has UNTRACKED lines (PortalService.LINE_UNTRACKED_SQL —
-- no allocation of any status) and no linked return request lists those lines one row per unit.
-- A worker marks each unit that came back "Arrived · sellable" or "Arrived · damaged"; unmarked
-- units simply didn't come back (partial returns are normal). One row here per marked unit.
--   - Undo sets undone_at (never DELETE — app_user has no DELETE); while the session is open only.
--   - At most one live row per (shipment, order line, unit): the partial unique index below, so a
--     double tap can never create two.
--   - A live sellable row raises one "Return To Receive" exception (keyed on the row id).
--   - via_phone: the parcel's AWB was scanned on the worker's paired phone in this session
--     (return_session_shipments.via_phone, set server-side from the verified relay event).
-- No piece, no stock, no Shopify write — sellable units enter stock only through Receiving.
-- Not part of return requests: the Requests tab and the returns/portal analytics never read it.

-- 1. Which AWB scans came from the paired phone (PhoneScanSource-verified, never client-sent).
ALTER TABLE return_session_shipments ADD COLUMN via_phone boolean NOT NULL DEFAULT false;

-- 2. A leg handled unit by unit.
ALTER TABLE shipments DROP CONSTRAINT shipments_return_intake_outcome_check;
ALTER TABLE shipments ADD CONSTRAINT shipments_return_intake_outcome_check
    CHECK (return_intake_outcome IN ('scanned', 'received_untracked', 'request_items_arrived',
                                     'untracked_units_arrived'));

-- 3. The units.
CREATE TABLE untracked_unit_intakes (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL REFERENCES tenants(id),
    return_session_id  uuid        NOT NULL REFERENCES return_sessions(id),
    shipment_id        uuid        NOT NULL REFERENCES shipments(id),
    order_id           uuid        NOT NULL REFERENCES orders(id),
    order_item_id      uuid        NOT NULL REFERENCES order_items(id),
    unit_no            int         NOT NULL CHECK (unit_no >= 1),
    condition          text        NOT NULL CHECK (condition IN ('sellable', 'damaged')),
    actor_user_id      uuid        NOT NULL REFERENCES users(id),
    via_phone          boolean     NOT NULL DEFAULT false,
    created_at         timestamptz NOT NULL DEFAULT now(),
    undone_at          timestamptz,
    undone_by          uuid        REFERENCES users(id),
    CHECK ((undone_at IS NULL) = (undone_by IS NULL))
);

CREATE UNIQUE INDEX ux_untracked_unit_intakes_live
    ON untracked_unit_intakes (shipment_id, order_item_id, unit_no) WHERE undone_at IS NULL;
CREATE INDEX ix_untracked_unit_intakes_session
    ON untracked_unit_intakes (tenant_id, return_session_id);
CREATE INDEX ix_untracked_unit_intakes_shipment
    ON untracked_unit_intakes (tenant_id, shipment_id) WHERE undone_at IS NULL;

ALTER TABLE untracked_unit_intakes ENABLE ROW LEVEL SECURITY;
ALTER TABLE untracked_unit_intakes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON untracked_unit_intakes
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- A mark is a record: app_user inserts and undoes (the two undo columns), never edits or deletes.
REVOKE UPDATE, DELETE, TRUNCATE ON untracked_unit_intakes FROM app_user;
GRANT UPDATE (undone_at, undone_by) ON untracked_unit_intakes TO app_user;
