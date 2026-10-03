-- V132 — Review mode, S3: reserved tracking numbers for simulated shipments.
--
-- Simulated-courier tenants (tenant_courier_simulation, V130) get a 'created' forward shipment
-- on every ingested order (SimulatedShipments, inside the order transaction). Its tracking number
-- comes from this sequence: 13 digits starting 777 — SimulatedTracking's reserved range
-- (^777\d{10}$). Real Bosta numbers are 8–10 digits and the public demo uses 12-digit 999…, so
-- the range never collides; BostaHttpGateway refuses it before any HTTP call (S1).
CREATE SEQUENCE simulated_tracking_seq AS bigint
    START WITH 7770000000001 MINVALUE 7770000000001 MAXVALUE 7779999999999 NO CYCLE;
GRANT USAGE ON SEQUENCE simulated_tracking_seq TO app_user;

-- A reserved-range tracking number may only ever belong to a simulated-courier tenant.
-- SECURITY INVOKER (like V130's triggers, not a DEFINER hatch): run by app_user it reads the row's
-- own tenant flag under RLS — shipments' own WITH CHECK forces NEW.tenant_id to be the GUC tenant.
CREATE FUNCTION forbid_reserved_tracking_for_real_tenant() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tracking_number ~ '^777[0-9]{10}$'
       AND NOT EXISTS (SELECT 1 FROM tenant_courier_simulation WHERE tenant_id = NEW.tenant_id) THEN
        RAISE EXCEPTION 'shipments: reserved simulated tracking number % on a real tenant', NEW.tracking_number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER shipments_reserved_tracking_simulated_only
    BEFORE INSERT OR UPDATE OF tracking_number, tenant_id ON shipments
    FOR EACH ROW EXECUTE FUNCTION forbid_reserved_tracking_for_real_tenant();
