-- =============================================================================================
-- Bosta pre-connect cleanup — BROEK + Femine (2026-10-02)
--
-- Run MANUALLY, once, AFTER the deploy that carries the discovery retry list (V128), the
-- pre-connect filter (BostaWebhookJob 6.2) and V129 — otherwise the discovery poll re-creates
-- these rows. Run as the database owner (postgres / Supabase SQL editor): RLS is bypassed, so
-- EVERY statement below is bound to a tenant_id AND exact ids.
--
-- Not a Flyway migration. Leaves webhook_events untouched.
--
-- How to use:
--   1. Run the whole file as is. It ends with ROLLBACK — nothing is kept.
--   2. Read every "expect" line against the counts printed above it.
--   3. Only then change the final ROLLBACK to COMMIT and run it again.
-- =============================================================================================

BEGIN;

-- BROEK  d6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d
-- Femine a7d6fab9-828d-4b3a-8ab6-71e6bef3d197

-- ---------------------------------------------------------------------------------------------
-- Part 1 — BROEK's 4 pre-connect EXC- placeholder orders (dashboard exchanges, no request)
--
--   order                                  number          exchange                              order_item                            shipment
--   1993fad9-f049-4721-bf6d-ac439ab93f63   EXC-9651000477  fc1a308d-a69d-4aac-bee7-791de94810f6  8375857f-99bd-48c0-a66c-e732e5cd6d57  e20c47f8-3b99-41c9-a6ce-756ca85e8543
--   85f739a4-d73d-4cc5-9115-18acf1e3d937   EXC-1785841805  c6d0cde2-4bfa-4651-8c19-8345bda86c78  84652202-02b0-49e6-addb-e600b7c79f2f  35056a52-e841-4363-8ea5-5ac9517f4c12
--   a83dbf34-3390-4e3c-9726-3cea30661cfa   EXC-1010896713  8b46ac95-0c06-4e47-ac43-39cc80819da2  b865065c-4766-41f1-ab7d-afb2a87ff246  ff234b29-9d4f-4d0c-93b5-d4ddb384427e
--   70fb30b4-7f21-4ad7-b128-4e82b672611a   EXC-4204825497  7eecc603-ebb8-4e99-861a-5cc172f36f3e  fbc19284-2ac7-429d-a3fe-eeef447f687c  6ccd67c1-9c43-4232-b51e-c9324b4fe16b
--
-- FK delete order (checked 2026-10-02): exchanges.outbound_order_id / matched_order_id → orders
-- are NO ACTION, so exchanges go first; shipment_status_history and pack_print_batch_items
-- CASCADE from shipments; pack_session_orders and order_fulfillment_tracking CASCADE from
-- orders; pack_sessions.current_order_id is SET NULL. Every NO ACTION reference that could hold
-- a ledger or custody fact is asserted to be zero below — the script aborts otherwise.
-- ---------------------------------------------------------------------------------------------

-- Dry-run: the targets themselves (expect 4 orders, all internal:exchange:, 4 exchanges with
-- return_request_id NULL and matched_order_id NULL, 4 order_items, 4 forward shipments).
SELECT o.id, o.number, o.external_id,
       e.id AS exchange_id, e.status, e.return_request_id, e.matched_order_id,
       (SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = o.id AND oi.tenant_id = o.tenant_id) AS order_items,
       (SELECT COUNT(*) FROM shipments s   WHERE s.order_id  = o.id AND s.tenant_id  = o.tenant_id) AS shipments,
       (SELECT COUNT(*) FROM shipment_status_history h JOIN shipments s ON s.id = h.shipment_id
         WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id) AS history_rows
FROM orders o
LEFT JOIN exchanges e ON e.outbound_order_id = o.id AND e.tenant_id = o.tenant_id
WHERE o.tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d'
  AND o.id IN ('1993fad9-f049-4721-bf6d-ac439ab93f63', '85f739a4-d73d-4cc5-9115-18acf1e3d937',
               'a83dbf34-3390-4e3c-9726-3cea30661cfa', '70fb30b4-7f21-4ad7-b128-4e82b672611a')
ORDER BY o.number;
-- expect (prod 2026-10-02): 4 rows; order_items 1 each; shipments 1 each; history 13/12/2/2

