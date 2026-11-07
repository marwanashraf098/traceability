---
id: portal-who-can-return
topic: portal
title: Who can request a return
roles: [owner, manager]
related: [portal-setup, portal-requests, exchanges-bosta, untracked-returns]
alerts: []
screens: [/settings?tab=portal]
video:
---
A customer can start a return when they enter an order number and the phone number on that order, and the order was delivered within your return window. Products you mark as not returnable are shown but can't be selected.

## What Traced checks

1. **Order number and phone** — both must match one order in Traced.
2. **Delivered** — Traced must have seen the order delivered. For Bosta orders that's Bosta's delivery. Orders shipped with other couriers count only when **Accept returns for orders shipped with other couriers** is on, and they're delivered when Shopify shows them delivered or you mark them delivered.
3. **Inside the return window** — counted in days from delivery. You set it under **Return window** (1 to 90 days).

If any check fails, the customer sees "We couldn't find a returnable order with those details". After too many failed tries they're asked to wait an hour.

## Which items they can choose

- Items from the order that weren't already returned in another request. An item already in a request shows **Already in a return request**.
- Items Traced never tracked can be returned too. When they come back, they're marked arrived instead of scanned — see [A return from an order Traced never tracked](/help/untracked-returns).
- Products under **Products that can't be returned** show **Can't be returned**. Add them with **Add products** in **Settings › Returns portal**.

## Exchanges

With **Allow exchanges** on, a customer can exchange one item for another size or colour of the same product, as long as it's in stock. See [Exchanges with Bosta, in one trip](/help/exchanges-bosta).

## Orders that don't qualify

Orders that were never delivered, orders outside the window, and orders a customer collected themselves don't qualify. Customers can contact you for those.
