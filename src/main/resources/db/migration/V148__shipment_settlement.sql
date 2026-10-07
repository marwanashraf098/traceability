-- V148 — Analytics slice 3: Bosta settlement columns on shipments.
--
-- Bosta's per-delivery settlement record (wallet.cashCycle) and payout reference
-- (wallet.cashout) live in the delivery payload. These columns hold them so money
-- analytics never reads raw jsonb. They are written by ShipmentSettlement (Java) on
-- every path that writes shipments.raw, and by SettlementRefreshJob, which re-reads
-- finished legs from Bosta (v0 GET, reads only) until they're paid.
--
-- Amounts and Bosta references only — no customer data. The V143 GDPR redaction
-- (bosta_raw_redacted) never touches wallet, and nothing here needs redacting.
-- shipments already has RLS (tenant_isolation since V1); new columns are covered.
--
--   settlement_status  'none'       no cashCycle seen yet
--                      'deposited'  cashCycle seen (Bosta settled it into the wallet), no payout yet
--                      'paid'       a cashout transaction id seen
--                      'unresolved' a successful Bosta read made 45+ days after the leg finished
--                                   still shows no payout: refresh stops
--   settlement_refreshed_at  the refresh job's last attempt (any outcome) — paces the queue
--   settlement_verified_at   its last SUCCESSFUL read (Bosta returned the delivery) — the only
--                            evidence 'unresolved' may rest on (stale raw never counts)
--   cashout_amount     Bosta's WHOLE payout batch total (all of the business's deliveries in
--                      that transfer, tracked by Traced or not) — never compare it to one leg.
--   shipment_fees_quoted  raw.shipmentFees (before VAT) — the estimate until a cashCycle exists.

ALTER TABLE shipments
    ADD COLUMN deposited_at            timestamptz,
    ADD COLUMN deposited_amt           numeric(12,2),
    ADD COLUMN cod_settled             numeric(12,2),
    ADD COLUMN bosta_fees              numeric(12,2),
    ADD COLUMN shipping_fees           numeric(12,2),
    ADD COLUMN vat                     numeric(12,2),
    ADD COLUMN opening_package_fees    numeric(12,2),
    ADD COLUMN collection_fees         numeric(12,2),
    ADD COLUMN insurance_fees          numeric(12,2),
    ADD COLUMN flex_ship_fees          numeric(12,2),
    ADD COLUMN promotion_discount      numeric(12,2),
    ADD COLUMN shipment_fees_quoted    numeric(12,2),
    ADD COLUMN cash_cycle_id           text,
    ADD COLUMN cashout_txn_id          text,
    ADD COLUMN cashout_date            date,
    ADD COLUMN cashout_amount          numeric(14,2),
    ADD COLUMN next_cashout_date       date,
    ADD COLUMN settlement_refreshed_at timestamptz,
    ADD COLUMN settlement_verified_at  timestamptz,
    ADD COLUMN settlement_status       text NOT NULL DEFAULT 'none'
        CONSTRAINT shipments_settlement_status_check
        CHECK (settlement_status IN ('none', 'deposited', 'paid', 'unresolved'));

-- Refresh queue: the legs still waiting for money, oldest-refreshed first.
CREATE INDEX shipments_settlement_queue_idx
    ON shipments (tenant_id, settlement_status, settlement_refreshed_at)
    WHERE settlement_status <> 'paid';

-- Payout grouping.
CREATE INDEX shipments_cashout_idx
    ON shipments (tenant_id, cashout_date)
    WHERE cashout_txn_id IS NOT NULL;

