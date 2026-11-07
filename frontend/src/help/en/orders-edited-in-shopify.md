---
id: orders-edited-in-shopify
topic: orders
title: Items removed or changed in Shopify
roles: [owner, manager]
related: [unpack-after-edit, alerts-packing, alerts-cancelled-orders]
alerts: []
screens: [/orders, /fulfill]
video:
---
When an order is edited in Shopify, Traced follows the new items until the order leaves your warehouse. After that, the parcel's contents are fixed. What you need to do depends on how far packing got.

## Not started yet
Traced just updates the items. Nothing else to do.

## Being picked
- Pieces already scanned for an item that was removed or reduced are released back to stock. Put them back on the shelf.
- A removed item can't be scanned: Traced says "This item was removed from the order in Shopify".
- If every item was removed, Traced says there is nothing to pack.
- An alert in **Alerts** says Shopify edited the order, and the order shows under **Needs attention** on **Orders**.

## Packed, still in the warehouse
Traced doesn't touch the packed parcel. The order shows **Edited in Shopify** in Pick & Pack's packed list, and an alert appears. Unpack it and pack it again — see [Unpack an order that changed in Shopify](/help/unpack-after-edit). If the total changed, update the cash on delivery amount in Bosta yourself; Traced doesn't change Bosta.

## Already with the courier
The parcel's contents don't change. A lower quantity in Shopify is treated as a refund, not a packing change, and no alert is raised. An item added in Shopify after shipping shows **Added after shipping — not in this parcel** in the order's details.

## In the order's details
On **Orders**, open the order: an item taken off in Shopify is marked **Removed in Shopify**.

## Cancelled orders
- Cancelled before packing: the reserved pieces go back to stock.
- Cancelled after packing: the pieces must be unpacked one by one — see [Unpack an order that changed in Shopify](/help/unpack-after-edit).
- Cancelled while with the courier: Traced can't cancel it and raises an alert — see [Cancelled order with a live shipment](/help/alerts-cancelled-orders).
