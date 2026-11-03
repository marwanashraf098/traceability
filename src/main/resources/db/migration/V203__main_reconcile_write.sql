-- V203 — Shopify Main-location reconciler R2: WRITE MODE (approved by Marawan 2026-11-04; CLAUDE.md amendment under the
-- increment-only invariant — named writes (8) pushPart1MainDecrement and (9) pushMainReconcile).
--
--   main_reconcile_runs.mode     'write' for a store in TRACED_MAIN_RECONCILE_WRITE_SHOPS; writes_* counts.
--   main_reconcile_part1_claims  fulfillment_created_at (Shopify's fulfillment time — the Part 1 cutoff,
--                                TRACED_MAIN_RECONCILE_PART1_SINCE, compares it); send_started_at / applied_at for the
--                                send (claim-before-call: queued → pending, committed, then ONE call);
--                                'ignored_pre_cutoff' = a report_only claim from a fulfillment before the cutoff (kept,
--                                never sent, not owed); 'superseded_by_reconcile' = a failed_ambiguous claim settled by a
--                                reconcile write of its variant (Shopify set to Traced's count — never sent).
--   main_reconcile_diffs         unfulfilled_shipped (fix 2: pieces that left Traced custody for a line still
--                                unfulfilled in Shopify — Shopify still holds them on_hand as committed), its raw value
--                                before the cap at Shopify's committed, and note ('unfulfilled_capped').
--   main_reconcile_writes        one row per reconcile write (named write 9): the claim, inserted 'pending' and
--                                committed BEFORE the call; Shopify before / fresh read, target and its parts, delta,
--                                result. At most one 'pending' per variant (partial UNIQUE). Never deleted.
--   shopify_inventory_adjustments.status 'superseded_by_reconcile' — another write path's failed_ambiguous claim at
--                                Main settled in the same transaction as the reconcile write that made Shopify = Traced
--                                for its variant (superseded_at stamped). Terminal: nothing re-sends it.

ALTER TABLE main_reconcile_runs DROP CONSTRAINT main_reconcile_runs_mode_check;
ALTER TABLE main_reconcile_runs ADD CONSTRAINT main_reconcile_runs_mode_check CHECK (mode IN ('report_only', 'write'));
ALTER TABLE main_reconcile_runs
    ADD COLUMN writes_attempted integer NOT NULL DEFAULT 0,
    ADD COLUMN writes_applied   integer NOT NULL DEFAULT 0;
GRANT UPDATE (writes_attempted, writes_applied) ON main_reconcile_runs TO app_user;

ALTER TABLE main_reconcile_part1_claims DROP CONSTRAINT main_reconcile_part1_claims_status_check;
ALTER TABLE main_reconcile_part1_claims ADD CONSTRAINT main_reconcile_part1_claims_status_check
    CHECK (status IN ('report_only', 'queued', 'pending', 'applied', 'failed', 'failed_ambiguous', 'dropped',
                      'ignored_pre_cutoff', 'superseded_by_reconcile'));
ALTER TABLE main_reconcile_part1_claims
    ADD COLUMN fulfillment_created_at timestamptz,
    ADD COLUMN send_started_at        timestamptz,
    ADD COLUMN applied_at             timestamptz;
GRANT UPDATE (send_started_at, applied_at) ON main_reconcile_part1_claims TO app_user;

-- Backfill Shopify's fulfillment time on existing (R1, report-only) claims from the stored order. Unreadable → NULL
-- (the cutoff then falls back to the claim's own created_at, which for every R1 claim is before R2).
UPDATE main_reconcile_part1_claims c
   SET fulfillment_created_at = (f->>'created_at')::timestamptz
  FROM orders o, jsonb_array_elements(CASE WHEN jsonb_typeof(o.raw->'fulfillments') = 'array'
                                           THEN o.raw->'fulfillments' ELSE '[]'::jsonb END) f
 WHERE o.id = c.order_id AND o.tenant_id = c.tenant_id
   AND f->>'id' = c.fulfillment_id
   AND f->>'created_at' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}'
   AND c.fulfillment_created_at IS NULL;

ALTER TABLE main_reconcile_diffs
    ADD COLUMN unfulfilled_shipped     integer,
    ADD COLUMN unfulfilled_shipped_raw integer,
    ADD COLUMN note                    text;

CREATE TABLE main_reconcile_writes (
    id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid        NOT NULL REFERENCES tenants(id),
    run_id                uuid        NOT NULL,
    variant_id            uuid        NOT NULL,
    location_id           uuid        NOT NULL,
    shopify_location_id   text        NOT NULL,
    inventory_item_id     text        NOT NULL,
    shopify_on_hand       integer     NOT NULL,     -- what the run saw (the confirmed difference)
    fresh_on_hand         integer,                  -- the read immediately before the write
    change_from_quantity  integer,                  -- "available" from that read — the compare-and-set baseline
    target                integer     NOT NULL,
    custody_at_main       integer     NOT NULL,
    away_counted          integer     NOT NULL,
    fulfilled_from_main   integer     NOT NULL,
    part1_owed            integer     NOT NULL,
    unfulfilled_shipped   integer     NOT NULL,
    delta                 integer     NOT NULL CHECK (delta <> 0),
    reason                text        NOT NULL DEFAULT 'confirmed_difference',
    status                text        NOT NULL DEFAULT 'pending'
                          CHECK (status IN ('pending', 'applied', 'failed', 'failed_ambiguous', 'cas_rejected',
                                            'changed_before_write', 'superseded_by_reconcile')),
    settled               jsonb       NOT NULL DEFAULT '[]'::jsonb,   -- other paths' claims settled by this write
    error                 text,
    created_at            timestamptz NOT NULL DEFAULT now(),
    finished_at           timestamptz,
    -- No FK on run_id: the retention purge deletes runs after retention-days; a write row outlives its run.
    FOREIGN KEY (location_id, tenant_id) REFERENCES locations (id, tenant_id)
);
CREATE UNIQUE INDEX main_reconcile_writes_one_pending ON main_reconcile_writes (tenant_id, variant_id)
    WHERE status = 'pending';
CREATE INDEX main_reconcile_writes_tenant_idx ON main_reconcile_writes (tenant_id, created_at DESC);
CREATE INDEX main_reconcile_writes_variant_idx ON main_reconcile_writes (tenant_id, variant_id, created_at DESC);

ALTER TABLE main_reconcile_writes ENABLE ROW LEVEL SECURITY;
ALTER TABLE main_reconcile_writes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON main_reconcile_writes
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT ON main_reconcile_writes TO app_user;
REVOKE UPDATE, DELETE, TRUNCATE ON main_reconcile_writes FROM app_user;
GRANT UPDATE (fresh_on_hand, change_from_quantity, status, settled, error, finished_at) ON main_reconcile_writes TO app_user;

ALTER TABLE shopify_inventory_adjustments DROP CONSTRAINT shopify_inventory_adjustments_status_check;
ALTER TABLE shopify_inventory_adjustments ADD CONSTRAINT shopify_inventory_adjustments_status_check
    CHECK (status IN ('shadow', 'pending', 'applied', 'failed', 'skipped', 'superseded_by_seed',
                      'skipped_shopify_restocked', 'skipped_not_fulfillment_location',
                      'queued', 'failed_ambiguous', 'cancelled', 'superseded_by_repair', 'superseded_by_reconcile'));
