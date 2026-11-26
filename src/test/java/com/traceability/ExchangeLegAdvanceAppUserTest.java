package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyReturnsGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.WaybillResolver;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BROEK 2026-11-24 (multi catch-up apply, main 3155bbc0): 70 of 70 committed replacement legs stayed 'created' —
 * advance-at-commit ran in an after-commit callback whose REQUIRED template joined the already-committed transaction,
 * so app.current_tenant was never set and on app_user RLS its fresh-leg read found nothing (silently). Every earlier
 * test ran the app as postgres (BYPASSRLS). Here the WHOLE app runs as app_user; Bosta timelines are prod's.
 * <pre>
 *   a1 catch-up apply as app_user: 46 "Returned to business" → delivered, 22 → with_courier, 105 "On hold" after
 *      out_for_exchange → with_courier, 23 "exchanged" → with_courier — one history row each, none pickable (queue,
 *      waybill), no piece event, no Shopify call; control at "Pickup requested" stays 'created' and pickable
 *   a2 repair (legs left 'created' by the bug, prod raws): dry run writes nothing and answers WOULD_ADVANCE /
 *      WOULD_HOLD / PICKUP_REQUESTED / ALREADY_PLACED / LEFT_FOR_PERSON (pieces reserved); apply advances through the
 *      same writer, holds the unplaceable order (out of the queue), touches nothing else; a rerun changes nothing
 * </pre>
 * Revert check: advanceAtCommit back on the REQUIRED template → a1 red (every leg 'created').
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeLegAdvanceAppUserTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
        try (var c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             var st = c.createStatement()) {
            st.execute("CREATE ROLE app_user LOGIN PASSWORD 'testpw'");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "app_user");
        r.add("spring.datasource.password", () -> "testpw");
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("analytics.settlement.refresh-enabled", () -> "false");
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
        r.add("traced.catalog-backfill.enabled", () -> "false");
    }

    @MockBean JobScheduler          jobScheduler;
    @MockBean ShopifyGateway        shopifyGateway;
    @MockBean ShopifyTokenProvider  tokenProvider;
    @MockBean ShopifyReturnsGateway returnsGateway;
    @MockBean BostaGateway          bostaGateway;

    @Autowired ObjectMapper                mapper;
    @Autowired EncryptionService           encryption;
    @Autowired ExchangeMultiCatchUpService catchUp;
    @Autowired ExchangeLegRepairService    repair;
    @Autowired FulfillService              fulfill;
    @Autowired WaybillResolver             waybills;

    /** Fixtures and assertions only (postgres); the app never uses it. */
    JdbcTemplate pg;
    static final AtomicLong IDS = new AtomicLong(77_000_000_000_000L);

    @BeforeAll
    void setUp() {
        pg = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider, returnsGateway, bostaGateway);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    // ── prod timelines (BROEK, read 2026-11-24; codes, steps and dates exactly as Bosta stored them) ─────────────

    /** 1030672465 — state 46 "Returned to business". */
    static final String T46 = "[" +
        "{\"code\":10,\"date\":\"2026-11-13T10:51:23.133Z\",\"done\":true,\"value\":\"new\"}," +
        "{\"code\":21,\"date\":\"2026-11-13T15:52:00.689Z\",\"done\":true,\"value\":\"picked_up\"}," +
        "{\"code\":30,\"date\":\"2026-11-16T06:00:41.022Z\",\"done\":true,\"value\":\"in_transit\"}," +
        "{\"code\":41,\"date\":\"2026-11-14T08:28:50.131Z\",\"done\":true,\"value\":\"out_for_exchange\"}," +
        "{\"code\":46,\"date\":\"2026-11-19T12:24:03.774Z\",\"done\":true,\"value\":\"out_for_return\"}," +
        "{\"code\":46,\"date\":\"2026-11-16T12:52:29.523Z\",\"done\":true,\"value\":\"exchanged_returned\"}]";
    /** 8065248208 — state 22 "Picking up from consignee". */
    static final String T22 = "[" +
        "{\"code\":10,\"date\":\"2026-11-22T10:53:18.295Z\",\"done\":true,\"value\":\"new\"}," +
        "{\"code\":21,\"date\":\"2026-11-22T16:13:14.879Z\",\"done\":true,\"value\":\"picked_up\"}," +
        "{\"code\":30,\"date\":\"2026-11-23T01:43:15.542Z\",\"done\":true,\"value\":\"in_transit\"}," +
        "{\"code\":41,\"date\":\"2026-11-23T10:14:54.851Z\",\"done\":true,\"value\":\"out_for_exchange\"}," +
        "{\"code\":41,\"date\":null,\"done\":false,\"value\":\"out_for_return\"}," +
        "{\"code\":46,\"date\":null,\"done\":false,\"value\":\"exchanged_returned\"}]";
    /** 2613051320 — state 105 "On hold" after out_for_exchange. */
    static final String T105 = "[" +
        "{\"code\":10,\"date\":\"2026-11-08T11:13:39.808Z\",\"done\":true,\"value\":\"new\"}," +
        "{\"code\":21,\"date\":\"2026-11-08T15:43:26.023Z\",\"done\":true,\"value\":\"picked_up\"}," +
        "{\"code\":30,\"date\":\"2026-11-11T06:40:05.196Z\",\"done\":true,\"value\":\"in_transit\"}," +
        "{\"code\":41,\"date\":\"2026-11-09T08:10:15.458Z\",\"done\":true,\"value\":\"out_for_exchange\"}," +
        "{\"code\":105,\"date\":\"2026-11-22T21:45:19.668Z\",\"done\":true,\"value\":\"on_hold\"}," +
        "{\"code\":46,\"date\":null,\"done\":false,\"value\":\"exchanged_returned\"}]";
    /** 6340662943 — state 23 "Picked up from consignee", latest step "exchanged". */
    static final String T23 = "[" +
        "{\"code\":10,\"date\":\"2026-11-22T10:58:24.711Z\",\"done\":true,\"value\":\"new\"}," +
        "{\"code\":21,\"date\":\"2026-11-22T16:10:10.538Z\",\"done\":true,\"value\":\"picked_up\"}," +
        "{\"code\":30,\"date\":\"2026-11-23T23:05:51.626Z\",\"done\":true,\"value\":\"in_transit\"}," +
        "{\"code\":41,\"date\":\"2026-11-24T07:11:59.724Z\",\"done\":true,\"value\":\"out_for_exchange\"}," +
        "{\"code\":23,\"date\":\"2026-11-24T08:58:35.781Z\",\"done\":true,\"value\":\"exchanged\"}," +
        "{\"code\":46,\"date\":null,\"done\":false,\"value\":\"exchanged_returned\"}]";
    /** 4556075224 — state 10 "Pickup requested". */
    static final String T10 = "[" +
        "{\"code\":10,\"date\":\"2026-11-24T08:37:22.019Z\",\"done\":true,\"value\":\"new\"}," +
        "{\"code\":21,\"date\":null,\"done\":false,\"value\":\"picked_up\"}," +
        "{\"code\":30,\"date\":null,\"done\":false,\"value\":\"in_transit\"}," +
        "{\"code\":41,\"date\":null,\"done\":false,\"value\":\"out_for_exchange\"}," +
        "{\"code\":41,\"date\":null,\"done\":false,\"value\":\"out_for_return\"}," +
        "{\"code\":46,\"date\":null,\"done\":false,\"value\":\"exchanged_returned\"}]";
    /** Only a step Traced doesn't know past "Pickup requested" (the 105 row without its recognised steps). */
    static final String T_UNKNOWN = "[" +
        "{\"code\":10,\"date\":\"2026-11-08T11:13:39.808Z\",\"done\":true,\"value\":\"new\"}," +
        "{\"code\":105,\"date\":\"2026-11-22T21:45:19.668Z\",\"done\":true,\"value\":\"on_hold\"}," +
        "{\"code\":46,\"date\":null,\"done\":false,\"value\":\"exchanged_returned\"}]";

    // ── a1 ───────────────────────────────────────────────────────────────────

    @Test
    void a1_catchUpApplyAsAppUser_legsAdvancedAtCommit_neverPickable() {
        Shop s = new Shop("A1", "BRK-61001-EG");
        String d46 = tn(), d22 = tn(), d105 = tn(), d23 = tn(), d10 = tn();
        Map<String, Object[]> cases = new LinkedHashMap<>();
        cases.put(d46,  new Object[]{46,  "Returned to business",      T46,  "delivered"});
        cases.put(d22,  new Object[]{22,  "Picking up from consignee", T22,  "with_courier"});
        cases.put(d105, new Object[]{105, "On hold",                   T105, "with_courier"});
        cases.put(d23,  new Object[]{23,  "Picked up from consignee",  T23,  "with_courier"});
        cases.put(d10,  new Object[]{10,  "Pickup requested",          T10,  "created"});
        int r = 0;
        for (Map.Entry<String, Object[]> c : cases.entrySet()) {
            Object[] v = c.getValue();
            BostaDelivery d = s.delivery(c.getKey(), s.number + ":" + s.number + "-R" + (++r), (int) v[0], (String) v[1], (String) v[2]);
            s.unlinked(d);
            when(bostaGateway.fetchDelivery(anyString(), eq(c.getKey()))).thenReturn(d);
        }

        List<ExchangeMultiCatchUpService.Row> rows = catchUp.run(s.tenant, true);

        for (Map.Entry<String, Object[]> c : cases.entrySet()) {
            String awb = c.getKey();
            String expected = (String) c.getValue()[3];
            assertThat(rows.stream().filter(x -> awb.equals(x.trackingNumber())).findFirst().orElseThrow().verdict())
                .as("%s committed", awb).isEqualTo("AUTO_COMMITTED");
            assertThat(s.legState(awb)).as("%s advanced at commit (Bosta %s)", awb, c.getValue()[0]).isEqualTo(expected);
            assertThat(s.history(awb)).isEqualTo("created".equals(expected) ? 0 : 1);
            assertThat(s.as(() -> waybills.resolve(awb, null)).code() == WaybillResolver.Code.OPEN)
                .as("%s pickable by waybill", awb).isEqualTo("created".equals(expected));
        }
        Set<String> queue = s.queueNumbers();
        assertThat(queue).as("only the Pickup-requested replacement is pickable").contains("EXC-" + d10)
            .doesNotContain("EXC-" + d46, "EXC-" + d22, "EXC-" + d105, "EXC-" + d23);
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM piece_events WHERE tenant_id = ?", Integer.class, s.tenant)).isZero();
        verifyNoInteractions(shopifyGateway);
    }

    // ── a2 ───────────────────────────────────────────────────────────────────

    @Test
    void a2_repair_dryRunThenApply_thenNoop() {
        Shop s = new Shop("A2", "BRK-62001-EG");
        String d46 = tn(), d105 = tn(), d23 = tn(), d10 = tn(), unknown = tn(), reserved = tn(), placed = tn();
        s.stuckLeg(d46, 46, "Returned to business", T46);
        s.stuckLeg(d105, 105, "On hold", T105);
        s.stuckLeg(d23, 23, "Picked up from consignee", T23);
        s.stuckLeg(d10, 10, "Pickup requested", T10);
        s.stuckLeg(unknown, 105, "On hold", T_UNKNOWN);
        UUID resOrder = s.stuckLeg(reserved, 46, "Returned to business", T46);
        s.reservePiece(resOrder);
        UUID placedLeg = s.legId(s.stuckLeg(placed, 46, "Returned to business", T46));
        pg.update("UPDATE shipments SET internal_state = 'delivered' WHERE id = ?", placedLeg);
        pg.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, provider_state) VALUES (?, ?, 'delivered', 46)",
            s.tenant, placedLeg);
        List<String> all = List.of(d46, d105, d23, d10, unknown, reserved, placed);
        String before = s.snapshot();

        Map<String, ExchangeLegRepairService.Row> dry = byTn(repair.run(s.tenant, false, all));
        assertThat(dry.get(d46).verdict()).isEqualTo("WOULD_ADVANCE");
        assertThat(dry.get(d46).state()).isEqualTo("delivered");
        assertThat(dry.get(d105).verdict()).isEqualTo("WOULD_ADVANCE");
        assertThat(dry.get(d105).state()).isEqualTo("with_courier");
        assertThat(dry.get(d23).state()).isEqualTo("with_courier");
        assertThat(dry.get(d10).verdict()).isEqualTo("PICKUP_REQUESTED");
        assertThat(dry.get(unknown).verdict()).isEqualTo("WOULD_HOLD");
        assertThat(dry.get(reserved).verdict()).isEqualTo("LEFT_FOR_PERSON");
        assertThat(dry.get(placed).verdict()).isEqualTo("ALREADY_PLACED");
        assertThat(s.snapshot()).as("dry run writes nothing").isEqualTo(before);
        assertThat(repair.candidates(s.tenant)).as("tenant-wide list = the 'created' legs not on hold")
            .containsExactlyInAnyOrder(d46, d105, d23, d10, unknown, reserved);

        Map<String, ExchangeLegRepairService.Row> applied = byTn(repair.run(s.tenant, true, all));
        assertThat(applied.get(d46).verdict()).isEqualTo("ADVANCED");
        assertThat(s.legState(d46)).isEqualTo("delivered");
        assertThat(s.legState(d105)).isEqualTo("with_courier");
        assertThat(s.legState(d23)).isEqualTo("with_courier");
        assertThat(s.history(d46)).isEqualTo(1);
        assertThat(s.legState(d10)).isEqualTo("created");
        assertThat(applied.get(unknown).verdict()).isEqualTo("HELD");
        assertThat(s.legState(unknown)).as("never guessed").isEqualTo("created");
        assertThat(pg.queryForMap("SELECT on_hold, hold_reason FROM orders WHERE external_id = ?", "internal:exchange:" + unknown))
            .containsEntry("on_hold", true).containsEntry("hold_reason", BostaWebhookJob.REPAIR_HOLD_REASON);
        assertThat(s.legState(reserved)).as("pieces reserved — left for a person").isEqualTo("created");
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND on_hold", Integer.class, s.tenant)).isEqualTo(1);
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM piece_events WHERE tenant_id = ?", Integer.class, s.tenant))
            .as("no ledger write").isEqualTo(1);
        assertThat(pg.queryForObject("SELECT status::text FROM pieces WHERE tenant_id = ?", String.class, s.tenant)).isEqualTo("reserved");
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?", Integer.class, s.tenant)).isZero();
        verifyNoInteractions(shopifyGateway, bostaGateway);

        Set<String> queue = s.queueNumbers();
        assertThat(queue).contains("EXC-" + d10, "EXC-" + reserved)
            .doesNotContain("EXC-" + d46, "EXC-" + d105, "EXC-" + d23, "EXC-" + unknown, "EXC-" + placed);
        assertThat(s.as(() -> waybills.resolve(unknown, null)).code()).isNotEqualTo(WaybillResolver.Code.OPEN);

        String after = s.snapshot();
        Map<String, ExchangeLegRepairService.Row> again = byTn(repair.run(s.tenant, true, all));
        assertThat(again.get(d46).verdict()).isEqualTo("ALREADY_PLACED");
        assertThat(again.get(unknown).verdict()).isEqualTo("HELD");
        assertThat(s.snapshot()).as("rerun changes nothing").isEqualTo(after);
    }

    static Map<String, ExchangeLegRepairService.Row> byTn(List<ExchangeLegRepairService.Row> rows) {
        Map<String, ExchangeLegRepairService.Row> m = new HashMap<>();
        rows.forEach(r -> m.put(r.trackingNumber(), r));
        return m;
    }

    static String tn() { return String.valueOf(5_000_000_000L + IDS.incrementAndGet() % 4_000_000_000L); }
    static String gid(String kind) { return "gid://shopify/" + kind + "/" + IDS.incrementAndGet(); }

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        final String number;
        final UUID sweat, crew;
        final String outDesc;

        Shop(String tag, String number) {
            this.number = number;
            pg.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Leg " + tag);
            pg.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, connection_type) " +
                "VALUES (?, ?, 'shopify', ?, 'connected', 'oauth')", store, tenant,
                "leg-" + tag.toLowerCase() + "-" + tenant.toString().substring(0, 6) + ".myshopify.com");
            pg.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", location, tenant);
            pg.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', ?, ?, 'active')", tenant, encryption.encrypt("key-" + tenant), "wh-" + tenant);
            String sk1 = "BRK-" + tag + "-BLK-L", sk2 = "BRK-" + tag + "-BEG-M";
            sweat = variant("Baggy Sweatpants", "Black / L", sk1);
            crew = variant("Never Quit Crewneck", "Beige / M", sk2);
            outDesc = "x1 Baggy Sweatpants – Black - L - SKU: " + sk1 + " // x1 Never Quit Crewneck - Beige - M - SKU: " + sk2;
            UUID original = pg.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
                "VALUES (?, ?, ?, ?, 'delivered', 'cod', now() - interval '5 days', '{}'::jsonb) RETURNING id",
                UUID.class, tenant, store, gid("Order"), number);
            for (UUID v : List.of(sweat, crew)) {
                pg.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) VALUES (?, ?, ?, 1, ?)",
                    tenant, original, v, gid("LineItem"));
            }
        }

        UUID variant(String productTitle, String title, String sku) {
            UUID p = pg.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, ?, 'active') RETURNING id", UUID.class, tenant, store, gid("Product"), productTitle);
            return pg.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class, tenant, p, gid("ProductVariant"), sku, title);
        }

        /** A two-in / two-out type 30 at Bosta {@code code}, its whole stored timeline {@code timeline}. */
        BostaDelivery delivery(String tracking, String reference, int code, String value, String timeline) {
            ObjectNode raw = mapper.createObjectNode();
            raw.put("_id", "d-" + tracking);
            raw.put("trackingNumber", tracking);
            raw.putObject("type").put("code", 30).put("value", "Exchange");
            raw.putObject("state").put("code", code).put("value", value);
            raw.put("businessReference", reference);
            raw.put("cod", 0);
            raw.putObject("receiver").put("firstName", "Mona").put("lastName", "Adel").put("phone", "01012345678");
            raw.putObject("specs").putObject("packageDetails").put("itemsCount", 2).put("description", outDesc);
            raw.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", 2).put("description", "x2 back");
            raw.put("createdAt", "2026-11-12T10:00:00.000Z");
            raw.put("updatedAt", "2026-11-24T09:00:00.000Z");
            try {
                raw.set("timeline", mapper.readTree(timeline));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return BostaDelivery.fromRaw(tracking, raw);
        }

        void unlinked(BostaDelivery d) {
            pg.update("INSERT INTO unlinked_bosta_deliveries (tenant_id, tracking_number, business_reference, bosta_state_code, " +
                "bosta_order_type, raw, match_reason) VALUES (?, ?, ?, ?, 'EXCHANGE', ?::jsonb, 'EXCHANGE_MULTI_ITEM')",
                tenant, d.trackingNumber(), d.businessReference(), d.stateCode(), d.raw().toString());
        }

        /** What the bug left in prod: a committed exchange, its replacement order 'new', a forward leg 'created', no history. */
        UUID stuckLeg(String awb, int code, String value, String timeline) {
            String raw = delivery(awb, number + ":" + number + "-R" + IDS.incrementAndGet() % 9, code, value, timeline).raw().toString();
            UUID order = pg.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, placed_at, raw) " +
                "VALUES (?, ?, ?, ?, now(), ?::jsonb) RETURNING id", UUID.class, tenant, store, "internal:exchange:" + awb, "EXC-" + awb, raw);
            pg.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) VALUES (?, ?, ?, 1, ?)",
                tenant, order, sweat, "internal:exchange-line:" + IDS.incrementAndGet());
            pg.update("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_order_id, raw, match_method, auto_matched) " +
                "VALUES (?, ?, 'matched', ?, ?::jsonb, 'reference', true)", tenant, awb, order, raw);
            pg.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
                "VALUES (?, ?, 'bosta', ?, 'created', 'forward', ?::jsonb)", tenant, order, awb, raw);
            return order;
        }

        UUID legId(UUID order) {
            return pg.queryForObject("SELECT id FROM shipments WHERE order_id = ?", UUID.class, order);
        }

        /** A piece of the replacement already reserved (picked) — the repair must not move it. */
        void reservePiece(UUID order) {
            String piece = pg.queryForObject("INSERT INTO pieces (id, tenant_id, variant_id, barcode, status, current_location_id, current_order_id) " +
                "VALUES ('P' || ?, ?, ?, ?, 'reserved', ?, ?) RETURNING id", String.class, IDS.incrementAndGet(), tenant, sweat,
                "TAG" + IDS.incrementAndGet(), location, order);
            pg.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status) " +
                "VALUES (?, ?, 'reserve', 'available', 'reserved')", tenant, piece);
            UUID item = pg.queryForObject("SELECT id FROM order_items WHERE order_id = ?", UUID.class, order);
            pg.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'active')", tenant, item, piece);
        }

        String legState(String awb) {
            return pg.queryForObject("SELECT internal_state::text FROM shipments WHERE tenant_id = ? AND tracking_number = ?",
                String.class, tenant, awb);
        }

        int history(String awb) {
            return pg.queryForObject("SELECT COUNT(*) FROM shipment_status_history h JOIN shipments s ON s.id = h.shipment_id " +
                "WHERE s.tenant_id = ? AND s.tracking_number = ?", Integer.class, tenant, awb);
        }

        Set<String> queueNumbers() {
            Set<String> out = new HashSet<>();
            as(() -> fulfill.getQueue()).forEach(r -> out.add(String.valueOf(r.get("number"))));
            return out;
        }

        /** Every row the repair could touch, as text — compared before / after. */
        String snapshot() {
            return pg.queryForList("SELECT s.tracking_number, s.internal_state, s.provider_state, o.status, o.on_hold, o.hold_reason, " +
                "(SELECT COUNT(*) FROM shipment_status_history h WHERE h.shipment_id = s.id) AS h, " +
                "(SELECT COUNT(*) FROM piece_events pe WHERE pe.tenant_id = s.tenant_id) AS pe, " +
                "(SELECT COUNT(*) FROM audit_log a WHERE a.tenant_id = s.tenant_id) AS au " +
                "FROM shipments s JOIN orders o ON o.id = s.order_id WHERE s.tenant_id = ? ORDER BY s.tracking_number", tenant).toString();
        }

        <T> T as(java.util.concurrent.Callable<T> body) { return TenantContext.runAs(tenant, body); }
    }
}
