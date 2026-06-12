# Progress Journal — Piece-Level Traceability SaaS

---

## Current state

Day 2 complete as of 2026-06-13. All 8 integration tests pass (BUILD SUCCESS).

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

**What has NEVER happened yet:**
- Migrations applied to Supabase.
- App run against Supabase.
- Hetzner VPS provisioned or deploy pipeline wired.

---

## Next up

Day 3: `InventoryLedger.transition()` + ULID generator + state-machine / scan-race tests.

---

## Decisions made

- **Runtime role is `app_user` / Flyway runs as owner** — app connects as unprivileged `app_user` so RLS is always enforced; Flyway (postgres superuser) bypasses RLS, which is the only safe way to apply DDL migrations without setting the tenant GUC.
- **Webhook idempotency via partial unique index + app-side key for Bosta** — `UNIQUE NULLS NOT DISTINCT (source, external_event_id)` in DB handles Shopify (which sends an event ID header); for Bosta (no HMAC, no event ID) we generate a deterministic key app-side and verify authenticity by re-fetching the event from the Bosta API.
- **`pieces.barcode` = app-generated ULID text** — the scannable piece identifier lives in `barcode TEXT NOT NULL UNIQUE`; the internal PK remains a UUID. ULID is time-sortable, URL-safe, and embeds a millisecond timestamp, which makes piece barcodes readable in sort order without a separate sequence.
- **Order hold = boolean column not enum value** — a separate `on_hold boolean` column avoids combinatorial enum explosion (every `order_status` value would need a corresponding `_held` twin).
- **Three SECURITY DEFINER functions are the only RLS escape hatches** — `auth_lookup_user` (V1), `resolve_tenant_by_shop_domain` (V1), `lookup_refresh_token` (V3). Adding a fourth requires explicit approval. Any future cross-tenant read must go through a named, code-reviewed `SECURITY DEFINER` function; bare superuser connections are not an acceptable pattern.

---

## Gotchas / environment quirks

- **Docker Desktop M3 + Testcontainers API v1.41 override**: Docker Desktop on Mac M3 rejects docker-java's default v1.24 version-negotiation request with HTTP 400. Fix: `DockerDesktopMacStrategy` in `src/test/java/com/traceability/` overrides `test()`, `getClient()`, *and* `getDockerClient()` (all three are required — Testcontainers calls `getDockerClient()` after `test()` passes, and the base implementation re-does version negotiation) to force API v1.41. Strategy is loaded via `~/.testcontainers.properties` AND `src/test/resources/testcontainers.properties`. **Do not delete or "clean up" this class.** On CI (Linux Docker socket) version negotiation works fine; the class is inert there because the built-in `UnixSocketClientProviderStrategy` wins first.
- **`pg_class` RLS flag column is `relrowsecurity`** — not `rowsecurity`. Fixed in `MigrationSmokeTest.java` line ~90.
- **`FORCE ROW LEVEL SECURITY` binds the table owner too** — any future Flyway migration that needs to INSERT into a tenant-scoped table (e.g., seed a default location for a new tenant) must do so as the postgres superuser (which bypasses RLS unconditionally) or with a `SECURITY DEFINER` helper. `app_user` will be blocked regardless.
- **`SET LOCAL` is a silent no-op outside a transaction** — the `SET LOCAL app.current_tenant = ?` call for the tenant context filter must happen inside an explicit transaction (`BEGIN` / `COMMIT`). Called outside a transaction it silently succeeds but resets at the next statement boundary, leaving subsequent queries with no tenant context (empty-string GUC → policy evaluates to false → zero rows or constraint violation).

---

## Pending human tasks (not code)

- Apply V1 + V2 + V3 migrations to Supabase (Frankfurt project) and set `app_user` password out-of-band via Supabase SQL editor or psql — the password cannot go in a migration file.
- Open Bosta whitelisting/staging ticket for a static egress IP (needed for webhook delivery in staging and production).
- Get pilots' label-size answer (40×25 or 50×25) before Day 10 label work begins.
