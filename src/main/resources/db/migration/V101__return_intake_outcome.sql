-- ============================================================
-- V101 — Return-leg intake outcome (Step 5: parcel cards)
--
-- return_intake_completed_at (V98) said WHEN a return leg's intake finished, not HOW.
-- Two outcomes now exist:
--   'scanned'            — a return session that legally scanned a piece of the order
--                          was closed (ReturnSessionService.close(); the auto-close job
--                          goes through the same path).
--   'received_untracked' — a worker marked the parcel received with NO piece scan,
--                          because Traced never tracked the order (no allocations of any
--                          status). Stock is NOT changed; the return_to_receive exception
--                          asks a manager to add the item in their next Receiving session.
--
-- return_intake_by: the user who closed / marked (NULL = system, e.g. the auto-close job).
-- return_intake_session_id: the return session it happened in. Deliberately NO foreign key:
-- return_sessions rows are deleted in FK-safe cleanup orders (tests, DemoSeeder.reseed)
-- that delete sessions before shipments — this is an audit pointer, not an ownership link.
--
-- Backfill: every already-stamped row predates 'received_untracked', so it is 'scanned'
-- (by and session stay NULL — not recorded at the time). Unstamped rows are untouched.
-- ============================================================

ALTER TABLE shipments
    ADD COLUMN return_intake_outcome    text
        CONSTRAINT shipments_return_intake_outcome_check
        CHECK (return_intake_outcome IN ('scanned', 'received_untracked')),
    ADD COLUMN return_intake_by         uuid REFERENCES users(id),
    ADD COLUMN return_intake_session_id uuid;

UPDATE shipments
SET    return_intake_outcome = 'scanned'
WHERE  return_intake_completed_at IS NOT NULL
  AND  return_intake_outcome IS NULL;
