package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.account.AuditService;
import com.traceability.integrations.shopify.ShopifyAdjustFailedException;
import com.traceability.integrations.shopify.ShopifyAdjustFailedException.FailureClass;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V123 — the initial seed supersedes the increment claims it made redundant.
 *
 * The seed pushes Traced's CURRENT on-hand. An unapplied increment claim (failed or pending,
 * legacy or not) created at or before the seed's on-hand read, for a variant the seed WROTE or a
 * variant with Traced on-hand 0 at that read, is marked 'superseded_by_seed' in the seed's own
 * transaction and is never retried or repushed.
 *
 *   s1 — blocked store, 2 never_sent claims → link + seed → both superseded; the retry job sends
 *        nothing; Shopify receives exactly the seed's deltas; the setup alert is gone.
 *   s2 — a claim created after the snapshot still retries.
 *   s3 — a legacy claim is superseded too; its legacy alert resolves; repush answers SUPERSEDED_BY_SEED.
 *   s4 — on-hand 0 at the snapshot (pieces have left): superseded, no seed write for it.
 *   s5 — skip_nonzero (Shopify already non-zero): the claim stays failed and is retried.
 *   s6 — failed seed (activation rejected): the claim stays failed and retryable.
 *   s7 — in flight: a 'pending' claim superseded mid-attempt — a late failure stays superseded;
 *        a late success records 'applied' and logs the possible double count.
 *   x1 — cross-tenant on app_user: A's seed never touches B's claim; A's own claim is superseded.
 *   a1 — receiving finalize fires its increment only after commit: the claim's attempt sees the
 *        committed pieces.
 *   a2 — a rolled-back finalize creates no claim.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class SeedSupersedesClaimsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
    }

    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired ShopifyInventoryService inventory;
    @Autowired ShopifyInventoryReconcileService seed;
    @Autowired ReceivingService receiving;
    @Autowired ExceptionService exceptions;
    @Autowired AuditService auditService;
    @Autowired ObjectMapper mapper;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    record T(UUID tenant, UUID store, String shop, UUID location, String traced, UUID user) {}
    record V(UUID id, String item) {}
    record Adjust(String shop, String item, int delta, String key) {}

    /** Every adjustInventoryQuantities call, in order. */
    private final List<Adjust> adjusts = new CopyOnWriteArrayList<>();
    /** Shopify "available" at the Traced location, by inventory item (default 0). */
    private final Map<String, Integer> shopifyAvailable = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void reset() {
        Mockito.reset(shopifyGateway, tokenProvider);
        adjusts.clear();
        shopifyAvailable.clear();
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        doAnswer(inv -> {
            adjusts.add(new Adjust(inv.getArgument(0), inv.getArgument(2), inv.getArgument(4), inv.getArgument(6)));
            return null;
        }).when(shopifyGateway).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
        when(shopifyGateway.fetchAvailableQuantities(any(), any(), any(), any())).thenAnswer(inv -> {
            List<ShopifyGateway.InventoryLevel> out = new ArrayList<>();
            for (Object item : (List<?>) inv.getArgument(3)) {
                out.add(new ShopifyGateway.InventoryLevel((String) item, shopifyAvailable.getOrDefault((String) item, 0)));
            }
            return out;
        });
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── s1: blocked store → link + seed → claims superseded, seed delta only ─────

    @Test
    void s1_blockedStore_linkAndSeed_claimsSuperseded_retrySendsNothing_shopifyGetsSeedDeltaOnly() throws Exception {
        T t = tenant("s1", false);
        V a = variant(t, "s1a"), b = variant(t, "s1b");
        pieces(t, a, 5, "available"); pieces(t, b, 15, "available");
        UUID sa = receive(t, a, 5), sb = receive(t, b, 15);
        assertThat(row(t, sa, a)).containsEntry("status", "failed").containsEntry("failure_class", "never_sent");
        assertThat(row(t, sb, b)).containsEntry("status", "failed").containsEntry("failure_class", "never_sent");
        assertThat(alerts(t)).extracting(m -> m.get("kind")).containsExactly("setup");
        assertThat(adjusts).as("blocked: nothing reached Shopify").isEmpty();

        link(t);
        ShopifyInventoryReconcileService.ApplyResult result = runSeed(t);

        assertThat(result.seeded()).isEqualTo(2);
        assertThat(result.superseded()).isEqualTo(2);
        assertThat(row(t, sa, a)).containsEntry("status", "superseded_by_seed").containsEntry("next_attempt_at", null);
        assertThat(row(t, sb, b)).containsEntry("status", "superseded_by_seed");
        assertThat(row(t, sa, a).get("superseded_at")).isNotNull();

        jdbc.update("UPDATE shopify_inventory_adjustments SET next_attempt_at = now() - interval '1 second' WHERE tenant_id = ?",
            t.tenant());
        ShopifyInventoryService.RetryResult r = retry(t);

        assertThat(r.due()).as("superseded claims are never due").isZero();
        assertThat(adjusts).as("Shopify got exactly the seed: +5 and +15, nothing else").containsExactlyInAnyOrder(
            new Adjust(t.shop(), a.item(), 5, seedKey(t, a)),
            new Adjust(t.shop(), b.item(), 15, seedKey(t, b)));
        assertThat(alerts(t)).as("setup alert resolved, nothing else open").isEmpty();
    }

    // ── s2: a claim created after the snapshot still retries ─────────────────────

    @Test
    void s2_claimCreatedAfterSnapshot_stillRetries() throws Exception {
        T t = tenant("s2", true);
        V a = variant(t, "s2");
        pieces(t, a, 4, "available");
        runSeed(t);
        assertThat(adjusts).containsExactly(new Adjust(t.shop(), a.item(), 4, seedKey(t, a)));

        // New stock after the seed; its increment fails once.
        pieces(t, a, 3, "available");
        failNextAdjustWith(FailureClass.REJECTED, 4);
        UUID s = receive(t, a, 3);
        assertThat(row(t, s, a)).containsEntry("status", "failed");
        makeDue(t);

        ShopifyInventoryService.RetryResult r = retry(t);

        assertThat(r.attempted()).isEqualTo(1);
        assertThat(row(t, s, a).get("status")).isEqualTo("applied");
        assertThat(adjusts).as("seed, then the post-snapshot claim's retry (new key after the rejection)").containsExactly(
            new Adjust(t.shop(), a.item(), 4, seedKey(t, a)),
            new Adjust(t.shop(), a.item(), 3, IncrementRecoveryRules.retryKey(t.tenant(), "receiving_session",
                s.toString(), a.id(), t.location(), 2)));
    }

    /** A claim created while the seed runs — after its on-hand read, before its supersede step. */
    @Test
    void s2b_claimCreatedDuringSeedAfterSnapshot_notSuperseded() throws Exception {
        T t = tenant("s2b", true);
        V a = variant(t, "s2b");
        pieces(t, a, 4, "available");
        String seedKey = seedKey(t, a);
        UUID[] late = new UUID[1];
        doAnswer(inv -> {
            String key = inv.getArgument(6);
            if (key.equals(seedKey) && late[0] == null) {
                adjusts.add(new Adjust(inv.getArgument(0), inv.getArgument(2), inv.getArgument(4), key));
                pieces(t, a, 2, "available");        // received after the snapshot was read
                late[0] = receive(t, a, 2);          // its own increment is rejected below
                return null;
            }
            throw new ShopifyAdjustFailedException(FailureClass.REJECTED, 0, "simulated rejected", null);
        }).when(shopifyGateway).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());

        ShopifyInventoryReconcileService.ApplyResult result = runSeed(t);

        assertThat(result.seeded()).isEqualTo(1);
        assertThat(result.superseded()).as("created after the snapshot — owed, not covered").isZero();
        assertThat(row(t, late[0], a).get("status")).isEqualTo("failed");
    }

    // ── s3: a legacy claim is superseded too ──────────────────────────────────────

    @Test
    void s3_legacyClaim_superseded_alertResolves_repushRefused() throws Exception {
        T t = tenant("s3", false);
        V a = variant(t, "s3");
        pieces(t, a, 6, "available");
        UUID s = receive(t, a, 6);
        jdbc.update("UPDATE shopify_inventory_adjustments SET legacy = true, next_attempt_at = NULL WHERE tenant_id = ?", t.tenant());
        link(t);
        assertThat(alerts(t)).extracting(m -> m.get("kind")).containsExactly("legacy");

        ShopifyInventoryReconcileService.ApplyResult result = runSeed(t);

        assertThat(result.superseded()).isEqualTo(1);
        assertThat(row(t, s, a).get("status")).isEqualTo("superseded_by_seed");
        assertThat(alerts(t)).as("legacy alert resolved").isEmpty();
        assertThatThrownBy(() -> TenantContext.runAs(t.tenant(), () ->
                inventory.repushFailedIncrement("receiving_session", s.toString(), a.id(), true)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("SUPERSEDED_BY_SEED");
        assertThat(adjusts).containsExactly(new Adjust(t.shop(), a.item(), 6, seedKey(t, a)));
    }

    // ── s4: on-hand 0 at the snapshot → superseded, nothing written ──────────────

    @Test
    void s4_onHandZeroAtSnapshot_superseded_noSeedWrite() throws Exception {
        T t = tenant("s4", false);
        V gone = variant(t, "s4");
        pieces(t, gone, 2, "delivered");          // received, then sold and delivered — off on-hand
        UUID s = receive(t, gone, 2);
        link(t);

        ShopifyInventoryReconcileService.ApplyResult result = runSeed(t);

        assertThat(result.seeded()).isZero();
        assertThat(row(t, s, gone).get("status")).isEqualTo("superseded_by_seed");
        makeDue(t);
        assertThat(retry(t).due()).isZero();
        assertThat(adjusts).as("a replay would have overstated Shopify by 2").isEmpty();
    }

    // ── s5: skip_nonzero → stays failed, retried ─────────────────────────────────

    @Test
    void s5_skipNonZero_claimStaysRetryable() throws Exception {
        T t = tenant("s5", false);
        V n = variant(t, "s5");
        pieces(t, n, 3, "available");
        UUID s = receive(t, n, 3);
        link(t);
        shopifyAvailable.put(n.item(), 7);          // relinked to a location that already counts stock

        ShopifyInventoryReconcileService.ApplyResult result = runSeed(t);

        assertThat(result.skippedNonZero()).isEqualTo(1);
        assertThat(result.superseded()).isZero();
        assertThat(row(t, s, n).get("status")).isEqualTo("failed");
        makeDue(t);
        assertThat(retry(t).attempted()).isEqualTo(1);
        assertThat(row(t, s, n).get("status")).isEqualTo("applied");
        assertThat(adjusts).containsExactly(new Adjust(t.shop(), n.item(), 3,
            ShopifyGateway.idempotencyKey(t.tenant(), "receiving_session", s.toString(), n.id(), t.location())));
    }

    // ── s6: failed seed → stays failed, retryable ────────────────────────────────

    @Test
    void s6_failedSeed_claimStaysRetryable() throws Exception {
        T t = tenant("s6", false);
        V f = variant(t, "s6");
        pieces(t, f, 2, "available");
        UUID s = receive(t, f, 2);
        link(t);
        doThrow(new ShopifyException("activation rejected")).doNothing()
            .when(shopifyGateway).activateInventoryItem(any(), any(), eq(f.item()), any(), any());

        ShopifyInventoryReconcileService.ApplyResult result = runSeed(t);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.superseded()).isZero();
        assertThat(row(t, s, f).get("status")).isEqualTo("failed");
        makeDue(t);
        assertThat(retry(t).attempted()).isEqualTo(1);
        assertThat(row(t, s, f).get("status")).isEqualTo("applied");
    }

    // ── s7: in-flight 'pending' claim superseded mid-attempt ─────────────────────

    @Test
    void s7_inFlight_lateFailureStaysSuperseded_lateSuccessRecordedWithWarning(CapturedOutput output) throws Exception {
        T t = tenant("s7", true);
        V lateFail = variant(t, "s7f"), lateOk = variant(t, "s7k");
        pieces(t, lateFail, 2, "available"); pieces(t, lateOk, 3, "available");
        UUID sf = UUID.randomUUID(), sk = UUID.randomUUID();
        String failKey = ShopifyGateway.idempotencyKey(t.tenant(), "receiving_session", sf.toString(), lateFail.id(), t.location());
        String okKey   = ShopifyGateway.idempotencyKey(t.tenant(), "receiving_session", sk.toString(), lateOk.id(), t.location());
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            String key = inv.getArgument(6);
            adjusts.add(new Adjust(inv.getArgument(0), inv.getArgument(2), inv.getArgument(4), key));
            if (key.equals(failKey) || key.equals(okKey)) {
                assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                if (key.equals(failKey)) throw new ShopifyAdjustFailedException(FailureClass.REJECTED, 0, "late reject", null);
            }
            return null;
        }).when(shopifyGateway).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());

        CompletableFuture<Void> f1 = inventory.onReceivingSessionClose(t.tenant(), sf, t.location(), Map.of(lateFail.id(), 2));
        CompletableFuture<Void> f2 = inventory.onReceivingSessionClose(t.tenant(), sk, t.location(), Map.of(lateOk.id(), 3));
        awaitStatus(t, sf, lateFail, "pending");
        awaitStatus(t, sk, lateOk, "pending");
        waitUntil(() -> adjusts.size() == 2);       // both attempts are inside Shopify now

        ShopifyInventoryReconcileService.ApplyResult result = runSeed(t);
        assertThat(result.superseded()).isEqualTo(2);
        release.countDown();
        f1.get(20, TimeUnit.SECONDS); f2.get(20, TimeUnit.SECONDS);

        assertThat(row(t, sf, lateFail).get("status")).as("late failure never re-arms a retry").isEqualTo("superseded_by_seed");
        assertThat(row(t, sk, lateOk).get("status")).as("late success recorded as the truth").isEqualTo("applied");
        assertThat(output.getAll()).contains("applied after superseded by seed — possible double count of 3 units for variant "
            + lateOk.id());
        makeDue(t);
        assertThat(retry(t).due()).isZero();
    }

    // ── x1: cross-tenant isolation on app_user ────────────────────────────────────

    @Test
    void x1_appUser_seedForANeverTouchesB_ownClaimSuperseded() throws Exception {
        T a = tenant("x1a", false), b = tenant("x1b", false);
        V va = variant(a, "x1a"), vb = variant(b, "x1b");
        pieces(a, va, 2, "available"); pieces(b, vb, 2, "available");
        UUID sa = receive(a, va, 2), sb = receive(b, vb, 2);
        link(a); link(b);

        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        DataSourceTransactionManager appTxm = new DataSourceTransactionManager(appDs);
        ShopifyInventoryReconcileService appSeed = new ShopifyInventoryReconcileService(appJdbc, appTxm, shopifyGateway,
            tokenProvider, mapper, auditService, new StoreRepository(appJdbc, appTxm));

        ShopifyInventoryReconcileService.ApplyResult r = TenantContext.runAs(a.tenant(), () -> appSeed.apply(null));

        assertThat(r.superseded()).isEqualTo(1);
        assertThat(row(a, sa, va).get("status")).as("same-tenant positive control").isEqualTo("superseded_by_seed");
        assertThat(row(b, sb, vb)).containsEntry("status", "failed").containsEntry("superseded_at", null);
    }

    // ── a1/a2: receiving finalize fires the increment after commit ────────────────

    @Test
    void a1_finalize_incrementFiresAfterCommit_seesCommittedPieces() throws Exception {
        T t = tenant("a1", true);
        V v = variant(t, "a1");
        AtomicInteger seenCommitted = new AtomicInteger(-1);
        CountDownLatch adjusted = new CountDownLatch(1);
        doAnswer(inv -> {
            seenCommitted.set(committedPieces(t, v));
            adjusted.countDown();
            return null;
        }).when(shopifyGateway).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());

        UUID session = TenantContext.runAs(t.tenant(), () -> {
            UUID id = receiving.createSession(t.user(), t.location(), "R-a1", null, null);
            receiving.addLine(id, v.id(), 4);
            new TransactionTemplate(txm).executeWithoutResult(st -> {
                receiving.finalize(id, t.user());
                sleep(1500);                         // the commit is deliberately late
            });
            return id;
        });

        assertThat(adjusted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(seenCommitted.get()).as("the increment ran only once the 4 pieces were committed").isEqualTo(4);
        awaitStatus(t, session, v, "applied");
    }

    @Test
    void a2_finalizeRolledBack_noClaim() throws Exception {
        T t = tenant("a2", true);
        V v = variant(t, "a2");

        UUID session = TenantContext.runAs(t.tenant(), () -> {
            UUID id = receiving.createSession(t.user(), t.location(), "R-a2", null, null);
            receiving.addLine(id, v.id(), 2);
            new TransactionTemplate(txm).executeWithoutResult(st -> {
                receiving.finalize(id, t.user());
                st.setRollbackOnly();
            });
            return id;
        });

        sleep(1500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?",
            Integer.class, t.tenant())).as("a rolled-back finalize never creates a claim").isZero();
        assertThat(committedPieces(t, v)).isZero();
        assertThat(adjusts).isEmpty();
        assertThat(session).isNotNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private T tenant(String name, boolean linked) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String traced = "gid://shopify/Location/" + shop;
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, name + "-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?)", store, tenant, shop, SCOPES);
        if (linked) {
            jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
                "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", location, tenant, traced);
        } else {
            jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_sync_status, is_fulfillment) " +
                "VALUES (?, ?, 'Main Warehouse', 'error', true)", location, tenant);
        }
        return new T(tenant, store, shop, location, traced, user);
    }

    private void link(T t) {
        jdbc.update("UPDATE locations SET shopify_sync_status = 'linked', shopify_location_id = ? WHERE id = ?",
            t.traced(), t.location());
    }

    private V variant(T t, String key) {
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        String item = "gid://shopify/InventoryItem/" + key + "-" + variant.toString().substring(0, 6);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, t.tenant(), t.store(), "gid://shopify/Product/" + t.shop() + "-" + key);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", variant, t.tenant(), product,
            "gid://shopify/ProductVariant/" + t.shop() + "-" + key, "SKU-" + key, item);
        return new V(variant, item);
    }

    private void pieces(T t, V v, int n, String status) {
        for (int i = 0; i < n; i++) {
            int k = seq.incrementAndGet();
            String id = String.format("01SEEDSUPERSEDE%011d", k);
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
                "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?)", id, t.tenant(), v.id(), status,
                "SS-" + k, String.format("Q%07d", k), t.location());
        }
    }

    private UUID receive(T t, V v, int qty) throws Exception {
        UUID session = UUID.randomUUID();
        inventory.onReceivingSessionClose(t.tenant(), session, t.location(), Map.of(v.id(), qty)).get(10, TimeUnit.SECONDS);
        return session;
    }

    private void failNextAdjustWith(FailureClass cls, Integer baseline) {
        doThrow(new ShopifyAdjustFailedException(cls, baseline, "simulated " + cls.db(), null))
            .doAnswer(inv -> {
                adjusts.add(new Adjust(inv.getArgument(0), inv.getArgument(2), inv.getArgument(4), inv.getArgument(6)));
                return null;
            })
            .when(shopifyGateway).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    private ShopifyInventoryReconcileService.ApplyResult runSeed(T t) {
        return TenantContext.runAs(t.tenant(), () -> seed.apply(null));
    }

    private ShopifyInventoryService.RetryResult retry(T t) {
        return TenantContext.runAs(t.tenant(), inventory::retryDueIncrements);
    }

    private void makeDue(T t) {
        jdbc.update("UPDATE shopify_inventory_adjustments SET next_attempt_at = now() - interval '1 second' " +
            "WHERE tenant_id = ? AND next_attempt_at IS NOT NULL", t.tenant());
    }

    private static String seedKey(T t, V v) {
        return ShopifyGateway.idempotencyKey(t.tenant(), "initial_seed", v.id().toString(), v.id(), t.location());
    }

    private Map<String, Object> row(T t, UUID session, V v) {
        return jdbc.queryForMap("SELECT status, failure_class, attempt_count, next_attempt_at, superseded_at " +
            "FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_id = ? AND variant_id = ?",
            t.tenant(), session.toString(), v.id());
    }

    private void awaitStatus(T t, UUID session, V v, String status) throws Exception {
        waitUntil(() -> jdbc.queryForList(
            "SELECT 1 FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_id = ? AND variant_id = ? AND status = ?",
            t.tenant(), session.toString(), v.id(), status).size() == 1);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("condition not reached in 10 s");
            Thread.sleep(25);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Pieces of this variant visible to a separate connection — i.e. committed. */
    private int committedPieces(T t, V v) {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM pieces WHERE tenant_id = ? AND variant_id = ?")) {
            ps.setObject(1, t.tenant()); ps.setObject(2, v.id());
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<Map<String, Object>> alerts(T t) {
        return TenantContext.runAs(t.tenant(), () -> exceptions.detectAllOpen()).stream()
            .filter(e -> "inventory_increment_sync_failed".equals(e.get("type"))).toList();
    }
}
