---
id: shopify-stock-mismatch
topic: stock
title: Shopify stock doesn't match Traced
roles: [owner, manager]
related: [old-location-zero, alerts-shopify-sync, stock-take, transfers-shopify]
alerts: []
screens: [/inventory, /settings?tab=connections]
video:
---
Traced only changes stock at its own Traced Main Warehouse location in Shopify, and only by adding or removing units when something happens in Traced. Most differences come from another Shopify location, orders that are committed, or an update that hasn't reached Shopify yet.

Check these causes in order.

## 1. You're looking at another Shopify location
Traced never writes to your other Shopify locations. Whatever they hold stays there and is sold alongside the Traced Main Warehouse. If that stock is also in Traced, it is counted twice. Set the old location to 0 — see [Set your old Shopify location to zero](/help/old-location-zero).

## 2. You're comparing available with on hand
Shopify's "available" leaves out units committed to open orders. Compare Shopify's on hand at the Traced Main Warehouse with Traced's count at your main warehouse. **Inventory** › **Stock by location** shows **On hand**, **Committed** and **Available** for each product.

## 3. An update hasn't reached Shopify
The **Shopify** column in Inventory shows **Synced**, **Pending** or **Failed**. Failed updates appear in **Alerts**. Some updates are never re-sent on their own, because Traced can't tell whether Shopify applied them — check Shopify and follow the alert. See [Shopify stock sync alerts](/help/alerts-shopify-sync).

## 4. The Traced Main Warehouse is turned off in Shopify
Updates wait until it is active again. Reactivate it in Shopify (Settings › Locations). The owner can then press **Re-send failed updates** in **Settings** › **Connections**.

## 5. Stock was changed by hand in Shopify
Don't edit stock at Traced Main by hand in Shopify. Traced keeps that location matching its own count, so hand edits may be undone. Make changes in Traced instead: receiving, adjustments in Lookup, or a stock take.

## 6. Traced's own count is off
If the shelf doesn't match Traced, run a [stock take](/help/stock-take). It writes off what's missing and brings back lost pieces it finds, and Shopify follows.

Still stuck? [Contact support](/help/contact-support).
