-- V134 — Bosta discovery on the v2 delivery search (2026-10-03).
--
-- Discovery stops paging GET /api/v0/deliveries (10 items per page whatever pageSize asked for) and
-- pages POST /api/v2/deliveries/search instead (sortBy "-createdAt", 50 per page — contract proven
-- in prod by BostaSearchProbe, BROEK + Femine). A v0 walk position means nothing on the v2 list, so
-- any interrupted V133 walk is dropped here: the first v2 run starts at page 1 and walks back to the
-- tenant's existing discovery_mark_at, which is kept — it is a creation time, valid on either list.
--
-- discovery_short_page_logged_on (V133) is no longer read or written (the short-page warning was a
-- v0 paging check); the column is left in place.

UPDATE courier_accounts
SET discovery_walk_page      = NULL,
    discovery_walk_newest_at = NULL
WHERE provider = 'bosta'
  AND (discovery_walk_page IS NOT NULL OR discovery_walk_newest_at IS NOT NULL);
