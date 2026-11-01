-- ============================================================
-- Generic-leg cancel history — one-off repair (2026-11-01, D6).
--
-- Until this build GenericShipments.cancel() (Bosta wins over a generic leg, or Shopify cancelled the
-- fulfillment it came from) wrote a 'cancelled' shipment_status_history row. Any forward history row other than
-- 'created' reads as "this order shipped": OrderLineFreeze clause 1, FulfillService's ALREADY_SHIPPED guard and
-- WaybillResolver's moved state — so the order could never be packed against its next waybill. The code no longer
-- writes the row; this removes the rows it already wrote. Prod 2026-11-01 (read-only): Maron Fashion, 14 orders.
--
-- Candidate row = a shipment_status_history row that GenericShipments.cancel() wrote:
--   internal_state 'cancelled', source NULL, provider_state NULL, webhook_event_id NULL,
--   on a generic FORWARD leg whose own internal_state is 'cancelled',
--   of a Shopify order (not internal:%) that isn't cancelled.
-- Candidate order = it has candidate rows AND, with them gone, nothing else freezes it:
--   - no other forward history row past 'created' (OrderLineFreeze clause 1 without the candidate rows),
--   - no packed piece in a left-custody status (clause 2), no left-custody piece event for the order (clause 3).
-- An order with candidate rows where any of those trips is LISTED as skipped and never touched.
--
-- Dry run (default): lists every candidate order — tenant, order, rows to delete, whether it has a live newer
-- forward leg — and, after a SIMULATED delete inside a savepoint that is rolled back, the real freeze
-- (OrderLineFreeze.frozenSql, inlined verbatim — GenericCancelHistoryRepairScriptTest checks it) and
-- WaybillResolver's moved state (NULL = the waybill isn't refused as already moving). Writes nothing.
--
-- Commit run: needs -v expect_orders=<n> equal to the candidate count (else aborts, nothing written). Locks the
-- candidate orders and shipments, re-checks the scope under the locks, copies the exact rows into
-- ops_backup_2026_11_01_generic_cancel_history (refuses if that table already exists; app_user / anon /
-- authenticated get no access), deletes them, proves in the transaction that it deleted exactly the backed-up
-- rows and that every candidate order is now unfrozen, commits, then re-reads.
-- Undo: INSERT INTO shipment_status_history SELECT * FROM ops_backup_2026_11_01_generic_cancel_history;
--
-- Run (psql as the database owner, BYPASSRLS):
--   psql "<conn>" -v ON_ERROR_STOP=1 -f scripts/ops/2026-11-01-generic-cancel-history-repair.sql
--   psql "<conn>" -v ON_ERROR_STOP=1 -v commit=yes -v expect_orders=14 -f scripts/ops/2026-11-01-generic-cancel-history-repair.sql
-- Light on prod: a few dozen rows, no jsonb payloads read.
-- ============================================================

\set ON_ERROR_STOP on
\if :{?commit}
\else
\set commit no
\endif
\if :{?expect_orders}
\else
\set expect_orders -1
\endif

BEGIN;

CREATE TEMP TABLE gch_rows ON COMMIT DROP AS
SELECT h.id AS history_id, h.shipment_id, s.order_id, s.tenant_id
FROM shipment_status_history h
JOIN shipments s ON s.id = h.shipment_id
JOIN orders o    ON o.id = s.order_id AND o.tenant_id = s.tenant_id
WHERE s.provider = 'generic' AND s.shipment_leg = 'forward' AND s.internal_state = 'cancelled'
  AND h.internal_state = 'cancelled' AND h.source IS NULL AND h.provider_state IS NULL AND h.webhook_event_id IS NULL
  AND o.external_id NOT LIKE 'internal:%'
  AND o.status <> 'cancelled'::order_status;

