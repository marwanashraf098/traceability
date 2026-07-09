# Session Summary — 2026-07-09

Covers the full Bosta integration + pilot stabilisation sprint (roughly 2026-07-04 → 2026-07-09).
Each item is commit-referenced; migration numbers are V-prefixed Flyway files.

---

## 1. What was built / fixed

### V32 — Shopify Custom-App Client Credentials path (`84dd37e`)
- `POST /api/v1/shopify/connect-custom` — Dev Dashboard path for merchants using Shopify custom apps (not the full public OAuth flow).
- `V32__custom_app_cc.sql` — `connection_type` column on `stores`, new `webhook_source` enum value `shopify_cc`.
- Guards: custom-app connect is behind `CUSTOM_APP_CONNECT_ENABLED=true` feature flag; disabled in production until public OAuth is ready.
- HMAC validation on webhooks keyed by custom-app shared secret.

### AWB size setting A4/A6 + audit_log RLS fix (`e6d27d2`, `696b9a9`)
- `courier_accounts.awb_format` column (existed from V20) now surfaced in Settings UI.
- `BostaHttpGateway.printMassAwb()` switched to v0 endpoint with raw `apiKey` auth (reverted from v2 in `701cfb0` — v2 requires OAuth tokens, our stored key format 401s).
- **Bug**: Settings save (PUT /tenant/settings) threw 500 because `audit.record()` ran in a separate `TenantContext.runAs()` block AFTER the main `tx.execute()` committed → GUC reset to `''` → RLS WITH CHECK failed. Fix: moved audit INSERT inside the same `tx.execute()`.

### V33 — Bosta delivery backfill (`694347e`, `9889094`)
- `BostaBackfillJob.run()`: paginates Bosta List API up to N pages, synthesizes a `{trackingNumber, state, updatedAt}` payload per delivery, inserts into `webhook_events` as `source='bosta_backfill'`, enqueues `BostaWebhookJob`. Single ingestion pipeline — backfill and live webhooks share all matching/state/piece logic.
- Real v0 API response shape parsed: `deliveries[]` envelope (not `data[]`), `state.code` object, `type.value` object, `shopifyInfo.orderId` for businessReference.
- `POST /api/v1/bosta/sync` (OWNER) triggers on demand. `GET /api/v1/bosta/sync/status` returns last-run stats.
- **Match-precedence fix** (`b4f5df7`): `matchByBusinessReference()` returns a `StrongMatch` record; ambiguous match (>1 order same ref) flagged before phone+COD fallback.
- **RLS/GUC bug #6** fixed (`b2af324`): `tryMatchDelivery()` must run inside `tx.execute()` so the GUC is active when querying `orders` under RLS.

### V35 — Two-tier Bosta delivery polling (`b6393a2`)
**Tier 1 — Status Poll** (every 3 min, `bosta-status-poll` JobRunr recurring):
- Queries non-terminal `shipments` rows per tenant, ordered by `last_polled_at ASC NULLS FIRST` (round-robin).
- Per shipment: `fetchDelivery()` → `BostaIngestionHelper` → `BostaWebhookJob`. Guard 2 skips re-enqueue if state unchanged.
- Cap: `bosta.poll.status-max-per-cycle=200`.

**Tier 2 — Discovery Poll** (every 20 min, `bosta-discovery-poll`):
- Pages first 3 Bosta list pages (~150 newest-created deliveries).
- New deliveries ingested + matched; already-seen ones dedup'd via `webhook_events_idem`.

**`BostaIngestionHelper`** extracted as shared component (backfill / status-poll / discovery-poll all use: fetch → synthesize payload → INSERT webhook_events → enqueue BostaWebhookJob).

`V35__bosta_poll.sql`: `shipments.last_polled_at` column, partial index on non-terminal shipments.