-- Guard: no pieces, piece_events, allocations, pickups, return links, notes or Shopify
-- inventory claims touch these orders / shipments / order_items. Aborts the transaction if any do.
DO $$
DECLARE
    t   uuid   := 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d';
    ord uuid[] := ARRAY['1993fad9-f049-4721-bf6d-ac439ab93f63', '85f739a4-d73d-4cc5-9115-18acf1e3d937',
                        'a83dbf34-3390-4e3c-9726-3cea30661cfa', '70fb30b4-7f21-4ad7-b128-4e82b672611a']::uuid[];
    shp uuid[] := ARRAY['e20c47f8-3b99-41c9-a6ce-756ca85e8543', '35056a52-e841-4363-8ea5-5ac9517f4c12',
                        'ff234b29-9d4f-4d0c-93b5-d4ddb384427e', '6ccd67c1-9c43-4232-b51e-c9324b4fe16b']::uuid[];
    itm uuid[] := ARRAY['8375857f-99bd-48c0-a66c-e732e5cd6d57', '84652202-02b0-49e6-addb-e600b7c79f2f',
                        'b865065c-4766-41f1-ab7d-afb2a87ff246', 'fbc19284-2ac7-429d-a3fe-eeef447f687c']::uuid[];
    n   bigint;
BEGIN
    SELECT
        (SELECT COUNT(*) FROM pieces           WHERE tenant_id = t AND current_order_id = ANY (ord))
      + (SELECT COUNT(*) FROM piece_events     WHERE tenant_id = t AND (order_id = ANY (ord) OR shipment_id = ANY (shp)))
      + (SELECT COUNT(*) FROM allocations      WHERE tenant_id = t AND order_item_id = ANY (itm))
      + (SELECT COUNT(*) FROM return_request_items WHERE tenant_id = t AND order_item_id = ANY (itm))
      + (SELECT COUNT(*) FROM pickup_shipments ps JOIN shipments s ON s.id = ps.shipment_id
          WHERE s.tenant_id = t AND ps.shipment_id = ANY (shp))
      + (SELECT COUNT(*) FROM return_requests  WHERE tenant_id = t AND (order_id = ANY (ord) OR return_shipment_id = ANY (shp)))
      + (SELECT COUNT(*) FROM order_notes      WHERE tenant_id = t AND order_id = ANY (ord))
      + (SELECT COUNT(*) FROM exchanges        WHERE tenant_id = t AND matched_order_id = ANY (ord))
      + (SELECT COUNT(*) FROM exchanges        WHERE tenant_id = t AND outbound_order_id = ANY (ord)
                                                 AND (return_request_id IS NOT NULL OR matched_order_id IS NOT NULL))
      + (SELECT COUNT(*) FROM orders           WHERE tenant_id = t AND id = ANY (ord)
                                                 AND external_id NOT LIKE 'internal:exchange:%')
    INTO n;
    IF n > 0 THEN
        RAISE EXCEPTION 'ABORT: % dependent ledger/custody/request row(s) on the EXC- orders — nothing deleted', n;
    END IF;
    RAISE NOTICE 'guard ok: 0 dependent rows';
END $$;

DELETE FROM exchanges
WHERE tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d'
  AND id IN ('fc1a308d-a69d-4aac-bee7-791de94810f6', 'c6d0cde2-4bfa-4651-8c19-8345bda86c78',
             '8b46ac95-0c06-4e47-ac43-39cc80819da2', '7eecc603-ebb8-4e99-861a-5cc172f36f3e')
  AND return_request_id IS NULL AND matched_order_id IS NULL;
-- expect DELETE 4

DELETE FROM shipment_status_history
WHERE tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d'
  AND shipment_id IN ('e20c47f8-3b99-41c9-a6ce-756ca85e8543', '35056a52-e841-4363-8ea5-5ac9517f4c12',
                      'ff234b29-9d4f-4d0c-93b5-d4ddb384427e', '6ccd67c1-9c43-4232-b51e-c9324b4fe16b');
-- expect DELETE = the history_rows total above (29 on 2026-10-02; it grows while the poll runs)

