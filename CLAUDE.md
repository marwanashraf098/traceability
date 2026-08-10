# Smart Choices — Odoo 17 Implementation

## Project Overview

**Company**: Smart Choices (Egypt)
**Platform**: Noon marketplace (Egypt)
**System**: Custom Odoo 17 Community module (`ecom_mgmt`)
**Server**: Oracle Cloud VM — `http://193.122.88.214:8069`
**SSH**: `ssh -i ~/.ssh/smart-choices-odoo.key ubuntu@193.122.88.214`
**Local**: `~/Documents/smart-choices-odoo/` (Docker on Mac M3)

---

## Business Model

4 ownership types for products:

| Owner | Commission | Notes |
|---|---|---|
| Smart Choices | N/A | Own inventory, COGS tracked |
| Ashy | SC keeps 15%, Ashy gets **85%** | Brother |
| Vantage | SC keeps 30%, Vantage gets **70%** | Partner |
| Afyouni | Cost price per unit | No commission, running balance |

**Fulfillment types**: FBN (Noon warehouse) and FBP (own warehouse)

---

## Infrastructure

```
Mac (local dev)          Oracle Cloud (production)
Docker Compose           Ubuntu 20.04, Docker
smart-choices DB         smart-choices DB
localhost:8069           193.122.88.214:8069
```

**Deploy changes:**
```bash
scp -i ~/.ssh/smart-choices-odoo.key \
  addons/ecom_mgmt/wizards/settlement_wizard.py \
  ubuntu@193.122.88.214:~/smart-choices-odoo/addons/ecom_mgmt/wizards/

ssh -i ~/.ssh/smart-choices-odoo.key ubuntu@193.122.88.214
cd ~/smart-choices-odoo && sudo docker-compose restart odoo
```

---

## Module Structure

```
addons/ecom_mgmt/
├── __manifest__.py
├── models/
│   ├── product_extension.py    # ownership_type, noon_sku, partner_cost_price
│   ├── noon_transaction.py     # NoonTransaction, NoonUnmatchedFee
│   └── settlement.py           # NoonSettlement, AfyouniSettlement, VendorExpense
├── wizards/
│   ├── noon_importer.py        # CSV import + classification
│   └── settlement_wizard.py    # Commission calculation engine
├── views/
│   ├── product_views.xml
│   ├── noon_transaction_views.xml
│   └── settlement_views.xml
└── security/ir.model.access.csv
```

---

## Settlement Wizard Logic

**File**: `wizards/settlement_wizard.py` → `_compute_commission_data()`

The wizard builds commission basis per order using these steps:

1. **Sales/Returns** — filtered by ownership type and month
2. **FBP fees** — `fbp_shipping_fee` + `shipping_credit_only`, applied **ONCE per order** (not per item)
3. **Late fees** — `fbn_late_fee` + `late_fee_adjustment` + `credit` for same-month orders
4. **Unmatched FBP fees** — manually resolved fees with `owner_override_type` set
5. **Order adjustments** — by order_nr OR by product ownership (for SKU-based adjustments)
6. **FBN late fees with SKU** — use product ownership directly
7. **Misc cross-month fees** — `late_fee_adjustment` + `credit` for orders whose sale is in a different month (lookup by order_nr across all dates)

**Commission basis formula:**
```
basis = net_proceeds + referral_fee + fbn_fulfillment_fee + shipping_credit + fbp_fee (once) + late_fees
```

**Rates:**
- Ashy: 85% of basis (SC keeps 15%)
- Vantage: 70% of basis (SC keeps 30%)

---

## Transaction Classifications

| Classification | Description |
|---|---|
| `sale` | Normal sale with SKU, net > 0 |
| `return` | order_update with net < 0 |
| `cancellation` | order with SKU, net < 0 |
| `fbp_shipping_fee` | order without SKU, fulfillment fee < 0 |
| `shipping_credit_only` | order without SKU, shipping_credit > 0, fee = 0 |
| `fbn_late_fee` | order with SKU, net = 0, fee < 0 |
| `order_adjustment` | order with SKU, net = 0, fee = 0, total < 0 |
| `late_fee_adjustment` | order_update with net = 0, fee < 0 |
| `credit` | order_update with total > 0 (Noon Credit) |
| `referral_fee_correction` | order_update with referral < 0 |

