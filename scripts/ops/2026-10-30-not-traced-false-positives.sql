-- =============================================================================================
-- 2026-10-30 — READ ONLY. Orders tagged "Not Traced" that went through Traced after all.
--
-- Before the returned-pieces fix, NotTracedTagger.maybeTagNotTraced tagged an order when none of its
-- allocations was live (active / packed). ReturnService.restock() releases the allocation of a piece
-- that came back, so once every piece of a returned order was restocked, the next Bosta event on that
-- order (typically its return leg reaching 46) stamped not_traced_at — one-way, never cleared.
--
-- The false-positive pattern (the same one the fixed tagger now skips): not_traced_at is set AND one
-- of the order's allocations' pieces has a return_received piece event for THIS order.
--
-- This script only reads. It changes nothing, takes no locks beyond plain SELECTs, and is safe to run
-- on production as postgres (BYPASSRLS — it reads every tenant). The un-tag script comes later, with
-- its own approval.
--
-- Usage:  psql "$DATABASE_URL" -f scripts/ops/2026-10-30-not-traced-false-positives.sql
-- =============================================================================================

\set ON_ERROR_STOP on

BEGIN READ ONLY;

\echo '== Per tenant: orders tagged Not Traced, and how many match the false-positive pattern'
WITH fp AS (
    SELECT o.tenant_id, o.id AS order_id, o.number, o.not_traced_at, o.status::text AS order_status,
           MIN(e.occurred_at)          AS first_return_received_at,
           COUNT(DISTINCT a.piece_id)  AS returned_pieces
    FROM merchant_orders o
    JOIN order_items oi ON oi.order_id = o.id
    JOIN allocations a  ON a.order_item_id = oi.id
    JOIN piece_events e ON e.piece_id = a.piece_id
                       AND e.order_id = o.id
                       AND e.event_type = 'return_received'
    WHERE o.not_traced_at IS NOT NULL
    GROUP BY o.tenant_id, o.id, o.number, o.not_traced_at, o.status
),
tagged AS (
    SELECT tenant_id, COUNT(*) AS tagged FROM merchant_orders WHERE not_traced_at IS NOT NULL GROUP BY tenant_id
)
SELECT t.id AS tenant_id, t.name AS tenant, tg.tagged,
       (SELECT COUNT(*) FROM fp WHERE fp.tenant_id = t.id) AS false_positive
FROM tenants t
JOIN tagged tg ON tg.tenant_id = t.id
ORDER BY false_positive DESC, tenant;

\echo '== The false-positive orders (tagged_after_return = the tag came after the first return scan)'
WITH fp AS (
    SELECT o.tenant_id, o.id AS order_id, o.number, o.not_traced_at, o.status::text AS order_status,
           MIN(e.occurred_at)          AS first_return_received_at,
           COUNT(DISTINCT a.piece_id)  AS returned_pieces
    FROM merchant_orders o
    JOIN order_items oi ON oi.order_id = o.id
    JOIN allocations a  ON a.order_item_id = oi.id
    JOIN piece_events e ON e.piece_id = a.piece_id
                       AND e.order_id = o.id
                       AND e.event_type = 'return_received'
    WHERE o.not_traced_at IS NOT NULL
    GROUP BY o.tenant_id, o.id, o.number, o.not_traced_at, o.status
)
SELECT t.name AS tenant, fp.number, fp.order_status, fp.not_traced_at, fp.first_return_received_at,
       fp.returned_pieces, (fp.not_traced_at > fp.first_return_received_at) AS tagged_after_return,
       fp.order_id
FROM fp
JOIN tenants t ON t.id = fp.tenant_id
ORDER BY t.name, fp.not_traced_at;

ROLLBACK;
