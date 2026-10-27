-- V187 — Flexible labeling slice 6a: factory runs (approved 2026-10-27).
--
-- A factory run is an order to a factory: per line a variant and the number of pieces expected. Its print issues
-- one named FACTORY label code per expected piece (kind 'factory', from the same PieceCodeCounter as every other
-- code); the factory sticks each label on a matching piece. The pieces enter stock only when the run is received
-- (slice 6b: Receiving → "Factory-labeled", pieces created WITH those codes). Until then no scan path binds a
-- factory code (InventoryLedger.bindLabel refuses kind factory).
--   - label_runs: one row per run. app_user may only close it (status, closed_at, closed_by).
--   - label_run_lines: one row per variant of a run; the close columns record the reason for a line's gap at
--     close (slice 6c). app_user may only write those.
--   - label_codes.run_line_id: the line a factory code was printed for — set exactly for kind 'factory'.
--     label_codes.overage: a factory code printed for a piece that arrived without a label after its line was
--     already full (slice 6b) — a flag only, never a block.
--   - label_code_batches.run_id: the run a factory print belongs to — set exactly for kind 'factory'.
-- label_codes / label_code_batches had no factory rows in production when this was written (checked 2026-10-27:
-- 194 codes, 8 batches, none factory), so both kind CHECKs hold with no backfill. All tables are small: plain DDL.

-- 1. Runs.
CREATE TABLE label_runs (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenants(id),
    factory_name  text        NOT NULL CHECK (length(btrim(factory_name)) BETWEEN 1 AND 120),
    reference     text        CHECK (reference IS NULL OR length(btrim(reference)) BETWEEN 1 AND 100),
    status        text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
    created_by    uuid        NOT NULL REFERENCES users(id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    closed_by     uuid        REFERENCES users(id),
    closed_at     timestamptz,
    CONSTRAINT label_runs_closed_has_time CHECK ((status = 'closed') = (closed_at IS NOT NULL)),
    CONSTRAINT label_runs_closed_has_user CHECK ((closed_at IS NULL) = (closed_by IS NULL)),
    UNIQUE (id, tenant_id)
);
CREATE INDEX ix_label_runs_tenant_created ON label_runs (tenant_id, created_at DESC, id DESC);

ALTER TABLE label_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE label_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON label_runs
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

REVOKE UPDATE, DELETE, TRUNCATE ON label_runs FROM app_user;
GRANT UPDATE (status, closed_by, closed_at) ON label_runs TO app_user;

-- 2. Lines.
CREATE TABLE label_run_lines (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL REFERENCES tenants(id),
    run_id             uuid        NOT NULL,
    line_no            int         NOT NULL CHECK (line_no >= 1),
    variant_id         uuid        NOT NULL REFERENCES variants(id),
    expected_qty       int         NOT NULL CHECK (expected_qty BETWEEN 1 AND 20000),
    close_reason       text        CHECK (close_reason IN ('short_shipped', 'lost', 'still_coming')),
    close_recorded_at  timestamptz,
    close_recorded_by  uuid        REFERENCES users(id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (run_id, tenant_id) REFERENCES label_runs (id, tenant_id),
    CONSTRAINT label_run_lines_reason_has_time CHECK ((close_reason IS NULL) = (close_recorded_at IS NULL)),
    CONSTRAINT label_run_lines_reason_has_user CHECK ((close_recorded_at IS NULL) = (close_recorded_by IS NULL)),
    UNIQUE (run_id, variant_id),
    UNIQUE (run_id, line_no),
    UNIQUE (id, tenant_id)
);

ALTER TABLE label_run_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE label_run_lines FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON label_run_lines
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

REVOKE UPDATE, DELETE, TRUNCATE ON label_run_lines FROM app_user;
GRANT UPDATE (close_reason, close_recorded_at, close_recorded_by) ON label_run_lines TO app_user;

-- 3. Factory codes name their line; a factory print names its run. Both are set once, at INSERT (V176's column
--    grant on label_codes stays the lifecycle columns only).
ALTER TABLE label_codes
    ADD COLUMN run_line_id uuid,
    ADD COLUMN overage     boolean NOT NULL DEFAULT false;
ALTER TABLE label_codes ADD CONSTRAINT label_codes_run_line_fk
    FOREIGN KEY (run_line_id, tenant_id) REFERENCES label_run_lines (id, tenant_id);
ALTER TABLE label_codes ADD CONSTRAINT label_codes_factory_has_run_line
    CHECK ((kind = 'factory') = (run_line_id IS NOT NULL));
ALTER TABLE label_codes ADD CONSTRAINT label_codes_overage_is_factory
    CHECK (NOT overage OR kind = 'factory');
CREATE INDEX ix_label_codes_run_line ON label_codes (tenant_id, run_line_id) WHERE run_line_id IS NOT NULL;

ALTER TABLE label_code_batches ADD COLUMN run_id uuid;
ALTER TABLE label_code_batches ADD CONSTRAINT label_code_batches_run_fk
    FOREIGN KEY (run_id, tenant_id) REFERENCES label_runs (id, tenant_id);
ALTER TABLE label_code_batches ADD CONSTRAINT label_code_batches_factory_has_run
    CHECK ((kind = 'factory') = (run_id IS NOT NULL));
CREATE INDEX ix_label_code_batches_run ON label_code_batches (tenant_id, run_id) WHERE run_id IS NOT NULL;