---

## Known Issues & Rules

### Duplicate Detection
- **BROKEN**: Item Nr is always empty in Noon CSVs
- Current workaround: manual check before importing
- TODO: Fix using composite key (Order Nr + SKU + Type + Total + Date)

### FBP Fee Rule
- Noon charges ONE FBP shipping fee per ORDER, not per item
- Multi-item orders: apply FBP fee to FIRST item only
- Code: `order_txs = defaultdict(list)` grouping in wizard

### Cross-Month Fees
- Late fees, credits, adjustments can appear in a different month than the original sale
- Handled by `misc_fees` block — searches by order_nr across ALL dates
- Transaction date determines which settlement month it belongs to

### Unmatched Fees
- FBP fees without a matching sale row → go to **Unmatched Fees** view
- Manually assign `owner_override_type` to resolve
- After resolution: included in that owner's settlement as standalone lines
- **Must resolve BEFORE running settlement wizard**

### Owner Override for Shared SKUs
- Some SKUs (e.g. Demolition Hammer) are shared between Ashy and Smart Choices
- Personal/owner orders must be manually excluded from settlement
- Example: NEGI50018963851 (Demolition Hammer) — owner's personal order, exclude from Ashy May settlement

---

## Monthly Workflow

### Weekly (after each Noon settlement batch)
1. Download Noon CSV from Seller Lab
2. **Ecom Management → Noon → Import CSV**
3. Preview → verify 0 unclassified
4. Confirm import
5. Go to **Unmatched Fees** → resolve all before next step
6. Classify **Pending Returns** (Sellable → FBN stock, Damaged → Damaged location)

### Monthly Settlement
1. Clear all pending returns
2. Resolve ALL unmatched fees
3. **Run Settlement Wizard** → select month → Calculate → verify numbers → Create
4. Review settlement lines — manually remove owner personal orders if any
5. Confirm settlement → vendor bill auto-created
6. Add any **Vendor Expenses** (packaging, shipping, advances) → link to settlement → Confirm
7. Pay vendor → Register Payment on vendor bill

---

## Validated Settlements

| Month | Basis (EGP) | Ashy Gets (85%) | Status |
|---|---|---|---|
| March 2026 | 26,640.90 | 22,644.77 | ✅ Confirmed |
| April 2026 | ~60,245 | ~51,208 | Needs recreation with fixed wizard |
| May 2026 | 61,316.79 | 52,119.27 | Needs recreation (exclude NEGI50018963851) |

---

## Key Database Queries

**Check Ashy basis for a month:**
```sql
SELECT 
    SUM(nt.net_proceeds + nt.referral_fee + nt.fulfillment_fee + nt.shipping_credit) as sale_basis
FROM noon_transaction nt
JOIN product_product pp ON pp.id = nt.product_id
JOIN product_template pt ON pt.id = pp.product_tmpl_id
WHERE nt.classification IN ('sale', 'return')
AND nt.transaction_date >= '2026-05-01'
AND nt.transaction_date <= '2026-05-31'
AND pt.ownership_type = 'ashy';
```

**Check unmatched fees:**
```sql
SELECT order_nr, fulfillment_fee, shipping_credit, owner_override_type, is_unmatched
FROM noon_transaction
WHERE is_unmatched = true
AND transaction_date >= '2026-05-01';
```

**Check FBP fees for Ashy orders:**
```sql
SELECT COUNT(*), SUM(fulfillment_fee + shipping_credit)
FROM noon_transaction
WHERE classification = 'fbp_shipping_fee'
AND transaction_date >= '2026-05-01'
AND transaction_date <= '2026-05-31'
AND order_nr IN (
    SELECT DISTINCT nt.order_nr FROM noon_transaction nt
    JOIN product_product pp ON pp.id = nt.product_id
    JOIN product_template pt ON pt.id = pp.product_tmpl_id
    WHERE nt.classification IN ('sale','return')
    AND pt.ownership_type = 'ashy'
);
```

---

## Pending Items

