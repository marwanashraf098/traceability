-- V133 — Bosta discovery paging (2026-10-03).
--
-- Bosta's GET /api/v0/deliveries returns at most 10 items per page whatever pageSize asks for
-- (prod: pageSize=50 → 10 items), while discovery assumed 50 and read pages 1–3 — it saw items
-- 1–10, 51–60 and 101–110 and never 11–50 or 61–100 (BROEK's 32 and Femine's burst of 79 sat in
-- those gaps). Discovery now asks for 10 per page, walks pages 1, 2, 3 … and keeps its state here:
--
--   discovery_mark_at              the newest Bosta creation time seen by the last COMPLETE run
--                                  (one that reached deliveries older than the previous mark, minus
--                                  an overlap, or the end of the list). Replaces the tracking-number
--                                  mark (discovery_high_water_tracking, V96 — kept, no longer read
--                                  except to seed the first run).
--   discovery_walk_page            a walk that hit the per-run page cap stores the next page here and
--                                  the following run continues from it (adjusted for deliveries
--                                  created meanwhile); NULL when no walk is in progress.
--   discovery_walk_newest_at       the newest creation time seen when that walk started — becomes the
--                                  mark once the walk completes.
--   discovery_short_page_logged_on the day a "page shorter than requested while more exist" WARN was
--                                  last logged for this tenant (at most once per tenant per day).

ALTER TABLE courier_accounts
    ADD COLUMN discovery_mark_at              timestamptz,
    ADD COLUMN discovery_walk_page            int,
    ADD COLUMN discovery_walk_newest_at       timestamptz,
    ADD COLUMN discovery_short_page_logged_on date;
