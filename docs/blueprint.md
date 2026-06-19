# Piece-Level Inventory Traceability Platform for Egyptian D2C Brands
## Complete Product & Architecture Blueprint

---

# 0. Executive Summary & Challenged Assumptions

Before the deliverables, here is where I push back on your plan. These changes materially improve your odds of getting paying customers from v1.

**Assumption 1 — "Bosta shipment is automatically created when the Shopify order arrives." Change this.**
In Egyptian D2C, 60–85% of orders are Cash on Delivery, RTO (return-to-origin) rates run 15–30%, and almost every serious brand runs a *confirmation call* before shipping. Creating the Bosta delivery at order time is wrong for three reasons: (a) COD amounts and addresses change after the confirmation call, (b) cancelled/edited orders create orphan shipments and reconciliation noise, and (c) Bosta charges/penalizes on cancelled AWBs in some plans. **Create the Bosta delivery at packing time**, when the order is confirmed, contents are scanned, and the COD amount is final. The order pipeline becomes: `New → Confirmed → Ready to Pick → Picking → Packed (AWB created here) → Awaiting Pickup → Handed to Courier`. MVP does not need a built-in confirmation call center — just a manual "Confirm" button and a webhook/tag sync from Shopify (many brands mark confirmation with Shopify tags).

**Assumption 2 — Returns are Phase 2 in your plan. They must be MVP.**
Your core promise is "know where every piece is and where loss happens." In Egypt, *the single biggest black hole is the return flow*: COD refusals come back weeks later, in batches, half-inspected, and pieces silently vanish between "Bosta says returned" and "back on the shelf." If the MVP can't scan a returned piece back in (restock / damaged / missing), the chain of custody breaks exactly where merchants bleed the most, and your pitch collapses in the first demo. The MVP needs one simple screen: scan returned piece → inspect → `Restock` / `Damaged` / route the discrepancy to the exception dashboard. This is ~1 week of work and it is the feature that closes deals.

**Assumption 3 — Labeling every piece is your moat AND your biggest adoption risk.**
A worker labels roughly 250–400 pieces/hour. A brand receiving 5,000 units/month adds ~15–20 labor-hours/month. That is acceptable *if and only if* receiving is brutally fast: bulk barcode generation, batch printing on a cheap thermal printer (Xprinter ~EGP 2,500, ubiquitous in Egypt), and labels designed to apply in one motion. Do not soften the core concept by adding SKU-level mode to the MVP — it doubles your data model and kills your differentiation — but **design receiving as a first-class, obsessively optimized workflow**, not an admin form. Target: 100 pieces received, printed, and labeled in under 10 minutes. Also: sell first to brands where per-unit value justifies it (fashion, jewelry, perfumes, electronics accessories) — not FMCG.

**Assumption 4 — "Mobile app after PMF" is too late for scanning, but you don't need an app.**
Warehouse scanning on day one should be: any Android phone or cheap USB/Bluetooth HID barcode scanner (EGP 800–1,500) + your **responsive web app / PWA**. HID scanners type the barcode like a keyboard — zero native code needed. The Flutter app is correctly post-PMF; the *scanning experience* is MVP.

**Assumption 5 — Don't build "dashboards," build three operational screens.**
MVP merchants don't need analytics; they need: (1) a pick/pack queue that workers live in, (2) a piece-lookup ("scan or search any barcode → full history"), and (3) an exceptions list. The piece-lookup screen *is your demo* — it's the "wow" moment. Treat fancy charts as Phase 2.

**Assumption 6 — One location in MVP, but model `location_id` from day one.**
You deferred locations to Phase 2 — correct — but the schema must carry `current_location_id` on every piece and every event from v1, seeded with a single default "Main Warehouse." Retrofitting location onto millions of immutable events later is painful; carrying a constant column now is free.

**The one-sentence strategy:** the MVP is not a WMS — it is a *chain-of-custody layer* between Shopify and Bosta that makes inventory loss visible and attributable, sold on the pain "stop losing inventory," buildable by 2 engineers in 10–12 weeks.

---

# 1. Complete PRD

## 1.1 Problem Statement
Egyptian D2C brands selling on Shopify and shipping via Bosta lose 2–8% of inventory annually with no ability to determine where, when, or under whose custody the loss occurred. Existing tools (Shopify inventory, spreadsheets, generic IMS) track *quantities*, not *individual pieces*, so discrepancies are discovered months late and are unattributable.

## 1.2 Product Goal
Give every physical piece a unique identity and an unbroken, timestamped, person-attributed chain of custody from warehouse receipt to terminal state (delivered, restocked, damaged, lost, destroyed) — with Shopify and Bosta wired in so the chain extends through fulfillment and last-mile.

## 1.3 Target Customer (MVP ICP)
Egyptian D2C brand on Shopify, shipping 300–5,000 orders/month primarily via Bosta, operating its own small warehouse/storeroom (1–10 staff), selling mid-to-high unit value goods (fashion, accessories, beauty, electronics accessories). Decision maker: founder or operations manager who personally feels the shrinkage pain.

