# Full Requirements Checklist — v1.3
One line per requirement · [M] Must / [S] Should / [C] Could · use as the build backlog: check items off as they ship.

## FR-1 Tenant & Account
- [x] 1.1 [M] Tenant signup: business name, owner, email, phone, password (verification [S])
- [x] 1.2 [M] Guided onboarding checklist: connect Shopify → connect Bosta → import → test label → first receiving [wizard + 5 component tests Round 3]
- [x] 1.3 [M] Auto-create default "Main Warehouse" location per tenant
- [x] 1.4 [M] Tenant settings: name, pickup address, label size, language AR/EN, timezone [frontend Settings screen Round 2]
- [ ] 1.5 [S] Plan display + manual invoicing status (no online payments)
- [ ] 1.6 [M] Internal super-admin: list/suspend tenants, logged impersonation

## FR-2 Auth, Users & Permissions
- [x] 2.1 [M] Email+password login, JWT access (15m) + refresh, logout-everywhere
- [x] 2.2 [M] User CRUD by Owner/Manager; deactivate only, never delete [frontend Users screen Round 2]
- [x] 2.3 [M] Worker PIN switch at shared stations; attribution until switch / 15-min idle
- [x] 2.4 [M] No anonymous custody actions; PIN lockout after 5 fails + Manager notify
- [x] 2.5 [M] Server-side permission matrix (Owner / Manager / Worker) on every endpoint
- [ ] 2.5a [S] Per-tenant toggle: workers may receive inventory
- [x] 2.6 [M] Privileged-action audit log (users, integrations, adjustments, impersonation)

## FR-3 Shopify Integration (order source — always)
- [x] 3.1 [M] Connect via custom-app credentials + validation ([S] public OAuth app track — OAuth Day 1: install+callback+state+HMAC done; OAuth Day 2: resolve-or-create decision tree + Path-2 provisioning + timestamp freshness + state cleanup done; OAuth Day 4: magic-link bridge shipped — V16 magic_link_tokens + consume_magic_link DEFINER + EmailGateway + MagicLinkController + provision wiring; OAuth Phase 1 complete; Day 21: F1 fixed (V18 JobRunr Flyway migration) + F2 fixed (expiring tokens + ShopifyTokenProvider); Day 22: F1+F2 live-cleared on docker-compose — BackgroundJobServer+recurring job running, token_is_fresh=t; pending browser reinstall on real Shopify store)
- [x] 3.2 [M] Initial import: products/variants + 90-day orders, resumable, idempotent, progress UI [background job + status endpoint done Day 5]
- [x] 3.3 [M] Webhooks orders create/updated/cancelled + products create/update: HMAC, raw persist, async, idempotent [Day 18: raw-body HMAC, shopify_webhook_events, async processor, GDPR handlers, app/uninstalled, RegisterShopifyWebhooksJob]
- [x] 3.4 [M] 15-min reconciliation poll (missed webhook ≠ lost order) [Day 33: ShopifyReconcileJob, gap-filler only, owner-pool cross-tenant listing, EXISTS check before ingest]
- [x] 3.5 [M] Cancel pre-pack → auto-release pieces; cancel post-pack → exception + guided unpack [both paths + Shopify orders/cancelled webhook wired Day 14]
- [x] 3.6 [M] Line-item edits mid-pick → release affected allocations + exception with diff
- [ ] 3.7 [M] Confirmation tag rules (for gated mode)
- [ ] 3.8 [S] Fulfillment write-back with Bosta tracking (toggle, default ON)
- [ ] 3.9 [M] Variant deleted in Shopify → archived locally, pieces/history intact

