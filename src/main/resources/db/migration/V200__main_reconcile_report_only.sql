-- V200 — Shopify Main-location reconciler ("check-up"), R0 + R1: REPORT-ONLY (approved 2026-11-02).
--
-- Rule (decided 2026-10-21, scope confirmed 2026-11-02): Shopify's Traced Main Warehouse = Traced, for every store;
-- other Shopify locations are never touched. R1 only READS Shopify and records what it would change. No Shopify write
-- exists in this migration's code (R2 adds pushMainReconcile behind its own approval).
--
--   main_reconcile_runs          one row per check-up of one tenant (a debounced per-variant run, or a full sweep):
--                                counts, the Part 1 summary, or the store-level skip reason (location inactive /
--                                deleted, needs reauth, main not linked, no store).
--   main_reconcile_diffs         per run, one row per variant that is out of sync OR skipped (in-sync variants are
--                                only counted on the run): Shopify on_hand / available / committed / damaged at Main,
--                                Traced's target and its parts, delta (target − on_hand), confirmed by the two-run
--                                rule or not, the skip reason. 'damaged_only' = on_hand in sync, damaged differs
--                                (report only — damage found at return inspection never reaches Shopify).
--   main_reconcile_variant_state one row per variant ever queued or checked: the debounce queue (pending_since /
--                                pending_touched_at, written by the webhooks, the claim-table triggers and the
--                                piece-left trigger on piece_events below),
--                                the last Shopify webhook seen for it (last_event_at — the two-run rule), and the
--                                current difference and since when (delta_since).
--   main_reconcile_part1_claims  Part 1: a Shopify fulfillment line shipped from ANOTHER location whose pieces Traced
--                                shipped — the units still owed off Main. Exactly once per (fulfillment, line item)
--                                (UNIQUE — the claim). R1 writes only status 'report_only'; the other statuses exist
--                                for R2 (claim-before-call, pushTransferOut classification). 'dropped' = the order or
--                                fulfillment was cancelled before it was sent.
--
-- shopify_refund_restocked_units(raw, variant_gid, main_location_gid) — the restock gate fix: only refund restocks
-- AT THE TRACED MAIN location count (a restock line naming another location never blocks Traced's +1 at Main; a line
-- with no location_id, as in pre-location payloads, still counts — unchanged). The 2-arg V151 function stays for the
-- 2026-10-08 repair script; live code (ShopifyInventoryService guard, ExceptionService restocked_twice) uses this one.
--
-- RLS on every new table (NULLIF pattern). app_user: no TRUNCATE anywhere; runs / diffs append-only except the run's
-- own finish columns, and DELETE (own tenant, RLS) only for the retention purge (MainReconcileService.purgeOld, runs +
-- diffs older than retention-days); part 1 claims and the variant state are never deleted; part 1 claims: only
-- status / reason / updated_at / quantity-free columns change.

CREATE TABLE main_reconcile_runs (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid        NOT NULL REFERENCES tenants(id),
    kind                 text        NOT NULL CHECK (kind IN ('variant', 'sweep')),
    mode                 text        NOT NULL DEFAULT 'report_only' CHECK (mode IN ('report_only')),
    status               text        NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'completed', 'skipped', 'failed')),
    skip_reason          text,
    location_id          uuid,
    shopify_location_id  text,
    started_at           timestamptz NOT NULL DEFAULT now(),
    shopify_read_at      timestamptz,
    finished_at          timestamptz,
    variants_checked     integer     NOT NULL DEFAULT 0,
    variants_in_sync     integer     NOT NULL DEFAULT 0,
    variants_skipped     integer     NOT NULL DEFAULT 0,
    differences          integer     NOT NULL DEFAULT 0,
    confirmed            integer     NOT NULL DEFAULT 0,
    confirmed_units_up   integer     NOT NULL DEFAULT 0,
    confirmed_units_down integer     NOT NULL DEFAULT 0,
    damaged_mismatches   integer     NOT NULL DEFAULT 0,
    skipped_by_reason    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    part1_summary        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    error                text,
    UNIQUE (id, tenant_id),
    CONSTRAINT main_reconcile_runs_skip CHECK ((status = 'skipped') = (skip_reason IS NOT NULL))
);
CREATE INDEX main_reconcile_runs_tenant_idx ON main_reconcile_runs (tenant_id, started_at DESC);

