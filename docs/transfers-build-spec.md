# FR-22 — Transfers & External Custody (Build Spec, Claude Code)

**What this is.** Send pieces out to a saved external destination (showroom / dry-clean / factory-repair / other) and reconcile them back — where returned pieces have **lost their barcodes** and, for showrooms, **come back in a different count** because some were sold off-book. Phase-2 transfer workflow + Phase-3 consignment, built pilot-thin.

**Prerequisite reading:** the 8 INVARIANTS (see note below), the piece status machine in `blueprint.md` §4, `ReturnService` / `InventoryLedger` / `FulfillService.scan()` in the current tree, `ShopifyInventoryService`'s `is_fulfillment` guard.

> **Step 0 is DONE** (findings dated this session). This spec is written against the *confirmed* tree, not assumptions. Schema facts below are derived from the full Flyway history (V1→V63) + Java call sites; Flyway is the only DDL path in this repo, so schema **shape** is authoritative. Live-row confirmation is still pending a DB-credential fix — not required for FR-22.1 (migration is DDL; race/RLS tests run on Testcontainers).
>
> **Constitution caveat:** the repo `CLAUDE.md` is currently the wrong file (Odoo content) with a real "Environment notes" tail. Rely on the invariants tail + this spec until it's restored. Do **not** treat the repo `CLAUDE.md` body as authoritative.

---

## Locked decisions (design gate)

1. **Primary use case = showrooms, sales OFF-Shopify.** Reconcile does **not** match Shopify orders. `sold` is a genuine off-book terminal.
2. **Items can be lost or condemned** on any transfer type. Condemned = existing `damaged` terminal + `attributed_to:vendor` metadata; lost = existing `lost` + attribution. **No new status** for condemned/lost.
3. **Relabel-on-return accepted** as physical workflow.
4. **Reconcile is Manager/Owner only.** Send-out mirrors pick permissions = `isAuthenticated()` (any role) — confirmed matching `FulfillController`.

---

## The mental model

A transfer is a **deliberate identity break-and-reissue.** Send-out is per-piece scanned (full chain fidelity through `transferred_out`). At the vendor, identity collapses into a fungible pool ("N units of variant V outstanding on transfer T"). Return is **not** re-identification (impossible for fungible units) — it is: reprint the outstanding labels, scan back each returned unit (re-verifies it), classify the residual shortfall by quantity. **Never mint new piece IDs** — reprint the barcodes of the specific outstanding piece IDs being closed. One reconcile flow, balance enforced:

```
qty_out  =  scanned_back(good→available + condemned→damaged)          // verified
          + classified_shortfall(sold + lost + condemned_not_returned) // quantity-asserted, vendor-attributed
```

Dry-clean/factory: shortfall ~0. Showroom: shortfall sold-heavy. Same machinery.

---

## 🚦 Sensitive-area approval gates (STOP, wait for Marawan)

- **G1 — Status enum + `InventoryLedger.ALLOWED`.** Adds `out_on_transfer` (active) + `sold` (terminal) + 5 transitions. Post the exact migration + ALLOWED diff and wait. **State it precisely:** `damaged`/`lost` are *not* fully sealed today — ALLOWED already has `damaged:destroyed`, `damaged:lost`, `lost:available` (Stock-Take write-off / found-again). The new `out_on_transfer:damaged` / `out_on_transfer:lost` are additions to an already-open graph, not the first opening of a terminal.
- **G2 — `CLAUDE.md` / `blueprint.md` status-diagram edits.** Only after G1 code lands (and ideally after the repo `CLAUDE.md` is restored).
- **G3 — Any Shopify write.** Spec mandates **zero** Shopify writes this build. If you think one's needed, stop and ask.

---

## Confirmed codebase facts (built against these — do not re-derive)

