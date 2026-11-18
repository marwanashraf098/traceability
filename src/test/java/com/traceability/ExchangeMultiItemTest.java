package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ExchangeService;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.PickupSessionService;
import com.traceability.inventory.ReturnService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Exchange reference S3a (2026-11-18) — multi-item dashboard exchanges through the real services.
 *
 * <pre>
 *   m1  two outbound rows → ONE internal:exchange: order with two lines (each row's quantity); outbound_variant_id
 *       NULL; every row (Arabic hints kept, one inbound row still unknown) in exchange_items, the 'out' rows naming
 *       their order line; needs_mapping → mapped (matched when an original is linked)
 *   m2  pack needs both pieces: one scanned → not complete, complete() refused; both → complete
 *   m3  the 4th named decrement stays per piece: both pieces leave at the pickup close → exactly two −1, one per
 *       piece (claim key = piece id)
 *   m4  one 'out' + one 'in' row → the single-item commit: one line, outbound_variant_id / inbound_variant_id filled
 *       exactly like map(); a missing outbound variant / a second mapping are refused
 *   m5  overrideOutboundVariant on a two-line order: no line named → 400; line B named → only line B (and its
 *       exchange_items row) changes; a single-line exchange still changes without naming the line
 *   m6  closure: a multi-item exchange stays 'matched' after its first old item is graded, and is return_received
 *       once every inbound unit is; a single-item exchange still closes on its one piece
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeMultiItemTest {

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

    static final String OUT_AR = "عدد-2 وايد ليج بيج وكحلي مقاس -1";
    static final String IN_AR  = "عدد-2 وايد ليج اسود مقاس -2";
    static final AtomicLong IDS = new AtomicLong(7_100_000_000L);

    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean BostaGateway         bostaGateway;

    @Autowired JdbcTemplate         jdbc;
    @Autowired ObjectMapper         mapper;
    @Autowired EncryptionService    encryption;
    @Autowired ExchangeService      exchanges;
    @Autowired FulfillService       fulfill;
    @Autowired PickupSessionService pickups;
    @Autowired ReturnService        returns;

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-test");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
            .thenAnswer(i -> "gid://shopify/InventoryItem/" + i.getArgument(2, String.class).replaceAll("\\D", ""));
    }

    @AfterEach void clear() { TenantContext.clear(); }

    // ── m1 ───────────────────────────────────────────────────────────────────

    @Test
    void m1_twoOutboundRows_oneOrderTwoLines_everyRowRecorded() {
        Shop s = new Shop("m1");
        UUID original = s.order("#7001", s.beige);
        String awb = s.exchange(original);
        UUID ex = s.exchangeId(awb);

        Map<String, Object> r = s.as(() -> exchanges.mapItems(ex,
            List.of(row(s.beige, 1, "وايد ليج بيج مقاس -1"), row(s.navy, 2, "وايد ليج كحلي مقاس -1")),
            List.of(row(s.black, 1, IN_AR), row(null, 1, "قطعة تانية مش معروفة")),
            s.owner));

        UUID order = UUID.fromString((String) r.get("orderId"));
        assertThat(jdbc.queryForObject("SELECT external_id FROM orders WHERE id = ?", String.class, order))
            .isEqualTo("internal:exchange:" + awb);
        List<Map<String, Object>> lines = jdbc.queryForList(
            "SELECT id, variant_id, quantity FROM order_items WHERE order_id = ? ORDER BY quantity", order);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("variant_id")).isEqualTo(s.beige);
        assertThat(lines.get(1).get("variant_id")).isEqualTo(s.navy);
        assertThat(lines.get(1).get("quantity")).isEqualTo(2);

        Map<String, Object> row = exchange(awb);
        assertThat(row.get("status")).as("original linked → matched").isEqualTo("matched");
        assertThat(row.get("outbound_order_id")).isEqualTo(order);
        assertThat(row.get("outbound_variant_id")).as("multi-item: no single outbound variant").isNull();
        assertThat(row.get("inbound_variant_id")).isNull();

        List<Map<String, Object>> items = jdbc.queryForList(
            "SELECT direction, variant_id, quantity, description_hint, order_item_id, mapped_by FROM exchange_items " +
            "WHERE exchange_id = ? ORDER BY direction DESC, quantity, description_hint", ex);
        assertThat(items).hasSize(4);
        assertThat(items.stream().filter(i -> "out".equals(i.get("direction"))).map(i -> i.get("order_item_id")))
            .containsExactlyInAnyOrder(lines.get(0).get("id"), lines.get(1).get("id"));
        assertThat(items.stream().map(i -> i.get("description_hint")))
            .contains("وايد ليج بيج مقاس -1", IN_AR, "قطعة تانية مش معروفة");
        assertThat(items.stream().filter(i -> "in".equals(i.get("direction")) && i.get("variant_id") == null)).hasSize(1);
        assertThat(items.stream().map(i -> i.get("mapped_by")).distinct()).containsExactly(s.owner);

        // An unmatched exchange stays 'mapped'.
        String awb2 = s.exchange(null);
        s.as(() -> exchanges.mapItems(s.exchangeId(awb2), List.of(row(s.beige, 1, OUT_AR), row(s.navy, 1, null)),
            List.of(), s.owner));
        assertThat(exchange(awb2).get("status")).isEqualTo("mapped");
    }

    // ── m2 + m3 ──────────────────────────────────────────────────────────────

    @Test
    void m2_m3_packNeedsBothPieces_thenTwoDecrements() throws Exception {
        Shop s = new Shop("m2");
        String awb = s.exchange(null);
        Map<String, Object> r = s.as(() -> exchanges.mapItems(s.exchangeId(awb),
            List.of(row(s.beige, 1, null), row(s.navy, 1, null)), List.of(), s.owner));
        UUID order = UUID.fromString((String) r.get("orderId"));
        String beige = s.piece(s.beige), navy = s.piece(s.navy);

        FulfillService.ScanResult first = s.as(() -> fulfill.scan(order, "PC-" + beige, s.owner));
        assertThat(first.success()).as(first.code() + " " + first.message()).isTrue();
        assertThat(first.allComplete()).isFalse();
        assertThatThrownBy(() -> s.as(() -> fulfill.complete(order, s.owner)))
            .isInstanceOf(ResponseStatusException.class);

        FulfillService.ScanResult second = s.as(() -> fulfill.scan(order, "PC-" + navy, s.owner));
        assertThat(second.allComplete()).isTrue();
        s.as(() -> fulfill.complete(order, s.owner));
        assertThat(status(beige)).isEqualTo("packed");
        assertThat(status(navy)).isEqualTo("packed");

        UUID pickup = s.as(() -> pickups.openSession(s.tenant, s.owner, LocalDate.now(), null, null));
        assertThat(s.as(() -> pickups.scan(s.tenant, pickup, s.owner, awb)).outcome())
            .isEqualTo(PickupSessionService.ScanOutcome.ACCEPTED);
        s.as(() -> { pickups.closeSession(s.tenant, pickup, s.owner); return null; });
        assertThat(status(beige)).isEqualTo("with_courier");
        assertThat(status(navy)).isEqualTo("with_courier");

        for (String piece : List.of(beige, navy)) awaitApplied(piece);
        Thread.sleep(300);
        assertThat(jdbc.queryForList("SELECT trigger_id FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'exchange_dispatch' AND delta = -1 AND status = 'applied'",
            String.class, s.tenant)).containsExactlyInAnyOrder(beige, navy);
        verify(shopifyGateway, times(2)).pushExchangeDispatch(eq(s.shop), anyString(), anyString(), eq(s.locationGid),
            eq(-1), anyString(), anyString());
        verify(shopifyGateway).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(), eq(-1),
            eq("traced://piece/" + beige), anyString());
        verify(shopifyGateway).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(), eq(-1),
            eq("traced://piece/" + navy), anyString());
    }

    // ── m4 ───────────────────────────────────────────────────────────────────

    @Test
    void m4_singleRowEachWay_isTheSingleItemCommit() {
        Shop s = new Shop("m4");
        String awb = s.exchange(null);
        UUID ex = s.exchangeId(awb);
        Map<String, Object> r = s.as(() -> exchanges.mapItems(ex,
            List.of(row(s.beige, 1, OUT_AR)), List.of(row(s.black, 1, IN_AR)), s.owner));
        UUID order = UUID.fromString((String) r.get("orderId"));
        assertThat(jdbc.queryForList("SELECT variant_id FROM order_items WHERE order_id = ?", UUID.class, order))
            .containsExactly(s.beige);
        assertThat(exchange(awb).get("outbound_variant_id")).isEqualTo(s.beige);
        assertThat(exchange(awb).get("inbound_variant_id")).isEqualTo(s.black);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_items WHERE exchange_id = ?", Integer.class, ex))
            .isEqualTo(2);

        assertThatThrownBy(() -> s.as(() -> exchanges.mapItems(ex, List.of(row(s.beige, 1, null)), List.of(), s.owner)))
            .as("already mapped").isInstanceOf(ResponseStatusException.class);
        String other = s.exchange(null);
        assertThatThrownBy(() -> s.as(() -> exchanges.mapItems(s.exchangeId(other),
            List.of(row(s.beige, 1, null), row(null, 1, "?")), List.of(), s.owner)))
            .as("every outbound row needs a variant").isInstanceOf(ResponseStatusException.class);
        assertThat(exchange(other).get("status")).isEqualTo("needs_mapping");
        assertThatThrownBy(() -> s.as(() -> exchanges.mapItems(s.exchangeId(other),
            List.of(row(s.beige, 0, null)), List.of(), s.owner))).isInstanceOf(ResponseStatusException.class);
    }

    // ── m5 ───────────────────────────────────────────────────────────────────

    @Test
    void m5_overrideOnTwoLines_changesOnlyThatLine() {
        Shop s = new Shop("m5");
        String awb = s.exchange(null);
        UUID ex = s.exchangeId(awb);
        Map<String, Object> r = s.as(() -> exchanges.mapItems(ex,
            List.of(row(s.beige, 1, null), row(s.navy, 1, null)), List.of(), s.owner));
        UUID order = UUID.fromString((String) r.get("orderId"));
        UUID lineBeige = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ? AND variant_id = ?",
            UUID.class, order, s.beige);
        UUID lineNavy = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ? AND variant_id = ?",
            UUID.class, order, s.navy);

        assertThatThrownBy(() -> s.as(() -> exchanges.overrideOutboundVariant(ex, s.black)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("more than one item");

        s.as(() -> exchanges.overrideOutboundVariant(ex, s.black, lineNavy));
        assertThat(jdbc.queryForObject("SELECT variant_id FROM order_items WHERE id = ?", UUID.class, lineNavy))
            .isEqualTo(s.black);
        assertThat(jdbc.queryForObject("SELECT variant_id FROM order_items WHERE id = ?", UUID.class, lineBeige))
            .isEqualTo(s.beige);
        assertThat(jdbc.queryForObject("SELECT variant_id FROM exchange_items WHERE order_item_id = ?", UUID.class, lineNavy))
            .isEqualTo(s.black);
        assertThat(exchange(awb).get("outbound_variant_id")).isNull();

        // Single line: unchanged behaviour, no line named.
        String single = s.exchange(null);
        UUID sx = s.exchangeId(single);
        s.as(() -> exchanges.map(sx, s.beige, s.black));
        s.as(() -> exchanges.overrideOutboundVariant(sx, s.navy));
        assertThat(exchange(single).get("outbound_variant_id")).isEqualTo(s.navy);
    }

    // ── m6 ───────────────────────────────────────────────────────────────────

    @Test
    void m6_closureWaitsForEveryInboundUnit_singleClosesAsBefore() {
        Shop s = new Shop("m6");
        UUID original = s.order("#7601", s.beige, s.navy, s.black);
        String delivBeige = s.deliveredPiece(original, s.beige);
        String delivNavy = s.deliveredPiece(original, s.navy);
        String unrelated = s.deliveredPiece(original, s.black);
        String awb = s.exchange(original);
        // In: one beige (known) + one not known yet.
        s.as(() -> exchanges.mapItems(s.exchangeId(awb), List.of(row(s.black, 2, OUT_AR)),
            List.of(row(s.beige, 1, null), row(null, 1, "قطعة تانية")), s.owner));
        assertThat(exchange(awb).get("status")).isEqualTo("matched");

        // Session 1 scanned the exchange AWB: the beige comes back in it and is graded — 1 of 2.
        UUID session1 = s.returnSession(awb);
        received(s, delivBeige, original, session1);
        s.as(() -> { returns.restock(delivBeige, s.location, s.owner); return null; });
        assertThat(exchange(awb).get("status")).as("first old item graded — still waiting for the second")
            .isEqualTo("matched");

        // An unrelated piece of the SAME original order, back on another parcel in another session, graded: it never
        // counts toward this exchange (not even for the unknown row).
        UUID other = s.returnSession("CRP-" + awb);
        received(s, unrelated, original, other);
        s.as(() -> { returns.markDamaged(unrelated, "stain", s.owner); return null; });
        assertThat(exchange(awb).get("status")).as("another leg / session never closes it").isEqualTo("matched");

        // The second old item comes back in a session that scanned the exchange AWB → received.
        UUID session2 = s.returnSession(awb);
        received(s, delivNavy, original, session2);
        s.as(() -> { returns.markDamaged(delivNavy, "torn seam", s.owner); return null; });
        assertThat(exchange(awb).get("status")).isEqualTo("return_received");

        // Single-item: closes on its one piece, as before (no session evidence needed).
        UUID original2 = s.order("#7602", s.beige);
        String piece2 = s.deliveredPiece(original2, s.beige);
        String single = s.exchange(original2);
        s.as(() -> exchanges.map(s.exchangeId(single), s.navy, s.beige));
        assertThat(exchange(single).get("status")).isEqualTo("matched");
        backForInspection(piece2);
        s.as(() -> { returns.restock(piece2, s.location, s.owner); return null; });
        assertThat(exchange(single).get("status")).isEqualTo("return_received");
    }

    /** The piece arrives in {@code session}: return_pending_inspection + its return_received event (as Scan returns writes it). */
    void received(Shop s, String piece, UUID order, UUID session) {
        backForInspection(piece);
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, from_status, to_status, metadata) " +
            "VALUES (?, ?, 'return_received', ?, 'delivered', 'return_pending_inspection', ?::jsonb)",
            s.tenant, piece, order, "{\"via\":\"return_scan\",\"session_id\":\"" + session + "\"}");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    static ExchangeService.ItemRow row(UUID variant, int qty, String hint) {
        return new ExchangeService.ItemRow(variant, qty, hint);
    }

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID();
        final UUID location = UUID.randomUUID();
        final String shop, locationGid;
        final UUID beige, navy, black;

        Shop(String name) {
            shop = "emi-" + name + "-" + tenant.toString().substring(0, 8) + ".myshopify.com";
            locationGid = "gid://shopify/Location/" + IDS.incrementAndGet();
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "EMI-" + name);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
                owner, tenant, "o-" + owner + "@emi.test");
            jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, import_status, access_token_scopes) " +
                "VALUES (?, ?, ?, 'connected', 'idle', 'read_orders,write_inventory,read_products,write_locations,read_locations')",
                store, tenant, shop);
            jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
                "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", location, tenant, locationGid);
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', ?, 'h', 'active')", tenant, encryption.encrypt("key-" + tenant));
            UUID product = UUID.randomUUID();
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, ?, 'وايد ليج', 'active')", product, tenant, store, "gid://shopify/Product/" + IDS.incrementAndGet());
            beige = variant(product, "بيج / 1");
            navy = variant(product, "كحلي / 1");
            black = variant(product, "اسود / 2");
        }

        private UUID variant(UUID product, String title) {
            UUID v = UUID.randomUUID();
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
                v, tenant, product, "gid://shopify/ProductVariant/" + IDS.incrementAndGet(), title, "WL-" + v.toString().substring(0, 6));
            return v;
        }

        UUID order(String number, UUID... variants) {
            UUID o = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
                "VALUES (?, ?, ?, ?, 'delivered'::order_status, now() - interval '5 days') RETURNING id", UUID.class,
                tenant, store, "gid://shopify/Order/" + IDS.incrementAndGet(), number);
            for (UUID v : variants) {
                jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)", tenant, o, v);
            }
            return o;
        }

        /** An available piece at the main warehouse. */
        String piece(UUID variant) {
            String id = UlidGenerator.generate();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available', ?)",
                id, tenant, variant, "PC-" + id, id, location);
            return id;
        }

        /** A piece delivered on {@code order}'s line of {@code variant} (packed allocation, as after a real delivery). */
        String deliveredPiece(UUID order, UUID variant) {
            String id = piece(variant);
            UUID line = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ? AND variant_id = ?", UUID.class, order, variant);
            jdbc.update("UPDATE pieces SET status = 'delivered', current_order_id = ? WHERE id = ?", order, id);
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')", tenant, line, id);
            return id;
        }

        /** A dashboard exchange (type 30, needs_mapping) with Bosta's Arabic descriptions; {@code original} linked or null. */
        String exchange(UUID original) {
            String tn = String.valueOf(IDS.incrementAndGet());
            ObjectNode raw = mapper.createObjectNode();
            raw.put("trackingNumber", tn);
            raw.putNull("businessReference");
            raw.putObject("type").put("code", 30).put("value", "Exchange");
            raw.putObject("state").put("code", 10).put("value", "Pickup requested");
            raw.putObject("receiver").put("fullName", "منى عادل").put("phone", "+201005557788");
            raw.putObject("dropOffAddress").put("firstLine", "12 شارع النصر");
            raw.putObject("specs").putObject("packageDetails").put("itemsCount", 2).put("description", OUT_AR);
            raw.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", 2).put("description", IN_AR);
            jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_description, inbound_description, " +
                "raw, matched_order_id, match_method, matched_at) VALUES (?, ?, 'needs_mapping', ?, ?, ?::jsonb, ?, ?, ?)",
                tenant, tn, OUT_AR, IN_AR, raw.toString(), original, original == null ? null : "manual",
                original == null ? null : java.sql.Timestamp.from(java.time.Instant.now()));
            return tn;
        }

        /** A closed return session in which {@code awb} was scanned. */
        UUID returnSession(String awb) {
            UUID id = jdbc.queryForObject("INSERT INTO return_sessions (tenant_id, status, opened_by, closed_by, closed_at) " +
                "VALUES (?, 'closed', ?, ?, now()) RETURNING id", UUID.class, tenant, owner, owner);
            jdbc.update("INSERT INTO return_session_shipments (tenant_id, session_id, awb) VALUES (?, ?, ?)", tenant, id, awb);
            return id;
        }

        UUID exchangeId(String tn) {
            return jdbc.queryForObject("SELECT id FROM exchanges WHERE tenant_id = ? AND tracking_number = ?", UUID.class, tenant, tn);
        }

        <T> T as(java.util.concurrent.Callable<T> body) { return TenantContext.runAs(tenant, body); }
    }

    void backForInspection(String piece) {
        jdbc.update("UPDATE pieces SET status = 'return_pending_inspection' WHERE id = ?", piece);
    }

    String status(String piece) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece);
    }

    Map<String, Object> exchange(String tn) {
        return jdbc.queryForMap("SELECT status, outbound_order_id, outbound_variant_id, inbound_variant_id FROM exchanges " +
            "WHERE tracking_number = ?", tn);
    }

    void awaitApplied(String piece) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE trigger_type = 'exchange_dispatch' " +
                "AND trigger_id = ? AND status <> 'pending'", Integer.class, piece);
            if (n != null && n > 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("no exchange_dispatch claim for " + piece);
    }
}