## 1.4 Personas
- **Founder/Owner** — buys the product; wants loss visibility, accountability, and trust in numbers. Uses exception dashboard and piece lookup.
- **Operations / Warehouse Manager** — runs receiving, assigns work, investigates discrepancies, schedules pickups.
- **Picker/Packer** — lives in the scan-driven pick/pack queue; minimal UI, Arabic-first, large touch targets, works with a handheld scanner.

## 1.5 Success Metrics
- Activation: merchant labels ≥ 500 pieces and ships ≥ 50 scanned orders within 14 days of signup.
- Core value: inventory record accuracy ≥ 99% at first cycle count; 100% of shipped orders traceable piece→order→AWB.
- Business: 10 paying merchants within 90 days of launch; < 5% monthly logo churn.
- Ops efficiency (secondary): pick-to-pack time per order; mis-ship rate (target < 0.3%).

## 1.6 MVP User Stories (condensed)
1. As a merchant, I connect my Shopify store and Bosta account in under 10 minutes and see my products and recent orders imported.
2. As an ops manager, I receive a delivery of 200 units, generate 200 unique barcodes, batch-print labels, and the pieces appear as Available.
3. As a picker, I open the pick queue, scan pieces against an order, and the system blocks me if I scan the wrong variant or an unavailable piece.
4. As a packer, I rescan the allocated pieces, the system creates the Bosta delivery, prints the AWB, and links piece ↔ order ↔ tracking number.
5. As an ops manager, I select packed orders and create one Bosta pickup; statuses update automatically as Bosta moves the shipments.
6. As an ops manager, I scan a returned piece, mark it restocked or damaged, and the chain of custody continues.
7. As a founder, I scan or search any barcode and see the piece's entire life: every status, timestamp, person, order, and shipment.
8. As a founder, I see exceptions (lost shipments, failed deliveries, returns pending inspection, stuck orders) in one list.

## 1.7 Non-Goals (MVP)
Multi-warehouse, bin locations, transfers, vendor tracking, purchase orders, demand forecasting, accounting sync, multi-courier, native mobile app, employee productivity analytics, cycle counting module (manual lookup suffices), Shopify inventory write-back (read-only first — see §8).

## 1.8 Risks
- **Adoption friction of labeling** → mitigate with receiving-speed obsession and ICP selection (see Assumption 3).
- **Bosta API instability / webhook gaps** → mandatory polling reconciliation loop (see §9).
- **Workers bypass scanning under pressure** → make the scan path *faster* than the bypass (auto-advance, no typing), and surface bypass as exceptions to the manager rather than hard-blocking everything.
- **Shopify order edits/cancellations after picking** → cancellation webhook releases reservations and alerts the packer.

---

# 2. MVP Scope (Phase 1 — ship in 10–12 weeks)

**In:** Shopify OAuth connect + product/variant/order import + order & cancellation webhooks · Bosta account connect · single default location · receiving with bulk barcode generation + thermal label batch printing (PDF) · order pipeline (New → Confirmed → Ready to Pick → Picking → Packed → Awaiting Pickup → Handed to Courier → terminal) · scan-validated picking with reservation · scan-validated packing with Bosta delivery creation + AWB print + tracking link · pickup creation (batch) · Bosta status sync (webhook + polling) with automatic piece status mapping · **return receiving (scan → restock/damaged)** · piece lookup with full event timeline · inventory status counts screen · fulfillment queue screen · exceptions screen · roles: Owner, Manager, Worker · Arabic + English UI · audit log = append-only event store.

**Out:** everything in §1.7.

**Team & timeline:** 2 full-stack engineers + you. Weeks 1–2 foundations (tenancy, auth, Shopify connect, import). Weeks 3–4 receiving + barcode + printing. Weeks 5–6 pick/pack + reservations. Weeks 7–8 Bosta integration (delivery, AWB, pickup, sync). Week 9 returns + exceptions. Weeks 10–12 piece lookup, polish, Arabic, pilot with 2 design-partner brands *who commit to paying at go-live*.

---

# 3. Product Roadmap

**Phase 1 — MVP (Months 0–3):** scope above. Goal: 10 paying merchants, prove the chain-of-custody value.

**Phase 2 — Movement & Locations (Months 4–7):** custom locations (warehouse, branch, showroom, retail, event); transfer workflow (scan-out → in-transit-internal → scan-in) with user, timestamp, reason; per-location inventory views; partial fulfillment & order editing; Shopify inventory write-back (now that internal data is trusted); manual stock adjustments with mandatory reason codes; basic cycle counting (scan-to-count a shelf/area); CSV exports; second courier integration (Mylerz or J&T) behind a courier-adapter interface you build in MVP but only implement for Bosta.

