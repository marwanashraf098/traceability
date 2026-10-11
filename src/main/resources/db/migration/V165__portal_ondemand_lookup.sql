-- ============================================================
-- V165 — Returns portal P4c: the on-demand lookup of a pre-connect order.
--
-- portal_lookup_attempts.ondemand_outcome: set on an attempt that went down the on-demand path
-- (the DB lookup found nothing, the flag is on): fetched | not_found | too_old | busy | unavailable.
-- NULL for every other attempt (today's lookups). The per-tenant and per-IP hourly caps count these
-- rows; the partial index serves the per-tenant count (the per-IP count uses V159's ip index).
--
-- No new table, no RLS change (the table's tenant_isolation policy covers the column).
-- ============================================================

ALTER TABLE portal_lookup_attempts
    ADD COLUMN ondemand_outcome text
        CHECK (ondemand_outcome IN ('fetched', 'not_found', 'too_old', 'busy', 'unavailable'));

CREATE INDEX portal_lookup_attempts_ondemand_idx
    ON portal_lookup_attempts (tenant_id, attempted_at)
    WHERE ondemand_outcome IS NOT NULL;
