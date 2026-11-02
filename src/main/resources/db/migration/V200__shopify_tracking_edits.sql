-- V200 — Shopify tracking-number edits follow the forward leg, S1 + S2 (2026-11-02).
--
-- A merchant who edits a Shopify fulfillment's tracking number (same fulfillment, tracking_numbers[] replaced — or
-- cancels the fulfillment and fulfills again with one new number) gets the order's forward leg moved to the new
-- number while the parcel is still in Traced custody (OrderLineFreeze.frozenSql, re-checked under the order row lock);
-- after custody it never changes and an alert is raised instead. S2 = generic (non-Bosta) legs; Bosta is S3.
--
-- 1. order_fulfillment_tracking (V129):
--      removed_at              the number left its (still live) fulfillment in a newer payload — an edit or a blanked
--                              number. NULL again if a newer payload carries it back. A cancelled fulfillment's rows
--                              are NOT removed (they keep fulfillment_status 'cancelled', as before).
--      fulfillment_updated_at  the fulfillment's Shopify updated_at in the payload that last wrote the row; a payload
--                              with an OLDER updated_at for the same fulfillment never rewrites or revives the row.
--    Both are NULL on every existing row, and stay NULL for every fulfillment whose numbers never change.
-- 2. shipments.tracking_changed_at — when a Shopify edit last changed this leg's tracking number (generic: in place).
-- 3. shipment_tracking_changes — append-only record of every decision (applied / refused / recorded), old → new,
--    source 'shopify_fulfillment_edit'. relabel = why the parcel must be relabelled after an applied change
--    (packed / printed / claimed — the MEDIUM tracking_changed_relabel alert). The refused ones carry the reason
--    (the HIGH alerts). Idempotent per decision: a payload repeating the same decision adds nothing.
--    app_user: INSERT / SELECT only.

ALTER TABLE order_fulfillment_tracking
    ADD COLUMN removed_at             timestamptz,
    ADD COLUMN fulfillment_updated_at timestamptz;

ALTER TABLE shipments ADD COLUMN tracking_changed_at timestamptz;

CREATE TABLE shipment_tracking_changes (
    id                          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                   uuid        NOT NULL REFERENCES tenants(id),
    order_id                    uuid        NOT NULL,
    shipment_id                 uuid        NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    new_shipment_id             uuid        REFERENCES shipments(id) ON DELETE CASCADE,
    source                      text        NOT NULL DEFAULT 'shopify_fulfillment_edit'
                                            CHECK (source IN ('shopify_fulfillment_edit')),
    kind                        text        NOT NULL CHECK (kind IN ('edit', 'cancel_refulfill', 'cosmetic', 'blanked')),
    old_tracking_number         text        NOT NULL,
    new_tracking_number         text,
    shopify_fulfillment_id      text,
    new_shopify_fulfillment_id  text,
    fulfillment_updated_at      timestamptz,
    outcome                     text        NOT NULL CHECK (outcome IN ('applied', 'refused', 'recorded')),
    reason                      text        CHECK (reason IN ('frozen', 'pickup_session', 'ambiguous', 'key_taken')),
    relabel                     text[]      NOT NULL DEFAULT '{}',
    created_at                  timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT shipment_tracking_changes_reason CHECK ((outcome = 'refused') = (reason IS NOT NULL)),
    CONSTRAINT shipment_tracking_changes_new CHECK ((kind = 'blanked') = (new_tracking_number IS NULL))
);

-- One row per decision: a repeated payload (or a JobRunr retry) writes nothing new.
CREATE UNIQUE INDEX shipment_tracking_changes_once
    ON shipment_tracking_changes (shipment_id, old_tracking_number, coalesce(new_tracking_number, ''), outcome,
                                  coalesce(reason, ''));
CREATE INDEX shipment_tracking_changes_order_idx ON shipment_tracking_changes (tenant_id, order_id, created_at DESC);

ALTER TABLE shipment_tracking_changes ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipment_tracking_changes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shipment_tracking_changes
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE UPDATE, DELETE, TRUNCATE ON shipment_tracking_changes FROM app_user;
