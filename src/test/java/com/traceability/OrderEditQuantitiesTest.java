package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.fulfillment.OrderController;
import com.traceability.fulfillment.OrderNotesService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifySyncService;
import com.traceability.integrations.shopify.ShopifyWebhookProcessorJob;
import com.traceability.inventory.AllItemsRemovedException;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.OrderLineFreeze;
import com.traceability.inventory.UntrackedParcelUnits;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Shopify order edits reach line quantities (2026-10-21). Every test drives a REAL REST payload through
 * ShopifyWebhookProcessorJob.process (orders/updated → handleOrderUpdated), in the shapes stored in prod:
 * an order edit KEEPS the removed line with its quantity unchanged and current_quantity 0, adds a new line,
 * and records a refund with restock_type 'cancel' and no transactions (Dolt.eg #16443, M → S); a refund after
 * the parcel came back is a 'return' refund on a line whose current_quantity drops (Nuvira #3134).
 *
 *   a  edit before picking: removed line → 0, added line inserted, no alert; the removed variant's scan →
 *      REMOVED_IN_SHOPIFY; the order completes with the S piece alone
 *   b  edit while picking: the removed line's reservation is released, alert raised
 *   c  edit after packing, before handover: alert, nothing released
 *   d  refund after the parcel came back (history with_courier → returned) on an awaiting_pickup order:
 *      frozen — no alert, quantity kept, raw updated
 *   e  GraphQL import node with currentQuantity 0 → quantity 0 and raw current_quantity 0 (V149 current_qty)
 *   f  every line removed: complete() refuses (ALL_ITEMS_REMOVED_IN_SHOPIFY)
 *   g  self-pickup handed over, then refunded: frozen; still frozen once the piece is restocked (history)
 *   h  frozen order (history with_courier) gets an edit-shaped payload: no alert, no release, quantity kept,
 *      parcel unit rows unchanged; the brand-new line is stored at 0 (raw keeps it), adds no unit rows,
 *      nothing raised
 *   i  a REST payload with no current_quantity keeps today's behaviour (quantity; reduced while picking →
 *      one release + alert)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderEditQuantitiesTest {

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

    @Autowired JdbcTemplate               jdbc;
    @Autowired ObjectMapper               mapper;
    @Autowired ShopifyWebhookProcessorJob processorJob;
    @Autowired ShopifySyncService         syncService;
    @Autowired FulfillService             fulfill;
    @Autowired org.springframework.transaction.PlatformTransactionManager txm;
    @MockBean  BostaGateway               bostaGateway;
    @MockBean  JobScheduler               jobScheduler;

    private static final AtomicLong IDS = new AtomicLong(50_833_358_520_000L);

    /** One tenant + connected store per test, variants M and S of BLACK LEATHER SET. */
    class Shop {
        final PackFixtures f;
        final UUID actor;
        final long restM = IDS.incrementAndGet(), restS = IDS.incrementAndGet();
        final UUID variantM, variantS;
        final String domain;
        int updates = 0;

        Shop(String tag) {
            f = new PackFixtures(jdbc, "OE-" + tag);
            domain = jdbc.queryForObject("SELECT shop_domain FROM stores WHERE id = ?", String.class, f.store);
            jdbc.update("UPDATE stores SET status = 'connected' WHERE id = ?", f.store);
            actor = f.user("Packer " + tag, "owner");
            UUID product = jdbc.queryForObject(
                "INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'BLACK LEATHER SET', 'active') RETURNING id",
                UUID.class, f.tenant, f.store, "gid://shopify/Product/" + IDS.incrementAndGet());
            variantM = variant(product, restM, "M");
            variantS = variant(product, restS, "S");
        }

        private UUID variant(UUID product, long rest, String title) {
            return jdbc.queryForObject(
                "INSERT INTO variants (tenant_id, product_id, external_id, sku, title) VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class, f.tenant, product, "gid://shopify/ProductVariant/" + rest, "BLS-" + title + "-" + rest, title);
        }

        /** Sends one orders/updated event; asserts it processed without error. */
        void send(ObjectNode payload) {
            updates++;
            payload.put("updated_at", Instant.parse("2026-10-20T19:00:00Z").plusSeconds(60L * updates).toString());
            UUID eventId = jdbc.queryForObject(
                "INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw) " +
                "VALUES (?, 'orders/updated', ?, ?, ?::jsonb) RETURNING id",
                UUID.class, f.tenant, domain, UUID.randomUUID().toString(), payload.toString());
            processorJob.process(eventId, f.tenant);
            assertThat(jdbc.queryForObject("SELECT process_error FROM shopify_webhook_events WHERE id = ?",
                String.class, eventId)).isNull();
        }

        UUID orderId(String gid) {
            return jdbc.queryForObject("SELECT id FROM orders WHERE external_id = ? AND tenant_id = ?", UUID.class, gid, f.tenant);
        }

        Integer qty(UUID orderId, long lineId) {
            List<Integer> q = jdbc.queryForList("SELECT quantity FROM order_items WHERE order_id = ? AND external_id = ?",
                Integer.class, orderId, "gid://shopify/LineItem/" + lineId);
            return q.isEmpty() ? null : q.get(0);
        }

        boolean conflict(UUID orderId) {
            return jdbc.queryForObject("SELECT shopify_edit_conflict_at IS NOT NULL FROM orders WHERE id = ?",
                Boolean.class, orderId);
        }

        <T> T as(Supplier<T> s) {
            TenantContext.set(f.tenant);
            try { return s.get(); } finally { TenantContext.clear(); }
        }

        FulfillService.ScanResult scan(UUID orderId, String shortCode) { return as(() -> fulfill.scan(orderId, shortCode, actor)); }
        int complete(UUID orderId) { return as(() -> fulfill.complete(orderId, actor)); }
    }

    // ── a ─────────────────────────────────────────────────────────────────────

    @Test
    void a_editBeforePicking_removedLineToZero_addedLineInserted_noAlert_completesWithS() {
        Shop s = new Shop("a");
        String gid = gid();
        long lineM = IDS.incrementAndGet(), lineS = IDS.incrementAndGet();
        s.send(order(gid, "#16443", lines(line(lineM, s.restM, "M", 1, 1)), refunds()));
        UUID o = s.orderId(gid);
        s.f.forward(o);
        String pieceM = s.f.piece(s.variantM, s.actor), pieceS = s.f.piece(s.variantS, s.actor);

        s.send(editM_to_S(gid, lineM, lineS, s));

        assertThat(s.qty(o, lineM)).as("removed line kept at 0").isZero();
        assertThat(s.qty(o, lineS)).isEqualTo(1);
        assertThat(s.conflict(o)).as("nothing picked yet — no alert").isFalse();
        assertThat(s.scan(o, pieceM).code()).isEqualTo("REMOVED_IN_SHOPIFY");
        FulfillService.ScanResult sr = s.scan(o, pieceS);
        assertThat(sr.success()).isTrue();
        assertThat(sr.allComplete()).as("the S piece alone completes the order").isTrue();
        assertThat(s.complete(o)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, o)).isEqualTo("packed");
    }

    // ── b ─────────────────────────────────────────────────────────────────────

    @Test
    void b_editWhilePicking_removedLineReleased_alertRaised() {
        Shop s = new Shop("b");
        String gid = gid();
        long lineM = IDS.incrementAndGet(), lineS = IDS.incrementAndGet();
        s.send(order(gid, "#16450", lines(line(lineM, s.restM, "M", 1, 1)), refunds()));
        UUID o = s.orderId(gid);
        s.f.forward(o);
        String pieceM = s.f.piece(s.variantM, s.actor);
        assertThat(s.scan(o, pieceM).success()).isTrue();

        s.send(editM_to_S(gid, lineM, lineS, s));

        assertThat(s.qty(o, lineM)).isZero();
        assertThat(s.qty(o, lineS)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, s.f.pieceId(pieceM)))
            .as("the M reservation is released").isEqualTo("available");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM allocations WHERE piece_id = ? AND status = 'active'",
            Integer.class, s.f.pieceId(pieceM))).isZero();
        assertThat(s.conflict(o)).isTrue();
    }

    // ── c ─────────────────────────────────────────────────────────────────────

    @Test
    void c_editAfterPackedBeforeHandover_alert_nothingReleased() {
        Shop s = new Shop("c");
        String gid = gid();
        long lineM = IDS.incrementAndGet(), lineS = IDS.incrementAndGet();
        s.send(order(gid, "#16460", lines(line(lineM, s.restM, "M", 1, 1)), refunds()));
        UUID o = s.orderId(gid);
        s.f.forward(o);
        String pieceM = s.f.piece(s.variantM, s.actor);
        assertThat(s.scan(o, pieceM).success()).isTrue();
        s.complete(o);

        s.send(editM_to_S(gid, lineM, lineS, s));

        assertThat(s.conflict(o)).as("packed parcel no longer matches Shopify").isTrue();
        assertThat(jdbc.queryForObject("SELECT status::text FROM allocations WHERE piece_id = ?", String.class,
            s.f.pieceId(pieceM))).isEqualTo("packed");
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, s.f.pieceId(pieceM)))
            .isEqualTo("packed");
    }

    // ── d ─────────────────────────────────────────────────────────────────────

    @Test
    void d_refundAfterParcelCameBack_awaitingPickupOrder_frozen_noAlert_quantityKept() {
        Shop s = new Shop("d");
        String gid = gid();
        long lineM = IDS.incrementAndGet();
        s.send(order(gid, "#3134", lines(line(lineM, s.restM, "M", 1, 1)), refunds()));
        UUID o = s.orderId(gid);
        // #3134's state in prod: awaiting_pickup, forward leg returned (history with_courier → returned),
        // the piece returned and restocked (allocation released).
        UUID leg = shipped(s, o, "returned", "with_courier", "returning", "returned");
        String pieceM = s.f.piece(s.variantM, s.actor);
        jdbc.update("INSERT INTO allocations (piece_id, order_item_id, tenant_id, status) " +
            "SELECT ?, oi.id, oi.tenant_id, 'released' FROM order_items oi WHERE oi.order_id = ?", s.f.pieceId(pieceM), o);
        jdbc.update("UPDATE orders SET status = 'awaiting_pickup' WHERE id = ?", o);
        assertThat(leg).isNotNull();

        s.send(order(gid, "#3134", lines(line(lineM, s.restM, "M", 1, 0)), refunds(refund(lineM, 1, "return", true))));

        assertThat(s.conflict(o)).as("a refund after the parcel came back is not a pick change").isFalse();
        assertThat(s.qty(o, lineM)).as("quantity is what was shipped").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_qty FROM order_items WHERE order_id = ?", Integer.class, o))
            .as("raw still follows Shopify").isZero();
    }

    // ── e ─────────────────────────────────────────────────────────────────────

    @Test
    void e_graphqlImportNode_currentQuantityZero_storedZero_rawCurrentQuantity() {
        Shop s = new Shop("e");
        String gid = gid();
        String lineM = "gid://shopify/LineItem/" + IDS.incrementAndGet();
        String lineS = "gid://shopify/LineItem/" + IDS.incrementAndGet();
        String lineOld = "gid://shopify/LineItem/" + IDS.incrementAndGet();
        ShopifyGateway.Order node = new ShopifyGateway.Order(gid, "#16443", null, null, null, "PENDING", List.of(),
            new BigDecimal("2199.00"),
            List.of(new ShopifyGateway.LineItem(lineM, 1, "gid://shopify/ProductVariant/" + s.restM, 0),
                    new ShopifyGateway.LineItem(lineS, 1, "gid://shopify/ProductVariant/" + s.restS, 1),
                    new ShopifyGateway.LineItem(lineOld, 2, "gid://shopify/ProductVariant/" + s.restS)),
            Instant.now(), mapper.createObjectNode().put("updatedAt", "2026-10-20T19:22:04Z"));

        syncService.ingestMissingOrder(s.f.store, s.f.tenant, node);

        UUID o = s.orderId(gid);
        Map<String, Object> m = jdbc.queryForMap(
            "SELECT quantity, original_qty, current_qty FROM order_items WHERE order_id = ? AND external_id = ?", o, lineM);
        assertThat(m).containsEntry("quantity", 0).containsEntry("original_qty", 1).containsEntry("current_qty", 0);
        assertThat(jdbc.queryForObject("SELECT quantity FROM order_items WHERE order_id = ? AND external_id = ?",
            Integer.class, o, lineS)).isEqualTo(1);
        Map<String, Object> old = jdbc.queryForMap(
            "SELECT quantity, current_qty, (raw -> 'current_quantity') IS NOT NULL AS has_key FROM order_items WHERE order_id = ? AND external_id = ?",
            o, lineOld);
        assertThat(old).as("no currentQuantity → the ordered quantity, raw unchanged in shape")
            .containsEntry("quantity", 2).containsEntry("has_key", false);
        assertThat(old.get("current_qty")).isNull();
    }

    // ── f ─────────────────────────────────────────────────────────────────────

    @Test
    void f_everyLineRemoved_completeRefuses() {
        Shop s = new Shop("f");
        String gid = gid();
        long lineM = IDS.incrementAndGet();
        s.send(order(gid, "#16470", lines(line(lineM, s.restM, "M", 1, 1)), refunds()));
        UUID o = s.orderId(gid);
        s.f.forward(o);
        String pieceM = s.f.piece(s.variantM, s.actor);

        s.send(order(gid, "#16470", lines(line(lineM, s.restM, "M", 1, 0)), refunds(refund(lineM, 1, "cancel", false))));

        assertThat(s.qty(o, lineM)).isZero();
        assertThat(s.scan(o, pieceM).code()).isEqualTo("REMOVED_IN_SHOPIFY");
        assertThatThrownBy(() -> s.complete(o)).isInstanceOf(AllItemsRemovedException.class)
            .satisfies(e -> assertThat(((AllItemsRemovedException) e).errorCode()).isEqualTo("ALL_ITEMS_REMOVED_IN_SHOPIFY"));
        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, o)).isEqualTo("new");
    }

    // ── g ─────────────────────────────────────────────────────────────────────

    @Test
    void g_selfPickupHandedOverThenRefunded_frozen_evenAfterRestock() {
        Shop s = new Shop("g");
        String gid = gid();
        long lineM = IDS.incrementAndGet();
        s.send(order(gid, "#16480", lines(line(lineM, s.restM, "M", 2, 2)), refunds()));
        UUID o = s.orderId(gid);
        jdbc.update("UPDATE orders SET is_self_pickup = true WHERE id = ?", o);
        String p1 = s.f.piece(s.variantM, s.actor), p2 = s.f.piece(s.variantM, s.actor);
        assertThat(s.scan(o, p1).success()).isTrue();
        assertThat(s.scan(o, p2).success()).isTrue();
        s.complete(o);
        s.as(() -> fulfill.handover(o, s.actor));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id = ?", Integer.class, o))
            .as("self-pickup: no forward leg").isZero();

        s.send(order(gid, "#16480", lines(line(lineM, s.restM, "M", 2, 1)), refunds(refund(lineM, 1, "return", true))));

        assertThat(s.conflict(o)).isFalse();
        assertThat(s.qty(o, lineM)).isEqualTo(2);

        // The returned unit is restocked: its allocation is released and the piece is available again.
        jdbc.update("UPDATE allocations SET status = 'released' WHERE piece_id = ?", s.f.pieceId(p1));
        jdbc.update("UPDATE pieces SET status = 'available' WHERE id = ?", s.f.pieceId(p1));
        jdbc.update("UPDATE allocations SET status = 'released' WHERE piece_id = ?", s.f.pieceId(p2));
        jdbc.update("UPDATE pieces SET status = 'available' WHERE id = ?", s.f.pieceId(p2));
        assertThat(s.as(() -> OrderLineFreeze.isFrozen(jdbc, o, s.f.tenant))).as("history keeps the freeze").isTrue();

        s.send(order(gid, "#16480", lines(line(lineM, s.restM, "M", 2, 0)),
            refunds(refund(lineM, 1, "return", true), refund(lineM, 1, "return", true))));

        assertThat(s.conflict(o)).isFalse();
        assertThat(s.qty(o, lineM)).isEqualTo(2);
    }

    // ── h ─────────────────────────────────────────────────────────────────────

    @Test
    void h_frozenOrder_editShapedPayload_noAlert_quantityKept_parcelUnitsUnchanged() {
        Shop s = new Shop("h");
        String gid = gid();
        long lineM = IDS.incrementAndGet(), lineS = IDS.incrementAndGet();
        s.send(order(gid, "#80211", lines(line(lineM, s.restM, "M", 2, 2)), refunds()));
        UUID o = s.orderId(gid);
        UUID leg = shipped(s, o, "returned", "with_courier", "returning", "returned");
        jdbc.update("UPDATE shipments SET raw = '{\"type\":{\"value\":\"Return to Origin\"}}'::jsonb WHERE id = ?", leg);
        jdbc.update("UPDATE orders SET status = 'awaiting_pickup' WHERE id = ?", o);
        UntrackedParcelUnits units = new UntrackedParcelUnits(jdbc);
        long before = unitsOf(units, s, leg, s.variantM);
        assertThat(before).as("returned-to-sender parcel: one row per shipped unit").isEqualTo(2);

        ObjectNode edit = order(gid, "#80211",
            lines(line(lineM, s.restM, "M", 2, 0), line(lineS, s.restS, "S", 1, 1)),
            refunds(refund(lineM, 2, "cancel", false)));
        s.send(edit);

        assertThat(s.conflict(o)).isFalse();
        assertThat(s.qty(o, lineM)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE tenant_id = ? AND event_type = 'unreserved'",
            Integer.class, s.f.tenant)).isZero();
        assertThat(unitsOf(units, s, leg, s.variantM)).isEqualTo(before);
        assertThat(s.qty(o, lineS)).as("a brand-new line on a frozen order: stored at 0 — never in the parcel")
            .isZero();
        assertThat(jdbc.queryForObject("SELECT current_qty FROM order_items WHERE order_id = ? AND external_id = ?",
            Integer.class, o, "gid://shopify/LineItem/" + lineS)).as("raw keeps Shopify's line").isEqualTo(1);
        assertThat(unitsOf(units, s, leg, s.variantS)).as("adds no Scan returns unit rows").isZero();
        // Plain instance (no @PreAuthorize proxy), as OrderStatusListDetailParityTest builds it.
        OrderController orderController = new OrderController(jdbc, mapper, txm, new OrderNotesService(jdbc), fulfill);
        List<OrderController.OrderItem> drawer = s.as(() -> orderController.detail(o).items());
        assertThat(drawer).extracting(OrderController.OrderItem::variantTitle, OrderController.OrderItem::quantity,
                OrderController.OrderItem::addedAfterShipping)
            .containsExactlyInAnyOrder(tuple("M", 2, false), tuple("S", 0, true));
    }

    // ── i ─────────────────────────────────────────────────────────────────────

    @Test
    void i_noCurrentQuantityKey_keepsQuantityBehaviour() {
        Shop s = new Shop("i");
        String gid = gid();
        long lineM = IDS.incrementAndGet();
        s.send(order(gid, "#16490", lines(line(lineM, s.restM, "M", 2, null)), refunds()));
        UUID o = s.orderId(gid);
        s.f.forward(o);
        assertThat(s.qty(o, lineM)).isEqualTo(2);
        String p1 = s.f.piece(s.variantM, s.actor), p2 = s.f.piece(s.variantM, s.actor);
        assertThat(s.scan(o, p1).success()).isTrue();
        assertThat(s.scan(o, p2).success()).isTrue();

        s.send(order(gid, "#16490", lines(line(lineM, s.restM, "M", 1, null)), refunds()));

        assertThat(s.qty(o, lineM)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE oi.order_id = ? AND a.status = 'active'", Integer.class, o)).isEqualTo(1);
        assertThat(s.conflict(o)).isTrue();
    }

    // ── payloads (REST, prod shapes) ──────────────────────────────────────────

    private ObjectNode editM_to_S(String gid, long lineM, long lineS, Shop s) {
        return order(gid, "#16443",
            lines(line(lineM, s.restM, "M", 1, 0), line(lineS, s.restS, "S", 1, 1)),
            refunds(refund(lineM, 1, "cancel", false)));
    }

    private static String gid() { return "gid://shopify/Order/" + IDS.incrementAndGet(); }

    private ObjectNode order(String gid, String name, ArrayNode lines, ArrayNode refunds) {
        ObjectNode p = mapper.createObjectNode();
        p.put("id", Long.parseLong(gid.substring(gid.lastIndexOf('/') + 1)));
        p.put("admin_graphql_api_id", gid);
        p.put("name", name);
        p.put("created_at", Instant.now().minusSeconds(3600).toString());
        p.put("financial_status", "pending");
        p.put("current_total_price", "2199.00");
        p.putArray("payment_gateway_names").add("Cash on Delivery (COD)");
        p.set("line_items", lines);
        p.set("refunds", refunds);
        p.putArray("fulfillments");
        return p;
    }

    private ArrayNode lines(ObjectNode... ls) {
        ArrayNode a = mapper.createArrayNode();
        for (ObjectNode l : ls) a.add(l);
        return a;
    }

    /** current null → no current_quantity key at all (pre-2023 payloads, test fixtures). */
    private ObjectNode line(long id, long variantRest, String size, int quantity, Integer current) {
        ObjectNode l = mapper.createObjectNode();
        l.put("id", id);
        l.put("admin_graphql_api_id", "gid://shopify/LineItem/" + id);
        l.put("variant_id", variantRest);
        l.put("title", "BLACK LEATHER SET");
        l.put("variant_title", size);
        l.put("quantity", quantity);
        if (current != null) l.put("current_quantity", current);
        l.put("fulfillable_quantity", current == null ? quantity : current);
        l.put("price", "2199.00");
        l.putArray("discount_allocations");
        return l;
    }

    private ArrayNode refunds(ObjectNode... rs) {
        ArrayNode a = mapper.createArrayNode();
        for (ObjectNode r : rs) a.add(r);
        return a;
    }

    /** A refund as Shopify stores it: an edit removal is restock_type 'cancel' with no transactions. */
    private ObjectNode refund(long lineId, int qty, String restockType, boolean withTransaction) {
        ObjectNode r = mapper.createObjectNode();
        r.put("id", IDS.incrementAndGet());
        r.put("created_at", "2026-10-20T22:22:04+03:00");
        r.put("restock", true);
        ArrayNode rli = r.putArray("refund_line_items");
        rli.addObject().put("line_item_id", lineId).put("quantity", qty).put("restock_type", restockType)
            .put("subtotal", 2199.0);
        ArrayNode tx = r.putArray("transactions");
        if (withTransaction) tx.addObject().put("kind", "refund").put("status", "success").put("amount", "2199.00");
        r.putArray("order_adjustments");
        return r;
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /** A forward leg in {@code state} with history created + {@code history}. */
    private UUID shipped(Shop s, UUID orderId, String state, String... history) {
        String tn = s.f.leg(orderId, "forward", state, null);
        UUID leg = jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tn);
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) " +
            "VALUES (?, ?, 'created', now() - interval '6 days')", s.f.tenant, leg);
        int d = 5;
        for (String h : history) {
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) " +
                "VALUES (?, ?, ?::shipment_internal_state, now() - (? * interval '1 day'))", s.f.tenant, leg, h, d--);
        }
        return leg;
    }

    private long unitsOf(UntrackedParcelUnits units, Shop s, UUID leg, UUID variant) {
        List<String> itemIds = jdbc.queryForList("SELECT id::text FROM order_items WHERE variant_id = ?", String.class, variant);
        return s.as(() -> units.units(s.f.tenant, leg)).stream()
            .filter(u -> itemIds.contains((String) u.get("orderItemId")))
            .count();
    }
}
