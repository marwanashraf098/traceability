package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.fulfillment.OrderController;
import com.traceability.fulfillment.OrderController.OrderPage;
import com.traceability.fulfillment.OrderController.OrderSummary;
import com.traceability.fulfillment.OrderController.OrderSummaryCounts;
import com.traceability.fulfillment.OrderNotesService;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ShipmentLinkService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orders tabs fix (2026-10-30). "Needs attention" = DISTINCT merchant orders with ≥ 1 open alert
 * (ExceptionService.openAttentionOrderIds), and the summary count, the attention=true list and each row's
 * attentionReasons come from one call of it. Delivered / Returns count and list on the same raw
 * shipment-state predicate (as In transit already did). The list sorts placed_at, created_at, id.
 *
 * Revert check (2026-10-30): with summary's needsAttention swapped for exceptionService.countOpenExceptions()
 * (the raw alert count the page used to show) a1 goes red (4 ≠ 1); with the merchant_orders restriction
 * removed from openAttentionOrderIds a1 goes red (the portal order appears).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrdersAttentionTest {

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

    @Autowired JdbcTemplate               jdbc;
    @Autowired ObjectMapper               mapper;
    @Autowired PlatformTransactionManager txm;
    @Autowired com.traceability.inventory.FulfillService fulfillService;
    @Autowired ExceptionService           exceptionService;
    @Autowired ShipmentLinkService        shipmentLinkService;

    private OrderController     controller;
    private TransactionTemplate tx;

    private JdbcTemplate               appUserJdbc;
    private PlatformTransactionManager appUserTxm;
    private TransactionTemplate        appUserTx;

    @BeforeAll
    void setup() {
        controller = new OrderController(jdbc, mapper, txm, new OrderNotesService(jdbc), fulfillService, exceptionService);
        tx = new TransactionTemplate(txm);
        DriverManagerDataSource rawDs = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(rawDs);
        appUserJdbc = new JdbcTemplate(appDs);
        appUserTxm  = new DataSourceTransactionManager(appDs);
        appUserTx   = new TransactionTemplate(appUserTxm);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    // ── fixture helpers ──────────────────────────────────────────────────────

    record Tenant(UUID id, UUID store) {}

    private Tenant tenant(String name) {
        UUID t = UUID.randomUUID(), s = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", t, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', ?, 'connected')", s, t, "att-" + s + ".myshopify.com");
        return new Tenant(t, s);
    }

    private UUID order(Tenant t, String number, String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'cod', now()) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + UUID.randomUUID(), number, status);
    }

    private UUID portalOrder(Tenant t, String number) {
        UUID gid = UUID.randomUUID();
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
            "    origin, shopify_order_gid) " +
            "VALUES (?, ?, ?, ?, 'delivered', 'cod', now(), 'portal_pre_connect', ?) RETURNING id",
            UUID.class, t.id(), t.store(), "internal:portal:" + gid, number, "gid://shopify/Order/" + gid);
    }

    private UUID shipment(Tenant t, UUID orderId, String leg, String state) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, ?) RETURNING id",
            UUID.class, t.id(), orderId, String.valueOf(1_000_000_000L + new Random().nextInt(900_000_000)),
            state, leg);
    }

    private <T> T as(Tenant t, java.util.function.Supplier<T> body) {
        return TenantContext.runAs(t.id(), () -> tx.execute(s -> body.get()));
    }

    // ── a1: Needs attention = distinct merchant orders with ≥ 1 open alert ──────

    @Test
    void a1_needsAttention_isDistinctMerchantOrders_countEqualsList_reasonsCarried_portalExcluded() {
        Tenant t = tenant("AttentionTenant");

        // X: two open alerts on the order itself.
        UUID x = order(t, "#ATT-X", "new");
        jdbc.update("UPDATE orders SET on_hold = true, hold_reason = 'blocked customer', " +
                    "shopify_edit_conflict_at = now() WHERE id = ?", x);
        // Y: no alert.
        UUID y = order(t, "#ATT-Y", "new");
        // An alert tied to no order: the store needs re-authorisation.
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', ?, 'needs_reauth')", UUID.randomUUID(), t.id(), "att-lost-" + UUID.randomUUID() + ".myshopify.com");
        // A portal-only order with an alert (missing_awb on its shipment — that detector joins `orders`).
        UUID portal = portalOrder(t, "#ATT-P");
        UUID portalLeg = shipment(t, portal, "return", "created");
        jdbc.update("UPDATE shipments SET awb_print_failed_reason = 'test', awb_print_failed_at = now() WHERE id = ?", portalLeg);

        int bell = TenantContext.runAs(t.id(), () -> tx.execute(s -> exceptionService.countOpenExceptions()));
        assertThat(bell).as("the bell still counts every open alert (X ×2, the store, the portal order)").isEqualTo(4);

        OrderSummaryCounts summary = as(t, () -> controller.summary());
        OrderPage list = as(t, () -> controller.list(null, null, null, null, 0, 100, null, true));

        assertThat(summary.needsAttention()).as("distinct merchant orders with an alert — X only").isEqualTo(1);
        assertThat(list.total()).as("the attention list is the same set").isEqualTo(1);
        assertThat(list.items()).extracting(OrderSummary::id).containsExactly(x.toString());
        assertThat(list.items().get(0).attentionReasons())
            .as("X carries both of its alert types").containsExactly("blocked_customer", "shopify_edit_conflict");

        // The full list: reasons on X, none on Y, and the portal row never appears.
        OrderPage all = as(t, () -> controller.list(null, null, null, null, 0, 100, null, false));
        Map<String, List<String>> reasons = new HashMap<>();
        all.items().forEach(o -> reasons.put(o.id(), o.attentionReasons()));
        assertThat(reasons).containsOnlyKeys(x.toString(), y.toString());
        assertThat(reasons.get(y.toString())).isEmpty();
        assertThat(reasons.get(x.toString())).containsExactly("blocked_customer", "shopify_edit_conflict");
    }

    // ── a2: an order is never inferred from a piece ─────────────────────────

    @Test
    void a2_pieceAlertCarryingAnOrderId_doesNotMakeTheOrderNeedAttention() {
        Tenant t = tenant("AttentionPieceTenant");
        UUID z = order(t, "#ATT-Z", "packed");
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, 'Tee')",
            product, t.id(), t.store(), "gid://shopify/Product/" + product);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) VALUES (?, ?, ?, ?, 'M')",
            variant, t.id(), product, "gid://shopify/ProductVariant/" + variant);
        String pieceId = "ATT" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
                    "VALUES (?, ?, ?, ?, ?, 'lost', ?, now())",
            pieceId, t.id(), variant, "PC-" + pieceId, pieceId.substring(0, 8), z);

        List<Map<String, Object>> open = TenantContext.runAs(t.id(), () -> tx.execute(s -> exceptionService.detectAllOpen()));
        assertThat(open).as("the lost alert names Z through the piece")
            .anySatisfy(a -> { assertThat(a.get("type")).isEqualTo("lost"); assertThat(a.get("order_id")).isEqualTo(z); });

        assertThat(as(t, () -> controller.summary()).needsAttention()).isZero();
        assertThat(as(t, () -> controller.list(null, null, null, null, 0, 100, null, true)).total()).isZero();
    }

    // ── a3: every detector type is classified ───────────────────────────────

    @Test
    void a3_everyDetectorTypeIsClassified() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/traceability/inventory/ExceptionService.java"));
        Set<String> emitted = new TreeSet<>();
        Matcher m = Pattern.compile("'(\\w+)' AS type").matcher(src);
        while (m.find()) emitted.add(m.group(1));
        Set<String> classified = new TreeSet<>();
        ExceptionService.ORDER_LINK.keySet().forEach(k -> classified.add(k.substring(0, k.indexOf('/'))));
        assertThat(emitted).as("detector types found").hasSizeGreaterThanOrEqualTo(40);
        assertThat(classified).as("every detector type has an ORDER_LINK entry, and no stale ones")
            .isEqualTo(emitted);
    }

    // ── a4: app_user — RLS scopes the attention set ─────────────────────────

    @Test
    void a4_appUser_sameTenantSeesItsAttentionOrder_otherTenantSeesNone() {
        Tenant t = tenant("AttentionRlsTenant");
        Tenant other = tenant("AttentionRlsOther");
        UUID x = order(t, "#ATT-RLS", "new");
        jdbc.update("UPDATE orders SET on_hold = true WHERE id = ?", x);

        ExceptionService appEx = new ExceptionService(appUserJdbc, Clock.systemUTC(), shipmentLinkService);
        OrderController appCtl = new OrderController(appUserJdbc, mapper, appUserTxm,
            new OrderNotesService(appUserJdbc), fulfillService, appEx);

        // Positive control: the same tenant on app_user sees X.
        OrderSummaryCounts mine = TenantContext.runAs(t.id(), () -> appUserTx.execute(s -> appCtl.summary()));
        OrderPage mineList = TenantContext.runAs(t.id(),
            () -> appCtl.list(null, null, null, null, 0, 100, null, true));
        assertThat(mine.needsAttention()).isEqualTo(1);
        assertThat(mineList.items()).extracting(OrderSummary::id).containsExactly(x.toString());

        // Another tenant on app_user sees none of it.
        OrderSummaryCounts theirs = TenantContext.runAs(other.id(), () -> appUserTx.execute(s -> appCtl.summary()));
        OrderPage theirList = TenantContext.runAs(other.id(),
            () -> appCtl.list(null, null, null, null, 0, 100, null, true));
        assertThat(theirs.needsAttention()).isZero();
        assertThat(theirList.total()).isZero();
        Map<UUID, List<String>> theirSet = TenantContext.runAs(other.id(),
            () -> appUserTx.execute(s -> appEx.openAttentionOrderIds()));
        assertThat(theirSet).isEmpty();
    }

    // ── a5: Delivered / Returns / In transit — count == list ────────────────

    @Test
    void a5_deliveredReturnsInTransit_countEqualsListTotal() {
        Tenant t = tenant("AttentionTabsTenant");
        // Delivered: one normal, two cancelled orders whose leg was delivered, one self-pickup delivered with
        // no shipment. The raw-state predicate counts 3 (the self-pickup has no leg); the old derived count was 2.
        shipment(t, order(t, "#TAB-D1", "delivered"), "forward", "delivered");
        shipment(t, order(t, "#TAB-D2", "cancelled"), "forward", "delivered");
        shipment(t, order(t, "#TAB-D3", "cancelled"), "forward", "delivered");
        UUID selfPickup = order(t, "#TAB-SP", "delivered");
        jdbc.update("UPDATE orders SET is_self_pickup = true WHERE id = ?", selfPickup);
        // Returns: one normal, one cancelled.
        shipment(t, order(t, "#TAB-R1", "awaiting_pickup"), "forward", "returned");
        shipment(t, order(t, "#TAB-R2", "cancelled"), "forward", "returned");
        // In transit: one.
        shipment(t, order(t, "#TAB-T1", "awaiting_pickup"), "forward", "with_courier");

        OrderSummaryCounts summary = as(t, () -> controller.summary());
        long deliveredList = as(t, () -> controller.list(null, "delivered", null, null, 0, 100)).total();
        long returnedList  = as(t, () -> controller.list(null, "returned", null, null, 0, 100)).total();
        long transitList   = as(t, () -> controller.list(null, "with_courier", null, null, 0, 100)).total();

        assertThat(summary.delivered()).as("Delivered tab count == its list").isEqualTo((int) deliveredList).isEqualTo(3);
        assertThat(summary.returned()).as("Returns tab count == its list").isEqualTo((int) returnedList).isEqualTo(2);
        assertThat(summary.withCourier()).as("In transit tab count == its list").isEqualTo((int) transitList).isEqualTo(1);
    }

    // ── a6: sort tie-break — offset pages never overlap ─────────────────────

    @Test
    void a6_sameTimestamps_sortByIdDesc_pagesDoNotOverlap() {
        Tenant t = tenant("AttentionSortTenant");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) ids.add(order(t, "#SORT-" + i, "new"));
        jdbc.update("UPDATE orders SET placed_at = '2026-10-01T10:00:00Z', created_at = '2026-10-01T10:00:00Z' " +
                    "WHERE tenant_id = ?", t.id());
        List<String> expected = jdbc.queryForList(
            "SELECT id::text FROM orders WHERE tenant_id = ? ORDER BY id DESC", String.class, t.id());

        // Pages of 3 over 8 tied rows: together exactly the id-DESC order, no row twice, none skipped.
        List<String> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            int p = page;
            as(t, () -> controller.list(null, null, null, null, p, 3)).items().forEach(o -> paged.add(o.id()));
        }
        assertThat(paged).as("ties on placed_at and created_at break on id DESC").isEqualTo(expected);
    }
}
