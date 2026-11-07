---
id: old-location-zero
topic: start
title: Why you set old Shopify locations to 0
roles: [owner, manager]
related: [connect-shopify, receiving-modes, receive-and-label, shopify-stock-mismatch]
alerts: []
screens: [/receiving, /settings?tab=connections]
video:
---
Traced only ever changes stock at its own **Traced Main Warehouse** location in Shopify, and receiving ADDS to it. If the same items are still counted at your old Shopify location, Shopify counts them twice — so set the old location to 0 for those items before you receive them in Traced.

## Why it happens

Shopify adds up the stock of every location that sells online. When you receive 10 pieces in Traced, **Traced Main Warehouse** goes up by 10. Traced never lowers, zeroes or corrects any other location. If your old location still shows those 10, customers can buy 20 — and you oversell.

## Bring your existing shelf stock into Traced

1. Pick the products you'll bring in first.
2. In Shopify, set those products' quantity at your old location to 0. Don't move them to Traced Main Warehouse in Shopify — Traced adds them itself when you receive.
3. In Traced, open **Receiving** and receive the pieces you have on the shelf. When you close the session, each piece is added to Traced Main Warehouse, so the stock customers can buy comes back up by the same number.
4. Repeat product by product. Doing it right before each receiving keeps the time a product shows as sold out short.

Any of the three receiving modes adds to Shopify the same way — see [Receiving modes](/help/receiving-modes).

## Stock Traced doesn't manage

Stock you keep somewhere Traced doesn't track (a second shop, for example) can stay as it is. Traced never touches other locations — just don't receive those items in Traced as well.

## Activate fulfillment

In **Settings › Connections**, the Shopify card may show **Activate Traced as a fulfillment location** (owner only). Only tick its confirmation and tap **Activate fulfillment** once your old locations are at 0 for the stock you've moved: after that, Traced Main Warehouse sells alongside any other active location.
