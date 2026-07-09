-- ============================================================
-- V40 — Shipment status history
-- ============================================================
-- Records each Bosta state transition for a shipment so the order-detail
-- page can show a timestamped delivery timeline.
--
-- One row per processed webhook event that carries a state change.
-- The unique index on webhook_event_id makes every write idempotent —
-- replaying a webhook_event never doubles up the history row.
--
-- Backfill note: existing shipments have no history rows; their current
-- internal_state in the shipments table is the single source of truth
-- until new transitions arrive.

CREATE TABLE shipment_status_history (
    id               bigserial    PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenants(id),
    shipment_id      uuid         NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    internal_state   text         NOT NULL,
    provider_state   integer,
    exception_code   integer,
    exception_reason text,
    occurred_at      timestamptz  NOT NULL DEFAULT now(),
    webhook_event_id bigint       REFERENCES webhook_events(id)
);

-- Efficient lookup for the order-detail delivery timeline.
CREATE INDEX shipment_status_history_shipment_idx
    ON shipment_status_history (shipment_id, occurred_at ASC);

-- Idempotency: one history row per webhook event (ON CONFLICT DO NOTHING).
CREATE UNIQUE INDEX shipment_status_history_event_uq
    ON shipment_status_history (webhook_event_id)
    WHERE webhook_event_id IS NOT NULL;

ALTER TABLE shipment_status_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipment_status_history FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON shipment_status_history
    USING  (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
