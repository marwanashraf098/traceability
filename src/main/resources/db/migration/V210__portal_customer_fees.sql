-- V210 — Returns portal fees F1: the data model for customer fees and exchange pricing (2026-11-05).
--
-- DATA ONLY. Nothing in F1 reads these for a decision: the portal lookup, submission, approval, booking
-- and refund suggestion are unchanged whatever is stored here (F2 adds the calculator, F3 the
-- different-product picker and price difference, F4 the type 30 COD). Every default is "off".
--
-- Decisions (Marawan, 2026-11-05): exchange to a different product off for everyone (opt-in);
-- merchant-fault reasons (fee waived) = damaged, wrong_item, not_as_pictured, editable by the merchant;
-- a fixed fee is charged unless every item is merchant-fault, a percent fee is waived per item;
-- fees round to whole EGP (half up); no override after approval; area overrides by city and district
-- only (no zone).

-- 1. Exchange pricing + the merchant-fault reasons (tenant settings).
ALTER TABLE tenants
    ADD COLUMN portal_exchange_any_product        boolean       NOT NULL DEFAULT false,
    ADD COLUMN portal_exchange_same_or_lower_only boolean       NOT NULL DEFAULT false,
    ADD COLUMN portal_exchange_max_difference     numeric(12,2)
        CHECK (portal_exchange_max_difference IS NULL
               OR (portal_exchange_max_difference BETWEEN 0 AND 30000
                   AND portal_exchange_max_difference = trunc(portal_exchange_max_difference))),
    ADD COLUMN portal_merchant_fault_reasons      text[]        NOT NULL
        DEFAULT ARRAY['damaged', 'wrong_item', 'not_as_pictured']::text[]
        CHECK (portal_merchant_fault_reasons <@ ARRAY['wrong_size', 'damaged', 'not_as_pictured', 'wrong_item',
                                                      'changed_mind', 'other']::text[]);

-- 2. The default fee rule per kind. A tenant with no row has no fee of that kind. Amounts are whole EGP;
--    a percent has at most 2 decimals. 'fixed' carries amount only; 'percent' carries percent and an
--    optional min / max (min <= max). The row keeps its values while switched off.
CREATE TABLE portal_fee_rules (
    tenant_id          uuid          NOT NULL REFERENCES tenants(id),
    kind               text          NOT NULL CHECK (kind IN ('exchange', 'refund')),
    enabled            boolean       NOT NULL DEFAULT false,
    mode               text          NOT NULL CHECK (mode IN ('fixed', 'percent')),
    amount             numeric(12,2),
    percent            numeric(5,2),
    min_amount         numeric(12,2),
    max_amount         numeric(12,2),
    waive_merchant_fault boolean     NOT NULL DEFAULT true,
    updated_by         uuid          REFERENCES users(id),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, kind),
    CONSTRAINT portal_fee_rules_shape CHECK (
        (mode = 'fixed'   AND amount IS NOT NULL AND percent IS NULL AND min_amount IS NULL AND max_amount IS NULL)
     OR (mode = 'percent' AND percent IS NOT NULL AND amount IS NULL)),
    CONSTRAINT portal_fee_rules_amount CHECK (amount IS NULL OR (amount BETWEEN 0 AND 30000 AND amount = trunc(amount))),
    CONSTRAINT portal_fee_rules_percent CHECK (percent IS NULL OR (percent > 0 AND percent <= 100)),
    CONSTRAINT portal_fee_rules_min CHECK (min_amount IS NULL OR (min_amount BETWEEN 0 AND 30000 AND min_amount = trunc(min_amount))),
    CONSTRAINT portal_fee_rules_max CHECK (max_amount IS NULL OR (max_amount BETWEEN 0 AND 30000 AND max_amount = trunc(max_amount))),
    CONSTRAINT portal_fee_rules_min_le_max CHECK (min_amount IS NULL OR max_amount IS NULL OR min_amount <= max_amount)
);

ALTER TABLE portal_fee_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE portal_fee_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON portal_fee_rules
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
-- Upserted, never deleted.
REVOKE DELETE, TRUNCATE ON portal_fee_rules FROM app_user;

-- 3. Area overrides of a kind's fee: a Bosta city, or one district of it (bosta_districts ids). The most
--    specific match wins: district, then city, then the default rule. Same amount shape as the rule; an
--    override may be 0 (no fee in that area). Edited as a table, so app_user keeps DELETE.
CREATE TABLE portal_fee_area_overrides (
    id           uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid          NOT NULL REFERENCES tenants(id),
    kind         text          NOT NULL CHECK (kind IN ('exchange', 'refund')),
    city_id      text          NOT NULL,
    district_id  text,
    mode         text          NOT NULL CHECK (mode IN ('fixed', 'percent')),
    amount       numeric(12,2),
    percent      numeric(5,2),
    min_amount   numeric(12,2),
    max_amount   numeric(12,2),
    updated_by   uuid          REFERENCES users(id),
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT portal_fee_area_overrides_shape CHECK (
        (mode = 'fixed'   AND amount IS NOT NULL AND percent IS NULL AND min_amount IS NULL AND max_amount IS NULL)
     OR (mode = 'percent' AND percent IS NOT NULL AND amount IS NULL)),
    CONSTRAINT portal_fee_area_overrides_amount CHECK (amount IS NULL OR (amount BETWEEN 0 AND 30000 AND amount = trunc(amount))),
    CONSTRAINT portal_fee_area_overrides_percent CHECK (percent IS NULL OR (percent >= 0 AND percent <= 100)),
    CONSTRAINT portal_fee_area_overrides_min CHECK (min_amount IS NULL OR (min_amount BETWEEN 0 AND 30000 AND min_amount = trunc(min_amount))),
    CONSTRAINT portal_fee_area_overrides_max CHECK (max_amount IS NULL OR (max_amount BETWEEN 0 AND 30000 AND max_amount = trunc(max_amount))),
    CONSTRAINT portal_fee_area_overrides_min_le_max CHECK (min_amount IS NULL OR max_amount IS NULL OR min_amount <= max_amount)
);
CREATE UNIQUE INDEX portal_fee_area_overrides_area_uq
    ON portal_fee_area_overrides (tenant_id, kind, city_id, COALESCE(district_id, ''));