1. **Fix May settlement** — delete and recreate, exclude NEGI50018963851
2. **Fix April settlement** — delete and recreate with updated wizard
3. **Fix duplicate detection** — use composite key instead of item_nr
4. **Vendor expense → bill deduction** — fix `_compute_tax_totals()` call
5. **Enter WH/SC warehouse stock** quantities
6. **Zoho Books** setup (optional, for external P&L reporting)
7. **DuckDNS** free domain setup (optional)

---

## SKU Profit Report (Jan–May 2026)

| Metric | Amount (EGP) |
|---|---|
| Total Revenue | 283,678 |
| Total Noon Fees | -46,844 |
| Net After Fees | 236,834 |
| Owner Share (paid out) | 168,994 |
| **Your Net Profit** | **67,839** |

---

## Afyouni Running Balance

- Total owed (Jan–Apr 2026): **47,140 EGP**
- Tracked in **Afyouni Statements** (cost price per unit sold)
- No commission — pays cost price only

---

## Session ritual

At the **start** of every session: read `docs/PROGRESS.md` before anything else.

At the **end** of every session (without being asked):
1. Update `docs/PROGRESS.md` — current state, next up, any new decisions, any new gotchas.
2. Tick completed items in `docs/requirements-checklist.md`.

---

## Environment notes

**Two writers of piece_events — both in `InventoryLedger`, never anywhere else:**
- `transition()` — gateway for state changes on **existing** pieces (UPDATE race-guard + diagnostic SELECT + INSERT event; 3 round-trips per piece).
- `batchReceive()` — piece **CREATION** only: two multi-row INSERTs in one `@Transactional` boundary. `from_status=NULL→available`, one `received` event per piece, `actor_user_id` mandatory. This is NOT a violation of "everything goes through transition()" — piece creation has no prior state to race on. **Do NOT refactor `batchReceive()` to call `transition()`** — that breaks the NULL→available path and destroys the 1,000-piece ≤10s performance guarantee.

**TenantContext is a ThreadLocal**: it does NOT propagate across `@Async` methods, executor-submitted tasks, parallel streams, or `CompletableFuture` chains. Any background work that reads or writes tenant data must be wrapped in `TenantContext.runAs(tenantId, ...)` so the context is explicitly set and cleared. Forgetting this causes silent zero-row results under RLS — not an exception, just missing data.

**UUIDv4 is not time-ordered — order by created_at, never id.** `gen_random_uuid()` produces random values with no chronological relationship to insertion order. Any "pick the latest row" query that does `ORDER BY id DESC LIMIT 1` on a table whose PK is a random UUID is non-deterministic — it does NOT reliably return the most-recently-inserted row. Confirmed empirically (2026-08-05): a test inserting two `shipments` rows in a known order and asserting `id DESC` picked the second one failed intermittently until rewritten with explicit low/high UUID literals. Fix: `ORDER BY created_at DESC, id DESC` (id only as a tiebreak for same-instant rows), and confirm the timestamp column is `NOT NULL DEFAULT now()` before relying on it. **Closed (2026-08-06): no `ORDER BY id DESC` latest-row proxy remains in the codebase.** Fixed at the order-status-redesign call sites (`OrderController.list()`'s LATERAL, `OrderController.detail()`'s forward-leg query, `ExceptionService.detectCancelledWithLiveShipment()`/`detectCancelledButDelivered()`) and, in the id-DESC sweep, at `NotTracedTagger.maybeTagNotTraced()` and `FulfillService.PICKABLE_ORDERS_FILTER`, both now `ORDER BY created_at DESC, id DESC`. `V57__orders_not_traced.sql`'s one-time backfill is **not** edited in place — it already applied in every migrated environment and Flyway checksums it, so changing its SQL would break startup wherever it already ran. `V68__not_traced_backfill_recency_fix.sql` instead re-runs the same predicate with the corrected ordering, guarded by the same `not_traced_at IS NULL` one-way check `NotTracedTagger` itself uses (adds a missing tag, never un-tags). If any future site genuinely needs `id`-only ordering for a non-recency reason, it must say so in a comment explaining why `created_at` doesn't apply.

