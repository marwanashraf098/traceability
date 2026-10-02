-- ============================================================
-- V127 — S6 phone as scanner (waybill mode): pairings + relay events + hatch #15
--
-- A worker's phone, paired to their open pack session by scanning a QR on the
-- tablet, reads barcodes with its camera and relays them to the tablet, which
-- applies them through the same scan path as its own scanner. The phone has
-- no login: a one-time pair code (in the QR, ~2 min) is exchanged for a device
-- secret (~12 h). Both are high-entropy random values; only their SHA-256
-- hashes are stored, and a hash is resolved to a pairing only through hatch #15.
-- ============================================================

-- 1. Pairings — a credential table (cf. V87): app_user can never read a hash.
CREATE TABLE scan_pairings (
    id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid        NOT NULL REFERENCES tenants(id),
    pack_session_id       uuid        NOT NULL REFERENCES pack_sessions(id),
    station_user_id       uuid        NOT NULL REFERENCES users(id),
    pair_code_hash        text        NOT NULL,
    device_secret_hash    text,
    created_at            timestamptz NOT NULL DEFAULT now(),
    pair_code_expires_at  timestamptz NOT NULL,
    claimed_at            timestamptz,
    expires_at            timestamptz NOT NULL,
    revoked_at            timestamptz,
    revoked_reason        text CHECK (revoked_reason IN
                              ('unpaired', 'replaced', 'session_ended', 'worker_switched')),
    device_label          text CHECK (char_length(device_label) <= 60),
    CHECK ((claimed_at IS NULL) = (device_secret_hash IS NULL)),
    CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);

CREATE UNIQUE INDEX scan_pairings_pair_code_hash_key ON scan_pairings (pair_code_hash);
CREATE UNIQUE INDEX scan_pairings_device_secret_hash_key ON scan_pairings (device_secret_hash)
    WHERE device_secret_hash IS NOT NULL;
-- One phone per station: at most one unrevoked pairing per pack session.
CREATE UNIQUE INDEX scan_pairings_one_active_per_session ON scan_pairings (pack_session_id)
    WHERE revoked_at IS NULL;

ALTER TABLE scan_pairings ENABLE ROW LEVEL SECURITY;
ALTER TABLE scan_pairings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON scan_pairings
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- No table-level SELECT/UPDATE/DELETE for app_user. It may INSERT a pairing (the tablet
-- creates it, with the hash), read only the non-credential columns, and update only the
-- claim / revoke columns — device_secret_hash is writable at claim but never readable.
-- A hash is matched to a row only inside resolve_scan_pairing (hatch #15) below.
REVOKE ALL ON scan_pairings FROM app_user;
GRANT INSERT ON scan_pairings TO app_user;
GRANT SELECT (id, tenant_id, pack_session_id, station_user_id, created_at, pair_code_expires_at,
              claimed_at, expires_at, revoked_at, revoked_reason, device_label)
    ON scan_pairings TO app_user;
GRANT UPDATE (claimed_at, device_secret_hash, revoked_at, revoked_reason, device_label)
    ON scan_pairings TO app_user;

COMMENT ON TABLE scan_pairings IS
    'S6 phone-as-scanner pairings (one phone per pack session). pair_code_hash / '
    'device_secret_hash are SHA-256 of high-entropy one-time secrets; app_user can never '
    'SELECT them (column grants, V127) — a hash resolves to a pairing only through '
    'resolve_scan_pairing (SECURITY DEFINER hatch #15).';

-- 2. Relay events — what the phone read, and what the tablet did with it.
CREATE TABLE scan_relay_events (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenants(id),
    pairing_id  uuid        NOT NULL REFERENCES scan_pairings(id),
    seq         bigint      NOT NULL CHECK (seq >= 0),
    code        text        NOT NULL CHECK (char_length(code) BETWEEN 1 AND 200),
    created_at  timestamptz NOT NULL DEFAULT now(),
    status      text        NOT NULL DEFAULT 'pending'
                            CHECK (status IN ('pending', 'delivered', 'accepted', 'rejected', 'expired')),
    message     text        CHECK (char_length(message) <= 200),
    outcome_at  timestamptz,
    UNIQUE (pairing_id, seq)
);

CREATE INDEX scan_relay_events_pairing_status_idx
    ON scan_relay_events (pairing_id, status, created_at);

ALTER TABLE scan_relay_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE scan_relay_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON scan_relay_events
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

REVOKE ALL ON scan_relay_events FROM app_user;
GRANT SELECT, INSERT ON scan_relay_events TO app_user;
GRANT UPDATE (status, message, outcome_at) ON scan_relay_events TO app_user;

-- 3. Hatch #15 — APPROVED (Marawan, 2026-10-02, S6).
-- The phone is pre-session: it holds only a pair code (from the QR) or, once claimed, its
-- device secret — no login, no tenant GUC. This maps the SHA-256 of that secret to
-- (tenant_id, pairing_id) and nothing else. One empty result for every invalid
-- sub-condition — unknown kind / no match / revoked / past expires_at / pack session not
-- open / (pair_code) already claimed or past pair_code_expires_at / (device_secret) not
-- claimed — no oracle. All subsequent work runs under app_user + RLS with that tenant set
-- for the request (ScanPairingService). Read-only: claiming is the caller's conditional
-- UPDATE (claimed_at IS NULL) under RLS, so a pair code can be claimed once.
-- Columns are qualified (sp / ps) — the RETURNS TABLE names are in scope in the body.
CREATE OR REPLACE FUNCTION resolve_scan_pairing(p_kind text, p_hash text)
RETURNS TABLE(tenant_id uuid, pairing_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT sp.tenant_id, sp.id
    FROM   scan_pairings sp
    JOIN   pack_sessions ps ON ps.id = sp.pack_session_id AND ps.tenant_id = sp.tenant_id
    WHERE  p_kind IN ('pair_code', 'device_secret')
      AND  p_hash IS NOT NULL
      AND  sp.revoked_at IS NULL
      AND  now() < sp.expires_at
      AND  ps.status = 'open'
      AND  (   (p_kind = 'pair_code'
                AND sp.pair_code_hash = p_hash
                AND sp.claimed_at IS NULL
                AND now() < sp.pair_code_expires_at)
            OR (p_kind = 'device_secret'
                AND sp.device_secret_hash = p_hash
                AND sp.claimed_at IS NOT NULL));
$$;

REVOKE ALL ON FUNCTION resolve_scan_pairing(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION resolve_scan_pairing(text, text) TO app_user;
