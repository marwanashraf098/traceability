-- =============================================================================================
-- Review mode S5, step B — flag the Shopify App Store review tenant as simulated-courier.
--
-- app_user can't write tenant_courier_simulation (V130: SELECT only), so this is an ops step,
-- run as the database owner (postgres), AFTER step A (POST /api/v1/ops/review-tenant) and BEFORE
-- step C (POST /api/v1/ops/review-tenant/{id}/seed, which refuses an unflagged tenant).
--
-- Usage (session pooler, port 5432):
--   psql "<connection string>" -v ON_ERROR_STOP=1 -v tenant_id=<tenant id from step A> \
--        -f scripts/ops/review-tenant-flag.sql
--
-- Guards (any failure → nothing is written): the tenant exists; it is not Jumi, The Snouts or the
-- public demo tenant; its owner is reviewer@tracedtech.com; it has no Bosta courier account (the
-- V130 trigger enforces that too) and no store that isn't disconnected (never flag a merchant).
-- Already flagged → a NOTICE, nothing changes.
-- =============================================================================================

BEGIN;

SELECT set_config('review.tenant_id', :'tenant_id', true);

DO $$
DECLARE
    t uuid := current_setting('review.tenant_id')::uuid;
BEGIN
    IF t IN ('07fc572c-2158-412d-ae31-ec61e22378b7',     -- Jumi Worldwide
             'e785e5e4-2c5c-428e-afdd-d26d90754229',     -- The Snouts
             '91c6027e-0b23-4c56-84a6-2a769315ed2d') THEN -- public demo (DemoSeeder)
        RAISE EXCEPTION 'review-tenant-flag: % is a protected tenant', t;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM tenants WHERE id = t) THEN
        RAISE EXCEPTION 'review-tenant-flag: tenant % does not exist', t;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM users WHERE tenant_id = t AND role = 'owner'
                                         AND lower(email) = 'reviewer@tracedtech.com') THEN
        RAISE EXCEPTION 'review-tenant-flag: tenant %''s owner is not reviewer@tracedtech.com', t;
    END IF;
    IF EXISTS (SELECT 1 FROM courier_accounts WHERE tenant_id = t) THEN
        RAISE EXCEPTION 'review-tenant-flag: tenant % has a courier account', t;
    END IF;
    IF EXISTS (SELECT 1 FROM stores WHERE tenant_id = t AND status <> 'disconnected') THEN
        RAISE EXCEPTION 'review-tenant-flag: tenant % has a store that is not disconnected', t;
    END IF;
    IF EXISTS (SELECT 1 FROM tenant_courier_simulation WHERE tenant_id = t) THEN
        RAISE NOTICE 'review-tenant-flag: tenant % is already flagged — nothing to do', t;
        RETURN;
    END IF;

    INSERT INTO tenant_courier_simulation (tenant_id, note)
    VALUES (t, 'Shopify App Store review tenant');
    RAISE NOTICE 'review-tenant-flag: tenant % flagged as simulated-courier', t;
END $$;

COMMIT;
