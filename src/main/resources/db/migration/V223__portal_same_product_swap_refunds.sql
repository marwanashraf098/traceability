-- V223 — Returns portal exchange pricing, decision C (2026-11-07): "Same-product swaps can refund the customer".
--
-- OFF (default, every tenant): on a same-product exchange whose price difference applies (free variant swaps off), a
-- LOWER new price never leaves the customer owed — the difference is floored at 0 (the customer still pays the fee and
-- a positive difference). ON: the pre-V223 behaviour (a lower price is paid back as customer_owed).
-- Different-product exchanges always use the real difference both ways. Read only by PortalAmounts.exchangePricing;
-- written only by CustomerFeeSettingsService.putExchangePricing (audited). Already-frozen requests never change.
ALTER TABLE tenants ADD COLUMN portal_same_product_swap_refunds boolean NOT NULL DEFAULT false;
