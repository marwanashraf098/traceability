-- ============================================================
-- V47 — FR-16 Phase 1: Pickup session scan model
--
-- Three changes:
--   1. pickups: rename status→session_status; add session columns
--   2. pickup_shipments: add scan attribution columns
--   3. shipments: add custody_locked_by_scan guard flag
-- ============================================================

-- ── 1. pickups ────────────────────────────────────────────────────────────────

-- Rename generic 'status' to the more precise 'session_status'.
-- Existing rows (legacy BostaPickupService) move their 'pending' value forward.
ALTER TABLE pickups RENAME COLUMN status TO session_status;

ALTER TABLE pickups
    ADD COLUMN scheduled_time_slot  text,
    ADD COLUMN business_location_id text,
    ADD COLUMN contact_person        jsonb,
    ADD COLUMN notes                 text,
    ADD COLUMN no_of_packages        integer,
    ADD COLUMN opened_by_user_id     uuid REFERENCES users(id),
    ADD COLUMN closed_by_user_id     uuid REFERENCES users(id),
    ADD COLUMN closed_at             timestamptz,
    ADD COLUMN submitted_at          timestamptz,
    ADD COLUMN bosta_error           text;

-- ── 2. pickup_shipments ───────────────────────────────────────────────────────

ALTER TABLE pickup_shipments
    ADD COLUMN scanned_at          timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN scanned_by_user_id  uuid REFERENCES users(id);

-- Fast lookup of all scans for a session (detail + manifest queries).
CREATE INDEX pickup_shipments_pickup_idx ON pickup_shipments (pickup_id);

-- ── 3. shipments — custody guard flag ────────────────────────────────────────
--
-- Set TRUE at session close when Traced physically confirms handover via scan.
-- Prevents BostaWebhookJob from demoting internal_state back to 'created'
-- (Bosta pre-transit codes 10/11/20) while the package is already in the
-- courier's van. Cleared when Bosta independently reports with_courier or
-- a genuine downstream state (returning/delivered/returned).
ALTER TABLE shipments
    ADD COLUMN custody_locked_by_scan boolean NOT NULL DEFAULT false;
