-- V188 — Flexible labeling slice 6b: factory receiving (approved 2026-10-27).
--
-- A FACTORY receiving session receives one factory run (V187): the pieces arrived wearing the run's printed factory
-- labels; the worker scans each one, and only scanned pieces enter stock. A scan is STAGED here first; finalize
-- creates the pieces with exactly the scanned codes and binds those codes (InventoryLedger, one transaction, all or
-- nothing) — Shopify +N through the existing receiving trigger, unchanged.
--   - receipts.mode gains 'factory' (drop + re-add of V178's CHECK; V178 is not edited), and receipts.label_run_id
--     names the run — set exactly for mode 'factory'. Set once, at INSERT.
--   - receipt_factory_scans: one row per staged scan. At most ONE live (not undone) row per label code across every
--     session (partial UNIQUE) — the real guard against the same piece scanned twice or in two sessions.
--     Undo sets undone_at / undone_by (never DELETE: app_user has no DELETE). The rows of a deleted OPEN session go
--     with it (ON DELETE CASCADE) — ReceivingService refuses that delete while any of its scans is live, so only
--     undone rows ever cascade.
-- receipts (176 rows) and label_codes (194 rows) are tiny in production (checked 2026-10-27): plain DDL.

-- 1. Receipts: the factory mode and its run.
ALTER TABLE receipts DROP CONSTRAINT receipts_mode_check;
ALTER TABLE receipts ADD CONSTRAINT receipts_mode_check CHECK (mode IN ('print_stick', 'count_only', 'factory'));

ALTER TABLE receipts ADD CONSTRAINT receipts_id_tenant_key UNIQUE (id, tenant_id);
ALTER TABLE receipts ADD COLUMN label_run_id uuid;
ALTER TABLE receipts ADD CONSTRAINT receipts_label_run_fk
    FOREIGN KEY (label_run_id, tenant_id) REFERENCES label_runs (id, tenant_id);
ALTER TABLE receipts ADD CONSTRAINT receipts_factory_has_run
    CHECK ((mode = 'factory') = (label_run_id IS NOT NULL));
CREATE INDEX ix_receipts_label_run ON receipts (tenant_id, label_run_id) WHERE label_run_id IS NOT NULL;

-- 2. Staged scans.
ALTER TABLE label_codes ADD CONSTRAINT label_codes_id_tenant_key UNIQUE (id, tenant_id);

CREATE TABLE receipt_factory_scans (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenants(id),
    receipt_id     uuid        NOT NULL,
    label_code_id  uuid        NOT NULL,
    run_line_id    uuid        NOT NULL,
    variant_id     uuid        NOT NULL REFERENCES variants(id),
    scanned_by     uuid        NOT NULL REFERENCES users(id),
    scanned_at     timestamptz NOT NULL DEFAULT now(),
    via_phone      boolean     NOT NULL DEFAULT false,
    undone_at      timestamptz,
    undone_by      uuid        REFERENCES users(id),
    FOREIGN KEY (receipt_id, tenant_id)    REFERENCES receipts (id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (label_code_id, tenant_id) REFERENCES label_codes (id, tenant_id),
    FOREIGN KEY (run_line_id, tenant_id)   REFERENCES label_run_lines (id, tenant_id),
    CONSTRAINT receipt_factory_scans_undone_has_user CHECK ((undone_at IS NULL) = (undone_by IS NULL))
);

CREATE UNIQUE INDEX ux_receipt_factory_scans_live ON receipt_factory_scans (label_code_id) WHERE undone_at IS NULL;
CREATE INDEX ix_receipt_factory_scans_receipt ON receipt_factory_scans (tenant_id, receipt_id, scanned_at DESC, id DESC)
    WHERE undone_at IS NULL;
CREATE INDEX ix_receipt_factory_scans_line ON receipt_factory_scans (tenant_id, run_line_id) WHERE undone_at IS NULL;

ALTER TABLE receipt_factory_scans ENABLE ROW LEVEL SECURITY;
ALTER TABLE receipt_factory_scans FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON receipt_factory_scans
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- A scan is a record: app_user inserts and undoes (the two undo columns), never edits or deletes.
REVOKE UPDATE, DELETE, TRUNCATE ON receipt_factory_scans FROM app_user;
GRANT UPDATE (undone_at, undone_by) ON receipt_factory_scans TO app_user;
