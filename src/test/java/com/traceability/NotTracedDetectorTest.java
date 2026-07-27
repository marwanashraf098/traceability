package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.inventory.NotTracedTagger;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.inventory.UlidGenerator;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Queue-gating-not-traced spec — go-forward detector (NotTracedTagger) and its two call
 * sites: BostaWebhookJob.process() (covers webhook/poll/discovery/backfill — they all funnel
 * into process()) and ShipmentLinkService.manualLink() (build-spec finding A: a shipment can
 * be CREATED already in a terminal state when an operator or BostaOrderReconcileJob manually
 * links a delivery that was already terminal at discovery time — that path never calls
 * process(), so it needs its own call site).
 *
 * Test inventory:
 *   a — pre-pack order, zero allocations, shipment → delivered (via process()) ⇒ tagged
 *   b — packed order (has packed allocations) reaching with_courier (via process()) ⇒ stays NULL
 *   c — idempotent re-run: a second, later-state event never overwrites the timestamp
 *   d — same-tenant positive control: one order tagged, a sibling order under the SAME
 *       tenant untouched, distinguished purely by the allocation guard
 *   e — manualLink() born-terminal path (finding A): linking an already-delivered delivery
 *       to a zero-allocation order tags it, with no BostaWebhookJob.process() call involved
 *   f — RLS: the not_traced_at UPDATE respects tenant isolation via app_user
 *       (positive same-tenant control + cross-tenant no-op)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotTracedDetectorTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
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

    @Autowired JdbcTemplate        jdbc;
    @Autowired ObjectMapper        mapper;
    @Autowired EncryptionService   encryptionService;
    @Autowired BostaWebhookJob     webhookJob;
    @Autowired ShipmentLinkService shipmentLinkService;
    @MockBean  BostaGateway        bostaGateway;
    @MockBean  JobScheduler        jobScheduler;

    // app_user infrastructure for the RLS test — mirrors InventoryLedgerTest's appUserLedger.
    private JdbcTemplate        appUserJdbc;
    private TransactionTemplate appUserTx;
    private NotTracedTagger     appUserTagger;

    private UUID tenantId, storeId, productId, variantId;

    @BeforeAll
    void setupAppUser() {
        DriverManagerDataSource rawDs =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(rawDs);
        appUserJdbc   = new JdbcTemplate(appDs);
        appUserTx     = new TransactionTemplate(new DataSourceTransactionManager(appDs));
        appUserTagger = new NotTracedTagger(appUserJdbc);
    }

    @BeforeAll
    void createFixtures() {
        tenantId  = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'NotTracedTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'nt_owner@test.local', 'h', 'owner')",
                    UUID.randomUUID(), tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'nt-test.myshopify.com', 'disconnected')",
                    storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'NT-PROD', 'NT Product', 'active')",
                    productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'NT-VAR', 'NT Variant')",
                    variantId, tenantId, productId);
        jdbc.update("INSERT INTO courier_accounts " +
                    "  (id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'testhash', 'active')",
                    tenantId, encryptionService.encrypt("nt-api-key"));
    }

    @BeforeEach
    void cleanup() {
        reset(bostaGateway);
        jdbc.execute("DELETE FROM unlinked_bosta_deliveries");
        jdbc.execute("DELETE FROM allocations");
        jdbc.execute("DELETE FROM piece_events");
        jdbc.execute("DELETE FROM pieces");
        jdbc.execute("DELETE FROM shipments");
        jdbc.execute("DELETE FROM order_items");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM webhook_events");
    }

    // ── a: pre-pack, zero allocations, delivered ⇒ tagged ───────────────────────

    @Test
    void a_prePack_zeroAllocations_delivered_tagsNotTraced() {
        UUID orderId = createOrder("NT-A");
        createOrderItem(orderId); // line item present, nothing scanned — zero allocations
        String tracking = "NT-TN-A";
        createShipment(tracking, orderId, "with_courier");

        String upd = "2026-07-27T09:00:00.000Z";
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 45, "SEND", 1, null, null, raw(upd)));

        long eventId = insertPendingEvent(tracking, 45, upd);
        webhookJob.process(eventId, tenantId);

        assertThat(notTracedAt(orderId)).as("zero-allocation order sent to Bosta must be tagged").isNotNull();
    }

    // ── b: packed order reaching with_courier ⇒ stays NULL ──────────────────────

    @Test
    void b_packedOrder_withCourier_staysNull() {
        UUID orderId = createOrder("NT-B");
        UUID itemId  = createOrderItem(orderId);
        packPiece(itemId);
        String tracking = "NT-TN-B";
        createShipment(tracking, orderId, "created");

        String upd = "2026-07-27T09:05:00.000Z";
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 21, "SEND", 1, null, null, raw(upd)));

        long eventId = insertPendingEvent(tracking, 21, upd);
        webhookJob.process(eventId, tenantId);

        assertThat(notTracedAt(orderId)).as("properly-packed order must not be tagged").isNull();
    }

    // ── c: idempotent re-run — timestamp is never overwritten ───────────────────

    @Test
    void c_idempotent_reRunDoesNotOverwriteTimestamp() {
        UUID orderId = createOrder("NT-C");
        createOrderItem(orderId);
        String tracking = "NT-TN-C";
        createShipment(tracking, orderId, "created");

        String upd1 = "2026-07-27T10:00:00.000Z";
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 21, "SEND", 1, null, null, raw(upd1)));
        long ev1 = insertPendingEvent(tracking, 21, upd1);
        webhookJob.process(ev1, tenantId);

        Instant ts1 = notTracedAt(orderId);
        assertThat(ts1).as("first sent-to-Bosta event tags the order").isNotNull();

        String upd2 = "2026-07-27T11:00:00.000Z";
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 45, "SEND", 1, null, null, raw(upd2)));
        long ev2 = insertPendingEvent(tracking, 45, upd2);
        webhookJob.process(ev2, tenantId);

        Instant ts2 = notTracedAt(orderId);
        assertThat(ts2).as("re-run must not overwrite the original tag timestamp").isEqualTo(ts1);
    }

    // ── d: same-tenant positive control ──────────────────────────────────────────

    @Test
    void d_sameTenantPositiveControl_oneTaggedOneNot() {
        UUID zeroAllocOrder = createOrder("NT-D-ZERO");
        createOrderItem(zeroAllocOrder);
        String trackingZero = "NT-TN-D-ZERO";
        createShipment(trackingZero, zeroAllocOrder, "with_courier");

        UUID packedOrder = createOrder("NT-D-PACKED");
        UUID itemId = createOrderItem(packedOrder);
        packPiece(itemId);
        String trackingPacked = "NT-TN-D-PACKED";
        createShipment(trackingPacked, packedOrder, "created");

        String upd = "2026-07-27T12:00:00.000Z";
        when(bostaGateway.fetchDelivery(anyString(), eq(trackingZero)))
            .thenReturn(new BostaDelivery(trackingZero, 45, "SEND", 1, null, null, raw(upd)));
        when(bostaGateway.fetchDelivery(anyString(), eq(trackingPacked)))
            .thenReturn(new BostaDelivery(trackingPacked, 21, "SEND", 1, null, null, raw(upd)));

        webhookJob.process(insertPendingEvent(trackingZero, 45, upd), tenantId);
        webhookJob.process(insertPendingEvent(trackingPacked, 21, upd), tenantId);

        assertThat(notTracedAt(zeroAllocOrder))
            .as("same tenant — zero-allocation order tagged").isNotNull();
        assertThat(notTracedAt(packedOrder))
            .as("same tenant — packed sibling order untouched").isNull();
    }

    // ── e: manualLink() born-terminal path (finding A) ──────────────────────────

    @Test
    void e_manualLink_bornTerminal_zeroAllocations_tagsNotTraced() {
        String orderNum = "#NT-E";
        UUID orderId = createOrder("NT-E", orderNum);
        createOrderItem(orderId); // zero allocations
        String tracking = "NT-TN-E";

        // Delivery was already 'delivered' (state 45) at the time discovery first saw it —
        // exactly the shape of the 58 stuck Jumi orders, but arriving via manual/reconcile
        // link instead of a live webhook.
        Long unlinkedId = jdbc.queryForObject(
            "INSERT INTO unlinked_bosta_deliveries " +
            "  (tenant_id, tracking_number, business_reference, bosta_state_code, " +
            "   bosta_order_type, match_reason, resolved) " +
            "VALUES (?, ?, ?, 45, 'SEND', 'NO_MATCH', false) RETURNING id",
            Long.class, tenantId, tracking, orderNum);

        TenantContext.runAs(tenantId, () ->
            shipmentLinkService.manualLink(unlinkedId, orderId, null));

        String shipmentState = jdbc.queryForObject(
            "SELECT internal_state FROM shipments WHERE tracking_number = ?",
            String.class, tracking);
        assertThat(shipmentState)
            .as("shipment must be born already terminal — process() was never called")
            .isEqualTo("delivered");

        assertThat(notTracedAt(orderId))
            .as("manualLink() must tag a zero-allocation order linked to an already-terminal delivery")
            .isNotNull();
    }

    // ── f: RLS — positive same-tenant control + cross-tenant no-op ──────────────

    @Test
    void f_rls_appUser_sameTenantPositive_crossTenantNoOp() {
        UUID tenantB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'NotTracedTenantB')", tenantB);
        UUID storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'nt-test-b.myshopify.com', 'disconnected')",
                    storeB, tenantB);

        UUID orderA = createOrder("NT-F-A");
        createShipment("NT-TN-F-A", orderA, "delivered");

        UUID orderB = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, 'EXT-NT-F-B', '#NT-F-B', 'new'::order_status, now()) RETURNING id",
            UUID.class, tenantB, storeB);
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "    internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', 'NT-TN-F-B', 'delivered'::shipment_internal_state, 'forward')",
            tenantB, orderB);

        // Positive same-tenant control: app_user WITH the correct GUC tags orderA.
        TenantContext.runAs(tenantId, () ->
            appUserTx.execute(s -> {
                appUserTagger.maybeTagNotTraced(orderA, tenantId);
                return null;
            }));
        assertThat(notTracedAt(orderA))
            .as("app_user with correct tenant GUC can tag its own tenant's order").isNotNull();

        // Cross-tenant no-op: GUC is set to tenantId (tenant A) but the target order/tenant
        // param is tenantB — RLS must block the UPDATE, leaving orderB untouched.
        TenantContext.runAs(tenantId, () ->
            appUserTx.execute(s -> {
                appUserTagger.maybeTagNotTraced(orderB, tenantB);
                return null;
            }));
        assertThat(notTracedAt(orderB))
            .as("app_user cannot tag another tenant's order across a GUC mismatch").isNull();

        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantB);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantB);
        jdbc.update("DELETE FROM stores WHERE tenant_id = ?", tenantB);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantB);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID createOrder(String extId) {
        return createOrder(extId, "#" + extId);
    }

    private UUID createOrder(String extId, String number) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, now()) RETURNING id",
            UUID.class, tenantId, storeId, "EXT-" + extId, number);
    }

    private UUID createOrderItem(UUID orderId) {
        return jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) " +
            "VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, tenantId, orderId, variantId);
    }

    private void packPiece(UUID orderItemId) {
        String pieceId = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
            "        'packed'::piece_status)",
            pieceId, tenantId, variantId, "PC-" + pieceId, pieceId);
        jdbc.update(
            "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'packed'::allocation_status)",
            tenantId, orderItemId, pieceId);
    }

    private void createShipment(String tracking, UUID orderId, String state) {
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "    internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward')",
            tenantId, orderId, tracking, state);
    }

    private long insertPendingEvent(String tracking, int stateCode, String updatedAt) {
        String payload = String.format(
            "{\"trackingNumber\":\"%s\",\"state\":%d,\"type\":\"SEND\",\"updatedAt\":\"%s\"}",
            tracking, stateCode, updatedAt);
        return jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status) " +
            "VALUES ('bosta_poll'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending') " +
            "RETURNING id",
            Long.class, tenantId, payload);
    }

    private ObjectNode raw(String updatedAt) {
        ObjectNode n = mapper.createObjectNode();
        n.put("updatedAt", updatedAt);
        return n;
    }

    private Instant notTracedAt(UUID orderId) {
        return jdbc.query(
            "SELECT not_traced_at FROM orders WHERE id = ?",
            rs -> {
                if (!rs.next()) return null;
                var ts = rs.getTimestamp("not_traced_at");
                return ts != null ? ts.toInstant() : null;
            }, orderId);
    }
}
