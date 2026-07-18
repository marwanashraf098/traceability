-- V53 — AWB scan normalization: persist the physically-scanned label string as evidence.
--
-- raw_scan stores the verbatim scanner output (e.g. 'D-07-2944282510') on the
-- tracking_linked custody event row so the exact label that was presented at the
-- pack station is immutably recorded.
--
-- Nullable — only AWB scan events carry a raw_scan; all other piece transitions
-- (courier_update, return_received, etc.) leave it NULL.
-- No index — raw_scan is evidence only, never a matching key.
-- No backfill — historical events predate this column.

ALTER TABLE piece_events
    ADD COLUMN raw_scan TEXT;
