---
id: other-couriers
topic: start
title: Ship with a courier other than Bosta
roles: [owner, manager]
related: [pack-other-couriers, exchanges-other-couriers, connect-bosta, hand-over-parcels]
alerts: []
screens: [/settings?tab=couriers, /orders]
video:
---
Turn on **I ship with other couriers** in **Settings › Couriers**. Traced then takes the courier's tracking number from the order's fulfillment in Shopify, so you can pack the order by scanning that courier's waybill.

## Switch it on

1. Open **Settings › Couriers** (the owner switches it on; managers can see it).
2. Turn on **I ship with other couriers**.
3. Set a **Default courier name** — the courier you use most.
4. Choose whether to also include open orders whose tracking was added in the last few days. Otherwise Traced starts from now: orders whose tracking is added to Shopify after you save.

Bosta stays your main courier: an order with a Bosta waybill is always a Bosta order.

## How it works day to day

1. Fulfill the order in Shopify with the courier's tracking number, as you do today.
2. The order shows in **Pick & Pack**. Pack it by scanning that courier's waybill.
3. Hand parcels over in **Pickups**. A pickup session holds one courier: a parcel for another courier is refused, so keep it for that courier's pickup.

## How Traced knows it was delivered or returned

There's no direct link to these couriers, so Traced learns it like this:

- **Delivered** — when the Shopify fulfillment shows as delivered, or when an owner or manager marks it by hand: in **Orders**, choose the courier in the **Courier** filter, tick the orders and tap **Mark delivered**. It's recorded under your name.
- **Returned** — when the parcel comes back and you scan its waybill or one of its pieces in **Scan returns**.

To check what Traced will do with a waybill, scan it under **Test a waybill** on the same settings page. Nothing is saved.

Turning the switch off stops new orders being added. Orders already added stay and can still be packed.
