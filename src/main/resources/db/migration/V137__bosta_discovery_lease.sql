-- V137 — Bosta discovery: a lease column instead of a pinned connection (2026-10-04, B2).
--
-- BostaDiscoveryPollJob held one app_user connection for a whole discovery cycle (48–261 s in prod) just
-- to keep a session-level pg_try_advisory_lock — one of the app pool's connections gone for minutes, every
-- cycle, every tenant. The overlap guard is now a lease on the tenant's Bosta row: a conditional UPDATE
-- claims it (NULL or expired → now() + lease), the cycle clears it when done, and a crashed run's lease
-- simply expires. No connection is held between statements.

ALTER TABLE courier_accounts
    ADD COLUMN discovery_lease_until timestamptz;
