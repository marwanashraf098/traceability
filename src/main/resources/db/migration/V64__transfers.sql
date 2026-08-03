-- ============================================================
-- V64 — FR-22.1: Transfers & External Custody schema
--
-- Three tenant-scoped tables, each with tenant_id + a tenant_isolation RLS
-- policy in this same migration (invariant #3). No piece status enum or
-- InventoryLedger changes here — that's FR-22.2, behind gate G1.
--
-- piece_id is `text`, not `uuid`: pieces.id is an app-generated ULID stored
-- as text (see MigrationSmokeTest's "pieces.id must be text" assertion and
-- V62's identical deviation note) — a uuid column could never carry a valid
-- FK to pieces(id).
--
-- Destination-location convention (no schema change): showroom destinations
-- use the existing location_type 'showroom'; dryclean/repair/other use the
-- existing 'vendor'. The workflow distinction lives in transfers.transfer_type,
-- not in location_type. LocationController.create() already accepts
-- is_fulfillment=false with any location_type, including these two.
-- ============================================================

-- ── transfers ─────────────────────────────────────────────────────────────

CREATE TABLE transfers (
    id                       uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                uuid        NOT NULL REFERENCES tenants(id),
    transfer_type            text        NOT NULL
        CHECK (transfer_type IN ('showroom', 'dryclean', 'repair', 'other')),
    destination_location_id  uuid        NOT NULL REFERENCES locations(id),
    status                   text        NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'reconciling', 'closed')),
    note                     text,
    expected_return_at       timestamptz,
    created_by               uuid        NOT NULL REFERENCES users(id),
    created_at               timestamptz NOT NULL DEFAULT now(),
    closed_by                uuid        REFERENCES users(id),
    closed_at                timestamptz
);

ALTER TABLE transfers ENABLE ROW LEVEL SECURITY;
ALTER TABLE transfers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON transfers
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ── transfer_lines ────────────────────────────────────────────────────────

CREATE TABLE transfer_lines (
    id                 uuid    PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid    NOT NULL REFERENCES tenants(id),
    transfer_id        uuid    NOT NULL REFERENCES transfers(id),
    variant_id         uuid    NOT NULL REFERENCES variants(id),
    qty_out            int     NOT NULL DEFAULT 0,
    qty_returned_good  int     NOT NULL DEFAULT 0,
    qty_condemned      int     NOT NULL DEFAULT 0,
    qty_sold           int     NOT NULL DEFAULT 0,
    qty_lost           int     NOT NULL DEFAULT 0,
    UNIQUE (transfer_id, variant_id)
);

CREATE INDEX transfer_lines_transfer_idx ON transfer_lines (transfer_id);

ALTER TABLE transfer_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE transfer_lines FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON transfer_lines
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ── transfer_pieces ───────────────────────────────────────────────────────

CREATE TABLE transfer_pieces (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES tenants(id),
    transfer_id       uuid        NOT NULL REFERENCES transfers(id),
    line_id           uuid        NOT NULL REFERENCES transfer_lines(id),
    piece_id          text        NOT NULL REFERENCES pieces(id),
    outcome           text        CHECK (outcome IN
                          ('returned_good', 'condemned', 'sold', 'lost')),
    outcome_verified  boolean,
    outcome_at        timestamptz,
    outcome_by        uuid        REFERENCES users(id)
);

CREATE INDEX transfer_pieces_transfer_idx ON transfer_pieces (transfer_id);
CREATE INDEX transfer_pieces_line_idx     ON transfer_pieces (line_id);

-- Concurrency referee (claim-before-call, mirrors allocations_piece_active_unique):
-- a piece may be on at most one active (unresolved) transfer at a time.
-- Send-out scan does the INSERT; this index rejects the loser.
CREATE UNIQUE INDEX transfer_pieces_one_active
    ON transfer_pieces (piece_id)
    WHERE outcome IS NULL;

ALTER TABLE transfer_pieces ENABLE ROW LEVEL SECURITY;
ALTER TABLE transfer_pieces FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON transfer_pieces
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