-- Every order with candidate rows, with why it would be skipped (NULL = in scope).
CREATE TEMP TABLE gch_orders ON COMMIT DROP AS
SELECT o.id AS order_id, o.tenant_id, t.name AS tenant, o.number AS order_number, o.status::text AS order_status,
       (SELECT count(*) FROM gch_rows r WHERE r.order_id = o.id) AS rows_to_delete,
       EXISTS (SELECT 1 FROM shipments s2 WHERE s2.order_id = o.id AND s2.tenant_id = o.tenant_id
                 AND s2.shipment_leg = 'forward' AND s2.internal_state NOT IN ('cancelled', 'terminated')
                 AND s2.provider_deleted_at IS NULL) AS has_live_leg,
       CASE
         WHEN EXISTS (SELECT 1 FROM shipment_status_history h JOIN shipments s ON s.id = h.shipment_id
                       WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id AND s.shipment_leg = 'forward'
                         AND h.internal_state <> 'created'
                         AND NOT EXISTS (SELECT 1 FROM gch_rows r WHERE r.history_id = h.id))
           THEN 'other forward history past created'
         WHEN EXISTS (SELECT 1 FROM order_items oi_fz
                       JOIN allocations a_fz ON a_fz.order_item_id = oi_fz.id
                       JOIN pieces p_fz ON p_fz.id = a_fz.piece_id
                       WHERE oi_fz.order_id = o.id AND oi_fz.tenant_id = o.tenant_id
                         AND a_fz.status = 'packed' AND p_fz.status::text IN ('with_courier', 'delivered', 'return_in_transit', 'return_pending_inspection'))
           THEN 'a packed piece left custody'
         WHEN EXISTS (SELECT 1 FROM order_items oi_fh
                       JOIN allocations a_fh ON a_fh.order_item_id = oi_fh.id
                       JOIN piece_events pe_fh ON pe_fh.piece_id = a_fh.piece_id AND pe_fh.order_id = oi_fh.order_id
                       WHERE oi_fh.order_id = o.id AND oi_fh.tenant_id = o.tenant_id
                         AND pe_fh.to_status::text IN ('with_courier', 'delivered', 'return_in_transit', 'return_pending_inspection'))
           THEN 'a piece of the order left custody'
       END AS skip_reason
FROM orders o JOIN tenants t ON t.id = o.tenant_id
WHERE o.id IN (SELECT order_id FROM gch_rows);

\echo ''
\echo '== skipped (never touched): orders with such rows that something else still freezes'
SELECT tenant, order_number, order_status, rows_to_delete AS rows, skip_reason
FROM gch_orders WHERE skip_reason IS NOT NULL ORDER BY tenant, order_number;

CREATE TEMP TABLE gch_scope ON COMMIT DROP AS
SELECT r.* FROM gch_rows r JOIN gch_orders q ON q.order_id = r.order_id WHERE q.skip_reason IS NULL;

\if :commit
-- Lock the candidate orders and their forward legs, then re-check the scope under the locks: a concurrent
-- pickup close / webhook writing a history row past 'created' is either seen here or waits for this transaction.
WITH locked AS (
    SELECT o.id FROM orders o WHERE o.id IN (SELECT order_id FROM gch_scope) FOR UPDATE OF o)
SELECT count(*) AS candidate_orders_locked FROM locked;
WITH locked AS (
    SELECT s.id FROM shipments s WHERE s.order_id IN (SELECT order_id FROM gch_scope) AND s.shipment_leg = 'forward'
    FOR UPDATE OF s)
