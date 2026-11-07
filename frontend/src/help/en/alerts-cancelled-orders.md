---
id: alerts-cancelled-orders
topic: alerts
title: Cancelled order with a live shipment
roles: [owner, manager]
related: [alerts-overview, cancelled-after-print, cancelled-waybills-pickup, alerts-packing]
alerts: [cancelled_live_shipment, cancelled_but_delivered, shopify_cancel_vs_inflight, generic_cancelled_live_shipment, generic_fulfillment_cancelled]
screens: [/exceptions, /orders]
video:
---
Cancelling an order in Shopify or Traced doesn't cancel its waybill at the courier. These alerts tell you when a cancelled order still has a parcel or waybill out there, so a courier doesn't collect or deliver something that shouldn't go.

## Cancelled · Live Shipment

The order is cancelled but its Bosta waybill is still active. Cancel the waybill in Bosta so the courier doesn't collect it. This alert has no **Resolve** button: it clears by itself when Bosta reports the delivery cancelled or delivered, or when Traced confirms it was deleted in Bosta (checked every few hours).

## Cancelled · Delivered

The order was cancelled, but the courier delivered it anyway. Settle the cash on delivery with Bosta or arrange a return with the customer, then **Resolve**. Delivered is final, so this alert never clears by itself.

## Shopify cancelled, parcel still out

The order was cancelled in Shopify, but Traced couldn't cancel it because its parcel is already on the way with the courier. Talk to Bosta: have the parcel returned to you, or cancel the waybill if it hasn't been collected yet. Then **Resolve**. If the courier delivers it anyway, it becomes **Cancelled · Delivered**.

## Cancelled order, other courier's parcel still out

For orders you ship with another courier: the order is cancelled, but its parcel is still with the courier or its waybill is still live. Traced can't cancel it at that courier, so tell them yourself (ask them to bring it back, or make sure they don't collect it). It clears once the shipment ends, or when you resolve it.

## Other courier's fulfillment cancelled in Shopify

The courier's fulfillment was cancelled in Shopify after the order was already packed. Traced kept its shipment, because something physical was packed. Check with your team and the courier what really happened to the parcel, then resolve. It also clears once the shipment ends.
