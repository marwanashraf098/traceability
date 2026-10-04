-- ============================================================
-- V139 — Q1 phone as scanner, per station: a pairing belongs to the worker and the tablet,
-- not to a pack session (approved by Marawan 2026-10-04, "phone everywhere" diagnosis §2).
--
-- A worker pairs their phone once per shift; it keeps working across pack sessions and scan
-- screens until it is unpaired, replaced (a new pairing on the same tablet or by the same
-- worker), the worker switches or signs out, the station locks, the worker logs out, the worker
-- is deactivated, or 12 h pass. Ending a pack session no longer ends the pairing.
-- ============================================================

-- 1. The pairing's anchor moves from the pack session to the tablet (+ the worker, already there).
ALTER TABLE scan_pairings ALTER COLUMN pack_session_id DROP NOT NULL;      -- kept for history only
ALTER TABLE scan_pairings ADD COLUMN station_device_id text
    CHECK (station_device_id ~ '^[A-Za-z0-9_-]{16,64}$');                  -- random per-tablet id (localStorage), routing key, not a secret
ALTER TABLE scan_pairings ADD COLUMN active_target    text CHECK (char_length(active_target) <= 80);
ALTER TABLE scan_pairings ADD COLUMN active_target_at timestamptz;

-- 2. S6 pairings are tied to pack sessions: revoke them once; the phone pairs again.
UPDATE scan_pairings SET revoked_at = now(), revoked_reason = 'replaced' WHERE revoked_at IS NULL;
-- Undelivered scans of those pairings can never be delivered now.
UPDATE scan_relay_events SET status = 'expired', outcome_at = now() WHERE status IN ('pending', 'delivered');

-- 3. Revocation reasons (V127's inline CHECK, default name scan_pairings_revoked_reason_check,
--    body: revoked_reason IN ('unpaired','replaced','session_ended','worker_switched')).
--    'session_ended' stays valid for the historical S6 rows.
ALTER TABLE scan_pairings DROP CONSTRAINT scan_pairings_revoked_reason_check;
ALTER TABLE scan_pairings ADD CONSTRAINT scan_pairings_revoked_reason_check CHECK (revoked_reason IN
    ('unpaired','replaced','session_ended','worker_switched','station_locked','signed_out'));
-- Every live pairing is anchored to a tablet.
ALTER TABLE scan_pairings ADD CONSTRAINT scan_pairings_anchor CHECK (revoked_at IS NOT NULL OR station_device_id IS NOT NULL);

-- 4. One live pairing per tablet AND per worker (replacing one per pack session).
DROP INDEX scan_pairings_one_active_per_session;
CREATE UNIQUE INDEX scan_pairings_one_active_per_device ON scan_pairings (tenant_id, station_device_id) WHERE revoked_at IS NULL;
CREATE UNIQUE INDEX scan_pairings_one_active_per_worker ON scan_pairings (tenant_id, station_user_id)   WHERE revoked_at IS NULL;

-- 5. app_user: the new non-credential columns are readable; the phone header's target writable.
--    The hash columns stay unreadable (V127).
GRANT SELECT (station_device_id, active_target, active_target_at) ON scan_pairings TO app_user;
GRANT UPDATE (active_target, active_target_at) ON scan_pairings TO app_user;

COMMENT ON TABLE scan_pairings IS
    'Phone-as-scanner pairings, one live per tablet (station_device_id) and per worker (V139; '
    'V127 tied them to a pack session). pair_code_hash / device_secret_hash are SHA-256 of '
    'high-entropy one-time secrets; app_user can never SELECT them (column grants) — a hash '
    'resolves to a pairing only through resolve_scan_pairing (SECURITY DEFINER hatch #15).';

-- 6. Hatch #15 — revised, APPROVED (Marawan, 2026-10-04, Q1). Same signature, still SECURITY
-- DEFINER with a fixed search_path, still returns only (tenant_id, pairing_id). "Pack session
-- open" is replaced by: the pairing's worker is an ACTIVE user of the pairing's tenant.
-- Liveness otherwise rests on explicit revocation and the 12 h expires_at. One empty result for
-- every invalid sub-condition — unknown kind / no match / revoked / past expires_at / worker
-- inactive / (pair_code) already claimed or past pair_code_expires_at / (device_secret) not
-- claimed — no oracle. Columns are qualified (sp / u) — the RETURNS TABLE names are in scope.
CREATE OR REPLACE FUNCTION resolve_scan_pairing(p_kind text, p_hash text)
RETURNS TABLE(tenant_id uuid, pairing_id uuid)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT sp.tenant_id, sp.id
    FROM   scan_pairings sp
    JOIN   users u ON u.id = sp.station_user_id AND u.tenant_id = sp.tenant_id
    WHERE  p_kind IN ('pair_code', 'device_secret')
      AND  p_hash IS NOT NULL
      AND  sp.revoked_at IS NULL
      AND  now() < sp.expires_at
      AND  u.active
      AND  (   (p_kind = 'pair_code'     AND sp.pair_code_hash = p_hash     AND sp.claimed_at IS NULL
                AND now() < sp.pair_code_expires_at)
            OR (p_kind = 'device_secret' AND sp.device_secret_hash = p_hash AND sp.claimed_at IS NOT NULL));
$$;
REVOKE ALL ON FUNCTION resolve_scan_pairing(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION resolve_scan_pairing(text, text) TO app_user;
