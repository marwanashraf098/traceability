---
id: inventory-value
topic: analytics
title: Inventory value and unit cost
roles: [owner]
related: [booked-vs-realized, bosta-owes, piece-statuses, receive-and-label]
alerts: []
screens: [/analytics/stock]
video:
---
**Stock health** in **Analytics** values the pieces on your shelves two ways: at selling price, and at cost. The cost comes from the **Cost per item** you set on each variant in Shopify; Traced reads it from there.

## The two values

1. **Stock value at selling price** multiplies every available piece Traced tracks by its variant's current price: what your stock sells for.
2. **Stock value at cost** multiplies the same pieces by their unit cost: the cash tied up in stock. Only variants that have a cost count, and the card says how many: "costed 40 of 52 SKUs", for example.

Only pieces that are available in your warehouses count. Pieces that are reserved, packed, with the courier, on hold, damaged or lost don't.

## Getting unit costs in

1. In Shopify, open each product variant and fill in **Cost per item**.
2. Traced reads costs from Shopify once a day, and again when a cost changes in Shopify. You don't enter costs in Traced.
3. Only costs in EGP are used.

If nothing is costed, the card says why: no cost per item in Shopify yet, Shopify didn't let Traced read costs (reconnect the store with inventory access), or the last read failed and costs will appear after the next successful one.

## Where else cost is used

- **Dead stock** (no sale in 60 days) shows the cash sitting in each item, at cost when it has one, otherwise at selling price.
- Margins and profit on **Revenue** and **Products & SKUs** use the same costs, for costed items only.

## Things to know

- These figures need pieces in Traced. With no pieces yet, the page asks you to receive stock first.
- If only a few of your orders are packed through Traced, the page warns you and some stock figures use Shopify's count instead.
- Analytics is for the account owner only.
