-- ============================================================
-- V73 — FR-24: Returns, session-based rebuild
--
-- Three tenant-scoped tables, each with tenant_id + a tenant_isolation RLS
-- policy in this same migration (invariant #3). Replaces the old waybill-first
-- returns session, which rode the shared `receipts` table (kind='returns',
-- V29__returns_session.sql) — that table/kind is left untouched; old closed
-- sessions become inert history (piece history stays intact in piece_events),
-- no backfill into these new tables.
--
-- piece_id is `text`, not `uuid`: pieces.id is an app-generated ULID stored
-- as text (see V62/V64's identical deviation note).
-- ============================================================

-- ── return_sessions ───────────────────────────────────────────────────────

CREATE TABLE return_sessions (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenants(id),
    status      text        NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'closed', 'abandoned')),
    opened_by   uuid        REFERENCES users(id),
    opened_at   timestamptz NOT NULL DEFAULT now(),
    closed_by   uuid        REFERENCES users(id),
    closed_at   timestamptz,
    note        text
);

-- Claim-before-call: at most one open return session per tenant. The INSERT
-- itself is the guard — a concurrent second createSession() hits the unique
-- violation and translates to 409. Abandoned/closed sessions don't count.
CREATE UNIQUE INDEX return_sessions_one_open_per_tenant
    ON return_sessions (tenant_id)
    WHERE status = 'open';

CREATE INDEX return_sessions_tenant_opened_idx
    ON return_sessions (tenant_id, opened_at DESC);

ALTER TABLE return_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE return_sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON return_sessions
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ── return_session_items ─────────────────────────────────────────────────

CREATE TABLE return_session_items (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenants(id),
    session_id      uuid        NOT NULL REFERENCES return_sessions(id),
    piece_id        text        NOT NULL REFERENCES pieces(id),
    scanned_at      timestamptz NOT NULL DEFAULT now(),
    scanned_by      uuid        REFERENCES users(id),
    scan_source     text        NOT NULL CHECK (scan_source IN ('barcode', 'awb')),
    disposition     text        NOT NULL DEFAULT 'pending'
        CHECK (disposition IN ('pending', 'restocked', 'damaged', 'mismatch')),
    disposition_at  timestamptz,
    disposition_by  uuid        REFERENCES users(id),
    damage_reason   text,
    unexpected      boolean     NOT NULL DEFAULT false,
    UNIQUE (session_id, piece_id)
);

CREATE INDEX return_session_items_session_idx ON return_session_items (session_id);

-- Backs the "unassigned pending returns" landing query: pieces at
-- return_pending_inspection with no open-session item and no resolved
-- (non-pending) item anywhere.
CREATE INDEX return_session_items_piece_idx ON return_session_items (piece_id);

ALTER TABLE return_session_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE return_session_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON return_session_items
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ── return_session_shipments ─────────────────────────────────────────────
-- One row per distinct AWB scanned into a session — close-summary shipment
-- count + AWB audit trail. Not a source of truth for pieces (that's
-- return_session_items); a piece only becomes a real item row when
-- physically piece-scanned.

CREATE TABLE return_session_shipments (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenants(id),
    session_id  uuid        NOT NULL REFERENCES return_sessions(id),
    awb         text        NOT NULL,
    linked_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (session_id, awb)
);

CREATE INDEX return_session_shipments_session_idx ON return_session_shipments (session_id);

ALTER TABLE return_session_shipments ENABLE ROW LEVEL SECURITY;
ALTER TABLE return_session_shipments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON return_session_shipments
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
