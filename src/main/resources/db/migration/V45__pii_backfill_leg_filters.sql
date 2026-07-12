-- V45: one-time PII backfill for orders linked via reconcile/manualLink (which skipped
-- populateConsigneePii) and forward-leg guard for shipment queries.
--
-- No schema changes — this migration is data-only.
--
-- The backfill is:
--   • Forward-leg only (shipment_leg = 'forward') — CRP receiver is not the consignee.
--   • COALESCE keeps any value already present (idempotent / re-runnable).
--   • Skips redacted orders (pii_redacted_at IS NOT NULL).
--   • Phone normalization mirrors ShipmentLinkService.normalizePhone().
--   • Runs at migration time; operators can also re-run POST /api/v1/bosta/backfill-pii
--     (which now uses the same forward-leg filter).

UPDATE orders o
SET
  customer_name  = COALESCE(o.customer_name,
                     NULLIF(TRIM(s.raw#>>'{receiver,fullName}'), '')),
  customer_phone = COALESCE(o.customer_phone,
                     CASE
                       WHEN REGEXP_REPLACE(COALESCE(s.raw#>>'{receiver,phone}',''),'[^0-9]','','g') ~ '^0020[0-9]{10}$'
                       THEN '0' || RIGHT(REGEXP_REPLACE(s.raw#>>'{receiver,phone}','[^0-9]','','g'), 10)
                       WHEN REGEXP_REPLACE(COALESCE(s.raw#>>'{receiver,phone}',''),'[^0-9]','','g') ~ '^20[0-9]{10}$'
                       THEN '0' || RIGHT(REGEXP_REPLACE(s.raw#>>'{receiver,phone}','[^0-9]','','g'), 10)
                       WHEN REGEXP_REPLACE(COALESCE(s.raw#>>'{receiver,phone}',''),'[^0-9]','','g') ~ '^01[0-9]{9}$'
                       THEN REGEXP_REPLACE(s.raw#>>'{receiver,phone}','[^0-9]','','g')
                       WHEN REGEXP_REPLACE(COALESCE(s.raw#>>'{receiver,phone}',''),'[^0-9]','','g') ~ '^1[0-9]{9}$'
                       THEN '0' || REGEXP_REPLACE(s.raw#>>'{receiver,phone}','[^0-9]','','g')
                       ELSE NULL
                     END),
  address        = COALESCE(o.address,
                     CASE
                       WHEN NULLIF(TRIM(s.raw#>>'{dropOffAddress,firstLine}'), '') IS NOT NULL
                         OR NULLIF(TRIM(s.raw#>>'{dropOffAddress,city,name}'),   '') IS NOT NULL
                       THEN jsonb_build_object(
                         'firstLine', NULLIF(TRIM(s.raw#>>'{dropOffAddress,firstLine}'),  ''),
                         'city',      NULLIF(TRIM(s.raw#>>'{dropOffAddress,city,name}'),  ''),
                         'zone',      NULLIF(TRIM(s.raw#>>'{dropOffAddress,zone,name}'),  ''),
                         'district',  NULLIF(TRIM(s.raw#>>'{dropOffAddress,district,name}'), '')
                       )
                       ELSE NULL
                     END),
  pii_source     = COALESCE(o.pii_source, 'bosta')
FROM shipments s
WHERE s.order_id        = o.id
  AND s.tenant_id       = o.tenant_id
  AND s.shipment_leg    = 'forward'
  AND s.raw             IS NOT NULL
  AND o.pii_redacted_at IS NULL
  AND o.pii_source      IS NULL
  AND (
    NULLIF(TRIM(s.raw#>>'{receiver,fullName}'), '') IS NOT NULL
    OR s.raw#>>'{receiver,phone}' IS NOT NULL
  );
