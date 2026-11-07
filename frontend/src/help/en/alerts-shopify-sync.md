---
id: alerts-shopify-sync
topic: alerts
title: Shopify stock and connection alerts
roles: [owner, manager]
related: [alerts-overview, shopify-stock-mismatch, connect-shopify, transfers-shopify]
alerts: [shopify_connection_lost, shopify_location_inactive, warehouse_address_push_failed, inventory_increment_sync_failed, restocked_twice, transfer_stock_not_removed, delivered_not_fulfilled_in_shopify, void_hold_sync_failed]
screens: [/exceptions, /settings?tab=connections]
video:
---
These alerts mean Traced's stock and Shopify's "Traced Main Warehouse" count may not agree, or that Traced can't reach Shopify at all. Fix the cause first; Traced never guesses a correction in Shopify for you.

## Shopify Connection Lost

Shopify stopped accepting Traced's access to your store. Stock updates wait. Reconnect in **Settings › Connections**; the alert clears by itself and the waiting updates are sent then.

## Traced Location Inactive In Shopify

The "Traced Main Warehouse" location was deactivated or deleted in Shopify, so stock updates are on hold. Reactivate it in Shopify; it clears by itself (checked every 10 minutes, or check now in **Settings › Connections**). If it was deleted, reconnect Shopify or contact support.

## Warehouse Address Not In Shopify

Traced couldn't copy your warehouse address to its Shopify location yet. It keeps retrying and the alert clears by itself; act only if it stays, by saving the address again.

## Stock Not In Shopify

Stock you received or restocked in Traced didn't reach Shopify. If the alert names a fix (reconnect Shopify, link your warehouse to a Shopify location), do that and the waiting updates go out. If it says the updates couldn't be sent after retrying, check those variants in Shopify, correct them by hand, then resolve.

## Restocked Twice

Traced already added returned units to Shopify, then a Shopify refund or return restocked them again. Lower that item's quantity in Shopify by the number shown, then resolve.

## Transfer Stock Still In Shopify

Pieces left the main warehouse on a transfer, but Shopify still counts them there. Lower them in Shopify by hand, then resolve.

## Delivered But Not Fulfilled In Shopify

The order was delivered more than 7 days ago but is still unfulfilled in Shopify, so Shopify keeps its stock committed. Mark it fulfilled in Shopify (or cancel it if it didn't go through); the alert clears by itself.

## Piece stock update failed in Shopify

A Critical alert about one piece (a hold, void, damage, sale or write-off from Lookup, or an exchange replacement) or a transfer: Shopify refused the update or never confirmed it. Check that item's quantity in Shopify before doing anything, correct it if needed, then resolve. An unconfirmed update is never sent again automatically, so it can't count twice.
