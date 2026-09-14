-- ============================================================
-- V92 — Order Notes (append-only)
--
-- Free-text notes an operator attaches to an order (drawer Notes tab).
-- Add-only this pass: no edit, no delete. author is resolved at read time
-- via created_by -> users.name, same convention as the trace timeline
-- (piece_events.actor_user_id -> users.name, OrderController.timeline()).
-- ============================================================

CREATE TABLE order_notes (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenants(id),
    order_id    uuid        NOT NULL REFERENCES orders(id),
    body        text        NOT NULL,
    created_by  uuid        NOT NULL REFERENCES users(id),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX order_notes_order_created_idx ON order_notes (order_id, created_at DESC);

ALTER TABLE order_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_notes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON order_notes
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
