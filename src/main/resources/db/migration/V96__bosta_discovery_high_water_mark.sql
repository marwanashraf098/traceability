-- ============================================================
-- V96 — Bosta discovery poll: high-water-mark cursor
--
-- BostaDiscoveryPollJob currently re-fetches the newest ~150 Bosta deliveries
-- (fetchDelivery, a real API call) on EVERY cycle, whether or not they were
-- already seen — there is no record of "how far discovery got last time".
-- Cutting discovery's cadence toward ~2 min (a later change, not this one)
-- makes that unconditional re-fetch far too expensive and 429-prone.
--
-- Storage choice: one column on courier_accounts, not a new table. Discovery
-- already loads exactly one courier_accounts row per active Bosta tenant
-- (tenant_id + api_key_encrypted) to run its cycle, and this cursor is a
-- singleton piece of per-tenant integration state — the same shape as the
-- row it already lives next to, not a growing/keyed dataset that would
-- justify its own table. It inherits courier_accounts' existing RLS policy
-- and tenant_id scoping for free; no new policy to write or get wrong.
--
-- The cursor is a tracking number, not a timestamp. The Bosta list endpoint
-- returns slim items with no createdAt field (BostaGateway.SlimDelivery has
-- only trackingNumber/stateCode/type — confirmed against BostaHttpGateway's
-- actual parsing), and the project invariant this design leans on is only
-- "creation-ordered, newest-first", not any specific timestamp field or
-- format. A tracking number is a stable, opaque, already-unique (shipments.
-- tracking_number UNIQUE, V1) identity for "the newest item we fully
-- accounted for as of the last clean cycle" — no parsing, no timezone/format
-- assumptions about a Bosta field we've never had to trust before.
-- ============================================================

ALTER TABLE courier_accounts
    ADD COLUMN discovery_high_water_tracking text;
