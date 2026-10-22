# Non-Bosta couriers G4b — live test plan (the test store only)

Returns-portal EXCHANGES for orders shipped with another courier. Run only on the test store — the ONLY tenant with
`generic_courier_enabled = true` and "Accept returns for orders shipped with other couriers" on (G4a). Every real
tenant stays off; the snapshot diff (steps 0 / 9) must be empty for all of them.

## 0. Before deploy — snapshot

```bash
AS_OF="$(date -u +%Y-%m-%dT%H:%M:%SZ)"     # ONE timestamp for both runs
psql "$PROD_DATABASE_URL" -X -q -A -t -v as_of="$AS_OF" -f scripts/generic_courier_snapshot.sql > before.txt
```

Session pooler :5432 (never :6543). Read-only. Section 6 (`g4|…`) now also prints, per tenant, generic exchange
replacement orders by status / outbound-leg state (`replacement_orders/…`) and their Step-5a claims
(`replacement_dispatch/…`). Before the deploy no tenant has either.

## 1. Setup

1. Settings → Returns portal: "Allow exchanges" on (needs pickup booking, Bosta and a return location — as today), and
   "Accept returns for orders shipped with other couriers" on.
2. A product with two sizes, M and L; one piece of L in stock at the main warehouse (Receiving). Note the L variant's
   Shopify "available" at the Traced Main Warehouse.
3. Order A: one piece of size M, fulfilled in Shopify with **Aramex**, scanned out at Pickups, then Orders → "With
   Aramex" → **Mark delivered**.

## 2. Customer exchange on the portal

1. Look A up. Expected: the Return / Exchange choice is offered; choose Exchange → M → L.
2. The pickup step reads "<Store> sends your new size and arranges collecting the old item from the address this order
   was delivered to." — no area / "A different address" choice, no "Bosta" / "بوسطة" (EN and AR).
3. Send. Expected: "Exchange requested", the steps say "<Store> sends your new size and arranges collecting the old one."

## 3. Approve — the replacement order, nothing booked with Bosta

1. Returns & exchanges → the request: the approve helper says the order went out with Aramex and approving creates the
   replacement order. Approve.
2. Expected: the drawer shows **Book the exchange with Aramex** (customer name / phone / address, Courier, Replacement
   waybill, optional Return waybill); the case list shows **Book with Aramex** in **To do**.
3. DB: `return_requests.booking_status = 'manual'`, `replacement_order_id` set; an order `EXC-RR-…` with external id
   `internal:exchange:rr:<request id>` and status `new`; **no** `exchanges` row, **no** JobRunr `ReturnPickupBookingJob`,
   no new delivery in Bosta's dashboard. The replacement is **not** in Pick & Pack yet.

## 4. Refusals

1. Enter the same number as Replacement and Return waybill → refused: "The return waybill must be different from the
   replacement's outbound waybill …"; nothing changes.
2. (Optional) a second exchange request with the only L piece already reserved → Approve refused (out of stock).

## 5. Ship the replacement

1. Book the delivery with Aramex outside Traced. Enter its waybill as **Replacement waybill** (and, if Aramex gave a
   separate return number, the **Return waybill**) → "Replacement booked with Aramex — it is in Pick & Pack."
   Expected: request "Pickup booked"; the case moves to **In progress**; `EXC-RR-…` appears in Pick & Pack.
2. Pick & Pack: open `EXC-RR-…` by scanning its Aramex waybill (G2), scan the L piece, complete.
3. Pickups: new session, scan the replacement's waybill (Courier: Aramex), close.
   Expected: the piece is `with_courier`; **one** `shopify_inventory_adjustments` row `exchange_dispatch`, −1, applied, at
   the main warehouse; Shopify "available" for L dropped by exactly 1.
4. Orders → With Aramex → select `EXC-RR-…` → Mark delivered. Expected: delivered, **no** second −1.

## 6. The old item comes back

- **With a return waybill:** Scan returns → scan the return waybill (opens the request's parcel), scan the M piece →
  accepted, attributed; order A stays **Delivered · Aramex**. Grade sellable, close → the return leg `returned`; the
  request → **Exchanged**.
- **Without a return waybill:** scan A's M piece directly → attributed; grade, close → **Exchanged**.

## 7. Untracked item, drawer Arrived (follow-up)

On a refund request with an untracked line and a return waybill entered: drawer → item → **Arrived · sellable**.
Expected: the return leg goes to `returned` with its intake stamped (no session needed). **Undo** → back to `created`.

## 8. Bosta exchanges unchanged (same store)

A Bosta-delivered order → portal exchange → approve: a Bosta type-30 trip is booked exactly as before (exchange trip,
AWB, "Exchange trip booked"); its replacement still decrements −1 once at its pickup.

## 9. After — snapshot diff

```bash
psql "$PROD_DATABASE_URL" -X -q -A -t -v as_of="$AS_OF" -f scripts/generic_courier_snapshot.sql > after.txt
diff before.txt after.txt
```

Expected: every real tenant's lines identical; the test store's differ only by the steps above (its
`replacement_orders/…`, `replacement_dispatch/…`, `requests/exchange/manual` lines and the G3/G4a lines they move).
