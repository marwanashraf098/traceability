-- ============================================================
-- V99 — Correct order addresses polluted by a CRP's merchant dropOffAddress
--
-- Before the Step 2 fix, ShipmentLinkService.populateConsigneePiiFromRaw() read a CRP
-- (Bosta type 25) delivery's dropOffAddress into orders.address (fill-only-if-null).
-- On a CRP, dropOffAddress is the MERCHANT's location — the customer is pickupAddress.
-- Production evidence (2026-09-23): 2 Jumi orders carry the merchant address.
--
-- Target rows — exactly the Step 2 detection predicate, plus pii_source='bosta':
--   order has a return leg with raw.type.code=25, and the order's address firstLine AND
--   city (trimmed) equal that CRP's dropOffAddress firstLine AND city.name (trimmed).
--   pii_redacted_at IS NULL — redacted orders are never touched.
--
-- New address source:
--   the order's forward leg dropOffAddress if a forward leg with raw exists (newest by
--   created_at, id tiebreak); otherwise the CRP's pickupAddress (the customer).
--
-- Shape: exactly what populateConsigneePiiFromRaw() writes today — keys firstLine / city /
-- zone / district, only keys whose raw value is present (jsonb_strip_nulls), no trimming,
-- "" kept as "". If none of the four is present the address becomes NULL — the app would
-- never have written the merchant address, and with no customer address it writes nothing.
-- NOTE: V45 / BostaController's PII backfill use a DIFFERENT shape (all four keys, JSON
-- null for missing, trimmed) — both shapes exist; no reader distinguishes them (see
-- PROGRESS.md follow-up). This migration does not normalize existing rows.
--
-- Address only: customer_name, customer_phone, pii_source, pii_redacted_at untouched.
-- Idempotent: a corrected row's address no longer matches the CRP's dropOffAddress (or is
-- NULL), so re-running changes nothing.
-- ============================================================

UPDATE orders o
SET    address = fix.new_address
FROM (
    SELECT DISTINCT ON (o2.id)
           o2.id AS order_id,
           CASE
             WHEN src.addr IS NULL THEN NULL
             WHEN src.addr->>'firstLine'      IS NULL
              AND src.addr#>>'{city,name}'     IS NULL
              AND src.addr#>>'{zone,name}'     IS NULL
              AND src.addr#>>'{district,name}' IS NULL THEN NULL
             ELSE jsonb_strip_nulls(jsonb_build_object(
                    'firstLine', src.addr->>'firstLine',
                    'city',      src.addr#>>'{city,name}',
                    'zone',      src.addr#>>'{zone,name}',
                    'district',  src.addr#>>'{district,name}'))
           END AS new_address
    FROM   orders o2
    JOIN   shipments c
           ON  c.order_id     = o2.id
           AND c.tenant_id    = o2.tenant_id
           AND c.shipment_leg = 'return'
           AND c.raw->'type'->>'code' = '25'
    LEFT JOIN LATERAL (
           SELECT f.raw
           FROM   shipments f
           WHERE  f.order_id     = o2.id
             AND  f.tenant_id    = o2.tenant_id
             AND  f.shipment_leg = 'forward'
             AND  f.raw IS NOT NULL
           ORDER  BY f.created_at DESC, f.id DESC
           LIMIT  1
    ) fwd ON true
    CROSS JOIN LATERAL (
           SELECT CASE WHEN fwd.raw IS NOT NULL
                       THEN fwd.raw->'dropOffAddress'
                       ELSE c.raw->'pickupAddress' END AS addr
    ) src
    WHERE  o2.address IS NOT NULL
      AND  o2.pii_source = 'bosta'
      AND  o2.pii_redacted_at IS NULL
      AND  TRIM(o2.address->>'firstLine') IS NOT DISTINCT FROM NULLIF(TRIM(c.raw#>>'{dropOffAddress,firstLine}'), '')
      AND  TRIM(o2.address->>'city')      IS NOT DISTINCT FROM NULLIF(TRIM(c.raw#>>'{dropOffAddress,city,name}'), '')
    ORDER  BY o2.id, c.created_at DESC, c.id DESC
) fix
WHERE  o.id = fix.order_id;