**Phase 3 — External Custody / Vendors (Months 8–12):** vendor registry (dry cleaning, tailoring, embroidery, studio, repair, influencer); send-out/receive-back workflows with expected-return dates and overdue alerts; consignment view ("347 pieces currently outside our walls, here's with whom and since when"); vendor loss attribution; purchase-order-lite for inbound expectations at receiving.

**Long-term vision (Year 2+):** the system of record for every physical asset a brand owns — shrinkage detection engine (expected vs. accounted, narrowed to last scan/location/person), employee accountability analytics, Flutter scanning app with offline queue, nightly three-way reconciliation (Shopify ↔ Bosta ↔ internal), AI loss investigation ("why am I losing inventory?" → pattern analysis across employees, vendors, locations, couriers), multi-courier rate/performance comparison, B2B/wholesale orders, and eventually an API/platform other Egyptian commerce tools build on.

**Monetization:** subscription tiers by monthly shipped orders (e.g., EGP 1,500 / 3,500 / 7,500 per month for 1k / 5k / 15k orders) — *not* per-piece pricing, which would penalize your own core behavior.

---

# 4. Domain Model

**Tenant** — one merchant company; root of all data isolation.
**User** — belongs to a tenant; role Owner / Manager / Worker; every custody event is attributed to a user.
**Store** — a connected Shopify shop (one per tenant in MVP, model as 1:N for the future).
**CourierAccount** — a connected Bosta account (provider-typed for future couriers).
**Product / Variant** — mirrors of Shopify catalog; Variant carries the SKU. Variants are the unit a piece instantiates.
**Receipt** — a receiving session ("we received 200 units on June 10 from supplier X"); groups pieces created together.
**Piece** — the atom of the system. One physical unit with a unique ID/barcode, a current status, current location, current order (nullable), and pointers to its last scan. *Current state on Piece is a read-optimized projection; the truth is the event stream.*
**PieceEvent** — append-only, immutable custody record: what happened, to which piece, by whom, where, when, in the context of which order/shipment. Never updated, never deleted.
**Location** — physical place; MVP seeds exactly one ("Main Warehouse").
**Order / OrderItem** — mirror of Shopify order with local fulfillment status; OrderItem = variant × quantity.
**Allocation** — the link Piece ↔ OrderItem created at pick-scan; this is what "Reserved" means physically.
**Shipment** — a Bosta delivery (AWB); belongs to an order; carries tracking number, COD amount, provider state, raw provider payloads.
**Pickup** — a Bosta pickup request grouping shipments.
**WebhookEvent** — raw inbound payloads from Shopify/Bosta, stored before processing (replayable, debuggable).

**Piece status machine:**

```
available → reserved → packed → awaiting_pickup → with_courier
   ↑            |                                     |
   |        (unpick)                                  ├→ delivered            [terminal]
   |                                                  ├→ return_in_transit → return_pending_inspection
   |                                                  |                          ├→ available (restocked)
   |                                                  |                          └→ damaged   [terminal]
   |                                                  └→ lost                  [terminal]
   └── manual adjustment paths: → damaged / lost / destroyed (reason required)
```

**Order status machine:** `new → confirmed → ready_to_pick → picking → packed → awaiting_pickup → with_courier → (delivered | returning → returned | lost) ; cancellable until packed (after packed, cancellation triggers Bosta AWB cancellation + unpack flow).`

Invariants enforced in code and DB: a piece has ≤ 1 active allocation; a piece can only be picked if `available`; allocation variant must equal order-item variant; every status transition writes exactly one PieceEvent in the same DB transaction; PieceEvents are insert-only.

---

# 5. Database Schema (PostgreSQL)

