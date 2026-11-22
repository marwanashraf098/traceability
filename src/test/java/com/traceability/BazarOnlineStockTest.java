package com.traceability;

import com.traceability.bazar.BazarDayService;
import com.traceability.bazar.BazarEndService;
import com.traceability.bazar.BazarException;
import com.traceability.bazar.BazarSaleService;
import com.traceability.bazar.BazarService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.LocationController;
import com.traceability.inventory.MainReconcileService;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
 * Bazar B8 — online stock per bazar, through the real paths, the Shopify gateway mocked, on a tenant whose Main
 * Warehouse is linked and counted through receiving (every piece's receipt +1 applied).
 *
 *   k1 KEEP SHOWING ONLINE end to end — send (scan + by count), remove from send (scan + count), tap sell, scan sell,
 *      cart, return (good + damaged), exchange, damaged home (scanned + tapped), lost, leftovers back (scanned + by
 *      count), close: NO transfer_shopify_syncs row, the transfer's snapshot is 'leave', no inventory claim row of any
 *      kind, and Shopify saw NO mutation (only reads). Then Main reconcile (report-only) on that variant: the pieces
 *      sold / lost / damaged at the bazar come off — delta = −(sold + lost + damaged) (approved 2026-11-22).
 *   r1 REMOVE (the default) positive control — the same kinds of action: exactly one pushTransferOut with −N for the
 *      pieces sent, and exactly one +1 per good leftover (scanned and by count) — as today.
 *   m1 the choice: new location gets it; detail online_stock / location / locked; change while draft + preparing;
 *      locked after Mark sent (409 BAZAR_ONLINE_STOCK_LOCKED), snapshot final; bad mode → 400
 *   m2 reuse: picking a location writes the chosen mode onto it; 409 BAZAR_ONLINE_STOCK_CONFLICT while another
 *      unsent bazar there has the other mode (create AND change); free again once that bazar is sent
 *   m3 Settings › Locations: changing the mode of a location with an unsent bazar → 409 (same code), the same mode is
 *      fine; once sent the switch works again (later transfers only)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)   // k1 and r1 first: a remove-mode send's push is asynchronous
