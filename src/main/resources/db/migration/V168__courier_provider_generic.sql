-- V168 — Non-Bosta couriers, G1 (2026-10-18): the 'generic' courier provider.
--
-- Its own migration because Postgres can't use an enum value added in the same transaction
-- (V169 uses it in partial indexes and CHECKs). Adding a value changes nothing for existing rows:
-- every shipments / courier_accounts row stays 'bosta'.
ALTER TYPE courier_provider ADD VALUE IF NOT EXISTS 'generic';