## FR-4 Bosta Integration (shipment source)
- [ ] 4.1 [M] Connect by API key, validate, encrypt at rest, capture pickup locations
- [ ] 4.2 [M] Mode A: create delivery at packing (type 10, COD, mapped address, businessReference) → tracking + AWB
- [ ] 4.3 [M] City/zone/district mapping seeded from Bosta zoning sheets; fix-it dropdown; remembered rules
- [x] 4.4 [M] Mode B: poll-detect plugin-created deliveries, match by reference (phone+COD fallback fixed Day 23: COD flat scalar, ambiguity guard, partial unique index, reason codes, phone canonicalization), unlinked-shipments screen
- [ ] 4.5 [M] Per-tenant mode: A / B / Hybrid
- [ ] 4.6 [M] Delivery cancellation via API where state allows; else exception with instructions
- [x] 4.7 [M] Status sync: webhook (secret header, verify-by-fetch, no HMAC exists) + 15–30 min polling; (state, type)-keyed mapping; numberOfAttempts stored; trackingNumber as string; unknown codes → exception+alert [Day 6 — webhook→ledger wiring complete; polling not yet built]
- [x] 4.8 [M] Pickup creation (see FR-10) [Day 24: TRACED_MANAGED calls Bosta createPickup; BOSTA_MANAGED skips it; both generate manifest]
- [ ] 4.9 [M] All Bosta failures: retry w/ backoff → plain-language AR/EN exception, never raw errors

## FR-5 Catalog
- [x] 5.1 [M] Product/variant browse+search with per-status piece counts [Committed/Available columns added 2026-07-28: derived-on-read, no stored counter — see PROGRESS.md]
- [ ] 5.2 [M] Variant detail: piece list by status with last-event time
- [ ] 5.3 [S] Local (non-Shopify) products, excluded from sync
- [ ] 5.4 [C] Low-stock indicator per variant
- [x] 5.5 [M] `locations.is_fulfillment` flag scopes which location's stock counts toward the Shopify shadow inventory number (2026-07-28, V59) — backfilled for existing tenants, set on standalone-signup seed. Shopify-first gap (zero-location tenants) **closed 2026-07-29** by `ShopifyLocationProvisioningService` — see 5.6.
- [x] 5.6 [M] FR-17 v2 — Traced-owned Shopify location + increment-only live inventory sync (2026-07-29/30, V60–V61, local/Testcontainers build; not yet run against production). Parts A–D: (A) `ShopifyLocationProvisioningService.ensureTracedWarehouse()` creates/links the Shopify location "Traced Main Warehouse" (`fulfillsOnlineOrders=true`) for both onboarding paths, wired into `ShopifyImportJob`, non-fatal on failure; `LocationController.create()` gated to `is_fulfillment=true` only; junk-location report + guarded per-location cleanup (`GET/POST /api/v1/locations/shopify-junk-report`, `.../{id}/shopify-cleanup`) — nothing auto-deleted. (B) `ShopifyCatalogActivationService` bulk `inventoryActivate`s the catalog at the Traced GID. (C) `ShopifyInventoryReconcileService` — read-only reconcile report (`GET /reconcile`, still available standalone), plus a guarded positive-delta-only initial seed; any variant already non-zero in Shopify is skipped and flagged, never corrected. (D) `ShopifyInventoryService` flipped from shadow rows to live `inventoryAdjustQuantities`/`inventoryMoveQuantities` calls (never `inventorySetOnHandQuantities` — structurally absent from `ShopifyGateway`); three triggers only (receiving +N, return-restock +1, sellable-piece-damaged available→damaged move via `PieceAdjustService`). **KNOWN GAP (not handled, recorded per spec — do not silently fix):** destroying/losing a currently-sellable piece overstates Shopify's sellable stock; closing it needs an approved narrow decrement or move-to-unavailable, a separate decision. **Concurrency hardening (2026-07-30, V61):** Parts A/C/D's original SELECT-then-act guards had a race window under genuine concurrency (proven with dedicated concurrent tests, each verified to fail pre-fix/pass post-fix) — replaced with claim-before-call (INSERT ... ON CONFLICT ... DO UPDATE WHERE status='failed', Parts A/D) and a per-tenant `pg_advisory_xact_lock` held for the whole operation (Part C). V61 adds `UNIQUE(tenant_id) WHERE is_fulfillment=true`. The bare `@idempotent` directive (no key, decorative) was removed. **Connect flow is now fully automatic (2026-07-30)** — `ShopifyImportJob.run()` chains provisioning → catalog import → activation (B) → on_hand seed (C) as one flow on every connect/reconnect, no manual approval gate; `POST .../activate` and `POST .../reconcile/apply` remain available as manual re-trigger tools if an automatic attempt fails.

