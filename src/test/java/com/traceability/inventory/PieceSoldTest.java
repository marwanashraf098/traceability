package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.JwtService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * C1 (2026-10-25) — Lookup "Sold" (available → sold, event sold_offline) and "Not sold" (sold → available,
 * event not_sold). Fixtures mirror C2: a tenant seeded an hour ago, pieces received a day ago (Shopify counts
 * them at the Traced Main Warehouse).
 *
 *   s1 Sold of a counted piece → one piece_sold claim (piece:sold_event_id), one pushPieceSold(−1), the event
 *   s2 skip — the piece's arrival never reached Shopify → skipped arrival_not_counted, no write
 *   s3 skip — Shopify not counting at the main warehouse (no seed, no receiving +1) → skipped not_counting
 *   s4 reserved piece → PIECE_COMMITTED; released through releaseForAdjust → Sold works
 *   s5 a non-available piece (damaged) → 409, nothing written
 *   n1 Not sold after an applied −1 → +1 (piece_sold_return, "correction"); sold again → a second −1 (new key)
 *   n2 Not sold after a skipped Sold → no +1 (departure_not_reached)
 *   n3 a transfer's off-book sale (sold_offbook) is never reversed by Not sold; Lookup says canUndoSold false
 *   l1 Lookup: phrase keys sold_offline / not_sold; canUndoSold only on a Lookup-sold piece
 *   h1 HTTP: a worker gets 403 on both endpoints (piece unchanged); a manager can Sold / Not sold
 *   x1 app_user: tenant B can't claim tenant A's piece_sold; A's own claim goes through
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PieceSoldTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PieceAdjustService adjust;
    @Autowired LookupService lookup;
    @MockBean JobScheduler jobScheduler;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    record T(UUID tenant, UUID store, String shop, UUID location, String traced, UUID user, UUID variant,
             String item) {}

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void reset() {
        Mockito.reset(shopifyGateway, tokenProvider, jobScheduler);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── Sold ──────────────────────────────────────────────────────────────────────

    @Test
    void s1_sold_counted_oneClaim_oneDecrement_andTheEvent() throws Exception {
        T t = tenant("s1", true);
        String piece = piece(t, "available", true);

        as(t, () -> adjust.markSold(piece, "Sold at the showroom", t.user()));

        assertThat(status(piece)).isEqualTo("sold");
        Map<String, Object> ev = jdbc.queryForMap("SELECT event_type, actor_user_id, metadata::text AS meta FROM piece_events " +
            "WHERE piece_id = ? AND to_status = 'sold'::piece_status", piece);
        assertThat(ev).containsEntry("event_type", "sold_offline").containsEntry("actor_user_id", t.user());
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = mapper.readValue((String) ev.get("meta"), Map.class);
        assertThat(meta).containsEntry("reason", "sold_offline").containsEntry("note", "Sold at the showroom");
        String soldEventId = (String) meta.get("sold_event_id");
        assertThat(soldEventId).isNotBlank();

        awaitStatus(t, "piece_sold", piece, "applied");
        assertThat(claim(t, "piece_sold", piece)).containsEntry("trigger_id", piece + ":" + soldEventId)
            .containsEntry("delta", -1).containsEntry("location_id", t.location());
        verify(shopifyGateway, times(1)).pushPieceSold(eq(t.shop()), eq("tok"), eq(t.item()), eq(t.traced()),
            eq(-1), eq("traced://piece/" + piece), anyString());
        verify(shopifyGateway, never()).pushPieceWriteOff(any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(count(t, "trigger_type <> 'initial_seed'")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_id = ? AND action = 'piece_sold'",
            Integer.class, piece)).isEqualTo(1);
    }

    @Test
    void s2_sold_arrivalNeverReachedShopify_skipped_noWrite() throws Exception {
        T t = tenant("s2", true);
        String piece = piece(t, "available", false);   // received after the seed, no receiving +1

        as(t, () -> adjust.markSold(piece, null, t.user()));

        assertThat(status(piece)).isEqualTo("sold");
        assertThat(claim(t, "piece_sold", piece)).containsEntry("status", "skipped")
            .containsEntry("skip_reason", "arrival_not_counted");
        Thread.sleep(300);
        verifyNoWrites();
    }

    @Test
    void s3_sold_shopifyNotCounting_skipped_noWrite() throws Exception {
        T t = tenant("s3", false);   // linked, but no seed and no receiving +1 applied
        String piece = piece(t, "available", true);

        as(t, () -> adjust.markSold(piece, null, t.user()));

        assertThat(status(piece)).isEqualTo("sold");
        assertThat(claim(t, "piece_sold", piece)).containsEntry("status", "skipped")
            .containsEntry("skip_reason", "not_counting");
        Thread.sleep(300);
        verifyNoWrites();
    }

    @Test
    void s4_reservedPiece_committed_thenReleased_thenSold() throws Exception {
        T t = tenant("s4", true);
        String piece = piece(t, "reserved", true);
        UUID order = UUID.randomUUID(), item = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, raw) " +
            "VALUES (?, ?, ?, ?, '#S4', 'ready_to_pick'::order_status, '{}'::jsonb)",
            order, t.tenant(), t.store(), "gid://shopify/Order/" + seq.incrementAndGet());
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            item, t.tenant(), order, t.variant());
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'active'::allocation_status)",
            t.tenant(), item, piece);

        assertThatThrownBy(() -> as(t, () -> adjust.markSold(piece, null, t.user())))
            .isInstanceOf(PieceCommittedException.class);
        assertThat(status(piece)).isEqualTo("reserved");
        assertThat(count(t, "trigger_type = 'piece_sold'")).isZero();

        as(t, () -> adjust.releaseForAdjust(piece, t.user()));
        as(t, () -> adjust.markSold(piece, null, t.user()));

        assertThat(status(piece)).isEqualTo("sold");
        awaitStatus(t, "piece_sold", piece, "applied");
    }

    @Test
    void s5_damagedPiece_refused_nothingWritten() {
        T t = tenant("s5", true);
        String piece = piece(t, "damaged", true);

        assertThatThrownBy(() -> as(t, () -> adjust.markSold(piece, null, t.user())))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(status(piece)).isEqualTo("damaged");
        assertThat(count(t, "trigger_type = 'piece_sold'")).isZero();
    }

    // ── Not sold ──────────────────────────────────────────────────────────────────

    @Test
    void n1_notSold_afterAppliedDecrement_plusOne_thenSoldAgain_secondDecrement() throws Exception {
        T t = tenant("n1", true);
        String piece = piece(t, "available", true);
        as(t, () -> adjust.markSold(piece, null, t.user()));
        awaitStatus(t, "piece_sold", piece, "applied");

        as(t, () -> adjust.markNotSold(piece, t.user()));

        assertThat(status(piece)).isEqualTo("available");
        assertThat(jdbc.queryForObject("SELECT event_type FROM piece_events WHERE piece_id = ? " +
            "AND from_status = 'sold'::piece_status", String.class, piece)).isEqualTo("not_sold");
        awaitStatus(t, "piece_sold_return", piece, "applied");
        assertThat(claim(t, "piece_sold_return", piece)).containsEntry("delta", 1).containsEntry("location_id", t.location());
        verify(shopifyGateway, times(1)).pushPieceIncrement(eq(t.shop()), eq("tok"), eq(t.item()), eq(t.traced()),
            eq(1), eq("correction"), eq("traced://piece/" + piece), anyString());

        // A second sale is its own departure (new sold_event_id) — Shopify goes down once more.
        as(t, () -> adjust.markSold(piece, null, t.user()));
        long deadline = System.currentTimeMillis() + 10_000;
        while (count(t, "trigger_type = 'piece_sold' AND status = 'applied'") < 2) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("second sale never applied");
            Thread.sleep(25);
        }
        verify(shopifyGateway, times(2)).pushPieceSold(eq(t.shop()), eq("tok"), eq(t.item()), eq(t.traced()),
            eq(-1), eq("traced://piece/" + piece), anyString());
    }

    @Test
    void n2_notSold_afterSkippedSold_noPlusOne() throws Exception {
        T t = tenant("n2", true);
        String piece = piece(t, "available", false);   // never counted by Shopify
        as(t, () -> adjust.markSold(piece, null, t.user()));
        assertThat(claim(t, "piece_sold", piece)).containsEntry("status", "skipped");

        as(t, () -> adjust.markNotSold(piece, t.user()));

        assertThat(status(piece)).isEqualTo("available");
        assertThat(claim(t, "piece_sold_return", piece)).containsEntry("status", "skipped")
            .containsEntry("skip_reason", "departure_not_reached");
        Thread.sleep(300);
        verifyNoWrites();
    }

    @Test
    void n3_transferOffBookSale_neverReversed() {
        T t = tenant("n3", true);
        String piece = piece(t, "sold", true);
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status, occurred_at) " +
            "VALUES (?, ?, 'sold_offbook', 'out_on_transfer', 'sold', now() - interval '1 hour')", t.tenant(), piece);

        assertThatThrownBy(() -> as(t, () -> adjust.markNotSold(piece, t.user())))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(status(piece)).isEqualTo("sold");
        assertThat(count(t, "trigger_type <> 'initial_seed'")).isZero();
        Map<String, Object> r = TenantContext.runAs(t.tenant(), () -> lookup.lookupPiece(piece, false));
        assertThat(r).containsEntry("canUndoSold", false);
    }

    // ── Lookup ────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void l1_lookup_phrases_andCanUndoSold() {
        T t = tenant("l1", false);
        String piece = piece(t, "available", true);
        Map<String, Object> before = TenantContext.runAs(t.tenant(), () -> lookup.lookupPiece(piece, false));
        assertThat(before).doesNotContainKey("canUndoSold");

        as(t, () -> adjust.markSold(piece, null, t.user()));
        Map<String, Object> sold = TenantContext.runAs(t.tenant(), () -> lookup.lookupPiece(piece, false));
        assertThat(sold).containsEntry("status", "sold").containsEntry("canUndoSold", true);

        as(t, () -> adjust.markNotSold(piece, t.user()));
        Map<String, Object> back = TenantContext.runAs(t.tenant(), () -> lookup.lookupPiece(piece, false));
        assertThat(back).doesNotContainKey("canUndoSold");
        List<String> phrases = new ArrayList<>();
        for (Map<String, Object> e : (List<Map<String, Object>>) back.get("timeline")) phrases.add((String) e.get("phraseKey"));
        assertThat(phrases).contains("sold_offline", "not_sold");
    }

    // ── HTTP / roles ──────────────────────────────────────────────────────────────

    @Test
    void h1_worker403_managerCanSoldAndNotSold() {
        T t = tenant("h1", false);
        UUID worker = user(t, "worker"), manager = user(t, "manager");
        String piece = piece(t, "available", true);

        assertThat(post(piece, "sold", jwt.issueAccessToken(worker, t.tenant(), "worker")).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(piece)).isEqualTo("available");

        String managerToken = jwt.issueAccessToken(manager, t.tenant(), "manager");
        assertThat(post(piece, "sold", managerToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(status(piece)).isEqualTo("sold");

        assertThat(post(piece, "not-sold", jwt.issueAccessToken(worker, t.tenant(), "worker")).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(piece)).isEqualTo("sold");
        assertThat(post(piece, "not-sold", managerToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(status(piece)).isEqualTo("available");
    }

    // ── app_user / RLS ────────────────────────────────────────────────────────────

    @Test
    void x1_appUser_tenantBNeverClaimsTenantAsSale_ownClaimGoesThrough() {
        T a = tenant("x1a", true), b = tenant("x1b", true);
        String pa = piece(a, "available", true);

        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        DataSourceTransactionManager appTxm = new DataSourceTransactionManager(appDs);
        ShopifyInventoryService appInventory = new ShopifyInventoryService(appJdbc, appTxm, shopifyGateway, tokenProvider,
            mapper, new StoreRepository(appJdbc, appTxm));
        TransactionTemplate appTx = new TransactionTemplate(appTxm);

        assertThatThrownBy(() -> TenantContext.runAs(b.tenant(), () -> appTx.execute(st ->
            appInventory.claimPieceDeparture(b.tenant(), "piece_sold", pa, pa + ":x"))))
            .as("tenant B cannot even see A's piece").isInstanceOf(IllegalStateException.class);
        assertThat(count(a, "trigger_type = 'piece_sold'")).isZero();

        Long id = TenantContext.runAs(a.tenant(), () -> appTx.execute(st ->
            appInventory.claimPieceDeparture(a.tenant(), "piece_sold", pa, pa + ":own")));
        assertThat(id).as("same-tenant positive control").isNotNull();
        assertThat(claim(a, "piece_sold", pa)).containsEntry("status", "queued");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private void verifyNoWrites() {
        verify(shopifyGateway, never()).pushPieceSold(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushPieceWriteOff(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushPieceIncrement(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    private ResponseEntity<String> post(String piece, String action, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + "/api/v1/pieces/" + piece + "/" + action,
            HttpMethod.POST, new HttpEntity<>("{}", h), String.class);
    }

    private UUID user(T t, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, ?, ?, 'h', ?::user_role)",
            id, t.tenant(), role, role + "-" + id.toString().substring(0, 8) + "@test.local", role);
        return id;
    }

    private T tenant(String name, boolean seeded) {
        UUID tenant = UUID.randomUUID();
        UUID store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        String shop = "sold-" + name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String traced = "gid://shopify/Location/" + shop;
        String variantGid = "gid://shopify/ProductVariant/" + (8_000_000 + seq.incrementAndGet());
        String item = "gid://shopify/InventoryItem/" + name + "-" + variant.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, name + "-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')", location, tenant, traced);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", variant, tenant, product, variantGid, "SKU-" + name, item);
        T t = new T(tenant, store, shop, location, traced, user, variant, item);
        if (seeded) {
            jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
                "trigger_type, trigger_id, status, created_at) VALUES (?, ?, ?, ?, 1, 'initial_seed', ?, 'applied', " +
                "now() - interval '1 hour')", tenant, UUID.randomUUID(), variant, location, "fixture-seed:" + UUID.randomUUID());
        }
        return t;
    }

    private String piece(T t, String status, boolean receivedBeforeSeed) {
        int k = seq.incrementAndGet();
        String id = "01S1" + UUID.randomUUID().toString().replace("-", "").substring(0, 22).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, created_at) " +
            "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?, " + (receivedBeforeSeed ? "now() - interval '1 day'" : "now()") + ")",
            id, t.tenant(), t.variant(), status, "S1-" + id + "-" + k, String.format("S%07d", k), t.location());
        return id;
    }

    private void as(T t, Runnable r) {
        TenantContext.runAs(t.tenant(), () -> { r.run(); return null; });
    }

    private String status(String piece) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece);
    }

    private int count(T t, String where) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND " + where,
            Integer.class, t.tenant());
    }

    private Map<String, Object> claim(T t, String triggerType, String piece) {
        return jdbc.queryForMap("SELECT trigger_id, status, skip_reason, delta, location_id " +
            "FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = ? " +
            "AND split_part(trigger_id, ':', 1) = ?", t.tenant(), triggerType, piece);
    }

    private void awaitStatus(T t, String triggerType, String piece, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
                "AND trigger_type = ? AND split_part(trigger_id, ':', 1) = ? AND status = ?",
                Integer.class, t.tenant(), triggerType, piece, status) == 0) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(triggerType + " for " + piece + " never reached " + status + ": "
                    + jdbc.queryForList("SELECT trigger_type, status, skip_reason, error FROM shopify_inventory_adjustments " +
                        "WHERE tenant_id = ?", t.tenant()));
            }
            Thread.sleep(25);
        }
    }
}