-- ---- Backfill from the payloads we already hold ------------------------------
-- Same rules as ShipmentSettlement.extract(): numbers may arrive as JSON numbers or
-- strings ("93.00"); a cashout date comes from transaction_date, else from the
-- transaction id's DDMONYY (e.g. WEDCOD09SEP26 → 2026-09-09).
CREATE FUNCTION pg_temp.settle_num(v jsonb) RETURNS numeric LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN v IS NULL OR jsonb_typeof(v) = 'null' THEN NULL
                WHEN jsonb_typeof(v) = 'number' THEN (v #>> '{}')::numeric
                WHEN jsonb_typeof(v) = 'string' AND (v #>> '{}') ~ '^-?[0-9]+(\.[0-9]+)?$' THEN (v #>> '{}')::numeric
           END
$$;

-- An impossible date (e.g. 31FEB26) gives NULL, never an error that would abort the migration.
CREATE FUNCTION pg_temp.settle_txn_date(txn text) RETURNS date LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE d date;
BEGIN
    IF txn ~ '^[A-Z]{3}COD[0-9]{2}[A-Z]{3}[0-9]{2}$' THEN
        d := to_date(substring(txn from 7 for 7), 'DDMONYY');
        IF upper(to_char(d, 'DDMONYY')) = substring(txn from 7 for 7) THEN
            RETURN d;
        END IF;
    END IF;
    RETURN NULL;
EXCEPTION WHEN others THEN
    RETURN NULL;
END
$$;

-- A timestamp that won't parse gives NULL (as in Java), never an error.
CREATE FUNCTION pg_temp.settle_ts(v text) RETURNS timestamptz LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    RETURN NULLIF(v, '')::timestamptz;
EXCEPTION WHEN others THEN
    RETURN NULL;
END
$$;

UPDATE shipments s SET
    deposited_at         = pg_temp.settle_ts(s.raw #>> '{wallet,cashCycle,deposited_at}'),
    deposited_amt        = pg_temp.settle_num(s.raw #> '{wallet,cashCycle,deposited_amt}'),
    cod_settled          = pg_temp.settle_num(s.raw #> '{wallet,cashCycle,cod}'),
    bosta_fees           = pg_temp.settle_num(s.raw #> '{wallet,cashCycle,bosta_fees}'),
    shipping_fees        = pg_temp.settle_num(s.raw #> '{wallet,cashCycle,shipping_fees}'),
    vat                  = pg_temp.settle_num(s.raw #> '{wallet,cashCycle,vat}'),
    opening_package_fees = pg_temp.settle_num(s.raw #> '{wallet,cashCycle,opening_package_fees}'),
    collection_fees      = pg_temp.settle_num(s.raw #> '{wallet,cashCycle,collection_fees}'),
    insurance_fees       = pg_temp.settle_num(s.raw #> '{wallet,cashCycle,insurance_fees}'),
    flex_ship_fees       = pg_temp.settle_num(s.raw #> '{wallet,cashCycle,flex_ship_fees}'),
    promotion_discount   = pg_temp.settle_num(s.raw #> '{wallet,cashCycle,promotion_discount_amount}'),
    shipment_fees_quoted = pg_temp.settle_num(s.raw -> 'shipmentFees'),
    cash_cycle_id        = CASE WHEN jsonb_typeof(s.raw #> '{wallet,cashCycle,_id}') IN ('number', 'string')
                                THEN s.raw #>> '{wallet,cashCycle,_id}' END,
    cashout_txn_id       = NULLIF(s.raw #>> '{wallet,cashout,transaction_id}', ''),
    cashout_date         = COALESCE(
                               (pg_temp.settle_ts(s.raw #>> '{wallet,cashout,transaction_date}')
                                    AT TIME ZONE 'Africa/Cairo')::date,
                               pg_temp.settle_txn_date(s.raw #>> '{wallet,cashout,transaction_id}')),
    cashout_amount       = pg_temp.settle_num(s.raw #> '{wallet,cashout,amount}'),
    next_cashout_date    = (pg_temp.settle_ts(s.raw #>> '{wallet,cashout,next_cashout_date}')
                                AT TIME ZONE 'Africa/Cairo')::date,
    settlement_status    = CASE WHEN NULLIF(s.raw #>> '{wallet,cashout,transaction_id}', '') IS NOT NULL THEN 'paid'
                                WHEN pg_temp.settle_ts(s.raw #>> '{wallet,cashCycle,deposited_at}') IS NOT NULL THEN 'deposited'
                                ELSE 'none' END
WHERE s.raw IS NOT NULL
  AND (s.raw ? 'wallet' OR s.raw ? 'shipmentFees');