## FR-6 Receiving & Labeling
- [x] 6.1 [M] Receiving session: location, supplier, reference, note
- [x] 6.2 [M] Lines: variant × qty; lookup by SKU/title ([S] scan manufacturer UPC); editable until finalize
- [x] 6.3 [M] Finalize → ULID piece per unit, status Available, received event; 1,000 pieces ≤ 10s; each piece gets a sequential short code (P000001...) at 0.333mm/module [FR-19 complete]
- [x] 6.4 [M] Label PDF: Code 128 + short code (P000001) + SKU + 24-char variant name; 40×25/50×25; Arabic fonts; 203dpi thermal via OS print [FR-19: short code replaces 26-char ULID, 1.75× GS1 minimum module width]
- [x] 6.5 [M] Reprint any label/session anytime (logged); per-variant "Print Barcodes (N)" button in finalized sessions [FR-20 complete]
- [ ] 6.6 [M] Speed budget: 200-piece session ≤ 60s of system interaction
- [ ] 6.7 [S] Void surplus / append pieces while session untouched (reason: receiving_correction)
- [x] 6.8 [M] Barcodes live at finalize — no activation step

## FR-7 Orders & Confirmation
- [x] 7.1 [M] Pipeline list + filters (incl. Self-Pickup Pending); search by number/name/phone/tracking
- [x] 7.2 [M] Order detail: customer, lines, COD, status timeline, allocated pieces, shipment, audit
- [ ] 7.3 [M] Confirmation modes: Auto-flow (default — straight to Ready to Pick after gates) / Gated (button or tag); prepaid auto-confirm toggle
- [x] 7.4 [M] Hold/unhold with reason; held orders leave queues
- [x] 7.5 [M] COD prominent, editable until packing (logged); ≤ 30,000 EGP validation; frozen after AWB
- [ ] 7.6 [S] Bulk actions: confirm, hold, batch
- [ ] 7.7 [M] No partials: ship complete or flag `short` with missing variant/qty
- [~] 7.8 [M] Entry gates: (a) ✅ blocked-customer phone check (normalized 010/+20/0020) → hold w/ release-or-cancel; (b) address mappability at entry → Address Review w/ remembered fix [deferred: FR-4.3/Mode-A]; (c) Bosta consignee rejection → same exception + offer blocklist add (source bosta_rejected) [deferred: TODO in BostaAwbService]
- [x] 7.9 [M] Blocklist management: add/remove w/ reason+source, logged, ≤50ms lookup ([S] CSV import)

## FR-8 Picking
- [x] 8.1 [M] Pick queue oldest-first; open = lock to worker; Manager can release
- [x] 8.1a [M] Queue gated by Bosta send-state: orders sent to Bosta without being picked in Traced (merchant fulfilled directly via Bosta app) drop from the queue and show a "Not Traced" badge on the Orders list; one-time backfill for pre-existing stuck orders [V57: FulfillService.getQueue() LATERAL join on latest forward shipment; NotTracedTagger shared predicate called from BostaWebhookJob.process() and ShipmentLinkService.manualLink(); tightened 2026-07-28 — orders with NO forward shipment at all are now also excluded unless is_self_pickup=true (previously any no-shipment order stayed in queue regardless of self-pickup)]
- [x] 8.2 [M] Pick screen per line; scan validated ≤300ms; full-screen green/red + audio
- [x] 8.3 [M] Rejection codes: PIECE_NOT_FOUND / WRONG_VARIANT / ALREADY_RESERVED / WRONG_STATUS / DUPLICATE_SCAN (cross-tenant = NOT_FOUND)
- [x] 8.4 [M] Scan atomically: Available→Reserved + allocation + event; concurrent double-scan → exactly one winner
- [x] 8.5 [M] Un-scan releases mis-pick
- [x] 8.6 [M] Picking completes only when all lines fully scanned
- [x] 8.7 [M] Gather list: consolidated variants×totals for selected wave, live decrement, printable [S]
- [ ] 8.7a [M] Wave locking (no double-gathering); Manager release
- [x] 8.7b [M] Shortage shown on gather list before walking shelves
- [ ] 8.7c [C] FIFO piece suggestion (no bins until Phase 2)
- [ ] 8.8 [M] No typed barcodes; Manager-only manual entry, flagged in event

