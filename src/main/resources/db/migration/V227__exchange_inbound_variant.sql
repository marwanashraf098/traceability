-- Bosta exchange (type 30) — THE item coming back (2026-11-12, bug: Scan returns expected the replacement).
--
-- A dashboard exchange's inbound (old) item was only ever known when a person mapped it (ExchangeService.map); the
-- auto-map path left exchanges.inbound_variant_id NULL (prod: 281 of 285 dashboard rows). Scan returns then read the
-- AWB's shipment, whose order is the replacement's EXC-… order, and expected the item that went OUT.
--
-- exchange_inbound_variant(...) is the ONE resolver (STABLE, INVOKER — not a hatch; app_user reads under RLS). In order:
--   1. the stored inbound_variant_id;
--   2. the SKU in the inbound description ("… - SKU: <sku>", exactly one "SKU:" in the text): the one variant of the
--      tenant with exactly that SKU; several variants share it → the one of them on the original order, if exactly one;
--   3. only when the description names no SKU, or a SKU no variant has: the original order's (matched_order_id) lines
--      other than the outbound variant, when exactly one variant is left (quantity-0 lines count — the item coming
--      back is often a line Shopify already edited / exchanged).
--   Anything else → NULL: never guessed (Scan returns shows the description and accepts any piece of the original).
-- Used by: ExchangeInbound (ingest / match fill, Scan returns' expected pieces, parcel card, unit rows) and
-- ExchangeMatchService.resolveIfDispositionedPieceMatches — never re-derive it.

CREATE OR REPLACE FUNCTION exchange_inbound_variant(p_tenant uuid, p_inbound_variant uuid, p_description text,
                                                    p_original_order uuid, p_outbound_variant uuid)
RETURNS uuid
LANGUAGE sql STABLE PARALLEL SAFE
AS $$
    WITH sku AS (
        SELECT CASE WHEN p_description IS NOT NULL
                         AND (SELECT count(*) FROM regexp_matches(p_description, 'SKU:', 'g')) = 1
                    THEN NULLIF(btrim(substring(p_description FROM 'SKU:\s*([^\s,;|)]+)')), '')
               END AS code
    ),
    by_sku AS (
        SELECT v.id FROM variants v, sku
        WHERE sku.code IS NOT NULL AND v.tenant_id = p_tenant AND v.sku = sku.code
    ),
    by_sku_on_original AS (
        SELECT DISTINCT b.id FROM by_sku b
        JOIN order_items oi ON oi.variant_id = b.id AND oi.order_id = p_original_order AND oi.tenant_id = p_tenant
    ),
    original_lines AS (
        SELECT DISTINCT oi.variant_id AS id FROM order_items oi
        WHERE p_original_order IS NOT NULL AND oi.order_id = p_original_order AND oi.tenant_id = p_tenant
          AND oi.variant_id IS NOT NULL AND oi.variant_id IS DISTINCT FROM p_outbound_variant
    )
    SELECT COALESCE(
        p_inbound_variant,
        CASE WHEN (SELECT count(*) FROM by_sku) = 1 THEN (SELECT id FROM by_sku) END,
        CASE WHEN (SELECT count(*) FROM by_sku) > 1 AND (SELECT count(*) FROM by_sku_on_original) = 1
             THEN (SELECT id FROM by_sku_on_original) END,
        CASE WHEN (SELECT count(*) FROM by_sku) = 0 AND (SELECT count(*) FROM original_lines) = 1
             THEN (SELECT id FROM original_lines) END)
$$;

-- Backfill: idempotent, fills only NULLs, never overwrites a stored value (a person's map() pick stays).
-- updated_at is left alone (nothing about the exchange changed; it is now known).
UPDATE exchanges e
SET inbound_variant_id = exchange_inbound_variant(e.tenant_id, NULL, e.inbound_description, e.matched_order_id,
                                                  e.outbound_variant_id)
WHERE e.inbound_variant_id IS NULL
  AND exchange_inbound_variant(e.tenant_id, NULL, e.inbound_description, e.matched_order_id,
                               e.outbound_variant_id) IS NOT NULL;
