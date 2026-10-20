# Non-Bosta couriers G3 — live test plan (the test store only)

Out at Pickups, delivered, returned parcels. Run only on the test store, the ONLY tenant with
`generic_courier_enabled = true`. Every real tenant stays switched off; the snapshot diff (step 0 / step 7) must be
empty for all of them.

## 0. Before deploy — snapshot

```bash
AS_OF="$(date -u +%Y-%m-%dT%H:%M:%SZ)"     # ONE timestamp for both runs
psql "$PROD_DATABASE_URL" -X -q -A -t -v as_of="$AS_OF" -f scripts/generic_courier_snapshot.sql > before.txt
```

Session pooler :5432 (never :6543). Read-only, a few seconds. Section 5 (`g3|…`) prints, per tenant, the open
pickup scans by courier, handovers / delivered / returned forward legs of the 30 days before `as_of` by courier and
source, parcels scanned back, and the returns-portal eligible order count.

## 1. Setup in Shopify (test store)

1. Two orders, A and B, each one tracked item. Pick and pack both in Traced (queue mode).
2. Fulfil both in Shopify with tracking company **Aramex** and two different Aramex tracking numbers (11 digits).
   → Orders shows both "Awaiting courier" with an Aramex badge.
3. A third order C fulfilled with **Bosta** (or a different carrier, e.g. Mylerz), also packed.

## 2. Pickup session — two Aramex parcels, one refused

1. Pickups → New session → scan A's Aramex waybill. Expected: ✓ Accepted, the header shows **Courier: Aramex**.
2. Scan B's Aramex waybill. Expected: ✓ Accepted.
3. Scan C's waybill. Expected: red **"This parcel goes with Aramex — keep it for that courier's pickup."**, nothing
   added (2 scanned).
4. Close session → Confirm handover. Expected: 2 shipments closed, no piece exceptions.
5. Print / Download manifest: **Courier: Aramex**, both AWBs listed.
6. Orders: A and B read **With Aramex**; their pieces are with courier (Lookup a piece → with_courier).
7. Order drawer timeline for A: "With courier" step captioned **Scanned out at Pickups**.

## 3. Mark A delivered by hand

1. Orders → Courier filter **With Aramex (2)** → tick A → **Mark delivered** → the dialog says
   "1 order(s) with Aramex will be marked delivered…" → **Mark 1 delivered**.
   Expected: "1 order(s) marked delivered." A reads **Delivered · Aramex**; the filter now shows (1).
2. Repeat on A (e.g. via a second tab before refresh): "skipped (already delivered…)", no second audit row.
3. Settings → Audit log: one `generic_mark_delivered` row for A, by you.
4. Timeline: "Delivered" captioned **Marked delivered by hand**. Lookup a piece of A → delivered.

## 4. B delivered via Shopify's fulfillment status

1. In Shopify, set B's fulfillment shipment status (Aramex app / admin "Mark as delivered" / the fulfillment events
   API) to **In transit** first. Expected in Traced: B still **With Aramex**; the drawer's Shipment card shows
   **Shopify status: In transit**. Nothing else moves.
2. Then **Delivered**. Expected: B reads **Delivered · Aramex**; timeline captioned **From Shopify's delivery status**;
   its pieces delivered.
3. Set it back to In transit (if the admin allows) → B stays Delivered (monotonic).

## 5. Returned parcel — scan, grade, restock

1. Scan returns → new session → scan A's Aramex waybill. Expected: the parcel card names **Courier: Aramex**, its
   piece listed as expected; A's order reads **Returned**.
2. Scan A's piece label. Expected: no UNEXPECTED badge.
3. Grade it **Restock**. Expected: piece available at Main; a Shopify +1 is recorded (Inventory sync / the
   `shopify_inventory_adjustments` row with trigger `return_inspection`), exactly as for a Bosta return.
4. Close the session.

## 6. Portal

1. Returns portal lookup with B's order number + phone (B is delivered): **not found** (generic orders stay out of
   the portal until G4).
2. A Bosta order delivered in the window still finds (control).

## 7. After — snapshot diff

```bash
psql "$PROD_DATABASE_URL" -X -q -A -t -v as_of="$AS_OF" -f scripts/generic_courier_snapshot.sql > after.txt
diff before.txt after.txt
```

Expected: only the test store's lines change (its g3 lines gain `generic/…`); every real tenant's lines are
identical. A real tenant's line that changed must show its own activity marker / new Bosta activity between runs.

## Alerts to expect on the test store

- None from steps 2–5 if every parcel was scanned out.
- If B is set Delivered in Shopify WITHOUT its pickup scan: **generic_delivered_without_pickup** (LOW) —
  "delivered by Aramex without a pickup scan — Traced recorded the handover as inferred".
- A cancelled order whose Aramex parcel is still out: **generic_cancelled_live_shipment** (HIGH), never the Bosta
  "cancel the AWB in Bosta" alert.
