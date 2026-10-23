-- Flexible labeling slice 7 — a stock take counts UNTAGGED pieces (short_code IS NULL) per variant.
-- They can't be scanned, so the person running the count types how many are on the shelf; finalize
-- writes off the shortfall (oldest first) through the existing stock-take write-off and never adds
-- pieces for a surplus.

-- Whether each expected piece was untagged when the session opened. The labeled rules (scan, Found,
-- write-off, damage correction) never touch a row with this set; only the counted shortfall does.
-- Constant default: metadata-only in Postgres 11+ (prod: ~6.4k rows).
ALTER TABLE stock_take_expected ADD COLUMN untagged_at_open boolean NOT NULL DEFAULT false;

CREATE TABLE stock_take_untagged_counts (
    tenant_id  uuid        NOT NULL REFERENCES tenants(id),
    session_id uuid        NOT NULL REFERENCES stock_take_sessions(id),
    variant_id uuid        NOT NULL REFERENCES variants(id),
    counted    integer     NOT NULL CHECK (counted >= 0),
    counted_by uuid        NOT NULL REFERENCES users(id),
    counted_at timestamptz NOT NULL DEFAULT now(),
    -- The untagged pieces finalize compared the count with (NULL while the session is open).
    expected_at_finalize integer CHECK (expected_at_finalize >= 0),
    PRIMARY KEY (session_id, variant_id)
);

ALTER TABLE stock_take_untagged_counts ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_take_untagged_counts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stock_take_untagged_counts
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
