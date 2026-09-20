package com.traceability.demo;

import com.traceability.account.UserService;
import com.traceability.identity.AuthRepository;
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
 *   - {@link #ensureBootstrapped()} — one-time tenant + owner + 2 workers, reusing the
 *     SAME provisioning seam as real signup ({@link AuthRepository#createTenantWithOwner}
 *     + {@link UserService#create}), never {@code provision_tenant_from_shopify} (that
 *     hatch exists to solve a chicken-and-egg UUID problem this seeder doesn't have — the
 *     demo tenant id is a fixed, known constant — and it forces a Shopify {@code stores}
 *     row into existence, which the demo tenant must not carry).
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

    private final JdbcTemplate    jdbc;
    private final DataSource      ownerDs;
    private final AuthRepository  authRepository;
    private final UserService     userService;
    private final PasswordEncoder passwordEncoder;

    public DemoSeeder(JdbcTemplate jdbc,
                       @FlywayDataSource DataSource ownerDs,
                       AuthRepository authRepository,
                       UserService userService,
                       PasswordEncoder passwordEncoder) {
        this.jdbc            = jdbc;
        this.ownerDs         = ownerDs;
        this.authRepository  = authRepository;
        this.userService     = userService;
        this.passwordEncoder = passwordEncoder;
    }

    // ==================================================================
    // Bootstrap — one-time, idempotent
    // ==================================================================

    /**
     * Creates the demo tenant + owner + 2 workers if they don't already exist. Safe to call
     * on every scheduled tick — the existence check makes every call after the first a no-op.
     */
    public void ensureBootstrapped() {
        boolean exists = TenantContext.runAs(DEMO_TENANT_ID, () -> Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM tenants WHERE id = ?)",
                        Boolean.class, DEMO_TENANT_ID)));
        if (exists) {
            return;
        }

        UUID ownerId = UUID.randomUUID();
        String passwordHash = passwordEncoder.encode(randomUndisclosedPassword());

        TenantContext.runAs(DEMO_TENANT_ID, () -> {
            authRepository.createTenantWithOwner(
                    DEMO_TENANT_ID, DEMO_TENANT_NAME, ownerId,
                    DEMO_OWNER_NAME, DEMO_OWNER_EMAIL, null, passwordHash,
                    PolicyVersions.PRIVACY, PolicyVersions.TERMS,
                    Timestamp.from(Instant.now()));
            return null;
        });

        // is_demo=true is a plain UPDATE under the tenant's own RLS context — not a new
        // parameter on createTenantWithOwner, which stays untouched for the real signup path.
        TenantContext.runAs(DEMO_TENANT_ID, () -> {
            jdbc.update("UPDATE tenants SET is_demo = true WHERE id = ?", DEMO_TENANT_ID);
            return null;
        });

        TenantContext.runAs(DEMO_TENANT_ID, () -> {
            userService.create(ownerId, "owner", DEMO_WORKER_1_NAME, null, "worker", null, DEMO_WORKER_1_PIN);
            userService.create(ownerId, "owner", DEMO_WORKER_2_NAME, null, "worker", null, DEMO_WORKER_2_PIN);
            return null;
        });

        log.info("Demo tenant bootstrapped: {}", DEMO_TENANT_ID);
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
        return TenantContext.runAs(DEMO_TENANT_ID, () -> jdbc.queryForObject(
                "SELECT id FROM users WHERE tenant_id = ? AND role = 'owner' AND active = true",
                UUID.class, DEMO_TENANT_ID));
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

            ojdbc.update(
                    "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, " +
                    "                    payment_method, placed_at, on_hold) " +
                    "VALUES (?, ?, ?, ?, ?, 'new'::order_status, 'cod'::order_payment_method, " +
                    "        now() - make_interval(hours => ?), false)",
                    orderId, tenantId, storeId, "DEMO-ORDER-Q" + i, "#DEMO-Q" + i, i * 3);

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

            ojdbc.update(
                    "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, " +
                    "                    payment_method, placed_at, on_hold) " +
                    "VALUES (?, ?, ?, ?, ?, 'with_courier'::order_status, 'cod'::order_payment_method, " +
                    "        now() - interval '2 days', false)",
                    orderId, tenantId, storeId, "DEMO-ORDER-T" + i, "#DEMO-T" + i);

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

    /** 1-2 returns sitting in an open return session, awaiting inspection. */
    private void insertReturns(JdbcTemplate ojdbc, UUID tenantId, UUID locationId,
                                List<UUID> workerIds, List<UUID> variantIds,
                                AtomicInteger shortCodeSeq) {
        UUID sessionId = UUID.randomUUID();
        UUID opener = workerIds.get(0);
        ojdbc.update(
                "INSERT INTO return_sessions (id, tenant_id, status, opened_by, opened_at) " +
                "VALUES (?, ?, 'open', ?, now() - interval '1 day')",
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
                "                    status, payment_method, placed_at, on_hold, hold_reason) " +
                "VALUES (?, ?, ?, 'DEMO-ORDER-BLOCKED', '#DEMO-BLOCKED', 'Repeat RTO Customer', " +
                "        'new'::order_status, 'cod'::order_payment_method, now() - interval '1 day', " +
                "        true, 'Blocked customer')",
                blockedOrderId, tenantId, storeId);
        ojdbc.update(
                "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)",
                tenantId, blockedOrderId, variantIds.get(1));
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
