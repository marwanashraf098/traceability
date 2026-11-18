-- V236 — Receiving moves from the merchant's Shopify location, M1 (approved by Marawan 2026-11-12, M1 go 2026-11-18).
-- Schema only for the write: M1 has NO Shopify mutation. The tenth named write (pushLocationMove) and its sweep land in
-- M2 and are the only writers of receiving_move_syncs / receiving_move_attempts.
--
-- 1. receipts.move_source_location_gid / _name — the session moves stock FROM this Shopify location (one of the
--    merchant's own active locations, never the Traced location, never an app / fulfillment-service location). Set at
--    session creation only (ReceivingMoveService.createSession, behind the move gate); NULL = an ordinary session.
--    Inbound receipts at the main warehouse only (the service checks the location; the CHECK pins the kind).
-- 2. receipt_lines.source_choice — what happens to units the source still shows after the move: 'keep' (default,
--    "Keep them there") or 'zero' ("Set source to 0", the computed delta −available). 'zero' only ever by an explicit
--    choice (per line or the "Set all to 0" bulk action); a new line always starts 'keep'.
-- 3. receiving_move_progress — the "X of Y" denominator: Y (products with stock available at the source) FROZEN at
--    the first move session from that source, per tenant + source. Written once (INSERT … ON CONFLICT DO NOTHING).
-- 4. receiving_move_syncs — ONE claim row per move session (claim-before-call, M2): queued → pending → pushed |
--    failed (definite, re-sent ≤ 5) | failed_ambiguous (NEVER re-sent) | exhausted | skipped (reason). C1 (approved
--    2026-11-12): failed_ambiguous and exhausted keep the Main reconcile's in-flight skip until a person resolves
--    them — resolved_at / resolved_by / resolution ('applied' | 'not_applied').
-- 5. receiving_move_attempts — every attempt's before / after (M2 "stale → recompute and re-send under the line's
--    rule"; each attempt recorded with what was read and what was sent; outcome NULL while the call is running).
-- RLS: ENABLE + FORCE + the NULLIF policy on every new table; app_user never deletes a claim, an attempt or a frozen
-- progress row.

ALTER TABLE receipts
    ADD COLUMN move_source_location_gid  text,
    ADD COLUMN move_source_location_name text;

ALTER TABLE receipts
    ADD CONSTRAINT receipts_move_source_gid_shape
        CHECK (move_source_location_gid IS NULL OR move_source_location_gid ~ '^gid://shopify/Location/[0-9]+$'),
    ADD CONSTRAINT receipts_move_source_named
        CHECK ((move_source_location_gid IS NULL) = (move_source_location_name IS NULL)),
    ADD CONSTRAINT receipts_move_source_inbound
        CHECK (move_source_location_gid IS NULL OR kind = 'inbound');

CREATE INDEX receipts_move_source_idx ON receipts (tenant_id, move_source_location_gid)
    WHERE move_source_location_gid IS NOT NULL;

ALTER TABLE receipt_lines
    ADD COLUMN source_choice text NOT NULL DEFAULT 'keep' CHECK (source_choice IN ('keep', 'zero'));

-- 3. Frozen progress denominator.
CREATE TABLE receiving_move_progress (
    tenant_id             uuid        NOT NULL REFERENCES tenants(id),
    source_location_gid   text        NOT NULL CHECK (source_location_gid ~ '^gid://shopify/Location/[0-9]+$'),
    total_products        integer     NOT NULL CHECK (total_products >= 0),
    frozen_at             timestamptz NOT NULL DEFAULT now(),
    frozen_by_receipt_id  uuid        NOT NULL,
    PRIMARY KEY (tenant_id, source_location_gid),
    CONSTRAINT receiving_move_progress_receipt_fk
        FOREIGN KEY (frozen_by_receipt_id, tenant_id) REFERENCES receipts (id, tenant_id)
);

ALTER TABLE receiving_move_progress ENABLE ROW LEVEL SECURITY;
ALTER TABLE receiving_move_progress FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON receiving_move_progress
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
-- Frozen once: never changed or removed by the app.
REVOKE UPDATE, DELETE, TRUNCATE ON receiving_move_progress FROM app_user;

-- 4. The claim (M2 writes it).
CREATE TABLE receiving_move_syncs (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid        NOT NULL REFERENCES tenants(id),
    receipt_id           uuid        NOT NULL,
    source_location_gid  text        NOT NULL CHECK (source_location_gid ~ '^gid://shopify/Location/[0-9]+$'),
    status               text        NOT NULL
        CHECK (status IN ('queued', 'pending', 'pushed', 'failed', 'failed_ambiguous', 'exhausted', 'skipped')),
    reason               text,
    lines                jsonb       NOT NULL DEFAULT '[]'::jsonb,
    attempt_count        integer     NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_idempotency_key text,
    error                text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    send_started_at      timestamptz,
    pushed_at            timestamptz,
    resolved_at          timestamptz,
    resolved_by          uuid        REFERENCES users(id),
    resolution           text        CHECK (resolution IN ('applied', 'not_applied')),
    CONSTRAINT receiving_move_syncs_one_per_session UNIQUE (receipt_id),
    CONSTRAINT receiving_move_syncs_receipt_fk FOREIGN KEY (receipt_id, tenant_id) REFERENCES receipts (id, tenant_id),
    CONSTRAINT receiving_move_syncs_skip_reason CHECK (status <> 'skipped' OR reason IS NOT NULL),
    CONSTRAINT receiving_move_syncs_resolution_whole
        CHECK ((resolved_at IS NULL) = (resolution IS NULL) AND (resolved_at IS NULL) = (resolved_by IS NULL)),
    CONSTRAINT receiving_move_syncs_resolution_only_unsettled
        CHECK (resolution IS NULL OR status IN ('failed_ambiguous', 'exhausted'))
);

CREATE INDEX receiving_move_syncs_open_idx ON receiving_move_syncs (tenant_id, status, created_at)
    WHERE status IN ('queued', 'pending', 'failed', 'failed_ambiguous', 'exhausted') AND resolved_at IS NULL;

ALTER TABLE receiving_move_syncs ENABLE ROW LEVEL SECURITY;
ALTER TABLE receiving_move_syncs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON receiving_move_syncs
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
-- A claim is a record of what was (or wasn't) sent: app_user never deletes one.
REVOKE DELETE, TRUNCATE ON receiving_move_syncs FROM app_user;

-- 5. Every attempt, append-only.
CREATE TABLE receiving_move_attempts (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES tenants(id),
    sync_id           uuid        NOT NULL REFERENCES receiving_move_syncs(id),
    attempt_no        integer     NOT NULL CHECK (attempt_no >= 1),
    idempotency_key   text        NOT NULL,
    before_levels     jsonb       NOT NULL,
    sent_changes      jsonb       NOT NULL,
    outcome           text
        CHECK (outcome IN ('pushed', 'stale', 'failed', 'failed_ambiguous', 'not_sent')),
    after_levels      jsonb,
    error             text,
    started_at        timestamptz NOT NULL DEFAULT now(),
    finished_at       timestamptz,
    CONSTRAINT receiving_move_attempts_once UNIQUE (sync_id, attempt_no)
);

ALTER TABLE receiving_move_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE receiving_move_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON receiving_move_attempts
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
-- Started before the call, finished after it (outcome / after_levels): never deleted.
REVOKE DELETE, TRUNCATE ON receiving_move_attempts FROM app_user;
