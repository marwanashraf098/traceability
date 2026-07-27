# Build Spec — FR-8.7 Gather List (read-only pick wave view)

**Scope: Option A only.** Stateless consolidated gather screen that sits *in front of* the existing pick flow. No new table, no wave entity, no locking, no changes to the scan/complete path. `FulfillController` scan/unscan/complete/lock behavior is untouched.

**Explicitly NOT in this slice** (do not implement, do not "improve" toward): FR-8.7a wave locking, FR-8.7b automatic drop-out into exceptions, FR-8.7c FIFO piece-ID suggestion. Shortage is *displayed only*, never acted on.

**Mode B guard:** this feature is a pure read-only aggregation. It makes zero Bosta calls and creates zero deliveries. If any part of the implementation touches Bosta or shipment creation, it is wrong — stop.

---

## What it does

Worker/manager opens the gather screen from the pick queue. System aggregates all `ready_to_pick` orders (oldest-first, optional limit) into one row per variant: total quantity needed, on-shelf available count, shortage flag, and the order numbers that need that variant. Worker collects everything in one pass, then runs the existing per-order scan flow unchanged. Printable. Recomputed on each load (refresh button) — no live sync, no polling, no websockets.

---

## Backend

**New service `GatherService`** (or method on the existing fulfillment service), **`@Transactional`, tenant GUC set via the standard `tx.execute` / `SET LOCAL app.tenant_id` pattern.** This is the C3 silent-zero-row RLS class — an aggregation spanning orders + order_items + variants + pieces is exactly where a missing GUC hides. The new method **must** be added to `RlsCoverageTest`.

**Aggregation (single read, RLS-scoped):**
- Source: `orders o JOIN order_items oi ON oi.order_id = o.id JOIN variants v ON v.id = oi.variant_id`
- Filter: `o.status = 'ready_to_pick'` (optionally `AND o.id = ANY(:orderIds)` if explicit selection is passed; v1 default is no selection = all `ready_to_pick`).
- `needed = SUM(oi.quantity)` grouped by variant. **Assumption to rely on:** a `ready_to_pick` order has no active allocations (allocations begin only when the order transitions to `picking` on first scan), so `remaining = needed`. Do **not** add an allocation-subtraction join — it's dead weight here.
- `availableCount` per variant = count of pieces with `status = 'available'` for that variant (RLS-scoped). Correlated subquery or separate grouped query, your call — keep it one round trip if clean.
- `orderNumbers` = `array_agg(DISTINCT o.order_number)` per variant.
- `shortage = availableCount < needed`.
- Oldest-first ordering by order `created_at` when applying `limit`.

**Endpoint:** `GET /api/v1/fulfill/gather` — roles OWNER/MANAGER/WORKER (same as `/queue`). Optional query params: `limit` (int, oldest-first cap; default none/all). Skip explicit `orderIds` multi-select in v1.

**Response DTO `GatherListResponse`:**
```
{
  "generatedAt": "<ISO instant>",
  "orderCount": <distinct ready_to_pick orders included>,
  "rows": [
    { "variantId", "name", "sku", "imageUrl" (nullable),
      "needed", "availableCount", "shortage", "orderNumbers": [...] }
  ]
}
```
`imageUrl` nullable — emit whatever the variant image field holds; null is fine, frontend degrades to text.

---

## Frontend

- New screen `GatherList` at `/fulfill/gather`, reachable via a **"Gather" button at the top of the pick queue**. Read-only — no scan input on this screen.
- Table: variant (image if `imageUrl` present, else name + SKU only), needed, available, order numbers. **Shortage rows highlighted** (available < needed) with a clear "need X, have Y" indicator.
- **Refresh** button (re-fetches; this is the "recomputed on load" behavior). Show `generatedAt`.
- **Print** button (browser print with a print-friendly layout).
- A "Start picking" / back affordance returns to the queue; the existing per-order flow is unchanged.
- **RTL + i18n, worker-facing → ships in Arabic and English.** react-i18next keys for all new strings, both `en` and `ar`.

---

## Tests (`GatherListTest`, Testcontainers)

Cross-tenant tests **must** have a same-tenant positive control (a cross-tenant-only test proves nothing):

- **Positive control:** 3 `ready_to_pick` orders with overlapping variants → correct `needed` SUM per variant, correct `orderNumbers` sets.
- **Availability + shortage:** variant where `availableCount < needed` → `shortage = true`; sufficient stock → `shortage = false`.
- **Status filter:** `new`, `packed`, and on-hold orders excluded; only `ready_to_pick` included.
- **Empty:** no `ready_to_pick` orders → empty `rows`, `orderCount = 0`.
- **Limit:** `limit=N` returns the oldest N orders' demand only.
- **Cross-tenant isolation (with the positive control above):** Tenant B's gather never sees Tenant A's orders or demand.
- **RLS coverage:** new service method registered in `RlsCoverageTest`.

---

## Slice ritual

- **No Flyway migration** — read-only, zero schema change. (Called out deliberately; do not add one.)
- Backend + frontend + tests + i18n (en/ar) in one slice.
- Update `PROGRESS.md` (session journal entry).
- Tick `requirements-checklist.md`: **8.7 done**; **8.7b partial** (shortage *shown* on the list; the "drop out into exceptions" behavior remains deferred); **8.7a / 8.7c remain unchecked**.
- Per-FR commit: `feat(fulfill): FR-8.7 gather list (read-only pick wave view)`.
- Clean working tree, push.

---

## Verification (Marawan, post-deploy)

`--no-cache` build. Then against a real pilot tenant with `ready_to_pick` orders: open Gather, confirm the per-variant totals match the orders by hand, confirm a deliberately-short variant flags red, confirm the Arabic layout renders RTL, confirm print output is legible. RLS check: the four-screens trap — confirm gather returns non-empty for a tenant that *has* ready_to_pick orders (proves the GUC fired, not a silent zero-row).
