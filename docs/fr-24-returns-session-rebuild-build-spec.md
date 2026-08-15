# FR-24 — Returns, Session-Based Rebuild — Step 0 Build Plan

Branch: `returns-rebuild` (cut from `main`). Status: **PLAN ONLY — no code written.** Mockup:
`design/Traced Returns Flow v2.dc.html` (note: the path in the request,
`Traced_Returns_Flow_v2_dc.html`, doesn't exist on disk — the real filename has spaces, see §6).

---

## 0. Two discrepancies found that need your decision before coding starts

These aren't blocking the plan below, but each is a place where the request's premise doesn't
match what's actually in the codebase. Flagging per the "diagnose before touching anything
behavioral" rule rather than silently resolving either way.

**(A) The "15+ exception types localized via AR/EN i18n keys" premise doesn't hold today.**
`ExceptionService.java` has 17 detector methods (`detectLost`, `detectNeverReceived`, …,
`detectReturnInTransitStuck`), and every one's description is a **hardcoded bilingual Java
string literal** inside `enrich()` (e.g. `ExceptionService.java:656-657`:
`item.put("descriptionEn", "Piece " + b + " is marked as lost...")` /
`item.put("descriptionAr", "القطعة " + b + " ...")`), not an i18next key. The frontend mirrors
this with a hardcoded `TYPE_LABELS` object in `Exceptions.tsx:39-53` covering only 11 of the 17
types, also not routed through `t()`. This is a **documented, known gap**
(`docs/frontend-inventory.md:415`), not an oversight I'm introducing. `en.json`/`ar.json` have no
`exceptions.<type>.*` keyspace at all today — only `exceptions.title`.
→ **Decision needed**: for the new returns-specific exception cases (rejected/foreign-item scan,
illegal-state mismatch), do we (a) follow existing precedent — hardcoded bilingual Java strings,
consistent with all 17 current types but perpetuating the gap — or (b) add real `t()` keys for
just the new ones, fixing it locally but diverging from every existing exception type? I'd lean
(a) for consistency unless you want to start closing the gap here.

**(B) Overview's "Awaiting Inspection" tile depends on the endpoint the spec retires.**
`Overview.tsx:409` calls `getReturnsPendingTotal()` → `GET /returns/pending?size=1` reading
`.total` (`api.ts:746-747`), which is `ReturnController.pending()` →
`returnService.countPending()`. The spec says retire "the three-tab UI and standalone
`listPending()`" but doesn't mention `countPending()` — and indeed no other Java caller of
`countPending()` exists, only `ReturnController` itself. Since `countPending()` is a pure
`COUNT(*) WHERE status='return_pending_inspection'` query, independent of the session/receipts
model, I plan to **keep `ReturnController.pending()`/`countPending()` alive unchanged** (drop only
`listPending()`, per the explicit instruction) so Overview keeps working without a frontend
change. Alternative: repoint Overview to the new `GET /returns/analytics` payload (which will
carry the same "unassigned pending" number anyway) and delete `GET /returns/pending` entirely.
Flagging so the choice is explicit rather than incidental.

---

## 1. Migrations

**Latest migration on disk: V72** (`V72__tenant_onboarding_dismissed.sql`). Next is **V73**.

**Plan: one migration, `V73__return_sessions.sql`**, covering all three tables — matching the
established precedent (`V62__stock_taking.sql` and `V64__transfers.sql` both create multiple
related tables + RLS in a single file; "RLS policy in the same migration" is invariant #3,
restated verbatim in `docs/fr-21-stock-taking-build-spec.md:40` and enforced by
`MigrationSmokeTest` checking for a policy literally named `tenant_isolation` on every table in
`TENANT_SCOPED_TABLES`).

```sql
-- return_sessions
id          uuid PRIMARY KEY DEFAULT gen_random_uuid()
tenant_id   uuid NOT NULL REFERENCES tenants(id)
status      text NOT NULL DEFAULT 'open' CHECK (status IN ('open','closed'))
opened_by   uuid REFERENCES users(id)
opened_at   timestamptz NOT NULL DEFAULT now()
closed_by   uuid REFERENCES users(id)
closed_at   timestamptz
note        text
-- CREATE UNIQUE INDEX return_sessions_one_open_per_tenant ON return_sessions (tenant_id) WHERE status = 'open';

-- return_session_items
id             uuid PRIMARY KEY DEFAULT gen_random_uuid()
tenant_id      uuid NOT NULL REFERENCES tenants(id)
session_id     uuid NOT NULL REFERENCES return_sessions(id)
piece_id       text NOT NULL REFERENCES pieces(id)   -- ⚠ text, not uuid — pieces.id is an app-generated ULID
scanned_at     timestamptz NOT NULL DEFAULT now()
scanned_by     uuid REFERENCES users(id)
scan_source    text NOT NULL CHECK (scan_source IN ('barcode','awb'))
disposition    text NOT NULL DEFAULT 'pending' CHECK (disposition IN ('pending','restocked','damaged','mismatch'))
disposition_at timestamptz
disposition_by uuid REFERENCES users(id)
damage_reason  text
unexpected     boolean NOT NULL DEFAULT false
-- UNIQUE (session_id, piece_id)

-- return_session_shipments
id         uuid PRIMARY KEY DEFAULT gen_random_uuid()
tenant_id  uuid NOT NULL REFERENCES tenants(id)
session_id uuid NOT NULL REFERENCES return_sessions(id)
awb        text NOT NULL
linked_at  timestamptz NOT NULL DEFAULT now()
-- UNIQUE (session_id, awb)
```

Each table gets, in this same file: `ENABLE ROW LEVEL SECURITY`, `FORCE ROW LEVEL SECURITY`,
`CREATE POLICY tenant_isolation ... USING/WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)`
— the exact pattern in `V64__transfers.sql`.

**Test bumps:**
- `MigrationSmokeTest.java:69-71` — `migrationsExecuted` expectation: **71 → 72**.
- `MigrationSmokeTest.java:34-53` — `TENANT_SCOPED_TABLES` (currently 29 entries): **29 → 32**
  (add all three new tables).
- `NotTracedBackfillTest.java:124-125` — post-V56 count: **16 → 17**.

**`label_reprinted` event type — no migration needed.** `piece_events.event_type` is
`text NOT NULL` with no CHECK constraint or enum anywhere (`V1__baseline.sql:173`, confirmed no
later migration adds one). `label_reprinted` is already a live value written by
`InventoryLedger.recordLabelReprinted()` (`InventoryLedger.java:333-345`), already reused by the
old `ReturnSessionController`. Same applies to a new `return_cancelled` event type needed for the
abandon-revert path (§2) — also free, no migration.

**Open question — historical data continuity.** The *old* returns-session flow stores its
sessions in the shared `receipts` table (`kind='returns'`, added by `V29__returns_session.sql`).
If any pilot tenant has real historical return sessions there, replacing `ReturnSessionService`
or repointing its reads to the new tables makes those old sessions unreadable via the app UI
(the underlying piece-level history stays fully intact in `piece_events` regardless — only the
session-grouping view is lost). **Recommendation: no backfill migration** — leave the old
`receipts kind='returns'` rows as inert history, not migrated into `return_sessions`. Flagging
for explicit confirmation since this diverges from "session record" being visible for pieces
returned before cutover.

---

## 2. State machine

**"Legal" piece statuses at scan time** (piece resolves to one of ours, and a real state-machine
transition into `return_pending_inspection` exists in `InventoryLedger.ALLOWED`):

| Current status | Transition | Event | `unexpected` | Notes |
|---|---|---|---|---|
| `return_in_transit` | → `return_pending_inspection` | `return_received` | false | expected RTO path |
| `with_courier` | → `return_pending_inspection` | `return_received` | **true** | Bosta-lag (state 41 never arrived) |
| `awaiting_pickup` | → `return_pending_inspection` | `return_received` | **true** | webhook-lag (state 21 never arrived) |
| `delivered`, within `customer_return_window_days` | → `return_pending_inspection` | `return_received` | false | customer-after-delivery; window enforced by `enforceReturnWindow()` (`ReturnSessionService.java:394-414`, reused as-is) |
| `return_pending_inspection` (already here — webhook or prior-session adopt) | *(no transition — sibling-append)* | `return_received` again, new `session_id` in metadata | false (unless it was already flagged) | this is the "adopt" path, see below |

All five rows above are **legal**: the piece gets a `return_session_items` row with
`disposition='pending'`, and all three dispositions (restock / damage / mismatch) are valid
against it. `unexpected=true` is a **visibility flag only** — per your contract it does not
restrict which dispositions are allowed; only the illegal-state fork does that.

**"Illegal-state" (mismatch-only) fork** — any other status the piece could be in:
`available`, `reserved`, `packed`, `damaged`, `lost`, `destroyed`, `out_on_transfer`, `sold`, or
`delivered` **outside** the return window. For these: create the item with `unexpected=true`,
**no ledger transition at all**, and only `mismatch` is a legal disposition.

This "restock/damage rejected server-side" requirement is **free** — no new guard code needed.
`ReturnService.restock()` and `markDamaged()` already hard-require
`status == 'return_pending_inspection'` (`ReturnService.java:176-179`, `225-228`) before
transitioning. Since an illegal-state piece is never moved to `return_pending_inspection`, calling
restock/damage on it hits that existing 409 automatically. Reuse them unchanged; the fork is
enforced by the fact that we simply never transition these pieces.

**Foreign scan (resolves to nothing of ours)**: 422, no row created, no ledger write, exception
logged with the raw scanned value + `session_id`. (Note: `foreign` here needs its own detection —
today's `intakeScan()` throws 404 for "not found," not 422; the new unified scan handler needs to
distinguish "no piece/shipment matches" (422 foreign) from "matches but wrong state" (which is the
illegal-state fork above, not an error).)

**Adopt path detail** (webhook coexistence #1): when a scan resolves to a piece already at
`return_pending_inspection`, call `InventoryLedger.recordReturnReceived()` again (the existing
sibling-append method, `InventoryLedger.java:313-322`) with the *new* session's id in metadata —
this exactly mirrors what `ReturnSessionService.recordVerdict()`'s `RETURN_PENDING_INSPECTION`
case already does today (`ReturnSessionService.java:268-273`). It is not a no-op; it's a second
`return_received` event carrying this session's id, which is what lets the abandon-revert logic
(next) tell adopted pieces apart from pieces this session itself transitioned.

**Abandon / delete-open-session — revert semantics.**
Mirrors `ReceivingService.deleteSession()` structurally (validate `status='open'`, reject with 409
if not, delete child rows then the session row, filtered by `kind`/table so it can never touch a
different domain's session) — but Receiving's delete is trivial because *no pieces exist yet* at
that point (pieces are created only at `finalize()`). Returns is different: pieces are already
transitioned to `return_pending_inspection` at scan time, before the session closes. So for every
`return_session_items` row still `disposition='pending'` at delete time, look at whether *this
session* caused a real status transition or just adopted an already-pending piece — determined
by re-reading that piece's `return_received` event for this `session_id` and checking its
`from_status`:

- `from_status = return_pending_inspection` (self-transition → this was an **adopt**, sub-case b):
  nothing to revert. Delete the session/item rows; the piece stays `return_pending_inspection`
  exactly as it was before this session touched it, and resurfaces in the landing's "unassigned
  pending returns" section.
- `from_status ∈ {return_in_transit, with_courier, awaiting_pickup, delivered}` (this session
  really transitioned it, sub-case a): **revert** — `ledger.transition(pieceId,
  RETURN_PENDING_INSPECTION, <that original from_status>, "return_cancelled", actorUserId, ctx)`.

This needs **four new entries added to `InventoryLedger.ALLOWED`**
(`return_pending_inspection:return_in_transit`, `:with_courier`, `:awaiting_pickup`,
`:delivered`) plus the new free-text event type `return_cancelled`. This is a real change to the
piece state machine (not just new SQL) — flagging explicitly since `ALLOWED` is a hand-maintained
allow-list and every entry in it is a load-bearing design decision per the existing code's
comments.

---

## 3. The two under-specified row states

Confirmed both will be built, as two structurally different things:

1. **Expected/awaiting-scan** (AWB-surfaced) — **not persisted**. Computed on read by reusing
   `ReturnSessionService.getSessionPieces()`'s existing join (`ReturnSessionService.java:159-205`:
   `pieces → allocations → order_items → orders → shipments` keyed on `tracking_number`), minus
   whatever's already a real `return_session_items` row for this session. Each transient row
   carries a `reprint` action per your contract.
   - **Gap found**: reprint on one of these rows would currently 422. The existing gate,
     `ReturnSessionService.validateAndRecordReprint()` (`ReturnSessionService.java:370-389`), only
     allows `return_pending_inspection` or `damaged` — an un-scanned piece still at
     `return_in_transit`/`with_courier`/etc. is rejected. Your contract says reprint is "allowed
     in any status" — so this gate needs widening to the full legal-scan status set (§2's table)
     to satisfy "each [awaiting-scan row] carries reprint." Flagging as a real behavior change to
     existing, working code, not a restyle.
2. **Scanned/needs-decision** — a real `return_session_items` row, `disposition='pending'`,
   persisted, rendered with the Restock / Damage / "Not the real piece" actions.

---

## 4. Reuse vs. replace

**Confirmed: Returns currently DOES ride a shared session table**, and the "kind-filter gap" is
real, not hypothetical. `receipts` (added `V1__baseline.sql:120`, `kind` column added
`V29__returns_session.sql:6-8`, values `'inbound'`/`'returns'`) is shared between Receiving and
the old returns-session flow, with **no DB-level "one open session" constraint of any kind** for
either domain (`receipts` has no unique index restricting open rows — confirmed by grep across
every migration). App-level enforcement is inconsistent:

- `ReceivingService.requireOpen()` (`ReceivingService.java:257-266`) and `getSession()`
  (`:179-194`) do **not** filter by `kind` — they'll happily operate on a `'returns'`-kind row if
  given its id. Only `deleteSession()` (`:83-98`) explicitly filters `kind='inbound'`, with a
  comment literally naming this as deliberate, narrow hardening of the one irreversible endpoint
  — not a fix of the general gap (`ReceivingService.java:78-81`).
- `ReturnSessionService.requireOpen()` (`ReturnSessionService.java:445-456`) does correctly filter
  `kind='returns'`.
- Neither domain enforces "only one open session per tenant" at all today — `ReceivingService.
  createSession()` and `ReturnSessionService.createSession()` both do a bare `INSERT ... 'open'`
  with no pre-check.

This rebuild fixes the gap **as a side effect of construction**, per your instruction to treat it
as a prerequisite rather than deferred: the new `return_sessions`/`return_session_items`/
`return_session_shipments` tables are **entirely separate** from `receipts`, with their own
partial-unique-open-session index. Once returns sessions stop being created in `receipts`, the
id-space collision risk for *future* rows disappears structurally. I'm not planning to also
retrofit `ReceivingService.requireOpen()`/`getSession()` with a `kind` filter as part of this PR
— that's Receiving's own bug, unaffected by this migration, and touching it would be scope creep
into a different feature's file. Flag if you want it bundled in anyway.

**Class-name collision.** `ReturnSessionService`/`ReturnSessionController` are the *existing*
waybill-first classes. Since that entire flow (waybill-required session creation, `receipts`-
table backing, `recordVerdict`) is exactly what's being superseded, the plan is to **rewrite the
contents of these two files in place** (same class/file names, entirely new internals against the
new tables) rather than invent new class names — avoids a permanent naming split between "old"
and "new" returns-session concepts. `ReturnSessionTest.java` (14 cases, `a`–`o`) tests the old
flow; cases `(i)/(j)/(m)/(n)/(o)` test `detectReturnInTransitStuck` in `ExceptionService`, which
is **unrelated to the session model** (it only reads piece status + `return_received` events, not
session ids) and must keep passing unmodified. Cases `(a)/(b)/(c)/(d)/(e)/(f)/(g)/(h)/(k)/(l)`
exercise the waybill-gated session creation/verdict/finalize path being replaced — those get
rewritten against the new contract.

**Reused as-is** (per your instruction, confirmed these fit without modification):
- `ReturnService.restock()` / `markDamaged()` (`ReturnService.java:164-234`) — both already gate
  on `status == return_pending_inspection`, both already do the right Shopify call shape.
  Note: `markDamaged()` intentionally has **no** Shopify call
  (`ReturnService.java:203`: "Damaged pieces are NOT routed here... invariant preserved") — this
  is correct per the FR-17 v2 invariant ("pieces sent to damaged at inspection MUST NOT
  increment — never sellable in Shopify — do nothing"), **not** an
  `inventoryMoveQuantities available→damaged` call. Your contract text says "damage disposition
  → Shopify inventoryMoveQuantities available→damaged (reuse markDamaged())" — literally reusing
  `markDamaged()` unchanged means **no Shopify call fires** for this disposition, which is the
  invariant-compliant behavior, not the move-call the sentence's wording suggests. Flagging so we
  build it as "reuse markDamaged() unchanged, no Shopify call" rather than someone reading that
  line literally and wiring a move call that would violate FR-17 v2.
- `TrackingNumberNormalizer.normalize()` (unchanged, full file already fits the contract exactly).
- `InventoryLedger.recordLabelReprinted()` + `LabelService.generatePieceLabel()` — reuse via a new
  loop-per-piece controller method (mirroring `TransferService.java:600`'s pattern, **not**
  widening the old single-piece gated endpoint per that file's own comment at
  `docs/transfers-build-spec.md:54`: "Do NOT widen the returns gate").
- `ReturnService.neverReceived()` — wrapped, unmodified, as the analytics "expected but not
  scanned" tile.
- `ReturnController` (`/returns/intake`, `/pending`, `/pieces/{id}/restock`, `/pieces/{id}/damage`,
  `/never-received`) — `intake`/`pieces/{id}/restock`/`pieces/{id}/damage` are retired along with
  the three-tab UI (no other Java callers found beyond `ReturnController` and
  `ReturnSessionService.recordVerdict()`, both being replaced). `pending`/`countPending` stay,
  per §0(B).

**Receiving's delete-open-session semantics to mirror** (`ReceivingService.deleteSession()`,
`ReceivingService.java:83-98`, `ReceivingController.java:95-100`): validate `status='open'` (409
if not), delete child rows then the parent row, `kind`-filtered (here: table-scoped, since we're
not sharing `receipts`), `@ResponseStatus(NO_CONTENT)` → 204 empty body. The one deviation, per §2,
is the piece-revert step Receiving doesn't need (nothing exists yet at Receiving's delete time).

---

## 5. Endpoints

All backend methods do `TenantContext.require()` + explicit `AND tenant_id = ?` in every query
(belt-and-suspenders app-layer filter) on top of the DB-level `tenant_isolation` RLS policy on
each new table (added in `V73` per §1) — this is the uniform mechanism used everywhere else in the
codebase (e.g. `ReturnSessionService.java` today). Cross-tenant access resolves to 404 because the
row is simply invisible/absent under both layers, same as every existing session-scoped endpoint.

| Method | Path | Response | Roles |
|---|---|---|---|
| POST | `/returns/sessions` | 201, `{sessionId,...}` — 409 if an open session already exists (new: partial unique index + catch/translate the constraint violation) | isAuthenticated |
| DELETE | `/returns/sessions/{id}` | 204 — 409 if not open | OWNER, MANAGER (mirrors Receiving) |
| POST | `/returns/sessions/{id}/scan` | 200, resolved row (persisted item, or 422 foreign, or transient AWB-expected list) | isAuthenticated |
| POST | `/returns/sessions/{id}/items/{pieceId}/disposition` | 200 — 400 if damage w/o reason, 409 if disposition not legal for this item's status | isAuthenticated |
| POST | `/returns/sessions/{id}/pieces/{pieceId}/reprint-label` | 200, PDF bytes | isAuthenticated |
| POST | `/returns/sessions/{id}/close` | 200, summary — 409 with the blocking item ids/titles if any item is still `pending` | OWNER, MANAGER (matches Receiving's finalize gating) |
| GET | `/returns/sessions` | 200, `{items,total}`, `ORDER BY opened_at DESC, id DESC` | OWNER, MANAGER |
| GET | `/returns/sessions/{id}` | 200, session detail incl. persisted items + transient AWB-expected rows | isAuthenticated |
| GET | `/returns/analytics?from&to` | 200, derived-on-read stats (default 30d) | OWNER, MANAGER |
| GET | `/returns/pending?size=1` | 200 (**kept**, per §0(B)) | OWNER, MANAGER |

---

## 6. Frontend widget → source map

Mockup file (actual path): `design/Traced Returns Flow v2.dc.html`, 421 lines, 3 sections (A =
landing, B = immersive open-session, C = AR/RTL mirror of both).

**Important correction to the "reuse Receiving's worker-loop conventions" premise**: Receiving
does **not** use the full-screen immersive pattern. Its `SessionView` renders inside the single
`App.tsx:129` `<Layout><Receiving /></Layout>` route with no internal Layout-switch at all — it's
in-shell for both list and active-session views. **Fulfill** is the actual precedent for the
route-unwrapped / internal-view-switch pattern CLAUDE.md describes (`App.tsx:137` `<Fulfill />`
unwrapped at the route; `Fulfill.tsx` applies `<Layout>` only around its `queue` view internally).
The Returns rebuild needs Fulfill's structure, not Receiving's, to get the mockup's true
full-screen open-session card (own header bar, "x" close, no sidebar/topbar).

| Mockup state | Type | Source to port from |
|---|---|---|
| Landing loading (A3, shimmer) | restyle | new — no direct precedent, simple skeleton |
| Landing empty (A4) | restyle | pattern precedent: any existing empty-state card |
| Landing populated + analytics band + pagination (A1) | **new behavior** | `GET /returns/analytics` + `GET /returns/sessions` (both new) |
| Landing "unassigned pending returns" callout | **new behavior** | wraps `ReturnService.listPending()`-shaped query, new UI |
| Landing error (A6) | restyle | existing error-card pattern (retry button) |
| Landing zero-analytics (A5) | restyle | conditional render on analytics response |
| "Session already open" resume card (A2) | **new behavior** | driven by the new 409-on-create response |
| Open-session needs-decision, legal (B1 card 1) | **behavior port** | `Returns.tsx` `SessionTab` piece-card rendering (`Returns.tsx:99+`) restyled to full-screen; actions call new `/disposition` endpoint instead of `/verdict` |
| Open-session needs-decision, unexpected (B1 card 2) | **behavior port** | same, `unexpected` flag already exists as a concept in `ReturnService.intakeScan()`'s `isUnexpected` |
| Open-session dispositioned (B1 cards 3-4) | restyle | `Returns.tsx` `processed` piece rendering (`p.processed` check, `Returns.tsx:424`) |
| Open-session illegal-state/mismatch-only card | **not in mockup — needs new design** | no visual precedent exists for this exact state (frontend agent confirmed); reuse the needs-decision card shell but hide Restock/Damage, keep only "Not the real piece" |
| Rejected-scan toast (B2) | **new behavior** | new 422-foreign response; toast pattern precedent exists generically in the codebase |
| Empty just-opened session (B3) | restyle | simple centered state |
| Close-blocked footer callout | **behavior port** | mirrors `Returns.tsx`'s `outOfWindowPieceId`/error-banner conventions; new 409 body shape (blocking item ids/titles) |
| Close-summary card (B4) | **behavior port** | `Returns.tsx` `FinalizeSummary` rendering (`Returns.tsx:64`, `180`) restyled |
| Scan input | **behavior port, SAFETY-CRITICAL** | `Returns.tsx:250-259`/`508-517` already has two separate marked-SAFETY-CRITICAL scan inputs (waybill + intake) — the new unified single scan input should port this pattern once, not both; refocus-on-click precedent lives in `Fulfill.tsx:218-222`/`753-760` |
| Reprint button | **behavior port** | `Returns.tsx:72-84` blob-PDF-open pattern; gate widening per §3 needed to match mockup showing it pre-disposition |
| AR/RTL mirror (C) | restyle | logical props already established repo-wide (`Layout.tsx:126,184,193,195,213,238` etc.) — no `ml-`/`mr-` found in current Returns/Receiving code, convention is clean to continue |

**i18n**: existing `returns.*` key nesting in `en.json:506-568` (`returns.session.*`,
`returns.intake.*`, `returns.pending.*`, `returns.neverReceived.*`, `returns.col.*`) is the
richest precedent in the file — new keys should follow the same `returns.<screen>.<field>`
convention (e.g. `returns.sessions.*` for landing, `returns.openSession.*` for the immersive
loop), given §0(A)'s finding that `exceptions.*` is a much thinner (non-)precedent to avoid.

**Test infra**: since the Returns route is Layout-wrapped (`App.tsx:219`), and Layout fires its
own `/me` + `/exceptions/count` on mount (`Layout.tsx:106-108, 114-119`), the new test suite
**must** call `stubFetchWithShellDefaults` (`mockShellFetch.ts:46-49`) last in `beforeEach` — note
the *current* `returns.test.tsx` does **not** call it and still passes, which is worth a quick
sanity check when the new suite is built (confirm it isn't silently relying on some other
isolation that won't carry over to the full-screen Fulfill-style route).

---

## 7. Test plan

**Backend (Testcontainers, following `InventoryLedgerTest`'s `appUserTx`/`appUserLedger` role-split
pattern for RLS, and `TransferRlsTest`'s negative+positive-control pairing for brand-new tables
with no service layer yet):**

1. One-open-session constraint: concurrent `createSession()` calls → exactly one wins (unique
   index violation → 409), Testcontainers concurrency test.
2. Scan → each of the 5 legal-status rows → correct ledger event (`return_received`, right
   `from_status`) + item created `disposition=pending`, correct `unexpected` flag.
3. Illegal-state scan → item created, `unexpected=true`, **no** ledger transition — assert
   `piece_events` count unchanged for that piece.
4. Disposition=restock → ledger → `available`, `restocked` event, `onReturnInspectionAvailable`
   Shopify call fires (mock/verify).
5. Disposition=damaged, reason blank → 400, no ledger write.
6. Disposition=damaged, reason present → ledger → `damaged`, `damaged` event, **no** Shopify call
   (assert `ShopifyInventoryService` mock never invoked — per §4's markDamaged() clarification).
7. Disposition=mismatch on a legal item → no ledger transition, exception raised, no Shopify call.
8. Illegal-state item: attempt restock/damage → 409 (falls out of `restock()`/`markDamaged()`'s
   existing guard for free, per §2 — assert it, don't reimplement it).
9. Foreign scan (no match) → 422, no row created, no ledger write, exception logged with raw value
   + session_id.
10. Close blocked while any item `pending` → 409, body lists the exact blocking items; unblocked
    once all dispositioned (including via mismatch as the release valve).
11. Abandon reverts undispositioned items correctly for both sub-cases (§2): a session-caused
    transition reverts to its true prior status via the 4 new `ALLOWED` pairs +
    `return_cancelled` event; an adopted (already-pending) item is left untouched, no event
    written.
12. Webhook-arrived piece (already `return_pending_inspection` before any session existed) adopts
    into a session scan with **no** second status transition, but a second `return_received`
    event carrying the new session id (mirrors old `ReturnSessionService.recordVerdict()`'s
    `RETURN_PENDING_INSPECTION` case).
13. Unassigned-pending surfacing: pieces at `return_pending_inspection` with no open-session item
    anywhere show up in the landing query.
14. Reprint: reads the stored barcode (no new barcode minted — assert `generatePieceLabel()`'s
    `SELECT`, no `INSERT`/`UPDATE`), writes `label_reprinted` as a non-status append (`from_status
    == to_status`), works across the full legal-scan status set (§3's gate-widening).
15. Cross-tenant → not-found for every new endpoint (RLS test, `app_user` role, zero rows,
    `TransferRlsTest`-style negative + same-tenant positive control pair).
16. Sessions list paginated, correct `total`, `ORDER BY opened_at DESC, id DESC`.
17. **Regression guard**: `ReturnSessionTest.java` cases `(i)/(j)/(m)/(n)/(o)`
    (`detectReturnInTransitStuck` snooze/re-fire logic) must keep passing unmodified — these don't
    depend on the session-creation path being replaced.
18. `MigrationSmokeTest` and `NotTracedBackfillTest` updated counts (§1) — both must pass green.

**Frontend (Vitest + Testing Library, matching `returns.test.tsx`'s existing style):**
- Landing: loading / empty / populated+analytics / error / zero-analytics / already-open-resume.
- Open session: expected-awaiting (transient AWB row + reprint), needs-decision legal,
  needs-decision unexpected, dispositioned (restocked/damaged, single collapsed view — no stale
  duplicate card), illegal-state mismatch-only (no restock/damage buttons rendered), rejected-scan
  toast (close button stays enabled).
- Close-blocked (named blocking items shown, button disabled) → close-summary (single
  post-action view, old dispositioned-card view is NOT still mounted underneath).
- Abandon confirmation modal, mirroring Receiving's `deleteModalBodyWithLines`/`Empty` split
  (`Receiving.tsx:439-448`).
- All Layout-wrapped tests call `stubFetchWithShellDefaults` last in `beforeEach`.
- Each new test proven to fail first by reverting its corresponding fix, per your standing rule.
- Full-suite baseline check: **3 pre-existing failures**, confirmed today via `npx vitest run` —
  `blocklist.test.tsx` (`fb7`), `overview.test.tsx` (`ov2`, `ov5`) — none in `returns.test.tsx`.
  The rebuild's suite should show exactly these 3, not more.

**Manual/visual verify bar** (per your instructions): tsc clean, vite clean, vitest at the 3-failure
baseline, headless-Chromium EN/LTR + AR/RTL sweep with a long Arabic and long Latin name across
every state enumerated in §6, reprint PDF flagged for physical Xprinter verification (mixed
Arabic/Latin), no deploy.

---

## Summary of open decisions requiring your explicit confirmation before I write code

1. §0(A) — new returns exception types: hardcoded bilingual strings (existing precedent) or real
   i18n keys (fixes the gap locally, breaks precedent)?
2. §0(B) — keep `GET /returns/pending` alive for Overview, or repoint Overview to
   `/returns/analytics` and delete it?
3. §1 — confirmed: no backfill migration for old `receipts kind='returns'` history. OK to proceed
   on that basis?
4. §2 — the 4 new reverse `ALLOWED` transition pairs + `return_cancelled` event for abandon-revert
   — confirm this design (vs. any alternative you'd prefer).
5. §3 — widening `validateAndRecordReprint()`'s status gate beyond `return_pending_inspection`/
   `damaged` to cover the full legal-scan set, so awaiting-scan rows can reprint.
6. §4 — confirmed: rewrite `ReturnSessionService`/`ReturnSessionController` in place (same class
   names) rather than introduce new ones alongside the old, now-dead code.

STOP — awaiting approval before any code is written.
