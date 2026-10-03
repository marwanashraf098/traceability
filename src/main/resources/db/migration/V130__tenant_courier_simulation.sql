-- V130 — Review mode, S1: per-tenant simulated courier flag.
--
-- A row here means the tenant's courier is SIMULATED: Traced never calls Bosta for it
-- (waybills and pickups are simulated in later slices). The default for every tenant is
-- real — no row. Rows are written only by migrations / ops scripts: app_user has SELECT
-- only, so no API path can create, change or remove a row. Used for the Shopify App Store
-- review tenant; NOT is_demo (DemoSeeder asserts exactly one is_demo tenant).
CREATE TABLE tenant_courier_simulation (
    tenant_id  uuid        PRIMARY KEY REFERENCES tenants(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    note       text
);

ALTER TABLE tenant_courier_simulation ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_courier_simulation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_read_own ON tenant_courier_simulation
    FOR SELECT
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT ON tenant_courier_simulation TO app_user;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON tenant_courier_simulation FROM app_user;

-- A simulated tenant may never hold a courier_accounts row (any status).
-- SECURITY INVOKER (not a DEFINER escape hatch): run by app_user it reads the tenant's own
-- flag row under RLS — courier_accounts' own WITH CHECK already forces NEW.tenant_id to be
-- the GUC tenant, so that row is visible to the check.
CREATE FUNCTION forbid_courier_account_for_simulated_tenant() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM tenant_courier_simulation WHERE tenant_id = NEW.tenant_id) THEN
        RAISE EXCEPTION 'courier_accounts: tenant % is in simulated courier mode', NEW.tenant_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER courier_accounts_no_simulated_tenant
    BEFORE INSERT OR UPDATE OF tenant_id ON courier_accounts
    FOR EACH ROW EXECUTE FUNCTION forbid_courier_account_for_simulated_tenant();

-- And the reverse: a tenant that already has a courier row (any status) can't be flagged.
CREATE FUNCTION forbid_simulation_flag_with_courier_account() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM courier_accounts WHERE tenant_id = NEW.tenant_id) THEN
        RAISE EXCEPTION 'tenant_courier_simulation: tenant % has a courier account', NEW.tenant_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER tenant_courier_simulation_no_courier_account
    BEFORE INSERT OR UPDATE OF tenant_id ON tenant_courier_simulation
    FOR EACH ROW EXECUTE FUNCTION forbid_simulation_flag_with_courier_account();

COMMENT ON TABLE tenant_courier_simulation IS
    'Review mode: a row = simulated courier (never calls Bosta). Default real (no row). '
    'Written only by migrations/ops; app_user SELECT only. Never coexists with a '
    'courier_accounts row (two triggers). Reset/purge scripts must include this table.';