ALTER TABLE portal_fee_area_overrides ENABLE ROW LEVEL SECURITY;
ALTER TABLE portal_fee_area_overrides FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON portal_fee_area_overrides
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE TRUNCATE ON portal_fee_area_overrides FROM app_user;

-- 4. The amounts of one request, frozen when it is submitted (F2 writes them; nothing does in F1).
--    Signed price_difference: positive = the customer pays. original_price_source is the F0 resolver's
--    source ('catalog' = approximate). lines = the per-item breakdown (item id, unit price, source, fee,
--    waived) — no PII. collect_cod is what the courier collects on the replacement (≤ 30,000, Bosta's
--    COD limit). fee_shopify_kind / fee_effective_percent are kept for S5's Shopify returnCreate.
--    Written once; afterwards app_user may UPDATE only the override columns, and only while the request
--    is still 'requested' (no override after approval) — trigger below. Never deleted.
CREATE TABLE return_request_amounts (
    request_id              uuid          PRIMARY KEY REFERENCES return_requests(id),
    tenant_id               uuid          NOT NULL REFERENCES tenants(id),
    currency                text          NOT NULL DEFAULT 'EGP',
    original_unit_price     numeric(12,2) CHECK (original_unit_price IS NULL OR original_unit_price >= 0),
    original_price_source   text          CHECK (original_price_source IN ('stored', 'shopify', 'catalog', 'none')),
    replacement_unit_price  numeric(12,2) CHECK (replacement_unit_price IS NULL OR replacement_unit_price >= 0),
    price_difference        numeric(12,2),
    item_value              numeric(12,2) NOT NULL CHECK (item_value >= 0),
    fee_kind                text          CHECK (fee_kind IN ('exchange', 'refund')),
    fee_amount              numeric(12,2) NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
    fee_rule_snapshot       jsonb,
    fee_waived              boolean       NOT NULL DEFAULT false,
    waive_reason            text          CHECK (waive_reason IN ('merchant_fault', 'merchant_override')),
    collect_cod             numeric(12,2) NOT NULL DEFAULT 0 CHECK (collect_cod BETWEEN 0 AND 30000),
    customer_owed           numeric(12,2) NOT NULL DEFAULT 0 CHECK (customer_owed >= 0),
    refund_due              numeric(12,2) CHECK (refund_due IS NULL OR refund_due >= 0),
    lines                   jsonb         NOT NULL DEFAULT '[]'::jsonb,
    fee_shopify_kind        text          CHECK (fee_shopify_kind IN ('return_shipping')),
    fee_effective_percent   numeric(7,4),
    created_at              timestamptz   NOT NULL DEFAULT now(),
    overridden_at           timestamptz,
    overridden_by           uuid          REFERENCES users(id),
    CONSTRAINT return_request_amounts_waive CHECK (fee_waived = (waive_reason IS NOT NULL)),
    CONSTRAINT return_request_amounts_override CHECK ((overridden_at IS NULL) = (overridden_by IS NULL))
);
CREATE INDEX return_request_amounts_tenant_idx ON return_request_amounts (tenant_id);

ALTER TABLE return_request_amounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE return_request_amounts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON return_request_amounts
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

REVOKE UPDATE, DELETE, TRUNCATE ON return_request_amounts FROM app_user;
GRANT UPDATE (fee_amount, fee_waived, waive_reason, collect_cod, customer_owed, refund_due,
              overridden_at, overridden_by) ON return_request_amounts TO app_user;

-- The request's tenant must be the row's tenant; an UPDATE only while the request is 'requested'.
-- INVOKER (not a hatch): it reads the caller's own return_requests row under the caller's RLS.
CREATE FUNCTION return_request_amounts_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    req_tenant uuid;
    req_status return_request_status;
BEGIN
    SELECT tenant_id, status INTO req_tenant, req_status FROM return_requests WHERE id = NEW.request_id;
    IF req_tenant IS DISTINCT FROM NEW.tenant_id THEN
        RAISE EXCEPTION 'return_request_amounts: request % is not this tenant''s', NEW.request_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.request_id <> OLD.request_id OR NEW.tenant_id <> OLD.tenant_id THEN
            RAISE EXCEPTION 'return_request_amounts: request and tenant never change' USING ERRCODE = 'check_violation';
        END IF;
        IF req_status <> 'requested' THEN
            RAISE EXCEPTION 'return_request_amounts: amounts are frozen once the request is decided (status %)', req_status
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER return_request_amounts_guard
    BEFORE INSERT OR UPDATE ON return_request_amounts
    FOR EACH ROW EXECUTE FUNCTION return_request_amounts_guard();