CREATE TABLE main_reconcile_diffs (
    id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid        NOT NULL REFERENCES tenants(id),
    run_id                uuid        NOT NULL,
    variant_id            uuid        NOT NULL,
    status                text        NOT NULL CHECK (status IN ('unconfirmed', 'confirmed', 'skipped', 'damaged_only')),
    skip_reason           text,
    custody_at_main       integer,
    away_counted          integer,
    fulfilled_from_main   integer,
    part1_owed            integer,
    target                integer,
    shopify_on_hand       integer,
    shopify_available     integer,
    shopify_committed     integer,
    shopify_damaged       integer,
    traced_damaged        integer,
    delta                 integer,
    delta_since           timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (run_id, tenant_id) REFERENCES main_reconcile_runs (id, tenant_id),
    CONSTRAINT main_reconcile_diffs_skip CHECK ((status = 'skipped') = (skip_reason IS NOT NULL))
);
CREATE INDEX main_reconcile_diffs_run_idx ON main_reconcile_diffs (tenant_id, run_id);
CREATE INDEX main_reconcile_diffs_variant_idx ON main_reconcile_diffs (tenant_id, variant_id, created_at DESC);

-- No FK on variant_id: the claim-table triggers write here and must never fail a claim write. Derived queue data:
-- it goes with its tenant (ON DELETE CASCADE), so a trigger-made row never blocks a tenant delete.
CREATE TABLE main_reconcile_variant_state (
    tenant_id           uuid        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    variant_id          uuid        NOT NULL,
    pending_since       timestamptz,
    pending_touched_at  timestamptz,
    pending_source      text,
    last_event_at       timestamptz,
    last_event_source   text,
    last_checked_at     timestamptz,
    last_run_id         uuid,
    last_delta          integer,
    delta_since         timestamptz,
    PRIMARY KEY (tenant_id, variant_id)
);
CREATE INDEX main_reconcile_state_pending_idx ON main_reconcile_variant_state (tenant_id, pending_touched_at)
    WHERE pending_since IS NOT NULL;

CREATE TABLE main_reconcile_part1_claims (
    id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid        NOT NULL REFERENCES tenants(id),
    order_id              uuid        NOT NULL,
    variant_id            uuid        NOT NULL,
    location_id           uuid        NOT NULL,
    fulfillment_id        text        NOT NULL,
    line_item_id          text        NOT NULL,
    fulfilled_location_id text        NOT NULL,
    fulfilled_quantity    integer     NOT NULL CHECK (fulfilled_quantity > 0),
    quantity              integer     NOT NULL CHECK (quantity > 0),
    status                text        NOT NULL DEFAULT 'report_only'
                          CHECK (status IN ('report_only', 'queued', 'pending', 'applied', 'failed',
                                            'failed_ambiguous', 'dropped')),
    reason                text,
    run_id                uuid,
    attempt_count         integer     NOT NULL DEFAULT 0,
    error                 text,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, fulfillment_id, line_item_id),
    CONSTRAINT main_reconcile_part1_qty CHECK (quantity <= fulfilled_quantity),
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders (id, tenant_id),
    FOREIGN KEY (location_id, tenant_id) REFERENCES locations (id, tenant_id)
);
CREATE INDEX main_reconcile_part1_variant_idx ON main_reconcile_part1_claims (tenant_id, variant_id, status);
CREATE INDEX main_reconcile_part1_order_idx ON main_reconcile_part1_claims (tenant_id, order_id, variant_id);

ALTER TABLE main_reconcile_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE main_reconcile_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON main_reconcile_runs
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE UPDATE, DELETE, TRUNCATE ON main_reconcile_runs FROM app_user;
GRANT DELETE ON main_reconcile_runs TO app_user;   -- retention purge only
GRANT UPDATE (status, skip_reason, shopify_read_at, finished_at, variants_checked, variants_in_sync, variants_skipped,
              differences, confirmed, confirmed_units_up, confirmed_units_down, damaged_mismatches, skipped_by_reason, part1_summary, error)
    ON main_reconcile_runs TO app_user;

ALTER TABLE main_reconcile_diffs ENABLE ROW LEVEL SECURITY;
ALTER TABLE main_reconcile_diffs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON main_reconcile_diffs
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE UPDATE, DELETE, TRUNCATE ON main_reconcile_diffs FROM app_user;
GRANT DELETE ON main_reconcile_diffs TO app_user;  -- retention purge only

ALTER TABLE main_reconcile_variant_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE main_reconcile_variant_state FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON main_reconcile_variant_state
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON main_reconcile_variant_state FROM app_user;

ALTER TABLE main_reconcile_part1_claims ENABLE ROW LEVEL SECURITY;
ALTER TABLE main_reconcile_part1_claims FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON main_reconcile_part1_claims
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE UPDATE, DELETE, TRUNCATE ON main_reconcile_part1_claims FROM app_user;
GRANT UPDATE (status, reason, attempt_count, error, updated_at) ON main_reconcile_part1_claims TO app_user;

