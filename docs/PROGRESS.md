# Progress Journal — Piece-Level Traceability SaaS

---

## Current state

Day 5 complete as of 2026-06-14. All 60 integration tests pass (BUILD SUCCESS).

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

Day 6: Link shipments to tracking_number + wire BostaWebhookJob to InventoryLedger.
- Link `webhook_events` to `shipments` via `tracking_number`; call `InventoryLedger.transition()` on courier state changes (ledger state machine is the idempotency backstop)
- Mode A delivery creation and pickup creation are deferred — Mode B only for launch
- Day 5 commit: see `git log`

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
- **Supabase session-mode pooler caps at 15 concurrent connections (free plan)** — running two app instances simultaneously exhausts the pool. Testcontainers tests use their own containers and don't count. Kill idle app instances before running load-heavy operations.
- **Shopify Protected Customer Data (PCD) is gated separately from `read_customers` scope** — `shippingAddress` and `customer` fields on Order are blocked even with `read_customers` granted until PCD is approved. Currently `customer_name`, `customer_phone`, `address` are null-populated; full data is preserved in `orders.raw` (jsonb) for backfill once approved. See pending human tasks for what to do.


- **Docker Desktop M3 + Testcontainers API v1.41 override**: Docker Desktop on Mac M3 rejects docker-java's default v1.24 version-negotiation request with HTTP 400. Fix: `DockerDesktopMacStrategy` in `src/test/java/com/traceability/` overrides `test()`, `getClient()`, *and* `getDockerClient()` (all three are required — Testcontainers calls `getDockerClient()` after `test()` passes, and the base implementation re-does version negotiation) to force API v1.41. Strategy is loaded via `~/.testcontainers.properties` AND `src/test/resources/testcontainers.properties`. **Do not delete or "clean up" this class.** On CI (Linux Docker socket) version negotiation works fine; the class is inert there because the built-in `UnixSocketClientProviderStrategy` wins first.
- **`pg_class` RLS flag column is `relrowsecurity`** — not `rowsecurity`. Fixed in `MigrationSmokeTest.java` line ~90.
- **`FORCE ROW LEVEL SECURITY` binds the table owner too** — any future Flyway migration that needs to INSERT into a tenant-scoped table (e.g., seed a default location for a new tenant) must do so as `postgres` (which holds `BYPASSRLS` and therefore bypasses RLS unconditionally — confirmed `rolbypassrls=true, rolsuper=false` on Supabase) or with a `SECURITY DEFINER` helper. `app_user` will be blocked regardless.
- **`SET LOCAL` is a silent no-op outside a transaction** — the `SET LOCAL app.current_tenant = ?` call for the tenant context filter must happen inside an explicit transaction (`BEGIN` / `COMMIT`). Called outside a transaction it silently succeeds but resets at the next statement boundary, leaving subsequent queries with no tenant context (empty-string GUC → policy evaluates to false → zero rows or constraint violation).

---

## Pending human tasks (not code)

- ~~Apply V1 + V2 + V3 migrations to Supabase and set `app_user` password out-of-band~~ **Done 2026-06-13.**
- **Shopify Protected Customer Data (PCD) approval** — blocks customer name, phone, and shipping address in order imports. Two steps required:
  - *Dev store (unblock testing now):* Shopify Admin → Settings → Apps and sales channels → custom app → API credentials → add `read_customers` scope + regenerate token → update `SHOPIFY_ADMIN_TOKEN` in `.env`. Then in Partner Dashboard → Apps → App setup → Protected customer data → request access and accept the data-handling terms.
  - *Production / public app (launch dependency — apply early):* PCD requires a Shopify review step with non-trivial lead time. Submit the PCD access request via the Partner Dashboard well before launch. Without approval, all orders import with null customer name/phone/address (data is preserved in `orders.raw` for backfill once approved).
- Open Bosta whitelisting/staging ticket for a static egress IP (needed for webhook delivery in staging and production).
- Get pilots' label-size answer (40×25 or 50×25) before Day 10 label work begins.
