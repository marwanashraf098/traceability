-- V139 — the order's shipping carrier (2026-10-05).
--
-- Femine ships ~25% with Wijha (Shopify fulfillment tracking_company "Wijha"); those orders were treated
-- as awaiting Bosta: the reconcile job flagged them 'not_created' (red "Shipment not created") and the
-- funnel counted them as New. The order now carries its carrier:
--
--   shipping_carrier_class   'bosta' | 'other_known' | 'unknown' | NULL (no fulfillment, no shipment)
--   shipping_carrier_name    the carrier as Shopify / Traced names it ("Bosta", "Wijha", "Other")
--
-- Derived in ONE place, shipping_carrier_of(order) (plain SQL, the caller's rights and RLS — not a
-- definer hatch), from: the order's tracked fulfillments (order_fulfillment_tracking, V129), every
-- fulfillment in orders.raw (also those without a tracking number, and orders before V129), and its live
-- Bosta forward shipment. Cancelled fulfillments are ignored. Bosta wins over any other carrier, a named
-- carrier over 'unknown'. Jumi's "Other" is 'unknown' — Bosta-eligible. The named non-Bosta carriers here
-- must match FulfillmentTrackingCapture.OTHER_KNOWN_CARRIERS.
-- Recomputed by FulfillmentTrackingCapture on every orders/updated and when a shipment is linked.

ALTER TABLE orders
    ADD COLUMN shipping_carrier_class text
        CHECK (shipping_carrier_class IN ('bosta', 'other_known', 'unknown')),
    ADD COLUMN shipping_carrier_name  text;

CREATE FUNCTION shipping_carrier_of(p_order uuid)
RETURNS TABLE (cls text, name text)
LANGUAGE sql STABLE AS $$
    WITH f AS (
        SELECT t.carrier_class AS cls, nullif(trim(t.carrier_raw), '') AS name
        FROM order_fulfillment_tracking t
        WHERE t.order_id = p_order AND lower(coalesce(t.fulfillment_status, '')) <> 'cancelled'
        UNION ALL
        SELECT CASE
                   WHEN lower(coalesce(x->>'tracking_company', '')) LIKE '%bosta%'
                     OR lower(coalesce(x->>'tracking_url', x->'tracking_urls'->>0, '')) LIKE '%bosta%' THEN 'bosta'
                   WHEN lower(trim(coalesce(x->>'tracking_company', ''))) IN ('wijha') THEN 'other_known'
                   ELSE 'unknown'
               END,
               nullif(trim(x->>'tracking_company'), '')
        FROM orders o,
             jsonb_array_elements(CASE WHEN jsonb_typeof(o.raw->'fulfillments') = 'array'
                                       THEN o.raw->'fulfillments' ELSE '[]'::jsonb END) x
        WHERE o.id = p_order AND lower(coalesce(x->>'status', '')) <> 'cancelled'
        UNION ALL
        SELECT 'bosta', 'Bosta'
        FROM shipments s
        WHERE s.order_id = p_order AND s.shipment_leg = 'forward' AND s.provider = 'bosta'
          AND s.internal_state NOT IN ('cancelled', 'terminated')
    )
    SELECT cls, name FROM f
    ORDER BY CASE cls WHEN 'bosta' THEN 0 WHEN 'other_known' THEN 1 ELSE 2 END, name NULLS LAST
    LIMIT 1
$$;

-- Backfill every order that has a fulfillment or a shipment.
UPDATE orders o
SET (shipping_carrier_class, shipping_carrier_name) = (SELECT c.cls, c.name FROM shipping_carrier_of(o.id) c)
WHERE jsonb_typeof(o.raw->'fulfillments') = 'array'
   OR EXISTS (SELECT 1 FROM order_fulfillment_tracking t WHERE t.order_id = o.id)
   OR EXISTS (SELECT 1 FROM shipments s WHERE s.order_id = o.id);
