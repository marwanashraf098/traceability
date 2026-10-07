package com.traceability;

import com.traceability.analytics.SettlementRefreshJob;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.inventory.UlidGenerator;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Analytics slice 3, FIX 2 — a settlement refresh that finds a different state goes through the
 * status poll's own pipeline (webhook_events → BostaWebhookJob), end to end against the real job:
 * history row, mapped state, the existing monotonic-vs-created guard. An unchanged state writes
 * only raw + settlement and creates no event. BostaGateway is mocked (no Bosta call); JobRunr is
 * mocked, so each event is processed by calling the job directly, as BostaPollJobTest does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class SettlementRefreshProcessingTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("analytics.settlement.refresh-enabled", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EncryptionService encryption;
    @Autowired SettlementRefreshJob refreshJob;
    @Autowired BostaWebhookJob webhookJob;
    @MockBean BostaGateway bostaGateway;
    @MockBean BostaV2Client bostaV2;
    @MockBean JobScheduler jobScheduler;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    final class T {
        final UUID id = UUID.randomUUID(), store = UUID.randomUUID(), variant = UUID.randomUUID();

        T(String name) {
            UUID product = UUID.randomUUID();
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                        "VALUES (?, ?, 'shopify', ?, 'disconnected')", store, id, "srp-" + id + ".myshopify.com");
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, 'P')",
                        product, id, store, "P-" + product);
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) VALUES (?, ?, ?, ?, 'V')",
                        variant, id, product, "V-" + variant);
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                        "VALUES (?, 'bosta', ?, ?, 'active')", id, encryption.encrypt("key-" + id), "h-" + id);
        }

        /** A delivered forward SEND leg (state 45) with one delivered piece on its order. */
        Shipment deliveredLeg(String tn, String raw) {
            UUID order = jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status) " +
                "VALUES (?, ?, ?, ?, 'new'::order_status) RETURNING id", UUID.class, id, store, "EXT-" + tn, "#" + tn);
            UUID item = jdbc.queryForObject(
                "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
                UUID.class, id, order, variant);
            String piece = UlidGenerator.generate();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                        "VALUES (?, ?, ?, ?, ?, 'delivered')", piece, id, variant, "PC-" + piece, "S" + tn);
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
                        id, item, piece);
            UUID ship = jdbc.queryForObject(
                "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, " +
                "  provider_state, raw, delivered_at) " +
                "VALUES (?, ?, 'bosta', ?, 'delivered', 'forward', 45, ?::jsonb, now() - interval '3 days') RETURNING id",
                UUID.class, id, order, tn, raw);
            for (String st : List.of("with_courier", "delivered")) {
                jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) " +
                            "VALUES (?, ?, ?::shipment_internal_state, now() - interval '3 days')", id, ship, st);
            }
            return new Shipment(ship, piece);
        }

        void bostaAnswers(String tn, String raw) {
            BostaDelivery d = BostaDelivery.fromRaw(tn, ShipmentSettlementTest.json(raw));
            when(bostaGateway.fetchDelivery(anyString(), org.mockito.ArgumentMatchers.eq(tn))).thenReturn(d);
        }

        Long eventFor(String tn) {
            return jdbc.query("SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
                              "AND source::text = 'bosta_poll' ORDER BY id DESC LIMIT 1",
                              rs -> rs.next() ? rs.getLong(1) : null, id, tn);
        }

        String state(UUID ship) {
            return jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE id = ?", String.class, ship);
        }

        List<String> history(UUID ship) {
            return jdbc.queryForList("SELECT internal_state::text FROM shipment_status_history WHERE shipment_id = ? " +
                                     "ORDER BY occurred_at, id", String.class, ship);
        }

        String pieceStatus(String piece) {
            return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece);
        }
    }

    record Shipment(UUID id, String piece) {}

    static final String SEND_45 = "{\"trackingNumber\":\"%s\",\"type\":{\"code\":10,\"value\":\"Send\"}," +
        "\"state\":{\"code\":45},\"cod\":500,\"shipmentFees\":50,\"updatedAt\":\"2026-10-01T10:00:00.000Z\"}";

    @Test
    void deliveredThenRto_goesThroughThePollPipeline() {
        T t = new T("SRP-RTO");
        String tn = "4400100";
        Shipment s = t.deliveredLeg(tn, SEND_45.formatted(tn));
        t.bostaAnswers(tn, "{\"trackingNumber\":\"" + tn + "\",\"type\":{\"code\":20,\"value\":\"Return to Origin\"}," +
            "\"state\":{\"code\":41},\"cod\":500,\"shipmentFees\":50,\"updatedAt\":\"2026-10-05T10:00:00.000Z\"}");

        SettlementRefreshJob.RefreshResult r = refreshJob.refreshTenant(t.id, "key-" + t.id);
        assertThat(r.routed()).isEqualTo(1);
        Long event = t.eventFor(tn);
        assertThat(event).as("a status change is an event for the webhook job, like a polled change").isNotNull();
        assertThat(t.state(s.id())).as("nothing applied before the job runs").isEqualTo("delivered");

        webhookJob.process(event, t.id);

        assertThat(t.state(s.id())).isEqualTo("returning");
        assertThat(t.history(s.id())).endsWith("returning");
        assertThat(jdbc.queryForObject("SELECT raw->'type'->>'value' FROM shipments WHERE id = ?", String.class, s.id()))
            .isEqualTo("Return to Origin");
        assertThat(jdbc.queryForObject("SELECT provider_state FROM shipments WHERE id = ?", Integer.class, s.id()))
            .isEqualTo(41);
        assertThat(t.pieceStatus(s.piece())).as("RTO 41 maps to no piece move").isEqualTo("delivered");
    }

    @Test
    void staleLowerState_neverRegresses_monotonicGuard() {
        T t = new T("SRP-Stale");
        String tn = "4400200";
        Shipment s = t.deliveredLeg(tn, SEND_45.formatted(tn));
        t.bostaAnswers(tn, "{\"trackingNumber\":\"" + tn + "\",\"type\":{\"code\":10,\"value\":\"Send\"}," +
            "\"state\":{\"code\":10},\"cod\":500,\"updatedAt\":\"2026-10-05T11:00:00.000Z\"}");

        refreshJob.refreshTenant(t.id, "key-" + t.id);
        Long event = t.eventFor(tn);
        assertThat(event).isNotNull();
        webhookJob.process(event, t.id);

        // The shipment has moved before: a 'created' code resolves to 'exception', never back to created.
        assertThat(t.state(s.id())).isEqualTo("exception");
        assertThat(t.pieceStatus(s.piece())).isEqualTo("delivered");
    }

    @Test
    void unchangedState_writesRawAndSettlementOnly_noEvent() {
        T t = new T("SRP-Same");
        String tn = "4400300";
        Shipment s = t.deliveredLeg(tn, SettlementPayloads.V2_ITEM_NULL_WALLET);
        t.bostaAnswers(tn, SettlementPayloads.DELIVERED_PAID);

        SettlementRefreshJob.RefreshResult r = refreshJob.refreshTenant(t.id, "key-" + t.id);
        assertThat(r.routed()).isZero();
        assertThat(r.newlyPaid()).isEqualTo(1);
        assertThat(t.eventFor(tn)).isNull();
        assertThat(t.state(s.id())).isEqualTo("delivered");
        assertThat(t.history(s.id())).containsExactly("with_courier", "delivered");
        assertThat(jdbc.queryForObject("SELECT raw::text FROM shipments WHERE id = ?", String.class, s.id()))
            .doesNotContain("_tracedRawShape").contains("WEDCOD09SEP26");
        assertThat(jdbc.queryForObject("SELECT settlement_status FROM shipments WHERE id = ?", String.class, s.id()))
            .isEqualTo("paid");
    }
}
