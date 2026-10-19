-- Non-Bosta couriers G2 — Settings switch, default carrier, pack-time conflict record, QP Express.
--
-- Nothing here changes what a tenant with generic_courier_enabled = false sees: one nullable column,
-- one new table no Bosta path writes, and one more name in the named-carrier list.

-- 1. The carrier name a merchant ships with by default ("I ship with other couriers" → default
--    carrier, Settings → Couriers). Free text; suggestions come from other_known_carriers().
ALTER TABLE tenants ADD COLUMN generic_default_carrier text
    CHECK (generic_default_carrier IS NULL OR length(trim(generic_default_carrier)) BETWEEN 1 AND 60);

-- 2. Pack-time Bosta-over-generic conflict (G1 carry-over). The queue's AWB link step refuses a Bosta
--    AWB on an order whose generic shipment is past 'created' (409, nothing linked). That refusal rolls
--    its transaction back, so the evidence is written here in a transaction of its own
--    (GenericConflictRecorder, REQUIRES_NEW) and ExceptionService derives a HIGH generic_bosta_conflict
--    alert from it — the same type G1 raises for a delivery Traced refused to link. Append-only: a
--    resolution is an exception_resolutions row like every other alert.
CREATE TABLE generic_conflict_scans (
    id              bigserial    PRIMARY KEY,
    tenant_id       uuid         NOT NULL REFERENCES tenants(id),
    order_id        uuid         NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    shipment_id     uuid         NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,  -- the generic leg
    tracking_number text         NOT NULL,                                             -- the Bosta AWB, normalized
    raw_scan        text         NOT NULL,
    actor_user_id   uuid         REFERENCES users(id),
    created_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX generic_conflict_scans_tenant ON generic_conflict_scans (tenant_id, created_at DESC);

ALTER TABLE generic_conflict_scans ENABLE ROW LEVEL SECURITY;
ALTER TABLE generic_conflict_scans FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON generic_conflict_scans
    USING      (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT ON generic_conflict_scans TO app_user;
REVOKE UPDATE, DELETE, TRUNCATE ON generic_conflict_scans FROM app_user;
GRANT USAGE, SELECT ON SEQUENCE generic_conflict_scans_id_seq TO app_user;

-- 3. QP Express joins the named non-Bosta carriers (mirrored by FulfillmentTrackingCapture.OTHER_KNOWN_CARRIERS,
--    GenericCarrierListParityTest). Stored orders.shipping_carrier_class rows are not rewritten — the
--    column is recomputed on the order's next update, as in V169. Prod 2026-10-18: 2 QP Express orders.
CREATE OR REPLACE FUNCTION other_known_carriers() RETURNS text[]
LANGUAGE sql IMMUTABLE AS $$
    SELECT ARRAY['wijha', 'mylerz', 'j&t', 'j&t express', 'jt express', 'aramex', 'r2s',
                 'smsa', 'smsa express', 'shipblu', 'turuq', 'qp express']::text[]
$$;
