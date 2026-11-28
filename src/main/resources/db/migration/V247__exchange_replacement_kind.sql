-- V247 — Returns portal: a defect replacement (2026-11-27). A customer whose item is damaged / faulty or who received
-- the wrong item may ask for the SAME variant again (same size, same colour) — a "Replacement (same item)".
--
-- 1. return_requests.exchange_kind — 'swap' (every exchange so far: another size / colour / product) or 'replacement'
--    (the same variant, only for a defect reason — portal.ReplacementRule is THE rule; PortalService.validExchange the
--    only writer of 'replacement'). Only an exchange can be a replacement; switching an exchange to a refund resets it
--    to 'swap' in the same UPDATE (ReturnRequestService.switchToRefundAndApprove). Every existing row is a 'swap' —
--    the default fills them (metadata-only ADD COLUMN with a constant default, no table rewrite).
-- 2. tenants.portal_replacement_fees — "Charge fees on defect replacements" (default OFF: a replacement is free).
--    ON: the exchange fee rule applies to a replacement, the merchant-fault waiver does not. A replacement NEVER has a
--    price difference, whatever this says. Written only by CustomerFeeSettingsService.putReplacementFees (audited);
--    read only by PortalService's pricing. Already-frozen requests never change.
--
-- Both tables are covered by their existing RLS policies and V1's table-level grants (app_user: full DML on both,
-- every column) — no new policy, grant or hatch. No orders column, so no merchant_orders / warehouse_orders re-create.
ALTER TABLE return_requests ADD COLUMN exchange_kind text NOT NULL DEFAULT 'swap';
ALTER TABLE return_requests ADD CONSTRAINT return_requests_exchange_kind_check
    CHECK (exchange_kind IN ('swap', 'replacement'));
ALTER TABLE return_requests ADD CONSTRAINT return_requests_replacement_is_exchange
    CHECK (exchange_kind = 'swap' OR type = 'exchange');

ALTER TABLE tenants ADD COLUMN portal_replacement_fees boolean NOT NULL DEFAULT false;
