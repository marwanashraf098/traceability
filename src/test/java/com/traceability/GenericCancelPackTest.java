package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaFulfillmentLinkService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.FulfillmentTrackingCapture;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.OrderLineFreeze;
import com.traceability.inventory.WaybillResolver;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A generic leg that Traced cancels itself — Bosta wins, or Shopify cancelled its fulfillment — must not
 * read as "this order already shipped". Cancelling writes no shipment_status_history row (the
 * BostaDeletionService pattern), so OrderLineFreeze, FulfillService's ALREADY_SHIPPED guard and the waybill
 * resolver's moved-state check still let the order be packed against its next waybill.
 *
 *   w1  generic leg → Bosta wins (fulfillment link) → the order picks and completes in the queue
 *   w2  same, waybill mode: the Bosta waybill opens the order
 *   c1  Shopify cancels the generic fulfillment → the order packs against its Bosta waybill
 *   pc  positive control: a Bosta-only order (no generic leg ever) packs exactly the same way
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericCancelPackTest {

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

    @Autowired JdbcTemplate                jdbc;
    @Autowired ObjectMapper                mapper;
    @Autowired PlatformTransactionManager  txm;
    @Autowired FulfillmentTrackingCapture  capture;
    @Autowired BostaFulfillmentLinkService fulfillmentLink;
    @Autowired FulfillService              fulfill;
    @Autowired WaybillResolver             resolver;
    @Autowired EncryptionService           encryption;
    @MockBean  BostaGateway                bostaGateway;
    @MockBean  JobScheduler                jobScheduler;

    private TransactionTemplate tx;

    @BeforeAll void setup() { tx = new TransactionTemplate(txm); }
    @BeforeEach void resetMocks() { reset(bostaGateway, jobScheduler); }
    @AfterEach  void clear() { TenantContext.clear(); }

    /** A Femine-like store: an active Bosta account and the other-couriers switch on. */
    final class Shop {
        final PackFixtures f;
        final UUID packer, variant;
        Shop(String name) {
            f = new PackFixtures(jdbc, name);
            jdbc.update("UPDATE tenants SET generic_courier_enabled = true WHERE id = ?", f.tenant);
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', ?, ?, 'active')", f.tenant, encryption.encrypt("key-" + f.tenant), "wh-" + f.tenant);
            packer = f.user("Mona", "worker");
            variant = f.variant("Dress", "DR-" + name, null);
        }
        UUID order(String number) {
            UUID id = f.orderWith(number, "new", 0, "gid://shopify/Order/" + Math.abs(UUID.randomUUID().getMostSignificantBits()));
            f.item(id, variant, 1);
            return id;
        }
        String gid(UUID order) {
            return jdbc.queryForObject("SELECT external_id FROM orders WHERE id = ?", String.class, order);
        }
    }

    // ── w: Bosta wins over a still-created generic leg ──────────────────────────────────────────

    @Test
    void w1_genericLeg_bostaWins_orderPicksAndCompletes() {
        Shop s = new Shop("w1");
        UUID o = s.order("#40211");
        UUID generic = genericThenBostaWins(s, o, "WJ-40211", "4266118553");
        assertThat(state(generic)).isEqualTo("cancelled");

        TenantContext.set(s.f.tenant);
        assertThat(OrderLineFreeze.isFrozen(jdbc, o, s.f.tenant)).as("not frozen").isFalse();
        FulfillService.ScanResult scan = fulfill.scan(o, s.f.piece(s.variant, s.packer), s.packer);
        assertThat(scan.code()).as("never ALREADY_SHIPPED").isNotEqualTo("ALREADY_SHIPPED");
        assertThat(scan.success()).isTrue();
        assertThat(fulfill.complete(o, s.packer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, o)).isEqualTo("packed");
    }

    @Test
    void w2_genericLeg_bostaWins_waybillModeOpensTheOrder() {
        Shop s = new Shop("w2");
        jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", s.f.tenant);
        UUID o = s.order("#40212");
        genericThenBostaWins(s, o, "WJ-40212", "1345710666");

        TenantContext.set(s.f.tenant);
        WaybillResolver.Resolution r = resolver.resolve("1345710666", s.packer);
        assertThat(r.code()).isEqualTo(WaybillResolver.Code.OPEN);
        assertThat(r.orderId()).isEqualTo(o);
    }

    // ── c: Shopify cancels the generic fulfillment ──────────────────────────────────────────────

    @Test
    void c1_shopifyCancelsGenericFulfillment_orderPacksAgainstItsBostaWaybill() {
        Shop s = new Shop("c1");
        UUID o = s.order("#40213");
        captureAs(s, o, payload(f(7329122353521L, "success", "Wijha", "WJ-40213")));
        UUID generic = jdbc.queryForObject("SELECT id FROM shipments WHERE order_id = ? AND provider = 'generic'", UUID.class, o);
        captureAs(s, o, payload(f(7329122353521L, "cancelled", "Wijha", "WJ-40213")));
        assertThat(state(generic)).isEqualTo("cancelled");
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state) " +
            "VALUES (?, ?, 'bosta', '8384882226', 'created')", s.f.tenant, o);

        TenantContext.set(s.f.tenant);
        assertThat(OrderLineFreeze.isFrozen(jdbc, o, s.f.tenant)).isFalse();
        FulfillService.ScanResult scan = fulfill.scan(o, s.f.piece(s.variant, s.packer), s.packer);
        assertThat(scan.code()).isNotEqualTo("ALREADY_SHIPPED");
        assertThat(scan.success()).isTrue();
        assertThat(fulfill.complete(o, s.packer)).isEqualTo(1);
    }

    // ── pc: same tenant, no generic leg ever ────────────────────────────────────────────────────

    @Test
    void pc_bostaOnlyOrder_packsTheSameWay() {
        Shop s = new Shop("pc");
        UUID o = s.order("#40214");
        bostaFulfillment(s, o, "853795364");
        assertThat(fulfillmentLink.attempt(s.f.tenant, o, "853795364", false).verdict())
            .isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);

        TenantContext.set(s.f.tenant);
        assertThat(OrderLineFreeze.isFrozen(jdbc, o, s.f.tenant)).isFalse();
        FulfillService.ScanResult scan = fulfill.scan(o, s.f.piece(s.variant, s.packer), s.packer);
        assertThat(scan.success()).isTrue();
        assertThat(fulfill.complete(o, s.packer)).isEqualTo(1);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** Wijha fulfillment → generic leg; then a Bosta fulfillment whose delivery links → Bosta wins. */
    private UUID genericThenBostaWins(Shop s, UUID o, String genericTn, String bostaTn) {
        captureAs(s, o, payload(f(7306270474458L, "success", "Wijha", genericTn)));
        UUID generic = jdbc.queryForObject("SELECT id FROM shipments WHERE order_id = ? AND provider = 'generic'", UUID.class, o);
        assertThat(state(generic)).isEqualTo("created");
        bostaFulfillment(s, o, bostaTn);
        BostaFulfillmentLinkService.Result r = fulfillmentLink.attempt(s.f.tenant, o, bostaTn, false);
        assertThat(r.verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shipments WHERE order_id = ? AND provider = 'bosta' " +
            "AND tracking_number = ? AND shipment_leg = 'forward'", Integer.class, o, bostaTn)).isEqualTo(1);
        return generic;
    }

    private void bostaFulfillment(Shop s, UUID o, String tn) {
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, " +
            "carrier_class, shopify_fulfillment_id, fulfillment_status) VALUES (?, ?, ?, 'Bosta', 'bosta', ?, 'success')",
            s.f.tenant, o, tn, "73" + tn);
        String number = jdbc.queryForObject("SELECT number FROM orders WHERE id = ?", String.class, o);
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "bosta-" + tn);
        raw.put("trackingNumber", tn);
        raw.put("businessReference", number);
        raw.put("createdAt", "2026-10-30T10:00:00.000Z");
        raw.put("updatedAt", "2026-10-30T11:00:00.000Z");
        raw.putObject("type").put("code", 10).put("value", "Send");
        raw.putObject("state").put("code", 10).put("value", "Pickup requested");
        when(bostaGateway.fetchDelivery(eq("key-" + s.f.tenant), eq(tn))).thenReturn(BostaDelivery.fromRaw(tn, raw));
    }

    /** As the orders/updated webhook does: the raw order is upserted first, then the capture runs. */
    private void captureAs(Shop s, UUID o, ObjectNode payload) {
        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", payload.toString(), o);
        TenantContext.runAs(s.f.tenant, () -> tx.execute(x -> capture.capture(s.f.store, s.gid(o), payload)));
    }

    private ObjectNode f(long id, String status, String company, String tn) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", id);
        n.put("status", status);
        n.put("created_at", "2026-10-30T10:00:00+02:00");
        n.put("tracking_company", company);
        n.put("tracking_number", tn);
        n.putArray("tracking_numbers").add(tn);
        return n;
    }

    private ObjectNode payload(ObjectNode... fulfillments) {
        ObjectNode p = mapper.createObjectNode();
        var arr = p.putArray("fulfillments");
        for (ObjectNode n : fulfillments) arr.add(n);
        return p;
    }

    private String state(UUID shipment) {
        return jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE id = ?", String.class, shipment);
    }
}
