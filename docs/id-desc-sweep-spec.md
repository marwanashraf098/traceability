# `id DESC` latest-row sweep — Build Spec

**Why:** `gen_random_uuid()` (UUIDv4) is not chronological, so `ORDER BY id DESC LIMIT 1`
does **not** reliably return the latest row. The status-redesign close-out (4911460) fixed
this in `OrderController` and `ExceptionService` and added the invariant to `CLAUDE.md`. The
Bosta ingest audit found the two remaining offenders. This sweep closes them so the
invariant is *true*, not aspirational.

**Blast radius of leaving them:** both feed merchant-visible outcomes — the pick queue and
`not_traced` tagging. A UUIDv4 tie (two forward shipments on one order) could hide the wrong
order from Pick & Pack or tag the wrong order as fulfilled-outside-Traced. Low probability
(needs near-same-instant inserts), real consequence. This is the last thing to land before
promote-off-shadow **because it touches the pick queue.**

**Scope:** three call sites, one commit. No schema change, no ingest-logic change, no display
change. `resolvePreconditions` is **not** in scope — the audit confirmed it orders by
`last_sync_at DESC NULLS LAST`, not `id` — it is already correct. Do not touch it.

---

## Step 0 — verify (diagnose only, no commit)

Confirm the three sites still read as the audit reported, and confirm `shipments.created_at`
is `NOT NULL` (the tie-break's correctness depends on it — the status feature already
confirmed `NOT NULL DEFAULT now()` in V1, re-confirm it hasn't changed):

- `FulfillService.java:81` — `PICKABLE_ORDERS_FILTER`'s `LEFT JOIN LATERAL … ORDER BY id DESC LIMIT 1`.
- `NotTracedTagger.java:71` — `ORDER BY s2.id DESC LIMIT 1`.
- The `V57` `not_traced` backfill `UPDATE` — per `NotTracedTagger`'s docstring and `CLAUDE.md`,
  it must move together with the tagger (same "latest forward shipment" selection logic).
  Locate it and confirm its ordering.

Report the three exact current expressions before changing anything.

## The fix

At all three sites, change the latest-forward-shipment selection to:

```sql
ORDER BY created_at DESC, id DESC LIMIT 1
```

`created_at DESC` is the real recency key; `id DESC` stays only as a deterministic tie-break
for the (rare, legitimate) case of two rows at the identical `created_at`. Preserve every
other clause verbatim — `shipment_leg = 'forward'`, tenant scoping, join conditions. This is
an ordering-only change.

Add at each site:
```
// UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
```

## CLAUDE.md

Flip the existing UUIDv4 invariant from "fixed in the status feature; NotTracedTagger and
FulfillService remain, to be swept together" to the closed form: **no `ORDER BY id DESC` as a
latest-row proxy remains in the codebase; use `created_at DESC, id DESC`.** If any future site
genuinely needs `id` ordering for a non-recency reason, it must say so in a comment.

## Tests (Testcontainers, real Postgres, fixtures production-shaped)

The bug only manifests on the shape that triggers it, so the test must **create it**:

- **Tie-break correctness — the point of the whole commit.** Insert an order with **two
  forward shipments** whose `created_at` differ, but insert them so the *older* one has the
  *higher* UUID (mint explicit UUID literals — do not rely on generation order, which is
  exactly the non-determinism under test). Assert the pick-queue filter / `NotTracedTagger`
  selects the shipment with the later `created_at`, i.e. the query no longer follows `id`.
  This test must **fail on the old `ORDER BY id DESC`** and pass on the fix — verify that by
  running it against the pre-change code once (or reasoning it through in the report).
- **Pick-queue regression:** `PICKABLE_ORDERS_FILTER` still includes/excludes the same orders
  it did before for single-shipment orders (the common case is unaffected).
- **NotTracedTagger regression:** single-forward-shipment orders tag identically to before;
  the existing tagger tests stay green.
- RLS: any new query path exercised under `app_user` with a same-tenant positive control
  alongside the cross-tenant negative. `RlsCoverageTest` stays green.

## Definition of done

- Three sites changed, one commit; `resolvePreconditions` untouched.
- Tie-break test present and demonstrably catches the old ordering.
- CLAUDE.md invariant flipped to closed.
- Full suite green; the two pre-existing unrelated failures already handled in 4911460 stay
  handled.
- `docs/PROGRESS.md` note; no checklist FR (this is invariant hygiene, not a feature) — link
  it to the UUIDv4 invariant line instead.
- Commit message: `chore(ingest): sweep id-DESC latest-row proxies → created_at DESC, id DESC`.

## Out of scope (audit backlog — do NOT pull in)

- Unbounded `shipment_status_history` growth → separate retention/cap infra ticket.
- Snapshot-only attempt/courier/SLA fields on `shipments`.
- Dropped `isConfirmedDelivery` / `deliveryPromiseDate`.
- Divergent attempt counts (`piece_events.metaJson` vs `shipments.number_of_attempts`).
