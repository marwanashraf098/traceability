-- =============================================================================================
-- Review mode S6 — reset the Shopify App Store review tenant between review rounds.
--
-- Clears everything the review tenant has accumulated (fixture, the reviewer's own store and
-- orders, sessions, events, exceptions, …) and KEEPS:
--   - the tenants row, its users and its locations (each location's Shopify link is cleared: the
--     next reviewer's shop provisions its own — provisioning only links a location whose
--     shopify_location_id IS NULL, so a kept link would point inventory writes at the old shop),
--   - the placeholder store review-tracedtech.myshopify.invalid (every other store row is deleted),
--   - the tenant_courier_simulation flag row.
-- Then re-run step C (POST /api/v1/ops/review-tenant/{id}/seed).
--
-- Run as the database owner (postgres) with psql — RLS is bypassed, so every statement is bound to
-- the one tenant id, and the guards below abort the whole transaction on any doubt:
--   the tenant exists, is not Jumi / The Snouts / the public demo / any is_demo tenant, is flagged
--   simulated-courier, is the ONLY flagged tenant, its owner is reviewer@tracedtech.com, it has no
--   courier account.
-- After the deletes it proves: nothing of the tenant is left outside the kept set; the kept rows are
-- all still there; and every other tenant's rows and every global table are exactly as before
-- (row counts per table, compared in the same transaction).
--
-- Dry run by default — ends with ROLLBACK unless -v commit=yes:
--   psql "<connection string>" -v ON_ERROR_STOP=1 -v tenant_id=<review tenant id> \
--        -f scripts/ops/review-tenant-reset.sql                    # dry run, read the NOTICEs
--   psql "<connection string>" -v ON_ERROR_STOP=1 -v tenant_id=<review tenant id> -v commit=yes \
--        -f scripts/ops/review-tenant-reset.sql                    # for real
--
-- Not a Flyway migration. JobRunr's tables are global and untouched: a job already queued for the
-- old reviewer store finds its rows gone and fails / no-ops.
-- =============================================================================================

\set ON_ERROR_STOP on

BEGIN;

SELECT set_config('review.tenant_id', :'tenant_id', true);

-- ---------------------------------------------------------------------------------------------
-- 1. Guards
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    t uuid := current_setting('review.tenant_id')::uuid;
BEGIN
    IF t IN ('07fc572c-2158-412d-ae31-ec61e22378b7',     -- Jumi Worldwide
             'e785e5e4-2c5c-428e-afdd-d26d90754229',     -- The Snouts
             '91c6027e-0b23-4c56-84a6-2a769315ed2d') THEN -- public demo (DemoSeeder)
        RAISE EXCEPTION 'review-tenant-reset: % is a protected tenant', t;
    END IF;
    PERFORM 1 FROM tenants WHERE id = t FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'review-tenant-reset: tenant % does not exist', t;
    END IF;
    IF EXISTS (SELECT 1 FROM tenants WHERE id = t AND is_demo) THEN
        RAISE EXCEPTION 'review-tenant-reset: tenant % is the demo tenant', t;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM tenant_courier_simulation WHERE tenant_id = t) THEN
        RAISE EXCEPTION 'review-tenant-reset: tenant % is not flagged simulated-courier', t;
    END IF;
    IF (SELECT COUNT(*) FROM tenant_courier_simulation) <> 1 THEN
        RAISE EXCEPTION 'review-tenant-reset: expected exactly one simulated-courier tenant, found %',
            (SELECT COUNT(*) FROM tenant_courier_simulation);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM users WHERE tenant_id = t AND role = 'owner'
                                         AND lower(email) = 'reviewer@tracedtech.com') THEN
        RAISE EXCEPTION 'review-tenant-reset: tenant %''s owner is not reviewer@tracedtech.com', t;
    END IF;
    IF EXISTS (SELECT 1 FROM courier_accounts WHERE tenant_id = t) THEN
        RAISE EXCEPTION 'review-tenant-reset: tenant % has a courier account', t;
    END IF;
    RAISE NOTICE 'review-tenant-reset: guards passed for tenant %', t;
END $$;

-- ---------------------------------------------------------------------------------------------
-- 2. Snapshot: what is NOT this tenant's (other tenants' rows per tenant-scoped table, every
--    global table except JobRunr's, which its server writes on its own), and the kept rows.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE review_reset_other (table_name text PRIMARY KEY, n bigint) ON COMMIT DROP;
CREATE TEMP TABLE review_reset_kept  (what text PRIMARY KEY, n bigint)       ON COMMIT DROP;

