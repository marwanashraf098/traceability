# Orders Rebuild — Pass (b), Backend + drawer wire-up

**Canonical visual truth:** `/design/Traced_Orders_dc.html` (Trace timeline section).
**Nature:** New backend read + two reconciliations + i18n vocabulary, then a thin FE consume that retires the "coming soon" placeholder built in pass (a).
**Retires:** `orders.drawer.timelineComingSoon` empty state → real trace timeline.
**Contract already stubbed:** the exported `TimelineItem` type in `OrderDrawer.tsx` is this pass's frontend contract — fill it, don't redesign it.

**Non-negotiable framing:** this is a traceability product. Every timeline row must correspond to a real recorded event. If a source for an event doesn't exist or can't be timestamped honestly, **omit the event — never synthesize a timestamp or actor.** An incomplete-but-true timeline beats a complete-looking fabricated one.

---

## STEP 0 — CC diagnoses and STOPS before writing code

Report the resolutions below + a delta list, then stop. Do not write code until reviewed. Where a source doesn't exist, say so and recommend omit-vs-derive — don't invent.

**Timeline source map.** For each event the timeline can emit, report the authoritative source table/column, whether it carries a real timestamp + actor + location, and whether it's RLS-scoped:

1. **Order created** — `orders.created_at`? Confirm. Note: exchanges (Bosta `type.code=30`) have no Shopify order behind them — confirm whether such rows reach this list/timeline at all, and if so that the timeline starts at the Bosta leg with no fabricated "created in Shopify" row.
2. **Inventory reserved** — is there a discrete, timestamped reservation event, or is committed-inventory purely derived (per the "committed is never a counter" rule)? If there's no honest timestamp, recommend deriving from the order-confirmation transition **or omitting this row** — do not fabricate one.
3. **Picked by \<name\>** — from `piece_events`, resolved **off `piece_events`, not latest-allocation** (the deferred `getSessionPieces` gap). Confirm actor (user) is captured per event and resolvable to a display name RLS-safely.
4. **Packed** — is the authoritative pack timestamp a `piece_events` row, or the `packedConfirmed` transition, or a pack-session record? Pick the one that can't drift from the deriver's `packedConfirmed`.
5. **Handed to Bosta / AWB** — `shipments` link/creation timestamp + AWB. Confirm.
6. **Courier waypoints** (Out for delivery, Delivered, Delivery attempt failed, In hub, Returned…) — from `shipment_status_history`. Enumerate the **full** set of ingested `internal_state` values that should surface, not just the mockup's 7.

**Attempt-count canonical source.** Identify BOTH divergent attempt-count sources. Recommend one as canonical — steer: derive from failed-attempt rows in `shipment_status_history` (append-only truth) rather than a stored counter, consistent with the "never a counter" rule. The pass-(a) subline (`N attempts`) and the timeline's failed-attempt rows must then agree by construction.

**In-transit reconciliation.** Confirm whether `/orders/summary`'s `withCourier` bucket is a **separate query from the Overview funnel** or shared. Report so we can converge the tab count and tab list onto one predicate (see reconciliation section) without silently changing Overview.

**Migration?** Prefer none — this is a read across existing tables. If a source (e.g. reservation event) genuinely needs a new column, flag it; then both `MigrationSmokeTest` and `NotTracedBackfillTest` counts bump together and the migration carries its RLS policy. Default expectation: zero new migrations.

Then a delta list of files touched.

---

## 1. Merged timeline read — `GET /api/v1/orders/{id}/timeline`

