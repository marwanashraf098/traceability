-- ============================================================
-- Deleted-in-Bosta slice 5 — backfill: close the Bosta legs the status poll already found
-- "Delivery not found" before V172's confirmation rule existed.
--
-- Targets every provider = 'bosta' shipment with provider_not_found_at set and provider_deleted_at NULL.
-- A row is changed only when ALL guards hold; a row failing any guard is LISTED with the failed guard(s)
-- and never changed:
--   history_created_only  shipment_status_history holds only 'created' rows for it (it never moved)
--   still_open            internal_state not already terminal (delivered/returned/lost/terminated/cancelled)
--   no_pieces_out         no piece of its order (non-released allocation) is awaiting_pickup / with_courier
--   no_open_request       a return leg is held by no open return request (requested … refund_pending)
-- Passing rows become: internal_state 'cancelled', provider_deleted_at = provider_not_found_at,
-- provider_deleted_reason 'deleted_in_bosta_backfill' (provider_not_found_at is Cairo wall-clock `timestamp`,
-- converted explicitly). NO shipment_status_history row, NO piece_events
-- (same as BostaDeletionService.close — a replacement waybill can still be packed). Then the affected
-- orders' stranded unlinked deliveries get matcher_version = NULL (one more matcher retry each).
-- Generic (non-Bosta courier) legs are never touched. Nothing is sent to Bosta.
--
-- Run (psql as the database owner, BYPASSRLS). Dry run by default — no UPDATE runs, ROLLBACK, writes nothing:
--   psql "<conn>" -v ON_ERROR_STOP=1 -f scripts/ops/2026-10-19-deleted-in-bosta-backfill.sql
-- Apply:
--   psql "<conn>" -v ON_ERROR_STOP=1 -v commit=yes -f scripts/ops/2026-10-19-deleted-in-bosta-backfill.sql
-- Light on prod: one pass over the stamped legs (~30 rows) and their orders; no jsonb detoasting.
-- ============================================================

\set ON_ERROR_STOP on
\if :{?commit}
\else
\set commit no
\endif

-- Dry run: no UPDATE runs at all and the transaction is rolled back (only the temp table below is created).
BEGIN;

CREATE TEMP TABLE dib_rows ON COMMIT DROP AS
SELECT s.id                       AS shipment_id,
       s.tenant_id,
       t.name                     AS tenant,
       s.order_id,
       o.number                   AS order_number,
       s.tracking_number,
       s.shipment_leg::text       AS leg,
       (s.raw -> 'type' ->> 'code') AS bosta_type,
       s.internal_state::text     AS state,
       s.provider_not_found_at,
       NOT EXISTS (SELECT 1 FROM shipment_status_history h
                    WHERE h.shipment_id = s.id AND h.internal_state <> 'created')            AS history_created_only,
       s.internal_state NOT IN ('delivered','returned','lost','terminated','cancelled')      AS still_open,
       NOT EXISTS (SELECT 1 FROM order_items oi
                     JOIN allocations a ON a.order_item_id = oi.id AND a.status <> 'released'
                     JOIN pieces p      ON p.id = a.piece_id
                    WHERE oi.order_id = s.order_id
                      AND p.status IN ('awaiting_pickup', 'with_courier'))                   AS no_pieces_out,
       (s.shipment_leg <> 'return' OR NOT EXISTS (
            SELECT 1 FROM return_requests h
             WHERE h.tenant_id = s.tenant_id
               AND (h.return_shipment_id = s.id OR h.bosta_tracking_number = s.tracking_number)
               AND h.status::text IN ('requested','approved','pickup_booked','received','refund_pending'))) AS no_open_request
FROM shipments s
JOIN tenants t ON t.id = s.tenant_id
LEFT JOIN orders o ON o.id = s.order_id
WHERE s.provider = 'bosta'
  AND s.provider_not_found_at IS NOT NULL
  AND s.provider_deleted_at IS NULL;

\echo ''
\echo '== deleted-in-Bosta backfill — every stamped leg and what it becomes'
SELECT tenant, order_number, tracking_number,
       leg || CASE bosta_type WHEN '10' THEN ' · send' WHEN '25' THEN ' · customer return pickup'
                              WHEN '30' THEN ' · exchange' WHEN '20' THEN ' · return to origin'
                              ELSE coalesce(' · type ' || bosta_type, '') END                AS leg_type,
       state,
       to_char(provider_not_found_at, 'YYYY-MM-DD HH24:MI')                                  AS not_found_at_cairo,
       CASE WHEN history_created_only AND still_open AND no_pieces_out AND no_open_request
            THEN 'cancelled (deleted_in_bosta_backfill)'
            ELSE 'LISTED, unchanged — failed: ' || concat_ws(', ',
                 CASE WHEN NOT history_created_only THEN 'history moved past created' END,
                 CASE WHEN NOT still_open           THEN 'already ' || state END,
                 CASE WHEN NOT no_pieces_out        THEN 'pieces awaiting_pickup / with_courier' END,
                 CASE WHEN NOT no_open_request      THEN 'open return request' END) END   AS becomes
FROM dib_rows
ORDER BY tenant, provider_not_found_at, tracking_number;

SELECT count(*) FILTER (WHERE history_created_only AND still_open AND no_pieces_out AND no_open_request) AS to_close,
       count(*) FILTER (WHERE NOT (history_created_only AND still_open AND no_pieces_out AND no_open_request)) AS listed_unchanged,
       count(*) AS stamped_total
FROM dib_rows;

\if :commit
UPDATE shipments s
   SET internal_state          = 'cancelled'::shipment_internal_state,
       -- provider_not_found_at is `timestamp` (V44) holding the app's Cairo wall-clock time (JVM
       -- -Duser.timezone=Africa/Cairo) — read it as Cairo time, whatever this psql session's TimeZone is.
       provider_deleted_at     = s.provider_not_found_at AT TIME ZONE 'Africa/Cairo',
       provider_deleted_reason = 'deleted_in_bosta_backfill'
  FROM dib_rows r
 WHERE s.id = r.shipment_id
   AND r.history_created_only AND r.still_open AND r.no_pieces_out AND r.no_open_request
   AND s.provider = 'bosta' AND s.provider_deleted_at IS NULL;

-- One more matcher retry for the closed forward legs' orders' unlinked deliveries. Mirrors the reference
-- part of OrderReference (the order number as sent / '#'-stripped / '#'-prefixed, or its external_id, or
-- shopifyInfo.orderId = the order's Shopify GID tail); BostaDeletionService uses OrderReference itself.
UPDATE unlinked_bosta_deliveries u
   SET matcher_version = NULL
  FROM dib_rows r
  JOIN orders o ON o.id = r.order_id
 WHERE r.leg = 'forward'
   AND r.history_created_only AND r.still_open AND r.no_pieces_out AND r.no_open_request
   AND u.tenant_id = r.tenant_id AND u.resolved = false
   AND o.external_id NOT LIKE 'internal:%'
   AND (   ltrim(u.business_reference, '#') = ltrim(o.number, '#')
        OR u.business_reference = o.external_id
        OR (o.external_id LIKE 'gid://shopify/Order/%'
            AND u.raw #>> '{shopifyInfo,orderId}' = substring(o.external_id FROM 'gid://shopify/Order/(.*)$')));

COMMIT;
\echo 'deleted-in-Bosta backfill: COMMITTED.'
\else
ROLLBACK;
\echo 'deleted-in-Bosta backfill: DRY RUN — nothing written (rolled back). Re-run with -v commit=yes to apply.'
\endif
