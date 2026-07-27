-- V57: "Not Traced" tag — orders that were sent to Bosta without ever passing through
-- the Traced pick/pack flow (merchant fulfilled directly via the Bosta app / plugin).
--
-- not_traced_at is set when the order's LATEST shipment_leg='forward' shipment is in a
-- sent-to-Bosta state (internal_state <> 'created') AND the order has zero allocations in
-- status active/packed (Marawan's definition: "it doesn't have an allocated piece to it").
-- Internal, badge-only — not pushed to Shopify, no Exceptions entry.
--
-- This predicate shape (latest forward shipment via `id DESC LIMIT 1` correlated subselect,
-- joined with the zero-allocation NOT EXISTS) MUST stay identical to
-- com.traceability.inventory.NotTracedTagger.maybeTagNotTraced() and to the queue-gate
-- LEFT JOIN LATERAL in FulfillService.getQueue() — all three select "latest forward
-- shipment" the same way so backfill, detector, and queue removal can never disagree.

ALTER TABLE orders ADD COLUMN not_traced_at timestamptz;

CREATE INDEX orders_not_traced_idx ON orders (tenant_id) WHERE not_traced_at IS NOT NULL;

-- Backfill: Flyway connects as postgres (BYPASSRLS, see spring.flyway.* / DataSourceConfig
-- ownerDataSource), so this UPDATE correctly spans all tenants in one pass.
UPDATE orders o
SET not_traced_at = now()
WHERE o.not_traced_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM allocations a
      JOIN order_items oi ON oi.id = a.order_item_id
      WHERE oi.order_id = o.id AND a.status IN ('active', 'packed')
  )
  AND EXISTS (
      SELECT 1 FROM shipments s
      WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id
        AND s.shipment_leg = 'forward'
        AND s.internal_state IS NOT NULL
        AND s.internal_state <> 'created'
        AND s.id = (
            SELECT s2.id FROM shipments s2
            WHERE s2.order_id = o.id AND s2.tenant_id = o.tenant_id
              AND s2.shipment_leg = 'forward'
            ORDER BY s2.id DESC LIMIT 1
        )
  );
