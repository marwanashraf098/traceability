package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.courier.GenericTrackingKey;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.FulfillmentTrackingCapture;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyReturnsGateway;
import com.traceability.integrations.shopify.ShopifyReturnsStore;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ShopifyShadowOrders;
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
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Shopify-native returns S3d — a NON-Bosta courier (generic, G1–G3) fulfillment of the original's EXCHANGE line items
 * belongs to the exchange's SHADOW order ({@code GenericShipments.onFulfillments} split, through the real
 * {@link FulfillmentTrackingCapture#capture} of a switched-on tenant).
 *
 * <pre>
 *   g1  the exchange fulfillment → a generic forward leg on the shadow; the original (no forward leg of its own) gets none
 *   g2  the original's own generic leg + the exchange parcel → no generic_multiple_parcels; a real extra parcel of the
 *       original's own line still raises it (positive control)
 *   g3  Shopify cancels the exchange fulfillment while the shadow leg is 'created' → the shadow leg is cancelled
 *   g4  Shopify's shipment_status 'delivered' on the exchange fulfillment → the shadow's leg is delivered (source shopify)
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShadowGenericFulfillmentTest {

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
    }

    @MockBean JobScheduler          jobScheduler;
    @MockBean ShopifyGateway        shopifyGateway;
    @MockBean ShopifyTokenProvider  tokenProvider;
    @MockBean ShopifyReturnsGateway returnsGateway;
    @MockBean BostaGateway          bostaGateway;

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ShopifyReturnsStore returnsStore;
    @Autowired FulfillmentTrackingCapture capture;
    @Autowired ExceptionService exceptions;

    JdbcTemplate appJdbc;
    DataSourceTransactionManager appTxm;
    ShopifyShadowOrders shadows;

    static final AtomicLong IDS = new AtomicLong(85_000_000_000_000L);
    static final Instant APPROVED = Instant.parse("2026-11-04T09:30:00Z");

    @BeforeAll
    void appUser() {
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(ds);
        appTxm = new DataSourceTransactionManager(ds);
        shadows = new ShopifyShadowOrders(appJdbc, appTxm, mapper);
    }

    @BeforeEach
    void stubs() { when(tokenProvider.getValidToken(any())).thenReturn("tok"); }

    @AfterEach
    void clear() { TenantContext.clear(); }

    static String gid(String kind) { return "gid://shopify/" + kind + "/" + IDS.incrementAndGet(); }
    static long num(String gid) { return Long.parseLong(gid.substring(gid.lastIndexOf('/') + 1)); }

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        final long restV = IDS.incrementAndGet();
        final UUID v, original;
        final String originalGid, ownLineGid, exchangeLineGid = gid("LineItem");
        /** Every fulfillment Shopify has sent for the original so far (orders/updated carries them all). */
        final Map<String, ObjectNode> fulfillments = new LinkedHashMap<>();

        /** {@code ownGenericLeg}: the original left on its own delivered generic leg; else a delivered piece, no leg. */
        Shop(String tag, boolean ownGenericLeg) {
            jdbc.update("INSERT INTO tenants (id, name, generic_courier_enabled) VALUES (?, ?, true)", tenant, "S3d G " + tag);
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, connection_type, returns_access) " +
                "VALUES (?, ?, 'shopify', ?, 'connected', 'oauth', 'granted')", store, tenant,
                "s3dg-" + tag.toLowerCase() + "-" + tenant.toString().substring(0, 6) + ".myshopify.com");
            jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", location, tenant);
            UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'LINEN SHIRT', 'active') RETURNING id", UUID.class, tenant, store, gid("Product"));
            v = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) VALUES (?, ?, ?, ?, 'M') RETURNING id",
                UUID.class, tenant, product, "gid://shopify/ProductVariant/" + restV, "S3DG-" + restV);
            originalGid = gid("Order");
            ownLineGid = gid("LineItem");
            original = jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
                "VALUES (?, ?, ?, ?, 'delivered', 'cod', now() - interval '5 days', '{}'::jsonb) RETURNING id",
                UUID.class, tenant, store, originalGid, "#S3DG" + tag);
            UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) " +
                "VALUES (?, ?, ?, 1, ?) RETURNING id", UUID.class, tenant, original, v, ownLineGid);
            if (ownGenericLeg) {
                ObjectNode own = fulfillment(ownLineGid, "ARX-OWN-" + tag, null, null);
                fulfillments.put(own.get("id").asText(), own);
                UUID s = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, " +
                    "internal_state, source, shopify_fulfillment_id, carrier_name) VALUES (?, ?, 'generic', ?, ?, 'delivered', " +
                    "'shopify_fulfillment', ?, 'Aramex') RETURNING id", UUID.class, tenant, original, "ARX-OWN-" + tag,
                    GenericTrackingKey.of("ARX-OWN-" + tag), own.get("id").asText());
                jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) VALUES (?, ?, 'delivered')", tenant, s);
            } else {
                String piece = com.traceability.inventory.UlidGenerator.generate();
                jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id) " +
                    "VALUES (?, ?, ?, ?, ?, 'delivered', ?)", piece, tenant, v, "PC-" + piece,
                    String.format("G%07d", IDS.incrementAndGet() % 10_000_000), original);
                jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
                    tenant, item, piece);
            }
            // the approved, processed exchange (one line of V on exchangeLineGid), its FO released
            ObjectNode r = mapper.createObjectNode();
            r.put("id", gid("Return"));
            r.put("name", "#S3DG" + tag + "-R1");
            r.put("status", "OPEN");
            r.put("createdAt", APPROVED.minusSeconds(3600).toString());
            r.put("requestApprovedAt", APPROVED.toString());
            r.putObject("order").put("id", originalGid);
            r.putObject("returnLineItems").putArray("nodes");
            ObjectNode n = r.putObject("exchangeLineItems").putArray("nodes").addObject();
            n.put("id", gid("ExchangeLineItem"));
            n.put("variantId", "gid://shopify/ProductVariant/" + restV);
            n.put("quantity", 1);
            n.put("processedQuantity", 1);
            n.putArray("lineItems").addObject().put("id", exchangeLineGid);
            as(() -> returnsStore.upsertReturn(store, original, r));
            ObjectNode fo = mapper.createObjectNode();
            fo.put("id", gid("FulfillmentOrder"));
            fo.put("status", "OPEN");
            fo.putObject("order").put("id", originalGid);
            fo.putArray("fulfillmentHolds");
            ObjectNode fl = fo.putObject("lineItems").putArray("nodes").addObject();
            fl.put("id", gid("FulfillmentOrderLineItem"));
            fl.put("remainingQuantity", 1);
            fl.put("totalQuantity", 1);
            fl.putObject("lineItem").put("id", exchangeLineGid).putObject("variant").put("id", "gid://shopify/ProductVariant/" + restV);
            as(() -> returnsStore.upsertFulfillmentOrder(store, original, fo));
            as(() -> shadows.reconcileForOrder(original));
        }

        <T> T as(java.util.concurrent.Callable<T> body) { return TenantContext.runAs(tenant, body); }

        UUID shadow() {
            return jdbc.queryForObject("SELECT order_id FROM shopify_exchange_shadows WHERE tenant_id = ? AND state = 'live'",
                UUID.class, tenant);
        }

        /** Shopify orders/updated: the order's raw is upserted, then the capture runs on every fulfillment so far. */
        void shopify(ObjectNode f) {
            fulfillments.put(f.get("id").asText(), f);
            ObjectNode payload = mapper.createObjectNode();
            ArrayNode arr = payload.putArray("fulfillments");
            fulfillments.values().forEach(arr::add);
            jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", payload.toString(), original);
            as(() -> capture.capture(store, originalGid, payload));
            as(() -> shadows.reconcileForOrder(original));
        }

        List<Map<String, Object>> generic(UUID orderId) {
            return jdbc.queryForList("SELECT id, tracking_number, internal_state::text AS state, shopify_fulfillment_id " +
                "FROM shipments WHERE order_id = ? AND provider = 'generic' AND shipment_leg = 'forward'", orderId);
        }

        List<Map<String, Object>> alerts(String type) {
            return as(() -> exceptions.detectAllOpen().stream().filter(a -> type.equals(a.get("type"))).toList());
        }
    }

    /** A REST fulfillment (Aramex) of one unit of {@code lineGid}; a fixed id when {@code id} is given. */
    ObjectNode fulfillment(String lineGid, String tracking, String id, String shipmentStatus) {
        ObjectNode f = mapper.createObjectNode();
        f.put("id", id != null ? id : String.valueOf(IDS.incrementAndGet()));
        f.put("status", "success");
        f.put("location_id", 7_002L);
        f.put("service", "manual");
        f.put("tracking_company", "Aramex");
        f.put("tracking_number", tracking);
        f.putArray("tracking_numbers").add(tracking);
        f.put("created_at", "2026-11-06T10:00:00+02:00");
        if (shipmentStatus != null) f.put("shipment_status", shipmentStatus);
        ObjectNode li = f.putArray("line_items").addObject();
        li.put("id", num(lineGid));
        li.put("quantity", 1);
        li.put("fulfillment_service", "manual");
        return f;
    }

    @Test
    void g1_exchangeFulfillment_genericLegOnShadow_originalGetsNone() {
        Shop s = new Shop("g1", false);
        UUID shadow = s.shadow();
        assertThat(s.generic(s.original)).isEmpty();
        s.shopify(fulfillment(s.exchangeLineGid, "ARX-X-1001", null, null));

        assertThat(s.generic(shadow)).singleElement().satisfies(r -> {
            assertThat(r.get("tracking_number")).isEqualTo("ARX-X-1001");
            assertThat(r.get("state")).isEqualTo("created");
        });
        assertThat(s.generic(s.original)).as("never a leg of the original").isEmpty();
    }

    @Test
    void g2_ownLegPlusExchangeParcel_noMultipleParcels_realExtraParcelStillAlerts() {
        Shop s = new Shop("g2", true);
        UUID shadow = s.shadow();
        s.shopify(fulfillment(s.exchangeLineGid, "ARX-X-2002", null, null));
        assertThat(s.generic(shadow)).hasSize(1);
        assertThat(s.generic(s.original)).as("the original keeps its own leg only").hasSize(1);
        assertThat(s.alerts("generic_multiple_parcels")).isEmpty();

        // control: a second parcel of the original's OWN line is an extra parcel
        s.shopify(fulfillment(s.ownLineGid, "ARX-EXTRA-2003", null, null));
        assertThat(s.alerts("generic_multiple_parcels")).singleElement()
            .satisfies(a -> assertThat(a.get("order_id")).isEqualTo(s.original));
    }

    @Test
    void g3_cancelledExchangeFulfillment_cancelsShadowLeg() {
        Shop s = new Shop("g3", false);
        UUID shadow = s.shadow();
        ObjectNode f = fulfillment(s.exchangeLineGid, "ARX-X-3003", null, null);
        s.shopify(f);
        assertThat(s.generic(shadow)).singleElement().satisfies(r -> assertThat(r.get("state")).isEqualTo("created"));

        ObjectNode cancelled = f.deepCopy();
        cancelled.put("status", "cancelled");
        s.shopify(cancelled);
        assertThat(s.generic(shadow)).singleElement().satisfies(r -> assertThat(r.get("state")).isEqualTo("cancelled"));
        assertThat(s.generic(s.original)).isEmpty();
    }

    @Test
    void g4_shopifyDelivered_deliversShadowLeg() {
        Shop s = new Shop("g4", false);
        UUID shadow = s.shadow();
        ObjectNode f = fulfillment(s.exchangeLineGid, "ARX-X-4004", null, null);
        s.shopify(f);
        UUID leg = (UUID) s.generic(shadow).get(0).get("id");

        ObjectNode delivered = f.deepCopy();
        delivered.put("shipment_status", "delivered");
        s.shopify(delivered);
        assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE id = ?", String.class, leg))
            .isEqualTo("delivered");
        assertThat(jdbc.queryForList("SELECT internal_state::text || ':' || coalesce(source, '-') FROM shipment_status_history " +
            "WHERE shipment_id = ? ORDER BY occurred_at, id", String.class, leg)).contains("delivered:shopify");
        assertThat(s.generic(s.original)).isEmpty();
    }
}
