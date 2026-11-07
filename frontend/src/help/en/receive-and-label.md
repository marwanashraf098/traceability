---
id: receive-and-label
topic: receive
title: Receive new stock and print labels
roles: [owner, manager]
related: [receiving-modes, reprint-labels, receiving-excel-import, put-away]
alerts: []
screens: [/receiving]
video:
---
Start a receiving session with Medium control · Label now, add the products and quantities that arrived, then finalize. Traced creates one barcode per piece and you print the labels and stick one on each piece.

1. Go to **Receiving** › **New Session** and choose **Medium control · Label now**.
2. Pick the **Location** the stock is going to. You can add a **Reference (PO #)**, a **Supplier name** and a **Note**. Press **Create Session**.
3. Tap each product and enter how many pieces of each variant arrived. You can search by product name or SKU. To fill the quantities from a file instead, use **Import from Excel** (see [Import receiving from a Shopify Excel export](/help/receiving-excel-import)).
4. Check the summary, then press **Finalize & Generate Pieces** and confirm with **Finalize**. The session is locked after this.
5. Press **Print Labels** to print every label of the session, or **Print Barcodes** next to a product to print only that product's labels.
6. Stick one label on each piece.

## What happens in Shopify
When the session is at your main warehouse, Traced adds the received pieces to the Traced Main Warehouse location in Shopify, so the stock customers can buy goes up by the same number. Receiving at another location doesn't change Shopify.

Only receive stock that Shopify doesn't count anywhere else yet. If the same stock is still counted at an older Shopify location, set that location to 0 first. See [Set your old Shopify location to zero](/help/old-location-zero).

## Your first session
The first time you receive, Traced can walk you through it step by step: add one product, close the session, print the labels, stick them on and check them by scanning. You can press **Skip, I'll do it later** and use the normal screen.

## After receiving
If you use places, press **Place on shelves now** to put the new pieces on shelves. See [Place pieces on shelves](/help/put-away). A label got torn or lost? See [Reprint a piece label](/help/reprint-labels).

While a session is still open, an owner or manager can remove it with **Delete Session**.
