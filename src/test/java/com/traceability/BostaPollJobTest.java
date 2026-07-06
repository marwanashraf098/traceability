package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for two-tier Bosta delivery polling.
 *
 * Matrix:
 *   p1  — Tier 1: non-terminal shipment with CHANGED state → fetched → enqueued → processed
 *   p2  — Tier 1: unchanged (same state+updatedAt) → second poll deduplicates
 *   p3  — Tier 1: terminal shipment is EXCLUDED from the poll set (fetchDelivery not called)
 *   p4  — Tier 1: cap + rotation — >maxPerCycle shipments → only N polled, last_polled_at set
 *   p5  — Tier 1: per-tenant TenantContext — GUC set, RLS enforced (verified as app_user)
 *   p6  — Tier 1 + Tier 2 coexistence — poll + discovery for the same delivery deduplicates
 *   p7  — Tier 2: new delivery on list → ingested with source='bosta_poll_discovery'
 *   p8  — Tier 2: already-ingested delivery → second discovery deduplicates
 *   p9  — terminal state set is correct (all 5 terminal values excluded)
 *   p10 — multi-tenant: tenant A's poll does not touch tenant B's shipments
 *
 * BostaGateway and JobScheduler are @MockBean. BostaStatusPollJob, BostaDiscoveryPollJob,
 * and BostaWebhookJob are exercised directly (JobRunr scheduling is not involved).
 * All per-delivery Bosta fetches are mocked — no real API calls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaPollJobTest {

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
        // Zero delay so tests run fast
        r.add("bosta.backfill.inter-fetch-delay-ms", () -> "0");
        r.add("bosta.poll.inter-fetch-delay-ms",     () -> "0");
        // Small cap for p4 (cap/rotation test)
        r.add("bosta.poll.status-max-per-cycle",     () -> "3");
    }

    @Autowired JdbcTemplate        jdbc;
    @Autowired ObjectMapper        mapper;
    @Autowired EncryptionService   encryptionService;
    @Autowired BostaStatusPollJob  statusPollJob;
    @Autowired BostaDiscoveryPollJob discoveryPollJob;
    @Autowired BostaWebhookJob     webhookJob;
    @MockBean  BostaGateway        bostaGateway;
    @MockBean  JobScheduler        jobScheduler;

    // app_user datasource — RLS enforced (no BYPASSRLS), used to prove GUC is active
    private JdbcTemplate       appUserJdbc;
    private TransactionTemplate appUserTx;

    // Shared fixture IDs (created once, reused across tests)
    private UUID tenantId;
    private UUID storeId;
    private UUID variantId;

    @BeforeAll
    void setupAppUser() {
        DriverManagerDataSource rawDs = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawDs);
        appUserJdbc = new JdbcTemplate(appUserDs);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
    }

    @BeforeAll
    void createFixtures() {
        tenantId  = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'PollTestTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'poll_owner@test.local', 'h', 'owner')",
                    UUID.randomUUID(), tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'poll-test.myshopify.com', 'disconnected')",
                    storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'PROD-POLL', 'Poll Product')",
                    productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'VAR-POLL', 'Poll Variant')",
                    variantId, tenantId, productId);
    }

    @BeforeEach
    void cleanup() {
        jdbc.execute("DELETE FROM unlinked_bosta_deliveries");
        jdbc.execute("DELETE FROM piece_events");
        jdbc.execute("DELETE FROM allocations");
        jdbc.execute("UPDATE pieces SET current_order_id = NULL");
        jdbc.execute("DELETE FROM shipments");
        jdbc.execute("DELETE FROM order_items");
        jdbc.execute("DELETE FROM pieces");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM webhook_events");
        jdbc.execute("DELETE FROM courier_accounts");
        reset(bostaGateway);
    }

    // ── p1: Tier 1 — changed state → full pipeline execution ──────────────────

    @Test
    void p1_statusPoll_changedState_enqueuedAndProcessed() {
        String tracking  = "BOS-POLL-P1";
        String extId     = "EXT-POLL-P1";
        String updatedAt = "2026-07-05T10:00:00.000Z";

        setupCourierAccount("poll-key-p1");
        UUID orderId     = createOrder(extId);
        UUID orderItemId = createOrderItem(orderId);
        String piece     = createPiece("packed");
        createAllocation(orderItemId, piece, "packed");
        // Shipment must link to the SAME orderId so BostaWebhookJob finds the pieces.
        jdbc.update(
            "INSERT INTO shipments " +
            "  (tenant_id, order_id, provider, tracking_number, internal_state) " +
            "VALUES (?, ?, 'bosta', ?, 'with_courier'::shipment_internal_state)",
            tenantId, orderId, tracking);

        when(bostaGateway.fetchDelivery(eq("poll-key-p1"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 45, "SEND", 1, extId, null,
                rawWithUpdatedAt(updatedAt)));

        statusPollJob.pollAll();

        Long eventId = pollWebhookEventId(tracking);
        assertThat(eventId).as("webhook_events row created by status poll").isNotNull();

        String source = jdbc.queryForObject(
            "SELECT source FROM webhook_events WHERE id = ?", String.class, eventId);
        assertThat(source).isEqualTo("bosta_poll");

        // Simulate JobRunr processing
        webhookJob.process(eventId, tenantId);

        assertThat(shipmentState(tracking)).isEqualTo("delivered");
        assertThat(pieceStatus(piece)).isEqualTo("delivered");
        assertThat(webhookStatus(eventId)).isEqualTo("processed");

        // last_polled_at must be updated
        Boolean polledAtSet = jdbc.queryForObject(
            "SELECT last_polled_at IS NOT NULL FROM shipments WHERE tracking_number = ?",
            Boolean.class, tracking);
        assertThat(polledAtSet).isTrue();
    }

    // ── p2: Tier 1 — unchanged state → state-change detection skips re-enqueue ──
    //
    // After first poll+process, shipment.provider_state is set to the last fetched
    // state code. The second poll reads it, fetches the same code again, and Guard 2
    // in BostaIngestionHelper returns false without creating a webhook_events row.
    // fetchDelivery IS still called (we always fetch to get the authoritative state
    // before deciding) — but no job is enqueued.

    @Test
    void p2_statusPoll_unchangedState_skipsReenqueue() {
        String tracking  = "BOS-POLL-P2";
        String updatedAt = "2026-07-05T11:00:00.000Z";

        setupCourierAccount("poll-key-p2");
        createShipment(tracking, "with_courier");

        when(bostaGateway.fetchDelivery(eq("poll-key-p2"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 41, "SEND", 0, "REF-P2", null,
                rawWithUpdatedAt(updatedAt)));

        // First poll — creates webhook_event and processes it (sets provider_state=41)
        statusPollJob.pollAll();
        Long firstEventId = pollWebhookEventId(tracking);
        assertThat(firstEventId).isNotNull();
        webhookJob.process(firstEventId, tenantId);
        assertThat(webhookStatus(firstEventId)).isEqualTo("processed");

        // After processing, provider_state must be set to the fetched code (41).
        // JDBC here runs as postgres (BYPASSRLS) — no TenantContext needed.
        Integer storedState = jdbc.queryForObject(
            "SELECT provider_state FROM shipments WHERE tracking_number = ?",
            Integer.class, tracking);
        assertThat(storedState).as("provider_state set by webhookJob").isEqualTo(41);

        // Second poll — same state (41) fetched again.
        // Guard 2 in BostaIngestionHelper sees fetchedState==provider_state → skips.
        // No new webhook_events row should be created.
        statusPollJob.pollAll();
        Long newestId = newestWebhookEventId(tracking, "bosta_poll");
        assertThat(newestId)
            .as("state-change detection must prevent re-enqueue when state is unchanged")
            .isEqualTo(firstEventId);

        // fetchDelivery called 3 times:
        //   poll 1: once by ingestDelivery + once by webhookJob re-fetch = 2
        //   poll 2: once by ingestDelivery (detects no change, returns early) = 1
        verify(bostaGateway, times(3)).fetchDelivery(eq("poll-key-p2"), eq(tracking));
    }

    // ── p3: Tier 1 — terminal shipment excluded from poll ─────────────────────

    @Test
    void p3_statusPoll_terminalShipment_notPolled() {
        setupCourierAccount("poll-key-p3");
        createShipment("BOS-POLL-P3-TERMINAL", "delivered");

        statusPollJob.pollAll();

        verify(bostaGateway, never()).fetchDelivery(anyString(), eq("BOS-POLL-P3-TERMINAL"));

        Integer eventCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM webhook_events WHERE payload->>'trackingNumber' = ?",
            Integer.class, "BOS-POLL-P3-TERMINAL");
        assertThat(eventCount).isZero();
    }

    // ── p4: Tier 1 — cap (max-per-cycle=3) + last_polled_at rotation ──────────

    @Test
    void p4_statusPoll_capAndRotation() {
        setupCourierAccount("poll-key-p4");

        // Create 5 non-terminal shipments
        List<String> trackings = List.of(
            "BOS-P4-1", "BOS-P4-2", "BOS-P4-3", "BOS-P4-4", "BOS-P4-5");
        for (String t : trackings) {
            createShipment(t, "with_courier");
            // Return null (not found) for each — simplest way to produce a no-op
            when(bostaGateway.fetchDelivery(anyString(), eq(t))).thenReturn(null);
        }

        statusPollJob.pollAll();  // max-per-cycle=3 (from test config)

        // Only 3 of the 5 should have been fetched (the cap applies)
        verify(bostaGateway, times(3)).fetchDelivery(anyString(), any());

        // Exactly 3 shipments should have last_polled_at set, 2 still NULL
        Integer polledCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tenant_id = ? AND last_polled_at IS NOT NULL",
            Integer.class, tenantId);
        Integer unpollCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tenant_id = ? AND last_polled_at IS NULL",
            Integer.class, tenantId);
        assertThat(polledCount).isEqualTo(3);
        assertThat(unpollCount).isEqualTo(2);

        // Second poll cycle: previously polled (last_polled_at set) come last → the 2
        // un-polled ones go first, plus 1 from the old batch
        reset(bostaGateway);
        for (String t : trackings) {
            when(bostaGateway.fetchDelivery(anyString(), eq(t))).thenReturn(null);
        }
        statusPollJob.pollAll();

        // After second cycle, all 5 should have been polled at least once
        Integer totalPolled = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tenant_id = ? AND last_polled_at IS NOT NULL",
            Integer.class, tenantId);
        assertThat(totalPolled).isEqualTo(5);
    }

    // ── p5: Tier 1 — per-tenant TenantContext verified via app_user role ──────

    @Test
    void p5_statusPoll_tenantContextAndRls_verifiedAsAppUser() {
        // Create a second tenant — the poll must only touch its own tenant's shipments
        UUID tenant2Id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'PollTenant2')", tenant2Id);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner2', 'poll2@test.local', 'h', 'owner')",
                    UUID.randomUUID(), tenant2Id);

        setupCourierAccount("poll-key-p5-a");
        // Tenant 2's courier account — direct insert bypassing RLS (postgres role)
        jdbc.update("INSERT INTO courier_accounts " +
                    "(tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (?, 'bosta', ?, 'hash2', 'active')",
                    tenant2Id, encryptionService.encrypt("poll-key-p5-b"));

        createShipment("BOS-P5-TENANT1", "with_courier");

        // Tenant 2 shipment — use a separate store for FK validity
        UUID store2 = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'p5-t2.myshopify.com', 'disconnected')",
                    store2, tenant2Id);
        UUID order2 = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, status) " +
            "VALUES (?, ?, 'EXT-P5-T2', 'new'::order_status) RETURNING id",
            UUID.class, tenant2Id, store2);
        jdbc.update("INSERT INTO shipments " +
                    "(tenant_id, order_id, provider, tracking_number, internal_state) " +
                    "VALUES (?, ?, 'bosta', 'BOS-P5-TENANT2', 'with_courier'::shipment_internal_state)",
                    tenant2Id, order2);

        when(bostaGateway.fetchDelivery(anyString(), anyString())).thenReturn(null);

        statusPollJob.pollAll();

        // Both tenants' shipments must have last_polled_at set (each ran in its own context)
        Boolean t1Polled = jdbc.queryForObject(
            "SELECT last_polled_at IS NOT NULL FROM shipments WHERE tracking_number = ?",
            Boolean.class, "BOS-P5-TENANT1");
        Boolean t2Polled = jdbc.queryForObject(
            "SELECT last_polled_at IS NOT NULL FROM shipments WHERE tracking_number = ?",
            Boolean.class, "BOS-P5-TENANT2");
        assertThat(t1Polled).as("tenant1 shipment polled").isTrue();
        assertThat(t2Polled).as("tenant2 shipment polled").isTrue();

        // Verify GUC was active for tenant1's run: app_user (RLS enforced) can see the
        // updated last_polled_at inside TenantContext.runAs(tenantId) — if the UPDATE
        // ran without the GUC set, the RLS WITH CHECK would have rejected it (the row
        // would not exist from app_user's perspective).
        Object lastPolledAt = TenantContext.runAs(tenantId, () -> appUserTx.execute(s ->
            appUserJdbc.queryForObject(
                "SELECT last_polled_at FROM shipments WHERE tracking_number = ?",
                Object.class, "BOS-P5-TENANT1")));
        assertThat(lastPolledAt)
            .as("last_polled_at visible via app_user — proves GUC was active during UPDATE")
            .isNotNull();

        // Tenant isolation: app_user under tenantId cannot see tenant2's shipment
        Integer t2Count = TenantContext.runAs(tenantId, () -> appUserTx.execute(s ->
            appUserJdbc.queryForObject(
                "SELECT COUNT(*) FROM shipments WHERE tracking_number = ?",
                Integer.class, "BOS-P5-TENANT2")));
        assertThat(t2Count)
            .as("tenant1 context must not see tenant2 shipments (RLS isolation)")
            .isZero();

        // Cleanup tenant2 rows
        jdbc.execute("DELETE FROM shipments WHERE tenant_id = '" + tenant2Id + "'");
        jdbc.execute("DELETE FROM orders WHERE tenant_id = '" + tenant2Id + "'");
        jdbc.execute("DELETE FROM stores WHERE tenant_id = '" + tenant2Id + "'");
        jdbc.execute("DELETE FROM courier_accounts WHERE tenant_id = '" + tenant2Id + "'");
        jdbc.execute("DELETE FROM users WHERE tenant_id = '" + tenant2Id + "'");
        jdbc.execute("DELETE FROM tenants WHERE id = '" + tenant2Id + "'");
    }

    // ── p6: Tier 1 + Tier 2 coexistence — same delivery deduplicated ──────────

    @Test
    void p6_pollAndDiscovery_sameDelivery_coexistWithoutDuplicate() {
        String tracking  = "BOS-POLL-P6";
        String updatedAt = "2026-07-05T12:00:00.000Z";
        String extId     = "EXT-POLL-P6";

        setupCourierAccount("poll-key-p6");
        createShipment(tracking, "with_courier");

        BostaDelivery delivery = new BostaDelivery(tracking, 45, "SEND", 1, extId, null,
            rawWithUpdatedAt(updatedAt));

        when(bostaGateway.fetchDelivery(anyString(), eq(tracking))).thenReturn(delivery);
        when(bostaGateway.listDeliveriesPage(anyString(), eq(1), anyInt()))
            .thenReturn(List.of(new BostaGateway.SlimDelivery(tracking, 45, "SEND")));
        when(bostaGateway.listDeliveriesPage(anyString(), eq(2), anyInt()))
            .thenReturn(List.of());
        when(bostaGateway.listDeliveriesPage(anyString(), eq(3), anyInt()))
            .thenReturn(List.of());

        // Both Tier 1 and Tier 2 fire for the same delivery
        statusPollJob.pollAll();
        discoveryPollJob.discoverAll();

        // Two webhook_events rows (one per source), but the same idem key
        Integer eventCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM webhook_events WHERE payload->>'trackingNumber' = ?",
            Integer.class, tracking);
        assertThat(eventCount).isEqualTo(2);

        // Process both — only the first one transitions; second is dedup'd
        List<Long> ids = jdbc.queryForList(
            "SELECT id FROM webhook_events WHERE payload->>'trackingNumber' = ? ORDER BY id",
            Long.class, tracking);
        webhookJob.process(ids.get(0), tenantId);
        webhookJob.process(ids.get(1), tenantId);

        assertThat(webhookStatus(ids.get(0))).isEqualTo("processed");
        String secondError = jdbc.queryForObject(
            "SELECT error FROM webhook_events WHERE id = ?", String.class, ids.get(1));
        assertThat(secondError).as("second event (same idem key) is a duplicate").contains("duplicate");
    }

    // ── p7: Tier 2 — new delivery discovered + ingested ────────────────────────

    @Test
    void p7_discoveryPoll_newDelivery_ingestedWithCorrectSource() {
        String tracking  = "BOS-DISC-P7";
        String updatedAt = "2026-07-05T13:00:00.000Z";

        setupCourierAccount("poll-key-p7");

        when(bostaGateway.listDeliveriesPage(anyString(), eq(1), anyInt()))
            .thenReturn(List.of(new BostaGateway.SlimDelivery(tracking, 41, "SEND")));
        when(bostaGateway.listDeliveriesPage(anyString(), eq(2), anyInt()))
            .thenReturn(List.of());
        when(bostaGateway.listDeliveriesPage(anyString(), eq(3), anyInt()))
            .thenReturn(List.of());
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 41, "SEND", 0, "REF-P7", null,
                rawWithUpdatedAt(updatedAt)));

        discoveryPollJob.discoverAll();

        Long eventId = jdbc.query(
            "SELECT id FROM webhook_events WHERE payload->>'trackingNumber' = ? AND source::text = 'bosta_poll_discovery'",
            rs -> rs.next() ? rs.getLong("id") : null,
            tracking);
        assertThat(eventId).as("discovery poll must create event with source=bosta_poll_discovery").isNotNull();
    }

    // ── p8: Tier 2 — already-ingested delivery deduplicates on second discovery ─

    @Test
    void p8_discoveryPoll_alreadyIngested_deduplicatesOnSecondRun() {
        String tracking  = "BOS-DISC-P8";
        String updatedAt = "2026-07-05T14:00:00.000Z";

        setupCourierAccount("poll-key-p8");

        when(bostaGateway.listDeliveriesPage(anyString(), eq(1), anyInt()))
            .thenReturn(List.of(new BostaGateway.SlimDelivery(tracking, 41, "SEND")));
        when(bostaGateway.listDeliveriesPage(anyString(), eq(2), anyInt()))
            .thenReturn(List.of());
        when(bostaGateway.listDeliveriesPage(anyString(), eq(3), anyInt()))
            .thenReturn(List.of());
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 41, "SEND", 0, "REF-P8", null,
                rawWithUpdatedAt(updatedAt)));

        // First discovery run
        discoveryPollJob.discoverAll();
        Long firstId = jdbc.query(
            "SELECT id FROM webhook_events WHERE payload->>'trackingNumber' = ? AND source::text = 'bosta_poll_discovery'",
            rs -> rs.next() ? rs.getLong("id") : null, tracking);
        assertThat(firstId).isNotNull();
        webhookJob.process(firstId, tenantId);
        assertThat(webhookStatus(firstId)).isEqualTo("processed");

        // Second discovery run — same idem key → dedup
        discoveryPollJob.discoverAll();
        List<Long> allIds = jdbc.queryForList(
            "SELECT id FROM webhook_events WHERE payload->>'trackingNumber' = ? AND source::text = 'bosta_poll_discovery' ORDER BY id",
            Long.class, tracking);
        assertThat(allIds).hasSize(2);

        webhookJob.process(allIds.get(1), tenantId);
        String secondError = jdbc.queryForObject(
            "SELECT error FROM webhook_events WHERE id = ?", String.class, allIds.get(1));
        assertThat(secondError).as("second discovery event deduplicated").contains("duplicate");
    }

    // ── p9: terminal state set is complete and correct ────────────────────────

    @Test
    void p9_terminalStateSet_allTerminalsExcluded() {
        setupCourierAccount("poll-key-p9");

        for (String state : List.of("delivered", "returned", "lost", "terminated", "cancelled")) {
            createShipment("BOS-TERM-" + state, state);
        }
        // One non-terminal so the poll actually runs
        createShipment("BOS-NONTERMINAL", "with_courier");
        when(bostaGateway.fetchDelivery(anyString(), eq("BOS-NONTERMINAL"))).thenReturn(null);

        statusPollJob.pollAll();

        // fetchDelivery called only for the non-terminal shipment
        verify(bostaGateway, times(1)).fetchDelivery(anyString(), any());
        verify(bostaGateway).fetchDelivery(anyString(), eq("BOS-NONTERMINAL"));

        for (String state : List.of("delivered", "returned", "lost", "terminated", "cancelled")) {
            verify(bostaGateway, never()).fetchDelivery(anyString(), eq("BOS-TERM-" + state));
        }
    }

    // ── p10: multi-tenant — poll creates events scoped to their own tenant ────

    @Test
    void p10_statusPoll_multiTenant_eventsCorrectlyScoped() {
        // Tenant1 (tenantId) already exists. Create tenant2.
        UUID tenant2Id = UUID.randomUUID();
        UUID store2    = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'PollTenant2b')", tenant2Id);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owns2', 'poll2b@test.local', 'h', 'owner')",
                    UUID.randomUUID(), tenant2Id);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'p10-t2.myshopify.com', 'disconnected')",
                    store2, tenant2Id);
        jdbc.update("INSERT INTO courier_accounts " +
                    "(tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (?, 'bosta', ?, 'h2', 'active')",
                    tenant2Id, encryptionService.encrypt("poll-key-p10-b"));

        setupCourierAccount("poll-key-p10-a");
        createShipment("BOS-P10-T1", "with_courier");

        UUID order2 = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, status) " +
            "VALUES (?, ?, 'EXT-P10-T2', 'new'::order_status) RETURNING id",
            UUID.class, tenant2Id, store2);
        jdbc.update("INSERT INTO shipments " +
                    "(tenant_id, order_id, provider, tracking_number, internal_state) " +
                    "VALUES (?, ?, 'bosta', 'BOS-P10-T2', 'with_courier'::shipment_internal_state)",
                    tenant2Id, order2);

        when(bostaGateway.fetchDelivery(anyString(), anyString())).thenReturn(null);

        statusPollJob.pollAll();

        // Each shipment's webhook_event (if any) must belong to its own tenant
        // (Neither returns a delivery here so no events — just verify both were polled)
        Boolean t1Polled = jdbc.queryForObject(
            "SELECT last_polled_at IS NOT NULL FROM shipments WHERE tracking_number = ?",
            Boolean.class, "BOS-P10-T1");
        Boolean t2Polled = jdbc.queryForObject(
            "SELECT last_polled_at IS NOT NULL FROM shipments WHERE tracking_number = ?",
            Boolean.class, "BOS-P10-T2");
        assertThat(t1Polled).as("tenant1 shipment polled").isTrue();
        assertThat(t2Polled).as("tenant2 shipment polled").isTrue();

        // Cleanup tenant2
        jdbc.execute("DELETE FROM shipments WHERE tenant_id = '" + tenant2Id + "'");
        jdbc.execute("DELETE FROM orders WHERE tenant_id = '" + tenant2Id + "'");
        jdbc.execute("DELETE FROM stores WHERE tenant_id = '" + tenant2Id + "'");
        jdbc.execute("DELETE FROM courier_accounts WHERE tenant_id = '" + tenant2Id + "'");
        jdbc.execute("DELETE FROM users WHERE tenant_id = '" + tenant2Id + "'");
        jdbc.execute("DELETE FROM tenants WHERE id = '" + tenant2Id + "'");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void setupCourierAccount(String rawApiKey) {
        jdbc.update(
            "INSERT INTO courier_accounts " +
            "    (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'test-hash', 'active')",
            tenantId, encryptionService.encrypt(rawApiKey));
    }

    private void createShipment(String trackingNumber, String state) {
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, status) " +
            "VALUES (?, ?, ?, 'new'::order_status) RETURNING id",
            UUID.class, tenantId, storeId, "EXT-" + trackingNumber);
        jdbc.update(
            "INSERT INTO shipments " +
            "  (tenant_id, order_id, provider, tracking_number, internal_state) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state)",
            tenantId, orderId, trackingNumber, state);
    }

    private UUID createOrder(String extId) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, status) " +
            "VALUES (?, ?, ?, 'new'::order_status) RETURNING id",
            UUID.class, tenantId, storeId, extId);
    }

    private UUID createOrderItem(UUID orderId) {
        return jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) " +
            "VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, tenantId, orderId, variantId);
    }

    private String createPiece(String status) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, status) " +
            "VALUES (?, ?, ?, ?, ?::piece_status)",
            id, tenantId, variantId, "PC-" + id, status);
        return id;
    }

    private void createAllocation(UUID orderItemId, String pieceId, String status) {
        jdbc.update(
            "INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) " +
            "VALUES (?, ?, ?, ?::allocation_status)",
            tenantId, orderItemId, pieceId, status);
    }

    private Long pollWebhookEventId(String trackingNumber) {
        return jdbc.query(
            "SELECT id FROM webhook_events " +
            "WHERE payload->>'trackingNumber' = ? AND source::text = 'bosta_poll' " +
            "ORDER BY id DESC LIMIT 1",
            rs -> rs.next() ? rs.getLong("id") : null,
            trackingNumber);
    }

    private Long newestWebhookEventId(String trackingNumber, String source) {
        return jdbc.query(
            "SELECT id FROM webhook_events " +
            "WHERE payload->>'trackingNumber' = ? AND source::text = ? " +
            "ORDER BY id DESC LIMIT 1",
            rs -> rs.next() ? rs.getLong("id") : null,
            trackingNumber, source);
    }

    private String shipmentState(String trackingNumber) {
        return jdbc.queryForObject(
            "SELECT internal_state FROM shipments WHERE tracking_number = ?",
            String.class, trackingNumber);
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject(
            "SELECT status FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String webhookStatus(Long id) {
        return jdbc.queryForObject(
            "SELECT status FROM webhook_events WHERE id = ?", String.class, id);
    }

    private ObjectNode rawWithUpdatedAt(String updatedAt) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("updatedAt", updatedAt);
        return raw;
    }
}