## FR-9 Packing, AWB & Cancellation
- [x] 9.1 [M] Single-step (pick+pack one flow, toggle default ON) or split stations
- [ ] 9.2 [M] Pack re-scan: scanned set ≡ allocated set, blocking mismatch error
- [x] 9.3 [M] Confirm → Mode B prompt AWB scan (Mode A delivery creation deferred post-launch); COD cap pre-validated
- [x] 9.4 [M] Pieces Reserved→Packed + events; allocations packed
- [x] 9.5 [M] AWB auto-print; fetch failure → Packed + missing-AWB exception + retry (never silent loss) [Day 24: mass-awb endpoint, printable-state filter, missing-AWB exception wired to ExceptionService]
- [x] 9.6 [M] AWB verification scan binds piece↔order↔tracking; mismatch rejected loudly — mandatory, no toggle: "Skip — link later" removed entirely; Complete is hidden until the order is linked AND its waybill has been printed, and the post-Complete verify-scan can no longer be bypassed [Fulfill.tsx: pre-Complete inline scan-to-link for unlinked orders, existing Print Waybill button gates Complete when already pre-matched, AwbLinkDialog skip button removed]
- [x] 9.7 [M] After verification → Awaiting Pickup
- [x] 9.8 [M] Guided unpack (cancel post-pack): cancel_requested_at set → worker unpack per piece (unpacked event, PACKED→AVAILABLE) → all clear → order Cancelled; no partial completion
- [x] 9.9 [M] Pre-handover cancel: pre-pack → auto-release pieces (unreserved events, allocations released, order Cancelled); post-pack → guided unpack exception
- [x] 9.10 [M] Self-pickup handover: handover event (packed→delivered), customer_pickup attributed to worker, metadata={"self_pickup":true} → order Delivered
- [x] 9.11 [M] Cancellation removes shipment from created pickup; manifest/COD total corrects [Day 26: removeFromPickupManifest() idempotent, hooked into cancelOrder/unpackPiece/handleOrderCancelled; COD derived live (V22 drops stored column)]
- [x] 9.12 [M] Courier already collected (with_courier/awaiting_pickup/returning) → 409 cancellation blocked
- [ ] 9.13 [S] Self-pickup no-show (7d) → exception: re-ship fresh AWB or cancel

## FR-10 Pickup
- [x] 10.1 [M] Batch pickup creation: select Awaiting Pickup orders, date, location → Bosta pickup [Day 24: BOSTA_MANAGED + TRACED_MANAGED, date validation, already-exists handling]
- [x] 10.2 [M] Printable manifest: order/AWB/COD per parcel + batch COD total [Day 24: manifest generated for both modes; Day 26: COD total derived live, no stored column]
- [~] 10.3 [M] State 21 (picked up from business) → With Courier + handed_to_courier event; manual fallback (attributed) [V47 FR-16 Phase 1: scan-session close is the attributed manual fallback → with_courier + handed_to_courier event; Bosta state-21 webhook path is Phase 2] [fix: PickupSessionService.scan() now normalizes the scanned AWB via TrackingNumberNormalizer before matching shipments.tracking_number — same hub-prefix mismatch bug as 3390752, second of two known-unnormalized paths, now closed]
- [ ] 10.4 [S] Skipped parcels (no collection by EOD+1) → exception

