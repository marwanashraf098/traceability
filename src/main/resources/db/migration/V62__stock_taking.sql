-- ============================================================
-- V62 — FR-21 Step 1: Stock Taking schema
--
-- Five tenant-scoped tables, each with tenant_id + a tenant_isolation RLS
-- policy in this same migration (invariant #3). Mirrors the receiving-session
-- shape (stock_take_sessions ~ receipts): truth still lives in piece_events
-- via InventoryLedger.transition() — these tables track the count session
-- itself, never piece state directly.
--
-- Deviation from the build spec's literal wording: piece_id columns below
-- are `text`, not `uuid`. pieces.id is an app-generated ULID stored as text
-- (see MigrationSmokeTest's "pieces.id must be text" assertion) — a uuid
-- column could never carry a valid FK to pieces(id). The spec's "piece_id
-- uuid" was a typo against the existing schema; this migration uses the
-- type that actually matches.
-- ============================================================

-- ── stock_take_sessions ──────────────────────────────────────────────────

CREATE TABLE stock_take_sessions (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenants(id),
    status         text        NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'finalized', 'cancelled')),
    scope_type     text        NOT NULL
        CHECK (scope_type IN ('all', 'variant_subset')),
    location_id    uuid        REFERENCES locations(id),
    complete_count boolean     NOT NULL DEFAULT false,
    opened_by      uuid        NOT NULL REFERENCES users(id),
    opened_at      timestamptz NOT NULL DEFAULT now(),
    finalized_by   uuid        REFERENCES users(id),
    finalized_at   timestamptz,
    note           text
);

ALTER TABLE stock_take_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_take_sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stock_take_sessions
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ── stock_take_scope_variants ────────────────────────────────────────────
-- Rows only when scope_type='variant_subset'. Composite PK doubles as the
-- spec's UNIQUE(session_id, variant_id) — a pure membership table needs no
-- surrogate id.

CREATE TABLE stock_take_scope_variants (
    session_id uuid NOT NULL REFERENCES stock_take_sessions(id),
    variant_id uuid NOT NULL REFERENCES variants(id),
    tenant_id  uuid NOT NULL REFERENCES tenants(id),
    PRIMARY KEY (session_id, variant_id)
);

ALTER TABLE stock_take_scope_variants ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_take_scope_variants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stock_take_scope_variants
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ── stock_take_expected — frozen full-population snapshot at open ───────

CREATE TABLE stock_take_expected (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenants(id),
    session_id     uuid        NOT NULL REFERENCES stock_take_sessions(id),
    piece_id       text        NOT NULL REFERENCES pieces(id),
    variant_id     uuid        NOT NULL REFERENCES variants(id),
    status_at_open text        NOT NULL,
    UNIQUE (session_id, piece_id)
);

CREATE INDEX stock_take_expected_session_idx ON stock_take_expected (session_id);

ALTER TABLE stock_take_expected ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_take_expected FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stock_take_expected
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ── stock_take_scans — blind scan log ────────────────────────────────────

CREATE TABLE stock_take_scans (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES tenants(id),
    session_id        uuid        NOT NULL REFERENCES stock_take_sessions(id),
    piece_id          text        REFERENCES pieces(id),
    raw_barcode       text,
    scanned_condition text        NOT NULL CHECK (scanned_condition IN ('good', 'damaged')),
    source            text        NOT NULL CHECK (source IN ('scan', 'manager_found')),
    actor_user_id     uuid        NOT NULL REFERENCES users(id),
    scanned_at        timestamptz NOT NULL DEFAULT now(),
    CHECK (piece_id IS NOT NULL OR raw_barcode IS NOT NULL)
);

-- Idempotent re-scan: one row per (session, piece) once resolved. Unknown
-- scans (piece_id NULL, raw_barcode set) are never deduplicated — every
-- unresolved scan is logged as its own row.
CREATE UNIQUE INDEX stock_take_scans_session_piece_uniq
    ON stock_take_scans (session_id, piece_id) WHERE piece_id IS NOT NULL;
CREATE INDEX stock_take_scans_session_idx ON stock_take_scans (session_id);

ALTER TABLE stock_take_scans ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_take_scans FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stock_take_scans
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ── stock_take_shopify_syncs — claim-before-call referee ─────────────────
-- Schema only in this migration. The live decrement push (Step 5) is out of
-- scope for this build; this table exists now so double-finalize is safe
-- once that step lands — UNIQUE(session_id) is the referee.

CREATE TABLE stock_take_shopify_syncs (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenants(id),
    session_id uuid        NOT NULL REFERENCES stock_take_sessions(id),
    status     text        NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'shadow_logged', 'pushed', 'failed')),
    payload    jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    pushed_at  timestamptz,
    UNIQUE (session_id)
);

ALTER TABLE stock_take_shopify_syncs ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_take_shopify_syncs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stock_take_shopify_syncs
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
