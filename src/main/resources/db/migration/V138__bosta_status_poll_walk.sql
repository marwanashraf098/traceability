-- V138 — Bosta status poll on the v2 delivery search (2026-10-04).
--
-- The status poll fetched every in-flight shipment one by one (~730 v0 requests/hour for a big
-- tenant). It now walks POST /api/v2/deliveries/search sortBy "-updatedAt" — only deliveries that
-- changed — with the same walk mechanics as discovery (V133/V134):
--
--   poll_mark_at            newest Bosta updatedAt seen by the last COMPLETE walk
--   poll_walk_page          next page of a walk that hit the page cap (NULL = none in progress)
--   poll_walk_newest_at     newest updatedAt when that walk began (the mark once it completes)
--
-- A slow per-shipment fetch remains as a safety net (bosta.poll.status-safety-net-hours).

ALTER TABLE courier_accounts
    ADD COLUMN poll_mark_at        timestamptz,
    ADD COLUMN poll_walk_page      int,
    ADD COLUMN poll_walk_newest_at timestamptz;
