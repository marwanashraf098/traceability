-- ============================================================
-- V74 — FR-EXCHANGE Phase 1: exchanges aggregate + ingest reroute plumbing
--
-- Forward-only shipment legs (no return-leg `shipments` row — see FR-EXCHANGE v2 spec
-- §3.2). Fleet data confirmed the forward and return legs of a Bosta-direct exchange
-- share the SAME tracking_number, and `shipments.tracking_number` has been a GLOBAL
-- UNIQUE constraint since V1 (never scoped to tenant, never dropped) — a second
-- shipments row for the same tracking_number is a hard DB-level conflict, not just an
-- application-level assumption. This table is the sole home for the return leg's
-- progress; return_session_shipments.awb (V73, free text, no FK to shipments) already
-- proves Returns needs no shipments row to function.
-- ============================================================

CREATE TABLE exchanges (
    id                     uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              uuid        NOT NULL REFERENCES tenants(id),
    tracking_number        text        NOT NULL,
    status                 text        NOT NULL DEFAULT 'needs_mapping'
                             CHECK (status IN ('needs_mapping','mapped','outbound_in_fulfillment',
                                               'out_for_exchange','return_pending','reconciled','cancelled')),
    outbound_description   text,
    inbound_description    text,
    inbound_description_ar text,
    outbound_variant_id    uuid        REFERENCES variants(id),
    inbound_variant_id     uuid        REFERENCES variants(id),
    outbound_order_id      uuid        REFERENCES orders(id),
    return_session_id      uuid        REFERENCES return_sessions(id),
    cod                    numeric(10,2),
    goods_value            numeric(10,2),
    raw                    jsonb       NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, tracking_number)
);

CREATE INDEX exchanges_needs_mapping_idx ON exchanges (tenant_id, status)
    WHERE status = 'needs_mapping';

ALTER TABLE exchanges ENABLE ROW LEVEL SECURITY;
ALTER TABLE exchanges FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON exchanges
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- ── Dead :EXCHANGE seed row (CC Phase 0 finding 5.6) ────────────────────────
-- type.code=30 deliveries are now intercepted in BostaWebhookJob.process() BEFORE the
-- generic (state_code, type) mapper is ever consulted (step 6.5, ahead of stateMapper.
-- map() at step 7) — this row would otherwise reintroduce the exact bug Phase 0 found:
-- 41:EXCHANGE (V37) maps the OUTBOUND leg to 'returning'/'return_in_transit', which is
-- wrong the moment an exchange gets its own forward shipment row (a later phase). No
-- 46:EXCHANGE row exists to delete (confirmed absent in every prior migration) — state
-- 46 already fell through to 46:ALL. Deleting 41:EXCHANGE drops the seeded row count
-- from 27 to 26 (MigrationSmokeTest updated accordingly).
DELETE FROM bosta_state_mappings
WHERE state_code = 41 AND applies_to_order_type = 'EXCHANGE';

-- ── One-time backfill: the unresolved EXCHANGE rows fleet-confirmed in Phase 0 ─────
-- (877468285, 6336637079, 184907356, 9293360461 at time of writing — all tenant "The
-- Snouts"). Selected generically by bosta_order_type/resolved rather than hardcoded
-- tracking numbers so the migration is correct even if fleet state has moved between
-- diagnosis and deploy. AND u.raw IS NOT NULL guards the NOT NULL exchanges.raw column
-- (unlinked_bosta_deliveries.raw is nullable) — a null-raw row is left as generic
-- unmatched noise rather than failing the migration.
INSERT INTO exchanges
    (tenant_id, tracking_number, status, outbound_description, inbound_description,
     inbound_description_ar, cod, goods_value, raw)
SELECT
    u.tenant_id, u.tracking_number, 'needs_mapping',
    u.raw -> 'specs' -> 'packageDetails' ->> 'description',
    u.raw -> 'returnSpecs' -> 'packageDetails' ->> 'description',
    u.raw -> 'returnSpecs' -> 'packageDetails' ->> 'descriptionAr',
    NULLIF(u.raw ->> 'cod', '')::numeric,
    NULLIF(u.raw -> 'goodsInfo' ->> 'amount', '')::numeric,
    u.raw
FROM unlinked_bosta_deliveries u
WHERE u.bosta_order_type = 'EXCHANGE' AND u.resolved = false AND u.raw IS NOT NULL
ON CONFLICT (tenant_id, tracking_number) DO NOTHING;

-- Only resolves rows that actually got an exchanges row above — a hypothetical
-- null-raw row (excluded above) stays resolved=false and keeps surfacing as a normal
-- unmatched_delivery exception rather than silently disappearing unrepresented.
UPDATE unlinked_bosta_deliveries u
SET resolved = true
WHERE u.bosta_order_type = 'EXCHANGE' AND u.resolved = false
  AND EXISTS (
      SELECT 1 FROM exchanges e
      WHERE e.tenant_id = u.tenant_id AND e.tracking_number = u.tracking_number);
