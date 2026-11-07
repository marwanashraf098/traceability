---
id: untracked-returns
topic: returns
title: A return from an order Traced never tracked
roles: [owner, manager, worker]
related: [return-to-receive, scan-returns, tracked-vs-not-tracked]
alerts: []
screens: [/returns]
video:
---
If an order was shipped before its items were tracked in Traced, its items have no labels to scan. In **Scan returns** you mark each item that came back as **Arrived · sellable** or **Arrived · damaged** — this does not add any stock.

## Steps

1. In an open return session, scan the parcel's waybill. The parcel card says "Traced didn't track this order" and lists the order's items one row per unit.
2. Open the parcel and check what's inside.
3. For each item that came back, tap **Arrived · sellable** or **Arrived · damaged**. Items you don't mark are treated as not having come back — a partial return is normal.
4. Made a mistake? Tap **Undo** on the row while the session is still open.
5. Close the session as usual.

If some items on the order were tracked and others weren't, scan the labelled ones as usual and mark the others.

## Why no stock is added

Traced never knew these pieces, so it can't put them back on the shelf from here. Each sellable item goes on managers' **Return To Receive** list instead, to be added in their next Receiving session — see [What "Return To Receive" means](/help/return-to-receive). Damaged items are only recorded.

Don't restock these items in Shopify yourself, or they'll be counted twice.

## When the contents don't match

If the parcel doesn't match the order, use **Mark parcel received** under "Contents don't match the order?" before marking any item. It records the whole parcel as received, and managers add what actually came back in Receiving. It's only offered while no item on the parcel is marked.

## Return requests

Items Traced never tracked can also be in a customer's return request. They have the same **Arrived** buttons on the parcel card and in the request.
