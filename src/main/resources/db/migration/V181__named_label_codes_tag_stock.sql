-- V181 — Flexible labeling slice 3: NAMED label codes + Tag stock (plan change approved 2026-10-21).
--
-- No blank labels anywhere: every label code belongs to ONE variant and is printed as the normal named
-- piece label (code, title, variant, SKU). A code is bound to an untagged available piece OF ITS VARIANT at
-- the main warehouse (InventoryLedger.bindLabel). label_codes / label_code_batches had 0 rows in prod when
-- this was written (checked 2026-10-23), so variant_id can be NOT NULL with no backfill.
--   - label_codes.variant_id NOT NULL; order_id / order_item_id NULL — packing print grouping only (slice 4).
--   - label_code_batches: spares and the blank-label size columns are gone (spares dropped; named labels print
--     exactly like piece labels today); tag_session_id links a Tag stock print to its session.
--   - tag_sessions: one Tag stock session at the main warehouse. RLS NULLIF + FORCE; app_user ends a session
--     (ended_at / ended_by) and never deletes one.

-- 1. Codes carry their variant (and, for packing, the order line they were printed for).
ALTER TABLE label_codes
    ADD COLUMN variant_id    uuid NOT NULL REFERENCES variants(id),
    ADD COLUMN order_id      uuid REFERENCES orders(id),
    ADD COLUMN order_item_id uuid REFERENCES order_items(id);
ALTER TABLE label_codes ADD CONSTRAINT label_codes_order_item_needs_order
    CHECK (order_item_id IS NULL OR order_id IS NOT NULL);
CREATE INDEX ix_label_codes_issued_variant ON label_codes (tenant_id, variant_id) WHERE status = 'issued';

-- 2. Tag stock sessions.
CREATE TABLE tag_sessions (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenants(id),
    location_id  uuid        NOT NULL REFERENCES locations(id),
    started_by   uuid        NOT NULL REFERENCES users(id),
    started_at   timestamptz NOT NULL DEFAULT now(),
    ended_at     timestamptz,
    ended_by     uuid        REFERENCES users(id),
    CHECK ((ended_at IS NULL) = (ended_by IS NULL)),
    UNIQUE (id, tenant_id)
);
CREATE INDEX ix_tag_sessions_open ON tag_sessions (tenant_id, started_by) WHERE ended_at IS NULL;

ALTER TABLE tag_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE tag_sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tag_sessions
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

REVOKE UPDATE, DELETE, TRUNCATE ON tag_sessions FROM app_user;
GRANT UPDATE (ended_at, ended_by) ON tag_sessions TO app_user;

-- 3. Batches: no spares, no blank-label size; a Tag stock print names its session.
ALTER TABLE label_code_batches DROP COLUMN spares;       -- also drops CHECK (count + spares BETWEEN 1 AND 2000)
ALTER TABLE label_code_batches DROP COLUMN label_w_mm, DROP COLUMN label_h_mm;
-- V176's column CHECK (count >= 0) took the auto name label_code_batches_count_check — replaced, not doubled.
ALTER TABLE label_code_batches DROP CONSTRAINT label_code_batches_count_check;
ALTER TABLE label_code_batches ADD CONSTRAINT label_code_batches_count_check CHECK (count BETWEEN 1 AND 2000);
ALTER TABLE label_code_batches ADD COLUMN tag_session_id uuid;
ALTER TABLE label_code_batches ADD CONSTRAINT label_code_batches_tag_session_fk
    FOREIGN KEY (tag_session_id, tenant_id) REFERENCES tag_sessions (id, tenant_id);
