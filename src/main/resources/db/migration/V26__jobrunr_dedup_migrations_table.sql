-- Deduplicate jobrunr_migrations: keep exactly one row per script name.
--
-- Why this is needed:
--   JobRunr's DatabaseCreator.isMigrationApplied() throws IllegalStateException
--   if any script name appears more than once in jobrunr_migrations.  Duplicates
--   arise when Flyway V18 inserts the 16 tracker rows into a table that JobRunr
--   had already populated by running with skip-create=false on a prior deployment.
--   V18's ON CONFLICT (id) clause does not prevent this because each row is
--   inserted with a fresh gen_random_uuid() — conflict on id never fires.
--
-- The DELETE keeps the lexicographically smallest id per script name.
-- Order is arbitrary (both rows are equivalent tracker entries); what matters
-- is that exactly one row per script remains so isMigrationApplied() returns
-- a single-element list and does not throw.
--
-- This migration is idempotent: if jobrunr_migrations is already clean
-- (one row per script) the subquery simply returns all ids and nothing is deleted.

DELETE FROM jobrunr_migrations
WHERE id NOT IN (
    SELECT MIN(id) FROM jobrunr_migrations GROUP BY script
);
