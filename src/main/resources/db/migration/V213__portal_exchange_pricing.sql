-- V213 — Returns portal fees F3: an exchange switched to a refund before approval has its frozen amounts recalculated
-- as a refund (RequestAmounts.recalculateAsRefund, Marawan 2026-11-05). That rewrites the fee kind, the rule snapshot,
-- the per-item lines and clears the exchange's price difference — so app_user may now also UPDATE those columns. The
-- V210 trigger is unchanged: any UPDATE is refused once the request is no longer 'requested', and the request and
-- tenant never change. Nothing else.
GRANT UPDATE (fee_kind, fee_rule_snapshot, lines, price_difference, replacement_unit_price, fee_effective_percent)
    ON return_request_amounts TO app_user;
