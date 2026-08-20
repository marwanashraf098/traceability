# Orders Rebuild — Pass (a), Frontend

**Canonical visual truth:** `/design/Traced_Orders_dc.html`
**Nature:** Restyle + drawer/timeline shell. Appearance from the mockup; behavior, data, and derived status from existing code.
**Out of scope this pass:** the merged timeline read endpoint and the summary-bucket predicates — those are pass (b). The timeline renders against a stub here.

---

## STEP 0 — CC diagnoses and STOPS before writing code

Report a delta list + the inventories below, then stop. Do not write code until reviewed and approved. Where a spec assumption doesn't match the code, flag it; proceed only when exactly one sensible resolution *reduces* surface.

Required Step-0 output:

1. **Status-label source inventory.** Locate the single place that maps a derived order status → (display label, badge/pill variant). Report:
   - Whether one canonical source exists, or whether labels/colors are currently scattered or hardcoded.
   - The full set of status values `OrderStatusDeriver` can emit for **each facet** (fulfillment facet and delivery facet — see §2), with each one's current label string, i18n key (if any), and DS badge variant (if any).
   - Any status the mockup shows (`Fulfilled`, `Delivered`, `In transit`, `Returned`, `Delivery failed`) that has **no** existing i18n key or DS variant. Flag these — do **not** invent labels or hexes for them.
2. **DS badge/pill component.** Confirm the existing `ui.tsx` status/badge component and its variant set. Confirm it (or a thin wrapper) can render both facets without a new one-off pill.
3. **Summary buckets.** Confirm `/orders/summary` returns the four tab counts the mockup needs (Needs attention, In transit, Delivered, Returns) or report which are missing (missing ones are a pass-(b) note, not a blocker for the restyle).
4. **Drawer data.** Confirm what the existing order detail read already returns (header, items, phone, value, Shopify link) vs. what the drawer needs, so we know the drawer shell can be wired without new endpoints this pass.
5. **Delta list** of files touched.

---

## Hard requirement: labels come from the DS, not this pass

Every status chip in the table and the drawer — `Fulfilled`, `Delivered`, `In transit`, `Returned`, `Delivery failed`, plus the drawer "Current state" title — **must render through the existing DS status/badge component and the canonical status→label mapping**, resolved via react-i18next (en + ar). 

- **No new hardcoded English label map.** Do not introduce a fresh `TYPE_LABELS`-style constant for order statuses. This is the same anti-pattern as the open exceptions-page i18n gap; we are not adding a second instance of it.
- **No ad-hoc pill colors.** Variants come from the DS token set for those statuses. The mockup's inline hexes are illustration only — the DS values win where they differ (Design System Sheet 2 canonical on conflict).
- If a required label/variant doesn't exist yet, Step 0 flags it and we add it **to the DS/i18n catalog** (en + ar together) — not inline in the Orders screen.

Timeline event labels (`Order created`, `Inventory reserved`, `Picked by <name>`, `Packed`, `Handed to Bosta`, `Out for delivery`, `Delivery attempt failed`) are a separate vocabulary from status chips. Same rule: reuse existing i18n keys; where a key is missing, flag it for the catalog rather than hardcoding. Person and warehouse names are data, not labels.

---

## Appearance (mockup = truth)

- **Table columns:** Order · Customer · **Fulfillment** · **Delivery** · Value · Updated. Two independent status columns per the mockup.
- **Delivery cell** supports a two-line failed state (label + subline `1 attempt · Retry scheduled`). Treat attempt count + retry state as fields already on the row shape (their authoritative source is a pass-(b) decision — see the two divergent attempt-count sources note; for this pass, render whatever the row provides).
- **Tabs:** All / Needs attention / In transit / Delivered / Returns, counts from `/orders/summary`.
- **Detail drawer** (slide-in, inline-end): header (#, name, phone, EGP, status pill, View in Shopify) → tabs (Overview/Items/Shipment/Notes) → Current-state card + Contact customer / View shipment → Items → **Trace timeline** → "Last updated" footer.
- Selected row gets the blue inset bar; row click opens the drawer.

## Behavior / data (existing code = truth)

- **The two columns are two facets of the single `OrderStatusDeriver` output — not a second derivation path.** Both facets read off the deriver's result. The fulfillment facet must reflect `packedConfirmed` (no new `PROGRESS_RANK` / SQL `CASE` sites, per the phantom-Packed fix). If the deriver doesn't currently expose a clean facet split, flag it in Step 0 — do not add a parallel deriver.
- **Timeline renders against a typed stub/fixture this pass.** Define the timeline item shape (`when`, `event` key, `actor?`, `location?`, `kind: done|now|fail`) so pass (b) drops the real read in without a FE change. Order newest-last visually as in the mockup; the real read will order `created_at DESC, id DESC` (never `id DESC` alone).
- Row → drawer is pure FE overlay. The drawer is not a route; `Layout` stays inside the view, not at route level. Drawer is an overlay on the list, not a separate page.

## Guardrails / verify bar

- RTL: drawer flips to inline-start, timeline mirrors, chips and sublines mirror. Verify EN/LTR + AR/RTL headless render.
- Page tests use the shared `stubFetchWithShellDefaults` helper (`/me` + `/exceptions/count`).
- Post-action states are one view — no duplicate/leftover views.
- Test fakes match real endpoint semantics (204 empty body, `structuredClone` on `.json()`, error shapes); prove coverage by reverting a change and confirming the test fails.
- Verify: `tsc` clean, `vite build` clean, `vitest` matches baseline (confirm the 3 known unrelated failures via `git stash`).
- Frontend-only pass — no `--no-cache` needed on deploy.

---

## Then: pass (b), backend (separate spec)

Merged `/orders/{id}/timeline` read (Shopify create · reservation · `piece_events` pick w/ person · pack · Bosta handoff+AWB · Bosta status · failed attempt), RLS-safe person + warehouse name resolution, resolve off `piece_events` not latest-allocation; plus confirming/adding the summary buckets and picking the authoritative attempt-count source. Kept separate so a backend bug can't hide inside the restyle.
