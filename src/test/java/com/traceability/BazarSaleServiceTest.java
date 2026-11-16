package com.traceability;

import com.traceability.bazar.BazarException;
import com.traceability.bazar.BazarSaleService;
import com.traceability.bazar.BazarService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar POS B2 — quick sell, undo, payment / customer edits, the sales list, the event's today totals, idempotency,
 * the concurrent double scan, seller roles, the flag, RLS (app_user, with a same-tenant positive control) and the
 * append-only payments table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarSaleServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
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

    @Autowired BazarService     bazar;
    @Autowired BazarSaleService sales;
    @Autowired JdbcTemplate     jdbc;

    UUID tenantId, otherTenantId, offTenantId;
    UUID ownerId, managerId, worker1Id, worker2Id;
    UUID mainId, variantA, variantNoPrice;
    BazarSaleService.Actor owner, manager, worker1, worker2;
    int seq = 0;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        otherTenantId = UUID.randomUUID();
        offTenantId = UUID.randomUUID();
        mainId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID(), productId = UUID.randomUUID();
        variantA = UUID.randomUUID();
        variantNoPrice = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Bazar Sale Co', true)", tenantId);
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Other Sale Co', true)", otherTenantId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Off Sale Co')", offTenantId);
        ownerId   = user("owner", "Owner Omar");
        managerId = user("manager", "Manager Mona");
        worker1Id = user("worker", "Seller Sara");
        worker2Id = user("worker", "Seller Sami");
        owner   = new BazarSaleService.Actor(ownerId, "owner");
        manager = new BazarSaleService.Actor(managerId, "manager");
        worker1 = new BazarSaleService.Actor(worker1Id, "worker");
        worker2 = new BazarSaleService.Actor(worker2Id, "worker");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'bazar-sale.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-BS', 'Linen Dress', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) " +
            "VALUES (?, ?, ?, 'VAR-BS-A', 'S', 349.00)", variantA, tenantId, productId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) " +
            "VALUES (?, ?, ?, 'VAR-BS-N', 'L', NULL)", variantNoPrice, tenantId, productId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", mainId, tenantId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clear() { TenantContext.clear(); }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private UUID user(String role, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, ?, ?, 'h', ?::user_role)",
            id, tenantId, name, role + "-" + id + "@bazar-sale-test.com", role);
        return id;
    }

    private String piece(UUID variant) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 7, '0'), 'available', ?)",
            id, tenantId, variant, "PC-" + id, id, mainId);
        return id;
    }

    private String code(String pieceId) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, pieceId);
    }

    /** A draft event with {@code n} pieces of {@code variant} scanned out; returns {eventId} + piece ids. */
    private Fixture event(List<String> methods, List<String> roles, UUID variant, int n) {
        Map<String, Object> ev = bazar.create(new BazarService.CreateEvent("Sale Bazar " + (++seq),
            LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3), null, "Sale Hall " + seq,
            methods, roles, null, null, null), ownerId);
        UUID eventId = (UUID) ev.get("id");
        List<String> pieces = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String p = piece(variant);
            assertThat(bazar.scanOut(eventId, "PC-" + p, ownerId, false).success()).isTrue();
            pieces.add(p);
        }
        return new Fixture(eventId, (UUID) ev.get("transfer_id"), pieces);
    }

    private Fixture liveEvent(int n) {
        return live(event(List.of("cash", "card", "instapay"), List.of("owner", "manager", "worker"), variantA, n));
    }

    private Fixture live(Fixture f) {
        bazar.markSent(f.eventId(), ownerId);
        bazar.start(f.eventId(), ownerId);
        return f;
    }

    record Fixture(UUID eventId, UUID transferId, List<String> pieces) {}

    private static String crid() { return "req-" + UUID.randomUUID(); }

    private static BazarException err(Runnable r) {
        try { r.run(); } catch (BazarException e) { return e; }
        throw new AssertionError("expected a BazarException");
    }

    private static BazarException.Code codeOf(Runnable r) { return err(r).code(); }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String outcome(UUID transferId, String pieceId) {
        return jdbc.queryForObject("SELECT outcome FROM transfer_pieces WHERE transfer_id = ? AND piece_id = ?",
            String.class, transferId, pieceId);
    }

    private int qtySold(UUID transferId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(qty_sold), 0)::int FROM transfer_lines WHERE transfer_id = ?",
            Integer.class, transferId);
    }

    private int shopifyRows() {
        return jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?) " +
            "+ (SELECT COUNT(*) FROM transfer_shopify_syncs WHERE tenant_id = ?)", Integer.class, tenantId, tenantId);
    }

    private List<Map<String, Object>> payments(UUID saleId) {
        return jdbc.queryForList("SELECT method, amount, kind, voids_payment_id FROM bazar_payments WHERE sale_id = ? " +
            "ORDER BY created_at, id", saleId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, BigDecimal> todayByMethod(UUID eventId) {
        return (Map<String, BigDecimal>) ((Map<String, Object>) bazar.get(eventId).get("today")).get("by_method");
    }

    // ── sell ─────────────────────────────────────────────────────────────────

    @Test
    void sell_happyPath_pieceSold_transferPieceSold_cashPayment_saleNumber_noShopifyWrite() {
        Fixture f = liveEvent(1);
        String p = f.pieces().get(0);
        int shopifyBefore = shopifyRows();

        Map<String, Object> sale = sales.sell(f.eventId(), code(p), crid(), null, worker1);

        UUID saleId = (UUID) sale.get("id");
        assertThat(sale.get("replayed")).isEqualTo(false);
        assertThat((String) sale.get("sale_number")).matches("B-\\d{4,}");
        assertThat(sale.get("price")).isEqualTo(new BigDecimal("349.00"));
        assertThat(sale.get("method")).isEqualTo("cash");
        assertThat(sale.get("seller_name")).isEqualTo("Seller Sara");
        assertThat(sale.get("piece_code")).isEqualTo(code(p));
        assertThat(sale.get("product_title")).isEqualTo("Linen Dress");
        assertThat(sale.get("variant_title")).isEqualTo("S");

        assertThat(pieceStatus(p)).isEqualTo("sold");
        assertThat(outcome(f.transferId(), p)).isEqualTo("sold");
        assertThat(qtySold(f.transferId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_type FROM piece_events WHERE piece_id = ? AND to_status = 'sold'",
            String.class, p)).isEqualTo("bazar_sold");
        List<Map<String, Object>> pays = payments(saleId);
        assertThat(pays).hasSize(1);
        assertThat(pays.get(0).get("kind")).isEqualTo("payment");
        assertThat(pays.get(0).get("method")).isEqualTo("cash");
        assertThat(pays.get(0).get("amount")).isEqualTo(new BigDecimal("349.00"));
        Integer counter = jdbc.queryForObject("SELECT last_number FROM bazar_sale_counters WHERE tenant_id = ?",
            Integer.class, tenantId);
        assertThat(sale.get("sale_number")).isEqualTo(String.format("B-%04d", counter));
        assertThat(jdbc.queryForObject("SELECT business_day = (sold_at AT TIME ZONE 'Africa/Cairo')::date " +
            "FROM bazar_sales WHERE id = ?", Boolean.class, saleId)).isTrue();

        assertThat(shopifyRows()).as("no Shopify adjustment / sync row of any kind").isEqualTo(shopifyBefore);

        Map<String, Object> detail = bazar.get(f.eventId());
        assertThat(((Map<?, ?>) detail.get("counts")).get("sold")).isEqualTo(1);
        assertThat(((Map<?, ?>) detail.get("counts")).get("at_bazar")).isEqualTo(0);
        assertThat(todayByMethod(f.eventId()).get("cash")).isEqualByComparingTo("349.00");
        assertThat(todayByMethod(f.eventId()).get("card")).isEqualByComparingTo("0");
    }

    @Test
    void saleNumbers_increasePerTenant() {
        Fixture f = liveEvent(2);
        String a = (String) sales.sell(f.eventId(), code(f.pieces().get(0)), crid(), null, owner).get("sale_number");
        String b = (String) sales.sell(f.eventId(), code(f.pieces().get(1)), crid(), null, owner).get("sale_number");
        assertThat(Integer.parseInt(b.substring(2))).isEqualTo(Integer.parseInt(a.substring(2)) + 1);
    }

    @Test
    void sell_acceptsEveryPieceCodeShape() {
        Fixture f = liveEvent(3);
        sales.sell(f.eventId(), code(f.pieces().get(0)).toLowerCase(), crid(), null, owner);   // p + digits, Caps Lock off
        sales.sell(f.eventId(), "PC-" + f.pieces().get(1), crid(), null, owner);
        sales.sell(f.eventId(), f.pieces().get(2), crid(), null, owner);                     // bare ULID
        assertThat(f.pieces()).allMatch(p -> pieceStatus(p).equals("sold"));
    }

    @Test
    void sell_errors() {
        // Not live: a draft event.
        Fixture draft = event(List.of("cash"), List.of("owner"), variantA, 1);
        assertThat(codeOf(() -> sales.sell(draft.eventId(), code(draft.pieces().get(0)), crid(), null, owner)))
            .isEqualTo(BazarException.Code.BAZAR_EVENT_NOT_LIVE);

        Fixture f = liveEvent(2);
        // Not here: a piece still at the main warehouse, and a piece on another bazar's transfer.
        String atMain = piece(variantA);
        assertThat(codeOf(() -> sales.sell(f.eventId(), code(atMain), crid(), null, owner)))
            .isEqualTo(BazarException.Code.BAZAR_PIECE_NOT_HERE);
        assertThat(codeOf(() -> sales.sell(f.eventId(), code(draft.pieces().get(0)), crid(), null, owner)))
            .isEqualTo(BazarException.Code.BAZAR_PIECE_NOT_HERE);

        // Not found / untagged.
        assertThat(codeOf(() -> sales.sell(f.eventId(), "P9999999999", crid(), null, owner)))
            .isEqualTo(BazarException.Code.BAZAR_PIECE_NOT_FOUND);
        String untagged = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'available', ?)", untagged, tenantId, variantA, "PC-" + untagged, mainId);
        assertThat(codeOf(() -> sales.sell(f.eventId(), untagged, crid(), null, owner)))
            .isEqualTo(BazarException.Code.BAZAR_PIECE_UNTAGGED);

        // Already sold: names the sale, its time and its seller.
        String p = f.pieces().get(0);
        Map<String, Object> first = sales.sell(f.eventId(), code(p), crid(), null, worker1);
        BazarException again = err(() -> sales.sell(f.eventId(), code(p), crid(), null, worker2));
        assertThat(again.code()).isEqualTo(BazarException.Code.BAZAR_ALREADY_SOLD);
        assertThat(again.details()).containsEntry("sale_number", first.get("sale_number"))
            .containsEntry("seller_name", "Seller Sara").containsKey("sold_at");
        assertThat(again.messageEn()).contains((String) first.get("sale_number")).contains("Seller Sara");

        // Payment method not on the event.
        Fixture cashOnly = live(event(List.of("cash"), List.of("owner"), variantA, 1));
        assertThat(codeOf(() -> sales.sell(cashOnly.eventId(), code(cashOnly.pieces().get(0)), crid(), "card", owner)))
            .isEqualTo(BazarException.Code.BAZAR_PAYMENT_METHOD_NOT_ALLOWED);
        // No cash on the event: the request must name a method.
        Fixture cardOnly = live(event(List.of("card", "instapay"), List.of("owner"), variantA, 1));
        assertThat(codeOf(() -> sales.sell(cardOnly.eventId(), code(cardOnly.pieces().get(0)), crid(), null, owner)))
            .isEqualTo(BazarException.Code.BAZAR_PAYMENT_METHOD_REQUIRED);
        assertThat(sales.sell(cardOnly.eventId(), code(cardOnly.pieces().get(0)), crid(), "card", owner).get("method"))
            .isEqualTo("card");

        // Every refusal left the pieces alone.
        assertThat(pieceStatus(atMain)).isEqualTo("available");
        assertThat(pieceStatus(f.pieces().get(1))).isEqualTo("out_on_transfer");
        assertThat(pieceStatus(cashOnly.pieces().get(0))).isEqualTo("out_on_transfer");
    }

    @Test
    void sell_variantWithNoBazarPrice_isRefused_neverSoldAtZero() {
        Fixture f = live(event(List.of("cash"), List.of("owner"), variantNoPrice, 1));
        String p = f.pieces().get(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bazar_event_prices WHERE event_id = ?",
            Integer.class, f.eventId())).isZero();
        BazarException e = err(() -> sales.sell(f.eventId(), code(p), crid(), null, owner));
        assertThat(e.code()).isEqualTo(BazarException.Code.BAZAR_NO_PRICE);
        assertThat(e.messageEn()).contains("Set a price first");
        assertThat(e.messageAr()).isNotBlank();
        assertThat(pieceStatus(p)).isEqualTo("out_on_transfer");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bazar_sales WHERE event_id = ?", Integer.class, f.eventId()))
            .isZero();

        // Once a price is set, it sells at that price.
        bazar.setOverride(f.eventId(), variantNoPrice, new BigDecimal("150"), ownerId);
        assertThat(sales.sell(f.eventId(), code(p), crid(), null, owner).get("price")).isEqualTo(new BigDecimal("150.00"));
    }

    @Test
    void sell_priceIsASnapshot() {
        Fixture f = liveEvent(1);
        Map<String, Object> s = sales.sell(f.eventId(), code(f.pieces().get(0)), crid(), null, owner);
        bazar.setOverride(f.eventId(), variantA, new BigDecimal("999"), ownerId);
        assertThat(jdbc.queryForObject("SELECT price FROM bazar_sales WHERE id = ?", BigDecimal.class, s.get("id")))
            .isEqualByComparingTo("349.00");
    }

    @Test
    void sameClientRequestId_returnsTheFirstSale_noSecondSale() {
        Fixture f = liveEvent(2);
        String req = crid();
        Map<String, Object> first = sales.sell(f.eventId(), code(f.pieces().get(0)), req, null, worker1);
        Map<String, Object> retry = sales.sell(f.eventId(), code(f.pieces().get(0)), req, null, worker1);
        assertThat(retry.get("id")).isEqualTo(first.get("id"));
        assertThat(retry.get("replayed")).isEqualTo(true);
        // Even a different code under the same request id is the first sale, not a second one.
        Map<String, Object> other = sales.sell(f.eventId(), code(f.pieces().get(1)), req, null, worker1);
        assertThat(other.get("id")).isEqualTo(first.get("id"));
        assertThat(pieceStatus(f.pieces().get(1))).isEqualTo("out_on_transfer");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bazar_sales WHERE event_id = ?", Integer.class, f.eventId()))
            .isEqualTo(1);
        assertThat(payments((UUID) first.get("id"))).hasSize(1);
    }

    /**
     * A retry arriving while the first request is still in flight (weak signal): an outside transaction holds the
     * piece row so the first request is blocked mid-sale; the retry must WAIT for it (per-request lock) and then
     * return the same sale — never race it into BAZAR_ALREADY_SOLD or a second sale. Two backends waiting on locks
     * proves both are in flight before the hold is released.
     */
    @Test
    void sameClientRequestId_concurrently_oneSale() throws Exception {
        Fixture f = liveEvent(1);
        String req = crid();
        String c = code(f.pieces().get(0));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection hold = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres")) {
            hold.setAutoCommit(false);
            try (PreparedStatement ps = hold.prepareStatement("SELECT 1 FROM pieces WHERE id = ? FOR UPDATE")) {
                ps.setString(1, f.pieces().get(0));
                ps.executeQuery();
            }
            Callable<Object> task = () -> {
                TenantContext.set(tenantId);
                try { return sales.sell(f.eventId(), c, req, null, worker1).get("id"); }
                catch (BazarException e) { return e.code(); }
                finally { TenantContext.clear(); }
            };
            Future<Object> a = pool.submit(task), b = pool.submit(task);
            long deadline = System.currentTimeMillis() + 20_000;
            while (jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'",
                    Integer.class) < 2) {
                if (System.currentTimeMillis() > deadline) throw new AssertionError("requests never both in flight");
                Thread.onSpinWait();
            }
            hold.commit();
            Object ra = a.get(30, TimeUnit.SECONDS), rb = b.get(30, TimeUnit.SECONDS);
            assertThat(ra).isInstanceOf(UUID.class);
            assertThat(rb).isEqualTo(ra);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bazar_sales WHERE event_id = ?", Integer.class, f.eventId()))
            .isEqualTo(1);
    }

    /**
     * Two sellers scan the same piece at the same moment. An outside transaction holds the piece row so BOTH sales
     * pass every pre-check and block on the ledger's race-guarded UPDATE; on release one wins and the other gets
     * BAZAR_ALREADY_SOLD (the ledger's StateConflictException), with nothing of it written.
     */
    @Test
    void concurrentDoubleScan_oneWins_otherAlreadySold() throws Exception {
        Fixture f = liveEvent(1);
        String p = f.pieces().get(0);
        String c = code(p);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection hold = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres")) {
            hold.setAutoCommit(false);
            try (PreparedStatement ps = hold.prepareStatement("SELECT 1 FROM pieces WHERE id = ? FOR UPDATE")) {
                ps.setString(1, p);
                ps.executeQuery();
            }
            Callable<Object> task1 = sellTask(f.eventId(), c, worker1);
            Callable<Object> task2 = sellTask(f.eventId(), c, worker2);
            Future<Object> a = pool.submit(task1), b = pool.submit(task2);
            // Both sales are now waiting on the piece row inside the ledger UPDATE.
            long deadline = System.currentTimeMillis() + 20_000;
            while (waitingOnLock() < 2) {
                if (System.currentTimeMillis() > deadline) throw new AssertionError("sales never reached the ledger");
                Thread.onSpinWait();
            }
            hold.commit();
            List<Object> results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertThat(results).filteredOn(r -> r instanceof Map).hasSize(1);
            assertThat(results).filteredOn(r -> r == BazarException.Code.BAZAR_ALREADY_SOLD).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(pieceStatus(p)).isEqualTo("sold");
        assertThat(qtySold(f.transferId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bazar_sales WHERE event_id = ?", Integer.class, f.eventId()))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'bazar_sold'",
            Integer.class, p)).isEqualTo(1);
    }

    private Callable<Object> sellTask(UUID eventId, String c, BazarSaleService.Actor who) {
        return () -> {
            TenantContext.set(tenantId);
            try { return sales.sell(eventId, c, crid(), null, who); }
            catch (BazarException e) { return e.code(); }
            finally { TenantContext.clear(); }
        };
    }

    private int waitingOnLock() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
            "AND query LIKE '%UPDATE pieces%'", Integer.class);
    }

    // ── undo ─────────────────────────────────────────────────────────────────

    @Test
    void undo_pieceBackOnTheTransfer_outcomeCleared_paymentsVoided_canSellAgain() {
        Fixture f = liveEvent(1);
        String p = f.pieces().get(0);
        UUID saleId = (UUID) sales.sell(f.eventId(), code(p), crid(), null, worker1).get("id");

        Map<String, Object> undone = sales.undo(f.eventId(), saleId, worker1);

        assertThat(undone.get("status")).isEqualTo("undone");
        assertThat(pieceStatus(p)).isEqualTo("out_on_transfer");
        assertThat(outcome(f.transferId(), p)).isNull();
        assertThat(qtySold(f.transferId())).isZero();
        assertThat(jdbc.queryForObject("SELECT event_type FROM piece_events WHERE piece_id = ? AND from_status = 'sold'",
            String.class, p)).isEqualTo("bazar_sale_undone");
        List<Map<String, Object>> pays = payments(saleId);
        assertThat(pays).hasSize(2);
        assertThat(pays.get(1).get("kind")).isEqualTo("void");
        assertThat(pays.get(1).get("voids_payment_id")).isNotNull();
        assertThat(todayByMethod(f.eventId()).get("cash")).isEqualByComparingTo("0");
        assertThat(((Map<?, ?>) bazar.get(f.eventId()).get("counts")).get("sold")).isEqualTo(0);

        assertThat(codeOf(() -> sales.undo(f.eventId(), saleId, owner))).isEqualTo(BazarException.Code.BAZAR_SALE_UNDONE);

        // The piece can be sold again (a new sale, a new number).
        Map<String, Object> again = sales.sell(f.eventId(), code(p), crid(), null, worker2);
        assertThat(again.get("id")).isNotEqualTo(saleId);
        assertThat(pieceStatus(p)).isEqualTo("sold");
    }

    @Test
    void undo_window_workerOwnSaleOnly_within2Minutes_managerAnyTime() {
        Fixture f = liveEvent(3);
        UUID s1 = (UUID) sales.sell(f.eventId(), code(f.pieces().get(0)), crid(), null, worker1).get("id");
        UUID s2 = (UUID) sales.sell(f.eventId(), code(f.pieces().get(1)), crid(), null, worker1).get("id");
        UUID s3 = (UUID) sales.sell(f.eventId(), code(f.pieces().get(2)), crid(), null, worker1).get("id");

        // Another worker can't undo it.
        assertThat(codeOf(() -> sales.undo(f.eventId(), s1, worker2))).isEqualTo(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED);
        // Past 2 minutes the seller can't either…
        jdbc.update("UPDATE bazar_sales SET sold_at = now() - interval '3 minutes' WHERE id IN (?, ?)", s1, s2);
        assertThat(codeOf(() -> sales.undo(f.eventId(), s1, worker1))).isEqualTo(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED);
        // …but they CAN still change how it was paid, for as long as the event is live; another worker can't.
        assertThat(sales.changePayment(f.eventId(), s1, "card", worker1).get("method")).isEqualTo("card");
        assertThat(codeOf(() -> sales.changePayment(f.eventId(), s2, "card", worker2)))
            .isEqualTo(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED);
        // …and a manager / owner can, any time while live.
        assertThat(sales.undo(f.eventId(), s1, manager).get("status")).isEqualTo("undone");
        assertThat(sales.undo(f.eventId(), s2, owner).get("status")).isEqualTo("undone");
        // Within the window the seller can.
        assertThat(sales.undo(f.eventId(), s3, worker1).get("status")).isEqualTo("undone");
    }

    @Test
    void undo_refusedWhenTheEventIsNotLive() {
        Fixture f = liveEvent(1);
        UUID saleId = (UUID) sales.sell(f.eventId(), code(f.pieces().get(0)), crid(), null, owner).get("id");
        jdbc.update("UPDATE bazar_events SET status = 'ended', ended_at = now(), ended_by = ? WHERE id = ?",
            ownerId, f.eventId());
        assertThat(codeOf(() -> sales.undo(f.eventId(), saleId, owner))).isEqualTo(BazarException.Code.BAZAR_EVENT_NOT_LIVE);
        assertThat(codeOf(() -> sales.changePayment(f.eventId(), saleId, "card", owner)))
            .isEqualTo(BazarException.Code.BAZAR_EVENT_NOT_LIVE);
        assertThat(codeOf(() -> sales.sell(f.eventId(), "P1", crid(), null, owner)))
            .isEqualTo(BazarException.Code.BAZAR_EVENT_NOT_LIVE);
        assertThat(pieceStatus(f.pieces().get(0))).isEqualTo("sold");
    }

    @Test
    void aBazarSale_cannotBeReversedByLookupNotSold() {
        Fixture f = liveEvent(1);
        String p = f.pieces().get(0);
        sales.sell(f.eventId(), code(p), crid(), null, owner);
        // Lookup "Not sold" only reverses a Lookup sale (sold_offline); the ledger has no other way back for it.
        Assertions.assertThrows(RuntimeException.class, () -> pieceAdjust.markNotSold(p, ownerId));
        assertThat(pieceStatus(p)).isEqualTo("sold");
    }

    @Autowired com.traceability.inventory.PieceAdjustService pieceAdjust;

    // ── edits ────────────────────────────────────────────────────────────────

    @Test
    void changePayment_isVoidPlusNew_totalsFollow() {
        Fixture f = liveEvent(1);
        UUID saleId = (UUID) sales.sell(f.eventId(), code(f.pieces().get(0)), crid(), null, worker1).get("id");

        Map<String, Object> s = sales.changePayment(f.eventId(), saleId, "instapay", worker1);
        assertThat(s.get("method")).isEqualTo("instapay");
        List<Map<String, Object>> pays = payments(saleId);
        assertThat(pays).extracting(m -> m.get("kind")).containsExactly("payment", "void", "payment");
        assertThat(pays.get(2).get("method")).isEqualTo("instapay");
        assertThat(pays.get(2).get("amount")).isEqualTo(new BigDecimal("349.00"));
        assertThat(todayByMethod(f.eventId()).get("cash")).isEqualByComparingTo("0");
        assertThat(todayByMethod(f.eventId()).get("instapay")).isEqualByComparingTo("349.00");
        @SuppressWarnings("unchecked")
        Map<String, Object> today = (Map<String, Object>) bazar.get(f.eventId()).get("today");
        assertThat((BigDecimal) today.get("total")).isEqualByComparingTo("349.00");
        assertThat(today.get("sales")).isEqualTo(1);

        // Same method again: no new rows.
        sales.changePayment(f.eventId(), saleId, "instapay", manager);
        assertThat(payments(saleId)).hasSize(3);
        // A method the event doesn't take.
        Fixture cashCard = live(event(List.of("cash", "card"), List.of("owner"), variantA, 1));
        UUID s2 = (UUID) sales.sell(cashCard.eventId(), code(cashCard.pieces().get(0)), crid(), null, owner).get("id");
        assertThat(codeOf(() -> sales.changePayment(cashCard.eventId(), s2, "instapay", owner)))
            .isEqualTo(BazarException.Code.BAZAR_PAYMENT_METHOD_NOT_ALLOWED);
    }

    @Test
    void customer_phoneNormalised_garbageRefused_workersSeeItMasked() {
        Fixture f = liveEvent(1);
        UUID saleId = (UUID) sales.sell(f.eventId(), code(f.pieces().get(0)), crid(), null, worker1).get("id");

        Map<String, Object> s = sales.setCustomer(f.eventId(), saleId, "  Nour  ", "+20 101 234 5678", owner);
        assertThat(s.get("customer_name")).isEqualTo("Nour");
        assertThat(s.get("customer_phone")).isEqualTo("01012345678");
        assertThat(jdbc.queryForObject("SELECT customer_phone FROM bazar_sales WHERE id = ?", String.class, saleId))
            .isEqualTo("01012345678");
        assertThat(sales.setCustomer(f.eventId(), saleId, "Nour", "٠١١٢٣٤٥٦٧٨٩", owner).get("customer_phone"))
            .isEqualTo("01123456789");

        for (String bad : List.of("abc", "0101234", "01312345678", "010123456789", "0101234567x")) {
            assertThat(codeOf(() -> sales.setCustomer(f.eventId(), saleId, "Nour", bad, owner)))
                .as(bad).isEqualTo(BazarException.Code.BAZAR_INVALID_PHONE);
        }

        // The worker who sold it can edit it, and sees the phone masked; the manager sees it in full.
        Map<String, Object> mine = sales.setCustomer(f.eventId(), saleId, "Nour A.", "01012345678", worker1);
        assertThat(mine.get("customer_name")).isEqualTo("Nour A.");
        assertThat(mine.get("customer_phone")).isEqualTo("010*****678");
        assertThat(firstItem(f.eventId(), worker2).get("customer_phone")).isEqualTo("010*****678");
        assertThat(firstItem(f.eventId(), worker2).get("customer_name")).isEqualTo("Nour A.");
        assertThat(firstItem(f.eventId(), manager).get("customer_phone")).isEqualTo("01012345678");
        // Another worker can't edit it.
        assertThat(codeOf(() -> sales.setCustomer(f.eventId(), saleId, "X", null, worker2)))
            .isEqualTo(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED);
        // Blank clears.
        assertThat(sales.setCustomer(f.eventId(), saleId, "", " ", owner).get("customer_phone")).isNull();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstItem(UUID eventId, BazarSaleService.Actor who) {
        return ((List<Map<String, Object>>) sales.list(eventId, 10, null, who).get("items")).get(0);
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void list_newestFirst_paged() {
        Fixture f = liveEvent(3);
        List<Object> ids = new ArrayList<>();
        for (String p : f.pieces()) ids.add(sales.sell(f.eventId(), code(p), crid(), "card", worker1).get("id"));
        sales.undo(f.eventId(), (UUID) ids.get(0), worker1);

        Map<String, Object> page1 = sales.list(f.eventId(), 2, null, owner);
        List<Map<String, Object>> items1 = (List<Map<String, Object>>) page1.get("items");
        assertThat(items1).extracting(m -> m.get("id")).containsExactly(ids.get(2), ids.get(1));
        assertThat(items1.get(0)).containsKeys("sale_number", "sold_at", "piece_code", "product_title",
            "variant_title", "price", "method", "seller_name", "status");
        assertThat(items1.get(0).get("method")).isEqualTo("card");
        assertThat(page1.get("next_cursor")).isNotNull();

        Map<String, Object> page2 = sales.list(f.eventId(), 2, (String) page1.get("next_cursor"), owner);
        List<Map<String, Object>> items2 = (List<Map<String, Object>>) page2.get("items");
        assertThat(items2).extracting(m -> m.get("id")).containsExactly(ids.get(0));
        assertThat(items2.get(0).get("status")).isEqualTo("undone");
        assertThat(page2.get("next_cursor")).isNull();
    }

    // ── roles / flag ─────────────────────────────────────────────────────────

    @Test
    void sellerRoles_enforced() {
        Fixture f = live(event(List.of("cash"), List.of("owner", "manager"), variantA, 2));
        assertThat(codeOf(() -> sales.sell(f.eventId(), code(f.pieces().get(0)), crid(), null, worker1)))
            .isEqualTo(BazarException.Code.BAZAR_NOT_A_SELLER);
        assertThat(codeOf(() -> sales.list(f.eventId(), 10, null, worker1)))
            .isEqualTo(BazarException.Code.BAZAR_NOT_A_SELLER);
        assertThat(pieceStatus(f.pieces().get(0))).isEqualTo("out_on_transfer");

        Fixture workersOnly = live(event(List.of("cash"), List.of("worker"), variantA, 1));
        assertThat(codeOf(() -> sales.sell(workersOnly.eventId(), code(workersOnly.pieces().get(0)), crid(), null, owner)))
            .isEqualTo(BazarException.Code.BAZAR_NOT_A_SELLER);
        UUID s = (UUID) sales.sell(workersOnly.eventId(), code(workersOnly.pieces().get(0)), crid(), null, worker1).get("id");
        // Owner / manager still manage the event's sales (list, undo) without being sellers.
        assertThat((List<?>) sales.list(workersOnly.eventId(), 10, null, owner).get("items")).hasSize(1);
        assertThat(sales.undo(workersOnly.eventId(), s, manager).get("status")).isEqualTo("undone");
    }

    @Test
    void flagOff_everyCallIsNotEnabled() {
        TenantContext.set(offTenantId);
        UUID id = UUID.randomUUID();
        for (Runnable r : List.<Runnable>of(
                () -> sales.sell(id, "P1", crid(), null, owner),
                () -> sales.undo(id, id, owner),
                () -> sales.changePayment(id, id, "cash", owner),
                () -> sales.setCustomer(id, id, null, null, owner),
                () -> sales.list(id, 10, null, owner))) {
            assertThat(codeOf(r)).isEqualTo(BazarException.Code.BAZAR_NOT_ENABLED);
        }
    }

    @Test
    void anotherTenantsEvent_isNotFound() {
        Fixture f = liveEvent(1);
        TenantContext.set(otherTenantId);
        assertThat(codeOf(() -> sales.sell(f.eventId(), code(f.pieces().get(0)), crid(), null, owner)))
            .isEqualTo(BazarException.Code.BAZAR_EVENT_NOT_FOUND);
    }

    // ── RLS (app_user) + append-only ─────────────────────────────────────────

    @Test
    void rls_appUser_seesOnlyItsOwnSalesPaymentsAndCounter() {
        Fixture f = liveEvent(1);
        UUID saleId = (UUID) sales.sell(f.eventId(), code(f.pieces().get(0)), crid(), null, owner).get("id");

        TenantAwareDataSource ds = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setReadOnly(true);
        String saleSql = "SELECT COUNT(*) FROM bazar_sales WHERE id = ?";
        String paySql  = "SELECT COUNT(*) FROM bazar_payments WHERE sale_id = ?";
        String ctrSql  = "SELECT COUNT(*) FROM bazar_sale_counters WHERE tenant_id = ?";

        TenantContext.set(otherTenantId);
        assertThat((Integer) tx.execute(s -> app.queryForObject(saleSql, Integer.class, saleId))).isZero();
        assertThat((Integer) tx.execute(s -> app.queryForObject(paySql, Integer.class, saleId))).isZero();
        assertThat((Integer) tx.execute(s -> app.queryForObject(ctrSql, Integer.class, tenantId))).isZero();

        TenantContext.clear();
        assertThat((Integer) tx.execute(s -> app.queryForObject(saleSql, Integer.class, saleId))).as("no tenant").isZero();

        TenantContext.set(tenantId);   // same-tenant positive control
        assertThat((Integer) tx.execute(s -> app.queryForObject(saleSql, Integer.class, saleId))).isEqualTo(1);
        assertThat((Integer) tx.execute(s -> app.queryForObject(paySql, Integer.class, saleId))).isEqualTo(1);
        assertThat((Integer) tx.execute(s -> app.queryForObject(ctrSql, Integer.class, tenantId))).isEqualTo(1);
    }

    @Test
    void appUser_cannotUpdateOrDeletePayments_norDeleteSales() {
        Fixture f = liveEvent(1);
        UUID saleId = (UUID) sales.sell(f.eventId(), code(f.pieces().get(0)), crid(), null, owner).get("id");
        TenantAwareDataSource ds = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        for (String sql : List.of(
                "UPDATE bazar_payments SET amount = 0 WHERE sale_id = ?",
                "DELETE FROM bazar_payments WHERE sale_id = ?",
                "DELETE FROM bazar_sales WHERE id = ?")) {
            Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                () -> tx.execute(s -> app.update(sql, saleId)), sql);
        }
        // Positive control: app_user CAN insert a payment row for its own tenant (append works).
        tx.execute(s -> app.update("INSERT INTO bazar_payments (tenant_id, sale_id, method, amount, kind, created_by) " +
            "VALUES (?, ?, 'cash', 1, 'refund', ?)", tenantId, saleId, ownerId));
        assertThat(payments(saleId)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT amount FROM bazar_payments WHERE sale_id = ? AND kind = 'payment'",
            BigDecimal.class, saleId)).isEqualByComparingTo("349.00");
    }
}