## FR-11 Shipment Lifecycle
- [x] 11.1 [M] (state code, order type)-keyed mapping per verified table (§8.3) → order/piece/event updates [Day 6 complete]
- [x] 11.2 [M] Terminal handling: 45→Delivered; 100→Lost+exception; 46→Return Pending + order Returned [webhook path done Day 6; exception alerts not yet built]
- [x] 11.3 [M] Attempts counter from numberOfAttempts; ≥2 fails → exception (configurable) [Day 33: high_attempts MEDIUM detector, number_of_attempts already stored from webhook]
- [ ] 11.4 [M] No end-customer notifications (merchant-facing only)
- [x] 11.5 [M] Stuck detector: no provider update 5d (configurable) → exception

## FR-12 Returns
- [x] 12.1 [M] Intake scan: piece → Return Pending Inspection at scan location + return_received event [fix: waybill-session open (ReturnSessionService.createSession) now normalizes the scanned AWB via TrackingNumberNormalizer before matching shipments.tracking_number — prod bug, hub-prefixed physical label never matched the bare-digit stored value, silent 404]
- [x] 12.2 [M] Unexpected return (shipment not in returning state) → intake proceeds + flag
- [x] 12.3 [M] Resolution: Restock (→Available) or Damaged (terminal, reason; [S] photo) [fix V58: restock() left the piece's old allocation row 'packed' — ReturnService.restock() now releases it (status='released', matching FulfillService/PieceAdjustService's existing value), so a restocked piece can be re-allocated to a new order instead of hitting ALREADY_RESERVED forever; V58 backfill releases the 6 already-stuck prod pieces; getSessionPieces widened to match]
- [x] 12.4 [M] Never-received report: RTO'd pieces not intaken in 3d, by exact ID — prominent
- [x] 12.5 [M] Restocked piece keeps identity + one continuous timeline; label reprint if peeled [fix: ReturnSessionService.getSessionPieces() status filter widened to include return_pending_inspection/available/damaged — prod bug, sessions always showed "no pieces allocated" since the filter never matched the status pieces are actually in by the time a session is opened; zero test coverage on this method before this fix]
- [ ] 12.6 [C] Customer-initiated returns/exchange workflow — out of MVP (Bosta EXCHANGE/CRP type mapping per §7 Q6 if pilots use it)

## FR-13 Adjustments
- [x] 13.1 [M] Manager/Owner: piece → Lost/Damaged/Destroyed with fixed reason list + adjusted event [Day 34: PieceAdjustService.adjustPiece(), reason enum 6 values, note required for other, adjusted event+audit, phraseKey]
- [x] 13.2 [M] Reserved/Packed pieces guarded: must release from order first [Day 34: PieceCommittedException 409 with orderId+orderNumber; releaseForAdjust reuses unscan/unpackPiece paths; two explicit steps]
- [x] 13.3 [M] Reverse ("found it"): Lost→Available with reason; history never rewritten [Day 34: same /adjust endpoint toStatus=available; terminal 409; append-only events confirmed in adj6 test]
- [x] 13.4 [S] Bulk adjustment by scan session — subsumed by FR-21 stock-take (see below)

## FR-14 Piece Lookup (showcase)
- [x] 14.1 [M] Global scan/type lookup → piece page ≤ 1s
- [x] 14.2 [M] Piece page: variant, status, location, order/shipment links, receiving origin, full timeline
- [x] 14.3 [M] Timeline human-phrased, newest-first, viewer's language
- [x] 14.4 [M] AWB barcode in lookup → shipment/order page with its pieces
- [x] 14.5 [M] Bidirectional navigation order↔piece↔shipment
- [ ] 14.6 [S] Timeline PDF export (dispute evidence)

