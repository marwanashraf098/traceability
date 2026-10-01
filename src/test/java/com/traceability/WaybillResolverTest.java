package com.traceability;

import com.traceability.account.AuditService;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.InventoryLedger;
import com.traceability.inventory.PackClaim;
import com.traceability.inventory.WaybillResolver;
import com.traceability.inventory.WaybillResolver.Code;
import com.traceability.inventory.WaybillResolver.Resolution;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pick &amp; Pack S3 commit 4 — WaybillResolver outcomes, the order claim (PackClaim) and the Q2
 * rule in the shared FulfillService.scan(): another packer's live claim refuses the scan in queue
 * mode too; a stale claim doesn't; an unclaimed order is unchanged.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WaybillResolverTest {

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

    @Autowired JdbcTemplate    jdbc;
    @Autowired WaybillResolver resolver;
    @Autowired FulfillService  fulfill;
    @Autowired InventoryLedger ledger;
    @Autowired AuditService    auditService;
    @MockBean  JobScheduler    jobScheduler;

    private TransactionTemplate appUserTx;
    private WaybillResolver     appUserResolver;
    private JdbcTemplate        appUserJdbc;

    @BeforeAll
    void setup() {
        DriverManagerDataSource rawDs = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(rawDs);
        appUserTx       = new TransactionTemplate(new DataSourceTransactionManager(appDs));
        appUserJdbc     = new JdbcTemplate(appDs);
        appUserResolver = new WaybillResolver(appUserJdbc, 30);
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    // ── Resolver outcomes ─────────────────────────────────────────────────────

    @Test
    void open_plainAndHubPrefixed() {
        PackFixtures f = new PackFixtures(jdbc, "Open");
        UUID me = f.user("Ahmed", "worker");
        UUID order = f.order("#1047", 1);
        String tn = f.forward(order);

        Resolution r = resolve(f, tn, me);
        assertThat(r.code()).isEqualTo(Code.OPEN);
        assertThat(r.orderId()).isEqualTo(order);
        assertThat(r.orderNumber()).isEqualTo("#1047");
        assertThat(resolve(f, "D-07-" + tn, me).code()).isEqualTo(Code.OPEN);
        assertThat(resolve(f, " " + tn + "\n", me).code()).isEqualTo(Code.OPEN);
    }

    @Test
    void internalExchangeReplacementOrder_opens() {
        PackFixtures f = new PackFixtures(jdbc, "Exchange");
        UUID me = f.user("Ahmed", "worker");
        String tn = PackFixtures.nextTracking();
        UUID order = f.orderWith("#EX-1", "new", 0, "internal:exchange:" + tn);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
                    "VALUES (?, ?, 'bosta', ?, 'created', 'forward', '{\"type\":{\"code\":30}}'::jsonb)",
                    f.tenant, order, tn);
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_order_id, raw) VALUES (?, ?, 'mapped', ?, '{}'::jsonb)",
                    f.tenant, tn, order);

        Resolution r = resolve(f, tn, me);
        assertThat(r.code()).isEqualTo(Code.OPEN);
        assertThat(r.orderId()).isEqualTo(order);
    }

    @Test
    void returnLeg_isReturnWaybill() {
        PackFixtures f = new PackFixtures(jdbc, "Return");
        UUID me = f.user("Ahmed", "worker");
        UUID order = f.orderWith("#R1", "delivered", 2, "EXT-" + UUID.randomUUID());
        String tn = f.leg(order, "return", "created", "{\"type\":{\"code\":25}}");
        assertThat(resolve(f, tn, me).code()).isEqualTo(Code.RETURN_WAYBILL);
    }

    @Test
    void cancelled_withShopifyTimeOnlyWhenKnown() {
        PackFixtures f = new PackFixtures(jdbc, "Cancelled");
        UUID me = f.user("Ahmed", "worker");
        UUID plain = f.orderWith("#C1", "cancelled", 1, "EXT-" + UUID.randomUUID());
        String tn1 = f.forward(plain);
        UUID shopify = f.orderWith("#C2", "new", 1, "EXT-" + UUID.randomUUID());
        jdbc.update("UPDATE orders SET cancel_requested_at = now(), raw = '{\"cancelled_at\":\"2026-10-01T11:05:00+03:00\"}'::jsonb WHERE id = ?", shopify);
        String tn2 = f.forward(shopify);

        Resolution r1 = resolve(f, tn1, me);
        assertThat(r1.code()).isEqualTo(Code.CANCELLED);
        assertThat(r1.at()).isNull();
        Resolution r2 = resolve(f, tn2, me);
        assertThat(r2.code()).isEqualTo(Code.CANCELLED);
        assertThat(r2.at()).isEqualTo(Instant.parse("2026-10-01T08:05:00Z"));
    }

    @Test
    void alreadyPacked_namesThePackerFromTheLatestPackEvent() {
        PackFixtures f = new PackFixtures(jdbc, "Packed");
        UUID me = f.user("Ahmed", "worker");
        UUID sara = f.user("Sara", "worker");
        UUID v = f.variant("Shirt", "S-1", null);
        UUID order = f.orderWith("#P1", "awaiting_pickup", 1, "EXT-" + UUID.randomUUID());
        String tn = f.forward(order);
        String piece = f.pieceId(f.piece(v, sara));
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, order_id, from_status, to_status, created_at) " +
                    "VALUES (?, ?, 'pack', ?, ?, 'reserved', 'packed', now() - interval '1 hour')",
                    f.tenant, piece, me, order);
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, order_id, from_status, to_status) " +
                    "VALUES (?, ?, 'pack', ?, ?, 'reserved', 'packed')",
                    f.tenant, piece, sara, order);

        Resolution r = resolve(f, tn, me);
        assertThat(r.code()).isEqualTo(Code.ALREADY_PACKED);
        assertThat(r.who()).isEqualTo("Sara");
        assertThat(r.at()).isNotNull();
    }

    @Test
    void claimedByOther_live_vs_stale_vs_mine() {
        PackFixtures f = new PackFixtures(jdbc, "Claimed");
        UUID me = f.user("Ahmed", "worker");
        UUID omar = f.user("Omar", "worker");
        UUID order = f.order("#K1", 1);
        String tn = f.forward(order);

        f.claim(order, omar, 1);
        Resolution live = resolve(f, tn, me);
        assertThat(live.code()).isEqualTo(Code.CLAIMED_BY_OTHER);
        assertThat(live.who()).isEqualTo("Omar");

        f.claim(order, omar, PackClaim.STALE_AFTER_MINUTES + 1);
        assertThat(resolve(f, tn, me).code()).isEqualTo(Code.OPEN);

        f.claim(order, me, 0);
        assertThat(resolve(f, tn, me).code()).isEqualTo(Code.OPEN);
    }

    @Test
    void tooOld_onHold_oldLeg_selfPickup_exchangeNotMapped() {
        PackFixtures f = new PackFixtures(jdbc, "Misc");
        UUID me = f.user("Ahmed", "worker");

        UUID old = f.order("#O1", 45);
        assertThat(resolve(f, f.forward(old), me).code()).isEqualTo(Code.TOO_OLD);

        UUID held = f.order("#H1", 1);
        jdbc.update("UPDATE orders SET on_hold = true, hold_reason = 'x' WHERE id = ?", held);
        assertThat(resolve(f, f.forward(held), me).code()).isEqualTo(Code.ON_HOLD);

        UUID relabelled = f.order("#L1", 1);
        String oldLeg = f.leg(relabelled, "forward", "terminated", null);
        jdbc.update("UPDATE shipments SET created_at = now() - interval '1 day' WHERE tracking_number = ?", oldLeg);
        String newLeg = f.forward(relabelled);
        assertThat(resolve(f, oldLeg, me).code()).isEqualTo(Code.NOT_PACKABLE);
        assertThat(resolve(f, newLeg, me).code()).isEqualTo(Code.OPEN);

        UUID selfPickup = f.order("#S1", 1);
        jdbc.update("UPDATE orders SET is_self_pickup = true WHERE id = ?", selfPickup);
        assertThat(resolve(f, f.forward(selfPickup), me).code()).isEqualTo(Code.NOT_PACKABLE);

        String unmapped = PackFixtures.nextTracking();
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, raw) VALUES (?, ?, 'needs_mapping', '{}'::jsonb)", f.tenant, unmapped);
        assertThat(resolve(f, unmapped, me).code()).isEqualTo(Code.EXCHANGE_NOT_MAPPED);
    }

    @Test
    void notFound_unlinked_andNotAWaybill() {
        PackFixtures f = new PackFixtures(jdbc, "NotFound");
        UUID me = f.user("Ahmed", "worker");
        Resolution none = resolve(f, PackFixtures.nextTracking(), me);
        assertThat(none.code()).isEqualTo(Code.NOT_FOUND);
        assertThat(none.detail()).isNull();

        String known = PackFixtures.nextTracking();
        jdbc.update("INSERT INTO unlinked_bosta_deliveries (tenant_id, tracking_number, bosta_state_code, bosta_order_type) " +
                    "VALUES (?, ?, 10, 'normal')",
                    f.tenant, known);
        Resolution unlinked = resolve(f, known, me);
        assertThat(unlinked.code()).isEqualTo(Code.NOT_FOUND);
        assertThat(unlinked.detail()).isEqualTo("unlinked");

        assertThat(resolve(f, "P000123", me).code()).isEqualTo(Code.NOT_A_WAYBILL);
        assertThat(resolve(f, "PC-01HZX0000000000000000000", me).code()).isEqualTo(Code.NOT_A_WAYBILL);
    }

    @Test
    void otherTenantsWaybill_isNotFound_asAppUser_withSameTenantPositiveControl() {
        PackFixtures a = new PackFixtures(jdbc, "RlsA");
        PackFixtures b = new PackFixtures(jdbc, "RlsB");
        UUID userA = a.user("Ahmed", "worker");
        UUID userB = b.user("Bassem", "worker");
        String tn = a.forward(a.order("#A1", 1));

        Resolution own = TenantContext.runAs(a.tenant, () -> appUserTx.execute(s -> appUserResolver.resolve(tn, userA)));
        assertThat(own.code()).isEqualTo(Code.OPEN);
        Resolution foreign = TenantContext.runAs(b.tenant, () -> appUserTx.execute(s -> appUserResolver.resolve(tn, userB)));
        assertThat(foreign.code()).isEqualTo(Code.NOT_FOUND);
        assertThat(foreign.orderId()).isNull();
        assertThat(foreign.orderNumber()).isNull();
    }

    // ── Claim ─────────────────────────────────────────────────────────────────

    @Test
    void claim_concurrentOpen_exactlyOneWins_staleIsTakeable_releaseClears() throws Exception {
        PackFixtures f = new PackFixtures(jdbc, "ClaimRace");
        List<UUID> users = new ArrayList<>();
        for (int i = 0; i < 6; i++) users.add(f.user("Packer" + i, "worker"));
        UUID order = f.order("#Z1", 1);
        f.forward(order);

        ExecutorService pool = Executors.newFixedThreadPool(users.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (UUID u : users) {
            results.add(pool.submit(() -> {
                go.await();
                return TenantContext.runAs(f.tenant, () -> appUserTx.execute(s -> PackClaim.take(appUserJdbc, order, f.tenant, u)));
            }));
        }
        go.countDown();
        int wins = 0;
        for (Future<Boolean> r : results) if (r.get(30, TimeUnit.SECONDS)) wins++;
        pool.shutdown();
        assertThat(wins).isEqualTo(1);

        UUID holder = jdbc.queryForObject("SELECT locked_by FROM orders WHERE id = ?", UUID.class, order);
        UUID other = users.stream().filter(u -> !u.equals(holder)).findFirst().orElseThrow();
        assertThat(take(f, order, other)).isFalse();
        assertThat(take(f, order, holder)).isTrue();                        // mine: re-take is fine

        jdbc.update("UPDATE orders SET locked_at = now() - interval '11 minutes' WHERE id = ?", order);
        assertThat(take(f, order, other)).isTrue();                         // stale: takeable

        TenantContext.runAs(f.tenant, () -> appUserTx.execute(s -> { PackClaim.release(appUserJdbc, order, f.tenant, holder); return null; }));
        assertThat(jdbc.queryForObject("SELECT locked_by FROM orders WHERE id = ?", UUID.class, order)).isEqualTo(other);
        TenantContext.runAs(f.tenant, () -> appUserTx.execute(s -> { PackClaim.release(appUserJdbc, order, f.tenant, other); return null; }));
        assertThat(jdbc.queryForObject("SELECT locked_by FROM orders WHERE id = ?", UUID.class, order)).isNull();
    }

    // ── Q2: shared scan() ─────────────────────────────────────────────────────

    @Test
    void queueScan_refusedWhileAnotherPackerHoldsALiveClaim_staleOrNoneUnchanged() {
        PackFixtures f = new PackFixtures(jdbc, "Q2");
        UUID me = f.user("Ahmed", "worker");
        UUID omar = f.user("Omar", "worker");
        UUID v = f.variant("Shirt", "S-1", null);
        UUID order = f.order("#Q1", 1);
        f.item(order, v, 3);
        f.forward(order);
        String p1 = f.piece(v, me), p2 = f.piece(v, me), p3 = f.piece(v, me);

        // Unclaimed: unchanged behaviour.
        FulfillService.ScanResult r0 = scan(f, order, p1, me);
        assertThat(r0.success()).isTrue();

        // Omar's live claim: refused, nothing allocated, piece still available.
        f.claim(order, omar, 1);
        FulfillService.ScanResult r1 = scan(f, order, p2, me);
        assertThat(r1.success()).isFalse();
        assertThat(r1.code()).isEqualTo("CLAIMED_BY_OTHER");
        assertThat(r1.message()).contains("Omar");
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, f.pieceId(p2)))
            .isEqualTo("available");

        // Omar himself can scan.
        assertThat(scan(f, order, p2, omar).success()).isTrue();

        // Stale claim no longer blocks.
        f.claim(order, omar, PackClaim.STALE_AFTER_MINUTES + 1);
        assertThat(scan(f, order, p3, me).success()).isTrue();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Resolution resolve(PackFixtures f, String scan, UUID user) {
        return TenantContext.runAs(f.tenant, () -> resolver.resolve(scan, user));
    }

    private boolean take(PackFixtures f, UUID order, UUID user) {
        return TenantContext.runAs(f.tenant, () -> appUserTx.execute(s -> PackClaim.take(appUserJdbc, order, f.tenant, user)));
    }

    private FulfillService.ScanResult scan(PackFixtures f, UUID order, String code, UUID user) {
        return TenantContext.runAs(f.tenant, () -> fulfill.scan(order, code, user));
    }
}
