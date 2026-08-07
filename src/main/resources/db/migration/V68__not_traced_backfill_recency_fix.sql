-- V68 — Correct V57's not_traced backfill: created_at DESC, id DESC (not bare id DESC)
--
-- V57's one-time backfill picked each order's "latest forward shipment" via a correlated
-- subselect ordered `s2.id DESC LIMIT 1`. UUIDv4 is not time-ordered (see CLAUDE.md's
-- invariant), so on an order with two forward shipments — legitimate under
-- ux_active_shipment_per_order_leg (V43) only when one of the two is 'terminated' or
-- 'cancelled' — where the older row happens to carry the higher UUID, that pick could
-- silently disagree with actual recency and select the wrong row. If the wrongly-picked
-- (older) shipment was still 'created' while the true latest shipment had already moved
-- past it, the order was never tagged not_traced even though it should have been.
--
-- V57's own file is NOT edited here: it already applied in every migrated environment
-- and Flyway checksums its content on every startup — changing its SQL text would break
-- that checksum and crash the app on any environment (including production) that already
-- ran it. This migration re-runs the same predicate with the corrected tie-break instead —
-- see com.traceability.inventory.NotTracedTagger, whose app-code query (and
-- FulfillService.getQueue()'s LATERAL) moved to the same `created_at DESC, id DESC`
-- ordering in the same change that added this migration.
--
-- One-way by design: this UPDATE only ever ADDS a missing tag (`not_traced_at IS NULL`
-- guard, identical to NotTracedTagger.maybeTagNotTraced()'s own guard) — it never clears
-- one. An order V57 already tagged (correctly or, in the opposite and much rarer direction,
-- because the wrong pick happened to also satisfy the condition) is left exactly as-is.
-- Un-tagging is out of scope — NotTracedTagger itself never reverses a tag either.
--
-- Idempotent / safe to run against fully-migrated prod data: the `not_traced_at IS NULL`
-- guard means every order this UPDATE could affect is no longer NULL after the first run,
-- so a second run (or this running after V57 already tagged everything it correctly could)
-- is a no-op that touches zero rows.
--
-- Flyway connects as postgres (BYPASSRLS, see spring.flyway.* / DataSourceConfig
-- ownerDataSource) — same as V57 — so this UPDATE correctly spans all tenants in one pass
-- with no GUC required; BYPASSRLS is the reason migrations can see every tenant's rows,
-- not a superuser grant (see V1's RLS section). No SET LOCAL app.current_tenant is needed
-- or wanted here — that would wrongly scope a cross-tenant backfill to a single tenant.

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
            -- UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
            SELECT s2.id FROM shipments s2
            WHERE s2.order_id = o.id AND s2.tenant_id = o.tenant_id
              AND s2.shipment_leg = 'forward'
            ORDER BY s2.created_at DESC, s2.id DESC LIMIT 1
        )
  );
