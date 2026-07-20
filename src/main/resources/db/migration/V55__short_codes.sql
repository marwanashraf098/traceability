-- FR-19: Short per-piece codes for scannable thermal labels.
--
-- Problem: 26-char ULID barcodes render at ~0.129mm/module on a 44mm label.
-- Passes ZXing on screen; ink bleed closes gaps on paper at 203 DPI.
-- Fix: 7-char "P" + 6-digit sequential code → 132 modules → 0.333mm/module
-- (1.75× GS1 general-use minimum of 0.191mm).

-- 1. Counter table — one row per tenant, tracks highest short_code issued.
--    ON CONFLICT DO UPDATE in batchReceive atomically increments by N and
--    RETURNING last_value gives the end of the batch range.
CREATE TABLE piece_counters (
    tenant_id  UUID    NOT NULL PRIMARY KEY REFERENCES tenants(id),
    last_value BIGINT  NOT NULL DEFAULT 0
);
ALTER TABLE piece_counters ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON piece_counters
    USING      (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON piece_counters TO app_user;

-- 2. Short-code column (nullable first so existing rows don't fail NOT NULL).
ALTER TABLE pieces ADD COLUMN short_code TEXT;

-- 3. Backfill existing pieces: sequential per tenant, ordered by receipt time.
--    ROW_NUMBER window handles all tenants in one pass with no row-by-row loop.
WITH ranked AS (
    SELECT id,
           'P' || lpad(
               ROW_NUMBER() OVER (PARTITION BY tenant_id ORDER BY created_at, id)::text,
               6, '0') AS sc
    FROM pieces
)
UPDATE pieces p
SET short_code = r.sc
FROM ranked r
WHERE p.id = r.id;

-- 4. Seed counters from backfilled pieces so new pieces continue after existing ones.
INSERT INTO piece_counters (tenant_id, last_value)
SELECT tenant_id, COUNT(*)::bigint
FROM pieces
GROUP BY tenant_id;

-- 5. Lock in NOT NULL and per-tenant uniqueness after backfill.
ALTER TABLE pieces ALTER COLUMN short_code SET NOT NULL;
ALTER TABLE pieces ADD CONSTRAINT pieces_short_code_tenant_unique
    UNIQUE (tenant_id, short_code);
