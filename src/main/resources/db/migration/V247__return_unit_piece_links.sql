-- V247 — Scan returns: link an unexpected tracked piece to the untracked unit it physically is
-- (returns dead end, BROEK BRK-47010-EG, diagnosis 2026-11-27; S1 + S2 + S3 approved 2026-11-27).
--
-- The case: a parcel's order line is UNTRACKED (no allocation), so the parcel lists it as unit rows.
-- The item in the box carries a Traced label of the SAME variant (e.g. it was received into stock
-- before the return was scanned), so the scan is an "unexpected" item. Until now the only way out was
-- "Not the real piece" (mismatch), which left the unit's Return To Receive open — a double count.
--
-- 1. return_session_items.disposition gains:
--      'linked'       — the item IS the unit of an untracked line on a parcel of this session
--                       (one live row in untracked_unit_piece_links below).
--      'needs_review' — an illegal-state item (not at return_pending_inspection) still pending when
--                       the session closed; raises one manager alert (return_needs_review) per item.
--    Legal pending items (return_pending_inspection) still block closing.
-- 2. untracked_unit_piece_links — which unit intake a piece was linked to. Undo sets undone_at
--    (never DELETE). One live link per intake, per piece and per session item.
--    No piece status change for a sellable link; a damaged link moves the piece through
--    PieceAdjustService (available → damaged). Never a Shopify write of its own.

ALTER TABLE return_session_items DROP CONSTRAINT return_session_items_disposition_check;
ALTER TABLE return_session_items ADD CONSTRAINT return_session_items_disposition_check
    CHECK (disposition IN ('pending', 'restocked', 'damaged', 'mismatch', 'linked', 'needs_review'));

CREATE TABLE untracked_unit_piece_links (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid        NOT NULL REFERENCES tenants(id),
    session_id          uuid        NOT NULL REFERENCES return_sessions(id),
    session_item_id     uuid        NOT NULL REFERENCES return_session_items(id),
    intake_id           uuid        NOT NULL REFERENCES untracked_unit_intakes(id),
    piece_id            text        NOT NULL REFERENCES pieces(id),
    condition           text        NOT NULL CHECK (condition IN ('sellable', 'damaged')),
    -- The link created the unit's intake (the unit wasn't marked Arrived yet) — undo undoes it too.
    created_intake      boolean     NOT NULL DEFAULT false,
    -- Case B: the order's Shopify fulfilment left from the Traced Main location (display note only).
    shopify_may_be_low  boolean     NOT NULL DEFAULT false,
    actor_user_id       uuid        REFERENCES users(id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    undone_at           timestamptz,
    undone_by           uuid        REFERENCES users(id),
    CHECK (undone_at IS NOT NULL OR undone_by IS NULL)
);

CREATE UNIQUE INDEX ux_unit_piece_links_live_intake
    ON untracked_unit_piece_links (intake_id) WHERE undone_at IS NULL;
CREATE UNIQUE INDEX ux_unit_piece_links_live_piece
    ON untracked_unit_piece_links (piece_id) WHERE undone_at IS NULL;
CREATE UNIQUE INDEX ux_unit_piece_links_live_item
    ON untracked_unit_piece_links (session_item_id) WHERE undone_at IS NULL;
CREATE INDEX ix_unit_piece_links_session
    ON untracked_unit_piece_links (tenant_id, session_id);

ALTER TABLE untracked_unit_piece_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE untracked_unit_piece_links FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON untracked_unit_piece_links
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- A link is a record: app_user inserts and undoes (the two undo columns), never edits or deletes.
GRANT SELECT, INSERT ON untracked_unit_piece_links TO app_user;
REVOKE UPDATE, DELETE, TRUNCATE ON untracked_unit_piece_links FROM app_user;
GRANT UPDATE (undone_at, undone_by) ON untracked_unit_piece_links TO app_user;
