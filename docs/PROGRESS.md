# Progress Journal — Piece-Level Traceability SaaS

---

## Current state

Day 1 complete. Review-fix pass completed 2026-06-12 (was claimed done earlier but had not been applied to the files).

**What exists:**
- Spring Boot 3.3.5 / Java 21 Maven project, Vite + React 18 + TypeScript + Tailwind in `/frontend`, built into Spring's static resources via `frontend-maven-plugin`.
- `V1__baseline.sql`: full schema (17 tenant-scoped tables + 2 global lookup tables), 16 enums, `app_user` role creation (password out-of-band), `ALTER DEFAULT PRIVILEGES` for future tables/sequences, all FK-safe table order, blueprint-mandated indexes, `FORCE ROW LEVEL SECURITY` + `tenant_isolation` policy on all 17 tenant tables, `REVOKE UPDATE, DELETE ON piece_events FROM app_user`. Also contains: `auth_lookup_user` + `resolve_tenant_by_shop_domain` SECURITY DEFINER functions (only two RLS escape hatches); `webhook_events_idem` partial unique index (WHERE external_event_id IS NOT NULL); `orders.on_hold` + `hold_reason` columns + `orders_tenant_hold` partial index; `pieces.id` as `text` (ULID, no default); `piece_events.piece_id` and `allocations.piece_id` as `text` to match.
- `V2__bosta_seed.sql`: 23 Bosta state mapping rows (code 41 has two rows: SEND + RTO) + 22 NDR codes (11 forward + 11 return; codes 26–30 marked `critical`).
- `MigrationSmokeTest.java`: 12 assertions, all green — migrations succeed, 17-table RLS coverage, `relrowsecurity` on `piece_events`, INSERT-only enforcement on `app_user`, seed row counts, critical NDR codes, two NULL-external_event_id Bosta webhook rows both insert, `pieces.id` is `text`, `auth_lookup_user` returns seeded user without GUC set.
- `DockerDesktopMacStrategy.java` + `~/.testcontainers.properties` override for Mac M3 Docker Desktop (see Gotchas).
- `/api/v1/health` endpoint with DB ping.
- `docker-compose.yml` for local Postgres dev.

**What has NEVER happened yet:**
- Migrations applied to Supabase.
- App run against Supabase.
- Hetzner VPS provisioned or deploy pipeline wired.

---

## Next up

Day 2 per `docs/four-week-pilot-core.md`:
- Tenant context filter: `TenantContext` holder + `DataSourceWrapper` that runs `SET LOCAL app.current_tenant = ?` inside each transaction.
- Auth: email + password login, JWT access (15 min) + refresh token, logout-everywhere.
- Worker PIN switch: `POST /api/v1/auth/pin` swaps the attributed user on the session; 15-min idle reset; lockout after 5 fails + notification.
- Permission middleware: server-side role matrix (Owner / Manager / Worker) on every endpoint.
- The four Day-2 tests: RLS isolation (two-tenant cross-read attempt), PIN lockout, JWT expiry, permission matrix.

---

## Decisions made

- **Runtime role is `app_user` / Flyway runs as owner** — app connects as unprivileged `app_user` so RLS is always enforced; Flyway (postgres superuser) bypasses RLS, which is the only safe way to apply DDL migrations without setting the tenant GUC.
- **Webhook idempotency via partial unique index + app-side key for Bosta** — `UNIQUE NULLS NOT DISTINCT (source, external_event_id)` in DB handles Shopify (which sends an event ID header); for Bosta (no HMAC, no event ID) we generate a deterministic key app-side and verify authenticity by re-fetching the event from the Bosta API.
- **`pieces.barcode` = app-generated ULID text** — the scannable piece identifier lives in `barcode TEXT NOT NULL UNIQUE`; the internal PK remains a UUID. ULID is time-sortable, URL-safe, and embeds a millisecond timestamp, which makes piece barcodes readable in sort order without a separate sequence.
- **Order hold = boolean column not enum value** — a separate `on_hold boolean` column avoids combinatorial enum explosion (every `order_status` value would need a corresponding `_held` twin).
- **The two SECURITY DEFINER functions are the only RLS escape hatches** — any future query that genuinely needs cross-tenant reads (e.g., internal analytics, super-admin lookup) must go through a named `SECURITY DEFINER` function so the bypass is visible, named, and code-reviewed; bare superuser connections are not an acceptable pattern.

---

## Gotchas / environment quirks

- **Docker Desktop M3 + Testcontainers API v1.41 override**: Docker Desktop on Mac M3 rejects docker-java's default v1.24 version-negotiation request with HTTP 400. Fix: `DockerDesktopMacStrategy` in `src/test/java/com/traceability/` overrides `test()`, `getClient()`, *and* `getDockerClient()` (all three are required — Testcontainers calls `getDockerClient()` after `test()` passes, and the base implementation re-does version negotiation) to force API v1.41. Strategy is loaded via `~/.testcontainers.properties` AND `src/test/resources/testcontainers.properties`. **Do not delete or "clean up" this class.** On CI (Linux Docker socket) version negotiation works fine; the class is inert there because the built-in `UnixSocketClientProviderStrategy` wins first.
- **`pg_class` RLS flag column is `relrowsecurity`** — not `rowsecurity`. Fixed in `MigrationSmokeTest.java` line ~90.
- **`FORCE ROW LEVEL SECURITY` binds the table owner too** — any future Flyway migration that needs to INSERT into a tenant-scoped table (e.g., seed a default location for a new tenant) must do so as the postgres superuser (which bypasses RLS unconditionally) or with a `SECURITY DEFINER` helper. `app_user` will be blocked regardless.
- **`SET LOCAL` is a silent no-op outside a transaction** — the `SET LOCAL app.current_tenant = ?` call for the tenant context filter must happen inside an explicit transaction (`BEGIN` / `COMMIT`). Called outside a transaction it silently succeeds but resets at the next statement boundary, leaving subsequent queries with no tenant context (empty-string GUC → policy evaluates to false → zero rows or constraint violation).

---

## Pending human tasks (not code)

- Apply V1 + V2 migrations to Supabase (Frankfurt project) and set `app_user` password out-of-band via Supabase SQL editor or psql — the password cannot go in a migration file.
- Open Bosta whitelisting/staging ticket for a static egress IP (needed for webhook delivery in staging and production).
- Get pilots' label-size answer (40×25 or 50×25) before Day 10 label work begins.