```sql
-- every table carries tenant_id; RLS enforces isolation (see §16)

tenants            (id uuid PK, name, plan, status, created_at)
users              (id uuid PK, tenant_id FK, name, email, phone,
                    password_hash, role enum(owner,manager,worker),
                    pin_code,          -- fast worker login at scan stations
                    active bool, created_at)

stores             (id uuid PK, tenant_id, platform enum(shopify),
                    shop_domain unique, access_token_encrypted,
                    webhook_secret, status, last_sync_at)

courier_accounts   (id uuid PK, tenant_id, provider enum(bosta),
                    api_key_encrypted, default_pickup_address jsonb,
                    business_ref, status)

locations          (id uuid PK, tenant_id, name, type enum(warehouse,branch,
                    showroom,retail,event,vendor), is_default bool)

products           (id uuid PK, tenant_id, store_id, external_id,
                    title, status, raw jsonb, UNIQUE(store_id, external_id))
variants           (id uuid PK, tenant_id, product_id, external_id,
                    sku, title, upc_barcode, price, raw jsonb,
                    UNIQUE(product_id, external_id))

receipts           (id uuid PK, tenant_id, reference, supplier_name,
                    received_by FK users, location_id, note, created_at)

pieces             (id uuid PK,            -- ULID; the barcode encodes this
                    tenant_id, variant_id, receipt_id,
                    barcode text UNIQUE,    -- e.g. PC-01J9XYZ8K2M4
                    status enum(available,reserved,packed,awaiting_pickup,
                                with_courier,delivered,return_in_transit,
                                return_pending_inspection,damaged,lost,destroyed),
                    current_location_id FK locations,
                    current_order_id FK orders NULL,
                    last_event_at timestamptz, last_user_id FK users,
                    created_at)
  INDEX (tenant_id, variant_id, status)     -- "give me an available piece of X"
  INDEX (tenant_id, status)
  INDEX (barcode)

piece_events       (id bigserial PK, tenant_id, piece_id FK,
                    event_type text,        -- received, reserved, unreserved,
                                            -- packed, tracking_linked,
                                            -- handed_to_courier, courier_update,
                                            -- delivered, return_received,
                                            -- restocked, marked_damaged,
                                            -- marked_lost, adjusted, moved
                    actor_user_id NULL,     -- NULL = system/webhook
                    order_id NULL, shipment_id NULL, location_id NULL,
                    from_status, to_status, metadata jsonb,
                    occurred_at timestamptz, created_at timestamptz)
  -- INSERT-only (revoke UPDATE/DELETE); partition by month when large
  INDEX (piece_id, occurred_at)
  INDEX (tenant_id, event_type, occurred_at)

orders             (id uuid PK, tenant_id, store_id, external_id UNIQUE/store,
                    number, customer_name, customer_phone, address jsonb,
                    payment_method enum(cod,prepaid), cod_amount numeric,
                    status enum(new,confirmed,ready_to_pick,picking,packed,
                                awaiting_pickup,with_courier,delivered,
                                returning,returned,lost,cancelled),
                    placed_at, raw jsonb, created_at)
order_items        (id uuid PK, tenant_id, order_id, variant_id,
                    quantity int, raw jsonb)

allocations        (id uuid PK, tenant_id, order_item_id, piece_id,
                    status enum(active,packed,released),
                    allocated_by, allocated_at,
                    UNIQUE(piece_id) WHERE status IN ('active','packed'))

shipments          (id uuid PK, tenant_id, order_id, courier_account_id,
                    provider enum(bosta), tracking_number UNIQUE,
                    provider_delivery_id, provider_state, internal_state,
                    cod_amount, awb_url, last_synced_at, raw jsonb, created_at)

pickups            (id uuid PK, tenant_id, courier_account_id,
                    provider_pickup_id, scheduled_date, status, created_at)
pickup_shipments   (pickup_id FK, shipment_id FK, PK(pickup_id, shipment_id))

webhook_events     (id bigserial PK, source enum(shopify,bosta), tenant_id NULL,
                    topic, payload jsonb, status enum(pending,processed,failed),
                    error text, received_at, processed_at)
  -- idempotency: UNIQUE(source, external_event_id)
```

Why this shape: `pieces` is a fast projection for queues and counts; `piece_events` is the legally-grade custody ledger; `allocations` makes reservation explicit and auditable instead of hiding it in a status flag; `raw jsonb` columns mean you never lose provider data you didn't think to model.

---

# 6. Event Model

Architecture: **event-sourcing-lite**. You do not need full event sourcing with projections/replay infrastructure at MVP — you need an immutable ledger plus a current-state table, updated together in one ACID transaction:

```
BEGIN;
  UPDATE pieces SET status='reserved', current_order_id=:o,
         last_event_at=now(), last_user_id=:u WHERE id=:p AND status='available';
  -- 0 rows updated → conflict → abort (prevents double-pick race)
  INSERT INTO allocations (...);
  INSERT INTO piece_events (piece_id, event_type:'reserved', from:'available',
         to:'reserved', actor_user_id, order_id, location_id, occurred_at) ...;
COMMIT;
```

Canonical event types (MVP): `received`, `reserved`, `unreserved`, `packed`, `tracking_linked`, `handed_to_courier`, `courier_update` (with provider state in metadata), `delivered`, `return_in_transit`, `return_received`, `restocked`, `marked_damaged`, `marked_lost`, `adjusted`, plus Phase-2 `moved`, Phase-3 `sent_to_vendor` / `received_from_vendor`.

Every event answers the four custody questions: **when** (`occurred_at`), **who** (`actor_user_id`, nullable = system), **what** (`event_type`, `from_status → to_status`, `metadata`), **where** (`location_id`) — plus context (`order_id`, `shipment_id`). The piece-lookup screen is literally `SELECT * FROM piece_events WHERE piece_id=? ORDER BY occurred_at` rendered as a timeline.

Internal pub/sub: after commit, publish a lightweight domain event (`piece.status_changed`, `order.status_changed`) onto Redis/BullMQ for side effects (exception detection, future notifications, future Shopify write-back) — keeping side effects out of the hot transaction.

---

# 7. Multi-Tenant SaaS Architecture