- **locations:** `type location_type NOT NULL DEFAULT 'warehouse'` (native PG enum: `warehouse|branch|showroom|retail|event|vendor`), `is_fulfillment boolean DEFAULT false` (V59), one-fulfillment-per-tenant partial-unique (V61). **`syncs_to_shopify` does NOT exist** — `is_fulfillment` alone is the Shopify gate (`ShopifyInventoryService` guard checks only that). `LocationController.create()` already accepts `is_fulfillment=false` + any type incl. `showroom`/`vendor`.
- **Destinations:** create as `is_fulfillment=false` locations. **Decision (baked):** `showroom` destinations → `type='showroom'`; dryclean/repair/other → `type='vendor'`. **No `ALTER TYPE`.** The workflow distinction lives in `transfers.transfer_type`, not in `location_type`.
- **Piece status:** PG enum + `PieceStatus.java`, kept in lockstep manually. Adding a value = `ALTER TYPE ... ADD VALUE` (cannot be used in the *same* transaction it's added in — separate migration statement / commit before reference) + one Java constant. Two new: `out_on_transfer`, `sold`.
- **`InventoryLedger.transition(pieceId, expected, new, eventType, actor, ctx)`** — `@Transactional READ_COMMITTED`; `ALLOWED` is a `Set<String>` of `"from:to"`, checked before DB access; race guard is `status = ?::piece_status` in the UPDATE WHERE. **`transition()` does NOT touch `current_location_id`** — the calling service does an explicit `UPDATE pieces SET current_location_id=?` (pattern in `ReturnService`). scanOut/reconcile mirror this.
- **piece_events.metadata** is `jsonb`; `TransitionContext.metadata()` is a raw JSON string. `attributed_to` / `reconciliation` / `verified` are just more keys — no schema change.
- **Send-out scan mirrors `FulfillService.scan()` exactly:** string check `if(!"available".equals(status)) → WRONG_STATUS`, then `transition(..., AVAILABLE, OUT_ON_TRANSFER, ...)` catching `StateConflictException`. Reuse existing scan codes (Invariant 7). ≤300ms.
- **Available/pick exclusion:** `PICKABLE_ORDERS_FILTER` is order-level (no piece status). The piece-level available checks (`getGatherList()` subquery + `scan()`) key on `status='available'` → `out_on_transfer` auto-excluded. No new predicate. **Verify with a test anyway.**
- **On-hand formula (Shopify-facing, `CatalogController`/`ShopifyInventoryReconcileService`):** `status IN ('available','reserved','packed','awaiting_pickup') AND current_location_id IN (fulfillment locations)` — **4 statuses, not 1**, committed inventory counts too. `out_on_transfer` is excluded regardless, so transfer-out correctly drops on-hand with zero code change. Do not write any doc asserting "available only."
- **Inventory summary breakdown (`InventoryController.summary()`, FR-15.1):** tenant-wide, ordered status buckets. This is where the new **"Out on transfer / At vendor"** bucket goes.
- **Label reprint:** existing single-piece reprint (`ReturnSessionController.reprintPieceLabel`) is hard-gated to `return_pending_inspection|damaged` → rejects `out_on_transfer`. **Do NOT widen the returns gate.** Add a new transfer controller method looping `LabelService.generatePieceLabel(pieceId)` + `InventoryLedger.recordLabelReprinted()` (existing no-status-change event writer) per outstanding piece.
- **Roles:** `user_role ENUM('owner','manager','worker')`, `@PreAuthorize`. Send-out endpoints → `isAuthenticated()`; `beginReconcile`/`reconcile*`/`close` → `hasAnyRole('OWNER','MANAGER')`.
- **RLS testing reality:** `RlsCoverageTest` audits GET *endpoints* (reflective `@GetMapping` scan vs COVERED/EXEMPT sets), NOT tables, and runs BYPASSRLS — it does **not** prove RLS. Table RLS is proven only by hand-authored `app_user`-connection tests (`Day10Test` pattern: `TenantAwareDataSource` over a non-BYPASSRLS connection, cross-tenant negative + same-tenant positive control). See test list.

---

## Schema — migration `V{n}__transfers.sql`

All tables: `tenant_id` + `ENABLE`/`FORCE ROW LEVEL SECURITY` + tenant policy **in this migration** (Invariant 3). UUID PKs; `piece_id` is ULID text.

```
transfers(id uuid pk, tenant_id, transfer_type text,           -- 'showroom'|'dryclean'|'repair'|'other'
  destination_location_id uuid → locations, status text,       -- 'open'|'reconciling'|'closed'
  note text, expected_return_at timestamptz,
  created_by uuid → users, created_at timestamptz default now(),
  closed_by uuid → users, closed_at timestamptz)

transfer_lines(id uuid pk, tenant_id, transfer_id → transfers, variant_id → variants,
  qty_out int default 0, qty_returned_good int default 0, qty_condemned int default 0,
  qty_sold int default 0, qty_lost int default 0,
  unique(transfer_id, variant_id))

transfer_pieces(id uuid pk, tenant_id, transfer_id → transfers, line_id → transfer_lines,
  piece_id text → pieces, outcome text,                        -- null=outstanding|'returned_good'|'condemned'|'sold'|'lost'
  outcome_verified boolean, outcome_at timestamptz, outcome_by uuid → users)
```

**Concurrency referee (claim-before-call):** a piece is on ≤1 active transfer. Partial unique index:
```
create unique index transfer_pieces_one_active on transfer_pieces (piece_id) where outcome is null;
```
Send-out scan does the INSERT; the index rejects the loser → `ALREADY_RESERVED`-class code (add `ALREADY_ON_TRANSFER` only if a distinct message is wanted).

---

## Status machine + `InventoryLedger` (behind G1)

Add enum values `out_on_transfer`, `sold` (separate `ALTER TYPE` statements) + Java constants + 5 ALLOWED pairs:
```
available:out_on_transfer   out_on_transfer:available   out_on_transfer:damaged
out_on_transfer:sold        out_on_transfer:lost
```
Every transition = one `transition(...)` call (Invariant 1); `current_location_id` updated by the calling service explicitly (per `ReturnService`); `piece_events` insert-only (Invariant 2). Reconcile writes `attributed_to`, `reconciliation:quantity_based|scan`, `verified` into `metadata`.

---

## Backend — `TransferService` / `TransferController` (`/api/v1/transfers`)

- `createTransfer(type, destinationLocationId, expectedReturnAt, note, actor)` → open transfer.
- `scanOut(transferId, barcode, actor)` — mirror `FulfillService.scan()`: `PIECE_NOT_FOUND → WRONG_STATUS(must be available) → claim(INSERT transfer_pieces, unique-index referee) → transition available→out_on_transfer → explicit UPDATE current_location_id=destination → upsert line qty_out`. ≤300ms.
- `listOpen()` / `getTransfer(id)` — consignment view. `@Transactional` (RLS GET).
- `beginReconcile(transferId, actor)` — open→reconciling. **MANAGER/OWNER.**
- `reconcileScanBack(transferId, barcode, condition, actor)` — `condition∈{good,condemned}`; resolve the piece's outstanding row → good→available (verified) or condemned→damaged (verified, attributed_to=vendor). Piece not outstanding on this transfer → clear error (mis-scan/extra → don't absorb).
- `reconcileClassifyShortfall(transferId, lineId, {sold,lost,condemned_not_returned}, actor)` — FIFO-pick that many still-outstanding pieces on the line, close each to terminal (verified=false, attributed_to=vendor). **Balance enforced:** reject unless `qty_out == returned_good + condemned + sold + lost` for the line.
- `closeTransfer(transferId, actor)` — reconciling→closed; reject if any `outcome IS NULL` remain.
- `reprintOutstandingLabels(transferId)` — new method, loop `generatePieceLabel()` + `recordLabelReprinted()` per outstanding piece (do NOT reuse the status-gated returns endpoint).

