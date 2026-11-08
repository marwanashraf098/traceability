-- Main reconcile — big-change brake (2026-11-08). Data only: nothing here changes what a run computes.
--
-- A write store whose confirmed differences would move more than max(brake-units, brake-percent of Shopify's on_hand
-- at the Traced Main location) in total (sum of |delta|) writes NO reconcile write that run (Part 1 subtracts are not
-- braked). The run is recorded status 'skipped', skip_reason 'large_change_held' (its counts and diffs stay as
-- computed), and the operators are emailed once per store until it is back under the limit or 24 h have passed
-- (MainReconcileBrake).
--
--   brake_units_up / brake_units_down  the confirmed units the brake weighed (the run's own confirmed differences plus,
--                                      on a per-variant run, the store's other standing confirmed differences)
--   brake_on_hand                      Shopify on_hand at Main the percentage was taken of
--   brake_limit                        max(brake-units, brake-percent of brake_on_hand)
--   brake_checked_at                   when the brake was evaluated (NULL = not evaluated: report-only, exchange hold,
--                                      store-level skip, failure, or a run before V226)
--   brake_alerted                      this run sent the operators' email
--   shopify_on_hand_total              a write-mode SWEEP's total Shopify on_hand at Main (every level it read) — the
--                                      denominator later per-variant runs use

ALTER TABLE main_reconcile_runs
    ADD COLUMN brake_units_up        integer,
    ADD COLUMN brake_units_down      integer,
    ADD COLUMN brake_on_hand         integer,
    ADD COLUMN brake_limit           integer,
    ADD COLUMN brake_checked_at      timestamptz,
    ADD COLUMN brake_alerted         boolean NOT NULL DEFAULT false,
    ADD COLUMN shopify_on_hand_total integer;

GRANT UPDATE (brake_units_up, brake_units_down, brake_on_hand, brake_limit, brake_checked_at, brake_alerted,
              shopify_on_hand_total)
    ON main_reconcile_runs TO app_user;

CREATE INDEX main_reconcile_runs_brake_idx ON main_reconcile_runs (tenant_id, brake_checked_at DESC)
    WHERE brake_checked_at IS NOT NULL;
