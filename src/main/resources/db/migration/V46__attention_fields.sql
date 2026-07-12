-- V46: Per-shipment attention fields derived from Bosta attempts[] array.
-- Supersedes number_of_attempts (was Bosta's unreliable flat counter)
-- to now store jsonb_array_length(attempts) — total array-derived count.
-- All new columns are co-located with raw and updated at every raw-write site.

ALTER TABLE shipments
    ADD COLUMN IF NOT EXISTS failed_delivery_attempts INT         NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_attempt_at          TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_failure_reason      TEXT,
    ADD COLUMN IF NOT EXISTS is_delayed               BOOLEAN,
    ADD COLUMN IF NOT EXISTS sla_breached             BOOLEAN,
    ADD COLUMN IF NOT EXISTS scheduled_at             TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS courier_name             TEXT,
    ADD COLUMN IF NOT EXISTS courier_phone            TEXT;

-- Step 1: supersede number_of_attempts → reliable array-length count.
UPDATE shipments
SET    number_of_attempts = jsonb_array_length(raw->'attempts')
WHERE  raw IS NOT NULL
  AND  raw->'attempts' IS NOT NULL
  AND  jsonb_typeof(raw->'attempts') = 'array';

-- Step 2: backfill all new columns from existing raw.
UPDATE shipments
SET
    failed_delivery_attempts = CASE
        WHEN raw->'attempts' IS NOT NULL AND jsonb_typeof(raw->'attempts') = 'array'
        THEN (
            SELECT COUNT(*)::int
            FROM   jsonb_array_elements(raw->'attempts') AS a
            WHERE  (a->>'type') = 'delivery'
              AND  (a->>'succeededAt') IS NULL
              AND  COALESCE(
                       CASE WHEN a->>'state' ~ '^\d+$' THEN (a->>'state')::int END,
                       -1) != 3
        )
        ELSE 0
    END,

    last_attempt_at = CASE
        WHEN raw->'attempts' IS NOT NULL AND jsonb_typeof(raw->'attempts') = 'array'
        THEN (
            SELECT MAX(
                CASE WHEN a->>'attemptDate' ~ '^\d{4}-\d{2}-\d{2}T'
                     THEN (a->>'attemptDate')::timestamptz
                     ELSE NULL
                END
            )
            FROM jsonb_array_elements(raw->'attempts') AS a
        )
        ELSE NULL
    END,

    last_failure_reason = CASE
        WHEN raw->'attempts' IS NOT NULL AND jsonb_typeof(raw->'attempts') = 'array'
        THEN (
            SELECT sub.reason
            FROM (
                SELECT a->'exception'->>'reason' AS reason,
                       CASE WHEN a->>'attemptDate' ~ '^\d{4}-\d{2}-\d{2}T'
                            THEN (a->>'attemptDate')::timestamptz
                            ELSE NULL::timestamptz END AS ts
                FROM   jsonb_array_elements(raw->'attempts') AS a
                WHERE  (a->>'type') = 'delivery'
                  AND  (a->>'succeededAt') IS NULL
                  AND  COALESCE(
                           CASE WHEN a->>'state' ~ '^\d+$' THEN (a->>'state')::int END,
                           -1) != 3
                  AND  a->'exception'->>'reason' IS NOT NULL
                ORDER BY ts DESC NULLS LAST
                LIMIT  1
            ) sub
        )
        ELSE NULL
    END,

    is_delayed = (raw->>'isDelayed')::boolean,

    sla_breached = CASE
        WHEN raw->'sla' IS NULL OR jsonb_typeof(raw->'sla') = 'null'
        THEN NULL
        ELSE
            COALESCE((raw->'sla'->'orderSla'->>'isExceededOrderSla')::boolean, false)
            OR
            COALESCE((raw->'sla'->'e2eSla'->>'isExceededE2ESla')::boolean, false)
    END,

    scheduled_at = CASE
        WHEN raw->>'scheduledAt' IS NOT NULL
         AND raw->>'scheduledAt' ~ '^\d{4}-\d{2}-\d{2}T'
        THEN (raw->>'scheduledAt')::timestamptz
        ELSE NULL
    END,

    courier_name  = NULLIF(TRIM(raw->'star'->>'name'),  ''),
    courier_phone = NULLIF(TRIM(raw->'star'->>'phone'), '')

WHERE raw IS NOT NULL;
