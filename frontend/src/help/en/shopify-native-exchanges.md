---
id: shopify-native-exchanges
topic: exchange
title: Exchanges made in Shopify by another returns app
roles: [owner, manager]
related: [alerts-shopify-returns-exchanges, scan-returns, exchanges-bosta, connect-shopify]
alerts: []
screens: [/fulfill, /exchanges]
video:
hidden: true
---
When a customer's exchange is created in Shopify (by Shopify itself or another returns app), Traced keeps the replacement item aside for it and lets you pack it in Pick & Pack. This needs Traced's newer Shopify permissions, which the owner approves in Settings › Connections.

## Before it works

If your store hasn't approved them yet, you'll see **Approve Traced's new Shopify permissions** in **Settings › Connections**. Until then, Traced doesn't see returns or exchanges made in Shopify.

## What happens

1. Once Shopify approves the exchange, Traced creates an exchange order with the original's number plus "-X1" (for example #1047-X1) and keeps the new item aside in your stock, so it isn't promised to another order.
2. While Shopify still holds the exchange, the order waits under **Waiting for release** in Pick & Pack. Keep the piece aside.
3. Once Shopify releases it, the exchange order joins the Pick & Pack queue, marked **Exchange for #1047**. Pack it and hand it over in **Pickups** like any order, once its courier waybill is linked.
4. When the old item comes back, scan it in **Scan returns** and grade it.

If you don't have enough stock, the order shows **Short by** and the number of missing pieces.

## Where you see it

The original order shows the Shopify return under **From Shopify**, with the item coming back and the one going out. These returns are read-only in Traced — you manage them in Shopify, and Traced books no courier for them.

## Release reservation

If an exchange is waiting and won't go ahead, an owner or manager can tap **Release reservation** on it in **Waiting for release**. Traced stops keeping the piece aside. This can't be done once a piece has been scanned for it.
