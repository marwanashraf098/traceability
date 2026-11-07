---
id: grade-returns
topic: returns
title: Grade returns: restock or damaged
roles: [owner, manager, worker]
related: [scan-returns, back-to-good, return-to-receive, shopify-stock-mismatch]
alerts: []
screens: [/returns]
video:
---
Every item you scan in a return session needs a decision: **Restock** puts it back in sellable stock at your main warehouse, **Damage** keeps it out of stock and needs a reason. You can't close the session until every item is graded.

## Restock

Tap **Restock** when the item can be sold again. Traced puts the piece back to available at your main warehouse, and adds 1 to your "Traced Main" location in Shopify.

Traced doesn't add that 1 if Shopify already restocked the item for this order (for example, someone ticked "Restock items" when refunding in Shopify). This stops the same unit being counted twice. When you refund in Shopify after restocking in Traced, leave "Restock items" unticked.

## Damage

Tap **Damage**, type a reason (it's required) and confirm. The piece is recorded as damaged and isn't sellable. Nothing changes in Shopify. If it turns out to be fine later, a manager can return it to stock — see [Back to good](/help/back-to-good).

## Not the real piece

Sometimes a scanned label belongs to a piece Traced doesn't see as out with a customer — for example a piece it still shows in stock. Restock and Damage aren't offered for it. An owner or manager marks it **Not the real piece** so it's recorded as a mismatch; a worker sees "This needs a manager or owner".

## Closing

**Close session** stays blocked while any scanned item still shows **NEEDS DECISION**. Grade every item, then close. Return requests linked to these items move forward on their own once their items are graded.

In some cases Traced doesn't change Shopify stock, for example before Shopify is counting at your main warehouse; the item's history shows why.
