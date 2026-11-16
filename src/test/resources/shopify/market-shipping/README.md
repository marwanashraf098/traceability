Live 2026-07 responses from the test store testl1location (market-driven shipping), read once,
read-only, 2026-11-16. Sanitised: Shopify ids replaced by stable fakes, cursors removed.

Locations: 1001 Shop location · 1002 My Custom Location · 1003 Snow City Warehouse (fulfillment
service, not returned by `locations` without includeLegacy) · 1004 Traced Main Warehouse.
Markets: 2001 Canada (option "International Shipping", no location) · 2002 United States
(Standard, Express → 1001 only; "L1 test - all locations" → 1001-1004 listed explicitly;
"L1 test - Traced Main only" → 1004).

coverage-live.json — the production query (ShopifyShippingModelGateway.MARKET_COVERAGE_QUERY) as run live:
shop country US, United States is the only active market listing US (Shopify's deprecated Market.primary agreed:
primary-live.json). Option ids match markets-live.json.
