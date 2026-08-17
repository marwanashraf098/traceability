# Progress Journal — Piece-Level Traceability SaaS

---

## Current state

**id-DESC latest-row sweep closed (2026-08-07) — `docs/id-desc-sweep-spec.md`.** Closes the
two remaining offenders the Bosta ingest audit found, so the UUIDv4 invariant in `CLAUDE.md`
is now true everywhere, not just at the order-status-redesign call sites. Three sites, one
commit; `resolvePreconditions` confirmed already correct (`last_sync_at DESC`) and untouched.

- `FulfillService.PICKABLE_ORDERS_FILTER`'s `LEFT JOIN LATERAL` and
  `NotTracedTagger.maybeTagNotTraced()`'s correlated subselect both moved from a bare
  `ORDER BY id DESC` to `ORDER BY created_at DESC, id DESC`.
- `V57__orders_not_traced.sql`'s one-time backfill (same "latest forward shipment" shape, same
  bug) was **not** edited in place — it already applied in every migrated environment and
  Flyway checksums it; changing its SQL would crash startup wherever it already ran, prod
  included. Added `V68__not_traced_backfill_recency_fix.sql` instead: re-runs the identical
  predicate with the corrected ordering, guarded by the same `not_traced_at IS NULL` one-way
  check `NotTracedTagger` itself uses (only ever adds a missing tag, never un-tags — matches
  the tagger's own permanent-once-set semantics). Idempotent; safe against fully-migrated data.
- **Tie-break test proves the bug, not just the fix**: an order with two forward shipments
  (legal only because one is `terminated`/`cancelled` — `ux_active_shipment_per_order_leg`,
  V43, blocks two simultaneously-live forward rows) where the *older* row (still `created`)
  carries the lexically *higher* UUID and the *newer* row (`terminated`) the lower one.
  Verified empirically, not just reasoned: temporarily reverted both `ORDER BY` lines back to
  bare `id DESC` and reran `IdDescSweepTest` — the tie-break test and its RLS variant both
  failed (wrong pick), the single-shipment regression test stayed green; restored the fix and
  all four passed. New tests: `IdDescSweepTest` (4 cases: tie-break for both the pick queue
  and the tagger, two single-shipment regressions, app_user RLS same-tenant/cross-tenant),
  `V68NotTracedRecencyFixTest` (Flyway two-stage migration test mirroring
  `NotTracedBackfillTest`'s pattern — confirms V68 corrects the tie-break order, no-ops on an
  already-correctly-tagged order, and leaves V57's applied-migration row untouched). Bumped
  the now-stale hardcoded migration counts in `NotTracedBackfillTest` (11→12) and
  `MigrationSmokeTest` (66→67) for V68's addition. `RlsCoverageTest` 19/19 unaffected.

**FR-13/FR-15.3 Part B shipped (2026-08-05) — two cancellation-reconciliation exception
detectors, additive only.** Same build spec (`docs/order-status-redesign-build-spec.md`).
Did not touch `cancelOrder`, the ingest path, or `OrderStatusDeriver` — Part B is pure
detection on top of what A1–A3.1 already derive.

- **`ExceptionService.detectCancelledWithLiveShipment()`** (`cancelled_live_shipment`, HIGH)
  and **`detectCancelledButDelivered()`** (`cancelled_but_delivered`, HIGH) — both scoped to
  the latest FORWARD shipment (`shipment_leg='forward' ORDER BY id DESC LIMIT 1`, a `JOIN
  LATERAL`, same convention as `OrderController.list()`/`NotTracedTagger`). Live = latest
  forward `internal_state` NOT IN the 5 terminal values (i.e. created/with_courier/
  returning/exception); delivered = `internal_state='delivered'`. Self-resolving like
  `detectGuidedUnpack` — no `exception_resolutions` row, the exception disappears the moment
  a normal Bosta sync moves the shipment to a terminal state. `occurred_at` is a
  path-agnostic `COALESCE(cancel_requested_at, shopify_cancel_requested_at, last_synced_at,
  created_at)` — status='cancelled' alone never says which cancel path fired, and neither
  the query nor the message assumes one.
- Grouped with the existing `detectShopifyCancelVsInflight` in both the aggregation list and
  `enrich()` (comment marks the trio) — mutually exclusive by construction
  (`status='cancelled'` vs `status='awaiting_pickup'`), confirmed by a dedicated test, not
  just asserted in a comment.
- **B2**: the A3 conflict chip (`OrderStatus` in `ui.tsx`) is now a `<Link>` to
  `/exceptions?type=<code>` — safe unconditionally because `conflictKey` and the two
  detectors read the exact same predicate over the same data, so whenever the chip renders
  the matching exception is guaranteed to exist (no extra existence check needed).
  `Exceptions.tsx` gained `useSearchParams()` support to read `?type=` on mount and
  pre-filter the queue — it had no URL-driven filtering before this. Added
  `TYPE_LABELS` entries for both new codes (existing pattern: hardcoded EN/AR pairs, not
  i18next — 6 *other* pre-existing detector types have the same gap, not touched). Also
  added the literally-requested `exc.cancelled_live_shipment`/`exc.cancelled_but_delivered`
  i18n keys to en.json/ar.json even though nothing currently reads them via `t()` — flagged
  as a minor inconsistency with the established `TYPE_LABELS` pattern, not silently resolved
  either way.
- **Known pre-existing UX quirk, not fixed** (matches `guided_unpack`'s existing behavior):
  the Exceptions page still shows a "Resolve" button for these two self-resolving types even
  though clicking it is a no-op — the detector query never consults `exception_resolutions`,
  so the row resurfaces on the next load regardless. Not introduced by Part B; inherited from
  the same pattern `detectGuidedUnpack` already has.
- **Confirmed empirically, not just noted**: `ORDER BY id DESC LIMIT 1` as a "latest
  shipment" proxy is fragile — `gen_random_uuid()` is not chronologically ordered. My own
  `j_onlyLatestForwardShipmentConsidered` test hit this directly (insertion-order fixture
  failed non-deterministically) and had to be rewritten with explicit low/high UUID literals
  to test the ordering deterministically. This is a pre-existing convention used everywhere
  in the codebase (`OrderController.list()`, `NotTracedTagger`, `PICKABLE_ORDERS_FILTER`),
  not something Part B introduced — flagged again since it's now proven, not hypothetical.
- Tests: new `CancellationConflictDetectorsTest` (13 cases: both live-states, delivered,
  4 "clean cancel" terminal states, no-shipment, non-cancelled order, mutual-exclusivity
  with `shopify_cancel_vs_inflight`, latest-forward-shipment scoping). `ExceptionRlsTest`
  gained 2 new cases (same-tenant positive control, cross-tenant negative control) — fixed
  its `@AfterEach` along the way (it never deleted `shipments`, which only became a problem
  once a test in that file started inserting them). Regression confirmed by RUNNING (not just
  not-touching) `Day14Test`, `Day37Test`, `Fr9ManifestSelfPickupTest`, `ExceptionExtTest` —
  all green. New frontend tests: `orderStatusConflictLink.test.tsx` (3 cases),
  `exceptionsDeepLink.test.tsx` (2 cases). Backend: 963 tests, same 2 pre-existing unrelated
  failures. `RlsCoverageTest`: 19/19. Frontend: 81 tests, same 3 pre-existing failures.
- Not built: Bosta `terminateDelivery` one-click resolve (item-28/§6.1 seam comment left in
  `enrich()`'s `cancelled_live_shipment` case, as instructed).

---

**FR-7/FR-11 order-status redesign, A3.1 reversed and built (2026-08-05, later the same day)
— return-leg ShipmentCard now shows a leg-scoped status badge.** Same build spec, same
`OrderStatusDeriver` (no second derivation path). Supersedes the "RTO-only default, skipped"
note below — CRP return-leg shipments are real, active production code
(`ShipmentLinkService.isCrpDelivery()`), so this was worth building instead of deferring.

- **`OrderStatusDeriver.deriveLegStatus(internalState)`** (new) — a small pure function for a
  SINGLE shipment leg's own status, no order-level precedence at all (no cancelled/conflict/
  chips/notes — those are order-scoped concepts decided by `derive()` for the forward leg
  only). New `LEG_KEY`/`LEG_TONE` maps cover all 9 real `internal_state` values: the 3
  progress states reuse the same key/tone `derive()` would pick at that state's own rank
  (`created`→`status.awaiting_courier`, `with_courier`→`status.in_transit`,
  `returning`→`status.returning`), the 5 terminal states reuse `TERMINAL_KEY`/`TERMINAL_TONE`
  verbatim, and `exception`→`status.needs_attention` matches `derive()`'s own exception
  branch. A dedicated parity test (`legStatus_neverDisagreesWithDeriveForAnEquivalentUnregressedShipment`)
  asserts leg status can never quietly drift from what `derive()` would produce for an
  equivalent, unregressed shipment — the strongest guarantee without collapsing the two
  functions into one (they have genuinely different inputs/scope).
- **`OrderController.detail()`** — `ShipmentDetail` gained a `legStatus` field, computed for
  every shipment row (both legs — cheap, pure, no extra query). Backend does NOT gate it to
  return-only; that's a frontend rendering decision, not a data-shape one.
- Frontend: new `<LegStatusBadge>` (`ui.tsx`), rendered by `ShipmentCard` for the return leg
  ONLY — `{isReturn && <LegStatusBadge legStatus={shipment.legStatus} />}`. The forward leg
  never gets a raw-state badge; its status lives solely in the `<OrderStatus>` header.
- Tests: 5 new `OrderStatusDeriverTest` cases (33 total, incl. the parity guard); 4 new
  `OrderStatusListDetailParityTest` cases (12 total) proving return-leg badges through the
  real DB-wired path AND that the order header stays driven by the forward leg only when a
  return leg is present; new `orderDetailLegStatus.test.tsx` (4 vitest render tests) proving
  the return card shows the leg badge and the forward card never does, even when the forward
  shipment's own state would map to a real label ("In transit" never renders for
  forward-only or forward+return fixtures). Backend suite: 948 tests, same 2 pre-existing
  failures. `RlsCoverageTest`: 19/19. Frontend: 76 tests, same 3 pre-existing failures.
- Still not built: a dedicated return-leg *timeline* (A4 explicitly kept the return leg on
  raw/ungrouped history, unchanged); Part B.

---

**FR-7/FR-11 order-status redesign, Part A3/A3.1/A4 shipped (2026-08-05) — cancelled-conflict
flag + collapsed timeline, built on the A1/A2 `OrderStatusDeriver` from earlier the same day.**
Same build spec: `docs/order-status-redesign-build-spec.md`. Reuses the single
`OrderStatusDeriver` — no second status-derivation path.

- **A3 — cancelled conflict.** `DerivedOrderStatus` gained a `conflictKey` field. For
  `order.status == cancelled`, the latest forward shipment's `internal_state` decides:
  `delivered` → `status.conflict.cancelled_but_delivered`; `created`/`with_courier`/
  `returning`/`exception` (still live) → `status.conflict.live_shipment`; `returned`/
  `terminated`/`cancelled`/`lost` (or no shipment at all) → no conflict, a clean cancel.
  Fixes 2e05d4e5 (cancelled + live "Preparing" AWB → now "Cancelled" + a red live-shipment
  chip). Chip is read-only — renders regardless of whether a Part-B exception exists yet;
  linking it is Part B's job, not built here. **Behavior change from A1/A2**: health chips
  (delayed/failed/NDR) are now suppressed entirely when `order.status == cancelled` — the
  conflict flag is the sole overlay. This overturns the A1/A2-era test that asserted chips
  DID still show alongside "Cancelled" (rewritten in `OrderStatusDeriverTest`, see the `a3_*`
  cases). **Correction to my own prior note**: I'd said `status.conflict.*` i18n keys
  "already exist" — they didn't (A1/A2 built the derivation logic but A3 itself, which owns
  those keys, wasn't started yet); added now, EN+AR, using the exact copy the original spec
  draft proposed.
- **A3.1 — return-leg card status: skipped, RTO-only default.** Confirmed no return-leg
  `ShipmentCard` renders any status badge (true since A2 removed `DeliveryBadge` from
  `ShipmentCard` for both legs uniformly) — nothing to change. **Flag**: `ShipmentLinkService`
  (`isCrpDelivery()` / `createOrFindReturnShipment()`, tested by 6 `CrpReturnShipmentTest`
  cases) actively creates `shipment_leg='return'` rows via CRP (customer-initiated return)
  linking — this is real, live production code, not dead/theoretical. If this pilot ever
  sees a CRP return, its return-leg card will show facts-only with zero status indicator.
  The RTO-only default may not hold — revisit A3.1 if CRP shows up in practice.
- **A4 — timeline.** "Promote attempts above raw history" was already the layout pre-A4
  (confirmed, not changed). New forward-leg-only history collapse: `groupHistory()` in
  `OrderDetail.tsx` folds consecutive identical `internal_state` rows into one entry
  (`{ state, count, firstAt, lastAt }`, e.g. "In transit · 14 scans · Jul 25–30"). Exception
  rows and terminal transitions are never folded, even if consecutive and identical — each
  is its own milestone (an NDR reason usually differs occurrence to occurrence even when the
  raw state repeats). Return leg keeps its raw, ungrouped history unchanged
  (`toRawDisplay()`) — its own returns-context timeline isn't built this round. Toggle
  copy changed from "Show/Hide history" to "Show/Hide full history" to match the spec's
  wording. `orderDetail.historyGroupScans` is the one new i18n key, EN+AR.
- Tests: 5 new `OrderStatusDeriverTest` cases (28 total) for A3's conflict matrix +
  suppression; 3 new `OrderStatusListDetailParityTest` cases (8 total) proving A3 through
  the real DB-wired path, not just the pure function; new `orderDetailHistoryGroup.test.ts`
  (7 cases, vitest) for the grouping algorithm — non-consecutive same-state runs stay
  separate, exception rows never fold even when identical and consecutive, a state after a
  milestone never merges backward into it. Backend suite: 939 tests, same 2 pre-existing
  failures as this morning (stale hardcoded migration counts, confirmed untouched).
  `RlsCoverageTest`: 19/19. Frontend: 3 pre-existing failures unrelated to this work
  (`overview.test.tsx`, `inventory.test.tsx`, `blocklist.test.tsx` — confirmed via
  `git stash` that they fail identically on the pre-A3/A4 baseline).
- **Still not built**: Part B (the two cancellation-reconciliation exception detectors — the
  conflict chip has nowhere to link yet), a return-leg timeline/status treatment if CRP turns
  out to be live for this pilot. `cancelOrder` and the ingest path were not touched.

---

**FR-7/FR-11 order-status redesign, Part A1–A2 shipped (2026-08-05) — one derived headline
replaces the two contradicting pipeline/shipment pills.** Full build spec:
`docs/order-status-redesign-build-spec.md`. A0 (diagnose-only ground-truth pass) ran first
and found the spec's assumed shipment-state vocabulary didn't match the DB: the real
`shipment_internal_state` enum has only 9 values (`created, with_courier, delivered,
returning, returned, lost, exception, terminated, cancelled`) — there is no
`in_transit`/`out_for_delivery`/`preparing`/`return_in_transit`; Bosta's granular §8.3 codes
collapse into this set before `internal_state` is ever written (e.g. "out for delivery" and
"picked up" are both just `with_courier`). A1/A2 were built against the corrected vocabulary,
not the original spec draft.

- **`OrderStatusDeriver`** (`fulfillment/OrderStatusDeriver.java`) — new pure-function single
  source of truth for display status, mirroring how `PICKABLE_ORDERS_FILTER` is shared.
  progress_rank ladder: `created=1, with_courier=2, returning=3, exception=0`; terminal =
  `{delivered,returned,lost,terminated,cancelled}`. Precedence: order.status==cancelled wins
  outright (label only — the A3 conflict flag is a separate, not-yet-built step) → shipment
  terminal → failed_delivery_attempts≥1 ("delivery failed") → latest==exception ("needs
  attention") → furthest-progress label → pipeline label (full 13-value `order_status` map,
  not the 3 the original spec sketched). Health chips are terminal-gated; historical notes
  (`delivered · attempt N`, `returned · N failed attempts`) are terminal-only.
- Filled 4 gaps the spec's A5 copy table didn't cover: `status.terminated`,
  `status.needs_attention`, `status.self_pickup_pending`, `note.returned_after` — all EN+AR,
  flagged for review rather than silently invented.
- §8.4 NDR `exception_code` → chip mapping only covers the 3 codes with dedicated A5 copy
  (1/3/8, plus return-side "postponed" synonyms 21/22); every other seeded code falls back to
  a generic `chip.exception`, DANGER-toned for the 5 critical courier-evidence codes
  (26–30: damaged/empty/incomplete/doesn't-belong/opened), WARN otherwise. Not full 1:1 §8.4
  coverage — a deliberate simplification, not an oversight.
- **`OrderController.list()`** — LATERAL extended with `number_of_attempts`, `exception_code`,
  and a `max_progress_rank` correlated subquery (SQL CASE mirroring
  `OrderStatusDeriver.PROGRESS_RANK`, COALESCEd against the shipment's own current
  `internal_state` so pre-V40 shipments with zero `shipment_status_history` rows still rank
  correctly — see that migration's backfill note). `not_traced_at` was already there.
- **`OrderController.detail()`** — added a standalone query selecting the latest forward-leg
  shipment (`shipment_leg='forward' ORDER BY id DESC LIMIT 1`, same shape as `list()`'s
  LATERAL) rather than trusting `shipments[0]` — `shipments` is ordered by
  `created_at DESC` per leg, which is a materially different (and UUID-non-monotonic-risk)
  selection than `list()`'s `id DESC`. `OrderStatusListDetailParityTest` proves list and
  detail can never derive a different `DerivedOrderStatus` for the same order.
- Frontend: new `<OrderStatus>` component (`components/ui.tsx`) folds `DeliveryBadge`'s tone
  tokens in; used by `Orders.tsx`'s list badge and `OrderDetail.tsx`'s header. Removed the
  standalone pipeline STATUS pill and the shipment SHIPMENT pill (`ShipmentCard` is facts-only
  now: tracking, provider, courier, scheduled, last-failure-reason). **Known gap, not
  fixed this round**: the return-leg shipment card lost its own status indicator along with
  the removal (the unified header only derives the *forward* shipment) — flagged for A3/A4
  or a follow-up, not silently patched.
- Tests: `OrderStatusDeriverTest` (23 pure-function cases covering the corrected divergence
  matrix, terminal suppression, furthest-progress-not-latest, NDR chip fallback,
  needs_attention, full 13-value pipeline map) + `OrderStatusListDetailParityTest`
  (Testcontainers, production-shaped fixtures — regressed created-after-with_courier,
  delivered+failed, cancelled+live shipment — plus RLS same-tenant positive / cross-tenant
  negative control on the detail path). Full suite: 931 tests, 0 new failures; the only 2
  failing tests (`MigrationSmokeTest`, `NotTracedBackfillTest`) are pre-existing stale
  hardcoded migration-count assertions (V67 already existed before this session started),
  confirmed via `git status` as untouched by this work. `RlsCoverageTest`: 19/19 green.
- **Explicitly not built this round (per spec sequencing)**: A3 (cancelled-conflict flag),
  A4 (timeline promote/collapse), Part B (the two cancellation-reconciliation exception
  detectors). `cancelOrder` and the ingest path were not touched.

---

**FR-22.6 area follow-up — closed a real desync hole in `PieceAdjustService.adjustPiece()`
(2026-08-04), found during the FR-22.9 pre-push review, approved and fixed same-day.**

**The hole**: `adjustPiece()`'s only status guard was RESERVED/PACKED →
`PieceCommittedException`. It read the piece's CURRENT status with no other filter
(`FIND_PIECE_STATUS` has no WHERE-clause status restriction), and `InventoryLedger.ALLOWED`
already permits `out_on_transfer:available` / `out_on_transfer:damaged` /
`out_on_transfer:lost` (added for `TransferService`'s own reconcile-scan-back /
classify-shortfall use). So calling adjust on a piece currently `out_on_transfer` with
`toStatus ∈ {available, damaged, lost}` passed every guard and transitioned cleanly — but
`adjustPiece()` never touches `transfer_pieces.outcome` or the `transfer_lines` counters.
The row stays `outcome IS NULL` forever, and `closeTransfer()`'s authoritative check
(`COUNT(*) FROM transfer_pieces WHERE outcome IS NULL`) never reaches zero for that
transfer — a permanent orphan, not recoverable without a manual DB fix.
(`toStatus=destroyed` was never exploitable — `out_on_transfer:destroyed` isn't in
`ALLOWED`, blocked structurally by `IllegalTransitionException`.)

Grepped every other `PieceStatus.fromDb(` call site (`FulfillService`, `ReturnService`,
`ReturnSessionService` ×2, `PickupSessionService`, `StockTakeReconciliationService`) before
touching anything — all were already safe, each via an explicit status allowlist
(`switch`+`default→throw` or an explicit `if` set) or structurally excluded (`PickupSessionService`'s
SQL requires an active allocation, which an `out_on_transfer` piece never has). The one
exception delegating into the hole: `StockTakeReconciliationService.resolveMarkDamaged()`
calls `pieceAdjustSvc.adjustPiece(pieceId, "damaged", ...)` directly with no live-status
check of its own — confirmed it inherits the fix via delegation, no second fix needed.

**Fix**: new `PieceOutOnTransferException` (distinct from `PieceCommittedException` — there's
no order here, only the transfer that owns the piece), thrown by a new guard in
`adjustPiece()` right after the RESERVED/PACKED check, before `ledger.transition()` is ever
called — no partial state to unwind. Carries the blocking `transferId` (looked up from the
still-open `transfer_pieces` row). New `ApiExceptionHandler.handlePieceOutOnTransfer()` →
409 `{code:"PIECE_OUT_ON_TRANSFER", transferId, message_en, message_ar}`, telling the
operator to reconcile/close the transfer instead. `InventoryLedger.ALLOWED`'s three
reconcile-only edges got an explicit comment: "legal only via TransferService, which
updates transfer_pieces.outcome in the same tx. Any other caller moving a piece out of
out_on_transfer orphans the transfer row — guard your path."

**4 new tests** in `TransferReconcileTest`: all three reachable targets
(`toDamaged`/`toLost`/`toAvailable`) reject with `PieceOutOnTransferException` carrying the
correct `transferId`, piece status unchanged, `transfer_pieces` row still `outcome IS NULL`
(not orphaned), and `closeTransfer()` (after `beginReconcile`) still correctly throws
`TRANSFER_HAS_OUTSTANDING_PIECES` for that same, still-legitimately-outstanding piece — plus
one positive control proving an ordinary `available` piece still adjusts to `damaged`
normally (the guard is scoped, not a blanket regression).

Full backend suite green: 891 tests (887 + 4 new), 0 failures, 3 pre-existing skips.

---

**FR-22.9 — Frontend: Transfers & External Custody, RTL + ar/en (2026-08-04) — last FR-22 build step, done.**

Five screens, all off the existing StockTake/Fulfill scan-screen conventions (useScanner
+ ScanShell own all scan mechanics, no per-screen reimplementation):

- **`Transfers.tsx`** — consignment list (`listOpenTransfers()`, shows destination/type/
  status/since/outstanding) + create form (destination `Select` restricted to
  `is_fulfillment=false` locations via a new `listTransferDestinations()` filter,
  transferType segmented buttons, optional expected-return date + note).
- **`TransferScanOut.tsx`** — full-screen send-out scan (NOT Layout-wrapped, mirrors
  `/stock-take/:id/scan`), open-status only (redirects to detail otherwise), per-variant
  running `qty_out` table refetched via `getTransfer()` after each successful scan (the
  scan response alone doesn't carry a variant display name for a line it just created).
- **`TransferDetail.tsx`** — header + lines table + status-gated actions: scan-out-more
  (any role, open only), begin-reconcile (OWNER/MANAGER, disabled until outstanding≥1),
  reprint-outstanding (OWNER/MANAGER, disabled at 0 outstanding), continue-reconcile
  (reconciling state).
- **`TransferReconcile.tsx`** — OWNER/MANAGER only (soft client-side gate, matching this
  codebase's existing convention — real enforcement is `@PreAuthorize` server-side): scan-
  back (good/condemned toggle) + per-line quantity classify (sold/lost/condemned-not-
  returned inputs, FIFO-applied server-side), live per-line balance (`qty_out` vs
  accounted), Close disabled until `transfer.outstandingCount === 0` — the same
  authoritative field `closeTransfer()` itself checks server-side, not a client-derived sum.
- Routes wired in `App.tsx`, nav entry added to `Layout.tsx` (ArrowLeftRight icon).

**Two response families rendered exactly as designed, no double-localization**: scan-family
rejections (`scanOutTransferPiece`/`scanBackTransferPiece`) render `message_en`/`message_ar`
straight off the response by current `i18n.language` — never re-derived from `code`.
Command-family failures (`create`/`begin-reconcile`/`classify`/`close`) go through a new
`TransferCommandError` (api.ts) that parses `{code, message_en, message_ar}` off the non-2xx
body — `request()`'s own error path only keeps `"<status>: <statusText>"`, same limitation
already documented above `resolveStockTake()`, so these use a dedicated `transferCommandRequest()`
fetch, one shared helper this time since there are four call sites (vs. stock-take's one).

**Backend gap closed to make the destination picker correct, not best-effort**:
`GET /api/v1/locations` didn't expose `is_fulfillment` — added it to `LocationController.list()`'s
SELECT (additive, no test asserted the old column set). Without it the frontend would have had
to guess which locations are valid destinations instead of filtering correctly client-side
(the backend already rejects `is_fulfillment=true` server-side in `createTransfer()`, but that's
a round-trip-and-fail UX, not a picker that's correct up front).

**FR-22.7 gap closed** (flagged, not fixed, in the FR-22.7 follow-up entry below): `Catalog.tsx`'s
hardcoded `STATUS_KEYS` now includes `out_on_transfer`/`sold`; `PieceCounts` interface in api.ts
gained both fields; `Badge`'s `STATUS_TONE` map gained tones for both (`warning`/`neutral`).
`Overview.tsx`'s Group A/B tiles needed no code change — they already derive labels live off
`catalog.statuses.${status}`, which was the only missing piece (confirmed by reading the
component, not assumed).

**i18n additions** (`en.json`/`ar.json`): full `transfers.*` tree (title/list/create/scanOut/
detail/reconcile, ~90 keys × 2 languages); `catalog.statuses`/`lookup.pieceStatus` gained
`out_on_transfer`/`sold`; `lookup.phrase` gained the six FR-22 timeline phrase keys
(`transferred_out`, `returned_from_transfer`, `condemned_at_vendor`, `sold_offbook`,
`lost_at_vendor`, `label_reprinted`) that `LookupService.phraseKey()` has emitted since FR-22.6
but the frontend never had translations for — found while wiring i18n for this step (a piece's
Lookup timeline would have silently fallen back to an untranslated, always-English
`phraseKey.replace(/_/g, ' ')` for any transfer-related event otherwise).

**E2E lifecycle test** (`transfersLifecycle.test.tsx`, `tl1`): create → scan-out 3 pieces of one
variant → begin-reconcile → scan-back 1 good + 1 condemned → classify the remaining 1 as lost →
close — driven through real `react-router` navigation across all four screens (not four isolated
renders), with a small mutable fake-backend model in the test (mirrors `qty_out`/
`qty_returned_good`/`qty_condemned`/`qty_sold`/`qty_lost`/`outstandingCount` bookkeeping) so the
UI's own balance-gating logic is exercised against realistic state transitions. Explicitly
asserts Close stays disabled with 1 piece still unaccounted for, then becomes enabled only after
the classify call brings `outstandingCount` to 0. One environment-specific fix during authoring:
`userEvent.type()` on `<input type="number">` didn't update the controlled value in this
jsdom/vitest setup — switched to `fireEvent.change()` for that one interaction.

Full suite green: frontend 62/65 passing (3 pre-existing unrelated failures, confirmed present
on a clean `git stash` of this branch before any FR-22.9 change — `ov4` chart rendering and one
`findByRole('alert')` timeout in `inventory.test.tsx`, not touched by this work); backend
887 tests, 0 failures, 3 pre-existing skips.

This is the last FR-22 build step. Full FR-22 lifecycle (backend + frontend) is ready for
end-to-end manual walkthrough before the branch is pushed.

---

**FR-22.8 follow-up — two pre-frontend backend confirmations (2026-08-04) — both found already correct, no production code changes.**

1. **`scanOut` open-status gate.** Confirmed `TransferService.scanOut()` already has
   `if (!"open".equals(transferStatus))` → bilingual `ScanOutResult.rejected("TRANSFER_NOT_OPEN", ...)`,
   which rejects BOTH `reconciling` and `closed` (added in the FR-22.6 follow-up, not new).
   Coverage gap only: the existing test covered `closed` but not `reconciling`. Split
   `scanOut_transferNotOpen_rejects` into `scanOut_transferClosed_rejectsWithNoRowWritten` and
   a new `scanOut_transferReconciling_rejectsWithNoRowWritten`, both now asserting
   `messageAr` is non-blank and that zero `transfer_pieces` claim rows get written.
2. **`listOpen()` predicate.** Confirmed the SQL already filters
   `t.status IN ('open', 'reconciling')`, not a literal `status = 'open'`, and
   `outstanding_count` is a live per-row subquery (`COUNT(*) ... WHERE outcome IS NULL`),
   correct at any point mid-reconcile. Coverage gap only: existing test only checked
   "returns non-empty." Added
   `listOpen_includesReconcilingTransfer_excludesClosedTransfer_countAccurateMidReconcile`
   to `TransferReconcileTest` — proves a reconciling transfer (5 out, 2 scanned back) reports
   `outstanding_count = 3` and appears in the list, while a fully closed transfer does not
   appear at all.

Full suite green: 887 tests, 0 failures, 3 pre-existing skips.

---

**FR-22.8 — Mode B guard + test (2026-08-04) — built and tested against Testcontainers.**

**Forward direction (transfers → Bosta), grep-confirmed:** zero references to
`integrations.bosta`, `BostaWebhookJob`, `ShipmentLinkService`, or any shipment/delivery/
pickup/courier vocabulary anywhere in `TransferService.java`/`TransferController.java`/
`TransferException.java`. The transfer paths cannot create or link a Bosta delivery because
they never call into that package — structurally, not by convention.

**Reverse direction (Bosta → transfers), no new guard code added — mirrors self-pickup
exactly.** Grepped `BostaWebhookJob`/`ShipmentLinkService` for any `self_pickup`/
`isSelfPickup` check first: zero hits in either file. Self-pickup's own "Mode B guard" is
not an explicit piece-type check — it relies entirely on (a) self-pickup orders never
getting a Bosta shipment row in the first place (structural), and (b) `InventoryLedger`'s
transition machine rejecting anything that doesn't fit. Matched that exact pattern for
transfers rather than adding a bespoke check:
- `BostaWebhookJob` reads a piece's CURRENT status and calls `ledger.transition()` with it.
  `InventoryLedger.ALLOWED` has no `out_on_transfer:*` entry for any Bosta-driven target
  (`with_courier`, `delivered`, `return_in_transit`, etc.) — `transition()` throws
  `IllegalTransitionException` before any DB write, caught by the job's existing
  `catch (IllegalTransitionException e) { log.warn(...); }` block (the SAME catch block
  that already handles self-pickup and every other "piece already past target state" case
  — not new code, not transfer-specific).
- `ShipmentLinkService.transitionPackedPieces()` (the `manualLink()` path) goes further:
  its own SQL pre-filters `p.status = 'packed'`, so an `out_on_transfer` piece is never even
  selected — an earlier, stronger guard than the ALLOWED check, and again not
  piece-type-aware, purely state-driven.

**Correction to the task's "routed to the exceptions list" framing.** Traced
`BostaWebhookJob`'s control flow end to end: after the per-piece catch-log-continue loop,
the webhook unconditionally proceeds to `webhook_events.status = 'processed'` — no error, no
distinct marker. `ExceptionService`'s ~15 detectors are all separate queries against
shipment/delivery state (stuck shipments, unmatched deliveries, etc.); none would surface a
single skipped piece transition. This is genuinely NOT a silent drop or a stacktrace — the
piece cleanly stays put, the webhook completes normally, nothing crashes, and
`log.warn(...)` is visible in application logs — but it is also not literally written to a
distinguishable exceptions-center row. This matches self-pickup's own existing, identical
treatment exactly; transfers were not silently given better treatment than self-pickup
already has for the same code path.

New `TransferModeBGuardTest` (2 tests, mirrors `BostaDay6Test`/`UnlinkedResolveTest`
harness conventions): each test seeds an `out_on_transfer` piece with a deliberately stale
allocation (a piece can never naturally reach this state under normal invariants — `scanOut()`
requires `available`, which precludes an active allocation — so the fixture is a constructed
proof, not a claim this is reachable in production) alongside an ordinary eligible piece on
the SAME order/webhook as a positive control. Confirms: the `out_on_transfer` piece is
untouched (status unchanged, zero new `piece_events`), the ordinary piece still moves
normally (proves the guard is specific, not an accidental blanket no-op), and the outer
operation (webhook / `manualLink()`) completes without throwing. The `Illegal transition
OUT_ON_TRANSFER → DELIVERED ... — skipping` warning was observed firing exactly as designed
during the test run.

Full suite green: 885 tests, 0 failures, 3 pre-existing skips.

---

**FR-22.7 follow-up — CatalogController.ALL_STATUSES widened (2026-08-04).** Grepped every
consumer of `pieceCounts` (backend: only `CatalogController` itself; frontend: `Catalog.tsx`'s
"N pieces" label + stock-breakdown chips) before touching anything — confirmed clean: nothing
sums `pieceCounts`/`total` as a sellable/on-hand number. `committed`/`available` (the real
sellable figures) are computed from three separate, independent SQL queries untouched by this
list. Added `out_on_transfer`/`sold` to `ALL_STATUSES` — before the fix, those pieces were
invisible in the breakdown and silently missing from `total`, which stopped summing to the
true piece count. New test (`i13`) proves the fix. Noted, not fixed: the frontend has its own
mirrored hardcoded `STATUS_KEYS` list (same 11 statuses, same gap) — needs display labels for
both new statuses, deferred to FR-22.9 per explicit instruction.

Full suite green: 883 tests, 0 failures, 3 pre-existing skips.

---

**FR-22.7 — Inventory summary buckets + exclusion tests (2026-08-04) — built and tested
against Testcontainers.** `InventoryController.summary()`: Group A (point-in-time) gains
`out_on_transfer` — "Out on transfer / At vendor" — consignment stock outside the warehouse,
not sellable, not pickable. Group B (windowed, 30-day) gains `sold` alongside
delivered/damaged/lost — a new terminal, and showroom sell-through is the primary reporting
value of the whole transfers feature; without it, sold pieces would be invisible in every
summary view. No new endpoint — same existing `/api/v1/inventory/summary`, already `@Transactional`
and already `RlsCoverageTest`-EXEMPT (unchanged, still valid: "all-zeros is valid initial state").

Exclusion proven, not assumed, in 4 places (new tests, no code changes needed in any of
them — `out_on_transfer` was already correctly excluded everywhere, by construction, since
none of these filters key on it):
- **Pick scan** (`Day9Test` i2): `FulfillService.scan()` rejects an `out_on_transfer` piece
  with `WRONG_STATUS`, same as any other non-available status.
- **Gather list** (`GatherListTest` b2): `availableCount` for a variant with 1 available + 2
  `out_on_transfer` pieces is 1, not 3.
- **Shopify on-hand formula** (`InventorySummaryTest` i12): `CatalogController`'s
  `on_hand(V)` (status IN available/reserved/packed/awaiting_pickup, at an
  `is_fulfillment=true` location) does not count an `out_on_transfer` piece even when it
  sits at that same location.
- **Summary itself** (`InventorySummaryTest` i10/i11): `out_on_transfer` pieces land only in
  their own bucket, not `available`; `sold` follows the identical windowing rule as
  delivered/damaged/lost (old sale outside 30d excluded, recent included).

**Found, not fixed — flagged for a separate decision:** `CatalogController`'s hardcoded
`ALL_STATUSES` list (used only for the raw `pieceCounts` per-status breakdown + its `total`
key) predates FR-22 and doesn't include `out_on_transfer`/`sold` — an out_on_transfer or sold
piece is invisible in that breakdown and silently missing from `total`. This does **not**
affect the `on_hand`/`available` formula (a separate, explicit SQL predicate, unaffected) or
anything tested above — it's a display-only undercount in the catalog page's per-variant
status chips. Not touched in this commit; wasn't in FR-22.7's explicit scope.

Full suite green: 882 tests, 0 failures, 3 pre-existing skips.

---

**FR-22.6 follow-up — createTransfer validation, a real cross-tenant leak (2026-08-04) — built
and tested against Testcontainers.** `createTransfer()` now validates before the INSERT:

1. **Cross-tenant location leak, confirmed and closed.** `destination_location_id uuid
   REFERENCES locations(id)` alone was insufficient — Postgres row security does not apply
   to foreign-key satisfaction checks, so the FK only proves a row exists *somewhere*, not
   that it belongs to the calling tenant. Tenant A could point a transfer at tenant B's
   location id and the FK would happily accept it. Fixed with an explicit `SELECT
   is_fulfillment FROM locations WHERE id = ? AND tenant_id = ?` before the INSERT — the FK
   remains as a second-layer guarantee, not the tenant boundary. New test
   (`createTransfer_crossTenantLocation_rejectsWithNoRowWritten`) proves it.
2. `destinationLocationId` pointing at the tenant's own `is_fulfillment=true` warehouse is
   now rejected (`TRANSFER_DESTINATION_IS_FULFILLMENT`) — can't transfer to yourself.
3. `transferType` is now validated against the same 4 values as the DB `CHECK` constraint
   (`showroom|dryclean|repair|other`) before hitting the DB. Correction to the original ask:
   the premise that "it's free text with no CHECK, so junk currently persists" was checked
   and found inaccurate — `V64` already has `CHECK (transfer_type IN (...))`, so invalid
   values were already structurally impossible to persist. The real gap was error *quality*:
   an invalid value surfaced as a raw `DataIntegrityViolationException` via the generic 400
   handler instead of a specific `TransferException`. Fixed for that reason.

All three new `TransferException.Code` values (`TRANSFER_TYPE_INVALID`,
`TRANSFER_DESTINATION_NOT_FOUND`, `TRANSFER_DESTINATION_IS_FULFILLMENT`) carry full
`message_en`/`message_ar` pairs.

**Bilingual audit (grepped every construction site):** all `TransferException` codes were
already fully bilingual — no gap there. The "scan family" (`ScanOutResult`/`ScanBackResult` —
`scan-out`/`scan-back`'s success/rejection responses) was **English-only by design**
(mirroring `FulfillService.scan()`'s `ScanResult`, which has no Arabic either). Per explicit
instruction this is now fixed: both records gained a `message_en`/`message_ar` pair
(`@JsonProperty`-annotated for the same snake_case wire shape as `TransferException`'s body),
and all 10 rejection call sites across `scanOut()`/`reconcileScanBack()` got real Arabic
text. This makes Transfers' scan family a superset of `FulfillService.scan()`'s shape (same
codes, extra fields) — deliberately not backported to `FulfillService` itself, out of scope
here.

Full suite green: 877 tests, 0 failures, 3 pre-existing skips.

---

**FR-22.6 — Transfers controller layer (2026-08-04) — built and tested against
Testcontainers.** `reprint-outstanding` (FR-22.5) has no transfer-status precondition — only
that the transfer exists and has ≥1 outstanding piece (`transfer_pieces.outcome IS NULL`);
works in both `open` and `reconciling` status.

`TransferController` now covers the full FR-22 surface. Two response families, deliberately
different shapes:
- **Scan family** (`scan-out`, `scan-back`) returns `ScanOutResult`/`ScanBackResult` directly
  as 200 JSON — success or clean rejection both — mirroring `FulfillController`'s `/scan`
  exactly (Invariant 7: same machine codes, frontend translates via `code` client-side, no
  server-side bilingual text at this layer, same as `FulfillService.scan()` already does).
- **Command family** (`createTransfer`, `beginReconcile`, `classifyShortfall`,
  `closeTransfer`) throws on failure. New `TransferException` (mirrors `ShopifyOAuthException`'s
  shape exactly: `Code` enum + messageEn/messageAr/httpStatus) replaces every
  `ResponseStatusException` these methods previously threw in FR-22.4/22.5 — those had no
  body at all under the generic `handleResponseStatus` handler. One new
  `ApiExceptionHandler.handleTransferException()` covers every `TransferException.Code` —
  the `StateConflictException` handler from FR-22.3 is untouched, not duplicated.
  `reconcileScanBack`'s condition-validation was moved from a thrown exception into a
  `ScanBackResult.rejected("INVALID_CONDITION", ...)` instead, so every scan-back response
  shares one shape.

`TransferService` gained `listOpen()` (open+reconciling, "what's outside our walls") and
`getTransfer(id)` (header + lines + outstanding count) — templated directly on
`ReceivingService.listSessions()`/`getSession()`. Both are `@Transactional(readOnly = true)`
and registered in `RlsCoverageTest` COVERED with seeded tests (Invariant 3 GET rule).

`LookupService.phraseKey()` gained the 5 transfer event types — trivial mapping since
`TransferService` already writes event_type strings identical to the target phraseKey names
(`transferred_out`, `returned_from_transfer`, `condemned_at_vendor`, `sold_offbook`,
`lost_at_vendor`). Also added `label_reprinted` (FR-22.5's reprint event, previously falling
through to the generic `status_changed` phraseKey, which is misleading for a from==to event)
— one key beyond the explicit list, flagged for visibility rather than silently bundled.

Not done (noted, not silently skipped): `createTransfer` doesn't pre-validate `transferType`
or `destinationLocationId` before the INSERT — an invalid value still surfaces via the
existing generic `DataIntegrityViolationException` handler (400, generic message), not a
`TransferException`. Wasn't in this task's explicit endpoint/requirement list; flagged rather
than silently expanded.

Full suite green: 874 tests, 0 failures, 3 pre-existing skips (12 new controller tests + 2 new
`RlsCoverageTest` entries).

---

**FR-22.4 metadata gap fixes + FR-22.5 — reprint outstanding labels (2026-08-04) — built and
tested against Testcontainers.**

Two real gaps found re-verifying FR-22.4 metadata against the target shape and fixed in their
own small commit first: (1) `available:out_on_transfer` never carried a `reason` key, only
`transfer_id` — now includes `reason` = the transfer's `transfer_type` (the only always-present
short categorical field on the transfer row). (2) `classifyShortfall`'s `sold` disposition
incorrectly carried `attributed_to: vendor` via a metadata builder shared across all three
dispositions — a sale is not a vendor loss and would have polluted the Phase-3 vendor-loss
report; `closeShortfallPiece()` now takes an explicit `vendorAttributed` flag (sold=false,
lost/condemned_not_returned=true). Confirmed with no code change needed: the balance guard
correctly rejects over-requests against still-outstanding pieces; `reconcileScanBack`/
`classifyShortfall` both gate on `status='reconciling'`; `sold` has zero outgoing entries in
`InventoryLedger.ALLOWED` (hard terminal, no un-sell path, as intended).

**FR-22.5 — `TransferService.reprintOutstandingLabels()` + `TransferController`.** Loops
`LabelService.generatePieceLabel()` (one page per outstanding piece — `transfer_pieces WHERE
outcome IS NULL`) merged into a single PDF via PDFBox's `PDDocument.importPage()`, and
`InventoryLedger.recordLabelReprinted()` (the existing no-status-change 4th piece_events write
path) per piece, all inside one `@Transactional` so all N reprint events land under the same
tenant GUC in one transaction. Deliberately does not reuse `ReturnSessionController`'s per-piece
reprint endpoint — that one is hard-gated to `return_pending_inspection`/`damaged` and would
reject an `out_on_transfer` piece outright.

First `TransferController` (`POST /api/v1/transfers/{id}/reprint-outstanding`,
`OWNER`/`MANAGER`) — created for this one endpoint only, ahead of the rest of FR-22's HTTP
surface (createTransfer/scanOut/reconcile*), which is FR-22.6. No new GET endpoints, so nothing
to register in `RlsCoverageTest` (POST-only, and it only audits GETs anyway).

Full suite green: 860 tests, 0 failures, 3 pre-existing skips.

---

**FR-22.2–22.4 — Transfers status machine + send-out + reconcile (2026-08-03) — built and
tested against Testcontainers.**

**FR-22.2 (gate G1, approved by Marawan):** `V65` adds `out_on_transfer`/`sold` to `piece_status`
via two separate `ALTER TYPE ... ADD VALUE` statements (nothing else in that migration file
references them — can't be used in the same transaction they're added in). `PieceStatus.java`
gets the two constants; `InventoryLedger.ALLOWED` gets the 5 transitions
(`available:out_on_transfer`, `out_on_transfer:{available,damaged,sold,lost}`). Pre-flight
confirmed no `.ordinal()`/`EnumType.ORDINAL`/`values()[...]` dependency anywhere on `PieceStatus`
(safe to append before `DESTROYED`), and the one `ORDER BY status`-adjacent hit
(`ReturnSessionService`'s `ORDER BY CASE p.status ...`) is an explicit priority list over a
5-value filter that excludes the new values. `damaged`/`lost` were already non-terminal before
this (`damaged:destroyed`, `damaged:lost`, `lost:available`) — `out_on_transfer:damaged/lost` are
additions to an already-open graph, not a first opening.

**FR-22.3:** `TransferService.createTransfer()` + `scanOut()`. Deviates from "mirror
`FulfillService.scan()` exactly" on purpose: `FulfillService.scan()` calls
`ledger.transition()` first and catches `StateConflictException` to return a clean rejection —
but `InventoryLedger.transition()` is a separate `@Transactional` bean, and when it throws while
*participating* in the caller's own transaction (default REQUIRED propagation joins the same
physical transaction), Spring marks that transaction rollback-only before rethrowing, regardless
of whether the caller catches it. The caller then returns normally but its own commit fails with
`UnexpectedRollbackException`. This isn't theoretical — reproduced it by pointing the identical
try/catch pattern at `FulfillService.scan()` itself (`Day9Test`'s own scan-race test fails the
same way under `-Dtest=Day9Test#j_scan_race...` in isolation, though it happens to pass when the
full class runs — a latent, pre-existing risk in code we didn't touch). Fix used in
`TransferService.scanOut()`: the race-deciding step is a plain `JdbcTemplate` INSERT into
`transfer_pieces` inside `scanOut()`'s own method body (no nested transactional-proxy boundary) —
its unique-index violation (`transfer_pieces_one_active`) is caught as `DuplicateKeyException`
with nothing to poison. `transition()` runs only after the claim wins and is left uncaught on
failure by design (an unrelated concurrent mutation should roll back the whole transaction, not
be silently absorbed). Verified via `psql` that Postgres treats `COMMIT` on an aborted
transaction as an implicit `ROLLBACK` with no client-visible error — confirms the loser path
needs no savepoint, the whole loser transaction (including its own earlier line-upsert write) is
discarded cleanly. Added `ApiExceptionHandler.handleStateConflict()` (409, `{code, pieceId,
expected, actual}`) — the first global mapping for `StateConflictException` in this codebase
(every other caller catches it locally); confirmed it runs after the `@Transactional` rollback
since `@RestControllerAdvice` sits outside the AOP transactional boundary. `sendOutRace` test now
captures any `Throwable` escaping each raw `Thread` into an `AtomicReference` and asserts both
are null, rather than only inferring correctness from success/rejection counters. Added
`docs/week5.md` (didn't exist) with a one-line deferred note about `FulfillService.scan()`'s
latent risk — diagnose-only, not fixed.

**FR-22.4:** `beginReconcile` (open→reconciling, atomic conditional UPDATE as its own race
guard), `reconcileScanBack` (good→available / condemned→damaged, same claim-before-transition
shape as `scanOut()` and for the identical reason — the plain `UPDATE transfer_pieces SET
outcome=... WHERE outcome IS NULL` is the race referee, 0 rows covers both "never outstanding on
this transfer" and "a concurrent resolve just won" as the same clean `NOT_OUTSTANDING_ON_TRANSFER`
rejection), `classifyShortfall` (FIFO `SELECT ... FOR UPDATE ... ORDER BY created_at ASC LIMIT n`
locks the exact pieces to resolve for the transaction's duration, so `transition()` doesn't need
its own claim step here — no concurrent racer can touch a locked row), `closeTransfer` (checks
`transfer_pieces.outcome IS NULL` directly — the authoritative signal — rather than trusting the
derived `qty_*` counters; zero outstanding rows implies balance by construction since every
resolution path increments its counter in the same transaction it resolves the row). `closeTransfer`
landed here instead of with `reprintOutstandingLabels` (originally bundled as 22.5) per explicit
build-order request this session.

Schema gap found and closed while building `classifyShortfall`: `transfer_pieces` had no
timestamp column, so "FIFO by transferred-out time" had nothing deterministic to order by
(`id` is a random `uuid`, unlike `pieces.id`'s time-sortable ULID). `V66` adds
`transfer_pieces.created_at timestamptz NOT NULL DEFAULT now()`.

Metadata honesty verified end to end: scan-back writes `verified:true, reconciliation:scan`
(+ `attributed_to:vendor` only for condemned — a good return isn't a vendor-attributed loss);
shortfall classification writes `verified:false, reconciliation:quantity_based,
attributed_to:vendor` for all three dispositions (sold/lost/condemned_not_returned, the last
sharing the `condemned` `transfer_pieces.outcome` value and `condemned_at_vendor` event_type with
the scan-verified case — distinguished by `outcome_verified`, not a second enum value, per the
spec's "no new status for condemned/lost" locked decision). Event-type strings match the FR-22
spec's future `LookupService` phraseKey vocabulary exactly (`transferred_out`,
`returned_from_transfer`, `condemned_at_vendor`, `sold_offbook`, `lost_at_vendor`) so FR-22.6 can
wire the phraseKey map directly without re-deriving what was written.

No `TransferController` exists yet — role gating (MANAGER/OWNER on
`beginReconcile`/`reconcileScanBack`/`classifyShortfall`/`closeTransfer`) is intentionally NOT
enforced inside `TransferService`; every other role-gated action in this codebase is
`@PreAuthorize` on the controller endpoint, never service-layer, and FR-22.6 is where that lands.

Same migration-count housekeeping as FR-22.1 each time a migration landed (`MigrationSmokeTest`,
`NotTracedBackfillTest`, bumped 3× across V65/V66). Full suite green: 853 tests, 0 failures, 3
pre-existing skips.

---

**FR-22.1 — Transfers & External Custody, schema only (2026-08-03) — built and tested against
Testcontainers.** Step 0 (diagnosis) found the spec's provisional "FR-21" number already taken
(FR-21 = Stock Taking, below) — renumbered to FR-22 in `docs/transfers-build-spec.md` before any
code landed. This commit is FR-22.1 only, per the commit plan: `V64__transfers.sql` adds
`transfers` / `transfer_lines` / `transfer_pieces`, each with `tenant_id` + `ENABLE`/`FORCE ROW
LEVEL SECURITY` + a `tenant_isolation` policy in the same migration (Invariant 3), plus the
`transfer_pieces_one_active` partial-unique index (`WHERE outcome IS NULL`) as the concurrency
referee for concurrent send-out scans — mirrors `allocations_piece_active_unique`. Destination
locations need **no schema change**: `location_type` already has `showroom` and `vendor`
(native enum since V1); showroom transfers use `type='showroom'`, dryclean/repair/other use
`type='vendor'`, and `LocationController.create()` already accepts `is_fulfillment=false` with
either type. The workflow distinction lives in `transfers.transfer_type`, not `location_type`.
**No piece-status enum or `InventoryLedger` change in this commit** — that's FR-22.2, posted for
review behind gate G1, not yet approved/committed.

RLS proof: new `TransferRlsTest` (Day10Test/InventoryLedgerTest app_user-harness pattern) inserts
a transfer as tenant A via the postgres/BYPASSRLS connection, then asserts over a real
`app_user` (non-BYPASSRLS) connection that tenant B's `SELECT` returns zero rows AND — the
positive control — tenant A's own `SELECT` still returns the row. `RlsCoverageTest` was not
touched: it audits GET *endpoints* (none exist yet for transfers; `TransferController` is
FR-22.6), not tables, and runs BYPASSRLS — it doesn't prove RLS. No new GET endpoints were added
this commit, so nothing to register there yet.

Housekeeping surfaced by adding a migration: two tests hardcode the total Flyway migration count
(`MigrationSmokeTest`, `NotTracedBackfillTest`) — bumped 62→63 and 7→8 pending-after-V56
respectively. Full suite green: 821 tests, 0 failures, 3 pre-existing skips.

**FR-21 — Stock Taking, Steps 0.5–6 (2026-08-02) — built and tested against LOCAL/Testcontainers
only; not run against production or any real Shopify store.** Built to
`docs/fr-21-stock-taking-build-spec.md` (backend, Steps 0.5–5) and
`docs/fr-21-step6-frontend-spec.md` (frontend, Step 6) end to end, per-step commits, Step 5
gated on explicit go-ahead as designed. **Step 6 (frontend) is now built** — list/create,
blind scan, review/reconciliation, and sync-status screens, wired to a new read/ops backend
slice (Step 6.1) and a scanner extraction shared with `/fulfill` (Step 6.2). Full feature is
frontend-and-backend complete; still gated on the same production caveat as everything else in
this section — LOCAL/Testcontainers only, no live Shopify store run yet.

**Step 0.5 (prerequisite, its own commit):** `ShopifyInventoryService.resolvePreconditions()`'s
store lookup was `... WHERE tenant_id = ? LIMIT 1` with no `ORDER BY` — nondeterministic on a
multi-store tenant (no `UNIQUE(tenant_id)` on `stores`). Fixed to
`ORDER BY last_sync_at DESC NULLS LAST LIMIT 1`, matching `ConnectionsController`'s existing
pattern. This gated Step 5 — a live decrement cannot ride a nondeterministic store pick.

**Steps 1–4 (schema, open+snapshot, blind scan, reconciliation+resolutions):** `V62` adds five
tenant-scoped tables (`stock_take_sessions/scope_variants/expected/scans/shopify_syncs`), each
with RLS in the same migration. `StockTakeService` (open/snapshot/scan) and
`StockTakeReconciliationService` (reconciliation report + resolutions + attest-complete +
cancel + finalize). Snapshot freezes the FULL piece population at the tenant's single
`is_fulfillment` location, every status, at open. Scan is blind and idempotent; cross-tenant and
genuinely-unknown barcodes are handled identically on purpose — under RLS they're
indistinguishable to the query, which IS the non-leak guarantee. Reconciliation buckets by
CURRENT live status (not the frozen snapshot status), so a piece that drifted since open shows
up where it actually is now. `InventoryLedger.ALLOWED` gained exactly one new edge,
`damaged:lost`, named to its one caller (`resolveLost()`). **`damaged → available` ("found it"
on a damaged piece) is deliberately NOT offered** — `PieceAdjustService.adjustPiece()` already
treats damaged as terminal (`AdjustTest.adj7`) and no code path reverses it; a scanned-good/
recorded-damaged mismatch surfaces as a read-only flag instead. `resolveLost()`'s drift guard
(`ledger.transition(expectedStatus = status_at_open, → lost, ...)`) falls out for free from the
existing optimistic-concurrency race guard — no bespoke drift-detection code needed.

**Step 5 (finalize + live decrement, explicit go-ahead required and given):** Two-phase finalize
— the mandatory rule was "never wrap the Shopify HTTP call in a DB transaction," learned once
already at Step 4 (`resolve()`'s `srt7` fix: a caught exception from a nested `@Transactional`
call still poisons the whole shared transaction under REQUIRED propagation) and reapplied one
level up. **Phase A** (`finalizeSession()`, one committed tx): atomic `open→finalized` guard,
delta = COUNT of `stock_take_missing` piece_events per variant (never expected-minus-counted),
claim insert (`UNIQUE(session_id)`), job enqueue. **Phase B** (`StockTakeShopifyPushJob`, a
JobRunr job): loads the claim and resolves preconditions in their own short committed
transactions, then calls `ShopifyGateway.pushStockTakeWriteOff()` — a brand-new dedicated method
that does NOT share code with `executeGraphQL()`/`adjustInventoryQuantities` (that shared path
wraps calls in Resilience4j retry-on-timeout, 3 silent attempts before any exception surfaces —
fine for the existing `@idempotent`-protected increment/move calls, unsafe for a non-idempotent
decrement that needs one unambiguous outcome). Exactly one HTTP attempt: a definitive rejection
throws `ShopifyException` (rethrown → JobRunr's own retry policy retries it, safely, nothing was
applied); a genuinely unconfirmed response (timeout, connection reset) throws the new
`ShopifyAmbiguousException` (swallowed after recording `failed_ambiguous` — never rethrown, so
JobRunr never auto-retries an outcome that might already have applied; surfaced for manual
verification via `referenceDocumentUri = traced://stock-take/{session_id}`). All per-variant
deltas ship in one `inventoryAdjustQuantities` mutation. `adjustInventoryQuantities` itself is
untouched — its positive-only guard stays the FR-17 v2 invariant. CLAUDE.md §7 amendment applied
verbatim, recording the dedicated-method carve-out.

**Step 6 (frontend, 2026-08-02) — three commits, diagnose-first.** Step 6.0 was a read-only
diagnosis pass against actual source (no code, no commit) that corrected several wrong
assumptions in the original draft spec before any building started: `GET /sessions` and
`GET /sessions/{id}` did not exist yet (had to be built, not just consumed); the Fulfill
scan infra (HID input, flash overlay, Web-Audio beep) was inline in `Fulfill.tsx`, not a
shared hook — needed extracting so stock-take didn't fork it; there was no camera fallback to
preserve; and the release-for-adjust 409 flow in `Lookup.tsx`'s `AdjustPanel` is an inline
warning card, not a `Modal` — the spec text calling it a modal was wrong. The corrected spec
was the ground truth for the actual build.

**Step 6.1 (backend read + ops endpoints):** `GET /sessions` (list, OWNER/MANAGER),
`GET /sessions/{id}` (detail incl. `shopifySync`, WORKER+), `POST .../sync/mark-resolved`
(only from `failed_ambiguous`), `POST .../sync/repush` (one fresh single attempt, reuses the
existing claim row under `UNIQUE(session_id)`). Mid-step, diagnosis surfaced a real gap: there
was no way to undo a blind mis-scan, and the spec had assumed one existed. Decision (given,
not inferred): add `DELETE /sessions/{id}/scan/{pieceId}` as an explicit delete, never an
upsert — an upsert would silently flip a piece's recorded condition on re-scan, unsafe in a
blind count. Deleting a non-existent row is a 204 no-op. Every isolation test in this step
pairs a cross-tenant negative with a same-tenant positive control — a same-tenant test that
returns empty is not proof of RLS, it's a bug wearing a green checkmark, and this bit the first
draft of the harness (see Gotchas: RLS-ordering).

**Step 6.2 (shared scanner extraction):** `useScanner`/`ScanShell` pulled the SAFETY-CRITICAL
scan blocks (HID input handling, flash overlay, beep, recent-scans ring buffer) out of
`Fulfill.tsx`'s `PickScreen` behind an `onScan` callback, preserving behavior verbatim —
UX-only extraction, no fetching inside the hook. `PickScreen` and `AwbLinkDialog` are
byte-for-byte untouched (`git diff --stat` empty on both files after the extraction).

**Step 6.3 (screens):** list/create (mirrors `Receiving.tsx`, reuses `/receiving/variants/search`
for scoped-session picking), a full-screen blind scan view (not `Layout`-wrapped, matches the
`/fulfill` precedent; condition mode toggle good/damaged; client-side 4-bucket tally since
workers can never see expected quantities — `/reconciliation` is OWNER/MANAGER-only by design,
so tallying from scan responses is structural, not a UX choice), a review/reconciliation screen
(per-variant rollup, 8 disposition buckets, Mark-lost gated on `completeCount`/attest, the
committed-piece 409 reusing the `AdjustPanel` inline-card pattern confirmed in 6.0 — not a
modal), and a sync panel covering all 4 `shopifySync` states. One spec/backend mismatch found
and resolved without pausing: the spec text says "Committed uncounted: Found-it works," but
`StockTakeReconciliationService.resolveFound()` unconditionally 409s for any piece whose live
status isn't `available`/`damaged` — there was no reasonable alternative reading, so committed
rows only expose Mark-lost. `resolveStockTake()` does its own fetch + 409 body parsing rather
than the shared `api.ts` `request()` helper, which has a pre-existing bug (never calls
`res.json()` on error, so it can't surface `PieceCommittedError` bodies over real network calls)
— scoped workaround, not a global fix; flagged here rather than widening blast radius. Frontend
tests at house depth on the scan and review screens (list/create skipped per instruction);
list/create screens introduced this codebase's first `useParams()`-dependent component test,
solved by wrapping the component in an explicit `<Routes><Route .../></Routes>` before handing
it to `renderWithProviders` (which itself only provides a bare `MemoryRouter`, no route
matching). 10/10 new tests green; full frontend suite has 3 pre-existing failures in
`blocklist`/`inventory`/`overview` tests, unrelated to this change (files never touched,
reproduce in isolation on main).

---

**FR-17 v2 — Traced-owned Shopify location + increment-only live inventory sync (2026-07-29) —
built and tested against LOCAL/Testcontainers only; not run against production or any real
Shopify store.** Built exactly `docs/traced-shopify-location-and-onhand-sync-spec.md` (the
updated FR-17 v2 revision — increment-only + damage-move model, replacing the earlier
`inventorySetOnHandQuantities` draft after a spec-mismatch was caught and the user re-issued
the correct version). Production Step-0 items (current Shopify locations per pilot, the
junk-location audit, which onboarding path each pilot used) are explicitly OPERATOR-RUN by
Marawan against prod — out of this slice's scope, not blocked on.

**Part A — provisioning:** `ShopifyLocationProvisioningService.ensureTracedWarehouse()`
handles both onboarding cases without needing to know which occurred: links an existing
`is_fulfillment=true` location (standalone signup, seeded by `AuthRepository`) or creates one
first (Shopify-first gap recorded 2026-07-28 in `requirements-checklist.md` 5.5 — `V14`'s
`provision_tenant_from_shopify` comment claiming "the import job" creates it was stale; it
creates none). Wired into `ShopifyImportJob.run()`, non-fatal on failure. Does **not** touch
the `provision_tenant_from_shopify` SECURITY DEFINER function — follows the existing
post-DEFINER-INSERT pattern already used for refresh-token fields in `ShopifyOAuthService`.
`LocationController.create()` gated to `is_fulfillment=true` only (previously unconditional
for any location); `fulfillsOnlineOrders=true` set on every location Traced creates. Junk-
location report (`GET /api/v1/locations/shopify-junk-report`) + guarded per-location cleanup
(`POST .../{id}/shopify-cleanup`, deactivates one Shopify location at a time) — nothing
deleted/deactivated automatically, list surfaced for Marawan first.

**Part B — activation:** `ShopifyCatalogActivationService.activateAll()` bulk-`inventoryActivate`s
every catalog variant at the Traced GID (required before any on_hand write lands — new
Shopify locations start with zero active inventory items); one bad variant doesn't abort the
batch.

**Part C — reconcile + guarded seed:** `ShopifyInventoryReconcileService.reconcile()` computes
Traced on_hand per variant (same formula as `CatalogController`'s `on_hand(V)`) and diffs
against Shopify's current "available" at the Traced GID — read-only, writes nothing. `apply()`
recomputes the same diff live (never trusts a stale client-held report) and seeds ONLY
variants where Shopify shows zero — a strictly-positive delta from 0. Any variant already
non-zero in Shopify is skipped and flagged for manual reconcile, never corrected up or down;
because the check is live, a re-run after a successful seed naturally no-ops instead of
double-adding. Every `apply()` call is audit-logged (`shopify_inventory_initial_seed`) and
recorded per-variant in `shopify_inventory_adjustments` (`trigger_type='initial_seed'`).

**Part D — live trigger wiring:** `ShopifyInventoryService` flipped from shadow rows to real
Shopify mutations, gated by a pre-Shopify-call idempotency check (a prior `'applied'` row for
the exact `(trigger_type, trigger_id, variant_id, location_id)` skips the call entirely — the
`ON CONFLICT` on INSERT alone isn't enough once the mutation happens before that insert) and
by `is_fulfillment=true` + `shopify_sync_status='linked'` on the triggering location, which is
also the *only* source of the `locationGid` ever passed to Shopify (structurally impossible to
target a different location's GID). Exactly three triggers: (1) receiving session close → `+N`
per variant; (2) return inspection → AVAILABLE → `+1` per piece (`return_pending_inspection →
damaged` still does nothing, unchanged); (3) a currently-**sellable** piece damaged in the
warehouse → `inventoryMoveQuantities` available→damaged, wired into
`PieceAdjustService.adjustPiece()` right after a successful `available→damaged` ledger
transition — the pre-existing RESERVED/PACKED 409 guard in that method is what keeps this to
the truly-sellable case; `ReturnService.markDamaged()` (the `return_pending_inspection→damaged`
verdict) is a separate method with no Shopify call at all. `inventorySetOnHandQuantities` does
not exist anywhere in `ShopifyGateway`/`ShopifyHttpGateway` — proven by a reflection-based test,
not a text scan (a text scan would also flag the doc comments naming the forbidden method).

**KNOWN GAP (recorded, not patched this round):** destroying/losing a currently-sellable piece
overstates Shopify's sellable stock — no trigger closes this; closing it needs an approved
narrow decrement or move-to-unavailable, a separate decision.

**Accepted consequence (documented, not implemented):** Shopify sums storefront availability
across all online-order-fulfilling locations; because Traced never touches the store's old
default location, the storefront shows the sum of both until the merchant empties/de-lists the
old one — added to the go-live acceptance checklist as a merchant-performed step, not a code task.

Tests: `ShopifyLocationProvisioningTest` (fulfillment gate, idempotent re-provisioning, both
onboarding cases, junk report + guarded cleanup), `ShopifyCatalogActivationTest`,
`ShopifyInventoryReconcileTest` (first-pass-writes-nothing, positive-delta-only seed, non-zero-
skip-never-correct, re-run-is-noop), rewritten `ShopifyInventoryTest` (live-call assertions,
location-target guard, full damage-trigger matrix), new package-scoped
`ShopifyHttpGatewayInventoryTest` (positive-delta/quantity validation pre-network-call).
`RlsCoverageTest` gains the two new GET endpoints. V60 migration widens
`shopify_inventory_adjustments.trigger_type` (`damage_move`, `initial_seed`); fixed two
pre-existing tests that hardcoded the total migration count. Full backend suite green
(`mvn test`). **Not deployed, not run against production.**

**Concurrency hardening (2026-07-30) — three check-then-act races found and fixed, all with
genuinely-concurrent tests (not sequential call-then-call), each verified to fail against the
pre-fix code and pass against the fix.** The original Parts A/C/D used SELECT-then-act guards
with a race window: two concurrent identical triggers (a retry overlapping the original, a
duplicate webhook, two operators clicking the same button) could both pass the check before
either wrote anything.
- **Part D**: `ShopifyInventoryService.claim()`/`markResult()` replace the SELECT with
  `INSERT ... ON CONFLICT (trigger_type, trigger_id, variant_id, location_id) DO UPDATE ...
  WHERE status='failed'` — the INSERT itself (gated by the V48 unique constraint) is the
  guard; a `'pending'`/`'applied'` row already there means 0 affected rows, no Shopify call.
  A `'failed'` row IS reclaimed for retry (case c, covered by `si14`).
- **Part A**: `ShopifyLocationProvisioningService.linkShopifyLocationIfNeeded()` uses the
  analogous conditional UPDATE (`unsynced`/`error` → `pending`). V61 adds
  `UNIQUE(tenant_id) WHERE is_fulfillment=true` as an independent, complementary invariant —
  surfaced a real fixture bug (standalone signup already seeds one `is_fulfillment=true`
  location; several tests were inserting a second one for the same tenant).
- **Part C**: `apply()` takes a per-tenant `pg_advisory_xact_lock` (transaction-scoped,
  confirmed NOT the session-scoped form — no leak-on-throw failure mode) held for the WHOLE
  operation including the Shopify calls — deliberate, since `apply()` is a manual,
  one-tenant-at-a-time action, not a hot path like Parts A/D.
- Removed the bare `@idempotent` directive from all three mutations — it carried no key and
  was decorative; a test now guards against reintroducing it without a real key.

CLAUDE.md's FR-17 gated replacement (verbatim v2 wording + location-target sentence + a new
claim-before-call paragraph) went in as the last, approved step.

**Connect flow made fully automatic (2026-07-30) — Parts B/C no longer require manual
approval.** `ShopifyImportJob.run()` now chains provisioning → catalog import → activation →
on_hand seed as ONE flow on every connect/reconnect. Previously Parts B (`activateAll()`) and
C (`apply()`) were operator-triggered only, via the reconcile-report-then-approve-then-apply
sequence on `ShopifyInventorySyncController`. Now both run automatically right after catalog
import (they need variants to exist first), each in its own try/catch (non-fatal, retried on
next reconnect since each is independently idempotent). `apply()` is called with
`actorUserId=null` (a system action, not an operator one — `AuditService.record()` already
supports null for this). All of Part C's guards are unchanged: the per-tenant advisory lock,
live-recompute-then-skip-nonzero, structurally-enforced positive-delta-only. The manual
endpoints on `ShopifyInventorySyncController` are unchanged and still available — `GET
/reconcile` remains a standalone read-only report; all three remain usable for manual
re-trigger if an automatic attempt fails. Test: `ShopifyImportTest.
connectFlowIdempotency_oneLocationEverCreated_onHandSeededOnceNotTwice` runs the full path
across three `importJob.run()` calls (simulating repeated reconnects) and asserts exactly one
Shopify location is ever created and the on_hand seed fires exactly once, not on a later
reconnect once Shopify already reflects it. Still not deployed — Marawan deploys manually.

---

**Location `is_fulfillment` flag + Committed/Available inventory columns (2026-07-28) —
shadow mode only, no Shopify write anywhere in this slice.** Built exactly the approved
`docs/location-sync-flag-and-committed-column-spec.md`, with two corrections after Step-0
verification surfaced real divergences (flagged and resolved with Marawan before writing
code):

1. **Column named `is_fulfillment`, not `syncs_to_shopify`.** V48 already added
   `shopify_location_id` / `shopify_sync_status` (`unsynced/pending/linked/error`) /
   `shopify_sync_error` / `shopify_synced_at` to `locations` — `LocationController.create()`
   already performs a **live** Shopify location-creation write today (pre-existing, untouched,
   out of scope here). Those track technical link state; `is_fulfillment` is a separate
   business flag ("does this location's stock count toward the shadow number"), independent
   of link state — reusing `shopify_sync_status='linked'` would have made the shadow
   computation empty until a location is actually linked, breaking "no-op today." V59
   migration: `ALTER TABLE locations ADD COLUMN is_fulfillment boolean NOT NULL DEFAULT false`
   + backfill `WHERE is_default = true`.

2. **Only the standalone-signup seed path was touched.** Checked all three assumed seed
   paths per Step 0: `AuthRepository.createTenantWithOwner` (standalone) does seed a Main
   Warehouse — now sets `is_fulfillment=true` explicitly, not relying on the column default.
   `provision_tenant_from_shopify` (V14 DEFINER function) and `ShopifyImportJob` — checked in
   full — create **zero locations** for Shopify-first tenants; the V14 comment's claim that
   "the import job" creates it is stale. **Gap recorded, not fixed in this slice** (see
   `requirements-checklist.md` 5.5): Shopify-first / App Store onboarding needs a Main
   Warehouse seed added to that path before go-live.

Part 1c shadow guard: `ShopifyInventoryService.insertAdjustmentRow` now looks up
`locations.is_fulfillment` for the triggering `locationId` and skips inserting the shadow
row entirely (not even a `'failed'` row) when false — no aggregate query existed to add a
WHERE clause to; the real shadow path is delta/event-based (`onReceivingSessionClose` /
`onReturnInspectionAvailable`), so the guard lives at the row-insertion point instead.

Part 2 Committed/Available: extended `CatalogController.list()` (`GET /api/v1/catalog`) —
**derived on read every request, no stored counter, by design** (a maintained counter needs
correct upkeep on create/pack/cancel/edit/restock, the "forgotten parallel path" bug class).
`committed(V)` = `sum(order_items.quantity)` for orders in `{new, confirmed, ready_to_pick,
picking, packed, awaiting_pickup}` (order_items carries no location column — inherently
tenant+variant scoped, not location-scoped). `on_hand(V)` = pieces at `is_fulfillment=true`
locations in `{available, reserved, packed, awaiting_pickup}` — **this is location-scoped,
unlike the existing tenant-wide Total/Stock-breakdown counts, and will intentionally diverge
from those badges once a second (non-fulfillment) location exists.** `available = on_hand -
committed`, not floored at zero (a negative number is a real oversold signal). Frontend:
Catalog page gets new Committed/Available columns between Total and the existing Stock
breakdown badges (Reserved stays one of those badges, untouched); proper `catalog.columns.*`
en/ar i18n keys added (previously only `defaultValue` fallbacks — Arabic was silently
showing English column headers for Product/Variant/Total/Stock breakdown; fixed as part of
this pass).

Tests: V59 migration/backfill (own Testcontainers instance, seeds pre-V59 data to prove the
backfill fires correctly, not just that the column exists); standalone-signup provisioning
assertion (no Shopify-first equivalent — the gap above); shadow-guard positive/negative
control in `ShopifyInventoryTest` (si6); `CommittedInventoryTest` — exact-sum positive
control across all 12 order statuses (proves both inclusion and exclusion, not a trivial
always-zero pass) + full order-lifecycle identity test (`available` constant until courier
handoff, then `on_hand`/`committed` drop together); `RlsCoverageTest` promotes
`/api/v1/catalog` from EXEMPT to COVERED with a seeded positive-control assertion. Full
backend suite green. Frontend `tsc --noEmit` clean, `vitest run` 51/54 (3 pre-existing
unrelated chart-rendering failures, confirmed present before this work via `git stash`).
**Not deployed** — merged-to-main code only, per instruction.

**Pick & Pack queue gate tightened (2026-07-28) — no-shipment orders no longer stay in
the queue by default.** Confirmed before writing anything: `getQueue()`'s committed WHERE
clause was
```sql
WHERE o.tenant_id = ?
  AND o.status IN ('new','ready_to_pick','self_pickup_pending')
  AND o.on_hold = false
  AND o.placed_at > now() - (? * INTERVAL '1 day')
  AND (latest_shipment.internal_state IS NULL OR latest_shipment.internal_state = 'created')
```
plus the `LEFT JOIN LATERAL` on `shipments` the last condition depends on. The `IS NULL`
branch meant an order with **no linked Bosta shipment at all** stayed in the queue right
alongside a legitimate 'created'-state order — wrong for a normal Bosta order ("Shipment
not created" means the Shopify plugin hasn't handed it to Bosta yet, nothing to physically
pick). Self-pickup signal confirmed: `orders.is_self_pickup` boolean column (also selected
in `getQueue()`'s output). Confirmed reliable regardless of shipment state — self-pickup
orders never get a forward shipment by design: `complete()` routes `is_self_pickup=true`
orders straight to `self_pickup_pending`, skipping the AWB-link step entirely, and
`convertToSelfPickup()` sets `is_self_pickup=true` in the same UPDATE as the status flip.

Fix: `(latest_shipment.internal_state IS NULL OR ... = 'created')` →
`(o.is_self_pickup = true OR latest_shipment.internal_state = 'created')`. Since
`PICKABLE_ORDERS_FILTER` is the single shared predicate used by both `getQueue()` and
`getGatherList()` (FR-8.7's own fix, same day), this tightening applies to gather too —
correct and intended, same "Shipment not created ⇒ nothing to pick" logic applies to the
consolidated gather view exactly as it does to the per-order queue.

Collateral from tightening a shared predicate: every existing test fixture that seeds a
non-self-pickup order with no shipment row now needs one (a forward shipment in 'created'
state) to stay gatherable/queueable — this touched `GatherListTest`'s `insertOrder()`
helper, one `RlsCoverageTest` gather fixture, `PickQueueRecencyTest`'s `insertOrder()`
helper (gained a `selfPickup` param + companion shipment insert), and one test in
`Day9Test` (`a_queue_shows_new_and_ready_orders_oldest_first`, added shipments only for
the two orders expected in-queue). `QueueSendStateGateTest.e` was the flipped assertion
itself, renamed to `e_noLinkedShipment_notSelfPickup_excluded`; verified as a real
regression guard (reverted the gate locally, confirmed it fails, restored, confirmed
green) — same for `PICKABLE_ORDERS_FILTER`'s own diff.

`not_traced` tagger, backfill, and detector untouched — this was one clause in
`getQueue()`/`PICKABLE_ORDERS_FILTER`. 715/715 backend suite green. **Not deployed** —
this is a merged-to-main code change only, per instruction.

**FR-8.7 gather list: Item column shows product + variant, print in A6 (2026-07-28).**
Two changes, everything else from prior FR-8.7 fixes kept intact (shared
`PICKABLE_ORDERS_FILTER`, `GREATEST`-floored per-line remaining, active+packed allocation
subtraction, RLS via the GUC, no migration).

1. **Item column composition.** Confirmed before writing: `products` table, FK column
   `variants.product_id → products(id)`, product name column is `products.title` (not
   `name`). Confirmed `LabelService.renderPdf()`'s exact composition (was inline, not a
   method): `productTitle.isEmpty() ? variantTitle : ("Default Title".equalsIgnoreCase(
   variantTitle) ? productTitle : truncate(productTitle + " - " + variantTitle, 32))` —
   separator is `" - "`. Extracted the composition logic (not the label's 32-char
   truncation, which is a physical-label-width constraint, not part of the shared
   naming format) into a new `ProductDisplayName.compose()` helper in
   `com.traceability.inventory`, package-private, used by both `LabelService` and
   `FulfillService.getGatherList()`. Verified zero behavior change in `LabelService`:
   `Day8Test` 12/12, `VariantLabelTest` 13/13, `LabelRoundTripTest` 1/1, all green.
   Gather's aggregation query now joins `products pr ON pr.id = v.product_id` and adds
   `pr.title` to `GROUP BY` — required explicitly since Postgres only infers functional
   dependency within one table's own primary key, not across a join to a different
   table. New `GatherRow.displayName` field carries the composed string; frontend Item
   column renders it instead of the bare variant title. Added
   `GatherListTest.j_displayName_composesProductAndVariant_matchingLabelServiceFormat`
   (distinct product/variant titles → asserts the composed string).

2. **A6 print stylesheet — CSS/JSX-class only, no backend, no migration.** The app is
   dark-theme-only (`index.css`: `html, body { background: #0D1117; color: #F2F4F7; }`,
   no light variant), so printing needed an explicit light override, not a theme toggle.
   Added `@media print { @page { size: A6; margin: 6mm } ... }` in `GatherList.tsx`
   forcing white background / dark text on `.card`/`.bg-elevated`/text utility classes.
   Caught one real bug via Tailwind's `content:` build inspection: `hidden sm:table-cell`
   / `hidden md:table-cell` (SKU/Orders columns) evaluate their breakpoint against the
   print page's rendered width — A6 (~105mm) is well under the `sm:` 640px breakpoint,
   so those columns would have stayed hidden on paper. Fixed with Tailwind's built-in
   `print:table-cell` variant on both columns; confirmed present in the built CSS
   (`grep` on the compiled bundle: `@media print{...print\:table-cell{display:table-cell}}`).
   Shortage color accents (`text-danger`, `bg-danger/10`) intentionally left unoverridden
   — red-flagging a shortage on the printed sheet is the point, not a dark-theme leftover.

10/10 → 11/11 `GatherListTest` (new displayName test), 715/715 backend suite. Frontend
`npx tsc --noEmit` clean, `vitest run` 51/54 (3 pre-existing unrelated chart-rendering
failures in `overview.test.tsx`/`inventory.test.tsx`, confirmed present on `main` before
any FR-8.7 work, same baseline as every prior FR-8.7 commit this session).

**FR-8.7 production bug fixed (2026-07-28) — gather list was filtering on
`status = 'ready_to_pick'` only, missing every order still in 'new'.** The
new→ready_to_pick confirmation flow was never built, so real orders sit in `'new'` for
their entire pickable lifetime — `getQueue()` has always shown them (confirmed verbatim
predicate below); gather silently didn't, understating demand for anything not yet
manually confirmed to ready_to_pick.

**Confirmed `getQueue()`'s exact predicate before changing anything** (per instruction —
no hardcoded literal, no guessing):
```sql
WHERE o.tenant_id = ?
  AND o.status IN ('new','ready_to_pick','self_pickup_pending')
  AND o.on_hold = false
  AND o.placed_at > now() - (? * INTERVAL '1 day')   -- lookbackDays, default 30
  AND (latest_shipment.internal_state IS NULL OR latest_shipment.internal_state = 'created')
```
plus a `LEFT JOIN LATERAL` on `shipments` (latest `shipment_leg='forward'` row) that the
last condition depends on. Two details the original gather implementation missed
entirely: `'new'` and `'self_pickup_pending'` in the status set, and the `placed_at`
lookback window (gather had no lookback filter at all, and ordered/filtered on
`created_at` instead — a different column).

**Extracted a single shared SQL fragment** — `FulfillService.PICKABLE_ORDERS_FILTER`
(private static final String) — containing the LATERAL join + the full WHERE clause
verbatim. Both `getQueue()` and `getGatherList()` now use this same constant (requires
aliasing the orders table as `o` and binding `tenantId, lookbackDays` first); there is
now exactly one place this predicate is written, so the two screens cannot drift apart
again. `getQueue()`'s own behavior is unchanged by the refactor — confirmed via
`QueueSendStateGateTest` (7/7) and `PickQueueRecencyTest` (7/7), both green.

One consequence of including `self_pickup_pending` in gather's order set: those orders
are fully packed (`complete()` already flipped their allocations to `'packed'`), so the
allocation-subtraction subquery was widened from `status = 'active'` to
`status IN ('active','packed')` — matching `getQueue()`'s own `scanned_units` subquery
convention exactly. For `'new'`/`'ready_to_pick'` orders this is equivalent to the old
`'active'`-only check (their allocations are never `'packed'` while in those statuses);
for `self_pickup_pending` orders it correctly nets `needed` to 0 (nothing left to gather).

Test fixture gap that let the original bug ship 9/9 green: no test ever seeded a `'new'`
order. Added `GatherListTest.e_freshNewOrder_zeroAllocations_appearsWithNeededEqualToFullQuantity`
— verified as a real regression guard (temporarily reverted the predicate to
`ready_to_pick`-only, confirmed the test fails `expected 1, got 0`, restored, confirmed
green). Rewrote `d_statusFilter...` (now `includesNewAndReadyToPick`) since it previously
asserted `'new'` was excluded, which was the bug, not a spec. All fixtures needed a
`placed_at` column addition too (the shared predicate's lookback filter — NULL
`placed_at`, the column default, silently excludes a row); fixed the shared
`insertOrder()` test helper and one `RlsCoverageTest` fixture that was still NULL.
Test list renumbered a–j (was a–i). 10/10 `GatherListTest`, 11/11 `RlsCoverageTest`,
714/714 backend suite.

**FR-8.7 review round 2 (2026-07-28) — per-line flooring + tenancy cleanup on the
allocation-subtraction fix.** Marawan's review of the prior fix (below) caught two real
issues before accepting it:
1. **Missing per-line floor.** `SUM(oi.quantity - active_count)` had no `GREATEST(..., 0)`
   per order_item. One corrupted/stray-over-allocated line (active_count > quantity — should
   never happen given `scan()`'s FOR-UPDATE guard, but the aggregate must not silently trust
   that) would go negative and cannibalize a different, healthy order_item's demand within
   the same variant's SUM — the floor must apply per line, before the SUM, not after. Fixed:
   `SUM(GREATEST(oi.quantity - COALESCE(active_count, 0), 0))`.
2. **Two tenancy mechanisms in one statement.** The allocations/pieces correlated subqueries
   bound a fresh `tenantId` Java parameter (`a.tenant_id = ?`) — correct in value (it was the
   session tenant), but a second, hand-rolled tenancy check running parallel to the RLS GUC
   the rest of the method relies on, and inconsistent with this same file's own convention:
   `getQueue()`'s correlated subqueries never bind an explicit tenant_id at all (trust RLS
   alone). Fixed: dropped the bound parameters; subqueries now correlate tenant_id to the
   already-RLS-scoped outer row (`a.tenant_id = oi.tenant_id`, `p.tenant_id = v.tenant_id`),
   matching `getOrder()`'s existing `s.tenant_id = o.tenant_id` join style. RLS is the only
   tenancy enforcement in this query; the join equality is schema consistency, not a second
   boundary.

Verified the flooring fix is a real regression guard, not a trivially-passing assertion: ran
the new test with `GREATEST` temporarily reverted — it failed (`expected 4, got 3`) — then
restored the fix and confirmed green. Added
`GatherListTest.h_perLineFlooring_overAllocatedLineDoesNotCannibalizeOtherLinesDemand`
(variant with 2 order_items: one at quantity=2 with 3 stray active allocations, one healthy
at quantity=4 with zero → asserts total needed=4, not the un-floored 3). Test list is now
a–i (i = cross-tenant isolation, renumbered). 9/9 `GatherListTest`, 713/713 backend suite.

**FR-8.7 follow-up fix (2026-07-28) — needed now subtracts active allocations.** The
gather list's `needed` field summed the raw `order_item.quantity` per the original build
spec's assumption that `ready_to_pick` orders carry no allocations yet. That assumption was
already flagged false during the initial build (see entry below) — orders in this codebase
never transition to `'picking'`, so a `ready_to_pick` order can be mid-scan with active
allocations the whole time. Left as spec-literal at first per explicit direction; this
follow-up corrects it. `FulfillService.getGatherList()`'s aggregation query now computes
`needed = SUM(oi.quantity - active_allocation_count_for_that_order_item)` via a correlated
subquery on `allocations` filtered to `status = 'active'` (confirmed literal against
`allocation_status` enum in `V1__baseline.sql`: `'active'`/`'packed'`/`'released'` — a
`ready_to_pick` order's allocations can only ever be `'active'`, since `complete()` is what
flips them to `'packed'`, in the same transaction that moves the order off `ready_to_pick`).
`shortage = availableCount < needed` unchanged, but now correctly compares shelf stock
against remaining-to-gather instead of raw demand — this is also what makes the list
decrement across reloads as required by FR-8.7. Response field name kept as `needed`
(meaning changed, not the wire shape) to avoid a frontend/API churn for a field rename.

Added `GatherListTest.g_midPick_activeAllocationsReduceRemaining_andRecalculateShortage`
— the gap that let the original bug through: order quantity 5, 3 pieces already
actively allocated (reserved) + 2 unreserved on the shelf → asserts `needed` = 2 (not 5)
and `shortage` = false (the pre-fix code would have wrongly flagged shortage: raw needed=5
vs. available=2). 8/8 `GatherListTest` green, 712/712 backend suite green.

**FR-8.7 Gather List shipped (2026-07-28) — read-only pick-wave view, Option A only.**
`FulfillService.getGatherList(Integer limit)` aggregates all `ready_to_pick`, not-on-hold
orders into one row per variant (needed/availableCount/shortage/orderNumbers), exposed at
`GET /api/v1/fulfill/gather`. Pure read: zero Bosta calls, zero writes, scan/unscan/complete/
lock path untouched. No Flyway migration — read-only, zero schema change.

Grounding surfaced three divergences from the build spec, resolved with Marawan before
building:
- **Order number column is `orders.number`, not `order_number`** — trivial, just used the
  real name.
- **No image field exists anywhere** — not on `variants`, not on `products`, not in either
  table's `raw` jsonb. Confirmed structurally impossible to populate: `ShopifyHttpGateway`'s
  variant GraphQL query is `node { id sku title price }` (no image requested), and
  `ShopifySyncService` serializes only `{id,sku,title,price}` into `variants.raw` — never the
  real Shopify payload. Decision: dropped `imageUrl` from `GatherListResponse` entirely rather
  than wiring a jsonb read that can never return non-null.
- **The spec's core assumption — "ready_to_pick has no active allocations, so remaining =
  needed" — is false against this codebase.** `order_status` has a `'picking'` enum value but
  it is never assigned anywhere (zero hits in any `.java` file or migration); `FulfillService.
  requirePickableStatus()` allows locking/scanning while status is `'new'` OR `'ready_to_pick'`,
  and only `complete()` advances status (to `packed`/`self_pickup_pending`). So a `ready_to_pick`
  order can carry active allocations mid-pick the whole time. Decision (explicit, Marawan):
  follow the spec literally anyway — `needed = SUM(oi.quantity)`, no allocation-subtraction
  join. Documented inline in `FulfillService.getGatherList()` javadoc so a future session
  doesn't "fix" this into matching `requirements-checklist.md`'s "live decrement" line without
  re-confirming intent.

One more inconsistency resolved without a stop (not a real-name divergence, just spec-internal):
the aggregation section's filter text is `status = 'ready_to_pick'` alone, but the spec's own
test list requires "on-hold orders excluded" — added `AND on_hold = false`, mirroring
`getQueue()`'s existing convention.

Frontend: new `GatherList.tsx` at `/fulfill/gather` (no `Layout` wrapper, matching `/fulfill`
itself), reachable via a "Gather" button added to the pick queue header in `Fulfill.tsx`.
Refresh/Print/Back affordances, shortage rows highlighted with a "need X, have Y" note, RTL via
existing `text-start`/`text-end` logical classes (no extra RTL code needed — `dir` flips
app-wide off `i18n.ts`). i18n keys added under `fulfill.gather.*` + `fulfill.gatherBtn` in both
`en.json`/`ar.json`.

Tests: new `GatherListTest` (7 tests: positive control w/ overlapping variants, shortage
true/false, status filter incl. on-hold exclusion, empty, limit, cross-tenant isolation paired
with the positive control) — all pass. `RlsCoverageTest` updated: `/api/v1/fulfill/gather`
added to `COVERED` with a seeded non-empty-response test (`fulfillGather_returnsSeededDemand`)
rather than `EXEMPT`, since this aggregation spanning orders+order_items+variants+pieces is
exactly the C3 silent-zero-row class the build spec called out. Full suite: 711/711 backend
tests green, frontend `fulfill.test.tsx` 11/11 green (2 pre-existing, unrelated failures in
`overview.test.tsx`/`inventory.test.tsx` confirmed present on `main` before this change too —
chart-rendering flakiness, not touched by this slice).

Requirements checklist: 8.7 done; 8.7b partial (shortage *shown*, drop-into-exceptions still
deferred); 8.7a (wave locking) and 8.7c (FIFO suggestion) remain unchecked — out of scope for
this slice by design.

**UX fix — Waybill Session 404 now shows a friendly empty state (2026-07-27).**
`SessionTab.openSession()` in `Returns.tsx` caught every `POST /returns/sessions` failure the
same way, falling back to the literal `HTTP 404` string whenever the backend response had no
JSON body — which it never does for this case: `ApiExceptionHandler`'s
`ResponseStatusException` handler (`ApiExceptionHandler.java:138-141`) intentionally returns a
bodyless `ResponseEntity<Void>`, by design, for every plain `ResponseStatusException` in the
app (not touched — the backend 404 is correct). Fixed frontend-only: `openSession`'s catch
block now checks `status === 404` and renders a styled `EmptyState` (`returns.session.notInTraced`
= "This order isn't in Traced", with a "Switch to waybill-less intake" action reusing the
existing `onSwitchToIntake` prop) instead of the `Alert` error banner; any other status
(network/500/etc.) still falls through to the generic error banner unchanged. i18n en/ar
added. Tests: `rt2` rewritten for the new 404 behavior (previously asserted a `message` field
the real backend never sends), `rt2b` confirms the switch-to-intake action works, `rt2c`
confirms a 500 still shows the generic banner. 13/13 `returns.test.tsx` pass.

**Production bug fixed — restocked piece couldn't be re-allocated (2026-07-27).** Root cause
**B**, confirmed against prod (6 stuck pieces across both tenants, all identical shape:
`available`, `current_order_id NULL`, allocation still `'packed'`): `ReturnService.restock()`
(`ReturnService.java:172-174`) cleared `pieces.current_order_id` correctly but never released
the piece's old `allocations` row, and `FulfillService.scan()`'s ALREADY_RESERVED guard
(`FulfillService.java:192-198`) reads `allocations.status IN ('active','packed')` by
`piece_id` alone — the stale row blocked re-allocation forever.

- **Fix**: `restock()` now releases the piece's old allocation(s) in the same transaction,
  right after clearing `current_order_id`:
  ```sql
  UPDATE allocations SET status = 'released'
  WHERE piece_id = ? AND status IN ('active','packed')
  ```
  `'released'` reused verbatim — it's the existing value at all 6 `UPDATE allocations SET
  status` call sites in `FulfillService`/`PieceAdjustService` (unscan, cancel, unpack); no new
  enum value invented. Scoped to `restock()` only — damaged/lost are terminal, their stale
  allocations are inert, not touched (per the confirmed narrow-fix direction).
- **Second-order fix, required by the first**: releasing the allocation broke
  `ReturnSessionService.getSessionPieces()` (the fix two entries below) — its JOIN required a
  *live* `active`/`packed` allocation to resolve a piece's shipment, so a freshly-restocked
  piece stopped appearing in its own session's piece list the instant this fix landed. Widened
  the join to `IN ('active','packed','released')`, pinned to the single latest allocation row
  per piece (`ORDER BY allocated_at DESC LIMIT 1`) so a piece with unscan/rescan history
  doesn't fan out into duplicate rows — safe because ALREADY_RESERVED structurally prevents a
  second live allocation from ever coexisting with the shipped one.
- **Test** (`Day12Test.h_restockedPiece_reAllocatesToNewOrder_oldAllocationReleased`, the
  coverage gap that let this ship — `c_restock_...` never exercised an allocation at all):
  receive/pick/pack a piece to order A → ship → return → restock → assert the OLD allocation
  row is `'released'` (not `'packed'`) → scan the SAME piece into a brand-new order B via
  `FulfillService.scan()` → assert success (`SCANNED`, not `ALREADY_RESERVED`).
- **Backfill — V58** (`V58__release_stale_restocked_allocations.sql`), tenant-agnostic
  (Flyway runs as `postgres`/BYPASSRLS): releases every already-stuck allocation, gated on
  `pieces.status = 'available' AND current_order_id IS NULL` — a combination only reachable via
  `restock()` (or an equivalent legitimate free-and-release path), so it can never touch a live
  allocation for a genuinely in-flight order.
- 703/703 backend tests green (Day12Test +1, MigrationSmokeTest count → 57 for V1–V58,
  `NotTracedBackfillTest`'s "migrate past V56" assertion updated from 1→2 pending migrations
  now that V58 exists — confirmed harmless to that test's fixtures, which use `'packed'`
  pieces, not `'available'`).

**Production bug fixed — Waybill Session always showing "no pieces allocated" (2026-07-27).**
`ReturnSessionService.getSessionPieces()` (`ReturnSessionService.java:139-`, now ~185 lines)
filtered pieces to `p.status IN ('return_in_transit', 'delivered')` only. A returns-desk
session is opened AFTER the parcel has physically arrived — by which point state-46 intake
(or the session's own `recordVerdict`) has already advanced the piece to
`return_pending_inspection`, a status the old filter never included. Confirmed from prod
(tenant `e785e5e4`, order `c31d5a80`): zero rows, every time, not an RLS/GUC issue (method was
already correctly `@Transactional(readOnly = true)` with `TenantContext.require()`) and not the
`allocations` join (confirmed allocations stay `'packed'` in prod — nothing in the codebase
ever releases them on delivery/return, verified by grep across all 6
`UPDATE allocations SET status` call sites).

- **Fix**: widened the status filter to the full return lifecycle a piece can be in when this
  is called: `return_in_transit`, `delivered`, `return_pending_inspection`, `available`,
  `damaged`. The last two matter because a re-opened/re-viewed session (worker navigates back)
  must still show already-resolved pieces (restocked → available, or damaged) — the `processed`
  `EXISTS` flag already existed in the SELECT specifically to distinguish resolved from
  pending, but was dead code until now because nothing ever passed the old filter to reach it.
  Verified `ReturnService.restock()`/`markDamaged()` never touch `allocations` (only
  `pieces.current_order_id`, cleared to NULL on restock) — the allocations→order_items→orders
  join this method is built on still resolves correctly post-verdict, so already-resolved
  pieces really do reappear rather than silently vanishing from the list.
- Added `s.shipment_leg = 'forward'` to the shipments join (`ReturnSessionService.java`),
  matching `intakeScan`/`fetchPieceContext` in the same file — prevents wrong-leg fan-out once
  CRP return-leg shipments exist for an order. Forward-only is correct for RTO (RTO never
  creates a second shipment row; only CRP does).
- Did NOT touch `pieces.current_shipment_id` — confirmed that column does not exist. The
  piece→shipment link here is `current_order_id`-independent: `allocations.order_item_id →
  order_items.order_id → orders.id → shipments.order_id`.
- **Follow-up (same day): `ORDER BY` fixed.** The plain `p.status DESC` flagged above sorted
  by Postgres enum declaration order, putting terminal `damaged` ahead of the still-actionable
  `return_pending_inspection`/`return_in_transit` rows. Replaced with an explicit
  actionable-first `CASE p.status WHEN 'return_pending_inspection' THEN 0 WHEN
  'return_in_transit' THEN 1 WHEN 'delivered' THEN 2 WHEN 'available' THEN 3 WHEN 'damaged'
  THEN 4 ELSE 5 END, p.last_event_at ASC` — intent no longer depends on enum declaration
  order, so a future enum addition can't silently reshuffle this list again. Both
  `getSessionPieces` tests now include a sibling `damaged` piece and assert
  `return_pending_inspection` sorts above it, locking the ordering intent in the test suite
  instead of leaving it as a comment.
- **Tests — this method had ZERO coverage before this fix** (confirmed: none of the prior 17
  `ReturnSessionTest` tests ever called `getSessionPieces`), which is exactly how the bug
  shipped unnoticed. Added 2 new tests, both using real bare-numeric tracking numbers:
  - `r_getSessionPieces_returnPendingInspection_appears` — the exact prod repro shape: a
    `returned`-state shipment with a `return_pending_inspection` piece must appear
    (`processed=false`).
  - `s_getSessionPieces_restockedPiece_stillAppearsProcessedTrue` — after `recordVerdict`
    restocks a piece to `available`, re-calling `getSessionPieces` must still return it, now
    with `processed=true`.
  19/19 `ReturnSessionTest` pass. Full backend suite: 702 tests, 0 failures, 0 errors.
- Not deployed — pending your review and manual deploy approval.

**Production bug fixed — Pickup Session scan (2026-07-27).** Same bug class, second and last
of the two paths flagged in the previous entry as unfixed: `PickupSessionService.scan()`
(`PickupSessionService.java:187-221`, backing `POST /api/v1/pickup-sessions/{id}/scans` — the
pickup-manifest scan a worker does to confirm courier handover) did the same raw exact-match
`WHERE s.tracking_number = ?` with no normalization. Fixed identically to `createSession` in
`3390752`: normalize via `TrackingNumberNormalizer.normalize()` before the lookup, null → `400
Unreadable waybill scan`. Persistence: `pickup_shipments` has no `tracking_number` column at
all (it only stores the `shipment_id` FK), so there was no canonical-column decision to make —
the normalized value is used for the match and echoed in the `ScanEntry` response, the raw
scan is simply not retained anywhere. No schema change.

- All 9 `PickupSessionTest` fixtures generated fictional tracking numbers
  (`"TN-PS-" + random hex`, non-numeric) — the same "why the bug shipped invisibly" pattern as
  `ReturnSessionTest`. Replaced with a monotonic numeric counter (`shipments.tracking_number`
  is globally UNIQUE, not per-tenant, so a counter guarantees no cross-test collisions).
  Added 2 new tests (ps8, ps9): a hub-prefixed scan (`D-07-<n>`) against a bare-stored
  shipment now returns `ACCEPTED` with the normalized number echoed back; an unreadable scan
  is rejected with 400. 9/9 `PickupSessionTest` pass. Full backend suite: 700 tests, 0
  failures, 0 errors.
- `OrderController.list()`'s orders-list tracking search filter
  (`OrderController.java:139-141`, `GET /api/v1/orders?tracking=`) is **known and accepted
  as unnormalized — not a bug, not gated for a future fix.** It's a soft `ILIKE '%...%'`
  substring search box, not an exact-match gate blocking a workflow. A bare-digit tracking
  number substring-matches correctly regardless of what the merchant types around it; a
  prefixed paste just narrows/misses results, which degrades gracefully into "no results" —
  never a silent-data-loss or blocked-workflow failure the way the two fixed paths were.
  Deliberately left alone.
- Every scanned-waybill / tracking-number read path in the app now normalizes before matching,
  except the one above (intentional). Audit closed.
- Not deployed — pending manual deploy per usual process.

**Production bug fixed — Waybill Session 404 (2026-07-27).** `POST /api/v1/returns/sessions`
was returning an empty-body 404 for real, existing Bosta waybills. Root-caused across three
diagnose-only passes before touching code:

1. Confirmed the endpoint IS deployed/routed (`ReturnSessionController.java:28`, present since
   `f3c467d`, carried through every commit since) — not a deploy gap.
2. Confirmed `ReturnSessionService.createSession()` (`ReturnSessionService.java:55`) does the
   exact-match lookup `WHERE s.tracking_number = ?` against the RAW request body value, with
   no normalization — a write-normalized/read-raw mismatch. Same bug class as the AWB-link fix
   from `01e406b`: the physical Bosta label's top barcode carries a hub-routing prefix
   (`D-07-2944282510`) that `shipments.tracking_number` never stores (bare digits only), so a
   worker scanning the "Waybill Session" tab's physical label could never match, no matter how
   real the waybill was.
3. Fixed: `createSession()` now normalizes via `TrackingNumberNormalizer.normalize()` before
   the lookup — null → `400 Unreadable waybill scan`. **Persistence decision**: the normalized
   value is canonical everywhere downstream — `receipts.reference` and the response's
   `waybillNumber` field both store/echo the normalized tracking number, never the raw scan
   (matches `ShipmentLinkService`'s own precedent: `shipments.tracking_number` is
   normalized-only, no parallel raw-scan column at that level — raw-scan preservation only
   exists at the `piece_events.raw_scan` layer for piece transitions, which `createSession()`
   doesn't touch). No new migration — no schema change needed.
- 15 existing `ReturnSessionTest` fixtures used fictional labels like `"AWB-RTO-A"` (not
  numeric — this is *why the bug shipped invisibly*: no test ever exercised a real
  Bosta-shaped tracking number through this path). Updated the 9 that call `createSession()`
  to bare-numeric tracking numbers so normalization is a no-op for them, same as before.
  Added 2 new tests: a hub-prefixed scan (`D-07-2944282510`) against a bare-stored shipment
  now opens the session (not 404); an unreadable scan (`###garbage###`) is rejected with 400.
  17/17 `ReturnSessionTest` pass. Full backend suite: 698 tests, 0 failures, 0 errors.
- **Audit of every other scanned-waybill read path** (grep for `trackingNumber`/`tracking_number`
  across all controllers/services touching a request body or query param):
  - `ShipmentLinkService.linkByAwbScan()` (`ShipmentLinkService.java:122`) — normalizes. OK.
  - `LookupService` global scan lookup (`LookupService.java:192-203`) — normalizes
    (falls back to the raw query only if normalization fails). OK.
  - `PickupSessionService.scan()` (`PickupSessionService.java:187-221`, backing
    `POST /api/v1/pickup-sessions/{id}/scans`) — **does NOT normalize.** Same exact-match
    `WHERE s.tracking_number = ?` pattern against a raw scanned value. This is the pickup
    manifest scan (worker scans a physical AWB to confirm courier handover) — same physical
    label, same hub-prefix exposure. **Not fixed this session — flagged for a gate decision.**
  - `OrderController.list()` tracking search filter (`OrderController.java:139-141`,
    `GET /api/v1/orders?tracking=`) — **does NOT normalize.** Soft `ILIKE '%...%'` search box,
    lower severity (a UX miss, not a hard-blocking 404) but a prefixed scan pasted into the
    search box won't substring-match a bare-stored value either. **Not fixed — flagged.**
  - `ReturnController.intake()` takes a piece `barcode`, not a tracking number — not
    applicable to this audit.
- Not deployed — pending manual deploy per usual process.

**Fulfill AWB gating — COMPLETE (2026-07-27).** Frontend-only change to `Fulfill.tsx` — no
backend touched (confirmed: `linkByAwbScan`, `TrackingNumberNormalizer`, and the swapped-AWB
check are unchanged; that normalization bug was already fixed in an earlier session,
commit `01e406b`).

- **Precondition to Complete = linked AND printed.** The pack-Complete button
  (`POST /fulfill/{orderId}/complete`, unchanged API/condition) is now hidden until the
  order's Bosta AWB is linked (`tracking_number` set) and Print Waybill has been pressed at
  least once this session (`awbPrintedOnce`, client-side only — no new backend field).
  - Already-linked-when-picking-finishes (Mode-B pre-match raced ahead of the packer): the
    existing Print Waybill button — already wired, already gated on `tracking_number` — is
    what the packer presses; Complete appears right after.
  - Not-yet-linked (the mainline case): a new "Scan Waybill to Link" button opens
    `AwbLinkDialog` inline (not a blocking modal — unscanning a piece flips `allComplete`
    back to false and this section disappears with it) at the bottom of `PickScreen`,
    reusing the exact same `POST /fulfill/{orderId}/link` call unchanged. On success the
    dialog closes and the order refetches, at which point the same Print Waybill path above
    takes over.
  - Either path converges: linked → printed → Complete → the existing post-Complete
    `AwbLinkDialog` mandatory verify-scan (re-links/verifies the same tracking number via
    the unchanged Mode-B verify branch in `linkByAwbScan` — no duplicate shipment created).
- **"Skip — link later" removed entirely** (`fulfill.linkAwb.skip` key deleted from both
  locales) — the post-Complete verify-scan can no longer be bypassed, and the new pre-Complete
  step never had a bypass to begin with.
- **`AwbLinkDialog` generalized, not duplicated**: two new optional props, `onLinked`
  (pre-Complete usage calls this immediately on a successful scan instead of showing the
  print+done sub-view) and `variant: 'modal' | 'inline'` (pre-Complete usage skips the
  `fixed inset-0` backdrop — it's not a blocking dialog). All scan/error/flash/beep logic is
  shared, not copy-pasted.
- **Self-pickup untouched** — `order.is_self_pickup` bypasses the entire link/print gate,
  exactly as before; Complete still appears immediately once `allComplete`.
- Test gotcha worth remembering: `PickScreen`'s SAFETY-CRITICAL global click-refocus effect
  (keeps the top piece-scan input focused on any click) fights `userEvent.type()`'s synthetic
  click when typing into the AwbLinkDialog's own input — mirrors a real risk (a worker tapping
  to type manually would hit the same refocus), but a real HID scanner never clicks. Tests
  that need to type into that input use `input.focus(); user.keyboard(...)` instead of
  `user.type(input, ...)` to avoid the synthetic click, matching how a scanner actually behaves.
- New tests in `fulfill.test.tsx` (ft7–ft11): Complete hidden until Print Waybill pressed
  (linked path); unlinked order cannot Complete without scanning to link first; no skip
  button in either the pre- or post-Complete dialog; self-pickup unaffected. 11/11 pass.
- Pre-existing, unrelated frontend test failures noted but NOT touched (not caused by this
  change, confirmed against the pre-change tree): `blocklist.test.tsx` (fb7 Modal styling),
  `inventory.test.tsx` (api-error case), `overview.test.tsx` (ov4 chart SVG assertion).
- Not deployed — pending manual deploy per usual process.

**696 backend tests green** — 2026-07-27 (V57: Pick & Fulfill queue gating by Bosta send-state + "Not Traced" tag).

**Queue-gating-not-traced — COMPLETE.**

Production evidence (Jumi, `07fc572c-2158-412d-ae31-ec61e22378b7`): 58 orders stuck in the
Pick & Fulfill queue at `status='new'` whose Bosta shipment was already `delivered`/`returned`
— merchant fulfilled them directly via the Bosta app, never picked in Traced. All 58 are
terminal, so a go-forward detector alone would never touch them — the backfill is what
actually clears the queue.

- **V57 migration** (`orders.not_traced_at timestamptz`, partial index, backfill UPDATE).
  Flyway runs as `postgres` (BYPASSRLS, confirmed via `spring.flyway.*` / `DataSourceConfig
  .ownerDataSource()`), so the backfill UPDATE runs directly in the migration and spans all
  tenants in one pass — no fallback job needed.
- **`NotTracedTagger`** (new, `com.traceability.inventory`) — the one shared predicate. Tags
  an order when its latest `shipment_leg='forward'` shipment is sent to Bosta
  (`internal_state <> 'created'`) AND it has zero `active`/`packed` allocations. Self-contained:
  re-derives the latest forward shipment from the DB rather than trusting whatever shipment
  the caller just touched — this is what makes a CRP return-leg update structurally unable to
  mis-fire the tag. The exact same "latest forward shipment via `id DESC LIMIT 1`" shape is
  used in the V57 backfill SQL and in `FulfillService.getQueue()`'s new `LEFT JOIN LATERAL` —
  one shape, three call sites, cannot drift.
- **Two call sites for the tagger** (build-spec finding A — the real gap): `BostaWebhookJob
  .process()`, unconditionally after the piece-transition step (the not-traced case IS zero
  pieces, so gating on "pieces found" would make it never fire for the orders it exists to
  catch); and `ShipmentLinkService.manualLink()`. The second call site is required because a
  shipment can be **born already in a terminal state** — `manualLink()` (called by the
  operator's manual-link action, and by `BostaOrderReconcileJob`'s 5-minute automated
  reconcile) creates the shipment row from the `bosta_state_code` stored on
  `unlinked_bosta_deliveries` at discovery time, which may already be `delivered`/`returned`.
  That path never calls `process()`, and a terminal shipment is never polled again, so without
  this second call site the tag would silently never fire going forward for exactly this
  shape of order. `linkByAwbScan()`/`completeLink()` were checked and ruled out — they
  hardcode `internal_state = 'created'` at creation, so they cannot birth a terminal shipment.
- **Queue gate** — `FulfillService.getQueue()` excludes an order when its latest forward
  shipment state is anything other than `NULL`/`created`. Deliberately NOT also filtered on
  `not_traced_at` — the two mechanisms are independent (a `created`-state order that was
  wrongly tagged still shows up in the queue). `self_pickup_pending` orders never have a
  shipment row, so they're unaffected (regression-tested).
- **Frontend** — `orders.notTracedAt` threaded through `OrderSummary`/`OrderDetail`, neutral
  "Not Traced" badge in `Orders.tsx`, i18n `orders.badge.notTraced` (en/ar).
- Migration is **V57** (repo had moved to V56 since the spec was written — `MigrationSmokeTest`
  now asserts `migrationsExecuted == 56`, confirmed by an actual Testcontainers run, not
  hand-computed).
- Mode B held: zero Bosta API writes anywhere in this change — read/filter/tag only.
- New tests: `QueueSendStateGateTest` (queue filter incl. self-pickup + on-hold regressions),
  `NotTracedDetectorTest` (detector via `process()`, same-tenant positive control, idempotent
  re-run, `manualLink()` born-terminal path, RLS positive + cross-tenant via `app_user`),
  `NotTracedBackfillTest` (two-phase Flyway run: migrate to V56, seed the stuck population
  directly, migrate to V57, assert exactly the stuck order is tagged and a traced sibling
  isn't). Full suite: 696 tests / 0 failures / 0 errors.
- Not deployed — pending manual `--no-cache` deploy + post-deploy verification on Jumi per
  the build spec (`docs/queue-gating-not-traced-build-spec.md`).

**682 backend tests green** — 2026-07-21 (C3/C4/C5: RLS @Transactional fixes, lookup error codes, coverage guard).

**C3/C4/C5 RLS audit — COMPLETE.**

**C3 — 8 @Transactional(readOnly = true) fixes:**
- `UserService.list()`, `AuditService.list()`, `ShipmentLinkService.listUnlinked()`, `ReturnService.listPending()`, `ReturnService.neverReceived()`, `ReturnSessionService.listSessions()`, `ReturnSessionService.getSessionPieces()`, `OrderController.dailyCounts()`.
- Root cause: without `@Transactional`, `TenantAwareDataSource` never calls `setAutoCommit(false)`, GUC never set, RLS evaluates `tenant_id = NULL` → silent 0 rows on prod (app_user connection).
- `AuditService` and `OrderController` also needed the `import org.springframework.transaction.annotation.Transactional` added.

**C4 — Day10Test positive RLS assertions (e-pos/e-pos2/e-pos3):**
- Same-tenant PC-barcode, short-code, and tracking-number lookups via `appUserLookupSvc` now use `appUserTx.execute()` (a `TransactionTemplate` backed by `TenantAwareDataSource`).
- Key insight: `@Transactional` on a directly-instantiated bean (via `new`) has no effect — no Spring proxy. `TransactionTemplate` is the correct way to drive the GUC for test-constructed service instances.
- Test (e) upgraded from "GUC-never-set proxy" to actual cross-tenant RLS verification.

**C5 — Machine-readable lookup error codes:**
- `LookupNotFoundException` (extends `ResponseStatusException`) carries `code` and `query` fields.
- `ApiExceptionHandler` returns `{"code": "PIECE_NOT_FOUND", "query": "PC-xxx"}` for 404s.
- `DATABASE_ERROR` 500 remains the signal for system failures — workers see different UX.

**C3c — RlsCoverageTest (permanent build guard):**
- Reflectively discovers all GET /api/ handlers via `RequestMappingHandlerMapping`.
- Fails the build when a new GET endpoint is added without being in COVERED (has a test here) or EXEMPT (written reason).
- 9 COVERED patterns with seeded non-empty assertions; 36 EXEMPT patterns with reasons.
- `/api/v1/test/probe` (test-only `TenantProbeController`) added to EXEMPT.

**Local .env fix:** `APP_DB_USER=app_user.jtkzpjaangjtkrepkqdz` (Supabase project-ref suffix). Without this, local dev connected as `postgres` (BYPASSRLS) instead of `app_user`.

**669 backend tests green** — 2026-07-21 (FR-20 follow-ups B1/B2/B3 complete; ShopifyImportTest fixed).

**FR-20 follow-ups B1/B2/B3 — COMPLETE.**

**B1 — Mixed-script label rendering (production defect fixed):**
- **Root cause:** `containsArabic(labelName)=true` → entire string rendered with NotoSansArabic → Latin glyphs (A–Z) have no glyph → crash. Real pilot data contains mixed names like "Vanilla Whey - بروتين واي" constantly.
- **Fix — per-run font segmentation in `LabelService.drawTextRow()`:** `segmentRuns(displayRight)` splits shaped text into contiguous runs by script. Arabic runs → NotoSansArabic; Latin/neutral → Helvetica. Each run measured + positioned independently.
- **Critical gotcha — ICU4J Presentation Forms:** `ArabicShaping.LETTERS_SHAPE` converts Arabic from U+0600–U+06FF (main `ARABIC` block) to Presentation Forms (U+FE70–U+FEFF and U+FB50–U+FDFF). `Character.UnicodeBlock.ARABIC` does NOT cover Presentation Forms. A narrowly-scoped check then misclassifies all shaped Arabic chars as Latin → Helvetica → `IllegalArgumentException`. Fix: `isArabicChar(c)` covers `ARABIC` + `ARABIC_PRESENTATION_FORMS_A` + `ARABIC_PRESENTATION_FORMS_B` + `ARABIC_SUPPLEMENT` + `ARABIC_EXTENDED_A`.
- **4 font-rendering tests (i, i2, i3, i4):** pure Arabic → no exception; mixed "Widget VLT - مسحوق بروتين" → per-run, no .notdef; pure Latin → unchanged; Arabic+digits+Latin "بروتين واي 1000g" → renders at 203dpi.

**B2 — Test (e) restored to app_user:**
- **Fix:** `TenantContext.runAs(tenantAId, () → appUserTx.execute(s → appUserJdbc.queryForList(...)))` using the class-level `appUserJdbc` field (same `TenantAwareDataSource` as `appUserTx`). Spring's transaction manager binds to the datasource instance — the field is already bound when `execute()` runs.
- **Gotcha:** `JdbcTemplate appUserJdbc = ...` in the initializer creates a local variable that shadows the class field → field stays null → NullPointerException at use time. Must be `appUserJdbc = ...` (assigns to field).
- **`(e2)` added:** asserts `tenant_id == tenantAId` read as app_user — assertion meaningless under BYPASSRLS, which is exactly why it belongs in app_user context.

**B3 — ShopifyImportTest failures diagnosed and fixed:**
- **Cause:** `order()` helper used `Instant.now()` BEFORE `connect()` set `orders_ingest_from = now()`. The ms gap made `createdAt < cutoff` → 0 orders imported.
- **Fix:** `Instant.now().plusSeconds(60)` in `order()` helper. Unrelated to B1 (no variant/title involvement).

**FR-20 — Per-variant barcode label printing inside a receiving session — COMPLETE.**
- **Feature:** "Print Barcodes (N)" button per variant line in finalized sessions. Returns a PDF with one label per piece for that variant only; cross-variant deduplication if same variant appears on two lines.
- **V56 migration:** `ALTER TABLE label_reprints ADD COLUMN variant_id uuid NULL REFERENCES variants(id)`. Existing whole-session reprint rows stay NULL. Sparse index `WHERE variant_id IS NOT NULL`.
- **`LabelService`:** Added `VariantPdf record(byte[] pdf, String sku)`. `generateVariantLabels()` queries pieces filtered by `receipt_id + variant_id`, reuses `renderPdf()`. `reprintVariant()` inserts a `label_reprints` row with `variant_id` set. `requireFinalized()` helper shared by both session-wide and variant paths. `countVariantPieces()` helper for reprint row.
- **`ReceivingController`:** `GET /sessions/{sessionId}/variants/{variantId}/labels` (preview) and `POST /sessions/{sessionId}/variants/{variantId}/reprint` (audit-logged). SKU sanitized via `[^a-zA-Z0-9._-]→_`; falls back to `labels-{first8ofVariantId}.pdf` if SKU empty/null after sanitizing.
- **`ReceivingService.getLines()`:** Added `piece_count` subquery to the lines SQL so the frontend knows how many pieces each variant row has.
- **Frontend (`Receiving.tsx`):** `piece_count: number` on `Line` interface. `printingVariant` state + `printVariantLabels()` function. Table column unconditional (open: delete button, finalized + first occurrence + piece_count>0: "Print Barcodes (N)" button with loading state). IIFE with `Map<string, number>` deduplicates same variant across multiple receipt lines.
- **Locales:** `"printBarcodes": "Print Barcodes"` (en) / `"طباعة الباركود"` (ar).
- **13 integration tests (VariantLabelTest a–j + i2/i3/i4):** (a) page counts 26/20; (b) barcodes match DB pieces, no cross-variant leakage; (c) open session → 422; (d) variant not in session → 422; (e) label_reprints row correct via app_user + (e2) tenant_id == acting tenant; (f) double-line same variant → merged 15-page PDF; (g) cross-tenant RLS → 404; (h) WORKER → 403; (i–i4) Arabic/mixed/Latin/worst-case font rendering; (j) session-wide regression guard.
- **MigrationSmokeTest:** Updated V1–V55 count 54 → 55 (V56 migration).

**656 backend tests green (expected)** — 2026-07-21 (FR-19: short piece codes + 6 new tests; 2 pre-existing ShopifyImportTest failures unrelated to FR-19).

**FR-19 — Short piece codes for scannable thermal labels — COMPLETE.**
- **Problem confirmed:** 26-char ULID barcode rendered at ~0.129mm/module on a 44mm label. Decoded fine on screen (lb2 passed); unscannable on paper from thermal printer — ink bleed closes sub-0.191mm gaps.
- **Solution:** `P` + 6-digit sequential short code per tenant (e.g. `P000001`). 7 chars → 132 modules → 0.333mm/module = 1.75× GS1 general-use minimum.
- **V55 migration:** `piece_counters (tenant_id PK, last_value BIGINT)` with RLS + `GRANT SELECT, INSERT, UPDATE TO app_user`. `pieces.short_code TEXT NOT NULL UNIQUE (tenant_id, short_code)`. Backfill existing pieces via `ROW_NUMBER() OVER (PARTITION BY tenant_id ORDER BY created_at, id)`.
- **`InventoryLedger.batchReceive()`:** Round-trip 0 added — atomic counter claim via `ON CONFLICT DO UPDATE RETURNING last_value`. Claims N codes in one SQL; firstCode = lastValue - N + 1. Counter rolls back with transaction on failure — no leaked codes, but gaps allowed (C3). Now 3 SQL statements (was 2).
- **`LabelService`:** Both queries add `p.short_code`. `drawLabel()` signature simplified (barcode/pieceId params removed). Barcode encodes `shortCode`, human-readable caption shows `shortCode` (C1 fix: was wrongly showing PC-ULID).
- **`LookupController.isPieceQuery()`:** Third case added: `P` + exactly 6 digits (length 7). Namespace clean — no collision with Bosta AWBs (pure digits) or hub-prefixed AWBs (contain dashes, longer). All three formats (short code / PC-ULID / bare ULID) route correctly.
- **Scan lookup:** All 3 services (`FulfillService`, `LookupService`, `ReturnService`) add `OR p.short_code = ?`. `LookupService.lookupPiece()` also selects `p.short_code` and includes `shortCode` in response.
- **Tests:** lb1 (LabelRoundTripTest) updated to encode `P000001`, adds module-width assertion (≥ 0.191mm). lb2 (Day8Test k) asserts decoded == shortCode, format check. r9-r11 (LookupRoutingTest) cover short-code routing. sc1-sc3 (ShortCodeTest): sc1 non-overlapping ranges, sc2 P\d{6} format + uniqueness, sc3 lookupPiece resolves by short_code. MigrationSmokeTest: `piece_counters` added to RLS list, count 53→54.
- **All test fixture INSERTs into pieces** (30+ locations across 25 test files) updated to include `short_code = 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0')` derived from piece ID.
- **Next: PRINT one label and scan it physically.** lb2 passing on screen is necessary but not sufficient — last 26-char ULID decoded fine on screen and failed on paper.

**650 backend tests green (expected)** — 2026-07-21 (label barcode fix + search-box routing fix + 9 new tests).

**LABEL BARCODE BUG — FIXED.** Piece barcodes were unscannable on both handheld and phone:
- **Bug #1 (root cause):** `EncodeHintType.MARGIN = 0` — zero quiet-zone modules. Code 128 requires ≥10 modules of white space each side of the bars so scanners can locate START/STOP patterns. Without it, 100% decode failure regardless of scanner quality.
- **Bug #2:** bitmap generated at full label width (400px, 50mm) but drawn at 44mm — 0.88× scale mismatch.
- **Bug #3:** encoding 29-char `PC-`+ULID string produces 374 total modules for a 352px draw area; ZXing overflows, bars get compressed below 0.191mm GS1 minimum.
- **Fix:** `MARGIN=10`; bitmap sized to draw area (`barcodeW = label - 2×margin`); encode the 26-char ULID (pieceId) not the 29-char barcode → 341 modules fit cleanly in 352px at 0.129mm/module.
- **Scan backward-compat:** all 3 lookup sites (`FulfillService`, `LookupService`, `ReturnService`) now accept `(barcode = ? OR id = ?)` — old labels (PC-ULID) and new labels (bare ULID) both resolve.
- **Tests:** lb1 (pure unit: ZXing encode→decode, locks MARGIN=10 and quiet-zone pixel assertions) + lb2 (Day8Test: full PDF→PDFRenderer rasterize→ZXing decode, catches any regression).
- **Search-box routing bug (also fixed):** `LookupController` branched on `startsWith("PC-")` to decide piece vs AWB. Bare ULID scans failed this check and fell through to `lookupTracking()` → 404. Fixed: `isPieceQuery()` accepts both `PC-<ULID>` (old labels) and bare 26-char Crockford ULID (new labels). Bosta AWBs are pure digits ≤13 chars — no character-set collision. 8 routing unit tests (r1–r8) covering both formats, excluded Crockford chars (I/L/O/U), wrong lengths, AWB formats, hub-prefixed AWBs.

**639 backend tests green (expected)** — 2026-07-20 (V54 — FR-18 connection-anchored ingest cutoff). Deploy with `--no-cache`. Deploy to Hetzner: `ssh <user>@167.233.46.223 "cd /opt/traced && git pull && docker compose -f deploy/docker-compose.yml build --no-cache && docker compose -f deploy/docker-compose.yml up -d"`.

**FR-18 — Connection-anchored order ingest cutoff — COMPLETE.**
- `stores.orders_ingest_from TIMESTAMPTZ DEFAULT NULL` (V54). Set once at first connect via INSERT; never written in DO UPDATE / UPDATE_STORE_TOKEN reconnect branches. NULL = no cutoff (Jumi + all pre-FR-18 stores).
- All 5 ingest paths covered: (1) ShopifyImportJob → `max(now()-30d, cutoff)` as Shopify API `createdAfter`; (2) manual `/sync` endpoint (same job); (3) `orders/create` webhook; (4) `orders/updated` webhook; (5) ShopifyReconcileJob → same max() pattern. Paths 3+4 check via `ShopifySyncService.ingestOrderWebhook()` — if `placed_at < cutoff` and order not yet in DB → log INFO + skip. Existing DB orders always receive updates.
- Cache: `cutoffCache ConcurrentHashMap<UUID, Optional<Instant>>` in `ShopifySyncService` — loaded once per store per JVM lifetime (value is immutable after connect).
- `Optional<Instant>` — never a sentinel. Empty = no cutoff, explicit branch, safe.
- Merchant banner (todo frontend): "From now on, every new order will be tracked in Traced." EN+AR in Connections.tsx after connect success.
- 5 new tests (ic1–ic5): reconnect preserves cutoff; pre-cutoff skipped; post-cutoff ingested; existing order updated; NULL cutoff ingests all.

**⚠ PERMANENT DIVERGENCE — Jumi (mmi24e-fx) has `orders_ingest_from = NULL`:**
- Jumi connected 2026-07-02, before FR-18. Their ~50 existing orders are fully preserved.
- NULL means no cutoff forever. Jumi will always ingest all orders regardless of age.
- Every future store will have a non-null cutoff. This is intentional and correct — not a bug.
- Do not backfill Jumi's cutoff (would hide existing data). Do not "fix" the NULL.
- If a Jumi order/create webhook arrives for a historical order (unlikely), it will be ingested.

**⚠ DEFERRED — Shopify `created_at:>` filter is DATE-granular, not timestamp-granular:**
- Passing `2026-07-20T13:32:20Z` to Shopify's GraphQL filter behaves as `2026-07-20` (day boundary only).
- This is what caused the production incident: order placed 100s before the cutoff on the same calendar day passed Shopify's filter and was returned in the API response.
- The Java-side check in `importOrders()` loop and `ingestMissingOrder()` is the actual enforcement guarantee — the API filter is a bandwidth optimisation only.
- Applies to reconcile too: `now()-30min` as `createdAfter` actually fetches the entire calendar day from Shopify; pre-cutoff orders are discarded in Java. Correct but wasteful on a rate-limited API.
- Revisit if reconcile volume grows — options: switch to `updated_at:>` filter (timestamp-precise on Shopify) or use REST `updated_at_min` parameter which also has timestamp precision.

**634 backend tests green** — 2026-07-19 (V53 — AWB normalization + FR-7.4 hold/unhold + FR-7.5 COD editing).

**FR-7.4 Hold/Unhold with reason — COMPLETE (commit 936cf4a).**
- `POST /api/v1/fulfill/{id}/hold` — sets `on_hold=true`, requires `reason` string (400 if blank), 409 if already held, `hold_reason` column written.
- `POST /api/v1/fulfill/{id}/release-hold` — existed for FR-7.8a (blocked-customer); now also serves manual FR-7.4 release.
- Fulfill queue already excluded `on_hold=true` orders (gate was in place). No schema change needed.
- Frontend: Hold/Unhold button on OrderDetail status card; Modal with reason text input for hold; unhold is one-click. Localized en+ar.

**FR-7.5 COD editable until packing — COMPLETE (commit 936cf4a).**
- `PATCH /api/v1/fulfill/{id}/cod` — updates `cod_amount` when status IN ('new','ready_to_pick'). Frozen (409) at ≥ packed. 0 or negative → 400. > 30,000 → 400. Audit-logged.
- Frontend: COD row in OrderDetail shows pencil icon when editable; inline input with Save/Cancel; frozen label when status ≥ packed. Localized en+ar.

**Gotcha (coding):** `AuditService.record()` 5th param is `Map<String, Object>`, NOT a plain `String`. Passing a bare string causes compilation failure. Always use `Map.of("key", value)`.

**Bug fixed:** `releaseOrderHold` in `frontend/src/api.ts` was hitting `/api/v1/orders/{id}/release-hold` (→ 404). Correct path is `/api/v1/fulfill/{id}/release-hold`. `blocklist.test.tsx` fixture URL updated accordingly.

**AWB scan normalization (V53) — COMPLETE.** Four-part implementation:
1. `TrackingNumberNormalizer` — strips zero-width chars, trims, takes substring after last `-` if present, requires `^[0-9]+$`. 22 unit tests in `TrackingNumberNormalizationTest`.
2. Mode B verify path in `ShipmentLinkService.linkByAwbScan`: if an active forward shipment exists → check tracking match → verify (no INSERT) or throw `AwbMismatchException` (409). No INSERT reachable on mismatch. Verify path uses `shipment_leg='forward'` filter explicitly.
3. V53 migration: `piece_events.raw_scan TEXT` (nullable, no index, no backfill). Raw scan stored verbatim on `tracking_linked` events. LookupService.lookupTracking normalizes search query.
4. 8 integration tests in `AwbScanNormalizationTest` — ALL assertions via `appUserJdbc`/`appUserTx` (RLS-scoped).

**Side fix during Part 4:** `transitionPackedPieces` query now also filters `pieces.status = 'packed'` to prevent calling `ledger.transition` on pieces already at `awaiting_pickup` (idempotent re-scan case). Without this, a second scan marked the outer `@Transactional` as rollback-only via `StateConflictException` even though the catch block handled it.

V48 — FR-17 Phase 1: Shopify inventory shadow sync (no mutation until scope + flag enabled).

**Migration V48** — `shopify_inventory_adjustments` table (batch_id, variant_id, location_id, shopify_inventory_item_id, shopify_location_id, delta, trigger_type, trigger_id, payload jsonb, status shadow/pending/applied/failed, shopify_response jsonb, error, applied_at) + RLS + app_user grants. `stores.shopify_inventory_sync_enabled BOOLEAN DEFAULT FALSE`. `locations` sync columns: shopify_location_id, shopify_sync_status (unsynced/pending/linked/error), shopify_sync_error, shopify_synced_at. UNIQUE index on (tenant_id, shopify_location_id) WHERE NOT NULL.

**Invariant (never relax without explicit approval):** Traced only INCREMENTS Shopify inventory. Shopify owns decrements. Two trigger points: (1) `ReceivingService.finalize()` after session commit; (2) `ReturnService.restock()` after RETURN_PENDING_INSPECTION → AVAILABLE. Damaged pieces are explicitly excluded — no sync call in `markDamaged()`.

**`ShopifyInventoryService`** — `@Async` + `TenantContext.runAs()` outer wrapper. Per-trigger: resolve store, shopify_location_id (must be `linked`), variant GID → inventoryItem GID (via `resolveInventoryItemId`, uses already-granted `read_products`). Inserts rows with `ON CONFLICT (trigger_type, trigger_id, variant_id, location_id) DO NOTHING` for idempotency. Resolution failures → `status='failed'` with `error` column. Shadow mode: all rows created as `status='shadow'`, no Shopify mutation called. Returns `CompletableFuture<Void>` for testability.

**`ShopifyGateway` + `ShopifyHttpGateway`** — added `resolveInventoryItemId(shopDomain, token, variantGid)`: `productVariant(id:$gid) { inventoryItem { id } }` query, returns inventoryItem GID. Also `executeGraphQLPublic()` package-private method for future `ShopifyLocationGatewayImpl` use.

**`ShopifyLocationGateway`** — interface with `findByName()` + `create(LocationInput)`. `StubShopifyLocationGateway` is `@Primary` and throws "scope not yet granted" on every call. `ShopifyLocationGatewayImpl` is a POJO (no `@Service`) — written with real `locationAdd` mutation but not in the Spring context. To activate: add `@Service @Primary` to impl, remove `@Primary` from stub.

**`ShopifyInventoryController`** — `GET /api/v1/shopify-inventory/adjustments` (filterable: status, triggerType, from, to; paginated). `GET /api/v1/shopify-inventory/adjustments/export.csv`.

**`LocationController`** — GET now includes shopify sync columns. `POST /api/v1/locations` (owner/manager only) creates location + attempts Shopify sync (caught, recorded as error since stub always throws).

**Frontend** — `Locations.tsx`: location list with sync badges (unsynced/pending/linked/error), create form, error message inline. `ShopifyInventory.tsx`: adjustments table with filter bar (status, triggerType, date range), failed rows in red, CSV export button. Both added as routes in `App.tsx`. `Layout.tsx`: `IconLocations` + `IconShopifyInv` + nav links (manager/owner only). `en.json` + `ar.json` translated.

**Tests** — 5 new tests (si1–si5): si1 receiving session inserts shadow rows with resolved inventoryItemId, si2 duplicate trigger blocked by UNIQUE (ON CONFLICT DO NOTHING), si3 unlinked location records failed row, si4 return inspection inserts shadow row for variant, si5 RLS wrong-tenant isolation with app_user. `MigrationSmokeTest` updated V1–V48 = 47, `shopify_inventory_adjustments` in tenant-scoped table list.

**Phase 2 (not built):** flip `shopify_inventory_sync_enabled=true`, change status to `pending`, call `inventoryAdjustQuantities` mutation, use `batch_id` as `@idempotent(key:)`, parse `changes[]` set for partial-failure mapping. Scope change (write_inventory): Marawan to test on dev store first; DO NOT touch shopify.app.toml until confirmed.

V47 — FR-16 Phase 1: Pickup sessions (scan-first, Traced-owned custody).

**Migration V47** — `pickups.status → session_status`; new session columns: `scheduled_time_slot`, `business_location_id`, `contact_person` (jsonb), `notes`, `no_of_packages`, `opened_by_user_id`, `closed_by_user_id`, `closed_at`, `submitted_at`, `bosta_error`. `pickup_shipments.scanned_at` + `scanned_by_user_id`. `shipments.custody_locked_by_scan BOOLEAN NOT NULL DEFAULT false`.

**`PickupSessionService`** — open/scan/close/manifest. Scan validates: session open, forward leg, not duplicate, not in another open session, pieces in packed/awaiting_pickup state. Close: unconditionally sets `internal_state='with_courier'` + `custody_locked_by_scan=true` on all scanned shipments; transitions pieces packed→with_courier (or awaiting_pickup→with_courier) via InventoryLedger with `handed_to_courier` event; pieces in unexpected state get `exception_resolutions` record (type: `pickup_piece_custody_gap`). Session remains scannable until explicitly closed.

**InventoryLedger** — `packed:with_courier` added as approved bypass. Session close is the authoritative physical handover; blocking because Bosta state-20 hasn't arrived produces a false custody state. Parallel precedent: `awaiting_pickup:delivered`.

**BostaWebhookJob custody guard** — step-9 UPDATE now enforces two CASE branches: HOLD branch (locks on `custody_locked_by_scan=true AND shipment_leg='forward' AND incoming='created'`) prevents Bosta pre-transit state from demoting with_courier. RELEASE branch clears the lock only on genuine downstream progression (`with_courier`, `returning`, `delivered`, `returned`); exception/cancelled/terminated/lost do NOT release. Both branches filter `shipment_leg='forward'`.

**PickupSessionController** — `POST /api/v1/pickup-sessions`, `GET /`, `GET /{id}`, `POST /{id}/scans`, `DELETE /{id}/scans/{shipmentId}`, `POST /{id}/close`, `GET /{id}/manifest`.

**Frontend** — `PickupSessions.tsx`: session list, create-session form (date + time slot + notes), scan screen (always-focused barcode input, Enter capture, 6-outcome feedback banner at 2xl size, optimistic UI with rollback, removable scan list, running count), close-confirmation modal (explicit irreversible copy), closed manifest view. `/pickups` route added to `App.tsx`. `IconPickups` + nav link in `Layout.tsx`. Full en.json + ar.json translations.

**Tests** — 7 new tests (ps1–ps7): ps1 full happy path (open→scan→close: shipment with_courier, custody_locked=true, piece with_courier, handed_to_courier event), ps2 duplicate scan, ps3 return-leg rejection, ps4 other-session conflict, ps5 custody guard holds Bosta 'created', ps6 custody guard releases on Bosta 'with_courier', ps7 RLS wrong-tenant isolation. Fr9ManifestSelfPickupTest updated for status→session_status rename.

**Phase 2 (not built)**: Bosta pickup creation API call, pending_bosta deadlock handling, two-way reconcile (scanned-not-in-Bosta → possible lost package; in-Bosta-not-scanned → custody gap), divergence poll job.

V46 — FR-15 direction-aware delivery status + attention fields.

**`BostaAttentionExtractor`** — single extraction point for all attention-field values from a Bosta raw payload's `attempts[]` array. Never uses Bosta's unreliable flat `numberOfAttempts`/`deliveryAttemptsLength`/`pickupAttemptsLength`. Fields extracted: `totalAttempts` (supersedes old flat counter), `failedDeliveryAttempts`, `lastAttemptAt`, `lastFailureReason`, `isDelayed`, `slaBreached`, `scheduledAt`, `courierName`, `courierPhone`, per-attempt `AttemptEntry` list. Called at all three raw-write sites: BostaWebhookJob step 9 UPDATE, `createOrFindShipment` INSERT, `createOrFindReturnShipment` INSERT.

**V46 migration** — 8 new columns on `shipments` (`failed_delivery_attempts INT NOT NULL DEFAULT 0`, `last_attempt_at TIMESTAMPTZ`, `last_failure_reason TEXT`, `is_delayed BOOLEAN`, `sla_breached BOOLEAN`, `scheduled_at TIMESTAMPTZ`, `courier_name TEXT`, `courier_phone TEXT`). Step 1: supersede `number_of_attempts` → `jsonb_array_length(raw->'attempts')` (reliable). Step 2: backfill all new columns from existing `raw`. PostgreSQL lateral subqueries over `jsonb_array_elements()`. Handles missing `attempts[]`, invalid state strings, JSON null vs SQL null for sla/isDelayed.

**API changes** — `OrderController.ShipmentSummary` replaced by `ShipmentDetail` with all new fields + `shipmentLeg` + per-attempt history. `OrderDetail.shipment: ShipmentSummary` → `shipments: List<ShipmentDetail>` (both legs). List LATERAL adds `failed_delivery_attempts`, `is_delayed`, `sla_breached`. Attempt history is parsed from raw JSON in the row mapper — one raw-parse per shipment in the detail endpoint.

**Frontend** — `DeliveryBadge` adds `shipmentLeg` prop; return leg uses `delivery.state.return.*` i18n sub-namespace with fallback to base. Orders list: failed-attempts pill (red, when `failedDeliveryAttempts > 0`) + Delayed flag (orange, when `isDelayed || slaBreached`). OrderDetail: loops `order.shipments[]` → per-leg `ShipmentCard` with courier, scheduled date, attention pills, per-attempt history list, expandable status timeline (leg-aware labels). `en.json`/`ar.json`: 9 return-leg state overrides.

**Tests** — `BostaAttentionFieldsTest`: 6 tests (af1–af5 unit, af6 DB integration). af6 verifies `failed_delivery_attempts=2`, `last_failure_reason="Customer refused"`, `is_delayed=true`, `sla_breached=true`, `courier_name="Khaled"`, `number_of_attempts=2` (from array); RLS check via `app_user` (skipped if app_user not configured in Testcontainer). MigrationSmokeTest: V1–V46 = 45.

**Decisions made:**
- `state == 3` (SUCCEEDED) is secondary signal; `succeededAt IS NOT NULL` is primary. Both checked.
- `slaBreached` = `NULL` when no `sla` key present (not false) — preserves data-availability signal.
- `number_of_attempts` now stores array-derived total, NOT Bosta's flat counter. No two columns with different semantics.
- Attempt history NOT stored as separate DB column — derived from `raw` at read time in `OrderController.detail()`. Reason: raw is the authoritative source; attempt history is rarely needed and varies per shipment; storing a separate JSONB column would duplicate data.
- `failed_delivery_attempts > 0` (not `> 1`) triggers the pill per user spec.

V45 — PII flicker fix + leg-awareness audit across 14 locations.

Three root causes diagnosed and fixed:

**Fix 1 — ShopifySync null-overwrite (active PII erasure every 30 min).**
`ShopifySyncService.UPSERT_ORDER` previously wrote `customer_name = EXCLUDED.customer_name` unconditionally. On Shopify Basic with PCD review pending, Shopify returns NULL for name/phone/address — erasing any Bosta-sourced value every reconcile cycle and every order webhook. Fixed: `customer_name = COALESCE(EXCLUDED.customer_name, orders.customer_name)` (same for phone/address). Other fields (`payment_method`, `cod_amount`, `placed_at`, `raw`) are always non-null from Shopify → no COALESCE needed.

**Fix 2 — Three paths never called `populateConsigneePii()`.**
(a) `manualLink()` — reconcile-linked orders never got PII. Fixed: fetch `raw::text` from `unlinked_bosta_deliveries`, parse as JsonNode, call new `populateConsigneePiiFromRaw()`. (b) Subsequent Bosta webhooks on already-linked shipments — `tryMatchDelivery()` is only called on first arrival; step 9+ path in `BostaWebhookJob` never wrote PII. Fixed: added step 9.6 call to `shipmentLinkService.populateConsigneePii()` after shipment UPDATE commits. (c) `backfill-pii` endpoint had no forward-leg filter — could pick return-leg raw (CRP receiver is not the consignee). Fixed: `AND s.shipment_leg = 'forward'`.

`populateConsigneePii(UUID, UUID, BostaDelivery)` made public (was private); delegates to new package-private `populateConsigneePiiFromRaw(UUID, UUID, JsonNode)` so both `BostaWebhookJob` and `manualLink()` share one implementation.

**Fix 3 — V43 leg-awareness regression at 14 locations (+ 1 crash).**
V43 added `shipment_leg` ('forward'/'return') and changed orders→shipments from one-active-per-order to two-active-per-order (one per leg). Every unfiltered `JOIN shipments ON order_id = o.id` became wrong:
- `LookupService:74` barcode lookup — `queryForMap()` crashed with `IncorrectResultSizeDataAccessException` on two-leg orders. **Critical fix.**
- `OrderController:133` list LATERAL — arbitrary UUID ordering picked either leg. Fixed: `AND shipment_leg = 'forward'`.
- `OrderController:232` detail shipment — `ORDER BY created_at DESC LIMIT 1` picked return leg if newer. Fixed.
- `ExceptionService:157` detectLost — duplicate shipment rows per piece. Fixed.
- `ExceptionService:175` detectNeverReceived — return leg 'returned' state matched as never-received. Fixed.
- `ExceptionService:238` detectStuck — return leg generated false stuck alarms. Fixed.
- `ExceptionService:353` detectShopifyCancelVsInflight — duplicate rows per order. Fixed.
- `ExceptionService:454` detectReturnInTransitStuck — duplicate rows per piece. Fixed.
- `FulfillService:76` pack-screen order detail — ambiguous `rows.get(0)`. Fixed.
- `ReturnService:53` receiveReturn — ambiguous shipment row on barcode scan. Fixed.
- `ReturnService:136` listPending — duplicate piece rows. Fixed.
- `ReturnService:212` neverReceived — return leg matched as never-received. Fixed.
- `ReturnSessionService:378` fetchPieceContext — ambiguous shipment_id picked for piece events. Fixed.
- `BostaPickupService:138` schedule — return-leg shipments incorrectly included in pickup manifest. Fixed.

**V45 migration** — forward-leg filtered, idempotent one-time backfill (`COALESCE` keeps any existing value; `pii_source IS NULL` guard prevents re-processing already-sourced PII).

**Tests added:**
- `ShopifyReconcileTest.r6` — Shopify sync with null PCD-blocked name/phone must NOT overwrite Bosta-sourced `customer_name`. Regression guard for the flicker bug.
- `BostaOrderReconcileTest.r7` — `manualLink()` with raw JSON in `unlinked_bosta_deliveries` must populate `customer_name` and `customer_phone` on the order.
- `MigrationSmokeTest` count updated: 43 → 44 (V45).

**583 tests green** (was 581). No flaky failures.

FR-13/FR-14 — version-stamp dedup + poll-exit on 400 (V44, commit `990f07c`). Root cause of production incident (9730639058 stranded): content-derived idem key `sha256(tracking:state:updatedAt)` meant deleting `unlinked_bosta_deliveries` row didn't invalidate the `webhook_events.external_event_id` slot — step 4 still found E1's processed event and no-op'd. Fix: `MatcherVersionHolder` reads Flyway current version at startup. Guard 3 (BostaIngestionHelper) blocks re-enqueue only when unlinked row's `matcher_version = current`; NULL or different version → passes → one retry per deploy. Step-4 dedup mirrors this: unlinked outcomes at old/null version are retry-eligible; linked outcomes block unconditionally. V44 migration adds `matcher_version TEXT` on `webhook_events` + `unlinked_bosta_deliveries`, `provider_not_found_at TIMESTAMP` on `shipments`. DO-block sentinel auto-decides at migration time: if unresolved unlinked count > 20, stamps existing rows with current version (prevents post-deploy retry storm at Bosta); if ≤ 20, no stamp (all retry on first cycle). Strict `=` / `<>` predicates only — no ordering comparison (Flyway version strings sort lexically wrong, e.g. "9" > "44"). FR-14: `DeliveryNotFoundException` thrown when Bosta returns HTTP 400 "Delivery not found"; `BostaStatusPollJob` catches it, stamps `provider_not_found_at = now()`, and the poll query excludes those rows permanently. Operational note in CLAUDE.md: NEVER force-retry by DELETE of unlinked row. Correct paths: deploy (version bump) or manual `UPDATE unlinked_bosta_deliveries SET matcher_version = NULL`. Tests: idem1–idem5 (step-4 and Guard-3 version paths), crp6 (full ingest→job→return-shipment pipeline), BostaPollJobTest p14 updated. 581 tests green.

FR-12.6 — CRP return shipment leg (V43, commit `2b42e25`). Bosta CRP (type.code=25, "Customer Return Pickup") is a separate Bosta delivery (new tracking number) for customer-initiated returns. Root cause of NO_MATCH: `ux_active_shipment_per_order` (V19) blocked the CRP INSERT because `'delivered'` is not excluded from the active slot, and the forward shipment remained `delivered`. V43 migration: (1) ADD COLUMN shipment_leg TEXT NOT NULL DEFAULT 'forward' CHECK ('forward','return'); (2) backfill type.code=25 → leg='return'; (3) DO-block safety guard; (4) DROP old index / CREATE UNIQUE INDEX ux_active_shipment_per_order_leg ON (order_id, shipment_leg); (5) Fix V37 seed: applies_to_order_type 'CRP' → 'CUSTOMER RETURN PICKUP' (actual type.value.toUpperCase() — state 41 has no :ALL fallback, was unknownCode). Java: BostaDelivery.typeCode() method (derived from raw.type.code, no constructor changes); ShipmentLinkService.isCrpDelivery(typeCode==25) + createOrFindReturnShipment; BostaAwbService SQL fix: raw->>'type' was returning JSON object as text (never "CRP") — changed to (raw->'type'->>'code')::int. BostaStateMappingTest s12 updated: map(41,"CUSTOMER RETURN PICKUP") now resolves; map(41,"CRP") is unknownCode (dead key). 5 FR-12.6 tests (crp1–crp5, app_user context). Pre-existing AwbPickupTest t04 fixed: raw JSON updated to object form {type:{code:25,...}}. Note: CASH_COLLECTION AWB exclusion also broken by same SQL bug — deferred. Note: order #385327609470 linked CRP 8012985727 via empty-slot path (no forward shipment) — custody gap, not a V43 blocker. Deferred: state 41 has no :ALL fallback — future unknown order types will unknownCode there.

V42 + email uniqueness (commit `6c4bede`). UNIQUE(users.email). 4 tests: eu1 (collision rolls back all 3 INSERTs), eu2 (original login unbroken), eu3 (regression: IncorrectResultSizeDataAccessException without constraint), eu_prod (full linkOrProvision path → 409 SHOPIFY_EMAIL_ALREADY_REGISTERED).

Full session record: [`docs/SESSION-SUMMARY-2026-07-10.md`](SESSION-SUMMARY-2026-07-10.md) — commit refs, gotchas, ingestion architecture, verified-vs-unverified status, open items.

FR-4.4 Bug Fix #2 — not_created flag recovery (commit `6129e4a`). An order flagged `bosta_link_status='not_created'` (after max reconcile attempts) was permanently excluded from retry even when its matching Bosta delivery later landed in `unlinked_bosta_deliveries`, because `BostaOrderReconcileJob` gates eligibility on `bosta_link_status IS NULL`. Fix: `BostaWebhookJob.recordUnlinked()` now counts existing unlinked rows before the upsert (`isFirstArrival = count == 0`). On first arrival (INSERT path), if the delivery's `businessReference` matches an order flagged `not_created`, the flag is cleared atomically in the same transaction. The reconcile job's next 5-minute cycle then picks up the now-eligible order and links it via the existing `manualLink()` path. `xmax` was evaluated and rejected: RETURNING `xmax = 0` for both INSERT and ON CONFLICT DO UPDATE (new tuple always starts with `xmax = 0`), so it cannot distinguish the two without false clears. `recordUnlinked()` changed from `private` to package-private so `NotCreatedFlagRecoveryTest` (same package) can call it directly. 3 tests: nc1 (oscillation guard — two-phase: INSERT clears, ON CONFLICT DO UPDATE does NOT), nc2 (full flow: flag cleared → reconcile links), nc3 (no auto-link: delivery arrival alone never creates a shipment).

FR-4.4 Bug Fix #1 — ingest path resolves unlinked row (commit `e3031e2`). `tryMatchDelivery()` was the only linking path that didn't call `resolveUnlinked()`. Fixed by adding `resolveUnlinked(tenantId, trackingNumber)` call at end of `tryMatchDelivery()`, inside `BostaWebhookJob`'s outer `tx.execute()` block — atomic with shipment creation. 3 tests in `UnlinkedResolveTest` (ul1–ul3, app_user context).

Bosta order reconcile (V41) — Tier 3 reconcile job (`BostaOrderReconcileJob`, `*/5 * * * *`). Detects Shopify orders with no linked Bosta delivery. Works entirely against local `unlinked_bosta_deliveries` (no new Bosta API calls). Per eligible order: searches by order number variants (raw/stripped/hashed/#, externalId) → if match found, calls `ShipmentLinkService.manualLink()` to link; if not found, increments `bosta_link_attempts`; after `max-attempts` cycles flags `bosta_link_status = 'not_created'`. Flag clears automatically when ANY path (webhook/backfill/reconcile/AWB scan) creates a shipment via `createOrFindShipment()` — `clearReconcileFlag()` helper is called there and in `linkByAwbScan()`. Orders list and detail show a distinct danger badge ("Shipment not created" / "لم تُنشأ شحنة") when flagged. Config: `bosta.reconcile.{enabled,max-attempts:10,lookback-days:30,batch-size:50}`. V41 migration adds `bosta_link_attempts`, `bosta_link_last_check`, `bosta_link_status` columns to `orders` with a sparse index on flagged rows. 6 tests in `BostaOrderReconcileTest` (r1–r6): counter increment, not_created flag, match→link, flag-clear via manualLink, active-shipment skip, terminal-status skip.

Orders list unified status column (V40 frontend). Single "Status" column shows `DeliveryBadge` (shipment `internal_state`) when a shipment exists, "Shipment not created" danger badge when `bostaLinkStatus='not_created'`, or pipeline status badge (`orders.pipeline.*` i18n namespace) otherwise. Removed separate "Bosta/Delivery" column. EN + AR labels for all 12 pipeline states.

Backend list endpoint lateral join. `OrderController.list()` now uses `LEFT JOIN LATERAL (SELECT ... ORDER BY id DESC LIMIT 1)` instead of a simple `LEFT JOIN shipments`. Guarantees one row per order for re-shipped orders (terminated + new active shipment coexist under V19 partial unique index). Both `OrderSummary` and `OrderDetail` records include `bostaLinkStatus`.

Bosta delivery status display (V40). Orders list shows a delivery status badge per order. Order detail shows current status badge + expandable timestamped history timeline. State 47 (exception) displays `exception_reason` as a caption below the badge. 9 Bosta state codes mapped to friendly labels in EN + AR (react-i18next, RTL). New `shipment_status_history` table (V40) — one row per webhook-driven state transition, RLS-safe, idempotent via `ON CONFLICT (webhook_event_id) WHERE NOT NULL DO NOTHING`. `BostaWebhookJob` writes the history row atomically with the shipment UPDATE. 7 new tests in `DeliveryStatusTest` (list JOIN, exception+reason, no-shipment, N transitions, idempotent replay, tenant RLS via app_user, terminal states). Fixed Day9Test `insertOrder` helpers to supply `placed_at=now()` (recency filter was silently excluding NULL-placed_at orders), fixed ExceptionRlsTest.b to use valid `'new'::order_status`, updated BostaPollJobTest p8 + BostaBackfillTest dedup assertions to reflect ON CONFLICT DO NOTHING moving dedup to creation layer.

`webhook_events` idempotency — graceful duplicate handling. `DuplicateKeyException` at `BostaWebhookJob.markProcessed()` (line 405) on `webhook_events_idem` partial unique index — now fixed via two-layer defence: (1) `BostaIngestionHelper` pre-computes idem key and inserts it at `webhook_events` creation time with `ON CONFLICT DO NOTHING` — second concurrent poll cycle's enqueue returns `null` and skips; (2) `markProcessed()` catches `DuplicateKeyException` as backstop and marks the event as `concurrent duplicate`. Tests p12 + p13 added.

Bosta delivery tenant-routing fix + Guard 3. Root cause: test tenant 2522cd56 and pilot 07fc572c share the same Bosta API key → both polled the same deliveries → wrong-tenant RLS → NO_MATCH → `unlinked_bosta_deliveries` → retry loop every ~30s. Immediate fix: production SQL to `SET status='disconnected'` on 2522cd56's courier_account + delete its wrong-tenant unlinked rows. Guard 3 in `BostaIngestionHelper`: if delivery is already in `unlinked_bosta_deliveries` at the same state code, skip re-enqueue (only retry on state change). Test p14 added.

**Pending production SQL (must run to stop the 2522cd56 retry loop):**
```sql
UPDATE courier_accounts SET status = 'disconnected'
WHERE tenant_id::text LIKE '2522cd56-%' AND provider = 'bosta';
DELETE FROM unlinked_bosta_deliveries
WHERE tenant_id::text LIKE '2522cd56-%';
```

Pick-queue recency filter + bounded import. 516 historical 'new' orders no longer flood the pick queue. Queue now shows only orders with `placed_at > now() - 30 days`. Old orders stay in DB, visible in orders-list, linkable for Bosta. Import bounded to same window via `ShopifySyncService`. Both driven by `shopify.import.lookback-days` (default 30, override via `SHOPIFY_IMPORT_LOOKBACK_DAYS`). 7 new tests in `PickQueueRecencyTest`.

`ExceptionService` RLS fix (8th occurrence pattern). `listExceptions()` / `listResolutions()` lacked `@Transactional` → GUC never fired → `queryForMap("...FROM tenants")` returned 0 rows → 500. Fixed with `@Transactional(readOnly=true)` on both read methods, `@Transactional` on `resolve()`. `ExceptionRlsTest` added (app_user coverage).

`courier_accounts` dedup + unique constraint. Tenant 07fc572c had 4 bosta rows (reconnect was INSERT-without-conflict-target). V39 migration: FK-safe dedup, `UNIQUE(tenant_id, provider)`. `BostaController.connect()` now atomic upsert. No more N× polling per reconnect.

---

**Pick-queue recency filter + bounded import (2026-07-09):**

*Problem:* 516 `status='new'` orders accumulated over ~2 months in the pick queue. Merchants don't advance Shopify order status, and nothing in the system auto-advances `new`. Every historical import order piled up, making the queue unusable.

*Root cause:* `FulfillService.getQueue()` had no date bound on `placed_at` — matched every `status IN ('new','ready_to_pick','self_pickup_pending')` order since first import.

*Fix (soft-exclude, NOT hard-delete):*
- `FulfillService.getQueue()` — added `AND o.placed_at > now() - (? * INTERVAL '1 day')` using a new `lookbackDays` field injected via `@Value("${shopify.import.lookback-days:30}")`.
- `ShopifySyncService.runImport()` — replaced hardcoded `90` with `importLookbackDays` from same config key. Prevents pulling ancient history on big stores on first connect.
- `application.yml` — added `shopify.import.lookback-days: ${SHOPIFY_IMPORT_LOOKBACK_DAYS:30}` under `shopify:` section.
- Orders-list (`OrderController`), order-detail, and Bosta linking are **unaffected** — no `placed_at` filter there. Old orders remain in DB, visible in full orders list, linkable to Bosta deliveries.
- Live webhooks (`ingestOrderWebhook`) and `ShopifyReconcileJob` (30-min window) are untouched.

*Tests (`PickQueueRecencyTest`, 7 cases):*
- (a) in-window 'new' → appears in queue
- (b) out-of-window 'new' → absent from queue, present in DB
- (c) direct orders query returns all dates regardless (orders-list not filtered)
- (d) out-of-window order still linkable as Bosta FK target
- (e) `ready_to_pick` + `self_pickup_pending` within window also appear
- (f) `on_hold=true` excluded regardless of recency
- (g) boundary test: 31-day-old hidden, 29-day-old visible

*Day9Test:* Updated `new FulfillService(...)` call (direct construction for app_user RLS test) to pass `lookbackDays=30`.

*Expected result after deploy:* Tenant 07fc572c pick queue drops from 516 to just the recent (<30 day) `new` orders. Old orders remain in DB + orders-list + Bosta-linkable.

---

**webhook_events idempotency — graceful DuplicateKeyException handling (2026-07-09):**

*Root cause:* Two overlapping `bosta-status-poll` cycles both fetched the same delivery in the same 3-min window. Both passed step-4 dedup check (which queries `WHERE status='processed'` — both events still `pending`). Both hit the NO_MATCH unlinked path. Second worker's `markProcessed()` threw `DuplicateKeyException` on `webhook_events_idem` (partial unique index `(source, external_event_id) WHERE external_event_id IS NOT NULL`). Unhandled → JobRunr marked the job failed → 30s retry → repeated collision.

*Fix — two-layer defence:*
1. **Dedup-at-creation** (`BostaIngestionHelper`): pre-compute idem key as `sha256(trackingNumber:stateCode:updatedAt)`. Add `external_event_id = ?` to the INSERT with `ON CONFLICT (source, external_event_id) WHERE external_event_id IS NOT NULL DO NOTHING RETURNING id`. If the idem key is already in the table, INSERT returns no row → `webhookEventId == null` → return false, skip enqueue entirely. The second poll cycle never creates a competing event row.
2. **`markProcessed()` backstop** (`BostaWebhookJob`): `try { UPDATE ... SET external_event_id=? ... } catch (DuplicateKeyException) { UPDATE ... SET error='concurrent duplicate' }`. Handles any residual race that slips through layer 1 (e.g., events created before this deploy with null external_event_id).

*Tests (p12 + p13 in `BostaPollJobTest`):* p12 — two `ingestDelivery()` calls same idem key: second returns false, 1 event row (not 2), processing succeeds no exception. p13 — two events same payload both in DB (simulating pre-deploy rows), sequential processing: both end as `processed`, no exception.

---

**Bosta delivery tenant-routing fix + retry-loop Guard 3 (2026-07-09):**

*Root cause:* Test tenant `2522cd56-*` and pilot tenant `07fc572c-*` share the same Bosta API key / same Bosta business. `BostaStatusPollJob` queries active tenants — both were active. Delivery 2499538591 (belonging to 07fc572c's order `#385328359470`) was fetched by whichever tenant polled first — often 2522cd56. Under 2522cd56's RLS context, `matchByBusinessReference()` found no order (order belongs to 07fc572c) → NO_MATCH → inserted into `unlinked_bosta_deliveries` with wrong tenant_id. The unlinked row + Guard 3 missing meant every subsequent poll cycle re-enqueued the same delivery → JobRunr processed it again → same NO_MATCH → retry in ~30s forever.

*Fix — three parts:*
1. **Production SQL** (must be run manually): `UPDATE courier_accounts SET status='disconnected' WHERE tenant_id LIKE '2522cd56-%'` + `DELETE FROM unlinked_bosta_deliveries WHERE tenant_id LIKE '2522cd56-%'`. Status `disconnected` is excluded by `ACTIVE_BOSTA_TENANTS` query (no restart needed).
2. **Guard 3 in `BostaIngestionHelper`** (before INSERT): check `SELECT bosta_state_code FROM unlinked_bosta_deliveries WHERE tracking_number=? AND resolved=false LIMIT 1` inside `tx.execute()` (GUC must fire → RLS scopes to current tenant). If existing row has same state code → `return false`. Only re-try when state changes — a new state may be matchable.
3. **Code-level defence against future shared-key scenarios**: Guard 3 ensures that even if two tenants share a key again, a permanently-unmatched delivery at a given state won't loop; it needs a state change to retry.

*Tests (p14 in `BostaPollJobTest`):* unlinked at state 41 → `ingestDelivery()` returns false, 0 event rows, no exception. State change to 45 → returns true, 1 event row created.

*Future design note:* If two tenants LEGITIMATELY share a Bosta business (e.g., two warehouses under one Bosta account), the system needs to match delivery→order to determine the correct tenant, not rely on whichever tenant polls first. This is flagged as a known limitation; not building now.

---

Bosta HTTP 429 rate-limit handling complete (V38). Poll cycle aborts on first 429, per-tenant backoff prevents hammering. `inter-fetch-delay-ms` increased to 2 seconds. Root cause of the -1 warnings confirmed as: runaway poll loop hammered the API key until Bosta returned `{success:false, errorCode:429}`, which the old `onStatus(is4xxClientError, noOp)` suppressed to a 200-with-body → state extraction found no state → -1.

Bosta state handling fully fixed (V37). Poll path no longer produces state code -1. State 47 (NDR exception) now stored correctly instead of aborting. State 60 is terminal. 12 new `BostaStateMappingTest` tests cover both state shapes, exception code storage, unknown code abort, and terminal-state poll exclusion.

Bosta consignee PII population complete (V36). When a Bosta delivery auto-links to an order, `customer_name` / `customer_phone` / `address` are filled from Bosta receiver data (fill-only-if-null, GDPR guard, phone normalized to 01XXXXXXXXX). Backfill endpoint fills already-linked orders from `shipments.raw`. Pack page scan (`/api/v1/lookup`) already returns these fields. Fulfill.tsx shows "Pending Bosta link" when `customer_name` is null.

---

**Bosta HTTP 429 rate-limit handling — V38 (2026-07-07):**

*Root cause:* The runaway poll loop (now fixed in V37+bed889d) had already hammered Bosta's API, resulting in a 429 rate-limit response. The old `.onStatus(is4xxClientError, noOp)` suppressed the 429 HTTP status and returned the body as a JsonNode — `{success:false, errorCode:429, retryAfter:285}`. State extraction found no `state` field → extracted -1. The -1 was NOT a parsing bug; it was a suppressed rate-limit error.

*Changes:*
- **`BostaRateLimitException`** — new typed exception extending `BostaException` (not `BostaTransientException`). NOT retried by JobRunr; the poll manages backoff manually.
- **`BostaHttpGateway.fetchDelivery()`** — `.onStatus(is4xxClientError, noOp)` changed to `.onStatus(status -> status.value() == 404, ...)` (only 404 suppressed). HTTP 429 now reaches `catch (RestClientResponseException)` → throws `BostaRateLimitException(retryAfter)`. Body-level 429 guard (`detectRateLimit()`) handles the case where Bosta returns a 200 with `{success:false, errorCode:429}` body.
- **`BostaHttpGateway.listDeliveriesPage()`** — same 429 handling in catch block.
- **`BostaStatusPollJob`** — `ConcurrentHashMap<UUID, Long> rateLimitRetryUntilByTenant`: set to `now + (retryAfter + 10)s` on 429; checked at top of tenant loop to skip tenants still in backoff. `catch (BostaRateLimitException e)` in shipment loop: sets backoff + `return`s immediately from the Runnable (no more fetches for this tenant, does NOT update `last_polled_at` for the rate-limited shipment).
- **`application.yml`** — `inter-fetch-delay-ms: 100` → `2000`. With 16 shipments × 2s = 32s per cycle. Reduces burst rate from ~10 calls/3s to ~1 call/2s.

*Tests (p11 in `BostaPollJobTest`):* 429 on fetch → cycle aborts (fetchDelivery called exactly 1 time); `last_polled_at` not set; 0 `webhook_events`; second immediate `pollAll()` call skipped by backoff (still 1 total fetch). Separate tenant used to isolate backoff state. 544 tests green.

---

**Bosta state handling fixes — V37 (2026-07-06):**

*Problem:* Every status poll produced "Unknown Bosta state code -1" warnings. State 47 (NDR) aborted processing instead of storing the exception. State 60 was non-terminal so polls ran forever.

*Root causes found & fixed:*
1. **`BostaHttpGateway.fetchDelivery()`** — Added double-nesting unwrap `data.data`, array-in-data, and no-wrapper defensive handling (same robustness as `listDeliveriesPage`). The state extraction `stateNode.isObject() ? path("code").asInt(-1) : asInt(-1)` was already correct after commit 9889094 — the -1 was coming from the envelope unwrap failing.
2. **`BostaStateMapper.MappedState`** — Split `isException` (maps to exception internal state = true for state 47, 101, 102) from `unknownCode` (no mapping row found = true for -1, 999, etc). These were both `true` causing `BostaWebhookJob` to abort on state 47 the same way as unknown codes.
3. **`BostaWebhookJob` step 7** — Changed check from `mapped.isException()` → `mapped.unknownCode()`. State 47 now continues to step 9.
4. **`BostaWebhookJob` step 9** — Extracts `exceptionCode` + `exceptionReason` from `delivery.raw()` when `mapped.isException()` is true. Stores both in `shipments` via `COALESCE(?, exception_code)` (preserves first-seen NDR code across repeated exception events).
5. **`BostaIngestionHelper`** — Added `type` field to the synthesized payload. BostaWebhookJob re-fetches the delivery and uses `delivery.type()` for mapping (not the payload), but having type in the payload keeps it consistent with real webhook shape.
6. **`V37__bosta_state_fix.sql`**:
   - `shipments.exception_code INTEGER` + `exception_reason TEXT` columns.
   - State 60 updated from `with_courier` → `returned` (terminal, stops poll loop).
   - State 11 "Waiting for route" → `created`.
   - State 41:FXF_SEND → `with_courier`; 41:EXCHANGE and 41:CRP → `returning`.
   - NDR codes 100 (bad weather) + 101 (suspicious consignee) for both forward and return. PK widened from `(code)` to `(code, category)` to allow same code in both categories.

*Tests (12 new in `BostaStateMappingTest`):* s1–s3 state shape parsing (object, flat, double-nested); s4 known codes (24 → with_courier, 45 → delivered); s5 state 60 → returned; s6 unknown code -1 → unknownCode=true; s7 state 47 → isException=true, unknownCode=false; s8 state 47 + exceptionCode 3 stored on shipment, webhook processed not failed; s9 unknown -1 → webhook failed; s10 returned shipment excluded from poll; s11 state 11 → created; s12 41:FXF_SEND/EXCHANGE/CRP type disambiguation.

---

**Bosta consignee PII population — V36 (2026-07-06):**

*Problem:* Shopify Basic plan blocks customer PII via custom app — `customer_name`, `customer_phone`, `address` were always null until PCD review approved. Bosta already has verified consignee data in `receiver.*` for every linked delivery.

*Changes:*
- `V36__bosta_pii_columns.sql` — adds `pii_source TEXT` and `pii_redacted_at TIMESTAMPTZ` to `orders`.
- `ShipmentLinkService.populateConsigneePii()` — called after auto-link; COALESCE per field (never overwrites existing Shopify PII); checks `pii_redacted_at IS NULL` (GDPR guard).
- Phone normalization: `receiver.phone` is `+20XXXXXXXXXX`; stripped to `01XXXXXXXXX` via `normalizePhone()`.
- `ShopifyWebhookProcessorJob` GDPR redact handlers (both `customers/redact` and `shop/redact`) now also set `pii_source = NULL`, `pii_redacted_at = now()` — once set, populate-on-link permanently skips the order.
- `POST /api/v1/bosta/backfill-pii` (OWNER-only) — fills orders already linked before this deploy using JSONB `#>>` operators on `shipments.raw`. Pure SQL, no extra API calls.
- `Fulfill.tsx` — `customer_name ?? t('common.pendingConsignee')` (was `?? t('common.na')`) in 4 places.

*Tests (10):* p1 link fills PII; p2 fill-only-if-null; p3 GDPR guard; p4 pii_source; p5 pack scan shows name/phone; p6 unlinked scan returns null; p7 backfill; p8 backfill skips redacted; p9 app_user RLS; p10 phone normalization.

Bosta webhook auth fixed (Bearer-prefix normalization). Copyable secret reveal panel + regenerate-secret endpoint added. Webhook should now pass. Keep `[BOSTA-WH-HIT]` log until a real webhook produces a `source='bosta'` row in `webhook_events`, then remove it.

---

**Bosta webhook 401 fix — Bearer prefix normalization (2026-07-06):**

*Root cause:* Handler hard-required `startsWith("Bearer ")`. Two failure modes:
- Mode A (confirmed): operator configured Bosta dashboard with the raw secret (no prefix). Bosta sends `Authorization: {secret}`. Handler → 401 before DB comparison.
- Mode B (guarded against): operator pasted `Bearer {secret}` into the dashboard field. Bosta constructs `Authorization: Bearer Bearer {secret}`. After stripping one prefix the remainder is `Bearer {secret}` → sha256 mismatch → 401.

*Fix:* Strip one `Bearer ` prefix if present (case-insensitive, whitespace-tolerant), accept raw secret without prefix. Both forms resolve to the same hash.

*Storage:* Already correct — `sha256(rawHex)` with no Bearer baked in. Existing stored secrets are compatible; no reconnect needed.

*After deploying:* Test with `curl -H "Authorization: {rawSecret}" POST /webhooks/bosta`. Should return 200 and produce a `source='bosta'` row in `webhook_events`. Then remove the `[BOSTA-WH-HIT]` diagnostic log from `BostaController`.

*3 new tests (5b–5d in `BostaDay5Test`):* raw-no-Bearer → 200; double-Bearer → 401 (documents exact bug); lowercase-bearer → 200.

---

**Bosta: copyable webhook secret reveal + regenerate-secret (2026-07-06):**

*Problem:* Webhook secret shown once as plain text → easy to lose → forced full reconnect (re-enter API key) to recover.

*Changes:*
- `POST /api/v1/bosta/regenerate-secret` (OWNER-only): rotates only the webhook secret — no API key needed. Returns new 64-hex secret once, stores SHA-256 hash. Old secret immediately invalidated. Returns 404 if no active account.
- `WebhookSecretReveal` panel (frontend): shown once after connect OR regenerate. Three copyable rows — **Webhook URL**, **Authorization Key** (`Bearer {secret}`, the exact value for Bosta's field), **Raw secret**. Each row has Copy button + "Copied!" transient feedback. Warning banner: "Save this now — it won't be shown again." Done button dismisses the panel.
- "Regenerate webhook secret" button in the connected Bosta card (with confirm dialog).
- `navigator.clipboard` with `execCommand` fallback. No secret logged to console.

*Security model:* unchanged (Option A). Secret only in component state for that session; not retrievable on reload.

*Tests:* 2 new in `BostaDay5Test` — rotation + old-secret-invalid, 404 on no-account.

---

**Two-tier Bosta delivery polling — V35 (2026-07-05):**

*Problem:* Bosta List API is creation-ordered with no update filter → page-polling would miss status changes on older deliveries. Webhook never arrives (entry-point log deployed to diagnose why).

*Tier 1 — Status Poll (every 3 min, `bosta-status-poll` JobRunr recurring):*
- Queries non-terminal shipments (`created`, `with_courier`, `returning`, `exception`) per tenant, ordered by `last_polled_at ASC NULLS FIRST` (round-robin so no shipment starves).
- `fetchDelivery()` per shipment → `BostaIngestionHelper` → `BostaWebhookJob`. Unchanged state = same idem key = dedup'd no-op. Changed state = new idem key = processed (shipment + pieces + ledger updated).
- Cap: `bosta.poll.status-max-per-cycle=200` (pilot: never hit).

*Tier 2 — Discovery Poll (every 20 min, `bosta-discovery-poll` JobRunr recurring):*
- Pages first 3 Bosta list pages (~150 newest-created deliveries).
- New deliveries ingested + matched via `ShipmentLinkService`; already-seen ones dedup'd.
- After discovery, Tier 1 keeps their status current.

*Shared pipeline:* `BostaIngestionHelper` extracted from `BostaBackfillJob` (eliminates duplication). All three callers (backfill/status-poll/discovery-poll) use: fetch → synthesize payload → insert `webhook_events` → enqueue `BostaWebhookJob`. Source tags: `bosta_backfill` / `bosta_poll` / `bosta_poll_discovery`.

*V35 migration:* `shipments.last_polled_at` column, partial index on non-terminal shipments, two new `webhook_source` enum values.

*Rate-limit estimate per tenant/hour:*
- Tier 1: 20 cycles × ~tens of fetches = 100–400 API calls/hr (pilot scale)
- Tier 2: 3 cycles × (3 list + ~50 fetches) = ~159 API calls/hr
- Total: ~260–560 API calls/hr/tenant

*10 new tests in `BostaPollJobTest`*: changed state → full pipeline; unchanged → dedup; terminal excluded; cap + rotation; TenantContext/RLS as app_user; Tier 1 + Tier 2 coexistence; discovery new delivery; discovery dedup; terminal set completeness; multi-tenant isolation.

*Webhook diagnostic log:* `[BOSTA-WH-HIT]` entry-point `log.warn` at first line of `bostaWebhook()` handler (before auth). Deploy, trigger a state change, check: if nothing logs → Cloudflare or nginx blocking; if logs appear → auth/secret issue. Remove once webhook delivery confirmed.

*Other fixes same session:*
- `api.ts request()`: skip `res.json()` on 204/empty responses → Settings save no longer shows false error.
- `BostaHttpGateway.printMassAwb()`: reverted from v2+Bearer to v0+raw apiKey (confirmed working; v2 appears to require OAuth tokens, not the stored API key format). Null-guard + INFO log added.

---

---

**Bosta AWB size setting + audit_log RLS fix (2026-07-05):**

*Feature:* Per-tenant AWB label size (A4 vs A6) stored in `courier_accounts.awb_format` (existed from V20). Bosta endpoint switched from v0 to v2 mass-awb with `Authorization: Bearer {apiKey}` and `requestedAwbType: "A4"|"A6"`. Settings page now shows AWB size + language selectors (disabled when Bosta not connected). `GET /connections` exposes `awbFormat`/`awbLang`. Tests: `BostaAwbSettingTest` (6 cases, `@MockBean BostaGateway`).

*Bug fixed — audit_log RLS violation (500 on Settings save):*

**Root cause:** `TenantController.update()` (PUT /tenant/settings) called `audit.record()` in a separate `TenantContext.runAs()` block OUTSIDE `tx.execute()`. After the UPDATE transaction committed, `SET LOCAL app.current_tenant` reset to `''`. The audit INSERT ran in autocommit with GUC = '' → `WITH CHECK (tenant_id = NULLIF('','')::uuid = NULL)` is always false → PSQLException "new row violates row-level security policy for table audit_log" → 500.

**Fix:** Moved `audit.record()` INSIDE the same `tx.execute()` block as the UPDATE. Both now share one transaction where the GUC is set by `TenantAwareConnection.setAutoCommit(false)`.

*Tests added to `TenantSettingsTest` (s4b + s4c — app_user RLS tests):*
- `s4b`: proves INSERT INTO audit_log via `app_user` WITH GUC set (inside `TenantContext.runAs + tx.execute()`) succeeds.
- `s4c`: proves INSERT INTO audit_log via `app_user` WITHOUT GUC set (autocommit, no tx) fails with RLS violation — documents the pre-fix bug.

*Match precedence fix (same session):* `ShipmentLinkService.matchByBusinessReference()` now returns `StrongMatch` record (found/ambiguous/notFound) with LIMIT 2 guard. Ambiguous strong-key match (>1 order with same business reference) is flagged immediately instead of falling through to phone+COD. Regression test: businessRef match is not vetoed by ambiguous phone+COD decoys.

---

**Bosta delivery backfill — V33 (2026-07-04):**

---

**Bosta delivery backfill — V33 (2026-07-04):**

*Problem:* Deliveries created on Bosta BEFORE the webhook was configured (or missed while it was down) were never ingested. No historical state in the system.

*Design principle:* Single ingestion code path. Backfill synthesizes a webhook-compatible `{trackingNumber, state, updatedAt}` payload, inserts into `webhook_events` as source='bosta_backfill', and enqueues `BostaWebhookJob`. All matching, state-mapping, and piece-transition logic is unchanged.

*V33 migration:*
- `ALTER TABLE courier_accounts ADD COLUMN last_backfill_at timestamptz, last_backfill_total int, last_backfill_enqueued int`
- `ALTER TYPE webhook_source ADD VALUE 'bosta_backfill'`

*Gateway:* `BostaGateway.listDeliveriesPage(apiKey, pageNumber, pageSize)` — fetches slim delivery items. Defensive envelope: handles both `{data:[...]}` and `{data:{data:[...]}}`. State/type handle both plain scalars and `{code:N, value:...}` object forms.

*Backfill job:* `BostaBackfillJob.run(tenantId, maxPages)` — entire job wrapped in `TenantContext.runAs` (RLS-safe). Paginates up to `maxPages` (default 20 × 50 = 1000 deliveries). Per-item: `fetchDelivery` for full shape + `updatedAt`, synthesize payload, insert `webhook_events`, enqueue. Counter update at end. 100ms inter-fetch throttle (configurable, disabled in tests).

*Triggers:* On `POST /bosta/connect` (fire-and-forget after account persisted) and on-demand via `POST /api/v1/bosta/sync` (OWNER). `GET /api/v1/bosta/sync/status` returns `{lastBackfillAt, lastBackfillTotal, lastBackfillEnqueued}`.

*Frontend:* `Connections.tsx` — `BostaCard` shows "Sync Bosta deliveries" button when connected, last-sync timestamp, and count. `api.ts`: `bostaSync()` and `bostaGetSyncStatus()`.

*Idempotency:* Synthesized payload uses `updatedAt` from the fetched delivery so `sha256(trackingNumber:stateCode:updatedAt)` matches the key a live webhook would produce. Re-running backfill or a subsequent live webhook for the same (tracking, state, updatedAt) deduplicates via existing `BostaWebhookJob` step 4.

*Piece state machine fix:* Added `awaiting_pickup → delivered` to `InventoryLedger.ALLOWED`. Required for backfill path where a delivery at state=45 was never seen at state=41 — `tryMatchDelivery` transitions pieces `packed → awaiting_pickup`, then step 10 goes `awaiting_pickup → delivered`. Also valid for live same-day delivery where Bosta skips the pickup update.

*Tests (BostaBackfillTest.java — 15 cases):*
- Routes through webhook pipeline (same outcome as live webhook)
- Idempotency: backfill + same-state webhook dedup; run twice dedup; state change processed normally
- Mid-lifecycle state=45: two ledger events (tracking_linked + courier_update)
- Order status NOT advanced (documented pilot limitation)
- Counter update on courier_accounts
- Mode B: only listDeliveriesPage + fetchDelivery called (no write endpoints)
- Owner-only: 403 on /sync and /sync/status for managers
- Page cap stops at maxPages
- 404 delivery skipped, counter reflects seen count
- Connect endpoint triggers backfill job

*Also:* `BostaListShapeTest.java` (7 cases) — pure JSON parsing regressions for both envelope shapes and object/scalar state/type.

*Decisions:*
- Page cap only (no date filter) in v1. Idempotency makes re-runs safe.
- Order status non-reconciliation accepted for pilot — shipments and pieces are correct, orders.status is not.

---

**Shopify Client Credentials (CC) grant — custom_app_cc pilot path (2026-07-02):**

*Overview:* Replaces the `custom_app` (admin token) path with a proper Shopify Client Credentials OAuth grant. Token lifetime ~24h; re-exchanged automatically on expiry by `ShopifyTokenProvider`. Stored under `connection_type='custom_app_cc'`. The original `custom_app` path and the OAuth path are **untouched**.

*V32 migration:*
- `ALTER TABLE stores ADD COLUMN IF NOT EXISTS client_id_encrypted text`
- `CREATE OR REPLACE FUNCTION upgrade_custom_app_to_oauth` — amended `WHERE` clause now covers both `'custom_app'` and `'custom_app_cc'`; `client_id_encrypted` is cleared on upgrade

*New interface method:* `ShopifyGateway.exchangeClientCredentials(shopDomain, clientId, clientSecret)` → implemented in `ShopifyHttpGateway`. POSTs `grant_type=client_credentials` to Shopify. Catches 4xx → `ShopifyStoreNeedsReauthException`, 5xx → `ShopifyTransientException`. No plaintext credentials in logs.

*New service method:* `ShopifySyncService.connectCustomAppCC(...)` — UPSERTs store with `connection_type='custom_app_cc'`, encrypts access token + client ID + secret, stores real expiry (not 100-year trick), clears refresh_token fields.

*Endpoint:* `POST /api/v1/shopify/custom-connect` now accepts `{shopDomain, clientId, clientSecret}` (replaces `adminToken`/`apiSecret`). Performs: CC exchange → `fetchShop` validation → `connectCustomAppCC` → enqueue import + webhooks jobs → 202.

*Token re-exchange:* `ShopifyTokenProvider` extended with `reExchangeWithLock()`. The CC branch is checked BEFORE the `refreshTokenEncrypted == null` check (CC stores have null refresh tokens by design). Uses holder-pattern to commit the `SET_NEEDS_REAUTH` update in a separate transaction before throwing (avoids rollback losing the reauth mark). `isFresh()` hot path unchanged.

*Phase B webhook HMAC:* `ShopifyWebhookController` query updated to `IN ('custom_app', 'custom_app_cc')` — both types use `api_secret_encrypted` as the webhook signing key.

*Connections status:* `ConnectionsController` query similarly updated to `IN ('custom_app', 'custom_app_cc')`.

*Frontend:*
- `api.ts`: `shopifyCustomConnect(shopDomain, clientId, clientSecret)` — body now `{shopDomain, clientId, clientSecret}`
- `Connections.tsx`: `ShopifyCustomAppCard` rewritten. State: `shopDomain`, `clientId`, `clientSecret` (removed `adminToken`/`apiSecret`). Title: "Custom App (Client Credentials)". Setup instructions panel added. Form fields: Shop domain, Client ID, Client Secret (password type). Amber warning banner always shown.

*Tests:* `CustomAppConnectTest.java` — 20 cases (CC1–CC15 plus 5 preserved). Key fixes discovered: `@MockBean` is auto-reset by `MockitoTestExecutionListener` after `@AfterEach`, so stubs must be established in `@BeforeEach`. The holder-pattern in `reExchangeWithLock` needed to avoid the transaction rollback losing the `needs_reauth` update.

*Decisions:*
- `grant_type=client_credentials` is the Shopify CC endpoint; no refresh_token is issued; token lifetime 86399s (~24h).
- `client_id_encrypted` stored alongside `api_secret_encrypted`; both required for re-exchange.
- `needs_reauth` commit outside the row-lock transaction (separate `tx.execute`) — transient 5xx does NOT mark `needs_reauth`, only 4xx does.

---

**Custom-app pilot connection path + Mode-B shopifyOrderId matching (2026-07-02):**

*New endpoint:* `POST /api/v1/shopify/custom-connect` (OWNER only, gated by `CUSTOM_APP_CONNECT_ENABLED` feature flag, default `false`). Accepts `shopDomain`, `adminToken`, `apiSecret`. Validates shop via `/shop.json`, guards against rotating tokens (rejects any token not starting with `shpat_`), encrypts both credentials and stores `connection_type='custom_app'`. Existing `POST /api/v1/shopify/connect` (OAuth path) is **untouched**.

*Required custom-app scopes:* `read_orders, read_products, read_fulfillments, write_webhooks`.

*V31 migration:*
- `ALTER TABLE stores ADD COLUMN connection_type text NOT NULL DEFAULT 'oauth'` — all existing rows stay `'oauth'`
- `ALTER TABLE stores ADD COLUMN api_secret_encrypted text`
- New SECURITY DEFINER function `upgrade_custom_app_to_oauth(...)` (sixth approved escape hatch) — Option-B upgrade path: atomically creates new tenant+owner, updates store row in-place (same store UUID), re-assigns child data tenant_ids (orders, products, variants, order_items, shipments, locations, shopify_webhook_events, unlinked_bosta_deliveries). Option-A (disconnect + reinstall) is the current operational procedure.

*Two-phase webhook HMAC:* `ShopifyWebhookController` now tries global `client-secret` first (hot path, zero overhead for OAuth stores). On failure, looks up per-store `api_secret_encrypted` for `connection_type='custom_app'` stores, decrypts, and verifies. Both fail → 401.

*Mode-B shopifyOrderId matching:*
- `BostaDelivery` record: added `shopifyOrderId` field
- `BostaHttpGateway`: parses `data.path("shopifyOrderId")` from Bosta API response
- `ShipmentLinkService.matchByBusinessReference`: extended to also check `shopifyOrderId` against `orders.external_id = 'gid://shopify/Order/' + shopifyOrderId`. businessReference tried first; shopifyOrderId is the fallback.

*Connections status:* `GET /api/v1/connections` now returns `shopifyCustomApp` (status of custom_app stores only) and `customAppAvailable` (feature flag value).

*Frontend:*
- `api.ts`: `ConnectionsStatus` type extended with `shopifyCustomApp` + `customAppAvailable`; new `shopifyCustomConnect()` function
- `Connections.tsx`: `ShopifyCustomAppCard` component rendered only when `customAppAvailable=true`. Amber border + "PILOT / TEMPORARY" label. Client-side `shpat_` token prefix validation. Amber "will be replaced by OAuth" banner when connected. Existing `ShopifyCard` + `shopifyInitiate` **untouched**.

*Tests:* `CustomAppConnectTest.java` — 16 cases covering: valid connect (202 + encrypted DB row), feature flag off (403), invalid domain (400), rotating token (400), non-owner (403), gateway failure, webhook HMAC Phase A + Phase B + wrong secret (401), null PII import, shopifyOrderId Mode-B match, businessReference-first match priority, connections status keys, RLS tenant isolation, Option-B upgrade path preserves child data.

*Decisions:*
- Rotating-token detection by `shpat_` prefix (Partner Dashboard permanent tokens always have this prefix; token-exchange flow produces expiring tokens with different prefixes).
- `connection_type DEFAULT 'oauth'` — zero-touch migration, no backfill needed.
- `upgrade_custom_app_to_oauth` implemented now to avoid future block; not yet called in production (Option A operationally for pilots).

---

**httpOnly-cookie persistent sessions — Part 1 + Part 2 (2026-06-30):**

Two-part auth overhaul that fixes browser refresh 401s and keeps users signed in across sessions.

*Part 1 — SPA route 401 on browser refresh (commit `7c921eb`):*

SecurityConfig `requestMatchers(...).permitAll()` was only covering `/login` and `/signup`. All 14 protected SPA routes (e.g. `/overview`, `/orders`, `/orders/*`, `/catalog`, ...) were falling through to `anyRequest().authenticated()`, causing the Spring filter chain to return HTTP 401 when a browser refreshed on those URLs. Fix: add all SPA routes to the permit list (shell serving only — `/api/**` stays auth-gated). `SpaRoutingTest` +15 cases (14 route assertions + 1 API 401 guard).

*Part 2 — httpOnly cookie refresh token (commits `1bfea92`, `7292a8c`, `a2793ae`):*

**Backend:**
- `AccessTokenResponse` record: structurally removes `refreshToken` from all response bodies.
- `AuthController`: login + signup set `traced_refresh` httpOnly + Secure + SameSite=Lax cookie (`Path=/api/v1/auth/refresh`, `Max-Age=2592000` = 30 days). Refresh reads `@CookieValue("traced_refresh")` — cookie is rotated on each call. Logout sets `Max-Age=0` to expire the cookie client-side. PIN switch (`pinSwitch`) returns `AccessTokenResponse` without touching the cookie — the worker session keeps the device's original cookie.
- `MagicLinkController`: stop putting tokens in URL fragment (was broken AND leaked refresh token into browser history). Now sets the httpOnly cookie and redirects to `{appUrl}/` with no tokens in the URL. SPA gets access token via on-load refresh.
- `ApiExceptionHandler`: `MissingRequestCookieException` → 401 (not 400).
- Access token lifetime: 15 → 30 min (fewer cycles now that silent refresh is in place).

**Frontend:**
- `auth.ts`: new module-level `_accessToken` store. Access token lives in JS memory only — never localStorage, never a cookie JS can read. Lost on page reload by design; `RequireAuth` restores it.
- `api.ts`: reads `getAccessToken()`; adds `doRefresh()` + `refreshPromise` dedup (concurrent 401s share one refresh call); `RETRY_FLAG` Symbol prevents infinite loops on real 401.
- `RequireAuth` (App.tsx): async loading/authenticated/unauthenticated states. Fast path if token already in memory. On page reload: shows spinner → calls `POST /api/v1/auth/refresh` → success stores token + renders, failure → `/login`. Eliminates forced re-login on browser refresh.
- `Login.tsx` + `Signup.tsx`: `setAccessToken()` instead of `localStorage.setItem`. Both run `localStorage.removeItem('token')` on mount to clean up the pre-cookie stale key.
- `Layout.tsx`: logout now calls `POST /api/v1/auth/logout` (server revokes DB refresh token + expires cookie), then `clearAccessToken()` + navigate.
- `Fulfill.tsx`, `Returns.tsx`, `Receiving.tsx`: inline fetch helpers updated from localStorage to `getAccessToken()` / `clearAccessToken()`.

**Tests:**
- `CookieAuthTest` — 12 cases (CA1–CA12): login/signup body + cookie; refresh rotation; missing/revoked cookie → 401; logout expires cookie; `refreshToken` absent from every endpoint body; cookie attributes (HttpOnly, Secure, SameSite=Lax, Path, Max-Age=2592000); path scoped to refresh endpoint; CORS rejects unknown origin.
- `AuthIntegrationTest` — helpers return `AccessTokenResponse`; test 6 (refresh rotation) uses `Cookie:` header.
- `ShopifyMagicLinkTest` — tests 1 + 7 updated: verify `Set-Cookie: traced_refresh=` + clean redirect URL; use cookie via refresh to get access token for JWT claim assertions.

**Decisions:**
- SameSite=Lax is sufficient CSRF protection for the refresh endpoint (no cross-site POST delivers the cookie on Lax browsers, and POST is not a "safe" top-level navigation). Defense-in-depth: CORS is restrictive.
- `Path=/api/v1/auth/refresh` — cookie only ever sent to the one endpoint. Never sent to `/api/v1/embedded/*` or any other route. ShopifySessionTokenFilter is structurally unaffected.
- No `refreshToken` in any response body. Structural enforcement via `AccessTokenResponse` record (no field to accidentally include).

---

**Order-intake stuck-pending fix + register-webhooks endpoint (2026-06-29):**

Root cause of "orders not syncing after needs_reauth recovery":

`shouldEnqueue` in `acquireOrRefreshViaSessionToken()` only checked `(idle, failed)` for the connected case. A store left in `connected/pending` — job was enqueued but JobRunr wasn't running, or job crashed before updating `import_status` — became permanently stuck: every token-exchange returned 204 "success" but `shouldEnqueue = false` → no jobs re-enqueued → webhooks never re-registered → orders never synced. Supabase confirmed: `traceability-dev.myshopify.com` had `import_status = pending`, `access_token_expires_at = NULL` (offline legacy token), zero import/webhook jobs in JobRunr.

*Fix:* Added `pending` to the connected-case `shouldEnqueue` condition. Both jobs are idempotent: Shopify rejects duplicate webhook topic+url with "already taken" (treated as success); import uses ON CONFLICT DO UPDATE throughout.

*New endpoint:* `POST /api/v1/shopify/stores/{storeId}/register-webhooks` (OWNER only) — runs webhook registration synchronously. Manual recovery after needs_reauth without requiring uninstall/reinstall.

*Immediate trigger for trace-d9onuxff (`e4297db2-...`):*
1. `POST /api/v1/shopify/stores/e4297db2-b627-4129-b7fa-03bb1525a65e/register-webhooks` — re-registers orders/create, orders/updated, etc.
2. `POST /api/v1/shopify/stores/e4297db2-b627-4129-b7fa-03bb1525a65e/sync` — runs import sync.

*Test:* TE12 — `connected/pending` → exchange runs → `access_token_expires_at` set (proves exchange fires and re-enqueues).

*Commit:* `7b721d0`. 411 tests green.

---

**Hybrid session-token exchange — embedded token acquisition (2026-06-29):**

`POST /api/v1/embedded/token-exchange` fires in parallel with the four dashboard data fetches on every embedded-app mount. Replaces the need to manually call the legacy OAuth callback to acquire a Shopify access token inside the embedded context.

*Backend:*
- `ShopifySessionTokenFilter` now populates `shopDomain` on the `CustomUserDetails` principal (4th record field; null for JWT-based principals). The shop domain comes from the filter's verified `dest` claim — cannot be redirected by caller input. Null-tenant → `{"error":"NOT_PROVISIONED"}` 401 (distinct from generic `{"error":"Unauthorized"}`).
- `EmbeddedTokenExchangeController.tokenExchange()` — `@PostMapping /api/v1/embedded/token-exchange`, `@PreAuthorize("hasRole('SHOPIFY_EMBEDDED')")`, delegates to `ShopifyOAuthService.acquireOrRefreshViaSessionToken()`.
- `ShopifyOAuthService.acquireOrRefreshViaSessionToken()` — freshness gate (skip if `access_token_expires_at > now+10min AND connected`), exchanges via `ShopifyHttpGateway.exchangeSessionToken()`, persists with `EXCHANGE_SESSION_TOKEN_UPDATE` (CASE expression: only flips `import_status→pending` when `status=needs_reauth OR import_status IN (idle,failed)`; completed/running imports are never disrupted), enqueues import+webhook jobs on recovery.
- `ShopifySessionTokenExchangeException` (new) — 4xx from Shopify; does NOT trigger `needs_reauth` (refresh token still valid). `ApiExceptionHandler` maps it → 502.
- `ShopifyTransientException` → 503.
- `EmbeddedReadOnlyGuardTest` updated with `TOKEN_EXCHANGE_EXEMPTIONS` allowlist — guard still enforces read-only for all other embedded-package methods.

*Frontend (`EmbeddedApp.tsx`):*
- `useAuthFetch` accepts optional `RequestInit` options (method, headers, body).
- Token-exchange POST fires in parallel (Q5 decision — not serial before data fetches). NOT_PROVISIONED 401 → `window.top.location.href = /auth/shopify/install?shop=…` (breaks out of iframe for OAuth consent). Generic 401 → no redirect. 502/503 → silent.

*Test matrix — `ShopifyTokenExchangeTest.java` (11 tests):*
TE01 fresh (>10 min) → 204 no exchange; TE02 stale → exchange → 204; TE03 null expiry → exchange; TE04 needs_reauth recovery → status=connected import_status=pending; TE05 connected+idle → pending; TE06 4xx → 502 status unchanged; TE07 5xx → 503 state unchanged; TE08 cross-shop confinement (gateway called with principal's shop, not SHOP_B); TE09 concurrent two-tabs → both 204; TE10 no auth → 401; TE11 unknown shop → NOT_PROVISIONED body.

*Commit:* `8f4ffa1`.

**Decisions made:**
- No `SELECT FOR UPDATE` for token exchange (idempotent; not single-use like refresh tokens).
- CASE-based SQL for import_status — unconditional update would disrupt `completed` stores on every routine token refresh.
- `shopDomain` from principal only — structural cross-shop confinement, not a validation check.

**Embedded Polaris dashboard (2026-06-29):**

Full read-only dashboard in `frontend/src/embedded/EmbeddedApp.tsx`. Replaces the placeholder that showed only a stores list.

*Five sections:*
1. **Connection status** — `GET /api/v1/embedded/stores/status`. Green "Connected" badge when `status='connected'`; Banner (`tone="warning"` for `needs_reauth`, `tone="info"` for disconnected/empty) with "Open Traced to connect →" deep-link.
2. **Inventory summary** — `GET /api/v1/embedded/inventory/summary`. GroupA (6 in-flight tiles: available/reserved/packed/awaiting_pickup/with_courier/pending_inspection) in responsive `InlineGrid`; GroupB (3 terminal 30d tiles: delivered/damaged/lost) below a Divider. `MetricTile` sub-component (label + large count).
3. **Order activity** — `GET /api/v1/embedded/orders/daily-counts?days=14`. Last 14 days as a CSS bar chart (Shopify green `#008060` bars). No charting library. Empty message when all zeros.
4. **Open exceptions** — `GET /api/v1/embedded/exceptions?limit=10`. Badge count + CRITICAL→LOW rows; each row: severity Badge + type label + subjectKey + "View" deep-link to `https://app.tracedtech.com/exceptions`. "View all N →" footer when total exceeds the limit. Empty message when clean.
5. **Footer CTA** — read-only notice + "Open Traced ↗" `<a target="_blank">` that breaks out of the Shopify iframe.

*Technical:* Four parallel fetch calls in one `useEffect`; each section has independent `{ status: 'loading'|'ok'|'err' }` state. CSS bar chart uses inline `style` width % — no extra dependency.

*Commit:* `a18614c`.

*Next:* Remove `ShopifyEntryDiagFilter` (temporary diagnostic, still in codebase) after install flow confirmed end-to-end.

---

**Shopify reinstall OAuth routing fix (2026-06-29):**

Root cause of the "Shopify 404 after uninstall + reinstall" bug:

`SpaController.root()` was treating `?shop=` without `?host=` as an embedded open and forwarding to `embedded.html`. On reinstall, Shopify sends the merchant to `https://app.tracedtech.com?shop=X&hmac=...×tamp=...` — no `host=` (the app is not yet in an iframe; OAuth hasn't run). App Bridge CDN loaded in a top-level browser context, found no parent admin frame, `shopify.idToken()` hung, OAuth never ran, and when the merchant went back to admin to open the app Shopify returned 404 (app not installed).

*Fix 1 — `SpaController.root()`:*
- `host=` present → forward to `embedded.html` (genuine embedded open). UNCHANGED.
- `shop=` present, `host=` absent → redirect to `/auth/shopify/install?{qs}` (install/OAuth initiation — HMAC and shop params passed through unchanged).
- Neither → forward to `index.html` (standalone landing page). UNCHANGED.

*Fix 2 — `ShopifyOAuthController.callback()`:*
- Shopify includes `host=` in callback params for embedded apps.
- LINKED_NEW/LINKED_EXISTING: if `host=` is present, redirect to `/?shop=X&host=Y` (not bare app root). CDN App Bridge detects top-level context → navigates to Shopify admin → merchant lands in embedded app. PROVISIONED and REJECTED_CROSS_TENANT unchanged.

*Test:* `rootWithShopParamOnlyRedirectsToInstall` asserts 3xx + redirect to `/auth/shopify/install?...`. 390/390 green.

*Commit:* `190511a`.

---

**Shopify App Bridge embedded shell + shopify.app.toml (2026-06-28):**

Separate Vite entry point + `shopify.app.toml` for the embedded Shopify dashboard shell.

*`shopify.app.toml` (repo root):*
- `embedded = true`, `application_url = https://app.tracedtech.com/embedded`
- `scopes` matches `application.yml` (`read_products,read_orders,read_fulfillments,read_customers`)
- `redirect_urls` includes the OAuth callback
- GDPR mandatory webhook subscriptions (customers/data_request, customers/redact, shop/redact)
- `client_id = dev-client-id` placeholder — replace with Partner Dashboard API key before `shopify app deploy`

*Vite dual-entry build (`frontend/vite.config.ts`):*
- `rollupOptions.input: { main: index.html, embedded: embedded.html }` — two completely separate bundles
- `main-*.js` — standalone SPA, UNCHANGED; App Bridge/Polaris NOT present (confirmed by bundle sizes)
- `embedded-*.js` — App Bridge v3 + Polaris v12 + EmbeddedApp (454 KB vs 459 KB standalone; no cross-contamination)
- Packages added: `@shopify/app-bridge@3.7.x`, `@shopify/app-bridge-react@3.7.x`, `@shopify/polaris@12.x`

*`frontend/src/embedded/main.tsx`:*
- `Provider` (App Bridge) wraps `AppProvider` (Polaris) — correct nesting order
- `config = { apiKey: VITE_SHOPIFY_API_KEY, host: URLSearchParams('host'), forceRedirect: false }`
- `host` is the base64-encoded shop origin Shopify passes as `?host=` on every load

*`frontend/src/embedded/EmbeddedApp.tsx`:*
- `useAuthenticatedFetch()` — App Bridge hook that auto-attaches session tokens as `Authorization: Bearer`
- Makes ONE call to `GET /api/v1/embedded/stores/status` on mount
- Shows stores list via Polaris `Page`, `Card`, `Badge`
- Loading / error / data states — proves the round trip end-to-end

*Routing (SpaController + SecurityConfig):*
- `@GetMapping("/embedded")` exact-match → `forward:/embedded.html` (beats catch-all before it forwards to index.html)
- `/embedded.html` (has `.`) falls through to `ResourceHttpRequestHandler` — no mapping needed
- SecurityConfig `permitAll`: added `/embedded`, `/embedded.html` (shell is public; API calls authenticate via ShopifySessionTokenFilter)

*Framing headers (nginx.conf):*
- `location ^~ /embedded` block with own `add_header` — does NOT inherit server-level `X-Frame-Options: DENY`
- `proxy_hide_header X-Frame-Options` strips Spring Security's DENY from the upstream response
- `Content-Security-Policy: frame-ancestors https://admin.shopify.com https://*.myshopify.com;` — Shopify admin can frame; all other origins denied
- Standalone paths: inherit server-level `X-Frame-Options: DENY` unchanged

*Tests:* `SpaRoutingTest` +1 — `GET /embedded → forward:/embedded.html` (not `/index.html`). 387 backend tests, 47 frontend tests, all green.

3 commits: `8efb416` (shopify.app.toml), `80e79f9` (embedded shell), `91a471d` (routing + framing).

**Next:** Polaris dashboard UI — the shell proves the bridge; the full dashboard (inventory summary tiles, orders chart, exceptions list using the 4 EmbeddedController endpoints) is the next step.

---

**Shopify App Bridge embedded dashboard auth core (2026-06-28):**

`ShopifySessionTokenFilter` + `EmbeddedController` — auth core for the read-only embedded Shopify dashboard.

*Filter (`ShopifySessionTokenFilter.java`):*
- Path-scoped to `/api/v1/embedded/**` via `shouldNotFilter()`.
- HS256 session-token validation: `SignedJWT.parse()` (throws on alg=none PlainJWT), explicit `JWSAlgorithm.HS256` header check before `MACVerifier` verify (blocks RS256 alg-confusion), signature verification with Shopify client secret.
- Claims: exp/nbf/iat (10s clock skew), aud (accepts both string and `List<String>` — Nimbus normalizes both), iss/dest domain cross-check (both must be the same `*.myshopify.com` host).
- Tenant lookup via `resolve_tenant_by_shop_domain(domain)` SECURITY DEFINER function — fail-closed (null or exception → 401, no default tenant).
- Synthetic userId: `UUID.nameUUIDFromBytes(sub.getBytes(UTF_8))` — deterministic UUID v3 from Shopify GID, never written to DB.
- Two-wall model: Wall 1 = `shouldNotFilter()` path scope. Wall 2 = `@PreAuthorize("hasRole('SHOPIFY_EMBEDDED')")` on every endpoint. Traced JWT on embedded path → passes filter → 403 at `@PreAuthorize` (correct).
- `reject()` uses `response.setStatus(401)` NOT `sendError()` (avoids ERROR-dispatch chain documented in CLAUDE.md).
- Filter order: `JwtAuthenticationFilter → ShopifySessionTokenFilter → TenantContextFilter`.

*Controller (`EmbeddedController.java`):*
- 4 read-only `@GetMapping` endpoints: `/inventory/summary`, `/orders/daily-counts`, `/stores/status`, `/exceptions`.
- All gated by `@PreAuthorize("hasRole('SHOPIFY_EMBEDDED')")` — OWNER/MANAGER tokens cannot pass.
- All queries wrapped in `TransactionTemplate.execute()` so `TenantAwareConnection` fires the GUC before SQL executes.
- `RowCallbackHandler` cast resolves `JdbcTemplate.query()` overload ambiguity.

*Tests:*
- `ShopifySessionTokenFilterTest` — 17 direct filter tests (mock `FilterChain`, NOT standalone MockMvc — standalone dispatches to servlet even when filter doesn't call `chain.doFilter()`, which masks early-termination rejections). Covers: alg=none, alg=RS256, tampered sig, expired, wrong aud (string+array), iss/dest mismatch, null tenant, DB exception, unparseable, no header, non-Bearer prefix, valid string aud, valid array aud, non-embedded path filter skip.
- `EmbeddedIntegrationTest` — E1–E11 against real Postgres (Testcontainers). Load-bearing: E2/E3 cross-tenant isolation (shop-A token sees only shop-A stores, NOT shop-B), E6 Traced OWNER JWT → 403 on embedded endpoint, E4/E5/E7 Shopify token on non-embedded paths → 401.
- `EmbeddedReadOnlyGuardTest` — reflection CI guard: fails build if any `@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping` appears anywhere in the `com.traceability.embedded` package.
- `SpaRoutingTest` — added `@MockBean JdbcTemplate` for updated `SecurityConfig.filterChain()` signature.

*Config changes:*
- `application.yml` default `shopify.client-secret` bumped to 34 bytes (Nimbus HS256 minimum is 256 bits = 32 bytes).
- `test/resources/application.properties` `shopify.client-secret` bumped to `test-shopify-secret-32-bytes-abc!!` (34 bytes). All HMAC tests inject the secret via `@Value` and use Java `Mac` which has no minimum key length — no regressions.

3 commits: `154699f` (filter+config), `6493d58` (EmbeddedController), `ba722d9` (tests).

**Next:** App Bridge frontend (Polaris UI) + `shopify.app.toml` — the auth core is complete; the frontend shell and `shopify.app.toml` come next as a separate task.

---

**Signup consent gate — FR-1.1 (2026-06-28):**

Full client + server consent gate. Backend: V30 migration adds `accepted_privacy_version`, `accepted_terms_version`, `accepted_at` (nullable) to `users` — existing RLS covers columns automatically, no new grants. `PolicyVersions.java` is the single source of truth (PRIVACY="1.0", TERMS="1.0"; bump here when policy is re-published). `SignupRequest` record gains `boolean consent`; `AuthService.signup()` throws 422 if `consent != true` and uses the Cairo clock to stamp `accepted_at`. `AuthRepository.createTenantWithOwner` persists all three consent columns on the owner INSERT. `GET /api/v1/tenant/settings` now also returns `consentPrivacyVersion`, `consentTermsVersion`, `consentAcceptedAt` (queried from the requesting user's row). Frontend: consent checkbox in `Signup.tsx` (wired through `react-i18next`; EN + AR translations added); button stays disabled until box is ticked AND all required fields are filled; both policy links open in a new tab. Settings page shows a read-only "Legal agreement" card with versions and accepted-at. 3 new backend tests (consent rejected without flag; versions + timestamp persisted; RLS enforced on consent columns via app_user). `MigrationSmokeTest` updated to expect V1–V30. 361 total backend tests, all green. TypeScript clean, production build clean.

**Privacy & Terms pages (2026-06-28):**

`/privacy` and `/terms` public routes render `docs/legal/privacy-policy.md` and `docs/legal/terms-of-service.md` via `react-markdown` + `remark-gfm`. All `[BRACKET]` placeholders resolved (entity: Traced, address: North Investors Area Cairo, contact: hello@tracedtech.com, effective 1 July 2026). Reviewer blockquote suppressed at render time (`blockquote: () => null`). Shared `LegalPage.tsx` component: scroll-blur nav, styled h1–h3/p/strong/a/ul/li/table, `max-w-prose` body, branded footer with Privacy/Terms/Contact links. Landing page footer updated to include "Privacy Policy" and "Terms of Service" links. Vite `server.fs.allow: ['..']` added for parent-dir `.md` import. TypeScript clean, zero errors. Commits: see below.

**Landing page (public marketing surface, 2026-06-27):**

`frontend/src/pages/Landing.tsx` built and wired to `/` route (public, no RequireAuth). Sections in order: sticky nav · hero (particle canvas, dashboard mock with animated stat counters + SVG chart, phone timeline mock) · How it works · Why Traced · Positioning + elevator pitch · Pricing (EGP 999/mo, single plan) · Mission/Vision/Brand story · Flow strip (Warehouse→Store→In Transit→Customer→Returns) · Footer. All copy verbatim from `docs/landing-page-build-spec.md §5`. Brand tokens and components reused from existing Tailwind config — no parallel design system. All animations degrade under `prefers-reduced-motion`. Single `SIGNUP_URL` constant (env `VITE_APP_SIGNUP_URL` → `/signup`) used by all CTAs. **TODO(payment)**: replace `SIGNUP_URL` direct redirect with checkout → payment (Instapay/bank transfer per FR-1.5) → provision → redirect. `vite-env.d.ts` added (was missing; now TypeScript resolves `import.meta.env`). TypeScript clean, zero errors.

**detectReturnInTransitStuck snooze re-fire (Day 44 addendum):**

Dismissing a stuck-piece exception previously buried it permanently. Changed the `NOT EXISTS (exception_resolutions ...)` suppression clause to add `AND er.resolved_at > now() - interval '7 days'` — a dismissal now acts as a 7-day snooze. After 7 days, a genuinely still-stuck piece re-surfaces automatically.

Processed pieces (those that have a `return_received` event and/or are no longer in `return_in_transit`) remain permanently excluded by the status predicate and the `NOT EXISTS (piece_events/return_received)` guard — those two guards are independent of dismissal age.

Column used: `resolved_at` (set by `DEFAULT now()` on INSERT in `exception_resolutions`, defined in V11).

3 new tests in `ReturnSessionTest` (m/n/o): dismiss-2d→suppressed, dismiss-8d→re-fires, dismiss-8d+processed→never re-fires. 15/15 `ReturnSessionTest`. 357 total backend (1 pre-existing flaky: `Fr9ManifestSelfPickupTest` fails on Fridays due to Bosta not scheduling pickups — unrelated).

*Commit:* `82a0230`.

**Fr9ManifestSelfPickupTest clock-pin fix:**

Brittleness (not a regression). `t1` called `LocalDate.now().plusDays(1)` without a `@MockBean Clock`, so running on Thursday → date = Friday → `BostaPickupService` rejected with 400 (error 1080 client-side guard). Same class as the pre-existing fix in `AwbPickupTest`. Fix: `@MockBean Clock` pinned to Wed 2026-06-17 (Cairo) in `@BeforeEach`; hardcoded `THURSDAY = 2026-06-18` replaces the dynamic date. A far-future literal would not have worked — `schedulePickup()` also rejects past dates using `LocalDate.now(clock)`, so the mock is necessary. 345/345 deterministically green on any weekday. *Commit:* `ce813c0`.

**Fulfill restyle + Print Waybill (Day 42):**

*Part A — Theme restyle:* All five Fulfill.tsx components (QueueView, PickScreen, HandoverScreen, GuidedUnpackPanel, AwbLinkDialog) converted to dark design system. bg-white/gray/indigo/green/amber → bg-base/panel/elevated + `.card`. Scan inputs use `input-scan`. Flash overlays use `bg-success/20`/`bg-danger/20` matching Returns.tsx. All buttons use `btn-brand`/`btn-outline`/`btn-danger`/`btn-ghost`. Logical RTL props (`ms-`/`ps-`/`text-end`). Progress bar: `bg-brand` on `bg-elevated`.

*Part B — Print Waybill (Mode B):* Button in PickScreen bottom bar with 3 states. `FulfillService.getOrder()` LEFT JOINs shipments to expose `shipment_id` and `tracking_number`. PRINTABLE → calls `POST /api/v1/bosta/awb/print` → decodes base64 PDF → `window.open`. NOT-YET-LINKED → disabled + note. ERROR → inline danger note, pack flow unaffected. AWB dialog after linking: shows Print Waybill + Done (replaces 1800ms auto-close); uses shipmentId already returned by `/fulfill/{id}/link`. No delivery creation — Mode B only.

*6 new frontend tests (ft1–ft6). 29 total frontend tests green.*

*Commits:* `a5c1d48` (frontend), `04d0564` (backend).

**Returns session flow (Day 43):**

Session tab was already built (Day 41 — waybill scan → piece list → verdict → finalize). Added the missing pieces in this session:

*Damage-reason validation:* `recordVerdict` now blocks if reason is empty; shows `data-testid="damage-reason-error"` inline. Reason input's onChange clears the error immediately.

*Reprint after damage:* After a damage verdict, `damagedPieceIds` set is updated. The piece card shows a "Print piece label" button (`data-testid="reprint-{pieceId}"`). `handleReprint` calls `printPieceLabel(pieceId)` → `GET /returns/pieces/{pieceId}/label` → blob PDF → `window.open`. Errors shown inline per piece. Damaged pieces stay at full opacity (not opacity-60) so the button is clearly clickable.

*`data-testid` hooks:* `session-error`, `pieces-list`, `out-of-window-nudge`, `switch-to-intake`, `damage-reason-error`, `reprint-{pieceId}`, `session-finalized`.

*Tab structure:* Session tab is PRIMARY (default). Waybill-less intake is SECONDARY (labeled fallback with explanation of when to use it). Out-of-window nudge routes worker to intake tab via `onSwitchToIntake()` callback.

*Un-scanned delivered pieces:* No danger/warning styling. Optional note: "optional — customer may have kept this". Only RTO unresolved count shown in finalize summary as the actionable metric.

*11 new tests (rt1–rt11):* session start success/404/422; piece list RIT+delivered+processed; un-scanned delivered not alarming; restock verdict; damage without reason blocked; damage with reason + reprint offered; out-of-window nudge + switch button; finalize counts (delivered-kept not in danger color); dark tokens + input-scan.

*Commit:* `333e3b7`.

**Blocklist + Exceptions theme fixes (Day 43 follow-up):**

*Blocklist:* `bg-surface` was undefined in Tailwind — rendered as transparent (visible bug), now `bg-panel`. `btn-primary` (non-existent) → `btn-brand btn`. All `text-red-500`/`hover:text-red-500` → `text-danger`/`hover:text-danger`. Heading `text-h2 font-bold` → `text-h1 font-light` (brand weight). AddModal converted from raw `fixed inset-0` div stack → system `<Modal>` component (bg-panel, backdrop-blur, ✕ button). Hardcoded "Source" column header → `t('blocklist.col.source')` with EN/AR keys added.

*Exceptions:* Cancel button `text-red-500 border-red-200` → `text-danger border-danger/30` (one-line fix).

*Connections:* Left as-is — hardcoded brand hex colors (#5a31f4 Shopify, #f59e0b Bosta) are intentional third-party brand colors, not token violations.

*Tests:* fb6 (token spot-check: no bg-surface, btn-brand present, no text-red-*) + fb7 (system Modal: bg-panel present, ✕ close button, title rendered). 42 total green.

*Commit:* `5ad84ab`.

**Logo + Orders chart (Day 44):**

*Logo SVG (Part A):* `<Logo variant="icon"|"wordmark" size={n} />` component in `components/Logo.tsx`. Inline SVG (no file/font dep): center node (r=3.5 in 32×32 viewBox) + 8 outer nodes at r=11 on equidistant spokes. Uses `currentColor` → `text-brand` colors it. Crisp at 24–64px. Three locations replaced: Layout sidebar (size=32), Login (size=56), Signup (size=56). "T" placeholder is gone. No Arabic-specific wordmark needed — icon + Latin wordmark works at both orientations.

*Chart (Part B):* No recharts in the project — installed nothing. Built a plain SVG line chart (OrdersChart component inline in Overview.tsx). Data source: new `GET /api/v1/orders/daily-counts?days=30` backend endpoint in OrderController — `generate_series` fills all 30 days with zero-padding, RLS + explicit tenant_id guard, returns `[{date, count}]`. Frontend fetches via `getOrderDailyCounts()` in api.ts. Chart: brand-colored (#6366FF) line + area gradient, grid lines (#2D3F55), muted (#647488) axis labels. 3 states: loading (Spinner), all-zero (empty message), data (SVG). RTL note: SVG rendering is LTR regardless of dir — x-axis labels (MM-DD) are still readable; this is a known chart limitation accepted for now.

*Tests:* 5 new (ov1–ov5): Logo SVG in Login, chart loading/empty/data/error. inventory.test.tsx updated to mock `getOrderDailyCounts`. 47 total green.

*Commits:* `fc6b145` (logo), `372e92c` (chart + backend).

Next up: Server provisioning runbook (Hetzner, firewall, Docker, first deploy) — Deploy-prep 3.

**Returns-receiving session (Day 41):**

New waybill-driven returns flow replacing the standalone barcode-scan intake as primary UX.

*Schema (V29):* `receipts.kind` discriminator (`inbound`/`returns`); `tenants.customer_return_window_days` (default 30); `tenants.return_in_transit_stuck_days` (default 3).

*Ledger edge:* `delivered:return_pending_inspection` added to `InventoryLedger.ALLOWED`. Guard enforced in `ReturnSessionService.enforceReturnWindow()` — checks `pieces.last_event_at` against `customer_return_window_days` using injected Clock. Hard reject (422) outside the window with a message directing the worker to the waybill-less intake. `recordReturnReceived()` gains optional metadata param (session_id + return_kind). `recordLabelReprinted()` is the 4th write path on `InventoryLedger` — no status change, custody event only.

*`return_kind` metadata:* RTO pieces → `{"return_kind":"rto","session_id":"..."}`. Customer-after-delivery pieces → `{"return_kind":"customer_after_delivery","session_id":"..."}`. Both stored in `piece_events.metadata` (jsonb, PostgreSQL-normalized on read).

*Session flow:* `POST /returns/sessions` validates shipment state (returning/returned/delivered/exception only — rejects with_courier etc.). `GET /sessions/{id}/pieces` queries both `return_in_transit` AND `delivered` pieces linked to the waybill. `POST /sessions/{id}/pieces/{id}/verdict` — atomic two-step: intake transition (DELIVERED→RPI or RIT→RPI) + restock/damage, both within one `@Transactional(READ_COMMITTED)` boundary via Spring REQUIRED propagation. `POST /sessions/{id}/finalize` does NOT block on unresolved pieces.

*Change 2 (finalize summary):* `unresolvedRtoCount` = unscanned `return_in_transit` pieces (actionable). `deliveredKeptCount` = unscanned `delivered` pieces (expected — customer kept them). These are separated so the UI never implies a delivered-but-unscanned piece is a problem.

*Change 3 (reprint scope):* `GET /returns/pieces/{id}/label` rejects pieces not in `return_pending_inspection` or `damaged` — returns 422 with explanation. Validates and records `label_reprinted` custody event; controller then calls `LabelService.generatePieceLabel()` for the PDF.

*15th detector:* `detectReturnInTransitStuck` — fires when `piece.status = return_in_transit` AND `last_event_at < now() - N days` AND no `return_received` event. Subject: piece. Type: `return_in_transit_stuck`. Severity: HIGH. Does not collide with `detectStuck` (subject = shipment, different key) or `detectNeverReceived` (requires `returned` state). `ReceivingService.listSessions()` now adds `AND kind = 'inbound'` filter.

*Frontend (Returns.tsx):* Session tab is primary (leftmost, default). Waybill-less Intake tab is secondary with a "fallback" badge and explanatory note. Out-of-window 422 surfaces inline on the specific piece with a one-click "Switch to waybill-less intake" button. Finalize summary distinguishes `unresolvedRtoCount` from `deliveredKeptCount`. Delivered-but-unscanned pieces show "(optional — customer may have kept this)" — not flagged as errors.

*Tests:* 12 new `ReturnSessionTest` + 1 `MigrationSmokeTest` bump (28→29). Total: 354 backend, 23 frontend. All green.

*Commits:* `f3c467d` (backend), `c040c81` (frontend).

Next up: Server provisioning runbook (Hetzner/Oracle, firewall, Docker, first deploy) — Deploy-prep 3.

**Deploy-prep 2 (Day 40) — Dockerfile + Nginx + Compose + .env.example + DEPLOY-NOTES:**

Multi-stage Dockerfile: Stage 1 = Node 22 Vite build (outputs to `src/main/resources/static` — Spring serves SPA, not nginx); Stage 2 = Maven JAR build with `-Dskip.frontend=true`; Stage 3 = `eclipse-temurin:21-jre-alpine`, non-root user, `MaxRAMPercentage=75.0` for CX32 4GB, `-Duser.timezone=Africa/Cairo`. Added `spring-boot-starter-actuator` (was missing) + `management.endpoints.web.exposure.include=health` — needed for Docker HEALTHCHECK and compose `depends_on: service_healthy`.

`deploy/docker-compose.yml`: app + nginx only. No db service — Postgres is Supabase external. nginx depends on app healthcheck.

`deploy/nginx.conf`: HTTP→HTTPS redirect, Let's Encrypt certs (host-mounted), Cloudflare real-IP (`set_real_ip_from` all CF IPv4 ranges + `real_ip_header CF-Connecting-IP`), `X-Request-Id` propagation, HSTS + security headers, gzip (text/json/js/css, not PDF), 10m body limit for AWB labels + webhooks. Bosta + Shopify webhook paths reachable via catch-all `/` location.

`.env.example`: 17 vars documented. `docs/DEPLOY-NOTES.md` stub: cert assumption (certbot on host), compose commands, runbook placeholder, Supabase `ALTER DATABASE SET timezone TO 'Africa/Cairo'` note.

Note: existing `docker-compose.yml` at root is the local-dev compose (Postgres container) — left untouched.

Next up: Prep 3 — server provisioning runbook (Hetzner, firewall, Docker, first deploy).

**Deploy-prep (Day 39) — Clock injection + Sentry + Correlation IDs:**

PART 1 — Injectable Clock: `AppConfig.java` declares `Clock.system(ZoneId.of("Africa/Cairo"))` as a `@Bean`. Injected into `BostaPickupService` (replaces `LocalDate.now()` → `LocalDate.now(clock)`) and `ExceptionService` (replaces `Instant.now()` → `clock.instant()`). All other 22 `now()` call sites are security/OAuth/sync timestamps — left on wall-clock intentionally. `AwbPickupTest` now `@MockBean Clock` pinned to Wednesday 2026-06-17 10:00 Cairo; named constants `TODAY/YESTERDAY/THURSDAY/FRIDAY` replace the old `nextValidPickupDate()` tech-debt helper. Tests are now deterministic regardless of run day.

PART 2 — Sentry + Correlation IDs (NFR-6): `sentry-spring-boot-starter-jakarta` 7.14.0 added. `SentryConfig` — `BeforeSendCallback` scrubs PII keys (phone/name/address/receiver/email) from extras+tags, attaches `tenant_id` from MDC. `sentry.dsn=${SENTRY_DSN:}` in application.yml — Sentry is a no-op when env var is absent. `CorrelationIdFilter` (HIGHEST_PRECEDENCE `OncePerRequestFilter`) — generates or propagates `X-Request-Id`, sets MDC `requestId` (appears in every log line via pattern `%X{requestId:--}`), echoes header on response, tags Sentry scope. MDC always cleared in `finally`. Test coverage: 4 filter unit tests (cf1–cf4), 6 PII scrubber tests (sp1–sp6). Total test delta: 320→330.

Two commits: `595e1ff` (clock), `06724b3` (sentry).

Next up: push + deploy to Oracle Cloud VM.

**Bosta live verification (Day 38):** Ran read-only Checks 1 & 2 against the pilot's real Bosta account (key confirmed working on API v0).

CHECK 1 (AWB print via mass-awb): PASSED — `POST /api/v0/deliveries/mass-awb` with `trackingNumbers=7478049248` returned HTTP 200 and a valid PDF-1.4 (40,750 bytes) in `response.data` as a base64 string. Bug found & fixed: gateway was reading `dataNode.path("pdf")` (expecting `data.pdf` object) but the live API returns `data` as the plain base64 string directly — gateway was falling through to email-path log for every AWB request. Fix: check `dataNode.isTextual()` first, fall back to `.path("pdf")` for legacy shape.

CHECK 2 (consignee fields): The live Bosta API v0 does NOT use a `consignee` key. Recipient data is in `receiver` (`phone`, `firstName`, `lastName`, `fullName`, `secondPhone`) and the delivery address is in `dropOffAddress` (`city.name`, `zone.name`, `district.name`, `firstLine`). Phone format: `+20XXXXXXXXXX` (E.164). Bug found & fixed: `ShipmentLinkService` was reading `raw.path("consignee").path("phone")` in two places (Mode-B auto-match + deferred FR-7.8a blocklist re-check) — both now read `raw.path("receiver").path("phone")`.

Also confirmed: both v0 and v2 base URL paths work for mass-awb (same response). API v0 is the verified working path for `GET /api/v0/deliveries/{id}` and `GET /api/v0/deliveries`.

**Bosta shape fixtures + regression guards (Day 38 cont.):** `src/test/resources/bosta/delivery-receiver.json` and `mass-awb-response.json` — synthetic PII, real structure. `BostaShapeRegressionTest` (4 tests, no Spring): reg1 guards that `data` is textual (not `data.pdf`), reg2 guards that `receiver.phone` is populated and `consignee` key is absent, reg3 guards `dropOffAddress.*`, reg4 guards COD flat scalar. `ModeBMatcherTest`: `bostaRaw()` helper fixed to `receiver.phone` (not `consignee.phone`), t10 fixed, t14 added (deferred FR-7.8a blocklist re-check extracts `receiver.phone` — would fail if consignee is read again). `@AfterEach` now cleans blocklist entries. Total: 5 new tests (315→320).

RTL fix + worker i18n (Day 37 supplement): RTL dir flip bug fixed — `i18n.on('languageChanged')` in i18n.ts is now the single handler for `document.documentElement.dir` / `.lang`. Fires on startup (covering localStorage-saved AR path) and every toggle. Layout.toggleLang() simplified to just changeLanguage + localStorage write. Fulfill.tsx: 18 hardcoded `isAr ? ... : ...` patterns replaced with `t('fulfill.*')` in HandoverScreen, QueueView, GuidedUnpackPanel, PickScreen. 8 new keys added to en.json + ar.json. Dead `fulfill_extra` AR namespace removed. 2 new frontend RTL tests (rtl1/rtl2). AwbPickupTest date flakiness fixed: `nextValidPickupDate()` helper skips Fridays. Tech-debt noted: BostaPickupService.schedulePickup() uses `LocalDate.now()` directly, no injectable Clock.

Reported-not-fixed (not worker-critical): Receiving.tsx SessionStatusBadge renders raw enum; FormField placeholders hardcoded — deferred.

FR-7.9 complete: blocklist table (V28), CRUD endpoints, frontend Blocklist.tsx page, AR+EN i18n. 9 backend + 5 frontend tests. FR-7.8(a) complete: blocked-customer entry gate wired in both import paths (GraphQL + webhook). No-phone-at-entry → gate skipped (pre-PCD handled explicitly). Mode-B deferred re-check in ShipmentLinkService.tryMatchDelivery(). releaseHold() + POST /orders/{id}/release-hold. Release/Cancel actions on blocked_customer exception cards.

FR-3.6 complete. Shopify line-item edits on in-progress orders: state-routed handler extends `orders/updated` webhook. Picking orders release affected allocations via standard unreserved/released path; packed+ orders raise `shopify_edit_conflict` exception (14th detector, HIGH) without touching pieces. V27 adds signal columns. 9 backend tests green. MigrationSmokeTest count bumped 26→28.

---

**Day 36 — FR-7.9 Blocklist + FR-7.8(a) Entry Gate**

*V28: `blocklist` table — `(id, tenant_id, phone_canonical, reason, source, created_by, created_at, active)`. RLS. Partial unique index on `(tenant_id, phone_canonical) WHERE active=true`.*

**FR-7.9 — blocklist CRUD:**
- Phone canonicalized via `ShipmentLinkService.normalizePhone()` (same function as Mode-B matching). `+20` / `0020` / local 10-digit → `01XXXXXXXXX`.
- `BlocklistService`: add (soft-upsert reactivates inactive entries), remove (soft-delete), list, `isBlocked()` for gate, `checkAndHoldIfBlocked()`.
- `BlocklistController`: `GET/POST/DELETE /api/v1/blocklist` — Owner/Manager. Audit on add+remove.
- Frontend: `Blocklist.tsx` — list, add modal, inline remove confirm. AR+EN i18n in `blocklist.*` namespace.

**FR-7.8(a) — entry gate:**
- Insertion point: `ShopifySyncService.upsertOrder()` (GraphQL/reconcile path) and `ingestOrderWebhook()` (REST webhook), inside the same `tx.execute()` block after line items, same layer as `FLAG_ORDER_UNMAPPED`.
- **No-phone-at-entry (pre-PCD)**: `customer_phone = null` → gate call is a no-op. NOT a silent pass — explicitly skipped and logged. Order proceeds to `new` normally.
- **Mode-B deferred re-check (option i)**: `ShipmentLinkService.tryMatchDelivery()` re-runs `checkAndHoldIfBlocked()` after a successful phone+COD link — Bosta consignee phone is the first reliable phone for pre-PCD orders. This is the deferred gate path.
- `FulfillService.releaseHold()`: clears `on_hold`, writes audit_log. `FulfillController`: `POST /orders/{id}/release-hold` (Owner/Manager).
- `ExceptionService.detectBlocked()` **unchanged** — still fires on `on_hold=true` with NOT EXISTS suppression. Enrich now adds `releaseUrl` + `cancelUrl` to the exception item.
- `Exceptions.tsx`: blocked_customer cards show "Release (ship anyway)" + "Cancel order" action buttons wired to `releaseOrderHold()` / `cancelOrder()`.
- **Gate (c) deferred**: TODO comment in `BostaAwbService.printAwb()` catch block.

**Tests (Day37Test, 9):** add+canonicalize, duplicate-international-local conflict, blocked order held, release+audit, cancel, null-phone graceful, index exists, tenant isolation, soft-delete+unblocks.

**Frontend tests (blocklist.test.tsx, 5):** fb1 empty state, fb2 add submit, fb3 remove, fb4 release action, fb5 cancel action.

FR-13 complete. Manual adjustments: available→lost/damaged/destroyed (13.1), reserved/packed guard with release step (13.2), lost→available found-it (13.3). 8 backend + 5 frontend tests. Two commits: backend + frontend.

FR-11.3 complete (13th ExceptionService detector — `high_attempts` MEDIUM). FR-3.4 complete (Shopify reconcile poll, 15-min recurring, gap-filler only). 5 + 5 new backend tests. MigrationSmokeTest count fixed 25→26 (V26 was missing).

FR-15.1 complete. 8 backend + 5 frontend tests. Overview.tsx replaced order-count placeholders with real piece-level counts.

FR-1.2 onboarding wizard complete. 5 component tests cover all states (all-pending, partial, all-done, signal-lag hint, API error). `npm test` from `frontend/` runs all 11 in ~900ms.

FR-1.4 (Settings) and FR-2.2 (User Management) are complete. Role-gating added via JWT decode in api.ts. Clean TS compile; backend tests unchanged at 270.

V23–V25 applied. 5 backend items shipped: audit log (FR-2.6), user CRUD (FR-2.2), tenant settings (FR-1.4), connections status (FR-1.2), onboarding checklist (FR-1.2). 34 new tests. All existing 236 still green. Commits still local only (git push blocked by credential mismatch — GitHub rejects stored credential `marwanashraf56` on repo owned by `marwanashraf098`).

V21 migration applied. `detectShopifyCancelVsInflight()` wired in ExceptionService (HIGH, `shopify_cancel_vs_inflight` type). Signal written in `ShopifyWebhookProcessorJob.handleOrderCancelled()` on 409 for in-flight orders. `stuck_shipment_days` default changed 3→5 (V21 UPDATE existing rows). 6 new tests in `ExceptionExtTest`. Day13Test `stuck_shipment_days` corrected to 5.

**LIVE SMOKE TEST PENDING**: Call `POST /api/v1/bosta/awb/print` with a real pilot tracking number. Needs running app + pilot Bosta API key. Steps in Day 24 entry below.

---

**Day 35 — FR-3.6 Shopify line-item edits**

*V27: `shopify_edit_conflict_at TIMESTAMPTZ` + `shopify_edit_conflict_diff JSONB` on orders.*

**State routing (orders/updated):**
| Status | Action |
|---|---|
| `new`, `confirmed`, `ready_to_pick` | No-op — no allocations yet; line items updated by ingestOrderWebhook |
| `picking` | Diff computed → removed lines: release all their active allocations; reduced lines: release (old−new) allocations; added/increased: exception only (can't auto-allocate mid-pick) |
| `packed`, `self_pickup_pending`, `awaiting_pickup`, `with_courier`, `returning` | No touch — box sealed; exception raised only |
| terminal (`delivered`, `returned`, `lost`, `cancelled`) | No-op |

**Diff computation:** `LineDiff` record (`removed`, `reduced`, `added`, `increased`). Pre-edit local `order_items` keyed by `external_id` (Shopify line item GID, added V4) compared against incoming payload `line_items`. Diff is empty → fall through to normal `ingestOrderWebhook` with no exception.

**Release path reused:** `FulfillService.releaseActiveAllocsForItem()` → same `ledger.transition(RESERVED→AVAILABLE, "unreserved")` + `UPDATE allocations SET status='released'` two-step used by `cancelOrder` / `unscan` / `releaseForAdjust`. SHOPIFY_WEBHOOK_ACTOR = `null` (same as cancel path; `actor_user_id` FK allows NULL).

**14th ExceptionService detector:** `detectShopifyEditConflict()` — HIGH severity, NOT EXISTS suppression via `exception_resolutions`, `diffJson` passthrough in enrich.

**Idempotency:** Second call on already-released pieces: `ledger.transition()` throws `StateConflictException` (caught); allocation already `released` (UPDATE is a no-op). `setEditConflictSignal` uses `COALESCE(shopify_edit_conflict_at, now())` — timestamp frozen on first signal.

**Tests (Day36Test, 9):** picking remove; picking reduce (2 of 3 released); picking add no-auto-alloc; new no-op; packed untouched; with_courier exception-only; null actor sentinel; double-release idempotency; tenant isolation.

**Removed lines policy:** `allocations.order_item_id` FK has no CASCADE — removed order_item rows kept as historical records; only their allocations are released.

---

**Day 34 — FR-13 manual adjustments**

*No migration — ALLOWED transitions (available:lost, available:damaged, available:destroyed, lost:available) already in InventoryLedger.*

**FR-13.1 — adjust endpoint:**
- `POST /api/v1/pieces/{id}/adjust` (OWNER/MANAGER). Body: `{ toStatus, reason, note? }`.
- Reason enum: `cycle_count_missing`, `damaged_in_storage`, `sample_giveaway`, `theft_suspected`, `receiving_correction`, `other`. `reason=other` requires non-blank note (400).
- Writes `adjusted` event via `InventoryLedger.transition()` + audit_log via `AuditService`.
- `phraseKey("adjusted", from, "available")` → `found_it`; all other `adjusted` → `adjusted`.

**FR-13.2 — committed guard:**
- Reserved/packed piece → `PieceCommittedException` (409 PIECE_COMMITTED, body: `{ error, orderId, orderNumber }`). Handled in `ApiExceptionHandler`.
- `POST /api/v1/pieces/{id}/release-for-adjust`: finds `active` allocation → `reserved→available` ("unreserved" event) + release; `packed` allocation → `packed→available` ("unpacked" event) + release. Same two-step ops as `FulfillService.unscan()/unpackPiece()`. Two explicit operator steps design confirmed.

**FR-13.3 — found it:**
- Same `/adjust` endpoint with `toStatus=available`. `damaged/destroyed→available` → 409 (terminal). `lost→available` → writes `adjusted` event. Original lost event preserved (append-only).

**Frontend:**
- `AdjustPanel` component embedded in `PieceView` (Lookup.tsx). Available → adjust dialog. Lost → "Found It" + "Adjust" buttons. Damaged/destroyed → no UI (terminal). PIECE_COMMITTED 409 → shows blocking order + "Release from order" button.
- `api.ts`: `adjustPiece()`, `releasePieceForAdjust()`, `ADJUST_REASONS`, `AdjustReason`, `PieceCommittedError`. i18n keys in `adjust.*` namespace.
- `@testing-library/user-event` installed for Vitest (was missing).

**Tests:**
- Backend (AdjustTest, 8): adj1 available→lost event+audit; adj2 other-without-note 400; adj3 reserved→PIECE_COMMITTED; adj4 packed→PIECE_COMMITTED; adj5 release frees allocation; adj6 found-it appends event preserves original; adj7 terminal reverse 409; adj8 tenant isolation.
- Frontend (adjust.test.tsx, 5): fa1 adjust dialog submit; fa2 other-without-note disabled; fa3 lost shows both buttons; fa4 found-it calls adjustPiece(available); fa5 damaged no adjust button.

**Cleanup applied in @AfterEach:**
- `pieces.current_order_id = NULL` before `DELETE FROM orders` — FK constraint prevents order delete when piece still references it.

---

**Day 33 — FR-11.3 attempts detector + FR-3.4 reconciliation poll**

*No migration — both FRs use existing schema.*

**Item 1 — FR-11.3: `high_attempts` exception detector (13th detector):**
- `ExceptionService.detectHighAttempts()` — non-terminal shipments with `number_of_attempts >= 2`, MEDIUM severity.
- `number_of_attempts` is already stored on `shipments` (written by `BostaWebhookJob` from every webhook payload). Column confirmed at `V1__baseline.sql:221`.
- Forward vs return attempts not distinguishable in the stored schema without parsing `shipments.raw->>'orderType'`; kept as one unified detector per spec.
- Enrich: `descriptionEn/Ar` names attempt count + tracking number; `suggestedAction=contact_customer`; `actionUrl=/orders/<id>`.
- Terminal states excluded: `delivered`, `returned`, `lost`, `terminated`, `cancelled`.
- Standard NOT EXISTS suppression on `exception_resolutions`.
- `MigrationSmokeTest` count fixed 25→26 (V26 existed but smoke test was stale).
- Tests (`AttemptsDetectorTest` — 5): at1 surfaces MEDIUM at 2 attempts; at2 skips 1 attempt; at3 excludes terminal; at4 suppresses after resolve; at5 severity ordering correct.

**Item 2 — FR-3.4: `ShopifyReconcileJob` — 15-min gap-filler:**
- `@Recurring(id="shopify-reconcile", cron="*/15 * * * *")`. Guarded with `@ConditionalOnProperty(org.jobrunr.background-job-server.enabled=true)` (same as `ShopifyStateCleanupJob`).
- Uses `@FlywayDataSource`-injected `JdbcTemplate` (owner pool, BYPASSRLS) to list `stores WHERE status='connected' AND import_status='completed'` — cross-tenant without GUC.
- Per-store: sets `TenantContext`, fetches orders with `created_at:> now()-30min` via `fetchOrdersPage`, checks `SELECT EXISTS(... WHERE tenant_id=? AND store_id=? AND external_id=?)`.
- Missing → `ShopifySyncService.ingestMissingOrder()` (new thin public wrapper around private `upsertOrder`). Existing → skip, zero writes. `status`/`on_hold` never touched.
- Concurrent safety: `ON CONFLICT (store_id, external_id) DO UPDATE` on `UPSERT_ORDER` is the backstop for poll+poll and webhook+poll races.
- Tests (`ShopifyReconcileTest` — 5): r1 missing order ingested; r2 mid-pick order untouched; r3 disconnected store skipped; r4 webhook+poll → single row; r5 tenant isolation.

*Gotchas:*
- `@MockBean` stubs reset between test methods — `tokenProvider.getValidToken(any()).thenReturn(FAKE_TOKEN)` must be in `@BeforeEach`, not `@BeforeAll`.
- `ShopifyReconcileJob` needs `properties = "org.jobrunr.background-job-server.enabled=true"` in `@SpringBootTest` to be instantiated in the test context (default test props set it false).

*Open items:*
- **git push** — still blocked by credential mismatch (`marwanashraf56` vs repo owner `marwanashraf098`). All commits local. Fix: `gh auth login` or `git remote set-url origin https://marwanashraf098@github.com/marwanashraf098/traceability.git`.

---

**Day 27 — FR account/onboarding layer (V23–V25)**

*Migrations:*
- **V23** — `shipments.provider_id_fetch_failed boolean` + Mode-B backfill of `provider_delivery_id` from `raw->>'_id'` (completed in previous session, Day 26 Task D).
- **V24** — `audit_log` table. Append-only: `REVOKE UPDATE, DELETE FROM app_user` (V1's `ALTER DEFAULT PRIVILEGES` grants all four ops by default; V24 explicitly revokes the two write ops). RLS with `NULLIF(...)::uuid` pattern. Three indexes.
- **V25** — Adds `default_language text NOT NULL DEFAULT 'ar'`, `timezone text NOT NULL DEFAULT 'Africa/Cairo'`, `pickup_address text` to `tenants`. Language/timezone/pickup_address are tenant-level settings; Bosta-specific fields stay on `courier_accounts`.

*Items shipped:*

**Item 1 — Audit log (FR-2.6):**
- `AuditService.record()` — single write path; requires `TenantContext` set.
- `AuditService.list()` — paginated; filters: action, actorUserId, from, to.
- `AuditController` — `GET /api/v1/audit-log` (Owner/Manager only).
- `FulfillService.convertToSelfPickup()` — replaced TODO comment with real `auditSvc.record("convert_to_self_pickup", ...)`.
- 7 tests (a1–a7): write/read round-trip, tenant isolation, append-only privilege check, `convertToSelfPickup` wires audit, filters by action and actor.

**Item 2 — User CRUD (FR-2.2):**
- `UserService` — create (PIN/password hash, role validation), update (name/role), deactivate (active=false, no hard delete). Every mutation writes audit_log.
- Role rule: Manager cannot create/modify/deactivate an Owner.
- `auth_lookup_user` already has `AND active = true` (V1) — deactivated users auto-blocked from auth without code change.
- `UserController` — GET/POST `/api/v1/users`, PATCH/deactivate `/{id}`. Returns 201/204 per REST conventions.
- 11 tests (u1–u11): all role rules, hash verification, no-hard-delete, piece_event attribution survives deactivation, audit writes, tenant isolation.

**Item 3 — Tenant settings (FR-1.4):**
- `TenantController.GET /api/v1/tenant/settings` — returns name, pickupAddress, labelSize (derived from label_width_mm/label_height_mm), defaultLanguage, timezone.
- `TenantController.PUT /api/v1/tenant/settings` — Owner-only; COALESCE partial update; validates labelSize ∈ {"40x25","50x25"}, language ∈ {"ar","en"}; writes audit.
- Bosta fields (`awb_format`, `pickup_mode`, `pickup_business_location_id`) stay on `courier_accounts` — NOT duplicated to `tenants`.
- 7 tests (s1–s7): V25 columns exist, defaults round-trip, PUT writes all fields + audit, bad labelSize → 400, bad language → 400, Bosta fields absent from tenants.

**Item 4 — Connections status:**
- `ConnectionsController.GET /api/v1/connections` — returns shopify and bosta connection objects derived from live DB signals. No new connect logic.
- 3 tests (c1–c3).

**Item 5 — Onboarding checklist:**
- `OnboardingController.GET /api/v1/onboarding/status` — 5 steps (connect_shopify, connect_bosta, initial_import, test_label, first_receiving), all derived from real DB signals, NOT manually toggled.
- 6 tests (o1–o6): fresh→all pending, each signal individually, allDone=true when all signals present.

*Gotchas logged:*
- `V1 ALTER DEFAULT PRIVILEGES GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO app_user` applies to every new table. V24 must explicitly `REVOKE UPDATE, DELETE ON audit_log FROM app_user` after the broad grant.
- `TenantContext.runAs()` always clears context in `finally`. Tests that call a controller with `runAs()` internally will lose context after the call; check the DB directly instead of calling `auditSvc.list()` in those cases.
- `stores` table has no `created_at` — `ConnectionsController` used `ORDER BY created_at DESC`, fixed to `ORDER BY last_sync_at DESC NULLS LAST`.
- `@PreAuthorize` on controller beans in `WebEnvironment.NONE` tests requires setting `SecurityContextHolder` manually in `@BeforeEach` with a `UsernamePasswordAuthenticationToken` carrying the `CustomUserDetails` principal.

*Open items:*
- **FR-4.6 cancelDelivery()** — still blocked on Bosta endpoint verification.
- **`awaiting_pickup → self_pickup_pending`** — still 409'd until FR-4.6.
- **git push** — blocked by GitHub credential mismatch. All commits are local. Fix: `gh auth login` or update stored credential for `marwanashraf098`.

---

**Day 32 — FR-15.1: Piece-level inventory summary**

*Design decisions:*
- **Group B "entered within 30d AND still in status" query**: single correlated EXISTS — `p.status IN ('delivered','damaged','lost') AND EXISTS (SELECT 1 FROM piece_events pe WHERE pe.piece_id = p.id AND pe.to_status = p.status AND pe.occurred_at >= now() - INTERVAL '30 days')`. If a piece was delivered then returned, its current status is `return_pending_inspection` not `delivered`, so `p.status = 'delivered'` already excludes it — no special de-dup needed.
- **Per-variant reuse**: CatalogController and InventoryController both query `pieces.status` directly. They are consistent by construction — no shared helper or code duplication needed.
- **Frontend IA**: Overview gets the inventory summary (replaces 4 order-count stat cards). Drill-down goes to new `/inventory?status=X[&within30d=true]` page (no sidebar entry, accessed only via tile clicks).

*Backend — `InventoryController.java` (`inventory` package):*
- `GET /api/v1/inventory/summary` — groupA (6 point-in-time) + groupB (3 windowed, 30d)
- `GET /api/v1/pieces?status=X&within30d=false&page=0&size=20` — paginated flat piece list
- Both OWNER/MANAGER only.
- **CRITICAL gotcha**: both handlers must be wrapped in `TransactionTemplate.execute()`. `TenantAwareConnection` fires the GUC (`set_config('app.current_tenant', ...)`) only on `setAutoCommit(false)` — i.e., only when a transaction begins. Without `TransactionTemplate`, `JdbcTemplate` runs in auto-commit mode, the GUC is never set, `NULLIF(current_setting(...))::uuid` returns NULL, and every WHERE clause fails silently with 0 rows. This was found and fixed after 7/8 tests failed all returning 0.

*pom.xml:*
- `node.version` updated v20.11.0 → v22.15.0 (`styleText` in `node:util` was added in Node 20.12.0; rolldown via Vite 5.4 requires it).
- Added `skip.frontend` property; use `-Dskip.frontend=true` to bypass Vite build during backend-only test runs.

*Frontend:*
- **Overview.tsx** rewrote the stat section with 9 inventory tiles (6 group A in a 6-col grid, 3 group B in a 3-col section with "Last 30 days" heading + "30d" badge). Inline error on API failure. Exceptions section unchanged.
- **Inventory.tsx** new page at `/inventory` — paginated flat piece list (barcode, variant, SKU, location, last event). Reads `?status=X&within30d=true/false` from search params. Back link to Overview. `src/App.tsx`: added `/inventory` route.
- **api.ts**: `getInventorySummary()` + `listPieces()` + interfaces.
- **i18n**: `inventory.*` (live/window30d/col/drillTitle/back/prev/next/showing) in AR+EN. Removed unused `overview.totalOrders/delivered/inTransit/returns` keys.

*Tests:*
- `InventorySummaryTest.java` — 8 tests: i1 available point-in-time; i2 old delivery not in window; i3 recent delivery in window; i4 returned piece excluded from delivered; i5 tenant isolation; i6 catalog consistency; i7 pieces status filter; i8 pieces within30d filter.
- `inventory.test.tsx` — 5 tests: all tiles render; 30d label on group B; zero-count tile renders; API error degrades gracefully; tile hrefs correct (group A no window, group B with within30d=true).
- Backend: **278/278** passing. Frontend: **11/11** passing.

---

**Day 31 — Frontend Round 3: Onboarding wizard + tests**

*Auto-show decision:* Nav item only (`Getting started`, checklist icon). Not forced on login because: (1) the backend endpoint is OWNER/MANAGER only — redirecting workers would 403; (2) the wizard is explicitly a non-blocking checklist, forcing it at login contradicts that; (3) it fits naturally with Connections/Settings/Users in the privileged nav section.

*Onboarding.tsx (`/onboarding`):*
- `GET /api/v1/onboarding/status` → 5 steps, each `{ key, label, status: done|pending }`.
- All 5 rendered as a non-blocking checklist — no hard gate (user can jump to any step).
- Each pending step has a `<Link>` to its completion screen: ①②③ → `/connections`, ④⑤ → `/receiving`.
- Step ④ (`test_label`) shows a signal-lag hint when pending: "Open a finalized receiving session and press Reprint. The initial print at session close does not register here."
- All-done state: full card with star icon, "You're all set up!", link to Overview.
- Inline error on API failure (role="alert"), no crash.

*api.ts:* `OnboardingStep`, `OnboardingStatus` interfaces + `getOnboardingStatus()`.

*Layout:* `IconOnboarding` (checklist SVG) + `SideNavLink to="/onboarding"` — hidden for Workers, placed before `/users` and `/settings`.

*setup.ts fix (important gotcha):* Added `afterEach(cleanup)` from `@testing-library/react`. RTL auto-cleanup only registers itself when `globals: true` is set (it looks for global `afterEach`). Without globals, the DOM was not cleared between tests → all 4 later tests were reading stale DOM from previous renders. Fix: import and wire `cleanup` explicitly in setup.ts.

*Tests (onboarding.test.tsx — 5 tests):*
1. All-pending → all 5 `data-testid="step-{key}"` present with `data-status="pending"`, action links present.
2. Partial (①② done, ③④⑤ pending) → correct `data-status` per row, `onboarding-complete` absent.
3. All-done → `onboarding-complete` present, step rows absent.
4. `test_label` pending → `step-test_label-hint` present with non-empty text.
5. API error → `role="alert"` present, no crash, no step rows or completed state.

Mock pattern: `vi.mock('../api', async importOriginal => ({ ...actual, getOnboardingStatus: vi.fn(), getRoleFromToken: vi.fn(() => 'owner') }))` — spreads real exports, overrides only the two used by the component.

*npm test result:* **6/6 passing** (1 smoke + 5 onboarding), ~650ms.

---

**Day 30 — Vitest + React Testing Library harness**

*Packages installed (devDependencies):*
`vitest@4.1.9`, `@testing-library/react@16`, `@testing-library/jest-dom@6`, `jsdom@29`

*Config changes:*
- `vite.config.ts`: import changed to `vitest/config`; `test` block: `environment: 'jsdom'`, `setupFiles: ['./src/test/setup.ts']`, explicit `include` pattern for `src/test/**`.
- `tsconfig.json`: added `"exclude": ["src/test"]` — keeps `tsc && vite build` clean (no test imports in production compile).
- `package.json`: `"test": "vitest run"`, `"test:watch": "vitest"`.

*Files added:*
- `src/test/setup.ts` — `import '@testing-library/jest-dom/vitest'` (extends vitest's `expect` with jest-dom matchers without requiring `globals: true`).
- `src/test/renderWithProviders.tsx` — creates a fresh `i18next.createInstance()` (synchronous, `initImmediate: false`) + wraps in `MemoryRouter` + `I18nextProvider`. Re-exports everything from `@testing-library/react` so test files have a single import.
- `src/test/smoke.test.tsx` — 1 passing test: renders a `<p>`, asserts `toBeInTheDocument()` + `toHaveTextContent()`.

*App globals handled:*
- `localStorage` — jsdom provides it; no mock needed.
- `i18n` — `renderWithProviders` uses a fresh instance, avoiding `src/i18n.ts`'s `localStorage.getItem('lang')` side effect at import time.
- `react-router` — `MemoryRouter` in `renderWithProviders`.
- No `window.matchMedia` mock needed yet (none of the current components use it).

*Run:* `npm test` (from `frontend/`)

---

**Day 29 — Frontend Round 2: Settings + User Management**

*Role-gating mechanism (new for this round):*
- `parseJwtClaims()` + `getRoleFromToken()` in `api.ts` — base64-decodes the JWT payload part, returns `'owner' | 'manager' | 'worker' | null`. No library.
- Layout.tsx: hides `/users` and `/settings` nav items when `role === 'worker'`.
- Settings.tsx: disables form and hides Save button for non-owners (backend `@PreAuthorize('OWNER')` is the real gate).
- Users.tsx: `canEdit(u)` guard — Managers cannot see edit/deactivate for Owner-role users; Owner option in role select hidden from Managers.
- Server remains the authoritative enforcer on every mutation.

*Nav placement:*
- `/users` ("Team") after Connections; `/settings` ("Settings") after Team — both hidden for Workers.

**FR-1.4 — Settings (`/settings`):**
- `Settings.tsx` — GET/PUT `/api/v1/tenant/settings`. Fields: business name, pickup address, label size (segmented control 40×25|50×25), default language (segmented control AR|EN), timezone text input with IANA hint.
- PUT is Owner-only. Managers see read-only form with notice banner. Save button only shown for Owner.
- Inline success confirmation (green banner, auto-clears after 4s). Inline danger error on failure.
- `langNote` explains this is the tenant account default, not the live sidebar toggle.

**FR-2.2 — User Management (`/users`):**
- `Users.tsx` — table with name, email, role badge (colour-coded: brand/accent/muted), active/inactive status, Edit + Deactivate row actions.
- `CreateUserModal`: role-aware form. Worker → PIN field (4-digit, numeric). Manager/Owner → password field (≥8). Owner option in role select only appears when `currentRole === 'owner'`.
- `EditUserModal`: Manager sees no Owner option; cannot open modal for Owner-role users at all (`canEdit()` returns false). Server re-enforces with 403.
- `DeactivateModal`: explains custody history preserved, action reversible, never hard-delete. No Delete button anywhere in the UI.
- Uses `Modal` from `ui.tsx` — existing component.

*api.ts additions:* `getRoleFromToken()`, `getTenantSettings()`, `updateTenantSettings()`, `listUsers()`, `createUser()`, `updateUser()`, `deactivateUser()` + `TenantSettings`, `User` interfaces.

*i18n:* `settings.*`, `users.*` (create/edit/deactivate sub-keys), `nav.users`, `nav.settings` — AR+EN in sync.

---

**Day 28 — Frontend Round 1: Signup + Connections**

*Items shipped:*

**FR-1.1 — Signup screen (`/signup`):**
- `Signup.tsx` — public route (no RequireAuth). Fields: business name (`tenantName`), owner name (`name`), email, phone (Egyptian validation only — `+20/0201/01XXXXXXXXX`; not sent to backend, which doesn't store it), password (≥8 chars). Calls `POST /api/v1/auth/signup`; on success stores `accessToken` in localStorage and navigates to `/overview`.
- Error mapping: 409 Conflict → "email already registered" message; other errors → generic.
- Login page now has "Don't have an account? Sign up" link.

**FR-1.2 connect screens — `Connections.tsx` (`/connections`):**
- Protected route with Layout sidebar. `ShopifyCard` + `BostaCard` in responsive 2-column grid.
- Shopify: domain input → `POST /api/v1/shopify/oauth/initiate` → `window.location.href = consentUrl`. Connected state shows domain, import status, last sync.
- Bosta: API key input (`type=password`, never shown back) → `POST /api/v1/bosta/connect`. Connected state shows business name + pickup mode. Reconnect flow re-shows the form.
- Both cards: not-connected / loading / error states. `GET /api/v1/connections` polled on mount and after Bosta connect.

*api.ts additions:* `signup()`, `getConnections()` (+ `ConnectionsStatus` interface), `shopifyInitiate()`, `bostaConnect()`.

*i18n:* `signup.*`, `connections.*` (shopify + bosta sub-keys), `login.noAccount/signUp`, `nav.connections` — all in both AR and EN.

*Layout:* New `IconConnections` SVG + `SideNavLink to="/connections"` after Exceptions.

*No frontend tests added* — no test framework exists in the project.

*Convention match:* `useEffect` + `useState` + `request<T>()` (no TanStack Query). Logical CSS props throughout (`ps-`, `pe-`, `text-start`). RTL works without reload via existing `Layout.toggleLang()`. Error display matches Login pattern exactly.

---

**Day 26 — FR-9 manifest/COD/self-pickup build (V22)**

*Items completed:*

**Item 1 — Derive-on-read pickup COD (V22):**
- `V22__fr9_cod_derive_self_pickup.sql`: `ALTER TABLE pickups DROP COLUMN total_cod_amount`.
- `BostaPickupService.schedulePickup()`: removed `total_cod_amount` from INSERT (3-arg form).
- `BostaPickupService.getManifest()`: removed stored column read; derives `totalCod` live from `lines.stream().reduce(...)` (already fetched from the same JOIN). Also fixed pre-existing NPE: `Map.of()` → `HashMap` for nullable `provider_pickup_id`.
- Tests t1 (column gone, COD correct from live data) and t2 (COD updates after shipment removal) green.

**Item 2 — removeFromPickupManifest() + 9.11 cancel cleanup:**
- `FulfillService.removeFromPickupManifest(UUID orderId)` — public method. Deletes `pickup_shipments` rows for all shipments belonging to the order. Idempotent (0 rows → no-op, no error).
- Private overload `removeFromPickupManifest(UUID orderId, UUID tenantId)` for internal callers.
- Hooked into `cancelOrder()` 202 branch (guided-unpack) and pre-pack auto-release branch.
- Hooked into `unpackPiece()` auto-cancel path (when last piece unpacked).
- `ShopifyWebhookProcessorJob.handleOrderCancelled()` — unconditional call after try/catch. This is the ONLY path that cleans manifest rows for `awaiting_pickup` orders (since those 409 out of cancelOrder before reaching its cleanup).
- Tests t3–t6 green.

**Item 3 — FR-4.6 cancelDelivery() — STOPPED:**
The Bosta cancel endpoint verb/path and terminal-state error codes cannot be confirmed from code alone. Most likely `DELETE /api/v2/deliveries/{trackingNumber}` but error shape for already-delivered/already-cancelled is unverified. Items 3 and the `awaiting_pickup` branch of item 4 remain pending endpoint confirmation.

**Item 4 — convertToSelfPickup() (9.9b):**
- `FulfillService.convertToSelfPickup(UUID orderId, String reason, UUID actorUserId)` — `@Transactional`.
- Allowed from `packed` or `awaiting_pickup`; else 409. Already `self_pickup_pending` → no-op (idempotent).
- Missing reason → 400.
- Calls `removeFromPickupManifest()` then advances order to `self_pickup_pending`, `is_self_pickup=true`.
- Writes audit record to `orders.metadata` via `json_build_object()` (type, reason, actor, previous_status, converted_at). No piece_events.
- **Bosta cancelDelivery stub:** the `awaiting_pickup` path does NOT yet call Bosta (FR-4.6 pending). Comment in code marks the TODO. packed→self_pickup_pending path is fully operational.
- `POST /api/v1/fulfill/{orderId}/convert-to-self-pickup` — Owner/Manager only; body `{"reason":"..."}`.
- Tests t7–t11 green.

**Item 5 — Guard setSelfPickup() dead-end:**
- `setSelfPickup()` now explicitly reads status and rejects `packed`/`awaiting_pickup`/`self_pickup_pending` with 409 "use convert-to-self-pickup action". Terminal statuses also guarded. Only `new`/`ready_to_pick` accepted.
- Tests t12–t15 green.

*V22 also adds `orders.metadata jsonb` for the convert-to-self-pickup audit record.*

*Post-commit audit fix (same day):* `convertToSelfPickup()` `awaiting_pickup` branch was NOT fail-closed — it proceeded past the TODO stub and set status to `self_pickup_pending` without cancelling the Bosta AWB. Fixed: explicit 409 thrown before any DB writes when `status == awaiting_pickup`. t8 updated to assert 409 + verify no DB side-effects (status unchanged, metadata null, manifest row kept). 231/231 green.

*Open items:*
- **FR-4.6 cancelDelivery()** — Bosta endpoint + terminal-state error codes must be confirmed before the `awaiting_pickup → self_pickup_pending` path can be wired. `shipments.provider_delivery_id` exists but is never written (always NULL). For Mode-B–matched shipments, the Bosta internal `_id` is at `shipments.raw->>'_id'`. AWB-scan–linked shipments have `raw = NULL` — no stored `_id`.
- 9.9b `packed → self_pickup_pending` path is fully operational. `awaiting_pickup` path is blocked (409) until FR-4.6.

---

**Day 25 — Exceptions center extensions (FR-15.3 detectors)**

*Audit result:* ExceptionService already has **10 detectors** registered (not 8 as previously documented): lost, never_received, unmatched_delivery, blocked_customer, stuck_shipment, unexpected_return, delivery_limbo, ndr_failed, guided_unpack (Day 14), missing_awb (Day 24). Both guided_unpack and missing_awb were fully wired. detectMissingAwb() was NOT orphaned.

*What changed:*

**V21 migration** (`V21__exceptions_ext.sql`):
- `orders.shopify_cancel_requested_at timestamptz` — signal column written by `handleOrderCancelled()` when a Shopify cancel arrives for an `awaiting_pickup` order and `cancelOrder()` returns 409.
- `tenants.stuck_shipment_days` default changed from 3 → 5 (FR-11.5 spec + pilot tracker). Existing rows at 3 updated to 5.

**`ShopifyWebhookProcessorJob.handleOrderCancelled()`** — 409 path now splits:
- Non-409 (terminal/already-cancelled): swallowed as before.
- 409: stamps `shopify_cancel_requested_at = COALESCE(..., now())` on the order. The exceptions center then surfaces it as HIGH for the operator to resolve manually (let it RTO, convert to self-pickup, or guide cancellation).

**`ExceptionService`** — 11th detector `detectShopifyCancelVsInflight()`:
- Queries `orders WHERE status='awaiting_pickup' AND shopify_cancel_requested_at IS NOT NULL`.
- Joins `shipments` for tracking number context.
- Suppressed by `exception_resolutions` (standard NOT EXISTS pattern — no stale-sync special case needed).
- Enriched with AR/EN descriptions and `suggestedAction` text per spec.

*Gap reported — not fabricated:* `short` detector (FR-7.7): no shortage signal (`is_short`, shortage quantity column) exists anywhere in the codebase. FR-7.7 is unimplemented. Detector not wired.

*Tests:*
- `ExceptionExtTest` — 6 new tests: surfaces with signal, no-surface without signal, no-surface once delivered, stays suppressed after resolve, fires at 5 days not 4, severity ordering correct.
- `Day13Test` — `stuck_shipment_days` explicit insert corrected to 5.
- `MigrationSmokeTest` — count 20→21.
- 216/216 tests pass. Zero regressions.

---

**Day 24 — AWB print + pickup handling (FR-9.5, FR-10.1, FR-10.2, FR-4.8):**

*What changed:*

**V20 migration** (`V20__awb_pickup_settings.sql`):
- `courier_accounts`: `pickup_mode` (BOSTA_MANAGED default), `pickup_business_location_id`, `contact_person` (jsonb), `awb_format` (A4 default), `awb_lang` (ar default).
- `shipments`: `awb_print_failed_reason text`, `awb_print_failed_at timestamptz` — for exceptions center detection.
- `pickups`: `total_cod_amount numeric(12,2)` — cached COD total for manifest retrieval.

**`BostaGateway` + `BostaHttpGateway`** — two new methods:
- `printMassAwb()`: POST `/api/v2/deliveries/mass-awb` with `{trackingNumbers, requestedAwbType, lang}`. Inline path → `AwbPrintResult(pdfBytes)`. Email path → `AwbPrintResult(null, message)`.
- `createPickup()`: POST `/api/v2/pickups`. Throws `BostaPickupAlreadyExistsException` (1078/2024–2027) or `BostaPickupDateException` (1080/1081/1083/2022).

**`BostaAwbService`**:
- Pre-filter: `NON_PRINTABLE_STATES` (delivered, returned, returning, lost, terminated, cancelled), `NON_PRINTABLE_TYPES` (CRP, CASH_COLLECTION). Excluded → `awb_print_failed_reason` written → exceptions center picks them up.
- Batch into ≤50 chunks, call Bosta per chunk. Returns `AwbBatchResult{pdfBase64List, emailMessage, exceptions}`.
- Format and lang default from `courier_accounts.awb_format/awb_lang` (overridable per request).

**`BostaPickupService`**:
- Pre-validates: past date → 400; Friday → 400 (Bosta error 1080 pre-empted). Loads orders in `awaiting_pickup` status.
- `BOSTA_MANAGED`: skips Bosta API, generates manifest. `TRACED_MANAGED`: calls `createPickup()`, `BostaPickupAlreadyExistsException` → non-error message, `BostaPickupDateException` → 400. Both modes insert `pickups` + `pickup_shipments`.
- Returns `PickupManifest{pickupId, scheduledDate, mode, providerPickupId, alreadyExistsMessage, shipments, totalCod, parcelCount}`.

**`ExceptionService`** — `detectMissingAwb()` added: `shipments WHERE awb_print_failed_reason IS NOT NULL`, suppressed by `exception_resolutions`. Enriched with `retry_awb_print` action.

**New endpoints (BostaController)**:
- `PUT /api/v1/bosta/settings` — update pickup_mode, locationId, contactPerson, awbFormat, awbLang.
- `POST /api/v1/bosta/awb/print` — `{shipmentIds, format?, lang?}` → `AwbBatchResult`.
- `POST /api/v1/bosta/pickup/schedule` — `{scheduledDate}` → `PickupManifest`.
- `GET /api/v1/bosta/pickup/manifest/{pickupId}` → `PickupManifest`.

*Key decision — awaiting_pickup is order_status not shipment_internal_state:*
- Shipments remain in `created` internal state until Bosta state 21 fires.
- Pickup query: `o.status = 'awaiting_pickup'::order_status`.
- Non-printable state check: `delivered, returned, returning, lost, terminated, cancelled` (all shipment_internal_state values).

*Already-exists (1078/2024–2027) is non-error:* merchant has both Bosta auto-pickup and TRACED_MANAGED enabled. Surface message, keep manifest, don't crash.

*Live smoke test (manual):*
1. Start app with pilot Bosta API key
2. `PUT /api/v1/bosta/settings {"awbFormat":"A4","awbLang":"ar"}`
3. Find a Mode-B shipment in `created` state (order in `awaiting_pickup`) → note its `id`
4. `POST /api/v1/bosta/awb/print {"shipmentIds":["<id>"]}`
5. Expect: `pdfBase64List[0]` → decode → valid PDF

*Test changes:*
- `AwbPickupTest` — 12 new integration tests.
- `MigrationSmokeTest` — count 19→20.
- 210/210 tests pass. Zero regressions.

---

**198/198 green — Mode-B phone+COD fallback hardened** — 2026-06-21.

V19 migration applied. `matchByPhoneAndCod()` rewritten: COD flat scalar, ambiguity decision table, partial unique index race guard, phone canonicalization, reason codes. 13 new tests in `ModeBMatcherTest`. All 185 pre-existing tests still pass.

**Next: live reinstall on real Shopify store (browser required)** — same checklist as before (see "Next" below).

---

**Day 23 — Mode-B matcher fix (FR-4.4 / matchByPhoneAndCod):**

*What changed:*

**V19 migration** (`V19__mode_b_match_fix.sql`):
- `ALTER TABLE unlinked_bosta_deliveries ADD COLUMN match_reason text` — records WHY a delivery was unlinked (NO_MATCH / AMBIGUOUS_MULTI / COD_ONLY_AMBIGUOUS). NULL on pre-V19 rows.
- `CREATE UNIQUE INDEX ux_active_shipment_per_order ON shipments (order_id) WHERE internal_state NOT IN ('terminated','cancelled')` — closes the concurrent-double-link race: the losing INSERT fails with 23505 and is routed to unlinked. Gating question answer: NO, an order cannot legitimately have two simultaneously-active shipments. The only valid multi-shipment case is after Bosta terminates/cancels the first delivery (codes 48/49/104); the partial predicate permits that re-ship.
- Functional index `orders_customer_phone_canonical` on `'0' || RIGHT(REGEXP_REPLACE(customer_phone, '[^0-9]', '', 'g'), 10)` — empty today (all phones NULL pre-PCD) but makes the phone+COD query index-seekable the moment PCD approval lands. No query change needed when PCD lands.

**`ShipmentLinkService`** — `matchByPhoneAndCod()` fully rewritten:
- COD bug fixed: reads `raw.path("cod")` (flat scalar) not `raw.path("cod").path("amount")`.
- COD = 0 (prepaid) treated as valid match value; only absent/JSON-null triggers the "missing" path.
- `normalizePhone()` extended: handles `+20…`, `0020…`, `0…`, `1XXXXXXXXX` (missing leading zero), and forms with spaces — all → `01XXXXXXXXX`.
- Phone absent on Bosta side → `COD_ONLY_AMBIGUOUS` immediately (no query).
- Candidate query: at-query-time canonicalization of `customer_phone` (`'0'||RIGHT(REGEXP_REPLACE(...))`), exact COD match, non-terminal status filter (added `delivered`), `NOT EXISTS (active shipment)`.
- Decision: 0 → `NO_MATCH`; 1 → auto-link; >1 → `AMBIGUOUS_MULTI`.
- `DuplicateKeyException` from `ux_active_shipment_per_order` → `NO_MATCH` (race loser).
- `LinkResult` record replaces `UUID` return type (carries `orderId` + `unlinkedReason`).
- `linkByAwbScan()` + `manualLink()` also catch `DuplicateKeyException` → 409.

**`BostaWebhookJob`** — `recordUnlinked()` gains `matchReason` parameter; call site updated.

*Decisions:*
- Partial unique index over `SELECT FOR UPDATE` — no held connections, no transaction restructuring.
- `NOT EXISTS` in candidate query: secondary guard + semantic correctness (don't double-link in-flight orders).
- Phone canonicalized at match time (SQL expression) → storage format of `customer_phone` can't break it.
- Pre-PCD behavior: all `customer_phone` NULL → 0 candidates → all fallback deliveries → `NO_MATCH` → unlinked for manual resolution. Safe and expected.

*Test changes:*
- `ModeBMatcherTest` — 13 new integration tests covering the full matrix.
- `MigrationSmokeTest` — count 18 → 19.
- 198/198 tests pass. Zero regressions.

---

**F1 live-cleared + F2 live-cleared + 185/185 green — OAuth Phase 1 complete** — 2026-06-20.

Phase 4 live re-verify done against docker-compose postgres (localhost:5432):
- **F1 cleared:** V18 applied, `BackgroundJobServer started successfully using PostgresStorageProvider`, `shopify-state-cleanup` recurring job registered in `jobrunr_recurring_jobs`.
- **F2 cleared:** `stores.access_token_expires_at` column live, `access_token_expires_at IS NOT NULL AND > now()` = true for stores created via the FR-3 path (876000-hour far-future expiry for DEV-ONLY connect; real OAuth callback stores actual Shopify expiry).
- **185/185 tests pass.** Zero regressions.

**Next: live reinstall on real Shopify store (browser required)**
1. Start tunnel: `ngrok http 8080` + set `SHOPIFY_REDIRECT_URI`, `SHOPIFY_WEBHOOK_BASE_URL`, `SHOPIFY_APP_URL` env vars to ngrok URL
2. Start app: `mvn spring-boot:run`
3. **[You]** Uninstall app from `traceability-dev.myshopify.com`, then reinstall (forces fresh token exchange)
4. Verify F2: `SELECT access_token_expires_at, refresh_token_encrypted, refresh_token_expires_at FROM stores WHERE shop_domain = 'traceability-dev.myshopify.com'` — all three must be non-null
5. Verify F1 (import): check `import_status` in stores — should go from `pending` → `completed` within ~30s after reinstall. Check logs for `ShopifyImportJob` output.
6. Verify F1 (webhooks): check logs for `RegisterShopifyWebhooksJob` — should log `Webhook registration complete for store ...`. Verify in Shopify Partner Dashboard → app → webhooks that 6 topics are registered.
7. Send a live `orders/create` webhook → verify 200 + `shopify_webhook_events` row inserted + processor job enqueued.

**Human task:** Email deliverability (SPF/DKIM, sending-domain setup) is an **ops task** — decide on SMTP provider and configure `spring.mail.host` + `app.email.from`. Until configured, `LoggingEmailGateway` logs links at WARN level (functional for dev/staging).

**Human task:** Enter the three GDPR webhook URLs in the Shopify Partner Dashboard app config (these are static URLs, configured once per app, not per-install). The three URLs:
- `POST https://<your-server>/webhooks/shopify/customers/data_request`
- `POST https://<your-server>/webhooks/shopify/customers/redact`
- `POST https://<your-server>/webhooks/shopify/shop/redact`

---

**Day 21 — FR-3 Expiring Token Migration (2026-06-20):**

*Design approved (Phase 1), built (Phase 2–3). F2 cleared.*

*What changed:*

**V17 migration** (`V17__expiring_tokens.sql`): `ALTER TYPE store_status ADD VALUE 'needs_reauth'`; `ALTER TABLE stores ADD COLUMN refresh_token_encrypted text, access_token_expires_at timestamptz, refresh_token_expires_at timestamptz`.

**ShopifyGateway interface**: new `TokenResponse` record (`accessToken`, `refreshToken`, `expiresIn`, `refreshTokenExpiresIn`); `exchangeCode()` return type `String` → `TokenResponse`; new `refreshAccessToken(shopDomain, refreshToken)` method.

**ShopifyHttpGateway**: `exchangeCode()` now sends `expiring=1` in the request body and parses all four response fields. New `refreshAccessToken()`: uses a dedicated `tokenRestClient` with 10 s read timeout (Correction B — pool size 5, pilot ≤ 3 simultaneous refreshes, acceptable headroom). Failure classification: `HttpClientErrorException` (4xx) → `ShopifyStoreNeedsReauthException` (permanent); `HttpServerErrorException` / `ResourceAccessException` → `ShopifyTransientException` (transient, no status change).

**ShopifyTokenProvider** (new `@Service`): single choke point `getValidToken(storeId)`. Two-phase: quick read (no lock) → if fresh (> 5 min from expiry) → return; else `SELECT FOR UPDATE` → re-check → call Shopify refresh API inside lock → write-back all four fields atomically. Permanent failure → mark `needs_reauth` + propagate `ShopifyStoreNeedsReauthException`. Transient failure → propagate `ShopifyTransientException` without touching status.

**ShopifyOAuthService**: `linkOrProvision()` uses `TokenResponse` end-to-end. `insertStore()` and `updateStoreToken()` write all four token/expiry fields. `provisionNewTenant()`: DEFINER function (`provision_tenant_from_shopify`) unchanged (no new approval needed); refresh fields written via post-provision UPDATE under tenant RLS. All `Instant` params converted to `java.sql.Timestamp` (pgjdbc requires explicit type; `Instant` not auto-inferred).

**ShopifyImportJob / RegisterShopifyWebhooksJob**: `EncryptionService` dependency removed; both now call `tokenProvider.getValidToken(storeId)`. `ShopifyStoreNeedsReauthException` → set `import_status='failed'` + warn log (permanent, merchant must reinstall). `ShopifyTransientException` → set `import_status='failed'` + error log (token still valid; re-trigger manually until F1 is fixed).

**ShopifySyncService** (DEV-ONLY connect path): `UPSERT_STORE` now sets `access_token_expires_at = now() + interval '876000 hours'` so `ShopifyTokenProvider` sees the dev-connect token as fresh.

*Decisions / gotchas:*
- `java.time.Instant` is NOT auto-inferred by pgjdbc — must use `java.sql.Timestamp.from(instant)` for all `TIMESTAMPTZ` JDBC parameters. Passing `Instant` directly silently NPEs the callback with `PSQLException: Can't infer the SQL type`.
- Store status `needs_reauth` is a plain text value (status column is a PostgreSQL enum `store_status`), so `ALTER TYPE store_status ADD VALUE` is required in V17.
- F2 is now fixed by this migration. The existing dev store (`traceability-dev.myshopify.com`) has a legacy `shpat_...` token. First import attempt after reinstall will call `tokenProvider.getValidToken()`, see a fresh token (set during OAuth callback), succeed.

*Test changes:*
- `ShopifyOAuthDay1Test`, `ShopifyOAuthDay2Test`, `ShopifyMagicLinkTest`: `exchangeCode()` stubs updated from `thenReturn(String)` to `thenReturn(TokenResponse)`.
- `ShopifyOAuthDay3Test`, `ShopifyImportTest`: test store INSERTs updated to include `access_token_expires_at = now() + interval '876000 hours'` so `tokenProvider` sees a fresh token.
- `MigrationSmokeTest`: migration count updated from 16 → 17.
- 185/185 tests pass.

*Next up (in priority order):*
1. Combined live re-verify (see "Current state" above) — browser reinstall on dev store
2. PROVISIONED path (new tenant) live-verify with a second Shopify store
3. Decide on public OAuth app vs. custom app for production (per CLAUDE.md note)

---

**Day 21 (cont.) — F1 fix: JobRunr Flyway migration V18 (2026-06-20):**

*Problem:* `JobRunrConfig.java` creates `PostgresStorageProvider(ownerDs)` which checks `jobrunr_migrations` to see which internal DDL scripts have been applied. Without any tables, this fails with NPE on first enqueue. `skip-create=true` in application.yml was preventing the Spring Boot auto-config path from creating tables, but our custom `@Bean` bypasses that property — it uses `DatabaseOptions.CREATE` (the default) regardless. The root cause was simply that no Flyway migration had ever created the JobRunr tables.

*Fix:* V18__jobrunr.sql — collapsed final DDL of all 15 JobRunr internal migrations:
- 4 tables: `jobrunr_jobs`, `jobrunr_recurring_jobs`, `jobrunr_backgroundjobservers`, `jobrunr_metadata` + `jobrunr_migrations` tracker
- 8 indexes (final set after v014 drops and replaces `updatedAt` index with compound `state_updated`)
- `jobrunr_jobs_stats` view (Postgres-specific v014 version using `ROLLUP`)
- `jobrunr_metadata` initial row `('succeeded-jobs-counter-cluster', ..., '0', ...)`
- Explicit GRANTs to `app_user` on all 5 tables + SELECT on view (belt-and-suspenders; V1 `ALTER DEFAULT PRIVILEGES` also covers these)
- `jobrunr_migrations` pre-populated with all 15 scripts → `DatabaseCreator` finds them applied → no DDL on startup

*V18 source verification:* DDL extracted directly from `jobrunr-7.3.0.jar` at `org/jobrunr/storage/sql/common/migrations/` and Postgres override at `postgres/migrations/v014`. NOT from a running DB — source of truth is the pinned jar version.

*`application.yml` comment updated* to accurately describe that `skip-create=true` only guards the Spring Boot auto-config path (overridden by our custom `@Bean`); the tables are created by V18, not by the bean.

*`MigrationSmokeTest` updated:* count 17 → 18. 185/185 pass.

*Decisions:*
- `JobRunrConfig.java` uses `new PostgresStorageProvider(ownerDs)` with default `DatabaseOptions.CREATE` — this is correct; it checks `jobrunr_migrations` and finds all scripts applied, so it's effectively a no-op after V18 runs.
- Owner pool size 2 is acceptable: JobRunr metadata operations (heartbeat, job state transitions) hold connections for ~1 ms each. The actual job body (`ShopifyImportJob.run()`) uses the `@Primary` app_user pool (size 5), not the owner pool.

---

**Day 22 — F1+F2 live re-verify + JobRunrConfig NPE fix (2026-06-20):**

*Problem:* After V18 was applied successfully (`Successfully applied 18 migrations`), app crashed with:
```
Error creating bean with name 'shopifyStateCleanupJob'
Caused by: NPE at RecurringJobTable.<init>(RecurringJobTable.java:24)
```
Root cause: `RecurringJobPostProcessor` is a `BeanPostProcessor` that fires `saveRecurringJob()` per-bean during context init, potentially BEFORE `BackgroundJobServer` creation (which normally calls `storageProvider.setJobMapper(jobMapper)`). This left `jobMapper = null` on the `StorageProvider` at the time `RecurringJobTable` was first constructed — `Objects.requireNonNull(jobMapper)` threw.

*Fix:* In `JobRunrConfig.storageProvider()`, call `provider.setJobMapper(new JobMapper(new JacksonJsonMapper()))` directly in the `@Bean` factory method. This guarantees the mapper is set before any `@Recurring` bean triggers the post-processor.

*Gotcha:* `JacksonJsonMapper(objectMapper)` (with Spring's shared `ObjectMapper`) BREAKS Spring MVC — `JacksonJsonMapper` calls `objectMapper.activateDefaultTyping()` which adds `@class` type ids to all serialized output, causing `JSON parse error: missing type id property '@class'` for normal REST responses. Must use `new JacksonJsonMapper()` (no-arg) which creates its own isolated `ObjectMapper`.

*Live re-verify results (2026-06-20, docker-compose postgres):*
- V18: `Successfully validated 18 migrations. Schema "public" is up to date.`
- F1: `BackgroundJobServer started successfully using PostgresStorageProvider and 128 BackgroundJobPerformers`
- F1: `shopify-state-cleanup` registered in `jobrunr_recurring_jobs`
- F2: `stores.access_token_expires_at` column present; stores created via FR-3 path show `token_is_fresh = t`, expiry ~100 years out
- App: `Started TraceabilityApplication in 1.19 seconds`

*Commit:* `c257f25` — fix: JobRunrConfig — set jobMapper before RecurringJobPostProcessor fires

---

**Day 20 — OAuth Phase 1 live verification (ngrok tunnel, 2026-06-19):**

*Scope: prove the already-built OAuth connector against traceability-dev.myshopify.com. No features added; findings only.*

**What passed:**

| Check | Result | Evidence |
|---|---|---|
| Install → consent URL (HMAC + state) | ✓ | 302 to `traceability-dev.myshopify.com/admin/oauth/authorize` with 4 scopes |
| Callback HMAC verified | ✓ | Flow completed without SHOPIFY_HMAC_INVALID |
| State single-use (consumed_at set) | ✓ | DB: `consumed_at` non-null after callback |
| Token stored as ciphertext | ✓ | DB: `access_token_encrypted = Kisac9Rd...` (len 88, not `shpat_...`) |
| LINKED_EXISTING outcome + 302 redirect | ✓ | Browser landed on SPA login page (not 500) |
| Webhook raw-body HMAC → 200 | ✓ | Test POST with correct HMAC-SHA256 returned 200 |
| Webhook bad HMAC → 401 | ✓ | Tampered HMAC returned 401, nothing persisted |
| Webhook idempotency | ✓ | Replay same `webhook_id` → 200, still 1 row in `shopify_webhook_events` |
| Tenant resolved from `X-Shopify-Shop-Domain` | ✓ | Event row has correct `tenant_id = dc276c72-...` |

*Note: LINKED_EXISTING path tested (dev store already connected from Day 9). PROVISIONED path (new tenant) covered by 7 integration tests with real DB but not live-verified here — needs a second Shopify store.*

**Blocking infrastructure issues found:**

**[F1] JobRunr tables missing from Supabase — all enqueue calls NPE.**
`database.skip-create=true` prevents JobRunr from creating its own tables. No Flyway migration creates them either. `PostgresStorageProvider.save(job)` → `new JobTable(…)` → NPE. Affects: `enqueueImport()` in OAuth callback (shop not actually scheduled for import), `RegisterShopifyWebhooksJob` (Shopify webhooks never registered), `ShopifyWebhookProcessorJob` (events stored but processor never enqueued). The `RecurringJobTable` NPE on startup (ShopifyStateCleanupJob's @Recurring) is the same root cause.

Fix options (pick one):
- (a) Set `database.skip-create=false` — `PostgresStorageProvider` auto-creates its 9 JobRunr tables on startup. No migration needed. This is the simplest fix.
- (b) Add a Flyway migration that creates the JobRunr schema (gives version control but more maintenance).

After fix: remove `ORG_JOBRUNR_BACKGROUND_JOB_SERVER_ENABLED=false` from `.env` (was added as workaround to avoid startup NPE during this session; reverted at end of session).

**[F2] Shopify non-expiring tokens deprecated — Admin API calls blocked.**
The OAuth exchange (`POST /admin/oauth/access_token`) still succeeds, but the issued token is the legacy non-expiring format (`shpat_...`). Shopify now rejects ALL Admin API calls with this token format: *"Non-expiring access tokens are no longer accepted for the Admin API."* This blocks `RegisterShopifyWebhooksJob` (GraphQL `webhookSubscriptionCreate`) and `ShopifyImportJob` (products/orders GraphQL queries).

Fix: In Partner Dashboard → app settings → enable **"Use expiring access tokens"** (or equivalent). Uninstall and reinstall the app from the dev store to get a new expiring token. The token exchange code in `ShopifyHttpGateway.exchangeCode()` does not need changes — Shopify switches the format at the platform level. Note: expiring tokens have a 24-hour lifetime; the app will need a token-refresh strategy before pilots (either the merchant re-installs, or the app requests an offline token with `access_mode=offline` if the feature is available).

**Workarounds applied during session (all reverted — working tree clean):**
- `ORG_JOBRUNR_BACKGROUND_JOB_SERVER_ENABLED=false` in `.env` → prevented startup NPE
- Temporary try-catch in `ShopifyOAuthService.enqueueImport()` → let callback 302 fire despite F1
- Temporary try-catch in `ShopifyWebhookController` → let webhook 200 fire despite F1
- Ngrok env vars (`SHOPIFY_REDIRECT_URI`, `SHOPIFY_WEBHOOK_BASE_URL`, `SHOPIFY_APP_URL`) → removed

**Next (in priority order):**
1. Fix F1: set `database.skip-create=false`, restart, verify enqueue works
2. Fix F2: enable expiring tokens in Partner Dashboard, reinstall dev store
3. After F1+F2 fixed: re-run live verification — points 4 (import enqueued) and webhook processor should pass
4. Test PROVISIONED path with a second Shopify store

---

---

**Day 19 — Shopify OAuth Day 4 (FR-3.1 magic-link bridge + FR-3.3 redact touch-up):**

*Part A — Redact touch-up (commit `ecd4120`, FR-3.3 fix):*
- `customers/redact` handler rewritten: now scoped to `orders_to_redact[].id` array from payload (converted to GIDs), not a broad `customer.id OR customer_phone` match across all tenant orders. Empty array = no-op. Prevents erasing mid-fulfillment orders that share a phone number.
- `REDACT_CUSTOMER_ORDERS_BY_IDS`: `external_id = ANY(?)` via `PreparedStatement + con.createArrayOf("text", ids)`. `client_details` and `note_attributes` added to stripped raw keys in BOTH SQL constants (`REDACT_CUSTOMER_ORDERS_BY_IDS` and `REDACT_ALL_CUSTOMERS_FOR_TENANT`).
- 5 Part A tests (`ShopifyRedactTouchupTest`): regression (two-order customer, only one in `orders_to_redact` → other intact byte-for-byte), empty array no-op, `client_details`/`note_attributes` stripped, `piece_events` unchanged, `shop/redact` still tenant-wide.

*Part B — Magic-link bridge (commit `688ebb0`, FR-3.1):*
- **V16 migration** (`V16__magic_link_tokens.sql`): `magic_link_tokens(id, tenant_id, user_id, token_hash, created_at, expires_at, consumed_at)`. NOT under RLS — token consumption is pre-session. `consume_magic_link(p_token_hash)` SECURITY DEFINER plpgsql function: `SELECT … FOR UPDATE` → validate (not found / consumed / expired → no rows) → `UPDATE consumed_at = now()` → `RETURN QUERY`. Atomic single-use. `search_path` pinned, REVOKE from PUBLIC, GRANT to app_user. Enumerated as hatch #6 in `blueprint.md §16.1`.
- **EmailGateway** interface + `SmtpEmailGateway` (`@ConditionalOnProperty("spring.mail.host")` scaffold) + `LoggingEmailGateway` fallback via `@Configuration/@Bean` + `@ConditionalOnMissingBean` (correct Spring Boot pattern — works in all test contexts without `@MockBean`).
- **MagicLinkService**: `issueMagicLink(userId, tenantId)` — CSPRNG 16 bytes, base64url raw token, SHA-256 hash stored; INSERT (no GUC needed); `TenantContext.runAs` to look up user email; send via `emailGateway`. `consumeMagicLink(rawToken)` — hash, call DEFINER function, `TenantContext.runAs` to look up role and store refresh token, return `TokenResponse`.
- **MagicLinkController**: `GET /auth/magic?token=` (`permitAll`) → 302 with `#access_token=…&refresh_token=…` in fragment (never reaches server on subsequent requests).
- **ShopifyOAuthService**: wired `magicLinkService.issueMagicLink(row.ownerId(), row.tenantId())` at PROVISIONED seam (replaces `// TODO Day 4`).
- **MAGIC_LINK_INVALID** error code added to `ShopifyOAuthException.Code` (AR+EN locales). All invalid sub-conditions (not found, expired, consumed) return identical error — no oracle.
- **`/auth/magic`** added to `SecurityConfig` `permitAll`.
- `AuthRepository.sha256` made `public static` (used in tests and `MagicLinkService`).
- **ShopifyOAuthDay2Test.cleanState** fixed: DELETE `magic_link_tokens` before users to satisfy new FK.
- 7 Part B tests (`ShopifyMagicLinkTest`): (1) happy path JWT, (2) single-use, (3) expiry, (4) forged, (5) hash at-rest, (6) provision wiring sendMagicLink call, (7) cross-tenant isolation.
- 185/185 tests pass.

*Decisions made:*
- `LoggingEmailGateway` not a `@Component` — `@ConditionalOnMissingBean` on `@Component` has evaluation-ordering issues in Spring's component scan; moved to `@Configuration/@Bean` factory (standard Boot auto-config pattern).
- Tokens delivered in URL fragment (not query param) — fragments are never sent to the server on `<a>` clicks or HTML form submissions; prevents accidental server-side logging of raw tokens.
- `consume_magic_link` uses `plpgsql` (not `sql`) because it requires `SELECT FOR UPDATE` + `UPDATE` — write path. `lookup_refresh_token` (hatch #4) is read-only `sql` function.

---

**Day 18 — Shopify OAuth Day 3 (FR-3.3 webhooks, GDPR, app/uninstalled, Parts A–E):**

*Migration V15 (`V15__shopify_webhook_events.sql`):*
- `shopify_webhook_events(id uuid PK, tenant_id uuid NOT NULL, topic text, shop_domain text, webhook_id text, payload_raw jsonb, received_at timestamptz, processed_at timestamptz NULL, process_error text NULL)`.
- `UNIQUE (webhook_id)` for idempotency on `X-Shopify-Webhook-Id`.
- RLS in same migration (FORCE ROW LEVEL SECURITY; `NULLIF` pattern). Index on `processed_at IS NULL` for processor sweep.
- `'disconnected'` store_status value confirmed present from V1.

*Part A — RegisterShopifyWebhooksJob:*
- `RegisterShopifyWebhooksJob.run(storeId, tenantId)` — registers 6 topics (orders/create, orders/updated, orders/cancelled, products/create, products/update, app/uninstalled) via GraphQL `webhookSubscriptionCreate`. Idempotent ("already taken" = success, logged not thrown). Per-topic failure continues to next topic. Called via `enqueueImport` in `ShopifyOAuthService` — both import and registration enqueued on every successful link/provision.
- `ShopifyGateway.registerWebhook(shopDomain, token, topic, callbackUrl)` interface method + `ShopifyHttpGateway` implementation via `WEBHOOK_REGISTER_MUTATION` GraphQL. Checks `userErrors` for "already taken" string.
- `shopify.webhook-base-url` config property (`${SHOPIFY_WEBHOOK_BASE_URL:http://localhost:8080}`).

*Part B — ShopifyWebhookController (complete rewrite):*
- URL: `POST /webhooks/shopify/{type}/{action}` (covers orders/create, products/update, app/uninstalled, customers/redact, shop/redact, etc.).
- **`@RequestBody byte[] rawBody`** — raw bytes only, never typed DTO.
- **HMAC over raw bytes first**: `ShopifyHmacUtil.verifyWebhookBody(rawBody, clientSecret, X-Shopify-Hmac-Sha256)` — base64(HMAC-SHA256). Wrong HMAC → 401, nothing persisted.
- Resolve tenant via `resolve_tenant_by_shop_domain(X-Shopify-Shop-Domain)` DEFINER hatch (no GUC). Unknown shop → 200 ack, drop.
- Insert into `shopify_webhook_events` under tenant GUC (TenantContext.set/clear around tx). `ON CONFLICT (webhook_id) DO NOTHING RETURNING id` — null result = duplicate, ack 200 skip.
- Ack 200 immediately; enqueue `ShopifyWebhookProcessorJob.process(eventId, tenantId)` — tenantId passed so processor can set GUC before loading the RLS-protected event row.

*Part C — ShopifyWebhookProcessorJob (async):*
- `process(UUID eventId, UUID tenantId)` — `@Job` method; sets TenantContext via `TenantContext.runAs(tenantId, ...)` for ALL reads including the initial event load.
- Dispatch table (switch on topic): orders/create → ingestOrderWebhook; orders/updated → ingestOrderWebhook; orders/cancelled → FulfillService.cancelOrder; products/create → ingestProductWebhook; products/update → ingestProductWebhook; app/uninstalled → disconnect store; customers/data_request → log GDPR request; customers/redact → erase customer PII; shop/redact → erase all tenant PII. Unknown topic → log error (never silent drop, invariant #8).
- `MARK_PROCESSED` / `MARK_ERROR` SQL on `shopify_webhook_events` — errors persist in `process_error` column.

*Part C — GDPR handlers:*
- **customers/redact**: `UPDATE orders SET customer_name=NULL, customer_phone=NULL, address=NULL, raw=raw-'customer'-'shipping_address'-'billing_address'-'email'-'phone' WHERE tenant_id=? AND (raw->'customer'->>'id'=? OR customer_phone=?)`. `piece_events` is INSERT-only (DB grants), holds NO customer PII — never touched.
- **shop/redact**: Same update across ALL orders for the tenant. Idempotent (re-running nulls already-null fields, harmless).
- **customers/data_request**: GDPR task logged; event already persisted in shopify_webhook_events as the audit trail. Full automated data export is [S] scope.

*Part D — app/uninstalled:*
- `UPDATE stores SET status='disconnected' WHERE shop_domain=? AND tenant_id=?`.
- `ShopifyImportJob.run()` added a disconnected-store early-return check: if `stores.status='disconnected'` → log + return without importing.

*ShopifySyncService additions:*
- `resolveStore(tenantId, shopDomain) → UUID` — looks up store by shop_domain+tenant_id+status=connected.
- `ingestOrderWebhook(storeId, tenantId, payload)` — parses Shopify REST order payload (admin_graphql_api_id as external_id, variant_id → GID, financial_status → payment method). Reuses existing `UPSERT_ORDER`, `UPSERT_ORDER_ITEM`, `FLAG_ORDER_UNMAPPED` SQL.
- `ingestProductWebhook(storeId, tenantId, payload)` — parses REST product payload. Reuses `UPSERT_PRODUCT`, `UPSERT_VARIANT` SQL.

*SecurityConfig:*
- `/webhooks/shopify/**` added to `permitAll` (authenticated by HMAC, not JWT).

*ShopifyHmacUtil additions:*
- `verifyWebhookBody(byte[] rawBody, String clientSecret, String providedBase64)` — static method. Base64(HMAC-SHA256(secret, rawBody)), constant-time compare.

*Tests (`ShopifyOAuthDay3Test` — 12 tests):*
- **(1)** Raw-body HMAC: non-canonical JSON spacing verifies (raw bytes); tampered body → 401.
- **(2)** Wrong HMAC → 401, nothing persisted.
- **(3)** Idempotency ×5 → one row; one processing effect.
- **(4)** Unknown shop → 200 ack, nothing persisted.
- **(5)** orders/create → order upserted (via REST payload ingestion).
- **(6)** orders/cancelled → FulfillService.cancelOrder dispatched, order becomes cancelled.
- **(7)** app/uninstalled → store.status='disconnected'; import job skips disconnected store.
- **(8)** customers/redact → customer_name/phone/address nulled, raw scrubbed; piece_events count+content unchanged.
- **(9)** shop/redact → all tenant orders have customer PII nulled.
- **(10)** customers/data_request → persisted, 200 ack, no exception.
- **(11)** Registration idempotency: run twice → no crash; "already taken" ShopifyException caught per-topic, job continues.
- **(12)** RLS proof: webhook insert visible to app_user with correct GUC; invisible with wrong tenant GUC.

*Breaking changes in Day 1/2 tests:*
- `enqueueImport()` now calls `jobScheduler.enqueue()` twice (import + webhook registration). Tests updated: `times(1)` → `times(2)` in Day 1 and Day 2 happyPath tests.

**Decisions made:**
- `tenantId` passed alongside `eventId` to processor job — avoids chicken-and-egg: loading event row without GUC would fail under RLS. Controller already resolved tenantId; passing it to the job is cheaper than a DEFINER hatch.
- GDPR handlers use `orders.address` (not `shipping_address`) — column is named `address` in V1 schema.
- `RegisterShopifyWebhooksJob` is NOT `@ConditionalOnProperty` — it has no `@Recurring` annotation, so `RecurringJobPostProcessor` never touches it. No NPE risk in tests.

---

**Day 17 — Shopify OAuth Day 2 (FR-3.1 resolve-or-create, Parts A–D):**

*Migration V14 (`V14__provision_tenant_from_shopify.sql`):*
- `provision_tenant_from_shopify(p_shop_domain, p_owner_email, p_shop_name, p_timezone, p_access_token_encrypted)` — fifth SECURITY DEFINER escape hatch, approved 2026-06-19.
- Atomically creates: one `tenants` row + one `users` row (Owner role, no password_hash — magic-link Day 4) + one `stores` row (status connected, import_status pending).
- A 23505 on the stores INSERT propagates to the caller's transaction → zero orphan tenants/users (verified by test 7).
- `REVOKE ALL … FROM PUBLIC; GRANT EXECUTE … TO app_user`.
- Enumerated in `docs/blueprint.md §16.1` (full justification table).
- `CLAUDE.md` updated: "Four approved" → "Five approved".

*Part A — Carry-over fixes:*
- **A1 Timestamp freshness**: `checkTimestampFreshness(params)` in controller — rejects requests with `timestamp` older than 300 s on BOTH `GET /auth/shopify/install` and `GET /auth/shopify/callback`. New error code `SHOPIFY_REQUEST_EXPIRED`.
- **A2 State cleanup job**: `ShopifyStateCleanupJob` — `@Recurring(cron="0 * * * *")` hourly sweep, `DELETE FROM shopify_oauth_state WHERE created_at < now() - interval '1 hour'`. Non-RLS table; runs with no TenantContext set (safe). `@ConditionalOnProperty(name="org.jobrunr.background-job-server.enabled", havingValue="true")` — prevents `RecurringJobPostProcessor` NPE in test contexts.
- **A3 Upsert rewrite**: `UPSERT_STORE` removed. Replaced with `insertStore(tenantId, shop, encryptedToken)` and `updateStoreToken(tenantId, shop, encryptedToken)` private helpers — each sets/clears TenantContext around their own write transaction.

*Part B — `linkOrProvision()` decision tree:*
- **`ShopifyOAuthService.linkOrProvision(state, shop, authCode) → LinkResult`** replaces Day-1 `handleCallback()`.
- `resolveShopOwner(shop)` calls `SELECT resolve_tenant_by_shop_domain(?)` with NO TenantContext — DEFINER function sees all tenants regardless of GUC.
- Resolve is called BEFORE any TenantContext.set() — cross-tenant row is never hidden by RLS.
- Path-1 (tenant in state): `owner==null → insertStore → LINKED_NEW`; `owner==intended → updateStoreToken → LINKED_EXISTING`; `owner!=intended → REJECTED_CROSS_TENANT` (no write to existing row).
- Path-2 (null tenant in state): `owner!=null → updateStoreToken → LINKED_EXISTING`; `owner==null → provisionNewTenant → PROVISIONED`.
- Race backstop: `DuplicateKeyException (23505)` → re-resolve → idempotent link to winner (or REJECTED_CROSS_TENANT if winner is a different tenant than intended in Path-1).
- Controller no longer sets TenantContext — fully managed inside service try/finally.
- `LinkOutcome` enum: `LINKED_NEW, LINKED_EXISTING, PROVISIONED, REJECTED_CROSS_TENANT`.

*Part C — Provisioning:*
- `provisionNewTenant()` calls `fetchShop` for email+name+timezone, checks email not blank (`SHOPIFY_SHOP_EMAIL_MISSING`), then calls V14 function via `tx.execute(s → jdbc.query("SELECT * FROM provision_tenant_from_shopify(...)"))`.
- No TenantContext set for provision call — DEFINER handles all inserts.
- TODO Day 4 seam: magic-link email to owner.

*Part D — Gateway:*
- `ShopifyGateway.ShopInfo(email, name, timezone)` record.
- `ShopifyGateway.fetchShop(shopDomain, token) → ShopInfo` interface method.
- `ShopifyHttpGateway.fetchShop()` — GET `/admin/api/{v}/shop.json`, uses existing retry/nullableText helpers. `email` may be null.

*Controller changes:*
- `TenantContext` import removed — TenantContext is now managed inside the service only.
- `checkTimestampFreshness(params)` called after HMAC on both install and callback.
- Callback switches on `LinkResult.outcome()`:
  - `LINKED_NEW / LINKED_EXISTING` → 302 `appUrl`
  - `PROVISIONED` → 302 `appUrl/connect/setup-pending`
  - `REJECTED_CROSS_TENANT` → 302 `appUrl/connect/error?code=SHOPIFY_STORE_ALREADY_CONNECTED`
- Day-1 `SHOPIFY_PATH2_NOT_YET` stub removed.

*Error codes added:*
- `SHOPIFY_REQUEST_EXPIRED` (400 BAD_REQUEST) — stale timestamp.
- `SHOPIFY_STORE_ALREADY_CONNECTED` (in enum for i18n; redirect not JSON throw) — cross-tenant rejection.
- `SHOPIFY_SHOP_EMAIL_MISSING` (502 BAD_GATEWAY) — empty shop email from Shopify.
- i18n keys added to `en.json` and `ar.json` for all three.

*Tests (`ShopifyOAuthDay2Test` — 10 tests):*
- **(1)** Path-1 new shop → store created under state.tenantId; import enqueued.
- **(2)** Path-1 same-tenant re-install → token updated; exactly one store; no new owner.
- **(3)** Path-1 cross-tenant → 302 `/connect/error?code=SHOPIFY_STORE_ALREADY_CONNECTED`; existing row token/tenant byte-for-byte unchanged.
- **(4)** Path-2 new shop → exactly one tenant + one owner (Owner role, no password_hash) + one store; import enqueued.
- **(5)** Path-2 existing shop → idempotent link; no new tenant/owner.
- **(6)** Concurrent double-install race (real threads, CountDownLatch) → exactly one tenant, one owner, one store; loser re-resolves and links.
- **(7)** Provisioning atomicity — pre-seeded stores conflict → `DuplicateKeyException`; zero orphan tenants, zero orphan users.
- **(8)** A1 timestamp freshness — stale timestamp on install AND callback → 400 `SHOPIFY_REQUEST_EXPIRED`.
- **(9)** A2 state sweep — rows >1h deleted; fresh rows retained. (`new ShopifyStateCleanupJob(jdbc).purgeExpiredStates()` called directly — bean is conditional on background-job-server.enabled.)
- **(10)** Cross-tenant detection works via DEFINER function (`resolve_tenant_by_shop_domain` returns correct tenant with no GUC), not via RLS-scoped SELECT.

**Decisions made:**
- `ShopifyStateCleanupJob` is `@ConditionalOnProperty(... enabled=true)` — `RecurringJobPostProcessor` crashes with NPE at bean init when background-job-server is disabled (storage layer null), so the bean must not be created in test contexts. Tests call the purge logic via `new ShopifyStateCleanupJob(jdbc)` directly.
- `SHOPIFY_STORE_ALREADY_CONNECTED` uses redirect (302) not JSON throw — cross-tenant is a user-recoverable condition (contact support), not an unrecoverable API error.
- Token exchange happens before resolve — resolving first would add latency for the common Path-1-new case. The race backstop handles the rare concurrent collision.
- Only Path-2-new uses the DEFINER provisioning function. Path-1 and Path-2-existing run under normal RLS with the GUC set — per the spec's "privileged-surface minimization" requirement.

**Next:** OAuth Day 4 — magic-link email send to provisioned owner; session-token filter for embedded Shopify dashboard.

---

**Day 16 — Shopify OAuth Day 1 (FR-3.1 public OAuth track, Path-1):**

*Migration V13 (`V13__shopify_oauth_state.sql`):*
- `shopify_oauth_state(nonce text PK, tenant_id uuid NULL, shop_domain text NOT NULL, created_at timestamptz, consumed_at timestamptz NULL)`.
- Intentionally NOT under tenant RLS — Path-2 states (new merchant installs) have no tenant_id yet.
- Documented inline in migration as a pre-tenant surface, reviewed with same scrutiny as SECURITY DEFINER escape hatches.
- Cleanup index on `created_at` for TTL sweep (states >1h are dead).

*New files:*
- **`ShopifyHmacUtil`** — static util for OAuth param HMAC verification (sorted canonical string, HMAC-SHA256 hex, constant-time compare via `MessageDigest.isEqual`). Reused by install and callback; ready for Day 3 webhook params.
- **`ShopifyOAuthException`** — typed exception with `{code, message_en, message_ar, httpStatus}`. Four codes: `SHOPIFY_HMAC_INVALID`, `SHOPIFY_STATE_INVALID`, `SHOPIFY_TOKEN_EXCHANGE_FAILED`, `SHOPIFY_PATH2_NOT_YET`.
- **`ShopifyOAuthService`** — state lifecycle: `initiateOAuth()` (CSPRNG 128-bit nonce, base64url), `consumeState()` (SELECT FOR UPDATE in transaction; validates exists/not-expired/not-consumed/shop-matches atomically; marks consumed; does not leak which sub-condition failed), `handleCallback()` (exchange code → encrypt → upsert stores → enqueue import), `buildConsentUrl()`.
- **`ShopifyOAuthController`** — 3 endpoints:
  - `POST /api/v1/shopify/oauth/initiate` (JWT-authenticated, OWNER only). Reads tenant_id from JWT (never query param). Validates `*.myshopify.com` domain. Returns `{consentUrl}`.
  - `GET /auth/shopify/install` (permitAll). Path-2 stub: verifies HMAC → state with `tenant_id=NULL` → 302 to consent.
  - `GET /auth/shopify/callback` (permitAll). HMAC first → consume state → Path-2 stub (`SHOPIFY_PATH2_NOT_YET`) → TenantContext.set → exchange code → upsert store → enqueue import → 302 to app.

*Modified files:*
- **`ShopifyGateway`** — added `exchangeCode(shopDomain, code)` method.
- **`ShopifyHttpGateway`** — implemented `exchangeCode()` (POST to `/admin/oauth/access_token`, returns `access_token` field); injected `clientId` and `clientSecret` via `@Value`.
- **`SecurityConfig`** — `/auth/shopify/install` and `/auth/shopify/callback` added to `permitAll` (authenticated by HMAC+state, not JWT).
- **`ApiExceptionHandler`** — added `@ExceptionHandler(ShopifyOAuthException.class)` returning `ResponseEntity<OAuthErrorBody>` with `{code, message_en, message_ar}` body.
- **`application.yml`** — added `shopify.client-id`, `shopify.client-secret`, `shopify.scopes`, `shopify.redirect-uri`, `shopify.app-url` with env-var overrides.
- **`frontend/src/locales/en.json` + `ar.json`** — added `shopify.oauth.*` i18n keys (title, subtitle, labels, buttons, all 5 error codes AR+EN).

*Tests (`ShopifyOAuthDay1Test` — 8 tests):*
- **(a)** Install HMAC reject → 401 `SHOPIFY_HMAC_INVALID` with `{code, message_en, message_ar}` body.
- **(b)** Canonical string correctness: correct HMAC on install → 302 with `Location` containing shop+client_id; state row created with `tenant_id=NULL`.
- **(c)** Callback HMAC reject → 401 `SHOPIFY_HMAC_INVALID`.
- **(d)** State replay: first callback → 302; second with same nonce → 400 `SHOPIFY_STATE_INVALID`.
- **(e)** State shop-mismatch: state bound to shop-a, callback claims shop-b → 400 `SHOPIFY_STATE_INVALID`.
- **(f)** Expired state (>10 min) → 400 `SHOPIFY_STATE_INVALID`.
- **(g)** Happy path: valid state → 302; store row created for correct tenant; `consumed_at` set; import job enqueued.
- **(h)** Token-at-rest: `access_token_encrypted` ≠ raw token; ciphertext longer than plaintext.

**Decisions made:**
- `SELECT FOR UPDATE` inside `consumeState` transaction — prevents concurrent replay attack (second request waits, sees `consumed_at IS NOT NULL`, rejects).
- All state-invalid sub-conditions (expired/consumed/shop-mismatch/not-found) throw identical `SHOPIFY_STATE_INVALID` — no leakage of which condition triggered.
- `SHOPIFY_PATH2_NOT_YET` stub in callback for null-tenant states — will be wired in Day 2 with resolve-or-create.
- `noRedirectRest` (JdkClientHttpRequestFactory + Redirect.NEVER) used in tests — `TestRestTemplate` follows 302 to `http://localhost:5173` (standalone SPA, not running in tests), causing ConnectionRefused.

**Next:** OAuth Day 2 — resolve-or-create decision tree + provisioning (Path-2 + cross-tenant safety).

---

"Traced" design system + full frontend restyle shipped 2026-06-18. Frontend build clean (✓ 86 modules, 322KB JS / 35KB CSS). 143 integration tests unchanged and passing.

**Day 15 — "Traced" design system + frontend dark-theme restyle:**

*Design tokens (`frontend/tailwind.config.js` + `frontend/DESIGN.md`):*
- New `fontSize` scale: `display` (2.25rem/light), `h1`–`h3`, `body` (0.875rem), `small`, `caption`.
- Semantic color tokens: `base` (#0B1220), `panel` (#1E293B), `elevated` (#253449), `line` (#2D3F55), `primary` (#F8FAFC), `muted` (#647488); brand palette `brand`/`brand-hover`, `accent`, `cyan`; state tokens `success`/`warning`/`danger` with `.muted` variants.
- `boxShadow`: `card`, `elevated`, `brand`, `glow`.
- `animation`: `flash` (scan feedback), `fadeIn`, `dotPing` (timeline pulse).
- `fontFamily`: `sans` → Inter, `arabic` → Cairo (Google Fonts @import in index.css; RTL font auto-switches via `[dir="rtl"]` CSS selector).

*Shared CSS layers (`frontend/src/index.css`):*
- `@layer base`: dark background (#0B1220), near-white text (#F8FAFC), scrollbar styling.
- `@layer components`: `.card`, `.btn`, `.btn-brand`, `.btn-outline`, `.btn-danger`, `.btn-ghost`, `.input`, `.input-scan`, `.badge`, `.tbl-header`, `.tbl-cell`, `.tbl-row`, `.nav-item`, `.nav-item-active`.

*Shared UI components (`frontend/src/components/ui.tsx`):*
- `Badge` — status badge with `/10` opacity backgrounds on dark theme.
- `OrderBadge` — maps order status strings.
- `Card`, `StatCard` — card primitives.
- `Button` — variant/size props.
- `Input` — standard + scan variant.
- `Spinner` — SVG animated.
- `EmptyState` — icon + message.
- `SeverityBadge` — CRITICAL/HIGH/MEDIUM/LOW.
- `Modal` — dark overlay + card.

*App shell (`frontend/src/components/Layout.tsx`):*
- Full dark sidebar (w-56, bg-panel, border-e border-line).
- Wordmark: `<span class="text-brand">tr</span>aced` with icon slot div.
- Inline SVG icons for Overview, Orders, Inventory, Shipments/Receiving, Fulfill, Returns, Exceptions.
- `SideNavLink` with active highlight: `bg-brand/10 border-s-2 border-brand`.
- Bottom: lang toggle + logout button.
- Top search bar for barcode/tracking lookup.

*Restyled pages (dark-first, all on design tokens):*
- **`Login.tsx`**: brand glow background, "traced" wordmark, dark card form.
- **`Overview.tsx`**: stat cards with SVG sparklines, recent exceptions, `/overview` route.
- **`Lookup.tsx`**: showpiece screen — brand-violet pulsing dot (`animate-dotPing`) on latest event, compact `TransitionPill`, `MetaField` grid, dark card backgrounds.
- **`Orders.tsx`**: dark table with `/10` opacity status badges, Spinner, EmptyState.
- **`Returns.tsx`**: tab nav with brand-violet active underline; intake (flash + beep preserved), pending, never-received table all dark-themed.
- **`Exceptions.tsx`**: severity dot + `SeverityBadge` + type badge per row; `Modal` for resolve dialog; filter selects.
- **`Catalog.tsx`**: dark product cards with variant breakdown; `Badge` for piece counts.
- **`OrderDetail.tsx`**: dark info sections; `Badge` for piece status; `InfoRow` helper.
- **`Receiving.tsx`**: dark session list + create form + session detail; dropdown autocomplete dark-styled.
- **`Fulfill.tsx`**: self-pickup + guided-unpack flows from Day 14 preserved; dark skin applied.

*Routing (`frontend/src/App.tsx`):*
- Added `/overview` route (wraps `Overview` in `Layout`).
- Default redirect `*` → `/overview` (was `/orders`).

*Bug fixed:* `DashStatCard` was receiving an unknown `accent` prop — removed.

---

Self-pickup + order cancellation (FR-9.9–9.13) shipped 2026-06-18. 143 integration tests pass (BUILD SUCCESS).

**Day 14 — Self-pickup + order cancellation (FR-9.9–9.13):**

*Migration V12 (`V12__day14_self_pickup_cancel.sql`):*
- `ALTER TYPE order_status ADD VALUE IF NOT EXISTS 'self_pickup_pending'`
- `ALTER TABLE orders ADD COLUMN is_self_pickup boolean NOT NULL DEFAULT false`
- `ALTER TABLE orders ADD COLUMN cancel_requested_at timestamptz`
- Partial index `orders_cancel_requested ON orders (tenant_id) WHERE cancel_requested_at IS NOT NULL`

*Backend:*
- **`InventoryLedger`** — added `"packed:delivered"` to ALLOWED set (self-pickup handover path only).
- **`FulfillService`** — 4 new methods:
  - `setSelfPickup(orderId, selfPickup)` — toggles `is_self_pickup` flag (blocked on terminal/cancelled orders).
  - `handover(orderId, actorUserId)` — verifies `self_pickup_pending`, transitions all packed pieces to `delivered` via `handover` event with `metadata={"self_pickup":true}`, updates order to `delivered`. Returns piece count.
  - `cancelOrder(orderId, actorUserId)` — stateful cancellation decision:
    - Terminal (cancelled/delivered/returned/lost) → 409
    - With-courier/awaiting_pickup/returning → 409 (pieces physically with courier)
    - Packed/self_pickup_pending with packed pieces → set `cancel_requested_at = COALESCE(cancel_requested_at, now())`, return `CancelResult("cancel_requested", ..., packedCount)` (202-equivalent)
    - Pre-pack (reserved only) → release pieces RESERVED→AVAILABLE with `unreserved` events, release active allocations, order → cancelled, return `CancelResult("cancelled", ..., 0)`
  - `unpackPiece(orderId, pieceId, actorUserId)` — verifies `cancel_requested_at` and correct order status; transitions PACKED→AVAILABLE with `unpacked` event; releases allocation; when remaining packed count reaches 0, cancels the order and clears `cancel_requested_at`. Returns `UnpackResult(cancelled, remainingPacked)`.
  - `complete()` updated: reads `is_self_pickup`, sets order status to `self_pickup_pending` (not `packed`) for self-pickup orders. Queue includes `self_pickup_pending` orders.
  - Added `CancelResult(String status, String message, int remainingPacked)` and `UnpackResult(boolean cancelled, int remainingPacked)` records.
- **`FulfillController`** — 4 new endpoints:
  - `PATCH /{orderId}/self-pickup` — toggle flag
  - `POST /{orderId}/handover` — confirm customer collection
  - `POST /{orderId}/cancel` — cancel (200 cancelled pre-pack, 202/200 cancel_requested post-pack)
  - `POST /{orderId}/unpack/{pieceId}` — guided unpack one piece
- **`ExceptionService`** — 9th detector `detectGuidedUnpack()`: finds orders with `cancel_requested_at IS NOT NULL AND status IN ('packed','self_pickup_pending')`. Surfaces as `guided_unpack` / `HIGH`. Disappears naturally when order cancels (no `exception_resolutions` entry needed).
- **`LookupService`** — phraseKey mappings for `handover`, `unpacked`, `unreserved`.
- **`ShopifyWebhookController`** (`/api/v1/webhooks/shopify`) — new file. Receives Shopify webhooks. `orders/cancelled` topic: resolves tenant via `resolve_tenant_by_shop_domain` SECURITY DEFINER; optional HMAC-SHA256 validation against per-store `webhook_secret`; dedup via `X-Shopify-Webhook-Id`; persists to `webhook_events`; calls `FulfillService.cancelOrder()` with same pre/post-pack logic. Already-terminal orders logged and skipped (no 5xx).

*Tests (`Day14Test` — 7 tests):*
- **(a)** Self-pickup `complete()` → `self_pickup_pending` (not `packed`); no AWB required; queue includes it.
- **(b)** `handover()` → pieces `delivered`, order `delivered`, `handover` events attributed to worker with correct `to_status`.
- **(c)** Pre-pack cancel → reserved pieces → `available`, `unreserved` events, active allocations released, order `cancelled`.
- **(d)** Post-pack cancel → `cancel_requested_at` set, piece still `packed`, order still `packed`; `guided_unpack` HIGH exception surfaces.
- **(e)** Guided unpack: first piece → order stays packed, remaining=1; last piece → order `cancelled`, `cancel_requested_at` cleared, guided_unpack exception gone.
- **(f)** With-courier cancel → 409 (`CONFLICT`), pieces untouched.
- **(g)** Shopify `orders/cancelled` webhook path tested directly via `cancelOrder()` — pre-pack auto-releases.

*Frontend:*
- **`Fulfill.tsx`** fully rewritten with new flows:
  - Queue view: separate "Awaiting Collection" section for `self_pickup_pending` orders (amber cards); clicking opens `HandoverScreen`.
  - `HandoverScreen` — full-screen confirm-handover UI for self-pickup orders; shows piece count; calls `POST /{id}/handover`.
  - Pick screen: Self-Pickup badge on header; Cancel button (top-right) → inline confirm dialog (shows pre vs post-pack message); post-pack cancel triggers `GuidedUnpackPanel`.
  - `GuidedUnpackPanel` — lists packed pieces with "Unpack" button per piece; calls `POST /{id}/unpack/{pieceId}`; auto-navigates back to queue when all unpacked.
  - After `complete()` on self-pickup order: green banner "Packed — awaiting collection" instead of AWB dialog.
- **`Exceptions.tsx`** — added `guided_unpack: { en: 'Unpack Required', ar: 'فك التعبئة' }` to `TYPE_LABELS`.
- **`en.json`** — new `fulfill.*` locale keys: selfPickup, selfPickupBadge, handoverTitle, handoverSubtitle, handoverConfirm, handoverSuccess, cancelOrder, cancelConfirmPre, cancelConfirmPost, cancelRequested, unpackPiece, unpackDone, selfPickupPending; `lookup.phrase` keys: handover, unpacked, unreserved.
- **`ar.json`** — corresponding Arabic translations under `fulfill_extra.*` (merged at runtime) and `lookup.phrase` extensions.

**Decisions made:**
- **`cancel_requested_at` as the guided-unpack signal** — a dedicated timestamptz column is more debuggable than a new enum value, and the guided_unpack exception detector disappears naturally when the order reaches `cancelled` without needing `exception_resolutions`.
- **No auto-move of packed pieces on cancellation** — packed pieces are physically in a box; the system must not auto-release them without worker confirmation to prevent stock reconciliation divergence.
- **Shopify webhook HMAC validation optional when `webhook_secret` is null** — allows dev environments to test the webhook path without configuring secrets; production stores will always have a secret set at connect time.

**Gotchas:**
- `packed:delivered` added to ALLOWED in `InventoryLedger` — this is the only new legal transition. It is only reachable via `FulfillService.handover()` (which first verifies `self_pickup_pending` order status). There is no other code path that transitions packed→delivered.
- `cancelOrder()` uses order status (not piece status) to determine pre-vs-post-pack: `complete()` atomically packs all pieces and sets order status, so order status is authoritative.

---

**Day 13 — Exceptions center (FR-15.3):**

*Migration V11 (`V11__day13_exceptions.sql`):*
- `tenants.stuck_shipment_days` (int, default 3) — per-tenant configurable stuck-shipment window.
- `exception_resolutions` table — operational audit log for exception acknowledgements:
  `(tenant_id, exception_type, subject_key, resolved_by, resolved_at, note)`.
  RLS + `tenant_isolation` policy. Two indexes: `(tenant_id, exception_type, subject_key)` for
  NOT EXISTS suppression; `(tenant_id, resolved_at DESC)` for audit trail queries.

*Backend:*
- **`BostaWebhookJob`** — step 9 UPDATE now persists `provider_state = delivery.stateCode()` so
  NDR (state 47) and delivery-limbo (state 103) detectors can query `shipments.provider_state`.
- **`ExceptionService`** — 8 detectors run as separate SQL queries, merged in Java, sorted by
  severity (CRITICAL→HIGH→MEDIUM→LOW) then `occurred_at ASC` (oldest first within tier):
  1. `lost` (CRITICAL) — pieces with `status='lost'`.
  2. `never_received` (HIGH) — reuses FR-12.4 detector; suppressed once ack'd.
  3. `unmatched_delivery` (MEDIUM) — `unlinked_bosta_deliveries.resolved=false`.
  4. `blocked_customer` (LOW) — `orders.on_hold=true`.
  5. `stuck_shipment` (HIGH) — non-terminal shipment with no courier update for `stuck_shipment_days`.
     Unique recurrence: ack is invalidated if `last_synced_at > resolved_at` (Bosta sync after ack
     reactivates the exception without requiring re-ack).
  6. `unexpected_return` (HIGH) — `return_received` event with `from_status IN ('with_courier','awaiting_pickup')`,
     piece still at `return_pending_inspection`.
  7. `delivery_limbo` (HIGH) — `provider_state = 103` (return failed 3×, Bosta awaiting action).
  8. `ndr_failed` (MEDIUM or CRITICAL based on `ndr_codes.severity`) — `provider_state = 47`,
     NDR code extracted from `shipments.raw->>'exceptionCode'`, joined to `ndr_codes` for description.
  Each detector adds `descriptionEn`, `descriptionAr`, `suggestedAction`, `actionUrl` via `enrich()`.
  Resolution suppression via NOT EXISTS on `exception_resolutions` per detector.
- **`ExceptionController`** (`/api/v1/exceptions`):
  - `GET /` — paginated+filterable list (params: `type`, `severity`, `page`, `size`). OWNER/MANAGER.
  - `POST /resolve` — acknowledge/resolve: writes audit record. OWNER/MANAGER.
  - `GET /resolutions` — audit trail with resolver name. OWNER/MANAGER.

*Tests (`Day13Test` — 12 tests):*
- **(a)** Lost piece → CRITICAL severity; resolve removes it; audit record written with correct resolver+note.
- **(b)** Never-received → HIGH; surfaces past window; ack removes it.
- **(c)** Unmatched delivery → MEDIUM; natural `resolved=true` removes it.
- **(d)** Blocked customer → LOW; ack removes it.
- **(e)** Stuck shipment → HIGH; ack removes it; backdating `resolved_at` before `last_synced_at` → reappears.
- **(f)** Unexpected return → HIGH; ack removes it.
- **(g)** Delivery limbo (provider_state=103) → HIGH; ack removes it.
- **(h)** NDR critical code 26 → CRITICAL; normal code 1 → MEDIUM; NDR description populated.
- **(i)** Severity ordering: CRITICAL < HIGH < MEDIUM < LOW (index positions verified).
- **(j)** Age ordering within same severity: oldest `occurred_at` first.
- **(k)** Tenant isolation: other tenant's lost piece invisible under correct `TenantContext`.
- **(l)** Resolve audit record: `exception_type`, `subject_key`, `resolved_by`, `resolved_at`, `note` all correct.

*Frontend (`Exceptions.tsx`):*
- Full-page exceptions command center at `/exceptions`.
- Severity filter (CRITICAL/HIGH/MEDIUM/LOW) + type filter (8 types) dropdowns.
- Each exception row: colored severity dot + CRITICAL/HIGH/MEDIUM/LOW badge, type badge, age (Xs/Xm/Xh/Xd),
  AR/EN description, sub-details (order #, tracking, barcode, NDR description), "Go →" action button
  routing to existing screens, "Resolve" button opening inline dialog with optional note field.
- Inline resolve dialog: confirms the exception description, optional note, calls POST /exceptions/resolve,
  refreshes list.
- Pagination. Empty state with checkmark. Refresh button.
- Route `/exceptions` added to `App.tsx`; "Exceptions" / "الاستثناءات" nav link in `Layout.tsx`.
- AR/EN locale keys added.

**Decisions made:**
- **Per-type Java-merged queries** over a single UNION SQL: 8 detectors have incompatible JOIN patterns;
  stuck-shipment's `resolved_at > last_synced_at` recurrence guard is query-type-specific;
  Java merge is readable, independently testable, trivially extensible.
- **`exception_resolutions` table NOT `piece_events`**: exceptions are operational history, not custody
  history. Separate table keeps the custody ledger append-only and the exception audit queryable
  independently.
- **Stuck-shipment recurrence**: ack suppression uses `er.resolved_at > COALESCE(s.last_synced_at, s.created_at)`
  so a Bosta sync after the ack invalidates it — operator must re-ack the new stalled state.

**Gotchas found:**
- PostgreSQL `?` JSONB existence operator (`raw ? 'key'`) is intercepted by JDBC as a parameter
  placeholder. Must use `raw->>'key' IS NOT NULL` instead in JDBC-prepared statements.

---

**Day 12 — Returns intake + resolution (FR-12.1–12.5):**

*Migration V10 (`V10__day12_returns.sql`):*
- `tenants.never_received_window_days` (int, default 3) — per-tenant configurable never-received window.
- `shipments.returned_at` (timestamptz) — set by `BostaWebhookJob` on state-46 webhook; starts the FR-12.4 detection clock.
- Indexes: `shipments_returned_at_idx` (partial, returned + non-null) and `piece_events_return_received_idx` (partial, event_type='return_received').

*Backend:*
- **`InventoryLedger`** — two additions: `"with_courier:return_pending_inspection"` added to ALLOWED set (Bosta-lag intake); `recordReturnReceived()` third write path for idempotent intake of pieces already at `return_pending_inspection` (state-46 webhook fires before scan).
- **`ReturnService`** — full returns logic: `intakeScan()` (switch on RETURN_IN_TRANSIT/WITH_COURIER/RETURN_PENDING_INSPECTION), `listPending()`, `restock()`, `markDamaged()` (reason mandatory — 400 if blank), `neverReceived()` (NOT EXISTS gate on return_received events past window).
- **`ReturnController`** (`/api/v1/returns`): `POST /intake`, `GET /pending`, `POST /pieces/{id}/restock`, `POST /pieces/{id}/damage`, `GET /never-received`.
- **`LookupService`** — added phraseKey mappings for `return_received`, `restocked`, `damaged`.
- **`BostaWebhookJob`** — step 9 UPDATE now sets `returned_at = now()` when `isReturnedState` (state 46).

*Tests (`Day12Test` — 7 tests):*
- **(a)** Intake scan: `return_in_transit` → `return_pending_inspection`, `return_received` event with location+actor, `current_location_id` updated; `isUnexpected=false`.
- **(b)** Unexpected return: piece at `with_courier`, shipment not in returning state → intake proceeds to `return_pending_inspection`; `isUnexpected=true`, event written.
- **(c)** Restock: `return_pending_inspection` → `available`, `restocked` event, `current_order_id` cleared, `current_location_id` set.
- **(d)** Damage: null reason → 400; valid reason → `damaged`, reason in event metadata.
- **(e)** Never-received detector: shipment `returned_at` 4 days ago; piece A (no `return_received` event) appears in report; piece B (has event) excluded.
- **(f)** Continuous timeline: intake → restock; lookup shows both `return_received` and `restocked` in correct newest-first order.
- **(g)** Cross-tenant isolation: different tenant context → 404 on intake scan.

*Frontend (`Returns.tsx`):*
- Three-tab UI: **Intake** (worker — HID-ready auto-focused scan, full-screen green/red flash + beep, amber warning on unexpected return); **Pending Inspection** (manager — list + Restock/Damage actions); **Never-Received** (manager — configurable window, amber banner, piece/order/tracking table).
- Route `/returns` added to `App.tsx`; "Returns" nav link added to `Layout.tsx`.

**Pending live verification:**
- Mode B linking built + tested against mocks — needs live verification against a real Bosta account (real delivery JSON shape, consignee fields, end-to-end match/link) once account/staging is available.

**Known issues / before-pilot fixes:**
- **[RISK] Phone+COD fallback delivery-matching can auto-link the wrong delivery on collision** (repeat customer, or common COD amount across multiple open orders). businessReference match is exact and safe — this risk only applies to the fallback path. Before pilots: change `matchByPhoneAndCod()` to flag-not-auto-commit — if it resolves to zero OR more than one candidate order, route to `unlinked_bosta_deliveries` for manual resolution; never guess. Only auto-link when there is exactly one non-terminal order matching both phone AND COD, and log a warning even then. Tighten once real Bosta data is available to observe match rates. **Risk if unaddressed: wrong custody attribution, which corrupts the core promise of the system.**

**Nearest human tasks:**
- [NEAREST IMPACT] Bosta IP whitelisting: give Bosta the server's egress IP so it can deliver webhooks. Without this, all state-change webhooks are silently dropped.
- Shopify PCD review application (apply early — Shopify review has lead time; launch-gating dependency).
- GDPR webhooks (`customers/data_request`, `customers/redact`, `shop/redact`) + privacy policy required before PCD approval.

Day 10 complete as of 2026-06-16. All 111 integration tests pass (BUILD SUCCESS). Commits: `766136b` (main Day 10), `4eb3692` (live-test fix).

---

**Day 11 — Mode B fulfillment linking (FR-9.6, FR-4.4):**

*Migration V9 (`V9__day11_awb_linking.sql`):*
- `CREATE INDEX unlinked_bosta_business_ref_idx ON unlinked_bosta_deliveries (tenant_id, business_reference) WHERE resolved = false AND business_reference IS NOT NULL` — fast auto-match lookup.

*Backend:*
- **`ShipmentLinkService`** — the central linking service:
  - `linkByAwbScan(orderId, trackingNumber, actorUserId)` — packer scans plugin-printed AWB after completing an order. `@Transactional(READ_COMMITTED)`. Flow: verify order packed → swapped-AWB check (409 if tracking belongs to different order) → INSERT shipment (or skip if idempotent re-scan) → transition all packed pieces to `awaiting_pickup` with `event_type='tracking_linked'` → UPDATE order to `awaiting_pickup` → resolve any unlinked row for this tracking number. Idempotent: same-order re-scan returns existing shipment; `StateConflictException(actual==AWAITING_PICKUP)` caught and skipped.
  - `tryMatchDelivery(tenantId, trackingNumber, delivery, mapped)` — NOT `@Transactional`; called from `BostaWebhookJob` between its `TransactionTemplate` blocks. Tries businessReference match first (handles both `#1003` and `1003` Shopify formats), then phone+COD fallback (phone normalized to 11-digit `01XXXXXXXXX` form). If matched: creates shipment, transitions packed pieces, advances order to `awaiting_pickup`. Returns matched `orderId` or `null`.
  - `manualLink(unlinkedId, orderId, actorUserId)` — operator resolves a previously unmatched delivery row. Fetches unlinked record (validates not already resolved), creates shipment, transitions packed pieces, marks `unlinked_bosta_deliveries.resolved=true`.
  - `listUnlinked(page, size)` — paginated list of unresolved unlinked deliveries for the dashboard.
  - `normalizePhone(phone)` — strips `+20`/`0020`, validates `01XXXXXXXXX` 11-digit form.
- **`FulfillController`** — added `POST /{orderId}/link` endpoint: packer POSTs `{"trackingNumber":"BOS-123"}` after completing an order; returns shipment + piece state summary.
- **`UnlinkedDeliveryController`** (`/api/v1/shipments`): `GET /unlinked` (OWNER/MANAGER), `POST /unlinked/{id}/link` (OWNER/MANAGER) — management UI endpoints.
- **`BostaWebhookJob`** — step 8.5 injected between "no shipment found" and `recordUnlinked()`: calls `shipmentLinkService.tryMatchDelivery()`. If matched, re-fetches shipment for steps 9–10 (state update + piece transitions). If not matched, falls through to `recordUnlinked()` as before (backward-compat: existing BostaDay6Test unlinked test unaffected).
- **`LookupService`** — added `tracking_linked` phraseKey mapping so the custody timeline shows "AWB scanned — awaiting courier pickup" for this event type.

*Tests (`Day11Test` — 6 tests):*
- **(a)** AWB-scan at pack: 2 packed pieces → `linkByAwbScan()` → pieces `awaiting_pickup`, 2 `tracking_linked` events with `shipment_id` set, order `awaiting_pickup`.
- **(b)** Swapped-AWB detection: link AWB to order1 first; then link same AWB to order2 → 409 containing `"AWB-SWAPPED"` and the conflicting order number.
- **(c)** businessReference auto-match: packed order `ORD-AUTOMATCH`, Bosta delivery with `businessReference="ORD-AUTOMATCH"` → webhook triggers match, shipment created, piece/order `awaiting_pickup`, `tracking_linked` event written.
- **(d)** Unmatched → unlinked + manual link: delivery with unknown businessReference → `unlinked_bosta_deliveries` row created, piece/order still `packed`; then `manualLink()` → piece `awaiting_pickup`, order `awaiting_pickup`, `resolved=true`, shipment count=1.
- **(e)** Courier sync on linked shipment: piece at `with_courier`, fires state-45 webhook → piece `delivered`, shipment state `delivered`, 1 `courier_update` event with correct `shipment_id`.
- **(f)** Cross-tenant tracking isolation: `lookupTracking("AWB-XTENANT")` from wrong tenant → 404 (RLS enforced).

*Note: Mode B linking is fully tested against mock Bosta gateway. Live verification (real delivery JSON shape, consignee phone field path, end-to-end match+link) is pending once a Bosta account / staging environment is available.*

---

**Day 10 — FR-14 Piece-lookup timeline (custody-history showcase screen):**

*Backend:*
- **`LookupService`** — dual routing: `q.startsWith("PC-")` → piece lookup; else → tracking number lookup.
  - `lookupPiece(barcode, isWorker)`: single JOIN across pieces/variants/products/locations/orders/shipments/receipts. Timeline: all piece_events newest-first, LEFT JOIN users/orders/shipments/locations. Worker role (`isWorker=true`) omits `customerName`/`customerPhone` from the order map; keeps `orderNumber`.
  - **Bug found in live test**: original query also joined `receipt_lines` (unused — only `receipts.id` and location name are needed). When a receipt had the same variant on 2+ lines, `queryForMap()` threw `IncorrectResultSizeDataAccessException` → 500. Fixed by dropping the join (`4eb3692`).
  - `lookupTracking(trackingNumber)`: fetches shipment + order, then pieces via `JOIN allocations → order_items` (allocations has no direct `order_id` column).
  - `phraseKey(eventType, fromStatus, toStatus)` — static mapping from event type + state pair to a human-phrase key (received_at, reserved_for_order, returned_to_stock, packed_for_order, courier_delivered, courier_picked_up, courier_awaiting_pickup, courier_return_transit, courier_return_received, courier_damaged, courier_lost, courier_destroyed, status_changed).
- **`LookupController`** — `GET /api/v1/lookup?q=` open to OWNER/MANAGER/WORKER; routes to piece or tracking lookup based on `PC-` prefix.
- **`Day10Test`** — 9 integration tests:
  - (a) Full timeline newest-first (3 events: received → reserved → packed)
  - (b) Actor name populated; null actor_user_id → isSystem=true + actor="System"
  - (c) phraseKey derivation for all event types
  - (d) Unknown barcode → 404
  - (e) Cross-tenant lookup via app_user → 404 (RLS fail-closed)
  - (f) Worker role: customerName/customerPhone hidden, orderNumber kept
  - (g) Tracking number → shipment + piece list (via allocations → order_items join)
  - (h) Bidirectional: piece.currentOrder.id = orderId; order detail has piece in allocatedPieces
  - (i) receivingSession populated when receipt_id present

*Frontend:*
- **`Lookup.tsx`**: dual-view lookup page.
  - `StatusBadge` — color-coded pill for all 11 piece statuses.
  - `TimelinePhrase` — i18n phrase with `orderNumber`, `location`, `toStatus` interpolation.
  - `PieceView` — header card (barcode, variant title, status badge, location/order/shipment/receivedAt grid, receivingSession link); vertical timeline with dot indicators (indigo = latest, gray = older).
  - `TrackingView` — shipment header + pieces list with links to individual piece lookups.
  - `LookupPage` — auto-focused search bar, URL `?q=` param support for deep linking, 404 error state.
- **`Layout.tsx`** — global barcode/tracking search input in nav bar; submitting navigates to `/lookup?q=` and clears the field.
- **`App.tsx`** — `/lookup` route wired (inside `RequireAuth` + `Layout`).
- AR/EN locale keys: `lookup.*`, `lookup.phrase.*`, `lookup.pieceStatus.*`, `nav.lookup` (placeholder text for global search bar).
- **Note**: shipment/tracking fields on the piece lookup screen are intentionally empty for all existing pieces — they will populate once Day 11 Mode B linking writes the `tracking_linked` events and binds `current_shipment_id`.

**Post-Day-9 live testing fixes (2026-06-16) — all flows manually verified:**
- **CORS**: `SecurityConfig` was only allowing `localhost:5173` and `localhost:3000`. Vite fell back to port 5174 (5173 in use), causing all browser requests to 403. Fixed by widening to `http://localhost:[*]`.
- **Label barcode text**: `LabelService` was printing `PC-` + last 10 chars of the piece ID as human-readable text under the barcode. Manually typing that short form caused PIECE_NOT_FOUND. Fixed to print the full `barcode` field (the same value encoded in the Code128 image). A physical scanner always reads correctly; this only matters when typing manually for dev testing.
- **New endpoint** `GET /api/v1/receiving/sessions/{id}/pieces`: returns piece IDs, barcodes, status, and variant info for a finalized session. Useful for dev testing without a physical scanner.
- **Shopify re-sync**: Added `GET /api/v1/shopify/stores` (list connected stores) and `POST /api/v1/shopify/stores/{id}/sync` (pull latest orders from Shopify). Sync runs synchronously in the request thread (JobRunr enqueue is broken on Supabase — NPE in JobTable at startup, likely a JobRunr 7.3.0 / PG 17.6 compatibility issue). Frontend: "↻ Sync Shopify" button added to Orders page header; auto-refreshes the list after sync completes.
- **Dev account**: `day4dev@example.com` / `password99` — connected to `traceability-dev.myshopify.com`, store id `e4297db2-b627-4129-b7fa-03bb1525a65e`. A second empty-tenant account `dev4dev@example.com` was accidentally created (transposed letters) — ignore it.
- **Manually tested end-to-end**: Receiving → finalize → pieces created → Pick & Pack queue → scan pieces → Complete order → order status → packed. Shopify sync button pulls new orders correctly.

**Day 9 — Scan-driven pick/pack fulfill flow (FR-8 + FR-9 core):**

*Migration V8 (`V8__fulfill_queue.sql`):*
- `ALTER TABLE orders ADD COLUMN locked_by uuid REFERENCES users(id), locked_at timestamptz`
- Index `orders_locked_by ON orders (tenant_id, locked_by) WHERE locked_by IS NOT NULL`

*Backend:*
- **`FulfillService`**:
  - `getQueue()` — orders with status `new`/`ready_to_pick`, not on hold, oldest-first; includes per-order scan progress (scanned/total units)
  - `getOrder(orderId)` — order detail with items + allocated pieces per item
  - `lockOrder()` / `releaseOrder()` — assign order to worker (idempotent for same user); manager can release any
  - `scan(orderId, barcode, actorUserId)` — the core method:
    - `@Transactional(isolation = Isolation.READ_COMMITTED)` so transition() joins at the correct isolation level
    - Validation order: PIECE_NOT_FOUND → DUPLICATE_SCAN → ALREADY_RESERVED → WRONG_VARIANT → capacity check (with lock) → WRONG_STATUS → transition() race catch
    - **Over-allocation guard**: `SELECT quantity FROM order_items WHERE id = ? FOR UPDATE` acquires a row lock on the order_item, serializing concurrent scans. The allocation count is a SEPARATE SQL statement after the lock — under READ_COMMITTED each statement gets a fresh snapshot, so the second thread re-reads the count AFTER the first thread commits, correctly seeing 1 ≥ 1 → rejected. (A subquery inside the FOR UPDATE would use the statement's start-time snapshot and miss the concurrent INSERT.)
    - Returns `ScanResult(success, code, pieceId, barcode, variantId, orderItemId, allocatedCount, requiredQuantity, allComplete)`
  - `unscan(orderId, pieceId, actorUserId)` — transition reserved→available + release allocation
  - `complete(orderId, actorUserId)` — validates all lines fully scanned; transitions all reserved pieces → packed; marks allocations packed; sets order status → packed
- **`FulfillController`** (`/api/v1/fulfill`):
  - `GET /queue` — authenticated (OWNER/MANAGER/WORKER)
  - `GET /{orderId}` — order with items + allocated pieces
  - `POST /{orderId}/lock`, `DELETE /{orderId}/lock` — worker lock management
  - `POST /{orderId}/scan` — returns ScanResult (200 whether accepted or rejected; check `.success`)
  - `DELETE /{orderId}/scan/{pieceId}` — unscan (204)
  - `POST /{orderId}/complete` — pack all (200 with `{packedPieces}`)

*Tests (`Day9Test` — 16 tests):*
- **(a)** Queue shows only `new`/`ready_to_pick` orders, not `packed` or on-hold
- **(b)** Lock assigns locked_by + locked_at; same user idempotent; different user → 409
- **(c)** Manager releases any lock; clears locked_by + locked_at
- **(d)** Scan PIECE_NOT_FOUND for unknown barcode
- **(e)** Scan success: piece reserved, allocation created, counts correct
- **(e2)** `allComplete=true` on last scan of a 1-unit order
- **(f)** Scan DUPLICATE_SCAN: same piece scanned twice to same order
- **(g)** Scan ALREADY_RESERVED: piece reserved for another order
- **(h)** Scan WRONG_VARIANT: piece variant not on order
- **(i)** Scan WRONG_STATUS: damaged piece rejected
- **(j)** Race — two threads scan same piece: exactly one wins, one ALREADY_RESERVED, exactly one event written
- **(k)** Over-allocation race — two threads scan two DIFFERENT pieces against a qty=1 line: exactly one allocation (no over-allocation), the SELECT FOR UPDATE + separate COUNT guard proves correct
- **(l)** Unscan: allocation released → piece back to available, allocation status='released'
- **(m)** Complete: all reserved→packed, allocations→packed, order→packed; piece_events written for each
- **(m2)** Complete rejects with 422 when not all items scanned
- **(n)** Cross-tenant: Tenant B cannot scan Tenant A's piece (PIECE_NOT_FOUND via RLS)

*LabelService bug fix:*
- `·` (U+00B7, Latin-1 Supplement) is not in NotoSansArabic. When a label's product+variant string contains Arabic, the whole string is rendered with arabicFont, which lacks `·`. Changed separator to ` - ` (ASCII hyphen). This fixed the pre-existing `i2_arabic_variant_label_renders_without_errors` test failure.

*Frontend:*
- `Fulfill.tsx`: queue view + full-screen pick screen
  - Queue: list of eligible orders, scan progress bar, lock indicator
  - Pick screen: auto-focused scan input (HID barcode scanner ready); full-screen green/red flash overlay (`animate-flash` Tailwind keyframe); audio beep (Web Audio API, silent fallback); per-piece unscan button; Complete button shown only when all lines fully scanned
- AR/EN translations for `fulfill.*` and `nav.fulfill` keys
- `Layout.tsx` nav: "Pick & Pack" / "التجميع" link added
- `App.tsx` route: `/fulfill` wired (no Layout wrapper — pick screen is full-screen)
- `tailwind.config.js`: `flash` keyframe + `animate-flash` class added

**Day 8 — Inventory receiving + piece generation + labels (FR-6.1–6.5, FR-6.8):**

*Migration V7 (`V7__receiving_labels.sql`):*
- `ALTER TABLE receipts ADD status text DEFAULT 'open'` + `finalized_at timestamptz`
- `ALTER TABLE tenants ADD label_width_mm`, `label_height_mm` (per-tenant label size config), `worker_receiving_enabled boolean DEFAULT false`
- `CREATE TABLE receipt_lines` (staged lines before finalization; RLS + `tenant_isolation` policy)
- `CREATE TABLE label_reprints` (audit log for every print/reprint; RLS + `tenant_isolation` policy; INSERT-only semantics for `app_user`)

*Backend:*
- **`InventoryLedger.batchReceive(List<ReceiveSpec>, UUID actorUserId)`** — the second and only other writer of `piece_events`. Two multi-row INSERTs in one `@Transactional` boundary: Round-trip 1: `INSERT INTO pieces VALUES (p1),(p2),...,(pN)`; Round-trip 2: `INSERT INTO piece_events VALUES (e1),(e2),...,(eN)` with `from_status=NULL`, `to_status='available'`, `event_type='received'`, `actor_user_id` mandatory. All-or-nothing: if any barcode UNIQUE violation or FK fails, both INSERTs roll back together (no partial session). Performance: 1,000 pieces in ~2s (Testcontainers), well within the 10s NFR bar.
- **`ReceivingService`** — `createSession`, `addLine`, `updateLine`, `deleteLine`, `finalize`, `getSession`, `listSessions`, `searchVariants` (ILIKE search by SKU or title). `finalize()` fetches lines → builds one `ReceiveSpec` per unit → calls `ledger.batchReceive()` → marks session `finalized`.
- **`ReceivingController`** (`/api/v1/receiving`):
  - `POST /sessions` — create open session (OWNER/MANAGER)
  - `GET /sessions`, `GET /sessions/{id}` — list + detail with lines
  - `POST /sessions/{id}/lines`, `PUT /sessions/{id}/lines/{lineId}`, `DELETE /sessions/{id}/lines/{lineId}` — line management
  - `POST /sessions/{id}/finalize` — generate pieces + events
  - `GET /sessions/{id}/labels` — PDF download (application/pdf)
  - `POST /sessions/{id}/reprint` — log reprint + return PDF
  - `GET /variants/search?q=` — autocomplete search
- **`LabelService`** (PDFBox 3.0.3 + ZXing 3.5.3 + ICU4J 74.2):
  - 50×25mm page (configurable via `widthMm`/`heightMm` params — default per FR-6.4)
  - Code 128 barcode at 203dpi (ZXing `Code128Writer`)
  - Two-font approach: Helvetica (built-in PDF Type1) for piece ID + SKU (always ASCII); NotoSansArabic (embedded TTF subset) for variant names that contain Arabic
  - `shapeForDisplay(text)`: ICU4J `ArabicShaping.LETTERS_SHAPE` → contextual letter forms; ICU4J `Bidi.RTL` → correct visual left-to-right order for PDF stream. Latin text passes through unchanged.
  - `reprint()` logs to `label_reprints` (tenant_id, receipt_id, reprinted_by, piece_count, note)
- **Fonts**: `NotoSansArabic-Regular.ttf` (177KB) embedded in `src/main/resources/fonts/` — subsetting active (only used glyphs embedded, ~4KB subset for a short Arabic variant name)

*Tests (`Day8Test` — 11 tests):*
- **(a)** `finalize()` → exactly N pieces + N received events (from_status=NULL, to_status=available); session marked finalized
- **(b)** 1,000 pieces in ≤10 seconds (actual: ~2s on Testcontainers Postgres)
- **(c)** All 50 piece barcodes unique, all prefixed 'PC-'
- **(d)** All pieces status='available' at session location
- **(e)** Batch rolls back entirely on duplicate barcode (all-or-nothing invariant)
- **(f)** Every received event carries non-null actor_user_id = receiving user
- **(g)** Label PDF generated (valid %PDF header, >500 bytes)
- **(h)** Reprint logged in `label_reprints` (2 reprints → 2 rows, correct piece_count + reprinted_by)
- **(i)** RLS isolation: tenant B sees 0 sessions + 0 pieces from tenant A via app_user datasource (no BYPASSRLS)
- **(i2)** Arabic variant label generates PDF with NotoSansArabic subset embedded + renders to PNG at 203dpi for visual verification
- **(j)** ICU4J Arabic shaping produces contextual letter forms (shaped ≠ isolated, same length)

*Frontend:*
- `Receiving.tsx` page: session list, create-session form (location, PO ref, supplier, note), session detail view (add-line with SKU/title autocomplete + quantity, remove line, running total, finalize button with confirm dialog, Print Labels button → PDF in new tab, Reprint → fetch + open PDF).
- AR/EN translations added (`receiving.*` keys in both locale files).
- `Layout.tsx` nav: "Receiving" / "الاستلام" link added.
- `App.tsx` route: `/receiving` wired.

*CLAUDE.md updated:* batchReceive architectural note added to Environment notes — two writers of piece_events, do not refactor batchReceive to call transition().

*MigrationSmokeTest updated:* V7 count (6→7), `receipt_lines` + `label_reprints` added to `TENANT_SCOPED_TABLES`.

**Visual proof of Arabic rendering:** `/tmp/day8-label-arabic-preview.png` (rendered at 203dpi via PDFBox `PDFRenderer`) shows connected Arabic glyphs for 'مسحوق بروتين فانيلا', no boxes or disconnected letters.

**Day 7 — Read-only UI endpoints + React frontend scaffold:**

*Backend:*
- **`GET /api/v1/orders`** — Paginated orders list (OWNER/MANAGER). Query params: `status`, `q` (ILIKE search on number/customer_name/customer_phone), `tracking` (ILIKE join to shipments), `page`, `size` (max 100). Explicit `tenant_id = NULLIF(current_setting(...))::uuid` filter on all queries (defense-in-depth on top of RLS — ensures correct scoping even when connecting as BYPASSRLS roles like postgres in tests).
- **`GET /api/v1/orders/{orderId}`** — Full order detail with line items + allocated pieces (per item) + shipment (if any). Returns 404 for cross-tenant requests (RLS + explicit tenant filter).
- **`GET /api/v1/catalog`** — All products + variants with piece counts by status (available/reserved/packed/…/total). One GROUP BY query fetches all piece counts for the tenant upfront, then maps to variants.
- **CORS** — Added to `SecurityConfig`: allows `localhost:5173` (Vite dev) + env-configurable production origins.
- **`Day7Test`** — 8 new integration tests: orders list RLS scoping (tenant B sees 0 orders from tenant A via explicit filter), pagination (page/size + total), status filter, customer name search, order detail 404 cross-tenant, order detail with items + allocated pieces, catalog piece counts (3 available + 1 packed → correct counts), WORKER role → 403.

*Frontend (`frontend/` — Vite + React 18 + TypeScript + Tailwind):*
- Added deps: `react-router-dom` 7, `react-i18next` 17, `i18next` 26.
- `src/i18n.ts` — i18n init with AR + EN JSON locale files. Language persisted in `localStorage`; RTL `dir` applied to `<html>` on switch.
- `src/api.ts` — typed fetch wrapper (Bearer JWT from localStorage, auto-redirect to `/login` on 401).
- `src/components/Layout.tsx` — nav with Orders / Catalog links + language toggle + logout.
- `src/pages/Login.tsx` — email/password form → `POST /api/v1/auth/login` → token stored → redirect to `/orders`.
- `src/pages/Orders.tsx` — orders table with status filter dropdown, text search, tracking filter, pagination. Status badges color-coded, HOLD badge shown when on_hold.
- `src/pages/OrderDetail.tsx` — 3-column layout: customer + order info + shipment (left) / items with allocated piece barcodes (right).
- `src/pages/Catalog.tsx` — product list with variants; only non-zero piece-count statuses shown as colored badges.
- `src/App.tsx` — BrowserRouter with `RequireAuth` guard. Routes: `/login`, `/orders`, `/orders/:id`, `/catalog`.

*Live demo verified against Supabase dev store (day4dev@example.com):*
- 15 products, 24 variants returned by `/api/v1/catalog` ✓
- 3 orders returned by `/api/v1/orders` ✓  
- customerName null (PCD gate not yet approved — expected; data preserved in `orders.raw`)
- No pieces yet (receiving starts Day 8)
- Frontend running at `http://localhost:5173`, proxies `/api` to backend on 8080

**Day 6 — Courier state → custody ledger wiring:**

*Scope: Mode B only — no delivery creation, no pickup API.*

- **V6 migration**: new `unlinked_bosta_deliveries` table. Records Bosta deliveries received via webhook before the matching shipment row exists (Mode-B plugin may create the delivery before ingestion/matching). RLS-isolated (`tenant_id` + NULLIF policy). Partial index on `(tenant_id, tracking_number) WHERE resolved = false` for the operator screen (FR-4.4). No explicit GRANT needed — V1 ALTER DEFAULT PRIVILEGES covers it.
- **`BostaWebhookJob` fully wired** (was stub at end of Day 5):
  - Step 8: shipment lookup by `tracking_number`. If no match → `recordUnlinked()` inserts into `unlinked_bosta_deliveries`, webhook marked `processed` with note (expected Mode-B case, not an error).
  - Step 9: `UPDATE shipments SET internal_state, number_of_attempts, raw, last_synced_at` from the fetched Bosta state.
  - Step 10: piece transitions via `InventoryLedger.transition("courier_update")`. Queries pieces via `JOIN allocations WHERE a.status IN ('active','packed')`. Three idempotency paths: `current==target` fast skip; `StateConflictException(actual==target)` concurrent duplicate no-op; `StateConflictException(actual!=target)` log+skip; `IllegalTransitionException` log+skip. Step 11 `DuplicateKeyException` handles concurrent workers claiming the same idemKey.
  - `recordUnlinked()` helper inserts into `unlinked_bosta_deliveries` with raw Bosta payload.
- **`BostaDay5Test.cleanUp()`** patched to delete from `unlinked_bosta_deliveries` before `webhook_events` (Day 6 job now inserts there for unlinked tracking numbers in test scenarios, and the FK would block cleanup otherwise).
- **`MigrationSmokeTest`** updated: count 5→6, `unlinked_bosta_deliveries` added to `TENANT_SCOPED_TABLES`.
- **`BostaDay6Test`**: 6 new tests covering the full wiring — state 45 moves all pieces to `delivered` with one `courier_update` event each; redelivery hits dedup check, no duplicate transitions; unlinked tracking_number recorded + processed; unknown state code → `failed` + pieces untouched; state 41 SEND → no piece transition, shipment to `with_courier`; state 41 RTO → pieces to `return_in_transit`, shipment to `returning`.

**Day 5 — Background jobs + Bosta webhook ingestion:**

*JobRunr wiring:* `jobrunr-spring-boot-3-starter` 7.3.0 added. `JobRunrConfig` uses Flyway (postgres/DDL) datasource for `PostgresStorageProvider` so JobRunr can CREATE TABLE without `app_user` needing DDL privileges. `org.jobrunr.database.skip-create=true` in application.yml prevents a second DDL attempt from the runtime pool. Background job server disabled in tests via `src/test/resources/application.properties`.

*Shopify import → background job:* `ShopifySyncService.connectAndImport()` split into `connect()` (sync: validate + encrypt + upsert store, sets `import_status='pending'`) and `runImport()` (unchanged). New `ShopifyImportJob` wraps `runImport()` in `TenantContext.runAs()`, catches all exceptions internally (no rethrow), and sets `import_status` to `'importing'` → `'completed'` or `'failed'` with error JSON. `ShopifyController` now returns `202 Accepted` with `{storeId, importStatus: "pending"}` and enqueues the import job. New `GET /api/v1/shopify/stores/{storeId}/status` endpoint (OWNER/MANAGER) exposes `import_status` + `import_summary`. `ShopifyImportTest` rewritten: 6 tests including idempotency, unmapped variant, encrypted token, non-owner 403, job failure, and status endpoint.

*Bosta webhook ingestion:*
- **V5 migration** adds `store_import_status` enum + `import_status`/`import_summary` to `stores`, `webhook_secret` to `courier_accounts`, and the **fourth SECURITY DEFINER escape hatch**: `resolve_tenant_by_webhook_secret(p_secret text) → uuid` (CSPRNG 32-byte secret; stored as SHA-256 hex hash; `SET search_path = public`; `REVOKE ALL FROM PUBLIC; GRANT EXECUTE TO app_user`).
- `BostaGateway` interface: `fetchBusinessProfile(apiKey)` + `fetchDelivery(apiKey, trackingNumber) → BostaDelivery(trackingNumber, stateCode, type, numberOfAttempts, businessReference, raw)`.
- `BostaHttpGateway`: Resilience4j retry, configurable `bosta.base-url` + `bosta.api-version`. Throws `BostaTransientException` (5xx/network) or `BostaException` (4xx).
- `BostaStateMapper`: loads all 23 `(stateCode, type)` → `(shipmentInternalState, pieceStatusAfter)` mappings from DB at startup. Code 41 SEND → `with_courier`; code 41 RTO → `returning`. Unknown codes return `isException=true`.
- `BostaController`: `POST /api/v1/bosta/connect` (OWNER-only) validates key, generates 32-byte CSPRNG secret (64 hex chars returned once, only SHA-256 hash stored), encrypts API key. `POST /api/v1/webhooks/bosta` (permitAll): resolves tenant via the escape hatch, persists raw payload as `status='pending'`, enqueues `BostaWebhookJob`.
- `BostaWebhookJob`: two-layer idempotency — dedup check on `external_event_id` (optimization), state machine is the real backstop. idemKey = SHA-256(trackingNumber:payloadState:timestamp) based on PAYLOAD (stable for redeliveries), set AFTER successful state application. Verify-by-fetch: acts on fetched state code, not payload. Transient errors rethrown → JobRunr retries. Known duplicates call `markDuplicate()` (no `external_event_id` claim) to avoid unique-constraint collision with the first row.
- `BostaDay5Test`: 10 tests — non-owner 403, connect success (encrypted key + 64-char secret), state mapper (code 41 SEND/RTO, unknown, code 45), webhook unknown secret → 401, webhook valid → 200 + persisted, verify-by-fetch proof, redelivered event duplicate handling.

**Day 4 — Shopify connect + import (still live):** V4 migration adds `order_items.external_id` with partial unique index for idempotent upserts. Full Shopify integration implemented: `ShopifyGateway` interface + `ShopifyHttpGateway` (GraphQL client, Resilience4j retry, proactive throttle back-off, api-version pinned to 2026-04). `ShopifySyncService` orchestrates per-row `TransactionTemplate` upserts; COD inference from `displayFinancialStatus` + payment gateway names; unmapped-variant hold flag. `EncryptionService` stores access tokens as AES-256-GCM ciphertext (12-byte IV per call).

**Security config hardened:** Custom `AccessDeniedHandler` calls `setStatus(403)` not `sendError()`, avoiding Servlet error dispatch that would override 403 → 401. `ApiExceptionHandler` extended with explicit `AccessDeniedException → 403` handler (catches `@PreAuthorize` rejections that otherwise reach `DispatcherServlet`) and catch-all `Exception → 500` with `log.error`.

**Live import against Supabase:** `traceability-dev.myshopify.com` → 15 products, 24 variants, 0 orders (dev store is empty). All 4 `ShopifyImportTest` scenarios (idempotency, unmapped variant, encrypted token, non-owner 403) pass.

**Supabase first-contact done (2026-06-13):** V1–V3 migrations applied (PostgreSQL 17.6, eu-west-1 pooler, session mode). `app_user` role active with password set out-of-band; `rolbypassrls=false, rolsuper=false` confirmed — RLS genuinely binds it. `postgres` confirmed `rolbypassrls=true, rolsuper=false` (BYPASSRLS, not superuser). App restarts cleanly as `app_user` with Flyway reporting "no migration necessary". Smoke tests against Supabase: health ✓ (200), signup ✓ (201), login ✓ (200, accessToken + refreshToken present).

**What exists (Day 3 additions on top of Day 2):**
- `UlidGenerator` (`com.traceability.inventory`) — Crockford base-32 ULID generation (48-bit ms timestamp + 80-bit random, 26 chars). Used for `pieces.id` PK; `barcode = 'PC-' || id`.
- `PieceStatus` enum — mirrors the `piece_status` SQL enum with a `.db` field for JDBC casts.
- `TransitionContext` record — carries optional `orderId`, `shipmentId`, `locationId`, `currentOrderIdToSet`, `metadata` (jsonb) through a transition.
- `StateConflictException` — thrown when the conditional UPDATE returns 0 rows AND the diagnostic SELECT finds the piece with a different status (concurrent change or wrong expectation).
- `PieceNotFoundException` — thrown when the diagnostic SELECT returns nothing (piece not found or invisible under RLS). Callers map this to `PIECE_NOT_FOUND`; distinct from `StateConflictException` (`WRONG_STATUS`/`ALREADY_RESERVED`).
- `IllegalTransitionException` — thrown before any DB access when `(expectedStatus → newStatus)` is not in the state machine's allowed set.
- `InventoryLedger.transition()` — the single gateway for all piece state changes. One `@Transactional(isolation = READ_COMMITTED)` boundary; native SQL only. Race guard lives in the UPDATE WHERE clause; on conflict throws before the INSERT so zero `piece_events` rows are ever written on the conflict path. `tenant_id` in the event INSERT comes from the GUC directly (`NULLIF(current_setting(...), '')::uuid`).
- **State machine** — 18 legal `(from → to)` pairs; illegal pairs throw `IllegalTransitionException` before touching the DB.
- `InventoryLedgerTest` — 28 integration tests (Testcontainers Postgres, real FK chain): all 18 legal transitions (including from/to/actor fields on the event row); 7 representative illegal transitions; race guard (two threads, exactly one winner, exactly one event); append-only REVOKE enforcement via app_user connection; RLS fail-closed (real `transition()` call as app_user with no GUC → `PieceNotFoundException`, zero events written).
- **Test harness note**: tests (a)–(c) run as postgres (BYPASSRLS) — they test logic and race semantics, not RLS. Tests (d)–(e) use `appUserLedger` wired to an app_user `TenantAwareDataSource` + `TransactionTemplate` to test privilege revoke and tenant isolation. The `@TestInstance(PER_CLASS)` + static initializer `POSTGRES.start()` pattern is required because `SpringExtension.postProcessTestInstance()` fires before `TestcontainersExtension.beforeAll()` with `PER_CLASS`, so the container must be started at class-load time.

**What exists (Day 2 additions on top of Day 1):**
- `V3__auth.sql`: `refresh_tokens` table (SHA-256 hashed opaque tokens, RLS-isolated), `lookup_refresh_token` SECURITY DEFINER function (3rd escape hatch), `pin_fail_count` + `pin_locked_until` columns on `users`.
- All V1 + V3 RLS policies use `NULLIF(current_setting('app.current_tenant', true), '')::uuid` — PostgreSQL resets `SET LOCAL` GUC to `''` (not NULL) after `ROLLBACK`, and `''::uuid` is a cast error. NULLIF guards against this.
- `TenantContext` (ThreadLocal holder), `TenantContextFilter`, `TenantAwareDataSource` / `TenantAwareConnection` (java.lang.reflect.Proxy-based wrapper that runs `SET LOCAL app.current_tenant = ?` at transaction start, Spring Framework 6 removed ConnectionWrapper).
- `JwtService` (nimbus-jose-jwt HS256, 15-min access / 7-day refresh), `JwtAuthenticationFilter` (OncePerRequestFilter).
- `SecurityConfig`: stateless JWT chain, `HttpStatusEntryPoint(401)`, role matrix via `@PreAuthorize`.
- `AuthController`: `/signup`, `/login`, `/refresh` (opaque token rotation — used token rejected on second use), `/pin`.
- `PinService`: argon2id PIN matching, O(n) over tenant users (pilot scale), lockout at 5 failures for 15 min, `@Transactional(noRollbackFor = ResponseStatusException.class)` so fail counter commits even when throwing 401/423.
- `ApiExceptionHandler` (`@RestControllerAdvice`): intercepts `ResponseStatusException` BEFORE `ResponseStatusExceptionResolver` can call `response.sendError()`. Without this, `sendError(423)` triggers a Servlet error dispatch to `/error`; Spring Security 6 applies `JwtAuthenticationFilter` (OncePerRequestFilter — doesn't re-run on error dispatches) so the security context is empty, and `.anyRequest().authenticated()` returns 401, overriding the original 423. The `@ControllerAdvice` writes `ResponseEntity` directly — no error dispatch, no Spring Security override.
- `AuthIntegrationTest`: 6 tests — signup+GUC probe, login, cross-tenant RLS isolation (3 fresh JDBC connections; reusing one connection across ROLLBACK resets the GUC to '' causing a cast error), unauthenticated 401, PIN lockout (5-failure → 423), refresh token rotation.

**Hetzner VPS not yet provisioned; deploy pipeline not wired.**

---

## Remaining work — pilot-ready MVP

Features in delivery order. Commit history is the source of truth for what is done.

### 1. Returns intake + never-received report (FR-12) ✅ shipped 2026-06-17
Three-tab UI: intake scan, pending-inspection queue, never-received report. See history below.

### 2. Exceptions center (FR-15.3) ✅ shipped 2026-06-18
8 exception types (lost · never-received · unmatched-delivery · blocked-customer · stuck-shipment · unexpected-return · delivery-limbo · ndr-failed) with severity ordering, per-type AR/EN descriptions, action URLs, and resolve audit trail. Frontend command-center view. 12 tests pass. See history above.

### 3. Cancellation + self-pickup flows (FR-9.8–9.13) ✅ shipped 2026-06-18
All core flows implemented. See Day 14 above. Remaining edge case not yet built: no-show (7-day self-pickup TTL → exception → re-ship or cancel). Low priority for initial pilots.

### 4. Mode B live verification against real Bosta account
businessReference match + phone/COD fallback have been built and tested against the mock gateway. Before pilots: end-to-end verify with real Bosta delivery JSON — confirm consignee phone field path, businessReference format, state code sequence, and that `tryMatchDelivery()` links correctly. **Gated on Bosta IP whitelisting (human task below).**

Also before pilots: tighten `matchByPhoneAndCod()` — change to flag-not-auto-commit; only auto-link when exactly one non-terminal order matches both phone AND COD; route zero-or-multiple-candidate cases to `unlinked_bosta_deliveries` for manual resolution. Current implementation auto-guesses, which risks wrong custody attribution on repeat customers or common COD amounts.

### 5. Public OAuth app (separate design thread)
Production Shopify connect requires a public OAuth app (custom apps cannot read customer PII on Basic-tier stores; see Decisions). The OAuth flow, scopes, callback URL, and state-parameter handling are being designed in a separate thread. The current custom-app endpoint (`POST /api/v1/shopify/connect`) is DEV-ONLY and must not ship to pilots. When the spec arrives it will be handed to this thread for implementation. **Gated on Shopify Partner Dashboard app registration + PCD review approval (human tasks below).**

### 6. VPS deployment
Provision Hetzner VPS, set up Docker Compose (app + Postgres or Supabase connection), Nginx reverse proxy, TLS, `systemd` restart policy, deploy pipeline. Currently runs only locally; no production environment exists.

### 7. Pilot onboarding
- Tenant signup flow (FR-1.1): business name, owner, email, password — currently only manual DB insert.
- Guided onboarding checklist (FR-1.2): connect Shopify → connect Bosta → import → test label → first receiving.
- User CRUD by Owner/Manager (FR-2.2).
- Per-tenant settings UI (FR-1.4): label size, language, timezone, pickup address.
- Both pilots need to be able to set themselves up without direct DB access.

---

## Human tasks (not code — blocking or long-lead-time)

- **[IMMEDIATE] Bosta IP whitelisting** — give Bosta our server's static egress IP so webhooks are delivered. Without this, all courier state changes are silently dropped. Also required for any live Bosta API calls (delivery lookup, staging verification). Open the support ticket now.
- **[LEAD TIME] Shopify public app registration** — register in Partner Dashboard before the OAuth build can start. Do this before the spec is handed back.
- **[LEAD TIME] Shopify PCD approval** — submit Protected Customer Data access request immediately after app registration. Non-trivial Shopify review time; hard launch dependency for customer name/phone/address (currently null in orders; data preserved in `orders.raw` for backfill).
- **[REQUIRED] GDPR webhooks + privacy policy** — `customers/data_request`, `customers/redact`, `shop/redact` endpoints and a published privacy policy are mandatory for any Shopify public app. Required for PCD approval.
- **[OPTIONAL NOW] Dev store PCD unblock** — Shopify Admin → Settings → Apps → custom app → add `read_customers` + regenerate token, then Partner Dashboard → App setup → request Protected customer data access. Gives richer test data now without waiting for production PCD review.

---

## Decisions made

- **Runtime role is `app_user` / Flyway runs as owner** — app connects as unprivileged `app_user` so RLS is always enforced; Flyway runs as `postgres`, which carries the `BYPASSRLS` attribute (not a superuser — verified via `SELECT rolbypassrls FROM pg_roles WHERE rolname='postgres'`; returns `true`). `FORCE ROW LEVEL SECURITY` binds the table owner just like any role without `BYPASSRLS`; Flyway succeeds because DDL statements (CREATE TABLE, ALTER TABLE, CREATE INDEX) are never subject to RLS, and V2 seeds only the tenant-unscoped lookup tables (`bosta_state_mappings`, `ndr_codes`) which have no RLS policy.
- **Webhook idempotency via partial unique index + app-side key for Bosta** — `UNIQUE NULLS NOT DISTINCT (source, external_event_id)` in DB handles Shopify (which sends an event ID header); for Bosta (no HMAC, no event ID) we generate a deterministic key app-side and verify authenticity by re-fetching the event from the Bosta API.
- **`pieces.id` = app-generated ULID text PK; `barcode = 'PC-' || id`** — `pieces.id` is `text PRIMARY KEY` (no default, app supplies the ULID). `barcode` is `text NOT NULL UNIQUE` and equals `'PC-' || id`. `piece_events.piece_id` and `allocations.piece_id` are both `text` FKs to `pieces(id)`. ULID is time-sortable and URL-safe; the PK itself is the scannable identity — no separate UUID PK.
- **Order hold = boolean column not enum value** — a separate `on_hold boolean` column avoids combinatorial enum explosion (every `order_status` value would need a corresponding `_held` twin).
- **Four SECURITY DEFINER functions are the only RLS escape hatches** — `auth_lookup_user` (V1), `resolve_tenant_by_shop_domain` (V1), `lookup_refresh_token` (V3), `resolve_tenant_by_webhook_secret` (V5, approved 2026-06-14). Adding a fifth requires explicit approval. Any future cross-tenant read must go through a named, code-reviewed `SECURITY DEFINER` function; bare `BYPASSRLS` connections in application code are not an acceptable pattern.
- **App datasource: session-mode pooler `:5432` — deliberate, not a workaround** — Supabase direct host (`db.jtkzpjaangjtkrepkqdz.supabase.co`) is IPv6-only; no A record (confirmed via nslookup). IPv4 requires Supabase's paid add-on, not on our plan. We run on the session-mode pooler (`aws-0-eu-west-1.pooler.supabase.com:5432`), which pins one backend connection per client session — `SET LOCAL app.current_tenant` behaves identically to a direct connection. Transaction-mode pooler port `6543` is FORBIDDEN: it resets the GUC between statements, silently breaking RLS. `DataSourceConfig.rejectTransactionPooler()` throws `IllegalStateException` at startup if port 6543 is detected; guarded by 4 unit tests.
- **Production Shopify connect = public OAuth app; custom-app endpoint is DEV-ONLY** — Both pilots are on Shopify Basic. Custom (legacy) apps cannot read customer PII (name/phone/address) on Basic-plan stores — only Advanced/Plus. Our product requires customer PII for address→Bosta zone mapping, blocked-customer checks, and Mode-B order↔delivery matching. A public OAuth app can read PII on any plan after Shopify's Protected Customer Data (PCD) review. Therefore: the production connect/auth seam will be a public OAuth flow; the current custom-app token endpoint (`POST /api/v1/shopify/connect` with `adminToken`) is DEV-ONLY and must not be shipped to pilots. Everything else is unchanged — import pipeline, idempotency, gateway, encryption, Bosta Mode-B, ledger, and tenant isolation all reuse without modification. Launch-gating dependencies from this decision: (1) PCD review approval (apply early — non-trivial lead time), (2) mandatory GDPR webhooks (`customers/data_request`, `customers/redact`, `shop/redact`) required for any public app, (3) a privacy policy and data-use statement. App Store listing is post-pilot. Do not revert to custom-app-only thinking.
- **FR-21 stock-take `damaged → available` is out of scope, permanently, not a stub** — `PieceAdjustService.adjustPiece()` already treats `damaged` as a terminal status (409, `AdjustTest.adj7`) and no code path reverses it. When Step 4's resolve-actions were being designed, adding a `damaged:available` `InventoryLedger.ALLOWED` edge to support it was explicitly considered and rejected — only `damaged:lost` was authorized. A damaged-in-snapshot piece scanned as good surfaces as a read-only flag in the `on_shelf_uncounted` bucket; reinstating it is a manual `PieceAdjustService`/`InventoryLedger` question, out of band from a stock take. Follow-up FR-21.1 (reinstate-from-damaged) needs its own separate approval if pilot demand appears — do not silently add the edge to "complete" the feature.
- **CLOSED 2026-08-15 (FR-24): `ReceivingService.requireOpen()`/`getSession()` now filter `kind='inbound'` too.** Previously only `deleteSession()` did (see the entry this replaces, kept below in spirit) — `requireOpen()`/`getSession()` had no `kind` filter, so a receiving endpoint could in principle be called with a returns-session UUID. FR-24 (returns session-based rebuild) fixed this as a 2-line change + `ReceivingKindFilterTest` regression coverage (proves a `kind='returns'` row is invisible to both `getSession()` and every mutating method that routes through `requireOpen()`). Structurally the gap is also closed from the other side: returns sessions no longer live in `receipts` at all as of FR-24 (moved to dedicated `return_sessions`/`return_session_items`/`return_session_shipments` tables, V73) — old closed `receipts kind='returns'` rows are inert history, not backfilled.
- **Non-idempotent Shopify writes need their own dedicated HTTP call, not a shared retry-wrapped helper** — `ShopifyHttpGateway.executeGraphQL()` (used by every increment/move mutation) wraps calls in Resilience4j `retryExceptions(ResourceAccessException.class)`, silently re-sending up to 3 times on a connection/read timeout before any exception ever reaches the caller. That's fine when the mutation carries Shopify's `@idempotent(key:)` directive (existing increment/move calls). For FR-21's stock-take write-off (a genuinely non-idempotent decrement — `-2` applied twice is `-4`), reusing that path would defeat the whole point: the caller needs to classify ONE unambiguous outcome (a confirmed rejection vs. a genuinely-unconfirmed response) to know whether auto-retry is safe. `ShopifyGateway.pushStockTakeWriteOff()` is a separate, self-contained method: no shared retry wrapper, exactly one HTTP attempt. General lesson for any future non-idempotent Shopify write: don't route it through `executeGraphQL()`.

---

## Gotchas / environment quirks

- **Shopify 2026-04 removed `financialStatus` field on Order** — use `displayFinancialStatus` instead. Returns capitalized display values ("Pending", "Paid", "Authorized"). COD inference checks `"pending".equalsIgnoreCase(displayFinancialStatus)` — case-insensitive, so both are safe.
- **`ApiExceptionHandler.handleGeneral(Exception)` intercepts `AccessDeniedException` from `@PreAuthorize`** — `DispatcherServlet` resolves `AccessDeniedException` through `ExceptionHandlerExceptionResolver` before `ExceptionTranslationFilter` can invoke the `AccessDeniedHandler`. Must have an explicit `@ExceptionHandler(AccessDeniedException.class) → 403` handler above the catch-all; otherwise the `Exception` handler returns 500.
- **Supabase 15-connection cap (free plan session-mode pooler)** — solved by sharing one `owner-pool` (max=2, min-idle=1) between Flyway and JobRunr (`@FlywayDataSource` bean in `DataSourceConfig`), and shrinking `HikariPool-1` (app_user) to max=5, min-idle=1. Total at startup: 2 connections. Stale connections from crashed previous runs can fill the 15 slots; kill them in Supabase SQL Editor with `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = 'Supavisor' AND pid <> pg_backend_pid()` then immediately restart. Use `./dev.sh` to start the app — it loads `.env`, kills :8080, and starts Maven. Running `mvn spring-boot:run` in a new terminal without sourcing `.env` first causes Flyway to try `localhost:5432` (connection refused).
- **Shopify Protected Customer Data (PCD) is gated separately from `read_customers` scope** — `shippingAddress` and `customer` fields on Order are blocked even with `read_customers` granted until PCD is approved. Currently `customer_name`, `customer_phone`, `address` are null-populated; full data is preserved in `orders.raw` (jsonb) for backfill once approved. See pending human tasks for what to do.
- **A caught exception from a nested `@Transactional` call still poisons the whole shared transaction** — hit twice building FR-21 (`StockTakeReconciliationService.resolve()`'s `srt7` fix, then `StockTakeShopifyPushJob`'s Phase A/B split one level up). Under Spring's default REQUIRED propagation, a `@Transactional` method called from within another `@Transactional` method joins the SAME physical transaction. If the inner call throws a RuntimeException, Spring's `TransactionInterceptor` marks that shared transaction `rollback-only` on the way out — even if the OUTER method catches the exception in a try/catch. The outer method's eventual commit then throws `UnexpectedRollbackException`, taking down everything else that ran in that transaction (e.g. every other item in a batch loop) even though it "handled" the error. Fix: don't wrap the outer operation in `@Transactional` at all when it needs to catch-and-continue past a nested call that might throw — let each independent unit commit on its own (a nested `@Transactional` call creates its own transaction when none is active). Same root cause as the general rule "never wrap an external HTTP call in a DB transaction" — both are really "don't let something outside your control decide whether your transaction commits."


- **Docker Desktop M3 + Testcontainers API v1.41 override**: Docker Desktop on Mac M3 rejects docker-java's default v1.24 version-negotiation request with HTTP 400. Fix: `DockerDesktopMacStrategy` in `src/test/java/com/traceability/` overrides `test()`, `getClient()`, *and* `getDockerClient()` (all three are required — Testcontainers calls `getDockerClient()` after `test()` passes, and the base implementation re-does version negotiation) to force API v1.41. Strategy is loaded via `~/.testcontainers.properties` AND `src/test/resources/testcontainers.properties`. **Do not delete or "clean up" this class.** On CI (Linux Docker socket) version negotiation works fine; the class is inert there because the built-in `UnixSocketClientProviderStrategy` wins first.
- **`pg_class` RLS flag column is `relrowsecurity`** — not `rowsecurity`. Fixed in `MigrationSmokeTest.java` line ~90.
- **`FORCE ROW LEVEL SECURITY` binds the table owner too** — any future Flyway migration that needs to INSERT into a tenant-scoped table (e.g., seed a default location for a new tenant) must do so as `postgres` (which holds `BYPASSRLS` and therefore bypasses RLS unconditionally — confirmed `rolbypassrls=true, rolsuper=false` on Supabase) or with a `SECURITY DEFINER` helper. `app_user` will be blocked regardless.
- **`BostaWebhookJob` piece-transition catches are intentional idempotency guards — do not convert to errors.** A repeat terminal-state webhook (e.g. state 45 delivered arriving twice) hits one of three safe paths: (1) `current == target` fast-path check skips `ledger.transition()` entirely — no DB write; (2) `StateConflictException` where `getActual() == targetStatus` — concurrent worker applied the same transition first, treat as no-op; (3) `IllegalTransitionException` — piece has no legal path to target (e.g. a stale `with_courier` event arriving after `delivered`), log warning and continue. None of these paths fail the webhook. The dedup check (step 4) catches exact payload redeliveries before reaching pieces at all. Do not "fix" any of these catches into error or rethrow paths.
- **`SET LOCAL` is a silent no-op outside a transaction** — the `SET LOCAL app.current_tenant = ?` call for the tenant context filter must happen inside an explicit transaction (`BEGIN` / `COMMIT`). Called outside a transaction it silently succeeds but resets at the next statement boundary, leaving subsequent queries with no tenant context (empty-string GUC → policy evaluates to false → zero rows or constraint violation).
- **`TenantContext.set()` must happen BEFORE `TransactionTemplate.execute()`, not inside the callback** — hit building `StockTakeOpsTest`'s `app_user`/RLS harness (FR-21 Step 6.1). `TenantAwareConnection.invoke()` reads `TenantContext.get()` at the `setAutoCommit(false)` intercept point, which `DataSourceTransactionManager` triggers at the *start* of `execute()` — before the lambda body ever runs. Setting `TenantContext.set(tenantId)` inside `appUserTx.execute(status -> {...})` fires the `SET LOCAL` with no tenant already bound, so even the SAME-TENANT positive control comes back empty/404 — a genuine RLS-isolation test can pass for the wrong reason (both cross-tenant *and* same-tenant return nothing) if you don't also assert the positive control succeeds. Fix: set `TenantContext` once, outside and before the transaction callback, matching `InventoryLedgerTest`'s `@BeforeEach setTenantContext()` pattern. Applies to any future `app_user`/`TenantAwareDataSource` test harness, not just stock-take.
- **`api.ts`'s shared `request()` never surfaces JSON error bodies** — `if (!res.ok) throw new Error(\`${res.status}: ${res.statusText}\`)` never calls `res.json()` on the error path. This makes `Lookup.tsx`'s `AdjustPanel` `PieceCommittedException` (409) parsing dead code against a real network call — it only "works" in `adjust.test.tsx` because that test mocks `adjustPiece()` directly rather than going through `request()`. Found again in FR-21 Step 6.3: `resolveStockTake()` needs the 409 body to render the release card, so it does its own `fetch` + body parsing instead of routing through `request()`. Not fixed globally (bigger blast radius than the task warranted) — flag it before reusing `request()` for anything that needs to inspect a non-2xx JSON body, and prefer the scoped-fetch pattern `resolveStockTake()` uses until `request()` itself is fixed.

---

## FR-23 Overview Dashboard ✅ built 2026-08-13, branch `overview-rebuild` (not yet merged)

Full rebuild of `/overview` from a mockup (`design/Traced Overview Dashboard.dc.html`) — greeting, condensed onboarding card, needs-attention row, inventory-health row (incl. a new costed inventory-value tile), at-a-glance (donut + fulfillment funnel + exceptions-by-severity), throughput + recent-activity row, restyled recent-exceptions list, fresh-tenant empty state. Backend: 5 new GET endpoints (`/inventory/status-totals`, `/inventory/valuation`, `/orders/funnel`, `/inventory/throughput`, `/activity/recent`), `/exceptions/count` extended backward-compatibly, `/returns/pending` reshaped to `{items,total}`, onboarding dismiss endpoint. 3 migrations (V70–V72). See `docs/requirements-checklist.md` FR-23 for the itemized list and test coverage. Old Group A/B inventory tiles and the orders-volume line chart removed from this screen; `OrdersChart` component kept (moved to `components/OrdersChart.tsx`, exported, currently unused) rather than deleted, since it's still correct and might be reused later.

**Decisions made (this feature):**
- **`label_created` (progress_rank=1, shipment `internal_state='created'`) buckets into the funnel's "Courier" column, not "Packed."** It means a shipment record already exists — past packing, AWB linked — and is waiting on physical courier pickup. Same "handoff imminent" meaning as `order_status='awaiting_pickup'` (already mapped to `status.awaiting_courier`, also Courier), not a packing-stage state. Any future change to the 5-bucket funnel mapping must keep this boundary aligned with what `OrderStatusDeriver` actually means by each `primaryKey`, not just what reads naturally in English.
- **Activity feed = finalized receiving (`receipts.kind='inbound'`) + finalized stock-take sessions only. Pickups are excluded entirely, not shown with a missing actor.** `pickups` has no actor column at all, and every other line in the feed follows a "by {name}" pattern — showing pickup lines with no actor would be visibly inconsistent rather than gracefully degraded. **Follow-up: pickups lack an actor column → excluded from activity feed until one exists.**
- **`receipts.kind` MUST be filtered wherever `receipts` is queried by feature, not just by tenant.** `receipts` is shared between goods-receiving (`kind='inbound'`, V1) and the FR-12 returns Waybill Session (`kind='returns'`, V29) — the SAME table, discriminated only by this column. The activity-feed endpoint's first draft queried `receipts` for "receiving" without a `kind` filter and would have silently mislabeled a finalized *returns* session as "N pieces received." Caught via `RlsCoverageTest`'s coverage-audit test, not by design review — any new `receipts` query needs the same check. `PROGRESS.md`'s existing DELETE-session gotcha above (`kind='inbound'` filter) is the same trap, hit twice now.
- **Exceptions severity 4→2 collapse (CRITICAL→critical, HIGH+MEDIUM+LOW→warning) is a display simplification for this one dashboard widget, not a new severity taxonomy.** `listExceptions()`/the Exceptions page keep the full 4-tier severity untouched. `ExceptionService.countOpenExceptionsBySeverity()` is a new method; `countOpenExceptions()` (the pre-existing `int`-returning method `ExceptionRlsTest` depends on) now delegates to it rather than re-running the 17 detectors a second time.
- **`/inventory/status-totals` (pure per-status GROUP BY) and `/inventory/valuation` (low-stock + inventory value, needs the `variants` join) are deliberately separate endpoints**, not folded together — the per-status count is a hot read every dashboard load pays for; the valuation join is not.
- **Total pieces / donut math = available + reserved + damaged + lost only, not all 13 `piece_status` values.** Confirmed against the mockup's own arithmetic (14,110 + 3,980 + 240 + 90 = 18,420) — pieces that have left the warehouse (delivered, sold, etc.) aren't part of this "what's on hand right now" view.

**Gotchas found building this:**
- **`RlsCoverageTest` (`coverageAudit_allGetEndpointsAreCoveredOrExempt`) fails the WHOLE test suite the moment a new `@GetMapping` is added anywhere, until it's placed in that test's `COVERED` or `EXEMPT` set.** Easy to miss if you're only running the specific feature's tests — always run the full suite (or at least `RlsCoverageTest` itself) after adding any new GET endpoint.
- **Inventory-value's "N of M variants costed" caveat is not optional polish — M=0 must render a distinct "not set up" state, never `EGP 0`.** Zero variants costed and zero actual value are different facts; collapsing them would read as a false "your inventory is worthless" on day one for both pilots (nothing costed yet). Covered by `overview.test.tsx` ov6/ov7, verified to actually catch a regression by temporarily breaking the gate and re-running (see also the fresh-tenant and onboarding-visibility gates — same revert-and-confirm-red check was done for those two).
- **The fresh-tenant empty-state gate and the "zero-value" gates are easy to accidentally collide in tests** — both key off `totalPieces === 0`, so a test writing zero `statusCounts` to check a widget's own calm empty state will instead trigger the *fresh-tenant full-page replacement* (which hides that widget entirely) unless `onboarding.allDone` is also set to escape the fresh-tenant condition.


---

## FR-24 Returns — Session-Based Rebuild ✅ built 2026-08-15, branch `returns-rebuild` (not yet merged)

Full replacement of the three-tab Returns UI (Session/Intake/Pending/Never-Received) with a session-based worker flow, mirroring the design-system worker-loop conventions established by Fulfill/Receiving. Step 0 build plan (`docs/fr-24-returns-session-rebuild-build-spec.md`) approved 2026-08-14 with four changes to the original spec (A–D below); no second Step 0 was requested, so those four changes are the diff between the approved plan and what's documented here. Mockup: `design/Traced Returns Flow v2.dc.html` — the behavioral contract in the build spec overrides the mockup wherever they differ (this happened in a few places, see below).

**The four approved changes to the original plan:**
- **(A) Receiving kind-filter retrofit** — bundled into this branch, not deferred. See the updated gotcha entry above (`ReceivingService.requireOpen()`/`getSession()` now filter `kind='inbound'`).
- **(B) Abandon redesign — no reverse transitions.** The original plan proposed reverting undispositioned pieces on abandon via 4 new reverse `InventoryLedger.ALLOWED` pairs + a `return_cancelled` event. Rejected. Abandon is now a pure soft-delete (`return_sessions.status='abandoned'`, session + item rows kept) with **no piece-state changes at all** — undispositioned legal-scan pieces simply stay at `return_pending_inspection` and resurface via the "unassigned pending" query. This is the single biggest simplification in the whole build: the piece state-machine surface (`InventoryLedger.ALLOWED`) is completely untouched by FR-24.
- **(C) `markDamaged()` fires no Shopify call.** The original spec's "damage disposition → Shopify `inventoryMoveQuantities` available→damaged" line was wrong — the deployed FR-17 v2 invariant (damaged-at-inspection pieces were never sellable in Shopify, so they must not decrement/move anything there) wins. `ReturnService.markDamaged()` is reused completely unchanged; a dedicated test (`disposition_damaged_neverCallsShopify`) asserts the Shopify mock is never invoked for that piece id.
- **(D) Fulfill's structure, not Receiving's, for the immersive session.** Receiving's `SessionView` is actually in-shell (no full-screen immersive mode exists there today, despite the general worker-loop convention describing one) — `/fulfill`'s route-unwrapped + internal `<Layout>`-around-the-list-view-only pattern is the real precedent, and `/returns` now follows it exactly (`App.tsx`'s `/returns` route is unwrapped; `Returns.tsx`'s root component applies `<Layout>` only around `LandingScreen`, never around `OpenSessionScreen`).

**Decisions made (this feature):**
- **`return_sessions`/`return_session_items`/`return_session_shipments` are fully separate from `receipts`** — no backfill of old `receipts kind='returns'` history into the new tables. Old closed returns-sessions become inert (their pieces' full audit trail stays intact in `piece_events` regardless; only the session-grouping view for that old history is lost). Confirmed acceptable since this branch has never been deployed — no real pilot data exists in the old shape yet.
- **`GET /returns/pending` (and `ReturnService.countPending()`) kept alive unchanged, NOT repointed to the new analytics endpoint.** Overview's "Awaiting Inspection" tile depends on it (`getReturnsPendingTotal()` in `api.ts`). This is semantically distinct from analytics' `unassignedPendingCount` — `countPending()` is ALL pieces at `return_pending_inspection`; unassigned-pending is only those not claimed by any session (open or resolved). Conflating the two would silently change what the Overview tile means.
- **New `return_mismatch` exception type follows the existing 17 detectors' precedent (hardcoded bilingual Java strings), not real i18n keys** — the request's premise that all exception types are i18n-key-driven doesn't hold today (confirmed: `ExceptionService.enrich()`'s `descriptionEn`/`descriptionAr` are hardcoded string literals for every one of the 17 existing types, and `Exceptions.tsx`'s `TYPE_LABELS` is a hardcoded object, not routed through `t()` — a documented gap in `docs/frontend-inventory.md`). Returns UI strings themselves ARE real `returns.*` i18n keys — only the exception *description* text follows the existing hardcoded precedent. The `exceptions.*` i18n gap stays a tracked follow-up, not touched here.
- **The old single-piece gated reprint endpoint (`GET /pieces/{pieceId}/label`, `ReturnSessionService.validateAndRecordReprint()`) is untouched, not widened, not merged with the new one.** The new `POST /sessions/{id}/pieces/{pieceId}/reprint-label` (any piece status) is a deliberately separate surface — same precedent `TransferService.reprintOutstandingLabels()` already established (built its own method rather than reuse/widen the old gate).
- **`ReturnSessionService`/`ReturnSessionController` were rewritten in place (same class/file names), not given new names alongside the old ones.** The old waybill-first flow (session creation required an upfront shipment match, backed by `receipts`) is fully superseded — keeping both would have left a permanently confusing "which ReturnSessionService" split. `ReturnSessionTest.java`'s waybill-path cases (`a`–`h`, `k`, `l`) were rewritten against the new contract or retired; the `detectReturnInTransitStuck` detector cases (`i`/`j`/`m`/`n`/`o`) are untouched — that detector reads `piece_events`/`pieces.status` directly and has no dependency on the session model.
- **"Unassigned pending" is defined by the session relationship, not piece status alone** — the load-bearing query (`ReturnSessionService.analytics()`'s private helpers) is: `status='return_pending_inspection' AND NOT EXISTS (an item in an OPEN session, or an item with a resolved (non-pending) disposition)`. This is what lets a mismatched piece (permanently stuck at `return_pending_inspection` — mismatch never transitions it, by design) stop re-surfacing once resolved, while an abandoned session's still-pending piece correctly DOES resurface (abandoned is neither 'open' nor a resolved disposition). Every branch of this predicate has dedicated test coverage (`ReturnSessionRebuildTest.analytics_unassignedPending_everyBranch`) and was independently confirmed live end-to-end (abandon a session with 1 pending item → it appears in the landing's unassigned callout on the next load).

**Gotchas found building this:**
- **`Button` (design-system component) does not spread `data-testid` or other rest props onto the underlying `<button>`.** Already documented in the old `Returns.tsx`'s own comments ("Button doesn't spread it") but easy to forget when writing new screens — 3 buttons in the new `Returns.tsx` (open-session, close-session, confirm-abandon) had to be raw `<button className="btn-brand">`/`<button className="btn-danger">` instead, using the plain CSS button classes (`.btn-brand`, `.btn-danger` in `index.css`) rather than the `Button` component, specifically because tests needed a stable selector.
- **Illegal-state gating is derivable client-side for free, no new API field needed.** `SessionItem.status` (the piece's current status, freshly joined on every read) is `'return_pending_inspection'` for every still-pending LEGAL item (since a legal scan always transitions there) and something else entirely for an illegal-state item (which never transitions). So `isIllegal = item.disposition === 'pending' && item.status !== 'return_pending_inspection'` on the frontend exactly matches the backend's fork, with no extra flag to keep in sync.
- **Live-verification found a seed-data bug, not an app bug, and it's worth recording the diagnosis method.** Manually seeding a shipment with `tracking_number = 'QA-9100005'` (a human-readable label prefix) then scanning that same literal string produced a false "not recognized" — `TrackingNumberNormalizer.normalize()` correctly strips everything up to the last dash (designed for Bosta's real hub-routing prefixes on physical labels), so the scan normalized to `9100005` and didn't match the stored `QA-9100005`. Real Bosta tracking numbers are always stored bare-digit; the fix was to correct the seed data (store bare digits, scan a hub-prefixed value like `D-07-9100005`), not the code — confirmed the normalizer is working exactly as designed. General lesson for any future live-seeding: store tracking numbers bare-digit, exactly like production `shipments.tracking_number` always does.
- **Viewport size drifts between `computer` screenshot calls when driving the browser via claude-in-chrome** — a fixed-coordinate click computed from one screenshot's dimensions can silently miss on the next call if the actual viewport resized in between (observed screenshots at 1568×780, 1470×675, and 1568×720 within the same session). Prefer `find`+`ref`-based clicks over raw coordinates for anything after the first click in a sequence; when a click appears to have "done nothing," check the viewport size first before assuming an app bug.
- **A single same-day `git stash` frontend baseline check read 1 pre-existing failure instead of the established 3 (`blocklist.test.tsx` fb7 + `overview.test.tsx` ov2/ov5) — a flake, not a real shift.** Re-run 3× the next day, both against the committed branch head and against `main` via a fresh stash, all four runs landed on the same 3 failures that every prior PROGRESS.md entry already documents. `ov2`/`ov5` use `new Date()`-based fixtures, which makes them plausible candidates for one-off timing flakiness (not confirmed as root cause, out of scope to chase here — pre-existing, unrelated to FR-24). Lesson: a single `git stash` comparison is not proof against a flaky suite; re-run before treating a baseline count as settled, especially when it contradicts documented history.
- **Closed 2026-08-16: the abandon "no revert" claim now has a direct piece_events-count tripwire, not just a piece-status assertion.** `ReturnSessionRebuildTest.abandon_softDeletes_doesNotRevertPendingPieces` captures `countEvents(piece)` immediately before and after the live `DELETE .../sessions/{id}` call and asserts they're equal — proven load-bearing by temporarily adding a throwaway `ledger.recordLabelReprinted()` loop inside `abandon()` and confirming the test fails (`expected: 1 but was: 2`), then reverting. The same test also now calls the real `GET /returns/analytics` after abandoning and asserts the piece appears in `unassignedPending` — a live write→read proof, not just the `insertSessionItem()` fixture path `analytics_unassignedPending_everyBranch` branch (d) uses.

---

## Orders screen restyle ✅ built 2026-08-16, frontend-only, appearance pass to Design System v1.0

Restyled `Orders.tsx` (list) and `OrderDetail.tsx` (detail) to `design/Traced Orders Flow v2.dc.html`. Purely frontend — no backend/endpoint/migration change, no new mount-time or on-interaction fetch. Went through a two-gate Step-0 process before any code: (1) a delta-list report identifying that this screen never actually had the gauges/chart/chip-gallery the brief described (that was `OrdersChart.tsx`, already dead/orphaned since the FR-23 Overview rebuild, untouched here), plus 5 things flagged as undoable frontend-only (row-expansion needs a new fetch-on-interaction, the 6-tile metric row has no honest data source, the Amount payment sub-line has no backing field, "Synced Xm ago" needs a new mount-time fetch, and the detail-page status stepper needed an explicit primaryKey→step mapping sign-off); (2) a second gate specifically for the stepper mapping, since a wrong mapping would visually misrepresent status.

**Five cuts, locked before build:** row-expansion (list stays flat — Order # is still the click-through link, no per-row fetch-on-expand); the 6-tile metric row (no all-time/multi-bucket aggregate endpoint exists — building it client-side would mean 6–8 sequential calls, a real perf/behavior addition a restyle must never introduce); the Amount column's illustrative "COD PENDING"/"Prepaid PAID" sub-line (no `paymentMethod` at list level, no paid/pending concept anywhere in the backend — Amount renders `codAmount` alone, always did); "Synced Xm ago" (would need `listShopifyStores()` on mount, a call the page doesn't make today — Sync button unchanged, still click-triggered); the mockup's 6th stepper step "Out for delivery" (no data signal distinguishes it from generic `with_courier` — Bosta's granular codes collapse into the 9-value `shipment_internal_state` enum before ever reaching the app, confirmed by `OrderStatusDeriver`'s own doc comment and `BostaStateMapper`).

**The status stepper (`computeStepperSteps` in `OrderDetail.tsx`) — the one genuinely new, risk-bearing element:**
- 5 steps: Created → Packed → With courier → In transit → Delivered. Read-only over fields `OrderDetail` already fetches (`derivedStatus.primaryKey`/`.notTraced`, the forward-leg shipment's `internalState`/`deliveryHistory`) — no new derivation of tone/label/health-chips, `OrderStatusDeriver` untouched, status still rendered solely via the existing `OrderStatus` component elsewhere on the page.
- Suppressed entirely (no stepper rendered) for: `notTraced` (checked first, unconditional), and any primaryKey off the forward-delivery ladder (`cancelled`, `returning`, `returned`, `lost`, `terminated`, `needs_attention`, `self_pickup_pending`) — plus the one narrow edge case where `delivery_failed` co-occurs with shipment `internalState==='exception'` (no reliable rank signal in that combination, since the failed-attempts branch in `derive()` is checked before the exception branch). Any *other* unmapped primaryKey also falls through to a safe `default: return null` in the switch — belt-and-suspenders, confirmed redundant-but-harmless during tripwire testing (see gotcha below).
- "Packed" never gets a timestamp sub-line (no `packed_at` column or order-status-history table exists anywhere in the backend — confirmed by reading `OrderController.detail()`'s SQL directly rather than assuming). The other 4 steps' timestamps come from `order.placedAt` (Created) and the forward shipment's own `deliveryHistory` entries (With courier / In transit / Delivered), omitted if that history is empty (the same pre-V40 empty-history fallback case `OrderStatusDeriver.maxProgressRank()`'s own doc comment already describes).
- Step label copy reuses existing i18n keys rather than minting new ones per the "reuse `orders.pipeline.*`/`status.*`" instruction: `orders.pipeline.packed`, `orders.pipeline.with_courier`, `status.in_transit`, `orders.pipeline.delivered`. Only `orderDetail.stepper.created` ("Created"/"تم الإنشاء") is genuinely new — no existing key means "order was created" as a step caption.

**Other restyle changes:** list table gets a DS-chrome `Alert` card for the error state (was a bare red `<p>`), Amount column header renamed COD→Amount (value unchanged, still `codAmount`-only), Date column renamed Placed→Date (copy-only), 3 previously-hardcoded strings in `Orders.tsx`'s sync flow (`'↻ Shopify'`, `'No store connected'`, `'Synced'`, `'Sync failed'`) moved to real `orders.*` i18n keys while already touching that code. Detail page: 3-column grid reflow to `260px/340px/1fr` (Customer | Status+Shipment+Return-leg | Items) matching the mockup, COD inline-edit 4-state visual polish (accent border+ring editing, dimmed+disabled saving, critical border on error) as pure `className` over the pre-existing `codEditing`/`codBusy`/`codErr` state machine — no new state, no new branches. `OrdersChart.tsx` left untouched (Overview's dead leftover, out of scope).

**Gotchas found building this:**
- **A new UI element legitimately reusing an existing i18n key can break an unrelated pre-existing test that was counting occurrences of that exact text.** `orderDetailLegStatus.test.tsx`'s `'order with only a forward shipment never renders a leg-scoped badge at all'` asserted `getAllByText('In transit')` had length 1 (guarding against the A3.1 forward-leg-badge bug) — the new stepper's "In transit" step, for that exact fixture (`primaryKey: 'status.in_transit'`), legitimately adds a 2nd, unrelated occurrence via the same `status.in_transit` key. Fixed by updating the count to 2 with a comment explaining why, not by avoiding the key reuse — the test's actual regression-guard intent (no phantom 3rd occurrence from a resurrected leg badge) is still fully intact and still fails if that regression is reintroduced.
- **Proving a suppression tripwire "fails first" sometimes needs more than toggling its own guard line, because of an intentional second line of defense.** `computeStepperSteps`'s switch has a `default: return null` — since none of the 7 suppressed primaryKeys are switch cases, disabling the explicit `STEPPER_SUPPRESS_KEYS.has()` check alone doesn't make the cancelled-order test fail (the default still catches it). Had to simulate the *actual* regression this guards against — removing `'status.cancelled'` from the suppress set AND adding a real `case 'status.cancelled': currentIndex = 1` to the switch, mimicking a future "helpful" change that wires cancelled into the ladder — to get a genuine red test, then reverted both. The not-traced and delivery_failed+exception tripwires didn't have this issue (their guards are the only thing standing between input and a non-null result), so a direct on/off toggle was sufficient proof for those two.
- **Playwright isn't a project devDependency and `npx playwright install` warns without `frontend/node_modules` being the active project root** — worked around by resolving the already-cached `~/Library/Caches/ms-playwright/chromium-1234` binary directly and running the driver script from inside the npx-cached `playwright` package's own directory (Node's ESM resolution requires the importing file to have `node_modules/playwright` in an ancestor directory; `NODE_PATH` does not help ESM resolution the way it helps CJS). Visual verification used a throwaway dev-only harness (`frontend/harness.html` + `frontend/src/devHarness.tsx`, a second Vite entry not listed in `vite.config.ts`'s `rollupOptions.input` so `vite build` never touches it) that stubs `window.fetch` per-state via a `?page=&state=&lang=&name=` query string and mounts `<Layout><Orders/></Layout>` / `<Layout><Routes><Route path="/orders/:id" element={<OrderDetail/>}/></Routes></Layout>` directly — both files deleted after verification, not committed.
- **A quick visual read of a multi-column screenshot can misattribute a timestamp to the wrong column when one column in between has no timestamp of its own.** Briefly suspected a real off-by-one bug in the stepper (Packed appearing to show a time it shouldn't) purely from eyeballing a screenshot — the automated positioning test for the exact same fixture had already passed, and a direct `page.getByTestId('order-stepper').innerText()` DOM dump confirmed the render was correct all along (Packed genuinely has no timestamp; the "4:52 PM" belongs to the next column, "With Courier"). Lesson: when a screenshot and a passing automated assertion disagree, trust the DOM dump over the screenshot read before concluding there's a bug.

---

## Orders — status-split diagnosis (Step 0 only, not built) + all-time summary tile row ✅ built 2026-08-16

**Two independent slices requested from the same prompt.** Slice 1 (reordering `label_created` vs `packed` in the stepper/derivation ladder) was diagnosis-only — reported and gated, not built, pending a design decision. Slice 2 (`GET /orders/summary`, a 5-tile all-time summary row) was approved and built. Documenting both here since the Slice 1 diagnosis is load-bearing context for anyone touching this area next.

**Slice 1 finding (NOT YET BUILT — needs a design decision before any code):** `label_created` (a `shipments` row existing at `internal_state='created'`) and `packed` (`orders.status='packed'`, set by `FulfillService.complete()` after real scan-gated pack completion) are NOT a fixed sequence — they're two independently-arriving signals that can interleave either way:
- `ShipmentLinkService.linkByAwbScan()` (packer scans the plugin-printed AWB) is hard-gated: throws 409 unless `order.status IN ('packed','awaiting_pickup')` already — packed always precedes label_created on this path.
- `ShipmentLinkService.tryMatchDelivery()` (Bosta webhook auto-match, called from `BostaWebhookJob`) is **not** gated — its own code comment says "Advance order **if** it is currently packed" before a conditional `UPDATE ... WHERE status='packed'` that's expected to sometimes be a no-op. Confirmed: for Mode-B orders (the Shopify plugin creates the Bosta delivery near order-placement time, independent of Traced's own packing), a shipment row at `internal_state='created'` can and does land while `orders.status` is still `new`/`confirmed`/`ready_to_pick`/`picking`.
- Since `OrderStatusDeriver.derive()` branches `shipmentLinked ? (shipment rank only) : (order.status pipeline only)` — never merging the two — the moment a shipment exists at all, the ENTIRE pre-shipment pipeline (packed included) stops being read, regardless of how far `order.status` had actually progressed. This is the exact mechanism behind the "Packed shows as done even though it never happened" bug: the frontend stepper's monotonic `stepState()` marks every index below the current one as `'done'`, so an early-webhook-matched order shows a false "Packed" checkmark.
- Two honest fixes, neither picked yet: **(A)** track both axes independently and display both facts without ever claiming one happened before it did (more correct, more invasive — touches `derive()`'s signature and all 3 duplicated SQL sites: `list()`, `detail()`, `funnel()`); **(B)** fix a single ordering (packed > label_created) and accept that the OTHER flow (`linkByAwbScan`, packed-then-link) will then show a step re-appearing (Packed → Label created → In transit) — not truly monotonic either, just moves the visible regression to the other flow. Full diagnosis with code citations was reported and is awaiting a decision; **do not build a stepper/derivation reorder without this decision being made explicitly** — it's not a one-line rank swap.

**Slice 2 — `GET /orders/summary` ✅ built.** Five-tile calm row (Total · Processing · With courier · Delivered · Returned) above the Orders list filter bar, cut from the original Orders v2 restyle for lack of an honest data source (see the "Orders screen restyle" entry above) — now built as a real endpoint, no scraping. `OrderController.summary()` mirrors `funnel()`'s exact query (same LATERAL-join-latest-forward-shipment SELECT, same per-row `OrderStatusDeriver.derive()` Java-side tally, same single source of truth) minus the `placed_at::date = CURRENT_DATE` filter — `total` is free (`primaryKeys.size()`, since the query has no status filter). Bucket mapping mirrors `funnel()`'s grouping exactly (Processing = new/confirmed/ready_to_pick/picking/packed; With courier = awaiting_courier/in_transit/label_created; Delivered = delivered; Returned = returned only, `returning` deliberately uncounted) — carries an explicit code comment noting the Slice-1 dependency (this Processing/With-courier boundary inherits today's not-yet-corrected packed/label_created ordering and must move if/when Slice 1 lands). No migration — pure derive-on-read over existing tables/RLS. Frontend: `getOrdersSummary()` in `api.ts`, an independent non-blocking mount-effect in `Orders.tsx` (own `useState`, own `.catch(() => {})` — never shares the table's `error` state), rendered as a `grid-cols-5` row of `StatCard` (no delta/sparkline) between the header and filter bar. i18n: 2 new keys (`orders.summary.total`, `orders.summary.processing`), 3 reused (`orders.pipeline.with_courier`/`delivered`/`returned`).

**Tests:** `RlsCoverageTest` — `/api/v1/orders/summary` added to `COVERED` + a seeded smoke assertion. New `OrderSummaryTest.java` — `summary_bucketsTallyCorrectly_andBreakdownIntentionallyDoesNotSumToTotal()` (one order per bucket + an intentionally-uncounted cancelled order, asserts each bucket and that `total` exceeds the 4-bucket sum) and `rls_summary_sameTenantPositiveControl_crossTenantNegativeControl()` (cross-tenant view sees `total==0`). Frontend: `orders.test.tsx` gained a summary-tile-row happy-path test and a dedicated calm-fail tripwire (`getOrdersSummary()` rejects → no tile row, table still renders, no shared error state) — proven by reverting the `.catch(() => {})` to share the table's `setError()` and confirming the test fails (the table itself stopped rendering, since the shared error state replaced it with the Alert card) before reverting back. Full backend suite: 1017 tests, 0 failures, 3 skipped (pre-existing).

**Gotchas found building this:**
- **`funnel()`/`summary()` are NOT unit-testable via direct `new OrderController(...)` instantiation the way `list()`/`detail()` are — they rely solely on Spring's `@Transactional` AOP, which is inert on a manually-constructed object.** `list()`/`detail()` each wrap their own body in `TransactionTemplate.execute(...)` (`tx.execute(txs -> {...})`) specifically so they still work when tested this way — see `OrderStatusListDetailParityTest`'s own doc comment. `funnel()`/`summary()` have no such internal wrapper. `TenantAwareConnection` (the proxy that fires `SET LOCAL app.current_tenant`) only intercepts on the `setAutoCommit(false)` transition (`TenantAwareConnection.java:43-47`) — a real transaction beginning — which a bare autocommit `jdbc.query()` never triggers. First attempt at `OrderSummaryTest` (mirroring the parity test's direct-instantiation pattern) silently returned `total=0` for every bucket — no error, just an empty tenant-filtered result, because the GUC was never set. Fix: wrap the test's own calls in a `TransactionTemplate` (`tx.execute(status -> controller.summary())`), exactly reproducing what `list()`/`detail()` do internally and what the real `@Transactional`-proxied HTTP endpoint does at runtime. Any future endpoint written funnel()-style (bare `@Transactional` annotation, no internal `tx.execute()`) needs the same test-side wrapper if tested via direct instantiation rather than through `RlsCoverageTest`'s real HTTP path.
- **An aggregate-over-the-whole-tenant endpoint test must not share a tenant with another test in the same `@TestInstance(PER_CLASS)` class unless every test's assertions are per-row/exact-ID, not tenant-wide tallies.** `OrderStatusListDetailParityTest` safely shares one `tenantId` across all its tests because every assertion targets one specific order by ID. `OrderSummaryTest`'s bucket-tally test counts ALL of a tenant's orders — sharing the class-level `tenantId` with the RLS test (which also seeds one order for that tenant) made the tally test's `total`/`processing` counts depend on JUnit's undefined method execution order within `PER_CLASS` (first run: correct; either order possible in practice — caught because a run happened to execute the RLS test first, leaking its seeded order into the tally). Fixed by giving the bucket-tally test its own dedicated tenant, created inline, so it's correct regardless of execution order — not by asserting exact order or forcing `@TestMethodOrder`.

---

## Orders — Slice 1: phantom-packed fix ✅ built 2026-08-16 (spine change, `OrderStatusDeriver`)

Fixes the diagnosis above ("Orders — status-split diagnosis"). Two-gate Step 0 (initial diagnosis, then a full design report covering the narrow-fix-sufficiency question, whether the lie survives past the stepper, the two-axis deriver design, a monotonicity proof, and the migration/re-bucketing check) both signed off before any code — Option B (reorder `PROGRESS_RANK` so `packed` outranks `label_created`) was explicitly rejected up front since it just moves the backward-looking-progress problem onto the packer-first flow instead of fixing it.

**Root design decision:** `label_created` and `packed` are two independently-arriving signals, not a fixed sequence — a shipment existing is not proof packing happened. Rather than reordering ranks, added one **purely additive** boolean, `OrderStatusDeriver.DerivedOrderStatus.packedConfirmed`, computed inside `derive()` from inputs the method already receives (`orderStatus`, `shipmentInternalState`, `maxProgressRank`, the internally-computed `terminal` flag) — no new parameters, no new SQL columns, no migration:
```java
packedConfirmed =
     Set.of("packed","awaiting_pickup","with_courier","delivered").contains(orderStatus)
  || (maxProgressRank != null && maxProgressRank >= 2)               // with_courier or returning
  || (terminal && !"cancelled".equals(shipmentInternalState));       // delivered/returned/lost/terminated
```
`primaryKey`/`tone`/`healthChips`/`conflictKey`/`historicalNote`/`notTraced` are all unchanged — `'status.label_created'` was never factually wrong (it only ever claimed a shipment record exists, never that packing happened), so no chip/copy change was needed, only the label_created→"past packing" *bucketing assumption* downstream in `funnel()`/`summary()` was wrong and needed fixing.

**Backend consumers updated:**
- `funnel()` — row-mapper now returns the full `DerivedOrderStatus` (was bare `primaryKey()` string) plus the order's own raw `status`, via a local `StatusRow(orderStatus, derived)` record. When `packedConfirmed` is false for a `label_created`/`awaiting_courier` primaryKey, the order routes to **New or Picking** (matching its own raw `order.status`) instead of Courier — funnel() keeps its finer New/Picking/Packed split, so there's no single merged bucket to fall back to.
- `summary()` — same reclassification, but simpler: routes into **Processing** (already one merged bucket covering new/confirmed/ready_to_pick/picking/packed), no need to sub-distinguish.
- Both doc comments rewritten — the old "a shipment record existing means past packing, AWB linked" premise is now explicitly flagged as disproven, not silently left in place.
- The 3 SQL CASE sites (`list()`, `detail()`, `funnel()` LATERAL) are **byte-for-byte unchanged** — they already select every column `packedConfirmed` needs. `PROGRESS_RANK` is **unchanged** (Option B stayed rejected).

**Frontend (`OrderDetail.tsx`'s `computeStepperSteps`):** only the Packed step (index 1) changes; steps 2–4 (With courier/In transit/Delivered) are untouched, still driven purely by the pre-existing shipment-rank `currentIndex`. Packed's `done`-ness is now computed independently, client-side, from fields already on `OrderDetail` (no new fetch, no read of the new backend `packedConfirmed` field — the frontend computes its own equivalent signal, mirroring the backend's logic exactly but not literally consuming it):
```ts
packedReached =
     ['packed','awaiting_pickup','with_courier','delivered'].includes(order.status)
  || primaryKey === 'status.in_transit'
  || primaryKey === 'status.delivered'
  || (primaryKey === 'status.delivery_failed' && forward?.internalState === 'with_courier')
packedState = packedReached ? 'done' : (forward === null ? 'current' : 'pending')
```
Two deliberate, signed-off UX trade-offs left as-is rather than adding new visual states: (1) when a shipment already exists but packing hasn't happened, Packed renders a hollow "pending" dot *to the left of* an already-lit shipment step, and the connecting line between them renders grey (accurate — nothing was hidden, the line-color logic already keys off `steps[i-1].state === 'done'`, unchanged); (2) when `order.status` reaches `'packed'` but no shipment exists yet, nothing is marked `'current'` for that brief window (Packed is `'done'`, nothing else has started) — no phantom highlight, just a short gap with no "you are here" marker.

**Deploy-boundary note (also called out in the sign-off, repeating here since it's the one thing to expect when eyeballing prod):** at deploy, orders currently displaying a phantom "Packed ✓" (shipment exists, never packed) lose that checkmark, and some "With courier"/Courier funnel counts drop as never-packed orders reclassify. **This is the fix landing, not a regression** — a one-time correction of data that was never true. The monotonicity proof (signed off) covers every order's own forward lifecycle in both flows (packer-first `linkByAwbScan`, hard-gated so `packed` always precedes shipment creation; and Mode-B `tryMatchDelivery`, unguarded, where a shipment can appear before packing) — in neither flow can a done step ever revert to not-done across an order's real lifecycle. It does not, and structurally cannot, cover the one-time visual correction for orders *currently* sitting in the phantom state at the moment this ships.

**Tests:** `OrderStatusListDetailParityTest` gained 4 new fixtures (`slice1_webhookEarlyMatch_...`, `slice1_packerFirst_...`, `slice1_courierHolding_...`, `slice1_cancelledShipment_...`) proving `packedConfirmed` for each of the four cases, plus `list()`/`detail()` parity (guaranteed by construction — both call the same `derive()` with the same inputs, so there's nothing to keep manually in sync). `OrderSummaryTest` gained `summary_neverPackedWebhookMatchedOrder_countsAsProcessing_notWithCourier` and `funnel_neverPackedWebhookMatchedOrder_countsAsPicking_notCourier` — the merchant-facing miscount tripwires, both proven to fail first (both asserted `expected: 1, but was: 0` when the reclassification `if` branches were temporarily disabled) before being restored. Frontend `orders.test.tsx` gained 4 `SLICE1(a–d)` tests: (a) the core bug — webhook-matched never-packed order shows Packed `pending`, With-courier `current`; (b) packer-first `order.status='packed'` shows Packed `done` even with no shipment; (c) courier physically holding the parcel shows Packed `done` via the rank≥2 branch even with `order.status` stuck pre-pack; (d) a shipment-cancelled order — the stepper is fully suppressed (`primaryKey` always resolves to `'status.cancelled'` for a terminal-cancelled shipment, already in `STEPPER_SUPPRESS_KEYS`), so there is no reachable non-suppressed combination to test the frontend's "cancelled excluded" behavior against — that exclusion is real and tested on the **backend** only (`slice1_cancelledShipment_notProofOfPacking_packedConfirmedFalse`). (a) and (b) were proven to fail first by temporarily reverting `packedState` to the old `stepState(1)` (both failed exactly as expected); (c) and (d) do **not** discriminate old-vs-new under that same revert — (c)'s `currentIndex` was already ≥2 under the old rule too (an `in_transit` shipment already implied `1 < currentIndex`), and (d) short-circuits on stepper suppression before `packedState` is ever computed — both are still meaningful correctness assertions, just not revert-provable tripwires for this specific change; noted honestly rather than fabricating a failing revert for them. Full backend suite: 1023 tests (1017 + 6 new), 0 failures, 3 skipped (pre-existing). Verified live end-to-end (headless Chromium) in EN/LTR and AR/RTL with a long Arabic and long Latin customer name, across all 4 cases.

---

## Stock-take (FR-21) — Returns-pattern restyle ✅ built 2026-08-17, appearance-only + one additive aggregate endpoint

Restyled `StockTake.tsx` (landing), `StockTakeScan.tsx` (immersive blind loop), `StockTakeReview.tsx` (reconciliation/finalize/sync) to `design/Traced Stock Take Flow v2.dc.html`, following the Returns v2 session pattern. Two-gate Step 0 process before any code: a diagnose-first report on the existing write-off engine (confirmed gated behind `complete_count`/attest, structurally walled off from the increment-only Shopify sync path, never live-verified against a real store), then a second delta-list + analytics-band diagnosis gate before touching anything. The negative-delta write-off engine — `StockTakeReconciliationService.resolveLost()`/`finalizeSession()`, the `complete_count` attest gate, the optimistic-concurrency drift guard, `PieceCommittedException`, `StockTakeShopifyPushJob`, `ShopifyHttpGateway.pushStockTakeWriteOff()` (single-attempt, no-retry, ambiguous-classifying), and the `ledger.transition(...LOST...)` write — is **untouched**: no endpoint signature, gating condition, or payload changed anywhere in that path.

**Four items were explicitly NOT pure restyle and were approved separately before building:**
1. **Scan-loop tally split 4→5 buckets** (`StockTakeScan.tsx`'s `Tally`/`bucketOf()`) — `unexpected_resurfaced` and `out_of_scope` were previously merged into one `unexpected` bucket; now five distinct outcomes (match/mismatch/resurfaced/outOfScope/unknown), each with its own icon/color and its own i18n key (`stocktake.scan.feedback.*`). Uses only data the scan endpoint already returns — no payload change. Still strictly outcomes-only: no denominator, no expected count, no "X of Y" anywhere on this screen.
2. **Wired "Abandon count"** into `StockTakeScan.tsx` — the first UI entry point to `cancelStockTake()` → `POST /sessions/{id}/cancel`, which existed in `api.ts` but was called from zero screens before this. Mirrors Returns' own abandon-link pattern exactly (`text-critical` link → `Modal` confirm → `btn-danger` confirm button).
3. **Finalize-confirm checkbox that actually gates.** "I understand this permanently decrements Shopify inventory" — the Finalize button inside the modal is `disabled={!understood}`, reset to unchecked on every open (proven — see Tests). Per-variant write-off breakdown added to the same modal, reusing `rollup.filter(r => r.variance < 0)` already computed in the file.
4. **Landing variance column for finalized rows.** `StockTakeService.listSessions()`'s SQL already computes `expected`/`counted` for every row regardless of status (only the frontend gated display to `status==='open'` before this) — un-gating it and rendering `counted - expected` for finalized rows is a pure client-side reveal, zero backend change. Cancelled rows render a neutral `—`, never a fabricated variance (a cancelled session was never reconciled).

**New endpoint — `GET /stock-takes/summary`, the landing's one genuinely additive element.** Mirrors `OrderController.summary()`'s pattern (derive-on-read, `@Transactional(readOnly=true)`, `@PreAuthorize("hasAnyRole('OWNER','MANAGER')")`) but uses this feature's own `TenantContext.require()` + parameterized `tenant_id=?` convention rather than Orders' bare-GUC-in-SQL style. `StockTakeService.summary()` → `StockTakeSummaryCounts(countsThisMonth, piecesWrittenOff, avgVariancePercent, openSessions)`:
- `countsThisMonth` — `COUNT(*)` of sessions opened this month, any status.
- `piecesWrittenOff` — `COUNT(*)` of `piece_events` with `event_type='adjusted' AND to_status='lost' AND metadata->>'reason'='stock_take_missing'`, **the exact same predicate `finalizeSession()`'s own delta query uses**, all-time across every session — not a parallel/approximate count.
- `openSessions` — `COUNT(*)` where `status='open'`.
- `avgVariancePercent` — a **ratio-of-sums** (weighted, not a mean of per-session percentages) over **finalized sessions only**: `(Σexpected_on_shelf − Σcounted) / Σexpected_on_shelf × 100`, one indexed query joining `stock_take_expected`/`stock_take_sessions`/`stock_take_scans`. Returns `null` — never a literal `0.0` — when no finalized session exists yet, so "no history" and "counts were perfect" never share a number; the frontend renders a calm "No counts yet" label for `null`.

Frontend: `getStockTakeSummary()` in `api.ts`, rendered via 4× existing `StatCard` (label/value only, no delta/sparkline, matching Orders' own summary-row restraint) above the sessions table, independent non-blocking mount effect (own `.catch(() => {})`, never shares the table's error state).

**Latent gap, logged not fixed:** `stock_take_sessions` has no unique-open-session partial index (unlike Returns' own concurrency referee). The landing's "resume the open count" UI is defensive against this — if more than one open session somehow exists, it resumes the **most recently opened** rather than crashing or picking arbitrarily (tested — `st5`). Candidate hardening slice for later, same bug class the Returns partial-unique-index already prevents; stock-take is higher-consequence given the write-off path downstream.

**Tests — every new/changed behavior proven to fail first, then fixed (prove-by-revert):**
- Backend `StockTakeSummaryTest.java` (new, own Testcontainers harness): `sts1to5` — full mixed fixture (open/finalized/cancelled sessions, one backdated to last month) asserting all four figures at once, including that `countsThisMonth` excludes the backdated session while `piecesWrittenOff`/`avgVariancePercent` (all-time / finalized-only respectively) still include it; `sts4` — zero finalized sessions → `avgVariancePercent` is `null`; `sts6` — cross-tenant isolation, same-tenant positive control over a real `app_user`/RLS connection. Proven to bite: temporarily corrupted the `piecesWrittenOff` predicate (`reason = 'BROKEN_FOR_REVERT_PROOF'`) — both `sts1to5` and `sts6` failed exactly as expected, then reverted.
- `RlsCoverageTest` caught the new endpoint immediately on the first full-suite run — `coverageAudit_allGetEndpointsAreCoveredOrExempt` requires every new `GET` to be registered COVERED (with its own seeded test) or EXEMPT (with a reason); `/api/v1/stock-takes/summary` was neither. Fixed by adding it to `COVERED` plus a new seeded `stockTakeSummary_reflectsSeededSession` test, mirroring the existing `stockTakeSessionsList_returnsSeededSession`/`ordersSummary_reflectsSeededOrders` pattern exactly. This is itself the "proven to bite" evidence — the gap was caught for real, not staged.
- **Full backend suite: 1027 tests, 0 failures, 0 errors, 3 skipped (pre-existing) — BUILD SUCCESS.** Ran twice: the first full run caught the `RlsCoverageTest` gap above; the second, after that one-line fix, was fully green.
- `StockTakeReconciliationTest.srt10` strengthened — now asserts `cancel()` writes **zero** `piece_events` rows and has **zero** interactions with `ShopifyGateway` (new `@MockBean`), not just that the session flips to `cancelled` and the piece status is unchanged. Proven to bite: temporarily added a bogus `piece_events` INSERT inside `cancel()` — the test failed exactly as expected, then reverted.
- Frontend `stocktakeScan.test.tsx`: `sts7` (new) — resurfaced and out-of-scope tally into distinct buckets, not merged. `sts8` (new) — Abandon count opens a confirm modal and, on confirm, calls `cancelStockTake` only, never `scanStockTakePiece` — the UI's first entry point to `cancel()` never bleeds into the write-off path. `sts3`/`sts4` updated for the classification-specific feedback text; `sts5` updated for the icon-only Unscan button (was a text button).
- Frontend `stocktakeReview.test.tsx`: `str3` extended — the Finalize confirm button is `disabled` before the understand-checkbox is ticked, a click on it while disabled does not call `finalizeStockTake`, ticking the checkbox enables it, then the click succeeds. `str5` (new) — re-opening the modal after Cancel resets the checkbox to unticked (no stale "understood" surviving a re-open). Both checkbox-gating assertions proven to bite: temporarily set the button's `disabled` prop to a literal `false` — both `str3` and `str5` failed exactly as expected, then reverted.
- Frontend `stocktake.test.tsx` (new, 9 tests) — mono session column derived from `sessionId` (`st1`), empty/error states (`st2`/`st3`), Resume-CTA + already-open note replacing "+ New Count" when a session is open (`st4`), defensive most-recent-open resolution when more than one open session exists (`st5`), all four analytics tiles render (`st6`), `avgVariancePercent===null` renders "No counts yet" not `0%` — proven to bite by removing the null check and confirming the raw `null%` string rendered instead (`st7`), finalized-row variance vs. cancelled-row dash (`st8`), client-side pagination slicing the already-fetched full array (`st9`).
- Frontend baseline confirmed via `git stash` (tracked stock-take files only — `frontend/node/` and unrelated untracked docs left undisturbed) immediately before restoring: **3 pre-existing failures, 137 passing, 140 total** (matches the documented baseline exactly). After restoring: **3 failures (same 3, unrelated `overview.test.tsx` — `ov2`/`ov5` — plus `blocklist.test.tsx` `fb7`), 149 passing, 152 total** — 12 new tests, all green, zero regressions. `tsc --noEmit` clean. `vite build` clean.

**No product thumbnails** — the mockup and the existing `StockTakeReview.tsx` disposition rows both show text (product/variant title) + mono `PC-…` pills only; `ProductThumb` was not needed here.