**Model: single database, shared schema, `tenant_id` on every row, enforced by PostgreSQL Row-Level Security.** At your scale (hundreds of merchants, each with 10³–10⁶ pieces) this is the right cost/complexity point; schema-per-tenant adds migration pain for no benefit until you have enterprise customers demanding isolation.

- Every request resolves the tenant from the auth token → `SET app.current_tenant = '<uuid>'` on the pooled connection → RLS policies (`USING (tenant_id = current_setting('app.current_tenant')::uuid)`) make cross-tenant reads impossible even if application code has a bug.
- Background jobs carry `tenant_id` in the payload and set the same GUC before touching the DB.
- Webhooks resolve tenant by `shop_domain` (Shopify) or business ID / API-key mapping (Bosta) before processing.
- Per-tenant rate limiting on sync jobs so one 50k-order import doesn't starve everyone.
- Application is a **modular monolith** (modules: identity, catalog, inventory, fulfillment, integrations-shopify, integrations-courier, events) — one deployable, clean internal boundaries, extractable later.

```
Shopify ⇄ webhooks/API ─┐
                        ├→ [API/Web monolith] ⇄ PostgreSQL (RLS)
Bosta   ⇄ webhooks/API ─┘        │
                                 ├→ Redis + BullMQ workers (sync, polling,
                                 │   webhook processing, PDF generation)
                                 └→ S3-compatible storage (label/AWB PDFs)
```

---

# 8. Shopify Integration Design

**App type:** start as a **custom app per merchant** (merchant creates it in their admin, pastes credentials) for your first 5–10 design partners — zero app-review delay. In parallel, submit a **public OAuth app** to the Shopify App Store (distribution channel + required for scale).

**Scopes (minimal):** `read_products, read_orders, read_fulfillments` — and *not* `write_inventory` in MVP. Writing back to Shopify inventory before your internal numbers are trusted creates a two-way sync problem (the hardest class of bug you can sign up for). Read-only first; write-back is a Phase 2 feature once a merchant has run clean for a month. Add `write_fulfillments` only if design partners demand that packing marks the Shopify order fulfilled with the Bosta tracking number — this one is low-risk and high-value, so treat it as a fast follow.

**Initial import:** GraphQL Bulk Operations for products/variants and last-90-days orders (handles large catalogs without rate-limit pain). Map `external_id` everywhere; upserts are idempotent.

**Webhooks:** `orders/create`, `orders/updated`, `orders/cancelled`, `products/create`, `products/update`, plus mandatory GDPR topics for the public app. Verify HMAC, persist raw payload to `webhook_events`, ack 200 immediately, process async.

**Reliability:** Shopify webhooks are at-least-once and occasionally never — run a reconciliation poll every 10–15 minutes (`orders updated_at > last_sync`) so a missed webhook costs minutes, not a lost order. Idempotency on `(source, external_event_id)` makes duplicates harmless.

**Order edits:** if items change after picking, release affected allocations (pieces → `available`, event `unreserved`), flag the order in exceptions. Cancellation after packing → alert + guided unpack + Bosta AWB cancellation.

# 9. Bosta Integration Design

**Connect:** merchant pastes Bosta API key; you validate by fetching the business profile; store encrypted; capture default pickup address.

**Create delivery (at packing):** type Package Delivery / forward order; payload includes consignee name/phone/address (Bosta city/zone codes — build a city-mapping table and a fallback "pick the zone" UI for unmappable Shopify addresses, a notoriously messy real-world step), COD amount for COD orders, item count, your order number as merchant reference. Response gives `trackingNumber` + delivery ID → create `shipment`, fetch/print AWB PDF.

**Tracking-number linking:** since the AWB is created via API, piece↔order↔AWB linkage is automatic at packing. Keep your "scan the AWB barcode" step anyway as a *verification* scan — it catches the swapped-labels failure mode (two parcels, two AWBs, crossed), which is a real and expensive error class.

**Pickup:** batch endpoint with selected packed shipments + pickup date → `pickups` row; courier collection (first `Picked Up` status from Bosta) drives `awaiting_pickup → with_courier` on order and pieces, with a system `handed_to_courier` event.

**Status sync — webhooks AND polling, never webhooks alone:** register Bosta webhooks where available; regardless, poll active (non-terminal) shipments every 15–30 minutes. Map provider states through a versioned mapping table:

| Bosta state | Internal shipment | Piece status |
|---|---|---|
| Created / Pending pickup | created | packed/awaiting_pickup |
| Picked Up / Received at warehouse | with_courier | with_courier |
| In Transit / Out for Delivery | with_courier | with_courier |
| Delivered | delivered | delivered (terminal) |
| Delivery Failed / Rescheduled | exception | with_courier + exception flag |
| Returning / Return in transit | returning | return_in_transit |
| Returned to origin | returned | return_pending_inspection |
| Lost / Investigation | lost | lost + exception |
| Cancelled | cancelled | back to packed → guided unpack |

