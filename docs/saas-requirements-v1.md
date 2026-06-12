# Full Requirements Specification (v1.0)
## Piece-Level Inventory Traceability Platform — MVP
### For Egyptian D2C brands on Shopify shipping via Bosta

> Status: v1.1 — Draft for design partners & engineering (v1.3: v1.2 + Bosta API verified-facts addendum §8; FR-4.7/9.3/15.3 aligned to verified provider behavior) · Companion to the Product & Architecture Blueprint
> Priorities use MoSCoW: **[M]** Must have (MVP cannot ship without) · **[S]** Should have (MVP target, cuttable under deadline pressure) · **[C]** Could have (build only if trivial) · Phase 2+ items are explicitly out of scope (§12).

---

# 1. Introduction

## 1.1 Purpose
This document defines the complete functional and non-functional requirements for version 1 (MVP) of the platform. It is written to be directly actionable by engineers and verifiable by design partners. Architecture, schema, and roadmap live in the Blueprint document; this document defines **what the system must do**, not how it is built.

## 1.2 Product Summary
A multi-tenant SaaS that assigns a unique barcode identity to every physical piece of inventory a merchant owns and maintains an unbroken, person-attributed chain of custody from warehouse receipt through picking, packing, Bosta shipping, delivery, and returns — integrated natively with Shopify (order source of truth) and Bosta (shipment source of truth).

## 1.3 Definitions
| Term | Meaning |
|---|---|
| Tenant | One merchant company; all data is isolated per tenant |
| Piece | One physical unit with a unique system identity and barcode |
| Custody event | An immutable record: what happened to a piece, when, by whom, where |
| Allocation | The binding of a specific piece to a specific order line at pick time |
| AWB | Air waybill — Bosta's shipping label with tracking barcode |
| Mode A | Platform creates the Bosta delivery at packing time (default) |
| Mode B | Merchant creates deliveries via Bosta's own Shopify plugin; platform ingests and links them |
| RTO | Return to origin — failed delivery returning to the merchant |
| Scan station | Any device (Android phone/tablet/PC) running the web app with an HID barcode scanner or camera |

## 1.4 Actors
- **Owner** — merchant founder/admin. Full access including billing, integrations, user management.
- **Manager** — operations lead. Receiving, returns resolution, adjustments, pickups, exceptions, worker management. No billing, no integration credentials.
- **Worker** — picker/packer. Scan-driven queues only. No costs, no exports, no adjustments.
- **System** — automated actor for webhook/poll-driven events (attributed as "System" in custody history).

---

# 2. Functional Requirements

## FR-1 — Tenant Onboarding & Account

**FR-1.1 [M]** A visitor can create a tenant account with: business name, owner name, email, phone, password. Email or phone verification **[S]**.
**FR-1.2 [M]** On first login, the system presents a guided setup checklist: ① connect Shopify ② connect Bosta ③ import catalog & orders ④ print a test label ⑤ receive first inventory. Each step shows done/pending state.
**FR-1.3 [M]** A default location "Main Warehouse" is created automatically for every new tenant. (Single location in MVP; the field exists on all records.)
**FR-1.4 [M]** Tenant settings page: business name, default pickup address (synced to/from Bosta where possible), label size preference (40×25 / 50×25 mm), language default (AR/EN), timezone (default Africa/Cairo).
**FR-1.5 [S]** Subscription plan display and manual invoicing status (no online payment processing in MVP — payment collected via Instapay/bank transfer, plan toggled by internal admin).
**FR-1.6 [M]** Internal super-admin panel (for the founding team only): list tenants, activate/suspend, impersonate for support (impersonation is logged).

*Acceptance:* a new merchant completes steps ①–⑤ in under 30 minutes without human help.

## FR-2 — Authentication, Users & Permissions

**FR-2.1 [M]** Email + password login with secure password storage; session via short-lived access token + refresh token; logout everywhere.
**FR-2.2 [M]** Owner/Manager can create users with role Worker or Manager (Owner creation reserved to Owner). Users can be deactivated, never hard-deleted (custody history references them).
**FR-2.3 [M]** **Worker PIN login:** each worker has a personal 4–6 digit PIN. On a shared scan station, a worker switches identity by entering their PIN (or scanning a personal badge barcode **[C]**). All subsequent scans are attributed to that worker until switch or 15-minute idle timeout.
**FR-2.4 [M]** Shared/anonymous accounts must be impossible: every custody-affecting action requires an identified user. PINs are unique per tenant; 5 failed attempts locks the PIN for 15 minutes and notifies the Manager.
**FR-2.5 [M]** Permission matrix enforced server-side on every endpoint:

| Capability | Owner | Manager | Worker |
|---|---|---|---|
| Connect/modify integrations | ✔ | ✖ | ✖ |
| Manage users | ✔ | Workers only | ✖ |
| Receive inventory & print labels | ✔ | ✔ | ✖* |
| Confirm orders | ✔ | ✔ | ✖ |
| Pick / pack / scan | ✔ | ✔ | ✔ |
| Create pickups | ✔ | ✔ | ✖ |
| Returns intake scan | ✔ | ✔ | ✔ |
| Returns resolution (restock/damaged) | ✔ | ✔ | ✖ |
| Manual adjustments (lost/damaged/destroyed) | ✔ | ✔ (reason required) | ✖ |
| Piece lookup & timeline | ✔ | ✔ | read-only, no customer PII |
| Dashboards & exceptions | ✔ | ✔ | queue counts only |
| Exports | ✔ | ✔ | ✖ |
| Billing | ✔ | ✖ | ✖ |