class BazarOnlineStockTest {

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
        r.add("traced.main-reconcile.enabled", () -> "true");      // report-only (the default) — reads only
    }

    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";
    /** Gateway methods that only READ. Anything else the mock sees is a mutation. */
    static final Set<String> READS = Set.of("fetchMainLevels", "fetchMainLevelsPage", "resolveInventoryItemIds",
        "inactiveMainLevels");

    @Autowired JdbcTemplate       jdbc;
    @Autowired BazarService       bazar;
    @Autowired BazarSaleService   sales;
    @Autowired BazarDayService    days;
    @Autowired BazarEndService    end;
    @Autowired LocationController locations;
    @Autowired MainReconcileService reconcile;
    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyTokenProvider tokenProvider;

    final AtomicInteger seq = new AtomicInteger();
    final Map<String, ShopifyGateway.MainLevel> shopifyMain = new HashMap<>();
    UUID tenant, owner, main, product;
    String traced;
    BazarSaleService.Actor actor;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID();
        owner = UUID.randomUUID();
        main = UUID.randomUUID();
        product = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        String shop = "bazar-online-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        traced = "gid://shopify/Location/" + (90_000_000L + Math.abs(tenant.hashCode() % 1000));
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Bazar Online Stock', true)", tenant);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            owner, tenant, "owner-" + tenant + "@bazar-online.test");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, " +
            "shopify_sync_status) VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')", main, tenant, traced);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Dress', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        actor = new BazarSaleService.Actor(owner, "owner");
    }

    @BeforeEach
    void reset() {
        Mockito.reset(shopify, tokenProvider);
        shopifyMain.clear();
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(shopify.resolveInventoryItemIds(any(), any(), anyList())).thenReturn(Map.of());
        when(shopify.inactiveMainLevels(any(), any(), any(), anyList())).thenReturn(Set.of());
        when(shopify.fetchMainLevels(any(), any(), any(), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            List<ShopifyGateway.MainLevel> out = new ArrayList<>();
            for (String id : ids) if (shopifyMain.containsKey(id)) out.add(shopifyMain.get(id));
            return out;
        });
        TenantContext.set(tenant);
        // LocationController is called directly: its @PreAuthorize needs an owner signed in.
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.TestingAuthenticationToken("owner", null, "ROLE_OWNER"));
    }

    @AfterEach void clear() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // ── k1 ───────────────────────────────────────────────────────────────────

    @Test @Order(1)
    void k1_keepShowingOnline_endToEnd_noShopifyWrite_thenReconcileLowersSoldLostDamaged() throws Exception {
        UUID labelled = variant(), untagged = variant();
        // Labelled: l1 scan-sold, l2 cart, l3 sold → returned good → back, l4 exchanged away → back, l5 exchange's new
        // piece (sold), l6 sold → returned damaged → scanned home damaged, l7 lost, l8 good leftover, l9 removed.
        List<String> l = new ArrayList<>();
        for (int i = 0; i < 9; i++) l.add(piece(labelled, true));
        // Untagged: 6 sent by count; 1 removed by count; 1 tap-sold; 1 tap-sold → returned damaged → tapped home;
        // 3 brought back by count.
        List<String> u = new ArrayList<>();
        for (int i = 0; i < 6; i++) u.add(piece(untagged, false));

        Map<String, Object> ev = create("Keep Bazar", null, "Keep Hall " + seq.incrementAndGet(), "leave");
        UUID eventId = (UUID) ev.get("id");
        UUID transferId = (UUID) ev.get("transfer_id");
        UUID location = (UUID) ev.get("location_id");
        assertThat(mode(location)).isEqualTo("leave");
        assertThat(ev).containsEntry("online_stock", "leave").containsEntry("online_stock_locked", false);

        for (String p : l) assertThat(bazar.scanOut(eventId, code(p), owner, false).success()).isTrue();
        bazar.sendByCount(eventId, untagged, 6, "k1-send", owner);
        bazar.removeFromSend(eventId, code(l.get(8)), null, null, "k1-rm-scan", owner);
        bazar.removeFromSend(eventId, null, untagged, 1, "k1-rm-count", owner);
        Map<String, Object> sent = bazar.markSent(eventId, owner);
        assertThat(sent).containsEntry("transfer_shopify_sync_mode", "leave").containsEntry("online_stock", "leave")
            .containsEntry("online_stock_locked", true);
        bazar.start(eventId, owner);

        sales.sell(eventId, code(l.get(0)), "k1-s1", "cash", actor);
        sales.completeCart(eventId, List.of(code(l.get(1))), null, "cash", null, null, "k1-cart", actor);
        sales.sell(eventId, code(l.get(2)), "k1-s3", "cash", actor);
        sales.returnSale(eventId, code(l.get(2)), null, "good", "k1-r3", actor);
        sales.sell(eventId, code(l.get(3)), "k1-s4", "cash", actor);
        sales.exchange(eventId, code(l.get(3)), null, code(l.get(4)), null, "cash", "good", "k1-x4", actor);
        sales.sell(eventId, code(l.get(5)), "k1-s6", "cash", actor);
        sales.returnSale(eventId, code(l.get(5)), null, null, "damaged", "k1-r6", actor);
        sales.sell(eventId, null, untagged, "k1-t1", "cash", actor);
        Map<String, Object> t2 = sales.sell(eventId, null, untagged, "k1-t2", "cash", actor);
        sales.returnSale(eventId, null, (UUID) t2.get("id"), null, "damaged", "k1-rt2", actor);
        String tappedDamaged = jdbc.queryForObject("SELECT piece_id FROM bazar_sales WHERE id = ?", String.class, t2.get("id"));
        closeToday(eventId, "k1-close");

        end.end(eventId, actor);
        end.scanBack(eventId, code(l.get(2)), "k1-b3", actor);
        end.scanBack(eventId, code(l.get(3)), "k1-b4", actor);
        end.scanBack(eventId, code(l.get(5)), "k1-b6", actor);
        end.scanBack(eventId, code(l.get(7)), "k1-b8", actor);
        end.bringHomeDamaged(eventId, tappedDamaged, "k1-home", actor);
        end.bringBackByCount(eventId, untagged, 3, "k1-back", actor);
        end.markLost(eventId, List.of(l.get(6)), actor);
        end.closeTransfer(eventId, actor);
        Thread.sleep(800);   // anything asynchronous (a push, a +1) would have started by now

        assertThat(count("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ?", transferId))
            .as("'leave' = no claim row").isZero();
        assertThat(jdbc.queryForObject("SELECT shopify_sync_mode FROM transfers WHERE id = ?", String.class, transferId))
            .isEqualTo("leave");
        assertThat(count("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND variant_id IN (?, ?) " +
            "AND trigger_type <> 'receiving_session'", tenant, labelled, untagged)).isZero();
        assertNoMutation();

        // Decision 4 — Main reconcile still lowers the pieces that are gone. Shopify still has every piece (no −N).
        int received = l.size() + u.size();
        int soldLostDamaged = count("SELECT COUNT(*) FROM pieces WHERE tenant_id = ? AND variant_id IN (?, ?) " +
            "AND status::text IN ('sold', 'lost', 'damaged')", tenant, labelled, untagged);
        assertThat(soldLostDamaged).as("l1 l2 l5 sold, l6 damaged, l7 lost; t1 sold, t2 damaged").isEqualTo(7);
        int labelledGone = count("SELECT COUNT(*) FROM pieces WHERE variant_id = ? AND status::text IN ('sold', 'lost', 'damaged')", labelled);
        level(labelled, l.size());
        level(untagged, u.size());
        UUID run = TenantContext.runAs(tenant, () -> reconcile.runVariants(tenant, List.of(labelled, untagged)));
        Map<String, Object> d = diff(run, labelled);
        assertThat(d.get("away_counted")).isEqualTo(0);
        assertThat(d.get("shopify_on_hand")).isEqualTo(l.size());
        assertThat(d.get("delta")).as("%s", d).isEqualTo(-labelledGone);
        assertThat(diff(run, untagged).get("delta")).isEqualTo(-(soldLostDamaged - labelledGone));
        assertThat(received).isEqualTo(15);
        assertNoMutation();
    }

    // ── r1 ───────────────────────────────────────────────────────────────────

    @Test @Order(2)
    void r1_removeFromOnlineStock_positiveControl_minusNThenPlusOnePerGoodLeftover() throws Exception {
        UUID labelled = variant(), untagged = variant();
        List<String> l = List.of(piece(labelled, true), piece(labelled, true), piece(labelled, true), piece(labelled, true));
        for (int i = 0; i < 3; i++) piece(untagged, false);

        Map<String, Object> ev = create("Remove Bazar", null, "Remove Hall " + seq.incrementAndGet(), null);
        UUID eventId = (UUID) ev.get("id");
        UUID transferId = (UUID) ev.get("transfer_id");
        assertThat(ev).containsEntry("online_stock", "remove");
        for (String p : l) assertThat(bazar.scanOut(eventId, code(p), owner, false).success()).isTrue();
        bazar.sendByCount(eventId, untagged, 3, "r1-send", owner);
        bazar.removeFromSend(eventId, code(l.get(3)), null, null, "r1-rm", owner);
        bazar.markSent(eventId, owner);
        await("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ? AND status = 'pushed'", 1, transferId);
        bazar.start(eventId, owner);
        sales.sell(eventId, code(l.get(0)), "r1-s1", "cash", actor);
        sales.sell(eventId, null, untagged, "r1-t1", "cash", actor);
        closeToday(eventId, "r1-close");
        end.end(eventId, actor);
        end.scanBack(eventId, code(l.get(1)), "r1-b2", actor);
        end.bringBackByCount(eventId, untagged, 2, "r1-back", actor);
        end.markLost(eventId, List.of(l.get(2)), actor);
        end.closeTransfer(eventId, actor);

        String applied = "SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND variant_id IN (?, ?) " +
            "AND trigger_type = 'transfer_return' AND status = 'applied'";
        await(applied, 3, tenant, labelled, untagged);
        assertThat(count(applied, tenant, labelled, untagged)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT shopify_sync_mode FROM transfers WHERE id = ?", String.class, transferId))
            .isEqualTo("remove");
        Map<String, Object> sync = jdbc.queryForMap("SELECT deltas::text AS deltas FROM transfer_shopify_syncs WHERE transfer_id = ?", transferId);
        assertThat((String) sync.get("deltas")).contains(labelled + "\": 3").contains(untagged + "\": 3");
        verify(shopify, times(1)).pushTransferOut(any(), any(), anyList(), any(), any(), any());
        verify(shopify, times(3)).adjustInventoryQuantities(any(), any(), any(), eq(traced), eq(1), anyString(), any());
    }

    // ── m1 ───────────────────────────────────────────────────────────────────

    @Test @Order(3)
    void m1_choice_changeWhileDraft_lockedAfterSend() {
        UUID v = variant();
        Map<String, Object> ev = create("Choice Bazar", null, "Choice Hall " + seq.incrementAndGet(), "remove");
        UUID eventId = (UUID) ev.get("id");
        UUID location = (UUID) ev.get("location_id");
        assertThat(ev).containsEntry("location_shopify_sync_mode", "remove").containsEntry("transfer_shopify_sync_mode", null);

        Map<String, Object> changed = bazar.setOnlineStock(eventId, "leave", owner);
        assertThat(changed).containsEntry("online_stock", "leave").containsEntry("online_stock_locked", false);
        assertThat(mode(location)).isEqualTo("leave");
        assertThat(code(() -> bazar.setOnlineStock(eventId, "sometimes", owner))).isEqualTo("BAZAR_INVALID_INPUT");
        assertThat(code(() -> bazar.setOnlineStock(eventId, null, owner))).isEqualTo("BAZAR_INVALID_INPUT");
        assertThat(code(() -> create("Bad", null, "Bad Hall " + seq.incrementAndGet(), "maybe"))).isEqualTo("BAZAR_INVALID_INPUT");
        bazar.setOnlineStock(eventId, "remove", owner);
        bazar.setOnlineStock(eventId, "leave", owner);

        assertThat(bazar.scanOut(eventId, code(piece(v, true)), owner, false).success()).isTrue();
        bazar.markSent(eventId, owner);
        assertThat(code(() -> bazar.setOnlineStock(eventId, "remove", owner))).isEqualTo("BAZAR_ONLINE_STOCK_LOCKED");
        assertThat(code(() -> bazar.setOnlineStock(eventId, "leave", owner))).isEqualTo("BAZAR_ONLINE_STOCK_LOCKED");
        bazar.start(eventId, owner);
        assertThat(code(() -> bazar.setOnlineStock(eventId, "remove", owner))).isEqualTo("BAZAR_ONLINE_STOCK_LOCKED");
        // The location can change in Settings now (later transfers only) — the snapshot stays final.
        locations.setShopifySyncMode(location, Map.of("mode", "remove"));
        Map<String, Object> after = bazar.get(eventId);
        assertThat(after).containsEntry("transfer_shopify_sync_mode", "leave").containsEntry("online_stock", "leave")
            .containsEntry("location_shopify_sync_mode", "remove").containsEntry("online_stock_locked", true);
    }

    // ── m2 ───────────────────────────────────────────────────────────────────

    @Test @Order(4)
    void m2_reuse_writesTheChoice_conflictWhileAnotherUnsentBazarDisagrees() {
        UUID v = variant();
        Map<String, Object> a = create("Reuse A", null, "Reuse Hall " + seq.incrementAndGet(), "leave");
        UUID location = (UUID) a.get("location_id");
        // Another bazar at the same location with the other choice: refused, nothing created.
        int events = count("SELECT COUNT(*) FROM bazar_events WHERE tenant_id = ?", tenant);
        assertThat(code(() -> create("Reuse B", location, null, "remove"))).isEqualTo("BAZAR_ONLINE_STOCK_CONFLICT");
        assertThat(code(() -> create("Reuse B", location, null, null))).as("default = remove").isEqualTo("BAZAR_ONLINE_STOCK_CONFLICT");
        assertThat(count("SELECT COUNT(*) FROM bazar_events WHERE tenant_id = ?", tenant)).isEqualTo(events);
        assertThat(mode(location)).isEqualTo("leave");
        // The same choice is fine.
        Map<String, Object> b = create("Reuse B", location, null, "leave");
        UUID bId = (UUID) b.get("id");
        // Now A can't change either while B (unsent) is there.
        assertThat(code(() -> bazar.setOnlineStock((UUID) a.get("id"), "remove", owner))).isEqualTo("BAZAR_ONLINE_STOCK_CONFLICT");
        // Once A and B are sent, the location is free: a new bazar there writes its choice.
        for (Map<String, Object> e : List.of(a, b)) {
            assertThat(bazar.scanOut((UUID) e.get("id"), code(piece(v, true)), owner, false).success()).isTrue();
            bazar.markSent((UUID) e.get("id"), owner);
        }
        assertThat(jdbc.queryForObject("SELECT shopify_sync_mode FROM transfers WHERE id = ?", String.class, b.get("transfer_id")))
            .isEqualTo("leave");
        Map<String, Object> c = create("Reuse C", location, null, "remove");
        assertThat(mode(location)).isEqualTo("remove");
        assertThat(c).containsEntry("online_stock", "remove");
        assertThat(bId).isNotNull();
    }

    // ── m3 ───────────────────────────────────────────────────────────────────

    @Test @Order(5)
    void m3_settingsLocations_refusedWhileTheLocationHasAnUnsentBazar() {
        UUID v = variant();
        Map<String, Object> ev = create("Settings Bazar", null, "Settings Hall " + seq.incrementAndGet(), "remove");
        UUID location = (UUID) ev.get("location_id");
        assertThat(code(() -> locations.setShopifySyncMode(location, Map.of("mode", "leave")))).isEqualTo("BAZAR_ONLINE_STOCK_CONFLICT");
        assertThat(mode(location)).isEqualTo("remove");
        assertThat(locations.setShopifySyncMode(location, Map.of("mode", "remove"))).containsEntry("shopify_sync_mode", "remove");
        // Another non-bazar location is unaffected.
        UUID showroom = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) VALUES (?, ?, ?, 'warehouse', false, false)",
            showroom, tenant, "Showroom " + seq.incrementAndGet());
        assertThat(locations.setShopifySyncMode(showroom, Map.of("mode", "leave"))).containsEntry("shopify_sync_mode", "leave");
        // Sent → the switch works again.
        assertThat(bazar.scanOut((UUID) ev.get("id"), code(piece(v, true)), owner, false).success()).isTrue();
        bazar.markSent((UUID) ev.get("id"), owner);
        assertThat(locations.setShopifySyncMode(location, Map.of("mode", "leave"))).containsEntry("shopify_sync_mode", "leave");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> create(String name, UUID locationId, String locationName, String mode) {
        return bazar.create(new BazarService.CreateEvent(name + " " + seq.incrementAndGet(),
            LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2), locationId, locationName,
            List.of("cash"), List.of("owner"), mode, null, null), owner);
    }

    private UUID variant() {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id, price) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 500.00)", v, tenant, product, "gid://shopify/ProductVariant/" + v,
            "V" + seq.incrementAndGet(), "SKU-" + v.toString().substring(0, 8), "gid://shopify/InventoryItem/" + v);
        return v;
    }

    /** A piece received at main the real way: its receipt's +1 applied (Shopify counts at Main through receiving). */
    private String piece(UUID v, boolean tagged) {
        String id = String.format("01BAZARONLINE%013d", seq.incrementAndGet());
        UUID receipt = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, tenant_id, received_by, location_id) VALUES (?, ?, ?, ?)",
            receipt, tenant, owner, main);
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, payload, status) VALUES (?, ?, ?, ?, 1, 'receiving_session', ?, '{}'::jsonb, 'applied')",
            tenant, UUID.randomUUID(), v, main, receipt.toString());
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, receipt_id, " +
            "created_at) VALUES (?, ?, ?, ?, ?, 'available'::piece_status, ?, ?, now() - interval '1 day')",
            id, tenant, v, "BO-" + id, tagged ? "O" + (5000000 + seq.get()) : null, main, receipt);
        return id;
    }

    private String code(String id) {
        String c = jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, id);
        return c != null ? c : "BO-" + id;
    }

    private String mode(UUID location) {
        return jdbc.queryForObject("SELECT shopify_sync_mode FROM locations WHERE id = ?", String.class, location);
    }

    private void closeToday(UUID eventId, String crid) {
        LocalDate today = jdbc.queryForObject("SELECT (now() AT TIME ZONE 'Africa/Cairo')::date", LocalDate.class);
        days.close(eventId, today, new BazarDayService.Entered(new BigDecimal("10000"), null, null, null, BigDecimal.ZERO),
            null, crid, actor);
    }

    private void level(UUID variant, int onHand) {
        String item = "gid://shopify/InventoryItem/" + variant;
        shopifyMain.put(item, new ShopifyGateway.MainLevel(item, true, onHand, onHand, 0, 0));
    }

    private Map<String, Object> diff(UUID run, UUID variant) {
        List<Map<String, Object>> d = jdbc.queryForList(
            "SELECT * FROM main_reconcile_diffs WHERE run_id = ? AND variant_id = ?", run, variant);
        assertThat(d).as("one diff for %s in run %s", variant, run).hasSize(1);
        return d.get(0);
    }

    /** Every Shopify call the mock saw was a read. */
    private void assertNoMutation() {
        for (Invocation inv : Mockito.mockingDetails(shopify).getInvocations()) {
            assertThat(READS).as("Shopify call %s", inv.getMethod().getName()).contains(inv.getMethod().getName());
        }
    }

    private String code(Runnable r) {
        BazarException e = catchThrowableOfType(r::run, BazarException.class);
        assertThat(e).as("expected a BazarException").isNotNull();
        return e.code().name();
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
}
