---
id: bazar-report
topic: bazar
title: The bazar report, PDF and orders CSV
roles: [owner, manager]
related: [bazar-close-day, bazar-end-event, bazar-customer-details]
alerts: []
screens: [/bazar/:id/report]
video:
---
Open a bazar's report with **View report** on its page, or from the Bazar list. It shows the money, what sold, the busiest hours, where every piece ended up, the sellers and the customers. Use **Download PDF** or **Export orders (CSV)** to keep a copy.

## What's in the report
- **Revenue**: sales minus returns, with the discounts given and the money not received.
- **Sell-through**, **Pieces sold**, **Average sale** and **Per day**.
- **Money by day**: each day's money and how its close went, and how customers paid over the whole bazar.
- **What sold**: each product and size, sold out of how many you took.
- **Busiest hours**: sales per hour, all days together.
- **Where the pieces ended up**: sold, back in stock, damaged, lost and still out, with a link to the transfer.
- **Sellers**: pieces and money per seller.
- **Customers**: how many customers were saved, with **Export customers (CSV)**.
- **Money not received**: from the day closes — cash short, card short, InstaPay not received, and any amounts over.

The report also lists days that weren't closed. A day with no sales can show there; it doesn't stop the bazar from ending.

You can open the report while the bazar is live too. Owners and managers can see it.

## Download PDF
A summary in the page's language: the totals, money by day (what it should have been, what was counted, the difference), payment methods, the notes from the day closes, and sales per hour. The PDF has no customer names or phones.

## Export orders (CSV)
One row per piece sold: the sale number, day, time, seller, product, variant, SKU, piece code, price, discount, net, payment method, status — sold, returned or exchanged, with "(damaged)" when it came back damaged — and the customer's name and phone. Undone sales are left out.

## Export customers (CSV)
One row per customer: name, phone, pieces, total and last visit.

Every customers or orders export is logged.
