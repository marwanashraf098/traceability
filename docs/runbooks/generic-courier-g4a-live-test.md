# Non-Bosta couriers G4a — live test plan (the test store only)

Returns-portal refunds for orders shipped with another courier. Run only on the test store — the ONLY tenant with
`generic_courier_enabled = true`, and the only one whose "Accept returns for orders shipped with other couriers" is
switched on. Every real tenant stays off; the snapshot diff (steps 0 / 8) must be empty for all of them.

## 0. Before deploy — snapshot

```bash
AS_OF="$(date -u +%Y-%m-%dT%H:%M:%SZ)"     # ONE timestamp for both runs
psql "$PROD_DATABASE_URL" -X -q -A -t -v as_of="$AS_OF" -f scripts/generic_courier_snapshot.sql > before.txt
```

Session pooler :5432 (never :6543). Read-only. Section 6 (`g4|…`) prints, per tenant: the portal switch for other
couriers (a line only when on), generic orders a customer could return now, return requests by type and booking
status, and portal-request return legs by state. Before the deploy no tenant has a `switch/` or `manual` line.

## 1. Switch it on (test store)

1. Settings → Couriers: "I ship with other couriers" is already on (G2).
2. Settings → Returns portal: **Accept returns for orders shipped with other couriers** → on. Expected: toast
   "Returns for other couriers' orders are on."; `audit_log` has one `portal_generic_returns_switch` row (false → true).
3. As a manager (other browser): the row is shown, the switch disabled, "Only the owner can change this."

## 2. An Aramex order delivered

1. Order A: one tracked item, picked and packed in Traced, fulfilled in Shopify with **Aramex** + an Aramex number,
   scanned out at Pickups (G3) → "With Aramex".
2. Portal (`https://returns.tracedtech.com/<slug>`): look up A with the customer's phone. Expected: **not found** —
   it isn't delivered yet.
3. Orders → "With Aramex" → select A → **Mark delivered**. Expected: "Delivered · Aramex".

## 3. Customer refund on the portal

1. Look A up again. Expected:
   - the item is listed; there is **no Return / Exchange choice** (even with exchanges on);
   - before the lookup, the note reads "The store will arrange a courier to collect the item from your address."
     (no "Bosta");
   - the pickup step says "**<Store> arranges a pickup with Aramex** from the address this order was delivered to."
     and offers **no** area / "A different address" choice;
   - EN and AR: no "Bosta" / "بوسطة" anywhere.
2. Send the refund. Expected (auto-approve off): "requested".

## 4. Approve — nothing is booked with Bosta

1. Returns & exchanges → the request. The footer reads "This order went out with Aramex. Approving doesn't book Bosta …".
2. Approve. Expected:
   - the drawer shows **Book the pickup with Aramex** with the customer's name, phone and delivery address, a
     Courier field (Aramex) and an optional Return tracking number — **no** "Bosta pickup" row, no spinner;
   - the case list pill reads **Book with Aramex**;
   - DB: `booking_status = 'manual'`, `manual_carrier_name = 'Aramex'`, `bosta_tracking_number` NULL; no JobRunr job
     `ReturnPickupBookingJob` for it; Bosta's dashboard shows no new CRP. Wait 15 minutes (two sweeps): still nothing.
3. Enter the order's own Aramex number as the return tracking. Expected: refused — "That's the order's own delivery
   waybill …"; nothing changed.

## 5. With a return tracking number — scan the return waybill

1. Book the pickup with Aramex outside Traced; enter its tracking number → **Save courier and tracking number**.
   Expected: "Return booked with Aramex"; drawer "Booked with Aramex · <number>"; request "Pickup booked"; DB: a
   `shipments` row provider generic, leg return, source portal_request, state created; `link_source merchant_entered`.
2. Scan returns → new session → scan the **return** waybill. Expected: the parcel card is the request's (its
   reference), carrier Aramex, the item expected.
3. Scan the item's piece label. Expected: accepted, attributed to the request; order A's forward leg stays
   **Delivered · Aramex** (not "returned to sender").
4. Grade it (sellable). Close the session. Expected: the return leg → `returned` (history source `return_scan`),
   intake stamped; the request → refund pending.
5. Record the refund in the drawer → mark refunded.

## 6. Without a tracking number — piece scan only

1. Order B: same as step 2 (Aramex, Mark delivered), customer refund, approve.
2. In the drawer save the courier only (leave tracking empty). Expected: "Courier saved: Aramex"; the request stays
   approved; no return leg.
3. Scan returns → scan B's **original** Aramex waybill, then its piece. Expected: B's forward leg stays delivered;
   the piece is attributed to the request (arrived). Grade, close → request refund pending. Record the refund.

## 7. Bosta orders unchanged (same store)

1. A Bosta-delivered order in the same store: the portal lookup shows the Bosta pickup area as before; submit →
   approve → the Bosta CRP is booked exactly as before (drawer "Bosta pickup" row).
2. Settings → Returns portal → switch off. Look A up again: not found (as G3).

## 8. After — snapshot diff

```bash
psql "$PROD_DATABASE_URL" -X -q -A -t -v as_of="$AS_OF" -f scripts/generic_courier_snapshot.sql > after.txt
diff before.txt after.txt
```

Expected: every real tenant's lines identical. The test store's lines differ only by its own steps above (its
`g4|…` switch / eligible / `requests/refund/manual` / `generic_return_legs/…` lines, and its G3 lines).
