-- ============================================================
-- V172 — Deleted-in-Bosta slices 4 + 5: confirm a deletion safely, close the dead shipment.
-- (V171 is G2's — merged to main first; this is the next free number.)
--
-- shipments (Bosta rows only — a generic leg never gets any of these):
--   provider_not_found_at  (existing, V44) now means SUSPECTED: the first v0 lookup that answered
--                          400 "Delivery not found". Cleared again if a later lookup finds it.
--   not_found_count        COUNTED fresh 400s since the suspicion: the first one, then only one at least
--                          30 minutes after the previous counted one (BostaDeletionService.observeNotFound).
--                          Rows stamped before V172 start at 0 — their old stamp never counts as one of the
--                          two 400s a confirmation needs.
--   last_not_found_at      when the last COUNTED 400 was seen. `timestamp` WITHOUT time zone on purpose,
--                          like V44's provider_not_found_at: both are written by the app's sessions (JVM
--                          -Duser.timezone=Africa/Cairo → Cairo wall-clock time, LOCALTIMESTAMP).
--   provider_deleted_at    CONFIRMED deleted in Bosta (BostaDeletionService: two counted 400s, the tenant's
--                          key proven working in the last 30 min, still 'created') or set by the backfill
--                          script. A closed leg is internal_state 'cancelled' with NO shipment_status_history
--                          row; a leg kept open (pieces with the courier / an open return request) keeps its
--                          state.
--   provider_deleted_reason 'deleted_in_bosta' | 'deleted_in_bosta_backfill'.
--
-- courier_accounts (the positive control — a wrong or rotated key must never confirm anything):
--   lookup_ok_at            the tenant's key last got a successful v0 lookup or v2 search page.
--   lookup_auth_failed_at   the key last got a 401 / 403 — pauses confirmation for 30 minutes.
--
-- Lock safety (prod): only ADD COLUMN — nullable, or NOT NULL with a constant DEFAULT (metadata-only since
-- PG 11; no table rewrite) — and CHECK constraints added NOT VALID (no scan of existing rows; every existing
-- row trivially satisfies them, the columns are new; enforced on every write from now on). No index build,
-- no data UPDATE. Each ALTER still takes a brief ACCESS EXCLUSIVE lock (no other migration here sets
-- lock_timeout). No new table, no new RLS policy: both tables keep theirs; app_user already has UPDATE on
-- both. Mode B unchanged — detection is read-only towards Bosta.
-- ============================================================

ALTER TABLE shipments
    ADD COLUMN provider_deleted_at     timestamptz,
    ADD COLUMN provider_deleted_reason text,
    ADD COLUMN not_found_count         integer NOT NULL DEFAULT 0,
    ADD COLUMN last_not_found_at       timestamp,
    ADD CONSTRAINT shipments_provider_deleted_reason_chk
        CHECK (provider_deleted_reason IN ('deleted_in_bosta', 'deleted_in_bosta_backfill')) NOT VALID,
    ADD CONSTRAINT shipments_provider_deleted_pair_chk
        CHECK ((provider_deleted_at IS NULL) = (provider_deleted_reason IS NULL)) NOT VALID,
    ADD CONSTRAINT shipments_provider_deleted_bosta_only_chk
        CHECK (provider_deleted_at IS NULL OR provider = 'bosta') NOT VALID,
    ADD CONSTRAINT shipments_not_found_count_chk
        CHECK (not_found_count >= 0) NOT VALID;

ALTER TABLE courier_accounts
    ADD COLUMN lookup_ok_at          timestamptz,
    ADD COLUMN lookup_auth_failed_at timestamptz;
