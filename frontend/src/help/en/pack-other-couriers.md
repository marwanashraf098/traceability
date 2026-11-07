---
id: pack-other-couriers
topic: pack
title: Pack orders for other couriers
roles: [owner, manager]
related: [other-couriers, pack-modes, hand-over-parcels]
alerts: []
screens: [/fulfill, /settings?tab=couriers]
video:
---
If some orders ship with a courier Traced isn't connected to (Wijha, Mylerz, J&T…), turn on **I ship with other couriers** in **Settings › Couriers**. Traced then takes the tracking number from the order's Shopify fulfillment, and you pack the order by scanning that courier's waybill.

1. As the owner, open **Settings › Couriers**, turn on **I ship with other couriers** and enter the **Default courier name** — the courier you use most.
2. Traced starts from now: it picks up orders whose tracking number is added in Shopify after you save. To bring in recent ones too, choose to include open orders whose tracking was added in the last few days (up to 7). Traced shows how many orders that adds before you save.
3. Add the courier's tracking number to the order's fulfillment in Shopify. The order then appears in **Pick & Pack**.
4. Pack it. In order queue mode, scan the pieces, press **Complete & Pack Order** and scan the courier's waybill. In waybill scan mode, scan the courier's waybill to open the order, then its pieces.
5. Hand it over in **Pickups**. A pickup session holds one courier — see [Hand parcels to the courier](/help/hand-over-parcels).

## Test a waybill
In **Settings › Couriers**, **Test a waybill** shows what Traced will do with a waybill you scan. Nothing is saved. If you scan with a phone camera: it reads Code 128 and QR barcodes only. If the phone doesn't beep on a waybill, use a hardware scanner.

## Good to know
- Bosta stays your main courier: an order with a Bosta waybill is always a Bosta order.
- There's no waybill to print in Traced for these orders — print it from the courier.
- Turning the switch off stops new orders being added. Orders already added stay and can still be packed.
- For how these orders are marked delivered, see [Ship with a courier other than Bosta](/help/other-couriers).
