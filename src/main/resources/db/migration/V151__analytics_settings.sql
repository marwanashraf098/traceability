-- V151 — Analytics slice 4: per-tenant analytics settings (restock suggestion inputs).
--
-- One row per tenant, written by the owner from Analytics (GET / PUT /api/v1/analytics/settings);
-- a tenant with no row reads the defaults. supplier_lead_days = days from ordering stock to it
-- being received; cover_days = days of sales the restock should last after it arrives.
-- Restock suggestion = velocity × (supplier_lead_days + cover_days) − on hand − coming back, min 0.
CREATE TABLE analytics_settings (
    tenant_id          uuid        PRIMARY KEY REFERENCES tenants(id),
    supplier_lead_days integer     NOT NULL DEFAULT 21 CHECK (supplier_lead_days BETWEEN 0 AND 365),
    cover_days         integer     NOT NULL DEFAULT 35 CHECK (cover_days BETWEEN 1 AND 365),
    updated_by         uuid        REFERENCES users(id),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE analytics_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE analytics_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON analytics_settings
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- V1's default privileges gave app_user full DML; a settings row is upserted, never deleted.
REVOKE DELETE, TRUNCATE ON analytics_settings FROM app_user;
