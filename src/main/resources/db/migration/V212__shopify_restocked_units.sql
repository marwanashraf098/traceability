-- V212 — S1b: restock double-count protection counts Shopify RETURN restocks too (2026-11-05).
--
-- Evidence (prod, store marwan): #1006-R1 was processed in Shopify with restock — a RESTOCKED disposition on the
-- return's reverse fulfillment order. The V151 / V201 guard (shopify_refund_restocked_units) reads only REFUND
-- restock lines, so a disposition-only restock left Traced free to send a second +1 at Main.
--
-- Step 0 (dev store marwan-nnrumxdy, 2026-11-05): a return processed with refund AND restock in one step (#1007)
-- records the ONE unit twice — a refund line restock_type RETURN and a RESTOCKED disposition — while Shopify's
-- on_hand rose once (50 → 49 → 50). A refund restock from the order page with no Return (#1008) has no disposition.
-- So the two records overlap: per order + variant (at Main) the units Shopify restocked are the GREATEST of the two,
-- never their sum.
--
-- 1. shopify_restocked_units(raw, tenant_id, order_id, variant_gid, main_location_gid) — THE definition of "units of
--    this variant Shopify restocked at the Traced Main location for this order": GREATEST(refund restocks (the V201
--    3-arg function, unchanged), RESTOCKED dispositions of the order's mirrored returns (V206)). Same location rule as
--    V201: a disposition naming another location never counts; one with no location still counts. The variant of a
--    disposition is its return line's (matched by fulfillment line). STABLE (reads the mirror), INVOKER — not a hatch;
--    the tenant is an argument, so it is correct on the owner pool (detectors) and under RLS alike.
--    Used by every consumer of the old expression: the return-inspection +1 guard, the Back to good guard, the
--    restocked_twice detector, Main reconcile (fulfilled_from_main / fulfilled_unpicked) and the new Receiving guard.
--    The 2-arg (V151) and 3-arg (V201) functions stay: the repair script and this function use them.
--
-- 2. receipt_lines: the link from a Receiving line to the return it brings back (Return To Receive / request item
--    to receive), so the Receiving +N can apply the same guard.
--      return_order_id          the order the unit came back from (composite FK, own tenant only)
--      return_source            'shipment:<id>' | 'untracked_unit:<id>' | 'request_item:<id>' — the alert it answers
--      shopify_restocked_units  units of this line NOT sent to Shopify at finalize because Shopify already restocked
--                               them (decided once, at finalize, under the per (order, variant) restock lock)
--    A receiving claim whose whole variant delta was skipped this way is recorded with the existing status
--    'skipped_shopify_restocked' (no constraint change) — PieceShopifyRules.arrival treats it as counted in Shopify.

CREATE OR REPLACE FUNCTION shopify_restocked_units(p_raw jsonb, p_tenant uuid, p_order uuid, p_variant_gid text,
                                                   p_main_location_gid text)
RETURNS bigint
LANGUAGE sql STABLE PARALLEL SAFE
AS $$
    SELECT GREATEST(
        shopify_refund_restocked_units(p_raw, p_variant_gid, p_main_location_gid),
        COALESCE((
            SELECT SUM(d.quantity)
            FROM shopify_returns r
            JOIN shopify_rfo_dispositions d ON d.tenant_id = r.tenant_id AND d.return_id = r.id
            JOIN shopify_return_lines rl    ON rl.tenant_id = d.tenant_id AND rl.return_id = d.return_id
                                           AND rl.fulfillment_line_gid = d.fulfillment_line_gid
            WHERE r.tenant_id = p_tenant AND r.order_id = p_order
              AND d.type = 'RESTOCKED' AND d.quantity > 0
              AND p_variant_gid IS NOT NULL AND rl.variant_gid = p_variant_gid
              AND (p_main_location_gid IS NULL OR d.location_gid IS NULL OR d.location_gid = p_main_location_gid)
        ), 0))
$$;

ALTER TABLE receipt_lines
    ADD COLUMN return_order_id         uuid,
    ADD COLUMN return_source           text,
    ADD COLUMN shopify_restocked_units integer NOT NULL DEFAULT 0;

ALTER TABLE receipt_lines
    ADD CONSTRAINT receipt_lines_return_order_fk
        FOREIGN KEY (return_order_id, tenant_id) REFERENCES orders (id, tenant_id),
    ADD CONSTRAINT receipt_lines_return_link_check
        CHECK ((return_order_id IS NULL) = (return_source IS NULL)
               AND (return_source IS NULL OR return_source ~ '^(shipment|untracked_unit|request_item):[0-9a-f-]{36}$')),
    ADD CONSTRAINT receipt_lines_shopify_restocked_check
        CHECK (shopify_restocked_units >= 0 AND shopify_restocked_units <= quantity
               AND (shopify_restocked_units = 0 OR return_order_id IS NOT NULL));

-- The guards count a return's earlier Receiving lines per (tenant, order, variant).
CREATE INDEX ix_receipt_lines_return_order ON receipt_lines (tenant_id, return_order_id, variant_id)
    WHERE return_order_id IS NOT NULL;
