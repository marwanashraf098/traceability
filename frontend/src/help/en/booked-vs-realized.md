---
id: booked-vs-realized
topic: analytics
title: Booked and realized numbers
roles: [owner]
related: [bosta-owes, inventory-value, orders-before-connect]
alerts: []
screens: [/analytics/summary, /analytics/revenue]
video:
---
**Booked** is what customers ordered in the period, after discounts. **Realized** is the part you actually kept: orders that were delivered, minus the items customers returned.

## Where you see them

The **Summary**, **Revenue**, **Products & SKUs** and **Delivery** pages in **Analytics** have a **Revenue basis** switch with **Booked** and **Realized**. Realized is the default. The switch changes the revenue figures and breakdowns on the page; the per-day chart shows both lines.

## How each is counted

1. **Booked** adds up the items of every order placed in the period, after discounts. Cancelled orders and Traced's internal exchange orders are left out.
2. **Realized** takes the booked value of the delivered orders and subtracts the items the customer returned. An order that failed, is still on the way or wasn't shipped yet counts as 0.
3. Both are counted by the day the order was **placed**, not the day it was delivered. A delivery next week still adds to this week's realized number.

## Why realized is lower

On the **Revenue** page, the chart **From gross sales to money you keep** shows the gap step by step: **Discounts**, **Still in pipeline** (in transit or not shipped yet), **Other carrier**, **Failed deliveries**, **Returns & refunds**, down to **Net realized**. **Realized ÷ booked** is the share of booked revenue you keep.

Realized trails booked for the last few days, because those orders are still with the courier. It catches up as Bosta delivers.

## Things to know

- Realized is measured from Bosta deliveries. Without Bosta connected, Traced can't measure realized. Orders shipped with another courier appear as **Other carrier**, because Traced can't see whether they were delivered.
- A figure marked **approximate** includes order lines with no stored price; their value uses the item's current price.
- Analytics is for the account owner only.
