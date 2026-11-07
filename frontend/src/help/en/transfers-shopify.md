---
id: transfers-shopify
topic: stock
title: Do transfers lower my Shopify stock?
roles: [owner, manager]
related: [transfers, shopify-stock-mismatch, alerts-shopify-sync]
alerts: []
screens: [/settings?tab=locations, /transfers]
video:
---
It depends on the destination's setting. With Remove from Shopify (the default), pieces leaving your main warehouse are taken off the Traced Main Warehouse location in Shopify and added back when they return; with Leave Shopify unchanged, Shopify doesn't change.

## Where to set it
Go to **Settings** › **Locations**. Every location other than your main warehouse has a **While stock is here** choice:
- **Remove from Shopify**: stock sent there can't be bought online while it is away.
- **Leave Shopify unchanged**: customers can still buy it online while it is away. **Tip:** this suits stock you'll bring back quickly.

Your main warehouse always shows **Always counted in Shopify**. Owners and managers can change the setting.

## When Shopify changes
- **Send out and back**: when you press **Mark as sent**.
- **Move to another location**: when you press **Close**.
- Only pieces that leave the main warehouse count. **Bring back** never takes anything off, because the stock is coming from another location.
- When a piece is scanned back **Good** at the main warehouse, it is added back — but only if its removal reached Shopify.
- Pieces recorded as sold, lost or condemned at the destination change nothing more: they were already taken off when they left.

The setting is fixed for a transfer when it is sent. Changing it later applies to the next transfers, not to stock already away, so the removal and the return always match.

## Only one Shopify location
Traced only ever writes to its own Traced Main Warehouse location in Shopify. It never adds stock to a Shopify location for your showroom or any other place, and never changes your other Shopify locations.

If an update can't be sent, you'll see it in **Alerts** — see [Shopify stock sync alerts](/help/alerts-shopify-sync).
