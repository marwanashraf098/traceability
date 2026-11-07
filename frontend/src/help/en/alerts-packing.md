---
id: alerts-packing
topic: alerts
title: Packing alerts
roles: [owner, manager]
related: [set-aside, cancelled-after-print, unpack-after-edit, alerts-cancelled-orders]
alerts: [guided_unpack, pack_set_aside, pack_cancelled_after_print, blocked_customer, shopify_edit_conflict, shipped_outside_traced]
screens: [/exceptions, /fulfill]
video:
---
These alerts come from **Pick & Pack**: an order that changed or was cancelled after packing started, an order a packer set aside, or an order held for a blocked customer. Each one tells you what physical step is needed before the order can go out.

## Unpack Required

The order was cancelled, but its pieces are already packed. Open the order (**Go →**) and unpack the pieces so they go back into stock. This alert has no **Resolve** button: it clears by itself once the pieces are unpacked and back in stock.

## Set Aside While Packing

A packer set the order aside during a waybill session and gave a reason: piece missing on the shelf, damaged piece, waybill damaged or unreadable, or another reason. Sort out the problem (find or replace the piece, reprint the waybill), and the order can be packed again. The alert clears when the order is packed or cancelled; you can also resolve it.

## Cancelled After Print

The order was cancelled after its waybill was printed in a batch. The printed label must not be packed or handed to the courier. Find the printed waybill, throw it away, then **Resolve** the alert to mark it discarded.

## On Hold

The order is on hold because the customer matches your blocklist, the order came in with a product Traced doesn't have in its catalog, or someone pressed **Hold order**; the reason is shown. Decide from the alert: **Release (ship anyway)** lets it continue to packing, **Cancel order** cancels it.

## Edited In Shopify After Packing

The order's items were changed in Shopify after it entered packing. Open the order and compare what's packed with what the customer now ordered. If the order is packed and still in your warehouse, the alert offers **Unpack order** so you can repack it correctly. Resolve once the parcel matches the order.

## Shipped Outside Traced

Pieces were scanned for this order in Pick & Pack, but the order was never completed there: it shipped another way (the Bosta app made the waybill and Shopify marked it fulfilled). Traced still holds the pieces, so they are missing from your stock and from the returns portal. Open the order (**Go →**) and choose **Shipped outside Traced**: the pieces are marked as with the courier (or delivered, if the courier says so), like any shipped order. Nothing changes in Shopify. The alert clears once the pieces are moved.