Unknown states map to `exception` and page you (Sentry alert) — courier APIs add states without notice. Store every raw payload.

**Courier adapter:** define a `CourierProvider` interface (`createDelivery`, `cancelDelivery`, `getAwb`, `createPickup`, `fetchStatus`, `normalizeState`) implemented only by `BostaProvider` in MVP — this is the cheap insurance that makes courier #2 a 2-week job instead of a refactor.

# 10. API Design

REST, JSON, `/api/v1`, JWT (worker PIN exchange for short-lived tokens at scan stations), tenant from token, cursor pagination, idempotency keys on mutating integration-adjacent endpoints.

```
POST  /auth/login | /auth/pin-login
GET   /catalog/products?query=            (variants embedded)
POST  /receipts                           {variant_id, quantity, supplier?, note?}
      → creates N pieces, returns receipt_id
GET   /receipts/:id/labels.pdf            (batch thermal-label PDF)
GET   /pieces/:barcode                    (piece + full event timeline)
GET   /pieces?status=&variant_id=&cursor=
POST  /pieces/:id/adjust                  {action: damaged|lost|destroyed|restock, reason}
GET   /orders?status=ready_to_pick|...    (the queues)
POST  /orders/:id/confirm
POST  /orders/:id/pick-scan               {barcode} → validates variant/availability,
                                           allocates; 409 + reason on mismatch
POST  /orders/:id/unpick                  {barcode}
POST  /orders/:id/pack                    {scanned_barcodes[]} → verifies = allocations,
                                           creates Bosta delivery, returns awb_url
POST  /orders/:id/verify-awb              {tracking_barcode}
POST  /pickups                            {shipment_ids[], date}
POST  /returns/scan                       {barcode} → return_pending_inspection context
POST  /returns/:piece_id/resolve          {outcome: restock|damaged, note}
GET   /dashboard/inventory-summary | /dashboard/exceptions
POST  /integrations/shopify/connect | /integrations/bosta/connect
POST  /webhooks/shopify | /webhooks/bosta  (public, signature-verified)
```

Design notes: scan endpoints return in <150 ms with explicit machine-readable error codes (`WRONG_VARIANT`, `ALREADY_RESERVED`, `PIECE_NOT_FOUND`, `WRONG_STATUS`) so the UI can give instant red/green + sound feedback — scan UX lives or dies on latency and clarity.

# 11. User Roles & Permissions

**Owner** — everything: billing, integrations, users, adjustments, exports.
**Manager** — receiving, returns resolution, adjustments (reason required), pickups, exceptions, dashboards, user management for workers; no billing/integration credentials.
**Worker** — pick/pack queues, scanning, return intake scan only; no costs, no customer phone numbers beyond what packing requires, no adjustments, no exports.

Implementation: simple role enum + permission middleware (full RBAC tables are over-engineering at MVP). Every privileged mutation already lands in `piece_events`/audit, so accountability is structural. Workers authenticate by personal PIN at shared scan stations — *named* attribution with zero login friction; never allow a shared "warehouse" account, it destroys your accountability story.

# 12. Barcode Strategy

- **Piece ID = ULID** (time-sortable, collision-free, no per-tenant sequence coordination). Human prefix: `PC-01J9XYZ8K2M4`.
- **Symbology: Code 128** — compact, alphanumeric, readable by every cheap scanner and phone camera. QR optional later for camera-first scanning (encode a URL `https://app.../p/PC-...` so a scanned piece deep-links to its timeline).
- **Label:** 40×25 mm or 50×25 mm thermal (Xprinter/Zebra-compatible), direct-thermal stock: barcode + human-readable ID + SKU + variant short name. Generate as batch PDF server-side; print via any OS driver — no printer SDK integration in MVP.
- **Scanning hardware:** Bluetooth/USB HID scanners (EGP 800–1,500) acting as keyboards into the web app; a global keyboard listener routes scans to the active workflow. Phone camera (html5-qrcode) as fallback.
- **Disambiguation rule:** your namespace prefix (`PC-`) prevents collisions with manufacturer UPC/EAN barcodes already on products and with Bosta AWB barcodes — the scan handler routes by prefix (piece vs AWB) automatically.
- Do **not** put product UPCs to work as piece IDs — many units share a UPC; that's SKU-level thinking sneaking back in.

# 13. Recommended Tech Stack

- **Backend:** TypeScript + **NestJS** (modular monolith fits it naturally; strong Egyptian hiring pool; one language across stack). Prisma or TypeORM + raw SQL where it matters.
- **DB:** PostgreSQL 16 (managed). **Queue/cache:** Redis + BullMQ. **Storage:** S3-compatible (AWS S3 or Cloudflare R2) for label/AWB PDFs.
- **Frontend:** Next.js + React, Tailwind, RTL-ready Arabic-first UI; PWA manifest so scan stations "install" it on Android tablets/phones.
- **PDF/labels:** server-side rendering via pdf-lib or Puppeteer template; bwip-js for Code 128.
- **Notable choice to challenge in your own thinking:** if you personally are stronger in Laravel or Rails, use it — at MVP, founder velocity beats stack elegance. The architecture above is stack-agnostic.

