-- Main reconcile — sweep retry + repeated-failure alert (2026-11-06).
--
--   main_reconcile_runs.sweep_attempt  for a sweep run: 1 = the store's first run in that sweep, 2 = its one retry
--                                      later in the same sweep (MainReconcileJob re-runs a store whose first run
--                                      failed, once). NULL for per-variant runs and for sweep rows written before
--                                      this migration (read as a first attempt).
--
-- A sweep counts as failed for a store only when its LAST attempt failed; a store whose sweep failed twice in a row
-- raises one internal alert (MainReconcileService.consecutiveFailedSweeps). Data only: nothing here changes what a
-- run computes or writes. Written on INSERT only (app_user's table INSERT grant covers it; no UPDATE grant added).

ALTER TABLE main_reconcile_runs ADD COLUMN sweep_attempt smallint;
ALTER TABLE main_reconcile_runs ADD CONSTRAINT main_reconcile_runs_sweep_attempt
    CHECK (sweep_attempt IS NULL OR (kind = 'sweep' AND sweep_attempt IN (1, 2)));
