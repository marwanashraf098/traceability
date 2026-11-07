---
id: piece-statuses
topic: stock
title: Lost, damaged, destroyed, void, on hold and sold
roles: [owner, manager]
related: [back-to-good, stock-take, shopify-stock-mismatch, lookup-sold-exchange]
alerts: []
screens: [/lookup]
video:
---
Open a piece in Lookup and press Adjust Piece to mark it lost, damaged, destroyed, void or on hold, or Sold to record a sale outside Shopify. Only owners and managers can do this, and Shopify's Traced Main Warehouse stock follows each change.

1. Scan the piece in **Lookup**.
2. Press **Adjust Piece**, choose the new status and a reason. **Other** needs a note. Press **Apply Adjustment**.

A piece reserved or packed for an order must first be freed with **Release from order**. Pieces out on a transfer or without a label can't be adjusted.

## What each status means
- **Lost**: the piece is missing. Shopify goes down by 1. If it turns up, press **Found It** and it goes back to available.
- **Damaged**: here but can't be sold. In Shopify the unit moves from available to damaged; on hand stays the same. See [Back to good](/help/back-to-good).
- **Destroyed**: gone for good. Shopify goes down by 1. It can't be undone.
- **Void**: the piece should never have existed — a receiving overcount or a duplicate entry. Shopify goes down by 1 only if the piece was added to Shopify when it was received. It can't be undone.
- **On Hold**: set aside for a quality check, quarantine or repair. Shopify goes down by 1 while it is held. **Release from Hold** puts it back to available and adds it back. A held piece can also be moved on to lost, damaged or destroyed, with no second change in Shopify.
- **Sold (Off-book)**: sold outside Shopify, for example in person or on Instagram. Press **Sold**, then **Mark sold**. Shopify goes down by 1. **Not sold** puts it back, and adds 1 back if the sale reached Shopify.

## When Shopify doesn't change
Shopify only changes for a piece it counts at the Traced Main Warehouse, such as a piece at your main warehouse whose receiving reached Shopify. For any other piece, only Traced changes.

Every change is saved in the piece's history in Lookup.