**Docker Desktop Mac M3 + Testcontainers**: `DockerDesktopMacStrategy` in `src/test/java/com/traceability/` forces docker-java API v1.41 — do not delete it; Docker Desktop rejects the default v1.24 negotiation with HTTP 400 and the fix requires overriding `test()`, `getClient()`, AND `getDockerClient()` on the strategy class.

**`frontend/package-lock.json` MUST be committed whenever `frontend/package.json` changes — omitting it breaks every `npm ci` Docker build.** The Dockerfile runs `npm ci --prefix frontend` (strict, reproducible). npm 10 in `node:22-alpine` is stricter than npm 11+ on the dev machine about optional peer-dep resolution: it requires nested esbuild binaries for `vitest/node_modules/vite` to appear in the lock file even though `esbuild` is marked `optional` in vite's peerDependenciesMeta. npm 11 locally dedupes them away; npm 10 in Docker flags "Missing: esbuild@0.28.1 from lock file" and aborts. Fix: after any `package.json` edit, regenerate the lock file INSIDE the build image so platform-binary optionality matches:
```
docker run --rm --platform linux/amd64 -v "$PWD/frontend":/app -w /app node:22-alpine npm install
docker run --rm --platform linux/amd64 -v "$PWD/frontend":/app -w /app node:22-alpine npm ci
```
Then commit the updated `frontend/package-lock.json` in the same commit as `package.json`. Never regenerate with the local npm (11+ on macOS) — it marks esbuild cross-platform binaries as required instead of optional, causing `EBADPLATFORM` in the linux/x64 Docker build.

**PostgreSQL resets SET LOCAL GUC to `''` (empty string, not NULL) after ROLLBACK**: `''::uuid` is a cast error. Every RLS policy — on every table, past and future — must use `NULLIF(current_setting('app.current_tenant', true), '')::uuid` not a bare cast. Never simplify this pattern.

**Spring Security 6 applies the security filter chain to ERROR dispatcher-type requests**: When `ResponseStatusException` is thrown, `ResponseStatusExceptionResolver` calls `response.sendError()`, which triggers a Servlet error dispatch to `/error`. `JwtAuthenticationFilter` extends `OncePerRequestFilter` and does not re-run on the error dispatch, leaving an empty security context. `.anyRequest().authenticated()` then overrides the original status (e.g. 423 → 401). Fix: `ApiExceptionHandler` (`@RestControllerAdvice`) intercepts `ResponseStatusException` before `sendError()` is called and returns a `ResponseEntity` directly. **Do not remove `ApiExceptionHandler`; do not let `ResponseStatusException` reach `ResponseStatusExceptionResolver`.**

**Five approved SECURITY DEFINER escape hatches — adding a sixth requires explicit approval**: `auth_lookup_user` (V1), `resolve_tenant_by_shop_domain` (V1), `lookup_refresh_token` (V3), `resolve_tenant_by_webhook_secret` (V5, approved 2026-06-14), `provision_tenant_from_shopify` (V14, approved 2026-06-19). These are the only points where RLS is bypassed. The fifth hatch is justified because Path-2 Shopify-first install must atomically create tenant+owner+store — impossible under app_user+RLS because the new tenant's UUID is unknown before its INSERT (chicken-and-egg). Any future cross-tenant read must go through a named, code-reviewed `SECURITY DEFINER` function; no bare `BYPASSRLS` connections in application code. Full enumeration in `docs/blueprint.md §16.1`.

**Test datasources — most integration tests connect as postgres (BYPASSRLS) to test logic without RLS friction. This means a green suite does NOT prove RLS is enforced.** Any test whose PURPOSE is isolation/tenant-scoping must connect as `app_user` via the `appUserTx`/`appUserLedger` harness (see `InventoryLedgerTest` test e) with no GUC set, and assert zero rows. When adding a security-sensitive table or path, add an `app_user`-role test — do not rely on the default postgres-connected tests for isolation coverage.

