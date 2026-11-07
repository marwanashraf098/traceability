---
id: alerts-overview
topic: alerts
title: What each alert means
roles: [owner, manager]
related: [alerts-shopify-sync, alerts-courier-shipments, alerts-returns, alerts-packing]
alerts: []
screens: [/exceptions]
video:
---
**Alerts** lists everything Traced thinks needs a person: a parcel that stopped moving, stock that didn't reach Shopify, a return nobody scanned. Each alert says what happened and what to do; you either fix it and press **Resolve**, or it clears on its own once the problem is gone.

## Reading the page

1. The four tiles at the top count open alerts by severity: **Critical**, **High**, **Medium** and **Low**. Click a tile to show only that severity; click it again to show all.
2. Use **All severities** and **All types** to filter the list. **Refresh** reloads it.
3. Each alert shows its severity, its type, how long it has been open, and the order, tracking number or barcode it is about.
4. **Go →** opens the screen where you fix it (the order, Receiving, Scan returns, Settings and so on).

Critical and High are about stock or money that is wrong right now (Shopify selling a piece you don't have, a parcel at risk). Medium and Low can wait for your daily check.

## Resolving

Press **Resolve** once you've dealt with it. You can add a note; it's optional. A resolved alert disappears, but some come back if something new happens to the same order or parcel (for example, a stuck shipment that gets a new Bosta update and then stops again).

## Alerts that clear on their own

Some alerts have no **Resolve** button. Instead they show a line saying what clears them, for example "Clears when you reconnect Shopify in Settings › Connections". These follow live state: Traced removes them by itself as soon as the cause is gone. Examples: a lost Shopify connection, a deactivated Traced location in Shopify, a cancelled order whose Bosta waybill is still active, and a cancelled order that still has packed pieces.

## Emails

Owners and managers get an email shortly after a new Critical or High alert appears. Every morning at 8:00 (Cairo time) they also get one summary of all open alerts. Workers don't see Alerts and get no alert emails.