- **Union across sources** (order create · reservation-if-real · `piece_events` pick+pack · Bosta handoff+AWB · `shipment_status_history` waypoints), each mapped to a `TimelineItem`.
- **Ordering:** sort on the real **event timestamp ascending** (chronological, oldest→newest as in the mockup), with a deterministic secondary tiebreak. **Never order by `id` as a time proxy** (UUIDv4 isn't time-ordered) — not `id DESC`, not `id ASC`-as-recency.
- **RLS + transaction:** the read service method is `@Transactional` (missing it causes silent zero-row RLS returns that look like "not found"). All source queries and all name resolutions (actor → user display name, location → warehouse name) run under the tenant GUC via `app_user`. Actor/location resolution is null-safe (system events, deleted users).
- **`kind` marking is presentational, not a second derivation:** mark failure rows `fail`; mark the chronologically-last row `now`; the rest `done`. The `now` row must visually agree with the drawer's Current-state card (which reads `derivedStatus`) — do not compute a competing status here.

**`TimelineItem` contract** (match the FE type): `{ occurredAt (ISO), eventKey (i18n), actorName?, locationName?, detail? (AWB / failure reason), kind: 'done'|'now'|'fail' }`. `detail` carries data (AWB number, failure reason), not label copy.

---

## 2. In-transit bucket reconciliation

Goal: the "In transit" tab **count and list use one predicate**. Today count = deriver definition (`with_courier` AND `packedConfirmed`), list = raw `internal_state='with_courier'` — so list ≥ count by the Mode-B early-webhook delta.

Recommended resolution (confirm against the Step-0 finding on whether summary shares the funnel query): **converge both onto the raw `internal_state='with_courier'` predicate** — the same shipment-state predicate the list already uses — and share the exact SQL predicate/CTE between count and list. Do **not** reproduce the deriver's `packedConfirmed` branch in SQL (no new CASE sites; the deriver stays the single Java source of truth). If this makes the Orders "In transit" tab differ from Overview's stricter funnel by the early-match edge case, **document the intentional difference** (each surface answers a slightly different question) rather than materializing derived status (which reintroduces the parallel-path staleness class we avoid). If Step 0 finds the funnel shares the summary query, flag before changing anything.

Keep pass (a)'s regression test (count-param == list-param); add one asserting the converged predicate returns identical membership for count and list on a seeded set.

---

## 3. Timeline i18n vocabulary

- New order-timeline namespace, en + ar parity. **Do not reuse `lookup.phrase.*`** (piece-level custody scope — wrong vocabulary). **No hardcoded English label map** (same anti-pattern bar as statuses).
- One key per event type CC enumerated in Step 0 (order_created, inventory_reserved, picked_by `{name}`, packed, handed_to_bosta, out_for_delivery, delivered, delivery_attempt_failed, returned/restocked, in_hub, … — the full ingested set, not just 7). `picked_by` interpolates the actor name via `t()`, not string concat.
- Source labels shown on the "where" line (Shopify, Bosta) and warehouse names: warehouse/AWB/reason are **data**; only fixed source words get keys.

---

## 4. Drawer wire-up (thin FE consume — retire placeholder)

- Replace the `timelineComingSoon` empty state with the real timeline fetched from the new endpoint into the existing drawer section (built + verified in pass (a) — pure consumption, no restyle).
- Render each `TimelineItem`: dot color from `kind`, `eventKey` through `t()` with `actorName` interpolation, `locationName`/`detail` on the sub-line.
- Loading + genuine-empty states (an order with no events yet shows an honest empty state, not the old placeholder copy).
- "Last updated" footer now derives from the last timeline event's `occurredAt` (the deferred pass-(a) item).

---

## Invariants / guardrails

- Append-only sources are **read-only** here — no writes to `piece_events` or ledger.
- `OrderStatusDeriver` untouched — the timeline reads events, it does not derive status.
- New GET endpoint gets a **real same-tenant positive control** (seed events under `app_user` with RLS enforced — not a BYPASSRLS connection, not an RlsCoverageTest exemption inherited from another test) asserting the seeded, ordered timeline returns; plus a cross-tenant negative control. Confirm `@Transactional` on the read path.
- If any migration is added: RLS policy in it; both migration counts bumped together.
- Test fakes match real endpoint semantics (204/empty, `structuredClone`, error shapes); prove coverage by reverting a change and confirming the test fails. Fixtures use production-shaped data (bare-numeric tracking via `TrackingNumberNormalizer`, not prefixed strings).

## Verify bar

- Backend: new-endpoint positive + cross-tenant + ordering tests green; attempt-count test (subline count == canonical failed-event count); in-transit convergence test; `RlsCoverageTest` covered by a real assertion; deriver suite still 42/42; `MigrationSmokeTest`/`NotTracedBackfillTest` consistent.
- Frontend: drawer renders the real timeline, placeholder gone, `picked_by` interpolates, no missing-key fallbacks, EN/LTR + AR/RTL headless render, page tests use `stubFetchWithShellDefaults`.
- `tsc` + `vite build` clean; `vitest` matches baseline (git stash the 3 known failures).
- **Deploy needs `--no-cache`** (backend code change), unlike pass (a).

---

## Sequenced sub-steps for CC (each with its own report-back)

1. Step 0 diagnosis (source map + 3 reconciliation findings) → **stop for review**.
2. Backend: the timeline read + attempt-count canonicalization + in-transit convergence → report with the positive-control assertion quoted.
3. FE consume + i18n vocabulary → report with EN/AR render confirmation and placeholder removal.
