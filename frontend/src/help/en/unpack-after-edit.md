---
id: unpack-after-edit
topic: pack
title: Unpack an order that changed in Shopify
roles: [owner, manager]
related: [orders-edited-in-shopify, alerts-packing, hand-over-parcels]
alerts: []
screens: [/fulfill]
video:
---
If an order changes in Shopify after you packed it, open the **Packed** list in Pick & Pack and press **Unpack** on that order. Its pieces go back to stock and the order goes back to Pick & Pack, to be packed again with its current items. It is not cancelled.

A packed order that was edited in Shopify shows **Edited in Shopify** in the Packed list, and its alert in **Alerts** has an **Unpack order** button that takes you to the same place.

1. Open **Pick & Pack**. In order queue mode, open the **Packed** tab; in waybill scan mode, scroll to **Packed, not picked up**.
2. Press **Unpack** on the order.
3. Choose why: **Customer edited the order**, **Packed the wrong item** or **Other** (Other needs a short note).
4. Press **Unpack order**, take the pieces out of the parcel and put them back on the shelf.
5. Pack the order again. Keep the waybill — the same waybill opens the order again.

## When Unpack is refused
Nothing changes, and Traced tells you why:
- The order has already left with the courier.
- Its waybill is in an open pickup session. Press **Open the pickup session**, remove the waybill there, then unpack.
- Its waybill was cancelled or deleted at the courier. Sort out the waybill first.
- Another packer has the order open right now.
- The order is being cancelled (see below).

## The amount changed
If the order's total changed, the Packed list says so, for example "Bosta still has 450 EGP, the order is now 380 EGP". Traced doesn't change Bosta — update the cash on delivery amount in Bosta yourself.

## Cancelled packed orders
When a packed order is cancelled, Traced raises the **Unpack Required** alert and keeps the order packed until each of its pieces is unpacked one by one with **Unpack**. Once the last piece is unpacked, the order is cancelled and the alert clears.
