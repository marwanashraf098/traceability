-- Bosta key health (2026-11-15) — a tenant whose Bosta API key Bosta refuses (401) stops costing Bosta calls.
--
-- Prod: one tenant's revoked key made ~1,500 failing webhook verify-by-fetch calls a day, plus every discovery and
-- status-poll cycle, all through the shared 0.75 req/s budget. BostaKeyHealth is the ONE writer of these columns and
-- THE rule for "known bad":
--   key_auth_fail_count / _first_at   consecutive 401s since the last success, for the key whose hash is in
--   key_auth_fail_key_hash            (sha256 of the stored ciphertext — never the key itself);
--   key_auth_bad_at                   set once the count reached bosta.key-health.bad-after-failures (3) over at
--                                     least bosta.key-health.bad-after-minutes (10); cleared by any success;
--   key_auth_probe_at                 the status poll's last probe of a known-bad key (one walk page every
--                                     bosta.key-health.probe-hours, 6) — a key Bosta accepts again resumes by itself.
-- Known bad = key_auth_bad_at IS NOT NULL AND key_auth_fail_key_hash = sha256(api_key_encrypted): saving a new key
-- (a new ciphertext) resumes everything at once, with no other code involved.
--
-- webhook_status 'skipped': a Bosta webhook for a tenant whose key is known bad is stored and marked skipped with its
-- reason in `error` — no Bosta call. Nothing re-processes 'skipped' events (the status poll catches up on the current
-- state once the key works).

ALTER TABLE courier_accounts
    ADD COLUMN key_auth_fail_count    integer NOT NULL DEFAULT 0,
    ADD COLUMN key_auth_fail_first_at timestamptz,
    ADD COLUMN key_auth_fail_key_hash text,
    ADD COLUMN key_auth_bad_at        timestamptz,
    ADD COLUMN key_auth_probe_at      timestamptz;

ALTER TYPE webhook_status ADD VALUE IF NOT EXISTS 'skipped';