**Supabase datasource configuration — three tiers, one forbidden:**
- **Best**: direct host `db.<ref>.supabase.co:5432` — one TCP connection per Hikari slot, no pooler in the path. Unavailable on our current plan (IPv6-only; IPv4 add-on required).
- **Our configuration (deliberate, not a workaround)**: session-mode pooler `aws-0-eu-west-1.pooler.supabase.com:5432` — Supabase Supavisor in session mode pins one backend connection per client connection for the lifetime of the session, so `SET LOCAL app.current_tenant` survives across the transaction exactly as it would on a direct connection. This is a supported, stable configuration.
- **FORBIDDEN**: transaction-mode pooler port `6543` — Supavisor reassigns the backend connection between statements; `SET LOCAL` is reset before the first query runs, GUC is empty, RLS policies evaluate to false, and every authenticated query silently returns zero rows. No error is raised. A startup guard in `DataSourceConfig` throws `IllegalStateException` if the configured URL resolves to port 6543.

If RLS mysteriously returns empty results in a new environment, check the JDBC URL port first: 5432 = safe, 6543 = broken.

**Production Shopify connect = public OAuth app; the custom-app token endpoint is DEV-ONLY.** Both pilots are on Shopify Basic. Custom (legacy) apps cannot read customer PII on Basic-plan stores — only Advanced/Plus. Customer PII (name/phone/address) is required for address→Bosta zone mapping, blocked-customer checks, and Mode-B order↔delivery matching. A public OAuth app unlocks PII on any plan after Shopify's Protected Customer Data (PCD) review. Never assume custom-app PII access works on Basic-tier stores. Launch-gating dependencies: PCD review approval (apply early), mandatory GDPR webhooks (`customers/data_request`, `customers/redact`, `shop/redact`), and a privacy policy. App Store listing is post-pilot.

**Shopify public OAuth app design is owned by a separate design thread — do not re-derive or implement it from this build thread.** The decision to use a public OAuth app is recorded in `docs/PROGRESS.md` ("Decisions made"). The detailed flow, scopes, callback URL, and state-parameter handling are being designed externally. The current custom-app `POST /api/v1/shopify/connect` endpoint is DEV-ONLY and must not be modified to simulate OAuth without that design spec. When the spec is ready it will be handed to this thread for implementation.

**Shopify Protected Customer Data (PCD) approval is required to populate customer name/phone/address; gated separately from `read_customers` scope.** Adding `read_customers` alone is not enough — Shopify enforces a PCD gate that blocks `shippingAddress` and `customer` fields on Order regardless of scope. Dev store: request via app settings (Partner Dashboard → Apps → App setup → Protected customer data) + add `read_customers` + regenerate token. Production/public app: PCD is a Shopify review step with lead time — a launch dependency, apply early. Until approved, `ShopifyHttpGateway` omits `shippingAddress` from the orders GraphQL query; `customer_name`, `customer_phone`, `address` are null-populated. Full data is preserved in `orders.raw` (jsonb) for backfill once approved. **`financialStatus` was also removed in Shopify API 2024-04 — use `displayFinancialStatus` instead.**

**`ApiExceptionHandler` catch-all `Exception` handler intercepts `AccessDeniedException` from `@PreAuthorize`**: When a `@ExceptionHandler(Exception.class)` catch-all is present, Spring MVC's `DispatcherServlet` resolves `AccessDeniedException` through `ExceptionHandlerExceptionResolver` before `ExceptionTranslationFilter` can invoke the `AccessDeniedHandler`. This returns 500 instead of 403. Always add an explicit `@ExceptionHandler(AccessDeniedException.class) → 403` handler above the catch-all. See `ApiExceptionHandler.java`.

**NEVER force-retry a Bosta unlinked delivery by DELETEing its `unlinked_bosta_deliveries` row.** The idem key is `sha256(trackingNumber:stateCode:updatedAt)` — content-derived. Deleting the unlinked row does NOT invalidate the idem key on the corresponding `webhook_events` row. Any re-ingest at the same (tracking, state, updatedAt) computes the same key, step 4 in `BostaWebhookJob` finds the already-processed event → `markDuplicate` → silent no-op. The delivery strands again. This bit us on 9730639058 (2026-07-10). Correct force-retry paths (V44+): (1) Deploy a new migration → `MatcherVersionHolder.get()` bumps → Guard 3 passes (old version ≠ new) → one retry fires automatically. (2) Manual admin clear: `UPDATE unlinked_bosta_deliveries SET matcher_version = NULL WHERE tracking_number = '<tn>' AND tenant_id = '<tid>';` — wait for next poll cycle, Guard 3 sees NULL ≠ current → passes → one retry.

