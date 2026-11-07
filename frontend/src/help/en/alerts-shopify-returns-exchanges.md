---
id: alerts-shopify-returns-exchanges
topic: alerts
title: Alerts for returns and exchanges made in Shopify
roles: [owner, manager]
related: [scan-returns, alerts-returns, alerts-shopify-sync]
alerts: [shopify_exchange_short, shopify_exchange_still_waiting, shopify_exchange_shipped_in_shopify, shopify_return_open_after_intake, shopify_return_ended_after_intake]
screens: [/exceptions, /fulfill, /returns]
video:
---
When a return or exchange is created in Shopify itself, Traced follows it: it keeps the replacement piece aside and lets you scan the returned item in **Scan returns**. These alerts tell you when Traced and Shopify see that return or exchange differently.

## Short For A Shopify Exchange

You don't have enough stock of an item for the exchanges made in Shopify that Traced is keeping aside. The oldest exchanges are covered first, so the newest ones wait. Receive more stock. This alert has no **Resolve** button: it clears by itself once your stock covers them.

## Shopify Exchange Still Waiting

Traced has kept a replacement aside for more than 7 days, but can't see why Shopify hasn't released the exchange for fulfilment (its fulfilment may be at a location Traced can't read). Check the exchange in Shopify. If it won't go out from your warehouse, open it in **Pick & Pack** and use **Release reservation**. It clears by itself once Shopify releases it or you release the piece.

## Exchange Shipped In Shopify

Shopify marked the exchange fulfilled without Traced packing it, while the piece is still available in Traced. Traced stopped keeping it aside. If the piece really left your warehouse, adjust it in **Lookup**; then resolve.

## Received, Return Still Open In Shopify

The item came back through **Scan returns** more than 7 days ago, but the return is still open in Shopify. Process it in Shopify (refund or restock) or close it there. It clears by itself once Shopify processes it.

## Shopify Return Ended After Arrival

The return was declined or cancelled in Shopify after its item had already arrived in Traced. Traced didn't move anything. Decide what happens to the piece (keep it, or send it back to the customer), then resolve.
