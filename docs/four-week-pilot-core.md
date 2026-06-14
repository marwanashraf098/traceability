# 4-Week Pilot Core — Compressed Build Plan
## Solo · Spring Boot + React · Supabase Postgres · Mode B only

> Goal redefined: by day 28, both pilot brands are labeling real inventory and shipping scan-verified orders with full custody timelines. NOT the full v1.2 spec — the smallest slice that proves the company.
> Hard conditions: full-time (~50h/wk) · pilots use Bosta's Shopify plugin (verify in 48h) · no new decisions mid-build · pilots tolerate rough edges.

---

# What ships (the Pilot Core)

**Identity & custody:** receiving sessions → unique barcode per piece → thermal label PDF → append-only event ledger → piece lookup with full timeline.
**Fulfillment:** Shopify orders in (import + webhook + poll) → simple queue → ONE combined pick/pack scan screen (single-step fulfillment, the per-tenant toggle is now the only mode) → AWB link scan (Mode B: plugin already created the delivery) → Bosta status polling → automatic piece status updates.
**Returns:** RTO intake scan → restock / damaged → the "returned but never received" report.
**Safety rails:** order hold, release-pieces-on-cancel, manual adjust (lost/damaged/found), inventory counts by status, a 4-detector exceptions list (lost shipment · never-received return · stuck shipment · unlinked Mode-B delivery).
**Foundation kept (non-negotiable even at this speed):** tenant_id + RLS isolation, transactional event+state helper, INSERT-only ledger grants, the pick-scan race guard, PIN-attributed workers, webhook idempotency.

# What's deferred to weeks 5–8 (post-launch, pilots already paying)

Mode A (delivery creation, city mapping, AWB fetch, pickup API) · gather list (interim: printable order list) · blocked-customer & address gates · self-pickup conversion (interim: hold + manual adjust) · guided unpack · gated confirmation mode & tag rules · fulfillment write-back · dashboards beyond counts · daily digest · bulk actions · CSV imports/exports · full Arabic pass (skeleton i18n from day 1; the 3 worker screens shipped in Arabic, admin screens English) · PWA polish · onboarding checklist · super-admin panel (psql).

---

# Week 1 — Skeleton + Shopify (days 1–7)
- D1: repo, Spring Boot 3 + Vite React scaffold, Docker Compose, Supabase project (Frankfurt, direct 5432), Flyway **baseline migration = the entire schema** (every table from the Blueprint, even unused ones — adding columns later is cheap, adding tables mid-sprint kills flow), deploy pipeline to a Hetzner VPS.
- D2: tenant context filter + RLS policies + the isolation test; auth (email/pw, JWT+refresh); worker PIN switch.
- D3: the **transactional event helper** (`transition(pieceId, expectedStatus, newStatus, eventType, actor, ctx)`) — native SQL, optimistic guard, event insert, one transaction. Write its tests now; everything else calls it.
- D4–5: Shopify connect — **production path is a public OAuth app** (pilots are on Basic tier; custom apps cannot read customer PII on Basic; PII is required for zone mapping, blocked-customer checks, and Mode-B matching). The existing import pipeline, idempotency, gateway, and encryption are unchanged — only the connect/auth seam changes from custom-app token to OAuth. The custom-app token endpoint remains for local dev only. **Timeline dependency: Shopify's PCD review is non-trivial lead time — register the app and submit the PCD request before or during this day, not after.**
- D6: webhook receiver (HMAC → raw persist → 200 → async), idempotency, 15-min reconciliation poll, cancel handling (status only).
- D7: orders list + detail (read-only), catalog list. **Demo: pilot's live store in your UI.** Buffer: half a day.
- *Parallel (non-coding evenings): pilot visits → confirm Mode B + label size; order hardware; partner signs pilot agreements.*

# Week 2 — Pieces, labels, lookup (days 8–14)
- D8–9: receiving sessions + lines + finalize → bulk ULID piece generation (batched insert, 1k ≤ 10s), `received` events.
- D10–11: label PDF — PDFBox + ZXing Code 128, Arabic font embedded, 40×25 + 50×25 presets, print + logged reprint. **Test on the real Xprinter day 11, not later.**
- D12: piece lookup — scan/type anything → piece page + human-phrased timeline. The demo screen; make it good.
- D13: inventory counts by status + variant piece counts; manual adjust (lost/damaged/destroyed/found, reason required).
- D14: **on-site: label 100+ real pieces at pilot #1 with their printer and their hands.** Fix the friction you watch. Buffer.

# Week 3 — Fulfillment scan flow + Bosta (days 15–21)
- D15: order pipeline statuses (auto-flow only: New → Ready to Pick on arrival), queue screen (oldest first, lock-on-open), hold/unhold, printable order list (gather interim).
- D16–17: the **combined fulfill screen**: scan pieces against order lines — server validation ≤300ms, five rejection codes, race guard, un-scan, audio + full-screen green/red. When complete → pieces `packed` in one step (single-step mode).
- D18: Mode B ingestion — JobRunr poller lists Bosta deliveries, match by business reference (fallback: phone+COD), unlinked-deliveries screen with manual match.
- D19: AWB link scan — scan the plugin-printed AWB on the fulfill screen → verify/link → `tracking_linked` events → Awaiting Pickup. Manual "courier collected" button (no pickup API).
- D20: status sync poller — Bosta→internal mapping table, courier events per piece, raw payloads, unknown-state alert; terminal handling (delivered / lost / returning / RTO).
- D21: stuck-shipment + lost detectors → exceptions list v0. **Demo: full chain — receive → scan-fulfill → AWB → courier statuses flowing onto piece timelines.** Buffer.

# Week 4 — Returns + launch (days 22–28)
- D22: RTO intake scan (+ unexpected-return flag), resolution restock/damaged, return events.
- D23: **never-received report** (RTO'd pieces not intaken in 3 days, by exact ID) + wire into exceptions; release-on-cancel for packed orders (simple: cancel delivery via `terminateDelivery`, rescan-free release with confirmation — guided unpack comes later).
- D24: Arabic for the 3 worker screens (fulfill, receiving, returns) + RTL check; hardening pass: rate limits, Sentry alarms, backup-restore drill, PITR on.
- D25: production deploy, DNS, smoke tests; seed pilot #1 tenant, import their blocklist-free order history.
- D26: **pilot #1 go-live day on-site**: connect store, receive backlog, first real scan-fulfilled shipments.
- D27: embed at pilot #1; same-day fixes; onboard pilot #2 (they drive, you watch).
- D28: stabilize, write the week-5–8 backlog from what the warehouses taught you. **Done = both pilots live.**

---

# Daily discipline (this only works with all five)
1. Ledger helper for every state change — zero bypasses, even "temporary."
2. 4-hour timebox on any integration rabbit hole → degrade to an exception, move on.
3. No mid-build feature additions: everything new goes to `week5.md`, even good ideas. Especially good ideas.
4. Ship to staging every night; partner sends pilots a demo clip twice a week.
5. If a day overruns, cut from the deferred-adjacent edge (printable list, Arabic admin screens, exceptions polish) — never from the ledger, scan validation, returns, or the never-received report.

# The honest risk register
- ~~Pilots don't use the plugin~~ **CLEARED: both pilots confirmed on the Bosta plugin. Mode B holds; the month stands.** Exchanges/refunds are manual at the pilots → handled as RTO intake + new order, nothing extra to build.
- **Bosta API surprises** (matching fields, state vocabulary, poll limits) → that's what the 4-hour timebox and the buffer half-days absorb.
- **You're not actually full-time** → this plan does not survive part-time; revert to the 16-week plan instead of failing this one.
