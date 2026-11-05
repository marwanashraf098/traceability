-- V208 — the price a customer paid for one unit of an order line: ONE definition (returns portal
-- fees F0, 2026-11-05).
--
-- Who fills what:
--   * REST webhook lines (orders/create, orders/updated) store Shopify's whole line_items[] element, so
--     V149's generated net_unit_price is price − Σ discount_allocations / quantity (line AND order-level
--     discounts — Shopify allocates both onto the line).
--   * GraphQL-imported lines and portal pre-connect lines have no REST price. Since F0 their raw carries
--     'paid_unit_price': Shopify's LineItem.discountedUnitPriceAfterAllDiscountsSet.shopMoney.amount (a
--     unit price after every discount), stored as Shopify's decimal text. Never written to raw->>'price':
--     V149's generated analytics columns read that key.
--
-- order_line_paid_unit_price(oi) = the REST net price when there is one, else the paid_unit_price key,
-- floored at 0; NULL when neither exists (the Java resolver then reads Shopify live, outside any
-- transaction, and finally falls back to the catalogue price, marked approximate). Every reader of a
-- paid price uses this function — never re-derive it.
--
-- STABLE, not IMMUTABLE: it takes the order_items row type. INVOKER (not a hatch); reads nothing but its
-- argument. No table is touched, no backfill.

CREATE FUNCTION order_line_paid_unit_price(oi order_items) RETURNS numeric
    LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN p IS NULL THEN NULL WHEN p < 0 THEN 0 ELSE p END
    FROM (SELECT COALESCE(oi.net_unit_price, analytics_num(oi.raw ->> 'paid_unit_price')) AS p) x
$$;
-- EXECUTE comes from PUBLIC's default, like V144's and V149's INVOKER functions.