DO $$
DECLARE
    t   uuid := current_setting('review.tenant_id')::uuid;
    r   record;
    cnt bigint;
BEGIN
    FOR r IN
        SELECT tb.table_name,
               EXISTS (SELECT 1 FROM information_schema.columns c WHERE c.table_schema = 'public'
                         AND c.table_name = tb.table_name AND c.column_name = 'tenant_id') AS scoped
        FROM information_schema.tables tb
        WHERE tb.table_schema = 'public' AND tb.table_type = 'BASE TABLE' AND tb.table_name NOT LIKE 'jobrunr%'
    LOOP
        IF r.scoped THEN
            EXECUTE format('SELECT COUNT(*) FROM %I WHERE tenant_id IS DISTINCT FROM $1', r.table_name) INTO cnt USING t;
        ELSE
            EXECUTE format('SELECT COUNT(*) FROM %I', r.table_name) INTO cnt;
        END IF;
        INSERT INTO review_reset_other VALUES (r.table_name, cnt);
    END LOOP;

    INSERT INTO review_reset_kept VALUES
        ('users',            (SELECT COUNT(*) FROM users     WHERE tenant_id = t)),
        ('locations',        (SELECT COUNT(*) FROM locations WHERE tenant_id = t)),
        ('placeholder store',(SELECT COUNT(*) FROM stores    WHERE tenant_id = t
                                                             AND shop_domain = 'review-tracedtech.myshopify.invalid')),
        ('flag',             (SELECT COUNT(*) FROM tenant_courier_simulation WHERE tenant_id = t));
END $$;

-- ---------------------------------------------------------------------------------------------
-- 3. Delete everything else of this tenant. Every tenant-scoped table except the kept ones, in
--    as many passes as the foreign keys need: a DELETE still referenced by a row deleted in a
--    later pass is rolled back to its savepoint and retried next pass. No progress in a pass →
--    abort, naming the tables and the blocking error (never forced).
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    t        uuid := current_setting('review.tenant_id')::uuid;
    pending  text[];
    still    text[];
    tbl      text;
    cnt      bigint;
    last_err text;
    pass     int := 0;
BEGIN
    SELECT array_agg(c.table_name::text ORDER BY c.table_name)
      INTO pending
      FROM information_schema.columns c
      JOIN information_schema.tables tb ON tb.table_schema = c.table_schema AND tb.table_name = c.table_name
     WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id' AND tb.table_type = 'BASE TABLE'
       AND c.table_name NOT IN ('users', 'locations', 'tenant_courier_simulation');

    LOOP
        pass  := pass + 1;
        still := ARRAY[]::text[];
        FOREACH tbl IN ARRAY pending LOOP
            BEGIN
                IF tbl = 'stores' THEN
                    DELETE FROM stores WHERE tenant_id = t AND shop_domain <> 'review-tracedtech.myshopify.invalid';
                ELSE
                    EXECUTE format('DELETE FROM %I WHERE tenant_id = $1', tbl) USING t;
                END IF;
                GET DIAGNOSTICS cnt = ROW_COUNT;
                IF cnt > 0 THEN
                    RAISE NOTICE 'review-tenant-reset: pass % deleted % row(s) from %', pass, cnt, tbl;
                END IF;
            EXCEPTION WHEN foreign_key_violation THEN
                still    := still || tbl;
                last_err := SQLERRM;
            END;
        END LOOP;
        EXIT WHEN cardinality(still) = 0;
        IF cardinality(still) = cardinality(pending) THEN
            RAISE EXCEPTION 'review-tenant-reset: no progress in pass %, still blocked: % (last error: %)',
                pass, still, last_err;
        END IF;
        pending := still;
    END LOOP;

    -- The kept locations lose their link to the old reviewer's Shopify shop.
    UPDATE locations
       SET shopify_location_id = NULL, shopify_sync_status = 'unsynced', shopify_synced_at = NULL,
           shopify_sync_error = NULL, shopify_delivery_profile_status = 'not_activated',
           shopify_delivery_profile_activated_at = NULL, shopify_delivery_profile_error = NULL
     WHERE tenant_id = t;
    GET DIAGNOSTICS cnt = ROW_COUNT;
    RAISE NOTICE 'review-tenant-reset: % location(s) unlinked from Shopify', cnt;