DELETE FROM shipments
WHERE tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d'
  AND id IN ('e20c47f8-3b99-41c9-a6ce-756ca85e8543', '35056a52-e841-4363-8ea5-5ac9517f4c12',
             'ff234b29-9d4f-4d0c-93b5-d4ddb384427e', '6ccd67c1-9c43-4232-b51e-c9324b4fe16b')
  AND order_id IN ('1993fad9-f049-4721-bf6d-ac439ab93f63', '85f739a4-d73d-4cc5-9115-18acf1e3d937',
                   'a83dbf34-3390-4e3c-9726-3cea30661cfa', '70fb30b4-7f21-4ad7-b128-4e82b672611a');
-- expect DELETE 4

DELETE FROM order_items
WHERE tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d'
  AND id IN ('8375857f-99bd-48c0-a66c-e732e5cd6d57', '84652202-02b0-49e6-addb-e600b7c79f2f',
             'b865065c-4766-41f1-ab7d-afb2a87ff246', 'fbc19284-2ac7-429d-a3fe-eeef447f687c')
  AND order_id IN ('1993fad9-f049-4721-bf6d-ac439ab93f63', '85f739a4-d73d-4cc5-9115-18acf1e3d937',
                   'a83dbf34-3390-4e3c-9726-3cea30661cfa', '70fb30b4-7f21-4ad7-b128-4e82b672611a');
-- expect DELETE 4

DELETE FROM orders
WHERE tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d'
  AND id IN ('1993fad9-f049-4721-bf6d-ac439ab93f63', '85f739a4-d73d-4cc5-9115-18acf1e3d937',
             'a83dbf34-3390-4e3c-9726-3cea30661cfa', '70fb30b4-7f21-4ad7-b128-4e82b672611a')
  AND external_id IN ('internal:exchange:9651000477', 'internal:exchange:1785841805',
                      'internal:exchange:1010896713', 'internal:exchange:4204825497');
-- expect DELETE 4

-- Their exception_notifications (dedup markers keyed by the deleted exchange ids).
SELECT id, exception_type, subject_key FROM exception_notifications
WHERE tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d' AND id IN (206, 207, 247, 248);
-- expect 4 rows, all exchange_unmapped_state:<one of the 4 exchange ids above>
DELETE FROM exception_notifications
WHERE tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d'
  AND id IN (206, 207, 247, 248)
  AND subject_key IN ('exchange_unmapped_state:fc1a308d-a69d-4aac-bee7-791de94810f6',
                      'exchange_unmapped_state:c6d0cde2-4bfa-4651-8c19-8345bda86c78',
                      'exchange_unmapped_state:7eecc603-ebb8-4e99-861a-5cc172f36f3e',
                      'exchange_unmapped_state:8b46ac95-0c06-4e47-ac43-39cc80819da2');
-- expect DELETE 4

-- ---------------------------------------------------------------------------------------------
-- Part 2 — BROEK exchange 7577553206 (pre-connect, never mapped) → dismissed
-- ---------------------------------------------------------------------------------------------
SELECT id, tracking_number, status, return_request_id, outbound_order_id, matched_order_id
FROM exchanges
WHERE tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d' AND id = '277dc26d-450a-4944-b80c-dcd02c3a9df0';
-- expect 1 row: 7577553206 needs_mapping, all three ids NULL
UPDATE exchanges SET status = 'dismissed', updated_at = now()
WHERE tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d'
  AND id = '277dc26d-450a-4944-b80c-dcd02c3a9df0'
  AND tracking_number = '7577553206'
  AND status = 'needs_mapping'
  AND return_request_id IS NULL AND outbound_order_id IS NULL;
-- expect UPDATE 1