**`BostaWebhookJob` piece-transition catch blocks are intentional idempotency guards — never convert them to error/rethrow paths.** A terminal-state webhook arriving a second time (e.g. state 45 delivered twice) hits one of three correct no-op paths in the piece loop: (1) `current == target` → `ledger.transition()` is not called at all; (2) `catch (StateConflictException e)` where `e.getActual() == targetStatus` → concurrent worker applied the transition first, silently continue; (3) `catch (IllegalTransitionException e)` → piece already past the target state (e.g. stale `with_courier` after `delivered`), log warning and continue. The outer dedup check (step 4 in `process()`) catches exact payload redeliveries before any of this code is reached. None of these paths should throw or mark the webhook failed.

**MODE B AMENDMENT (approved by Marawan):** Traced never creates DELIVERIES in Bosta — the Shopify plugin owns delivery creation; Traced ingests and links. Traced MAY create and edit PICKUPS, which schedule courier collection of deliveries that already exist. A pickup is not a shipment. This does not license any other Bosta write.

**`packed:with_courier` is an approved InventoryLedger.ALLOWED bypass (FR-16, approved by Marawan).** The pickup session close is the authoritative physical handover event. If Bosta's state-20 (route-assigned) webhook has not yet arrived when the worker closes the scan session, blocking the transition would leave Traced claiming 'packed' while the package is already in the courier's van — a worse custody lie than the bypass. Parallel precedent: `awaiting_pickup:delivered` (Bosta-lag same-day delivery). Do not remove this transition without explicit approval.**

**FR-17 v2 INVENTORY INVARIANT (increment-only + damage-move — approved by Marawan 2026-07-30; supersedes the earlier FR-17 wording; do NOT relax without explicit approval):** Traced NEVER decrements Shopify on_hand and NEVER writes absolute on_hand. Only two write shapes are permitted, both targeting the Traced Main Warehouse GID only:
- `inventoryAdjustQuantities` with a **positive** delta — the increment triggers.
- `inventoryMoveQuantities` **available→damaged** — the damage trigger.

**Three triggers, no others without explicit approval:**
1. Receiving session close — `+N` per variant.
2. Return inspection approved as AVAILABLE — `+1` per piece. Pieces sent to `damaged` at inspection MUST NOT increment (never sellable in Shopify) — do nothing.
3. A currently-**sellable** piece damaged in the warehouse — move 1 unit available→damaged. on_hand is unchanged; the unit leaves the sellable pool. Guards: fires only if the piece is in Shopify's available pool at damage time (NOT `reserved`/allocated to an open order — those are Shopify committed, resolved via order lifecycle / Traced re-pick). Idempotent per piece. If Shopify available is insufficient (remainder committed), the move MUST fail cleanly and be treated as a reallocation signal — never forced.

**Initial seed** of the empty Traced location = trigger (1) semantics: one-time positive adjust from 0 to true count. If the location already shows non-zero, do NOT auto-correct downward (forbidden) — surface to Marawan for manual reconcile.

**KNOWN GAP (do not silently fix):** destroyed/lost of a currently-sellable piece overstates Shopify sellable stock. Not handled this round; closing it needs an approved narrow decrement or move-to-unavailable — separate decision. Record it, don't patch it.

**Location-target invariant:** Traced writes inventory **only** to the Traced Main Warehouse location GID. It must **never** issue an inventory mutation against any other `locationId`, for any reason — no zeroing, no reconciling, no reading-then-writing another location.

Implementation (2026-07-30, local/Testcontainers build — not yet run against production): `ShopifyInventoryService` (all three triggers), `PieceAdjustService.adjustPiece()` (trigger 3 hook, right after a successful `available→damaged` ledger transition), `ShopifyLocationProvisioningService` / `ShopifyCatalogActivationService` / `ShopifyInventoryReconcileService` (Parts A–C: provisioning, activation, initial seed). `inventorySetOnHandQuantities` does not exist anywhere in `ShopifyGateway`/`ShopifyHttpGateway` — proven by a reflection test, not a text scan.