### V36 — Consignee PII from Bosta (`8909f3a`)
- Shopify Basic plan blocks customer PII via custom app until PCD review. Bosta already has verified `receiver.{name,phone,address}`.
- `ShipmentLinkService.populateConsigneePii()`: called on auto-link; COALESCE per field (never overwrites existing Shopify PII); GDPR-guarded by `pii_redacted_at IS NULL`.
- Phone normalisation: `+20XXXXXXXXXX` → `01XXXXXXXXX`.
- `POST /api/v1/bosta/backfill-pii`: fills already-linked orders from `shipments.raw` JSONB.
- Fulfill.tsx: shows "Pending Bosta link" when `customer_name` is null.
- `V36__bosta_pii_columns.sql`: `pii_source TEXT`, `pii_redacted_at TIMESTAMPTZ` on `orders`.

### Bosta webhook auth fix — Bearer-prefix normalisation (`7f2ff72`) **[CONFIRMED WORKING]**
- **Root cause**: handler hard-required `Authorization: Bearer {secret}`. Bosta sent raw secret without prefix → 401.
- **Fix**: strip one `Bearer ` prefix if present (case-insensitive); accept raw secret. Both forms hash identically to the stored SHA-256.
- **Confirmed in production**: real Bosta webhook returned 200, `webhook_events` row with `source='bosta'` and `status='processed'` appeared.
- 3 tests: raw-no-Bearer → 200; double-Bearer → 401; lowercase-bearer → 200.

### Copyable webhook secret reveal + regenerate-secret (`c8da6da`)
- `POST /api/v1/bosta/regenerate-secret` (OWNER): rotates webhook secret, returns new 64-hex value once, stores SHA-256.
- `WebhookSecretReveal` panel: three copyable rows (Webhook URL, Authorization Key, Raw secret), transient "Copied!" feedback, "save now" warning, shown once after connect or regenerate.

### V37 — Complete Bosta state + exception mapping (`2891db9`, `bed889d`)
- **Root cause of state -1**: `BostaHttpGateway.fetchDelivery()` envelope unwrap was failing (not all shapes handled) → state extraction found nothing → -1. Not a parsing bug.
- `BostaStateMapper.MappedState`: split `isException` (state 47/101/102) from `unknownCode` (-1/unmapped). Previously both were `true`, causing state 47 (NDR) to abort like an unknown code.
- Step 9 in `BostaWebhookJob`: extracts `exceptionCode` + `exceptionReason` from `delivery.raw()` when `isException`. Stored with COALESCE (preserves first NDR code).
- Kill-switch guard: never enqueue on state -1 (Guard 1 in `BostaIngestionHelper`).
- `V37__bosta_state_fix.sql`:
  - State 60: `with_courier` → `returned` (terminal — stops the poll loop).
  - State 11: → `created`.
  - State 41: disambiguated by `type` — FXF_SEND → `with_courier`; EXCHANGE/CRP → `returning`.
  - NDR codes 100/101 added for both forward and return categories.
  - `shipments.exception_code INTEGER`, `shipments.exception_reason TEXT`.
  - `bosta_state_mappings` PK widened from `(code)` to `(code, category)`.

### V38 — Bosta HTTP 429 rate-limit handling (`bfd3249`, `c94f8df`)
- **Root cause of -1 loop**: runaway poll hammered Bosta until 429 → `{success:false, errorCode:429}` body returned under suppressed 4xx → state extraction → -1 → re-enqueued forever.
- `BostaRateLimitException` (new, not retried by JobRunr).
- `BostaHttpGateway`: only 404 suppressed now (not all 4xx). 429 HTTP → `BostaRateLimitException`. Body-level 429 guard (`detectRateLimit()`) for cases where Bosta wraps 429 in a 200 body.
- `BostaStatusPollJob`: `ConcurrentHashMap<UUID, Long> rateLimitRetryUntilByTenant` — set on 429, checked at top of tenant loop.
- `inter-fetch-delay-ms`: 100 → 2000ms. At 16 shipments × 2s = 32s per cycle.
- Test p11: 429 → cycle aborts (1 fetch, not N); `last_polled_at` not set; second immediate call skipped by backoff.

