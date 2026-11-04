-- =============================================================================================
-- 2026-11-04 — Finding 2: stop ONE queued exchange_dispatch claim from ever sending (BROEK).
--
-- The claim: trigger_type 'exchange_dispatch', trigger_id (piece) '01M4JQ870VCJ9BZGV7D5SB82SA', queued since
-- 2026-10-31 18:58 for the Bosta type-30 AWB 7756985263 (businessReference "BRK-46369-EG:BRK-46369-EG-R1").
-- It is a Shopify-native exchange: Shopify already counts the replacement on its own exchange line of
-- BRK-46369-EG, so a −1 from Traced would take the unit out twice. The fix (ShopifyNativeExchange) skips it on
-- send, but until it is deployed the reconnect resume would send it the moment BROEK's hold clears.
--
-- Marks it 'skipped' with skip_reason 'shopify_native_exchange' — the same outcome the fix records. Nothing is
-- sent to Shopify, nothing else is touched. Every guard aborts the whole run (nothing written):
--   * exactly one claim with that trigger, status 'queued' (pending / applied / failed_ambiguous → STOP: it may
--     already have reached Shopify — check Shopify by hand);
--   * the piece is allocated to an internal exchange order named by the exchanges row for AWB 7756985263, in the
--     claim's tenant, with no Traced request (return_request_id NULL) and that businessReference;
--   * exactly one row updated.
--
-- Run as postgres / the owner role (NOT app_user: RLS would hide every row and the guards would abort).
-- Dry run by default — ends with ROLLBACK unless -v commit=yes:
--   psql "<connection string>" -X -v ON_ERROR_STOP=1 -f scripts/ops/2026-11-04-broek-native-exchange-dispatch-skip.sql
--   psql "<connection string>" -X -v ON_ERROR_STOP=1 -v commit=yes -f scripts/ops/2026-11-04-broek-native-exchange-dispatch-skip.sql
-- =============================================================================================

BEGIN;
SET LOCAL statement_timeout = '15s';
SET LOCAL lock_timeout = '5s';

