# Progress Journal — Piece-Level Traceability SaaS

---

## Current state

**315 backend + 23 frontend tests — Bosta live probe complete, 2 field-path bugs fixed** — 2026-06-24.

**Bosta live verification (Day 38):** Ran read-only Checks 1 & 2 against the pilot's real Bosta account (key confirmed working on API v0).

CHECK 1 (AWB print via mass-awb): PASSED — `POST /api/v0/deliveries/mass-awb` with `trackingNumbers=7478049248` returned HTTP 200 and a valid PDF-1.4 (40,750 bytes) in `response.data` as a base64 string. Bug found & fixed: gateway was reading `dataNode.path("pdf")` (expecting `data.pdf` object) but the live API returns `data` as the plain base64 string directly — gateway was falling through to email-path log for every AWB request. Fix: check `dataNode.isTextual()` first, fall back to `.path("pdf")` for legacy shape.

CHECK 2 (consignee fields): The live Bosta API v0 does NOT use a `consignee` key. Recipient data is in `receiver` (`phone`, `firstName`, `lastName`, `fullName`, `secondPhone`) and the delivery address is in `dropOffAddress` (`city.name`, `zone.name`, `district.name`, `firstLine`). Phone format: `+20XXXXXXXXXX` (E.164). Bug found & fixed: `ShipmentLinkService` was reading `raw.path("consignee").path("phone")` in two places (Mode-B auto-match + deferred FR-7.8a blocklist re-check) — both now read `raw.path("receiver").path("phone")`.

Also confirmed: both v0 and v2 base URL paths work for mass-awb (same response). API v0 is the verified working path for `GET /api/v0/deliveries/{id}` and `GET /api/v0/deliveries`.

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

---

## Gotchas / environment quirks

- **Shopify 2026-04 removed `financialStatus` field on Order** — use `displayFinancialStatus` instead. Returns capitalized display values ("Pending", "Paid", "Authorized"). COD inference checks `"pending".equalsIgnoreCase(displayFinancialStatus)` — case-insensitive, so both are safe.
- **`ApiExceptionHandler.handleGeneral(Exception)` intercepts `AccessDeniedException` from `@PreAuthorize`** — `DispatcherServlet` resolves `AccessDeniedException` through `ExceptionHandlerExceptionResolver` before `ExceptionTranslationFilter` can invoke the `AccessDeniedHandler`. Must have an explicit `@ExceptionHandler(AccessDeniedException.class) → 403` handler above the catch-all; otherwise the `Exception` handler returns 500.
- **Supabase 15-connection cap (free plan session-mode pooler)** — solved by sharing one `owner-pool` (max=2, min-idle=1) between Flyway and JobRunr (`@FlywayDataSource` bean in `DataSourceConfig`), and shrinking `HikariPool-1` (app_user) to max=5, min-idle=1. Total at startup: 2 connections. Stale connections from crashed previous runs can fill the 15 slots; kill them in Supabase SQL Editor with `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = 'Supavisor' AND pid <> pg_backend_pid()` then immediately restart. Use `./dev.sh` to start the app — it loads `.env`, kills :8080, and starts Maven. Running `mvn spring-boot:run` in a new terminal without sourcing `.env` first causes Flyway to try `localhost:5432` (connection refused).
- **Shopify Protected Customer Data (PCD) is gated separately from `read_customers` scope** — `shippingAddress` and `customer` fields on Order are blocked even with `read_customers` granted until PCD is approved. Currently `customer_name`, `customer_phone`, `address` are null-populated; full data is preserved in `orders.raw` (jsonb) for backfill once approved. See pending human tasks for what to do.


- **Docker Desktop M3 + Testcontainers API v1.41 override**: Docker Desktop on Mac M3 rejects docker-java's default v1.24 version-negotiation request with HTTP 400. Fix: `DockerDesktopMacStrategy` in `src/test/java/com/traceability/` overrides `test()`, `getClient()`, *and* `getDockerClient()` (all three are required — Testcontainers calls `getDockerClient()` after `test()` passes, and the base implementation re-does version negotiation) to force API v1.41. Strategy is loaded via `~/.testcontainers.properties` AND `src/test/resources/testcontainers.properties`. **Do not delete or "clean up" this class.** On CI (Linux Docker socket) version negotiation works fine; the class is inert there because the built-in `UnixSocketClientProviderStrategy` wins first.
- **`pg_class` RLS flag column is `relrowsecurity`** — not `rowsecurity`. Fixed in `MigrationSmokeTest.java` line ~90.
- **`FORCE ROW LEVEL SECURITY` binds the table owner too** — any future Flyway migration that needs to INSERT into a tenant-scoped table (e.g., seed a default location for a new tenant) must do so as `postgres` (which holds `BYPASSRLS` and therefore bypasses RLS unconditionally — confirmed `rolbypassrls=true, rolsuper=false` on Supabase) or with a `SECURITY DEFINER` helper. `app_user` will be blocked regardless.
- **`BostaWebhookJob` piece-transition catches are intentional idempotency guards — do not convert to errors.** A repeat terminal-state webhook (e.g. state 45 delivered arriving twice) hits one of three safe paths: (1) `current == target` fast-path check skips `ledger.transition()` entirely — no DB write; (2) `StateConflictException` where `getActual() == targetStatus` — concurrent worker applied the same transition first, treat as no-op; (3) `IllegalTransitionException` — piece has no legal path to target (e.g. a stale `with_courier` event arriving after `delivered`), log warning and continue. None of these paths fail the webhook. The dedup check (step 4) catches exact payload redeliveries before reaching pieces at all. Do not "fix" any of these catches into error or rethrow paths.
- **`SET LOCAL` is a silent no-op outside a transaction** — the `SET LOCAL app.current_tenant = ?` call for the tenant context filter must happen inside an explicit transaction (`BEGIN` / `COMMIT`). Called outside a transaction it silently succeeds but resets at the next statement boundary, leaving subsequent queries with no tenant context (empty-string GUC → policy evaluates to false → zero rows or constraint violation).

