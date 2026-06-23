-- FR-7.9: Blocklist table for blocked customers.
-- phone_canonical stores 11-digit Egyptian form (01XXXXXXXXX) — same normalizePhone() as ShipmentLinkService.
-- source: manual (operator) | bosta_rejected (auto-detected via Bosta AWB, deferred gate-c).
-- active: false = soft-deleted (unblocked).  Hard index on (tenant_id, phone_canonical) for ≤50ms gate check.

CREATE TYPE blocklist_source AS ENUM ('manual', 'bosta_rejected');

CREATE TABLE blocklist (
    id             uuid            PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid            NOT NULL REFERENCES tenants(id),
    phone_canonical text           NOT NULL,
    reason          text           NOT NULL,
    source          blocklist_source NOT NULL DEFAULT 'manual',
    created_by      uuid           REFERENCES users(id),
    created_at      timestamptz    NOT NULL DEFAULT now(),
    active          boolean        NOT NULL DEFAULT true,
    UNIQUE (tenant_id, phone_canonical) DEFERRABLE INITIALLY DEFERRED
);

-- Fast lookup for gate check and management list
CREATE INDEX idx_blocklist_tenant_phone ON blocklist (tenant_id, phone_canonical) WHERE active = true;

-- RLS: each tenant sees only its own entries
ALTER TABLE blocklist ENABLE ROW LEVEL SECURITY;
CREATE POLICY blocklist_tenant_isolation ON blocklist
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- GRANT app_user access
GRANT SELECT, INSERT, UPDATE ON blocklist TO app_user;
