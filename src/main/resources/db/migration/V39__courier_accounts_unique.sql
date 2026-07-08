-- V39: Deduplicate courier_accounts and enforce one-row-per-tenant-per-provider.
--
-- Root cause: no UNIQUE constraint on (tenant_id, provider) meant ON CONFLICT DO NOTHING
-- in BostaController.connect() had no conflict target → always inserted a new row.
-- Tenant 07fc572c accumulated 4 bosta rows; each was polled independently, multiplying
-- Bosta API calls 4× per cycle and contributing to 429 rate-limiting.
--
-- Fix in three steps:
--   1. Repoint shipments.courier_account_id / pickups.courier_account_id from loser rows
--      to the keeper row (the one referenced by shipments, else the latest id).
--   2. Delete loser rows.
--   3. Add UNIQUE(tenant_id, provider) so reconnecting upserts, never inserts a dupe.

-- ── Step 1a: repoint shipments ────────────────────────────────────────────────
-- For each (tenant_id, provider) group, pick the winner:
-- prefer the row already referenced by ≥1 shipment; tiebreak on id DESC.
UPDATE shipments
SET courier_account_id = winners.winner_id
FROM (
    SELECT DISTINCT ON (ca.tenant_id, ca.provider)
           ca.id        AS winner_id,
           ca.tenant_id,
           ca.provider
    FROM courier_accounts ca
    ORDER BY
        ca.tenant_id,
        ca.provider,
        (EXISTS (SELECT 1 FROM shipments s WHERE s.courier_account_id = ca.id)) DESC,
        ca.id DESC
) winners
JOIN courier_accounts losers
    ON  losers.tenant_id = winners.tenant_id
    AND losers.provider  = winners.provider
    AND losers.id       <> winners.winner_id
WHERE shipments.courier_account_id = losers.id;

-- ── Step 1b: repoint pickups ─────────────────────────────────────────────────
UPDATE pickups
SET courier_account_id = winners.winner_id
FROM (
    SELECT DISTINCT ON (ca.tenant_id, ca.provider)
           ca.id        AS winner_id,
           ca.tenant_id,
           ca.provider
    FROM courier_accounts ca
    ORDER BY
        ca.tenant_id,
        ca.provider,
        (EXISTS (SELECT 1 FROM shipments s WHERE s.courier_account_id = ca.id)) DESC,
        ca.id DESC
) winners
JOIN courier_accounts losers
    ON  losers.tenant_id = winners.tenant_id
    AND losers.provider  = winners.provider
    AND losers.id       <> winners.winner_id
WHERE pickups.courier_account_id = losers.id;

-- ── Step 2: delete loser rows ─────────────────────────────────────────────────
-- After step 1, no FK references point to losers, so deletion is safe.
-- ca2 alias avoids ambiguity between the DELETE target and the subquery range.
DELETE FROM courier_accounts
WHERE id NOT IN (
    SELECT DISTINCT ON (ca2.tenant_id, ca2.provider) ca2.id
    FROM courier_accounts ca2
    ORDER BY
        ca2.tenant_id,
        ca2.provider,
        (EXISTS (SELECT 1 FROM shipments s WHERE s.courier_account_id = ca2.id)) DESC,
        ca2.id DESC
);

-- ── Step 3: add the unique constraint ────────────────────────────────────────
-- Each (tenant_id, provider) pair now has exactly one row — safe to constrain.
ALTER TABLE courier_accounts
    ADD CONSTRAINT courier_accounts_tenant_provider_uq UNIQUE (tenant_id, provider);