-- 1. Before: the claim (locked, so the resume job can't take it while this runs).
SELECT sia.id, sia.tenant_id, t.name AS tenant, sia.status, sia.skip_reason, sia.delta, sia.created_at,
       sia.attempt_count, sia.error
FROM shopify_inventory_adjustments sia
JOIN tenants t ON t.id = sia.tenant_id
WHERE sia.trigger_type = 'exchange_dispatch' AND sia.trigger_id = '01M4JQ870VCJ9BZGV7D5SB82SA'
FOR UPDATE OF sia;

-- 2. Guards + the update.
DO $$
DECLARE
    v_claims   int;
    v_id       bigint;
    v_tenant   uuid;
    v_status   text;
    v_matches  int;
    v_updated  int;
BEGIN
    SELECT count(*) INTO v_claims FROM shopify_inventory_adjustments
     WHERE trigger_type = 'exchange_dispatch' AND trigger_id = '01M4JQ870VCJ9BZGV7D5SB82SA';
    IF v_claims <> 1 THEN
        RAISE EXCEPTION 'STOP: expected exactly 1 exchange_dispatch claim for the piece, found %', v_claims;
    END IF;

    SELECT id, tenant_id, status INTO v_id, v_tenant, v_status FROM shopify_inventory_adjustments
     WHERE trigger_type = 'exchange_dispatch' AND trigger_id = '01M4JQ870VCJ9BZGV7D5SB82SA';
    IF v_status <> 'queued' THEN
        RAISE EXCEPTION 'STOP: claim % is % — not queued. If pending / applied / failed_ambiguous it may already '
                        'be in Shopify: check the variant at the Traced Main location by hand.', v_id, v_status;
    END IF;

    SELECT count(*) INTO v_matches
      FROM pieces p
      JOIN allocations a  ON a.piece_id = p.id AND a.tenant_id = p.tenant_id
      JOIN order_items oi ON oi.id = a.order_item_id AND oi.tenant_id = p.tenant_id
      JOIN orders o       ON o.id = oi.order_id AND o.tenant_id = p.tenant_id
      JOIN exchanges e    ON e.outbound_order_id = o.id AND e.tenant_id = o.tenant_id
     WHERE p.id = '01M4JQ870VCJ9BZGV7D5SB82SA' AND p.tenant_id = v_tenant
       AND o.external_id LIKE 'internal:exchange:%'
       AND e.tracking_number = '7756985263'
       AND e.return_request_id IS NULL
       AND e.raw->>'businessReference' = 'BRK-46369-EG:BRK-46369-EG-R1';
    IF v_matches < 1 THEN
        RAISE EXCEPTION 'STOP: the piece is not the replacement of the dashboard exchange 7756985263 '
                        '(BRK-46369-EG:BRK-46369-EG-R1) in tenant % — not touching it', v_tenant;
    END IF;

    UPDATE shopify_inventory_adjustments
       SET status = 'skipped', skip_reason = 'shopify_native_exchange',
           payload = COALESCE(payload, '{}'::jsonb) || jsonb_build_object('ops',
               jsonb_build_object('script', '2026-11-04-broek-native-exchange-dispatch-skip', 'wasStatus', status,
                                  'at', now()))
     WHERE id = v_id AND tenant_id = v_tenant AND status = 'queued';
    GET DIAGNOSTICS v_updated = ROW_COUNT;
    IF v_updated <> 1 THEN
        RAISE EXCEPTION 'STOP: expected to update 1 row, updated %', v_updated;
    END IF;
    RAISE NOTICE 'broek-native-skip: claim % (tenant %) queued → skipped (shopify_native_exchange)', v_id, v_tenant;
END $$;

-- 3. After.
SELECT id, status, skip_reason, payload->'ops' AS ops
FROM shopify_inventory_adjustments
WHERE trigger_type = 'exchange_dispatch' AND trigger_id = '01M4JQ870VCJ9BZGV7D5SB82SA';

-- 4. Read-only: any OTHER unsent exchange_dispatch claim the fix will skip (all tenants). Expected: none.
--    Same predicate as ShopifyNativeExchange (keep in step if it changes).
SELECT t.name AS tenant, sia.id, sia.trigger_id AS piece, sia.status, sia.skip_reason, sia.created_at,
       e.tracking_number AS awb, e.raw->>'businessReference' AS business_reference
FROM shopify_inventory_adjustments sia
JOIN tenants t      ON t.id = sia.tenant_id
JOIN pieces p       ON p.id = sia.trigger_id AND p.tenant_id = sia.tenant_id
JOIN allocations a  ON a.piece_id = p.id AND a.tenant_id = p.tenant_id AND a.status IN ('active', 'packed', 'released')
JOIN order_items oi ON oi.id = a.order_item_id AND oi.tenant_id = p.tenant_id
JOIN orders o       ON o.id = oi.order_id AND o.tenant_id = p.tenant_id AND o.external_id LIKE 'internal:exchange:%'
JOIN exchanges e    ON e.outbound_order_id = o.id AND e.tenant_id = o.tenant_id
WHERE sia.trigger_type = 'exchange_dispatch'
  AND sia.trigger_id <> '01M4JQ870VCJ9BZGV7D5SB82SA'
  AND (sia.status IN ('queued', 'failed')
       OR (sia.status = 'skipped' AND sia.skip_reason IN ('arrival_unconfirmed', 'arrival_not_counted')))
  AND e.return_request_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM return_requests rr WHERE rr.replacement_order_id = e.outbound_order_id
                    AND rr.tenant_id = e.tenant_id)
  AND (COALESCE(e.raw->>'businessReference', '') ~ ':[^:]*-R[0-9]+$'
       OR EXISTS (SELECT 1 FROM orders mo
                  JOIN order_items moi ON moi.order_id = mo.id AND moi.tenant_id = mo.tenant_id
                  WHERE mo.id = e.matched_order_id AND mo.tenant_id = e.tenant_id
                    AND mo.external_id LIKE 'gid://shopify/Order/%'
                    AND moi.variant_id = p.variant_id
                    AND moi.quantity = 0 AND COALESCE(moi.current_qty, 0) > 0))
ORDER BY sia.created_at;

-- 5. Commit only with -v commit=yes.
\if :{?commit}
\if :commit
COMMIT;
\echo 'broek-native-skip: COMMITTED.'
\else
ROLLBACK;
\echo 'broek-native-skip: dry run — ROLLED BACK (pass -v commit=yes to apply).'
\endif
\else
ROLLBACK;
\echo 'broek-native-skip: dry run — ROLLED BACK (pass -v commit=yes to apply).'
\endif
