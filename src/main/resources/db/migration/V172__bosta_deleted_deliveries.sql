-- ============================================================
-- V172 — Deleted-in-Bosta slices 4 + 5: confirm a deletion safely, close the dead shipment.
--
-- shipments (Bosta rows only — a generic leg never gets any of these):
--   provider_not_found_at  (existing, V44) now means SUSPECTED: the first v0 lookup that answered
--                          400 "Delivery not found". Cleared again if a later lookup finds it.
--   last_not_found_at      the latest such answer; not_found_count how many since the suspicion.
--                          `timestamp` WITHOUT time zone on purpose, the same type as V44's
--                          provider_not_found_at: both are written by the app's sessions (JVM
--                          -Duser.timezone=Africa/Cairo → Cairo wall-clock time), so the 30-minute spacing
--                          (last − first) compares like with like. Converting V44's column would rewrite
--                          the whole shipments table under an exclusive lock — not worth it for this.
--   provider_deleted_at    CONFIRMED deleted in Bosta (BostaDeletionService: two not-founds ≥ 30 min
--                          apart, the tenant's key proven working in the last 30 min, still 'created')
--                          or set by the 2026-10-19 backfill script. A closed leg is
--                          internal_state 'cancelled' with NO shipment_status_history row; a leg kept
--                          open (pieces with the courier / an open return request) keeps its state.
--   provider_deleted_reason 'deleted_in_bosta' | 'deleted_in_bosta_backfill'.
--
-- courier_accounts (the positive control — a wrong or rotated key must never confirm anything):
--   lookup_ok_at            the tenant's key last got a successful v0 lookup or v2 search page.
--   lookup_auth_failed_at   the key last got a 401 / 403 — pauses confirmation for 30 minutes.
--
-- No new table, no new RLS policy: both tables keep theirs (NULLIF pattern, FORCE). app_user already
-- has UPDATE on both. Mode B unchanged — detection is read-only; Traced never deletes, edits,
-- terminates or re-books anything in Bosta.
-- ============================================================

ALTER TABLE shipments
    ADD COLUMN provider_deleted_at     timestamptz,
    ADD COLUMN provider_deleted_reason text,
    ADD COLUMN not_found_count         integer NOT NULL DEFAULT 0,
    ADD COLUMN last_not_found_at       timestamp;   -- NO time zone, like provider_not_found_at (V44): see below

ALTER TABLE shipments
    ADD CONSTRAINT shipments_provider_deleted_reason_chk
        CHECK (provider_deleted_reason IN ('deleted_in_bosta', 'deleted_in_bosta_backfill')),
    ADD CONSTRAINT shipments_provider_deleted_pair_chk
        CHECK ((provider_deleted_at IS NULL) = (provider_deleted_reason IS NULL)),
    ADD CONSTRAINT shipments_provider_deleted_bosta_only_chk
        CHECK (provider_deleted_at IS NULL OR provider = 'bosta'),
    ADD CONSTRAINT shipments_not_found_count_chk
        CHECK (not_found_count >= 0);

-- The suspect re-check queue (BostaDeletionService.recheckSuspects): a tenant's suspected, unconfirmed
-- Bosta legs, oldest observation first.
CREATE INDEX shipments_bosta_suspect_idx
    ON shipments (tenant_id, last_not_found_at)
    WHERE provider_not_found_at IS NOT NULL AND provider_deleted_at IS NULL;

ALTER TABLE courier_accounts
    ADD COLUMN lookup_ok_at          timestamptz,
    ADD COLUMN lookup_auth_failed_at timestamptz;

-- Legs the status poll already stamped (one 400 each) are the first observation of a suspicion: the
-- re-check queue's next 400, ≥ 30 min later, is the second. Nothing is confirmed here — the backfill
-- script (scripts/ops/2026-10-19-deleted-in-bosta-backfill.sql) is the separate, guarded, manual step.
UPDATE shipments
   SET not_found_count = 1, last_not_found_at = provider_not_found_at
 WHERE provider_not_found_at IS NOT NULL AND provider = 'bosta';
