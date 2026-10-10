-- ============================================================
-- V164 — Returns portal P4b: GDPR for portal pre-connect orders (V163).
--
-- A portal pre-connect order's external_id is 'internal:portal:<uuid>'; its Shopify order GID is
-- orders.shopify_order_gid. Everything that finds "this Shopify order" by GID must look at both.
--
-- 1. shopify_event_keep_order_redacted (V143) — a stored orders/* webhook for a REDACTED order is
--    stripped on insert. It matched the order by external_id only, so a webhook for a redacted
--    portal order would have been stored whole. Re-created to match external_id OR
--    shopify_order_gid (ux_orders_tenant_shopify_order_gid serves the lookup).
--
-- 2. portal_delivery (the original Bosta delivery's address + receiver) is customer PII. Redaction
--    clears it (CustomerRedaction.REDACT_ORDERS); this trigger keeps it cleared — any later write
--    to a redacted order leaves it NULL, the same "redaction sticks" rule V143 applies to raw.
--    INVOKER, not a hatch.
--
-- No orders column changes, so merchant_orders (V163) is untouched.
-- ============================================================

CREATE OR REPLACE FUNCTION shopify_event_keep_order_redacted() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.topic LIKE 'orders/%'
       AND EXISTS (SELECT 1 FROM orders o
                   WHERE o.tenant_id = NEW.tenant_id
                     AND (o.external_id = NEW.payload_raw ->> 'admin_graphql_api_id'
                          OR o.shopify_order_gid = NEW.payload_raw ->> 'admin_graphql_api_id')
                     AND o.pii_redacted_at IS NOT NULL) THEN
        NEW.payload_raw := shopify_order_raw_redacted(NEW.payload_raw);
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION orders_keep_portal_delivery_redacted() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.pii_redacted_at IS NOT NULL THEN
        NEW.portal_delivery := NULL;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER orders_keep_portal_delivery_redacted
    BEFORE INSERT OR UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION orders_keep_portal_delivery_redacted();
