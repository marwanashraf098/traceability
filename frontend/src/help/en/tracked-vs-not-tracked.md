---
id: tracked-vs-not-tracked
topic: orders
title: Tracked and not tracked orders
roles: [owner, manager]
related: [hand-over-parcels, orders-tabs, stock-take]
alerts: []
screens: [/orders]
video:
---
A Bosta order gets the **Not traced** tag when Bosta reports its shipment moving but none of its pieces were ever scanned in Pick & Pack. Traced then can't tell which pieces left, so it still counts them as on your shelf.

## When Traced adds the tag
Traced checks a Bosta order each time Bosta sends an update for its delivery. If the update is anything past the waybill being created — picked up, delivered, returned or cancelled — and no piece of the order was scanned in Pick & Pack, the order is tagged **Not traced**.

It usually means the parcel was packed by hand, outside Traced, and given to the courier.

An order whose pieces came back through **Scan returns** is not tagged.

## Find these orders
- The **Not traced** tag shows on the order in **Orders**.
- Press **Filters** and choose **Traced › Not traced** to list them. Choose **Traced** to see the others.
- The tag is for Bosta orders only, and it stays on the order.

## Why it matters
The pieces that went out were never taken out of Traced's stock, so Traced shows more stock for those products than you really have. A [stock take](/help/stock-take) brings the numbers back in line.

The tag is only shown in Traced. It isn't sent to Shopify and it doesn't create an alert.

## How to avoid it
1. Pack every Bosta order in **Pick & Pack** before the courier comes.
2. Hand the parcels over in **Pickups** — see [Hand parcels to the courier](/help/hand-over-parcels).