Errors `{code,message_en,message_ar}`, i18n from day one. `LookupService` phraseKeys: `transferred_out`, `returned_from_transfer`, `condemned_at_vendor`, `sold_offbook`, `lost_at_vendor`. **Custody honesty:** scanned events `verified=true,reconciliation=scan`; shortfall events `verified=false,reconciliation=quantity_based,attributed_to=vendor`. Timeline must not claim a scan that didn't happen.

---

## Shopify (behind G3) — zero writes this build

External destinations are `is_fulfillment=false` → never feed Shopify. `out_on_transfer` is outside the 4-status on-hand formula, so transfer-out drops the computed on-hand automatically. **Emit no Shopify write on any transfer path.** Add one marker `// TODO(shadow-mode-lift): transfer deltas fold in via available↔non-sellable move; never inventorySetOnHandQuantities` + one line in `docs/week5.md`. Never decrement Shopify directly.

---

## Mode B guard

Transfers never create/link Bosta deliveries. Wall transfer paths off from `BostaWebhookJob` / `ShipmentLinkService` like self-pickup. Test: a Bosta webhook referencing an `out_on_transfer` piece must not transition it (no-op / exception). Confirm on both webhook and manual-link paths.

---

## Tests that must exist and pass (write WITH the feature)

- **State machine:** every new legal transition + illegal (`packed→out_on_transfer` rejected, `out_on_transfer→reserved` rejected).
- **Send-out race:** two concurrent `scanOut` of one piece → exactly one wins (partial-unique referee), one event (Testcontainers).
- **Reconcile balance:** close with `good+condemned+sold+lost != qty_out` → rejected.
- **Showroom:** out 10 → scan back 6 good + 1 condemned → classify 3 sold → 6 available, 1 damaged(vendor), 3 sold; closes.
- **Dryclean:** out 10 → scan back 10 good → 10 available, zero shortfall.
- **Lost shortfall:** out 10 → scan back 9 → classify 1 lost → piece `lost`, attributed_to=vendor, verified=false.
- **Mis-scan:** scan a piece not outstanding on this transfer → clear error, no mutation.
- **Exclusion:** `out_on_transfer` piece absent from pick queue, gather list, and both on-hand/summary counts.
- **RLS (hand-authored, `Day10Test` pattern):** `app_user`-connection cross-tenant negative on transfers **paired with a same-tenant positive control** (else RLS blindness gives a false green). Add each new `GET /api/v1/transfers...` to `RlsCoverageTest` COVERED (seeded) or EXEMPT (reason) or the build fails.
- **Mode B:** webhook on `out_on_transfer` piece → no transition.
- **Role gate:** WORKER cannot `beginReconcile`/classify/close.

