---
id: stock-take
topic: stock
title: Run a stock take (and what happens to missing and found pieces)
roles: [owner, manager]
related: [piece-statuses, shopify-stock-mismatch, tag-stock, find-piece]
alerts: []
screens: [/stock-take]
video:
---
A stock take counts your main warehouse by scanning the pieces on the shelf. When you finalize it, pieces that should be there but weren't scanned are written off as lost, and lost pieces you scanned go back into stock.

1. Go to **Stock Take** › **New Count**. Choose **Whole warehouse** or **Variant subset** (only the products you pick), then **Open Count**. Only one count can be open at a time.
2. Scan every piece. Switch between **GOOD** and **DAMAGED** to record its condition. **Unscan** takes back a wrong scan.
3. Untagged pieces can't be scanned: count them on the shelf and type the number for each product. Type 0 if there are none.
4. Press **Review →** to see what was counted and what is still uncounted. Missed one? Use **Found one? Scan it**.
5. Press **Attest Complete** to confirm the count covered everything in scope.
6. Press **Finalize**, read the summary, tick **I understand this permanently decrements Shopify inventory.** and press **Finalize + Push to Shopify**. If few pieces were counted or a lot would be written off, you are asked to type the number of pieces first.

## What finalizing does
- Available pieces that weren't found are written off as lost, and Shopify stock at the Traced Main Warehouse goes down by the same number.
- Missing damaged or on-hold pieces are written off in Traced only — Shopify doesn't count them as available.
- Pieces you scanned as damaged are marked damaged.
- Lost pieces you scanned go back into stock, and Shopify goes back up for those it had taken off.
- Extra untagged pieces aren't added. Receive them with Quick count receiving.

This can't be undone. Found a written-off piece later? Open it in **Lookup** and press **Found It** — see [Lost, damaged, destroyed, void and on hold](/help/piece-statuses).

**Abandon count** cancels the session and changes nothing.

With places on, **Count one place** counts a single shelf or box. It only fixes where pieces are: it never writes off stock and never changes Shopify.
