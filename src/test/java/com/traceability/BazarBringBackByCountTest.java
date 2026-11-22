package com.traceability;

import com.traceability.bazar.BazarCatalogService;
import com.traceability.bazar.BazarDayService;
import com.traceability.bazar.BazarEndService;
import com.traceability.bazar.BazarException;
import com.traceability.bazar.BazarSaleService;
import com.traceability.bazar.BazarService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Bazar POS B7d-2 — leftovers without a barcode come home after the event: bring back by count (untagged only,
 * oldest first, all or nothing, retry-safe, through the scan-back's own good path — the Shopify +1 per piece only
 * when its departure reached Shopify, with a positive control), a damaged one tapped home, the balance still adds up,
 * who may do it (the scan-back rule: seller_roles, not removed), and hidden never-sent sizes (B7d-1 follow-up). Main
 * Warehouse linked and seeded, the gateway mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarBringBackByCountTest {

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

    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";

    @Autowired JdbcTemplate        jdbc;
    @Autowired BazarService        bazar;
    @Autowired BazarSaleService    sales;
    @Autowired BazarDayService     days;
    @Autowired BazarEndService     end;
    @Autowired BazarCatalogService catalogs;
    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyTokenProvider tokenProvider;

    final AtomicInteger seq = new AtomicInteger();
    UUID tenant, otherTenant, owner, worker, main, product;
    String traced;
    BazarSaleService.Actor ownerA, workerA;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID();
        otherTenant = UUID.randomUUID();
        owner = UUID.randomUUID();
        worker = UUID.randomUUID();
        main = UUID.randomUUID();
        product = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        String shop = "bazar-back-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        traced = "gid://shopify/Location/" + shop;
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Bazar Back Count', true)", tenant);
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Bazar Back Other', true)", otherTenant);
        for (Object[] u : List.of(new Object[]{owner, "owner"}, new Object[]{worker, "worker"})) {
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, ?, ?, 'h', ?::user_role)",
                u[0], tenant, "U " + u[1], u[1] + "-" + tenant + "@bazar-back.test", u[1]);
        }
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?)", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, " +
            "shopify_sync_status) VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')", main, tenant, traced);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Dress', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        ownerA = new BazarSaleService.Actor(owner, "owner");
        workerA = new BazarSaleService.Actor(worker, "worker");
    }

    @BeforeEach
    void reset() {
        Mockito.reset(shopify, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        TenantContext.set(tenant);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    /** A fresh variant of the test product, counted at main (seeded) — each test its own, so no test picks another's pieces. */
    private UUID newVariant() {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id, price) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 500.00)", v, tenant, product, "gid://shopify/ProductVariant/" + v,
            "V" + seq.incrementAndGet(), "SKU-" + v.toString().substring(0, 8), "gid://shopify/InventoryItem/" + v);
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, payload, status, created_at) VALUES (?, ?, ?, ?, 4, 'initial_seed', 'seed', '{}'::jsonb, " +
            "'applied', now() - interval '1 hour')", tenant, UUID.randomUUID(), v, main);
        return v;
    }

    /** A piece received at main after the seed; {@code counted}: its receipt's +1 applied. */
    private String piece(UUID v, boolean tagged, boolean counted, int minutesAgo) {
        String id = String.format("01BAZARBACKCNT%012d", seq.incrementAndGet());
        UUID receipt = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, tenant_id, received_by, location_id) VALUES (?, ?, ?, ?)",
            receipt, tenant, owner, main);
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, payload, status) VALUES (?, ?, ?, ?, 1, 'receiving_session', ?, '{}'::jsonb, ?)",
            tenant, UUID.randomUUID(), v, main, receipt.toString(), counted ? "applied" : "failed");
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, receipt_id, " +
            "created_at) VALUES (?, ?, ?, ?, ?, 'available'::piece_status, ?, ?, now() - (? * interval '1 minute'))",
            id, tenant, v, "BB-" + id, tagged ? "K" + (4000000 + seq.get()) : null, main, receipt, minutesAgo);
        return id;
    }

    private String code(String id) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, id);
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    private void await(String sql, int n, Object... args) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (count(sql, args) < n) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("never reached " + n + ": " + sql);
            Thread.sleep(25);
        }
        Thread.sleep(300);
    }

    /** A live event (seller roles owner + worker) whose stock went out: labelled by scan, untagged by count. */
    private UUID liveEvent(List<String> labelled, Map<UUID, Integer> byCount) throws Exception {
        Map<String, Object> ev = bazar.create(new BazarService.CreateEvent("Back Bazar " + seq.incrementAndGet(),
            LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2), null, "Back Hall " + seq.get(),
            List.of("cash"), List.of("owner", "worker"), null, null, null), owner);
        UUID eventId = (UUID) ev.get("id");
        for (String p : labelled) assertThat(bazar.scanOut(eventId, code(p), owner, false).success()).isTrue();
        for (Map.Entry<UUID, Integer> e : byCount.entrySet()) {
            bazar.sendByCount(eventId, e.getKey(), e.getValue(), "send-" + UUID.randomUUID(), owner);
        }
        bazar.markSent(eventId, owner);
        await("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ? AND status IN ('pushed', 'skipped')", 1,
            (UUID) ev.get("transfer_id"));
        bazar.start(eventId, owner);
        return eventId;
    }

    private UUID transferOf(UUID eventId) {
        return jdbc.queryForObject("SELECT transfer_id FROM bazar_events WHERE id = ?", UUID.class, eventId);
    }

    private String status(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private UUID location(String pieceId) {
        return jdbc.queryForObject("SELECT current_location_id FROM pieces WHERE id = ?", UUID.class, pieceId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> counts(Map<String, Object> leftovers) {
        return (Map<String, Object>) leftovers.get("counts");
    }

    private static String code(Throwable t) {
        return ((BazarException) t).code().name();
    }

    private BazarException refused(Runnable r) {
        return catchThrowableOfType(r::run, BazarException.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(Map<String, Object> r) {
        return (List<String>) r.get("piece_ids");
    }

    // ── bring back by count ─────────────────────────────────────────────────

    @Test
    void b1_untaggedOnly_oldestFirst_backGoodAtMain_plusOneOnlyWhenTheDepartureCounted() throws Exception {
        UUID variant = newVariant(), other = newVariant();
        String counted1 = piece(variant, false, true, 50);
        String counted2 = piece(variant, false, true, 40);
        String never = piece(variant, false, false, 30);     // its +N never reached Shopify → left out of the −N
        String newest = piece(variant, false, true, 20);
        String labelled = piece(variant, true, true, 10);
        UUID eventId = liveEvent(List.of(labelled), Map.of(variant, 4));
        UUID transferId = transferOf(eventId);
        assertThat((String) jdbc.queryForObject("SELECT piece_ids::text FROM transfer_shopify_syncs WHERE transfer_id = ?",
            String.class, transferId)).contains(counted1, counted2, newest, labelled).doesNotContain(never);
        end.end(eventId, ownerA);

        Map<String, Object> r = end.bringBackByCount(eventId, variant, 3, "back-1", workerA);
        assertThat(ids(r)).containsExactly(counted1, counted2, never);
        assertThat(r.get("count")).isEqualTo(3);
        assertThat(r.get("replayed")).isEqualTo(false);
        for (String p : List.of(counted1, counted2, never)) {
            assertThat(status(p)).isEqualTo("available");
            assertThat(location(p)).isEqualTo(main);
            assertThat(jdbc.queryForObject("SELECT outcome FROM transfer_pieces WHERE transfer_id = ? AND piece_id = ?",
                String.class, transferId, p)).isEqualTo("returned_good");
            assertThat(jdbc.queryForObject("SELECT metadata->>'by' FROM piece_events WHERE piece_id = ? " +
                "AND event_type = 'returned_from_transfer'", String.class, p)).isEqualTo("count");
        }
        assertThat(status(newest)).isEqualTo("out_on_transfer");
        assertThat(status(labelled)).isEqualTo("out_on_transfer");
        assertThat(counts((Map<String, Object>) r.get("leftovers"))).containsEntry("back", 3).containsEntry("outstanding", 2);
        assertThat(jdbc.queryForObject("SELECT qty_returned_good FROM transfer_lines WHERE transfer_id = ? AND variant_id = ?",
            Integer.class, transferId, variant)).isEqualTo(3);

        // Shopify: +1 for each piece whose departure was counted (the positive control), none for `never`.
        String applied = "SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'transfer_return' AND status = 'applied' AND variant_id = ?";
        await(applied, 2, tenant, variant);
        assertThat(jdbc.queryForList("SELECT split_part(trigger_id, ':', 1) FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'transfer_return' AND status = 'applied' AND variant_id = ?",
            String.class, tenant, variant)).containsExactlyInAnyOrder(counted1, counted2);
        // Only this test's item: another test's background +1 (b2 …) names its own item, so the order doesn't matter.
        verify(shopify, times(2)).adjustInventoryQuantities(any(), any(), eq("gid://shopify/InventoryItem/" + variant),
            eq(traced), eq(1), anyString(), any());
        assertThat(count("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'transfer_return' AND split_part(trigger_id, ':', 1) = ? AND status = 'applied'", tenant, never))
            .isZero();
    }

    @Test
    void b2_labelledLeftoversMustBeScanned_andNotEnoughBringsNothingBack() throws Exception {
        UUID variant = newVariant(), other = newVariant();
        String u = piece(other, false, true, 30);
        String l1 = piece(other, true, true, 20), l2 = piece(other, true, true, 10);
        UUID eventId = liveEvent(List.of(l1, l2), Map.of(other, 1));
        end.end(eventId, ownerA);

        BazarException more = refused(() -> end.bringBackByCount(eventId, other, 2, "b2-" + UUID.randomUUID(), ownerA));
        assertThat(more.code()).isEqualTo(BazarException.Code.BAZAR_NOT_ENOUGH_UNTAGGED);
        assertThat(more.details()).containsEntry("untagged", 1).containsEntry("labelled", 2);
        assertThat(more.messageEn()).contains("Only 1 without a barcode").contains("scan the 2 with a barcode");
        assertThat(status(u)).as("all or nothing").isEqualTo("out_on_transfer");

        assertThat(end.bringBackByCount(eventId, other, 1, "b2-" + UUID.randomUUID(), ownerA).get("count")).isEqualTo(1);
        BazarException labelledOnly = refused(() -> end.bringBackByCount(eventId, other, 1, "b2-" + UUID.randomUUID(), ownerA));
        assertThat(labelledOnly.code()).isEqualTo(BazarException.Code.BAZAR_NOT_ENOUGH_UNTAGGED);
        assertThat(labelledOnly.messageEn()).isEqualTo("These pieces have a barcode — scan them back one by one.");
        assertThat(status(l1)).isEqualTo("out_on_transfer");
        assertThat(status(l2)).isEqualTo("out_on_transfer");
        // The scan still brings a labelled one back.
        assertThat(end.scanBack(eventId, code(l1), "b2-scan", ownerA).get("result")).isEqualTo("back_good");

        for (int bad : List.of(0, 501)) {
            assertThat(code(refused(() -> end.bringBackByCount(eventId, other, bad, "b2-" + UUID.randomUUID(), ownerA))))
                .isEqualTo("BAZAR_INVALID_INPUT");
        }
        assertThat(code(refused(() -> end.bringBackByCount(eventId, other, 1, " ", ownerA)))).isEqualTo("BAZAR_INVALID_INPUT");
    }

    @Test
    void b3_sameRequestIdTwice_bringsBackOnce_andOneShopifyPlusOne() throws Exception {
        UUID variant = newVariant(), other = newVariant();
        String a = piece(variant, false, true, 30), b = piece(variant, false, true, 20);
        UUID eventId = liveEvent(List.of(), Map.of(variant, 2));
        end.end(eventId, ownerA);
        Map<String, Object> first = end.bringBackByCount(eventId, variant, 1, "b3-same", ownerA);
        Map<String, Object> again = end.bringBackByCount(eventId, variant, 1, "b3-same", ownerA);
        assertThat(again.get("replayed")).isEqualTo(true);
        assertThat(ids(again)).isEqualTo(ids(first)).containsExactly(a);
        assertThat(status(b)).isEqualTo("out_on_transfer");
        await("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = 'transfer_return' " +
            "AND split_part(trigger_id, ':', 1) = ? AND status = 'applied'", 1, tenant, a);
        assertThat(count("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'transfer_return' AND split_part(trigger_id, ':', 1) = ?", tenant, a)).isEqualTo(1);
    }

    @Test
    void b4_onlyAfterTheEventEnded() throws Exception {
        UUID variant = newVariant(), other = newVariant();
        piece(variant, false, true, 30);
        UUID eventId = liveEvent(List.of(), Map.of(variant, 1));
        assertThat(code(refused(() -> end.bringBackByCount(eventId, variant, 1, "b4-" + UUID.randomUUID(), ownerA))))
            .isEqualTo("BAZAR_EVENT_NOT_ENDED");
    }

    // ── damaged without a barcode, tapped home ──────────────────────────────

    @Test
    void d1_aDamagedUntaggedLeftover_isTappedHome_stillDamaged_noShopify_andTheBalanceAddsUp() throws Exception {
        UUID variant = newVariant(), other = newVariant();
        String sold = piece(variant, false, true, 50);
        String damaged = piece(variant, false, true, 40);
        String back = piece(variant, false, true, 30);
        String lost = piece(variant, false, true, 20);
        String labelledDamaged = piece(variant, true, true, 10);
        UUID eventId = liveEvent(List.of(labelledDamaged), Map.of(variant, 4));
        // The labelled one sold by its code and returned damaged; then tap-sold (B7a picks the oldest open piece:
        // `sold`, then `damaged`) and the second one returned damaged.
        Map<String, Object> s3 = sales.sell(eventId, code(labelledDamaged), "d1-s3", "cash", ownerA);
        sales.returnSale(eventId, code(labelledDamaged), null, null, "damaged", "d1-r3", ownerA);
        Map<String, Object> s1 = sales.sell(eventId, null, variant, "d1-s1", "cash", ownerA);
        Map<String, Object> s2 = sales.sell(eventId, null, variant, "d1-s2", "cash", ownerA);
        assertThat(jdbc.queryForObject("SELECT piece_id FROM bazar_sales WHERE id = ?", String.class, s1.get("id"))).isEqualTo(sold);
        assertThat(jdbc.queryForObject("SELECT piece_id FROM bazar_sales WHERE id = ?", String.class, s2.get("id"))).isEqualTo(damaged);
        sales.returnSale(eventId, null, (UUID) s2.get("id"), null, "damaged", "d1-r2", ownerA);
        assertThat(s3).isNotNull();
        LocalDate today = jdbc.queryForObject("SELECT (now() AT TIME ZONE 'Africa/Cairo')::date", LocalDate.class);
        days.close(eventId, today, new BazarDayService.Entered(new BigDecimal("500"), null, null, null, BigDecimal.ZERO),
            null, "d1-close", ownerA);
        end.end(eventId, ownerA);
        Mockito.clearInvocations(shopify);

        // The labelled damaged one is scanned, never tapped.
        assertThat(refused(() -> end.bringHomeDamaged(eventId, labelledDamaged, "d1-tap-l", workerA)).code())
            .isEqualTo(BazarException.Code.BAZAR_PIECE_HAS_BARCODE);
        Map<String, Object> home = end.bringHomeDamaged(eventId, damaged, "d1-tap", workerA);
        assertThat(home.get("result")).isEqualTo("back_damaged");
        assertThat(status(damaged)).isEqualTo("damaged");
        assertThat(location(damaged)).isEqualTo(main);
        assertThat(end.bringHomeDamaged(eventId, damaged, "d1-tap", workerA).get("replayed")).isEqualTo(true);
        assertThat(code(refused(() -> end.bringHomeDamaged(eventId, damaged, "d1-tap-2", workerA)))).isEqualTo("BAZAR_ALREADY_BACK");
        assertThat(code(refused(() -> end.bringHomeDamaged(eventId, back, "d1-tap-3", workerA)))).isEqualTo("BAZAR_ALREADY_BACK");

        end.bringBackByCount(eventId, variant, 1, "d1-back", workerA);
        end.markLost(eventId, List.of(lost), ownerA);
        Map<String, Object> l = end.leftovers(eventId, ownerA);
        Map<String, Object> c = counts(l);
        assertThat(c).containsEntry("sent", 5).containsEntry("sold", 1).containsEntry("back", 1)
            .containsEntry("damaged", 2).containsEntry("lost", 1).containsEntry("outstanding", 0);
        assertThat(l.get("balanced")).isEqualTo(true);
        assertThat(status(back)).isEqualTo("available");
        // The damaged bring-home wrote nothing to Shopify (only the good one's +1).
        await("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = 'transfer_return' " +
            "AND split_part(trigger_id, ':', 1) = ?", 1, tenant, back);
        assertThat(count("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND split_part(trigger_id, ':', 1) = ?", tenant, damaged)).isZero();
    }

    // ── who may do it ───────────────────────────────────────────────────────

    @Test
    void p1_theScanBackRule_sellersOnly_notARemovedSeller_notAnotherTenant() throws Exception {
        UUID variant = newVariant(), other = newVariant();
        String a = piece(variant, false, true, 30);
        Map<String, Object> ev = bazar.create(new BazarService.CreateEvent("Owners only " + seq.incrementAndGet(),
            LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2), null, "Owners Hall " + seq.get(),
            List.of("cash"), List.of("owner"), null, null, null), owner);
        UUID eventId = (UUID) ev.get("id");
        bazar.sendByCount(eventId, variant, 1, "p1-send", owner);
        bazar.markSent(eventId, owner);
        bazar.start(eventId, owner);
        end.end(eventId, ownerA);
        assertThat(code(refused(() -> end.bringBackByCount(eventId, variant, 1, "p1-w", workerA)))).isEqualTo("BAZAR_NOT_A_SELLER");
        assertThat(code(refused(() -> catalogs.bringBackStockView(eventId, workerA)))).isEqualTo("BAZAR_NOT_A_SELLER");

        // A worker in seller_roles who was removed from this bazar is refused too.
        piece(variant, false, true, 25);
        UUID eventW = liveEvent(List.of(), Map.of(variant, 1));
        jdbc.update("INSERT INTO bazar_event_sellers (tenant_id, event_id, user_id, added_by, removed_at, removed_by) " +
            "VALUES (?, ?, ?, ?, now(), ?)", tenant, eventW, worker, owner, owner);
        end.end(eventW, ownerA);
        assertThat(code(refused(() -> end.bringBackByCount(eventW, variant, 1, "p1-rm", workerA)))).isEqualTo("BAZAR_NOT_A_SELLER");
        assertThat(code(refused(() -> catalogs.bringBackStockView(eventW, workerA)))).isEqualTo("BAZAR_NOT_A_SELLER");
        assertThat(code(refused(() -> end.bringHomeDamaged(eventW, a, "p1-rm-d", workerA)))).isEqualTo("BAZAR_NOT_A_SELLER");

        TenantContext.set(otherTenant);
        assertThat(code(refused(() -> end.bringBackByCount(eventId, variant, 1, "p1-o", ownerA)))).isEqualTo("BAZAR_EVENT_NOT_FOUND");
        TenantContext.set(tenant);
        assertThat(status(a)).isEqualTo("out_on_transfer");
    }

    // ── the picker's read ───────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void s1_bringBackStock_isTheUntaggedLeftoversStillOut_andRlsHidesThemFromAnotherTenant() throws Exception {
        UUID variant = newVariant(), other = newVariant();
        piece(variant, false, true, 30);
        piece(variant, false, true, 20);
        String labelled = piece(variant, true, true, 10);
        UUID eventId = liveEvent(List.of(labelled), Map.of(variant, 2));
        end.end(eventId, ownerA);
        Map<String, Object> v = catalogs.bringBackStockView(eventId, workerA);
        List<Map<String, Object>> sizes = ((List<Map<String, Object>>) v.get("products")).stream()
            .flatMap(p -> ((List<Map<String, Object>>) p.get("sizes")).stream()).toList();
        assertThat(sizes).hasSize(1);
        assertThat(sizes.get(0)).containsEntry("variant_id", variant).containsEntry("left", 2);
        end.bringBackByCount(eventId, variant, 2, "s1-back", workerA);
        assertThat((List<?>) catalogs.bringBackStockView(eventId, workerA).get("products")).isEmpty();

        TenantAwareDataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setReadOnly(true);
        String sql = "SELECT COUNT(*) FROM transfer_pieces WHERE transfer_id = ?";
        UUID transferId = transferOf(eventId);
        try {
            TenantContext.set(otherTenant);
            assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, transferId))).isZero();
            TenantContext.clear();
            assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, transferId))).as("no tenant").isZero();
            TenantContext.set(tenant);
            assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, transferId))).isEqualTo(3);
        } finally {
            TenantContext.set(tenant);
        }
    }

    // ── B7d-1 follow-up: a size with no piece sent isn't shown ──────────────

    @Test
    @SuppressWarnings("unchecked")
    void h1_aSizeTakenOffTheSend_isHiddenFromPricesCatalogAndPriceSheet_itsPriceRowKept() throws Exception {
        UUID variant = newVariant(), other = newVariant();
        String kept = piece(variant, false, true, 30);
        String removed = piece(other, false, true, 20);
        Map<String, Object> ev = bazar.create(new BazarService.CreateEvent("Hidden " + seq.incrementAndGet(),
            LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2), null, "Hidden Hall " + seq.get(),
            List.of("cash"), List.of("owner"), null, null, null), owner);
        UUID eventId = (UUID) ev.get("id");
        bazar.sendByCount(eventId, variant, 1, "h1-a", owner);
        bazar.sendByCount(eventId, other, 1, "h1-b", owner);
        bazar.removeFromSend(eventId, null, other, 1, "h1-rm", owner);
        assertThat(status(removed)).isEqualTo("available");
        assertThat(kept).isNotNull();

        assertThat(count("SELECT count(*) FROM bazar_event_prices WHERE event_id = ? AND variant_id = ?", eventId, other))
            .as("the auto price row is kept").isEqualTo(1);
        assertThat(bazar.listPrices(eventId).stream().map(p -> p.get("variant_id")).toList()).containsExactly(variant);
        List<Map<String, Object>> sizes = ((List<Map<String, Object>>) catalogs.catalogView(eventId, ownerA).get("products"))
            .stream().flatMap(p -> ((List<Map<String, Object>>) p.get("sizes")).stream()).toList();
        assertThat(sizes).extracting(s -> s.get("variant_id")).containsExactly(variant);
        assertThat(catalogs.catalog(eventId, ownerA).groups().stream().flatMap(g -> g.sizes().stream())
            .map(BazarCatalogService.Size::variantId).toList()).as("the price sheet's rows").containsExactly(variant);

        // A price set by hand still shows a never-sent size (B7a: a product priced mid-event gets the next number).
        bazar.setOverride(eventId, other, new BigDecimal("450"), owner);
        assertThat(bazar.listPrices(eventId).stream().map(p -> p.get("variant_id")).toList())
            .containsExactlyInAnyOrder(variant, other);
    }
}
