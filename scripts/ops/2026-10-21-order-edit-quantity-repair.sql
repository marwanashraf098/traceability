-- ============================================================
-- Shopify order edits — one-off repair (2026-10-21): open order lines Traced still counts at Shopify's
-- ORIGINAL quantity after an order edit / pre-shipment refund lowered it.
--
-- Until this build Traced stored line_items[].quantity (the ordered amount — Shopify keeps it unchanged and
-- lowers current_quantity, to 0 for a removed line). Removed lines stayed as live demand in Pick & Pack,
-- the gather list and committed stock (prod 2026-10-21: Dolt.eg #16443 wants BLACK LEATHER SET – M and – S).
--
-- Candidate = an order_items row whose stored REST raw has current_quantity (V149 current_qty) LOWER than
-- quantity, on an order that is:
--   - a Shopify order (not internal:exchange / internal:portal),
--   - not cancelled (status and raw cancelled_at),
--   - NOT frozen — OrderLineFreeze.frozenSql, inlined verbatim below (OrderEditQuantityRepairScriptTest
--     checks it): an order that has left Traced custody keeps what was shipped.
-- Each candidate becomes quantity = current_qty (0 for a removed line — the line is kept, V174).
-- Lines with no current_qty (imported through GraphQL before this build) are LISTED and never changed.
--
-- Aborts the whole run (nothing written) if any candidate line has an active or packed allocation — in the
-- commit run re-checked inside the transaction AFTER the candidate lines are locked (FOR UPDATE: a concurrent
-- pick scan waits on the same row lock, then sees the new quantity). The dry run takes no locks.
--
-- After the UPDATE (commit run only) it proves, in the transaction, that the rows this transaction wrote are
-- exactly the candidates and each now holds its current_qty; then commits and re-reads them.
--
-- Run (psql as the database owner, BYPASSRLS). Dry run by default — no UPDATE runs, ROLLBACK, writes nothing:
--   psql "<conn>" -v ON_ERROR_STOP=1 -f scripts/ops/2026-10-21-order-edit-quantity-repair.sql
-- Apply:
--   psql "<conn>" -v ON_ERROR_STOP=1 -v commit=yes -f scripts/ops/2026-10-21-order-edit-quantity-repair.sql
-- Light on prod: generated columns only (no jsonb detoasting of order payloads), a few dozen rows.
-- ============================================================

\set ON_ERROR_STOP on
\if :{?commit}
\else
\set commit no
\endif

BEGIN;

-- Open, unfrozen Shopify orders — the only orders whose lines may change.
CREATE TEMP TABLE oeq_orders ON COMMIT DROP AS
SELECT o.id AS order_id, o.tenant_id
FROM orders o
WHERE o.external_id NOT LIKE 'internal:%'
  AND o.status <> 'cancelled'::order_status
  AND NOT o.raw_cancelled
  -- OrderLineFreeze.frozenSql("o.id", "o.tenant_id") — verbatim, never edit one without the other.
  AND NOT (EXISTS (
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
            AND pe_fh.to_status::text IN ('with_courier', 'delivered', 'return_in_transit', 'return_pending_inspection')));

CREATE TEMP TABLE oeq_rows ON COMMIT DROP AS
SELECT oi.id               AS item_id,
       oi.tenant_id,
       t.name              AS tenant,
       o.id                AS order_id,
       o.number            AS order_number,
       o.status::text      AS order_status,
       pr.title || COALESCE(' – ' || NULLIF(v.title, ''), '') AS item,
       v.sku,
       oi.quantity         AS quantity_before,
       oi.current_qty      AS quantity_after
FROM oeq_orders q
JOIN orders o   ON o.id = q.order_id
JOIN order_items oi ON oi.order_id = o.id AND oi.tenant_id = o.tenant_id
JOIN tenants t  ON t.id = o.tenant_id
JOIN variants v ON v.id = oi.variant_id
JOIN products pr ON pr.id = v.product_id
WHERE oi.current_qty IS NOT NULL
  AND oi.current_qty < oi.quantity;

\echo ''
\echo '== order-edit quantity repair — every candidate line, before → after'
SELECT tenant, order_number, order_status, item, sku, quantity_before AS before, quantity_after AS after
FROM oeq_rows
ORDER BY tenant, order_number, item;

\echo ''
\echo '== per order'
SELECT tenant, order_number, order_status,
       count(*) AS lines, sum(quantity_before) AS units_before,
       sum(quantity_before - quantity_after) AS units_removed
FROM oeq_rows
GROUP BY tenant, order_number, order_status
ORDER BY tenant, order_number;

SELECT count(DISTINCT order_id) AS orders, count(*) AS lines,
       coalesce(sum(quantity_before - quantity_after), 0) AS units_removed
FROM oeq_rows;

\echo ''
\echo '== listed, never changed: GraphQL-imported lines with no current quantity on open, unfrozen orders'
SELECT t.name AS tenant, o.number AS order_number, o.status::text AS order_status,
       pr.title || COALESCE(' – ' || NULLIF(v.title, ''), '') AS item, oi.quantity
FROM oeq_orders q
JOIN orders o   ON o.id = q.order_id
JOIN order_items oi ON oi.order_id = o.id AND oi.tenant_id = o.tenant_id
JOIN tenants t  ON t.id = o.tenant_id
JOIN variants v ON v.id = oi.variant_id
JOIN products pr ON pr.id = v.product_id
WHERE oi.current_qty IS NULL
  AND oi.raw ? 'gid'
ORDER BY 1, 2, 4;

\if :commit
-- Lock the candidate lines (a pick scan takes the same row lock before it counts allocations), so the
-- allocation check below and the UPDATE see the same lines. The dry run takes no locks.
WITH locked AS (SELECT oi.id FROM order_items oi JOIN oeq_rows r ON r.item_id = oi.id FOR UPDATE OF oi)
SELECT count(*) AS candidate_lines_locked FROM locked;
\endif

-- Abort if any candidate line holds a live allocation (in the commit run: under the row locks).
DO $$
DECLARE bad text;
BEGIN
    SELECT string_agg(r.tenant || ' ' || r.order_number || ' · ' || r.item || ' (' || a.status::text || ')', '; ')
      INTO bad
      FROM oeq_rows r JOIN allocations a ON a.order_item_id = r.item_id
     WHERE a.status IN ('active', 'packed');
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'order-edit quantity repair ABORTED — candidate lines hold live allocations: %', bad;
    END IF;
END $$;

\if :commit
UPDATE order_items oi
   SET quantity = r.quantity_after
  FROM oeq_rows r
 WHERE oi.id = r.item_id
   AND oi.quantity = r.quantity_before;

SELECT (txid_current() % 4294967296)::text AS repair_xid \gset

-- Proof: the rows this transaction wrote are exactly the candidates, each at its new quantity.
DO $$
DECLARE written int; expected int; stray int; wrong int;
BEGIN
    SELECT count(*) INTO expected FROM oeq_rows;
    SELECT count(*) INTO written FROM order_items WHERE xmin::text = (txid_current() % 4294967296)::text;
    SELECT count(*) INTO stray FROM order_items oi
     WHERE oi.xmin::text = (txid_current() % 4294967296)::text
       AND NOT EXISTS (SELECT 1 FROM oeq_rows r WHERE r.item_id = oi.id);
    SELECT count(*) INTO wrong FROM oeq_rows r JOIN order_items oi ON oi.id = r.item_id
     WHERE oi.quantity <> r.quantity_after;
    IF written <> expected OR stray <> 0 OR wrong <> 0 THEN
        RAISE EXCEPTION 'order-edit quantity repair ABORTED — proof failed: wrote % rows, expected %, % outside the list, % at the wrong quantity',
            written, expected, stray, wrong;
    END IF;
    RAISE NOTICE 'proof: wrote exactly the % listed rows, each at its current quantity', written;
END $$;

CREATE TEMP TABLE oeq_done AS SELECT item_id, quantity_after FROM oeq_rows;

COMMIT;
\echo 'order-edit quantity repair: COMMITTED.'

\echo '== after commit — the rows the repair transaction wrote (still that transaction''s rows, at the new quantity)'
SELECT count(*) FILTER (WHERE oi.xmin::text = :'repair_xid')                AS rows_written_by_repair,
       count(*) FILTER (WHERE oi.xmin::text = :'repair_xid'
                          AND NOT EXISTS (SELECT 1 FROM oeq_done d WHERE d.item_id = oi.id)) AS outside_the_list,
       (SELECT count(*) FROM oeq_done d JOIN order_items x ON x.id = d.item_id
         WHERE x.quantity = d.quantity_after)                               AS listed_at_new_quantity,
       (SELECT count(*) FROM oeq_done)                                      AS listed
FROM order_items oi;
\else
ROLLBACK;
\echo 'order-edit quantity repair: DRY RUN — nothing written (rolled back). Re-run with -v commit=yes to apply.'
\endif
