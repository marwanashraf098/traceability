-- V58: backfill — release stale allocations left behind by ReturnService.restock().
--
-- Root cause: restock() cleared pieces.current_order_id but never released the piece's
-- OLD allocations row, which stayed 'packed'/'active' forever. FulfillService.scan()'s
-- ALREADY_RESERVED guard reads allocations.status by piece_id alone (no order filter),
-- so a restocked, genuinely-available piece was permanently blocked from re-allocation
-- to any new order. Fixed going forward in ReturnService.restock() (same deploy); this
-- migration is the one-time backfill for pieces already stuck this way.
--
-- Safety gate: only release an allocation whose piece is BOTH 'available' AND has
-- current_order_id IS NULL. That combination is only reachable via restock() (or an
-- equivalent legitimate free-and-release path) — a piece genuinely reserved/packed for
-- an in-flight order never has both conditions true at once, so this can never touch a
-- live allocation for a real, active shipment in progress.
--
-- Flyway runs as postgres (BYPASSRLS, see spring.flyway.* / DataSourceConfig
-- ownerDataSource) — this correctly spans all tenants in one pass.

UPDATE allocations
SET status = 'released'
WHERE status IN ('active', 'packed')
  AND piece_id IN (
      SELECT p.id FROM pieces p
      WHERE p.status = 'available'
        AND p.current_order_id IS NULL
  );
