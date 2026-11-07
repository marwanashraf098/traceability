---
id: alerts-tracking-changes
topic: alerts
title: Tracking number changed in Shopify
roles: [owner, manager]
related: [alerts-courier-shipments, reprint-labels, hand-over-parcels, orders-edited-in-shopify]
alerts: [tracking_changed_after_custody, tracking_change_needs_review, tracking_changed_relabel, superseded_waybill_moving]
screens: [/exceptions, /orders]
video:
---
When someone changes an order's tracking number in Shopify, Traced follows the new number only when it's safe. If it isn't, Traced keeps the old number and raises one of these alerts so a person can check which waybill is really on the parcel.

## Waybill Changed After Pickup

The tracking number was changed in Shopify after the parcel left the warehouse, or while it was in an open pickup session. Traced kept the old number, because the parcel is already out under it. Check with the courier which waybill they have, and fix Shopify if it's wrong. Then resolve.

## Shopify Shows A Different Waybill

Shopify now shows a different number for the order, and Traced kept the old one because:

1. the new number already belongs to another shipment, or
2. the old Bosta waybill is still live in Bosta. Cancel it in Bosta and Traced switches to the new one, or
3. Shopify shows more than one new number, so Traced didn't guess.

Check the order in Shopify and in Bosta. The alert clears by itself if Shopify goes back to the number Traced kept; you can also resolve it.

## Put The New Waybill On The Parcel

Traced switched to the new number, but the parcel is already packed or printed with the old waybill. Print the new waybill and put it on the parcel before it goes out. It clears by itself once the parcel has left with the courier, has been unpacked, or the waybill was reprinted with the new number.

## Old Waybill Still Moving At Bosta

Shopify replaced the order's Bosta waybill, but the old one is moving at Bosta anyway, so the courier may have the parcel under the old waybill. Check with Bosta which parcel the courier has. It clears by itself once Bosta shows the old delivery ended or deleted.

For the first three alerts, a resolved alert comes back only if the tracking number changes again.