---

## Commit plan (small, one sub-item each, message references FR-22.x)

1. `FR-22.1` migration: 3 tables + RLS + partial-unique + destination-location convention **(feeds G1 context)**.
2. `FR-22.2` status enum + Java constants + ALLOWED transitions **(GATE G1 — stop for approval; post the precise diff + the damaged/lost-already-open note)**.
3. `FR-22.3` `createTransfer` + `scanOut` + send-out race test.
4. `FR-22.4` reconcile scanBack + classifyShortfall + balance + tests.
5. `FR-22.5` `reprintOutstandingLabels` (new loop method) + `closeTransfer`.
6. `FR-22.6` controller + role gates + i18n + LookupService phraseKeys + RlsCoverageTest entries.
7. `FR-22.7` inventory-summary "At vendor" bucket + exclusion tests.
8. `FR-22.8` Mode B guard + test.
9. `FR-22.9` frontend: create/send-out scan screen, consignment list, reconcile screen (Manager/Owner), relabel-print action. RTL, ar+en.
10. Tick `docs/requirements-checklist.md` (add `## FR-22 Transfers` — confirm it's absent first), update `docs/PROGRESS.md`, week5 deferrals. **(GATE G2 for any status-diagram doc edit.)**

---

## Deferred to week5

Vendor registry (typed locations suffice); Shopify transfer-delta fold-in (blocked on shadow-mode lift); overdue-transfer alerts in Exceptions center; per-piece re-scan of *which* physical unit returned (impossible without serialization — out of scope permanently).
