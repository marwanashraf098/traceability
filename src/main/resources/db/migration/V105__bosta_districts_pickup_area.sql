-- ============================================================
-- V105 — Returns portal Step 4c-2: Bosta reference data, return warehouse, pickup area.
-- No Bosta writes anywhere in this step.
--
-- 1. bosta_districts — GLOBAL reference data (Bosta's cities → zones → districts), the same
--    shape as bosta_state_mappings (V1): no tenant_id, no RLS policy. It holds no tenant data.
--    Written only by BostaDistrictsRefreshService on the owner connection (approved for this
--    table only, 2026-09-25); app_user can read it and nothing else. A district Bosta stops
--    returning is kept with pickup_available = false — never deleted, so a request's stored
--    district id always still resolves.
-- 2. tenants.portal_pickup_booking — per-tenant switch replacing the global
--    PortalService.PICKUP_BOOKING constant (default off; no UI switch yet).
-- 3. courier_accounts.return_business_location_* — the Bosta pickup location returns go
--    back to (chosen in Settings → Returns portal, validated against Bosta's list).
-- 4. return_requests.pickup_* — snapshot of the customer's chosen pickup area (text, so a
--    later refresh of bosta_districts never rewrites what the customer picked).
-- ============================================================

CREATE TABLE bosta_districts (
    district_id        text        PRIMARY KEY,
    city_id            text        NOT NULL,
    city_name          text        NOT NULL,
    city_name_ar       text,
    zone_id            text,
    zone_name          text,
    zone_name_ar       text,
    district_name      text        NOT NULL,
    district_name_ar   text,
    pickup_available   boolean     NOT NULL DEFAULT false,
    dropoff_available  boolean     NOT NULL DEFAULT false,
    refreshed_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX bosta_districts_city_idx ON bosta_districts (city_id);

-- V1's ALTER DEFAULT PRIVILEGES gave app_user full DML on every new table; this one is
-- read-only for the application role.
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON bosta_districts FROM app_user;
GRANT SELECT ON bosta_districts TO app_user;

ALTER TABLE tenants
    ADD COLUMN portal_pickup_booking boolean NOT NULL DEFAULT false;

ALTER TABLE courier_accounts
    ADD COLUMN return_business_location_id   text,
    ADD COLUMN return_business_location_name text;

ALTER TABLE return_requests
    ADD COLUMN pickup_city_id          text,
    ADD COLUMN pickup_city_name        text,
    ADD COLUMN pickup_district_id      text,
    ADD COLUMN pickup_district_name    text,
    ADD COLUMN pickup_district_name_ar text;
