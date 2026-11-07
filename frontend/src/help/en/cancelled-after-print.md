---
id: cancelled-after-print
topic: pack
title: An order was cancelled after its waybill printed
roles: [owner, manager]
related: [batch-waybills, alerts-cancelled-orders, alerts-packing, unpack-after-edit]
alerts: []
screens: [/fulfill, /exceptions]
video:
---
If an order is cancelled after its waybill was printed in a batch, find that printed waybill and throw it away, then mark it discarded by resolving its **Cancelled After Print** alert. Never pack it or hand it to the courier.

## What Traced does
- Pieces already scanned for the order go back to stock, and the order leaves Pick & Pack.
- In waybill scan mode, the waybill shows as **Cancelled after print** at the top of **Printed but not packed**.
- If someone scans it in a pack session, Traced says **Don't pack this one** and shows when it was cancelled.
- Reprinting the batch skips it.

## What you do
1. Find the printed waybill. **Printed but not packed** and the alert both show the order number, the waybill number and the batch number.
2. Throw the paper waybill away.
3. Open the **Cancelled After Print** alert in **Alerts** — owners and managers can also press the row in **Printed but not packed** to open it.
4. Press **Resolve**. The waybill then leaves **Printed but not packed**.

## Good to know
- If the delivery is still live in Bosta, cancel it there too. Traced shows a separate alert while it's live — see [Cancelled order with a live shipment](/help/alerts-cancelled-orders).
- If the order was already packed when it was cancelled, the pieces must be unpacked instead — see [Unpack an order that changed in Shopify](/help/unpack-after-edit).
