# Progress Journal — Piece-Level Traceability SaaS

---

## Current state

**Issue 2 — transfers sync with Shopify (2026-10-08, branch `feat/transfer-shopify-sync` off main 9b648a6; NOT merged,
NOT deployed). Migration V157. Repair script written + dry-run only (NOT executed).** Approved: model (b) sync by custody;
a FIFTH named decrement `pushTransferOut`; per-location mode 'remove' (default) | 'leave'; no mirroring, no new Shopify
locations — Traced still writes only to the Traced Main Warehouse.
- **V157:** `locations.shopify_sync_mode`, `transfers.shopify_sync_mode` (snapshot at send), `transfer_pieces.from_location_id`
  (recorded at scan-out), `transfer_shopify_syncs` (one claim per transfer: queued/pending/pushed/failed/failed_ambiguous/
  skipped+reason; RLS + policy; app_user no DELETE), trigger type `transfer_return`. MigrationSmokeTest 156,
  NotTracedBackfillTest 101 (renumbered V155 → V157 at merge: analytics slice 6 took V155, the payload fix V156).
- **Send:** `TransferShopifySync.claimSend` in markSent (send-and-back / bring-back) and closeOneWay (permanent move):
  snapshot the governing mode; 'leave' → nothing; pieces that left the MAIN warehouse → one queued claim (−N per
  variant); else 'skipped' with reason (not_from_main_warehouse — every bring-back; main_warehouse_not_linked;
  no_main_warehouse). After commit: queued→pending (one-sender guard), ONE `pushTransferOut` → pushed | failed
  (definitive; `TransferShopifySweepJob` re-sends up to 5) | failed_ambiguous (never re-sent; pending 15 min →
  ambiguous). Ambiguous / exhausted → CRITICAL `void_hold_sync_failed` (trigger_type transfer_out).
- **Return:** reconcile scan back good → `ShopifyInventoryService.onTransferReturn` +1 via the increment path (key
  piece:transfer, retry job) only when `RETURN_COUNTED_SQL` holds: the outbound transfer's claim was pushed for the
  piece, or it left before the tenant's initial seed (the seed counted only main-warehouse pieces).
  `departedAtSql` is the one departure-time definition (pre-V118 send-and-backs have no sent_at → reconcile start /
  creation). Sold / lost / condemned → no write. Cancel only before any scan → nothing to undo.
- **UI:** Settings → Locations "While stock is here: Remove from Shopify / Leave Shopify unchanged" + help line (EN/AR);
  `PUT /api/v1/locations/{id}/shopify-sync-mode` (owner/manager; 409 for the main warehouse).
- **CLAUDE.md:** named decrement set now five (pushTransferOut) + the ISSUE 2 TRANSFER OUT paragraph.
- **Tests:** `TransferShopifySyncTest` (16), `TransferOutClassificationWireTest` (13), `TransferRepairScriptTest` (3, real psql),
  frontend `locationsSyncMode` (2).
  Revert-checked: no claim at markSent → 8 red; no one-sender guard → ts2; ambiguous as failed → ts3; no +1 → ts4/6/9;
  leave ignored → ts5/6; live mode instead of snapshot → ts6; no unlinked skip → ts7; no claim at closeOneWay → ts9;
  sent_at-only departure → ts11; frontend no save → l2. Headless renders EN/AR in the session scratchpad `loc-renders/`.
- **Prod dry run (read-only, 2026-10-08):** ZERO −1 claims to queue. The Snouts' 4 pieces at Warehouse 2 (1
  out_on_transfer on a pre-V118 send-and-back, 3 transferred_out) all left BEFORE its 10-07 seed → seed_excluded (Shopify
  never counted them; each gets +1 on return). Sold/lost report: Snouts DBPINK-3 sold ×1 left before the seed → nothing
  to fix; Onboarding Videos sold ×2 / lost ×4 → linked but not seeded → the seed won't count them. Jumi + demo excluded.
- **Review round (approved 2026-10-09):** linked-but-never-seeded = unlinked (send → skipped `not_seeded`; the +1 rule
  requires an applied seed). `pushTransferOut` classification: definite ONLY HTTP 4xx / userErrors / THROTTLED-only with no data
  (sweep re-sends ≤ 5; THROTTLED approved 2026-10-09); 5xx, any other top-level GraphQL error (THROTTLED mixed or with data), timeouts, connection errors, empty/unreadable bodies and any
  unexpected error → failed_ambiguous, never re-sent. New tests ts12–ts16 + `TransferOutClassificationWireTest` (13),
  each revert-checked. Existing tests edited (approved): WorkerPermissionGuardTest cleanup (+ transfer_shopify_syncs),
  MigrationSmokeTest TENANT_SCOPED_TABLES (+ transfer_shopify_syncs).
**Fix — order imports made stored payloads poorer (2026-10-08, branch `fix/order-payload-downgrade`, merged to main; NOT
deployed). Migration V156.**
- **Cause:** every GraphQL order import (connect / reconnect / OAuth upgrade re-import, reconcile catch-up) re-reads the
  last 30 days (`SHOPIFY_IMPORT_LOOKBACK_DAYS`) and `UPSERT_ORDER` replaced `orders.raw` outright — so each import
  overwrote the REST webhook payloads in its window with a GraphQL node: before Build B (2026-10-06) with no customer /
  address fields at all, and never with refunds / fulfillments / discount allocations / source_name. Prod: The Snouts
  111 orders, Jumi 37 (no other tenant). The ACCESS_DENIED step-down was not involved.
- **Fix (`ShopifySyncService.UPSERT_ORDER_IMPORT`, import paths only — webhooks unchanged):** FRESHNESS — an existing
  order is written only when the incoming `updatedAt` (`shopify_order_updated_at`: REST updated_at / GraphQL updatedAt;
  the orders query now asks for `updatedAt`) is strictly newer than the stored payload's, or the stored one has none;
  otherwise nothing is written (no items, no flags). KEEP CUSTOMER — when it writes, the stored customer / shipping /
  billing / phone groups the node lacks (missing or null, either spelling) survive (`shopify_order_raw_keep_customer`).
- **V156 restore** from each order's latest stored orders/* webhook (never a redacted order, fill-only PII columns,
  idempotent): a GraphQL node whose webhook payload is at least as new → the FULL webhook payload (never poorer in
  customer data); otherwise customer groups only. Prod dry run: Snouts 111 full, Jumi 37 full, 0 customer-only.
- **Impact it repairs (prod 2026-10-08, overwritten orders):** channel lost on all 148 (source_name), discounts on 10,
  refund add-back on 9; the REST fulfillments (tracking) that BostaFulfillmentCatchUpService / BostaVisibilityCheckService
  read from `orders.raw` were missing on 138 (2 Snouts orders with a tracking number and no forward Bosta leg). Real-time
  linking (FulfillmentTrackingCapture) reads the webhook payload and was never affected.
- **Customer id on imports (approved 2026-10-09):** the FULL-tier query now asks for `customer { id … }`, so an
  imported order's `customer_key` is `c:<id>` like a webhook order's (it was `p:<phone>`, splitting one customer in
  two in Analytics → Customers); `ShopifyHttpGatewayOrdersPiiTest.w1` updated (approved).
- Tests: `OrderPayloadDowngradeTest` (9), `OrdersQueryFieldsTest` (1).

**Analytics slice 6 — customers since connect, backend only (2026-10-08, branch `analytics/s6-customers`, merged to
main; NOT deployed). Migration V155 (`orders.customer_created_at`, renumbered from V154 — the other session took it).**
- **Endpoints (`CustomerAnalyticsService`, owner-only):** `/customers/summary?period&compare` (customers who ordered;
  new / existing / returning / unknown; repeat purchase rate = of them, 2+ orders since connect up to the period end;
  median days between orders; orders without a customer; per class orders / booked / realized / success rate —
  `byClass` is the Revenue page's "New vs returning" source), `/customers/top` (≤ 50, realized since connect),
  `/customers/by-governorate` (repeat rate, < 10 customers → "Other"), `/customers/cohorts` (first-order month, % ordering
  again in months 1–3; current month partial, future null), `/customers/watch` (2+ refused COD orders, "Ask for
  prepayment", `blocked` from the blocklist — read-only, matched in SQL with `CustomerSubject.canonicalPhoneSql`).
- **Rules:** identity = `orders.customer_key`, NEVER returned (can hold a phone); `customerRef` = HMAC-SHA256 of tenant +
  key under **`ANALYTICS_REF_SECRET`** (`analytics.ref-secret`, ≥ 32 bytes; the app refuses to start without it —
  **set it in prod `.env` before deploying**; changing it changes every ref). Display name = first name + last initial.
  Connect = the store's analytics floor, else its first ingested order (Jumi). Order class: existing (customer created
  before connect) / new (first order since connect) / unknown (first order, no created date) / returning (later
  orders); a customer counts in the class of their first order in the period.
- **The Snouts' keys (13 of 118 orders) — corrected by fix/order-payload-downgrade:** every GraphQL re-import re-reads
  the last 30 days (`shopify.import.lookback-days`) and `UPSERT_ORDER` replaced raw, so each import overwrote the REST
  webhook payloads (customer + phone) in its window. The 99 oldest (≤ 2026-09-06) were last written by imports BEFORE
  Build B, whose orders query asked for no customer / address fields at all; the 2026-10-07 OAuth-upgrade import only
  re-read orders after 2026-09-07 (12, at full tier). Not the ACCESS_DENIED step-down. Fixed and restored by V156.
- Tests: `AnalyticsCustomersTest` (10), RlsCoverageTest +5.

**Issue 1 — Scan returns: untracked parcel items, one row per unit (2026-10-08, branch `feat/untracked-parcel-units` off
main 591c915; NOT merged, NOT deployed). Migration V154.** Design signed off 2026-10-08 (`design/returns-parcel-states`
3 / 3b / 3c / 6 / 8, committed separately as 96b68bd with the .v1 originals and renders).
- **Decisions (signed off):** whole-parcel "Mark parcel received" = fallback only while no unit is marked; one Return To
  Receive per sellable unit (key `unit:<intake id>`, Undo removes it); partial returns: a parcel is HANDLED once a unit is
  marked (pill "2 of 3 in" neutral vs "All 3 in"), never blocks closing, leg stamped `untracked_units_arrived`, Undo of the
  last mark reopens it; returned-to-sender = forward leg `returned` + Bosta type Return to Origin (Snouts AWB 445040939
  matches; all 197 returned forward legs in prod are RTO, both raw shapes carry type.value).
- **V154:** `untracked_unit_intakes` (RLS + FORCE + tenant_isolation in the same migration; live-slot partial UNIQUE;
  app_user INSERT/SELECT + UPDATE of undone_at/undone_by only); `return_session_shipments.via_phone`; outcome CHECK +
  `untracked_units_arrived`. MigrationSmokeTest 153, NotTracedBackfillTest 98 (renumbered V152 → V154 at merge: analytics slice 4 took V152/V153).
- **Backend:** `UntrackedParcelUnits` (not a bean; ReturnSessionService builds it from its JdbcTemplate) — unit rows from
  `PortalService.LINE_UNTRACKED_SQL` / `UNTRACKED_CAP_SQL` (made public, single definition), mark (ON CONFLICT on the live
  slot; double tap = no-op), undo, stamping through `returnLegScanEvidenceSql`. `ShipmentLinkService`: new
  untracked-unit evidence clause, `returnedToSenderSql`, `UNIT_TO_RECEIVE_OPEN_SQL`; `RETURN_TO_RECEIVE_OPEN_SQL` also
  covers returned-to-sender legs. `ReturnSessionService`: markReceived/undo allow RTO forward legs, refuse once a unit
  is marked; endpoints `POST /returns/sessions/{sid}/parcels/{shipmentId}/units/arrived[/undo]`; parcel view adds
  `untrackedUnits` / `unitsIn` / `canMarkReceived` / `returnedToSender` / Bosta `state` + `rtoSince` (RTO legs are never
  a blank card); `scanAwb` records a verified phone scan (sticky). ExceptionService: per-unit `return_to_receive` branch +
  EN/AR text. ReturnCaseRules / case C: `untracked_units_arrived` like `request_items_arrived`; add_in_receiving when a
  unit's Return To Receive is open.
- **Frontend:** `Returns.tsx` — `UntrackedUnitRow` (Arrived · sellable / damaged, outcome + Undo for this session's marks,
  via-phone tag), pill / collapsed summary, guidance box (untracked / mixed / returned-to-sender), whole-parcel fallback,
  Bosta status strip; a parcel with units marked in this session stays expanded (it would otherwise collapse after the
  first mark). EN + AR `returns.openSession.parcel.units.*`.
- **Tests:** backend `UntrackedParcelUnitsTest` (10: p1–p8 + x1 app_user isolation with positive control + g1 app_user
  grants — INSERT and the two undo columns allowed, any other UPDATE and DELETE refused; p8 = a parcel from a CLOSED
  session rescanned in a new one, the prod case of Snouts AWB 445040939; g1 revert-checked: no REVOKE/GRANT → red); frontend
  `untrackedParcelUnits.test.tsx` (5, userEvent, incl. AR). Revert-checked: no unit rows → all red; leg gate → p2/p6;
  evidence clause → p3/p4/p5; live slot non-unique → all marking tests; per-unit exception → p3; frontend units → 5/5 red.
  Real-screen headless renders EN + AR (throwaway harness, deleted) in the session scratchpad `real-renders/`.
  Full backend 2564 tests, only red ExchangeBackfillTest (known); vitest 769/769; tsc + vite build clean.
- `MigrationSmokeTest.TENANT_SCOPED_TABLES` + `untracked_unit_intakes` (approved). Unit rows only on return legs and
  returned-to-sender forward legs.
**Analytics slice 4 — stock, backend only (2026-10-08, branch `analytics/s4-stock`, merged to main; NOT deployed).
Migrations V154 (analytics_settings) and V153 (variants Shopify stock columns).** Owner-only; tenants with no pieces
get `hasPieces: false` (trust level "none"); voided pieces never count.
- **Endpoints (`StockAnalyticsService`):** `/stock/summary?period` (in warehouse by location, value at price and at
  cost — costed variants only, null when none —, avg days in stock, 0–30/31–60/61–90/90+ buckets, on hold, damaged at
  cost, lost/destroyed this period at cost, pieces moved 4+ times, `trust`, `lowTrust`), `/stock/variants?sort&filter
  &limit` (on hand, `shopifyAvailable`, `stockUsed` + `stockSource`, coming back, velocity, cover, sell-through, avg
  piece age, last sale, returns + exchanges rate (90 d) + top reason, running low ≤ 7 d, dead stock no sale 60 d with
  cash at cost else price), `/stock/restock` (velocity × (lead + cover) − stock − coming back, rounded up, ≥ 0),
  `GET/PUT /settings` (V154 analytics_settings: supplier_lead_days 0–365 default 21, cover_days 1–365 default 35; RLS;
  app_user can't DELETE; PUT audited), `/pieces/{id}/history` (trips with order, AWB, city, s2 outcome, fee),
  `/variants/{id}/pieces?minTrips`.
- **Definitions:** velocity = delivered units (s2 outcome, orders placed in the last 30 days) ÷ 30; received =
  pieces.created_at; coming back = return_in_transit + return_pending_inspection; trip = piece event from packed /
  awaiting_pickup / reserved into with_courier / delivered (leg = the event's shipment, else the order's forward leg
  booked before it; no leg + delivered = self-pickup).
- **Stock trust (`StockAnalyticsService.trust`):** packedThroughTracedPct = Bosta-delivered orders (last 30 days) with
  a piece allocation or piece event ÷ all of them; the Shopify figure = `variants.shopify_inventory_quantity` (V153:
  REST `inventory_quantity`, all locations, only as fresh as the variant's last products/* webhook —
  `shopify_variant_updated_at`; GraphQL-imported variants have none); mismatch = Σ|traced available − max(Shopify, 0)|
  ÷ Shopify units over variants with pieces and a figure. high = ≥ 80 % packed AND mismatch ≤ 10 %; else low. **At low
  trust** cover / running low / dead stock / sell-through / restock / sells-out-soon use the Shopify figure
  (`stockSource: "shopify"`; a variant without one falls back to pieces and says so); piece-only figures stay on pieces,
  flagged `lowTrust`. Prod 2026-10-08: every piece tenant is LOW (Snouts 67 % packed of 3 deliveries, mismatch 31 %;
  Jumi 4 %, mismatch 16×; BROEK 0 % of 223).
- **Sells-out-soon alert** (s7, upgraded): best sellers (top 20 by velocity) with stock and ≤ 4 days of cover, in `skus`.
- Shared: `SalesAnalyticsService.LAST_SOLD_CTE`, `ProductExtrasAnalyticsService.variantRows` (goldens unchanged).
- Tests: `AnalyticsStockTest` (11), `StockRulesTest` (3), RlsCoverageTest +6.

**Analytics B1 — small backend fixes (2026-10-08, branch `analytics/b1-fixes`, merged to main; NOT deployed).
Migration V150.**
- **`GET /api/v1/analytics/sales/variants/daily?ids=…&period`** (≤ 20 ids, owner-only): sold units per Cairo day (the
  slice-1 sold-line rule), dense series, ids in the order asked, unknown / other-tenant ids → zeros. For the Top SKUs
  sparklines and the SKU drawer chart.
- **Order list `q`** also matches the customer DISPLAY name (first name + last initial) — never the stored full name.
- **Payout lag — ONE definition (`SettlementSql.medianPayoutLag` / `payoutLagLeg`):** median of payout day − Cairo
  delivery day over paid forward legs, post-floor. `/money/fees` (window: payout day in the period) and the cash
  forecast (payout in the last 90 days) both use it; /money/fees used the average deposit → paid before. Prod (30 d):
  BROEK 3.3 → 4 days, Snouts 11.1 → 6 days.
- **Zero cash cycle (`SettlementSql.zeroCycle`):** Bosta settled the leg for exactly 0 (fee-free RTO, COD = fees) and no
  cashout names it — nothing is owed and Bosta never sends a cashout (prod 2026-10-08: 9 of 10 zero cycles had none;
  every negative deposit got one). Never 'unresolved', never delivered-not-paid / awaiting payout; the refresh job
  stops re-reading it; on the order list a DELIVERED zero cycle is paid at 0 — the outcome still wins (a refused one
  stays lost). **V150** puts zero cycles already marked 'unresolved' back to 'deposited' (prod: the 2 Snouts RTOs
  2360263820, 445040939).
- Tests: `AnalyticsB1Test` (8); approved edit to `AnalyticsMoneyTest` (payout lag 8.0 → 4.0, delivered → paid).

**Fix — returns restock → Shopify + inventory location selector (2026-10-08, branch `fix/returns-restock-location-stock`
off main ec55e09; two commits; NOT merged, NOT deployed). Migration V151. Repair script written + dry-run only (NOT executed).**
From the same-day 4-issue diagnosis (Issues 1 and 2 — untracked RTO parcels in Scan returns, transfers → Shopify — still
await Marawan's design / invariant decisions).
- **Issue 3 root cause:** `Returns.tsx` sent `locationId: null` and `ReturnService.restock` wrote it into
  `pieces.current_location_id` — every restocked piece lost its location (invisible to stock counts and the seed) and its
  +1 was silently skipped (`isFulfillmentLocation`, info log, no row). Zero `return_inspection` rows in prod ever.
- **3a:** restock resolves a null location to the tenant's main warehouse BEFORE the transition; none → 409
  NO_MAIN_WAREHOUSE (logged ERROR), piece untouched. A named location must be the tenant's. Frontend sends no location.
  A restock into a non-main location is now a recorded `skipped_not_fulfillment_location` row + WARN.
- **3b:** restock claim key = `piece_id:restock_event_id` (uuid generated in restock, carried in the `restocked` event's
  metadata with the order id; the async processor reads the piece's newest restocked event). A piece with no such event
  keeps the bare piece_id (pre-V151 shape) — direct calls on fixture pieces behave as before.
- **3c guard:** `shopify_refund_restocked_units(raw, variant_gid)` (V151, the ONE definition: Σ refund_line_items quantity
  with restock_type return / legacy_restock for that variant). Per (order, variant): skip with
  `skipped_shopify_restocked` while Shopify units > Traced restock claims already counted for that order+variant
  (`source_order_id`, V151 column) — decided and claimed in ONE transaction under a per (tenant, order, variant) advisory
  lock (`claim()` split into `claimInCurrentTx`). Late case: detector `restocked_twice` (HIGH, read-only, no decrement):
  LEAST(applied, shopify − skipped) > 0, qty in the subject key; label + EN/AR description, action → the order.
- **V151:** statuses skipped_shopify_restocked / skipped_not_fulfillment_location; `source_order_id` (FK, ON DELETE SET
  NULL) + partial index; CHECK on the restock trigger_id shape; the guard function. MigrationSmokeTest 150,
  NotTracedBackfillTest 95. (Renumbered V150 → V151 at merge: analytics B1 took V150 on origin first.)
- **Repair script** `scripts/ops/2026-10-08-restock-null-location-repair.sql` (psql as postgres, dry run unless
  `-v commit=yes`): Group A (in-warehouse statuses) → main warehouse with a `location_corrected` piece event (actor NULL,
  from = to; LookupService phrase + EN/AR); Group B listed only; restocks at/after the main warehouse's Shopify link →
  guard → `skipped_shopify_restocked` row or a `failed`/never_sent claim due now that the existing increment retry job
  sends (the script never calls Shopify); seed shortfall report only. Idempotent (deterministic trigger_id). Tested
  through real psql (`RestockRepairScriptTest`). **Prod dry run (read-only SQL, 2026-10-08):** Group A 12 (Snouts 9, Jumi
  3), Group B 5 (Snouts 4, Jumi 1), +1 to queue 2 (Snouts, both 2026-10-08, guard → push), seed shortfall 6 units
  (Snouts: 1000-YELLOW-S 1, DBWHITE-3 2, DBWHITE-4 1, SBPINK-2 1, SBPINK-3 1). Run order: deploy V151 first.
- **Issue 4 root cause / fix:** stock + drawer counted only `available` at a location; transfer destinations hold
  `out_on_transfer` / `transferred_out`. A non-main location now counts the three (`AT_OTHER_LOCATION_STATUSES_SQL`) as
  "At location", available null (shown "—"); main warehouse and All locations unchanged.
- **Tests:** ReturnRestockSyncTest (6), RestockRepairScriptTest (3), InventoryLocationStockTest (2); each revert-checked.
  Existing tests changed (approved 2026-10-08): returnsParcelCards pc6 (body without locationId), WorkerPermissionGuardTest
  cleanup (+ shopify_inventory_adjustments), ExchangeDispatchDecrementTest awaitTriggerCount / sumAppliedDeltas and
  PortalExchangeBookingTest awaitTrigger / e3 sum (type-filtered, split_part on the restock key; counts unchanged).
  Full backend run before the test edits: 2523 tests, reds = ExchangeBackfillTest (known) + exactly those 3.
- **Next:** Marawan runs the repair (`-v commit=yes`) after V151 is deployed; reconcile the 6-unit Snouts seed shortfall
  by hand in Shopify.
**Analytics slice 7 — order finances, Summary alerts, cash forecast, backend only (2026-10-08, branch
`analytics/s7-finances`, merged to main; NOT deployed). No migration.** Owner-only, same period / floor / RLS rules,
V148/V149 columns only (no raw parsing).
- **`GET /api/v1/analytics/orders`** (`OrderFinanceService`, on OrderFacts' CTEs — the s5 cohort): one row per order —
  name, placed, customer display name (first name + last initial, never more), governorate EN/AR, items, total (booked),
  payment group, delivery status (outcome + leg state label), financial status, Bosta fees (+ estimated), net to you,
  refundAmountUnknown, tracking numbers. Filters status / governorate / variantId / q (order name or tracking number);
  `counts` per status for the chips (same filters except status); placed_at DESC, id DESC; page (0-based) / size ≤ 200.
- **Financial status — ONE rule, `OrderFinanceService.financialStatus`, first match wins:** other_carrier (Wijha) →
  lost (refused / other_terminal, net = −fees) → refunded (customer return on a delivered order; net = collected − fees
  − refunds in the ledger net of voids, else null + refundAmountUnknown) → paid (delivered and a cashout txn or
  prepaid; net = deposited_amt / total − fees) → overdue (s3 delivered-not-paid, or in transit with no change for 7
  days) → awaiting_payout → expected. **Prepaid (approved 2026-10-08): the deciding Bosta forward leg's COD is 0 —
  Bosta is the truth; only with no Bosta leg (or its COD unknown) the payment group decides (Card only).**
  Bosta fees = Σ SettlementSql.fee over the order's Bosta legs, skipping legs cancelled / terminated before pickup.
- **`/orders/export.csv`**: same filters, newest first, UTF-8 + BOM, formula guard ('= + - @'), cap
  `analytics.orders.export-max-rows` (default 50,000; more → newest 50k + `X-Export-Truncated: true`). One audit_log
  row `analytics_orders_export` (filters, q as true/false, row count, truncated — never row data), written in the SAME
  read-write transaction as the read (`OrderFinanceService.export`) — outside a transaction app_user's RLS WITH CHECK
  refuses it (found on the app_user bench; postgres-connected tests can't see it; reflection guard test).
- **`/variants/{id}/orders`**: the variant's 10 newest orders (window = the 10th's Cairo day → today), same status.
- **`/alerts`** (period for the governorate line): stuck with Bosta / never picked up / delivered not paid (counts +
  COD / deposited sums from `MoneyAnalyticsService.stuck()`), governorates with success < 65 % and ≥ 10 orders
  (Unknown excluded; amount = failed orders' booked), sells-out-soon (only when the tenant has pieces: available > 0 and
  ≤ 7 days at the last 30 days' rate; amount null — s4 replaces it). Links are proposed frontend paths (`/analytics/…`).
- **`/cash-forecast`**: null + reason `payout_cadence_unknown` / `payout_lag_unknown`; else next 7 / 8–14 / 15–30 days
  (+ later): deposited → next payout weekday; delivered not settled → first payout weekday ≥ delivered + median
  delivered→paid lag (paid forward legs, 90 days); in transit (the pipeline's stage × city rates) → first payout
  weekday ≥ today + lag. Method inputs returned.
- **Shared rules lifted (goldens unchanged):** `SettlementSql.deliveredNotPaid / stuckWithBosta / neverPickedUp /
  lastChange`, `MoneyAnalyticsService.IN_TRANSIT_LEGS / AWAITING_DEPOSITED / AWAITING_UNSETTLED / inTransit()`.
- **Bench (60k orders, prod settings, warm p95):** orders 30-day page 151–156 ms (366 d ~1.7 s), export 157 ms /
  1.7 s, alerts 539 ms / 1.7 s, cash-forecast 115 ms, SKU drawer 440 ms.

**Analytics slice 8 — performance, backend only (2026-10-08, branch `analytics/s8-perf`, merged to main; NOT deployed).
Migration V149.**
- **V149** moves the raw parsing analytics did per request into STORED generated columns over IMMUTABLE, PARALLEL SAFE,
  total functions (no plpgsql EXCEPTION blocks — they open a subtransaction per call and break parallel plans; casts are
  regex- or `pg_input_is_valid`-guarded, so a generated column can never fail an ingest write). orders: raw_cancelled,
  is_cancelled, source_name, channel, payment_group, customer_key, ship_province, shopify_fulfilled, discount_types /
  labels / codes, refund_lines; order_items: line_key, unit_price, price_is_raw, original_qty, current_qty,
  line_discount, net_unit_price, alloc_amounts, alloc_indexes; shipments: type_code, state_value, city_id, city_name,
  raw_cod, collected_from_business_at, last_failure_category; products: product_type_norm, size_position; variants:
  option1–3. Indexes: shipments_tenant_delivered_at_idx, shipments_terminal_unstamped_idx, shipments_tenant_open_idx.
  Generated columns compute after BEFORE triggers, so V143 redaction / V144 email strip apply first (redaction clears
  customer_key and ship_province). The SQL mapping functions are twins of `AnalyticsMappings`;
  `AnalyticsSqlParityTest` asserts identical output, malformed payloads, redaction, and that no EXCEPTION block exists.
- **Queries rewritten on the columns, byte-identical output:** `AnalyticsGoldenTest` (frozen clock 2026-10-08 12:00
  Cairo, fixed ids) compares every analytics endpoint's raw body against `src/test/resources/analytics-golden/`
  (re-record with `-Dgolden.record=true`; a mismatch writes target/golden-actual/). Clock-bound SQL (money city-rate
  90-day window, payout weekday) takes `now` from the service Clock.
- **Long ranges skip the previous period:** a period > 92 days returns `previous: null` / `previousRange: null` unless
  `compare=true` (all comparing endpoints: revenue summary / breakdown / discounts / heatmap, delivery summary /
  failure-reasons, products/extras). `AnalyticsSql.previousOrNull`.
- **Outcome correction (shared rule, applies to s2/s3/s5):** a deciding forward leg cancelled / terminated BEFORE
  pickup (no collected_from_business_at and no with_courier / returning / returned / delivered / lost history) =
  not_shipped (wijha by the carrier rule); after pickup = other_terminal (failed). Goldens re-recorded deliberately: every
  change comes from two seed orders moving other_terminal → not_shipped.
- **Benchmark** (`AnalyticsBenchmark`, skipped unless `-Dbench.url`; `bench/seed.sql` = 60k orders / 120k lines / ~57k
  shipments; app as app_user; container with prod flags jit=off, work_mem=2184kB, shared_buffers=224MB): every 30-day
  endpoint < 400 ms warm p95. 366 days (no compare), warm p95 ms: sales/variants 1272, products 437, cities 770, money
  ≤ 457, revenue summary / breakdowns / heatmap 1260–1373, discounts **1866** (the one miss vs 1.5 s — the fixture
  discounts nearly every order), delivery 1371 / 1414, extras 1393.
- **No rollup table now.** Plan if a tenant nears ~3k orders/month: a per-order facts table (one row per order with
  outcome, money, channel, governorate, …) refreshed on order / shipment writes, read by OrderFacts instead of
  recomputing soldLines + ORDER_OUTCOMES per request.
- **Deploy checklist:** V149 rewrites orders / order_items / shipments / products / variants under ACCESS EXCLUSIVE locks
  (50 s on the 60k bench; prod estimate a few seconds) — **deploy in a quiet hour**. Rebuild with `--no-cache`.

**Fix — embedded token exchange stored a NON-expiring token (2026-10-08, branch `fix/embedded-expiring-token` off main
63308bb; merged to main; NOT pushed, NOT deployed). No migration.** Prod: embedded signup `test-oaozdwro` got a
non-expiring offline token → import 403 "Non-expiring access tokens are no longer accepted".
- **Root cause:** `ShopifyHttpGateway.exchangeSessionToken` sent `requested_token_type=offline` but no `expiring`;
  Shopify defaults `expiring=0` → non-expiring token, no refresh token. The parser then defaulted `expires_in` to 3600,
  so the row looked like a 1-hour token (access expiry set, refresh NULL). Every session-token exchange was affected:
  embedded signup, pending link, and the embedded open's refresh (which could also have OVERWRITTEN a good pair).
  `exchangeCode` (OAuth callback) always sent `expiring=1`.
- **Fix:** `expiring: "1"` on the session-token exchange; both exchanges parse through one `expiringOfflineToken`
  (no defaults). `ShopifyStoredToken` (new) is the ONE token → columns path (encrypt + both expiries) used by every
  writer — insertStore (now delegates to insertStoreInCurrentTransaction), updateStoreToken (checked BEFORE the legacy
  webhook cleanup), applyExchangedToken, embedded signup, pending links — and refuses a token without refresh token /
  expiries (`ShopifyNonExpiringTokenException`, 502 SHOPIFY_TOKEN_NOT_EXPIRING, logged ERROR) before anything is written.
- **Repair:** `acquireOrRefreshViaSessionToken` treats an OAuth row with no refresh token as never fresh — the next
  embedded open re-exchanges (expiring pair stored) and re-enqueues import + webhooks. Prod affected (2026-10-08):
  only `test-oaozdwro.myshopify.com` (tenant "testfromshop", connected, import failed); no live pending links.
- **Tests:** `ShopifyTokenRequestTest` (3, the wire request), `EmbeddedOnboardingTest` e1–e4 (expiring pair stored,
  non-expiring refused + nothing saved, no downgrade, repair). Revert-checked: no expiring=1 → t1; storage without the
  check → e2 + e3; no repair trigger → e4. Existing test changed (approved): ShopifyTokenExchangeTest.te01 gives its
  "fresh" store a refresh token (its fixture was exactly the broken state the repair now re-exchanges).
- **Follow-up (not built):** Shopify's one-shot server-side migration (token exchange with the non-expiring offline
  token as subject_token, expiring=1) could repair without the merchant opening the app — but it permanently retires the
  old token and the docs say new public apps can't use non-expiring tokens at all, so it may not apply to us.

**Build D — onboarding inside the embedded Shopify app (2026-10-07, branch `feat/embedded-onboarding` off main 8862521;
merged to main; NOT pushed, NOT deployed). Migration V147.** Mockup: `design/Traced_embedded_onboarding_dc.html` (amended: L1 = pending
link + top-level navigation, L2 dropped). Built with Polaris like the rest of the embedded app.
- **Filter:** `/api/v1/embedded/onboarding/**` only → tenantless `SHOPIFY_ONBOARDING` principal (shop from the verified
  dest claim); every other `/embedded/**` path unchanged. Controllers in `com.traceability.onboarding` (the `embedded`
  package stays read-only).
- **Signup** (`EmbeddedOnboardingService.signup`): rate limit → shop unlinked → `AuthService.validateSignup` → token
  exchange → ONE transaction (`AuthService.createAccount` / `AuthRepository.createTenantWithOwnerAnd`: tenant + owner +
  Main Warehouse + attribution utm_source=shopify_app_store + stores row via
  `ShopifyOAuthService.insertStoreInCurrentTransaction`, oauth, ingest floor now) → after commit import + webhooks +
  welcome email; response carries the 10-minute one-time sign-in link (`MagicLinkService.issueSignInLink`, only caller).
  Prefill = shop name + contact email (`fetchShop`). 409 EMAIL_TAKEN (web signup wording) / SHOP_LINKED_ELSEWHERE.
- **Existing account = pending link** (amendment): `POST /embedded/onboarding/pending-link` → `shopify_pending_links`
  (nonce hash, encrypted tokens, 15 min, single use) → `window.open(url, '_top')` → `/connect/shopify?link=` (new
  `ConnectShopify` page, RequireAuth) → owner previews / confirms (`/api/v1/shopify/pending-link/{preview,confirm}`, body)
  → same-shop + one-tenant-per-shop checked before the link is used → `linkPendingShop` → back to
  `admin.shopify.com/store/<handle>/apps/<handle>?traced_connected=1` → embedded C1. No status endpoint, no polling.
- **Removed:** `provisionNewTenant`, `LinkOutcome.PROVISIONED`, `/connect/setup-pending` branch,
  `UPDATE_PROVISION_REFRESH_FIELDS`, `USERS_EMAIL_CONSTRAINT`, codes SHOPIFY_SHOP_EMAIL_MISSING /
  SHOPIFY_EMAIL_ALREADY_REGISTERED (+ the SHOPIFY_SHOP_EMAIL_MISSING locale key), MagicLinkService dependency of the
  OAuth service, `NotLinked` + `notLinkedCopy.ts`, test `ShopifyMagicLinkTest.provisionWiring_path2NewInstall` + its 3
  helpers. Kept (still tested): `issueMagicLink` / `EmailGateway.sendMagicLink` (no app caller now).
- **Review round (approved 2026-10-07), also in V147:** `provision_tenant_from_shopify` (hatch #5) DROPPED — its
  direct tests went with it (EmailUniquenessProvisionTest eu1 deleted, setup rewritten with plain inserts;
  ShopifyOAuthDay2Test.provisioningAtomicity deleted). Pending links: app_user may UPDATE only consumed_at /
  consumed_by_tenant / the two token columns, the consume nulls both tokens in the same statement (CHECK enforces it).
  **Hatch #16 `purge_onboarding_artifacts()`** (no parameters): deletes expired-or-consumed pending links and rate-limit
  rows older than 24 h; `OnboardingPurgeJob` runs it nightly 03:30 Cairo; app_user has no DELETE on either table (the
  rate limiter's own cleanup DELETE was removed). Recorded in CLAUDE.md and blueprint §16.1.
- **Bug fixed on the way:** `/auth/magic` never worked on app_user — `MagicLinkService` read the role (and the email)
  outside a transaction, so no tenant GUC → RLS hid the user → MAGIC_LINK_INVALID. Now inside `tx.execute`.
- **Gotcha:** the revert-check script restoring a file with an OLDER mtime leaves Maven's mutant classes in place —
  always `touch` restored sources.
- **Tests:** `EmbeddedOnboardingTest` (15, whole app on app_user), `embeddedOnboarding.test.tsx` (19, EN + AR),
  `connectShopify.test.tsx` (12, EN + AR). Approved existing-test changes: embeddedNotLinked (4 old-screen tests
  deleted), embeddedTabs (welcome title), reviewerConnectPath (3 deep-link tests deleted), MigrationSmokeTest (146),
  NotTracedBackfillTest (+V147), RlsCoverageTest (prefill exempt), plus the two provision-function tests above.
- **Follow-ups (logged, not built):**
  - `/auth/signup` answers a taken email with 400 CONSTRAINT_VIOLATION, not 409 — the web signup's "email taken"
    message never shows (fixing it changes WelcomeEmailTest's 400 assertion).
  - nginx access logs may record `?link=` / `?token=` query strings: strip query strings from the access log for
    `/connect/shopify` and `/auth/magic`.
  - "Install from the Shopify App Store" button for NEW merchants (Settings card → listing; after install the merchant
    picks "I already have a Traced account" → pending link); reconnect via pending link (allow it when the shop's own
    tenant has it disconnected + a Reconnect button on the embedded Disconnected screen); upgrade-on-install for
    custom-app stores (the embedded token refresh skips custom-app rows today — would run Build C's cleanup + swap,
    gated per store; decide whether opening the official app counts as consent).

**"Find your store" — connect via the official app without knowing the .myshopify.com address (2026-10-07, branch
`feat/shopify-store-finder` off main 77aa474; merged to main; not pushed, not deployed). No migration.** Signed-off mockup:
`design/Traced_shopify_connect_wizard_dc.html` (website-address lookup dropped for v1; no "check couldn't run" state).
- **Backend:** `ShopDomainNormalizer` (`:47`) — the ONE place a typed address becomes `<handle>.myshopify.com`
  (lowercase; scheme, capitals, whitespace, invisible chars, trailing dot/slash, path; admin links
  `admin.shopify.com/store/<handle>/…` and legacy `<handle>.myshopify.com/admin…`; credentials / port / anything else →
  NOT_SHOPIFY_ADDRESS); no network. `/oauth/initiate` normalises first (`ShopifyOAuthController:74`) — fixes the capitals
  bug (a capitalised shop used to pass the regex and fail the callback's shop match). New owner-only
  `POST /api/v1/shopify/resolve-store {input}` → `{shopDomain, source: myshopify|admin_link}` (`:88`), pure
  normalisation. `ShopifyOAuthService.initiateChecked` (`:220`, used only by the HTTP initiate): same-shop rule →
  store typo check → `initiateOAuth` (unchanged; `TenantContextRestoreTrapsTest` still calls it directly, offline).
  Store check `ShopifyStoreExistence` (`:40`), reached via `ShopifyGateway.checkStoreExists` (default / mock null =
  inconclusive): GET `https://<handle>.myshopify.com/meta.json`, URL built only from a normalised domain (refused before
  any I/O otherwise), https only, no redirects, 3 s timeouts, body discarded. Only a 404 blocks → 422 STORE_NOT_FOUND;
  2xx/3xx, any other status, timeout, network error → through. Probed 2026-10-07: a non-existent handle → 404,
  real stores → 200. New `ShopifyOAuthException` codes NOT_SHOPIFY_ADDRESS, STORE_NOT_FOUND (EN + AR messages).
  RlsCoverageTest unchanged: it audits GET endpoints only and resolve-store is a POST reading no tenant data.
- **Frontend:** never-connected tenant → `StoreFinder` (input with live recognition from `storeAddress.ts` — a preview of
  the normaliser — format chips, "Find my store" → resolve → "We found your store" confirm, "That's not my store" keeps
  the text, "Connect this store" → initiate; errors: not an address / not a Shopify address (by whether the text looks
  like a domain) / store not found (+ "Change the address") / backend message as-is). Linked shop → reconnect: no input,
  one "Connect with Shopify" running initiate on the linked shop (same check). `StoreFinderGuide`: 3 steps (open admin,
  copy the address bar, paste) + "On your phone?" Settings › Domains. `WizardStep.tsx` (WizardHeader, StepBody) extracted
  from SetupWizard, markup unchanged. EN + AR for every string (`connections.shopify.finder.*`, `.guide.*`).
- **Tests:** ShopDomainNormalizerTest (39), ShopifyStoreExistenceTest (30), StoreFinderTest s1–s5 (HTTP; revert-checked:
  no normalising → s2/s5, not-found ignored → s4), storeFinder.test.tsx (9). Existing tests changed (approved):
  reviewerConnectPath (new input label), shopifyCallbackError + shopifyDisconnect (new placeholder), shopifyLinkedShop
  f1 (reconnect: notice + button, no input) and f2 (reconnect calls initiate with the linked shop; backend error shown).
- **Suites (2026-10-07, after memory was freed):** backend 2,372 run — reds = the baseline two (ExchangeBackfillTest,
  ShopifyMagicLinkTest). The first full run also had `BostaPollJobTest.p6` red (Bosta status-poll test, untouched by
  this build): it passed alone twice (21/21) and in the second full run — a timing flake. Frontend 104 files / 740 tests
  green. (Earlier runs on an out-of-memory machine stalled; no failures there.)
**Analytics slice 5 — revenue & delivery breakdowns, backend only (2026-10-08, branch `analytics/s5-breakdowns`, merged
to main; NOT deployed). No migration.** Owner-only; every response is `{range, previousRange, current, previous}`
(previous = same length, right before).
- `/api/v1/analytics/revenue/summary` (gross → code / automatic / other discounts → booked → realized; waterfall gross −
  discounts − in transit − not shipped − other carrier (Wijha) − refused − other terminal − returns = net realized, exact
  to the cent because money is rounded PER ORDER; funnel ordered → fulfilled (Shopify fulfillment, Bosta collected, or
  delivered/refused) → delivered → paid to you (s3 cashout); booked + realized per Cairo day), `/revenue/breakdown?by=
  channel|payment|governorate|productType`, `/revenue/discounts` (per code, case-insensitive, + one "Automatic
  discounts" row), `/revenue/heatmap` (avg orders per Cairo weekday × hour), `/delivery/summary` (success, lost sales,
  order→handed (collectedFromBusiness, else first with_courier) and handed→delivered hours with a Cairo/Giza split,
  8-week trend by placed week, same-day / 1 / 2 / 3+ day buckets), `/delivery/failure-reasons` (6 groups + coverage),
  `/products/extras` (ABC 80/95 — the variant crossing 80 % is A; most-failed ≥ 20 orders; size curve with returns and
  exchanges, unparseable sizes excluded and counted; bought together ≥ 3 orders, top 10).
- **Mapping tables** are Java (`AnalyticsMappings`, unit-tested): channel = draft order → Manual / DM; no source fields
  (GraphQL-imported) → Unknown; referrer host BEFORE utm_source (stores put campaign names in utm_source); truncated Meta
  utm "fa/fac/faceb…" → Facebook; the store's own domain → Direct. Payment COD / Card (Paymob, Kashier, card) / Manual /
  Mixed / Other (gift cards are Other). Governorate = Bosta city of the deciding leg, else Shopify province → Bosta city
  (lowest city id per name), else Unknown.
- **One success-rate definition everywhere (approved 2026-10-08):** successRate = delivered ÷ (delivered + failed),
  refusalRate = refused ÷ (delivered + failed), failed = refused + other_terminal (lost / terminated / cancelled deciding
  leg). The rule is `SalesAnalyticsService.LEG_OUTCOME_WHENS`, shared by ORDER_OUTCOMES and the s3 pipeline's city rates
  (now per order's deciding leg, orders placed in the last 90 days). s2 refusalRate / cities successRate and s5
  failureRate changed accordingly.
- **Performance:** `OrderFacts` is one per-order query (built on soldLines + ORDER_OUTCOMES + LINE_RETURNS, money
  aggregated straight from line_facts) run ONCE per request over previous + current period (+ trend weeks), split in
  Java. Prod Femine (1,130 orders, 60-day window): 2.1–2.6 s; the remaining cost is soldLines decompressing orders.raw
  per line (slice 8).

**Analytics slice 3 — Bosta money, backend only (2026-10-07, branch `analytics/s3-money`, merged to main;
NOT deployed). Migration V148 (renumbered from V147 on 2026-10-08: Build D took V147).**
- **V148** adds Bosta's per-delivery settlement to `shipments` (wallet.cashCycle + cashout): deposited_at/_amt,
  cod_settled, bosta_fees, shipping_fees, vat, opening_package / collection / insurance / flex_ship fees,
  promotion_discount, shipment_fees_quoted (raw.shipmentFees, the pre-VAT quote), cash_cycle_id, cashout_txn_id,
  cashout_date (transaction_date, else the txn id's DDMONYY: WEDCOD09SEP26 → 2026-09-09), cashout_amount (Bosta's WHOLE
  batch total — never compare to one leg), next_cashout_date, settlement_refreshed_at (last attempt),
  settlement_verified_at (last SUCCESSFUL read), settlement_status none|deposited|paid|unresolved. Backfilled from stored
  raw in the same migration (prod at build time: Femine 449/1/0, BROEK 337/7/0, Jumi 168/66/8, Snouts 66/40/9 —
  none/deposited/paid). GDPR: bosta_raw_redacted never touches wallet; no PII in the columns.
- **`ShipmentSettlement` (integrations.bosta) is THE extractor + writer**, monotonic (a stored value is never replaced by
  a missing one; status only moves forward; 'unresolved' moves on only when money shows). Called after every
  shipments.raw writer — `ShipmentSettlementWiringGuardTest` fails the build when a new raw writer forgets it.
  `BostaStatusPollJob` logs the v2-walk wallet count (stored v2 items: 0 of 363 had a cashCycle).
- **`SettlementRefreshJob`** (hourly :23 Cairo, owner-pool tenant list like the poll, per tenant in runAs): re-reads
  terminal post-floor legs with the v0 GET (BACKGROUND) until paid — none every 12 h; deposited daily, only the day after
  the tenant's payout weekday when known (8-day safety net); a 429 stops the tenant until retry-after. A payload whose
  state/type differs from ours goes through the status poll's pipeline (`BostaIngestionHelper.ingestFetched` →
  BostaWebhookJob); unchanged → raw + settlement only. **'unresolved' only on evidence:** a successful read made 45+ days
  after the leg finished that still shows no payout (not-found / failed reads never count); such legs are read first,
  oldest first. Cap `ANALYTICS_SETTLEMENT_REFRESH_MAX_PER_TENANT` (default 100; **deploy with 30 for the first day**,
  see .env.example); kill switch `ANALYTICS_SETTLEMENT_REFRESH_ENABLED`. One INFO line per tenant run.
- **Owner-only `/api/v1/analytics/money/`**: `pipeline` (not fulfilled / in transit with city-rate expected values,
  awaiting payout incl. negative deposits, in your bank by cashout_date), `fees` (shipping / failed / exchange / return,
  settled bosta_fees else quote × 1.14 with estimatedCount, settled components, cost per successful / unsuccessful
  delivery, payout lag), `fees/extra?groupBy=awb|sku` (sku split by line value), `stuck` ("Booked, never picked up" vs
  "Stuck with Bosta", delivered-not-paid PER SHIPMENT: two payout weekdays after deposit + refreshed within 24 h),
  `payouts` (per txn: Traced's tracked shipments next to Bosta's batch total, never a difference). Wijha / non-Bosta never
  in money. Shared SQL in `SettlementSql` (fragments are space-padded: Java text blocks strip trailing spaces).
- **Deploy checklist:** V148 runs on startup; rebuild with `--no-cache`; add
  `ANALYTICS_SETTLEMENT_REFRESH_MAX_PER_TENANT=30` to prod `.env` for day one, then remove it.

**Analytics slice 2 — delivery outcomes, customer returns and cities, backend only (2026-10-07, branch
`analytics/s2-outcomes`, merged to main and pushed; not deployed). No migration.**
- **`/api/v1/analytics/sales/variants` new fields (slice-1 fields kept):** outcome units deliveredUnits / refusedUnits /
  inTransitUnits / wijhaUnits / notShippedUnits / otherTerminalUnits (sum to soldUnits), returnedUnits, netSoldUnits,
  returnRate (null when delivered = 0), refusalRate (null when delivered + refused = 0), deliveredRevenue /
  returnedRevenue / netRevenue; totals add deliveredOrders / refusedOrders / wijhaOrders, returnsOnUndeliveredOrders
  (returns on non-delivered outcomes, never in returnedUnits) and unverifiedNoRestockLines. Cohort = order placed_at.
- **Order outcome = the deciding FORWARD leg** (never type 25 / 30): the one non-terminated/cancelled forward leg
  (ux_active_forward_shipment_per_order), else the latest ended one. Order: no leg (or ended leg + carrier other_known)
  → wijha / not_shipped; state delivered → delivered; type code 20 (RTO, even while with_courier) / returning /
  returned / history returning before delivered → refused; history delivered → delivered; lost / terminated /
  cancelled → other_terminal; else in_transit. No prod order has two forward legs today.
- **Sold qty (approved 2026-10-06, applies to slice 1 too):** Shopify lowers current_quantity on refund, so refunded
  units with restock_type 'return' / 'no_restock' are added back when the refund came AFTER a non-cancelled fulfillment
  containing the line; before fulfillment (any type) and 'cancel' stay out; no fulfillments key → 'return' only, and the
  'no_restock' lines are counted in unverifiedNoRestockLines. Prod: Jumi +7 units / +7,700 EGP; everyone else +0;
  fallback lines 0.
- **Customer returns (delivered outcomes only), one count per unit:** piece return_received FROM delivered ∪ portal
  refund items arrived/done (tracked piece / untracked unit_no), Shopify 'return' refunds only add units the first two
  don't identify (LEAST(qty, GREATEST(identified, shopify))). Exchanged units are left out — ONLY those: a portal
  exchange's own unit, and per dashboard exchange row one 'exchange_match' scan (inbound variant first). Prod has no
  post-delivery scans yet → returnedUnits 0 everywhere (frontend must say "No returns recorded yet", never 0%).
- **`GET /api/v1/analytics/sales/cities` (OWNER):** per deciding Bosta forward leg, keyed by dropOffAddress.city._id →
  cityId, nameEn, nameAr (bosta_districts.city_name_ar, fallback nameEn), orders / delivered / refused / in transit /
  other terminal, successRate; legs with no city = one "Unknown" row. Prod names are clean (one English name per id,
  25 cities).
- **SQL performance:** the planner estimated the cohort at ~1 row (jsonb IS NULL default selectivity) and nested-looped
  over CTEs (3.2 s Femine) — fixed: raw cancelled_at checked after materialising, leg + history via per-order LATERAL
  index lookups, each raw read once via jsonb_to_record. Femine 366 d core ~0.7 s warm, ~3 s cold on prod. Grows with
  history: extracted columns (migration) are the later fix.
- **Tests:** `AnalyticsOutcomesTest` (14), `AnalyticsSalesTest` (14), `RlsCoverageTest` covers /cities. 30 revert checks
  across both rounds, each red (one equivalent mutant: the NULL-raw COALESCE guard). Full suite on the merge: 2313 tests,
  3 failures — the known reds ShopifyMagicLinkTest + ExchangeBackfillTest, plus BostaPollJobTest.p6 (the documented
  load-sensitive one; its class passes 21/21 alone).
- **Next:** the analytics mockup / frontend slice.

**Build C — custom_app_cc → official OAuth upgrade, made safe (2026-10-06, branch `feat/oauth-upgrade` off main
5cd15a9; merged to main; not pushed, not deployed). Migration V146. SHOPIFY_OAUTH_AVAILABLE unchanged (false).**
- **Upgrade = an OAuth callback that re-links an existing custom-app row** (`ShopifyOAuthService.updateStoreToken`,
  `:591`; used by Path-1 re-link, Path-2 existing link and the race re-link). Before the swap,
  `LegacyWebhookCleanup` (new) deletes the OLD app's subscriptions that point at Traced with the old app's own token —
  the stored one, or a client-credentials re-exchange with the stored Client ID / Secret when expired (not persisted);
  works whatever the store's status; never throws. Then `UPDATE_STORE_TOKEN` (`:55-80`) sets oauth, clears
  `api_secret_encrypted` / `client_id_encrypted`, and records the outcome (V146: `oauth_upgraded_from`,
  `oauth_upgraded_at`, `legacy_webhook_cleanup_status` done|failed, `_detail` ≤ 500, `_at`). Logs `OAUTH_UPGRADE`,
  `OAUTH_UPGRADE_CLEANUP`.
- **Cleanup failure:** upgrade still completes, status `failed` + reason. No in-app retry (the custom-app credentials are
  cleared on the flip, as asked): the leftover subscriptions only produce 401s; deleting the custom app removes them.
- **Uninstall safety:** after the flip the old app's webhooks fail HMAC — phase B only applies to custom-app rows, so
  the `connection_type` flip alone already blocks them; clearing the secret is defence in depth. Before the flip, a
  deleted custom app's `app/uninstalled` disconnects the store (trap) — recovery is "Connect with Shopify".
- **Scope cache:** `ShopifyGateway.forgetOrderPiiTier(shop)` (default no-op; `ShopifyHttpGateway:403`) on every
  insert / re-link / session-token swap (`ShopifyOAuthService:567, 613, 763`), CC connect (`ShopifySyncService:239`)
  and CC scope refresh (`ShopifyController:264`).
- **Per-store rollout (added after review):** `SHOPIFY_OAUTH_UPGRADE_SHOPS` (`shopify.oauth-upgrade-shops`, default
  empty). `GET /connections`' `oauthAvailable` — the upgrade banner's only input — is now per store
  (`ConnectionsController.upgradeOffered`): flag on AND (list empty OR this shop listed; case / spaces ignored). Banner
  only — `/oauth/initiate` is not gated. Documented in application.yml, .env.example, the runbook (add a shop, verify,
  then the next). Tests: `UpgradeRolloutRuleTest` (3 cases), `OAuthUpgradeRolloutTest` (HTTP, listed vs unlisted),
  `oauthUpgradeBanner.test.tsx` (banner follows oauthAvailable; neutral recovery copy) — revert-checked.
- **Recovery card copy (added after review):** the disconnected card's OAuth section no longer says "For Shopify
  reviewers": badge "Official app", title "Connect with Shopify", hint "Reconnect this store through the official
  Traced app." (EN + AR; locale keys `connections.shopify.reviewer.*` kept, no behaviour change). No existing test
  asserted the old copy.
- **Runbook:** `docs/runbooks/upgrade-store-to-oauth.md` (pre-checks incl. SHOPIFY_APP_HANDLE=trace-3 and the global
  banner flag, what the merchant clicks, SQL to verify, when to delete the custom app, the trap, rollback — note
  `app/uninstalled` disconnects by shop domain whichever app sent it).
- **Tests:** `OAuthUpgradeTest` u1–u4 — the WHOLE app on app_user (RLS everywhere, as prod; app_user is created with
  its password before Spring starts, which unblocks what ShopifyConnectAmbientContextTest couldn't do) and a REAL
  JobRunr background server running the enqueued import + webhook registration; mocked only ShopifyGateway,
  ShopifyLocationGateway, BostaGateway, BostaV2Client, EmailGateway. `@DirtiesContext` closes that context (and its
  job server) after the class. Revert-checked: no cleanup → u1/u3/u4; secrets kept → u1/u3; no cache clear → u1.
  Existing tests changed (approved): MigrationSmokeTest / NotTracedBackfillTest counts for V146.
  Suite: backend 2,298 run — reds = the baseline two (ShopifyMagicLinkTest, ExchangeBackfillTest); frontend 103 files /
  730 tests green.
- **Report-only findings:** no static navigation links in code (embedded app uses in-page Polaris tabs,
  `EmbeddedApp.tsx:638/754`; the live app config has none) — check the Dev Dashboard's app navigation setting. The
  upgrade banner shows only while SHOPIFY_OAUTH_AVAILABLE=true (global for every custom-app store); after consent the
  browser goes to the embedded app URL built with SHOPIFY_APP_HANDLE. The disconnected card's OAuth button is labelled
  "For Shopify reviewers" — misleading for a merchant recovering from the trap (for build D).

**Build B — customer name, phone and address on Shopify orders (2026-10-06, branch `feat/shopify-pii-ingest` off
main 1c8539e; merged to main; not pushed, not deployed). Migrations V144 (schema) + V145 (data, non-transactional).**
Protected customer data: NAME, PHONE, ADDRESS approved — EMAIL NOT: no email column, email stripped from stored payloads.
- **One precedence, in SQL (V144):** `shopify_order_pii_name` (shipping name → customer first + last → billing name),
  `_phone` (shipping phone → customer phone / GraphQL defaultPhoneNumber → billing phone), `_address` (shipping block →
  new `orders.shopify_address`). REST + GraphQL shapes. Used by `UPSERT_ORDER` (webhook + import + reconcile) and the
  backfill. The `ShopifyGateway.Order` record's three PII fields are no longer read (kept so callers compile).
- **UPSERT_ORDER (`ShopifySyncService.java:117`):** INSERT … SELECT computes PII from the payload; DO UPDATE is
  fill-only `COALESCE(orders.x, EXCLUDED.x)` for name / phone / shopify_address; `orders.address` is no longer written
  by Shopify at all (Bosta-owned); pii_source 'shopify' only when Shopify filled the name/phone of a source-less row;
  redacted rows get nothing (V143 guard extended to shopify_address); RETURNING customer_phone feeds the blocklist gate
  (`:414`, `:586`) — orders are now held at creation for a blocked phone.
- **Import tiers (`ShopifyHttpGateway.java:98-431`):** scopes from the token's live
  `currentAppInstallation.accessScopes` (GraphQL — App Store review 2.2.4), cached per shop 1 h; read_customers → FULL
  (shippingAddress + billingAddress + customer { firstName lastName defaultPhoneNumber { phoneNumber } }), else
  ADDRESSES; lookup failure → ADDRESSES for 5 min; ACCESS_DENIED → step down and re-ask the same page (FULL → ADDRESSES
  → NONE), so a missing scope never fails or empties the import. Query validated against the 2026-04 schema
  (Customer.phone is deprecated → defaultPhoneNumber). Stored `stores.access_token_scopes` NOT used (can be stale;
  plumbing it would change the gateway interface every mock depends on).
- **Bosta:** `populateConsigneePiiFromRaw` (`ShipmentLinkService.java:1303`) and the one-off `/bosta/backfill-pii`
  (`BostaController.java:614`) set 'bosta' only when they fill an empty field of a source-less row.
- **Address readers:** simulated waybill reads `shopify_address.address1` (+ city, Bosta city fallback)
  (`BostaAwbService.java:236`); pack card (`PackSessionStore.java:298`) and request drawer
  (`ReturnRequestService.java:123`) keep Bosta first, Shopify city only when `orders.address` IS NULL. Also changed:
  `CustomerRedaction` clears shopify_address; the data-request export includes it. Not changed (follow-up if wanted):
  order detail / pick screens still show only `orders.address`.
- **Email:** `shopify_raw_without_email()` (every `email` / `*_email` key holding a string or null, any depth; prod
  has email, contact_email, customer.email — `customer.verified_email` is a boolean and stays) via BEFORE triggers on
  `orders.raw` and `shopify_webhook_events.payload_raw`.
- **Backfill (`shopify_pii_backfill(500)`, V145):** strips email from every order (3,087; 2,446 hold email) and stored
  webhook (21,293; 15,251 hold email); fills from REST raw, fill-only, never redacted, never before the store's floor
  (Jumi: 2026-07-02 Cairo). COMMIT per 500-row keyset batch; idempotent; never calls Shopify.
  **Prod dry run (read-only, 2026-10-06), fill name / phone / shopify_address — skip before floor / skip no REST raw:**
  High line 1052/1052/1052 — 0/0 · Femine 545/545/841 — 0/0 · Jumi 10/10/199 — 470/37 · BROEK 70/70/309 — 0/0 ·
  The Snouts 0/0/7 — 0/99 · blnco 1/1/27 — 0/0 · Juno Babies 0/0/5 — 0/0 · Review store 0/0/0 — 0/1. 0 redacted.
  Left without a name afterwards (no REST raw, no Shopify call): Snouts 2 (cancelled 2026-07-21), Review store 1.
  The other 135 no-REST-raw orders (Snouts 97, Jumi 37 + Jumi's 470 pre-floor) already have names from Bosta.
- **Tests:** `ShopifyPiiIngestTest` b1–b7 (app_user + RLS; the backfill CALLed as owner, like Flyway) and
  `ShopifyHttpGatewayOrdersPiiTest` w1–w4 (real gateway, wire bodies) — revert-checked: fill-only reversed → b3;
  email trigger removed → b1 + b7; floor dropped → b7; scopes ignored → w2; old Bosta pii_source → b4; blocklist on
  null → b6. GdprBuildATest still green. Existing tests changed (approved): MigrationSmokeTest / NotTracedBackfillTest
  counts for V144 + V145. Backend 2,275 run — reds = the baseline two (ShopifyMagicLinkTest, ExchangeBackfillTest).
  Frontend 102 files / 727 tests green (no frontend change).
- **Deploy notes (reviewed 2026-10-06):** local benchmark (Postgres 16, synthetic data at prod volume, 8.6 KB avg
  vs prod 11 KB) — V144 < 1 s, V145 9 s first run, 1.5 s re-run. Estimate on Supabase: ~20–60 s, worst case ~2 min,
  added to the normal boot (the app is down from container recreate until Flyway finishes). Supabase's server-wide
  statement_timeout = 2 min applies to Flyway's postgres role and the CALL is one statement → V145 sets
  `statement_timeout = 0` for its own session (verified: with a 1 s server timeout a bare CALL is cancelled, V145
  completes). The procedure's email pre-filter matches only keys that really hold an email string/null, so a re-run
  rewrites nothing.
- **If V145 fails partway (runbook):** the app will not start (Flyway: "Detected failed migration to version 145").
  (1) Where it stopped: `SELECT version, success, installed_on, execution_time FROM flyway_schema_history WHERE
  version IN ('144','145');` and progress — `SELECT count(*) FROM orders WHERE raw::text ~* '"([a-z0-9_]*_)?email":
  ("|null)';` (same for `shopify_webhook_events.payload_raw`), `SELECT count(*) FROM orders WHERE pii_source =
  'shopify';`. Committed batches stay. (2) Repair = remove the failed row (what `flyway repair` does):
  `DELETE FROM flyway_schema_history WHERE version = '145' AND success = false;` (psql as postgres on the session
  pooler, port 5432) — or the CLI, from the server repo ~/traceability (/home/traced/traceability): `docker run --rm -v "$PWD/src/main/resources/db/migration:/flyway/sql"
  flyway/flyway:10.15.0 -url="$FLYWAY_DB_URL" -user="$FLYWAY_DB_USER" -password="$FLYWAY_DB_PASSWORD" -outOfOrder=true repair`.
  (3) Restart the app: V145 runs again and skips what's done (idempotent, verified). V144 is transactional — if it
  fails it rolls back completely and leaves no history row; just fix and restart.
**Analytics slice 1 — owner-only sales by variant and by product, backend only (2026-10-06, branch `analytics/s1-sales`,
merged to main; not deployed). No migration.** Step 0 / 0b / 0c diagnosis: most orders have no pieces, so analytics is
built on Shopify order lines + Bosta shipments; no variant has a unit cost (no writer exists); Bosta per-shipment
settlement (`wallet.cashCycle`, `wallet.cashout`) is in the delivery payload but stale in our snapshots — the spec has no
payout endpoint (Step 0c live calls not made: SSH to prod was refused; pending).
- **Endpoints (OWNER only — manager/worker/station worker 403):** `GET /api/v1/analytics/sales/variants` and
  `/products` (`sort=units|revenue`, `limit` 1–100). Periods = Cairo calendar days (`AnalyticsPeriod`: today / 7d / 30d
  ending today, or from/to, ≤ 366 days; boundaries via zone rules, never a fixed offset), counted by order `placed_at`.
- **Sold line (`SalesAnalyticsService.soldLines`):** post-floor, not cancelled (`status` OR `raw->>'cancelled_at'` — 5
  BROEK orders are cancelled in raw only), not `internal:exchange:%`, qty = `raw.current_quantity` else `quantity`, qty > 0.
  Net unit price = `raw.price` − Σ `discount_allocations` / the ORIGINAL quantity (same as RefundSuggestionService); no
  REST price → `variants.price`, counted in `approximateLines` (Jumi 39 / 242 post-floor lines, Snouts Jul–Sep).
  `lastSoldAt` = all time post-floor. Wijha orders included.
- **Analytics floor:** `stores.orders_ingest_from`, else `analytics.floor-overrides` (shop-domain=YYYY-MM-DD, 00:00 Cairo;
  Jumi `mmi24e-fx.myshopify.com=2026-07-02`) — read ONLY by analytics. Jumi's column stays NULL: ingest
  (`loadCutoff`) and the Bosta pre-connect filter / discovery read it.
- **SQL:** one statement per endpoint, app_user + RLS + explicit tenant filters; `lines` CTE is MATERIALIZED (inlined, the
  products sort carried line raw and spilled to disk). Prod timings swing with cache (`/variants` High line 54 ms warm,
  1.6 s cold); `/variants` reads all post-floor lines for `lastSoldAt`, so it grows with history — an order_items
  (tenant_id, variant_id) index or a non-raw cancelled flag is the later fix.
- **Tests:** `AnalyticsSalesTest` (14, incl. DST day, app_user isolation, real `/auth/pin` station token), `RlsCoverageTest`
  covers both. 12 revert checks, each red. Full suite at the baseline (known reds ShopifyMagicLinkTest, ExchangeBackfillTest).
- **Next:** slice 2 (delivery outcomes + customer returns per variant and per city).

**GDPR build A — /connect removed, redaction that sticks, real customers/data_request (2026-10-05, branch
`feat/gdpr-build-a` off main b5b2157; not merged, not pushed, not deployed). Migration V143.** Context: trace-3 is
approved on the App Store and protected customer data is approved for **Name, Phone, Address only — Email is NOT
approved: never add an email column or read email into any column.** Builds B (customer name + PII precedence +
backfill), C (OAuth go-live + pilot migration) and D (cold-install landing) are NOT started.
- **Step 0 diagnosis (read-only, prod + code):** both ingest paths hardcode customer name/phone/address to null, but
  the REST order webhooks already store full PII in `orders.raw` and `shopify_webhook_events.payload_raw` for every
  custom_app_cc store; ~1,655 of the ~1,670 null-name orders are fillable from stored raw (B). Bosta-vs-Shopify name
  differs 0 times in 761 overlapping orders. Jumi's 468 pre-connect nulls (May 4 – Jul 1) stay out of B's backfill
  (floor 2026-07-02, approved). B decision: a separate `shopify_address` column; `orders.address` stays Bosta-owned.
- **Removed:** `POST /api/v1/shopify/connect` (admin-token paste, no flag, 100-year expiry, left connection_type
  untouched), `ShopifySyncService.connect()`, `UPSERT_STORE`, the dead `connectCustomApp()` / `UPSERT_STORE_CUSTOM_APP`.
  `ShopifyGateway.validateShop` now has no production caller (still stubbed by other tests — left in place).
- **V143:** `shopify_order_raw_redacted(jsonb)` / `bosta_raw_redacted(jsonb)` (INVOKER, IMMUTABLE — the ONE definition
  of the PII part of a raw payload); `pii_redacted_at` on shipments / exchanges / unlinked_bosta_deliveries with
  BEFORE INSERT/UPDATE triggers that re-strip raw on every later write (a new shipment on a redacted order is born
  redacted); BEFORE INSERT trigger strips a stored `orders/*` webhook for a redacted order; partial index
  `orders_redacted_by_external_id`; `customer_data_requests` (RLS + FORCE, NULLIF policy, app_user cannot DELETE,
  UNIQUE webhook_event_id without FK so event pruning never blocks; expires_at = now() + 30 days — calendar days, so
  across Egypt's DST change it's 30 days ± 1 h).
- **UPSERT_ORDER:** on a row with `pii_redacted_at` set, customer_name / customer_phone / address keep their cleared
  value and raw goes through `shopify_order_raw_redacted()` — so neither import nor orders/updated can restore PII.
- **Redaction (`privacy/CustomerRedaction`, scope `privacy/CustomerSubject`):** customers/redact = orders_to_redact +
  the internal replacement orders of their exchanges; clears orders, return requests (email, note, custom address —
  the pickup AREA snapshot stays, per Marawan), shipments / exchanges raw (via the triggers), unlinked Bosta deliveries
  matched by the orders' canonical phones, numbers or numeric Shopify ids, stored order webhooks, the customer's
  email/phone inside `customers/*` payloads, and that customer's data requests' phone. shop/redact = all of it,
  tenant-wide. Never touched: piece_events, return_request_events, Bosta `webhook_events` (status payloads only — no
  PII, checked in prod).
- **Blocklist on customers/redact (approved after review):** the customer's rows (phones from `CustomerSubject`) get
  `reason = '[redacted]'` (the column is NOT NULL); phone_canonical, source, created_by, created_at and active stay, so
  the block keeps holding orders (hold_reason reads `blocked_customer: [redacted]`). shop/redact leaves blocklist
  reasons as they are (not in scope).
- **data_request:** processor records one `customer_data_requests` row per event (customer id, phone as sent, orders
  as GIDs — never the email), then emails the active owners "a customer data request is ready — Settings → Privacy"
  with NO customer data (store domain, link, expiry date only; once, `notified_at`). Export (`GET
  /api/v1/privacy/data-requests/{id}/export`, OWNER only, 404 other tenant, 410 expired) is built at download time:
  orders (requested + phone-matched) with columns + the PII keys of raw, return requests, shipments / exchanges /
  unlinked Bosta customer blocks, blocklist rows for the phones, stored Shopify payloads for those orders and this
  customer. It includes whatever email Traced already holds (portal `customer_email`, raw payloads) because it
  discloses what is stored — nothing new is written. Settings → Privacy tab (owner only): list + Download.
- **Tests:** `GdprBuildATest` g1–g7 (g7 = blocklist reason cleared, block still holds — revert-checked) (app_user + RLS for every code path; g6 over HTTP for 403 / 404), revert-checked
  (UPSERT guard removed → g1 red; shipments trigger no-op → g2 + g4 red; exchanges redact removed → g2 red; tenant
  filter + RLS removed → g5 + g6 red). `privacyTab.test.tsx` (3). Existing tests changed (approved): `ShopifyImportTest`
  seeds the store row directly; its "(c) encrypted token" and "(d) non-owner → 403" tested the removed endpoint and
  are replaced by "admin-token connect removed → owner 404, no store row"; `MigrationSmokeTest` / `NotTracedBackfillTest`
  counts for V143; `RlsCoverageTest` registers the two new GETs in COVERED with a seeded cross-tenant test (approved).
  The removed-endpoint test asserts "refused, no store row" rather than 404: an unknown /api path answers **500**
  today (ApiExceptionHandler's `Exception` catch-all takes Spring's NoResourceFoundException) — pre-existing, app-wide.
- **Suite:** backend 2,260 run — reds = the two known on main (`ShopifyMagicLinkTest.provisionWiring_path2NewInstall…`,
  `ExchangeBackfillTest`) + three fixed afterwards (RlsCoverageTest registry, ShopifyImportTest status, PickPackModeTest
  container timeout — re-run green). Frontend 102 files / 727 tests green.
- **Follow-ups:** unknown /api routes → 500 instead of 404 (map NoResourceFoundException → 404 above the catch-all); stored raw payloads still hold email for non-redacted orders (pre-existing,
  verbatim storage — B/C to decide); `ShopifyGateway.validateShop` is dead in production.

**Station mode survives logins/logouts elsewhere — Build A (2026-10-05, branch `fix/session-device-logout`, rebased
on main f7604e2; approved, not merged, not pushed, not deployed). Migration V142 (V141 = Q1b on main). Orphan cleanup
approved — Marawan runs it after V142 deploys (dry run first). Build B approved in principle; starts only after Build A
has run cleanly in prod for a day or two.**
- **Step 0 diagnosis (read-only, prod + code):** station mode was only `localStorage.stationMode` — no server identity;
  the tablet ran on the owner's own refresh cookie (until a worker PINned in). `POST /auth/logout` revoked EVERY refresh
  token of the user (`AuthRepository.revokeAllRefreshTokens`), so an owner logging out at home killed an owner-held
  tablet within ≤ 15 min → refresh 401 → `api.ts` hard-redirect to /login → whoever signed in there hit
  `Login.tsx` `exitStationMode()` → station mode gone. A login alone never touched other sessions. Prod: Snouts owner
  logout-all ×11 in 3 weeks (10 tokens in one statement on 2026-09-28 20:34:26), Jumi ×4. Other kick paths: rotation
  with no grace + the reload refresh (App.tsx) not shared with api.ts's refreshPromise (two tabs / a lost rotation
  response → 401). Side bug: the cookie path `/api/v1/auth/refresh` means `/auth/pin` never got the cookie, so every PIN
  switch left the tablet's previous token live (prod: 6 superseded Snouts worker tokens). Prod couldn't attribute a
  token to a device (no UA, no created/revoked reason, no rejection log).
- **Build A:**
  - Access JWTs carry `sid` = the refresh token's row id (`JwtService.issueAccessToken(…, sessionId)`, `sessionIdOf`).
    That is how "this device" is known server-side WITHOUT widening the cookie path (existing tablet cookies keep
    working at deploy; pre-V142 access tokens have no sid → a device logout just clears the cookie for ≤ 15 min).
  - `POST /auth/logout` = this device only (sid + cookie if sent; phone pairing only for `?deviceId=` when given,
    else the old all-pairings fallback). New `POST /auth/logout-all` = old revoke-all (+ pairings). Password reset keeps
    revoke-all. Settings → Users: "Log out of all devices" card (two-step, no browser dialog).
  - Rotation (`AuthRepository.rotate`): claim = conditional UPDATE to 'rotated' + successor INSERT in one tx; a token
    presented again within 30 s gets the SAME successor — its raw value is derived, never stored:
    HMAC(key derived from app.jwt.secret, predecessor raw); only while the successor is live. Outside → 401.
  - `/auth/pin` revokes the tablet's previous token by sid (reason pin_switch) and stamps the UA on the new one.
  - V142 `refresh_tokens`: `created_via` (login/refresh/pin/signup/magic_link), `revoked_reason`
    (logout_device/logout_all/password_reset/rotated/pin_switch/orphan_cleanup), `user_agent` (≤512), `replaced_by`.
    One `REFRESH_REJECTED reason=… token=<id8|hash:…> user=…` line per refusal; `LOGOUT_DEVICE` / `LOGOUT_ALL` lines.
  - Frontend: `useAuthRefresh` uses api.ts `refreshAccessToken` (one shared refresh); /login tries a silent refresh
    first (form renders meanwhile); `Login.tsx` no longer calls `exitStationMode()` — station mode ends only through
    the gate's Exit step; Layout's logout sends `stationDeviceId()`.
  - Prod orphans: `scripts/ops/refresh-token-orphan-cleanup.sql` (after V142; dry run unless `-v commit=yes`) revokes
    only superseded WORKER tokens (dry-run count 6); owner orphans are indistinguishable from real sessions and expire
    in ≤ 30 days.
- **Tests:** `DeviceSessionTest` d1–d10 (backend) + `deviceSession.test.tsx` (7, frontend), each revert-checked (10
  backend + 5 frontend mutations, each red on its own test). Existing tests updated (approved): `CookieAuthTest.ca5` and
  `AuthIntegrationTest.refreshTokenRotates` (reuse within the grace → 200 + same token; after 31 s → 401),
  `MigrationSmokeTest` / `NotTracedBackfillTest` counts for V142, `workerExperienceFrontend (d)` (a login keeps
  station mode). Known reds already on main: `ShopifyMagicLinkTest.provisionWiring_path2NewInstall…`,
  `ExchangeBackfillTest`.
- **Build B (plan only):** see the Build A/B report 2026-10-05 — `station_devices` + `traced_station` cookie
  (`tenantId.stationId.secret`, verified by hash under RLS, no new hatch), /station/me|roster|pin|end, worker
  sessions tagged `station_device_id`, Active stations list with remote End, adopt existing tablets, audit
  station_started/station_ended.
**Q2 — Phone scanner on Scan returns, with a "via phone" marker (2026-10-05, branch `feat/phone-returns` off main
7e31225; pushed, not merged, not deployed). No migration.**
- **Source:** `POST /returns/sessions/{id}/scan` takes an optional `relayEventId`, checked by the unchanged
  `PhoneScanSource` (`ReturnSessionController.java:155`). Recorded only as `{"via":"phone"}` on the scan's
  `return_received` piece events (the shared metadata suffix, `ReturnSessionService` scanPiece) — no row column:
  `return_session_items.scan_source` already means barcode vs AWB, and the screens derive the marker from the event.
  Illegal-state scans write no event today, so a phone scan of one is not marked (unchanged behaviour).
- **Exposed:** `via_phone` + `order_number` per session item (and parcel card), `phoneScanCount` per session,
  `viaPhone` per return-request item (matched on the event's `request_item_id`).
- **Frontend:** Scan returns is a phone target ("Scan returns · RT-…"), PhoneScanButton in the session header (in-shell,
  so the top-bar icon also shows while paired — as TransferReconcile), paused by the damage-reason field / abandon
  dialog; phone lines "Received · #1047" / "Not expected" / "Parcel label recognised · <awb>" / "Not recognized".
  "via phone" tags on session items and on the case detail's piece code (PieceCode); footer "N scans came from a phone".
- **Marked block:** exactly two lines inside Returns.tsx's SAFETY-CRITICAL scan handler changed (approved 2026-10-05):
  `meta` is read, and the body adds `relayEventId` only for a phone scan — a keyboard scan's body is byte-identical
  (asserted). Flash trigger / overlay / input untouched.
- **Tests:** ReturnPhoneScanTest (4), phoneReturns.test (8), returnsPhoneInterleave.browser (Chromium + WebKit).
- **Follow-up:** In-shell app pages keep the desktop sidebar at phone width (~390 px) and squeeze the content — responsive layout follow-up.

**Q1b — Phone scanner on stock take + transfers, with a "via phone" marker (2026-10-05, branch
`feat/phone-stocktake-transfers` off main ce6f9d6; pushed, not merged, not deployed). V141.**
- **Source, decided server-side (`inventory/PhoneScanSource`):** a scan endpoint takes an optional `relayEventId`; the
  scan is 'phone' only when that relay event is in the caller's tenant, belongs to a pairing held by the caller that is
  live (not revoked, not expired) and carries the same code (whitespace ignored). Anything else is a normal hardware
  scan — never rejected, logged at INFO. A client "source" is never read. Runs in a (joined) transaction so the tenant
  GUC applies under app_user.
- **Stock take:** V141 adds `stock_take_scans.scan_device` ('hardware'|'phone', default 'hardware') — `source` was
  already taken ('scan' / 'manager_found'). A re-scan keeps the first row's device. The scan response carries
  `scanDevice`; the reconciliation adds `scanCount`, `phoneScanCount` and `scanDevice` per piece row.
- **Transfers:** no new column — scan-out / return-scan-out / scan-back events get `{"via":"phone"}` in
  `piece_events.metadata` (the single history source). The transfer detail adds `phone_scans` per line and
  `phoneScanCount`.
- **Frontend:** StockTakeScan, TransferScanOut, TransferReconcile are phone targets (usePhoneScanTarget) with
  PhoneScanButton in their headers; labels "Stock take · <note or id>", "Transfer out · <location>", "Transfer
  reconcile · <destination>"; phone lines "Counted · Match", "Scanned out", "Returned · Good" or the rejection text.
  Paused by the abandon dialog / the close confirm / a focused shortfall quantity. relayEventId is sent only for phone
  scans. "via phone" tags on the stock-take recent list, review rows and transfer detail lines; the review shows
  "N of M scans came from a phone" (finalize card + modal, informational). The stock-take header wraps at narrow widths.
- **Tests:** PhoneScanSourceTest (3), phoneStockTransfers.test (10), stockTakePhoneInterleave.browser (Chromium +
  WebKit); migration counts bumped. Existing stock-take / transfer tests unchanged.

**Exchanges and customer-return pickups linked to their original order by reference (2026-10-05, branch
`feat/exchange-reference-link` off main ce6f9d6; not merged, not pushed, not deployed). No migration.**
- **One reference rule — `OrderReference.resolve`** (bosta package): the businessReference through
  `PreConnectDeliveryFilter.referenceCandidates` (as sent, and both sides of a ':'), each '#'± and as external_id;
  shopifyInfo.orderId as a Shopify GID only when the reference finds nothing; internal orders (EXC-…) never match;
  at most 2 distinct ids — exactly one = the order, two = ambiguous (never guessed).
- **Users:** `ShipmentLinkService.matchByBusinessReference` (SEND / RTO / CRP strong match) — BROEK's CRPs
  ("BRK-44903-EG:BRK-44903-EG-R1") now link as return legs through the existing path;
  `ExchangeMatchService.matchByReference` — new first step of `attemptMatch`: exactly one order →
  matched_order_id, match_method 'reference' (status matched; a needs_mapping row keeps needs_mapping and becomes
  matched when `ExchangeService.commit` maps it); ambiguous → needs_confirmation, nothing set; no match / no
  reference → the phone matcher as before. Dashboard rows only — return_request_id rows never touched.
- **Fulfillment linking:** a tracking number already a shipment on an internal exchange order whose exchange is
  matched to this order (or whose reference resolves to it) → link_status 'linked', reason "linked via exchange
  EXC-…", no Bosta call, no new shipment, no exception. The exchange step also marks that row linked when it
  matches. The shipping badge's link-problem check now ignores `linked` rows.
- **Catch-up (`ExchangeReferenceCatchUpService`):** owner POST /api/v1/bosta/exchange-reference/catch-up?apply=…
  or BOSTA_EXCHANGE_REFERENCE_CATCH_UP_ON_STARTUP=<ids>|all (+ _APPLY=true). Kinds in order: exchanges without
  an original; open unlinked CRPs whose reference resolves to one order (re-run through the normal pipeline with a
  'bosta_backfill' event and its own idempotency key — no matcher-version bump); fulfillment rows in conflict /
  skipped because of an EXC order. Dry run writes nothing and calls no Bosta API. One `EXCHANGE_REF_CATCHUP` line
  per row (tenant, kind, tracking, reference, order, verdict, reason) + a summary. Prod expectation (2026-10-05
  read-only diagnosis): BROEK 6 exchanges → matched, 2 CRPs (1332878806, 6394431795) → return legs, 6
  fulfillment rows → linked via exchange; the multi-item exchange 7098606041 stays in the unlinked lane.
- **Tests:** `ExchangeReferenceLinkTest` r1–r11; revert-checked (20 mutations + RLS disabled, each red).
  **Existing tests changed:** `PreConnectFilterTest.pf11` ("blncoeg:#515960" now LINKS through the tail instead of
  going unlinked — asserts the shipment); `OrderShippingCarrierTest` order fixture computes placed_at from the JVM
  clock with a one-hour margin (b2 / f1 flaked when the Docker clock ran ahead).
- **Later (out of scope, noted 2026-10-05):** (a) BROEK EXC orders sit as pickable 'new' orders in Fulfill;
  (b) Snouts 9293360461: the EXC leg is stuck at 'created' while Bosta says "Returned to business"
  (ExchangeStateInterpreter vocabulary gap); (c) Femine CRPs carry no reference — manual linking only.
**Phone control placement — no floating control (2026-10-05, branch `fix/phone-control-placement` off main 82f6bf5;
pushed, not merged, not deployed). Frontend only, presentation only — PhoneScanProvider, routing and pairing unchanged.**
- `phone/PhoneScanButton.tsx`: `PhoneScanButton` lives INSIDE a phone-capable scan screen's header, in its flow, next to
  the header actions — pack session (before the worker / End session) and PickScreen (before Cancel Order); every future
  phone-capable screen uses the same component. "Use phone" → QR modal; "Waiting for the phone…" / "Phone connected ·
  <device>" / "Phone link reconnecting…" → a menu with Unpair (Cancel while waiting). Below 640 px it is an icon button
  in the same slot. `PhoneTopbarIcon` in Layout's top bar (beside the bell) shows ONLY while a phone is paired (status +
  Unpair). Non-scanning pages show nothing phone-related unless paired.
- `phone/PhoneControl.tsx` now renders nothing: it is only the authenticated-page signal (RequireAuth) that tells the
  provider to load the pairing and open the stream. The floating placement logic is gone.
- Tests: `phoneControlPlacement.browser.test` deleted (approved) → `phoneHeaderPlacement.browser.test` (header flow, no
  overlap, icon below 640 px, top-bar icon, QR modal — 1280/768/390, Chromium + WebKit) and `phoneHeaderTopbar.test` (6).
  `phoneStation.test`'s control tests render the header button instead of the floating control; the connected test
  opens the button's menu before Unpair (assertions unchanged).

**Q1 — Phone scanner per station: pairing to the tablet + worker; pack & pick (2026-10-05, branch `feat/station-phone`
off main 07a21a4, origin/main 0462c48 merged in; pushed, not merged, not deployed). Migration V140 (V138 and V139 went
to the Bosta status poll and the order shipping badge on main while this was built). V140 + revised hatch #15 approved by Marawan 2026-10-04 (incl.
revoking existing S6 pairings once, one live pairing per tablet AND per worker, retiring S6's per-session endpoints,
the station GETs in RlsCoverageTest). useScanner unchanged; no marked block edited.**
- **Model:** a pairing belongs to the tablet (`station_device_id`, random localStorage id — routing key, not a secret) and
  the worker; it lasts the shift across screens and pack sessions. Hatch #15 (V140): "pack session open" → the worker is
  an ACTIVE user of the tenant; still DEFINER, fixed search_path, returns only (tenant_id, pairing_id). V140 revoked every
  live S6 pairing ('replaced') and expired their undelivered scans. Register updated (blueprint §16.1 row 15, CLAUDE.md).
- **Endpoints (`StationPairingController`):** POST/GET/DELETE `/station/pairings[/current]` (deviceId), PUT
  `/station/pairings/current/target {label}`, GET `/station/relay-stream?deviceId=` (pairing's worker only; 403
  PAIRING_NOT_YOURS / 409 NO_PAIRING / 400 BAD_DEVICE), POST `/station/relay-events/{id}/outcome`; DELETE
  `/pack-sessions/pairings/mine[?reason=station_locked]` kept. S6's `/pack-sessions/{id}/pairings…`, per-session stream
  and outcome are gone. Hub keyed by pairing id (one stream per tablet; a second replaces the first).
- **Revocation:** unpair; replaced (same tablet or same worker, `ScanPairingService.create`); PIN switch
  (`AuthController.pinSwitch`); tablet sign-out (`StationProvider.signOutWorker`); station lock (`StationGate` mount →
  `?reason=station_locked`); full logout (`AuthController.logout` → signed_out); worker deactivated (hatch); 12 h.
  Ending a pack session no longer revokes (`PackSessionStore.end`).
- **Frontend:** `phone/PhoneScanProvider` at the root (device id, status, the one stream, target stack, exactly-one-outcome
  bookkeeping); `phone/PhoneControl` beside every authenticated page (RequireAuth), bottom-end; `phone/usePhoneScanTarget`
  (`wrap` onScan + `attach` scanner — the attached clearQueue answers dropped phone scans). Targets: pack session
  (paused by set-aside), PickScreen (paused by the cancel confirm), AwbLinkDialog on top while open (through its own
  handleLink). No target → "No scanning screen open on the tablet"; paused → "Tablet busy — finish the dialog"; unmount /
  clearQueue → "Not applied — scan again". Phone header shows `active_target` ("Pick & Pack · #1047").
- **nginx (manual on deploy):** the unbuffered stream location is now `location = /api/v1/station/relay-stream`
  (DEPLOY-NOTES, same steps as S6: `nginx -t` then restart nginx).
- **Tests:** StationPairingMigrationTest (1), StationPhoneSchemaTest (9), StationPhoneTest (21), RlsCoverageTest
  (station GET covered + stream EXEMPT naming the app_user test), migration counts bumped; frontend phoneStation.test (19),
  phoneRelayInterleave.browser (pack session + PickScreen × Chromium/WebKit). Approved test edits: ScanPairingTest /
  ScanPairingSchemaTest / phoneScannerTablet deleted (every case mapped), TenantContextRestoreTraps + SimulatedCourierFlow
  e3 one fixture/call each, phoneScanPage header assertion, scanHelperScreens merchant test waits on /me. Backend 2206
  (only the 2 known failures), vitest 677/677, tsc + build clean, test:browser 40/40 three runs.
- **Gotcha:** `Button` (components/ui) doesn't forward extra props — a `data-testid` on it is silently dropped.
- **Floating control placement (fix before merge):** it places itself clear of every visible actionable element (end
  edge bottom-up, then start edge); collapses to a 40 px icon button when the chip fits nowhere
  (`phoneControlPlacement.browser.test`: PickScreen / pack session / stock-take scan at 1280×800, 768×1024, 390×844).
  It may sit over plain text (e.g. the pack session's "This session" list at 390 wide) — never over a control.
- **Follow-ups:** (a) unmapped `/api` paths answer 500 — `ApiExceptionHandler`'s catch-all `Exception` handler swallows
  Spring's `NoResourceFoundException`; should be 404 (e.g. S6's retired `/pack-sessions/{id}/pairings…`).
  (b) `BostaPollJobTest.p6_pollAndDiscovery_alreadyLinkedDelivery_discoveryCheaplySkipsIt` is load-sensitive in the full
  backend suite (from the Bosta status-poll work, not Q1) — passes when its class runs alone.
- **Next:** Q1b (stock take / transfers with a "via phone" marker), Q2 returns wiring.
**Order shipping badge + carrier on the order (2026-10-05, branch `feat/order-shipping-carrier-badge` off main 0c6ebe9; not
merged, not pushed, not deployed). V139. Replaces the reconcile job's red "Shipment not created" badge (bosta_link_status =
'not_created'), which fired on every order not linked within ~48 min — including Femine's Wijha orders (268 of 378 flagged in
30 days) and orders simply not booked yet. Option (d) — what happens to Wijha orders' stock / pickability — is DEFERRED
(status quo; Marawan decides b vs c after talking to the merchant). Pickable gate and Bosta linking untouched.**
- **Carrier on the order (V139):** `orders.shipping_carrier_class` ('bosta' / 'other_known' / 'unknown' / NULL) +
  `shipping_carrier_name`, from ONE SQL function `shipping_carrier_of(order)`: non-cancelled fulfillments in
  `order_fulfillment_tracking` AND in `orders.raw.fulfillments` (the pre-V129 Wijha orders), plus a live (not
  cancelled/terminated) Bosta forward shipment; Bosta wins, then other_known, then unknown; cancelled fulfillments ignored, so
  a cancelled Bosta fulfillment flips the order back. The named carriers in the SQL must match
  `FulfillmentTrackingCapture.OTHER_KNOWN_CARRIERS` (Wijha). Jumi's "Other" stays 'unknown' → Bosta-eligible. Backfilled in
  the migration. Kept current by `OrderCarrier.recompute()` (only writes on change) from `FulfillmentTrackingCapture.capture()`
  / `upsertOnly()` (every orders/updated, after the raw order is upserted) and `ShipmentLinkService.clearReconcileFlag()` (every
  link path).
- **Badge, derived at read time (`OrderShippingBadge`)** — API field `shippingBadge {state, carrier, days}` on the order list
  and detail; null = no badge. Precedence: cancelled → linked (live Bosta forward shipment; UI keeps its existing delivery
  badge) → none (self-pickup, tenant without an active Bosta account or simulated courier, delivered/returned/lost) →
  bosta_tracking_not_linked (red: a non-cancelled Bosta fulfillment whose tracking number isn't a shipment of the order, with
  link_status conflict/gave_up or first seen > `orders.shipping-badge.link-grace-minutes` (60) ago) → shipped_elsewhere
  (neutral, "Shipped with {carrier}") → not_booked_overdue (warning, ≥ `orders.shipping-badge.overdue-days` (3) since placed)
  → awaiting_booking (neutral). EN + AR labels under `delivery.shippingBadge.*`. Shown on OrderDetail, the order drawer's
  Shipment tab, and the Orders list's Delivery cell when there's no shipment.
- **Reconcile:** `BostaOrderReconcileJob` skips other_known orders entirely and never sets 'not_created' any more; at
  max-attempts an order simply leaves the candidate set (`bosta_link_attempts < max`). The column stays; existing flags in
  prod stay as they are and nothing displays them (the link paths still clear them).
- **Funnel / Overview:** `OrderShippingBadge.isShippedElsewhere` (other_known, no live forward shipment) — such orders are
  not "New" (nor Picking) in `OrderController.funnel()` / `EmbeddedController.ordersFunnel()` and not late-to-pack in
  `OverviewService.lateToPack()`; `FunnelCounts.shippedElsewhere` is shown as a small line under the Overview flow strip.
  `OverviewService.isStillPrePack` moved to `OrderStatusDeriver.isPrePack` (shared, same logic).
- **Tests:** `OrderShippingCarrierTest` (c1–c5 carrier/backfill/reconcile, b1–b7 every badge state through list() AND
  detail(), f1 funnel + embedded + late-to-pack, i1 app_user cross-tenant isolation); `orderShippingBadge.test.tsx` (every
  label EN + AR, tones, OrderDetail, Overview count). Revert-checked: 24 backend + 7 frontend mutations, each red.
  **Existing tests changed:** `BostaOrderReconcileTest.r2` (was "flagged not_created at max" → now "not flagged, no longer a
  candidate" — the approved behaviour change); `BostaLinkingHardeningTest.h4` (asserted 'not_created' after max
  attempts → now attempts = max and no flag, same change); `MigrationSmokeTest` 137→138, `NotTracedBackfillTest` 82→83 (+V139).
- **After deploy (read-only check):** `SELECT shipping_carrier_class, count(*) FROM orders WHERE placed_at > now() -
  interval '30 days' GROUP BY 1;` — Femine's Wijha orders should read other_known.

**R1 — Scan returns + Pickups on useScanner (2026-10-04, branch `fix/returns-pickups-scanner` off main 9ffcdc2; pushed,
not merged, not deployed). Frontend only — backend untouched, useScanner unchanged, no migrations. Edits to Scan returns'
SAFETY-CRITICAL scan handler, refocus effect and scan input approved by Marawan 2026-10-04; Pickups has no marked blocks.**
- **Bugs fixed:** Scan returns disabled its input while a scan was in flight (keystrokes of the next hardware scan dropped —
  1–4 of 20 sent in a browser burst) and focused the still-disabled input after a 422, so focus was lost until a click;
  its unconditional click-refocus pulled focus out of the damage-reason field on every click. Pickups returned early while
  a scan was in flight, leaving the next code in its controlled input — 4–5 of 20 sent, and two back-to-back scans sent
  ONE request (the second concatenated onto the next).
- **Scan returns now:** `onScan` (Returns.tsx:650-680) strips all whitespace, POSTs, triggers the screen's own flash,
  reloads the session; 422 → rejected-scan banner 4 s, other errors → message. Marked flash trigger (:644-647) and overlay
  unchanged. `useScanner` (:682) with focusPaused while the damage-reason field or abandon modal is open; the reason field
  gets its own later-mounted click-refocus (:684-697, AwbLinkDialog template). Old refocus effect removed (:638-642 note).
  Input (:1011-1022): useScanner's ref, never disabled, aria-busy. Waiting scans dropped on abandon (:768) and close (:781).
  Chips go through handleScan, disabled while scanning.
- **Pickups now:** `onScan` (PickupSessions.tsx:230-269) = the old handler (optimistic row, rollback, ACCEPTED 1.5 s,
  outcome banners); `useScanner` (:271) focusPaused while the close confirm is open. Input (:377-386) uncontrolled,
  useScanner's ref, cleared on Enter; onBlur refocus kept. Waiting scans dropped on Close session (:468) and confirm (:311).
- **Tests:** `test-browser/returnsPickupsScanner.browser.test.tsx` (6 × Chromium/WebKit — Returns burst / focus after 422 /
  damage-reason focus; Pickups burst / two back-to-back / focus after reject; all but Pickups reject-focus fail on
  origin/main — Pickups never disabled its input), `test/returnsPickupsScanner.test.tsx` (8 jsdom must-survive). Existing
  tests unchanged (no mock edits). vitest 664/664, tsc + build clean, test:browser 38/38 three runs.
- **Deviation:** Pickups now beeps on scan (useScanner's beep); Scan returns beeps once from the hook (its own playBeep
  call removed from the scan path, still used by dispositions).

**Bosta status poll on the v2 -updatedAt search + (b) list items instead of a second v0 fetch (2026-10-04, branch
`feat/bosta-status-poll-v2` rebased on main 07a21a4; not merged, not deployed). V138.**
- Status poll (BostaStatusPollJob): per tenant, a walk of `POST /api/v2/deliveries/search` sortBy `-updatedAt`, limit 50 —
  until items updated before `poll_mark_at` minus 10 min, an empty/short page, or `status-max-pages` (10); mark advances
  only on a complete walk, a capped walk resumes (head first, then shifted — discovery's mechanics), repeat-page guard.
  First run walks back `status-safety-net-hours`. Only this tenant's in-flight shipments are ingested (ingestListItem,
  source `bosta_poll`; idem key drops unchanged) and stamped last_polled_at. Safety net: the old per-shipment v0 fetch,
  now only for in-flight shipments unchecked for 4 h (never one this cycle's walk just checked). No sleeping in the
  worker (status poll and discovery's retry pass no longer sleep inter-fetch-delay-ms; the limiter paces).
- (b) BostaWebhookJob: an event Traced wrote from a fresh search item (`bosta_poll_discovery` / `bosta_poll`, item kept
  in memory ≤ 10 min by BostaListItemCache — never persisted: it carries the customer's address/phone) is applied from
  that item, no v0 fetch — only for forward deliveries (type 10/20) and only while numberOfAttempts equals the stored
  raw's (a new attempt's history/reason lives in v0 attempts[] → fetch). Fields the item lacks are carried over from
  the stored raw (except old exception fields); raw is marked `_tracedRawShape: v2-list`. Exception code falls back to
  `state.lastExceptionCode` (job + ExceptionService NDR SQL). A real Bosta webhook (source `bosta`) always fetches.
- Observability (walk misses): when the safety-net fetch finds a state change on a shipment the walk hasn't shown for 4 h,
  WARN `status walk missed change — tracking <tn> state <old>→<new> (Bosta updatedAt <t>)` (a shipment with no known
  state yet isn't a miss); one INFO per tenant per cycle: `walk N page(s), M change(s) ingested; safety net F fetch(es),
  C change(s) found (X missed by the walk)`. Prod check: `grep -c "status walk missed change"` should stay at / near 0.
- Lazy v0 (ShipmentRawRefresher, USER_FACING): PickupAreaService.forOrder when the forward leg is a v2 copy without the
  city; ReturnPickupBookingService.book() refreshes the delivered forward leg before its transaction (address block).
- Tests: BostaStatusPollWalkTest sw1–sw9; BostaPollJobTest: `status-safety-net-hours=0` (its p-tests cover the
  per-shipment path, now the safety net) and p6's fetch assertion updated (the walk now handles it — no fetch);
  migration counts 137 / 82. Revert-checked: 14 mutations, each RED (W4 re-run with a correct mutation).
- Load (prod 2026-10-04: 312 in-flight — Femine 181, BROEK 91, blnco 26, Jumi 10, Snouts 4; status poll ran only 3×/h,
  each ~20 min): before ≈ 3 × 312 = ~940 status fetches/h + ~150 discovery pages/h + ~55 verify fetches/h ≈ 1,150/h
  (0.32 req/s, Femine alone ~590/h). After ≈ 100 walk pages/h (20 cycles × 5 tenants) + ~80 safety-net fetches/h
  (312 / 4 h) + ~150 discovery pages/h + ~20 verify fetches/h ≈ 350/h (~0.1 req/s, 13% of the 0.75/s budget; Femine ~100/h).
  Status-update latency: before, each shipment re-checked once per ~20-min cycle; after, a change shows on the next
  3-minute cycle (p50 ≈ 1.5 min, worst ≈ 3 min + 5 s pickup) — assuming Bosta bumps updatedAt on every change.

**P1 — PickScreen (queue mode) on useScanner (2026-10-04, branch `fix/pickscreen-scanner` off main 484192e; pushed, not
merged, not deployed). Frontend only — backend untouched, useScanner unchanged. Edits to PickScreen's SAFETY-CRITICAL
scan handler, refocus effect and scan input approved by Marawan 2026-10-04.**
- **Bugs fixed:** the scan input was disabled while a scan was in flight (keystrokes of the next hardware scan dropped —
  3 of 20 in a browser burst), and after a REJECTED scan focus() ran on the still-disabled input so focus was lost until a
  click (a success only recovered via the [order] refocus). The AWB link step lost focus the same way after AWB_MISMATCH /
  conflict / error. Unscanning with the link step open left it open on an incomplete order, and the [order] refocus pulled
  focus out of it.
- **Now:** `onScan` (Fulfill.tsx:914-941) posts the scan, sets lastResult, triggers PickScreen's own flash, awaits the
  order reload on success; useScanner queues + single-flights, clears the input on Enter, beeps (same tones). Input
  (:1166-1185): useScanner's ref, never disabled, autoFocus kept, aria-busy. Refocus: useScanner's (PickScreen's [order]
  effect removed, :900). focusPaused while the link step / verify-scan modal / cancel confirm is open. Waiting scans dropped
  on Complete (:963), cancel confirm (:1217) and cancel (:993), opening the link step (:1347) and the completion card (:949).
  The link step only renders for a fully picked order and an unscan closes it (:953-954, :1393). AwbLinkDialog refocuses
  when `linking` clears (:258-262). PickScreen's flash trigger / overlay (marked) unchanged and still drive the flash.
- **Tests:** `pickScreenScanner.browser.test.tsx` (3 × Chromium/WebKit — burst, focus after reject, focus after
  AWB_MISMATCH; all fail on origin/main), `pickScreenP1.test.tsx` (13). Existing fulfill* tests unchanged (no mock edits).
  vitest 656/656, tsc + build clean, test:browser 26/26 three runs.

**Fix — TenantContext.runAs restores the previous tenant (2026-10-04, branch `fix/tenantctx-restore` off main 4c1c8d1;
pushed, not merged, not deployed). No migration.**
- **runAs** (both overloads, `TenantContext.java:78/92`): save the thread's tenant → set → finally restore it (remove when
  there was none); RuntimeExceptions as-is, checked wrapped as before. It used to CLEAR: a runAs nested in a request, a job
  or another runAs left the rest of that work with no tenant (new transactions without GUC → RLS reads empty / UPDATEs hit
  nothing; require() throws). It broke S2's batch recording (worked around) and the Bosta fulfillment link in production
  (2026-10-03, hotfixed with re-sets in `BostaFulfillmentLinkService:250/258`).
- **Switch guard** (`enter`, `:101`): runAs to a DIFFERENT tenant while a transaction is active (the GUC is fixed per
  transaction, so the switch can't apply to it) → `tenancy.runas-switch-guard` = `warn` (default; one WARN with both
  tenants' short ids and the caller, then continue) or `throw` (tests: `src/test/resources/application.properties` and
  the global JUnit extension). Same tenant → no-op. Set by `TenantContextSettings`.
- **Converted to runAs** (mechanical, bodies untouched): the 7 ShopifyOAuthService set/clear blocks, ExceptionDigestJob,
  ExceptionImmediateAlertJob, ShopifyReconcileJob. Removed the redundant set in ShopifyWebhookProcessorJob.handleOrderUpdated,
  the S2 workaround in PackPrintBatchService, and fixed ShopifySameShopGuard's "no ambient context" comment.
  TenantContextFilter's finally-clear and ScanPairPublicController's guard unchanged.
- **Tests:** global `TenantContextTestExtension` (META-INF/services + junit-platform.properties autodetection) clears the
  context after every test and resets the guard to THROW. `TenantContextTest` (10) and `TenantContextRestoreTrapsTest` (5
  traps — print batch over HTTP, PIN switch revoke, digest detection, status poll stamping, Shopify OAuth leaves the
  request tenant; all 5 fail with clear-on-exit, checked). No existing test relied on clear-on-exit and none tripped the
  guard (full suite: only the 2 known reds).
- **Follow-ups:** (1) the TEMPORARY "SCOPE-CHECK-DIAG" log at `ShopifyInventoryService:679` (2026-07-31) is still in —
  remove once the scope-check question is closed (separate change). (2) `BostaFulfillmentLinkService:250/258` re-set the
  tenant after `webhookJob.process()` — now redundant (harmless); the Bosta-linking owner can drop them.

**DB / job reliability — Build 2 (2026-10-04, branch `fix/reliability-build-2` rebased on main da0a21a; not merged,
not deployed). V136, V137. Three commits: B4, A, B1–B3.**
- **B4** — no worker sleeps / waits long on Bosta. BostaFulfillmentLinkService: one fetch; a rate limit (429 or limiter
  refusal) → 'retry' at the retry-after, never counted (no Thread.sleep in the worker); a link whose webhook event was
  rescheduled on a rate limit → 'retry' too; the retry sweeper runs every 2 min (was 10). BostaRateLimiter:
  `bosta.rate-limit.background-max-wait-ms` 5 s for BACKGROUND (USER_FACING keep 60 s) → jobs reschedule instead of
  holding a worker. (Backfill on connect skips more items under contention — discovery covers post-connect anyway.)
- **A** — Shopify webhook events (V136 retry_count / next_retry_at / superseded_at). Processor: already processed → no-op;
  ordering safety for orders/create|updated, products/create|update (a LATER event for the same resource applied, or the
  stored raw.updated_at strictly newer → superseded, never applied); no DB connection (CannotCreateTransactionException /
  CannotGetJdbcConnectionException / SQLTransientConnectionException in the chain) → rethrown, JobRunr retries 3×; other
  failures → retry_count + next_retry_at (1, 5, 15, 60, 240 min) up to `shopify.webhook.retry.max-attempts` 5.
  ShopifyWebhookRetrySweeper (*/2): due failures + never-processed events older than 30 min within 48 h, ids listed on
  the owner pool (as the link retry sweeper), claimed under the tenant with a 30-min lease, re-enqueued. Legacy failures
  (pre-V136, next_retry_at NULL) only via ShopifyWebhookReprocessService: dry run (SUPERSEDED / WOULD_REPROCESS) by default,
  apply through process(); `POST /api/v1/shopify/webhooks/reprocess?apply=` or `SHOPIFY_WEBHOOK_REPROCESS_ON_STARTUP` (+ `_APPLY`).
- **B1** — the AWB scan / exchange mapping's Bosta _id fetch runs after commit in ProviderDeliveryIdJob (BACKGROUND;
  rate limit → reschedule ≤ 6×; failure → provider_id_fetch_failed as before). `com.traceability.jobs.AfterCommit` hands
  after-commit work to a virtual thread so the caller's connection isn't held while JobRunr waits on the owner pool; a
  failed enqueue sets the flag. (PackCompleter's guard already kept pack completion off this path.)
- **B2** — discovery overlap guard is a lease (V137 `courier_accounts.discovery_lease_until`, 15 min, released in finally,
  expires on crash) instead of a session advisory lock pinning an app connection for the whole cycle.
- **B3** — FulfillmentTrackingCapture's link-job enqueue goes through AfterCommit (virtual thread); a failed enqueue makes
  the row 'retry' in 60 s (never counted).
- Tests: BostaBackgroundWaitTest bw1–bw2; FulfillmentLinkRlsTest rr5 rewritten (no in-worker retry), rr6 new, rr1 waits
  for the async enqueue; ShopifyWebhookRetryTest wr1–wr8 + rp1–rp2; ProviderDeliveryIdTest t2/t3 run the after-commit job;
  PackScanFetchAfterCommitTest pa1–pa2 (replaces build 1's PackScanFetchPriorityTest); BostaPollJobTest p18 (lease) and
  p21 (no connection held during Bosta calls); FulfillmentCaptureEnqueueTest ce1–ce2; migration counts 136 / 81.
  Revert-checked: 4 (B4) + 11 (A) + 8 (B1–B3), each RED.
- **8 workers after B4:** recurring jobs alone peaked at 12 concurrent in prod (36 h — the hourly alignment; ≥ 4 running 30%
  of the time). Long ones: status poll (p50 215 s), discovery (~85 s), link-retry sweep, exception sweep (~30 s),
  reconciliation (~25 s), daily digest (~65 s). With B4 nothing sleeps for minutes any more, so 8 leaves ≥ 2–4 workers for
  webhooks even at the alignment; 10 gives more margin (≤ the 12-connection app pool; idle-waiting workers hold none).

**Review mode S7 — click-to-scan helpers + reviewer connect path + ops hardening (2026-10-04, branch
`feat/review-tenant-s7` off main 9518feb, worktree `.claude/worktrees/review-s7`; not merged, not deployed). No migration.**
- Capability: `ReviewCapabilities` (scanHelpers = is_demo OR simulated; demoMode = is_demo); `/me` carries both.
  `GET /api/v1/scan-helpers/{context}` (pieces?variantId / waybills / pickup / returns / lookup) — 404 unless scanHelpers.
- Screens (`ScanHelperChips` → the screen's own scan handler): pick screen (pieces + "Use this AWB"), waybill pack
  session (waybills → pieces), pickup session, return session, Lookup. No-stock hint → Receiving. Station exit without
  password: demo only. `DEMO_TENANT_ID` no longer read by any screen (constant kept for tests).
- Fix A: NotLinked "Open Traced" → `/settings?tab=connections&shop=<shop>` (only *.myshopify.com) + "reload this page";
  RequireAuth passes `state.from`, Login returns to it (in-app paths only, `loginReturnPath.ts`); the Shopify card
  prefills the reviewer connect form from `?shop=` (validated). Fix B: the no-stock hint.
- Ops: `OpsSecretFilter` before body parsing; `HttpMessageNotReadableException` → 400 BAD_REQUEST_BODY.
- Tests: `ScanHelpersTest` h1–h6 (h4 = every candidate accepted by the real scan endpoint; h6 app_user + RLS),
  `OpsHardeningTest`, `OpsSecretFilterTest`, frontend `scanHelperScreens` (11), `reviewerConnectPath` (7); edited with
  approval: fulfillDemoScanHelper, fulfillDemoAwbHelper, stationExitDemo (+ review-tenant case), embeddedNotLinked (href),
  RlsCoverageTest (EXEMPT entry). Revert-checked: 8 backend + 15 frontend mutations, each red.
- **Review notes (reviewer steps):** install → NotLinked → Open Traced → sign in (reviewer@tracedtech.com) → lands on
  Settings → Connections with the shop prefilled → "Connect" (For Shopify reviewers) → approve in Shopify → reload the
  admin tab. Automatic after connect (ShopifyImportJob): "Traced Main Warehouse" location created in their store and
  linked (Settings → Locations: Shopify sync = linked), products imported, active variants activated there. Then
  Receiving → New Session → add 2 units of a product → Finalize (pieces + labels; +2 at the Traced location in Shopify)
  → place an order in Shopify for it → it appears in Pick & Pack with a simulated waybill → open it → tap Scan on a
  piece → Print waybill (simulated PDF; required before Complete) → Complete → "Use this AWB" → Pickups: new session →
  tap Scan → Close.

**DB / job reliability — Build 1 (2026-10-04, branch `fix/db-pool-jobrunr-timeouts` rebased on main 53e2b2a; not merged,
not deployed). No migration.**
- Step 0 (prod): 10-03 06:18:55 UTC, 50 Femine orders/updated in 8 s → JobRunr released 56 jobs in one poll onto a 5-connection
  app pool (discovery pinning 1) → 9 failed "Could not open JDBC Connection" (5 s timeout). A failed event is caught, stored
  with process_error, and the job ends SUCCEEDED — never retried; Shopify never redelivers (200). Lag p50 5.9 s / p95 13.6 s
  is JobRunr pickup (15 s poll); processing p50 0.67 s. 38 failed in 30 d + 1 never-enqueued; all superseded except Jumi
  #385329559470 (differs only in Shopify's shipment_status) — nothing missing.
- Pools (`DataSourceConfig`, application.yml): app_user 5 → 12 (`app-pool`), owner (postgres) 2 → 4 with connection timeout 3 s
  (`traced.owner-pool.*`), leak detection 20 s on both (`traced.datasource.leak-detection-ms`). Supavisor session mode: 15 per
  user+db (Nano), max_connections 60 — these are the app's only two pools (DemoSeeder borrows from the owner pool).
- JobRunr: `worker-count: 8` (was cores × 8 × 2 virtual threads), `poll-interval-in-seconds: 5` (was 15).
- v0 `BostaHttpGateway`: Spring constructor sets a JDK request factory — connect `bosta.http.connect-timeout` 5 s, read
  `bosta.http.read-timeout` 20 s (there was no read timeout). Resilience4j: 3 attempts, 1 s apart → one call ≤ 77 s. The
  hand-wired constructors (tests bound to MockRestServiceServer) are unchanged. BostaV2Client already had its own (5 s / 30 s;
  create 5 s / 20 s).
- `BostaRateLimiter.userFacing(...)`: a per-thread priority override; ShipmentLinkService.fetchAndStoreProviderDeliveryId
  (pack scan / pack completion / exchange mapping) now fetches USER_FACING. Still inside the transaction (Build 2).
- Shopify webhook endpoint: the enqueue gets `shopify.webhook.enqueue-timeout-ms` (2 s) on a virtual thread; timeout or
  failure → logged, still 200, the row stays unprocessed for Build 2's sweeper.
- Tests: PoolAndJobRunrConfigTest cf1–cf4 (enables the job server for itself), BostaGatewayTimeoutTest gt1,
  PackScanFetchPriorityTest pp1, ShopifyWebhookEnqueueDeadlineTest wd1–wd3. Revert-checked (each RED): pool 5 / owner 2 /
  no owner timeout / no leak detection → cf1/cf2; default workers / poll → cf3; no gateway timeouts → cf4 + gt1; pack fetch
  BACKGROUND → pp1; 60 s enqueue deadline → wd1; enqueue failure escaping → wd2.
- **Open (8 workers can starve):** recurring + one-off jobs peaked at 11 concurrent; fulfillment-link jobs sleep in the worker
  during 429 backoff (55 concurrent × ~180 s at 10-03 14:03 UTC). See the Build 2 plan (no worker ever sleeps or waits on the
  limiter for long; reschedule instead).

**Review mode S6 — reset the review tenant (2026-10-04, branch `feat/review-tenant-s6` off main bdb7036, worktree
`.claude/worktrees/review-s6`; not merged, not deployed). No migration.**
- `scripts/ops/review-tenant-reset.sql`: `psql "<conn>" -v ON_ERROR_STOP=1 -v tenant_id=<id> [-v commit=yes] -f …` —
  DRY RUN (ROLLBACK) unless `-v commit=yes`. Guards: not Jumi / Snouts / demo / is_demo; exists; flagged; the ONLY row in
  `tenant_courier_simulation`; owner reviewer@tracedtech.com; no courier account. Keeps the tenants row, users,
  locations (Shopify link cleared — provisioning only links a location whose shopify_location_id IS NULL, so a kept link
  would point the next reviewer's inventory writes at the old shop), the placeholder store and the flag row; deletes
  every other tenant-scoped row of the tenant (incl. the reviewer's own store rows, refresh tokens, audit log) in
  repeated FK passes, aborting on no progress. Then proves: no tenant row left outside the kept set, kept rows intact,
  every other tenant's rows + every global table (JobRunr's excepted) unchanged (row counts, same transaction).
- Seeder: reuses the kept placeholder store; seed requires a CLEAN tenant (no products / orders / pieces / receipts —
  FIXTURE_EXISTS otherwise; replaces the old review-fixture-order-or-placeholder check).
- `ReviewTenantResetTest` r1–r5 (the real file through real psql in the container): guards refuse + DB unchanged; dry
  run rolls back; commit keeps exactly the kept set, locations unlinked, others unchanged; re-seed = the whole fixture on
  the same placeholder store; not clean → FIXTURE_EXISTS. Revert-checked: protected guard off → r1; one-flagged guard
  off → r1; placeholder deleted → r2–r4; unlink off → r3; dry run commits → r2; stray delete of another tenant's rows →
  the script's own check aborts (r2–r4); seeder doesn't reuse the store → r4–r5; old store-based clean check → r4.
- **Rehearsed** on a local restore of the 2026-10-02 dump with the prod purge applied (17 tenants) and migrated to V134:
  step A (service) → step B (flag script, psql) → step C + a reviewer round's data → reset refused for Jumi, Snouts and a
  real merchant even with commit=yes → dry run (3 FK passes, rolled back) → commit → re-seed → second reset + re-seed.
  A content hash of every row outside the review tenant (65 tables) identical before, during and after all of it.
- Runbook per round: reset dry run → read NOTICEs → `-v commit=yes` → step C (seed) → hand the reviewer the login.
  A JobRunr job still queued for the old reviewer store finds its rows gone (global tables untouched by design).

**Review mode S5 — the review tenant (2026-10-03, branch `feat/review-tenant-s5` off main 836ffd0, worktree
`.claude/worktrees/review-s5`; not merged, not deployed). No migration. Needs `TRACED_OPS_SECRET` in the server .env.**
- **Ops endpoints** (`review` package): `POST /api/v1/ops/review-tenant` (step A) and `POST /api/v1/ops/review-tenant/{id}/seed`
  (step C), behind `OpsSecretGuard` — read from `traced.ops-secret: ${TRACED_OPS_SECRET:}` (application.yml); unset/blank →
  404, missing/wrong `X-Ops-Secret` → 403 (MessageDigest.isEqual). `/api/v1/ops/**` permitted in SecurityConfig (no JWT).
- **Step A:** `AuthService.signup` (owner password from the request, never logged / echoed — `CreateRequest.toString` redacts;
  default fulfillment location; `@tracedtech.com` → no attribution) + `UserService.create` worker with PIN. Non-@tracedtech.com → 400.
- **Step B:** `scripts/ops/review-tenant-flag.sql` (`psql -v ON_ERROR_STOP=1 -v tenant_id=<id> -f …`): refuses Jumi / Snouts /
  demo / unknown / owner ≠ reviewer@tracedtech.com / courier row / non-disconnected store; already flagged → NOTICE.
- **Step C (`ReviewTenantSeeder`):** placeholder store, 5 products × 3 variants (images: 5 generated flat illustrations,
  `frontend/public/assets/review/*.webp`, generator `scripts/dev/make_review_assets.py`), receipt + 45 pieces via
  `InventoryLedger.batchReceive` (counters advance), 13 orders: R1001–R1002 in print batch #1 + packed, R1003 in batch #1 only,
  R1004 with_courier, R1005 delivered, R1006 delivered + approved refund request RR-REVW2, R1007 on hold, R1008–R1013 ready
  (R1008 / R1011 two units). Each step sets the tenant itself (runAs doesn't nest); not one transaction — a partial run is
  refused next time (FIXTURE_EXISTS) → S6 reset. Queue-mode packing leaves orders `packed` (not awaiting_pickup).
- **Fail fast:** every seed step is named (`step(label)`: "create #R1004", "pack #R1002", "pickup scan #R1004", …);
  a throw logs `Review seed FAILED at step '<label>' (tenant …): the fixture is PARTIAL` and rethrows. A partial
  fixture stays inside the tenant: every write is app_user under that tenant (RLS WITH CHECK), the placeholder
  domain is `.invalid` (reserved, never a real shop), the only global side effect is `simulated_tracking_seq`.
- **`ReviewTenantRlsTest` (o4b, o4c):** the seeder + its whole service chain hand-wired over an app_user
  TenantAwareDataSource (the *RlsTest pattern) with @Transactional proxies on the app_user tx manager — o4b =
  o4's assertions (shared `ReviewFixtureAssertions`) + app_user with no / another tenant sees none of it; o4c = a
  failing step is logged with step + order and rethrown, other tenants' rows and global tables unchanged, the next
  seed refuses (FIXTURE_EXISTS). Nested `TenantContext.runAs` did NOT lose the tenant in any step (each step's
  GUC is set at its transaction's begin). Revert-checked: variant inserts outside a transaction (no GUC) → o4b
  red on RLS 42501 while postgres-run o4 stays green; step logging removed → o4c red.
- Tests `OpsSecretGuardTest`, `ReviewTenantTest` o1–o6 + o8 (random password + secret per run; none in the log), static
  `ReviewTenantSeederGuardTest` (o7) — all revert-checked: no secret check → o1 + guard test; no email check → o1; no flag
  check → o3 (o4, o6); no fixture marker → o5; raw piece inserts → o4 + o7; owner-pool reference → o7; SQL protected-tenant
  guard off → o8.
- **Deploy order:** add `TRACED_OPS_SECRET=<long random>` to the server .env → deploy → step A (curl with X-Ops-Secret) →
  step B (psql) → step C (curl) → log in as reviewer@tracedtech.com.

**Bosta global rate limit + rate-limit-as-reschedule + re-process + discovery pre-connect skip (2026-10-04, branch
`feat/bosta-global-limit`, rebased on main 0fabeef; not merged, not deployed). V135.**
- Cause (prod 2026-10-04 00:28:59): the first v2 discovery run enqueued 130 webhook jobs; their verify-by-fetches (≈2 req/s
  combined, ~1.5 req/s with discovery) tripped Bosta's server-wide limit (~60–90/min) → 429 retry-after 300 s for EVERY
  key (BROEK + Femine in the same second; idle blnco / Jumi refused on their first call; all let back in together at
  ~00:33:59). The per-key limiter turned each block into instant refusals (countdown 300…288 s) and the webhook job
  marked every one failed — 78 deliveries stranded behind their idem keys (+87 older rate-limited failures since July).
- `BostaRateLimiter`: a GLOBAL bucket on top of the per-key one — `bosta.rate-limit.global-per-second` 0.75,
  `global-burst` 3, `background-reserve` 0.2 (background takes a global token only while ≥ 20% of the burst stays for
  user-facing calls; USER_FACING also jumps the queue). A 429 on ANY key blocks that key and the global bucket for its
  retry-after; `onRateLimited(key, secs, context)` logs every 429 (`Bosta 429 (<context>): retry after Ns — key <8 hex>
  and ALL keys blocked until …`), v2 search / create / pickup-locations / v0 included. The 3-arg constructor stays
  per-key only (hand-wired tests); test `application.properties` sets `global-per-second=0` so one test's simulated 429
  can't block the shared Spring context — the global layer is tested directly.
- `BostaWebhookJob`: a BostaRateLimitException at verify-by-fetch (Bosta's 429 or the limiter's > max-wait refusal) keeps
  the event 'pending', counts `rate_limit_retries` (V135), and schedules process() again after max(retry-after,
  60 s × 2^(n−1)) capped at `bosta.webhook.rate-limit-max-delay-seconds` (1800) + jitter (≤ 60 s × min(n,5)); failed
  only after `rate-limit-max-retries` (8), naming the cause.
- `BostaRateLimitedReprocessService`: failed events with error `Bosta fetch error: Bosta rate limit…`, per tenant (RLS,
  tenant-bound): SKIP (no active account / superseded by a later processed-or-pending event) | dry run WOULD_REPROCESS |
  apply claims failed→pending and runs BostaWebhookJob.process() → REPROCESSED <outcome> / RESCHEDULED / FAILED.
  Logs `BOSTA_REPROCESS {json}` + `BOSTA_REPROCESS_SUMMARY`. Owner `POST /api/v1/bosta/reprocess-rate-limited?apply=`
  or startup `BOSTA_REPROCESS_RATE_LIMITED_ON_STARTUP=<ids>|all` (+ `BOSTA_REPROCESS_RATE_LIMITED_APPLY=true`).
- Discovery pre-connect: `ingestListItem` runs `PreConnectDeliveryFilter.shouldIgnore` on the list item (same rules —
  both sides of ':', shopifyInfo.orderId, createdAt vs cutoff, NULL cutoff never) before anything else: an ignored
  delivery gets ONE processed `ignored_pre_connect: <tn>` row (its idem key, no job, no fetch); once it has one (any
  source), later states write nothing. Note: the webhook_events idem index is global (source, key) — fine, Bosta
  tracking numbers are unique.
- Tests: BostaGlobalRateLimitTest g1–g6, BostaWebhookRateLimitRescheduleTest rs1–rs3, BostaRateLimitedReprocessTest
  rp1–rp5, BostaDiscoveryPreConnectTest pc1–pc4; migration counts 134 / 79. Revert-checked (each RED): no global layer;
  429 not global; no user priority; no reserve; rate limit → failed; unbounded; no backoff; dry run applies; no
  superseded check; candidates not tenant-scoped; trigger not wired; no discovery pre-connect; no already-ignored check;
  already-ignored not per tenant.
- **(b)/(c) field check (not built):** v2 list item → BostaDelivery.fromRaw reads trackingNumber, state.code, type.value
  (+ type.code), numberOfAttempts, businessReference, shopifyInfo.orderId — all present. PickupAreaService reads
  dropOffAddress.city._id (present) and district._id / districtId (only when resolved — same as v0). Booking read-back
  uses _id, businessReference, createdAt (present). Matcher: receiver.fullName (first; firstName/lastName fallback),
  receiver.phone, cod — present. exceptionCode: BostaWebhookJob (raw.exceptionCode) and ExceptionService
  (raw->>'exceptionCode') read it top-level — v2 has none: use state.lastExceptionCode there.
- **Next (proposed, not built):** status poll via a `-updatedAt` search walk.

**Bosta discovery on the v2 delivery search (2026-10-03, branch `feat/bosta-discovery-v2-search` off main c41c299;
not merged, not deployed). V134.**
- Probe passed in prod (20:38, BROEK + Femine): `POST /api/v2/deliveries/search` with the tenant key → 200,
  `data.deliveries`, page / limit honoured, count always "0"; `-createdAt` newest created first, page 2 exactly after
  page 1; `-updatedAt` contiguous too. Femine created 50 deliveries within 06:18:49–06:19:44 UTC — batches are real.
- `BostaV2Client.searchDeliveriesPage` (sortBy, limit; shared limiter BACKGROUND; 429 → blocks the key for its
  retry-after + BostaRateLimitException; 5xx / IO / no data.deliveries → BostaTransientException; other 4xx →
  BostaException; never retried). `BostaDiscoveryPollJob` walks it: `-createdAt`, `bosta.poll.discovery-page-limit`
  (50), until items created before the mark minus the overlap, an empty or short page, or
  `bosta.poll.discovery-max-pages` (default now 10 = 500 deliveries; env BOSTA_POLL_DISCOVERY_MAX_PAGES) / the item
  ceiling. Mark advances only on a complete walk; capped walk resumes (head first, then shifted by the new ones).
- Repeat-page guard: same first/last tracking as the page before, or every item already seen this run → WARN, stop,
  nothing saved (mark kept, no walk position).
- V134 clears `discovery_walk_page` / `discovery_walk_newest_at` (a v0 position means nothing on v2) — the first v2
  run starts at page 1 and walks back to the existing `discovery_mark_at`. `discovery_short_page_logged_on` is left
  in place, unused (the short-page warning is retired with v0 paging).
- List items used directly: `BostaIngestionHelper.ingestListItem` builds the event from the item — trackingNumber,
  state, type, updatedAt (idem key), plus businessReference, uniqueBusinessReference, shopifyInfo.orderId
  (`shopifyOrderId`), creationTimestamp in the payload — no per-delivery fetch in discovery. An item without
  state / type / updatedAt, or with a (state, type) the mapper doesn't know (the v2 list's labels may differ from
  v0's), falls back to one fetch — never dropped. Pre-connect filter, status mapping, retry list, Guard 3, idem
  key and limiter unchanged. The 2 s inter-fetch pause no longer applies to list items (retry pass keeps it).
- **Still one v0 fetch per NEW delivery:** BostaWebhookJob's verify-by-fetch is untouched (its v0 response becomes
  `shipments.raw`, which PickupAreaService / booking read in v0 shape). Dropping it for discovery-sourced events is a
  separate decision once the v2 item is confirmed to carry the same fields.
- Tests: new `BostaV2SearchPageTest` sq1–sq3, `BostaDiscoveryPagingTest` rewritten to the v2 contract (v1–v10);
  `BostaPollJobTest` p6–p8/p15–p20, `BostaDiscoveryRetryTest` dr1–dr5 and `BostaDiscoveryKillSwitchTest` moved from
  the v0 list stub to the search stub (fetch-count proxies → discovery-event counts; dr1–dr3's failing items lack
  updatedAt so they take the fallback fetch); `BostaSearchItems` fixture helper; migration counts 133 / 78.
  Revert-checked (each RED): no repeat guard → v4,v5; V134 a no-op → v6; always fetch → v7,v8,p15–p17; short page not
  the end → v1; mark advanced on an incomplete walk → v2–v5,v9,v10; wrong sort → v1; 429 not blocking the key → sq2;
  unmappable item dropped → v8,dr1–dr3; extra field dropped → v7.
- **Not built (noted):** (1) status poll via `-updatedAt` search — one walk down to the last poll's updatedAt
  instead of one fetch per in-flight shipment: ~730 req/h → ~20–60 req/h for a big tenant (~95% less);
  (2) BostaBackfillJob (on connect / Sync button) still pages the v0 list with the 10-per-page cap — same blind
  spots as old discovery; (3) BostaSearchProbe can be removed once this is live.

**Review mode S4 — mute list (2026-10-03, branch `feat/review-simulated-courier-s4` off main cf92cf6, worktree
`.claude/worktrees/review-s4`; not merged, not deployed). No migration. Built BEFORE S5 (Marawan's reorder).**
- `ExceptionService.detectAllOpen`: `detectStuck` and `detectCancelledWithLiveShipment` skipped for a simulated-courier
  tenant (`CourierSimulation.isSimulated`); every other detector unchanged.
- `ExceptionImmediateAlertJob` / `ExceptionDigestJob`: tenant list = `is_demo = false AND NOT EXISTS
  tenant_courier_simulation` (owner pool) — no exception email ever goes to a simulated tenant.
- `ShopifyInventoryService.claim()`: simulated tenant + variant whose external_id isn't `gid://shopify/…` → returns false
  before the INSERT (no claim row → no Shopify call → no failed-claim alert); callers already treat false as "nothing to
  do". Covers every per-piece and increment trigger (receiving, restock, hold exit, damage move, void, hold enter,
  exchange dispatch, stock-take found, retries). `StockTakeReconciliationService.finalizeSession`: same rule in the
  per-variant delta query (a write-off of only fixture variants → `nothing_to_push`).
- Tests `ReviewModeMuteTest` m1–m7, each with a real-tenant control — all revert-checked (ExceptionService → m1 m2;
  immediate job → m4; digest job → m5; ShopifyInventoryService → m6; StockTakeReconciliationService → m7).
- **Activation + location seed (added to S4 on Marawan's review):** `ShopifyCatalogActivationService.activateAll` and `ShopifyInventoryReconcileService.loadVariants` (report + seed) leave a simulated tenant's non-gid variants out BEFORE the batch item-id read, so a seeded variant can never fail that read for the reviewer's real variants. Real tenants: no filter (a non-gid variant on a real tenant still fails as before). Tests `SimulatedActivationSeedTest` v1–v4 (v2 / v4 real-tenant controls) — revert-checked: activation → v1; seed → v3.

**Bosta v2 delivery-search probe (2026-10-03, branch `feat/bosta-search-probe` off main bf880c1; committed, not merged,
not deployed).**
- Marawan found the dashboard's working paging contract (The Snouts account): `POST /api/v2/deliveries/search`
  `{"stateCodes":[],"limit":50,"page":N,"sortBy":"-updatedAt"}` → `data.deliveries` (page / limit honoured,
  `count` always 0). The dashboard uses a session token — the probe proves whether our API key works.
- `BostaV2Client.searchDeliveries` (read-only query as a POST body; raw key as Authorization like every v2 call;
  shared rate limiter, BACKGROUND; never retried) + `BostaSearchProbe`, run by the visibility check's startup
  trigger after its report: (a) `-createdAt` and (b) `-updatedAt`, limit 50, pages 1–2; (c) `createdAt:-1` and
  `-creationTimestamp` only if (a) fails. One `BOSTA_SEARCH_PROBE {json}` line per call: status, items, first/last
  tracking + createdAt / creationTimestamp / updatedAt, newestCreatedFirst, overlapWithPage1, response / data /
  item keys, count / echoed page and limit. Writes nothing.
- Stop-gap until discovery switches (config only, not applied): `BOSTA_POLL_DISCOVERY_MAX_PAGES=1`.
- Tests `BostaSearchProbeTest` sp1–sp4 (mutations: body key, limiter bypass, fallbacks always, overlap — each RED).

**Bosta discovery paging + shared per-key rate limit (2026-10-03, branch `fix/bosta-discovery-paging`, rebased onto main
cf92cf6 after S3; not merged, not deployed).**
- **Cause:** Bosta's `GET /api/v0/deliveries` returns at most 10 items per page whatever pageSize asks for
  (prod BROEK: pageSize=50 → 10 items, reportedCount 45,498), next page offset by the requested size. Discovery read
  pages 1–3 of "50" → items 1–10, 51–60, 101–110; 11–50 and 61–100 were never seen (BROEK's 32 from the 09-30 / 10-01
  Shopify-app batches, Femine's burst of 79). The "stuck" BROEK mark (5829813860, 10-01 16:55 → 10-03 ~10:29) was
  simply no new delivery at the head; it moved once BROEK created new ones (mark 1177840993 on 10-03).
- **Paging (V133):** pageSize 10, pages 1, 2, 3 … newest first, dedup by tracking within the run; complete when it
  reaches a delivery created before `discovery_mark_at` − 10 min overlap, or an empty page; cap 30 pages (or the
  fetch ceiling) → incomplete: mark kept, `discovery_walk_page` / `discovery_walk_newest_at` stored, WARN; next run
  takes the new head first (exact compare with walk_newest_at — a same-second batch must not trap the head pass),
  then continues shifted by the number of new deliveries (one page earlier for overlap). Short page mid-list →
  WARN once per tenant per day (`discovery_short_page_logged_on`). First run after V133 walks back to
  max(connect cutoff, min(old mark's createdAt, now − 7 days)). Retry list unchanged. List items carry
  `creationTimestamp` / `createdAt` (`SlimDelivery.createdAt`, 3-arg constructor kept).
- **Rate limit:** `BostaRateLimiter` — one token bucket per API key (hashed), default 1 req/s burst 2, used by
  every call in BostaHttpGateway (list / fetch = background; mass-awb, pickup, connect = user-facing) and
  BostaV2Client (create, pickup-locations = user-facing). A 429 blocks the key for its retry-after for everyone;
  user-facing waiters go first; a wait over 60 s becomes a BostaRateLimitException. One JVM (prod runs one).
- **Tests:** `BostaDiscoveryPagingTest` pg1–pg7 (all RED on 690b4ce's job), `BostaRateLimiterTest` rl1–rl6.
  Existing tests changed only for the marker contract (tracking → creation time): BostaPollJobTest p15, p17, p19,
  p20; BostaDiscoveryRetryTest dr1, dr2, dr3 (+ markAt helper, fixture creation times). MigrationSmokeTest 132 files,
  NotTracedBackfillTest 77.
- **Steady-state load (prod 2026-10-03, read-only):** poll set BROEK 97, Femine 97, blnco 25, Jumi 3, Snouts 4,
  demo 13 shipments. The status poll fetches each with a 2 s inter-fetch delay, tenants one after another, so a
  run takes ~4.5 min on average (cron */3 — overlapping fires are skipped) and a shipment is re-polled every
  ~4–20 min. Per key per hour: status poll ≈ 700–750 (BROEK, Femine) / ≈ 20–200 (others), webhook verify-fetch
  ≈ 10, discovery ≈ 30–60 list pages + a few fetches, link jobs ≈ 5–7 → ≈ 800/h ≈ 0.22 req/s of the 1 req/s
  budget (≈ 4.5× headroom). The limiter isn't the status poll's bottleneck — the fixed 2 s delay and the
  sequential tenant loop are: interval ≈ 2 s × Σ min(active, 200) over all tenants (today ≈ 8 min). Grows
  linearly with active shipments; not a problem until several hundred are active. Follow-up if needed: drop the
  inter-fetch delay to ~1 s (the limiter now enforces the per-key rate) or poll tenants in parallel.
- **Migration number:** S3 merged first with V132__simulated_tracking; this is V133__bosta_discovery_paging.
- **EXC-8854860251 (read-only):** a BROEK type-30 exchange created through Bosta's API (10-03 10:47 UTC),
  businessReference `BRK-44868-EG:BRK-44868-EG-R1` (post-connect order BRK-44868-EG, delivered). By design an
  exchange's replacement ships as its own internal order (tryAutoMap committed it, map-time leg) — that's the EXC-
  order, not a mislink. Its link to the original order is ExchangeMatchService's phone/description match, which
  can't work for BROEK (no phones) → `unmatched`. The ':' reference is never used there. Proposal (not built):
  match_method 'reference' when the part before ':' resolves to exactly one post-connect order. No other
  post-connect reference-bearing EXC- placeholders (3 Snouts dashboard exchanges have no reference).

**Review mode S3 — auto-shipment on order ingest (2026-10-03, branch `feat/review-simulated-courier-s3` off main
690b4ce, worktree `.claude/worktrees/review-s3`; not merged, not deployed). Migration V132.**
- **V132:** `simulated_tracking_seq` (7770000000001–7779999999999, app_user USAGE) + INVOKER trigger
  `shipments_reserved_tracking_simulated_only` (a `^777\d{10}$` tracking number on a real tenant → check_violation).
- `SimulatedShipments.ensureForwardShipment(jdbc, tenant, order)` — one INSERT … SELECT … ON CONFLICT DO NOTHING:
  simulated tenant only, order not cancelled (Traced status or REST `cancelled_at`; the GraphQL import fetches no cancel
  field), no active forward leg; provider bosta, 'created', the order's COD. Called in `ShopifySyncService.ingestOrderWebhook`
  and `upsertOrder` (import / reconcile / missing order) right after the blocklist gate, inside the order transaction.
- Behaviour: edits / replays / reconcile → no second shipment, same number, no sequence consumed; cancelled later →
  shipment untouched, order leaves the queue; on hold → shipment created, order held until released; self-pickup is an
  operator conversion later (shipment left alone); FR-18 pre-connect orders never ingested; fulfillment-link skips
  ("active forward leg") before any Bosta call; packing the existing shipment never runs the provider-id fetch.
- **Would fire for a simulated tenant → S4 mute list:** `ExceptionService.detectStuck` (`stuck_shipment`, HIGH — a
  'created' / 'with_courier' simulated leg never syncs, so after `stuck_shipment_days` it fires) and
  `detectCancelledWithLiveShipment` (`cancelled_live_shipment`, HIGH — a cancelled order keeps its 'created' simulated leg).
  Both are HIGH → `ExceptionImmediateAlertJob` emails + the daily digest. Checked and silent: discovery / status poll /
  order reconcile / visibility (active courier rows only), fulfillment-link (skips: active leg / no account; its
  `fulfillment_link_problem` needs conflict / gave_up), `missing_provider_id` (only for a shipment CREATED by an AWB link —
  never ours), `delivery_limbo` / NDR / high attempts (Bosta state data), `missing_awb` (same exclusions as real),
  `bosta_discovery_failed` (no discovery), not-traced tagging (Bosta webhooks only).
- Tests `SimulatedAutoShipmentTest` a1–a11 + a9b — all revert-checked: no webhook hook → a1 a2 a6 a7 a8 a9 a9b a11; no
  import hook → a3; no flag check → a4; no cancel check → a5; no NOT EXISTS → a2 (sequence consumed); no trigger → a10.
  a9 asserts the order in GET /fulfill/queue (the endpoint both Pick & Pack modes read — Fulfill.tsx queue view and
  WaybillPackPage tiles) with the tenant in `order_queue` AND `waybill_scan`, awaiting-waybill count 0, then print →
  decoded top barcode → waybill-scan session → packed.
- Approved existing-test edits: MigrationSmokeTest 130 → 131 files, NotTracedBackfillTest 75 → 76; `SimulatedCourierFlowTest` fixture line 123 — real tenants now get `PackFixtures.nextTracking()` (the V132 trigger correctly refused the 777… numbers it gave them), no assertion changed.
- **S4 mute list (decided by Marawan 2026-10-03): skipped for simulated-courier tenants** — `stuck_shipment` (`ExceptionService.detectStuck`), `cancelled_live_shipment` (`detectCancelledWithLiveShipment`), and exception emails (`ExceptionImmediateAlertJob` immediate CRITICAL/HIGH + `ExceptionDigestJob` daily digest).
- **Gotcha:** `tenants.pick_pack_mode` values are `order_queue` / `waybill_scan` (V126 CHECK), not `queue`.

**Review mode S2 — simulated waybills, pickups, booking refusal (2026-10-03, branch
`feat/review-simulated-courier-s2` off main 94c4a4c, worktree `.claude/worktrees/review-s2`; not merged, not deployed).
No migration (the next review migration, S3's tracking sequence, takes V132).**
- **Waybills:** `SimulatedWaybillRenderer` (PDFBox + ZXing, A4/A6, one page per tracking number): TOP Code 128 in Bosta's
  real shape `G - 0 2 - ` + spaced digits (fixture `AwbSpacedBarcodeTest.spaced()` / `TrackingNumberSpacesTest`), plain
  bottom Code 128, QR `BOSTA_<tn>`, tracking number as extractable text, order / customer / phone / address / COD,
  "SIMULATED — not a Bosta shipment" banner (EN + AR, shaped via LabelTextFitter). `BostaAwbService.printAwbDetailed`
  short-circuits for a simulated tenant before the account lookup → `printSimulated`; steps 2–3 extracted to
  `loadPrintable` so both paths share the pre-filter. Single + batch print unchanged for callers (batch: orderGuaranteed).
- `FulfillService` `shipment_has_courier` OR simulated → Print Waybill offered. `PackPrintBatchStore.defaultPaper` unchanged
  (no courier row → A4; comment added).
- **Pickups:** `BostaPickupService.schedulePickup` → `scheduleSimulated`: same awaiting-pickup query (now the shared
  `AWAITING_PICKUP_SQL`), pickup with `courier_account_id` NULL, `provider_pickup_id` `SIM-PU-` + 12 hex, mode SIMULATED,
  never `createPickup`. Audit: no query joins pickups to courier_accounts; every pickup list / detail / manifest uses
  LEFT JOIN users only, and pickup sessions already have NULL couriers — nothing else needed. Pickup sessions (open → scan
  the simulated label → close → shipment + pieces with_courier) work unchanged.
- **Booking:** `ReturnPickupBookingService` book-now / retry / not-booked / confirm → 409 `REVIEW_MODE_UNAVAILABLE`
  ("Not available in review mode." / "غير متاح في وضع المراجعة."); `book()` (the job) returns before any claim. Frontend:
  the four booking calls use `transferCommandRequest`; ExchangeProgressView + ReturnRequestDrawer show the typed message.
- **Phone-as-scanner (S6):** the phone POSTs to /scan-pair/scan; the relay stores the code (edge-trimmed, ≤ 200 chars) and
  the tablet applies it through the same `onScan` → the same /pack-sessions/{id}/waybill endpoint. e3 proves the relay keeps
  the decoded top barcode byte-for-byte and that it opens the order.
- Tests: `SimulatedWaybillRendererTest` w0–w4, `SimulatedCourierFlowTest` e1–e3 / p1–p4 / k1–k3 (e1 decodes the rendered
  top barcode with ZXing at 300 dpi → normalizer → WaybillResolver AND the real waybill-scan session), `ReviewModeBookingTest`
  r1–r3, `frontend/src/test/reviewModeBooking.test.tsx` — all revert-checked (no text layer → w1–w4; plain-digit top barcode
  → e1–e3, k3; no QR → same; no print short-circuit → e1–e3, p1, p2, k3; FulfillService + no pickup branch → p3, k1; booking
  guards → r1, r2; frontend → the Book-now test). Sample A4 waybill: `target/simulated-waybill-sample-A4.pdf` (p1).
- **Real-path regression guard:** `RealPrintPathRegressionTest` g1 (single print: exact tracking numbers in caller order — ids passed in reverse insertion order — duplicates dropped, other-tenant / unknown ids skipped, chunks 49 + 3, account A6 / ar, exclusions delivered / returned / CRP in input order and recorded on the rows, result shape) and g2 (batch print: one call, oldest-first order, requested paper, result shape). Passes unchanged against the pre-S2 BostaAwbService (main 94c4a4c) — the loadPrintable refactor changed no real-path behaviour. Mutation-checked: database order → g1 + g2; chunk size 50 → g1; CRP printable → g1. The UNLINKED exclusion is unreachable (shipments.order_id NOT NULL), before and after.
- **Gotcha (tests):** a revert check that removes a constant a test references fails to COMPILE and leaves the previous
  surefire report in place — delete the report first, or revert behaviour only.

**HOTFIX — fulfillment link failed after linking (2026-10-03, branch `fix/fulfillment-link-tenant-context` off
main 94c4a4c; committed, not merged, not deployed).**
- **Prod:** every link job and both catch-up applies threw `EmptyResultDataAccessException` at
  BostaFulfillmentLinkService:255 (`SELECT … FROM webhook_events WHERE id = ?`) right after the link committed.
- **Root cause:** `BostaWebhookJob.process()` wraps itself in `TenantContext.runAs`, whose `finally` CLEARS the
  ThreadLocal instead of restoring the caller's tenant. Back in the link service (inside its own `runAs`) every
  later query ran with no tenant → under app_user RLS the just-committed shipment was invisible (EXISTS false)
  and the webhook_events read returned 0 rows. Tests never saw it: they run the services on the postgres
  (BYPASSRLS) connection.
- **Fix:** the link service re-sets its tenant after `process()` (finally, and before the error path's
  markRetry); the catch-up stores a raw-only fulfillment in its own transaction; each catch-up row is
  isolated (exception → verdict ERROR, run continues, summary always logged); a 429 is retried within the run
  (retry-after capped by `bosta.fulfillment-link.max-backoff-ms`, at most `rate-limit-retries` 3) before the
  'retry' state.
- **Gotcha:** `TenantContext.runAs` does not nest — the inner call leaves NO tenant behind. Any code that calls a
  `runAs`-wrapped method (e.g. `BostaWebhookJob.process`) from inside its own `runAs` must re-set the tenant
  afterwards. Not changed globally in this hotfix (follow-up: make `runAs` restore the previous value).
- **Prod damage (read-only):** 5 shipments linked through this path 16:10–16:14 (BROEK 44876, 44839, 44889, 44866;
  Femine 70607). process() completed for all (shipment, status history, not_traced_at, reconcile flag); only
  markLinked was lost on 4 tracking rows (44876's was marked by the sweeper). Repair:
  `scripts/ops/2026-10-03-fulfillment-link-repair.sql` (4 rows → 'linked', ROLLBACK by default). 97 pre-deploy
  Bosta tracking rows (BROEK 19, Femine 78) never got a job — rerun the catch-up apply after the hotfix.
  JobRunr: 2 link jobs + 2 catch-ups FAILED, `retries = 0` → nothing scheduled; a retry would hit "already
  linked" before process() (clean no-op, proven by rr3).
- Tests `FulfillmentLinkRlsTest` rr1–rr5 (services over app_user, like prod): RED on 94c4a4c (rr1–rr4 with prod's
  exact EmptyResultDataAccessException).

**Bosta link from Shopify fulfillment (A) + catch-up (C) + discovery Step 0 (2026-10-03, branch
`feat/bosta-fulfillment-link`, own worktree ~/Documents/traceability-bosta-link, rebased onto origin/main
17613ca after S1 merged; not deployed).**
- **Shared-tree incident:** another session (review mode S1) was editing the same working tree and also added a
  V130. Split per Marawan: S1 = V130__tenant_courier_simulation (merged first), this work =
  V131__fulfillment_link, rebased after it. MigrationSmokeTest 129→130 files (V1–V131), NotTracedBackfillTest
  74→75.
- **Step 0 (read-only):** discovery lists `GET /api/v0/deliveries?pageNumber=1..3&pageSize=50` — no sort, no
  filter, no date. BROEK: lock free, 0 retry-list rows, 0 discovery events and mark 5829813860 unmoved since
  10-01 16:55, yet the 32 are FOUND by tracking with the same key. Ranked: (1) the v0 list doesn't return them
  (default filter by channel / location / business — the 09-30 batch was absent from the 10-01 13:08 listing
  that held newer and older items); (2) BROEK's list call failing since 16:55 (non-transient error → only a WARN
  "Discovery poll failed for tenant d6e1ffe7…"); updatedAt sort, marker and ceiling ruled out. The enriched
  visibility check now logs createdAt / updatedAt / creationSrc / sender / pickup fields for FOUND rows and
  page 1 of the list as discovery fetches it (BOSTA_VISIBILITY_LIST*). Unlinked 5658/5659/5660/5666/5667 are
  Femine BUSINESS_APP deliveries, post-connect, null reference and no Shopify id — kept by the filter; no link
  possible (0 of 311 Femine orders have a phone; Femine's Shopify fulfillments are Wijha only).
- **A (`BostaFulfillmentLinkService`, V131):** capture enqueues one job per (order, tracking) — after commit,
  deterministic id — for a 'bosta' row, not cancelled, link_status NULL, no forward shipment. Job: tenant's own
  key; links only type 10/20 whose reference (both sides of ':') or shopifyInfo.orderId is THIS order and
  nothing else, no active forward leg, number not on another order. Link = a 'shopify_fulfillment'
  webhook_events row with the order hint → BostaWebhookJob.process (hint honoured for that source only) →
  ShipmentLinkService.linkDeliveryToOrder (tryMatchDelivery's step 3, shared) + applyMappedState. Late link
  = BRK-44871 outcome: order stays 'new', no pieces/allocations, not_traced_at set, Bosta state + history.
  Conflict (reference/Shopify id elsewhere, both null, non-forward type, number on another order) →
  'conflict' + `fulfillment_link_problem` (HIGH). 404/errors → 'retry' (15 min doubling ≤ 2 h, sweeper
  `fulfillment-link-retry` */10) for 24 h then 'gave_up' + exception; 429 → retry-after, not counted.
  Tests `BostaFulfillmentLinkTest` fl1–fl15.
- **C (`BostaFulfillmentCatchUpService`):** candidates = 'bosta' tracking rows or REST fulfillments in
  orders.raw, not cancelled, no forward shipment; dry run (default) writes nothing; apply stores raw-only
  fulfillments as tracking rows then runs the same attempt(). Owner `POST /api/v1/bosta/fulfillment-link/
  catch-up?apply=…` or `BOSTA_FULFILLMENT_LINK_CATCH_UP_ON_STARTUP=<ids>|all` (+ `…_APPLY=true`). Logs
  BOSTA_CATCHUP / BOSTA_CATCHUP_SUMMARY. Tests `BostaFulfillmentCatchUpTest` cu1–cu3; enrichment
  `BostaVisibilityEnrichmentTest` ve1–ve3.
- **Suite:** 2,021 run, 4 skipped, only the 2 known reds.

**Review mode S1 — simulated-courier flag + shop binding rule (2026-10-03, branch
`feat/review-simulated-courier-s1` off main 6ce8cd8, worktree `.claude/worktrees/review-s1`; not merged, not deployed).**
Goal of review mode: one Shopify App Store review tenant with seeded demo data, simulated Bosta (never calls Bosta),
connectable to the reviewer's own store. Slices: S1 → S2 (simulated waybills + pickups) → S3 (auto-shipment on
Shopify ingest) → S5 (seeder, reviewer@tracedtech.com) → S4 + S7 (mute shipment exceptions/emails, Shopify writes for
seeded variants; frontend helpers) → S6 (reset ops script).
- **V130 `tenant_courier_simulation`** (tenant_id PK, created_at, note): a row = simulated courier; app_user SELECT only,
  RLS own row; two INVOKER triggers keep it and `courier_accounts` mutually exclusive. `CourierSimulation.isSimulated`.
- Bosta connect / sync / visibility-check → 409 `COURIER_SIMULATED` before any Bosta call. `/connections` →
  `bosta.simulated` (connected, no account) and, for a simulated tenant, disconnected Shopify rows hidden. Onboarding's
  Bosta step counts as done. Frontend: read-only "Simulated (review mode)" Bosta card; Business-tab AWB fields stay
  disabled with "Waybills are simulated".
- **Reserved tracking range** `^777\d{10}$` (`SimulatedTracking`); `BostaHttpGateway.fetchDelivery` / `printMassAwb`
  refuse it before any HTTP. BostaV2Client takes no tracking numbers (comment only).
- **Shop binding rule** (`ShopifySameShopGuard.boundShopDomains`, used by initiate + callback backstop + custom-app
  paths): real tenant bound by every row incl. disconnected (409 names the linked shop, pre-redirect); simulated tenant
  bound only by non-disconnected rows. Frontend: disconnected card shows "linked to X" and prefills the shop.
- **"Bosta connected" consumers for a simulated tenant** (classified a/b/c): gated now — Bosta card, Business AWB
  fields, onboarding step, connect/sync/visibility-check; S2 — `FulfillService` shipment_has_courier (Print button),
  `BostaAwbService` print (single + batch), `PackPrintBatchStore` default paper, `BostaPickupService`, return-request
  booking "Book now" (refuse cleanly "Not available in review mode"); already off — portal pickup booking / exchanges
  toggles (`PortalSettingsService` reads courier_accounts → BOOKING_NEEDS_BOSTA), return locations (V2 client needs a key);
  fine — Fulfill "Connect Bosta" hint hidden, regenerate-secret 404, poll jobs / webhooks (active courier rows only).
- Tests: `ShopBindingRuleTest` b1–b5 (initiate AND callback), `CourierSimulationTest` cs1–cs7 (incl. app_user RLS +
  grants), `SimulatedTrackingGuardTest` sg1–sg4, `frontend/src/test/shopifyLinkedShop.test.tsx` f1–f3 — all
  revert-checked (shop rule → b1, b3 red; backstop only → b3; controllers → cs1, cs4, cs5, cs7; triggers + REVOKE →
  cs2, cs3, cs6; gateway guard → sg1, sg2; frontend → f1, f3).
- Approved existing-test edits: `ShopifySameShopGuardTest.initiate_tenantOwnsOnlyDisconnectedDifferentShop_rejected`
  and `CustomAppConnectTest.sameShopGuard_onlyDisconnectedRow_differentShop_rejected409` (flipped from allowsSwitch);
  `MigrationSmokeTest` 128→129 files, `NotTracedBackfillTest` 73→74; `ConnectionsOnboardingTest` c7 comment only.
- **Migration numbers:** S1 = V130 (merged first, so it reaches prod before V131 — Flyway refuses out-of-order).
  **fulfillment-link must rebase onto main and take V131 after V130; re-bump MigrationSmokeTest / NotTracedBackfillTest
  counts** (to 130 files / 75 pending). Branch `feat/bosta-fulfillment-link`, worktree `~/Documents/traceability-bosta-link`.
  Review mode S2 then takes V132 if it needs a migration.
- **Gotcha (process):** two sessions once edited the same working tree (~/Documents/traceability) at the same time and
  both created a V130. Every build now runs in its own worktree; the main checkout stays on main.
- **S6 note:** the reset script (and any future purge) must include `tenant_courier_simulation`.

**Prod purge of non-merchant tenants (2026-10-02, ops only — no code change; committed in prod by Marawan).**
- Purged 23 test / reviewer / screencast tenants and every tenant-scoped row (54 `tenant_id` tables, catalog-derived
  coverage check), in one REPEATABLE READ transaction run as postgres via psql through the session pooler (5432).
  Prod after: 18 tenants — 6 merchants (Jumi, The Snouts, blnco, BROEK, High line, Femine), the demo tenant
  91c6027e (kept: DemoSeeder's fixed id, re-bootstraps itself anyway), 10 real signups with no store, and Mody 2004.
- **Purge rule:** purge = tenants NOT in an explicit 18-id keep list AND `created_at < 2026-10-02 12:37:51 UTC` (the
  pg_dump archive's "Archive created at" — the snapshot time). Anything created after the dump was kept
  automatically; purge count asserted = 23. The purge list was never hand-typed. Guards: keep ids exist,
  Jumi/Snouts/demo in keep, isolation = repeatable read, keep counts + md5 of every keep row unchanged per table,
  0 purge rows left. Rehearsed (rollback + real commit + abort test) on a local restore, dry-run on prod first.
- **Backup:** `~/Documents/traced-backups/traced-prod-pre-purge-2026-10-02.dump` (pg_dump 17, `-n public -Fc`, 20 MB),
  local only, restore verified. Same folder: `purge_commit.sql`, `verify.sql`. postgres password rotated afterwards.
- **Cross-tenant FK (Jumi/test2):** 5 resolved Jumi `unlinked_bosta_deliveries` rows pointed at `webhook_events`
  owned by test2 (both tenants had polled the same Bosta account in July). Fixed by setting `webhook_event_id = NULL`
  on those 5 rows only (option a); they were the only keep-tenant rows the purge modified.
- Cleanup: 64 JobRunr jobs (60 FAILED test1, 4 DELETED Reviewer) referencing purged ids deleted; a stale
  earlier pg_dump session (idle in transaction, AccessShare on 235 tables) terminated; local rehearsal container
  and its data volume removed.
- Freed for reuse (global uniques): `reviewer*@tracedtech.com` and Marawan's two emails (`users_email_unique`), the
  review / dev shop domains (`stores_shop_domain_key`), portal slug `test`. Purged dev stores still have the app
  installed: the embedded app shows NotLinked, their webhooks are acked and dropped.
- **Follow-ups (not done):**
  a. `webhook_events_idem` is UNIQUE (source, external_event_id) with no tenant_id, and the Bosta key is
     content-derived (`sha256(tracking:state:updatedAt)`) — two tenants on the same Bosta account collide (root
     cause of the Jumi/test2 tangle). Never share a Bosta account across tenants until fixed; `shipments.tracking_number`
     is also globally unique.
  b. `DemoSeeder.DELETE_ORDER` is missing `return_request_events`, `return_refunds`, `return_request_items`,
     `return_requests` (and `portal_lookup_attempts`): a demo visitor's return request would make the next reseed
     fail on `return_requests → orders`.

**Bosta discovery hardening + pre-connect filter + fulfillment tracking capture + visibility check
(2026-10-02, branch `fix/bosta-discovery-preconnect` rebased onto origin/main 08c94dd; merged to main).**
- Step 0 finding (prod, read-only): BROEK's 32 "late-booked" orders are not a late-booking problem — Bosta
  creates BROEK deliveries through the day and Traced links them within ~1 min; the 6 PM batch is the
  Shopify fulfillment write-back. The 32 tracking numbers have never been visible to BROEK's connected
  key (0 webhook_events; discovery mark unmoved since 10-01 16:55). Likely a second Bosta account —
  Slice 5's check answers it.
- **Migrations renumbered after two rebases:** `bosta_discovery_failures` → **V128**,
  `order_fulfillment_tracking` → **V129** (main's V125 pack_print_batches, V126 pack_waybill_sessions,
  V127 phone_scanner_pairing come first). MigrationSmokeTest 126→128 files (V1–V129),
  NotTracedBackfillTest 71→73.
- **Slice 1 (V128):** a per-item discovery fetch failure (5xx/IO, "Delivery not found", unexpected) goes on a
  retry list retried by tracking number at the start of every cycle (independent of the 150-item window);
  success deletes the row (and clears the exception). A 429 records the item without counting, stops the
  cycle and holds the mark. At `bosta.poll.discovery-max-item-failures` (10) the row escalates →
  `bosta_discovery_failed` (HIGH) and is retried every `discovery-slow-retry-minutes` (60) until
  `discovery-retry-cap-hours` (48) after the first failure; then `retries_stopped_at`, exception stays open
  until the delivery reaches Traced or it's resolved. The mark never waits on a failed item.
  Tests `BostaDiscoveryRetryTest` dr1–dr5.
- **Slice 2 (`PreConnectDeliveryFilter`, BostaWebhookJob step 6.2 — every ingest source):** ignores a
  delivery (any type) whose reference resolves to no Traced order (as sent, '#'±, external_id, parts
  before AND after ':', shopifyInfo.orderId) when every store of the tenant has a non-null
  `orders_ingest_from` and (Bosta createdAt < cutoff, or the number part has the tenant's order-number
  shape and is below its lowest). Never: Traced-owned (shipments row / exchanges.return_request_id /
  return_requests.bosta_tracking_number), Jumi (NULL cutoff), NULL reference created after the cutoff.
  Note `ignored_pre_connect: <tn>`, nothing else written; step-4 dedup blocks redeliveries.
  Bosta connect no longer enqueues a backfill (`POST /bosta/sync` stays, filtered);
  BostaBackfillTest test renamed `connect_doesNotEnqueueBackfill` (approved). Tests `PreConnectFilterTest`
  pf1–pf11.
- **Slice 3:** `scripts/ops/2026-10-02-bosta-preconnect-cleanup.sql` (BEGIN … ROLLBACK, run manually after
  deploy): BROEK's 4 EXC- orders deleted (guarded), 7577553206 dismissed, 44 unlinked rows resolved (BROEK
  21 + Femine 23); the 7 post-cutoff NULL-reference rows stay unresolved. exception_resolutions has no
  system actor (resolved_by NOT NULL → users), so audit rows use the tenant owner + note "Ops cleanup by
  Traced (pre-connect, 2026-10-02)". Read-only checks in `…-predeploy-checks.sql`. Test
  `OpsPreConnectCleanupScriptTest` oc1–oc3.
- **Slice 4 (V129):** orders/updated REST fulfillments[] → one row per (order, tracking number);
  carrier_class bosta / other_known (Wijha) / unknown ("Other", null — Jumi's Bosta says "Other");
  TrackingNumberNormalizer only for 'bosta', every other carrier stored as sent minus whitespace
  ("WJ-12345" stays). Cancelled → status 'cancelled', never deleted. GraphQL path never touches it; nothing
  reads it yet. Tests `FulfillmentTrackingCaptureTest` ft1–ft8.
- **Slice 5 (`BostaVisibilityCheckService`):** read-only; owner `POST /api/v1/bosta/visibility-check` or ops
  `BOSTA_VISIBILITY_CHECK_ON_STARTUP=<tenant ids>|all` (startup, one-shot). Logs `BOSTA_VISIBILITY` /
  `BOSTA_VISIBILITY_SUMMARY`. Tests `BostaVisibilityCheckTest` vc1–vc4.
- **Gotcha:** blnco's Bosta references are `blncoeg:#515956` (order number AFTER the ':'); BROEK exchanges
  are `BRK-44719-EG:BRK-44719-EG-R1` (BEFORE). Order numbers are stored as Bosta sends them: BROEK
  `BRK-44841-EG` (with -EG), Femine `70370`, Jumi/Snouts/blnco `#…`.
- **Gotcha:** after renaming a migration, `mvn clean` (or delete target/classes/db/migration) — the stale
  copy in target/ makes Flyway fail with "more than one migration with version".
- **Prod pre-deploy check (2026-10-02 15:40):** filter would ignore BROEK 21 + exchange 7577553206, Femine 23,
  blnco 10 (pre-connect), Snouts 1 (5470, pre-connect RTO); cleanup targets unchanged; new Femine rows
  5658/5659 (NULL ref, post-cutoff) kept.
- **Suite:** 1,967 run, 4 skipped, only the 2 known reds (ShopifyMagicLinkTest, ExchangeBackfillTest).
- **Deploy order:** deploy (V128, V129) → Slice 5 check (BROEK, then all) → cleanup script (ROLLBACK, compare,
  COMMIT).

**Pick & Pack S6 — phone as scanner, waybill mode (2026-10-02, branch `feat/phone-scanner` off main 69c54e7; pushed, not
merged, not deployed). Approved: hatch #15 and an additive meta argument in useScanner's marked blocks.**
- **Normalizer:** `BOSTA_<digits>` (Bosta's waybill QR, prefix case-insensitive) → the digits; anything else after the
  prefix → null. All 8 callers re-checked; none relied on it being rejected.
- **V127:** `scan_pairings` — credential table: REVOKE ALL from app_user, then INSERT + column-scoped SELECT (never the two
  hashes) + UPDATE of the claim / revoke columns; forced tenant RLS; one unrevoked pairing per pack session.
  `scan_relay_events` — tenant RLS, UNIQUE(pairing_id, seq), app_user SELECT/INSERT + UPDATE(status, message, outcome_at).
  **Hatch #15 `resolve_scan_pairing(kind, hash)`** → (tenant_id, pairing_id) only for a live pairing on an open session
  (registered in blueprint §16.1 and CLAUDE.md — 15 hatches). Count tests bumped (126 / 71).
- **API:** tablet (session owner): POST/GET/DELETE `/pack-sessions/{id}/pairings[/current]`, DELETE
  `/pack-sessions/pairings/mine`, GET `/pack-sessions/{id}/relay-stream` (SSE: `pairing` + `scan` events, heartbeat 20 s,
  one stream per session, events < 5 s delivered, older expired — never replayed), POST
  `/pack-sessions/{id}/relay-events/{eventId}/outcome`. Phone (public `/api/v1/scan-pair/**`, X-Device-Secret): POST
  `/claim`, POST `/scan` (idempotent on seq, 10/s per pairing), GET `/scan/{eventId}`, GET `/status`; every dead secret →
  401 PAIRING_ENDED (one reason — hatch #15 has no oracle). Tenant set per request in a finally, never runAs.
- **Revocation:** new pairing (`ScanPairingService.java:96`), unpair (`:116`), session end (`PackSessionStore.java:245`),
  worker switch (`AuthController.java:114` on PIN switch + `StationProvider.signOutWorker` → `DELETE pairings/mine`),
  12 h expiry (hatch).
- **SecurityConfig:** `/api/v1/scan-pair/**` public; DispatcherType.ASYNC permitted (an SSE emitter's completion dispatch
  was AccessDenied → "response already committed" errors without it — verified).
- **nginx (MANUAL on deploy — DEPLOY-NOTES):** `scanpair` zone 10 r/s burst 10 on `^~ /api/v1/scan-pair/`; unbuffered
  relay-stream location (proxy_buffering off, read timeout 120 s). Needs `nginx -t` in a one-off container + `restart nginx`.
- **Frontend:** useScanner `handleScan(code, meta?)` → `onScan(code, meta)`; `clearQueue()` returns dropped metas.
  PackSessionScreen: "Use phone" (QR modal, lazy chunk, qrcode-generator SVG), connected chip + Unpair, fetch-based SSE
  client (Bearer token; EventSource can't send it) with reconnect + "reconnecting" after 5 s; one outcome per phone scan;
  dropped scans answered "Not applied — scan again"; a phone scan never wipes a keyboard scan being typed. Phone page
  `/scan/:pairCode` (lazy, public): claim once, secret in sessionStorage, camera (BarcodeDetector or zxing, Code 128 + QR,
  torch), 2 s duplicate suppression, poll ≤ 4 s, accepted / rejected / not confirmed / not sent / disconnected. Spike removed.
- **Tests:** TrackingNumberBostaQrTest 13, ScanPairingSchemaTest 5, ScanPairingTest 13 (incl. the relay-stream app_user
  isolation test named in RlsCoverageTest's EXEMPT), RlsCoverageTest 57 (+1 covered test, approved). Backend 1,968 run,
  4 skipped, only the 2 known reds. vitest 620/620, browser 20/20, tsc + build clean.
- **Known limits:** a phone scan refused because the tablet queue is full (20 waiting) gets no outcome (phone shows "Not
  confirmed"); phone scans still queued when the tablet leaves the screen likewise; the relay hub is in-memory (one app
  instance — fine today).

**Fix — useScanner: never more than one scan in flight (2026-10-02, branch `fix/scanner-single-flight` off main 28bf0e7;
pushed, not merged, not deployed). Edit to the SAFETY-CRITICAL worker block approved by Marawan 2026-10-02.**
- **Race:** the worker guarded on the render-time `scanning` state. React 18 gives updates made inside an effect at most
  Default priority (react-dom flushPassiveEffects) but an Enter keydown's `setPending` Sync priority, and renders the Sync
  update first without the Default ones — two Enters right after a scan started (or one Enter plus any screen state change
  when onScan is a new function each render: StockTakeScan, TransferScanOut, TransferReconcile) gave a render with
  `scanning` still false, and the worker started a second onScan. Behind the WebKit-only scannerBurst flake (3 failures in
  60 WebKit test results over 14 suite runs: StockTakeScan ×2, PackSessionScreen ×1). On PackSessionScreen it sent pieces to the waybill endpoint
  while the order was opening (rejection → queue cleared) or the next waybill to the old order. Servers were safe (pack:
  session row FOR UPDATE; stock-take: ON CONFLICT DO NOTHING).
- **Fix:** an in-flight ref (`busyRef`) set before the next code is taken; it is released only by the render that commits
  the scan's completion (`completed` counter state === `startedRef`), the same render that holds the finished onScan's own
  state updates, so the next onScan is built from that state. A ref cleared in `finally` alone is not enough: an Enter
  between the response and its render would run the next scan with the old onScan (proved by a test). `scanning` is
  display-only. Screens unchanged.
- **Tests:** `useScannerRace.browser.test.tsx` (3 × Chromium/WebKit; overlap cases fail on main, latest-state case fails
  on the ref-only variant) and `packSessionSequencing.browser.test.tsx` (2 × Chromium/WebKit; both fail on main). vitest
  607/607, tsc + build clean, `npm run test:browser` 18/18 five runs in a row.

**SPIKE — phone camera barcode reading (2026-10-02, merged at 28bf0e7). FOLLOW-UP DONE in S6 (2026-10-02):** the
`/scan-spike` page, its route and its test are removed; `@zxing/browser` + `@zxing/library` stay — the S6 phone page
(`/scan/:pairCode`, `pages/scanpair/CameraReader.tsx`) uses them. Spike results that shaped S6: iPhone Safari has no
BarcodeDetector (zxing there), zxing reads our Code 128 piece labels and Bosta waybills fast, the waybill QR holds
"BOSTA_<digits>", the camera reads the top barcode without spaces.
- Public `/scan-spike` (lazy, outside RequireAuth / Layout, next to /login). No API calls, stores nothing, English only.
  Spring already serves any dotless path as the SPA (SecurityConfig permitAll + SpaController) — no backend change.
- Rear camera (environment, ideal 1920×1080, falls back to any camera), continuous autofocus / torch where the track
  exposes them. Engines switchable: native BarcodeDetector (when present) and @zxing/browser (pure JS, no wasm). Formats
  Code 128 / QR / EAN-13, or "all formats" to learn Bosta's symbology. Same code within 2 s counted once; log of the
  last 50 (raw text with visible spaces / control chars), Copy log, counters, Start test, vibrate + beep.
- Headers: app.tracedtech.com sets no Permissions-Policy (camera allowed for the page's own origin); CSP has no
  media-src (default-src 'self' doesn't govern a MediaStream on srcObject); zxing needs no wasm / worker. Nothing loosened.
- Bundle: zxing only in the ScanSpike chunk (464 KB, 120 KB gzip). main.js +1.3 KB (route + lazy loader), main.css
  +0.6 KB (spike-only utility classes).
- Tests: scanSpike.test.tsx (2). vitest 607/607, tsc + build clean, browser 8/8 (one earlier run 7/8 — scannerBurst
  "expected 2 to be 1", not reproduced in 3 re-runs; pre-existing flake, unrelated). No backend change, no backend run.

**Pick & Pack S4 — waybill mode: batch lists, printed-but-not-packed, manager exceptions, session summary (2026-10-02,
branch `feat/pack-lists-summary` off origin/main; pushed, not merged, not deployed).** No migration (`exception_type`
has no CHECK; nothing new to store — resolutions reuse `exception_resolutions`).
- **`PackListRules`** (inventory) is the single source of the predicates: not-yet-packed, cancelled (status or
  cancel_requested_at), latest deciding pack outcome (packed / set_aside, `created_at DESC, id DESC`), open set-aside,
  open cancelled-after-print, batch item state. Detectors, both lists and the summary read it — never inline.
- **Exceptions (MEDIUM → daily 08:00 digest only; the immediate alert job is CRITICAL/HIGH):** `pack_set_aside`
  (subject `pack_set_aside:<pack_session_orders.id>` — a new set-aside after a resolve re-opens; auto-clears when the
  order is packed or cancelled) and `pack_cancelled_after_print` (subject `pack_cancelled_after_print:<shipment id>`;
  printed waybill + cancelled order; resolve = "waybill discarded"). actionUrl `/fulfill`.
- **Endpoints (any signed-in user, tenant RLS):** GET `/fulfill/print-batches/today` (Cairo day, newest first, counts
  packed / setAside / cancelled / waiting — "packing now" counts as waiting); POST `/fulfill/print-batches/{id}/reprint`
  (stored position order, one Bosta call, no new batch row, cancelled orders skipped as ORDER_CANCELLED); GET
  `/fulfill/printed-not-packed` (latest batch per shipment, no time window; status cancelled > packing > set_aside >
  waiting; packed and resolved-cancelled drop out; sorted cancelled, set_aside, packing, waiting, then printed_at,
  batch, position); GET `/pack-sessions/{id}/summary` (own sessions only — 403 otherwise).
- **Frontend:** `PackLists.tsx` (both lists; owner/manager rows with an exception open `/exceptions?type=&key=`,
  workers read-only), `SessionSummaryScreen.tsx` (after End session; `?summary=<id>` keeps it on reload), Exceptions
  `?key=` highlights + scrolls to the row.
- **Tests:** `PackListsTest` (8), RlsCoverageTest +3 GETs (56/56), `packListsSummary.test.tsx` (6). Backend 1,936 run, 4 skipped,
  only the 2 known reds; vitest 605/605, browser 8/8, tsc + build clean.
- **Deviations:** reprint also skips cancelled orders (beyond Bosta's own exclusions); batch progress folds "packing now"
  into waiting.

**Hotfix — waybill top barcode with spaces (2026-10-01, branch `fix/awb-barcode-spaces` off main 76c56b8; pushed, not
merged, not deployed).** Production, Jumi 2026-10-01: two pack-session rejections had raw_scan
"G - 0 2 - 8 4 8 4 8 0 5 6 9 9" — Bosta's TOP waybill barcode encodes a space between every character; the bottom
barcode "8484805699" packed fine.
- **`TrackingNumberNormalizer.normalize()`** now removes every whitespace character (Character.isWhitespace + Unicode
  space separators — no-break, thin, narrow no-break, ideographic) before the unchanged prefix strip and digits-only
  check. Callers (none relied on spaced input being rejected; two already stripped whitespace themselves):
  ShipmentLinkService.linkTrackingNumberToOrder, WaybillResolver.resolve, PackCompleter.completeAndLink (guard),
  PackSessionStore.scanPiece (waybill-while-packing check), PickupSessionService.scan, ReturnSessionService.scan
  (pre-strips), LookupService.lookupTracking, ReturnPickupBookingService.confirmBooked (pre-strips).
- **Resolver:** a scan that doesn't normalize is NOT_A_WAYBILL only when it looks like a piece code (P + 6+ digits,
  "PC-" + alphanumerics, or a 26-char Crockford ULID); anything else is the new **UNRECOGNISED_BARCODE** — "This
  barcode isn't a waybill we recognise. Try the barcode at the bottom of the waybill (Tracking Number)." (EN + AR;
  screen title "Not a waybill we recognise").
- **Side list:** rejected rows show the raw scan (monospace, truncated to 22 chars, full value on hover) instead of
  "—"; the session view's recent rows carry `rawScan`.
- **Tests:** `TrackingNumberSpacesTest` (6), `AwbSpacedBarcodeTest` (3: spaced barcode resolves; piece vs unrecognised;
  spaced barcode packs end to end, tracking_linked raw_scan = the spaced scan, no Bosta call) — main's normalizer → 4
  RED; `packSessionAwbCopy.test.tsx` (3). Existing tests unedited (TrackingNumberNormalizerTest 22,
  WaybillResolverTest 12, PackSessionTest 12 green). Backend 1,927 run, 4 skipped, only the 2 known reds; vitest
  599/599, browser 8/8, tsc + build clean.

**Pick & Pack S3 — waybill scan mode (2026-10-01, branch `feat/pack-waybill-session`, rebased onto main 938a39a — the
scanner fix; pushed, not merged, not deployed).** Commits: getOrder fix · V126 · mode setting · resolver/claim · session API · frontend · refocus.
- **Gate (resolved, option a):** no `TenantContext.runAs` under complete()/linkByAwbScan()/completeLink()/ledger.
  `PackCompleter.completeAndLink` (one transaction, after the scan transaction committed) guards first: the
  normalized opening waybill must be a FORWARD shipment row on this order, else `CompleteFailed WAYBILL_NOT_ON_ORDER`
  (rollback; claim and open order kept). Proven: `PackSessionTest` asserts `bostaGateway.fetchDelivery` is never
  called on the success path, the terminated-leg path and the guard path; guard removed → 2 RED.
- **V126:** `tenants.pick_pack_mode` (default order_queue); `pack_sessions` (one open per tenant+user; the open order
  + its opening raw scan live on the session: `current_order_id` ON DELETE SET NULL, `current_waybill_scan`,
  `current_opened_at`); `pack_session_orders` (packed / set_aside / rejected + raw_scan + reason). RLS NULLIF + FORCE;
  app_user sessions S/I/U, outcomes S/I. MigrationSmokeTest 124→125, NotTracedBackfillTest 69→70.
- **Mode:** PUT /tenant/settings `{pickPackMode}` owner-only; GET /api/v1/fulfill/mode every role; Settings › Pick &
  Pack tab (owner edits, manager read-only). `/fulfill` → `FulfillRoute`: order_queue = existing page unchanged;
  waybill_scan = waybill page; `?view=self-pickup` = existing queue filtered to self-pickup.
- **Resolver** (`WaybillResolver`): OPEN, CANCELLED (Shopify cancel time only when known), ALREADY_PACKED (latest 'pack'
  event's actor + time), CLAIMED_BY_OTHER, RETURN_WAYBILL, EXCHANGE_NOT_MAPPED, TOO_OLD (30-day window), NOT_FOUND
  (+ 'unlinked'), NOT_A_WAYBILL, ON_HOLD, NOT_PACKABLE with sub-reason in `detail`: LEG_ENDED, ALREADY_MOVING (+ state;
  live or ever in shipment_status_history), SELF_PICKUP, OTHER (gate refuses: confirmed/picking status, or the newest
  forward leg ended while an older one is active). A "not the current waybill" reason can't occur (V104 allows one
  non-ended forward leg). Type-30 internal exchange orders open. PICKABLE_SHIPMENT_GATE reused verbatim.
- **Claim** (`PackClaim`, orders.locked_by/locked_at): atomic conditional take on open, refreshed on every piece
  scan/undo, stale after 10 min (NULL locked_at = stale), released on auto-complete / set aside / session end (all of
  the packer's claims). Q2: shared `FulfillService.scan()` refuses CLAIMED_BY_OTHER while another packer's claim is
  live — queue mode too (server-side only; PickScreen untouched).
- **Session API** `/api/v1/pack-sessions`: POST (start / resume; refused MODE_NOT_WAYBILL in order_queue mode; mode
  copied onto the session), GET /summary, GET /{id}, POST /{id}/waybill, POST /{id}/orders/{o}/scan (scanned / rejected
  / completed / complete_failed), POST …/complete (retry), DELETE …/scan/{piece} (undo, only while open), POST
  …/set-aside {reason} (existing unscan for every active allocation), POST /{id}/end (refused while an order is open).
  Owner-only-by-user: another packer → 403 SESSION_NOT_YOURS; another tenant → 404. Errors are ApiException
  `{code, message_en, message_ar}`. tracking_linked events carry the opening raw scan + `{"pack_session_id": …}`
  (new `linkByAwbScan` overload; the 3-arg form passes null as before).
- **Frontend:** waybill page (mode chip, Print waybills, Start/Resume, tiles, self-pickup entry) and `PackSessionScreen`
  on useScanner + ScanShell (waiting → order card with 88px images → auto-complete flash; rejection screen per code;
  complete_failed + Try again; Undo only while open; set aside with required reason; End disabled while open). EN+AR.
  **After the rebase onto the scanner fix (938a39a):** S3 doesn't change useScanner / ScanShell. PackSessionScreen's own
  refocus effect is gone (the hook keeps focus); `focusPaused: setAsideOpen`; `scanner.clearQueue()` on a waybill
  rejection, on complete_failed, when the set-aside dialog opens and before ending the session (via a ref from inside
  onScan); the `if (setAsideOpen) return {success:false}` guard stays. Queued scans run with the latest onScan, so a
  piece queued behind its waybill goes to the piece endpoint.
- **Tests:** PackSessionTest 12, WaybillResolverTest 12, PackSessionSchemaTest 4, PickPackModeTest 3,
  GetOrderForwardLegTest 3; RlsCoverageTest registers /fulfill/mode, /pack-sessions/{id}, /pack-sessions/summary
  (approved). Frontend packWaybillSession 8 (incl. a rejection with 2 scans queued behind it → both dropped, never
  applied to the next waybill; reverted → RED), pickPackSettings 3. Browser (npm run test:browser): PackSessionScreen
  added — one waybill then 19 pieces at 300 ms each → 20 requests in order, max 1 in flight, auto-completes, input
  focused, Chromium + WebKit (8/8 total). After the rebase: vitest 596/596, tsc + build clean; backend 1,918 run, 4 skipped, only the 2 known reds.
- **Follow-ups:** (1) pre-existing synchronous Bosta HTTP call inside the link transaction on the new-shipment branch —
  `ShipmentLinkService.linkTrackingNumberToOrder` → `fetchAndStoreProviderDeliveryId` → `fetchDelivery` (main 3b8a503:
  :260-261 → :737; this branch: :273-274 → :754), the queue-mode AWB link path; out of scope here. (2) ~~useScanner focus loss on the other scan screens~~ — fixed on main by the scanner fix (938a39a).
**Scanner fix — no lost scans (2026-10-01, branch `fix/scanner-no-lost-scans` off main 3b8a503; pushed, not merged,
not deployed).** Marawan approved editing the SAFETY-CRITICAL blocks of `hooks/useScanner.ts` and
`components/ScanShell.tsx` for exactly this change (2026-10-01). Screens: StockTakeScan, TransferScanOut,
TransferReconcile. PickScreen untouched. S3's PackSessionScreen is not on main yet — see follow-up.
- **Bug:** ScanShell disabled the input while `scanning`; useScanner called `focus()` in `finally` before React
  re-enabled it. Every keystroke of a scan arriving mid-request was dropped (no beep, no server trace — a piece
  physically scanned in a stock take could be written off at V124 finalize and decremented in Shopify), and in
  Chromium focus never came back: every later scan lost until a tap.
- **Fix:** input never disabled for scanning (only the screen's own `disabled`); spinner + `aria-busy` instead. Enter →
  trimmed code onto a FIFO queue, input cleared at once. One effect-driven worker, one `onScan` at a time, strictly in
  order, own beep/flash/recent entry per scan, no dedup; each queued scan runs with the latest render's `onScan`.
  `MAX_QUEUED_SCANS = 20` waiting → the next is refused with error beep + flash + "Too many scans waiting — slow down".
  `pending` ("N scans waiting" under the input), `queueFull`, `clearQueue()`; queue dropped on unmount (= leaving the
  screen — no extra call sites needed on the three screens). Focus effect: after every scan / when the queue drains,
  only on an enabled input, not while `focusPaused` (StockTakeScan abandon dialog, TransferReconcile close confirm),
  and never out of another text field (TransferReconcile shortfall inputs). Click-to-refocus unchanged. beep / click
  refocus / flash trigger / flash overlay blocks byte-identical to main.
- **Tests:** jsdom `useScannerQueue.test.tsx` (7: no focus() on a disabled input + ends focused; 6 scans with the first
  held → 6 calls in order, one at a time; 21st waiting refused visibly; clearQueue; unmount; latest onScan; focus not
  stolen / paused). Real browser `src/test-browser/scannerBurst.browser.test.tsx` — Vitest browser mode + Playwright,
  Chromium AND WebKit, 20 scanner bursts (Playwright keyboard, 4 ms/key, no clicks) with 300 ms per request on all three
  screens → exactly 20 requests in order, max 1 in flight, input focused: 6/6 pass; with main's useScanner/ScanShell
  6/6 FAIL (Chromium 1 of 20 arrive, WebKit 1–3 of 20). Run: `cd frontend && npx playwright install chromium webkit`
  (once) then `npm run test:browser`. Not part of `npm test` / the Docker build.
- **Deps:** devDependencies `@vitest/browser-playwright@^4.1.10` (matches vitest 4.1.10) + `playwright@^1.56.1` (no
  install script — `npm ci` downloads no browsers). Lock regenerated inside node:22-alpine (linux/amd64) from main's
  lock: +107 entries, nothing removed; postcss 8.5.19→8.5.28, nanoid 3.3.16→3.3.19, lightningcss 1.32→1.33 (dev,
  in range). Docker-image `npm ci` + `npm run build` verified. `src/test-browser` excluded from `tsc` like `src/test`.
- **Suite:** vitest 585/585 (578 + 7), browser 6/6, tsc + build clean; backend 1,882 run, 4 skipped, only the 2 known reds.
- **S3 follow-up (PackSessionScreen, after rebase onto this) — DONE on feat/pack-waybill-session after its rebase:** call `scanner.clearQueue()` on a waybill rejection
  (`setRejection(r)`), on complete_failed (`setFailed(...)`), when opening the set-aside dialog, and before
  `endPackSession`/`onEnded`; pass `focusPaused: setAsideOpen`; remove its own screen-level refocus effect (the hook
  now does it) and the "drops a scan while one is in flight" comment; keep `if (setAsideOpen) return {success:false}`
  as a guard; add PackSessionScreen to the browser burst test (waybill then 19 pieces, each request 300 ms).
- **Follow-up:** PickScreen still disables its scan input during a scan (`Fulfill.tsx:1218`) and its marked refocus
  effect re-runs only on `[order]` (:956-963) — fast scans dropped and, after a rejected scan, focus likely lost.
  Separate gated fix (SAFETY-CRITICAL).

**Pick & Pack S2 — batch waybill printing, queue mode (2026-10-01, branch `feat/pack-print-batches`, rebased on main
abf62ba; pushed, not merged, not deployed).** Mockup `design/pick-pack-waybill-mockup/PrintDialog.html`.
- **V125** `pack_print_batches` (batch_no per tenant, paper, sort, scope, waybill_count, order_guaranteed) +
  `pack_print_batch_items` (order, shipment, tracking, position). Tenant RLS (NULLIF) + FORCE, app_user SELECT/INSERT
  only. Item FKs to orders/shipments are ON DELETE CASCADE (prod never deletes either; keeps DemoSeeder.reseed and
  test cleanups working without editing DemoSeeder). Rebased onto main abf62ba (V124 stock-take finalize already
  there), so V125 follows it in order.
- **POST /api/v1/fulfill/print-batches** `{scope new|all, paper A6|A4, sort oldest|newest}` → JSON `{batchId, batchNo,
  waybillCount, candidateCount, remainingCount, orderGuaranteed, pdfBase64 (one merged PDF), excluded[], message}`;
  **GET …/print-batches/options** → `{defaultPaper}`. `isAuthenticated()` (all roles, as every Fulfill endpoint).
  `PackPrintBatchService` (no tx) → `PackPrintBatchStore.candidates` (read tx) → `BostaAwbService.printAwbDetailed`
  (≤50/call, sorted order) → `WaybillPdfAssembler` → `PackPrintBatchStore.record` (write tx).
- **Candidates** = `PICKABLE_ORDERS_FILTER` (made package-private, visibility only) − self-pickup, latest forward
  shipment 'created'; 'new' = no batch item for that shipment. Sort created_at then id, same direction.
- **49 per print (`PackPrintBatchService.MAX_WAYBILLS_PER_PRINT`):** from 50 tracking numbers up Bosta mass-awb stops
  returning the PDF and emails the labels instead. Each print takes the first 49 in the chosen order — exactly one Bosta
  request — and returns `remainingCount`; the dialog shows real totals, the button "Print 49 waybills", and after
  printing "Printed the first 49. Print again to get the remaining N." ("New only" picks them up). Safety net for every
  caller: `BostaAwbService.BATCH_SIZE` 50 → 49 (only used inside that class), chunking + email-path handling kept.
- **Single-order reprint:** PickScreen's Print Waybill button renders whenever the order has a tracking number
  (`Fulfill.tsx` PRINTABLE branch), independent of `awbPrinted` — a lost label can always be reprinted; no UI change.
- **Finding 4 fixed:** `printAwbDetailed` sends tracking numbers in the caller's order (rows re-ordered in Java after the
  IN load); `printAwb()` keeps its exact result contract on top of it. **Finding 3:** batch path merges every chunk
  PDF server-side (PickScreen's single-order path still opens `pdfBase64List[0]`, always 1 shipment).
- **Page order:** per-page text → batch tracking numbers (digit runs, Arabic-Indic folded, spaced groups joined). All
  pages map 1:1 → pages reordered to batch order, `orderGuaranteed=true`; else Bosta's order kept, false. Logged
  without PII. Bosta's own ordering still unverified live (/tmp/awb-order-test script).
- **batch_no:** per-tenant `pg_advisory_xact_lock("pack_print_batch:"+tenant)` around MAX+1, write tx only;
  UNIQUE(tenant_id, batch_no) backstop. Two simultaneous "new" prints can still both print the same waybills (no lock
  across Bosta calls) — two batches, both recorded.
- **Gotcha:** `TenantContext.runAs` CLEARS the context when it finishes (doesn't restore). Anything that runs after a
  `BostaAwbService` call on the same thread has no tenant — `PackPrintBatchService` re-wraps its write in runAs.
- **Single-order gate:** GET /fulfill/{id} `awbPrinted`; PickScreen sets `awbPrintedOnce` from it (new effect, nothing
  marked touched). Queue rows carry `awb_printed` → header "Waybills: N printed · M not printed yet" with no extra
  request (keeps fulfill.test.tsx's sequential mocks in sync). Gather list `?batchId=` (page `?batch=`).
- **UI:** Print waybills button + dialog (Modal/Radio/SegmentedControl/Checkbox/Alert), merged PDF opened in a tab
  pre-opened on click; pick list = "Open pick list" button in the result (a second automatic tab is popup-blocked).
- **Tests:** `PackPrintBatchTest` (15), revert-checked: DB send order → RED; no advisory lock → concurrency RED;
  never reorder → reorder RED; no cap → cap test RED; BATCH_SIZE 50 → max-49 test RED. RlsCoverageTest + options test (own A6, other tenant's A6 never read). Frontend
  `fulfillPrintBatches.test.tsx` (5; awbPrinted effect revert → RED). Count bumps (on top of V124's):
  MigrationSmokeTest 123→124, NotTracedBackfillTest 68→69.
  Full suite (rebased on abf62ba): 1,880 run, 4 skipped, only the 2 known reds (ShopifyMagicLinkTest.
  provisionWiring_path2NewInstall_…, ExchangeBackfillTest). Vitest 577/577, tsc + build clean.
**Stock-take finalize applies the count (2026-10-01, branch `feature/stocktake-finalize-applies` off main 8f53628;
merged, not deployed).**
- Decisions (Marawan, 2026-10-01): (a) finalize applies the count in one transaction under the session lock — unscanned
  free stock (available / damaged / on_hold at open, still in that status = drift guard) → lost; scanned damaged on a
  live-available piece → damaged (PieceAdjustService); push enqueued after commit; per-row resolve stays.
  (b) 0 piece scans → 400 ZERO_SCANS; typed confirmation (the write-off count) when coverage < 80% or write-offs > 10% of
  expected free stock — `StockTakeFinalizePolicy`; never asked when nothing is written off (refinement found while
  building). Write-offs still need the attestation (409 ATTESTATION_REQUIRED). (c) expected set = physically present
  statuses (`StockTakeService.PHYSICALLY_PRESENT_STATUSES_SQL`; DemoSeeder mirrors it), new sessions only.
  (d) V124: 'nothing_to_push' (pushed_at only on a real push) + 'superseded_by_seed' + superseded_at; trigger_type
  'stock_take_found'. (e) variance positive = short everywhere; the finalize modal shows `reconciliation.finalizePlan`
  — the SAME `plan()` finalize runs. (f) damaged → lost / on_hold → lost excluded from the push (delta = from 'available'
  only). (g) 4th increment trigger `stock_take_found` (+1 when the piece's latest →lost was a stock-take write-off from
  available whose push applied — `foundIncrementEligible`). (h) the seed supersedes pending/failed stock-take pushes at
  the location created ≤ its snapshot when every variant in the push was seeded or on-hand 0 (all-or-nothing);
  stock_take_found claims ride the increment supersede. CLAUDE.md FR-17 v2 (trigger 4) + FR-21 §7 (from available)
  amended.
- Push job: proceeds only from pending / failed (a superseded or nothing_to_push claim used to fall through and push);
  a late result can't flip superseded back; a late success records pushed + WARN.
- Tests: `StockTakeFinalizeAppliesTest` (13) and `frontend/src/test/stocktakeFinalize.test.tsx` (5), all revert-checked.
- **Second round (Marawan, 2026-10-01):** found piece +1 also when (i) the write-off was from on_hold and that hold
  cycle's hold-enter decrement applied, or (ii) the write-off's push was superseded by the seed and the write-off was at /
  before the seed's snapshot (`superseded_snapshot_at`). Partial supersede: covered variants leave `payload.deltas` for
  `payload.superseded`, revision+1 → the push job sends with key `session:rev:n`; only when the push can't have reached
  Shopify ('failed', or 'pending' with `send_started_at` NULL — the job now claims the row as pending + send_started_at
  before anything else). 'failed_ambiguous' / already-sending pushes are never rewritten: `payload.seedOverlap` +
  `inventory_increment_sync_failed` kind `stock_take_seed_overlap` (HIGH, cleared by resolving). Close summary: "written
  off" = all write-offs in Traced (`writtenOff`), plus "pushed to Shopify" (`pushedToShopify`). Typed confirmation never
  asked at 0 write-offs (test c1).
- Approved existing-test edits: a brand-new on-shelf piece scanned in the 13 zero-scan tests (sft1–8 via
  `openAllScope`, ops3, pc1–4 via `sessionWithOneWriteOff`); sft1 delta 2→1; sft8 'pushed'→'nothing_to_push'; st1 / srt1
  narrowed snapshot; StockTakeOpsTest cleanup deletes stock_take_scans before sessions; frontend st8 → the row's variance
  cell shows 3 as a shortage (text-danger).
- **Gotcha:** `Card` (components/ui) doesn't forward `data-testid` — `close-summary` was never in the DOM; tests find the
  summary by its title.

**Pick & Pack S1 — product images on pack lines, queue mode (2026-10-01, branch `feat/pack-line-images` off
main 8f53628; pushed, not merged, not deployed).** First slice of the waybill-mode feature (mockup
`design/pick-pack-waybill-mockup/`, Step 0 report in session).
- **Backend:** `FulfillService.getItemsWithAllocations()` selects `p.image_url AS "imageUrl"` (V69 products.image_url,
  product-level, nullable). Only caller is `getOrder()` → `GET /fulfill/{id}`; each line gains `imageUrl`, nothing
  else changes shape. Read-only, no Shopify call.
- **Frontend:** `PickScreen` itemsList renders `ProductThumb size={88} cdnWidth={176}` left of name/variant/SKU;
  name and SKU now wrap (`break-words`, was `truncate` on the name). No SAFETY-CRITICAL code touched; ProductThumb
  defaults unchanged (missing/broken → Package placeholder, fixed 88px tile, no layout shift).
- **Tests:** `PackLineImageTest` a/b/c (image set, null, cross-tenant 404 as app_user with same-tenant control) —
  revert-checked (SELECT line removed → a, b RED). Full suite: 1,845 run, 4 skipped, only the 2 known reds
  (ShopifyMagicLinkTest.provisionWiring_path2NewInstall_…, ExchangeBackfillTest). Vitest 567/567, tsc + build clean.
  Layout checked at 1280×800 and 360px (static harness of the real markup + built CSS): no horizontal overflow,
  long names/SKUs wrap.
- **Not in S1:** variant images (not stored — GraphQL import captures product featuredImage only); initials placeholder
  (mockup) — still the Package icon, needs a new ProductThumb prop if wanted.
- **Mass-AWB > 50 (Step 0 finding 3) is not live:** the only caller of `POST /bosta/awb/print` is PickScreen's
  `printAwbPdf`, always one shipment. It becomes live with S2 batch printing.

**Stock-take push enqueued after commit (2026-10-01, branch `fix/stocktake-push-after-commit` off main 6795cac;
merged, not deployed).** `StockTakeReconciliationService.finalizeSession` and `repushSync` now enqueue
`StockTakeShopifyPushJob` through `ShopifyInventoryService.afterCommit` (a rolled-back repush used to leave the row
'failed', which the job treats as retryable, so it pushed anyway). `StockTakePushAfterCommitTest` pc1–pc4,
revert-checked (both enqueues back inside the tx → 4/4 RED).

**Stock-take finalize changes nothing — Step 0 findings (2026-10-01, read-only, no code).** By design (FR-21 spec
Step 4/5) finalize only pushes write-offs a manager already made per piece via `POST /resolve` ("Mark lost", gated on
attest-complete); it never applies the count itself. Prod: Snouts 620cc645 attested + finalized 9 s apart with 0 scans
(932 uncounted on-shelf, no resolves) — the finalize modal told the user "{{count}} piece(s) will be written off" anyway.
Traced Demo Store fc63b584 is a DemoSeeder fixture (raw SQL), never went through finalize. Also found: review-page
variance sign inverted (backend expected−counted, UI treats negative as shortfall); zero-delta claim is 'pushed' with
pushed_at NULL; damaged:lost write-offs are counted into the Shopify 'available' decrement. Fix proposal awaiting
Marawan's decision — see the Step 0 report in the session.

**All six Shopify inventory triggers fire after commit (2026-10-01, branch `fix/decrement-triggers-after-commit` off
main ea0f071; merged, not deployed).**
- `PieceAdjustService.adjustPiece` (damage_move), `voidPiece` (void_correction), `hold` (hold_enter) now register their
  `@Async` job through `ShopifyInventoryService.afterCommit(...)`, like receiving / restock / unhold did since ea0f071;
  javadocs corrected. No other behaviour change — same named decrement set, Traced-location-only, same claims.
- `AfterCommitTriggersTest` (restock, unhold, damage, void, hold × committed / rolled back): with the commit delayed the
  Shopify call sees the piece's new status committed; a rolled-back caller → no claim, no Shopify call. Revert-checked:
  all five call sites back inside the tx → 10/10 RED. Receiving: `SeedSupersedesClaimsTest` a1/a2.
- **Audit:** `ShopifyInventoryService` holds the only `@Async` methods; `onExchangeReplacementDispatched` already used
  afterCommit. Every non-inventory Shopify/Bosta JobRunr enqueue (Bosta webhook/poll/backfill ingest, Shopify
  webhooks/import/OAuth, pickup booking) runs after its own transaction commits — nothing to report there.
  **Still inside a transaction (inventory, not changed):** `StockTakeReconciliationService.finalizeSession` (:456) and
  `repushSync` (:532) enqueue the stock-take push job from inside `@Transactional` (comment relies on JobRunr's poll
  interval to separate it from the commit). A rollback still leaves the job enqueued: after a rolled-back finalize the
  job finds no claim row and no-ops (`StockTakeShopifyPushJob.push` :67-70); after a rolled-back repush the row is
  still 'failed', which the job treats as retryable (:71-79), so it pushes anyway.

**Seed supersedes redundant increment claims (2026-10-01, branch `fix/seed-supersedes-increment-claims` off main
41c1a9d; merged, not deployed).** Must ship before any blocked store's location is linked.
- **Bug:** the seed (`ShopifyInventoryReconcileService.apply`) pushes Traced's CURRENT on-hand; unapplied increment
  claims from before it would then retry on top → double count.
- **V123:** status `superseded_by_seed` (CHECK widened) + `superseded_at`. Inside the seed's transaction, under its
  tenant advisory lock: cutoff = `clock_timestamp()` read immediately before the Traced on-hand read; after the writes,
  every increment claim (receiving_session / return_inspection / hold_exit, legacy or not) at the Traced location in
  'failed' or 'pending' with `created_at <= cutoff` is superseded — for variants the seed WROTE in this run, and for
  variants with Traced on-hand 0 at the snapshot (Marawan, 2026-10-01: scope 3). skip_nonzero and failed-seed variants
  stay retryable (the seed wrote nothing for them). `ApplyResult.superseded` + audit field.
- **Never retried/repushed:** every `IncrementRecoveryRules` predicate is `status = 'failed'`, `claim()` reclaims only
  'failed' → retry job and claim path skip them; repush answers 409 `SUPERSEDED_BY_SEED`; the setup / gave_up / legacy
  alerts drop them (auto-resolve). Stock-screen sync health treats them as synced.
- **In flight:** `markIncrementResult` locks the row; a superseded claim never goes back to 'failed'; a late success is
  recorded 'applied' with WARN "applied after superseded by seed — possible double count of N units for variant X".
- **Trigger firing fixed (root cause of the cutoff ambiguity):** the three increment triggers fired `@Async` from INSIDE
  their `@Transactional` callers, so a claim could exist before its pieces committed (and a rolled-back finalize still
  reached Shopify). Now `ShopifyInventoryService.afterCommit(...)`: `ReceivingService.finalize` (receiving_session),
  `ReturnService.restock` (return_inspection), `PieceAdjustService.unhold` (hold_exit); javadocs corrected.
  **Same pattern, NOT changed (decrements / moves, out of scope):** `PieceAdjustService.adjustPiece` → damage_move,
  `voidPiece` → void_correction, `hold` → hold_enter.
- **Residual window:** a trigger whose pieces committed before the on-hand read but whose async claim row is inserted
  after the cutoff — the claim is treated as owed and sends +N on top of the seed. Width = after-commit dispatch →
  async executor pickup → claim INSERT (milliseconds; longer only if the executor queue is backed up), and only for a
  trigger landing in the same instant as a seed (link/relink only).
- **Tests:** `SeedSupersedesClaimsTest` s1–s7, s2b, x1 (app_user), a1, a2 — revert-checked: no supersede step → s1/s3/s4/
  s7/x1 RED; seeded-only → s4 RED; every variant → s5/s6 RED; no cutoff → s2b RED; no in-flight guard → s7 RED;
  receiving trigger inside the tx → a1/a2 RED. Count bumps: MigrationSmokeTest 121→122 (V1–V123), NotTracedBackfillTest
  66→67.
- **Prod (read-only, 2026-10-01):** unapplied non-legacy increment claims — only The Snouts, 2 never_sent receiving
  claims / 20 units / 2 variants (location `error`). Legacy unapplied: Snouts 32/1,040, Jumi 22/119, tesloc 8/312,
  TracedLocations 6/44 (+1 'shadow' row, untouched).

**RTO@20 false "exception" — Step 1 (2026-09-30, branch `fix/rto-route-assigned` off main d2d9d19, not merged,
not deployed).**
- **Problem:** Bosta relabels a SEND as type 20 "Return to Origin" on the way back (same AWB, still the forward leg).
  RTO@20 ("Route assigned") fell through to `20:ALL` → `'created'`, and the monotonic guard turned it into
  `'exception'` ("Needs attention") until state 46. Prod: 56 legs went through it after the guard (Jumi 47, Snouts 9);
  2 Jumi legs stuck (2017084040, 3338348731). RTO@41 matched nothing — the seeded `41:RTO` row is keyed "RTO" but the
  mapper key is the fetched `type.value` uppercased, `"RETURN TO ORIGIN"` → unknown code, webhook failed (1 prod event,
  219203, 2026-08-26; its leg reached returned@46 the same day, not behind).
- **Mapper key confirmed fetch-only:** `BostaWebhookJob.java:330` (delivery from `fetchDelivery`, :187),
  `BostaIngestionHelper.java:106` (:92), `ShipmentLinkService.java:437` (stored `bosta_order_type`, written from the
  fetched `delivery.type()` at `BostaWebhookJob.java:808`; prod never holds "RTO"). Type string built at
  `BostaHttpGateway.java:225-228` / `BostaDelivery.fromRaw`. The webhook body's `type` is never read.
- **V122 (data only):** `(20,'RETURN TO ORIGIN')` and `(41,'RETURN TO ORIGIN')` → `'returning'`, piece NULL (the dead
  41:RTO row stays; its return_in_transit move is NOT copied). Guarded repair UPDATE (forward, exception,
  provider_state 20, raw type.code 20 → returning; history untouched) — prod dry-run 2026-09-30: 2 rows, both Jumi.
  CLAUDE.md monotonic paragraph amended (the "no mapping migration for 10/11/20" sentence now carries the RTO exception).
- **Tests:** `RtoRouteAssignedTest` r1, r1b, r2–r6 (+ one `@Disabled` CRP demonstration). Revert-checked: rows removed →
  r1/r1b/r4/r5 RED; repair UPDATE's type guard removed → r6 RED (2 rows repaired, not 1). Count edits (pre-approved):
  MigrationSmokeTest 120→121 files / V1–V122, mapping rows 26→28; NotTracedBackfillTest 65→66.
- **Gotcha:** a repaired leg shows "In transit", not "Returning" — its history still says created@20 and the label is
  max-over-history. A leg that takes RTO@20 live after V122 records `returning@20` and shows "Returning" (and keeps it
  through later RTO@24/30).
- **Known gap (not fixed, read-only finding):** CRP return legs also hit the guard (CRP@20 after progress → exception),
  and `'exception'` is in `RETURN_LEG_TERMINAL_STATES` (`ShipmentLinkService.java:798-801`), so
  `hasReturnLegAwaitingIntake` (:830) is false while a leg sits there; `ReturnSessionService.scanPiece` (:227-266) then
  refuses a DELIVERED piece's scan unless it's in the return window or attributed to a request item. Prod today: 0 CRP
  legs at exception (all 9 returned@46). Demonstrated by the `@Disabled` test. A mapping row is not the fix (CRP@20
  before pickup is genuinely created).
- **Out of scope, untouched:** SEND@20 guard behaviour (prod: 35 legs, all normal re-routes), keying the mapper by
  type.code, the 20:ALL packed→awaiting_pickup side effect, deleting 41:RTO.

**Bosta late-booking linking — Step 0 diagnosis + Step 1 hardening (2026-09-30, branch `fix/bosta-linking-hardening`
off main 1447d64, not merged, not deployed).**
- **Step 0 (read-only, prod SELECTs):** the suspected "late booking is abandoned" gap does NOT exist. Delivery-side
  matching (`ShipmentLinkService.matchByBusinessReference`) never filters on `bosta_link_status`, so a SEND booked hours
  after the order still links on first sight (discovery poll / webhook / backfill → `tryMatchDelivery`) and
  `clearReconcileFlag` resets the order to NULL / 0 attempts. Proof: BROEK BRK-44803…44808-EG linked 1.5–2.5 h after
  placement (att 0, last_check NULL). "Zero orders linked after 10 attempts" was an artefact of that reset.
  `BostaOrderReconcileJob` makes NO Bosta calls — it only searches local `unlinked_bosta_deliveries`; the ~48 min =
  10 ticks of `*/5`. BROEK's 15 open SEND rows are pre-connect orders (BRK-44742…44802, before the FR-18 cutoff);
  BROEK has never received a Bosta webhook (discovery poll only). Jumi's open row 5540 is a type-30 EXCHANGE_MULTI_ITEM
  on an order linked since 09-04 (exchange lane, out of scope); Jumi's 87 not_created are the June–July backlog.
- **Step 1 (this branch):**
  - Order-side type allow-list `ShipmentLinkService.FORWARD_LINKABLE_TYPE_CODES = {10 SEND, 20 RETURN TO ORIGIN}`
    keyed on the stored `raw.type.code` (prod 2026-09-30: forward legs 245×10, 113×20 = SENDs Bosta re-labels on the way
    back, 5×30 all internal exchange orders, 16 no raw). Reconcile only considers allow-listed rows;
    `manualLink()` (reconcile AND the owner/manager `POST /shipments/unlinked/{id}/link`) refuses anything else — null /
    missing type included — BEFORE any write with `UnlinkedDeliveryTypeException` (422
    `UNLINKED_DELIVERY_TYPE_NOT_LINKABLE`, EN/AR body). No UI calls that endpoint today. `tryMatchDelivery` unchanged.
  - Reconcile never breaks a tie: it links a row only when the row's reference matches exactly ONE order in the tenant
    at reconcile time (same variants as matchByBusinessReference). Was RED: two stores with the same order number →
    `recordUnlinked` cleared BOTH orders' flag and reconcile linked the oldest.
  - **Decision (Marawan, 2026-09-30): `match_reason` is NOT consulted by reconcile** (a first cut skipped
    AMBIGUOUS_MULTI / COD_ONLY_AMBIGUOUS rows; dropped). Those reasons can come from the phone+COD fallback, which only
    runs when the reference matched nothing at arrival — skipping them would strand a delivery that arrived before its
    order was ingested. The exactly-one-order check alone covers every ambiguity case (h3, h3b); h3c proves a
    COD_ONLY_AMBIGUOUS row links once its order arrives (RED with the reason clause restored).
  - Double-link (second SEND for an order with an active forward leg) was already GREEN — the V104 conflict is caught
    (`ShipmentLinkService` tryMatchDelivery createOrFindShipment catch), the aborted transaction's COMMIT is a silent
    server ROLLBACK under pgjdbc 42.7.4 defaults, and only reads preceded the INSERT in that tx. Locked in by a test.
  - Tests: `BostaLinkingHardeningTest` h1–h6 (+h3b, h3c, h4b). No migration.
- **Gotcha:** the order-side allow-list keys on `raw.type.code`; a hand-inserted unlinked row with NULL raw is refused
  by reconcile/manualLink. Any test fixture that builds an unlinked row for reconcile/manualLink must give it a `raw`
  with `type.code`. Approved fixture fix (no assertion changes) applied to the 8 tests that built raw NULL / `{}`:
  BostaOrderReconcileTest r3/r4/r7 (r7 merged into its existing raw), NotCreatedFlagRecoveryTest nc2,
  UnlinkedResolveTest ul2, TransferModeBGuardTest manualLink_outOnTransferPiece…, Day11Test d_unmatchedDelivery…
  (merged into the mocked raw), NotTracedDetectorTest e_manualLink_bornTerminal….
- **Suite (branch head):** 1,809 run, 2 failures — only the known ShopifyMagicLinkTest + ExchangeBackfillTest.
- **Not done (out of scope):** not_created badge semantics, the 10-attempt window, reconcile LIMIT/ordering, row 5540,
  Jumi backlog, a `bosta_link_flagged_at` column, BROEK webhook setup.

**Failed-increment recovery — Part D (2026-09-30, branch `feature/increment-recovery` off main 8b31c06, not merged, not deployed).**
- V121 claim-row columns: `failure_class` (never_sent / rejected / ambiguous), `change_from_quantity` (baseline SENT),
  `sent_idempotency_key` + `sent_key_first_at`, `attempt_count`, `first/last/next_attempt_at`, `legacy`. Every increment
  claim failed at deploy time → `legacy = true` (prod at 2026-09-30: The Snouts 32 claims / 1,040 units, Jumi 22 / 119,
  two test tenants) — never auto-retried.
- Gateway: `ShopifyAdjustFailedException` (class + sent baseline); `resendInventoryAdjustment` = identical resend (same key,
  stored baseline, no fresh read, positive only). Rules in `IncrementRecoveryRules` (single source, like ReturnCaseRules).
- `IncrementRetryJob` every 10 min → `ShopifyInventoryService.retryDueIncrements()`: existing claim path, original delta,
  Traced location only. never_sent → same key; rejected → claim key + ":attempt:n" (Shopify docs are unclear whether a
  failed response is cached under the key — assumed yes); ambiguous → identical resend while the sent key is < 20 h old,
  then no retry. Backoff 10 min / 1 h / 6 h / 24 h, max 5 attempts. A setup problem (no store / missing
  read_products+write_inventory / Traced location not linked) blocks the pass, no attempt spent.
- `inventory_increment_sync_failed` (HIGH): setup (names the fix + blocked count; gone once fixed and retried), gave_up,
  legacy ("N units across M variants received in Traced never reached Shopify (since …). Reconcile manually — do not
  replay; the seed pushes current stock.", variant list). Manual repush
  `POST /exceptions/increment-sync/repush {triggerType, triggerId, variantId, confirmOld}` — 409 CONFIRMATION_REQUIRED
  past 24 h; **legacy claims are never repushable (409 LEGACY_NOT_REPUSHABLE)** — cleared only by resolving the exception
  (API only, no UI yet — same as the void/hold repush).
- **Snouts / Jumi (Part E, read-only):** neither token has write_locations; Snouts has read_locations (can link a location
  the merchant names exactly "Traced Main Warehouse"), Jumi has no inventory/location scope at all. Relink = ShopifyImportJob
  (Sync / reconnect) → activation → seed. The seed would push +949 (Snouts, 20 variants) / +105 (Jumi, 4) at a NEW
  location — confirm the merchants' Shopify counts first (their existing location likely already counts those units).
- **CatalogBackfillJob:** catalog imported + activation failed for a SETUP reason (Traced location not linked, or every
  variant rejected while the token lacks inventory scope) → marker set, one WARN naming the fix, store not failed (was: the
  7 unlinked stores retried forever). Token/reauth failure (import fails) and every-variant-rejected with the setup in place
  stay failures.

**Activation perf + seed fix + catalog filters (2026-09-30, `feature/activation-and-catalog-perf` merged to main, not deployed).**
- **A (dcd0203 + 422516a):** V120 `variants.shopify_inventory_item_id` (column only). Import (both product queries) and the
  products webhook (REST `inventory_item_id`) store it; `InventoryItemIdService` (not a bean — built from the caller's
  JdbcTemplate) reads the column first, resolves + writes back on a miss — used by increments, stock-take push, seed,
  activation. Batch activation: `ShopifyGateway.resolveInventoryItemIds` (nodes ≤250) + `activateInventoryItems`
  (25 aliased `inventoryActivate`, ≤2 in flight, per-alias errors, never a quantity arg, batch reserves its own cost in
  the pacing, a batch that exhausts THROTTLED retries is resent ≤3×). **Policy:** connect / backfill / manual activation
  = ACTIVE products only; draft/archived activated lazily in `applyIncrementAdjustment` (activate → adjust; activation
  fails → claim 'failed', adjust not sent); webhook activates new variants only for an active product.
- **B:** seed candidates = Traced on-hand > 0; item ids resolved BEFORE the advisory lock; `fetchAvailableQuantities`
  chunked to 250 (the old single nodes() call with every id broke for >250 variants). The Shopify "available" read stays
  under the lock (double-add guard). Writes proven identical by `SeedGoldenTest` (recorded on the old code). `noop` now
  counts every non-candidate variant.
- **C (5da8734, cherry-picks onto main alone):** `/catalog` one variants query per page (N+1 gone), optional q / status /
  variantIds / cursor+limit, keyset **title ASC, id ASC** (spec said created_at DESC — products have no created_at and
  no migration was approved), no params = old whole-catalog response + `nextCursor`. `/inventory/stock` optional status.
  Shared `ProductStatusFilter` (ui.tsx). Receiving grid + Stock tab default Active + Draft; exchange picker Active only +
  "Show draft & archived".
- Approved test edits: re-stubs in ShopifyCatalogActivationTest, CatalogBackfillJobTest (+ policy updates bf1/bf6/wh1/wh2,
  new wh4/wh5), ShopifyImportTest, ShopifyInventoryReconcileTest; MigrationSmokeTest 119, NotTracedBackfillTest 64.
- Simulated Shopify (260 ms latency, bucket 1000 / 100 pts/s): 1,000 variants 101 s activation (+13 s resolve when the
  column is empty), 6,121 in 613 s (+116 s resolve); old serial path 0.53 s/variant (≈ 9 min / 1,000, ≈ 54 min / 6,121).
- **Next (Part D, diagnosed only):** automatic retry of failed increment claims — see the report of 2026-09-30.
- **Seed activation (807f0c2):** a seed row now runs the same lazy activation as an increment before its adjust (A3 left
  draft/archived candidates unactivated). Full suite on 807f0c2: 1,733 run, only ShopifyMagicLinkTest + ExchangeBackfillTest red.
- **Gotcha:** `mvn test` runs npm install + vite build unless `-Dskip.frontend=true`.

**Label layout rework (2026-09-29, branch `feature/label-layout`, 4 commits, not merged, not deployed).** Every piece
label PDF (Receiving session/variant print + reprint, Returns reprint-label + gated /pieces/{id}/label, Transfer
reprint-outstanding) now goes LabelService (queries, label_reprints, 50×25 default) → LabelPdfRenderer → PieceLabelLayout
→ LabelTextFitter → LabelFonts.
- **Fonts (a):** embedded NotoSans-Regular/-Bold + real NotoSansArabic-Regular (the old file was the THIN weight), OFL.txt in
  resources/fonts; no Standard-14 Helvetica. Per-glyph fallback: Arabic script → NotoSansArabic, everything else ("/", "&",
  "…", Latin, Cyrillic, digits) → NotoSans, unknown → "?". Fixed the 500 on Arabic + "/" ("No glyph for U+002F").
- **Layout (b/c):** barcode 12 → 10 mm (x 3 mm, width W−6 mm, module width + quiet zones unchanged; top margin now 1.5 mm).
  Rows: piece code Bold 7.5 · product 6→5 pt, 2 lines · variant 5.5→5 pt (omitted blank / "Default Title") · SKU 5 pt
  (omitted blank). Width-measured wrap → shrink → "…" (only when text was actually removed, after the floor; product + variant cut
  after the last whole word via ICU line breaks with trailing — – - , ، / & ; : stripped, grapheme cut only when the first word
  alone is too wide; the SKU keeps a grapheme cut); vertical fit
  (reordered after review — the variant outranks the 2nd title line and the SKU): title 5 → variant 5 → title 1 line "…" →
  drop SKU → drop variant (`Layout.steps()` lists what fired). **All rows centred**
  (review follow-up), each line keeps its own bidi order. **Line height:** Latin-only 1.1 × size; lines with Arabic use the
  Arabic letters' ink envelope computed from the font (`LabelFonts.ARABIC_LETTER_INK` = +1.010 / −0.421 em → 1.431 ×), or the
  line's own ink if taller (harakat). The font's declared ascent/descent (1.374 / −0.738 = 2.112 ×) is NOT used — it reserves
  room for stacked Quranic marks and would drop the variant on nearly every Arabic label. Sample fixture "S / أحمر…" carries
  its own U+2026 (test data for the glyph fallback) — not a truncation.
- **Arabic shaping bug found + fixed:** the old `TEXT_DIRECTION_VISUAL_LTR` flag on logical text gave ISOLATED letter forms —
  Arabic printed unjoined. Now `TEXT_DIRECTION_LOGICAL` (LabelTextFitterTest.t5; `shapeForDisplay` delegates).
- **Transfer "no barcode" (d):** `LabelService.generatePieceLabels(List<pieceId>)` renders one document; the page merge in
  `TransferService.reprintOutstandingLabels` is deleted.
- Tests: LabelFontsTest 5, LabelTextFitterTest 16, PieceLabelLayoutTest 13, LabelEndpointsTest 8 (every endpoint, 50×25 + 40×25,
  every page decoded, fonts embedded; r1 = Arabic "/" regression), TransferReprintTest +2 (every page decoded / owns its image).
  No existing test edited.
- **TODO (decided 2026-09-29, not built):** wire the tenant label size (Settings → `tenants.label_width_mm/height_mm`) into
  LabelService's size defaults. The layout and tests already cover 40×25 — it's a one-line change where the defaults resolve.

**Import every Shopify product status + one-time catalog backfill (2026-09-29, merged to main as 84a7911, not deployed).** Zero-variant store in the backfill: `total() > 0` guard → marker set, job does not throw (checked by a throwaway test; guard removed → it errors).
- **Import:** `query: "status:active"` removed from `ShopifyHttpGateway.PRODUCTS_QUERY` — ACTIVE, DRAFT, ARCHIVED and UNLISTED
  (in the 2026-04 `ProductStatus` enum) all import, stored lowercase in `products.status` (free text, no CHECK, no migration).
  Nested variants no longer stop at 50: when a product's `variants.pageInfo.hasNextPage` is true, `fetchRemainingVariants`
  runs `ProductVariantsPage(product(id).variants(first: 250, after))` through the same throttle-aware `executeGraphQL`.
  The products/create|update webhook already stored draft/archived before this change; `products/delete` is still not subscribed (by decision).
- **Portal exchanges = ACTIVE products only:** `ExchangeOptions.forVariant` returns an empty `exchangeOptions` list (keys kept)
  when the line's product isn't 'active'; `PortalService.validExchange` requires `p.status = 'active'` (→ 400); merchant approve
  of an exchange → 409 `REPLACEMENT_NOT_ACTIVE` (no job, no Bosta call); the booking job's `exchangePrecondition` also refuses
  ("The replacement's product is no longer active in Shopify.") so an archive between approval and booking never reaches Bosta.
  Refunds, lookup lines, receiving, labels and inventory sync are unchanged (no status filter).
- **Badge:** `ProductStatusBadge` (ui.tsx) — active → nothing, draft/archived → `productStatus.*` (EN/AR), else the raw value.
  Used in Inventory Stock tab (`/inventory/stock` gained `status` on each product), Receiving grid card, merchant ExchangeVariantPicker (badge only, not blocking).
- **Tests:** new `ShopifyHttpGatewayProductsTest` (3, p1/p2 RED on old code), `ShopifyProductStatusImportTest` (4, i1–i3 RED on old
  code; w1 webhook guard), `PortalExchangeActiveProductTest` (7, l1/l2/s1/a1/b1 RED with the predicates removed; a2/r1 controls),
  `productStatusBadge.test.tsx` (5, psb5 RED without the picker badge). No existing test edited.
- **Part B — one-time catalog backfill:**
  - **V119** `stores.catalog_backfilled_at timestamptz NULL` (column only; RLS policy unchanged). MigrationSmokeTest 118,
    NotTracedBackfillTest 63 (approved bumps).
  - A successful `ShopifyImportJob` (connect / reconnect / sync) sets the marker — never backfilled afterwards.
  - `CatalogBackfillTrigger` (ApplicationReadyEvent, fail-soft, only when the JobRunr server is on) enqueues
    `CatalogBackfillJob` under `CatalogBackfillJob.jobIdFor(today in Africa/Cairo)` (UUID of "catalog-backfill-<date>")
    when a connected store has a NULL marker. **JobRunr 7.3 makes a repeat enqueue under an existing id a silent
    no-op in ANY state** (`AbstractJobScheduler.saveJob` swallows `ConcurrentJobModificationException`) — so same-day
    starts collapse into one job, and a job that ended FAILED is followed by a new one on the next day's first start.
    Kill switch `traced.catalog-backfill.enabled` (env `TRACED_CATALOG_BACKFILL_ENABLED`, default true), checked by the trigger and the job.
  - `CatalogBackfillJob`: one owner-pool read of eligible stores (same listing as ShopifyReconcileJob), then per store in
    `TenantContext.runAs`: `importCatalogOnly()` → `activateAll()` → marker. Marker rule: import fails → no marker, store
    failed; activation fails at STORE level (activateAll throws — token / no linked location / no store — or EVERY
    variant rejected, which is how a missing scope or unreachable shop shows up) → no marker, store failed; only SOME
    variants rejected → WARN with store id + rejected variant ids + reasons, marker SET, store not failed. Never writes
    import_status / last_sync_at / status. Failed stores → the job throws at the end → JobRunr retry; done stores skipped.
  - **Webhook activation gap closed:** confirmed in code that only `activateAll()` (connect import + manual endpoint)
    ever activated — a variant added in Shopify after connect was never activated at the Traced location (not observed in
    prod's failed-adjustment rows, which are all location-link / scope errors). `ingestProductWebhook` now returns the
    newly inserted variant ids (before/after diff for that one product); `ShopifyWebhookProcessorJob` activates just those
    via `ShopifyCatalogActivationService.activateVariants()` after the upsert commits; failures are logged, never fail the upsert.
  - Tests: `CatalogBackfillJobTest` (16; revert-checked: marker guard → bf2/bf3/im1, webhook activation → wh1/wh2,
    before/after diff → wh1/wh2, import marker → im1, old any-variant-fails rule → bf6, date-free job id → tr3). Headless-Chromium screenshots of the Stock tab EN/LTR + AR/RTL with
    draft + archived badges (scratchpad, mocked API).
  - Prod at build time: 14 connected stores eligible (10 custom_app_cc, 4 oauth — 2 with import_status failed).

**Returns & exchanges — Step 2: the new page, renames, old page removed (2026-09-28, branch `feature/returns-cases-2`, not merged, not deployed).**
- **/exchanges** (URL unchanged) = one "Returns & exchanges" list on GET /returns-exchanges + /counts: tiles (hidden at 0,
  click → To do + removable chip), tabs All · To do · In progress · Done with counts (To do badge amber), type select +
  debounced search (list AND counts), All grouped under stage headers ("— showing n" while more pages exist), Show more
  (cursor), tone pills in the mockup's exact colours, overdue age red + bold, redacted "—", "Not found". Row → drawer by
  target: request → ReturnRequestDrawer; dashboard exchange / courier return → ExchangeRefundDrawer (own row type now).
  Drawer changes reload list + counts. Deep link `?tab=requests&request=<id>[&parcel=<id>]` still opens the drawer.
- **Renames (EN/AR):** sidebar + title "Returns & exchanges" / "المرتجعات والاستبدال"; scanning page "Scan returns" /
  "مسح المرتجعات" (sidebar all roles, page heading, worker home tile, Overview "awaiting inspection" line, refund drawer
  link). The Overview stat tile that COUNTS returns keeps "Returns" (own key `overview.stats.returns`).
- **Removed:** old tabs/cards/merged feed (`normalize.ts`), `RequestsPanel.tsx`. Kept: all drawers, `statusTone.ts` (drawer
  uses it), every backend endpoint. Now unused by the frontend: GET /refunds, GET /return-requests (list).
- **Backend (read-only additions to the case list):** reason.trackingNumber, refundTotal, currency, closeReason,
  inspectionState (C) and legStatus (C) — for the pill/reason texts and the courier-return drawer.
- Tests: returnsExchanges (13), exchangeRefundDrawer (5, drawer tests moved from the old page file), returnsExchangesNav (6);
  ReturnCasesTest +2 (labelling guard moved from the deleted exchangesRefundsNormalize.test.ts; display inputs).
  Drawer tests navigate via the deep link (approved). Frontend 557 (baseline 573: old-page tests removed/replaced).

**Returns & exchanges — Step 1 backend: one case list + counts on shared rules (2026-09-28, branch `feature/returns-cases-1`, not merged, not deployed).**
- **`ReturnCaseRules`** (`com.traceability.returncases`) — THE single source of the return-alert predicates AND the case
  stages: booking problem (+ key), exchange needs mapping, return_link_ambiguous (candidate lateral + "not held by a
  request"), return leg unscanned, return_to_receive open, request_item_to_receive open (+ key), return in transit stuck, the
  leg inspection-state expression (was Java in listCrpReturns), `notResolved()`; REFUND_OVERDUE / ITEMS_OVERDUE and the
  ShipmentLinkService leg predicates are re-exported, never copied. ExceptionService detectors and listCrpReturns now read
  it (pure refactor — their tests unchanged and green).
- **`ReturnCaseService`** — one CTE (`UNION ALL` of A portal requests, B dashboard exchanges with no request, C return legs no
  request holds) → next step code → stage / tone / overdue / open alerts. De-dup: a request absorbs its leg (id or booked
  tracking number) and its exchanges row. Sold-out exchange check = `VariantStockService.computeAll()` once per call, only
  when the tenant has a requested exchange, passed as a uuid[] of in-stock variants. Keyset cursor
  (stage rank, updated µs, case key). A resolved alert clears the red/overdue flag, never the stage; resolving
  return_to_receive / request_item_to_receive IS how those tasks end (their open-ness has always included it).
- **Endpoints (owner/manager):** `GET /api/v1/returns-exchanges?stage&type&tile&q&cursor&limit≤50` → {items, nextCursor};
  `GET /api/v1/returns-exchanges/counts?type&q` → {stages, tiles}. Search: exact RR reference / tracking number (incl. a
  request's absorbed leg and exchange AWBs), contains on order number / customer name; no new index.
- Tests: `ReturnCasesTest` (9: mapping table, row content, de-dup (revert-checked), tiles, filters/search, paging with equal
  timestamps, agreement with all 8 related detectors incl. resolved ones, app_user cross-tenant, HTTP roles);
  RlsCoverageTest COVERED + 2 tests. Current page and endpoints untouched until Step 2.

**Transfer lifecycle — Stage 1 backend + Stage 2 frontend (2026-09-28, merged to main, not deployed; both ship together).**
- **V118** (V117 = portal custom pickup address, merged first; counts now MigrationSmokeTest 117, NotTracedBackfillTest 62): status CHECK preparing|sent|reconciling|closed|cancelled, default preparing; sent_at/by, cancelled_at/by, reconcile_started_at/by; CHECK cancelled ⇒ cancelled_at. Backfill: open + no transfer_pieces → preparing; open + pieces → sent (round_trip/relocate_return) / preparing (relocate_out). Prod effect: the 3 empty stuck transfers → preparing (cancellable once the UI ships), demo open showroom → sent.
- **TransferService:** scanOut/returnScanOut read the transfer FOR SHARE and need preparing; new markSent (returning modes, ≥ 1 piece) and cancel (preparing, 0 transfer_pieces ever) lock FOR UPDATE then re-count in a fresh statement; beginReconcile FOR UPDATE, needs sent + returning mode (server-side now); closeOneWay locks the transfer row first, needs preparing — this also closes the old scan-vs-close race. listOpen "open" = preparing+sent+reconciling, "closed" = closed+cancelled; getTransfer adds the new stamps + piecesEverCount. Codes: TRANSFER_NOT_OPEN (enum) replaced by TRANSFER_NOT_PREPARING, + NOT_SENT, HAS_PIECES, EMPTY, WRONG_MODE; scan rejections keep the string "TRANSFER_NOT_OPEN". No piece events, no ledger change.
- **Endpoints:** POST /transfers/{id}/mark-sent, /cancel (OWNER/MANAGER). DemoSeeder writes the open showroom transfer as sent.
- **Tests:** new TransferLifecycleTest (21, incl. 6 hold-the-lock race tests — all 6 fail with the locks removed) + TransferLifecycleBackfillTest (1); WorkerPermissionGuardTest +2. Edited (approved): markSent before every reconcile path (Reconcile/Relocate/RelocateReturn/Reprint/Controller tests), two 0-piece reconcile fixtures → 1 piece, TransferServiceTest "open" → "preparing", RlsCoverageTest fixture status, MigrationSmokeTest 116, NotTracedBackfillTest 61.
- **Error copy (C):** the UI renders the backend's bilingual message verbatim (api.ts rule), so the plain copy lives in TransferService: scan refused → "This transfer isn't accepting scans anymore."; TRANSFER_EMPTY / TRANSFER_HAS_PIECES get their own sentences; NOT_PREPARING / NOT_SENT / WRONG_MODE share "This action isn't available for this transfer right now. Refresh the page." (EN + AR).
- **Stage 2 frontend:** TransferStatus = 5 values; `transferStatusTone` (Transfers.tsx) preparing grey · sent amber · reconciling blue · closed green · cancelled grey (only 5 DS tones, red kept for problems → preparing shares grey with cancelled). Tiles Sent / Reconciling / Pieces outstanding. Detail actions: preparing = Scan out more, Mark as sent (returning modes, disabled at 0 pieces), Close (move), Cancel (0 pieces ever, confirm dialog with "Keep it"); sent = Begin Reconcile + Reprint; reconciling unchanged; cancelled = banner. Scan-out only while preparing; Reconcile treats preparing/sent as not started, cancelled like closed. Bring back form: read-only piece list, no checkboxes. lookup.phrase.relocated_out reworded. Tests: new transferLifecycleUi.test.tsx (15, revert-checked); edited tl1 (Mark as sent step, fixture transfer_mode + piecesEverCount), ts1–ts3, td fixture, transfersNewChooser fixture status. Vitest 557/557, tsc + build clean. Backend transfer tests 110/110 after the message change.
- **Follow-up (approved):** detail subtitle uses the list's `typeLabel` (Move to another location / Bring back / category); a cancelled transfer shows no Lines table. +2 tests (lc16, lc17, revert-checked). Vitest 559/559, tsc + build clean. Merged to main on top of V117 — not deployed. Merged tree: backend 1663 run, only the 2 known reds (ExchangeBackfillTest, ShopifyMagicLinkTest); vitest 573/573; tsc + build clean.

**Portal custom pickup address — V117 (2026-09-28, merged to main, not deployed).**
The customer chooses the delivery address (default, unchanged) or "A different address" (refunds and exchanges).
- **V117:** `return_requests.pickup_address_source` ('order' | 'custom', default 'order') + `custom_first_line`,
  `custom_second_line` (landmark), `custom_building_number`, `custom_floor`, `custom_apartment`, `pii_redacted_at`.
  Shape CHECK: 'order' rows carry no custom_*; 'custom' rows need a street > 5 chars unless redacted (then all NULL).
  pickup_city_* / pickup_district_* keep holding the chosen area for both sources. MigrationSmokeTest 116, NotTracedBackfillTest 61.
- **Public API:** lookup `pickup.cities` (every Bosta city with a pickup-available district — only inside the existing
  `pickup` object, i.e. when booking is on AND the delivery city was offered). New `GET /portal/{slug}/districts?cityId=…
  [&mode=exchange]` (lookup token as Bearer; 401 without / other tenant's token; empty when booking off or unknown city;
  RlsCoverageTest EXEMPT). Submit `addressSource` 'custom' + cityId/districtId/firstLine (+ secondLine/buildingNumber/
  floor/apartment): booking must be on, district of that city, pickup-available (+ drop-off for an exchange), street
  6–250 chars, short fields ≤ 20. 'order' / absent = today's path.
- **Booking:** `loadContext` swaps the address block for the custom_* columns (same field names) — type 25 pickupAddress,
  type 30 dropOffAddress; receiver, city/district, everything else unchanged; missing-street message names the typed address.
- **Drawer:** detail `pickupAddressSource` + `customAddress` (only for 'custom'); `CustomAddressBlock` under Pickup (refund)
  and Area (exchange view), "Customer entered a new address"; "Change area" unchanged (area within the snapshot city only).
- **GDPR (closes a pre-existing gap):** customers/redact (same transaction, by orders_to_redact) and shop/redact
  (tenant-wide) now clear every affected request's `customer_email`, `customer_note` AND custom_*, stamping
  `return_requests.pii_redacted_at` (idempotent). Drawer: `piiRedacted` → one "removed after a privacy request" line.
  Checked: `return_request_events` metadata and the exception detectors / exception_resolutions / exception_notifications
  hold no copy of email, note or typed address (asserted by `noCopies_inRequestEvents_orExceptions`; revert-checked).
- **Portal P3:** "Pickup address" radio (shown only when pickup.cities is present) → governorate / area (by zone) / street /
  building / floor / apartment / landmark; Send disabled until complete; 401 on districts → back to start ("expired").
- Tests: PortalCustomAddressTest (13, incl. app_user cross-tenant + positive control, redaction, schema), new booking tests in
  ReturnPickupBookingTest / PortalExchangeBookingTest, PortalPickupAreaTest key list + "cities" (approved);
  portalCustomAddress.test.tsx (7), customAddressDrawer.test.tsx (7). Frontend 550/550.

**Transfers "+ New transfer" chooser (2026-09-28, branch `feature/transfers-new-chooser`, uncommitted, not deployed).** UX only — no backend, createTransfer, mode, scan-out, reconcile or close change. `Transfers.tsx`: Relocate / Return header buttons removed; one "+ New transfer" (header + empty state) is `loading`/disabled until the destination + ever-relocated checks resolve, then opens `NewTransferChooser` (existing `Modal`) with the unchanged gating (Send out and back always; Move to another location if a destination exists; Bring back if also a relocate_out ever existed) — one visible option skips the modal. Type column: relocate_out → "Move to another location", relocate_return → "Bring back", round_trip → its category. i18n: `transfers.new` "New transfer"; `transfers.relocate/return.action` removed; relocate/return title, submit and scanOutSubtitle renamed; new `transfers.chooser.*` (EN+AR). `EmptyState` action gained optional `loading`. tl1 edited (approved) to go through the chooser and wait for the checks; new `transfersNewChooser.test.tsx` (6, revert-checked). Frontend 542/542, tsc + build clean. Screenshots EN/AR taken headless against `vite preview` with mocked API. Noticed (not fixed, likely pre-existing): the Modal backdrop leaves a ~16px uncovered strip at the top of the viewport. Next (separate Step 0): cancel/void for empty transfers (3 stuck in prod), Bring-back piece checkboxes, Modal accessibility. Copy follow-up (same day): `transfers.create.title` → "Send out and back" / "إرسال واسترجاع"; relocate close-confirm → "Close this move?" / "إغلاق عملية النقل هذه؟"; EN `transfers.return.description` / `noPieces` no longer say "relocated". Left as is (not Transfers page): `lookup.phrase.relocated_out` "Relocated to {{location}} — no longer pickable". Still 542/542, tsc + build clean.

**Meta pixel SPA fix (2026-09-28, merged to main, not deployed).** Live bug: after
signup the pixel stayed loaded into /overview; fbevents.js fired a PageView on every SPA history change (one landed with
CompleteRegistration) and would run automatic button-click events in the signed-in app. `metaPixel.ts` now sets
`fbq.disablePushState = true` and `fbq('set','autoConfig',false,ID)` before init; marketing `meta-pixel.js` gets the same
autoConfig line (no more SubscribedButtonClick; PageView + Lead kept). New `signupMetaPixelNavigation.test.tsx` (2,
revert-checked). `signupMetaPixel.test.tsx` test 1 expectation now starts with `['set','autoConfig',false,ID]`
(approved). Frontend 536/536. Live check after deploy: one PageView + one CompleteRegistration, none after signup.

**Meta signup attribution — Build B (2026-09-28, merged to main, not deployed).**
- **nginx:** app.tracedtech.com CSP + `https://connect.facebook.net` (script-src) and `https://www.facebook.com
  https://connect.facebook.net` (img-src, connect-src). `'unsafe-inline'` untouched (still there — separate change).
- **Frontend:** `src/metaPixel.ts`, imported ONLY by `pages/Signup.tsx`: loads fbevents.js on mount (init + PageView, no
  inline script); signup body gains `attribution` {fbp, fbc (cookies), fbclid, utmSource..utmContent (URL)}; after a
  successful signup `CompleteRegistration` with eventID `reg-<tenant claim>`, skipped for @tracedtech.com, once per load.
  `metaPixelEntries.test.ts` walks embedded.html / portal.html import graphs (positive control: index.html reaches it;
  revert-checked); `signupMetaPixel.test.tsx` (4). Built bundles: only `main-*.js` contains connect.facebook.net.
- **Backend:** V116 `tenant_ad_attribution` (PK/FK tenant_id ON DELETE CASCADE, RLS NULLIF policy, app_user DELETE/TRUNCATE
  revoked). `SignupRequest.attribution` is untyped JsonNode (6-arg ctor kept); `SignupAttribution.from()` validates
  (fbp/fbc/fbclid regex, utm ≤ 200, UA ≤ 512, control chars stripped) → bad values null; row only when there is an ad
  signal; never for @tracedtech.com. Inserted in `createTenantWithOwner` behind a SAVEPOINT (insert failure → account still
  created; revert-checked). client_ip = `getRemoteAddr()` (forward-headers native), UA header.
  **Retention (design only):** clear client_ip + client_user_agent once `connected_event_sent_at` is set or 90 days after
  `captured_at`, whichever first. `SignupAdAttributionTest` (7, incl. app_user RLS + same-tenant positive control).
  MigrationSmokeTest 115, NotTracedBackfillTest 60.
- **Fixed (approved):** `AuthIntegrationTest.signupWithConsentPersistsVersionsAndTimestamp` hard-coded `"1.0"` (red since
  bd4babc) — now compares against `PolicyVersions.PRIVACY/TERMS`. `tenant_ad_attribution` added to MigrationSmokeTest's
  tenant-table list. Full suite: 1624 run, only the known reds remain (ExchangeBackfillTest, ShopifyMagicLinkTest).
- Next: ShopifyConnected Conversions API job (reads this table, stamps connected_event_sent_at) + the retention sweep.

**Meta signup attribution — Build A (2026-09-28, on main `440c480` + `dbf1974`, pushed, not deployed).** Marketing + legal only.
- `route-desktop.js` / `route-mobile.js` keep `location.search` (+ hash) on the desktop↔mobile redirect — previously a phone
  visitor from an ad lost `fbclid` before the pixel ran, so `_fbc` was never set.
- `assets/js/signup-params.js` (both pages, external — CSP `script-src 'self'`): one delegated click/auxclick listener copies
  `fbclid` + `utm_source|medium|campaign|term|content` from the page URL onto any `https://app.tracedtech.com/signup` link at
  click time (covers the JS-rendered pricing buttons; never overwrites a param already on the link).
- Privacy policy 1.2 (effective 28 September 2026): §4/§6/§10 Meta Pixel disclosure, Meta Platforms in the sub-processor
  table; `PolicyVersions.PRIVACY` = "1.2". A bump only changes the version stored for NEW signups + shown in
  Settings → Business → consent; there is no re-consent prompt anywhere. Live only with the next APP deploy (Privacy.tsx
  bundles the markdown).
- Next: Build B (app CSP widen, metaPixel.ts on Signup only + CompleteRegistration eventID `reg-<tenantId>`, V116
  `tenant_ad_attribution`; client_ip/user_agent retention = clear after ShopifyConnected sent or 90 days — design only).

**Meta Pixel on the marketing site (2026-09-28, on main, DEPLOYED 2026-09-28 — verified in Events Manager).**
`marketing/index.html` + `marketing/mobile.html` only. Pixel 1837033837461823: base code in `assets/js/meta-pixel.js`
(PageView), Lead on any click of a `calendly.com` link via one delegated listener in `assets/js/meta-pixel-lead.js`.
External files, not inline — the tracedtech.com CSP is `script-src 'self'`. Base code sits right AFTER the
desktop/mobile route script so a redirected load doesn't double-count PageView. CSP widened in `deploy/nginx.conf`
(script-src + https://connect.facebook.net; img-src/connect-src + https://www.facebook.com https://connect.facebook.net),
approved by Marawan 2026-09-28 — live only after the next deploy (nginx reload).

**Step 6b — screens for untracked request items + the drawer-arrival intake gap — built 2026-09-27 on branch `feature/untracked-returns-6b` (from `feature/untracked-returns-6a` `8bef912`; 6a + 6b ship together; not merged, not deployed).**
- **V115 / D:** shipments.return_intake_outcome + 'request_items_arrived'. ReturnRequestLifecycle stamps the request's linked courier-return leg (completed_at, outcome, by, session) when an Arrived action leaves no item awaited — no return_to_receive; undo back to awaiting clears it. listCrpReturns: request_items_arrived → resolved (needs_inspection while a scanned piece is still undecided).
- **Portal:** `lineKey()` = orderItemId for untracked lines, variantId otherwise; submit sends orderItemId for untracked lines (exchange too). No visible difference for the customer.
- **Parcel card (Returns.tsx):** request reference from requestReference ?? itemsRequestReference; `UntrackedItemRow` per untracked request item (Not tracked, Arrived · sellable / Arrived · damaged → outcome + Undo, 6a session endpoints); counts/pill include untracked items; no whole-parcel mark-received on request-linked cards; a completed card collapses like a scanned one.
- **Drawer:** `UntrackedArrivedControls` (RequestLifecycle.tsx) in the item list, the lifecycle list and the X5 exchange view (untracked old item); Undo hidden once a refund exists or the request is finished (backend re-checks).
- **Tests:** backend `UntrackedLegIntakeTest` (2); frontend `untrackedPortal` (3), `untrackedParcelCards` (4), `untrackedDrawer` (4). MigrationSmokeTest 114, NotTracedBackfillTest 59. Renders in the session scratchpad `renders6b/`.

**Step 6a — portal returns / exchanges for UNTRACKED order lines (backend + null-safety) — built 2026-09-27 on branch `feature/untracked-returns-6a` (from origin/main `5f327c6`; not merged, not deployed — 6b adds the screens).**
No new Shopify or Bosta writes; InventoryLedger stays the only piece-status writer; tracked lines unchanged.
- **V114:** return_request_items — piece_id NULLable, order_item_id (FK) + unit_no, CHECK exactly one binding, partial UNIQUE (order_item_id, unit_no) WHERE active (per-piece index kept), arrived_condition (sellable|damaged), arrived_by.
- **Lookup/submit (PortalService):** `LINE_UNTRACKED_SQL` (per-line orderUntrackedSql); untracked lines carry orderItemId + tracked:false (only they); cap = min(quantity, REST current_quantity) − units in non-released items (a unit that came back is not offered again); exchangeOptions as for tracked lines. SubmitLine gains orderItemId (4-arg; 3-arg kept); units bound 1..cap first-free; the unit index maps to the existing 409; exchange may use an untracked line.
- **Arrived (ReturnRequestLifecycle.arrivedUntracked / undoArrivedUntracked):** drawer + session entry points (guards: untracked, awaiting, open request; session open + linked leg / exchange AWB scanned). Sellable → `request_item_to_receive` exception (MEDIUM, /receiving; key carries arrived_at so undo removes it). Undo → awaiting, status back to pickup_booked/approved, exchanges row back to matched. Leg-level mark-received refuses request-linked legs.
- **Canonical rule:** returnLegScanEvidenceSql request clause + untracked-arrival clause (session-scoped via the item_arrived_untracked event). Revert check: clause disabled → e1 fails.
- **Read paths:** detail LEFT JOIN pieces (tracked, orderItemId, unitNo, arrivedCondition); parcel cards add itemsRequestId / itemsRequestReference / requestItems (incl. untracked; also for a Traced-booked exchange AWB) and treat a request with untracked items as complete once none is awaited. Refund suggestion, booking itemsCount/descriptions, read-back, list counts unchanged (variant_id kept, one row per unit).
- **Fix found while building:** tracked-scan attribution could substitute a piece onto an untracked item (CHECK violation) → substitute only among tracked items.
- **Frontend (null-safety only):** item piece code shows "Not tracked"; outcome "Arrived · to receive" for sellable untracked items; Exceptions label for request_item_to_receive. The portal UI does not yet submit orderItemId (6b).
- **Tests:** `UntrackedReturnsTest` (11). MigrationSmokeTest 113, NotTracedBackfillTest 58.

**Landing hero — CTAs no longer hidden by the device mockup (2026-09-27, pushed to main, not deployed).**
`marketing/index.html` only. Root cause: the laptop+phone image (`#rig`) was sized from a guess (`--stage-h: 100vh − 470px`)
while the text block above it grows with vw, so on wide-but-short viewports (1536×864 = 1920×1080 @125%, 1600×900,
1920×960/1016) the bottom-anchored image overflowed its area upward over "Start free"/"View demo" and stole their clicks.
Fix: `.stage` is a `container-type:size` box; `#rig` width = `min(100cqw − 2·gutter, 100cqh·1787/875, 1180px)` so it can
never exceed the stage; stage top margin `max(42px,3.2vh)` (= 28px clearance + the 14px the hero text keeps from its stuck
`[data-r]` translateY — that stuck transform is pre-existing and left alone); h1 max 80px, sub max 17px. "Returned · Not
cleaned" card moved inside `#rig` (`left:78%; bottom:calc(77% + 8px)`, above the phone) with `--fy:0` so its inactive pose
no longer drops onto the phone. Verified by headless sweep (12 desktop viewports × 4 carousel steps: 0 CTA/stage overlap,
CTAs hit-test to themselves, 0 card/phone overlap, ≥28px clearance) + auto-cycle, swipe, resize→scroll handoff (lands
identically to before). Gotcha: `#hero .card{opacity:var(--fade)!important}` keeps inactive cards fully visible in their
offset pose — measure every step, not just the active one. Landing is EN-only by design (no AR/RTL).

**Step 5c — Traced books the Bosta exchange (type 30) on approval; the replacement goes to Pick & Pack; exchange requests finish as "Exchanged" — built 2026-09-27 on branch `feature/portal-exchanges-5c` (from origin/main `e9f0696`; not merged, not deployed).**
MODE B AMENDMENT #3 (CLAUDE.md, BostaV2Client class doc). No new Shopify writes (5a's decrement + trigger 2 cover it); committed stays derived; PICKABLE_ORDERS_FILTER untouched.
- **V112** `return_request_status` + 'exchanged' (own migration). **V113** `tenants.portal_exchanges_since`, `exchanges.return_request_id` (FK, partial UNIQUE), close_reason + 'exchange_failed'.
- **Create:** `BostaV2Client.createExchange()` + `exchangePayload()` (type 30, cod 0, dropOffAddress, businessLocationId, receiver, specs/returnSpecs 1 each, businessReference = order number, uniqueBusinessReference = request id). Shares only the private one-attempt transport with `createReturnPickup` (both keep their own builders). Never logs payload/response.
- **Booking:** `ReturnPickupBookingService` dispatches by type: `exchangePrecondition()` (approved; exchanges + pickup booking on; Bosta + return location; district pickup AND drop-off available; replacement in stock via VariantStockService; not redacted; firstLine > 5; name + phone) → claim → ONE POST → CREATED: saveBooked → `attachExchange` (ExchangeService.attachForRequest) → read-back (type 30, cod 0, specs/returnSpecs 1, businessReference, district from dropOffAddress). Confirm-by-tracking checks type 30 with exchange wording. `bookNow()` + `POST /return-requests/{id}/booking/book-now`. Sweeper: exchange orphans only when decided ≥ `portal_exchanges_since`; (e) re-attaches booked exchanges missing their Traced exchange (SweepResult.exchangesAttached). `approveExchange` enqueues after commit when pickup booking AND exchanges are on. New 8-arg constructor (ExchangeService); the 7-arg one stays (refund-only wiring).
- **Exchange in Traced:** `ExchangeService.attachForRequest()` — insert-or-attach the row (raw `{}` until the first webhook), claim + commit (internal order + forward leg), PII from the original delivered leg, match_method 'reference', status matched. `findOwnExchange()` + `ExchangeIngestService.upsertOwn()/refreshOwnRaw()`; BostaWebhookJob routes ours without HOLD / tryAutoMap / attemptMatch. tryAutoMap / attemptMatch / resolveIfDispositionedPieceMatches / exchange_needs_mapping skip `return_request_id` rows.
- **Lifecycle:** reevaluate → 'exchanged' for exchanges (never refund_pending); refunds / mark-refunded / refund suggestion 409 for exchanges; close 'exchange_failed' (exchanges only). pickup_booking_problem has exchange wording.
- **EX.6:** the drawer's "Change area" list and PUT /pickup-area for EXCHANGE requests offer / accept only districts that are pickup- AND drop-off-available (refunds unchanged).
- **Settings:** "Allow exchanges" (`exchangesEnabled` in PortalSettingsService; codes EXCHANGES_NEEDS_BOSTA / _RETURN_LOCATION / _BOOKING; stamps since on the flip; pickup booking off → exchanges off). **Portal:** lookup `pickup.exchangeDistrictIds` (only when exchanges on); exchange-mode P3 shows only those; submit re-checks.
- **UI:** X4 approve copy per spec; X5 `ExchangeProgressView` (7 derived steps, AWB + internal order number, reused BookingState, Book now, close with "Exchange failed"); list/drawer label "Exchange booked" / "Exchanged". EN/AR.
- **Tests:** `PortalExchangeBookingTest` (18: payload ×2, CREATED, idempotency ×2, preconditions, enqueue, since/Book now, races ×2, read-back, E2E ×3 incl. 5a decrement once and failed swap net zero, cross-tenant ×2, portal areas, settings). Revert check: a retry loop in createExchange → 3 POSTs → i2 fails. Frontend `exchangeBooking.test.tsx` (12), `portalExchangeAreas.test.tsx` (2). MigrationSmokeTest 112, NotTracedBackfillTest 57.
- **Existing tests updated (approved 2026-09-27):** `PortalExchangeTest.a1` (asserted 5b's "no booking for exchanges" — now: no CRP, the type-30 attempt stops at its preconditions in that fixture) and `exchangeRequestDrawer.test.tsx` X4 copy (the spec's new footer text). Full run: 1597, only the 3 known failures after the update.

**Step 5b — portal exchange requests (another variant of the same product, same price) up to approval — built 2026-09-27 on branch `feature/portal-exchanges-5b` (from origin/main; not merged, not deployed).**
No Bosta writes, no Shopify writes, no piece-status writes. Committed inventory stays derived; PICKABLE_ORDERS_FILTER untouched.
- **V111:** `return_requests.type` CHECK ('refund','exchange'); `return_requests.refund_fallback_ok` (NOT NULL default false);
  `return_request_items.replacement_variant_id` (FK variants, NULL for refunds, cleared on switch); `tenants.portal_exchanges_enabled`
  (default false, **no Settings switch yet** — set by SQL until 5c).
- **Lookup / config:** only when the tenant has exchanges on — config adds `exchangesEnabled: true`, each lookup line adds `optionAxes`
  [{name, kind colour|size|option}], `currentOptions`, `exchangeOptions` [{variantId, title, options, inStock}] (the other variants of
  the same product; `inStock` = VariantStockService `available > 0`). Keys are absent otherwise, so PortalBrandingTest / PortalLookupTest
  exact-key assertions are unchanged. `ExchangeOptions` (portal, not a bean): option values from `variants.raw.option1..3` (REST,
  webhook-ingested variants) with axis names from `products.raw.options[].name`; otherwise parsed from the title split on " / "
  ("Default Title" → none); axis kind from the name (color/colour/لون, size/مقاس), else all-values-look-like-sizes → size, and with
  two axes the other one → colour.
- **Submit `mode:'exchange'`:** exactly one line, quantity 1, a valid reason, `replacementVariantId` a different variant of the same
  product and in stock (re-checked server-side), `refundFallbackOk`; stored as type 'exchange'; never auto-approved (portal_auto_approve
  ignored). Refund mode unchanged. 'requested' event metadata carries type + replacement.
- **Merchant:** list/detail carry `type`; detail adds `refundFallbackOk` and per item `replacementVariantId/Title/Available/InStock`
  (live). `approve()` on an exchange locks the row, re-checks stock → 409 `REPLACEMENT_OUT_OF_STOCK`, else approved with **no booking
  enqueue**. `POST /return-requests/{id}/switch-to-refund` (owner/manager): requested + exchange + refund_fallback_ok, else 409 → type
  refund, replacement cleared, event `switched_to_refund`, then the normal refund approve (booking as for any refund).
- **CRP guard (not in the spec, required):** `ReturnPickupBookingService` never books a type-25 CRP for an exchange — `bookInTenant`
  returns early and the sweeper's orphan query adds `rr.type = 'refund'`. 5c books the single Bosta EXCHANGE trip.
- **UI:** portal X1 (Return / Exchange cards, one-item radio + reason select under the chosen item), X2 (colour / size chips, own
  combination "· yours", out-of-stock disabled, sizes in wearing order, summary, refund-fallback checkbox pre-ticked), P3 as step 3 of 3
  with an "Exchanging" card, X3 confirmation; hidden unless config says enabled. Merchant drawer X4 / X6 (`ExchangeRequestView`) and the
  Exchange pill (drawer header + Requests list). EN/AR.
- **Tests:** `PortalExchangeTest` (11: lookup ×3, submit ×3, approve / 409 / switch / detail ×4 incl. no CRP booking or sweep, app_user
  cross-tenant for approve + switch with a same-tenant control); MigrationSmokeTest 110, NotTracedBackfillTest 55 (standing approval).
  Frontend `portalExchanges.test.tsx` (5), `exchangeRequestDrawer.test.tsx` (8).
- **Next (5c):** Settings switch for `portal_exchanges_enabled`; approving an exchange puts the replacement in Pick & Pack and books one
  Bosta EXCHANGE (type 30) trip — needs its own Mode-B amendment; the approve helper copy then becomes the mockup's.

**Step 5a — Shopify decrement when an exchange replacement leaves Traced custody — built 2026-09-26 on branch `feature/exchange-dispatch-decrement` (from origin/main `3fa7770`; not merged, not deployed).**
Approved 2026-09-26: the fourth named decrement. Internal exchange orders (`internal:exchange:%`) never exist in Shopify, so every
exchange left Shopify one unit too high.
- **Gateway:** `ShopifyGateway.pushExchangeDispatch()` / `ShopifyHttpGateway` — own mutation constant, single attempt, negative-delta
  guard before any network call, `changeFromQuantity: null`, `referenceDocumentUri traced://piece/{id}`; no shared code with the other
  three. `adjustInventoryQuantities` untouched.
- **Service:** `ShopifyInventoryService.onExchangeReplacementDispatched(tenantId, pieceId)` (@Async) → `processExchangeDispatch` — eligibility
  (active/packed allocation to an `internal:exchange:%` order named by an `exchanges` row; piece at the fulfillment location) → claim
  `exchange_dispatch`/piece id → preconditions → one call → markResult. Static helpers `leavesCustody(from, to)` and `afterCommit(Runnable)`.
  Re-push: `repushFailedVoidOrHold` accepts `exchange_dispatch` (released allocations accepted there, so a re-push after a restock still works).
- **Hooks:** `PickupSessionService.closeSession()` — inside the `try`, right after `ledger.transition(..., WITH_COURIER, "handed_to_courier")`,
  registered after-commit (the close runs in one transaction). `BostaWebhookJob.applyMappedState()` — inside the piece loop's `try`, right after
  `ledger.transition(..., "courier_update")` (no outer transaction there, so it runs at once); catch blocks untouched.
- **V110:** widens `shopify_inventory_adjustments_trigger_type_check` with `exchange_dispatch` (approved mid-build — the spec hadn't listed it).
- **Exceptions:** `void_hold_sync_failed` now also covers `exchange_dispatch` (CRITICAL, EN/AR "exchange replacement"). No frontend change.
- **Tests:** `ExchangeDispatchDecrementTest` (16: exactly once ×5 incl. pickup→webhook, webhook→pickup, duplicates, packed→delivered;
  never ×6 incl. Shopify order, demo order, pack, unpick, no-op, non-fulfillment location; reversal ×2; failure + re-push with the same key;
  both guards; app_user cross-tenant). Revert: webhook hook disabled → e2/e3/e4 fail. Edited: NamedDecrementSetGuardTest (+rule, pre-approved),
  MigrationSmokeTest 109, NotTracedBackfillTest 54 (approved with V110).
- **Past drift is NOT corrected** (stock take can't — those pieces aren't missing); merchants lower Shopify by the per-variant count from the
  5a diagnosis SQL, once, after deploy.

**Step 4d-2 — refunds, suggested amount, alerts, exact per-return displays, lifecycle screens (R1–R6) — built 2026-09-26 on branch `feature/portal-4d2` (from origin/main `f35464d`; not merged, not deployed).**
No Shopify WRITES (one order READ for the suggestion), no Bosta writes, no piece-status writes.
- **V109:** `return_refunds` (append-only: kind refund|void, `voids_refund_id`, method cash/instapay/wallet/bank_transfer/other,
  amount numeric(12,2) > 0, currency default EGP, refunded_on, reference ≤ 100, note ≤ 300, recorded_by, created_at clock_timestamp;
  shape CHECK; partial UNIQUE = a refund is voided once; RLS NULLIF; app_user INSERT/SELECT only; ON DELETE CASCADE from the request).
  `return_requests.refunded_at/_by`. `tenants.refund_pending_window_days` (5), `return_arrival_window_days` (10) — no settings UI yet.
- **Suggestion** `GET /return-requests/{id}/refund-suggestion` (owner/manager, `RefundSuggestionService`): items arrived/done (else awaiting),
  per variant × quantity; sources in order `shopify` (new read-only `ShopifyOrderPriceGateway` → `order(id:)` `currencyCode`,
  `lineItems.nodes.quantity / variant.id / discountedUnitPriceAfterAllDiscountsSet.shopMoney.amount`, validated against Admin GraphQL
  2026-04 = the pinned `shopify.api-version`, scope read_orders), `stored_order` (orders.raw REST line: price − Σ discount_allocations /
  quantity), `catalog` (variants.price, approximate). Shipping excluded. Any Shopify failure / disconnected store / unknown variant → next
  source; nothing → amount null. Never logs the payload.
- **Refunds (all in `ReturnRequestLifecycle`):** `POST …/refunds` (received / refund_pending; validation 400; currency = order's
  raw currency else EGP; event refund_recorded), `POST …/refunds/{refundId}/void` (void row, once, not after refunded; event
  refund_voided), `POST …/mark-refunded` (refund_pending + ≥ 1 non-voided refund → refunded, refunded_at/by; event refunded).
  Detail adds `refunds` (void state), `refundTotal`, `currency`, `history` (newest first — `events` stays oldest first for 4d-1
  callers), item `disposition`/`damageReason`, `unexpectedItems`, `returnTrackingNumber`, `linkableParcels` (+ candidate requests, for R5).
  List adds arrived/awaiting counts, closeReason, currency, refundTotal, refundOverdueDays, unexpectedItem.
- **Alerts:** `refund_pending_overdue` (HIGH, `ReturnRequestLifecycle.REFUND_OVERDUE_SQL`, shared with the list badge) and
  `return_items_overdue` (MEDIUM, clock = latest pickup_booked event → booking attempt → approval; `ITEMS_OVERDUE_SQL`). EN/AR with the
  RR reference + order number; link to the request; resolve via the usual note flow. `return_link_ambiguous` link now also carries
  `&parcel=<shipmentId>`, which opens R5 by itself.
- **Exact displays (RP.14 closed):** `listCrpReturns` — a request-linked leg counts its request's `arrived` items; legs without a request keep
  the order-level count; rows add `request_reference` + `pending_inspection_count`. Parcel cards — a request-linked leg shows
  `requestReference`, exactly its awaiting items as expected and the scans attributed to it; other legs unchanged. No money on worker screens.
- **UI:** drawer 580px; lifecycle layout once anything came back or received/refund_pending/refunded (Customer / Order / Returned or
  Parcel; items with outcome; different-product flag; R1 refund form with method chips, prefilled amount, date ≤ today, reference, note,
  restock warning; R2 refunds list + void confirm + add another + history + Mark as refunded; R3 partial actions); R4 close dialog; R5
  link dialog (drawer prompt + exception link); R6 pills/badges + "{n} awaiting refund". EN/AR, `<bdi>` for AWBs/references.
- **Tests:** `ReturnRefundsTest` (15: suggestion ×4, refunds ×4, append-only, alerts ×2, displays, roles ×2 over HTTP, app_user cross-tenant),
  RlsCoverageTest + refund-suggestion (approved), MigrationSmokeTest (108 + return_refunds), NotTracedBackfillTest (53);
  frontend `returnRequestRefunds.test.tsx` (16). Renders R1/R2/R3/R6 EN+AR (session scratchpad).
- **Decisions / deviations:** R6 keeps today's columns and tab order (Marawan, 2026-09-26) — only pills, badges and the tab badge
  added; R1 keeps the customer's note (an existing 4d-1 test reads it) and adds the spec's optional Note field (not in the mockup);
  R2 hides the customer/items block like the mockup; history `newest first` is a new `history` key; amounts shown with Latin digits.

**Step 4d-1 — link returned parcels to requests, attribute and reconcile items, request lifecycle, history — built 2026-09-26 on branch `feature/portal-4d1` (from origin/main `55e5333`; not merged, not deployed).**
Backend + status labels only (screens are 4d-2). No Shopify writes, no Bosta writes, no new piece statuses.
- **V107:** `return_request_status` + `closed` (own migration — an added enum value can't be used in the same transaction).
- **V108:** `return_requests` + `received_at`, `refund_pending_at`, `closed_at/_by`, `close_reason` (no_refund / rest_not_coming / other),
  `close_note` (≤ 300), `link_source` (traced_booking / auto_matched / merchant_selected; existing links backfilled traced_booking),
  partial UNIQUE on `return_shipment_id` (one request per leg). `return_request_items.item_status` (awaiting / arrived / done /
  not_coming) + `arrived_at` / `done_at`; backfill awaiting-if-active else not_coming; CHECK `active = item_status IN (awaiting, arrived)`.
  `return_session_items.request_item_id` (FK ON DELETE SET NULL). `return_request_events` (RLS NULLIF policy, app_user INSERT/SELECT only,
  FK ON DELETE CASCADE from the request, `occurred_at DEFAULT clock_timestamp()` so events written in one transaction keep their
  order); backfilled requested / approved / rejected / pickup_booked from the existing columns (`metadata.backfilled`).
- **`ReturnRequestLifecycle` (portal package, NOT a bean — built from each caller's own JdbcTemplate, like PickupAreaService):** the only
  writer of request events, item status and post-approval request status. Used by ReturnSessionService, ShipmentLinkService,
  ReturnPickupBookingService, ReturnRequestService, PortalService — no constructor signatures changed.
- **Linking:** `linkByTracking` (webhook side, replaces `linkReturnRequest`'s UPDATE; skips a leg another request holds);
  `saveBooked` sets `link_source` and never takes a held leg; sweeper step (d) `repairTrackingLinks` fixes the booking/webhook race
  (`SweepResult.linksRepaired`). Hand-booked: `autoMatchLeg` after the tracking link found nothing — candidates
  `ReturnRequestLifecycle.linkCandidateSql` (same order, approved, no leg, no Traced tracking number, booking NULL/failed, created_at ≤
  the leg's Bosta `raw.createdAt`); exactly 1 → linked auto_matched + pickup_booked; 0 → nothing; 2+ → nothing, and the
  `return_link_ambiguous` detector (MEDIUM, subject = the leg, EN/AR text with the order and candidate references, deep link to the first
  candidate) lists it. `POST /return-requests/{id}/link-leg {shipmentId}` (owner/manager): return leg (type 25 when raw says so) of the
  same order, not held, request open and without a leg → merchant_selected (approved → pickup_booked).
- **Scans (`scanPiece`, DELIVERED):** `attributionFor` — (1) the piece is bound to an awaiting item of an open request
  (approved / pickup_booked / received) on its order, or (2) the piece is bound to no live item and an open request on the order has an
  awaiting item of the same variant (oldest request first) → substitute, `item_substituted {from, to}`, piece_id swapped. Either →
  accepted regardless of the window, item arrived, `request_id` / `request_item_id` in the `return_received` metadata and
  `return_session_items.request_item_id`, `return_kind = request_return` (precedence exchange_match > request_return > crp_return >
  customer_after_delivery). Accepted but unattributed (e.g. other variant) → `unexpected_item_received` on each open request of the order.
- **Lifecycle (`reevaluate`, after every attributed scan, final disposition, link and merchant action):** received when no item is
  awaiting and ≥ 1 arrived/done; refund_pending when additionally none is still arrived. A restocked/damaged disposition finishes its item
  at once (done, active false); mismatch doesn't. `POST …/rest-not-coming` (awaiting → not_coming; nothing ever arrived → closed
  rest_not_coming). `POST …/close {reason: no_refund|other, note}` from approved / pickup_booked / received / refund_pending (awaiting →
  not_coming, arrived → done). Reject → items not_coming. `refunded` not set (4d-2). Detail adds lifecycle fields, item status and `events`.
- **Canonical rule (approved change):** `returnLegScanEvidenceSql` checks first: a `return_received` carrying the request_id of the request
  whose `return_shipment_id` is the leg. It only ADDS evidence; Rules 1–2 unchanged.
- **Frontend:** `closed` pill (neutral), status label + close-reason labels EN/AR, drawer "Why it was closed" (+ note) when closed; the
  `return_link_ambiguous` type label on the Exceptions page.
- **Tests:** `ReturnRequestLifecycleTest` (22: L1–L7 linking, S1–S4 scans, C1–C5 lifecycle, E1–E2 history/detail, X1–X4 app_user
  cross-tenant with positive controls), `ReturnReservationReleaseTest` (1), frontend `returnRequestLifecycle.test.tsx` (6).
  Revert runs: the reservation test on origin/main fails (new customer's lookup shows 0 returnable); with the request clause disabled S4
  fails. Pre-approved test edits: MigrationSmokeTest (107 migrations, + `return_request_events` in the tenant list),
  NotTracedBackfillTest (52 pending after V56).
- **Deviations:** auto-match is deferred while any request on the order has a Traced booking `pending` (its tracking number isn't saved
  yet, so the leg may be its own); legs with an unreadable `raw.createdAt` are never auto-matched; close turns arrived items into done
  (their pieces are already back); `cancelled` has no code path, so nothing releases on it; read-back counts items awaiting/arrived/done.
- **Not done (still open):** `listCrpReturns` pending count and parcel-card grouping are still order-level (RP.14); Rule 2 can still use
  another leg's request-attributed scan as evidence for a no-request leg (RP.30).

**Step 4c-3 — book the Bosta customer return pickup (type 25) on approval — built 2026-09-25 on branch `feature/portal-4c3` (from origin/main `fb29db8`; not merged, not deployed).**
MODE B AMENDMENT #2 implemented: create type 25 only, from an approved request, claim-before-call, businessReference = order
number. No terminate, no edit, no other Bosta write.
- **V106:** `return_requests.booking_status` (pending/booked/failed/failed_ambiguous/needs_review), `booking_attempted_at`,
  `booking_error`, `bosta_delivery_id`, `bosta_tracking_number` (unique when set), `booking_verified_at`;
  `tenants.portal_pickup_booking_since` (deviation — see below).
- **Create:** `BostaV2Client.createReturnPickup` — its own RestClient (connect 5 s, read 20 s; `bosta.create-read-timeout`),
  ONE attempt, no retry decorator. 2xx+trackingNumber → CREATED; 429/other 4xx → NOT_CREATED (Bosta's message);
  ConnectException/UnknownHost/NoRouteToHost → NOT_CREATED (nothing sent); 5xx/timeout/reset/unreadable 2xx → AMBIGUOUS.
  Logs request id, outcome, status only. Payload builder `returnPickupPayload` is a pure function.
- **Booking:** `ReturnPickupBookingService` (programmatic transactions; app_user-constructible). Preconditions → 'failed' + reason,
  no POST (not approved, booking off, no active account, no return location, no/unavailable district, redacted, firstLine ≤ 5,
  plus: no receiver name/phone, no items). Claim UPDATE → 'pending' from NULL/'failed' only; POST outside any transaction;
  result in its own transaction; CREATED → booked + request pickup_booked + link existing return leg; then read-back.
  `ReturnPickupBookingJob` (`@Job(retries = 0)`); `PickupBookingScheduler.enqueueAfterCommit` (approve), enqueue after the
  submit transaction (auto-approve), both only when the tenant books pickups. Sweeper `ReturnPickupBookingSweepJob` every
  10 min (tenant ids via the owner pool like BostaStatusPollJob; work per tenant under runAs).
- **Read-back:** v0 `fetchDelivery`: type 25, businessReference (equal or equal without '#'), cod 0 (missing = 0),
  itemsCount (returnSpecs, falling back to specs), customer district (pickupAddress first, else dropOffAddress).
- **Merchant:** POST `/return-requests/{id}/booking/retry` (from failed), `/booking/not-booked` (ambiguous → failed → retry),
  `/booking/confirm {trackingNumber}` (ambiguous only; type 25, this order, created ≥ claim − 2 min, not on another request).
  Change area blocked while pending/booked/needs_review. `pickup_booking_problem` exception (HIGH; key includes the attempt
  time so a later failure is a new exception) → `/exchanges?tab=requests&request=<id>` (new deep link).
  Settings switch; drawer booking row; list "Attention" badge; portal copy (P1/P4, auto-approve lead) EN/AR.
- **Tests:** `ReturnPickupBookingTest` (19; real HTTP Bosta stub for v2 create + v0 read). Revert: the create wrapped in the
  v0 Resilience4j retry → the timeout case makes 3 POSTs and the test fails. Frontend +20 (portal copy, drawer booking,
  settings switch). Renders: drawer states EN/AR + requests list.
- **Deviations:** `portal_pickup_booking_since` (so switching booking on never books older approvals the merchant may have
  booked by hand); connection-never-made = NOT_CREATED; two extra preconditions (receiver name/phone, items); manual entry
  allows 2 min of clock skew; businessReference compared with/without '#'; cod missing = 0; itemsCount falls back to specs.
- **Not verified live:** the v2 create itself (fields, cod 0 on type 25, Bosta's rearrangement, error bodies). Stage it first.

**Step 4c-2 — Bosta reference data, return warehouse, pickup area in the portal (no booking) — built 2026-09-25 on branch `feature/portal-4c2` (from origin/main `cd1ec79`; not merged, not deployed).**
No Bosta writes. Two new Bosta v2 READS in `BostaV2Client` (v0 client and `bosta.api-version` untouched).
- **V105:** `bosta_districts` (global, no tenant_id/RLS, app_user SELECT only — INSERT/UPDATE/DELETE/TRUNCATE
  revoked; PK district_id, index city_id); `tenants.portal_pickup_booking` (default false); `courier_accounts.
  return_business_location_id/_name`; `return_requests.pickup_city_id/_name, pickup_district_id/_name/_name_ar`.
  MigrationSmokeTest only checks its explicit TENANT_SCOPED_TABLES list, so a table without tenant_id is simply
  not checked (like bosta_state_mappings); the grants are proven in `BostaDistrictsRefreshTest` on app_user.
- **Districts refresh:** `BostaDistrictsRefreshService` — getAllDistricts with NO Authorization header, one owner-pool
  transaction: upsert all (stamped with the run time), then `pickup_available = false` on rows not touched; never
  deletes; empty/failed fetch changes nothing; 401/403 logs "needs auth" and stops (never a tenant key). A district is
  pickup-available only when both it and its city say so. `BostaDistrictsRefreshJob`: daily 04:00 Africa/Cairo +
  enqueued at startup when empty (fail-soft). Approved owner-connection use, this table only (CLAUDE.md).
- **Return warehouse:** `GET /api/v1/tenant/bosta/return-locations` (owner/manager) → [{id,name,isDefault,cityName}]
  via the tenant's decrypted key (raw header); errors with a body: 409 NO_BOSTA_ACCOUNT, 422 BOSTA_KEY_REFUSED
  ("Bosta didn't accept the connected API key for locations"), 502 BOSTA_UNAVAILABLE. PUT portal-settings accepts
  `returnLocationId`: checked against a fresh fetch BEFORE the settings transaction (400 RETURN_LOCATION_UNKNOWN);
  the already-saved id is accepted without calling Bosta; absent/blank leaves it unchanged (not full-replace).
  GET adds `returnLocationId/Name` and `portalPickupBooking` (existing `pickupBooking` kept, same value).
- **Portal:** config `pickupBooking` = the tenant column. Lookup always has `pickup` — {cityId, cityName, cityNameAr,
  districts[{id,name,nameAr,zoneName,zoneNameAr}] (pickup-available, by zone then name), preselectedDistrictId (the
  forward leg's district when in the list)} or null (booking off / city unknown / none available). Submit re-derives
  the offer; when offered, `districtId` must be in it (else the generic 400) and is snapshotted; otherwise ignored.
  `PickupAreaService` (city = newest delivered forward leg's raw.dropOffAddress.city._id) is shared by lookup, submit
  and the drawer; it is built from each service's own JdbcTemplate so app_user-constructed tests read on it too.
- **Merchant:** drawer Pickup = snapshot "city · district" (Arabic district in AR), else the order address; "Change
  area" (requested/approved) → `GET /return-requests/{id}/pickup-areas`, `PUT .../pickup-area {districtId}` (409 other
  statuses, 400 not available, owner/manager). Settings: "Returns go back to" native select (default preselected
  when nothing saved; error text + saved name + retry on failure). Neither control is in the M2/M4 mockups.
- **Tests:** backend `BostaV2ClientTest` (real JDK HttpServer: no Authorization header, raw key, 401s, Arabic +
  pickup=false parsing), `BostaDistrictsRefreshTest` (upsert/deactivate/empty/401, app_user grants),
  `PortalPickupAreaTest` (13: lookup, submit, drawer + app_user cross-tenant, return locations, settings, config);
  `PortalLookupTest` exact-key list + "pickup" (pre-approved); `RlsCoverageTest` COVERED + two tests for the new
  GETs (approved): pickup-areas own 200 / other tenant 404; return-locations uses only this tenant's decrypted key. Frontend `portalPickupArea`, `returnRequestArea`,
  `returnsPortalWarehouse` (13). Fixtures follow the vendor spec's documented shapes, not live captures.
- **Not verified live:** getAllDistricts really being unauthenticated, and v2 pickup-locations accepting the raw
  v0-style key — both need a staging check before the booking step relies on them.
- **Gotchas:** `mvn test`/`vite build` output under src/main/resources/static is tracked — restore it after a build.
  `renderWithProviders` uses its own English-only i18n; to test Arabic, nest `<I18nextProvider i18n={appI18n}>`.
  Postgres permission errors surface from Spring as BadSqlGrammarException (SQLState 42501) — assert on the cause.

**Step 4c-1 — more than one courier-return (CRP) leg per order — built 2026-09-25 on branch `feature/multi-return-legs` (from origin/main; not merged, not deployed).**
Before: V43's `ux_active_shipment_per_order_leg` let a finished return leg hold the order's only
return slot forever, so a second CRP (second portal request, or one after a dashboard CRP) was
booked in Bosta but landed in `unlinked_bosta_deliveries` (production: 9 Jumi orders have a
finished leg in the slot).
- **V104:** `ux_active_forward_shipment_per_order` = UNIQUE (order_id) WHERE forward AND not
  terminated/cancelled, created first; then V43's index dropped. Return legs: global UNIQUE
  `tracking_number` only (`createOrFindReturnShipment` already finds by tracking before insert).
- **Canonical rule `ShipmentLinkService.returnLegScanEvidenceSql(sessionIdExpr)`** — "return leg has
  scan evidence". Rule 1: own AWB scanned in a non-abandoned session holding a `return_received`
  event for the order. Rule 2: the order's only unstamped return leg + a `return_received` event for
  the order at/after the leg's `created_at`. `sessionIdExpr` = null (any session) or one session.
  Two refinements beyond the approved text (both change nothing on a single-leg order):
  (a) an event from a session in which ANOTHER return leg's AWB was scanned is that leg's, not
  Rule 2 evidence for this one — without it the approved test "A never scanned, B scanned via its
  AWB → A still awaiting" fails once B is stamped (A becomes the only unstamped leg and B's scan
  hides it; proven by a revert run); (b) a leg in created/with_courier/returning gets no Rule 2
  evidence while the order has another non-terminated/cancelled return leg (Marawan, option 1:
  single-leg courier lag stays exactly as today). `terminated`/`cancelled` legs never count as
  "other legs" (they never held V43's slot, so the old single-leg world is preserved).
- **Used by:** `ReturnSessionService.close()` (stamps only legs with evidence from THIS session;
  single UPDATE, so "only unstamped leg" is judged on the pre-close state) and so the auto-close
  job; `resolveReturnLegIfComplete()` (only unstamped legs with evidence; still writes
  `internal_state` only — no history row, no `returned_at`, unchanged); `RETURN_LEG_AWAITING_SCAN_SQL`
  (awaiting-scan callout + `return_leg_unscanned`).
- **Tests:** `MultiReturnLegTest` (7): m1 close stamps only the AWB-scanned leg, in-transit leg
  neither stamped nor flipped (also in a later session); m2 older unscanned leg still awaiting +
  still raises `return_leg_unscanned`; m3 two unstamped legs + items, no AWB → neither stamped;
  m4 second CRP on an order with a finished leg links as a return leg via the real ingest path;
  m5 single in-transit leg + item scan → stamped and flipped as today; m6/m6b multi-leg in-transit
  legs not stamped without their own AWB. Revert runs: old Java logic → m1 m2 m3 m6 m6b fail; old
  awaiting SQL only → m2 fails; literal Rule 2 (no refinement a) → m2 fails; no V104 → m4 (and
  others) fail. `MigrationSmokeTest` 103, `NotTracedBackfillTest` 48.
- **Single-leg edge changes (accepted by the approved rule, no existing test covers them):** close()
  now needs the session's event to be at/after the leg's `created_at` (before: any event of the
  session) and `resolveReturnLegIfComplete` now needs evidence and skips stamped legs (before:
  flipped every non-terminal return leg). Only differs when a scan predates the leg's Traced
  insert (e.g. a CRP linked late) or a stamped leg is still non-terminal.
- **TODO(4d), not built:** `listCrpReturns` pending_inspection_count is order-level; parcel cards put
  all scanned items on the order's first card; `manualLink()` still turns a CRP into a forward leg
  (RS.7, untouched); The Snouts' unlinked type-25 delivery has no matching order (not the index).
- **Gotcha:** a spec's revert tests can prove a rule wrong — here the literal Rule 2 failed the
  spec's own m2; run the revert before trusting the wording.

**App Store reviewer fixes — built 2026-09-25 on branch `fix/reviewer-embedded-crash` (rebased onto origin/main `b0447b1`; 4 commits + the landing-webp Bosta-API docs commit; not deployed).**
- **A — embedded white page:** `EmbeddedController.exceptions()` read `exceptionType`/`subjectKey`; ExceptionService rows carry
  `type`/`subject_key` → every embedded exception had type=null → `fmtLabel(null).replace` crashed the Overview (reviewer2 tenant's
  on-hold #1291 = one `blocked_customer`). Keys fixed (response names unchanged); `fmtLabel`/`statusLabel` null → "—";
  `components/ErrorBoundary.tsx` (self-contained, inline DS v1.0 values, EN/AR from `<html lang>`) wraps both roots.
- **B — Pick & Pack:** `GET /fulfill/queue/awaiting-waybill-count` → `{count}` (open, not held, 30d, not self-pickup, NO forward
  shipment; a moved-on shipment is not counted). PICKABLE_ORDERS_FILTER untouched; base predicates restated, equivalence pinned by
  `AwaitingWaybillCountTest`. Empty queue shows the pluralized waybill message (+ Connect Bosta when `/connections` says not
  connected; workers get no link). OrderDrawer: "No Bosta waybill yet" under a disabled View shipment.
- **C — Overview:** fresh-tenant card now also requires `orders/summary.total === 0`; its Shopify step reads `/connections`.
- **Approved test edits applied:** `RlsCoverageTest` EXEMPT entry for the new endpoint; `overview.test.tsx` ov11/ov19 get
  `ordersSummary: ZERO_ORDERS_SUMMARY`. Backend 1464 run, only the 3 known reds; vitest 421/421.
- **Demo first-scan helper:** pick screen, demo tenant only (JWT `tenant` claim === `DEMO_TENANT_ID`, same check as
  StationGate): each unfinished line lists ≤3 available barcodes (existing `GET /inventory/pieces`, OWNER/MANAGER) with a Scan
  button calling PickScreen's own `handleScan` — no second scan path, refocus/scan handlers untouched. Station-mode worker
  tokens get 403 there → helper silently absent. Verified live (local jar + throwaway Postgres): demo start → #DEMO-Q1 → 2 scans
  via buttons → Complete → order `packed`.
- **Demo AWB helper (approved):** in `AwbLinkDialog`, demo tenant only, "Use this AWB" shows THIS order's own forward
  `tracking_number` (already on the `/fulfill/{id}` detail — `shipment_leg='forward'` join, tenant-scoped) and submits via
  the dialog's own `handleLink` → `POST /fulfill/{id}/link` (server AWB_MISMATCH/conflict checks unchanged). No skip; the
  dialog's input focus/refocus untouched. Live: queue → scan via helper → Complete → Use this AWB → "Order complete" →
  `orders.status = awaiting_pickup`. The drawer reads **"Label created"** (OrderStatusDeriver maps a `created` shipment to
  label_created — by design, not raw orders.status).
- **Prod was not on landing-webp:** landing-webp = origin/main minus the 6 portal 4c-1…4c-3 commits (+1 docs commit); prod's
  main bundle carries 18/33 origin/main-only strings. This branch is now rebased onto origin/main (only PROGRESS.md
  conflicted; both entries kept). `main-*.js` hashes can't be compared (VITE_CALENDLY_SETUP_URL is baked in at build time).
- **Findings:** reviewer2 (`8mqr0k-qs`) and reviewer3 stores were disconnected by Shopify `app/uninstalled` webhooks
  (2026-09-25 17:02:22 and 2026-09-24 20:14:33 + `shop/redact`). Demo tenant: 10 pickable Bosta-linked orders reseeded every
  30 min; the pick screen never shows a scannable barcode, so a demo visitor has to look one up elsewhere (not browser-verified).
- **Gotcha:** `mvn test` runs the frontend build into the tracked `src/main/resources/static/` — `git checkout` it afterwards
  unless shipping a build. Layout has no mobile sidebar handling (fixed 224px) — pre-existing, seen at 375px.

**Proxy trust hardening — built 2026-09-24 on branch `fix/proxy-trust` (not merged, not deployed).**
Follows the Step 0 diagnosis (no Cloudflare; Spring trusted client-sent forwarded headers).
- **Spring:** `server.forward-headers-strategy: native` (Tomcat `RemoteIpValve`) replaces `framework`.
  `server.tomcat.remoteip.*` stated explicitly: internal-proxies = Boot 3.3.5's default private
  ranges (the Docker bridge is in 172.16/12), `X-Forwarded-For` / `X-Forwarded-Proto` /
  `X-Forwarded-Host`. Client IP = rightmost non-proxy XFF entry; RFC 7239 `Forwarded` ignored.
  `SpaController`'s two redirects still come out as `https://app.tracedtech.com/…` behind nginx.
- **nginx:** every app.tracedtech.com location that proxies (`= /`, `^~ /embedded`, `/assets/`,
  `^~ /api/v1/portal/`, `/api/`, `/`, `/actuator/health`) sets `Host`/`X-Forwarded-Host $host`,
  `X-Forwarded-Proto https`, `X-Real-IP`/`X-Forwarded-For $remote_addr`, `Forwarded`/
  `CF-Connecting-IP`/`CF-Ray ""` (X-Request-Id and existing `Origin ""` kept; framing CSPs untouched).
  Default servers: `:80 default_server → 444`, `:443 ssl default_server → ssl_reject_handshake on`.
  Cloudflare `set_real_ip_from`/`real_ip_*` removed. Returns host: `CF-Ray ""` added, comment → native.
- **ShopifyEntryDiagFilter:** redacts query/body params `id_token, hmac, session, code, state,
  signature` (raw query string and param list, URL-encoded names too) and headers `Authorization`,
  `Cookie` → `[redacted]`. TODO: remove after App Store approval.
- **Tests:** `ProxyTrustTest` (real HTTP, 7) — Forwarded ignored for IP and host, XFF from the
  loopback proxy sets the IP, client-prepended XFF ignored, demo limit per resolved IP, both SPA
  redirects `https://app.tracedtech.com/…`. Revert-checked: 4 of 7 fail under `framework`.
  `ShopifyEntryDiagFilterRedactionTest` (2) — both fail on the old filter.
- **Gotcha:** `-Dserver.forward-headers-strategy=…` on the mvn command line does reach the forked
  test JVM (proved by the revert check) — handy for strategy experiments without file edits.
- Not changed: `log_format` still records `cf_ray=$http_cf_ray` (client-supplied, log-only).

**Returns portal Step 4e-B — customer portal on returns.tracedtech.com — built 2026-09-24 on branch `feature/portal-4e-customer` (not merged, not deployed).**
Mockups `design/returns-portal/P1–P7`. Two deploy groups: **group 1** = everything below except
the returns port-443 block; **group 2** = one commit adding only that block, deployed after
`certbot certonly --webroot -w /var/www/certbot -d returns.tracedtech.com` (runbook:
`docs/DEPLOY-NOTES.md` §10).
- **Frontend:** third Vite input `portal: portal.html` → `static/portal.html` + `assets/portal-*.js/.css`.
  `src/portal/**` imports nothing from the merchant app (no `api.ts`, `src/i18n.ts`, router, shell,
  `index.css`); own fetch wrapper (`credentials: 'omit'`, Bearer = lookup token on submit only), own
  i18next instance + `src/portal/locales/{en,ar}.json`, plain CSS (`.pp-*`), self-hosted fontsource.
  Language: saved (`localStorage traced.portal.lang`) → browser `ar*` → English; toggle sets `<html dir/lang>`.
  Brand: `brandColor ?? #3656E0`; button text white or #141821 at ≥ 4.5:1, else default blue + white.
  `frontend/scripts/check-portal-bundle.mjs` (run after a build) follows every file `portal.html` loads
  and fails on the app's token-refresh path or merchant-only strings.
- **Backend:** `SecurityConfig` permits `GET /portal.html` only (test fixture
  `src/test/resources/static/portal.html`; the real one is Vite output, never committed).
- **nginx (group 1):** portal zone 30r/m, burst 15; nginx's own 429 on `/api/v1/portal/` →
  `{"message":"Too many attempts. Please try again later."}` JSON via `error_page 429 = @portal_429`;
  returns port-80 block (ACME + 301); stale "commented out"/Cloudflare-edge comments fixed
  (Cloudflare `real_ip` directives kept, documented as inert).
- **nginx (group 2):** returns port-443 block — `/assets/`, `^~ /api/v1/portal/` (zone + JSON 429),
  `= /` and `^/[a-z0-9-]{3,40}/?$` rewritten to `/portal.html`, everything else 404; own CSP,
  X-Frame-Options DENY; forwarded headers overwritten (`X-Forwarded-For $remote_addr`,
  `Forwarded ""`, `CF-Connecting-IP ""`).
- **Mockup vs behaviour:** see the Step 4e-B report — pre-4c pickup copy (no city/area, no "Bosta
  courier collects"), no "Contact {store}" link (no contact data in config), no "Back to store",
  IBM Plex Mono → self-hosted Geist Mono (no third-party requests).
- **DEPLOY-NOTES §2.1/§2.2/§2.4** now match the server: GoDaddy DNS, no Cloudflare; all certificates
  `--webroot /var/www/certbot`, renewed by `certbot.timer` + deploy hook
  `/etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh`, no root crontab; repo at
  `/home/traced/traceability`. 2026-09-24: the app certificate was found expiring the same day
  (renewal was `standalone`, couldn't bind port 80) and was re-issued via webroot.
- **Gotcha:** `nginx.conf` is a single-file bind mount — `nginx -t` inside the running container tests
  the file it started with, not a freshly pulled one. Test with a one-off container (§10 step 4).
- **Flaky test (pre-existing):** `ShopifyMagicLinkTest.expiry_expiredToken_isMagicLinkInvalid` inserts a
  token expired 1 s ago by the JVM clock and is checked against Postgres `now()` — fails when the Docker
  VM clock lags. Passed on re-run.

**Returns portal Step 4e-A — merchant side: Requests tab, approve/reject, portal settings, branding — built 2026-09-24 on branch `feature/portal-4e-merchant` (not merged).**
Stacked on `feature/portal-4b-requests` (main does not contain Step 4b, whose endpoints this step uses).
Mockups `design/returns-portal/M1–M4`. No Bosta calls, no customer-facing UI, no piece moves.
- **V103:** `tenants.portal_logo_url` (CHECK `LIKE 'https://cdn.shopify.com/%'`), `portal_brand_color`
  (CHECK `^#[0-9A-Fa-f]{6}$`), `portal_policy_text` (CHECK ≤ 2000). Nullable, no backfill.
  `MigrationSmokeTest` 101→102, `NotTracedBackfillTest` 46→47.
- **Settings PUT** now carries `logoUrl/brandColor/policyText` (full replace — absent/blank → NULL), validated
  as 400s before the single UPDATE. Every settings 400/409 now has a body `{field, error, message}`
  (error codes `SLUG_FORMAT/SLUG_RESERVED/SLUG_REQUIRED/SLUG_TAKEN/WINDOW_RANGE/LOGO_URL/BRAND_COLOR/POLICY_LENGTH/REQUIRED`) —
  answered in `ReturnsPortalAdminController` because the global `ResponseStatusException` handler is bodyless.
  GET also returns read-only `pickupBooking` (constant `PortalService.PICKUP_BOOKING = false` until Step 4c).
- **Public config** adds `logoUrl, brandColor, policyText, autoApprove, pickupBooking:false` — nothing else.
- **`GET /api/v1/variants?search=&page=&size=`** (owner/manager): id, productTitle, variantTitle, sku,
  nonReturnable; ILIKE on product title / variant title / SKU with `%`/`_` literal; ORDER BY product title,
  variant title, id; `{items, total}`. In `RlsCoverageTest.COVERED` with a cross-tenant test.
- **Request detail** adds `customerPhone`, `deliveredAt` (latest forward-leg `delivered_at`), `pickupCity/pickupZone`
  (`orders.address` city/zone) for the M2 drawer.
- **UI:** E&R gains a "Requests" tab (owner/manager only; "{n} new" badge = `status=requested` total) with its
  own paginated fetch (25/page), drawer (M2) and inline reject form (M3, ≤ 300, counter). Pre-4c footer copy:
  "After approving, book the pickup in Bosta." 409 → "This request was already decided — refreshed." + reload.
  Existing E&R tabs untouched (the stat chips hide only while Requests is active). Settings gains a
  "Returns portal" tab (`?tab=portal`, owner/manager): switches (`role="switch"`), link + Copy, window,
  auto-approve, non-returnable list (each switch saves immediately), Branding (logo link + preview, colour,
  policy). `Tabs` got an optional `badge`, `Toggle` an optional `ariaLabel`, `CopyRow` exports `writeToClipboard`.
- **Mockup vs behaviour:** order number in the drawer is plain text (no `/orders/:id` route exists);
  auto-approve helper doesn't promise a Bosta booking before 4c; M4 is a Settings tab, not a standalone page;
  the "Exchanges" tab the mockup omits is kept.

**Shopify OAuth connect failures now visible — built 2026-09-24 on branch `feature/portal-4b-requests` (uncommitted; display-only).**
- Every failure in `/auth/shopify/install` and `/auth/shopify/callback` → 302 to
  `{appUrl}/settings?tab=connections&shopify_error=CODE` (codes only: `SHOP_LINKED_ELSEWHERE`,
  `SHOP_MISMATCH`, `INSTALL_EXPIRED` = state invalid/expired/replayed + stale timestamp,
  `INSTALL_FAILED` = HMAC, bad shop, token exchange, anything unexpected). Nothing from the request
  is reflected. `initiate()` still JSON. Decision tree / service / schema untouched.
  The old `/connect/error` target (a dead React route that silently bounced to /overview) is gone.
- **NOT_LINKED unchanged** (still redirects into the embedded app's NotLinked state).
- `ShopifyConnectionCard` shows a warning banner (EN/AR, dismissible) per code — unknown/empty →
  generic — and strips `shopify_error` with `replace`, keeping `tab`.
- Logging: `ShopifyOAuthException` → no extra log (as before); DB/unexpected → ERROR (mirrors
  `ApiExceptionHandler`'s messages, now from `ShopifyOAuthController`'s logger).
- **Known gaps (accepted):** (1) a logged-out user (Path-2 / pre-state failure) hits RequireAuth →
  /login and loses the param; (2) a *framed* `/auth/shopify/install` error is a blank frame —
  nginx sends `X-Frame-Options: DENY` on `/auth/shopify/*`. Follow-up: frame-ancestors CSP there.
- Pre-existing, unrelated: `ShopifyMagicLinkTest.provisionWiring_path2NewInstall_…` fails on
  main too (expects Path-2 provisioning, dead since Option A 2026-09-04).
- `PROVISIONED` branch still points at dead `/connect/setup-pending` — unreachable, left as is.

**Returns portal Step 4b — customer requests, merchant approve/reject, portal settings — built 2026-09-24 on branch `feature/portal-4b-requests` (not merged).**
Backend only; no frontend, no Bosta calls, no emails, no piece moves (InventoryLedger untouched).
- **V102:** `return_requests.reference` NOT NULL (`RR-` + 6 chars from `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`;
  CHECK accepts 4+; UNIQUE(tenant_id, reference); generated in `PortalService` with a pre-checked
  retry loop), `customer_note` (CHECK ≤ 300).
- **Public submit** `POST /api/v1/portal/{slug}/requests`: Bearer lookup token verified (signature,
  expiry, tenant == slug's tenant) else 401 generic. Eligibility re-checked from scratch (shared
  `deliveredWithinWindow()`), specific free delivered pieces bound `ORDER BY created_at, id`, request +
  items in one tx. Race on `return_request_items_one_active_per_piece` → 409 (only that index name;
  other unique violations rethrow). Invalid line / out of window → 400 generic. `portal_auto_approve`
  → `approved`, `decided_by` NULL. Response `{reference, status}` only; email/note/phone never logged.
  The controller parses the raw body itself (malformed JSON → 400, not the catch-all's 500).
- **Merchant** (`ReturnsPortalAdminController`, owner/manager): `GET /return-requests` (`{items, total}`,
  created_at DESC, id DESC), `GET /return-requests/{id}`, `POST …/approve`, `POST …/reject {reason}`
  — only from `requested` (else 409; unknown id 404). Reject deactivates the items, so those pieces
  are returnable again.
- **Settings:** `GET/PUT /tenant/portal-settings`, `PUT /variants/{id}/non-returnable`. Slug lowercased,
  format + reserved list → 400; enabled needs a slug; window 1–90. **Taken slug under RLS:** the
  global UNIQUE on `tenants.portal_slug` is enforced across all rows regardless of RLS, so the single
  UPDATE of our own row raises `tenants_portal_slug_unique` → 409 (constraint name matched; nothing
  else maps). One UPDATE statement → all-or-nothing. **No new SECURITY DEFINER; hatch count stays 14.**
- No audit-log entry for settings changes yet.

**Returns Step 5 — parcel cards, untracked courier returns, "Return To Receive" — built 2026-09-23 on branch `feature/returns-parcel-cards` (not merged).**
Mockups: `design/returns-parcel-states/1–7`. No pieces created or moved, no Shopify writes.
- **V101:** `shipments.return_intake_outcome` (`scanned` | `received_untracked`), `return_intake_by`
  (NULL = system), `return_intake_session_id` (no FK — audit pointer; FK-safe cleanup orders delete
  sessions before shipments). Stamped rows backfilled to `scanned`. `close()` (and so auto-close)
  now records outcome `scanned`, the closing user (NULL for the job) and the session.
- **"Never tracked"** = no allocation of ANY status on the order — `ShipmentLinkService.orderUntrackedSql()`
  / `isOrderUntracked()`; the only definition, used by mark-received and the parcel view.
- **Mark received / undo:** `POST /returns/sessions/{id}/parcels/{shipmentId}/mark-received` and
  `…/undo-mark-received` (isAuthenticated — workers included). Guards: open session, return leg of
  this tenant (else 404), AWB scanned in THIS session, intake not complete, order untracked — each
  a specific 409. Undo only in the same open session for an intake that session recorded.
- **Session detail:** additive `parcels[]` (per scanned AWB: leg, order, customer first name + initial,
  returnedAt, Bosta note, tracked, intake outcome/marked-by, expectedPieces, scannedItems, counts,
  complete), `otherItems[]`, `lastScan`. An item's order comes from its `return_received` event in this
  session (restock clears `current_order_id`).
- **`return_to_receive`** (MEDIUM): open while outcome `received_untracked` and no resolution at/after
  the marking (undo + re-mark re-opens it); shared open-predicate with `listCrpReturns`'
  `awaiting_receiving`. `inspection_state = received_untracked` (counted under Received in E&R).
- **UI:** parcel cards per mockups 1–4/6 using the EXISTING row renderers and disposition/reprint
  controls (mismatch kept — see below); feedback strip from `lastScan`; empty state only with no items
  and no parcels; footer summary; plurals `_one`/`_other`; `<bdi>` + Unicode isolates for AWBs,
  codes, names, order numbers. The SAFETY-CRITICAL scan handler and refocus handlers are untouched.
- **Mockup vs behaviour (behaviour kept):** "Not the real piece" (mismatch) stays in the item controls;
  the existing close-blocked callout (lists blocking pieces) stays alongside the footer line;
  restock location ("Shelf A-04") isn't shown — the session restock sends no location; Exceptions keeps
  the generic "Go →" (to Receiving) rather than a "Receiving →" label; E&R phone masking not changed.
- Resolve flow already supports a note (optional textarea → `exception_resolutions.note`).

**Returns portal Step 4a (foundation) — built 2026-09-23 on branch `feature/portal-4a-foundation` (not merged).**
Backend + nginx only; no frontend, no Bosta calls, no emails, no piece moves.
- **V100:** `tenants.portal_slug` (UNIQUE, `^[a-z0-9-]{3,40}$`), `portal_enabled`, `portal_auto_approve`
  (no slug backfill); `variants.non_returnable`; `shipments.delivered_at` (forward legs backfilled
  from the earliest `delivered` history row, else the earliest delivered piece event at/after
  `created_at`); `orders (tenant_id, number)` index; `return_request_status` enum;
  `return_requests`, `return_request_items` (one active item per piece, partial unique),
  `portal_lookup_attempts` (no IP, no phone) — all three RLS in-migration and in
  `MigrationSmokeTest.TENANT_SCOPED_TABLES`; hatch #14 `resolve_tenant_by_portal_slug` (BUILT —
  CLAUDE.md + blueprint §16.1 now say 14).
- **Ingest:** `applyMappedState` sets `delivered_at` once when a FORWARD leg maps to `delivered`
  (never overwritten; return legs and type-30 exchange paths excluded).
  Known gap: `manualLink()` (parked) never calls `applyMappedState`, so a manually linked
  delivered shipment has no `delivered_at` and its order won't pass portal lookup.
- **Public API** (`com.traceability.portal`, permitAll `/api/v1/portal/**`): `GET {slug}/config`,
  `POST {slug}/lookup`. Slug → hatch #14 → `TenantContext.runAs` + programmatic tx on app_user.
  Every lookup failure is the same 404 body; throttle 5 failures/60 min per tenant + order key
  → 429. Only real attempts (success or genuine failure) are recorded — a throttled 429 is NOT,
  so the lockout lifts 60 min after the 5th real failure however often the caller retries. Success returns an HMAC token (30 min) + variant-grouped lines,
  no customer PII. `PortalTokenService.verify()` is the 4b hook.
- **Secret:** `PORTAL_TOKEN_SECRET` (≥32 bytes) is REQUIRED — the app refuses to start without it.
  **Add it to the server `.env` before deploying** (deploy compose reads `../.env`).
- **nginx:** `limit_req_zone portal` 10r/m per client IP, `location ^~ /api/v1/portal/` burst 5.

**Fulfill `shipment_has_courier` fix — built 2026-09-23 on branch `fix/fulfill-has-courier-predicate`, pushed to main 2026-09-23 (not deployed).**
Critical prod bug: Fulfill showed "Waybill printed outside Traced — no courier account
connected" for every pilot order (e.g. tenant e785e5e4, order #2212129474), hiding Print
Waybill and letting Complete through without a print on BOTH pilots since d3aabb8.

- **Root cause:** `FulfillService.getOrder()` derived the flag from
  `shipments.courier_account_id IS NOT NULL`; no ingest path ever writes that column (prod:
  Jumi 200/200, Snouts 105/105 forward shipments NULL), so it was always false. Hidden because
  no backend test covered the predicate — `fulfill.test.tsx` hand-sets the computed flag.
- **Fix (backend only):** `shipment_has_courier = s.id IS NOT NULL AND EXISTS(tenant's
  courier_accounts row, provider='bosta', status='active')` — exactly
  `BostaAwbService.printAwb()`'s account resolution, so flag == "print can succeed". Field name,
  `Fulfill.tsx`, ingest paths and the column untouched; no backfill.
- **Tests:** new `FulfillShipmentHasCourierTest` (6, app_user/RLS path, every shipment has
  `courier_account_id` NULL): active → true (and print succeeds); none → false; disconnected /
  error → false AND `printAwb()` throws `NoBostaAccountException`; self-pickup / return-leg-only →
  false. Revert-to-confirm: (a) RED on the old predicate ("expected: true but was: false");
  the other five pass on both. `fulfill.test.tsx` `makeOrderDetail()` comment only.
- **Impact query** (orders packed after the gate deploy with no Traced print) is in the Step 0
  report; there is no print-success record, so the set is every non-self-pickup order packed
  post-deploy on a tenant with an active Bosta account.
- **Backlog (recorded, NOT fixed):**
  1. Fulfill's `fulfill.printAwb.noCourierAccount` copy ("no courier account connected") is
     inaccurate when the account exists but is `disconnected` / `error`.
  2. `shipments.courier_account_id` is dead — never written by any ingest path, and now never
     read. Decide later: drop the column, or populate it at ingest.
  3. Successful AWB prints are never recorded (only failures, via
     `awb_print_failed_reason/_at`) — "was this order printed via Traced?" is unanswerable.
- Gotcha: two sessions shared one checkout; switching its branch made the other session's
  commits land on the wrong branch. Use a separate `git worktree` per concurrent session.

**CRP address correction (V99) — built 2026-09-23 on branch `fix/crp-address-backfill` (not merged).**
Data-only migration correcting `orders.address` rows polluted by a CRP's merchant
`dropOffAddress` before the Step 2 fix (production: 2 Jumi orders, #385327169470 via CRP
6136538746 with no forward leg, #385328209470 via CRP 9730639058 with a forward leg).
- Targets exactly the Step 2 detection predicate + `pii_source='bosta'` + `pii_redacted_at IS NULL`.
- New address: the forward leg's `dropOffAddress` if a forward leg with raw exists, else the
  CRP's `pickupAddress`; NULL if neither has any of the four fields.
- Shape = what `populateConsigneePiiFromRaw()` writes today (`jsonb_strip_nulls`, untrimmed,
  "" kept). Address only; idempotent (proven by re-executing the V99 SQL → 0 rows).
- `MigrationSmokeTest` 97→98, `NotTracedBackfillTest` 42→43; `ReturnIntakeBackfillTest` and the
  new `CrpAddressBackfillTest` pin their second migrate with `.target(...)` so later migrations
  can't stale their "only this migration" count (the trap `ExchangeBackfillTest` fell into).

**Follow-up — two `orders.address` JSON shapes exist in production (do not normalize without a decision):**
V45 and `BostaController`'s PII backfill write all four keys (`firstLine/city/zone/district`),
JSON null for missing, values TRIMmed, only when firstLine or city is present.
`populateConsigneePiiFromRaw()` (and V99) write only present keys, untrimmed, "" kept, when any of
the four is present. Harmless only while no reader distinguishes missing key from null — checked
2026-09-23: `OrderController.detail()` (generic Object passthrough), frontend `OrderDetail.tsx`
(`Object.values(...).filter(Boolean)`), `FulfillService.getOrder()` (passthrough, unused by
Fulfill.tsx), `SentryConfig` (key-name scrubbing). No `?`/`@>`/whole-object equality anywhere.
Any new reader must treat missing and null the same, or the shapes must be normalized first.

**Returns page: courier returns awaiting scan — Step 2 COMPLETE on branch `feature/returns-awaiting-scan` (2026-09-23, not merged).**
Built A–G. Backend 1385 run / only the 3 known failures; frontend 334/334, tsc + vite build clean.

- **A:** `ShipmentLinkService.RETURN_LEG_AWAITING_SCAN_SQL` is THE definition of "return leg
  awaiting scan" (return leg, `returned`, intake NULL, no scan evidence). `detectReturnLegUnscanned`
  reuses it plus the age window; `RETURN_LEG_ENTERED_RETURNED_AT_SQL` shared too. Detector tests
  unchanged and green.
- **B:** `GET /api/v1/returns/awaiting-scan` — `isAuthenticated()` (owner, manager, worker; same
  gate as opening a session). Count + ≤50 legs (returned DESC, id DESC) with
  `returnSpecs.packageDetails` itemsCount/description/descriptionAr. No customer PII. In
  `RlsCoverageTest` COVERED.
- **D:** CRP AWB scan response carries itemsCount/description/descriptionAr; session detail
  carries `courierReturns` and the UI renders "Bosta says: N items — description" from the
  detail, because the SAFETY-CRITICAL `handleScan` discards the scan response and reloads.
- **E:** `pages/returns/sessionStart.ts` — date + time when the session didn't open today;
  new `returns.openSession.startedOn` key (AR `startedAt` reads "at hour", unusable with a date).
- **G:** `populateConsigneePiiFromRaw` takes a CRP's (type 25) address from `pickupAddress`
  (customer), not `dropOffAddress` (merchant). Receiver = customer on a CRP (merchant is `sender`),
  so name/phone unchanged. Revert-to-confirm proven.
- **C:** `LandingCallout` (one component for both banners); awaiting-scan callout for owner,
  manager AND worker (reduced landing included), hidden at 0, also shown above the empty state.
  Approved test change: `returns.test.tsx` answers `/returns/awaiting-scan` by URL (count 0) in
  `beforeEach` — the sequential queues stay in step, no assertion changes.
- **F:** `ReturnSessionAutoCloseJob` (hourly) closes open sessions idle > 12h (latest of
  opened_at, piece scan, AWB scan, disposition) with ZERO pending items, via the normal `close()`
  (intake stamp runs; preconditions never bypassed). Actor = system (`closed_by` NULL). Decision
  (2026-09-23) after the gate: `close()` rejects pending dispositions, so sessions with pending
  items are never auto-closed.
- **RS.7 (`manualLink()` creating a forward leg for a CRP) PARKED** — production count is 0, and
  the V43 `ux_active_shipment_per_order_leg` index already 409s it for orders with a forward leg.
- Gotcha: `vite build` (not just `mvn test`) also writes into `src/main/resources/static/` —
  restore it before committing.

**Returns: scan-as-truth for return legs — built 2026-09-23 on branch `feature/returns-scan-as-truth` (not merged, not deployed).**
Step 1 of the Returns Portal work (Step 0 / 0b were diagnosis-only). Local/Testcontainers only;
production was not touched.

- **Root cause fixed:** `BostaWebhookJob.applyMappedState()`'s piece step is order-scoped (every
  `active`/`packed` allocation on the order), so a CRP (type 25) return leg reaching state 46
  moved **every** delivered piece to `return_pending_inspection`, including items the customer
  kept. Now skipped when the resolved shipment's `shipment_leg='return'` — shipment row, status
  history, PII and not-traced tagging still run. All three callers inherit it; `:277` (exchange
  post-pack) and `:688` (admin re-interpret) only ever resolve `shipment_leg='forward'` rows, and
  only `tryMatchDelivery()` (type 25) + the V43 backfill create return legs.
- **V98:** `shipments.return_intake_completed_at`, `tenants.return_unscanned_window_days` (default
  3), evidence-based backfill (terminal return legs with a `return_received` event at/after the
  leg's `created_at` → earliest such `occurred_at`; everything else NULL — a pre-V98 state-46
  `courier_update` is NOT scan evidence).
- **Intake completion:** `ReturnSessionService.close()` (never abandon) stamps every return leg of
  an order that had a legal scan in the session — orders read from the session's
  `return_received` events (`metadata->>'session_id'`), because `restock()` clears
  `pieces.current_order_id` before close.
- **Acceptance:** new `ShipmentLinkService.hasReturnLegAwaitingIntake()` (non-terminal, or
  `returned` + intake NULL) OR'd into `scanPiece()`'s DELIVERED case. `hasActiveReturnLeg()` and
  its four callers untouched.
- **`return_kind` labels now mean what they say (window-independent), acceptance unchanged:**
  `exchange_match` (matched exchange on the order — new `hasMatchedExchange()`) > `crp_return`
  (new) > `customer_after_delivery`. The legacy adopt path (piece already at
  `return_pending_inspection`) still writes no `return_kind`. No production reader of
  `return_kind` exists (LookupService passes metadata through opaquely; frontend types it
  `unknown`).
- **`listCrpReturns` `inspection_state`:** `returned` + intake NULL → `needs_inspection` (zero
  pending pieces before the scan means "not scanned", not "resolved").
- **New HIGH exception `return_leg_unscanned`:** returned, intake NULL, entered `returned` >
  `return_unscanned_window_days` ago (`shipments.returned_at`, fallback earliest `returned`
  `shipment_status_history` row), and **no scan evidence** (silent while a scan sits in an
  open session). `ExceptionEmailFormatter` is type-agnostic (renders `enrich()`'s EN/AR text) and
  the immediate-alert job picks up every HIGH detector automatically — no per-type entry needed.
- Open return sessions never auto-close or expire — only manual `close()`/`abandon()`.
- Tests: new `ReturnLegScanAsTruthTest` (9) + `ReturnIntakeBackfillTest` (1). T1/T2 proven RED with
  the B1 skip reverted (pieces went to `return_pending_inspection`), GREEN restored.
  `RefundListTest` r7/r8 fixtures updated (assertions unchanged): r7 seeds
  `return_intake_completed_at`; r8 now goes open → scan → restock disposition → close. Backend
  1373 run, only the 3 known failures; frontend 316/316, tsc + vite build clean.
- CLAUDE.md / blueprint §16.1: hatch inventory corrected (13 built incl. `upgrade_custom_app_to_oauth`),
  #14 `resolve_tenant_by_portal_slug` recorded APPROVED-NOT-BUILT, MODE B AMENDMENT #2 (type-25
  creation, approved-not-built), return-leg invariant.

**Follow-ups (not fixed this step):**
- `ShipmentLinkService.manualLink()` always calls `createOrFindShipment()` — manually linking an
  unlinked CRP creates a **forward** leg, which then still moves pieces under applyMappedState.
- Known pre-V98 stranded piece: one production piece moved to `return_pending_inspection` by a CRP
  state-46 `courier_update`; identify with the post-deploy SQL in the Step 1 report — do not auto-fix.
- After deploy, existing return legs left NULL by the backfill will raise `return_leg_unscanned`.

Next up: merge/deploy decision for this branch; then Returns Portal Slice 1 proper (hatch #14,
CRP creation under MODE B AMENDMENT #2).

**FR-13.x Void / On Hold + FR-14 Lookup restyle + order-number lookup shipped (2026-08-23).**
Two-phase build (Step 0 diagnosis → Phase 1 backend gate → Phase 2 frontend), both gates
reviewed and approved by Marawan before proceeding.

- **Void** (`PieceAdjustService.voidPiece()`) — receiving-overcount/duplicate-entry
  correction, terminal (`available:voided` only, no reverse edge), explicitly NOT a loss.
  Excluded from all three loss-reporting sites (`OverviewService.exceptionsRaw`,
  `ExceptionService.detectLost`, `InventoryStockController.breakdown()`) by construction —
  they all filter on literal `status='lost'`/`'damaged'`, never a negated set, so a genuinely
  new enum value is invisible to them without any code change. Proven, not assumed: RTC
  reverted `voided`→`lost` in the test's own seed helper and watched all three assertions
  flip to failing before restoring.
- **On Hold** (`PieceAdjustService.hold()`/`unhold()`) — reversible QC/quarantine.
  `available:on_hold` (−1 Shopify decrement) / `on_hold:available` (+1 via the EXISTING
  increment path, not a new gateway method) / escalation `on_hold:{damaged,lost,destroyed}`
  with **zero** Shopify call (the piece already left the sellable pool at hold-enter — a
  second decrement here would be a real double-count bug). `hold()` generates a
  `holdEventId` per cycle and threads it through both the `held` event's metadata and the
  Shopify claim's `trigger_id` (`pieceId:holdEventId`) — required because a piece can be
  held/released/held again, and `piece_id` alone would collide with a prior cycle's
  already-`applied` claim row.
- **FR-21 §7 invariant amended (approved 2026-08-23), not violated** — "sole sanctioned
  decrement" became a **named, closed set of three** single-attempt gateway methods:
  `pushStockTakeWriteOff` (unchanged), `pushVoidCorrection`, `pushHoldEnter`. Each is its
  own method, own call site, no shared decrement helper. A `NamedDecrementSetGuardTest`
  source-text scan guards the set from quietly growing a fourth caller — proven with a real
  RTC (temporarily added a second textual caller, watched the test fail, removed it).
- **Failed void/hold decrements are no longer silent** — new CRITICAL exception detector
  `ExceptionService.detectVoidHoldSyncFailed()` (scoped to `status='failed'` only; the
  `'skipped'` outcome for a void whose receiving increment never applied is explicitly NOT
  an exception — that's the correct, do-nothing outcome). Resolution reuses the existing
  generic `resolve()` flow. Manual (not auto) repush:
  `ShopifyInventoryService.repushFailedVoidOrHold()` reuses `claim()`'s existing
  `WHERE status='failed'` reclaim branch — no new claim mechanism. Full `failed_ambiguous`
  auto-repush parity with stock-take is deliberately deferred.
- **Order-number lookup** — `#1042`/bare digits/full Shopify order name. `#`-prefixed is
  unambiguous (no tracking format uses `#`); bare digits try `lookupTracking()` FIRST
  (zero regression on existing AWB lookups, since a Bosta AWB can also be all-digits) and
  fall back to `lookupOrder()` only on 404. `LookupService.lookupOrder()` deliberately
  resolves IDENTITY ONLY (order id + number) — it is NOT a second order-detail data path.
  The frontend takes the resolved id and opens the SAME `OrderDrawer` (`getOrder()`/
  `getOrderTimeline()` → `GET /orders/{id}` → `OrderController.detail()`) already used from
  the Orders list — confirmed and consolidated after first shipping a parallel
  Lookup-specific fetch, per Marawan's review.
- **Lookup restyle** to `design/Traced Lookup Flow.dc.html` — search prompt/not-found/AR
  states, piece-view meta grid (+ new Condition field, backed by `pieces.condition`, V81),
  pulsing-dot custody timeline, tracking-view piece chips. Appearance-only except where
  explicitly flagged: `pieces.condition` is new data (not a restyle), and the `lost`-piece
  Adjust button (dead — lost's only legal edge is `lost:available`, already covered by
  Found It) was first preserved as a behavior-freeze default, then removed in a dedicated
  follow-up commit once Marawan confirmed it — never silently dropped mid-restyle.
- **AdjustPanel extended** with Void/On Hold pills, **contextual to piece status** — from
  `available`: all five `ALLOWED` targets (Lost/Damaged/Destroyed/Void/On Hold); from
  `on_hold`: only the three escalation targets (Void has no `on_hold:voided` edge, on_hold
  has no self-edge). Reason dropdown swaps per pill (`ADJUST_REASONS`/`VOID_REASONS`/
  `HOLD_REASONS`). "Release from Hold" added alongside "Found It" as a peer quick-action.
- 3 migrations (V80–V82): `piece_status` += `voided`/`on_hold`; `pieces.condition`;
  `shopify_inventory_adjustments` trigger_type/status CHECK widen. Both migration-count
  gates bumped in the same commits (`MigrationSmokeTest` 78→81, `NotTracedBackfillTest`
  23→26).
- New test files: `VoidHoldTest` (14 cases incl. vh9b/vh9c — explicit per-target
  double-decrement guards for on_hold→lost and on_hold→destroyed, not just damaged, added
  after Marawan flagged that the property rested on a single `if` in `adjustPiece()`),
  `VoidHoldSyncExceptionTest` (5 cases), `NamedDecrementSetGuardTest` (1). Every load-bearing
  claim in this entry has a real revert→fail→restore RTC behind it, not just a green run.

Next up: Deploy-prep / VPS provisioning (still the top item below), or any FR-13.x/FR-14
follow-ups Marawan flags after this ships.

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

- **Store switch for a real merchant = manual ops script, on request. There's no in-app path, by design** (Marawan,
  2026-10-03). A real tenant stays bound to its shop_domain even after disconnecting (`ShopifySameShopGuard`); only a
  simulated-courier (review mode) tenant ignores its disconnected rows. No script exists yet — written when first needed.
- **Review mode uses its own flag (`tenant_courier_simulation`, V130), never `is_demo`** (2026-10-03) — DemoSeeder
  asserts exactly one is_demo tenant. Reset (S6) and any future purge must include the table.

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

- **`mvn test` rebuilds the frontend bundle into `src/main/resources/static/`** — every backend test run leaves `static/index.html` modified and new hashed `assets/main-*.js/.css` files (old ones deleted). This is how the working tree got its uncommitted bundle before 2026-09-23. After a test run, `git checkout -- src/main/resources/static/` and delete the untracked `main-*` files unless the bundle is being deliberately committed.
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
- **`api.ts`'s shared `request()` never surfaces JSON error bodies** — `if (!res.ok) throw new Error(\`${res.status}: ${res.statusText}\`)` never calls `res.json()` on the error path. This makes `Lookup.tsx`'s `AdjustPanel` `PieceCommittedException` (409) parsing dead code against a real network call — it only "works" in `adjust.test.tsx` because that test mocks `adjustPiece()` directly rather than going through `request()`. Found again in FR-21 Step 6.3: `resolveStockTake()` needs the 409 body to render the release card, so it does its own `fetch` + body parsing instead of routing through `request()`. Not fixed globally (bigger blast radius than the task warranted) — flag it before reusing `request()` for anything that needs to inspect a non-2xx JSON body, and prefer the scoped-fetch pattern `resolveStockTake()` uses until `request()` itself is fixed. **Blast radius grew 2026-08-23**: `AdjustPanel`'s Void/On Hold submit paths reuse the exact same `err instanceof Response` catch pattern, so the same dead-code gap now also applies to void/hold's `PIECE_COMMITTED` 409 body — not newly introduced, just newly present on two more code paths that share the pre-existing bug.

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
