-- V176 — Flexible labeling slice 1: blank label codes (approved 2026-10-21).
--
-- A blank label carries a code and nothing else (no product name). Codes are ordinary piece
-- short codes ("P" + 6 or more digits) claimed from the SAME per-tenant counter receiving uses
-- (piece_counters, PieceCodeCounter), so a blank code never collides with a received piece's
-- short_code and every scanner already reads it as a piece. A code is issued here and, later,
-- bound to exactly one piece (slice 3: InventoryLedger.bindLabel sets pieces.short_code to it) or
-- voided. Nothing in this migration changes any existing row or behaviour.
--   - label_code_batches: one row per print request; the idempotency key makes a retried request
--     return the same batch instead of issuing twice. Immutable for app_user.
--   - label_codes: one row per code. app_user may change only the lifecycle columns, never DELETE.
-- run_id (factory runs, slice 6) and order seals (slice 8) come in their own migrations.

-- 1. The per-tenant switch (default off; set by ops while the feature is built).
ALTER TABLE tenants ADD COLUMN flexible_labeling_enabled boolean NOT NULL DEFAULT false;

-- 2. Batches.
CREATE TABLE label_code_batches (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid         NOT NULL REFERENCES tenants(id),
    kind             text         NOT NULL CHECK (kind IN ('pack', 'tag', 'factory', 'replacement')),
    count            int          NOT NULL CHECK (count >= 0),
    spares           int          NOT NULL DEFAULT 0 CHECK (spares >= 0),
    label_w_mm       numeric(5,2) NOT NULL CHECK (label_w_mm > 0),
    label_h_mm       numeric(5,2) NOT NULL CHECK (label_h_mm > 0),
    idempotency_key  text         NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 100),
    created_by       uuid         NOT NULL REFERENCES users(id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    CHECK (count + spares BETWEEN 1 AND 2000),
    UNIQUE (tenant_id, idempotency_key),
    UNIQUE (id, tenant_id)
);

ALTER TABLE label_code_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE label_code_batches FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON label_code_batches
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- A batch is a record of what was printed: app_user inserts and reads, never edits or deletes.
REVOKE UPDATE, DELETE, TRUNCATE ON label_code_batches FROM app_user;

-- 3. Codes.
CREATE TABLE label_codes (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenants(id),
    code         text        NOT NULL CHECK (code ~ '^P[0-9]{6,}$'),
    status       text        NOT NULL DEFAULT 'issued' CHECK (status IN ('issued', 'bound', 'voided')),
    kind         text        NOT NULL CHECK (kind IN ('pack', 'tag', 'factory', 'replacement')),
    batch_id     uuid        NOT NULL,
    piece_id     text        REFERENCES pieces(id),
    issued_by    uuid        NOT NULL REFERENCES users(id),
    issued_at    timestamptz NOT NULL DEFAULT now(),
    bound_by     uuid        REFERENCES users(id),
    bound_at     timestamptz,
    voided_at    timestamptz,
    void_reason  text        CHECK (length(void_reason) BETWEEN 1 AND 100),
    created_at   timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (batch_id, tenant_id) REFERENCES label_code_batches (id, tenant_id),
    UNIQUE (tenant_id, code),
    CONSTRAINT label_codes_bound_has_piece   CHECK ((status = 'bound') = (piece_id IS NOT NULL)),
    CONSTRAINT label_codes_bound_has_time    CHECK ((status = 'bound') = (bound_at IS NOT NULL)),
    CONSTRAINT label_codes_voided_has_time   CHECK ((status = 'voided') = (voided_at IS NOT NULL)),
    CONSTRAINT label_codes_voided_has_reason CHECK ((voided_at IS NULL) = (void_reason IS NULL))
);

-- One code per piece, ever.
CREATE UNIQUE INDEX ux_label_codes_piece ON label_codes (piece_id) WHERE piece_id IS NOT NULL;
CREATE INDEX ix_label_codes_batch ON label_codes (tenant_id, batch_id);

ALTER TABLE label_codes ENABLE ROW LEVEL SECURITY;
ALTER TABLE label_codes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON label_codes
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- app_user moves a code through its lifecycle (issued → bound | voided) and nothing else.
REVOKE UPDATE, DELETE, TRUNCATE ON label_codes FROM app_user;
GRANT UPDATE (status, piece_id, bound_by, bound_at, voided_at, void_reason) ON label_codes TO app_user;