# 14. Infrastructure Architecture

MVP: keep it boring and cheap (~$100–200/month).

- One region close to Egypt: AWS `eu-south-1` (Milan) or `me-south-1` (Bahrain) — or simpler, **Railway/Render/Fly.io** for app + workers, with managed Postgres (Neon/RDS) and Upstash Redis.
- Containers: `web` (API+frontend), `worker` (BullMQ), `cron` (polling schedulers). Single Dockerfile, two processes.
- Cloudflare in front (TLS, WAF, caching static assets).
- Observability from day one: Sentry (errors), structured JSON logs, uptime monitor, a dead-letter queue dashboard for failed webhooks — integration debugging will be 40% of your support load, instrument for it.
- Daily automated Postgres backups + point-in-time recovery; test a restore before launch (your entire pitch is "trust our ledger").

# 15. Scaling Strategy

The honest math: 500 merchants × 100k pieces × ~10 events each ≈ 5×10⁸ event rows — a single well-indexed Postgres handles this comfortably long before you have 500 merchants.

1. **Phase A (0–50 merchants):** vertical scaling only. Add read replica when dashboards slow writes.
2. **Phase B (50–300):** partition `piece_events` by month; move dashboard aggregates to materialized views refreshed by workers; per-tenant job concurrency caps; extract the courier-sync worker into its own service if polling volume grows (it's already isolated behind the adapter).
3. **Phase C (300+):** read replicas for lookup/timeline traffic; archive terminal pieces' cold events to cheap storage with on-demand rehydration; consider extracting integrations into services; only now revisit anything fancier (Kafka, CQRS projections) — and only if measured pain demands it.
Multi-courier and multi-channel (WooCommerce, Salla) scale horizontally through the adapter interfaces, not through re-architecture.

# 16. Security Design

- **Tenant isolation:** Postgres RLS as the backstop beneath application-level scoping (defense in depth) — covered in §7.
- **Credentials:** Shopify tokens and Bosta API keys encrypted at rest (AES-256-GCM via KMS-managed key), never logged, never sent to the frontend.
- **Webhooks:** Shopify HMAC verification; Bosta signature/secret verification or at minimum per-tenant secret URLs; reject unsigned traffic before parsing.
- **AuthN/Z:** bcrypt/argon2 passwords, short-lived JWT + refresh, PIN-login limited to worker role and scoped permissions, rate-limited; lockout on brute force.
- **Auditability:** `piece_events` is INSERT-only at the DB-grant level — even your app role cannot UPDATE/DELETE custody history; admin actions (user changes, integration changes, adjustments) go to a separate audit table.
- **Data protection:** customer PII (names, phones, addresses) minimized on worker screens; TLS everywhere; backups encrypted. Be aware of Egypt's Personal Data Protection Law (No. 151/2020) — practically: data-processing terms in your merchant contract, deletion on request, breach-notification plan.
- **App hygiene:** input validation at the edge (zod/class-validator), dependency scanning, secrets in a manager not env-committed, least-privilege DB roles, separate prod/staging.

## 16.1 Approved SECURITY DEFINER Escape Hatches

All application writes run under `app_user` + row-level security. The five functions below are the ONLY points where RLS is bypassed. Each has an explicit justification. Any new cross-tenant read or write requires a new named function with the same scrutiny; no bare `BYPASSRLS` connections in application code.

| # | Function | Migration | Justification |
|---|---|---|---|
| 1 | `auth_lookup_user(p_email text)` | V1 | Login requires reading a user row by email before the tenant UUID is known — the caller has no GUC to set. Reads exactly one row from `users`; returns `(user_id, tenant_id, password_hash, role, status)`. |
| 2 | `resolve_tenant_by_shop_domain(p_shop_domain text)` | V1 | OAuth callback and webhook routing must find the tenant that owns a shop domain without prior tenant context. Returns one nullable `uuid`. Read-only. Called before any GUC is set in the OAuth decision tree so RLS cannot blind a cross-tenant collision. |
| 3 | `lookup_refresh_token(p_token_hash text)` | V3 | Token rotation must locate a refresh-token row by hash before the tenant context is established. Returns `(token_id, user_id, tenant_id, expires_at, revoked)`; read-only. |
| 4 | `resolve_tenant_by_webhook_secret(p_secret text)` | V5 | Bosta webhook requests arrive with no tenant context; the per-tenant CSPRNG secret IS the authentication mechanism. The function maps secret → tenant before any GUC can be set. Returns one nullable `uuid`; read-only. |
| 5 | `provision_tenant_from_shopify(p_shop_domain, p_owner_email, p_shop_name, p_timezone, p_access_token_encrypted)` | V14 | Path-2 Shopify-first install creates a new tenant+owner+store atomically. Under `app_user`+RLS this is impossible: `INSERT INTO tenants` requires `GUC = new_tenant_id`, but the UUID is unknown until after the INSERT (chicken-and-egg). DEFINER running as the Flyway owner bootstraps the triple in a single atomic block. A 23505 on the `stores` INSERT rolls back ALL three rows — zero orphan tenants or users. Writes exactly: one `tenants` row, one `users` row (Owner role, no password — magic-link Day 4), one `stores` row (status connected, import_status pending). Nothing else. This is the only writer of the new-tenant bootstrap triple. |
| 6 | `consume_magic_link(p_token_hash text)` | V16 | Magic-link consumption is inherently pre-session: the JWT issued IS the session, so no tenant GUC can exist before the lookup. A `SELECT ... FOR UPDATE` followed by `UPDATE consumed_at` is atomic — concurrent double-consume: the second waits for the first to commit, sees `consumed_at IS NOT NULL`, and returns empty. Returns `(user_id, tenant_id)` on success; empty result for any invalid sub-condition (not-found / expired / consumed) — no oracle, identical to `SHOPIFY_STATE_INVALID` design. `magic_link_tokens` has no app_user SELECT/UPDATE grants; all access goes via this function. `search_path` pinned against DEFINER injection. |

**`EXECUTE` granted to `app_user` only; `REVOKE ALL … FROM PUBLIC` precedes every grant.**

# 17. Competitive Analysis

**Egyptian/MENA landscape:**
- **Flextock, Khazenly** — 3PL fulfillment-as-a-service. They *take* the inventory; you serve brands that keep their own warehouse. Adjacent, not competing; later they could be channel partners or you could power their client-visibility layer.
- **OTO, ShipBlu integrations, Bosta's own dashboard** — shipping aggregation/management. They start at the AWB; they have zero visibility before the parcel exists and none at piece level. Bosta's dashboard is your most common "we already have tracking" objection — answer: Bosta tracks *parcels it carries*; you track *every piece the brand owns, including the 95% of its life Bosta never sees, and the return black hole after Bosta hands it back*.
- **Local ERPs / Odoo implementations** — quantity-based, heavy, consultant-driven, no piece identity, no Bosta-native workflow.

**Global:**
- **Zoho Inventory, Cin7, Katana, Stocky** — SKU-quantity systems; serial-number support exists in some but as an afterthought (manual entry, no custody chain, no courier integration relevant to Egypt, no Arabic, USD pricing).
- **Packiyo / open-source WMS (e.g., for 3PLs)** — closest functionally (scan-driven pick/pack), but 3PL-oriented, no piece-level custody ledger, no Bosta, significant setup burden.

**Your defensible wedge:** the *combination* — piece-level chain of custody (not serial-number bookkeeping) + native Shopify-and-Bosta workflow + COD/RTO-aware Egyptian operations + Arabic worker UX + local pricing. No incumbent holds more than two of those five. The custody ledger also becomes a data moat: once a brand has a year of piece history in your system, switching means amnesia.

---

# 18. Positioning Recommendation

**Position it as an Inventory Traceability Platform** — but sell the pain, not the category.

Why not the alternatives:
- **Inventory Management System** — a commoditized, race-to-the-bottom category ("how many do I have?"). It invites comparison with Zoho at $39/month and erases your differentiation, which is precisely that you answer a question IMS cannot.
- **Warehouse Management System** — signals enterprise heaviness (bins, waves, slotting, ASNs, months of implementation). Your ICP — a 5-person brand warehouse — hears "expensive and complicated," and sophisticated buyers will fault you for lacking real WMS depth you intentionally don't have.
- **Warehouse Execution System** — accurate for your pick/pack layer but jargon nobody in Egyptian D2C uses; you'd spend your marketing budget explaining the term.
- **Supply Chain Visibility Platform** — describes enterprise multi-tier supplier networks (project44 et al.); wrong buyer, wrong scale, wrong promise.

**Inventory Traceability Platform** is correct because it names your actual differentiation (identity + chain of custody + loss attribution), claims a category with no incumbent in your market rather than entering one with many, sets accurate expectations (you trace and execute, you don't optimize warehouses), and scales narratively into the long-term vision ("the source of truth for every physical item a brand owns") without renaming.

One pragmatic caveat: nobody searches Google for "inventory traceability platform." The category label is for your deck, your About page, and analysts. Your homepage, ads, and sales pitch should lead with the pain in the customer's own words: **"اعرف كل قطعة فين" — know where every single piece is, who touched it last, and where your inventory is leaking — from your warehouse shelf to your customer's door and back.** Category for credibility; pain for conversion.

---

*End of blueprint. Recommended next step: pick 2–3 design-partner brands this week, walk them through the receiving + return flows on paper, and let their reactions reorder this scope before a line of code is written.*