-- ---------------------------------------------------------------------------------------------
-- Part 3 — unresolved pre-connect unlinked_bosta_deliveries rows → resolved
--
-- No reason column exists; match_reason (free text, no CHECK) is set to PRE_CONNECT_CLEANUP
-- (every target's previous value is NO_MATCH or EXCHANGE_MULTI_ITEM). exception_resolutions has
-- no system/ops actor (resolved_by is NOT NULL REFERENCES users), so each delivery's audit row
-- (GET /exceptions/resolutions) is attributed to the tenant's first owner with the note
-- "Ops cleanup by Traced (pre-connect, 2026-10-02)". exception_notifications rows are left as is.
--
-- Only rows the new pre-connect filter also covers (reference below the tenant's first ingested
-- order number, or Bosta createdAt before the cutoff):
--   BROEK  (21): 5560 5561 5562 5563 5564 5566 5568 5570 5571 5572 5575 5576 5577 5578 5579
--                5580 5581 5611 5612 5613 5614
--   Femine (23): 5582 5583 5584 5585 5586 5587 5588 5589 5590 5591 5598 5599 5600 5601 5602
--                5603 5604 5605 5606 5607 5608 5609 5610
-- NOT touched (NULL reference, created in Bosta after the cutoff — stay unresolved):
--   BROEK 5616, 5627; Femine 5615, 5628, 5631, 5648, 5657
-- ---------------------------------------------------------------------------------------------

SELECT tenant_id, COUNT(*) AS rows, COUNT(*) FILTER (WHERE resolved) AS already_resolved,
       COUNT(*) FILTER (WHERE business_reference IS NULL) AS null_ref
FROM unlinked_bosta_deliveries
WHERE (tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d'
       AND id IN (5560,5561,5562,5563,5564,5566,5568,5570,5571,5572,5575,5576,5577,5578,5579,
                  5580,5581,5611,5612,5613,5614))
   OR (tenant_id = 'a7d6fab9-828d-4b3a-8ab6-71e6bef3d197'
       AND id IN (5582,5583,5584,5585,5586,5587,5588,5589,5590,5591,5598,5599,5600,5601,5602,
                  5603,5604,5605,5606,5607,5608,5609,5610))
GROUP BY tenant_id;
-- expect (prod 2026-10-02): BROEK 21 rows / 0 resolved / 0 null_ref ; Femine 23 rows / 0 resolved / 4 null_ref

WITH t AS (
    UPDATE unlinked_bosta_deliveries
    SET resolved = true, match_reason = 'PRE_CONNECT_CLEANUP', last_seen_at = now()
    WHERE resolved = false
      AND ((tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d'
            AND id IN (5560,5561,5562,5563,5564,5566,5568,5570,5571,5572,5575,5576,5577,5578,5579,
                       5580,5581,5611,5612,5613,5614))
        OR (tenant_id = 'a7d6fab9-828d-4b3a-8ab6-71e6bef3d197'
            AND id IN (5582,5583,5584,5585,5586,5587,5588,5589,5590,5591,5598,5599,5600,5601,5602,
                       5603,5604,5605,5606,5607,5608,5609,5610)))
    RETURNING tenant_id, id
)
INSERT INTO exception_resolutions (tenant_id, exception_type, subject_key, resolved_by, note)
SELECT t.tenant_id, 'unmatched_delivery', 'unmatched:' || t.id,
       (SELECT u.id FROM users u WHERE u.tenant_id = t.tenant_id AND u.role = 'owner'
        ORDER BY u.created_at, u.id LIMIT 1),
       'Ops cleanup by Traced (pre-connect, 2026-10-02)'
FROM t;
-- expect INSERT 0 44  (BROEK 21 + Femine 23)


-- ---------------------------------------------------------------------------------------------
-- Final check — expect: 0 EXC orders for BROEK, exactly the 7 untouched NULL-reference rows
-- still unresolved for BROEK + Femine, exchange 7577553206 dismissed.
-- ---------------------------------------------------------------------------------------------
SELECT
  (SELECT COUNT(*) FROM orders WHERE tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d'
     AND external_id LIKE 'internal:exchange:%' AND number IN
     ('EXC-9651000477','EXC-1785841805','EXC-1010896713','EXC-4204825497')) AS exc_orders_left,
  (SELECT string_agg(id::text, ',' ORDER BY id) FROM unlinked_bosta_deliveries WHERE resolved = false
     AND tenant_id IN ('d6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d', 'a7d6fab9-828d-4b3a-8ab6-71e6bef3d197')) AS unresolved_left,
  (SELECT status FROM exchanges WHERE tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d'
     AND id = '277dc26d-450a-4944-b80c-dcd02c3a9df0') AS exchange_7577553206;
-- expect 0 | 5615,5616,5627,5628,5631,5648,5657 | dismissed   (unresolved_left lists more ids if
-- NEW deliveries arrived since the dry run — check them before committing; this script never
-- touches them)

ROLLBACK;   -- ← change to COMMIT only after every "expect" above matched
