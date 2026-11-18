package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaIngestionHelper;
import com.traceability.integrations.bosta.BostaStateMapper;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyReturnsGateway;
import com.traceability.integrations.shopify.ShopifyReturnsStore;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.inventory.ShopifyShadowOrders;
import com.traceability.security.EncryptionService;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Exchange reference S1 (2026-11-17) — a Bosta Shopify-plugin reference "&lt;shop handle&gt;:#&lt;order&gt;-R1" (shape b)
 * that names a LIVE Shopify exchange shadow still goes to the shadow (S3d), unchanged by the new -R stripping.
 *
 * <pre>
 *   h1  type 30 through the real ingest pipeline → exchanges.outbound_order_id = the shadow, no internal:exchange:
 *       order, the shadow holds the forward leg; and the ORIGINAL is now found by reference (matched_order_id —
 *       new with the -R strip; the original gets no new shipment)
 *   h2  a SEND with the same reference (tryMatchDelivery step 0b) → the shadow's forward leg; the original untouched
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeReferenceShadowShapeTest {

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
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
        r.add(ShopifyShadowOrders.S3C_FLAG, () -> "true");
    }

    @MockBean JobScheduler          jobScheduler;
    @MockBean ShopifyGateway        shopifyGateway;
    @MockBean ShopifyTokenProvider  tokenProvider;
    @MockBean ShopifyReturnsGateway returnsGateway;
    @MockBean BostaGateway          bostaGateway;

    @Autowired JdbcTemplate         jdbc;
    @Autowired ObjectMapper         mapper;
    @Autowired EncryptionService    encryption;
    @Autowired ShopifyReturnsStore  returnsStore;
    @Autowired BostaWebhookJob      webhookJob;
    @Autowired BostaIngestionHelper ingestionHelper;
    @Autowired BostaStateMapper     stateMapper;
    @Autowired ShipmentLinkService  links;

    ShopifyShadowOrders shadows;

    static final AtomicLong IDS = new AtomicLong(86_000_000_000_000L);
    static final Instant APPROVED = Instant.parse("2026-11-04T09:30:00Z");
    static final String RAW_KEY = "ers-bosta-key";

    @BeforeAll
    void appUser() {
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        shadows = new ShopifyShadowOrders(new JdbcTemplate(ds), new DataSourceTransactionManager(ds), mapper);
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider, returnsGateway, bostaGateway);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    static String gid(String kind) { return "gid://shopify/" + kind + "/" + IDS.incrementAndGet(); }
    static String tn() { return String.valueOf(5_000_000_000L + IDS.incrementAndGet() % 4_000_000_000L); }

    // ── h1 / h2 ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void h1_type30_pluginShape_liveShadow_goesToShadow_originalByReference() {
        Shop s = new Shop("H1");
        s.ret();
        UUID shadow = s.shadow();
        String awb = tn();
        ingest(s, delivery(awb, 30, "Exchange", s.ref));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND external_id LIKE 'internal:exchange:%'",
            Integer.class, s.tenant)).as("no internal:exchange: order").isZero();
        Map<String, Object> ex = jdbc.queryForMap("SELECT status, outbound_order_id, matched_order_id FROM exchanges " +
            "WHERE tenant_id = ? AND tracking_number = ?", s.tenant, awb);
        assertThat(ex.get("outbound_order_id")).isEqualTo(shadow);
        assertThat(ex.get("matched_order_id")).as("the original, by reference (-R stripped)").isEqualTo(s.original);
        assertThat(ex.get("status")).isNotEqualTo("needs_mapping");
        assertThat(s.shipments(awb)).singleElement().satisfies(r -> {
            assertThat(r.get("order_id")).isEqualTo(shadow);
            assertThat(r.get("leg")).isEqualTo("forward");
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id = ? AND tracking_number = ?",
            Integer.class, s.original, awb)).isZero();
    }

    @Test
    void h2_send_pluginShape_liveShadow_linksToShadow_originalUntouched() {
        Shop s = new Shop("H2");
        s.ret();
        UUID shadow = s.shadow();
        int originalLegs = jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id = ?", Integer.class, s.original);
        String awb = tn();
        BostaDelivery d = delivery(awb, 10, "Send", s.ref);
        ShipmentLinkService.LinkResult r = s.as(() -> links.tryMatchDelivery(s.tenant, awb, d, stateMapper.map(10, "SEND")));

        assertThat(r.orderId()).isEqualTo(shadow);
        assertThat(s.shipments(awb)).singleElement().satisfies(m -> assertThat(m.get("order_id")).isEqualTo(shadow));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id = ?", Integer.class, s.original))
            .isEqualTo(originalLegs);
    }

    // ── fixture ─────────────────────────────────────────────────────────────────────────────────────────────────

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        final long restV = IDS.incrementAndGet(), restW = IDS.incrementAndGet();
        final UUID v, w, original;
        final String originalGid, number, returnName, ref;

        Shop(String tag) {
            number = "#ERS" + tag;
            returnName = number + "-R1";
            String handle = "ers-" + tag.toLowerCase() + "-" + tenant.toString().substring(0, 6);
            ref = handle + ":" + returnName;                       // the Bosta Shopify plugin: "<shop handle>:#<order>-R1"
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "ERS shadow " + tag);
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, connection_type, returns_access) " +
                "VALUES (?, ?, 'shopify', ?, 'connected', 'oauth', 'granted')", store, tenant, handle + ".myshopify.com");
            jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", location, tenant);
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', ?, ?, 'active')", tenant, encryption.encrypt(RAW_KEY), "wh-" + tenant);
            UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'LINEN SHIRT', 'active') RETURNING id", UUID.class, tenant, store, gid("Product"));
            v = variant(product, restV, "M");
            w = variant(product, restW, "L");
            originalGid = gid("Order");
            original = jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw, " +
                "    customer_name, customer_phone) VALUES (?, ?, ?, ?, 'delivered', 'cod', now() - interval '5 days', '{}'::jsonb, " +
                "    'Mona Adel', '01012345678') RETURNING id", UUID.class, tenant, store, originalGid, number);
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) VALUES (?, ?, ?, 1, ?)",
                tenant, original, w, gid("LineItem"));
            UUID leg = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                "VALUES (?, ?, 'bosta', ?, 'delivered', 'forward') RETURNING id", UUID.class, tenant, original, tn());
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) VALUES (?, ?, 'delivered')", tenant, leg);
        }

        UUID variant(UUID product, long rest, String title) {
            return jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class, tenant, product, "gid://shopify/ProductVariant/" + rest, "ERS-" + rest, title);
        }

        <T> T as(java.util.concurrent.Callable<T> body) { return TenantContext.runAs(tenant, body); }

        /** OPEN Return "#ERS<tag>-R1" with one (unprocessed) exchange line of V. */
        void ret() {
            ObjectNode r = mapper.createObjectNode();
            r.put("id", gid("Return"));
            r.put("name", returnName);
            r.put("status", "OPEN");
            r.put("createdAt", APPROVED.minusSeconds(3600).toString());
            r.put("requestApprovedAt", APPROVED.toString());
            r.putObject("order").put("id", originalGid);
            r.putObject("returnLineItems").putArray("nodes");
            ObjectNode n = r.putObject("exchangeLineItems").putArray("nodes").addObject();
            n.put("id", gid("ExchangeLineItem"));
            n.put("variantId", "gid://shopify/ProductVariant/" + restV);
            n.put("quantity", 1);
            n.put("processedQuantity", 0);
            ArrayNode li = n.putArray("lineItems");
            assertThat(li).isEmpty();
            as(() -> returnsStore.upsertReturn(store, original, r));
            as(() -> shadows.reconcileForOrder(original));
        }

        UUID shadow() {
            return jdbc.queryForObject("SELECT order_id FROM shopify_exchange_shadows WHERE tenant_id = ? AND original_order_id = ?",
                UUID.class, tenant, original);
        }

        List<Map<String, Object>> shipments(String tracking) {
            return jdbc.queryForList("SELECT order_id, shipment_leg::text AS leg FROM shipments WHERE tenant_id = ? AND tracking_number = ?",
                tenant, tracking);
        }
    }

    BostaDelivery delivery(String tracking, int typeCode, String typeValue, String reference) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "d-" + tracking);
        raw.put("trackingNumber", tracking);
        raw.putObject("type").put("code", typeCode).put("value", typeValue);
        raw.putObject("state").put("code", 10).put("value", "Created");
        raw.put("businessReference", reference);
        raw.putObject("receiver").put("firstName", "Mona").put("lastName", "Adel").put("phone", "01012345678");
        if (typeCode == 30) {
            raw.putObject("specs").putObject("packageDetails").put("itemsCount", 1).put("description", "Linen Shirt M");
            raw.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", 1).put("description", "Linen Shirt L");
        }
        raw.put("updatedAt", Instant.parse("2026-11-06T10:00:00Z").plusSeconds(IDS.incrementAndGet() % 1000).toString());
        return BostaDelivery.fromRaw(tracking, raw);
    }

    void ingest(Shop s, BostaDelivery d) {
        when(bostaGateway.fetchDelivery(anyString(), eq(d.trackingNumber()))).thenReturn(d);
        boolean enqueued = TenantContext.runAs(s.tenant,
            () -> ingestionHelper.ingestDelivery(s.tenant, RAW_KEY, d.trackingNumber(), "bosta_poll"));
        assertThat(enqueued).isTrue();
        Long eventId = jdbc.queryForObject("SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
            "ORDER BY received_at DESC, id DESC LIMIT 1", Long.class, s.tenant, d.trackingNumber());
        webhookJob.process(eventId, s.tenant);
        TenantContext.clear();
    }
}
