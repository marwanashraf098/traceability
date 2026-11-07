---
id: receiving-excel-import
topic: receive
title: Import receiving quantities from Excel or a Shopify export
roles: [owner, manager]
related: [receive-and-label, receiving-modes, old-location-zero, label-runs]
alerts: []
screens: [/receiving]
video:
---
In an open receiving session, press Import from Excel, type what arrived in the On hand (new) column of the template or a Shopify inventory export, and upload it. Traced fills the session's quantities; nothing is received until you finish the session.

1. Open a session in **Receiving** (Low control · Quick count or Medium control · Label now — a Label & verify session is scanned, so it has no import).
2. Press **Import from Excel**.
3. Press **Download template** (Excel or CSV). It lists your products with Traced's current count in **On hand (current)**. Or use a Shopify inventory export for one location.
4. Type the number that arrived in the **On hand (new)** column. Leave a row empty if nothing arrived.
5. Drag the file in or click to browse. A .csv or .xlsx file up to 5 MB.
6. Check the result: how many products matched, and the **Not added** list with the reason for each row (for example **Not in Traced**, or **Several products match — add a SKU**). A row whose new number equals the current Shopify number is flagged: **Same as Shopify stock — is this really what arrived?**
7. Press **Add N units to this session**, then finish the session as usual.

## How rows are matched
By SKU first, then by the product handle and options, then by product and variant title. When more than one product could match, the row is never guessed — it goes to **Not added**.

## The On hand (new) column is empty
A raw Shopify export keeps its stock in **On hand (current)**. Traced tells you the new column is empty. If those numbers really are what arrived, press **Use On hand (current) instead**.

## Don't import it into Shopify too
Traced adds these quantities to Shopify when you finish receiving at the main warehouse. Importing the same file into Shopify as well would count the stock twice.
