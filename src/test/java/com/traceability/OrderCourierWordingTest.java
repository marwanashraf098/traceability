package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.courier.GenericTrackingKey;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.inventory.ExceptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-09 — the drawer's courier wording (Bug A "{{carrier}}", Bug B "Bosta" on non-Bosta orders):
 *   c*  GET /orders/{id} carries {@code courier} for a non-Bosta order: the NEWEST forward shipment's provider +
 *       carrier name; with no forward shipment on a store without Bosta, kind null. A Bosta order (its newest
 *       forward shipment is Bosta, or none on a Bosta store) has no block — its payload is unchanged
 *   t*  the timeline names a non-Bosta waybill "waybill_linked" / "return_waybill_linked"; a Bosta one keeps
 *       "awb_linked" / "return_awb_linked"
 *   u*  return_leg_unscanned reads "per Bosta" only for a Bosta leg — any other courier reads "per the courier"
 *       (EN + AR); the provider used for the wording never leaves the detector
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderCourierWordingTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired JwtService       jwt;
    @Autowired ObjectMapper     mapper;
    @Autowired ExceptionService exceptions;
    @MockBean  BostaGateway     bostaGateway;
    @MockBean  BostaV2Client    bostaV2;
    @MockBean  ShopifyGateway   shopifyGateway;
    @MockBean  JobScheduler     jobScheduler;

    @AfterEach void clear() { TenantContext.clear(); }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    final class Shop {
        final UUID tenant, store, owner, variant;
        int seq = 0;
        Shop(String name, boolean bostaAccount) {
            tenant = UUID.randomUUID(); store = UUID.randomUUID();
            String slug = "cw-" + name.toLowerCase() + "-" + tenant.toString().substring(0, 6);
            jdbc.update("INSERT INTO tenants (id, name, generic_courier_enabled) VALUES (?, ?, true)", tenant, "CW " + name);
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
                store, tenant, slug + ".myshopify.com");
            jdbc.update("INSERT INTO locations (tenant_id, name, is_fulfillment) VALUES (?, 'Main', true)", tenant);
            if (bostaAccount) {
                jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (?, 'bosta', 'x', ?, 'active')", tenant, "wh-" + tenant);
            }
            owner = jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, 'Owner', ?, 'x', 'owner'::user_role, true) RETURNING id", UUID.class, tenant,
                "owner-" + UUID.randomUUID() + "@test.com");
            UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'Linen Dress', 'active') RETURNING id", UUID.class, tenant, store, "gid://shopify/Product/" + tenant);
            variant = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) " +
                "VALUES (?, ?, ?, 'LD-M', 'M') RETURNING id", UUID.class, tenant, product, "gid://shopify/ProductVariant/" + tenant);
        }
        UUID order() {
            int k = ++seq;
            UUID id = jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, cod_amount, payment_method, placed_at) " +
                "VALUES (?, ?, ?, ?, 'confirmed'::order_status, 'Mona Adel', 900, 'cod', now() - interval '30 days') RETURNING id",
                UUID.class, tenant, store, "gid://shopify/Order/" + tenant.toString().substring(0, 8) + k, "#" + (7000 + k));
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)", tenant, id, variant);
            return id;
        }
        /** A generic leg; {@code ageDays} back-dates created_at (and returned_at for a returned leg). */
        UUID generic(UUID order, String leg, String tn, String carrier, String state, int ageDays) {
            return jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, " +
                "internal_state, shipment_leg, source, carrier_name, created_at, returned_at) VALUES (?, ?, 'generic', ?, ?, " +
                "?::shipment_internal_state, ?, ?, ?, now() - (interval '1 day' * ?), " +
                "CASE WHEN ? = 'returned' THEN now() - (interval '1 day' * ?) END) RETURNING id", UUID.class,
                tenant, order, tn, GenericTrackingKey.of(tn), state, leg,
                "forward".equals(leg) ? "shopify_fulfillment" : "portal_request", carrier, ageDays, state, ageDays);
        }
        UUID bosta(UUID order, String leg, String tn, String state, int ageDays) {
            return jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, " +
                "shipment_leg, created_at, returned_at) VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, ?, " +
                "now() - (interval '1 day' * ?), CASE WHEN ? = 'returned' THEN now() - (interval '1 day' * ?) END) RETURNING id",
                UUID.class, tenant, order, tn, state, leg, ageDays, state, ageDays);
        }
        JsonNode get(String path) throws Exception {
            HttpHeaders h = new HttpHeaders();
            h.setBearerAuth(jwt.issueAccessToken(owner, tenant, "owner"));
            ResponseEntity<String> r = rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(h), String.class);
            assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
            return mapper.readTree(r.getBody());
        }
        JsonNode courier(UUID order) throws Exception { return get("/api/v1/orders/" + order).path("courier"); }
        List<String> timelineKeys(UUID order) throws Exception {
            List<String> keys = new ArrayList<>();
            for (JsonNode n : get("/api/v1/orders/" + order + "/timeline")) keys.add(n.path("eventKey").asText());
            return keys;
        }
        List<Map<String, Object>> unscanned() {
            return TenantContext.runAs(tenant, () -> exceptions.detectAllOpen()).stream()
                .filter(e -> "return_leg_unscanned".equals(e.get("type"))).toList();
        }
    }

    // ── c: the order's courier ──────────────────────────────────────────────────────────────────

    @Test
    void c1_courier_isTheNewestForwardShipment_elseTheStoresBostaAccount() throws Exception {
        Shop femine = new Shop("Femine", true);
        UUID generic = femine.order();
        femine.generic(generic, "forward", "34127840151", "Aramex", "with_courier", 1);
        UUID bosta = femine.order();
        femine.bosta(bosta, "forward", "5100000001", "with_courier", 1);
        UUID none = femine.order();
        UUID unnamed = femine.order();
        femine.generic(unnamed, "forward", "MYL1", null, "with_courier", 1);

        assertThat(femine.courier(generic).path("kind").asText()).isEqualTo("generic");
        assertThat(femine.courier(generic).path("name").asText()).isEqualTo("Aramex");
        assertThat(femine.courier(bosta).isMissingNode()).as("a Bosta order has no courier block").isTrue();
        assertThat(femine.courier(none).isMissingNode()).as("no shipment, Bosta store → no block").isTrue();
        assertThat(femine.courier(unnamed).path("name").isNull()).as("blank carrier name → null").isTrue();

        Shop marwan = new Shop("Marwan", false);
        UUID noCourier = marwan.order();
        assertThat(marwan.courier(noCourier).has("kind")).isTrue();
        assertThat(marwan.courier(noCourier).path("kind").isNull()).as("no shipment, no Bosta account").isTrue();
    }

    @Test
    void c2_newestForwardWins_whicheverCourier() throws Exception {
        Shop s = new Shop("Switch", true);
        UUID toGeneric = s.order();
        s.bosta(toGeneric, "forward", "5100000002", "cancelled", 5);
        s.generic(toGeneric, "forward", "34127840199", "Mylerz", "with_courier", 1);
        UUID toBosta = s.order();
        s.generic(toBosta, "forward", "34127840200", "Mylerz", "cancelled", 5);
        s.bosta(toBosta, "forward", "5100000003", "with_courier", 1);

        assertThat(s.courier(toGeneric).path("kind").asText()).isEqualTo("generic");
        assertThat(s.courier(toGeneric).path("name").asText()).isEqualTo("Mylerz");
        assertThat(s.courier(toBosta).isMissingNode()).as("newest forward is Bosta → no block").isTrue();
    }

    // ── t: timeline ─────────────────────────────────────────────────────────────────────────────

    @Test
    void t1_timeline_genericWaybillsAreNotBosta_bostaUnchanged() throws Exception {
        Shop s = new Shop("Timeline", true);
        UUID generic = s.order();
        s.generic(generic, "forward", "34127840300", "Aramex", "delivered", 3);
        s.generic(generic, "return", "34127840301", "Aramex", "created", 1);
        UUID bosta = s.order();
        s.bosta(bosta, "forward", "5100000004", "delivered", 3);
        s.bosta(bosta, "return", "5100000005", "created", 1);

        assertThat(s.timelineKeys(generic)).contains("waybill_linked", "return_waybill_linked")
            .doesNotContain("awb_linked", "return_awb_linked");
        assertThat(s.timelineKeys(bosta)).contains("awb_linked", "return_awb_linked")
            .doesNotContain("waybill_linked", "return_waybill_linked");
    }

    // ── u: return_leg_unscanned ─────────────────────────────────────────────────────────────────

    @Test
    void u1_returnLegUnscanned_perBostaOnlyForABostaLeg() {
        Shop s = new Shop("Unscanned", true);
        UUID gOrder = s.order();
        s.generic(gOrder, "return", "34127840400", "Aramex", "returned", 20);
        UUID bOrder = s.order();
        s.bosta(bOrder, "return", "5100000006", "returned", 20);

        Map<String, Map<String, Object>> byTracking = new HashMap<>();
        for (Map<String, Object> e : s.unscanned()) byTracking.put((String) e.get("tracking_number"), e);
        assertThat(byTracking).containsOnlyKeys("34127840400", "5100000006");

        Map<String, Object> g = byTracking.get("34127840400");
        assertThat((String) g.get("descriptionEn")).contains("is back per the courier").doesNotContain("Bosta");
        assertThat((String) g.get("descriptionAr")).contains("عاد وفق شركة الشحن").doesNotContain("بوسطة");

        Map<String, Object> b = byTracking.get("5100000006");
        assertThat((String) b.get("descriptionEn")).contains("is back per Bosta");
        assertThat((String) b.get("descriptionAr")).contains("عاد وفق بوسطة");

        assertThat(g).as("the wording's provider never leaves the detector").doesNotContainKey("leg_provider");
        assertThat(b).doesNotContainKey("leg_provider");
    }
}
