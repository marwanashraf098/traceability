---
id: cancelled-waybills-pickup
topic: pickup
title: Cancelled or deleted waybills at pickup
roles: [owner, manager, worker]
related: [hand-over-parcels, alerts-courier-shipments, unpack-after-edit]
alerts: []
screens: [/pickups]
video:
---
A waybill that's cancelled or deleted in Bosta is never handed to the courier. If you scan one, Traced refuses it. If it's cancelled after you scanned it, you can still close the session — Traced leaves that parcel out of the handover.

## When you scan it
Traced shows "This waybill is cancelled or deleted in Bosta — don't hand it to the courier." Nothing is added to the session. Keep the parcel back and carry on with the others.

## When it's cancelled after the scan
1. Close the session as usual. Closing is not blocked.
2. Traced hands over everything else, and shows "1 waybill(s) left out of the handover" with the waybill numbers.
3. Those waybills are taken out of the session and the manifest. Their pieces stay packed.
4. Keep those parcels in the warehouse.

## What to do with the parcel
- **The order was cancelled:** its pieces need to be unpacked — see [Unpack an order that changed in Shopify](/help/unpack-after-edit).
- **The order should still ship:** book a new delivery in Bosta for it. Traced follows the new waybill and tells you in **Alerts** when the parcel needs the new waybill — see [Courier and shipment alerts](/help/alerts-courier-shipments).

## Good to know
The only thing that stops a session from closing is having nothing scanned in it.