## FR-15 Dashboards & Exceptions
- [x] 15.1 [M] Inventory counts by status with drill-down [summary endpoint + Overview tiles + Inventory drill-down page Day 32]
- [ ] 15.2 [M] Fulfillment board with age colors (>24h amber, >48h red)
- [x] 15.3 [M] Exceptions center, one prioritized list w/ resolving actions: lost · never-received · unexpected return · failed attempts · stuck (5-day default, per-tenant) · Bosta state 103 limbo · NDR 26–30 evidence · short (signal missing — FR-7.7 deferred) · blocked customer · address review · pending unpack · self-pickup no-show · missing AWB · unlinked Mode-B · shopify_cancel_vs_inflight (Day 25) · Shopify edit conflict; archived w/ resolver
- [ ] 15.4 [S] Owner daily digest (email; WhatsApp [C])
- [ ] 15.5 [C] CSV exports

## FR-16 Localization & Scan UX
- [~] 16.1 [M] Full AR/EN, RTL-correct everywhere incl. labels + manifests (dir flip fixed; Fulfill worker strings done; Receiving placeholders/status badge deferred)
- [~] 16.2 [M] Worker screens one-handed on 5–6.5" Android; auto-focused scan field [V47: pickup scan screen has always-focused input, Enter capture, arm's-length feedback banner; one-handed Android sizing not validated]
- [ ] 16.3 [M] Distinct success/failure audio
- [ ] 16.4 [M] EGP everywhere ([C] Arabic-Indic numerals per user)

## FR-21 Stock Taking
- [x] 21.1 [M] Blind whole-tenant/variant-subset count session; snapshot full piece population (every status) at open [Step 2: `StockTakeService.openSession()`]
- [x] 21.2 [M] Blind idempotent scan, same-tenant only, cross-tenant/unknown barcode never leaks existence [Step 3: `StockTakeService.scan()`]
- [x] 21.3 [M] Disposition report: buckets (on-shelf counted/uncounted, committed, with-courier/delivered, returns bench, damaged, previously written off, unexpected finds) + per-variant rollup + coverage% [Step 4: `StockTakeReconciliationService.reconciliation()`]
- [x] 21.4 [M] Resolutions: found-it (no piece_event), lost on free stock (gated on complete_count, drift-guarded), lost on committed stock (routes through FR-13.2 release guard, no one-tap write-off), condition correction available→damaged [Step 4: `resolve()`; `damaged→available` explicitly out of scope, see PROGRESS.md]
- [x] 21.5 [M] Finalize: live Shopify write-off decrement via dedicated method (never `adjustInventoryQuantities`, never `inventorySetOnHandQuantities`), non-idempotent retry rule (definitive failure auto-retries, ambiguous ack does not) [Step 5: `StockTakeReconciliationService.finalizeSession()` + `StockTakeShopifyPushJob`, CLAUDE.md §7 amended]
- [x] 21.6 [M] Frontend `StockTake.tsx` + AR/EN i18n [Step 6: list/create, blind scan, review/reconciliation, sync-status screens + `useScanner`/`ScanShell` extraction + `GET /sessions`, `GET /sessions/{id}`, `DELETE /sessions/{id}/scan/{pieceId}`, sync mark-resolved/repush]

Built 2026-08-02 per `docs/fr-21-stock-taking-build-spec.md`, Steps 0.5–5, per-step commits; local/Testcontainers only, not run against production or a real Shopify store.

