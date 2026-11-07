---
id: receiving-modes
topic: receive
title: The three ways to receive stock
roles: [owner, manager]
related: [receive-and-label, label-runs, tag-stock, old-location-zero]
alerts: []
screens: [/receiving, /settings?tab=labels]
video:
---
Every new receiving session asks how you want to receive the delivery: Low control · Quick count, Medium control · Label now, or High control · Label & verify. Choose by how much control you want over each piece.

Go to **Receiving** › **New Session**. The screen asks **How do you want to receive this delivery?** and shows three cards.

## Low control · Quick count
You only enter how many pieces arrived. No labels are printed: the pieces go into your main warehouse as untagged and show in Inventory as soon as you finish. Each piece gets its label later, when it's packed in Pick & Pack or in a Tag stock session. Good for a big delivery you don't want to label yet. See [Tag stock that is already on your shelves](/help/tag-stock).

## Medium control · Label now
You choose the exact products and quantities. When you finish, Traced creates a barcode for every piece, and you print the labels and stick one on each piece. You can receive into any of your locations. See [Receive new stock and print labels](/help/receive-and-label).

## High control · Label & verify
The labels are printed first, by you or your factory, and stuck on each piece. You then scan every piece to receive it. Only the pieces you scan enter stock, so you get exactly what is physically there. This mode needs an open factory run: without one the card shows **Needs a label run** with a **Set up** link. See [Send named labels to your factory](/help/label-runs).

## Good to know
- Quick count and Label & verify always receive into the main warehouse.
- When you finish a session at the main warehouse, Traced adds the pieces to the Traced Main Warehouse location in Shopify. If the same stock is still counted at another Shopify location, set that location to 0 first or it is counted twice. See [Set your old Shopify location to zero](/help/old-location-zero).
- **Settings** › **Labels & printing** › **Default receiving mode** picks which card is already selected. Anyone can still choose another mode each time.
