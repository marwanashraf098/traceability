-- Deleted-in-Bosta workstream, slice 1 — READ-ONLY dry run (writes nothing, no -v commit mode).
--
-- Lists open missing_awb alerts that came from INNOCENT waybills of a rejected mass-awb chunk:
-- before slice 1, one deleted waybill made Bosta reject its whole chunk (≤ 49) and every shipment in
-- it got awb_print_failed_reason 'BOSTA_REJECTED:…' (→ one missing_awb alert each).
--
-- "Same rejected chunk" = same tenant, same reason text, awb_print_failed_at within 10 seconds of the
-- first row of the group (markFailed stamps them one after another, milliseconds apart). A group is a
-- suspect chunk when at least one of its shipments now has provider_not_found_at set (the culprit);
-- the innocent rows are the others. "Open" = the missing_awb detector would still show it (no
-- resolution newer than the failure).
--
-- psql "<conn>" -v ON_ERROR_STOP=1 -f scripts/ops/2026-10-18-stale-missing-awb-dryrun.sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10s';

WITH failed AS (
    SELECT s.id, s.tenant_id, s.tracking_number, s.order_id, s.internal_state,
           s.awb_print_failed_reason AS reason, s.awb_print_failed_at AS failed_at,
           s.provider_not_found_at IS NOT NULL AS not_found
    FROM shipments s
    WHERE s.awb_print_failed_reason LIKE 'BOSTA_REJECTED:%'
      AND s.awb_print_failed_at IS NOT NULL
),
lagged AS (
    SELECT f.*, LAG(f.failed_at) OVER (PARTITION BY f.tenant_id, f.reason ORDER BY f.failed_at, f.id) AS prev_at
    FROM failed f
),
grouped AS (
    -- a new group starts when the previous failure of this tenant + reason is > 10 s earlier
    SELECT l.*, SUM(CASE WHEN l.prev_at IS NULL OR l.failed_at - l.prev_at > interval '10 seconds'
                         THEN 1 ELSE 0 END)
                OVER (PARTITION BY l.tenant_id, l.reason ORDER BY l.failed_at, l.id) AS grp
    FROM lagged l
),
chunks AS (
    SELECT tenant_id, reason, grp,
           COUNT(*) AS chunk_size,
           COUNT(*) FILTER (WHERE not_found) AS culprits,
           string_agg(tracking_number, ',' ORDER BY tracking_number) FILTER (WHERE not_found) AS culprit_awbs
    FROM grouped
    GROUP BY tenant_id, reason, grp
)
SELECT t.name AS tenant, g.failed_at, c.chunk_size, c.culprit_awbs,
       g.tracking_number AS innocent_awb, o.number AS order_number, o.status AS order_status,
       g.internal_state, left(g.reason, 90) AS reason,
       'missing_awb:shipment:' || g.id AS subject_key
FROM grouped g
JOIN chunks c ON c.tenant_id = g.tenant_id AND c.reason = g.reason AND c.grp = g.grp
JOIN tenants t ON t.id = g.tenant_id
LEFT JOIN orders o ON o.id = g.order_id
WHERE c.culprits > 0
  AND NOT g.not_found
  AND NOT EXISTS (SELECT 1 FROM exception_resolutions er
                  WHERE er.tenant_id = g.tenant_id AND er.exception_type = 'missing_awb'
                    AND er.subject_key = 'missing_awb:shipment:' || g.id
                    AND er.resolved_at > g.failed_at)
ORDER BY t.name, g.failed_at, g.tracking_number;

-- Context: every BOSTA_REJECTED group (with or without a culprit), to see what the rule above leaves out.
WITH failed AS (
    SELECT s.tenant_id, s.awb_print_failed_reason AS reason, s.awb_print_failed_at AS failed_at, s.id,
           s.provider_not_found_at IS NOT NULL AS not_found
    FROM shipments s
    WHERE s.awb_print_failed_reason LIKE 'BOSTA_REJECTED:%' AND s.awb_print_failed_at IS NOT NULL
),
lagged AS (
    SELECT f.*, LAG(f.failed_at) OVER (PARTITION BY f.tenant_id, f.reason ORDER BY f.failed_at, f.id) AS prev_at
    FROM failed f
),
grouped AS (
    SELECT l.*, SUM(CASE WHEN l.prev_at IS NULL OR l.failed_at - l.prev_at > interval '10 seconds'
                         THEN 1 ELSE 0 END)
                OVER (PARTITION BY l.tenant_id, l.reason ORDER BY l.failed_at, l.id) AS grp
    FROM lagged l
)
SELECT t.name AS tenant, MIN(g.failed_at) AS failed_at, COUNT(*) AS chunk_size,
       COUNT(*) FILTER (WHERE g.not_found) AS now_not_found, left(g.reason, 110) AS reason
FROM grouped g JOIN tenants t ON t.id = g.tenant_id
GROUP BY t.name, g.tenant_id, g.reason, g.grp
ORDER BY MIN(g.failed_at) DESC;

ROLLBACK;