## FR-22 Transfers
- [x] 22.1 [M] Schema: `transfers` / `transfer_lines` / `transfer_pieces`, RLS in-migration, `transfer_pieces_one_active` partial-unique concurrency referee [V64]
- [x] 22.2 [M] Status machine: `out_on_transfer` (active) + `sold` (terminal) enum values + `InventoryLedger.ALLOWED` transitions (gate G1, approved by Marawan) [V65]
- [x] 22.3 [M] `createTransfer` + `scanOut` + send-out race test [claim-before-transition, not transition-as-race-guard — see PROGRESS.md; deviates from "mirror FulfillService.scan() exactly" for a documented Spring transactional reason]
- [x] 22.4 [M] Reconcile: `beginReconcile` + `reconcileScanBack` + `classifyShortfall` (FIFO by `transfer_pieces.created_at`, V66) + `closeTransfer` (moved up from 22.5 — see below) + balance enforcement
- [x] 22.5 [M] `reprintOutstandingLabels` — `TransferController` (`POST /api/v1/transfers/{id}/reprint-outstanding`, `OWNER`/`MANAGER`) created for this one endpoint only
- [ ] 22.6 [M] `createTransfer`/`scanOut`/reconcile* endpoints + role gates (send-out `isAuthenticated()`, reconcile/close `OWNER`/`MANAGER`) + i18n + `LookupService` phraseKeys + `RlsCoverageTest` entries
- [ ] 22.7 [M] Inventory-summary "Out on transfer / At vendor" bucket + pick/gather exclusion tests
- [ ] 22.8 [M] Mode B guard (Bosta webhook on `out_on_transfer` piece → no-op) + test
- [ ] 22.9 [M] Frontend: create/send-out scan screen, consignment list, reconcile screen (Manager/Owner), relabel-print action; RTL, ar+en

Built against `docs/transfers-build-spec.md` (renumbered from a provisional FR-21 — FR-21 is Stock Taking, already built). `closeTransfer` landed in 22.4 rather than 22.5 per explicit build-order request — 22.5 became `reprintOutstandingLabels` only. `TransferController` exists now (created in 22.5, one endpoint) but `beginReconcile`/`reconcileScanBack`/`classifyShortfall`/`createTransfer`/`scanOut` still have no HTTP endpoint and no role gate — that's 22.6.

## NFR (verifiable bars)
- [ ] N1 Scan validation p95 ≤ 300ms · piece page ≤ 1s · 1k receive ≤ 10s · 500-label PDF ≤ 15s · lists ≤ 1.5s @100k pieces · import 5k products+10k orders ≤ 30min
- [ ] N2 **[x] Event+state in one ACID tx (zero custody loss)** · **[x] ledger INSERT-only at DB grants** · **[x] webhooks raw-persisted, replayable, idempotent (Bosta Day 5)** · graceful integration outages · 99.5% availability · daily backups + PITR + tested restore
- [ ] N3 **[x] RLS tenant isolation w/ automated cross-tenant test** · credentials encrypted, never logged · signature/secret-verified webhooks · **[x] argon2/bcrypt + lockouts** · worker PII minimization · Egypt PDPL posture · logged impersonation
- [ ] N4 Chrome/Safari Android 10+/iOS 15+ · PWA installable · HID keyboard-wedge scanners + camera fallback · 203dpi thermal via OS dialog · 40×25/50×25 stock
- [ ] N5 Sentry + structured logs w/ correlation IDs · dead-letter retry UI · per-tenant feature flags · resettable demo tenant
- [ ] N6 **[x] Tests: state machine** · **[x] scan race** · **[x] webhook idempotency (Bosta Day 5)** · **[x] RLS** · **[x] Bosta mapping (Day 5)** · staging wired to Shopify dev store + Bosta staging
- [ ] OPS Bosta IP whitelisting ticket (static IP) · staging access (stg-app.bosta.co)

## Go-live acceptance (30 days, both pilots)
- [ ] 100% new inventory labeled · ≥95% orders full scan path · every piece queryable end-to-end · all RTOs intaken + gaps caught · 300-piece count ≥99% match · zero isolation/custody incidents · ≥150 pieces/hr receiving, ≤4 min pick+pack · both pilots paying
- [ ] **Merchant step — empty/de-list the store's old default Shopify location.** Traced never touches it (FR-17 v2 locked decision), so its stock stays live and the storefront shows the sum of both locations until the merchant does this manually in Shopify admin. Not a code task.