-- ── Debounce queue: every Traced Shopify stock claim queues its variant(s) ──────────────────────────────────────
-- Runs as the writer (INVOKER, not a hatch). Never raises for a malformed deltas key (skipped), never needs a
-- variants row (no FK), so it can never fail the claim write it hangs off.
CREATE OR REPLACE FUNCTION main_reconcile_queue_variant(p_tenant uuid, p_variant uuid, p_source text)
RETURNS void LANGUAGE sql AS $$
    INSERT INTO main_reconcile_variant_state (tenant_id, variant_id, pending_since, pending_touched_at, pending_source)
    VALUES (p_tenant, p_variant, now(), now(), p_source)
    ON CONFLICT (tenant_id, variant_id) DO UPDATE
       SET pending_since      = COALESCE(main_reconcile_variant_state.pending_since, now()),
           pending_touched_at = now(),
           pending_source     = EXCLUDED.pending_source
$$;

CREATE OR REPLACE FUNCTION main_reconcile_queue_claim() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    k text;
    deltas jsonb;
BEGIN
    IF TG_TABLE_NAME = 'shopify_inventory_adjustments' THEN
        IF NEW.variant_id IS NOT NULL AND NEW.tenant_id IS NOT NULL THEN
            PERFORM main_reconcile_queue_variant(NEW.tenant_id, NEW.variant_id, 'claim:' || NEW.trigger_type);
        END IF;
        RETURN NULL;
    END IF;
    -- Separate statements: a statement naming a column the firing table doesn't have must never be planned.
    IF TG_TABLE_NAME = 'transfer_shopify_syncs' THEN
        deltas := NEW.deltas;
    ELSE
        deltas := NEW.payload->'deltas';
    END IF;
    IF deltas IS NULL OR jsonb_typeof(deltas) <> 'object' THEN RETURN NULL; END IF;
    FOR k IN SELECT jsonb_object_keys(deltas) LOOP
        IF k ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
            PERFORM main_reconcile_queue_variant(NEW.tenant_id, k::uuid, 'claim:' || TG_TABLE_NAME);
        END IF;
    END LOOP;
    RETURN NULL;
END $$;

CREATE TRIGGER main_reconcile_queue AFTER INSERT OR UPDATE OF status ON shopify_inventory_adjustments
    FOR EACH ROW EXECUTE FUNCTION main_reconcile_queue_claim();
CREATE TRIGGER main_reconcile_queue AFTER INSERT OR UPDATE OF status ON transfer_shopify_syncs
    FOR EACH ROW EXECUTE FUNCTION main_reconcile_queue_claim();
CREATE TRIGGER main_reconcile_queue AFTER INSERT OR UPDATE OF status ON stock_take_shopify_syncs
    FOR EACH ROW EXECUTE FUNCTION main_reconcile_queue_claim();

-- ── Debounce queue: a piece leaving Traced custody queues its variant (Part 1 is evaluated within ~1 minute) ───────
-- Fires only for a move INTO with_courier / delivered from anything else (the WHEN keeps batchReceive and every other
-- event off this path). Same INVOKER queue function; never raises (no piece row → nothing queued).
CREATE OR REPLACE FUNCTION main_reconcile_queue_piece_left() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM main_reconcile_queue_variant(p.tenant_id, p.variant_id, 'piece_left')
    FROM pieces p WHERE p.id = NEW.piece_id AND p.tenant_id = NEW.tenant_id AND p.variant_id IS NOT NULL;
    RETURN NULL;
END $$;

CREATE TRIGGER main_reconcile_queue_piece_left AFTER INSERT ON piece_events
    FOR EACH ROW
    WHEN (NEW.to_status::text IN ('with_courier', 'delivered')
          AND (NEW.from_status IS NULL OR NEW.from_status::text NOT IN ('with_courier', 'delivered')))
    EXECUTE FUNCTION main_reconcile_queue_piece_left();

-- ── Restock gate fix: only Shopify refund restocks at the Traced Main location count ────────────────────────────
CREATE OR REPLACE FUNCTION shopify_refund_restocked_units(raw jsonb, variant_gid text, main_location_gid text)
RETURNS bigint
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT COALESCE(SUM(
               CASE WHEN rli->>'quantity' ~ '^[0-9]+$' THEN (rli->>'quantity')::bigint ELSE 0 END), 0)
    FROM jsonb_array_elements(
             CASE WHEN jsonb_typeof(raw->'refunds') = 'array' THEN raw->'refunds' ELSE '[]'::jsonb END) r,
         jsonb_array_elements(
             CASE WHEN jsonb_typeof(r->'refund_line_items') = 'array' THEN r->'refund_line_items' ELSE '[]'::jsonb END) rli
    WHERE rli->>'restock_type' IN ('return', 'legacy_restock')
      AND variant_gid IS NOT NULL
      AND 'gid://shopify/ProductVariant/' || (rli->'line_item'->>'variant_id') = variant_gid
      AND (main_location_gid IS NULL
           OR rli->>'location_id' IS NULL
           OR 'gid://shopify/Location/' || (rli->>'location_id') = main_location_gid)
$$;
