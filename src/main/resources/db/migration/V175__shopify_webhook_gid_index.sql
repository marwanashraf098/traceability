-- V175: index for the webhook ordering check (ShopifyWebhookProcessorJob.supersededReason).
--
-- Before applying orders/create|updated or products/create|update, the processor looks for a LATER event
-- for the same order / product that was already applied:
--   WHERE tenant_id = ? AND id <> ? AND topic = ANY(?) AND payload_raw->>'admin_graphql_api_id' = ?
--     AND received_at > ? AND processed_at IS NOT NULL AND process_error IS NULL AND superseded_at IS NULL
--   ORDER BY received_at DESC LIMIT 1
-- With no index on the GID it was a Seq Scan that de-TOASTed every payload (prod 2026-10-17: ~77k rows,
-- 20 MB heap, 276 MB TOAST, 12-16 s per webhook). The key and the partial predicate match the query
-- exactly; topic and id are checked on the few rows the index returns (a GID names one order / product).
--
-- Non-transactional (V175__shopify_webhook_gid_index.sql.conf): CREATE INDEX CONCURRENTLY never blocks
-- webhook inserts or the processor's updates. If a build fails it leaves an INVALID index behind; the
-- DROP first makes a re-run (after `flyway repair`) start clean. Nothing else in this file.

DROP INDEX CONCURRENTLY IF EXISTS shopify_webhook_events_gid_applied_idx;

CREATE INDEX CONCURRENTLY shopify_webhook_events_gid_applied_idx
    ON shopify_webhook_events (tenant_id, (payload_raw->>'admin_graphql_api_id'), received_at DESC)
    WHERE processed_at IS NOT NULL AND process_error IS NULL AND superseded_at IS NULL;
