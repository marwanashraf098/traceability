---
id: alerts-returns
topic: alerts
title: Return alerts
roles: [owner, manager]
related: [scan-returns, return-to-receive, untracked-returns, portal-requests]
alerts: [never_received, return_leg_unscanned, return_in_transit_stuck, return_to_receive, request_item_to_receive, return_link_ambiguous, pickup_booking_problem, refund_pending_overdue, return_items_overdue, return_mismatch]
screens: [/exceptions, /returns, /exchanges]
video:
---
Return alerts catch parcels Bosta says came back but nobody scanned, returned items that still need to go into stock, and return requests that are stuck at booking, arrival or refund. Most are fixed in **Scan returns**, **Receiving** or the request itself (**Go →**).

## Never Received

A delivery came back to you per Bosta, but one of its pieces was never scanned back in. Scan it in **Scan returns**. If it really isn't there, check with Bosta before writing it off.

## Return Not Scanned

A customer's return parcel is back per Bosta but was never scanned in. Find it and scan it in **Scan returns**.

## Returned Piece Not Scanned

Bosta has had a returning piece on its way back for several days and it still hasn't been scanned in. Check whether it reached you and scan it, or ask Bosta. If you resolve it and nothing changes, it comes back after a week.

## Return To Receive

A return parcel came back for an order Traced never tracked, and it was marked received (or a unit was marked sellable). Stock didn't change. Add the item in your next **Receiving** session (**Go →** opens Receiving with this return), and don't restock it in Shopify as well. Resolve it if it won't go back into stock.

## Returned Item To Receive

An item on a return request came back sellable, but Traced never tracked it. Add it in your next **Receiving** session.

## Return · Which Request?

A return parcel could belong to more than one return request on the same order. Traced doesn't guess: open the request and use **Link parcel…** to pick the right one.

## Bosta Pickup Booking

Traced couldn't book the Bosta pickup (or exchange) for an approved request. If it failed, fix the reason and **Retry**. If Traced isn't sure whether it was booked, check Bosta first, then choose **It wasn't booked — retry** or **It was booked — enter tracking number**. If it was booked with different details, review it in Bosta.

## Refund Overdue

The request has been waiting for its refund for several days. Refund the customer, then record it in the **Record the refund** section of the request.

## Return Items Late

Items on a booked or approved request still haven't come back. Check with the courier or the customer. If they won't come, use **The rest isn't coming** or close the request.

## Return Mismatch

A piece scanned in a return session was marked as not matching what was expected (for example, not the real piece). Inspect it, decide what happens to it, then resolve.