**Claim-before-call, not check-before-call, on every one of these write paths.** A plain SELECT-then-act has a race window: two concurrent identical triggers (a retry overlapping the original, a duplicate webhook, two operators clicking the same button) can both pass the check before either writes anything, and both call Shopify. `ShopifyInventoryService.claim()`/`markResult()` use `INSERT ... ON CONFLICT (trigger_type, trigger_id, variant_id, location_id) DO UPDATE ... WHERE status='failed'` — the INSERT itself, gated by the V48 unique constraint, is the guard (a 'pending'/'applied' row already there means 0 affected rows, no Shopify call). `ShopifyLocationProvisioningService.linkShopifyLocationIfNeeded()` uses the analogous conditional UPDATE (unsynced/error → pending). `ShopifyInventoryReconcileService.apply()` instead takes a per-tenant `pg_advisory_xact_lock` held for the whole operation (a manual, one-shot, one-tenant-at-a-time action, not a hot path — unlike the other two, holding one connection for the duration is the right trade). V61 adds `UNIQUE(tenant_id) WHERE is_fulfillment=true` as an independent, complementary invariant. No bare `@idempotent` directive anywhere — a keyless one was removed 2026-07-30 as decorative; the DB claim-row is the real guarantee.

**FR-21 §7 AMENDMENT TO THE FR-17 v2 INVARIANT ABOVE (approved by Marawan, Step 5 build gate):** The automatic inventory sync (FR-17 v2) is increment-only and never decrements; `adjustInventoryQuantities` continues to reject non-positive deltas and is not modified. The **sole** sanctioned decrement is a finalized stock-take write-off, issued through a dedicated reconciliation client method whose only call site is stock-take finalize, equal to the pieces transitioned to `lost` in that session, at the tenant's `is_fulfillment` location — never `inventorySetOnHandQuantities`, never the increment-only sync path. Implementation: `ShopifyGateway.pushStockTakeWriteOff()` / `ShopifyHttpGateway` (the dedicated method — deliberately does not call or share code with `executeGraphQL()`/`adjustInventoryQuantities`, and makes exactly one HTTP attempt, no internal retry, because the decrement is NOT idempotent at the business level); `StockTakeReconciliationService.finalizeSession()` (Phase A — claim, committed before any HTTP call); `StockTakeShopifyPushJob` (Phase B — the push, with no DB transaction around the HTTP call). Retry rule: a definitive Shopify rejection (`ShopifyException`) is safe to auto-retry; an ambiguous/unconfirmed response (`ShopifyAmbiguousException` — timeout, no response) is NEVER auto-retried and is surfaced via `stock_take_shopify_syncs.status = 'failed_ambiguous'` for manual verification against Shopify (`referenceDocumentUri = traced://stock-take/{session_id}`).

**FR-18 INGEST CUTOFF — Jumi has `orders_ingest_from = NULL` permanently (do NOT backfill or "fix" this).** Jumi (mmi24e-fx, connected 2026-07-02) pre-dates FR-18. Their `orders_ingest_from` is NULL, meaning no cutoff — all Shopify orders are ingested regardless of age. Every store connected after FR-18 has a non-null cutoff set at first connect and frozen forever (never moved on reconnect). The NULL vs. non-null divergence is intentional and permanent. Do not backfill Jumi's cutoff — it would have no effect on their ~50 already-ingested orders but could silently block future webhook updates for historical orders. `loadCutoff()` explicitly returns `Optional.empty()` for NULL and callers skip the check entirely — no sentinel, no comparison.

**Worker screens — LIST views render in-shell, ACTIVE SCAN LOOPS render full-screen immersive; the `<Layout>` wrapper goes INSIDE the internal view switch, never at the route.** Established by the Fulfill shell-wrapping fix: `/fulfill` is one `App.tsx` route whose root component (`Fulfill.tsx`) switches internally between `queue` (list — must be wrapped in `<Layout>`, sidebar+topbar visible) and `pick`/`handover` (active scan loop / customer handoff — must stay full-screen, no `<Layout>`, own "Back to Queue" exit). Wrapping the whole route in `<Layout>` would leak the sidebar into the scan loop; leaving the route unwrapped (the original bug) strands the list view with no shell. The same split applies to every future worker screen built the same way (stock-take, transfers, receiving scan flows, etc.) — check which of its internal views are "look at a list" vs. "actively scanning/confirming" and wrap only the former.

