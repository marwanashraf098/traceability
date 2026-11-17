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

coverage-egypt-uncovered.json — the same store on 2026-11-17 after its country moved to Egypt (live test, reported by
Marawan; built from coverage-live.json's shape, not a second capture): shop country EG; markets Egypt (2003, shipping
on, one active rate "Bosta Egypt" → 1001 Shop location only), Canada (unchanged), United States (Standard, Express → 1001
only; the two L1 test rates removed). Traced Main (1004) is in no active rate of any market, and every market shows
"Parent market: Store default" in admin (its shipping block is the market's own — not inherited). Expected: NOT_COVERED,
"Not added for: Egypt, Canada, United States". Ticking 1004 on "Bosta Egypt" → COVERED "Egypt — Bosta Egypt".
