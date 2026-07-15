-- Two constraints that work together with LocationController's UPSERT logic.
--
-- 1. UNIQUE INDEX: one (tenant, name) pair per tenant (case-insensitive, trimmed).
--    LocationController's INSERT ... ON CONFLICT (tenant_id, lower(trim(name))) DO UPDATE
--    depends on this index existing. Without it the ON CONFLICT clause fails at parse time.
--
--    WARNING: this index creation will fail if any tenant currently has two locations with
--    the same name. Run the orphan cleanup query first:
--      DELETE FROM locations
--      WHERE shopify_sync_status IN ('error', 'unsynced')
--        AND id NOT IN (
--            SELECT DISTINCT ON (tenant_id, lower(trim(name))) id
--            FROM locations
--            ORDER BY tenant_id, lower(trim(name)), shopify_sync_status DESC
--        );
--
-- 2. CHECK CONSTRAINT: name must be >= 3 characters after trimming whitespace.
--    NOT VALID skips validation of existing rows (short orphan names can be cleaned up
--    separately); all future inserts and updates are validated immediately.
CREATE UNIQUE INDEX locations_name_unique
    ON locations (tenant_id, lower(trim(name)));

ALTER TABLE locations ADD CONSTRAINT locations_name_length
    CHECK (length(trim(name)) >= 3) NOT VALID;