END $$;

-- ---------------------------------------------------------------------------------------------
-- 4. Prove it: nothing left outside the kept set; kept rows intact; nobody else touched.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    t     uuid := current_setting('review.tenant_id')::uuid;
    r     record;
    cnt   bigint;
    wrong text[] := ARRAY[]::text[];
BEGIN
    FOR r IN
        SELECT c.table_name::text AS table_name
          FROM information_schema.columns c
          JOIN information_schema.tables tb ON tb.table_schema = c.table_schema AND tb.table_name = c.table_name
         WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id' AND tb.table_type = 'BASE TABLE'
           AND c.table_name NOT IN ('users', 'locations', 'tenant_courier_simulation')
    LOOP
        IF r.table_name = 'stores' THEN
            SELECT COUNT(*) INTO cnt FROM stores WHERE tenant_id = t
               AND shop_domain <> 'review-tracedtech.myshopify.invalid';
        ELSE
            EXECUTE format('SELECT COUNT(*) FROM %I WHERE tenant_id = $1', r.table_name) INTO cnt USING t;
        END IF;
        IF cnt <> 0 THEN wrong := wrong || format('%s=%s', r.table_name, cnt); END IF;
    END LOOP;
    IF cardinality(wrong) > 0 THEN
        RAISE EXCEPTION 'review-tenant-reset: tenant rows left behind: %', wrong;
    END IF;

    IF (SELECT n FROM review_reset_kept WHERE what = 'users')
         <> (SELECT COUNT(*) FROM users WHERE tenant_id = t)
       OR (SELECT n FROM review_reset_kept WHERE what = 'locations')
         <> (SELECT COUNT(*) FROM locations WHERE tenant_id = t)
       OR (SELECT n FROM review_reset_kept WHERE what = 'placeholder store')
         <> (SELECT COUNT(*) FROM stores WHERE tenant_id = t AND shop_domain = 'review-tracedtech.myshopify.invalid')
       OR (SELECT COUNT(*) FROM tenant_courier_simulation WHERE tenant_id = t) <> 1
       OR NOT EXISTS (SELECT 1 FROM tenants WHERE id = t) THEN
        RAISE EXCEPTION 'review-tenant-reset: a kept row is missing';
    END IF;

    FOR r IN SELECT o.table_name, o.n,
                    EXISTS (SELECT 1 FROM information_schema.columns c WHERE c.table_schema = 'public'
                              AND c.table_name = o.table_name AND c.column_name = 'tenant_id') AS scoped
               FROM review_reset_other o
    LOOP
        IF r.scoped THEN
            EXECUTE format('SELECT COUNT(*) FROM %I WHERE tenant_id IS DISTINCT FROM $1', r.table_name) INTO cnt USING t;
        ELSE
            EXECUTE format('SELECT COUNT(*) FROM %I', r.table_name) INTO cnt;
        END IF;
        IF cnt <> r.n THEN wrong := wrong || format('%s: %s→%s', r.table_name, r.n, cnt); END IF;
    END LOOP;
    IF cardinality(wrong) > 0 THEN
        RAISE EXCEPTION 'review-tenant-reset: rows outside the review tenant changed: %', wrong;
    END IF;

    RAISE NOTICE 'review-tenant-reset: verified — kept % user(s), % location(s), % placeholder store, the flag; others untouched',
        (SELECT n FROM review_reset_kept WHERE what = 'users'),
        (SELECT n FROM review_reset_kept WHERE what = 'locations'),
        (SELECT n FROM review_reset_kept WHERE what = 'placeholder store');
END $$;

-- ---------------------------------------------------------------------------------------------
-- 5. Commit only with -v commit=yes.
-- ---------------------------------------------------------------------------------------------
\if :{?commit}
\if :commit
COMMIT;
\echo 'review-tenant-reset: COMMITTED — now re-run the seed (step C).'
\else
ROLLBACK;
\echo 'review-tenant-reset: dry run — ROLLED BACK (pass -v commit=yes to apply).'
\endif
\else
ROLLBACK;
\echo 'review-tenant-reset: dry run — ROLLED BACK (pass -v commit=yes to apply).'
\endif