### V39 — courier_accounts dedup + idempotent reconnect (`d61d5dd`)
- **Root cause**: `ON CONFLICT DO NOTHING` without a unique constraint always inserted a new row. Tenant 07fc572c accumulated 4 Bosta rows → 4× API calls per poll cycle.
- `V39__courier_accounts_unique.sql`: FK-safe dedup (repoint `shipments` + `pickups` FKs to winner row, delete losers), then `ADD CONSTRAINT courier_accounts_tenant_provider_uq UNIQUE (tenant_id, provider)`.
- `BostaController.connect()`: atomic `INSERT ... ON CONFLICT (tenant_id, provider) DO UPDATE SET ...` upsert.

### ExceptionService RLS-context fix — GET /api/v1/exceptions 500 (`d61d5dd`)
- **Root cause (8th occurrence of the pattern)**: `listExceptions()` / `listResolutions()` had no `@Transactional` → no transaction → `TenantAwareConnection.setAutoCommit(false)` never called → GUC never set → `queryForMap("...FROM tenants WHERE id=?")` under RLS returned 0 rows → `EmptyResultDataAccessException`.
- Fix: `@Transactional(readOnly=true)` on both read methods, `@Transactional` on `resolve()`.
- `ExceptionRlsTest`: two cases as `app_user` (empty tenant → `[]` not throw; blocked order → `blocked_customer` surfaces).

### Pick-queue recency filter + bounded import (`0eb0c3a`)
- **Problem**: 516 `status='new'` orders (2 months of history) flooded the pick queue. Merchants don't advance Shopify status; nothing auto-advances `new`.
- `FulfillService.getQueue()`: added `AND o.placed_at > now() - (? * INTERVAL '1 day')`. Injected via `@Value("${shopify.import.lookback-days:30}")`.
- `ShopifySyncService.runImport()`: hardcoded `90` → `importLookbackDays` from same config.
- `application.yml`: `shopify.import.lookback-days: ${SHOPIFY_IMPORT_LOOKBACK_DAYS:30}`.
- Orders-list, order-detail, Bosta linking: **unfiltered** — old orders stay in DB, visible, linkable.
- `PickQueueRecencyTest`: 7 cases (in-window, out-of-window, orders-list unfiltered, Bosta FK linkability, all eligible statuses, on_hold, boundary).

### webhook_events idempotency — graceful duplicate handling (`d848d2e`)
- **Race**: two overlapping `bosta_poll` cycles both fetched the same delivery, both inserted `webhook_events` rows with `external_event_id=NULL`, both passed step-4 dedup (which needs `status='processed'`), both reached `markProcessed()` → `DuplicateKeyException` on the partial unique index `webhook_events_idem` — unhandled → failed JobRunr job → 30s retry loop.
- **Fix layer 1** (`BostaIngestionHelper`): pre-compute idem key before INSERT, set `external_event_id` at creation with `ON CONFLICT (source, external_event_id) WHERE external_event_id IS NOT NULL DO NOTHING`. Second call for same idem key → no row returned → skip enqueue.
- **Fix layer 2** (`BostaWebhookJob.markProcessed()`): `try/catch (DuplicateKeyException)` matching the existing inline catch at step 11. Marks event as `'concurrent duplicate'` without rethrowing.
- Tests p12 + p13: dedup-at-creation (second call returns false, 0 extra rows); end-to-end two-event sequence both resolve as `'processed'` with no exception.

