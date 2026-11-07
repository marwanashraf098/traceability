---
id: order-not-in-pick-pack
topic: pack
title: Why isn't my order in Pick & Pack?
roles: [owner, manager]
related: [connect-bosta, other-couriers, orders-before-connect, pack-other-couriers]
alerts: []
screens: [/fulfill]
video:
---
An order shows in Pick & Pack only while it's open, not on hold, a recent order and — unless it's a self-pickup order — has a courier waybill that hasn't moved yet. Stock doesn't matter: an order still shows when you have no pieces for it.

Check these, in order:

1. **It has no waybill yet.** A courier order appears only once its delivery exists. For Bosta, create the delivery in Bosta (usually through the Bosta app in Shopify); the order appears once Traced links it. When the list is empty, Pick & Pack tells you how many orders are waiting for a Bosta waybill, and offers **Connect Bosta** if Bosta isn't connected. For another courier, the tracking number must be on the order's Shopify fulfillment — see [Pack orders for other couriers](/help/pack-other-couriers).
2. **Its waybill already moved, or was cancelled.** Once the courier has the parcel, or the delivery was cancelled in Bosta, the order leaves the list. A cancelled delivery needs a new one in Bosta.
3. **It's already packed.** Look under the **Packed** tab.
4. **It's cancelled.**
5. **It's on hold.** An order is held when the customer is on your blocklist, when it came in with a product Traced didn't have in its catalog, or when someone pressed **Hold order** in its details on **Orders**. Press **Release hold** there once it's sorted out.
6. **It isn't a recent order.** Pick & Pack only lists recent orders.
7. **It was placed before you connected Shopify.** Traced doesn't bring in older orders — see [Orders placed before you connected](/help/orders-before-connect).

## Good to know
- An order marked **Locked** is open with another packer right now. It's still in the list.
- An exchange made in Shopify waits under **Waiting for release** until Shopify releases it for fulfilment, then moves into the list.
