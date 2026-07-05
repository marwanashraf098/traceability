package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.bosta.*;
import com.traceability.inventory.UlidGenerator;
import com.traceability.security.EncryptionService;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.traceability.identity.model.AccessTokenResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.jobrunr.jobs.lambdas.JobLambda;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for the Bosta delivery backfill feature.
 *
 * BostaGateway and JobScheduler are @MockBean — no real Bosta API calls.
 * BostaBackfillJob is exercised directly; BostaWebhookJob is also invoked directly
 * to simulate JobRunr processing (enqueue is a no-op via mock; we manually call
 * webhookJob.process() after querying webhook_events for the inserted rows).
 *
 * Covered cases:
 *   (1)  Backfill routes through webhook pipeline — outcome identical to live webhook.
 *   (2)  Idempotency: backfill + same-state-timestamp webhook deduplicate.
 *   (3)  Idempotency: run backfill twice — second run's events are dedup'd.
 *   (4)  State change after backfill: new state produces new idem key → processed.
 *   (5)  Mid-lifecycle: state=45 backfill on packed order → shipment=delivered,
 *        pieces packed→awaiting_pickup→delivered, two ledger events.
 *   (6)  Order status NOT advanced (documented pilot limitation).
 *   (7)  Counter update on courier_accounts after run.
 *   (8)  Mode B: gateway never called for delivery creation.
 *   (9)  /bosta/sync and /bosta/sync/status require OWNER role.
 *   (10) Page cap is respected.
 *   (11) 404 delivery (fetchDelivery returns null) is skipped gracefully.
 *   (12) Connect endpoint triggers backfill job.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaBackfillTest {

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
        // Disable inter-fetch delay so tests run fast
        r.add("bosta.backfill.inter-fetch-delay-ms", () -> "0");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate    rest;
    @Autowired JdbcTemplate        jdbc;
    @Autowired JwtService          jwtService;
    @Autowired BostaBackfillJob    backfillJob;
    @Autowired BostaWebhookJob     webhookJob;
    @Autowired EncryptionService   encryptionService;
    @Autowired ObjectMapper        mapper;
    @MockBean  BostaGateway        bostaGateway;
    @MockBean  JobScheduler        jobScheduler;

    private String ownerToken;
    private UUID   ownerTenantId;
    private UUID   storeId;
    private UUID   variantId;

    @BeforeAll
    void setupOwner() {
        SignupRequest req = new SignupRequest(
            "Backfill Co", "bf_owner", "backfill@test.com", "password99", true);
        ResponseEntity<TokenResponse> resp = rest.postForEntity(
            base() + "/api/v1/auth/signup", req, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ownerToken    = resp.getBody().accessToken();
        ownerTenantId = UUID.fromString(
            (String) jwtService.verify(ownerToken).getClaim("tenant"));

        storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'backfill-test.myshopify.com', 'disconnected')",
            storeId, ownerTenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
            "VALUES (?, ?, ?, 'PROD-BF', 'Backfill Product')",
            productId, ownerTenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
            "VALUES (?, ?, ?, 'VAR-BF', 'Backfill Variant')",
            variantId, ownerTenantId, productId);
    }

    @BeforeEach
    void cleanUp() {
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

    // ── (1) Backfill routes through the webhook pipeline ──────────────────────

    @Test
    void backfill_routesThroughWebhookPipeline_sameOutcomeAsLiveWebhook() {
        String tracking  = "BOS-BF-PIPELINE";
        String extId     = "EXT-BF-PIPELINE";
        String updatedAt = "2026-06-23T14:00:00.000Z";

        setupCourierAccount("bf-api-key-1");
        UUID orderId     = createOrder(extId);
        UUID orderItemId = createOrderItem(orderId);
        String piece     = createPiece("packed");
        createAllocation(orderItemId, piece, "packed");

        when(bostaGateway.listDeliveriesPage(eq("bf-api-key-1"), eq(1), anyInt()))
            .thenReturn(List.of(new BostaGateway.SlimDelivery(tracking, 45, "SEND")));
        when(bostaGateway.listDeliveriesPage(eq("bf-api-key-1"), eq(2), anyInt()))
            .thenReturn(List.of());
        when(bostaGateway.fetchDelivery(eq("bf-api-key-1"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 45, "SEND", 1, extId, null, rawWithUpdatedAt(updatedAt)));

        backfillJob.run(ownerTenantId, 5);

        Long eventId = webhookEventId(tracking);
        assertThat(eventId).as("webhook_events row must be inserted by backfill").isNotNull();

        String source = jdbc.queryForObject(
            "SELECT source FROM webhook_events WHERE id = ?", String.class, eventId);
        assertThat(source).isEqualTo("bosta_backfill");

        webhookJob.process(eventId, ownerTenantId);

        assertThat(shipmentExists(tracking)).isTrue();
        assertThat(pieceStatus(piece)).isEqualTo("delivered");
        assertThat(webhookStatus(eventId)).isEqualTo("processed");
    }

    // ── (2) Idempotency: backfill + same-state-and-timestamp webhook dedup ────

    @Test
    void backfill_thenSameStateWebhook_deduplicates() {
        String tracking  = "BOS-BF-IDEM-A";
        String updatedAt = "2026-06-23T14:00:00.000Z";

        setupCourierAccount("bf-api-key-2");
        ObjectNode raw = rawWithUpdatedAt(updatedAt);
        when(bostaGateway.listDeliveriesPage(eq("bf-api-key-2"), eq(1), anyInt()))
            .thenReturn(List.of(new BostaGateway.SlimDelivery(tracking, 45, "SEND")));
        when(bostaGateway.listDeliveriesPage(eq("bf-api-key-2"), eq(2), anyInt()))
            .thenReturn(List.of());
        when(bostaGateway.fetchDelivery(eq("bf-api-key-2"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 45, "SEND", 1, "ORD-IDEM-A", null, raw));

        // Run backfill and process the event
        backfillJob.run(ownerTenantId, 5);
        Long backfillEventId = webhookEventId(tracking);
        webhookJob.process(backfillEventId, ownerTenantId);
        assertThat(webhookStatus(backfillEventId)).isEqualTo("processed");

        // Same tracking+state+updatedAt → same idem key → duplicate
        String livePayload = String.format(
            "{\"trackingNumber\":\"%s\",\"state\":45,\"updatedAt\":\"%s\"}", tracking, updatedAt);
        Long liveEventId = jdbc.queryForObject(
            "INSERT INTO webhook_events(source,tenant_id,topic,payload,status,received_at) " +
            "VALUES('bosta',?,'delivery_update',?::jsonb,'pending',now()) RETURNING id",
            Long.class, ownerTenantId, livePayload);

        webhookJob.process(liveEventId, ownerTenantId);

        assertThat(webhookStatus(liveEventId)).isEqualTo("processed");
        String liveError = jdbc.queryForObject(
            "SELECT error FROM webhook_events WHERE id = ?", String.class, liveEventId);
        assertThat(liveError).as("live webhook must be flagged as duplicate").contains("duplicate");
    }

    // ── (3) Idempotency: run backfill twice ───────────────────────────────────

    @Test
    void backfill_runTwice_secondRunEventsAreDeduped() {
        String tracking  = "BOS-BF-TWICE";
        String updatedAt = "2026-06-23T12:00:00.000Z";

        setupCourierAccount("bf-api-key-3");
        ObjectNode raw = rawWithUpdatedAt(updatedAt);
        when(bostaGateway.listDeliveriesPage(eq("bf-api-key-3"), eq(1), anyInt()))
            .thenReturn(List.of(new BostaGateway.SlimDelivery(tracking, 45, "SEND")));
        when(bostaGateway.listDeliveriesPage(eq("bf-api-key-3"), eq(2), anyInt()))
            .thenReturn(List.of());
        when(bostaGateway.fetchDelivery(eq("bf-api-key-3"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 45, "SEND", 1, "ORD-TWICE", null, raw));

        // First run
        backfillJob.run(ownerTenantId, 5);
        Long firstEventId = webhookEventId(tracking);
        webhookJob.process(firstEventId, ownerTenantId);
        assertThat(webhookStatus(firstEventId)).isEqualTo("processed");

        // Second run — same delivery, same state, same updatedAt → same idem key
        backfillJob.run(ownerTenantId, 5);
        List<Long> allEventIds = allWebhookEventIds(tracking);
        assertThat(allEventIds).hasSize(2);

        Long secondEventId = allEventIds.stream()
            .filter(id -> !id.equals(firstEventId)).findFirst().orElseThrow();
        webhookJob.process(secondEventId, ownerTenantId);
        assertThat(webhookStatus(secondEventId)).isEqualTo("processed");

        String secondError = jdbc.queryForObject(
            "SELECT error FROM webhook_events WHERE id = ?", String.class, secondEventId);
        assertThat(secondError).as("second-run event must be flagged as duplicate").contains("duplicate");
    }

    // ── (4) State change after backfill → new idem key → processed normally ───

    @Test
    void backfill_thenStateChange_processedNormally() {
        String tracking    = "BOS-BF-STATE-CHANGE";
        String updatedAtV1 = "2026-06-23T10:00:00.000Z";
        String updatedAtV2 = "2026-06-23T14:00:00.000Z";

        setupCourierAccount("bf-api-key-4");
        UUID orderId     = createOrder("EXT-BF-SC");
        UUID orderItemId = createOrderItem(orderId);
        String piece     = createPiece("packed");
        createAllocation(orderItemId, piece, "packed");

        // Phase 1: backfill at state=41 SEND (out for delivery)
        when(bostaGateway.listDeliveriesPage(eq("bf-api-key-4"), eq(1), anyInt()))
            .thenReturn(List.of(new BostaGateway.SlimDelivery(tracking, 41, "SEND")));
        when(bostaGateway.listDeliveriesPage(eq("bf-api-key-4"), eq(2), anyInt()))
            .thenReturn(List.of());
        when(bostaGateway.fetchDelivery(eq("bf-api-key-4"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 41, "SEND", 1, "EXT-BF-SC", null, rawWithUpdatedAt(updatedAtV1)));

        backfillJob.run(ownerTenantId, 5);
        Long eventId41 = webhookEventId(tracking);
        webhookJob.process(eventId41, ownerTenantId);
        assertThat(webhookStatus(eventId41)).isEqualTo("processed");

        // State=41 SEND: piece_status_after is null (no courier_update transition).
        // BUT tryMatchDelivery ran (no pre-existing shipment) and called transitionPackedPieces
        // which moves piece packed → awaiting_pickup with event_type='tracking_linked'.
        assertThat(pieceStatus(piece)).isEqualTo("awaiting_pickup");

        // Phase 2: live webhook at state=45 with a different updatedAt → new idem key
        when(bostaGateway.fetchDelivery(eq("bf-api-key-4"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 45, "SEND", 2, "EXT-BF-SC", null, rawWithUpdatedAt(updatedAtV2)));

        String livePayload = String.format(
            "{\"trackingNumber\":\"%s\",\"state\":45,\"updatedAt\":\"%s\"}", tracking, updatedAtV2);
        Long liveEventId = jdbc.queryForObject(
            "INSERT INTO webhook_events(source,tenant_id,topic,payload,status,received_at) " +
            "VALUES('bosta',?,'delivery_update',?::jsonb,'pending',now()) RETURNING id",
            Long.class, ownerTenantId, livePayload);
        webhookJob.process(liveEventId, ownerTenantId);

        // Different (state=45, updatedAt=V2) → different idem key → processed, not duplicate
        assertThat(webhookStatus(liveEventId)).isEqualTo("processed");
        String error = jdbc.queryForObject(
            "SELECT error FROM webhook_events WHERE id = ?", String.class, liveEventId);
        assertThat(error == null || !error.contains("duplicate"))
            .as("state-change event must NOT be flagged as duplicate")
            .isTrue();
        assertThat(pieceStatus(piece)).isEqualTo("delivered");
    }

    // ── (5) Mid-lifecycle: backfill a delivery already at state=45 ────────────

    @Test
    void backfill_alreadyDelivered_shipmentDeliveredPiecesTransitionWithTwoLedgerEvents() {
        String tracking  = "BOS-BF-DELIVERED";
        String extId     = "EXT-BF-DLVRD";
        String updatedAt = "2026-06-23T14:00:00.000Z";

        setupCourierAccount("bf-api-key-5");
        UUID orderId     = createOrder(extId);
        UUID orderItemId = createOrderItem(orderId);
        String piece     = createPiece("packed");
        createAllocation(orderItemId, piece, "packed");

        when(bostaGateway.listDeliveriesPage(eq("bf-api-key-5"), eq(1), anyInt()))
            .thenReturn(List.of(new BostaGateway.SlimDelivery(tracking, 45, "SEND")));
        when(bostaGateway.listDeliveriesPage(eq("bf-api-key-5"), eq(2), anyInt()))
            .thenReturn(List.of());
        when(bostaGateway.fetchDelivery(eq("bf-api-key-5"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 45, "SEND", 1, extId, null, rawWithUpdatedAt(updatedAt)));

        backfillJob.run(ownerTenantId, 5);
        Long eventId = webhookEventId(tracking);
        webhookJob.process(eventId, ownerTenantId);

        // Shipment must be created and end at 'delivered'
        String shipmentState = jdbc.queryForObject(
            "SELECT internal_state FROM shipments WHERE tracking_number = ?",
            String.class, tracking);
        assertThat(shipmentState).isEqualTo("delivered");

        // Piece transitions:
        //   packed → awaiting_pickup  (tracking_linked, via tryMatchDelivery/transitionPackedPieces)
        //   awaiting_pickup → delivered (courier_update, via webhookJob step 10)
        assertThat(pieceStatus(piece)).isEqualTo("delivered");

        int trackingLinkedCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'tracking_linked'",
            Integer.class, piece);
        assertThat(trackingLinkedCount)
            .as("one tracking_linked event for packed→awaiting_pickup")
            .isEqualTo(1);

        int courierUpdateCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'courier_update'",
            Integer.class, piece);
        assertThat(courierUpdateCount)
            .as("one courier_update event for awaiting_pickup→delivered")
            .isEqualTo(1);

        assertThat(webhookStatus(eventId)).isEqualTo("processed");
    }

    // ── (6) Order status NOT advanced (documented pilot limitation) ───────────

    @Test
    void backfill_orderStatusNotAdvanced() {
        String tracking  = "BOS-BF-ORDER-STATUS";
        String extId     = "EXT-BF-ORDSTS";
        String updatedAt = "2026-06-23T14:00:00.000Z";

        setupCourierAccount("bf-api-key-6");
        UUID orderId     = createOrder(extId);
        UUID orderItemId = createOrderItem(orderId);
        String piece     = createPiece("packed");
        createAllocation(orderItemId, piece, "packed");

        when(bostaGateway.listDeliveriesPage(eq("bf-api-key-6"), eq(1), anyInt()))
            .thenReturn(List.of(new BostaGateway.SlimDelivery(tracking, 45, "SEND")));
        when(bostaGateway.listDeliveriesPage(eq("bf-api-key-6"), eq(2), anyInt()))
            .thenReturn(List.of());
        when(bostaGateway.fetchDelivery(eq("bf-api-key-6"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 45, "SEND", 1, extId, null, rawWithUpdatedAt(updatedAt)));

        backfillJob.run(ownerTenantId, 5);
        Long eventId = webhookEventId(tracking);
        webhookJob.process(eventId, ownerTenantId);

        // Shipment and pieces correctly updated
        assertThat(shipmentExists(tracking)).isTrue();
        assertThat(pieceStatus(piece)).isEqualTo("delivered");

        // orders.status NOT updated — documented pilot limitation consistent with
        // the live webhook flow (which also does not write to orders.status)
        String orderStatus = jdbc.queryForObject(
            "SELECT status FROM orders WHERE id = ?", String.class, orderId);
        assertThat(orderStatus).isNotEqualTo("delivered");
    }

    // ── (7) Counter update on courier_accounts ────────────────────────────────

    @Test
    void backfill_updatesCountersOnCourierAccounts() {
        setupCourierAccount("bf-api-key-7");
        ObjectNode emptyRaw = mapper.createObjectNode();

        when(bostaGateway.listDeliveriesPage(eq("bf-api-key-7"), eq(1), anyInt()))
            .thenReturn(List.of(
                new BostaGateway.SlimDelivery("BOS-CNT-1", 45, "SEND"),
                new BostaGateway.SlimDelivery("BOS-CNT-2", 41, "SEND")));
        when(bostaGateway.listDeliveriesPage(eq("bf-api-key-7"), eq(2), anyInt()))
            .thenReturn(List.of());
        when(bostaGateway.fetchDelivery(anyString(), eq("BOS-CNT-1")))
            .thenReturn(new BostaDelivery("BOS-CNT-1", 45, "SEND", 1, "REF-1", null, emptyRaw));
        when(bostaGateway.fetchDelivery(anyString(), eq("BOS-CNT-2")))
            .thenReturn(new BostaDelivery("BOS-CNT-2", 41, "SEND", 0, "REF-2", null, emptyRaw));

        backfillJob.run(ownerTenantId, 5);

        Integer total = jdbc.queryForObject(
            "SELECT last_backfill_total FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta'",
            Integer.class, ownerTenantId);
        Integer enqueued = jdbc.queryForObject(
            "SELECT last_backfill_enqueued FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta'",
            Integer.class, ownerTenantId);
        Boolean lastBackfillAtSet = jdbc.queryForObject(
            "SELECT last_backfill_at IS NOT NULL FROM courier_accounts " +
            "WHERE tenant_id = ? AND provider = 'bosta'",
            Boolean.class, ownerTenantId);

        assertThat(total).isEqualTo(2);
        assertThat(enqueued).isEqualTo(2);
        assertThat(lastBackfillAtSet).isTrue();
    }

    // ── (8) Mode B: gateway never called for delivery creation ────────────────

    @Test
    void backfill_modeB_onlyListAndFetchCalled_noWriteEndpoints() {
        setupCourierAccount("bf-api-key-8");

        when(bostaGateway.listDeliveriesPage(anyString(), eq(1), anyInt()))
            .thenReturn(List.of(new BostaGateway.SlimDelivery("BOS-MODEB", 45, "SEND")));
        when(bostaGateway.listDeliveriesPage(anyString(), eq(2), anyInt()))
            .thenReturn(List.of());
        when(bostaGateway.fetchDelivery(anyString(), eq("BOS-MODEB")))
            .thenReturn(new BostaDelivery("BOS-MODEB", 45, "SEND", 1, "REF-MB", null, mapper.createObjectNode()));

        backfillJob.run(ownerTenantId, 5);

        verify(bostaGateway, atLeastOnce()).listDeliveriesPage(anyString(), anyInt(), anyInt());
        verify(bostaGateway, atLeastOnce()).fetchDelivery(anyString(), eq("BOS-MODEB"));
        // Delivery-write endpoints must NOT be called
        verify(bostaGateway, never()).printMassAwb(anyString(), any(), anyString(), anyString());
        verify(bostaGateway, never()).createPickup(anyString(), anyString(), anyString(), any(), anyInt());
        // fetchBusinessProfile is a connect-only call, must not appear during backfill
        verify(bostaGateway, never()).fetchBusinessProfile(anyString());
    }

    // ── (9a) /bosta/sync → 202 for OWNER ─────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void sync_ownerRole_returns202WithMessage() {
        setupCourierAccount("bf-api-key-9");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(ownerToken); h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/bosta/sync", HttpMethod.POST,
            new HttpEntity<>(Map.of(), h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).containsKey("message");
    }

    // ── (9b) /bosta/sync → 403 for non-OWNER ─────────────────────────────────

    @Test
    void sync_nonOwner_returns403() {
        String mgEmail = "bf-mgr-" + UUID.randomUUID() + "@test.com";
        String mgToken  = createManagerAndLogin(mgEmail, "mgr-password");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(mgToken); h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = rest.exchange(
            base() + "/api/v1/bosta/sync", HttpMethod.POST,
            new HttpEntity<>(Map.of(), h), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── (9c) /bosta/sync/status → 403 for non-OWNER ──────────────────────────

    @Test
    void syncStatus_nonOwner_returns403() {
        String mgEmail = "bf-mgr2-" + UUID.randomUUID() + "@test.com";
        String mgToken  = createManagerAndLogin(mgEmail, "mgr-password2");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(mgToken);
        ResponseEntity<String> resp = rest.exchange(
            base() + "/api/v1/bosta/sync/status", HttpMethod.GET,
            new HttpEntity<>(h), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── (9d) /bosta/sync/status → 200 with counter keys for OWNER ────────────

    @Test
    @SuppressWarnings("unchecked")
    void syncStatus_owner_returnsCounterKeys() {
        setupCourierAccount("bf-api-key-10");

        HttpHeaders h = new HttpHeaders(); h.setBearerAuth(ownerToken);
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/bosta/sync/status", HttpMethod.GET,
            new HttpEntity<>(h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKeys(
            "lastBackfillAt", "lastBackfillTotal", "lastBackfillEnqueued");
    }

    // ── (10) Page cap is respected ────────────────────────────────────────────

    @Test
    void backfill_pageCap_stopsAtMaxPages() {
        setupCourierAccount("bf-api-key-11");

        // Every page returns a non-empty list — would run forever without the cap
        when(bostaGateway.listDeliveriesPage(anyString(), anyInt(), anyInt()))
            .thenReturn(List.of(new BostaGateway.SlimDelivery("BOS-PAGED", 45, "SEND")));
        when(bostaGateway.fetchDelivery(anyString(), anyString()))
            .thenReturn(new BostaDelivery("BOS-PAGED", 45, "SEND", 1, "REF-P", null, mapper.createObjectNode()));

        backfillJob.run(ownerTenantId, 3);  // maxPages=3

        // listDeliveriesPage must be called exactly 3 times (pages 1, 2, 3)
        verify(bostaGateway, times(3)).listDeliveriesPage(anyString(), anyInt(), anyInt());
    }

    // ── (11) 404 delivery is skipped gracefully ───────────────────────────────

    @Test
    void backfill_fetchReturnsNull_deliverySkipped_countersReflectSeen() {
        setupCourierAccount("bf-api-key-12");

        when(bostaGateway.listDeliveriesPage(eq("bf-api-key-12"), eq(1), anyInt()))
            .thenReturn(List.of(new BostaGateway.SlimDelivery("BOS-404", 45, "SEND")));
        when(bostaGateway.listDeliveriesPage(eq("bf-api-key-12"), eq(2), anyInt()))
            .thenReturn(List.of());
        when(bostaGateway.fetchDelivery(eq("bf-api-key-12"), eq("BOS-404")))
            .thenReturn(null);  // 404

        backfillJob.run(ownerTenantId, 5);

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM webhook_events WHERE source = 'bosta_backfill'",
            Integer.class);
        assertThat(count).as("no webhook_events inserted for a 404 delivery").isZero();

        // 1 seen (in the list), 0 enqueued (fetch returned null)
        Integer total    = jdbc.queryForObject(
            "SELECT last_backfill_total FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta'",
            Integer.class, ownerTenantId);
        Integer enqueued = jdbc.queryForObject(
            "SELECT last_backfill_enqueued FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta'",
            Integer.class, ownerTenantId);
        assertThat(total).isEqualTo(1);
        assertThat(enqueued).isZero();
    }

    // ── (12) Connect endpoint triggers backfill job ───────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void connect_triggersBackfillJob() {
        when(bostaGateway.fetchBusinessProfile(anyString())).thenReturn("BF Business");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(ownerToken); h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/bosta/connect", HttpMethod.POST,
            new HttpEntity<>(Map.of("apiKey", "bf-connect-key"), h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // JobScheduler.enqueue() must have been called at least once (for the backfill job)
        verify(jobScheduler, atLeastOnce()).enqueue(any(JobLambda.class));
    }

    // ── (13) Match precedence: strong businessRef not vetoed by ambiguous phone+COD ──

    /**
     * Verifies that a confident businessReference match is definitive:
     * even when multiple orders share the same phone+COD (which would
     * produce AMBIGUOUS_MULTI if phone+COD ran), the delivery is matched
     * to the correct order and no unlinked row is created.
     *
     * Setup:
     *   Order A — number='#PREC-STRONG-001', phone='+201555444333', cod=500 (target)
     *   Order B — different number, same phone+COD as A              (decoy)
     *   Delivery — businessRef='#PREC-STRONG-001', phone='+201555444333', cod=500
     *
     * If phone+COD ran, it would see both A and B → AMBIGUOUS_MULTI → unlinked.
     * Since businessRef matches A first, phone+COD is never consulted.
     */
    @Test
    void matchPrecedence_strongBusinessRef_notVetoedByAmbiguousPhoneCod() {
        String tracking  = "BOS-PREC-001";
        String updatedAt = "2026-07-05T10:00:00.000Z";
        String ref       = "#PREC-STRONG-001";
        String phone     = "+201555444333";
        int    cod       = 500;

        setupCourierAccount("prec-api-key");

        // Target order — matched by businessReference
        UUID orderA = jdbc.queryForObject(
            "INSERT INTO orders " +
            "  (tenant_id, store_id, external_id, number, customer_phone, cod_amount, status) " +
            "VALUES (?, ?, 'EXT-PREC-A', ?, ?, ?, 'new'::order_status) RETURNING id",
            UUID.class, ownerTenantId, storeId, ref, phone, cod);

        // Decoy order — same phone+COD as the delivery; would cause AMBIGUOUS_MULTI if phone+COD ran
        jdbc.update(
            "INSERT INTO orders " +
            "  (tenant_id, store_id, external_id, number, customer_phone, cod_amount, status) " +
            "VALUES (?, ?, 'EXT-PREC-B', '#PREC-DECOY-001', ?, ?, 'new'::order_status)",
            ownerTenantId, storeId, phone, cod);

        // Build raw JSON: updatedAt for backfill, plus cod + receiver.phone for phone+COD path
        // (the phone+COD path is never reached — these fields prove it wouldn't matter if it were)
        ObjectNode raw = mapper.createObjectNode();
        raw.put("updatedAt", updatedAt);
        raw.put("cod", cod);
        raw.putObject("receiver").put("phone", phone);

        when(bostaGateway.listDeliveriesPage(eq("prec-api-key"), eq(1), anyInt()))
            .thenReturn(List.of(new BostaGateway.SlimDelivery(tracking, 45, "SEND")));
        when(bostaGateway.listDeliveriesPage(eq("prec-api-key"), eq(2), anyInt()))
            .thenReturn(List.of());
        when(bostaGateway.fetchDelivery(eq("prec-api-key"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 45, "SEND", 1, ref, null, raw));

        backfillJob.run(ownerTenantId, 2);

        Long eventId = webhookEventId(tracking);
        assertThat(eventId).as("webhook_event must be inserted by backfill").isNotNull();

        webhookJob.process(eventId, ownerTenantId);

        // businessRef matched → shipment created
        assertThat(shipmentExists(tracking))
            .as("shipment must be created — businessRef match is definitive").isTrue();

        // no unlinked row — delivery was matched, not routed to manual resolution
        Integer unlinkedCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE tracking_number = ?",
            Integer.class, tracking);
        assertThat(unlinkedCount)
            .as("no unlinked_bosta_deliveries row — delivery matched via strong key").isZero();

        // shipment is linked to order A specifically, not the decoy
        UUID linkedOrderId = jdbc.queryForObject(
            "SELECT order_id FROM shipments WHERE tracking_number = ?",
            UUID.class, tracking);
        assertThat(linkedOrderId)
            .as("shipment must be linked to order A (businessRef target), not the decoy")
            .isEqualTo(orderA);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String base() { return "http://localhost:" + port; }

    private void setupCourierAccount(String rawApiKey) {
        jdbc.update(
            "INSERT INTO courier_accounts " +
            "    (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'test-hash', 'active')",
            ownerTenantId, encryptionService.encrypt(rawApiKey));
    }

    private UUID createOrder(String extId) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, status) " +
            "VALUES (?, ?, ?, 'new'::order_status) RETURNING id",
            UUID.class, ownerTenantId, storeId, extId);
    }

    private UUID createOrderItem(UUID orderId) {
        return jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) " +
            "VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, ownerTenantId, orderId, variantId);
    }

    private String createPiece(String status) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, status) " +
            "VALUES (?, ?, ?, ?, ?::piece_status)",
            id, ownerTenantId, variantId, "PC-" + id, status);
        return id;
    }

    private void createAllocation(UUID orderItemId, String pieceId, String status) {
        jdbc.update(
            "INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) " +
            "VALUES (?, ?, ?, ?::allocation_status)",
            ownerTenantId, orderItemId, pieceId, status);
    }

    private Long webhookEventId(String trackingNumber) {
        return jdbc.query(
            "SELECT id FROM webhook_events " +
            "WHERE payload->>'trackingNumber' = ? AND source = 'bosta_backfill' " +
            "ORDER BY id DESC LIMIT 1",
            rs -> rs.next() ? rs.getLong("id") : null,
            trackingNumber);
    }

    private List<Long> allWebhookEventIds(String trackingNumber) {
        return jdbc.queryForList(
            "SELECT id FROM webhook_events " +
            "WHERE payload->>'trackingNumber' = ? ORDER BY id",
            Long.class, trackingNumber);
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject(
            "SELECT status FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String webhookStatus(Long id) {
        return jdbc.queryForObject(
            "SELECT status FROM webhook_events WHERE id = ?", String.class, id);
    }

    private boolean shipmentExists(String trackingNumber) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tracking_number = ?",
            Integer.class, trackingNumber);
        return count != null && count > 0;
    }

    private ObjectNode rawWithUpdatedAt(String updatedAt) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("updatedAt", updatedAt);
        return raw;
    }

    /** Creates a manager user via the owner-authenticated POST /users endpoint, then logs in. */
    @SuppressWarnings("unchecked")
    private String createManagerAndLogin(String email, String password) {
        HttpHeaders oh = new HttpHeaders();
        oh.setBearerAuth(ownerToken); oh.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<?> createResp = rest.exchange(
            base() + "/api/v1/users", HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "name", "Mgr", "email", email, "role", "manager", "password", password), oh),
            Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        HttpHeaders lh = new HttpHeaders(); lh.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> loginResp = rest.postForEntity(
            base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", password), lh),
            AccessTokenResponse.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return loginResp.getBody().accessToken();
    }
}