SELECT count(*) AS forward_legs_locked FROM locked;
DO $$
DECLARE moved text;
BEGIN
    SELECT string_agg(DISTINCT q.tenant || ' ' || q.order_number, '; ') INTO moved
      FROM gch_scope r JOIN gch_orders q ON q.order_id = r.order_id
     WHERE EXISTS (SELECT 1 FROM shipment_status_history h JOIN shipments s ON s.id = h.shipment_id
                    WHERE s.order_id = r.order_id AND s.shipment_leg = 'forward' AND h.internal_state <> 'created'
                      AND NOT EXISTS (SELECT 1 FROM gch_rows x WHERE x.history_id = h.id))
        OR NOT EXISTS (SELECT 1 FROM shipment_status_history h WHERE h.id = r.history_id);
    IF moved IS NOT NULL THEN
        RAISE EXCEPTION 'generic cancel history repair ABORTED — changed since the scan: %', moved;
    END IF;
END $$;
\endif

SAVEPOINT simulate;
DELETE FROM shipment_status_history h USING gch_scope r WHERE h.id = r.history_id;

\echo ''
\echo '== candidate orders — after the delete: frozen (OrderLineFreeze) and WaybillResolver moved state (NULL = not refused)'
SELECT q.tenant, q.order_number, q.order_status, q.rows_to_delete AS rows, q.has_live_leg,
       (SELECT s.tracking_number FROM shipments s WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id
          AND s.shipment_leg = 'forward' AND s.internal_state NOT IN ('cancelled', 'terminated')
          AND s.provider_deleted_at IS NULL ORDER BY s.created_at DESC, s.id DESC LIMIT 1) AS live_leg,
       -- OrderLineFreeze.frozenSql("o.id", "o.tenant_id") — verbatim, never edit one without the other.
       (EXISTS (
          SELECT 1 FROM shipment_status_history h
          JOIN shipments s ON s.id = h.shipment_id
          WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id
            AND s.shipment_leg = 'forward'
            AND h.internal_state <> 'created')
       OR EXISTS (
          SELECT 1 FROM order_items oi_fz
          JOIN allocations a_fz ON a_fz.order_item_id = oi_fz.id
          JOIN pieces p_fz ON p_fz.id = a_fz.piece_id
          WHERE oi_fz.order_id = o.id AND oi_fz.tenant_id = o.tenant_id
            AND a_fz.status = 'packed' AND p_fz.status::text IN ('with_courier', 'delivered', 'return_in_transit', 'return_pending_inspection'))
       OR EXISTS (
          SELECT 1 FROM order_items oi_fh
          JOIN allocations a_fh ON a_fh.order_item_id = oi_fh.id
          JOIN piece_events pe_fh ON pe_fh.piece_id = a_fh.piece_id AND pe_fh.order_id = oi_fh.order_id
          WHERE oi_fh.order_id = o.id AND oi_fh.tenant_id = o.tenant_id
            AND pe_fh.to_status::text IN ('with_courier', 'delivered', 'return_in_transit', 'return_pending_inspection'))) AS frozen_after,
       -- WaybillResolver.movedState for the live leg: its own state if past 'created', else the latest forward
       -- history state past 'created'.
       COALESCE(
         (SELECT NULLIF(s.internal_state::text, 'created') FROM shipments s WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id
            AND s.shipment_leg = 'forward' AND s.internal_state NOT IN ('cancelled', 'terminated')
            AND s.provider_deleted_at IS NULL ORDER BY s.created_at DESC, s.id DESC LIMIT 1),
         (SELECT h.internal_state::text FROM shipment_status_history h JOIN shipments s ON s.id = h.shipment_id
           WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id AND s.shipment_leg = 'forward'
             AND h.internal_state <> 'created' ORDER BY h.occurred_at DESC, h.id DESC LIMIT 1)) AS moved_state_after
FROM gch_orders q JOIN orders o ON o.id = q.order_id
WHERE q.skip_reason IS NULL
ORDER BY q.tenant, q.order_number;

SELECT count(DISTINCT order_id) AS orders, count(*) AS rows_to_delete,
       string_agg(DISTINCT (SELECT name FROM tenants t WHERE t.id = gch_scope.tenant_id), ', ') AS tenants
FROM gch_scope;

ROLLBACK TO SAVEPOINT simulate;

