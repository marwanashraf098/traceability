---
id: exchanges-other-couriers
topic: exchange
title: Exchanges with other couriers
roles: [owner, manager]
related: [other-couriers, pack-other-couriers, exchanges-bosta, lookup-sold-exchange]
alerts: []
screens: [/settings?tab=portal, /exchanges]
video:
---
For orders you shipped with a courier other than Bosta, Traced doesn't book anything. You approve the exchange, book it with your courier, and enter the replacement's waybill so it goes to Pick & Pack.

## Turn it on

1. Traced needs to know which orders went out with other couriers: turn on **I ship with other couriers** in **Settings › Couriers**.
2. In **Settings › Returns portal**, the owner turns on **Accept returns for orders shipped with other couriers**.
3. Turn on **Allow exchanges** and tap **Save changes**.

Customers can then ask to exchange an item from a delivered order shipped with another courier for another size or colour of the same product.

## Handle a request

1. Open the request in **Returns & exchanges** and tap **Approve exchange**. Traced creates the replacement order — no Bosta booking.
2. Book the exchange with your courier.
3. In the request, choose the **Courier** and enter the **Replacement waybill (going out)**. You can also enter the **Return waybill (old item)**, if your courier gave you one — it must be a different number.
4. Tap **Save and send to Pick & Pack**. The replacement is now in Pick & Pack: pack it by scanning its waybill and hand it over in **Pickups**.
5. When the old item arrives, scan it in **Scan returns** (or, without a return waybill, scan the item's piece) and grade it. The request then shows **Exchanged**.

## Shopify stock

When the replacement leaves your warehouse, Traced takes 1 off "Traced Main" in Shopify. Restocking the old item adds 1 back.

## Exchanges that didn't come through the portal

To exchange a piece for a customer yourself — for example over WhatsApp — use **Exchange** in Lookup. See [Mark a piece Sold or Exchange in Lookup](/help/lookup-sold-exchange).