### Bosta delivery tenant-routing fix + retry-loop guard (`630b829` + production SQL)
- **Root cause**: tenant 2522cd56 (old test tenant) and pilot 07fc572c share the same Bosta business / API key. Both polled the same deliveries. Delivery 2499538591 (order #385328359470 under 07fc572c) got processed under 2522cd56 → wrong-tenant RLS → NO_MATCH → `unlinked_bosta_deliveries` → looped every ~30s (JobRunr retries feeding into the poll re-fetch cycle).
- **Immediate fix (production SQL)**:
  ```sql
  UPDATE courier_accounts SET status = 'disconnected'
  WHERE tenant_id::text LIKE '2522cd56-%' AND provider = 'bosta';

  DELETE FROM unlinked_bosta_deliveries
  WHERE tenant_id::text LIKE '2522cd56-%';
  ```
  `status='disconnected'` is excluded by `ACTIVE_BOSTA_TENANTS` (`WHERE status='active'`) — no restart needed.
- **Guard 3** (`BostaIngestionHelper.ingestDelivery()`): before INSERT, check `unlinked_bosta_deliveries WHERE tracking_number=? AND resolved=false`. If existing row has the same `bosta_state_code` → `return false` (skip re-enqueue). Only re-try when state changes — a new state may be matchable.
  - Runs inside `tx.execute()` so GUC fires and RLS scopes the check to the current tenant.
- Test p14: same-state unlinked → Guard 3 fires, 0 event rows, no exception. State change 41→45 → re-enqueues (1 event row created).

---

## 2. Key learnings / gotchas

**Bosta API auth is endpoint-inconsistent — do not assume uniform auth:**
- Bosta webhook `Authorization` header: raw secret (no Bearer). Our webhook handler now accepts both.
- Bosta v0 REST endpoints (list, fetchDelivery, createPickup, mass-awb v0): `Authorization: {apiKey}` — NO Bearer prefix.
- Bosta v2 endpoints: appear to need OAuth tokens, NOT the stored API key. `printMassAwb()` was briefly broken by a v2 switch — reverted to v0.
- Always test a Bosta endpoint change with a real curl before assuming it works.

**Bosta state code -1 = rate-limit suppressed as 200, NOT a parsing bug:**
- Before the 4xx suppression fix, `.onStatus(is4xxClientError, noOp)` converted HTTP 429 (body `{success:false, errorCode:429}`) into a 200. State extraction found no `state` field → -1. Every -1 re-enqueued. The loop hammered the API into deeper rate-limiting. The fix: only suppress 404; let 429 reach `catch (RestClientResponseException)` → throw `BostaRateLimitException`.

**Bosta List API is creation-ordered with no working updatedAfter filter:**
- Polling the list for "changed since last cycle" does NOT work — the API has no such filter in v0. The correct approach: poll KNOWN non-terminal shipments by tracking number (Tier 1), and page the list only for discovering NEW deliveries not yet in the system (Tier 2, bounded by page count).

**Bosta webhook fires on state CHANGE only; payload shapes differ from list/fetch:**
- Webhook payload: state and type are FLAT scalars (`"state": 45`, `"type": "SEND"`).
- List/fetch responses: state is an object `{"code": 45, "value": "Delivered"}`, type is an object `{"code": 1, "value": "Send"}`.
- Envelope variants observed in the wild: `{data:{...}}`, `{data:{data:{...}}}`, `{data:[...]}`, `{deliveries:[...]}`, flat `{trackingNumber:...}`. All handled defensively.

**The RLS-context bug: 8+ occurrences this sprint, all identical pattern:**
- Any DB query against an RLS table without the tenant GUC fires returns 0 rows (reads) or violates WITH CHECK (writes). The GUC fires only inside a transaction opened via `TenantAwareConnection.setAutoCommit(false)` — which happens on `@Transactional` methods or explicit `tx.execute()` calls when `TenantContext` is set on the thread.
- `postgres`-role test connections use BYPASSRLS — a green test suite does NOT prove RLS is enforced. Must test as `app_user` inside a `TenantAwareDataSource` + `TransactionTemplate`.
- Fix is always: add `@Transactional` to the service method (or wrap in `tx.execute()`). Never do raw `jdbc.query()` outside a transaction on RLS tables.
- Affected services this sprint: `ReceivingService`, `LabelService`, `FulfillService` (getQueue/getOrder/removeFromPickupManifest), `ExceptionService`, `ShipmentLinkService.tryMatchDelivery()`, `AuditService.record()` (in TenantController), auth refresh (user role lookup), `BostaWebhookJob` step 8.5.

**Sharing one Bosta business across multiple Traced tenants breaks routing:**
- The current model routes a Bosta delivery to whichever tenant's poll job fetches it — NOT based on which tenant's orders match. This is fine for the pilot (one merchant = one Bosta API key = one tenant). If two tenants ever legitimately share a Bosta business, the system will misroute deliveries. Flagged as a future design concern; NOT building now.

**Two-step INSERT + UPDATE without a unique constraint is not idempotent:**
- The `ON CONFLICT DO NOTHING` pattern requires a matching unique constraint or index. Without one, PostgreSQL has nothing to conflict on and always inserts. This caused the courier_accounts accumulation bug (4 rows for one tenant). Always verify that `ON CONFLICT` has a matching constraint before trusting it.

**`external_event_id` set at INSERT time (not after processing) is the correct dedup anchor:**
- Original design: insert with `external_event_id=NULL`, set it in `markProcessed()` after all state is applied. This leaves a window where concurrent workers both pass the step-4 dedup check (which requires `status='processed'`) and both race to write the same `external_event_id`. Fix: pre-compute the idem key and write it in the INSERT with `ON CONFLICT DO NOTHING`. If there's a conflict, the second insert gets no row back → skip enqueue entirely.

---

## 3. Status — DONE+VERIFIED vs BUILT-BUT-UNVERIFIED

| Item | Status |
|---|---|
| Bosta webhook auth (Bearer fix) | ✅ **VERIFIED in production** — real webhook returned 200, `source='bosta'` row confirmed |
| Bosta delivery auto-link via businessReference | ✅ **VERIFIED** — order `#385328009470` linked via businessRef in production |
| Bosta PII from receiver (customer_name/phone on pack page) | ✅ **VERIFIED** — merchant confirmed name/phone appear on pack screen |
| AWB print (v0 mass-awb, A4/A6) | ✅ **VERIFIED** — AWB generated and printed successfully |
| Bosta state mapping (all states, NDR, exception_code) | ✅ **BUILT + TESTED** (BostaStateMappingTest 12 cases) — production observation: real webhook event processed correctly |
| 429 rate-limit handling + per-tenant backoff | ✅ **BUILT + TESTED** — rate-limit incident resolved; test p11 covers the abort/backoff cycle |
| Two-tier polling (status + discovery) | ✅ **BUILT + DEPLOYED** — running since 2026-07-05; Tier 1 status updates confirmed in production |
| Pick-queue recency filter (516 orders flood) | ⚠️ **BUILT + DEPLOYED** — fix is live; actual queue count after deploy not yet reported back |
| webhook_events dedup-at-creation | ✅ **BUILT + TESTED** (p12/p13) — no more DuplicateKeyException in logs after deploy |
| courier_accounts dedup (V39) | ✅ **BUILT + DEPLOYED** — 07fc572c reduced to 1 Bosta row; poll rate normalized |
| Bosta tenant-routing fix (deactivate 2522cd56) | ⚠️ **SQL PROVIDED** — pending manual execution on production DB |
| Guard 3 retry-loop (already-unlinked skip) | ✅ **BUILT + TESTED** (p14) — deployed; retry loop should stop after SQL step |
| ExceptionService RLS fix | ✅ **BUILT + DEPLOYED** — GET /api/v1/exceptions no longer 500s |
| Copyable webhook secret reveal | ✅ **BUILT + DEPLOYED** |
| Shopify CC custom-app connect path | ✅ **BUILT** — behind feature flag; not yet used by pilot (pilot uses standard connect) |
| `[BOSTA-WH-HIT]` diagnostic log | ⚠️ **STILL IN CODE** — should be removed once webhook delivery is stable |

---

## 4. Known open items / TODO

### Immediate (before next pilot interaction)
1. **Run production SQL to deactivate 2522cd56** — delivery 2499538591 will then link correctly under 07fc572c on the next discovery poll cycle (within 20 min):
   ```sql
   UPDATE courier_accounts SET status = 'disconnected'
   WHERE tenant_id::text LIKE '2522cd56-%' AND provider = 'bosta';
   DELETE FROM unlinked_bosta_deliveries
   WHERE tenant_id::text LIKE '2522cd56-%';
   ```
2. **Remove `[BOSTA-WH-HIT]` diagnostic log** from `BostaController.bostaWebhook()` — webhook is confirmed working; the log is noisy and leaks the fact that a webhook arrived even before auth.
3. **Verify pick queue** under 07fc572c after deploy — should be ≤30 orders (recent `new` only), not 516.

### Near-term
4. **On-order-arrival instant Bosta linking** — design approved (Option B1: `BostaOnOrderArrivalJob` triggered from `ShopifyWebhookProcessorJob.handleOrderUpsert()`, pages 1-2 of Bosta list). Not yet built. Current latency: up to 20 min (discovery poll). Approved design: event-driven, 1-2 list API calls per new order, feeds existing pipeline, graceful "not found" no-op.
5. **Systematic RLS-context sweep** — 8+ occurrences of the same bug in one sprint. Should sweep all `@Service` classes with public methods that query RLS tables and confirm each has `@Transactional`. Add a checklist or linting rule.
6. **Shared Bosta-business multi-tenant routing** — current model assigns delivery to the polling tenant. If ever two tenants legitimately share a Bosta business, must match delivery to order to determine tenant. Not needed for pilot.
7. **Rate-limit relief at scale** — current Tier 1 does one `fetchDelivery()` API call per in-flight shipment per 3-min cycle. At >200 shipments this becomes expensive. Future: Bosta bulk-status endpoint (if available) or sliding poll window. Not needed at current pilot scale.

### Parked (lower priority)
8. **Frontend 204 false error** — `api.ts request()` fix already shipped (`1b60c10`). If any other endpoint returns 204 with a body, similar false error could recur. Consider a global 204 guard in the fetch wrapper.
9. **`@Transactional` audit for completeness** — `FulfillService`, `LabelService`, `ReceivingService` were all fixed reactively this sprint. Should do a proactive pass.
10. **`webhook_source` enum values** — currently: `shopify`, `bosta`, `bosta_backfill`, `bosta_poll`, `bosta_poll_discovery`, `bosta_order_arrival` (future). Document the taxonomy.
11. **Bosta `inter-fetch-delay-ms` tunability** — hardcoded as cron in JobRunr but delay is configurable. At very high volume, may need dynamic throttling. Not needed now.

---

## Migration inventory (this sprint)

| Version | File | Purpose |
|---|---|---|
| V32 | `V32__custom_app_cc.sql` | Shopify custom-app `connection_type`, webhook_source enum |
| V33 | (inline in backfill job) | `webhook_source` + `bosta_backfill` value, courier_accounts backfill columns |
| V34 | `V34__unlinked_idempotent.sql` | `uix_unlinked_active_per_tracking` partial unique index |
| V35 | `V35__bosta_poll.sql` | `shipments.last_polled_at`, non-terminal shipment index, poll source enum values |
| V36 | `V36__bosta_pii_columns.sql` | `orders.pii_source`, `orders.pii_redacted_at` |
| V37 | `V37__bosta_state_fix.sql` | State 60 terminal, state 11/41 mapping, NDR codes, exception columns on shipments |
| V38 | (code-only — rate-limit handling) | No schema change |
| V39 | `V39__courier_accounts_unique.sql` | FK-safe dedup + `UNIQUE(tenant_id, provider)` |

---

## Test coverage added this sprint

| File | Cases | What it covers |
|---|---|---|
| `BostaBackfillTest` | 15 | Backfill pipeline, idempotency, piece transitions, unlinked recording |
| `BostaPollJobTest` (p1–p14) | 14 | Status poll, discovery, dedup, 429 backoff, concurrent dedup, Guard 3 |
| `BostaStateMappingTest` (s1–s12) | 12 | All state code shapes, NDR, exception storage, terminal classification |
| `BostaPiiTest` | 10 | Consignee PII fill, GDPR guard, phone normalisation, backfill, app_user RLS |
| `BostaDay5Test` | 8+ | Connect, upsert, webhook auth, secret regeneration |
| `BostaDay6Test` | varies | Matching, linking flows |
| `ExceptionRlsTest` | 2 | listExceptions under app_user (empty + blocked_customer) |
| `PickQueueRecencyTest` | 7 | Queue recency filter boundary, orders-list unaffected, on_hold |
| `TenantSettingsTest` (s4b/s4c) | 2 | audit_log RLS: GUC-set succeeds, GUC-missing fails |

**Total backend tests: ~553 (all green except 1 pre-existing Shopify flake).**
