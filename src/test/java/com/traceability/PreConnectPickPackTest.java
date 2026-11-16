package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.AwbPrintResult;
import com.traceability.integrations.bosta.BostaAwbService;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.*;
import com.traceability.portal.PreConnectParentImport;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-11-16 addendum (E1–E4) — a Storm-shape exchange (pre-connect parent #2739, post-connect type 30
 * 2472106867 "#2739:#2739-R1") in Pick &amp; Pack and printing, both pick_pack_mode values.
 *
 *   k1  not yet mapped: listed by GET /fulfill/exchanges-to-map (replacementFor #2739), NOT pickable — no queue row,
 *       no print-batch candidate, the waybill scan "A-06-2472106867" answers EXCHANGE_NOT_MAPPED (opens nothing); a picked-up trip (state 21) and an
 *       exchange of a post-connect order are never listed
 *   k2  mapped → a normal queue row (is_exchange, replacementFor #2739, 1 unit), in the gather list, a print-batch
 *       candidate, its AWB sent to Bosta's mass-awb; gone from exchanges-to-map; order_queue mode
 *   k3  waybill_scan mode: "A-06-2472106867" resolves OPEN to EXC-2472106867; scan the piece + complete → packed; the
 *       replacement leaving custody records the dispatch −1 as skipped (shopify_native_exchange), no Shopify write
 *   k4  repaired by the reprocess service (E4): lands in exchanges-to-map with no extra step, then in the queue once mapped
 *   k5  cross-tenant: tenant B never sees A's exchange to map, A's queue row, or resolves A's waybill
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreConnectPickPackTest {

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

    static final Instant CUTOFF = Instant.parse("2026-11-13T17:15:08Z");
    static final String AFTER = "Sun Nov 15 2026 22:43:11 GMT+0000 (Coordinated Universal Time)";

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EncryptionService encryption;
    @Autowired BostaWebhookJob webhookJob;
    @Autowired ExchangeService exchangeService;
    @Autowired FulfillService fulfill;
    @Autowired WaybillResolver waybills;
    @Autowired PackPrintBatchStore batches;
    @Autowired BostaAwbService awb;
    @Autowired ShopifyInventoryService shopifyInventory;
    @Autowired PreConnectParentImport parentImport;
    @Autowired com.traceability.integrations.bosta.PreConnectReprocessService reprocess;
    @MockBean BostaGateway bostaGateway;
    @MockBean JobScheduler jobScheduler;
    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyTokenProvider tokens;

    @BeforeEach
    void stubs() {
        reset(bostaGateway, shopify, tokens);
        parentImport.clearCaches();
        when(tokens.getValidToken(any())).thenReturn("shpat-test");
        when(bostaGateway.printMassAwb(anyString(), anyList(), any(), any()))
            .thenReturn(new AwbPrintResult("%PDF-1.4 test".getBytes(), null));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void k1_unmapped_listedToMap_notPickable_notPrintable_waybillNotFound() {
        Shop s = new Shop("k1");
        s.parentInShopify("#2739");
        ingest(s, exchange("2472106867", "#2739:#2739-R1", 10));

        List<Map<String, Object>> toMap = as(s, () -> fulfill.getExchangesToMap());
        assertThat(toMap).hasSize(1);
        assertThat(toMap.get(0).get("trackingNumber")).isEqualTo("2472106867");
        assertThat(((Map<?, ?>) toMap.get(0).get("replacementFor")).keySet().toString()).as("the number only").isEqualTo("[number]");
        assertThat(((Map<?, ?>) toMap.get(0).get("replacementFor")).get("number")).isEqualTo("#2739");

        assertThat(as(s, () -> fulfill.getQueue())).extracting(r -> r.get("number")).containsExactly("#3018");
        assertThat(as(s, () -> batches.candidates("all", "oldest"))).extracting(PackPrintBatchStore.Candidate::trackingNumber)
            .doesNotContain("2472106867");
        // The waybill scan says what it is (the resolver's existing EXCHANGE_NOT_MAPPED) — it opens nothing.
        WaybillResolver.Resolution unmapped = as(s, () -> waybills.resolve("A-06-2472106867", s.owner));
        assertThat(unmapped.code()).isEqualTo(WaybillResolver.Code.EXCHANGE_NOT_MAPPED);
        assertThat(unmapped.orderId()).isNull();

        // Never listed: a trip Bosta already picked up, or an exchange of an order Traced holds normally.
        s.parentInShopify("#2740");
        ingest(s, exchange("2472106868", "#2740:#2740-R1", 21));
        ingest(s, exchange("2472106869", "#3018:#3018-R1", 10));
        assertThat(as(s, () -> fulfill.getExchangesToMap())).extracting(r -> r.get("trackingNumber")).containsExactly("2472106867");
    }

    @Test
    @SuppressWarnings("unchecked")
    void k2_mapped_isANormalQueueRow_gather_batch_awb() {
        Shop s = new Shop("k2");
        s.parentInShopify("#2922");
        ingest(s, exchange("5028197718", "#2922:#2922-R1", 10));
        UUID exc = s.map("5028197718");
        s.piece(s.jeans32);

        Map<String, Object> row = as(s, () -> fulfill.getQueue()).stream()
            .filter(r -> exc.equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(row.get("number")).isEqualTo("EXC-5028197718");
        assertThat(row.get("is_exchange")).isEqualTo(true);
        assertThat(((Number) row.get("total_units")).intValue()).isEqualTo(1);
        assertThat((Map<String, Object>) row.get("replacementFor")).as("the number only — never the pre-connect id")
            .containsOnlyKeys("number").containsEntry("number", "#2922");
        assertThat(row).doesNotContainKey("exchangeFor");
        Map<String, Object> card = as(s, () -> fulfill.getOrder(exc));
        assertThat((Map<String, Object>) card.get("replacementFor")).containsOnlyKeys("number").containsEntry("number", "#2922");
        assertThat(card).doesNotContainKey("heldForRelease");

        assertThat(as(s, () -> fulfill.getExchangesToMap())).isEmpty();
        assertThat(as(s, () -> fulfill.getGatherList(null)).toString()).contains(s.jeans32.toString());

        List<PackPrintBatchStore.Candidate> cands = as(s, () -> batches.candidates("all", "oldest"));
        PackPrintBatchStore.Candidate c = cands.stream().filter(x -> "5028197718".equals(x.trackingNumber())).findFirst().orElseThrow();
        BostaAwbService.AwbDetailedResult printed = awb.printAwbDetailed(s.tenant, List.of(c.shipmentId()), null, null);
        assertThat(printed.exclusions()).isEmpty();
        verify(bostaGateway).printMassAwb(eq("key-" + s.tenant), eq(List.of("5028197718")), any(), any());
    }

    @Test
    void k3_waybillMode_scanOpens_packs_dispatchSkippedAsShopifyNative() {
        Shop s = new Shop("k3");
        jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", s.tenant);
        s.parentInShopify("#2906");
        ingest(s, exchange("3251207139", "#2906:#2906-R1", 10));
        UUID exc = s.map("3251207139");
        String piece = s.piece(s.jeans32);

        WaybillResolver.Resolution r = as(s, () -> waybills.resolve("A-06-3251207139", s.owner));
        assertThat(r.code()).isEqualTo(WaybillResolver.Code.OPEN);
        assertThat(r.orderId()).isEqualTo(exc);
        assertThat(r.orderNumber()).isEqualTo("EXC-3251207139");

        FulfillService.ScanResult scan = as(s, () -> fulfill.scan(exc, "PC-" + piece, s.owner));
        assertThat(scan.success()).as(scan.code()).isTrue();
        run(s, () -> fulfill.complete(exc, s.owner));
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece)).isEqualTo("packed");

        try {
            shopifyInventory.onExchangeReplacementDispatched(s.tenant, piece).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        Map<String, Object> claim = jdbc.queryForMap(
            "SELECT status, skip_reason FROM shopify_inventory_adjustments WHERE trigger_type = 'exchange_dispatch' AND trigger_id = ?", piece);
        assertThat(claim.get("status")).isEqualTo("skipped");
        assertThat(claim.get("skip_reason")).isEqualTo("shopify_native_exchange");
        // The only Shopify call of the whole flow is the parent import's read — no inventory write of any kind.
        verify(shopify, times(1)).findOrdersByName(anyString(), anyString(), anyString());
        verifyNoMoreInteractions(shopify);
    }

    @Test
    void k4_repairedByReprocess_landsInPickPack_noExtraStep() {
        Shop s = new Shop("k4");
        s.parentInShopify("#2945");
        ObjectNode raw = exchange("7779516755", "#2945:#2945-R1", 10);
        when(bostaGateway.fetchDelivery(eq("key-" + s.tenant), eq("7779516755"))).thenReturn(BostaDelivery.fromRaw("7779516755", raw));
        ObjectNode payload = mapper.createObjectNode().put("trackingNumber", "7779516755").put("state", 10)
            .put("type", "EXCHANGE").put("updatedAt", raw.path("updatedAt").asText());
        jdbc.update("INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at, processed_at, external_event_id, error) " +
            "VALUES ('bosta_poll_discovery'::webhook_source, ?, 'delivery_update', ?::jsonb, 'processed', now(), now(), ?, ?)",
            s.tenant, payload.toString(), BostaWebhookJob.sha256("7779516755:10:" + raw.path("updatedAt").asText()),
            "ignored_pre_connect: 7779516755");

        assertThat(reprocess.run(s.tenant, List.of("7779516755"), true))
            .extracting(com.traceability.integrations.bosta.PreConnectReprocessService.Row::verdict).containsExactly("REPROCESSED");
        assertThat(as(s, () -> fulfill.getExchangesToMap())).extracting(r -> r.get("trackingNumber")).containsExactly("7779516755");
        UUID exc = s.map("7779516755");
        assertThat(as(s, () -> fulfill.getQueue())).extracting(r -> r.get("id")).contains(exc);
    }

    @Test
    void k5_crossTenant_neverSeesTheOtherTenantsExchange() {
        Shop a = new Shop("k5a");
        Shop b = new Shop("k5b");
        a.parentInShopify("#2955");
        ingest(a, exchange("1332754325", "#2955:#2955-R1", 10));
        assertThat(as(a, () -> fulfill.getExchangesToMap())).hasSize(1);          // positive control
        assertThat(as(b, () -> fulfill.getExchangesToMap())).isEmpty();
        UUID exc = a.map("1332754325");
        assertThat(as(a, () -> fulfill.getQueue())).extracting(r -> r.get("id")).contains(exc);
        assertThat(as(b, () -> fulfill.getQueue())).extracting(r -> r.get("id")).doesNotContain(exc);
        assertThat(as(b, () -> waybills.resolve("A-06-1332754325", b.owner)).code()).isEqualTo(WaybillResolver.Code.NOT_FOUND);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID();
        final UUID warehouse = UUID.randomUUID();
        final String shop;
        final UUID jeans30 = UUID.randomUUID(), jeans32 = UUID.randomUUID();
        final String jeans30Gid = "gid://shopify/ProductVariant/" + UUID.randomUUID();
        final String jeans32Gid = "gid://shopify/ProductVariant/" + UUID.randomUUID();
        final java.util.Map<String, ShopifyGateway.Order> shopifyOrders = new java.util.HashMap<>();

        Shop(String name) {
            shop = "pp-" + name + "-" + tenant.toString().substring(0, 8) + ".myshopify.com";
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Storm-" + name);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
                owner, tenant, "owner-" + tenant + "@example.com");
            jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main Warehouse', true)",
                warehouse, tenant);
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', ?, 'h', 'active')", tenant, encryption.encrypt("key-" + tenant));
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                "VALUES (?, ?, 'shopify', ?, 'connected', ?)", store, tenant, shop, Timestamp.from(CUTOFF));
            // The store's first tracked order (#3018), already picked: it isn't the subject here.
            UUID first = UUID.randomUUID();
            jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at) " +
                "VALUES (?, ?, ?, ?, '#3018', 'new'::order_status, now())",
                first, tenant, store, "gid://shopify/Order/" + UUID.randomUUID());
            jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                "VALUES (?, ?, 'bosta', ?, 'created', 'forward')", tenant, first, "8" + (100000000 + new java.util.Random().nextInt(899999999)));
            UUID product = UUID.randomUUID();
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Jeans', 'active')",
                product, tenant, store, "gid://shopify/Product/" + UUID.randomUUID());
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, '30', ?)",
                jeans30, tenant, product, jeans30Gid, "JN-30-" + name);
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, '32', ?)",
                jeans32, tenant, product, jeans32Gid, "JN-32-" + name);
            when(shopify.findOrdersByName(eq(shop), anyString(), anyString())).thenAnswer(inv -> {
                ShopifyGateway.Order o = shopifyOrders.get("#" + inv.getArgument(2, String.class));
                return new ShopifyGateway.PortalOrderSearch(o == null ? List.of() : List.of(o), true);
            });
        }

        /** Shopify holds this pre-connect order: one line of the size-30 jeans (the item coming back). */
        void parentInShopify(String name) {
            ObjectNode raw = mapper.createObjectNode();
            raw.put("name", name);
            ArrayNode edges = raw.putObject("lineItems").putArray("edges");
            ObjectNode node = edges.addObject().putObject("node");
            node.put("id", "gid://shopify/LineItem/" + UUID.randomUUID());
            node.put("quantity", 1);
            node.put("currentQuantity", 1);
            node.putObject("variant").put("id", jeans30Gid);
            shopifyOrders.put(name, new ShopifyGateway.Order("gid://shopify/Order/" + UUID.randomUUID(), name, null, null, null,
                "PENDING", List.of("Cash on Delivery (COD)"), new BigDecimal("950.00"), List.of(),
                Instant.parse("2026-11-01T10:00:00Z"), raw));
        }

        /** A person maps the replacement (size 32 out, size 30 back) → the EXC order id. */
        UUID map(String tracking) {
            UUID ex = jdbc.queryForObject("SELECT id FROM exchanges WHERE tenant_id = ? AND tracking_number = ?", UUID.class, tenant, tracking);
            Map<String, Object> r = as(this, () -> exchangeService.map(ex, jeans32, jeans30));
            return UUID.fromString((String) r.get("orderId"));
        }

        /** An available, tagged piece at the main warehouse. */
        String piece(UUID variant) {
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                "VALUES (?, ?, ?, ?, ?, 'available', ?)", id, tenant, variant, "PC-" + id,
                "P" + (100000 + new java.util.Random().nextInt(899999)), warehouse);
            return id;
        }
    }

    ObjectNode exchange(String tracking, String reference, int state) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "bosta-" + tracking);
        raw.put("trackingNumber", tracking);
        raw.put("businessReference", reference);
        raw.put("createdAt", AFTER);
        raw.put("updatedAt", "2026-11-15T22:43:11.000Z");
        raw.putObject("type").put("code", 30).put("value", "Exchange");
        raw.putObject("state").put("code", state).put("value", state == 10 ? "Pickup requested" : "Picked up");
        raw.putObject("specs").putObject("packageDetails").put("itemsCount", 1).put("description", "Exchange item");
        raw.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", 1).put("description", "Exchange item back");
        raw.putObject("receiver").put("fullName", "Test Customer").put("phone", "01000000000");
        return raw;
    }

    void ingest(Shop s, ObjectNode raw) {
        String tracking = raw.path("trackingNumber").asText();
        when(bostaGateway.fetchDelivery(eq("key-" + s.tenant), eq(tracking))).thenReturn(BostaDelivery.fromRaw(tracking, raw));
        ObjectNode payload = mapper.createObjectNode();
        payload.put("trackingNumber", tracking);
        payload.put("state", raw.path("state").path("code").asInt());
        payload.put("updatedAt", raw.path("updatedAt").asText());
        Long id = jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, s.tenant, payload.toString());
        webhookJob.process(id, s.tenant);
    }

    <T> T as(Shop s, java.util.function.Supplier<T> body) {
        return TenantContext.runAs(s.tenant, body::get);
    }

    void run(Shop s, Runnable body) {
        TenantContext.runAs(s.tenant, body);
    }
}
