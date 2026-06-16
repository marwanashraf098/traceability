# Progress Journal — Piece-Level Traceability SaaS

---

## Current state

Day 11 complete as of 2026-06-17. All 117 integration tests pass (BUILD SUCCESS).

**Pending live verification:**
- Mode B linking built + tested against mocks — needs live verification against a real Bosta account (real delivery JSON shape, consignee fields, end-to-end match/link) once account/staging is available.

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

## Next up

**Live test session (2026-06-16) — bugs found and fixed:**
- `SecurityConfig` was blocking static assets (`/`, `/index.html`, `/assets/**`) — SPA couldn't load, browser got 401
- `/api/v1/locations` endpoint missing — receiving form showed text input instead of warehouse dropdown; added `LocationController`
- `Receiving.tsx` `loadLocations()` called `api('/api/v1/locations')` which double-prepended the `/api/v1` base, resulting in 404; fixed to `/locations`
- `ReceivingService.searchVariants()` filtered on `v.status = 'active'` but `status` lives on `products` not `variants` — SQL error silently returned 0 results; fixed to `p.status = 'active'`
- Added `dev.sh` convenience script (loads `.env`, kills :8080, runs Maven) to avoid repeated env-var loss between terminal sessions

**Day 11: Mode B fulfillment linking.** The pilots' Bosta plugin creates deliveries and AWBs; we INGEST and LINK them — we do NOT create deliveries. Scope:
- AWB-scan at pack time: packer scans the plugin-printed waybill barcode; system creates the `shipments` row, binds pieces↔order↔tracking, writes `tracking_linked` events, advances order to `awaiting_pickup`.
- Reject swapped/mismatched AWBs (wrong order reference) loudly.
- Match plugin-created Bosta deliveries to Shopify orders via `businessReference` / order # / phone+COD fallback.
- Surface unmatched deliveries in `unlinked_bosta_deliveries` for manual linking (screen already exists from Day 6).
- Built against the mock Bosta gateway; live verification deferred until Bosta IP whitelisting clears.
- **Do NOT build Mode A delivery creation — deferred post-launch.**

**Shopify OAuth design is owned by a separate design thread.** The production Shopify connect path = public OAuth app (decision recorded in "Decisions made" below). The detailed OAuth flow, scopes, callback URL, and state-parameter handling are being designed in a separate chat/thread. Do not re-derive or modify the OAuth design from this build thread. When that design is finalized it will be handed back here as a spec for implementation. The current custom-app endpoint (`POST /api/v1/shopify/connect` with `adminToken`) is DEV-ONLY and stays as-is until the spec arrives.

**Human tasks remaining:**
- Open Bosta whitelisting/staging ticket (static egress IP) — needed for webhook delivery + API calls
- Shopify PCD approval (customer name/phone/address unblocked) — submit early, has lead time
- Register the public Shopify app in the Partner Dashboard (required before OAuth flow can be built)
- Submit the Shopify PCD access request immediately after registering the app — review has non-trivial lead time and is a hard launch dependency
- Write a privacy policy and data-use statement (required by Shopify for any public app, including pre-App-Store pilots)

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

---

## Pending human tasks (not code)

- ~~Apply V1 + V2 + V3 migrations to Supabase and set `app_user` password out-of-band~~ **Done 2026-06-13.**
- ~~Get pilots' label-size answer~~ — label PDF already ships at 50×25mm (configurable); no blocker.

- **[NEAREST IMPACT] Open Bosta whitelisting / staging ticket** — static egress IP required for webhook delivery and API calls from our server to Bosta. This gates Day 11 live verification: Mode B matching will be built and unit-tested against the mock gateway, but cannot be verified end-to-end until our IP is whitelisted. Also gates any live Bosta delivery lookups. Open the ticket with Bosta support immediately.

- **Shopify app registration + PCD approval** — post-pilot but needs lead time:
  - Register the public Shopify app in the Partner Dashboard (required before the production OAuth flow can be built).
  - Submit PCD access request immediately after registration — Shopify review has non-trivial lead time and is a hard launch dependency for customer name/phone/address. Without it, orders import with null PII (data preserved in `orders.raw` for backfill once approved).
  - Write a privacy policy and data-use statement (required for any Shopify public app, including pre-App-Store pilots).

- **Shopify Protected Customer Data (PCD) — dev store unblock** (optional, for richer testing now): Shopify Admin → Settings → Apps → custom app → API credentials → add `read_customers` scope + regenerate token → update `SHOPIFY_ADMIN_TOKEN` in `.env`. Then Partner Dashboard → Apps → App setup → Protected customer data → request access.
