package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.integrations.shopify.FulfillmentTrackingCapture;
import com.traceability.inventory.*;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * D2 — the BRK-47903 link path with the WHOLE app on app_user + RLS (the way prod runs): the Shopify capture, the link
 * job, BostaWebhookJob's supersede and OrderReopen all run on the app pool under the tenant set by the link job. Fixtures
 * are written on a separate postgres connection.
 *
 *   a1  tenant A's refulfill links 2109710882, supersedes the deleted 6855433207 and reopens A's order (4 units,
 *       audit row); tenant B's identical order is untouched — and app_user under A's tenant can't see B's order at all
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeletedWaybillReopenAppUserTest {

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
        r.add("bosta.fulfillment-link.max-backoff-ms", () -> "0");
        r.add("analytics.settlement.refresh-enabled", () -> "false");
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
        r.add("traced.catalog-backfill.enabled", () -> "false");
    }

    @Autowired JdbcTemplate                appJdbc;
    @Autowired ObjectMapper                mapper;
    @Autowired PlatformTransactionManager  txm;
    @Autowired FulfillmentTrackingCapture  capture;
    @Autowired BostaFulfillmentLinkService fulfillmentLink;
    @Autowired BostaWebhookJob             webhookJob;
    @Autowired BostaDeletionService        deletion;
    @Autowired FulfillService              fulfill;
    @Autowired InventoryLedger             ledger;
    @Autowired ReturnService               returns;
    @Autowired EncryptionService           encryption;
    @MockBean  BostaGateway                bostaGateway;
    @MockBean  BostaV2Client               bostaV2;
    @MockBean  JobScheduler                jobScheduler;

    JdbcTemplate pg;
    TransactionTemplate tx;
    final Map<String, JsonNode> bosta = new ConcurrentHashMap<>();

    @BeforeAll
    void setup() {
        pg = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        tx = new TransactionTemplate(txm);
        when(bostaGateway.fetchDelivery(anyString(), anyString())).thenAnswer(inv -> {
            JsonNode raw = bosta.get((String) inv.getArgument(1));
            if (raw == null) throw new DeliveryNotFoundException("not found");
            return BostaDelivery.fromRaw(inv.getArgument(1), raw);
        });
        when(bostaV2.searchDeliveriesPage(anyString(), anyInt(), anyInt(), anyString())).thenReturn(List.of());
    }

    @AfterEach void clear() { TenantContext.clear(); }

    record Shop(PackFixtures f, UUID packer, List<UUID> variants) {}

    private Shop shop(String name) {
        PackFixtures f = new PackFixtures(pg, name);
        pg.update("UPDATE stores SET status = 'connected' WHERE id = ?", f.store);
        pg.update("UPDATE locations SET is_fulfillment = true WHERE id = ?", f.location);
        pg.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, ?, 'active')", f.tenant, encryption.encrypt("key-" + f.tenant), "wh-" + f.tenant);
        UUID packer = f.user("Mona", "worker");
        List<UUID> vs = List.of(f.variant("Gray Relaxed Fit Denim Jeans", "BROEK-Gray-RFD-6-" + name, null),
            f.variant("Dark Washed Relaxed Fit Denim Jeans", "BROEK-DARK-WASHED-RFD-006-" + name, null),
            f.variant("Raw Denim Relaxed Fit Jeans", "BROEK-Raw-Denim-RFD-6-" + name, null));
        return new Shop(f, packer, vs);
    }

    /** The BRK-47903 prod shape up to the refulfill: packed onto {@code tn}, deletion confirmed, taken apart. */
    private UUID brokenOrder(Shop s, String tn) {
        UUID o = s.f().orderWith("BRK-47903-EG", "new", 0, "gid://shopify/Order/" + Math.abs(UUID.randomUUID().getMostSignificantBits()));
        for (UUID v : s.variants()) s.f().item(o, v, 1);
        UUID sweat = s.f().variant("Smoked Basic Summer Sweatpants", "SmSSw3x-" + s.f().tenant, null);
        List<String> pieces = new ArrayList<>();
        TenantContext.set(s.f().tenant);
        for (UUID v : s.variants()) {
            String code = s.f().piece(v, s.packer());
            assertThat(fulfill.scan(o, code, s.packer()).success()).isTrue();
            pieces.add(s.f().pieceId(code));
        }
        assertThat(fulfill.complete(o, s.packer())).isEqualTo(3);
        TenantContext.clear();
        delivery(o, tn, 10);
        webhook(s, tn, 10);
        UUID leg = pg.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tn);
        pg.update("UPDATE shipments SET provider_not_found_at = LOCALTIMESTAMP, not_found_count = 2, " +
            "last_not_found_at = LOCALTIMESTAMP - INTERVAL '40 minutes' WHERE id = ?", leg);
        pg.update("UPDATE courier_accounts SET lookup_ok_at = now() WHERE tenant_id = ?", s.f().tenant);
        assertThat(TenantContext.runAs(s.f().tenant, () -> deletion.tryConfirm(s.f().tenant, leg)).name()).isEqualTo("KEPT_PIECES_OUT");
        bosta.remove(tn);
        TenantContext.set(s.f().tenant);
        for (String p : pieces) {
            ledger.transition(p, PieceStatus.AWAITING_PICKUP, PieceStatus.RETURN_PENDING_INSPECTION, "return_received",
                s.packer(), new TransitionContext(o, leg, s.f().location, o, "{\"return_kind\":\"rto\"}"));
            returns.restock(p, s.f().location, s.packer());
        }
        TenantContext.clear();
        pg.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id, raw) VALUES (?, ?, ?, 0, " +
            "'gid://shopify/LineItem/50941022404887', '{\"current_quantity\": 1}'::jsonb)", s.f().tenant, o, sweat);
        return o;
    }

    @Test
    void a1_appUser_linkPath_supersedesAndReopens_ownTenantOnly() {
        Shop a = shop("aua"), b = shop("aub");
        UUID oa = brokenOrder(a, "6855433401"), ob = brokenOrder(b, "6855433402");

        delivery(oa, "2109710981", 10);
        ObjectNode fu = mapper.createObjectNode();
        fu.put("id", 7346473500981L).put("status", "success").put("created_at", "2026-11-12T09:00:00+02:00")
          .put("updated_at", "2026-11-14T19:21:58+02:00").put("tracking_company", "Bosta").put("tracking_number", "2109710981");
        fu.putArray("tracking_numbers").add("2109710981");
        ObjectNode payload = mapper.createObjectNode();
        payload.putArray("fulfillments").add(fu);
        pg.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", payload.toString(), oa);
        String gid = pg.queryForObject("SELECT external_id FROM orders WHERE id = ?", String.class, oa);
        TenantContext.runAs(a.f().tenant, () -> tx.execute(x -> capture.capture(a.f().store, gid, payload)));

        assertThat(fulfillmentLink.attempt(a.f().tenant, oa, "2109710981", false).verdict())
            .isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);

        assertThat(pg.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, oa)).isEqualTo("new");
        assertThat(pg.queryForObject("SELECT sum(quantity)::int FROM order_items WHERE order_id = ?", Integer.class, oa)).isEqualTo(4);
        assertThat(pg.queryForObject("SELECT count(*)::int FROM audit_log WHERE tenant_id = ? AND action = 'order_reopened'",
            Integer.class, a.f().tenant)).isEqualTo(1);
        Integer units = TenantContext.runAs(a.f().tenant, () -> fulfill.getQueue()).stream()
            .filter(r -> oa.equals(r.get("id"))).map(r -> ((Number) r.get("total_units")).intValue()).findFirst().orElse(null);
        assertThat(units).isEqualTo(4);

        assertThat(pg.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, ob)).isEqualTo("awaiting_pickup");
        assertThat(pg.queryForObject("SELECT count(*)::int FROM audit_log WHERE tenant_id = ? AND action = 'order_reopened'",
            Integer.class, b.f().tenant)).isZero();
        Integer seen = TenantContext.runAs(a.f().tenant, () -> tx.execute(x ->
            appJdbc.queryForObject("SELECT count(*)::int FROM orders WHERE id = ?", Integer.class, ob)));
        assertThat(seen).as("RLS: A's context never sees B's order").isZero();
    }

    private void delivery(UUID o, String tn, int state) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "bosta-" + tn);
        raw.put("trackingNumber", tn);
        raw.put("businessReference", pg.queryForObject("SELECT number FROM orders WHERE id = ?", String.class, o));
        raw.put("createdAt", "2026-11-12T10:00:00.000Z");
        raw.put("updatedAt", Instant.now().toString());
        raw.putObject("type").put("code", 10).put("value", "Send");
        raw.putObject("state").put("code", state).put("value", "x");
        bosta.put(tn, raw);
    }

    private void webhook(Shop s, String tn, int state) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("trackingNumber", tn);
        payload.put("state", state);
        payload.put("type", "SEND");
        payload.put("updatedAt", Instant.now().toString() + "-" + UUID.randomUUID());
        Long id = pg.queryForObject("INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, s.f().tenant, payload.toString());
        webhookJob.process(id, s.f().tenant);
        assertThat(pg.queryForObject("SELECT status::text FROM webhook_events WHERE id = ?", String.class, id)).isEqualTo("processed");
    }
}