*\*Worker receiving permission is a per-tenant toggle **[S]** — some merchants want workers to receive, others restrict it.*

**FR-2.6 [M]** Audit: every privileged action (user changes, integration changes, adjustments, impersonation) is recorded with actor, timestamp, and before/after values.

## FR-3 — Shopify Integration

**FR-3.1 [M]** Connect via custom-app credentials (merchant pastes Admin API token + shop domain); the system validates by fetching shop info. Public OAuth app **[S]** (parallel track, required for App Store distribution).
**FR-3.2 [M]** Initial import on connect: all active products and variants (title, SKU, price, options, Shopify IDs, image URL **[S]**); orders from the last 90 days with line items, customer name, phone, shipping address, payment method (COD/prepaid), financial status, tags, and fulfillment status. Import is resumable and idempotent; progress is visible to the merchant.
**FR-3.3 [M]** Webhooks subscribed and processed: `orders/create`, `orders/updated`, `orders/cancelled`, `products/create`, `products/update`. All inbound payloads verified (HMAC), persisted raw before processing, acknowledged immediately, processed asynchronously, idempotent on redelivery.
**FR-3.4 [M]** Reconciliation poll every 15 minutes for orders/products updated since last sync — a missed webhook may delay data by minutes, never lose it.
**FR-3.5 [M]** Order ingestion rules: imported orders enter status `New`. Cancellation webhook on an order that is not yet `Packed` releases all allocations (pieces → Available with `unreserved` events) and sets order `Cancelled`. Cancellation after `Packed` raises an exception requiring guided unpack (FR-9.8).
**FR-3.6 [M]** Line-item edits on a `Picking`-stage order release affected allocations and flag the order in exceptions with a clear diff of what changed.
**FR-3.7 [M]** Confirmation tag mapping: tenant can define Shopify tags that drive confirmation (e.g., tag `confirmed` → order `Confirmed`; tag `cancelled-by-cs` → cancel). This lets merchants keep their existing OTP/call-center apps as the confirmation source.
**FR-3.8 [S]** Write-back of fulfillment: when an order is packed and the AWB exists, mark the Shopify order fulfilled with the Bosta tracking number and tracking URL. Per-tenant toggle, default ON. (No inventory-quantity write-back in MVP.)
**FR-3.9 [M]** Variant mapping integrity: pieces reference variants by internal ID; if a variant is deleted in Shopify, existing pieces and history remain intact and the variant is marked archived.

## FR-4 — Bosta Integration

