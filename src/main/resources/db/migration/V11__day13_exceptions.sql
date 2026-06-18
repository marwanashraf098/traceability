-- ============================================================
-- V11 — Day 13: Exceptions center (FR-15.3)
-- ============================================================

-- Per-tenant window for stuck-shipment detection: a shipment in a
-- non-terminal courier state with no update for this many days surfaces
-- in the exceptions list. Default 3 days.
ALTER TABLE tenants
    ADD COLUMN stuck_shipment_days integer NOT NULL DEFAULT 3;

-- Operational audit log for exception acknowledgements.
-- Each row records an operator resolving one exception instance.
-- subject_key format (type-specific):
--   lost:piece:<piece_id>
--   never_received:piece:<piece_id>
--   unmatched:<unlinked_bosta_deliveries.id>
--   blocked:<order_id>
--   stuck:shipment:<shipment_id>
--   unexpected_return:<piece_id>
--   delivery_limbo:shipment:<shipment_id>
--   ndr:shipment:<shipment_id>
--
-- Suppression logic per detector:
--   Most types:        NOT EXISTS (... WHERE subject_key = ?)
--   stuck_shipment:    NOT EXISTS (... AND er.resolved_at > s.last_synced_at)
--                      — a Bosta sync AFTER the ack invalidates the suppression
--                        so the exception reappears if the shipment stalls again.
CREATE TABLE exception_resolutions (
    id             bigserial    PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenants(id),
    exception_type text         NOT NULL,
    subject_key    text         NOT NULL,
    resolved_by    uuid         NOT NULL REFERENCES users(id),
    resolved_at    timestamptz  NOT NULL DEFAULT now(),
    note           text
);

-- Fast suppression check in every detector (NOT EXISTS sub-query).
CREATE INDEX exception_resolutions_lookup
    ON exception_resolutions (tenant_id, exception_type, subject_key);

-- Full audit trail queryable by type or time range.
CREATE INDEX exception_resolutions_audit
    ON exception_resolutions (tenant_id, resolved_at DESC);

ALTER TABLE exception_resolutions ENABLE ROW LEVEL SECURITY;
ALTER TABLE exception_resolutions FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON exception_resolutions
    USING  (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
