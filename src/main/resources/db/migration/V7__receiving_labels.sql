-- V7: Receiving session status + lines, label reprints, worker toggle.

-- Add status lifecycle + finalized_at to receipts.
ALTER TABLE receipts
    ADD COLUMN status       text        NOT NULL DEFAULT 'open',
    ADD COLUMN finalized_at timestamptz;

-- Per-tenant label size preference (null = default 50x25mm).
ALTER TABLE tenants
    ADD COLUMN label_width_mm  numeric(5,2),
    ADD COLUMN label_height_mm numeric(5,2),
    ADD COLUMN worker_receiving_enabled boolean NOT NULL DEFAULT false;

-- receipt_lines: items staged before finalization.
CREATE TABLE receipt_lines (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenants(id),
    receipt_id  uuid        NOT NULL REFERENCES receipts(id),
    variant_id  uuid        NOT NULL REFERENCES variants(id),
    quantity    integer     NOT NULL CHECK (quantity > 0),
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- label_reprints: audit log for every label print/reprint.
CREATE TABLE label_reprints (
    id           bigserial   PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenants(id),
    receipt_id   uuid        NOT NULL REFERENCES receipts(id),
    reprinted_by uuid        REFERENCES users(id),
    piece_count  integer     NOT NULL,
    note         text,
    reprinted_at timestamptz NOT NULL DEFAULT now()
);

-- RLS on new tables.
ALTER TABLE receipt_lines  ENABLE ROW LEVEL SECURITY;
ALTER TABLE receipt_lines  FORCE ROW LEVEL SECURITY;
ALTER TABLE label_reprints ENABLE ROW LEVEL SECURITY;
ALTER TABLE label_reprints FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON receipt_lines
    USING      (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE POLICY tenant_isolation ON label_reprints
    USING      (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Grants for app_user.
GRANT SELECT, INSERT, UPDATE, DELETE ON receipt_lines  TO app_user;
GRANT SELECT, INSERT                  ON label_reprints TO app_user;
GRANT USAGE, SELECT ON SEQUENCE label_reprints_id_seq  TO app_user;

-- Indexes.
CREATE INDEX receipt_lines_receipt_idx  ON receipt_lines  (receipt_id);
CREATE INDEX receipts_tenant_status_idx ON receipts       (tenant_id, status);
CREATE INDEX label_reprints_receipt_idx ON label_reprints (receipt_id);