**FR-4.1 [M]** Connect by pasting the Bosta API key; system validates by fetching the business profile; key stored encrypted; capture default pickup location(s) from Bosta.
**FR-4.2 [M] Mode A — delivery creation at packing:** on pack completion (FR-9), create a Bosta forward/package delivery with consignee name, phone, mapped city/zone, address lines, COD amount (for COD orders; zero/prepaid otherwise), item count, and the order number as merchant reference. Store tracking number, provider delivery ID, and raw response. Retrieve the AWB PDF and present it for printing.
**FR-4.3 [M]** City/zone mapping: maintain a mapping table from free-text Shopify governorate/city values to Bosta city & zone codes, pre-seeded for all 27 governorates and common spellings (Arabic and Latin). When mapping fails, packing is blocked at the address step with a dropdown UI to pick the correct Bosta city/zone; the choice is remembered as a new mapping rule for that tenant **[M]** and proposed globally **[C]**.
**FR-4.4 [M] Mode B — ingest externally created deliveries:** detect Bosta deliveries created outside the platform (via Bosta's Shopify plugin or dashboard) by polling/webhook, match them to Shopify orders by merchant reference / order number / phone+COD heuristics, and attach them as the order's shipment. Unmatched deliveries appear in an "unlinked shipments" list for manual matching. In Mode B, packing links pieces to the existing AWB via the verification scan (FR-9.6) instead of creating a delivery.
**FR-4.5 [M]** Per-tenant mode setting: Mode A (default) / Mode B / Hybrid (Mode A unless a delivery already exists for the order).
**FR-4.6 [M]** Delivery cancellation: cancelling a packed order triggers Bosta delivery cancellation via API where the provider state allows it; otherwise raises an exception with instructions.
**FR-4.7 [M]** Status synchronization — *aligned to verified Bosta behavior (see §8 addendum):* consume Bosta webhooks (account-level URL or per-delivery `webhookUrl`) **and** poll all non-terminal shipments every 15–30 minutes. Bosta webhooks carry **no HMAC signature** — security model: per-tenant secret in a custom Authorization header (Bosta supports custom headers) + every webhook is treated as an untrusted *hint*: on receipt, fetch the delivery from Bosta's API and act on the fetched state, never on the raw webhook body alone. Bosta webhooks fire **only on state changes, never on creation** — therefore Mode B detection of new plugin-created deliveries is polling-only by design. Map numeric provider state codes to internal states via the versioned mapping table (§8). Unknown codes map to `exception` and alert the internal team. Every status change writes `courier_update` custody events on all pieces in the shipment; store the raw payload; persist `numberOfAttempts` from the payload as the shipment's attempt counter. Treat `trackingNumber` as a string everywhere regardless of JSON type.
**FR-4.8 [M]** Pickup creation per FR-10.
**FR-4.9 [M]** All Bosta API failures are retried with exponential backoff; after final failure the affected order/shipment enters the exception list with the error surfaced in plain language (Arabic/English), never a raw stack trace.

## FR-5 — Catalog

**FR-5.1 [M]** Browse/search products and variants (by title, SKU, Shopify barcode); show per-variant piece counts by status (Available / Reserved / Packed / With Courier / etc.).
**FR-5.2 [M]** Variant detail page: identity info + list of its pieces with status and last-event time, filterable by status.
**FR-5.3 [S]** Manual product/variant creation for items not in Shopify (e.g., packaging inserts the merchant wants to track). Flagged as "local" and excluded from Shopify sync.
**FR-5.4 [C]** Low-stock indicator per variant (threshold set by merchant) — display only, no purchasing workflow.

## FR-6 — Inventory Receiving & Labeling

**FR-6.1 [M]** Create a receiving session: select location (default Main Warehouse), optional supplier name, optional reference (PO/invoice no.), optional note.
**FR-6.2 [M]** Within a session, add lines: variant (searchable by SKU/title/scanning the manufacturer UPC **[S]**) × quantity. Multiple variants per session. Editable until the session is finalized.
**FR-6.3 [M]** On finalize: the system generates one piece per unit with a globally unique, time-sortable ID and barcode value (prefix `PC-`), status `Available`, location = session location, and a `received` custody event attributed to the receiving user. Finalization of 1,000 pieces completes in ≤ 10 seconds.
**FR-6.4 [M]** Label generation: a print-ready PDF for the session (and for any subset/range) sized to the tenant's label stock; each label contains Code 128 barcode, human-readable piece ID, SKU, and variant short name (≤ 24 chars, auto-truncated). Labels print correctly on common thermal printers (Xprinter/Zebra-class) via standard OS drivers at 203 dpi.
**FR-6.5 [M]** Reprint: any label or any session's labels can be reprinted at any time (lost/damaged labels). Reprints are logged.
**FR-6.6 [M]** Receiving speed target: from session creation to labels printing, ≤ 60 seconds of system interaction for a 200-piece session (excluding physical labeling time).
**FR-6.7 [S]** Quantity correction: before any piece in a session has left `Available`, a Manager can void surplus pieces (status `Destroyed`, reason `receiving_correction`) or append additional pieces to the session.
**FR-6.8 [M]** A piece scanned anywhere in the system before its label was supposedly applied still resolves — the barcode is live from finalization (no "activation" step; activation adds friction without value).

## FR-7 — Order Management & Confirmation

**FR-7.1 [M]** Orders list with status pipeline filters: New / Confirmed / Ready to Pick / Picking / Packed / Awaiting Pickup / Self-Pickup Pending / With Courier / Delivered / Returning / Returned / Lost / Cancelled. Search by order number, customer name, phone, tracking number.
**FR-7.2 [M]** Order detail: customer info, line items with variant images **[S]**, payment method and COD amount, status timeline, allocated pieces (each linking to its piece page), shipment info (tracking number, provider state, AWB reprint), and the full audit of who did what to this order.
**FR-7.3 [M]** Confirmation mode (per-tenant setting):
 (a) **Auto-flow (default):** every imported order moves `New → Ready to Pick` automatically once it passes the order-entry validation gates (FR-7.8) — no human confirmation step. Acceptance is delegated to the doorstep: the courier confirms with the customer at delivery, refusals come back as RTOs and are handled by the returns flow (FR-12). This is the standard flow for merchants who ship everything immediately.
 (b) **Gated:** orders require explicit confirmation — manual Confirm button (Owner/Manager) and/or a Shopify tag rule (FR-3.7) — before entering `Ready to Pick`. For merchants running pre-ship confirmation calls or OTP apps.
 In both modes, prepaid orders may auto-confirm regardless (toggle, default ON).
**FR-7.8 [M] Order-entry validation gates** (run on every imported order, in both modes, before it can reach `Ready to Pick`):
 (a) **Blocked customer:** the order's customer phone (normalized: 010/0020/+20 variants treated as equal) is checked against the tenant's blocklist (FR-7.9). A match holds the order in a `Blocked Customer` exception with the block reason shown; Owner/Manager can release (ship anyway, logged) or cancel.
 (b) **Address mappability:** the shipping address is validated against the Bosta city/zone mapping (FR-4.3) at entry, not at packing. Unmappable addresses hold the order in an `Address Review` exception with the fix-and-remember UI; on resolution the order proceeds automatically. *(FR-4.3's packing-time check remains as a final safety net.)*
 (c) If Bosta rejects delivery creation at packing for a consignee-blocked reason despite the gates, the order moves to the `Blocked Customer` exception (not a generic error) and the phone is offered for addition to the local blocklist with source `bosta_rejected`.
**FR-7.9 [M] Blocklist management** (Owner/Manager, in settings): add/remove blocked phone numbers with reason and source (manual / imported CSV **[S]** / bosta_rejected); every block/unblock is logged; the list is checked in ≤ 50 ms at order entry. *(Whether Bosta's API exposes a queryable blocked-consignee check is an open integration question — see §7; if it exists, it becomes gate (a)'s second source.)*
**FR-7.4 [M]** Hold/unhold: any pre-packed order can be put On Hold with a reason; held orders leave the pick queue.
**FR-7.5 [M]** COD amount displayed prominently and editable until packing (edits logged); after packing, COD changes require shipment cancellation + repack (the AWB carries the COD).
**FR-7.6 [S]** Bulk actions on the list: confirm, hold, move to pickup batch.
**FR-7.7 [M]** Partial fulfillment is **not** supported in MVP: an order ships complete or not at all. If a line cannot be fully allocated (insufficient available pieces), the order is flagged `short` in exceptions with the missing variant/quantity. (Splitting/partials are Phase 2.)

## FR-8 — Picking

**FR-8.1 [M]** Pick queue (Worker home screen): orders in `Ready to Pick`, oldest first, showing order number, line summary, and piece availability indicator. Tapping an order starts picking (`→ Picking`, locks the order to that worker; a Manager can release the lock).
**FR-8.2 [M]** Pick screen shows each line: variant name, image **[S]**, quantity required, quantity scanned. The worker scans piece barcodes; every scan is validated server-side in ≤ 300 ms round-trip and answered with unmistakable feedback (full-screen green flash + beep on success; red + distinct error sound + Arabic/English reason on failure).
**FR-8.3 [M]** Scan validation rules — a scan is rejected with a specific reason code when the barcode: is unknown (`PIECE_NOT_FOUND`); belongs to a different variant than any open line (`WRONG_VARIANT`, shows what it actually is); is not `Available` (`ALREADY_RESERVED` / `WRONG_STATUS`, shows current status and, if reserved, for which order); is already scanned for this order (`DUPLICATE_SCAN`); belongs to another tenant (indistinguishable from `PIECE_NOT_FOUND`).
**FR-8.4 [M]** A successful scan atomically: sets piece `Available → Reserved`, creates the allocation to the order line, writes a `reserved` custody event with worker/time/location. Concurrent scans of the same piece from two stations: exactly one wins; the other gets `ALREADY_RESERVED`.
**FR-8.5 [M]** Un-scan: the worker can remove a scanned piece (mis-pick) → allocation released, piece `→ Available`, `unreserved` event.
**FR-8.6 [M]** When all lines are fully scanned, the worker completes picking → order `→ Ready to Pack` (which may be the same physical moment as packing; see FR-9.1). An order cannot complete picking with missing scans.
**FR-8.7 [M] Gather List (pre-pick screen):** before processing orders, the worker (or Manager) selects a set of orders in `Ready to Pick` (default: all, or the N oldest) and the system generates a consolidated gather list: each variant with image **[S]**, SKU, and **total quantity needed across the selected orders**, plus the list of order numbers per variant. The worker uses this to collect everything from the shelves in one pass, then proceeds to per-order scanning (FR-8.2) where pieces are assigned to specific orders. The gather list is printable **[S]** and live-updating: quantities decrement as pieces are scanned into orders, so the worker always sees what remains ungathered.
**FR-8.7a [M]** Orders included in an active gather list are locked to that wave (status `Picking`, lock owner = the wave) so two workers don't gather the same demand twice; a Manager can release a wave.
**FR-8.7b [M]** Insufficient availability is flagged on the gather list itself (variant shows "need 5, available 3") before the worker walks the shelves — short orders drop out of the wave into exceptions per FR-7.7.
**FR-8.7c [C]** FIFO suggestion: the list can show the oldest received pieces' IDs per variant as a suggestion. *(Without bin locations — Phase 2 — the system cannot direct the worker to a shelf position; any available piece of the correct variant is valid at scan time.)*
**FR-8.8 [M]** No keyboard entry of barcodes in normal flow; manual entry exists behind a Manager-permission fallback (damaged barcode) and is flagged in the custody event as `manual_entry: true`.

## FR-9 — Packing, AWB & Tracking Linkage

**FR-9.1 [M]** Pick and pack may be performed as one continuous flow by the same worker (per-tenant toggle "single-step fulfillment", default ON for small teams) or as separate stations.
**FR-9.2 [M]** Pack screen: shows the order's allocated pieces. The packer **re-scans every piece** going into the parcel; the system verifies the scanned set exactly equals the allocated set (catches swapped items between pick and pack). Mismatch → blocking error naming the discrepancy.
**FR-9.3 [M]** On verification success, COD amount and destination are displayed for a final glance; the packer confirms → **Mode A:** system creates the Bosta delivery (FR-4.2), receives tracking number, renders the AWB for printing. **Mode B:** system prompts for the AWB verification scan (FR-9.6) of the pre-existing label. COD validation before any Bosta call: amount must be ≤ 30,000 EGP (Bosta's hard cap, error 3007) — violations block with a plain-language message at confirmation/packing, not as a raw provider error.
**FR-9.4 [M]** Pieces `Reserved → Packed` with `packed` custody events; order `→ Packed`; allocations marked packed.
**FR-9.5 [M]** AWB printing: opens the Bosta AWB PDF print dialog automatically; reprint available from the order page. If AWB retrieval fails, the order is `Packed` with a `missing AWB` exception and a retry button — packing is never silently lost because Bosta hiccuped.
**FR-9.6 [M]** **AWB verification scan:** the packer scans the tracking barcode on the printed AWB before sealing. The system verifies it matches this order's shipment (Mode A) or links the ingested shipment (Mode B) and writes `tracking_linked` events binding piece ↔ order ↔ tracking number. A mismatched AWB (swapped labels across two parcels) is rejected loudly. Per-tenant toggle to make this step optional **[S]** — default mandatory.
**FR-9.7 [M]** After verification, order `→ Awaiting Pickup`.
**FR-9.8 [M]** Guided unpack (cancellation after packing): step-by-step flow — cancel Bosta delivery (FR-4.6) → rescan each piece out of the parcel → pieces `→ Available` (`unreserved` events) → order `→ Cancelled`. Cannot be completed partially.
**FR-9.9 [M] Pre-handover shipment cancellation:** at any point after AWB creation and **before** the first courier-collection status (order in `Packed` or `Awaiting Pickup`), an Owner/Manager can cancel the Bosta shipment from the order page with one of two outcomes, chosen explicitly:
 (a) **Cancel order** — runs the guided unpack (FR-9.8); pieces return to `Available`; order `Cancelled`.
 (b) **Convert to self-pickup** — the sale stands; only the shipping changes. Bosta delivery is cancelled via API; order moves to a `Self-Pickup Pending` state and leaves all courier flows; pieces remain `Packed` at the warehouse with a `converted_to_self_pickup` event (actor + mandatory reason note, e.g., "customer will collect").
**FR-9.10 [M] Self-pickup handover:** when the customer collects, the staff member scans each piece in the parcel (or the AWB if still attached) to confirm contents, records the handover → pieces `→ Delivered` with event `customer_pickup` (actor = staff member, location = warehouse), order `→ Delivered (self-pickup)`. COD orders display the amount to collect; the user confirms cash received (logged) **[M]**. Custody thus remains attributed through the final handover, exactly as it would with a courier.
**FR-9.11 [M]** If the shipment was already included in a created Bosta pickup (FR-10), cancellation removes it from the pickup (API where supported, otherwise the pickup manifest regenerates and the discrepancy is noted) and the pickup's expected COD total updates.
**FR-9.12 [M]** If Bosta's state no longer permits cancellation (courier already collected per provider data), the action is blocked with a clear explanation: the parcel must complete the courier journey or come back as an RTO — the system never pretends a parcel in courier custody is at the warehouse.
**FR-9.13 [S]** Self-pickup no-show: orders in `Self-Pickup Pending` for more than Z days (default 7) surface in exceptions with actions: convert back to shipping (creates a fresh Bosta delivery — new AWB, since the old one is void) or cancel order (guided unpack).

## FR-10 — Pickup Management

**FR-10.1 [M]** Pickup creation: Manager selects orders in `Awaiting Pickup` (select-all by default), chooses pickup date and pickup location, system calls Bosta's pickup API and records the pickup with its shipments.
**FR-10.2 [M]** Pickup manifest: printable list (order no., tracking no., COD amount, piece count) for driver handover; total COD expected for the batch.
**FR-10.3 [M]** Courier collection is detected from the first "picked up"-class status from Bosta per shipment: order and pieces `→ With Courier`, custody event `handed_to_courier` (actor System). Manual "mark collected" fallback per shipment **[M]** for the day Bosta statuses lag, attributed to the user who clicked.
**FR-10.4 [S]** Discrepancy surfacing: shipments in a pickup that show no collection status by end of day +1 appear in exceptions ("courier didn't take these?").

## FR-11 — Shipment Lifecycle & Status Sync

**FR-11.1 [M]** Internal shipment states and Bosta mapping per Blueprint §9 table; every transition updates order status, piece statuses, and writes custody events with the raw provider state in metadata.
**FR-11.2 [M]** Terminal handling: Delivered → pieces `Delivered` (terminal), order `Delivered`. Lost → pieces `Lost`, order `Lost`, exception raised. Returned-to-origin → pieces `Return Pending Inspection`, order `Returned`, returns intake required (FR-12).
**FR-11.3 [M]** Delivery-failure attempts (rescheduled/failed) keep pieces `With Courier`, increment an attempts counter on the shipment, and surface in exceptions after N failed attempts (default 2, configurable).
**FR-11.4 [M]** Customer-facing nothing: MVP sends no notifications to end customers (Shopify/Bosta already do). All sync is merchant-facing.
**FR-11.5 [M]** Stuck-shipment detection: any non-terminal shipment with no provider update for X days (default 5) appears in exceptions.

## FR-12 — Returns Intake & Resolution

**FR-12.1 [M]** Returns intake screen: worker scans any piece barcode on an arriving RTO parcel. The system recognizes the piece, shows its order/shipment context, and confirms intake: piece `Return In Transit`/`Return Pending Inspection` → `Return Pending Inspection` at the scanning location, custody event `return_received` (this is the moment custody passes from Bosta back to the merchant — timestamped and attributed).
**FR-12.2 [M]** If the scanned piece's shipment was not in a returning state, intake still proceeds but raises a `unexpected return` exception (Bosta status lag or mis-scan — both worth seeing).
**FR-12.3 [M]** Resolution (Manager): for each piece pending inspection — **Restock** (piece `→ Available`, event `restocked`) or **Damaged** (piece `→ Damaged`, terminal, mandatory reason/note, photo upload **[S]**).
**FR-12.4 [M]** Missing-from-return detection: when all of a returned shipment's pieces are not intaken within Y days of RTO status (default 3), the un-scanned pieces appear in exceptions as `returned by courier but never received` — this is the black-hole report and a headline feature; it must be prominent.
**FR-12.5 [M]** A restocked piece keeps its identity and label; its full history (shipped, returned, restocked) remains one continuous timeline. If the label was removed by the customer, Manager reprints it (FR-6.5).
**FR-12.6 [C]** Customer-initiated returns/exchanges workflow — out of scope; RTO only in MVP. An exchange is handled as: RTO intake + a new Shopify order.

## FR-13 — Manual Adjustments

**FR-13.1 [M]** Owner/Manager can transition a piece to `Lost`, `Damaged`, or `Destroyed` from its piece page, with a mandatory reason from a fixed list (cycle count missing, damaged in storage, sample/giveaway, theft suspected, receiving correction, other+note). Custody event `adjusted` records actor, reason, from/to.
**FR-13.2 [M]** A piece in `Reserved`/`Packed` cannot be adjusted without first releasing it from its order (guarded flow explaining the consequence).
**FR-13.3 [M]** Reverse adjustment ("found it"): `Lost → Available` allowed with reason; the timeline keeps both events — history is never rewritten.
**FR-13.4 [S]** Bulk adjustment by scanning a series of pieces into an adjustment session (e.g., flood-damaged shelf).

## FR-14 — Piece Lookup & Chain of Custody (the showcase feature)

**FR-14.1 [M]** Global lookup: scanning or typing any piece barcode anywhere in the app (persistent search field; dedicated full-screen lookup mode for demos) opens the piece page in ≤ 1 second.
**FR-14.2 [M]** Piece page shows: product/variant + image **[S]**, current status, current location, current/last order with link, shipment/tracking with link to Bosta tracking, receiving session origin, and the **full custody timeline** — every event with timestamp, actor (name, or "System"), action, location, and contextual detail (order no., tracking no., provider state, reason).
**FR-14.3 [M]** The timeline is rendered newest-first, in the viewer's language, with human phrasing ("Reserved for order #1042 by Ahmed — Main Warehouse — 14:32") not raw event types.
**FR-14.4 [M]** Scanning an **AWB barcode** in global lookup resolves to the shipment/order page listing its pieces (the lookup handles both barcode namespaces transparently).
**FR-14.5 [M]** From any order: jump to each piece's timeline; from any piece: jump to its order/shipment. The custody graph is fully navigable in both directions.
**FR-14.6 [S]** Export a piece's timeline as PDF (dispute evidence with couriers/employees).

## FR-15 — Dashboards, Queues & Exceptions

**FR-15.1 [M]** Inventory summary: piece counts by status (Available, Reserved, Packed, Awaiting Pickup, With Courier, Delivered (last 30d), Return Pending, Damaged (30d), Lost (30d)) with drill-down to filtered piece lists; per-variant breakdown via catalog (FR-5.1).
**FR-15.2 [M]** Fulfillment board: counts and lists for Ready to Pick / Picking / Ready to Pack / Packed / Awaiting Pickup, with order age highlighting (>24h amber, >48h red).
**FR-15.3 [M]** Exceptions center — single prioritized list, each row with a one-line plain-language description and a resolving action: lost shipments · returns never received (FR-12.4) · unexpected returns · failed-delivery threshold · stuck shipments · **Bosta "awaiting your action" (state 103 — return failed 3×, inventory in limbo at Bosta's hub)** · **return-exception evidence (Bosta NDR codes 26–30: damaged / empty order / incomplete / doesn't belong — attached to the affected shipment and its never-received entries)** · short orders (FR-7.7) · blocked-customer holds (FR-7.8a) · address review (FR-7.8b) · cancellation-after-pack pending unpack · self-pickup no-shows (FR-9.13) · missing AWB · unlinked Mode-B shipments · Shopify edit conflicts. Resolved exceptions are archived with resolver and timestamp.
**FR-15.4 [S]** Daily email/WhatsApp digest to Owner: yesterday's shipped/delivered/returned counts + open exception count. *(WhatsApp via simple template provider; cut to email-only under pressure.)*
**FR-15.5 [C]** CSV export of pieces and orders lists.

## FR-16 — Localization & UX Baseline (functional)

**FR-16.1 [M]** Full Arabic and English UI with per-user language; Arabic is RTL-correct throughout, including the scan screens and printed manifests. Label PDFs render Arabic variant names correctly.
**FR-16.2 [M]** Worker-facing screens are operable one-handed on a 5–6.5″ Android phone: large tap targets, no horizontal scrolling, scan field auto-focused, next action always obvious.
**FR-16.3 [M]** All scan feedback includes audio (distinct success/failure tones) and works with device volume — warehouses are loud and workers don't read while scanning.
**FR-16.4 [M]** Currency display: EGP everywhere, Arabic-Indic numerals optional per user **[C]**.

---

# 3. Non-Functional Requirements

## NFR-1 Performance
- Scan validation round-trip (request → validated response): **p95 ≤ 300 ms** on Egyptian mobile networks; UI feedback rendered ≤ 100 ms after response.
- Piece page (with full timeline ≤ 200 events): ≤ 1 s load.
- Receiving finalization: 1,000 pieces ≤ 10 s; label PDF for 500 pieces ≤ 15 s.
- Order list / queues: ≤ 1.5 s for tenants with 100k pieces and 50k orders.
- Shopify initial import: 5,000 products + 10,000 orders completes ≤ 30 min, backgrounded with progress.

## NFR-2 Reliability & Data Integrity
- **Zero custody loss:** every status transition and its custody event commit in one ACID transaction; a piece can never be in a state with no corresponding event.
- Custody events are immutable at the database-privilege level (no UPDATE/DELETE grants to the application role).
- All inbound webhooks are persisted raw before processing and replayable; processing is idempotent.
- Integration outage behavior: Bosta/Shopify downtime degrades gracefully — local operations (receiving, picking, lookup) continue; queued external calls retry with backoff; affected items surface as exceptions, never silently dropped.
- Target availability 99.5% monthly (single-region acceptable at MVP); planned maintenance outside 09:00–21:00 Cairo.
- RPO ≤ 24 h (daily backups) with point-in-time recovery; restore procedure tested before first paying tenant.

## NFR-3 Security & Privacy
- Tenant isolation enforced at the database layer (RLS) in addition to application scoping; cross-tenant access must be impossible by construction, verified by automated tests.
- Integration credentials encrypted at rest (envelope encryption); never logged; never returned to any client.
- Webhook endpoints verify signatures before parsing; unauthenticated traffic rate-limited and dropped.
- Passwords argon2/bcrypt; tokens short-lived; PIN brute-force lockout per FR-2.4.
- Worker role never sees customer phone/address except the packing destination check; exports restricted per FR-2.5 matrix.
- Compliance posture: Egypt PDPL (Law 151/2020) — data-processing clause in merchant ToS, deletion-on-request procedure, breach-notification plan. Shopify mandatory privacy webhooks honored (data erasure).
- All admin/support impersonation logged and visible to the tenant Owner **[S]**.

## NFR-4 Compatibility
- Browsers: current Chrome/Safari on Android 10+ and iOS 15+, desktop Chrome/Edge/Safari; PWA-installable on Android scan stations.
- Scanners: any HID keyboard-wedge barcode scanner (USB/Bluetooth) with configurable suffix (Enter); camera scanning fallback via the browser.
- Printers: 203 dpi thermal printers via OS print dialog (no proprietary SDK); A4 fallback layout for AWB and manifests.
- Label stock: 40×25 mm and 50×25 mm presets.

## NFR-5 Operability
- Centralized error tracking and structured logs with tenant/order/piece correlation IDs.
- Dead-letter visibility: failed webhook/integration jobs visible to the internal team with one-click retry.
- Feature flags per tenant (mode A/B, single-step fulfillment, AWB verification mandatory, worker receiving).
- Seed/demo tenant with fake data for sales demos, resettable in one action.

## NFR-6 Quality gates
- Automated tests required for: the piece status machine (every legal/illegal transition), scan-race concurrency (double-pick), webhook idempotency, RLS isolation, and the Bosta state mapping.
- Staging environment wired to Shopify dev store + Bosta staging (or sandboxed mock) before any production tenant.

---

# 4. Screen Inventory (MVP — 24 screens)

**Auth/Setup (4):** Sign up · Login · PIN switch (scan-station) · Onboarding checklist
**Settings (5):** Tenant settings · Blocked customers (FR-7.9) · Users & PINs · Shopify connection · Bosta connection (incl. mode A/B, address-mapping table)
**Catalog (2):** Products/variants list · Variant detail (pieces by status)
**Receiving (3):** Sessions list · Session create/edit (lines) · Label print/reprint
**Fulfillment (6):** Orders list (pipeline filters) · Order detail (incl. cancel/self-pickup actions) · **Gather list (pick wave)** · Pick screen · Pack/AWB screen (incl. unpack & self-pickup handover flows) · Pickup creation + manifest
**Returns (1):** Intake & resolution
**Visibility (3):** Inventory summary · Exceptions center · Piece lookup/timeline (+ shipment lookup view)

---

# 5. Go-Live Acceptance Criteria (pilot success definition)

The MVP is accepted when, for both design-partner tenants, over a continuous 30-day period:

1. 100% of newly received inventory is labeled through the system (verified against supplier invoices).
2. ≥ 95% of shipped orders completed the full scan path (pick scan + pack verification + AWB verification); the remainder are explained exceptions.
3. Every shipped piece is queryable: barcode → complete timeline including courier states.
4. All RTOs in the period were intaken by scan, and the "returned but never received" report correctly identified any gap.
5. A physical cycle count of ≥ 300 randomly selected pieces matches system status/location ≥ 99%.
6. Zero cross-tenant data incidents; zero custody events lost or mutated.
7. Median receiving rate ≥ 150 pieces/labeled hour; median pick+pack ≤ 4 minutes/order.
8. Both merchants convert to paying status.

---

# 6. Explicitly Out of Scope (MVP)

Multi-location & transfers (Phase 2) · vendor/external custody (Phase 3) · partial fulfillment & order splitting · customer-initiated returns/exchanges workflow · Shopify inventory-quantity write-back · purchase orders & supplier management · cycle-count module (manual count + adjustments suffice) · employee productivity analytics · shrinkage analytics engine · native mobile app (PWA only) · couriers other than Bosta (adapter interface only) · sales channels other than Shopify · online subscription billing · customer notifications · barcode scales/serialized manufacturer integration · multi-currency.

---

# 7. Open Questions (to resolve with design partners before build freeze)

1. ~~Plugin or dashboard?~~ **RESOLVED: both pilots use the Bosta Shopify plugin → Mode B is the launch mode.** Mode A becomes the week 5–8 upgrade.
2. Bosta API — remaining spike items (webhooks, states, COD cap, address codes now VERIFIED — see §8): blocked-consignee query vs rejection-only? Which states permit terminate/cancel? Can a shipment be removed from a created pickup? Exact delivery-object response fields for Mode B matching? Start the IP-whitelisting support ticket immediately (Bosta requires it; the VPS needs a static IP).
3. Label size their shelves/products tolerate — 40×25 or 50×25? Any items too small to label (jewelry) → polybag-labeling convention to document.
4. Single-step fulfillment (one worker picks+packs) or split stations? (Sets the default for FR-9.1.)
5. Do they ship any non-Bosta volume today via *other couriers*? (Customer self-pickup is now in scope per FR-9.9–9.13; other-courier shipping remains out of scope — if material, it becomes a "shipped externally" terminal action **[S]**.)
6. ~~Exchange handling?~~ **RESOLVED: pilots handle exchanges/refunds manually → no native EXCHANGE/CRP support needed at launch.** Manual flow maps to existing requirements: old piece returns via RTO intake (FR-12), replacement ships as a new order. Sync mapper still logs any EXCHANGE/CRP-type delivery it encounters as an unlinked-shipment exception (defensive, 2 lines).

---

# 8. Addendum — Bosta API: Verified Facts (from official docs, docs.bosta.co)

*Read directly from Bosta's documentation; these replace the corresponding assumptions. Anything not listed here remains an open spike item (§7 Q2).*

## 8.1 Endpoints & order types
- Create delivery: `POST https://app.bosta.co/api/v2/deliveries?apiVersion=1` · bulk endpoint: `/deliveries/bulk?apiVersion=1`.
- Order type codes: **Deliver = 10**, **Cash Collection = 15**, **CRP (Customer Return Pickup) = 25**, **Exchange = 30**. Webhook `type` values: `SEND | EXCHANGE | CUSTOMER_RETURN_PICKUP | RTO | SIGN_AND_RETURN | FXF_SEND`.
- `packageDetails` = `{ description: string, itemsCount: number }` — **no structured line items exist in Bosta**. Shopify is the only order-content source (final confirmation of the architecture decision).
- Addresses require `city`, `zoneId`, or `districtId` (errors 3001–3004, 3009); official downloadable zoning sheets exist → seed FR-4.3's mapping table from them.
- **COD hard cap: 30,000 EGP** (error 3007) → validated locally per FR-9.3.
- `businessLocationId` selects pickup/return location per delivery (multi-pickup-location support exists provider-side).
- FlexShip option exists (fee charged only on doorstep refusal; requires "Allow Open Package") — surface as a per-tenant Mode A toggle in Phase 1.5, not MVP.

## 8.2 Webhooks (the real behavior)
- Fire **only on state change — never on creation**. Mode B detection of new deliveries is **polling-only**.
- **No HMAC.** Optional custom Authorization header (name+value pair) → our model: per-tenant secret header + verify-by-fetch before acting (FR-4.7).
- Payload fields: `_id, trackingNumber, state (numeric), type, cod (Delivered only), timeStamp, isConfirmedDelivery, deliveryPromiseDate, exceptionReason, exceptionCode, businessReference, numberOfAttempts`.
- `numberOfAttempts` arrives in every payload → FR-11.3's counter is read, not derived. `isConfirmedDelivery` = proof-of-delivery flag → store on shipment.
- `trackingNumber` may arrive as a JSON number → **always store as string**.

## 8.3 Verified state table (seeds the FR-11.1 mapping, keyed by numeric code)
| Code | Bosta state | Internal shipment | Piece status |
|---|---|---|---|
| 10 | Pickup requested | created | packed / awaiting_pickup |
| 20 | Route assigned | created | awaiting_pickup |
| 21 | Picked up from business | with_courier | with_courier (= FR-10.3 trigger) |
| 24 | Received at warehouse | with_courier | with_courier |
| 30 | In transit between hubs | with_courier | with_courier |
| 41 | Picked up (out for delivery / out for return by type) | with_courier / returning | with_courier / return_in_transit |
| 45 | Delivered | delivered | delivered (terminal) |
| 46 | Returned to business | returned | return_pending_inspection (starts FR-12.4 clock) |
| 47 | Exception | exception | unchanged + NDR recorded |
| 48 | Terminated | terminated | exception → manager resolution |
| 49 | Canceled | cancelled | per cancellation flow |
| 100 | Lost | lost | lost (terminal) + exception |
| 101 | Damaged | exception | exception → manager resolves to damaged |
| 102 | Investigation | exception | unchanged + exception |
| 103 | Awaiting your action (return failed 3×) | exception | exception — inventory in limbo at Bosta hub |
| 105 | On hold | with_courier | unchanged + flag |
| 22, 23, 40, 25, 60, 104 | CRP/Exchange/Cash-collection/Fulfillment/Archived states | mapped per type when those flows are enabled | — |

## 8.4 Exception (NDR) codes worth first-class handling
- Forward (delivery attempts): customer not at address (1), changed address (2), postponed (3), wants to open (4), data modification needed (5, 13, 14), sender cancelled (6), not answering (7), **refused (8)**, outside coverage (12).
- Return (RTO attempts): retry/postponed/data (20–23, 25), business refused (24), **order damaged (26), empty order (27), incomplete (28), doesn't belong (29), opened when it shouldn't be (30)**.
- Codes 26–30 are structured courier-side evidence of loss/tamper → attached to the shipment and surfaced with the never-received report (FR-15.3). State 103 gets its own exception detector.

## 8.5 Operational requirements from Bosta's side
- **IP whitelisting is required** for integration → the production VPS needs a static IP, communicated to Bosta support. Support-ticket lead time → start on day 1 (added to §7 Q2).
- Staging environment exists (`stg-app.bosta.co`) → integration tests against staging before touching production deliveries.

— End of requirements (v1.3) —
