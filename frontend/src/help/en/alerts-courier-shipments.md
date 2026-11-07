---
id: alerts-courier-shipments
topic: alerts
title: Courier and shipment alerts
roles: [owner, manager]
related: [alerts-overview, connect-bosta, other-couriers, hand-over-parcels]
alerts: [lost, unmatched_delivery, bosta_discovery_failed, fulfillment_link_problem, stuck_shipment, delivery_limbo, ndr_failed, high_attempts, waybill_deleted_in_bosta, unexpected_return, generic_bosta_conflict, generic_multiple_parcels, generic_delivered_without_pickup]
screens: [/exceptions, /orders]
video:
---
These alerts are about parcels once they leave your shelf: a delivery Traced can't connect to an order, a shipment that stopped moving, or a failed delivery. Most need a check in Bosta (or with your other courier) and then **Resolve**.

## Lost

A piece is marked lost: because the courier reported it, or because a stock take or a **Lookup** adjustment wrote it off. If the courier had it, check with Bosta; resolve once you've accepted the loss. If the piece turns up, restore it in **Lookup**.

## Unmatched

A Bosta delivery couldn't be connected to any order, or it belongs to an order that already has another Bosta waybill. Look the tracking number up in Bosta. If an order has two waybills, cancel the one that isn't shipping.

## Bosta Fetch Failed

Traced saw a Bosta delivery but couldn't load it after several tries. It clears by itself once the delivery reaches Traced; check it in Bosta if it stays.

## Bosta Link Needed

Shopify says the order shipped with a Bosta tracking number, but Traced won't link it (the number points to another order, or Bosta can't find it). Check the number in Bosta. It clears once the order has a shipment with that number.

## Stuck

A shipment has had no courier update for several days. Ask Bosta where it is. It clears when Bosta sends a new update.

## Limbo

A return delivery failed several times and is waiting at Bosta's hub. Ask Bosta to retry or cancel the return.

## NDR Failed

Bosta tried to deliver and failed; the reason Bosta gave is shown. Contact the customer.

## Many delivery attempts

The shipment has had two or more delivery attempts; the customer may be unreachable. Contact them.

## Waybill Deleted in Bosta

The waybill was deleted in Bosta after its pieces left the shelf. Find the parcel and book a new waybill. It becomes Critical once a piece is with the courier.

## Unexp. Return

A piece was scanned back in Scan returns without Bosta reporting a return. Inspect it, then resolve.

## Bosta waybill on another courier's order

A Bosta waybill turned up for an order already shipped with another courier. Traced didn't link it. Check which courier really has the parcel.

## More than one parcel in Shopify

Shopify shows more than one parcel for an order shipped with another courier. Traced tracks only the first; check the others yourself.

## Delivered without a pickup scan

Shopify says another courier delivered the order, but its parcel was never scanned out at **Pickups**. Traced recorded the handover itself; scan parcels out next time. If the order wasn't packed, nothing moved: pack it, or check the fulfillment in Shopify.
