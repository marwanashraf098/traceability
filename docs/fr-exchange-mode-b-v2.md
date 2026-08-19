# FR-EXCHANGE v2 — First-class Bosta Exchange support (Mode B, Bosta-direct)

**Supersedes v1.** Rewritten after Phase 0 diagnosis + full fleet-data confirmation. This is the corrected shape CC builds against.
**Status:** Phase 1 spec, gated. CC clears the §0 hard preconditions first and STOPS if any surprises; otherwise proceeds through Phase 1.

**What changed from v1 (read this first):**
- **§3.2 REVERSED — forward-only legs.** The return leg does **not** get a `shipments` row. Fleet data confirmed `fwd_tn = crp_tn` on all 4 exchanges — two rows sharing one tracking number would break the codebase's one-row-per-tracking assumption at 4 hot lookup sites (CC 5.4). Forward leg is the only `shipments` row; return progress rides the `exchanges` record's status. This leaves the swapped-AWB guard and the other 3 lookup sites **unchanged** — lowest blast radius on the most critical table.
- **State mapper is bypassed, not patched** (CC 5.6). `type.code=30` deliveries route around the generic `(code,type)` mapper into a dedicated per-leg interpreter; the dead `:EXCHANGE` seed rows are deleted.
- **Phase 3 + Phase 4 merged** — outbound-in-Fulfill and per-leg state interpretation are one atomic pass (the forward shipment row is corrupt the instant it exists without the interpreter).
- **Baseline corrected:** Flyway head **V73**, suite **1027/0/3-skipped** (v1's V70–72 / 891 was stale memory).
- **Backfill set is exactly 4 rows, all The Snouts.** Jumi has zero exchanges.

---

## 0. Hard preconditions — CC clears these before any Phase 1 code, and STOPS on any surprise

These are the residual reads Phase 0 flagged. All three must come back clean or the model changes.

**0.1 — No independent `recordUnlinked()` path.** Read `BostaStatusPollJob` and `BostaDiscoveryPollJob` line-by-line. Confirm they re-post through `BostaWebhookJob.process()` and reach `recordUnlinked()` only via that shared path (CC's own 5.3 caveat — traced via callers, not read fully). If either poller reaches `recordUnlinked()` independently, the `type.code=30` reroute must be applied there too — the "side effect forgotten on a parallel path" class.

**0.2 — Nothing requires a return-leg `shipments` row (the forward-only veto check).** Confirm no path in Returns intake, courier reconcile, or the never-received/exception detectors *requires* a `shipments` row with `shipment_leg='return'` to exist for an exchange's return to be handled. Expected clean: the Returns rebuild uses `return_session_shipments`, not `shipments`. Also report whether a `UNIQUE(tenant_id, tracking_number)` constraint exists on `shipments` (tells us how hard the one-row invariant is held, and whether the forward-only INSERT needs no dedup guard). **If a return-leg `shipments` row is genuinely required somewhere, STOP and report — forward-only is vetoed and we reconsider.**

**0.3 — Shopify-sweep grep (Model A gate).** Grep every background job / reconcile path for the pattern "iterate orders by `store_id` → push/fetch/reconcile to Shopify." Any such job would sweep the synthetic internal exchange order (§3.3) and choke on its missing Shopify GID. Report every hit. Phase 1 hardens each by excluding the sentinel `external_id` prefix (or the explicit `origin='exchange'` flag, whichever the code makes cleaner).

**0.4 — Does the deployed return-restock sync to Shopify?** Read the Returns disposition restock path. Confirm whether restocking a returned piece currently pushes a `+1` to Shopify (safe increment) or only moves the piece internally. Determines whether the exchange inbound `+1` (§3.6) is free reuse or new work. Report the exact method and call site.

**Hygiene (non-blocking):** CC's Supabase pooler creds are dead (`FATAL: tenant/user not found`). Marawan is running DB truth from the console; fix CC's connection string when convenient so CC can self-serve later, but it does not block this pass.

---

## 1. Scope

**In scope:** recognise EXCHANGE deliveries at ingest → route to an exchange lane (not generic unmatched); an `exchanges` aggregate binding an **outbound leg** (pickable → Fulfill) and an **inbound leg** (return → Returns intake) under one shared tracking number; a **manual mapping step** (operator maps both free-text descriptions → variants); outbound flows through existing Fulfill with an "Exchange" badge and links to the existing AWB at pack; inbound reconciles at existing Returns intake against the pre-mapped variant; exchange displayed as an exchange; one-time backfill of the 4 existing rows.

**Out of scope (overbuild guard):** no analytics/dashboard/funnel; **no auto-mapping or description parsing** (fleet data proves sizes are option-list ranges like `XS/S/M/L` — any parser would be wrong); no Traced-initiated Bosta creation (Mode B holds); no COD settlement workflow (all fleet rows are `cod:0` — store the value, don't build settlement). Shopify inventory **is** wired (§3.6) — build the safe negative-delta version, don't defer.

---

## 2. Source of truth — fleet-verified payload shape

4 unresolved EXCHANGE rows, all tenant `e785e5e4-…` (The Snouts). All: both legs populated, `fwd_tn = crp_tn`, `cod:0`, `business_reference:null`, `fwd_itemsCount = ret_itemsCount = 1`.

| Field | Path |
|---|---|
| Router key | `raw->'type'->>'code'` = `30` / `bosta_order_type='EXCHANGE'` |
| Outbound desc | `raw->'specs'->'packageDetails'->>'description'` (⚠ may have trailing space — trim on display) |
| Outbound count | `raw->'specs'->'packageDetails'->>'itemsCount'` |
| Inbound desc | `raw->'returnSpecs'->'packageDetails'->>'description'` |
| Inbound desc AR | `raw->'returnSpecs'->'packageDetails'->>'descriptionAr'` (feeds RTL display directly) |
| Inbound count | `raw->'returnSpecs'->'packageDetails'->>'itemsCount'` |
| Tracking (both legs) | `raw->'parcels'->'forward'->>'trackingNumber'` == `raw->'parcels'->'crp'->>'trackingNumber'` |
| COD | `raw->>'cod'` · Goods value | `raw->'goodsInfo'->>'amount'` |
| Receiver | `raw->'receiver'` · Customer addr | `raw->'dropOffAddress'` · Return addr | `raw->'returnAddress'` |

**Backfill set (exactly these):** `877468285`, `6336637079`, `184907356`, `9293360461` — all The Snouts. Three are currently in the Exceptions screen as "could not be matched"; rerouting clears them.

**Traps (hard requirements):**
- **Trap A:** `parcels.crp.desc` is `"N/A"` — the real return description is in `returnSpecs.packageDetails`. Never read the return leg from `parcels.crp`.
- **Trap B:** exchange numeric states are a *combined two-leg* timeline. Code `41` appears twice (`out_for_exchange` forward / `out_for_return` inbound); code `46` = `exchanged_returned`. The generic `(code,type)` mapper cannot disambiguate legs — see §3.5.
- **Data trap:** descriptions carry option-list sizes (`XS/S/M/L`) and can be garbled (`"...//Checkered shirt"`). Show raw as context; operator always picks the exact variant off the browse-grid. `itemsCount>1` on either leg at ingest → raise an exception, do not auto-handle.

---

## 3. Locked architecture

### 3.1 `exchanges` aggregate (shape for Phase 1)
Tenant-scoped, keyed `UNIQUE(tenant_id, tracking_number)`. Own `status` enum — an exchange-progress concept, **not** an order display status and **not** produced by `OrderStatusDeriver` (invariant untouched).

```sql
CREATE TABLE exchanges (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              uuid NOT NULL REFERENCES tenants(id),
    tracking_number        text NOT NULL,
    status                 text NOT NULL DEFAULT 'needs_mapping'
                             CHECK (status IN ('needs_mapping','mapped','outbound_in_fulfillment',
                                               'out_for_exchange','return_pending','reconciled','cancelled')),
    outbound_description   text,          -- raw; trim only at display
    inbound_description    text,
    inbound_description_ar text,
    outbound_variant_id    uuid REFERENCES variants(id),   -- set at mapping
    inbound_variant_id     uuid REFERENCES variants(id),   -- set at mapping; single FK (fleet itemsCount=1)
    outbound_order_id      uuid REFERENCES orders(id),     -- Model A internal order
    return_session_id      uuid REFERENCES return_sessions(id),  -- set at Returns intake; NOT a shipments FK
    cod                    numeric(10,2),
    goods_value            numeric(10,2),
    raw                    jsonb NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, tracking_number)
);
CREATE INDEX exchanges_needs_mapping_idx ON exchanges (tenant_id, status)
    WHERE status = 'needs_mapping';
-- RLS + tenant_isolation policy in THIS migration, same pattern as every tenant-scoped table.
```
No return-shipment FK. No return-items table (fleet is single-item; multi-item return is an ingest-time exception, not a stored shape).

### 3.2 Forward-only legs
The **forward** leg is the only `shipments` row (created at pack via `linkByAwbScan`, `shipment_leg='forward'`). The **return** leg has no `shipments` row; its courier progress advances `exchanges.status` (poll sees `out_for_return`/`exchanged_returned` → `return_pending`), and its physical arrival creates a `return_session` linked via `exchanges.return_session_id`. Consequence: the 4 tracking-lookup sites CC found (webhook step 8, swapped-AWB guard `:415`, `createOrFindShipment :485`, `createOrFindReturnShipment :597`) need **no changes** — one-row-per-tracking is preserved.

### 3.3 Outbound leg → Fulfill (Model A)
Materialise the outbound leg as an **internal (non-Shopify) order** flagged `origin='exchange'`, one `order_item` = mapped `outbound_variant_id`, qty = outbound itemsCount. Fulfill machinery is unchanged (CC 5.7 confirmed no provenance coupling; pickability gate keys off `is_self_pickup` / forward-shipment `internal_state='created'`). Synthetic unique `external_id = 'internal:exchange:<tracking_number>'` on the tenant's real Shopify store row (satisfies `NOT NULL UNIQUE(store_id, external_id)`; cannot collide with Shopify GIDs). The internal order's own display status legitimately comes from `OrderStatusDeriver` (it is an order). **Gated on §0.3.**

### 3.4 Pack = existing AWB link
Pack links the outbound piece to the existing Bosta AWB via `linkByAwbScan` (Mode B, no new delivery). Guard stays leg-blind but safe because only a forward row is ever created for this tracking — the swapped-AWB check at `:415` still 409s a genuine cross-order swap and never sees a competing return row. (If §0.2 reveals a `UNIQUE(tenant_id, tracking_number)` constraint, the forward INSERT needs no extra dedup; confirm.)

### 3.5 Exchange state interpreter (bypass the generic mapper)
For `type.code=30` deliveries, branch **before** `BostaStateMapper.map()` into a dedicated interpreter that decides state **per leg** off the timeline `value` strings, never the bare numeric:
- Forward leg terminal = **delivered** (never `returned`; never set `returned_at` on the forward exchange shipment — this is what prevents the never-received false-fire, since `detectNeverReceived()` keys on `shipment_leg='forward' AND internal_state='returned' AND returned_at`).
- Return progress → `exchanges.status` (`out_for_return` → `return_pending`), no shipment row touched.
- In the **same migration**, DELETE the dead `41:EXCHANGE` and `46:EXCHANGE` (and any other `:EXCHANGE`) seed rows from `bosta_state_mappings` — CC confirmed `41:EXCHANGE` (V37) is already latently wrong for the outbound leg, and no `46:EXCHANGE` exists so 46 falls through to `46:ALL → returned`. Removing them prevents reintroduction and forces everything type-30 through the interpreter.

### 3.6 Inventory — WIRED, both deltas fire at return inspection (Marawan)
No Shopify order drives the movement, so Traced pushes both sides itself. **Both deltas settle at one point: when the returned (inbound) piece is received and inspected.** The inbound leg runs through the existing Returns machinery, flagged as an exchange, and its inspection is the single supervised moment where inventory settles.

**Why inspection, not courier pickup:** binds the dangerous negative delta to a confirmed physical event with a human already in the loop, instead of firing speculatively at pickup (which would need reversal if the outbound later failed to deliver). Fewer speculative fires, no reversal path.

**Treat-as-return + note:** the inbound leg creates a `return_session` (Phase 5) flagged `origin='exchange'`, linked via `exchanges.return_session_id`, so intake shows the worker "this is an exchange return" and disposition ties back to the exchange. Reuses the rebuilt Returns disposition loop wholesale.

**At inspection, two writes:**
- **Inbound +1 / damage-move** on the returned variant, by disposition (restock → `+1` via `inventoryAdjustQuantities`; damaged → available→damaged move; mismatch → exception, no auto `+1`). Reuses existing restock sync (§0.4).
- **Outbound −1** on `outbound_variant_id` via the **dedicated negative-delta gateway** (never `inventorySetOnHandQuantities`, never a raw negative) — fired here because the completed exchange confirms the outbound unit is consumed. **Independent of the return's disposition:** the outbound left regardless of what condition the return arrived in, so the −1 fires on restock, damage, and mismatch alike.

**Precondition + the edge that must not strand the −1:**
- The −1 fires **only if the forward leg was delivered.** Whole exchange fails / outbound RTOs back (forward leg returned, never delivered) → **no −1**; the outbound unit never left and re-enters via its own return.
- **Forward delivered but return never received** (customer keeps it / courier never collects — Bosta split-leg / state-103): the −1 must still fire, because the outbound unit is gone. Wire the **never-received resolution for an exchange** as the fallback trigger. Missing this strands the negative delta forever = Shopify silently overstates the outbound variant — the "side effect forgotten on a parallel path" class.

**Two non-negotiable rules on the −1:**
1. **Idempotent single-fire.** Unique-constrained `INSERT (exchange_id, kind='outbound_decrement')` via non-proxied `JdbcTemplate` (codebase anti-race pattern) wins-or-throws; only the winner calls the gateway. Inspection and never-received-fallback can therefore never double-fire; re-inspections / re-polls are no-ops.
2. **First live fire is a controlled validation, shared with stock-take.** Same gateway → the first completed exchange's inspection (decrement one variant → reconcile Shopify count against expected −1 → confirm exact) is the live proof for both features at once. A human is already at inspection, so this first fire is naturally supervised. Do not run the −1 unattended across the fleet until that one fire is reconciled.

**Testcontainers (revert-to-confirm each):** at inspection, outbound −1 + inbound +1 both fire; −1 fires on restock, damage, and mismatch dispositions; forward-RTO (never delivered) fires no −1; forward-delivered + never-received-resolution fires the −1; −1 fires exactly once (inspection-then-never-received and re-inspection both no-op). Bare-numeric tracking fixtures.

---

## 4. Invariants & bug-classes (every phase)
RLS + policy in the same migration; `RlsCoverageTest` green (seeded assertion or documented exemption for new GETs). Bump **both** migration counts (`MigrationSmokeTest`, `NotTracedBackfillTest`) — see §6 for current values. Flyway forward-only. Append-only ledger is the sole piece-status writer. Increment-only Shopify writes (deferred here anyway). Never `ORDER BY id DESC` as latest-row proxy (`created_at DESC, id DESC`). Production-shaped test fixtures: bare-numeric tracking (`877468285`), never prefixed. RLS reads inside `tx.execute()` with tenant GUC set. Every ingest path branches on type-30, not just one (§0.1). Prove each test by reverting the fix and confirming failure.

---

## 5. Phase 1 deliverables (after §0 clears)
1. Migration: `exchanges` table (§3.1) + RLS/policy + `:EXCHANGE` seed-row deletion (§3.5) + both counts bumped.
2. Ingest reroute: in `process()`, branch `type.code=30` before step 8's lookup → upsert an `exchanges` row in `needs_mapping` (idempotent on `(tenant_id, tracking_number)`; refresh `raw`/`last_seen`), instead of `recordUnlinked()`. Apply to any independent poll path found in §0.1.
3. One-time backfill: the 4 rows in §2 → `exchanges` in `needs_mapping`; mark their `unlinked_bosta_deliveries` rows `resolved=true`.
4. Exception detector: `detectUnmatched()` gets `AND u.bosta_order_type <> 'EXCHANGE'`; add a new `needs_mapping` exception category ("Exchange — needs mapping") sourced from `exchanges`.
5. Tests: ingest reroute (type-30 → exchange row, not unlinked), backfill idempotency, cross-tenant RLS isolation on `exchanges`, detector no longer double-surfaces EXCHANGE, `itemsCount>1` → exception. Revert-to-confirm each.

**STOP after Phase 1; report delta; await review before the merged Phase 2+3/4 pass.**

## 6. Baseline (corrected, from CC 5.9)
Flyway head **V73**. `MigrationSmokeTest` asserts **72** applied (V1–V73, V38 unused). `NotTracedBackfillTest` asserts **17** pending after V56 (V57–V73). Backend suite **1027 / 0 failures / 0 errors / 3 skipped**.

## 7. Remaining phase shape (each its own gated pass)
- **Phase 2 — Mapping step:** list + map endpoints; frontend reuses Receiving browse-grid + variant-modal + selected-summary (`ProductThumb`). Show raw descriptions as context only, no parsing; trim trailing whitespace. Map creates the Model-A internal order (outbound) + sets `inbound_variant_id`; status → `mapped`.
- **Phase 3+4 (merged) — Pick & Pack + state interpreter:** outbound leg in Fulfill queue with Exchange badge; pack = `linkByAwbScan` forward leg; exchange interpreter (§3.5) live; forward-leg terminal = **delivered** (this is the state that later gates the −1); never-received false-fire guarded.
- **Phase 5 — Return reconciliation + both inventory deltas:** inbound at Returns intake vs `inbound_variant_id`; creates `return_session` flagged `origin='exchange'`, sets `return_session_id`; disposition loop. **At inspection, fire both deltas per §3.6** — inbound +1/damage-move (reuse existing restock sync per §0.4) and outbound −1 via the negative-delta gateway (idempotent single-fire, gated on forward-delivered, with the never-received fallback trigger). First live fire held for the controlled validation.
- **Phase 6 — Display:** exchange badge on record + lookup timeline; minimal two-leg exchange view. No analytics.

**Per-pass verify bar:** `tsc` + `vite build` clean; `vitest` matches baseline (3 known unrelated failures via `git stash`); headless-Chromium EN/LTR + AR/RTL. Backend Testcontainers green; `RlsCoverageTest` / `MigrationSmokeTest` / `NotTracedBackfillTest` green.
