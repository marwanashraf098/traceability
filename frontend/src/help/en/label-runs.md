---
id: label-runs
topic: receive
title: Send named labels to your factory (factory runs)
roles: [owner, manager]
related: [receiving-modes, receiving-excel-import, reprint-labels]
alerts: []
screens: [/receiving?tab=runs]
video:
---
A factory run prints a named label for every piece you expect from a factory. The factory sticks them on, and you receive the pieces by scanning each label with High control · Label & verify.

## 1. Create the run
1. Go to **Receiving** › **Factory runs** › **New factory run**.
2. Enter the **Factory name** and, if you like, a **Reference (optional)**.
3. Add each product and the number of pieces you expect, or fill them with **Import from Excel**. A run can have up to 20,000 pieces; split bigger orders into two runs.
4. Press **Create run · N pieces**.

## 2. Print and send the labels
Press **Print labels · N**. Each product starts with a header label, then one label per piece. Send them to the factory: one label on each piece of the product named on it. **Reprint** prints the same labels again — no new ones are made.

## 3. Receive the pieces
1. Go to **Receiving** › **New Session** and choose **High control · Label & verify**.
2. Under **Which factory run arrived?** pick the run, then **Create Session**. The stock always goes to the main warehouse.
3. Press **Scan pieces** and scan each piece's label. **Undo last scan** takes back a wrong scan. A piece arrived without a label? Use **Piece without a label** to print one for it.
4. Finish the session. Only the scanned pieces enter stock, and they are added to Shopify at the Traced Main Warehouse location.

## 4. Close the run
Press **Close run**. Every product with missing pieces needs a reason:
- **Short-shipped** or **Lost**: that product's unused labels stop working.
- **Still coming**: the labels stay valid and the run stays open, so you can receive the rest and close it later.

Once nothing is still coming, the run closes and every label it didn't use is voided. A closed run can't be reopened. The **Shortfall report** shows what was missing, as a PDF or CSV.
