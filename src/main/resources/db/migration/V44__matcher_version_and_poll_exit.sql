-- FR-13: version-stamp columns — Guard-3-safe retry after matching-logic deploys
-- FR-14: provider_not_found_at — break infinite Bosta-400 poll loop

-- FR-13 ------------------------------------------------------------------

ALTER TABLE webhook_events
    ADD COLUMN matcher_version TEXT;

ALTER TABLE unlinked_bosta_deliveries
    ADD COLUMN matcher_version TEXT;

-- FR-14 ------------------------------------------------------------------

ALTER TABLE shipments
    ADD COLUMN provider_not_found_at TIMESTAMP;

-- FR-13 sentinel (retry-storm guard) -------------------------------------
--
-- After V44, each unlinked row carries the matcher_version that produced it.
-- Guard 3 in BostaIngestionHelper blocks re-enqueue when the stored version
-- equals the current version (no new logic to apply). When a deploy bumps
-- the version, Guard 3 passes → exactly one retry fires.
--
-- Legacy rows (matcher_version IS NULL) are treated as "old version" by the
-- Guard-3 predicate (NULL = '44' → NULL → not matched → Guard 3 passes).
-- This means a V44 deploy would retry ALL legacy unlinked rows in the first
-- poll cycle. If that count is large (> 20) it would hammer Bosta's API.
--
-- The DO block below checks the count at migration time. If > 20, it stamps
-- existing rows with '44' so Guard 3 blocks them (same version = no retry).
-- To unblock a specific delivery after deploy, clear its sentinel:
--   UPDATE unlinked_bosta_deliveries SET matcher_version = NULL
--   WHERE tracking_number = '<tn>' AND tenant_id = '<tid>';
-- Then wait for the next poll cycle — Guard 3 passes (NULL <> '44') → retry.
--
-- '44' is hardcoded here to match MatcherVersionHolder.get() post-deploy.
-- MatcherVersionHolder reads flyway.info().current().getVersion().getVersion()
-- which returns "44" after this migration completes.

DO $sentinel$
DECLARE
    unlinked_count INT;
BEGIN
    SELECT COUNT(*) INTO unlinked_count
    FROM unlinked_bosta_deliveries WHERE resolved = false;

    IF unlinked_count > 20 THEN
        UPDATE unlinked_bosta_deliveries
        SET matcher_version = '44'
        WHERE resolved = false AND matcher_version IS NULL;
        RAISE NOTICE 'V44 sentinel: stamped % unresolved unlinked rows with matcher_version=44 — retry storm prevention active. Clear individual rows to unblock.', unlinked_count;
    ELSE
        RAISE NOTICE 'V44 sentinel: % unresolved unlinked rows — no sentinel needed (≤20); all will retry on first post-V44 poll cycle.', unlinked_count;
    END IF;
END $sentinel$;
