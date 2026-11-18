-- V235 — Bazar POS slice B4 (approved 2026-11-18): close the day. NO Shopify write of any kind.
--   - bazar_day_closes: one row per CLOSE of one business day (Africa/Cairo date) of one event — APPEND-ONLY like
--     bazar_payments: app_user has INSERT / SELECT only. Closing again (the day changed after it was closed, or a
--     recount) writes a NEW row whose supersedes_id points at the close it replaces; the CURRENT close of a day is the
--     one nothing supersedes. A day's first close has supersedes_id NULL (one per event + day); a close is superseded
--     at most once; the composite FK keeps a superseding row on the same tenant, event and day.
--   - What the merchant entered: opening_float (the cash the drawer started with — the first close asks it, later
--     days default to the previous close's cash_kept), cash_counted, card_settlement, instapay_received (ticked =
--     received in full; unticked = the whole InstaPay amount counts as not received), cash_kept (change left in the
--     drawer for tomorrow), note.
--   - Snapshot of what was expected when it was closed, from bazar_payments rows WRITTEN that day (payments −
--     refunds, voided rows out — the same source as /today): net_cash / net_card / net_instapay, expected_cash
--     (= opening_float + net_cash), expected_card, expected_instapay; the differences (entered − expected; InstaPay
--     unticked → −expected), not_received_total (the shortfalls), over_total (money over), take_home (counted − kept).
--   - BazarDayService is the ONE writer. client_request_id: the phone's idempotency key (UNIQUE per tenant).
--   - bazar_payments.business_day (B4 review, approved 2026-11-18): the business day a money row COUNTS on. Real money
--     (a sale's payment, a return's refund, an exchange's difference) = the Africa/Cairo date it was written (the
--     column's default). A payment-method correction stays on the sale's day: its void row AND its replacement row
--     inherit the business_day of the row they replace (BazarSaleService) — no money moves on the day of the edit;
--     that day reads "changed after close" when it was closed. Undo voids inherit the same way. Existing rows: the
--     Cairo date they were written (voids: their voided row's).
-- RLS NULLIF + FORCE.

CREATE TABLE bazar_day_closes (
    id                  uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid          NOT NULL REFERENCES tenants(id),
    event_id            uuid          NOT NULL,
    business_day        date          NOT NULL,
    opening_float       numeric(12,2) NOT NULL CHECK (opening_float >= 0),
    cash_counted        numeric(12,2) NOT NULL CHECK (cash_counted >= 0),
    card_settlement     numeric(12,2) NOT NULL CHECK (card_settlement >= 0),
    instapay_received   boolean       NOT NULL,
    cash_kept           numeric(12,2) NOT NULL CHECK (cash_kept >= 0 AND cash_kept <= cash_counted),
    note                text          CHECK (note IS NULL OR length(btrim(note)) BETWEEN 1 AND 500),
    net_cash            numeric(12,2) NOT NULL,
    net_card            numeric(12,2) NOT NULL,
    net_instapay        numeric(12,2) NOT NULL,
    expected_cash       numeric(12,2) NOT NULL,
    expected_card       numeric(12,2) NOT NULL,
    expected_instapay   numeric(12,2) NOT NULL,
    cash_difference     numeric(12,2) NOT NULL,
    card_difference     numeric(12,2) NOT NULL,
    instapay_difference numeric(12,2) NOT NULL,
    not_received_total  numeric(12,2) NOT NULL CHECK (not_received_total >= 0),
    over_total          numeric(12,2) NOT NULL CHECK (over_total >= 0),
    take_home           numeric(12,2) NOT NULL CHECK (take_home >= 0),
    closed_by           uuid          NOT NULL REFERENCES users(id),
    closed_at           timestamptz   NOT NULL DEFAULT now(),
    client_request_id   text          NOT NULL CHECK (length(client_request_id) BETWEEN 1 AND 100),
    supersedes_id       uuid,
    CHECK (supersedes_id IS NULL OR supersedes_id <> id),
    UNIQUE (tenant_id, client_request_id),
    UNIQUE (id, tenant_id, event_id, business_day),
    FOREIGN KEY (event_id, tenant_id) REFERENCES bazar_events (id, tenant_id),
    -- A re-close replaces a close of the SAME tenant, event and day.
    FOREIGN KEY (supersedes_id, tenant_id, event_id, business_day)
        REFERENCES bazar_day_closes (id, tenant_id, event_id, business_day)
);

-- One first close per event + day; a close is superseded at most once — so a day has exactly one current close.
CREATE UNIQUE INDEX bazar_day_closes_first_per_day ON bazar_day_closes (event_id, business_day)
    WHERE supersedes_id IS NULL;
CREATE UNIQUE INDEX bazar_day_closes_superseded_once ON bazar_day_closes (supersedes_id)
    WHERE supersedes_id IS NOT NULL;
CREATE INDEX bazar_day_closes_event_day_idx ON bazar_day_closes (event_id, business_day, closed_at);

ALTER TABLE bazar_day_closes ENABLE ROW LEVEL SECURITY;
ALTER TABLE bazar_day_closes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bazar_day_closes
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE UPDATE, DELETE, TRUNCATE ON bazar_day_closes FROM app_user;

-- The day a money row counts on (see above).
ALTER TABLE bazar_payments ADD COLUMN business_day date;
UPDATE bazar_payments SET business_day = (created_at AT TIME ZONE 'Africa/Cairo')::date;
UPDATE bazar_payments v SET business_day = o.business_day
    FROM bazar_payments o WHERE v.voids_payment_id = o.id AND v.kind = 'void';
ALTER TABLE bazar_payments ALTER COLUMN business_day SET DEFAULT ((now() AT TIME ZONE 'Africa/Cairo')::date);
ALTER TABLE bazar_payments ALTER COLUMN business_day SET NOT NULL;
CREATE INDEX bazar_payments_day_idx ON bazar_payments (tenant_id, business_day);
