-- V217 — Shopify-native returns S3a: re-create Overview's partial index on the new merchant_orders predicate.
--
-- V163's orders_merchant_tenant_placed_at_idx is partial on "external_id NOT LIKE 'internal:portal:%'" so that
-- Overview's order counts over merchant_orders stay index-only (measured 12 → 963 → 10 buffers). V216 added a
-- second test (NOT LIKE 'internal:shopify-exchange:%'); the old index still applies but the planner then needs
-- the heap to check the new test. Same index, partial on BOTH tests, under the same name.
--
-- Non-transactional (V217__merchant_orders_index_shadow.sql.conf): CREATE / DROP INDEX CONCURRENTLY never block
-- order writes (V179 / V190 pattern; Flyway's lock is session-level, spring.flyway.postgresql.transactional-lock:
-- false). The new index is built under a temporary name, then the old one is dropped and the new one renamed, so
-- the old index serves queries the whole time. Re-runnable after a failure (`flyway repair`): each step is
-- IF EXISTS / starts by dropping a leftover (possibly INVALID) build.

DROP INDEX CONCURRENTLY IF EXISTS orders_merchant_tenant_placed_at_v217_idx;

CREATE INDEX CONCURRENTLY orders_merchant_tenant_placed_at_v217_idx
    ON orders (tenant_id, placed_at)
    WHERE external_id NOT LIKE 'internal:portal:%'
      AND external_id NOT LIKE 'internal:shopify-exchange:%';

DROP INDEX CONCURRENTLY IF EXISTS orders_merchant_tenant_placed_at_idx;

ALTER INDEX orders_merchant_tenant_placed_at_v217_idx RENAME TO orders_merchant_tenant_placed_at_idx;
