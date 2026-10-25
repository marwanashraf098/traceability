-- ============================================================
-- V185 — Onboarding gate: connect Shopify + main warehouse address before entering the app.
--
-- 1. locations.address_* — the main warehouse's street address (Egypt): governorate as the
--    Shopify / ISO-3166-2 province code (C, GZ, ALX …), city / area, street, optional
--    building and floor. Plain text; the setup screen is the writer (SetupAddressService).
--    address_shopify_push_* — the after-commit push of that address to the "Traced Main
--    Warehouse" Shopify location (MainLocationAddressPusher): NULL = nothing to push yet,
--    'pending' | 'pushed' | 'failed'. A failure never blocks the merchant; it raises the LOW
--    alert warehouse_address_push_failed and the job retries.
-- 2. tenants.shopify_connected_at — STICKY "this tenant has connected Shopify" stamp. Set the
--    first time any of its stores rows is written with a status other than 'disconnected'
--    (trigger below — every connect path writes stores), never cleared. Live store status
--    (token expiry, needs_reauth, a later disconnect) never re-locks a merchant.
-- 3. tenants.setup_completed_at — the onboarding gate is done. Stamped when the address is
--    saved after Shopify was connected.
--
-- Backfill (grandfathering): every tenant that has a stores row whose status is not
-- 'disconnected' right now gets both stamps — it is never gated, not even on the address
-- (it sees a soft "Add your warehouse address" banner instead). Tenants whose only stores
-- rows are 'disconnected' (previously connected) and tenants with no stores row are gated.
-- The public demo (is_demo) and the review tenant (tenant_courier_simulation) are bypassed
-- in /me (SetupStatus), not here.
-- ============================================================

ALTER TABLE locations
    ADD COLUMN address_province_code       text,
    ADD COLUMN address_city                text,
    ADD COLUMN address_street              text,
    ADD COLUMN address_building            text,
    ADD COLUMN address_floor               text,
    ADD COLUMN address_updated_at          timestamptz,
    ADD COLUMN address_shopify_push_status text,
    ADD COLUMN address_shopify_push_error  text,
    ADD COLUMN address_shopify_push_at     timestamptz,
    ADD CONSTRAINT locations_address_push_status_check
        CHECK (address_shopify_push_status IS NULL
               OR address_shopify_push_status IN ('pending', 'pushed', 'failed'));

ALTER TABLE tenants
    ADD COLUMN shopify_connected_at timestamptz,
    ADD COLUMN setup_completed_at   timestamptz;

UPDATE tenants t
   SET shopify_connected_at = now(),
       setup_completed_at   = now()
 WHERE EXISTS (SELECT 1 FROM stores s
                WHERE s.tenant_id = t.id AND s.status <> 'disconnected');

-- Sticky stamp. SECURITY INVOKER (not a DEFINER hatch): run by app_user inside the connect
-- transaction, whose tenant GUC is the store's tenant (stores' WITH CHECK forces it), so the
-- tenants row is visible and updatable under RLS. Touches the row only while the stamp is
-- still NULL, so later store writes take no tenants row lock.
CREATE FUNCTION stamp_tenant_shopify_connected() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM 'disconnected' THEN
        UPDATE tenants SET shopify_connected_at = now()
         WHERE id = NEW.tenant_id AND shopify_connected_at IS NULL;
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER stores_stamp_tenant_shopify_connected
    AFTER INSERT OR UPDATE OF status ON stores
    FOR EACH ROW EXECUTE FUNCTION stamp_tenant_shopify_connected();

COMMENT ON COLUMN tenants.shopify_connected_at IS
    'Sticky: first time a stores row of this tenant had a status other than disconnected (trigger '
    'stores_stamp_tenant_shopify_connected). Never cleared. Onboarding gate step 1.';
COMMENT ON COLUMN tenants.setup_completed_at IS
    'Onboarding gate done (V185). Backfilled for tenants connected at migration time.';