\if :commit
-- psql variables don't reach inside $$ … $$: hand expect_orders over as a transaction-local setting.
SELECT set_config('gch.expect_orders', :'expect_orders', true) AS expect_orders;
DO $$
DECLARE n int; expected int := current_setting('gch.expect_orders')::int;
BEGIN
    SELECT count(DISTINCT order_id) INTO n FROM gch_scope;
    IF n <> expected THEN
        RAISE EXCEPTION 'generic cancel history repair ABORTED — % candidate orders, expect_orders=% (re-run the dry run)', n, expected;
    END IF;
    IF to_regclass('public.ops_backup_2026_11_01_generic_cancel_history') IS NOT NULL THEN
        RAISE EXCEPTION 'generic cancel history repair ABORTED — ops_backup_2026_11_01_generic_cancel_history already exists';
    END IF;
END $$;

CREATE TABLE ops_backup_2026_11_01_generic_cancel_history AS
SELECT h.* FROM shipment_status_history h WHERE h.id IN (SELECT history_id FROM gch_scope);
REVOKE ALL ON ops_backup_2026_11_01_generic_cancel_history FROM PUBLIC;
DO $$
DECLARE r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['app_user', 'anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('REVOKE ALL ON ops_backup_2026_11_01_generic_cancel_history FROM %I', r);
        END IF;
    END LOOP;
END $$;
ALTER TABLE ops_backup_2026_11_01_generic_cancel_history ENABLE ROW LEVEL SECURITY;

DELETE FROM shipment_status_history h USING gch_scope r WHERE h.id = r.history_id;

-- Proof: exactly the backed-up rows are gone, nothing else was, and every candidate order is now unfrozen.
DO $$
DECLARE backed int; expected int; still int; frozen text;
BEGIN
    SELECT count(*) INTO expected FROM gch_scope;
    SELECT count(*) INTO backed FROM ops_backup_2026_11_01_generic_cancel_history;
    SELECT count(*) INTO still FROM shipment_status_history h
     WHERE h.id IN (SELECT id FROM ops_backup_2026_11_01_generic_cancel_history);
    SELECT string_agg(o.number, ', ') INTO frozen FROM orders o
     WHERE o.id IN (SELECT order_id FROM gch_scope)
       AND EXISTS (SELECT 1 FROM shipment_status_history h JOIN shipments s ON s.id = h.shipment_id
                    WHERE s.order_id = o.id AND s.shipment_leg = 'forward' AND h.internal_state <> 'created');
    IF backed <> expected OR still <> 0 OR frozen IS NOT NULL THEN
        RAISE EXCEPTION 'generic cancel history repair ABORTED — proof failed: backed up %, expected %, % still present, still frozen: %',
            backed, expected, still, coalesce(frozen, '-');
    END IF;
    RAISE NOTICE 'proof: deleted exactly the % backed-up rows; every candidate order is unfrozen', backed;
END $$;

CREATE TEMP TABLE gch_done AS SELECT DISTINCT order_id FROM gch_scope;

COMMIT;
\echo 'generic cancel history repair: COMMITTED.'

\echo '== after commit'
SELECT (SELECT count(*) FROM ops_backup_2026_11_01_generic_cancel_history) AS backed_up,
       (SELECT count(*) FROM shipment_status_history h
         WHERE h.id IN (SELECT id FROM ops_backup_2026_11_01_generic_cancel_history)) AS still_present,
       (SELECT count(*) FROM gch_done d
         WHERE EXISTS (SELECT 1 FROM shipment_status_history h JOIN shipments s ON s.id = h.shipment_id
                        WHERE s.order_id = d.order_id AND s.shipment_leg = 'forward' AND h.internal_state <> 'created')) AS still_frozen;
\else
ROLLBACK;
\echo 'generic cancel history repair: DRY RUN — nothing written (rolled back). Re-run with -v commit=yes -v expect_orders=<n> to apply.'
\endif
