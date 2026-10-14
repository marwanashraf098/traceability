-- V167 — "Shopify is counting at the main warehouse" (approved 2026-10-14; replaces "has an applied
-- initial seed"). Diagnosis: every prod tenant but The Snouts was never seeded, yet its receiving
-- +1s applied from day one (connect with 0 → receive into Traced); the seed-only gate then skipped
-- every Lookup / transfer decrement (`not_seeded`) and Shopify drifted up.
--
-- 1. shopify_counting_source(tenant) — THE one definition, used by PieceShopifyRules.setup (every
--    piece decision and TransferShopifySync.claimSend), TransferShopifySync.RETURN_COUNTED_SQL and the
--    seed refusal (ShopifyInventoryReconcileService.apply). NULL = Shopify is not counting: no
--    fulfillment location, not linked, or neither an applied initial_seed nor an applied
--    receiving_session claim at that location. Otherwise whichever started the count first:
--    'seed' (The Snouts — seeded before its first applied receiving) or 'receiving'.
--    SECURITY INVOKER: under app_user it sees only the caller's tenant (RLS) — not a hatch.
-- 2. transfer_shopify_syncs.payload — what a claim records beyond its deltas: the pieces left out
--    of the −N because their arrival never reached Shopify ({excluded: [{piece_id, variant_id,
--    reason}]}) and a repair note ({repair: {...}}, scripts/ops/2026-10-14-not-seeded-repair.sql).

CREATE FUNCTION shopify_counting_source(p_tenant uuid) RETURNS text
LANGUAGE sql STABLE SECURITY INVOKER AS $$
    SELECT CASE
             WHEN c.receiving_at IS NULL AND c.seed_at IS NULL THEN NULL
             WHEN c.receiving_at IS NULL OR (c.seed_at IS NOT NULL AND c.seed_at <= c.receiving_at) THEN 'seed'
             ELSE 'receiving' END
    FROM (SELECT (SELECT MIN(a.created_at) FROM shopify_inventory_adjustments a
                   WHERE a.tenant_id = p_tenant AND a.trigger_type = 'initial_seed' AND a.status = 'applied') AS seed_at,
                 (SELECT MIN(a.created_at) FROM shopify_inventory_adjustments a
                   WHERE a.tenant_id = p_tenant AND a.trigger_type = 'receiving_session' AND a.status = 'applied'
                     AND a.location_id = l.id) AS receiving_at
          FROM locations l
          WHERE l.tenant_id = p_tenant AND l.is_fulfillment
            AND l.shopify_sync_status = 'linked' AND l.shopify_location_id IS NOT NULL) c
$$;

GRANT EXECUTE ON FUNCTION shopify_counting_source(uuid) TO app_user;

ALTER TABLE transfer_shopify_syncs ADD COLUMN payload jsonb NOT NULL DEFAULT '{}'::jsonb;
