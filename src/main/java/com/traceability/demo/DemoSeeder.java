package com.traceability.demo;

import com.traceability.identity.PolicyVersions;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FR-DEMO Day 1 — bootstraps and re-seeds the single shared, lead-gated demo tenant.
 *
 * Two entry points, both idempotent, both safe to call repeatedly:
 *   - {@link #ensureBootstrapped()} — one-time tenant + owner + 2 workers. Fixed,
 *     deterministic ids for every row (tenant/owner/workers/location) plus
 *     {@code ON CONFLICT (id) DO NOTHING} on every INSERT make this safe against a genuine
 *     concurrent race between {@link DemoBootstrapStartupListener} and {@link DemoReseedJob}'s
 *     cron tick both bootstrapping on the same boot (confirmed prod incident: both passed the
 *     EXISTS check against an empty table, both INSERTed, the loser hit a duplicate-key
 *     exception on {@code tenants_pkey}). Writes raw SQL directly rather than routing through
 *     {@code AuthRepository.createTenantWithOwner()}/{@code UserService.create()} — both are
 *     shared with real signup/user-management and must keep their own random-id,
 *     throw-on-conflict semantics unchanged for those paths. Never
 *     {@code provision_tenant_from_shopify} (that hatch exists to solve a chicken-and-egg UUID
 *     problem this seeder doesn't have — the demo tenant id is a fixed, known constant — and it
 *     forces a Shopify {@code stores} row into existence, which the demo tenant must not carry).
 *   - {@link #reseed()} — deletes every mutable row for the demo tenant and reloads the
 *     golden fixture, in ONE transaction on the raw BYPASSRLS owner connection. Called by
 *     {@link DemoReseedJob} on both the very first tick (bootstrap already happened, the
 *     tenant is empty, so the DELETEs are no-ops and the fixture load populates it) and
 *     every subsequent scheduled tick (clears whatever demo visitors mutated, reloads
 *     pristine data) — literally the same method both times, per the build spec.
 *
 * SAFETY (guardrail #1 of the spec): {@code reseed()} NEVER trusts a tenant id passed in —
 * it always re-resolves ":demo" from {@code is_demo = true} and hard-asserts exactly one
 * row, that it equals the fixed {@link #DEMO_TENANT_ID} constant, and that it is not one of
 * the two known production pilot tenants. A missing filter here would truncate a real
 * tenant; the assertion is deliberately paranoid (three independent checks) rather than
 * trusting any single signal.
 *
 * BYPASSRLS carve-out: {@code reseed()} runs on {@code ownerDs} (the same 2-connection
 * Flyway/JobRunr pool as every other cross-tenant admin job in this codebase —
 * {@link com.traceability.notifications.ExceptionDigestJob},
 * {@link com.traceability.notifications.ExceptionImmediateAlertJob}), NOT a new
 * SECURITY DEFINER function — this is the un-scoped "read across all tenants to resolve
 * :demo" step the hard assertion needs, mirrored from those two jobs' own
 * {@code SELECT id FROM tenants}. Every subsequent statement inside the transaction is
 * still explicitly {@code WHERE tenant_id = :demo} — bypassing RLS is not a license to
 * skip the filter, it is what makes the assertion step possible in the first place.
 *
 * Writing pieces/piece_events directly (not through {@link com.traceability.inventory.InventoryLedger})
 * is a deliberate, narrowly-scoped carve-out: the seeder constructs FIXTURE state for a
 * demo tenant, not a real business operation, and only ever touches rows already scoped to
 * {@link #DEMO_TENANT_ID}. See the CLAUDE.md note alongside the "two writers of
 * piece_events" invariant.
 */
@Service
public class DemoSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    /** Fixed, stable demo tenant id — never regenerated. Every reseed resolves back to this row. */
    public static final UUID DEMO_TENANT_ID = UUID.fromString("91c6027e-0b23-4c56-84a6-2a769315ed2d");

    /**
     * Known production pilot tenants (Jumi, The Snouts — see PickErrorReversalService for
     * the same Snouts constant). reseed() must never be able to resolve to either of these,
     * even in the event of an operator mistake flipping is_demo on a real row.
     */
    private static final Set<UUID> FORBIDDEN_TENANT_IDS = Set.of(
            UUID.fromString("07fc572c-2158-412d-ae31-ec61e22378b7"), // Jumi
            UUID.fromString("e785e5e4-2c5c-428e-afdd-d26d90754229")  // The Snouts
    );

    private static final String DEMO_TENANT_NAME  = "Traced Demo Store";
    private static final String DEMO_OWNER_EMAIL  = "demo-owner@tracedtech.invalid";
    private static final String DEMO_OWNER_NAME   = "Demo Owner";
    private static final String DEMO_WORKER_1_NAME = "Aya (Demo Worker)";
    private static final String DEMO_WORKER_1_PIN  = "4821";
    private static final String DEMO_WORKER_2_NAME = "Karim (Demo Worker)";
    private static final String DEMO_WORKER_2_PIN  = "1197";

    /**
     * Fixed, deterministic ids for every row ensureBootstrapped() creates — same reasoning
     * as DEMO_TENANT_ID: a concurrent second caller's INSERT targets the exact same primary
     * key, so ON CONFLICT (id) DO NOTHING makes the loser a no-op instead of a duplicate-key
     * exception. Never regenerated.
     */
    private static final UUID DEMO_OWNER_ID    = UUID.fromString("50d3c595-114f-42f4-9542-691a231650fe");
    private static final UUID DEMO_WORKER_1_ID = UUID.fromString("2547a91c-b876-4d31-850d-1402ef6f2343");
    private static final UUID DEMO_WORKER_2_ID = UUID.fromString("28dfc318-df62-4a3d-bd31-99503703e4c6");
    private static final UUID DEMO_LOCATION_ID = UUID.fromString("54796ed5-55e2-4404-8506-e76716e084a7");

    /**
     * Second demo location — transfers need a destination distinct from the fulfillment
     * warehouse (transfers.destination_location_id is NOT NULL; V64/V90 give round_trip
     * transfers no notion of "source" at all, only a destination). Non-fulfillment
     * (is_fulfillment=false — V61 allows only one true per tenant, already claimed by
     * DEMO_LOCATION_ID) so it never competes for that slot. Bootstrap-time, not
     * golden-fixture: locations is one of the 3 tables reseed() deliberately never
     * deletes (see DELETE_ORDER's javadoc), so this has to be idempotent-inserted here,
     * exactly like DEMO_LOCATION_ID, not (re)inserted in loadGoldenFixture().
     */
    private static final UUID DEMO_DESTINATION_LOCATION_ID = UUID.fromString("6a1f9d3e-8c47-4b12-9e05-3d7b9a2c4e81");

    /**
     * Every table carrying per-tenant mutable state, in strict FK-safe (child-before-parent)
     * delete order. tenants, users, and locations are the ONLY tenant-scoped tables NOT in
     * this list — they are the identity/fixture-anchor rows reseed() preserves. Derived from
     * the full information_schema FK graph (Step 0 diagnosis), not just RlsCoverageTest's
     * partial cleanPerTest() ordering.
     */
    private static final String[] DELETE_ORDER = {
        // Tier A — deepest leaves: reference pieces/orders/shipments/sessions, nothing references these.
        "shipment_status_history",
        "piece_events",
        "stock_take_scans",
        "stock_take_expected",
        "stock_take_scope_variants",
        "stock_take_shopify_syncs",
        "transfer_pieces",
        "return_session_items",
        "return_session_shipments",
        "pickup_shipments",
        // Tier B — parents of Tier A, still children of orders/pieces/sessions.
        "stock_take_sessions",
        "transfer_lines",
        "exchanges",
        "return_sessions",
        "pickups",
        "allocations",
        "order_notes",
        "label_reprints",
        "receipt_lines",
        // Tier C.
        "transfers",
        "order_items",
        "pieces",
        // Tier D.
        "shipments",
        "receipts",
        // Tier E.
        "orders",
        "courier_accounts",
        // Tier F — catalog (child before parent: adjustments/variants before products).
        "shopify_inventory_adjustments",
        "variants",
        "products",
        // Tier G — independent (only reference tenants/users, which are preserved).
        "unlinked_bosta_deliveries",
        "webhook_events",
        "shopify_webhook_events",
        "blocklist",
        "audit_log",
        "exception_notifications",
        "exception_resolutions",
        "magic_link_tokens",
        "password_reset_codes",
        "refresh_tokens",
        "piece_counters",
        // Tier H — parent of the catalog and of orders; must be last.
        "stores",
    };

    /** ~15-25 SKUs (here: 6 products, 20 variants) so every catalog/inventory screen has content. */
    private static final List<ProductSpec> CATALOG = List.of(
        new ProductSpec("Classic Cotton T-Shirt", "tshirt",
            List.of("TSHIRT-S", "TSHIRT-M", "TSHIRT-L", "TSHIRT-XL"),
            List.of("Small", "Medium", "Large", "X-Large")),
        new ProductSpec("Running Sneakers", "sneakers",
            List.of("SNEAKER-39", "SNEAKER-40", "SNEAKER-41", "SNEAKER-42"),
            List.of("EU 39", "EU 40", "EU 41", "EU 42")),
        new ProductSpec("Leather Crossbody Bag", "crossbody-bag",
            List.of("BAG-BLK", "BAG-BRN", "BAG-TAN"),
            List.of("Black", "Brown", "Tan")),
        new ProductSpec("Stainless Steel Water Bottle", "water-bottle",
            List.of("BOTTLE-500", "BOTTLE-750", "BOTTLE-1000"),
            List.of("500ml", "750ml", "1L")),
        new ProductSpec("Wireless Earbuds", "earbuds",
            List.of("EARBUD-WHT", "EARBUD-BLK", "EARBUD-BLU"),
            List.of("White", "Black", "Blue")),
        new ProductSpec("Ceramic Coffee Mug Set", "mug-set",
            List.of("MUGSET-2", "MUGSET-4", "MUGSET-6"),
            List.of("2-Piece", "4-Piece", "6-Piece"))
    );

    /** Realistic Egyptian customer name/phone per seeded order — FR-DEMO fix. */
    private record Customer(String name, String phone) {}

    /** #DEMO-Q1..Q10 (insertPickableOrders), indexed i-1. */
    private static final List<Customer> Q_CUSTOMERS = List.of(
        new Customer("Nour Hassan",    "+20 100 214 5533"),
        new Customer("Ahmed Fathy",    "+20 111 887 2210"),
        new Customer("Mariam Adel",    "+20 128 440 9187"),
        new Customer("Omar Khaled",    "+20 100 662 3390"),
        new Customer("Salma Ibrahim",  "+20 122 019 7745"),
        new Customer("Youssef Nabil",  "+20 114 553 8801"),
        new Customer("Habiba Sherif",  "+20 106 778 2394"),
        new Customer("Kareem Mostafa", "+20 101 330 5567"),
        new Customer("Farida Tarek",   "+20 127 904 1123"),
        new Customer("Mahmoud Ali",    "+20 112 246 8890")
    );

    /** #DEMO-T1..T3 (insertInTransitShipments), indexed i-1. */
    private static final List<Customer> T_CUSTOMERS = List.of(
        new Customer("Laila Sami",   "+20 100 558 7712"),
        new Customer("Hassan Gamal", "+20 128 113 4406"),
        new Customer("Dina Ashraf",  "+20 115 667 9028")
    );

    /** #DEMO-BLOCKED keeps its narrative name ("Repeat RTO Customer") — phone only. */
    private static final String BLOCKED_CUSTOMER_PHONE = "+20 106 000 4417";

    private final JdbcTemplate       jdbc;
    private final DataSource         ownerDs;
    private final PasswordEncoder    passwordEncoder;
    private final TransactionTemplate tx;

    public DemoSeeder(JdbcTemplate jdbc,
                       @FlywayDataSource DataSource ownerDs,
                       PasswordEncoder passwordEncoder,
                       PlatformTransactionManager txm) {
        this.jdbc            = jdbc;
        this.ownerDs         = ownerDs;
        this.passwordEncoder = passwordEncoder;
        this.tx              = new TransactionTemplate(txm);
    }

    // ==================================================================
    // Bootstrap — one-time, idempotent
    // ==================================================================

    /**
     * Creates the demo tenant + owner + 2 workers if they don't already exist. Safe to call
     * on every scheduled tick, on every application boot, and from two callers racing on the
     * same boot — the EXISTS check below is a fast-path OPTIMIZATION only (skip the password
     * hashing + DB round trip once bootstrapped); it is deliberately NOT the correctness
     * mechanism. Correctness comes from {@link #insertDemoFixtureIdempotent} using fixed ids
     * and a bare {@code ON CONFLICT DO NOTHING} on every statement, so a second caller that
     * also passed the EXISTS check (TOCTOU race) silently no-ops instead of throwing — even
     * against a stale leftover row that shares a natural key (email, location name) but not
     * the fixed id, e.g. a row committed by a pre-fix caller before this idempotency guard
     * existed. A narrower {@code ON CONFLICT (id)} only catches an id collision and left
     * {@code users_email_unique} (and, latently, {@code locations_name_unique} /
     * {@code locations_one_fulfillment_per_tenant}) unguarded — confirmed prod incident.
     */
    public void ensureBootstrapped() {
        // MUST run inside tx.execute(), not a bare jdbc call: TenantAwareConnection only fires
        // SET LOCAL app.current_tenant on a connection's setAutoCommit(true->false) transition.
        // A bare autocommit=true query never crosses that transition, so the GUC is never set
        // and this RLS-scoped read silently sees zero rows — confirmed prod bug (this EXISTS
        // check always evaluated false, silently re-running insertDemoFixtureIdempotent() on
        // every call). @Transactional on this method would NOT fix it either: the AOP proxy
        // begins the transaction (and fires the one-shot GUC-set) at method entry, BEFORE
        // TenantContext.runAs() below has set the ThreadLocal — the GUC would still be set from
        // a null tenant. TenantContext must be set (by runAs) BEFORE the transaction begins, so
        // tx.execute() has to be the inner call, exactly like insertDemoFixtureIdempotent() below.
        boolean exists = TenantContext.runAs(DEMO_TENANT_ID, () -> tx.execute(status -> Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM tenants WHERE id = ?)",
                        Boolean.class, DEMO_TENANT_ID))));
        if (exists) {
            return;
        }

        insertDemoFixtureIdempotent();
        log.info("Demo tenant bootstrapped: {}", DEMO_TENANT_ID);
    }

    /**
     * Raw, idempotent inserts for the tenant/owner/location/2 workers — every statement uses a
     * BARE {@code ON CONFLICT DO NOTHING} (no target list), which absorbs a violation of ANY
     * unique or exclusion constraint on that table, not just the {@code id} primary key. This
     * matters because a stale leftover row (committed by a pre-fix caller under the old
     * random-id code, during the exact race this idempotency guard exists to prevent) shares a
     * natural key — {@code users.email}, or {@code locations}' per-tenant name/fulfillment
     * uniqueness — without sharing the new fixed id, so a narrower {@code ON CONFLICT (id)}
     * would still throw on that other constraint. Confirmed prod incident on
     * {@code users_email_unique}; {@code locations_name_unique} and
     * {@code locations_one_fulfillment_per_tenant} are the same latent shape, pre-empted here
     * before they fire. Deliberately does NOT call
     * AuthRepository.createTenantWithOwner()/UserService.create() — see class javadoc.
     * Wrapped in one explicit transaction (TransactionTemplate, not a self-invoked
     * {@code @Transactional} method, which Spring's proxy would silently ignore) so
     * TenantAwareConnection reliably fires the GUC set before any RLS-checked write.
     */
    // Package-private (not private) so DemoSeederNaturalKeyCollisionTest can call it directly,
    // bypassing ensureBootstrapped()'s own EXISTS(tenants.id=...) gate. That gate can never let
    // a real caller reach this method while a natural-key-colliding users/locations row already
    // exists for DEMO_TENANT_ID: those tables' tenant_id is NOT NULL REFERENCES tenants(id), so
    // such a row can only exist once the tenant row itself does — which is exactly the condition
    // that makes the outer EXISTS check short-circuit first. No other behavior change.
    void insertDemoFixtureIdempotent() {
        String ownerPasswordHash = passwordEncoder.encode(randomUndisclosedPassword());
        String worker1PinHash    = passwordEncoder.encode(DEMO_WORKER_1_PIN);
        String worker2PinHash    = passwordEncoder.encode(DEMO_WORKER_2_PIN);
        Timestamp acceptedAt     = Timestamp.from(Instant.now());

        TenantContext.runAs(DEMO_TENANT_ID, () -> tx.execute(status -> {
            jdbc.update(
                    "INSERT INTO tenants (id, name, plan, status, is_demo) " +
                    "VALUES (?, ?, 'trial', 'trial', true) ON CONFLICT DO NOTHING",
                    DEMO_TENANT_ID, DEMO_TENANT_NAME);

            jdbc.update(
                    "INSERT INTO users " +
                    "(id, tenant_id, name, email, password_hash, role, " +
                    " accepted_privacy_version, accepted_terms_version, accepted_at) " +
                    "VALUES (?, ?, ?, ?, ?, 'owner', ?, ?, ?) ON CONFLICT DO NOTHING",
                    DEMO_OWNER_ID, DEMO_TENANT_ID, DEMO_OWNER_NAME, DEMO_OWNER_EMAIL,
                    ownerPasswordHash, PolicyVersions.PRIVACY, PolicyVersions.TERMS, acceptedAt);

            jdbc.update(
                    "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                    "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true) " +
                    "ON CONFLICT DO NOTHING",
                    DEMO_LOCATION_ID, DEMO_TENANT_ID);

            jdbc.update(
                    "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                    "VALUES (?, ?, 'Zamalek Showroom', 'showroom', false, false) " +
                    "ON CONFLICT DO NOTHING",
                    DEMO_DESTINATION_LOCATION_ID, DEMO_TENANT_ID);

            jdbc.update(
                    "INSERT INTO users (id, tenant_id, name, pin_code, role) " +
                    "VALUES (?, ?, ?, ?, 'worker') ON CONFLICT DO NOTHING",
                    DEMO_WORKER_1_ID, DEMO_TENANT_ID, DEMO_WORKER_1_NAME, worker1PinHash);

            jdbc.update(
                    "INSERT INTO users (id, tenant_id, name, pin_code, role) " +
                    "VALUES (?, ?, ?, ?, 'worker') ON CONFLICT DO NOTHING",
                    DEMO_WORKER_2_ID, DEMO_TENANT_ID, DEMO_WORKER_2_NAME, worker2PinHash);

            return null;
        }));
    }

    private static String randomUndisclosedPassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * FR-DEMO Day 2: the demo owner's user id is random per bootstrap (unlike
     * {@link #DEMO_TENANT_ID}, which is fixed) — the public demo-start endpoint resolves it
     * at request time rather than relying on a hardcoded constant. Plain RLS-scoped read
     * (tenant id is already known) — no DEFINER hatch needed.
     */
    public UUID resolveOwnerId() {
        // Same tx.execute()-inside-runAs() requirement as ensureBootstrapped()'s EXISTS check
        // above — see that comment. This was the confirmed prod 500: the bare (non-transactional)
        // version of this call never set the RLS GUC, so it always found zero rows regardless
        // of whether the owner row existed, throwing "Incorrect result size: expected 1, actual 0".
        return TenantContext.runAs(DEMO_TENANT_ID, () -> tx.execute(status -> jdbc.queryForObject(
                "SELECT id FROM users WHERE tenant_id = ? AND role = 'owner' AND active = true",
                UUID.class, DEMO_TENANT_ID)));
    }

    // ==================================================================
    // Reseed — the dangerous operation
    // ==================================================================

    /**
     * Deletes every mutable row for the demo tenant and reloads the golden fixture, in ONE
     * transaction on the raw BYPASSRLS owner connection. See the class javadoc for the
     * safety model. Called by both the first post-bootstrap load and every scheduled tick.
     */
    public void reseed() {
        try (Connection raw = ownerDs.getConnection()) {
            boolean originalAutoCommit = raw.getAutoCommit();
            raw.setAutoCommit(false);
            SingleConnectionDataSource single = new SingleConnectionDataSource(raw, true);
            JdbcTemplate ojdbc = new JdbcTemplate(single);
            try {
                UUID tenantId = resolveAndAssertDemoTenant(ojdbc);
                deleteMutableRows(ojdbc, tenantId);
                loadGoldenFixture(ojdbc, tenantId);
                raw.commit();
                log.info("Demo tenant reseeded: {}", tenantId);
            } catch (RuntimeException e) {
                raw.rollback();
                throw e;
            } finally {
                raw.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Demo reseed failed", e);
        }
    }

    /**
     * Guardrail #1: never trust a passed-in id. Re-resolve ":demo" from is_demo=true and
     * hard-assert (a) exactly one row, (b) it is not a known production pilot tenant, and
     * (c) it matches the fixed DEMO_TENANT_ID constant. Any failure aborts loudly — no
     * partial reseed, nothing deleted.
     */
    private UUID resolveAndAssertDemoTenant(JdbcTemplate ojdbc) {
        List<UUID> demoTenantIds = ojdbc.queryForList(
                "SELECT id FROM tenants WHERE is_demo = true", UUID.class);

        if (demoTenantIds.size() != 1) {
            throw new IllegalStateException(
                    "Demo reseed aborted: expected exactly 1 tenant with is_demo=true, found "
                    + demoTenantIds.size() + " (" + demoTenantIds + ")");
        }

        UUID resolved = demoTenantIds.get(0);

        if (FORBIDDEN_TENANT_IDS.contains(resolved)) {
            throw new IllegalStateException(
                    "Demo reseed aborted: is_demo=true resolved to " + resolved
                    + ", which is a known production pilot tenant");
        }

        if (!resolved.equals(DEMO_TENANT_ID)) {
            throw new IllegalStateException(
                    "Demo reseed aborted: is_demo=true resolved to " + resolved
                    + " but the fixed demo tenant id is " + DEMO_TENANT_ID);
        }

        return resolved;
    }

    private void deleteMutableRows(JdbcTemplate ojdbc, UUID tenantId) {
        for (String table : DELETE_ORDER) {
            ojdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenantId);
        }
    }

    // ==================================================================
    // Golden fixture
    // ==================================================================

    private void loadGoldenFixture(JdbcTemplate ojdbc, UUID tenantId) {
        UUID locationId = ojdbc.queryForObject(
                "SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment = true LIMIT 1",
                UUID.class, tenantId);

        // Self-heal: an already-bootstrapped tenant may predate DEMO_DESTINATION_LOCATION_ID
        // (confirmed prod incident — ensureBootstrapped()'s fast-path returns early for an
        // existing tenant, so a location added to insertDemoFixtureIdempotent() after that
        // tenant was first created never retroactively appears on it). This must run on
        // EVERY reseed, not just bootstrap, so it's idempotent (fixed id, ON CONFLICT DO
        // NOTHING, locations is never deleted by deleteMutableRows()) and unconditional here
        // — never gated behind ensureBootstrapped()'s EXISTS check. Runs before
        // insertTransfers() below, which requires this row to exist as a destination.
        ojdbc.update(
                "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                "VALUES (?, ?, 'Zamalek Showroom', 'showroom', false, false) " +
                "ON CONFLICT DO NOTHING",
                DEMO_DESTINATION_LOCATION_ID, tenantId);

        List<UUID> workerIds = ojdbc.queryForList(
                "SELECT id FROM users WHERE tenant_id = ? AND role = 'worker' ORDER BY created_at LIMIT 2",
                UUID.class, tenantId);
        if (workerIds.size() < 2) {
            throw new IllegalStateException(
                    "Demo reseed aborted: expected 2 demo workers, found " + workerIds.size()
                    + " — has ensureBootstrapped() run?");
        }

        UUID storeId = insertPlaceholderStore(ojdbc, tenantId);
        List<UUID> variantIds = insertCatalog(ojdbc, tenantId, storeId);

        AtomicInteger shortCodeSeq = new AtomicInteger(1);
        insertReceivedStock(ojdbc, tenantId, locationId, workerIds, variantIds, shortCodeSeq);
        insertPickableOrders(ojdbc, tenantId, storeId, variantIds);
        insertInTransitShipments(ojdbc, tenantId, storeId, workerIds, variantIds, shortCodeSeq);
        insertReturns(ojdbc, tenantId, locationId, workerIds, variantIds, shortCodeSeq);
        insertExceptions(ojdbc, tenantId, storeId, locationId, workerIds, variantIds, shortCodeSeq);
        insertReceivingSessions(ojdbc, tenantId, locationId, workerIds, variantIds, shortCodeSeq);
        insertTransfers(ojdbc, tenantId, locationId, DEMO_DESTINATION_LOCATION_ID, workerIds, variantIds, shortCodeSeq);
        insertPickups(ojdbc, tenantId, workerIds);
        insertStockTake(ojdbc, tenantId, locationId, workerIds);
        insertExchange(ojdbc, tenantId, variantIds);

        // ISSUE 1b fix: seed piece_counters PAST every short code this fixture just
        // raw-inserted (shortCodeSeq's final value), so a real finalize() on the demo
        // tenant (InventoryLedger.batchReceive()'s ON CONFLICT counter claim) never
        // collides with a fixture piece's short_code. DemoSeeder's raw inserts never
        // touch piece_counters themselves (they don't go through batchReceive at all)
        // — confirmed root cause of the prod "HTTP 400" finalize error. +50 buffer,
        // not a hardcoded constant, so this stays correct if the fixture grows later.
        ojdbc.update(
                "INSERT INTO piece_counters (tenant_id, last_value) VALUES (?, ?) " +
                "ON CONFLICT (tenant_id) DO UPDATE " +
                "SET last_value = GREATEST(piece_counters.last_value, EXCLUDED.last_value)",
                tenantId, (long) (shortCodeSeq.get() + 50));
    }

    /**
     * A store row is structurally required — products.store_id is NOT NULL. Kept
     * permanently 'disconnected' (fake, obviously-invalid shop_domain, no access token) so
     * StoreRepository.findActiveStoreByTenant() always returns empty for the demo tenant —
     * the same no-op path a store-less tenant gets, satisfying "no Shopify writes for the
     * demo tenant" without a real connection ever existing. Deleted and recreated fresh
     * every reseed, same as the rest of the catalog.
     */
    private UUID insertPlaceholderStore(JdbcTemplate ojdbc, UUID tenantId) {
        UUID storeId = UUID.randomUUID();
        ojdbc.update(
                "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                "VALUES (?, ?, 'shopify'::store_platform, 'demo-tracedtech.myshopify.invalid', " +
                "        'disconnected'::store_status)",
                storeId, tenantId);
        return storeId;
    }

    private List<UUID> insertCatalog(JdbcTemplate ojdbc, UUID tenantId, UUID storeId) {
        List<UUID> variantIds = new ArrayList<>();
        int productSeq = 1;
        for (ProductSpec spec : CATALOG) {
            UUID productId = UUID.randomUUID();
            String imageUrl = "https://cdn.shopify.com/s/files/1/0000/0002/products/demo-"
                    + spec.slug() + ".jpg";
            ojdbc.update(
                    "INSERT INTO products (id, tenant_id, store_id, external_id, title, status, image_url) " +
                    "VALUES (?, ?, ?, ?, ?, 'active', ?)",
                    productId, tenantId, storeId, "DEMO-PROD-" + productSeq, spec.title(), imageUrl);

            for (int i = 0; i < spec.variantSkus().size(); i++) {
                UUID variantId = UUID.randomUUID();
                int variantSeq = i + 1;
                // Realistic bare-numeric UPC/EAN — distinct from pieces.barcode (which stays
                // the standard "PC-"+ULID internal scan-label format used everywhere else).
                String upc = String.format("20%011d", (productSeq * 1000L) + variantSeq);
                BigDecimal price = new BigDecimal("199.00").add(new BigDecimal(variantSeq * 25));
                ojdbc.update(
                        "INSERT INTO variants " +
                        "(id, tenant_id, product_id, external_id, sku, title, upc_barcode, price) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        variantId, tenantId, productId, "DEMO-VAR-" + productSeq + "-" + variantSeq,
                        spec.variantSkus().get(i), spec.variantTitles().get(i), upc, price);
                variantIds.add(variantId);
            }
            productSeq++;
        }
        return variantIds;
    }

    /** 3 available pieces per variant at the warehouse — the on-shelf sellable pool. */
    private void insertReceivedStock(JdbcTemplate ojdbc, UUID tenantId, UUID locationId,
                                      List<UUID> workerIds, List<UUID> variantIds,
                                      AtomicInteger shortCodeSeq) {
        int i = 0;
        for (UUID variantId : variantIds) {
            for (int k = 0; k < 3; k++) {
                String pieceId = UlidGenerator.generate();
                UUID actor = workerIds.get(i % workerIds.size());
                insertPiece(ojdbc, tenantId, pieceId, variantId, "available", locationId, null,
                        shortCodeSeq.getAndIncrement());
                insertPieceEvent(ojdbc, tenantId, pieceId, "received", actor, null, null, locationId,
                        null, "available", daysAgo(i % 5));
                i++;
            }
        }
    }

    /** A full pickable/gatherable backlog — unallocated demand behind a 'created' forward shipment. */
    private void insertPickableOrders(JdbcTemplate ojdbc, UUID tenantId, UUID storeId,
                                       List<UUID> variantIds) {
        for (int i = 1; i <= 10; i++) {
            UUID orderId = UUID.randomUUID();
            UUID variantId = variantIds.get((i - 1) % variantIds.size());
            Customer customer = Q_CUSTOMERS.get(i - 1);

            ojdbc.update(
                    "INSERT INTO orders (id, tenant_id, store_id, external_id, number, customer_name, " +
                    "                    customer_phone, status, payment_method, placed_at, on_hold) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 'new'::order_status, 'cod'::order_payment_method, " +
                    "        now() - make_interval(hours => ?), false)",
                    orderId, tenantId, storeId, "DEMO-ORDER-Q" + i, "#DEMO-Q" + i,
                    customer.name(), customer.phone(), i * 3);

            ojdbc.update(
                    "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?)",
                    tenantId, orderId, variantId, 1 + (i % 2));

            ojdbc.update(
                    "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
                    "                       internal_state, shipment_leg) " +
                    "VALUES (?, ?, 'bosta'::courier_provider, ?, 'created'::shipment_internal_state, 'forward')",
                    tenantId, orderId, bareTrackingNumber(999000000000L + i));
        }
    }

    /** A few synthetic in-transit Bosta shipments — bare-numeric tracking, no real Bosta call. */
    private void insertInTransitShipments(JdbcTemplate ojdbc, UUID tenantId, UUID storeId,
                                           List<UUID> workerIds, List<UUID> variantIds,
                                           AtomicInteger shortCodeSeq) {
        for (int i = 1; i <= 3; i++) {
            UUID orderId = UUID.randomUUID();
            UUID variantId = variantIds.get(i % variantIds.size());
            UUID actor = workerIds.get(i % workerIds.size());
            Customer customer = T_CUSTOMERS.get(i - 1);

            ojdbc.update(
                    "INSERT INTO orders (id, tenant_id, store_id, external_id, number, customer_name, " +
                    "                    customer_phone, status, payment_method, placed_at, on_hold) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 'with_courier'::order_status, 'cod'::order_payment_method, " +
                    "        now() - interval '2 days', false)",
                    orderId, tenantId, storeId, "DEMO-ORDER-T" + i, "#DEMO-T" + i,
                    customer.name(), customer.phone());

            UUID orderItemId = UUID.randomUUID();
            ojdbc.update(
                    "INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) " +
                    "VALUES (?, ?, ?, ?, 1)",
                    orderItemId, tenantId, orderId, variantId);

            String pieceId = UlidGenerator.generate();
            insertPiece(ojdbc, tenantId, pieceId, variantId, "with_courier", null, orderId,
                    shortCodeSeq.getAndIncrement());
            insertPieceEvent(ojdbc, tenantId, pieceId, "received", actor, null, null, null,
                    null, "available", daysAgo(3));

            UUID allocationId = UUID.randomUUID();
            ojdbc.update(
                    "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status, allocated_by) " +
                    "VALUES (?, ?, ?, ?, 'packed'::allocation_status, ?)",
                    allocationId, tenantId, orderItemId, pieceId, actor);
            insertPieceEvent(ojdbc, tenantId, pieceId, "pack", actor, orderId, null, null,
                    "available", "packed", daysAgo(2));

            UUID shipmentId = UUID.randomUUID();
            ojdbc.update(
                    "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, " +
                    "                       internal_state, shipment_leg) " +
                    "VALUES (?, ?, ?, 'bosta'::courier_provider, ?, " +
                    "        'with_courier'::shipment_internal_state, 'forward')",
                    shipmentId, tenantId, orderId, bareTrackingNumber(999100000000L + i));
            insertPieceEvent(ojdbc, tenantId, pieceId, "with_courier", actor, orderId, shipmentId, null,
                    "packed", "with_courier", daysAgo(1));
        }
    }

    /**
     * ISSUE 3 fixture — 2 return_sessions total (NOT 3; see below), varied states:
     *   - session 1 (open): 2 'pending' items (unchanged) + 1 NEW 'damaged' item
     *     (Omar Khaled's return) — still just ONE open session.
     *   - session 2 (closed): 1 'restocked' item (Salma Ibrahim's return).
     *
     * DEVIATION FROM SPEC, FLAGGED: the spec asked for 3 sessions (existing +2),
     * with the 3rd described as "open/pending/damaged". return_sessions has
     * return_sessions_one_open_per_tenant — a UNIQUE INDEX on (tenant_id) WHERE
     * status='open' (V73__return_sessions.sql) — a real tenant can never have two
     * open return sessions at once, and inserting a second one here would throw a
     * unique violation and abort the ENTIRE reseed transaction. Resolved by adding
     * the 3rd item's disposition ('damaged') to the ALREADY-open session 1 instead
     * of a genuinely separate session row — this still shows "open session with a
     * mix of pending and damaged items" on the Returns screen, just as one session,
     * not two. Total return_sessions rows: 2 (1 open, 1 closed), not 3.
     *
     * return_session_items.disposition values (confirmed via V73__return_sessions.sql
     * CHECK constraint): 'pending', 'restocked', 'damaged', 'mismatch'.
     * return_sessions.status values: 'open', 'closed', 'abandoned'.
     * Neither table has a customer-facing column (return_session_items only
     * references piece_id, no order/customer link) — the Returns screen doesn't
     * display a customer name for a return item today, so the given customer names
     * are attached via return_sessions.note (a free-text field that already exists
     * for exactly this kind of context), not a fabricated order/customer_name link.
     */
    private void insertReturns(JdbcTemplate ojdbc, UUID tenantId, UUID locationId,
                                List<UUID> workerIds, List<UUID> variantIds,
                                AtomicInteger shortCodeSeq) {
        UUID sessionId = UUID.randomUUID();
        UUID opener = workerIds.get(0);
        ojdbc.update(
                "INSERT INTO return_sessions (id, tenant_id, status, opened_by, opened_at, note) " +
                "VALUES (?, ?, 'open', ?, now() - interval '1 day', " +
                "        'Includes a damaged item from Omar Khaled''s return.')",
                sessionId, tenantId, opener);

        for (int i = 1; i <= 2; i++) {
            UUID variantId = variantIds.get(i % variantIds.size());
            String pieceId = UlidGenerator.generate();
            UUID actor = workerIds.get(i % workerIds.size());

            insertPiece(ojdbc, tenantId, pieceId, variantId, "return_pending_inspection", locationId, null,
                    shortCodeSeq.getAndIncrement());
            insertPieceEvent(ojdbc, tenantId, pieceId, "received", actor, null, null, locationId,
                    null, "available", daysAgo(6));
            insertPieceEvent(ojdbc, tenantId, pieceId, "return_received", actor, null, null, locationId,
                    "available", "return_pending_inspection", daysAgo(1));

            ojdbc.update(
                    "INSERT INTO return_session_items " +
                    "(tenant_id, session_id, piece_id, scanned_by, scan_source, disposition) " +
                    "VALUES (?, ?, ?, ?, 'barcode', 'pending')",
                    tenantId, sessionId, pieceId, actor);
        }

        // 3rd item in the SAME (still open) session — Omar Khaled's damaged return,
        // already disposed (not pending).
        {
            UUID variantId = variantIds.get(2 % variantIds.size());
            String pieceId = UlidGenerator.generate();
            UUID actor = workerIds.get(0);
            insertPiece(ojdbc, tenantId, pieceId, variantId, "damaged", locationId, null,
                    shortCodeSeq.getAndIncrement());
            insertPieceEvent(ojdbc, tenantId, pieceId, "received", actor, null, null, locationId,
                    null, "available", daysAgo(8));
            insertPieceEvent(ojdbc, tenantId, pieceId, "return_received", actor, null, null, locationId,
                    "available", "return_pending_inspection", daysAgo(2));
            insertPieceEvent(ojdbc, tenantId, pieceId, "adjusted", actor, null, null, locationId,
                    "return_pending_inspection", "damaged", daysAgo(2));
            ojdbc.update(
                    "INSERT INTO return_session_items " +
                    "(tenant_id, session_id, piece_id, scanned_by, scan_source, disposition, " +
                    " disposition_at, disposition_by, damage_reason) " +
                    "VALUES (?, ?, ?, ?, 'barcode', 'damaged', now() - interval '2 days', ?, " +
                    "        'Crushed box on arrival')",
                    tenantId, sessionId, pieceId, actor, actor);
        }

        // Session 2 — closed, one restocked item, Salma Ibrahim's return.
        UUID closedSessionId = UUID.randomUUID();
        UUID closer = workerIds.get(1);
        ojdbc.update(
                "INSERT INTO return_sessions " +
                "(id, tenant_id, status, opened_by, opened_at, closed_by, closed_at, note) " +
                "VALUES (?, ?, 'closed', ?, now() - interval '4 days', ?, now() - interval '3 days', " +
                "        'Restocked return from Salma Ibrahim.')",
                closedSessionId, tenantId, closer, closer);

        UUID restockedVariantId = variantIds.get(3 % variantIds.size());
        String restockedPieceId = UlidGenerator.generate();
        insertPiece(ojdbc, tenantId, restockedPieceId, restockedVariantId, "available", locationId, null,
                shortCodeSeq.getAndIncrement());
        insertPieceEvent(ojdbc, tenantId, restockedPieceId, "received", closer, null, null, locationId,
                null, "available", daysAgo(10));
        insertPieceEvent(ojdbc, tenantId, restockedPieceId, "return_received", closer, null, null, locationId,
                "available", "return_pending_inspection", daysAgo(4));
        insertPieceEvent(ojdbc, tenantId, restockedPieceId, "restocked", closer, null, null, locationId,
                "return_pending_inspection", "available", daysAgo(3));
        ojdbc.update(
                "INSERT INTO return_session_items " +
                "(tenant_id, session_id, piece_id, scanned_by, scan_source, disposition, " +
                " disposition_at, disposition_by) " +
                "VALUES (?, ?, ?, ?, 'barcode', 'restocked', now() - interval '3 days', ?)",
                tenantId, closedSessionId, restockedPieceId, closer, closer);
    }

    /** A couple of live exceptions: one CRITICAL (lost piece), one LOW (blocked customer). */
    private void insertExceptions(JdbcTemplate ojdbc, UUID tenantId, UUID storeId, UUID locationId,
                                   List<UUID> workerIds, List<UUID> variantIds,
                                   AtomicInteger shortCodeSeq) {
        UUID actor = workerIds.get(0);

        // ExceptionService.detectLost — a piece at status='lost'.
        String lostPieceId = UlidGenerator.generate();
        insertPiece(ojdbc, tenantId, lostPieceId, variantIds.get(0), "lost", null, null,
                shortCodeSeq.getAndIncrement());
        insertPieceEvent(ojdbc, tenantId, lostPieceId, "received", actor, null, null, locationId,
                null, "available", daysAgo(10));
        insertPieceEvent(ojdbc, tenantId, lostPieceId, "adjusted", actor, null, null, locationId,
                "available", "lost", daysAgo(2));

        // ExceptionService.detectBlocked — an order with on_hold=true.
        UUID blockedOrderId = UUID.randomUUID();
        ojdbc.update(
                "INSERT INTO orders (id, tenant_id, store_id, external_id, number, customer_name, " +
                "                    customer_phone, status, payment_method, placed_at, on_hold, hold_reason) " +
                "VALUES (?, ?, ?, 'DEMO-ORDER-BLOCKED', '#DEMO-BLOCKED', 'Repeat RTO Customer', ?, " +
                "        'new'::order_status, 'cod'::order_payment_method, now() - interval '1 day', " +
                "        true, 'Blocked customer')",
                blockedOrderId, tenantId, storeId, BLOCKED_CUSTOMER_PHONE);
        ojdbc.update(
                "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)",
                tenantId, blockedOrderId, variantIds.get(1));
    }

    /**
     * ISSUE 4 fixture — 2 receiving sessions:
     *   - session 1: 'finalized', 3 lines (qty 12/8/20), pieces raw-inserted
     *     directly (receipt_id set — see insertPieceWithReceipt) — display-only,
     *     never goes through ReceivingService.finalize()/InventoryLedger.batchReceive().
     *   - session 2: 'open', 2 lines (qty 5/5), NO pieces — a real visitor can open
     *     it and click Finalize. That call DOES go through the real finalize()/
     *     batchReceive() path, which is exactly what ISSUE 1b's piece_counters seed
     *     (in loadGoldenFixture(), after this method runs) protects against
     *     colliding with every short_code this method and the rest of the fixture
     *     already claimed.
     */
    private void insertReceivingSessions(JdbcTemplate ojdbc, UUID tenantId, UUID locationId,
                                          List<UUID> workerIds, List<UUID> variantIds,
                                          AtomicInteger shortCodeSeq) {
        UUID receiver = workerIds.get(0);

        // Session 1 — finalized, display-only.
        UUID finalizedSessionId = UUID.randomUUID();
        ojdbc.update(
                "INSERT INTO receipts (id, tenant_id, reference, supplier_name, received_by, " +
                "                      location_id, status, finalized_at) " +
                "VALUES (?, ?, 'REC-DEMO-1', 'Cairo Cotton Co.', ?, ?, 'finalized', now() - interval '5 days')",
                finalizedSessionId, tenantId, receiver, locationId);

        int[] finalizedQtys = { 12, 8, 20 };
        for (int i = 0; i < finalizedQtys.length; i++) {
            UUID variantId = variantIds.get(i % variantIds.size());
            int qty = finalizedQtys[i];
            ojdbc.update(
                    "INSERT INTO receipt_lines (tenant_id, receipt_id, variant_id, quantity) " +
                    "VALUES (?, ?, ?, ?)",
                    tenantId, finalizedSessionId, variantId, qty);
            for (int u = 0; u < qty; u++) {
                String pieceId = UlidGenerator.generate();
                insertPieceWithReceipt(ojdbc, tenantId, pieceId, variantId, finalizedSessionId,
                        "available", locationId, shortCodeSeq.getAndIncrement());
                insertPieceEvent(ojdbc, tenantId, pieceId, "received", receiver, null, null, locationId,
                        null, "available", daysAgo(5));
            }
        }

        // Session 2 — open, interactive: a demo visitor can add/edit lines and
        // Finalize it for real (no pieces yet — finalize() creates them).
        UUID openSessionId = UUID.randomUUID();
        ojdbc.update(
                "INSERT INTO receipts (id, tenant_id, reference, supplier_name, received_by, location_id) " +
                "VALUES (?, ?, 'REC-DEMO-2', 'Nile Textiles', ?, ?)",
                openSessionId, tenantId, receiver, locationId);

        int[] openQtys = { 5, 5 };
        for (int i = 0; i < openQtys.length; i++) {
            UUID variantId = variantIds.get((i + 1) % variantIds.size());
            ojdbc.update(
                    "INSERT INTO receipt_lines (tenant_id, receipt_id, variant_id, quantity) " +
                    "VALUES (?, ?, ?, ?)",
                    tenantId, openSessionId, variantId, openQtys[i]);
        }
    }

    /**
     * FR-DEMO — 2 transfers to the second demo location, both transfer_mode='round_trip'
     * (V64 transfer_type/status CHECK; V89 transfer_mode/outcome CHECK — round_trip needs
     * no source_location_id, that column is relocate_return-only per V90). Event types and
     * outcome→piece-status mapping match TransferService exactly (scanOut()'s
     * "transferred_out", reconcileScanBack()'s "returned_from_transfer"/
     * "condemned_at_vendor", outcome values 'returned_good'/'condemned').
     *
     * Transfer 1 — open, showroom: 2 pieces still out (outcome NULL, status
     * out_on_transfer, current_location_id = the showroom) — this is what
     * transfer_pieces_one_active (UNIQUE (piece_id) WHERE outcome IS NULL) protects, and
     * why these pieces are never touched by any other insert method.
     * Transfer 2 — closed, dryclean: 2 pieces sent, 1 returned_good (back to available at
     * the warehouse), 1 condemned (damaged, still physically back at the warehouse per
     * reconcileScanBack()'s own current_location_id convention).
     */
    private void insertTransfers(JdbcTemplate ojdbc, UUID tenantId, UUID fulfillmentLocationId,
                                  UUID destinationLocationId, List<UUID> workerIds,
                                  List<UUID> variantIds, AtomicInteger shortCodeSeq) {
        UUID creator = workerIds.get(0);
        UUID closer = workerIds.get(1);

        // Transfer 1 — open, round_trip, 2 pieces still out.
        UUID openTransferId = UUID.randomUUID();
        ojdbc.update(
                "INSERT INTO transfers (id, tenant_id, transfer_type, transfer_mode, " +
                "                       destination_location_id, note, created_by, created_at) " +
                "VALUES (?, ?, 'showroom', 'round_trip', ?, 'Window display rotation', ?, " +
                "        now() - interval '3 days')",
                openTransferId, tenantId, destinationLocationId, creator);

        for (int i = 0; i < 2; i++) {
            UUID variantId = variantIds.get(i);
            UUID lineId = UUID.randomUUID();
            ojdbc.update(
                    "INSERT INTO transfer_lines (id, tenant_id, transfer_id, variant_id, qty_out) " +
                    "VALUES (?, ?, ?, ?, 1)",
                    lineId, tenantId, openTransferId, variantId);

            String pieceId = UlidGenerator.generate();
            insertPiece(ojdbc, tenantId, pieceId, variantId, "out_on_transfer", destinationLocationId, null,
                    shortCodeSeq.getAndIncrement());
            insertPieceEvent(ojdbc, tenantId, pieceId, "received", creator, null, null, fulfillmentLocationId,
                    null, "available", daysAgo(9));
            insertPieceEvent(ojdbc, tenantId, pieceId, "transferred_out", creator, null, null,
                    destinationLocationId, "available", "out_on_transfer", daysAgo(3));

            ojdbc.update(
                    "INSERT INTO transfer_pieces (id, tenant_id, transfer_id, line_id, piece_id) " +
                    "VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), tenantId, openTransferId, lineId, pieceId);
        }

        // Transfer 2 — closed, round_trip, 2 pieces sent: 1 returned good, 1 condemned.
        UUID closedTransferId = UUID.randomUUID();
        ojdbc.update(
                "INSERT INTO transfers (id, tenant_id, transfer_type, transfer_mode, " +
                "                       destination_location_id, status, note, created_by, created_at, " +
                "                       closed_by, closed_at) " +
                "VALUES (?, ?, 'dryclean', 'round_trip', ?, 'closed', 'Dry-clean batch', ?, " +
                "        now() - interval '10 days', ?, now() - interval '2 days')",
                closedTransferId, tenantId, destinationLocationId, creator, closer);

        UUID sharedVariantId = variantIds.get(2);
        UUID closedLineId = UUID.randomUUID();
        ojdbc.update(
                "INSERT INTO transfer_lines " +
                "(id, tenant_id, transfer_id, variant_id, qty_out, qty_returned_good, qty_condemned) " +
                "VALUES (?, ?, ?, ?, 2, 1, 1)",
                closedLineId, tenantId, closedTransferId, sharedVariantId);

        String goodPieceId = UlidGenerator.generate();
        insertPiece(ojdbc, tenantId, goodPieceId, sharedVariantId, "available", fulfillmentLocationId, null,
                shortCodeSeq.getAndIncrement());
        insertPieceEvent(ojdbc, tenantId, goodPieceId, "received", creator, null, null, fulfillmentLocationId,
                null, "available", daysAgo(15));
        insertPieceEvent(ojdbc, tenantId, goodPieceId, "transferred_out", creator, null, null,
                destinationLocationId, "available", "out_on_transfer", daysAgo(10));
        insertPieceEvent(ojdbc, tenantId, goodPieceId, "returned_from_transfer", closer, null, null,
                fulfillmentLocationId, "out_on_transfer", "available", daysAgo(2));
        ojdbc.update(
                "INSERT INTO transfer_pieces " +
                "(id, tenant_id, transfer_id, line_id, piece_id, outcome, outcome_verified, outcome_at, outcome_by) " +
                "VALUES (?, ?, ?, ?, ?, 'returned_good', true, now() - interval '2 days', ?)",
                UUID.randomUUID(), tenantId, closedTransferId, closedLineId, goodPieceId, closer);

        String condemnedPieceId = UlidGenerator.generate();
        insertPiece(ojdbc, tenantId, condemnedPieceId, sharedVariantId, "damaged", fulfillmentLocationId, null,
                shortCodeSeq.getAndIncrement());
        insertPieceEvent(ojdbc, tenantId, condemnedPieceId, "received", creator, null, null, fulfillmentLocationId,
                null, "available", daysAgo(15));
        insertPieceEvent(ojdbc, tenantId, condemnedPieceId, "transferred_out", creator, null, null,
                destinationLocationId, "available", "out_on_transfer", daysAgo(10));
        insertPieceEvent(ojdbc, tenantId, condemnedPieceId, "condemned_at_vendor", closer, null, null,
                fulfillmentLocationId, "out_on_transfer", "damaged", daysAgo(2));
        ojdbc.update(
                "INSERT INTO transfer_pieces " +
                "(id, tenant_id, transfer_id, line_id, piece_id, outcome, outcome_verified, outcome_at, outcome_by) " +
                "VALUES (?, ?, ?, ?, ?, 'condemned', true, now() - interval '2 days', ?)",
                UUID.randomUUID(), tenantId, closedTransferId, closedLineId, condemnedPieceId, closer);
    }

    /**
     * FR-DEMO — 2 pickups (FR-16 session model). scheduled_date is mandatory on every
     * row: PickupSessionService.listSessions()/getSession() both call
     * rs.getDate("scheduled_date").toLocalDate() unconditionally — a NULL here 500s the
     * Pickups screen. courier_account_id stays NULL (nullable — the demo deliberately has
     * no courier_accounts row; Bosta pickups are merchant-managed in Bosta's own
     * dashboard per FR-16 Phase 1, Traced never creates one). pickup_shipments rows are
     * raw-inserted directly against the fixture's own already-seeded shipments, bypassing
     * scan()'s allocation/piece-state re-validation (not needed for a display fixture).
     */
    private void insertPickups(JdbcTemplate ojdbc, UUID tenantId, List<UUID> workerIds) {
        UUID opener = workerIds.get(0);
        UUID closer = workerIds.get(1);

        // Open pickup — scheduled tomorrow, 2 pickable-order shipments already scanned in.
        UUID openPickupId = UUID.randomUUID();
        ojdbc.update(
                "INSERT INTO pickups (id, tenant_id, session_status, scheduled_date, " +
                "                     scheduled_time_slot, notes, opened_by_user_id) " +
                "VALUES (?, ?, 'open', CURRENT_DATE + 1, '12:00 PM - 4:00 PM', " +
                "        'Afternoon pickup — packages ready at the counter', ?)",
                openPickupId, tenantId, opener);

        List<UUID> openShipmentIds = ojdbc.queryForList(
                "SELECT id FROM shipments WHERE tenant_id = ? AND tracking_number IN (?, ?)",
                UUID.class, tenantId,
                bareTrackingNumber(999000000000L + 1), bareTrackingNumber(999000000000L + 2));
        for (UUID shipmentId : openShipmentIds) {
            ojdbc.update(
                    "INSERT INTO pickup_shipments (pickup_id, shipment_id, tenant_id, scanned_at, scanned_by_user_id) " +
                    "VALUES (?, ?, ?, now() - interval '2 hours', ?)",
                    openPickupId, shipmentId, tenantId, opener);
        }

        // Closed pickup — yesterday morning, the 3 in-transit shipments already handed to courier.
        UUID closedPickupId = UUID.randomUUID();
        ojdbc.update(
                "INSERT INTO pickups (id, tenant_id, session_status, scheduled_date, scheduled_time_slot, " +
                "                     notes, opened_by_user_id, closed_by_user_id, closed_at, no_of_packages) " +
                "VALUES (?, ?, 'closed', CURRENT_DATE - 1, '9:00 AM - 12:00 PM', " +
                "        'Morning pickup — completed', ?, ?, now() - interval '1 day', 3)",
                closedPickupId, tenantId, opener, closer);

        List<UUID> closedShipmentIds = ojdbc.queryForList(
                "SELECT id FROM shipments WHERE tenant_id = ? AND tracking_number IN (?, ?, ?)",
                UUID.class, tenantId,
                bareTrackingNumber(999100000000L + 1), bareTrackingNumber(999100000000L + 2),
                bareTrackingNumber(999100000000L + 3));
        for (UUID shipmentId : closedShipmentIds) {
            ojdbc.update(
                    "INSERT INTO pickup_shipments (pickup_id, shipment_id, tenant_id, scanned_at, scanned_by_user_id) " +
                    "VALUES (?, ?, ?, now() - interval '1 day', ?)",
                    closedPickupId, shipmentId, tenantId, closer);
        }
    }

    /**
     * FR-DEMO — 2 stock-take sessions over the demo's real, already-seeded piece
     * population at the fulfillment location (scope_type='all', mirroring
     * StockTakeService.snapshotExpectedPopulation()'s own query exactly, so the snapshot
     * is never a hand-picked subset that could drift from what the fixture actually
     * contains). Session 1 — finalized, complete_count=true, ~92% counted, 0 write-offs
     * (StockTakeReconciliationService.finalizeSession() lands a 0-delta claim straight at
     * 'pushed' with no JobRunr job enqueued — this fixture mirrors that exact terminal
     * shape). Session 2 — open, ~40% counted, in progress.
     */
    private void insertStockTake(JdbcTemplate ojdbc, UUID tenantId, UUID locationId, List<UUID> workerIds) {
        UUID opener = workerIds.get(0);
        UUID closer = workerIds.get(1);

        UUID finalizedSessionId = UUID.randomUUID();
        ojdbc.update(
                "INSERT INTO stock_take_sessions " +
                "(id, tenant_id, status, scope_type, location_id, complete_count, opened_by, opened_at, " +
                " finalized_by, finalized_at, note) " +
                "VALUES (?, ?, 'finalized', 'all', ?, true, ?, now() - interval '2 days', ?, " +
                "        now() - interval '1 day', 'Monthly full count')",
                finalizedSessionId, tenantId, locationId, opener, closer);
        snapshotAllPiecesAtLocation(ojdbc, tenantId, finalizedSessionId, locationId);
        scanFractionOfExpected(ojdbc, tenantId, finalizedSessionId, closer, 92);
        ojdbc.update(
                "INSERT INTO stock_take_shopify_syncs (id, tenant_id, session_id, status, payload) " +
                "VALUES (gen_random_uuid(), ?, ?, 'pushed', ?::jsonb)",
                tenantId, finalizedSessionId, "{\"locationId\":\"" + locationId + "\",\"deltas\":{}}");

        UUID openSessionId = UUID.randomUUID();
        ojdbc.update(
                "INSERT INTO stock_take_sessions " +
                "(id, tenant_id, status, scope_type, location_id, opened_by, opened_at, note) " +
                "VALUES (?, ?, 'open', 'all', ?, ?, now() - interval '2 hours', 'Spot count in progress')",
                openSessionId, tenantId, locationId, opener);
        snapshotAllPiecesAtLocation(ojdbc, tenantId, openSessionId, locationId);
        scanFractionOfExpected(ojdbc, tenantId, openSessionId, opener, 40);
    }

    private void snapshotAllPiecesAtLocation(JdbcTemplate ojdbc, UUID tenantId, UUID sessionId, UUID locationId) {
        ojdbc.update(
                "INSERT INTO stock_take_expected (tenant_id, session_id, piece_id, variant_id, status_at_open) " +
                "SELECT ?, ?, p.id, p.variant_id, p.status::text " +
                "FROM pieces p WHERE p.tenant_id = ? AND p.current_location_id = ?",
                tenantId, sessionId, tenantId, locationId);
    }

    /**
     * Scans the first {@code percent}% (by piece_id order — arbitrary but deterministic)
     * of this session's on-shelf-eligible (available/damaged) expected pieces, condition
     * matching status_at_open exactly (a clean "match" under StockTakeService.classify()).
     * Percentage-based, not a fixed row count, so this adapts to however many pieces the
     * rest of the fixture happens to seed at this location, rather than assuming a count.
     */
    private void scanFractionOfExpected(JdbcTemplate ojdbc, UUID tenantId, UUID sessionId,
                                         UUID actorUserId, int percent) {
        ojdbc.update(
                "INSERT INTO stock_take_scans " +
                "(id, tenant_id, session_id, piece_id, scanned_condition, source, actor_user_id) " +
                "SELECT gen_random_uuid(), ?, ?, ranked.piece_id, " +
                "       CASE WHEN ranked.status_at_open = 'damaged' THEN 'damaged' ELSE 'good' END, " +
                "       'scan', ? " +
                "FROM (" +
                "    SELECT piece_id, status_at_open, " +
                "           ROW_NUMBER() OVER (ORDER BY piece_id) AS rn, COUNT(*) OVER () AS total " +
                "    FROM stock_take_expected " +
                "    WHERE session_id = ? AND status_at_open IN ('available', 'damaged')" +
                ") ranked " +
                "WHERE ranked.rn <= (ranked.total * ?) / 100",
                tenantId, sessionId, actorUserId, sessionId, percent);
    }

    /**
     * FR-DEMO — ONE exchange row, status='reconciled'. Confirmed via ExchangeService/
     * ExceptionService/BostaWebhookJob review: no @Recurring/@Scheduled job polls
     * exchanges at all — every write is either event-driven off a REAL Bosta webhook
     * matching this tenant's tracking_number (unreachable for the demo tenant — no real
     * courier, no real webhooks) or a user action off ExchangeController's REST
     * endpoints (map/attach/bare-return/dismiss), none of which this row is ever the
     * target of on its own. 'reconciled' is additionally a declared-but-unwritten
     * placeholder status (V74/V95 migration comments: no service code writes it today),
     * so it sits fully inert — display-only, matching the frontend's own
     * EXCHANGE_STATUS_TONE mapping ('reconciled' -> success/green).
     *
     * raw is shaped to match ExchangeService.ROW_SELECT's JSONB-path extraction exactly
     * (receiver.fullName/phone, specs/returnSpecs.packageDetails.itemsCount) so the
     * Exchanges & Refunds tab's customer/item columns render real values, not blanks.
     * matched_order_id/match_method/matched_at are left NULL — 'reconciled' doesn't
     * require a match, and this keeps the row minimal rather than fabricating a
     * relationship to a specific seeded order.
     */
    private void insertExchange(JdbcTemplate ojdbc, UUID tenantId, List<UUID> variantIds) {
        UUID exchangeId = UUID.randomUUID();
        String trackingNumber = bareTrackingNumber(999200000001L);
        UUID outboundVariantId = variantIds.get(14); // Wireless Earbuds — White
        UUID inboundVariantId = variantIds.get(17);  // Ceramic Coffee Mug Set — 2-Piece

        String raw = "{"
                + "\"receiver\":{\"fullName\":\"Nadia Fouad\",\"phone\":\"+20 109 774 2261\"},"
                + "\"specs\":{\"packageDetails\":{\"itemsCount\":1,\"description\":\"Wireless Earbuds - White\"}},"
                + "\"returnSpecs\":{\"packageDetails\":{\"itemsCount\":1,"
                + "\"description\":\"Ceramic Coffee Mug Set - 2-Piece\","
                + "\"descriptionAr\":\"طقم أكواب قهوة سيراميك 2 قطعة\"}},"
                + "\"cod\":0,\"goodsInfo\":{\"amount\":224.00}"
                + "}";

        ojdbc.update(
                "INSERT INTO exchanges " +
                "(id, tenant_id, tracking_number, status, outbound_description, inbound_description, " +
                " inbound_description_ar, outbound_variant_id, inbound_variant_id, cod, goods_value, raw, " +
                " created_at, updated_at) " +
                "VALUES (?, ?, ?, 'reconciled', 'Wireless Earbuds - White', " +
                "        'Ceramic Coffee Mug Set - 2-Piece', 'طقم أكواب قهوة سيراميك 2 قطعة', ?, ?, 0, 224.00, " +
                "        ?::jsonb, now() - interval '6 days', now() - interval '1 day')",
                exchangeId, tenantId, trackingNumber, outboundVariantId, inboundVariantId, raw);
    }

    // ---- raw piece / piece_event writers (deliberate InventoryLedger carve-out) ----

    private void insertPiece(JdbcTemplate ojdbc, UUID tenantId, String pieceId, UUID variantId,
                              String status, UUID locationId, UUID orderId, int shortCodeSeq) {
        ojdbc.update(
                "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, " +
                "                    current_location_id, current_order_id, last_event_at) " +
                "VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, ?, now())",
                pieceId, tenantId, variantId, "PC-" + pieceId,
                "P" + String.format("%06d", shortCodeSeq), status, locationId, orderId);
    }

    /**
     * ISSUE 4 fixture only — same as insertPiece() but also sets receipt_id, so a
     * raw-inserted piece attributes back to a specific (also raw-inserted, never-
     * finalized-via-batchReceive) receiving session. ReceivingService.getSession()/
     * getLines() compute piece_count via pieces.receipt_id — without this, a
     * "finalized" demo receiving session would always display 0 pieces.
     */
    private void insertPieceWithReceipt(JdbcTemplate ojdbc, UUID tenantId, String pieceId, UUID variantId,
                                         UUID receiptId, String status, UUID locationId, int shortCodeSeq) {
        ojdbc.update(
                "INSERT INTO pieces (id, tenant_id, variant_id, receipt_id, barcode, short_code, status, " +
                "                    current_location_id, last_event_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?::piece_status, ?, now())",
                pieceId, tenantId, variantId, receiptId, "PC-" + pieceId,
                "P" + String.format("%06d", shortCodeSeq), status, locationId);
    }

    private void insertPieceEvent(JdbcTemplate ojdbc, UUID tenantId, String pieceId, String eventType,
                                   UUID actorUserId, UUID orderId, UUID shipmentId, UUID locationId,
                                   String fromStatus, String toStatus, Timestamp occurredAt) {
        ojdbc.update(
                "INSERT INTO piece_events " +
                "(tenant_id, piece_id, event_type, actor_user_id, order_id, shipment_id, location_id, " +
                " from_status, to_status, occurred_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?::piece_status, ?::piece_status, ?)",
                tenantId, pieceId, eventType, actorUserId, orderId, shipmentId, locationId,
                fromStatus, toStatus, occurredAt);
    }

    private static Timestamp daysAgo(int days) {
        return Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS));
    }

    /** Deliberately outside Bosta's real numeric range (9-10 digits) — synthetic, never collides. */
    private static String bareTrackingNumber(long seed) {
        return Long.toString(seed);
    }

    private record ProductSpec(String title, String slug, List<String> variantSkus, List<String> variantTitles) {}
}