**Never render two auto-focusing scan/text inputs on screen at once. If a second one is briefly unavoidable (e.g. a mandatory verify-scan dialog opened over a screen that already has its own scan input), the LATER-MOUNTED input must win the click-refocus race — never weaken the earlier one's refocus handler to fix it, especially if that handler is marked SAFETY-CRITICAL.** `PickScreen`'s own scan input has a SAFETY-CRITICAL `document.addEventListener('click', refocus)` effect that stays active for its whole mount lifetime, including while `AwbLinkDialog` (inline or modal) is open over/alongside it. The fix for the resulting "select then immediately deselect" bug was NOT to touch that marked effect — it was to add the identical refocus pattern to `AwbLinkDialog`'s own input. Click listeners on the same target fire in attachment order, and a dialog can only mount after the screen underneath it already has, so its listener always attaches later and always wins. This is the template: give the newer/foreground input the SAME refocus pattern rather than editing the older one's marked-do-not-modify logic.

**Every scan/tracking-number input strips ALL whitespace (not just `.trim()`'s leading/trailing) before it's sent for lookup or link.** A paste can carry internal spaces or newlines that edge-trimming won't catch. `AwbLinkDialog.handleLink()` normalizes via `tracking.replace(/\s+/g, '')` before calling `/fulfill/{orderId}/link`; the backend's `TrackingNumberNormalizer.normalize()` independently strips the Bosta hub-prefix (everything before the last `-`) and requires the remainder to be pure digits (`^[0-9]+$`), returning `null` → HTTP 400 otherwise. A 400 here is a real, legitimate rejection (unreadable/malformed scan) — the frontend must still surface a clear, distinct error for it, never fold it into a generic catch-all that reads like "the barcode was wrong" when the real cause was formatting.

**Any test that renders a page wrapped in the shared `Layout` component MUST use the shared `stubFetchWithShellDefaults` helper (`frontend/src/test/mockShellFetch.ts`), not a one-off local shunt.** `Layout` fires its own background `GET /me` and `GET /exceptions/count` on mount (real identity in the sidebar, the notification bell). In a test that stubs `fetch` with a sequential `mockReturnValueOnce()` queue for the page's own calls, Layout's extra calls silently consume responses meant for the page and desync everything after — this produced a false "10 of 11 tests failed" in `fulfill.test.tsx` before being fixed. Call `stubFetchWithShellDefaults(yourMockFn)` last in the test's `beforeEach`, after building the page's own mock — it intercepts only `/me` and `/exceptions/count` before they reach the sequential queue. Do not re-patch this per file; do not put a bare stub in `test/setup.ts` either — a global stub there gets overwritten by each file's own `vi.stubGlobal('fetch', ...)`, so a callable wrapper invoked last is the only shape that actually composes.

**A post-action state (completion, success, confirmation) must be exactly ONE rendered view — never two views chained together for one user action.** When collapsing a full-screen completion state into a card (or any similar consolidation), DELETE the superseded view's rendering path entirely; do not leave it mounted-but-usually-bypassed. A leftover path resurfaces as a phantom second screen the moment its trigger condition is reachable, exactly as happened in Fulfill: the post-Complete `AwbLinkDialog` had its own internal "linked" success sub-view (checkmark + Done button) that fired before `PickScreen`'s own `completed` card, chaining two success screens for one Complete action. The fix was to make the dialog call `onLinked` (closing itself immediately, no sub-view of its own) instead of `onDone` (which left its sub-view reachable) — and then delete the now-dead `linked` state and its JSX branch outright, not leave it unreferenced in place.

**General note for this rebuild:** a "restyle-only" or "appearance-only" pass must DIAGNOSE before touching anything that could be behavioral. If a requested visual fix turns out to require changing data, a gating condition, or a SAFETY-CRITICAL-marked handler, stop, report the root cause, and wait for explicit approval before touching it — do not silently expand scope from presentation into logic to make a symptom go away.
