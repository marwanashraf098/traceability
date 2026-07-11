package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * FR-13: version-stamped dedup — Guard-3-safe retry after matching-logic deploys.
 *
 * Tests the interaction between step-4 in BostaWebhookJob and Guard-3 in
 * BostaIngestionHelper, proving the "exactly one retry per matcher-version bump"
 * guarantee without creating a re-processing loop.
 *
 *   idem1 — linked outcome at any version blocks all future events (always dedup)
 *   idem2 — unlinked at CURRENT version blocks re-process (steady state)
 *   idem3 — unlinked at NULL/old version allows ONE retry (logic-deploy path)
 *   idem4 — Guard 3 blocks re-enqueue at current version and same state (no loop)
 *   idem5 — Guard 3 passes at NULL version, same state (legacy row → retry eligible)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeliveryIdempotencyTest {

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
        r.add("bosta.poll.inter-fetch-delay-ms", () -> "0");
    }

    @Autowired JdbcTemplate         jdbc;
    @Autowired ObjectMapper          mapper;
    @Autowired EncryptionService     encryptionService;
    @Autowired BostaWebhookJob       webhookJob;
    @Autowired BostaIngestionHelper  ingestionHelper;
    @Autowired MatcherVersionHolder  matcherVersionHolder;
    @MockBean  BostaGateway          bostaGateway;
    @MockBean  JobScheduler          jobScheduler;

    private UUID tenantId;
    private UUID storeId;

    @BeforeAll
    void setupFixtures() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'IdemTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'idem@test.local', 'h', 'owner')", userId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'idem.myshopify.com', 'disconnected')", storeId, tenantId);
    }

    @BeforeEach
    void cleanup() {
        jdbc.execute("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = '" + tenantId + "'");
        jdbc.execute("DELETE FROM shipments WHERE tenant_id = '" + tenantId + "'");
        jdbc.execute("DELETE FROM orders WHERE tenant_id = '" + tenantId + "'");
        jdbc.execute("DELETE FROM webhook_events WHERE tenant_id = '" + tenantId + "'");
        jdbc.execute("DELETE FROM courier_accounts WHERE tenant_id = '" + tenantId + "'");
        reset(bostaGateway);
        TenantContext.set(tenantId);
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    // ── idem1: linked outcome at any version always blocks re-process ──────────────────

    /**
     * Step-4 EXISTS predicate: linked outcome (error IS NULL) blocks regardless of
     * matcher_version. A NULL-versioned linked event still dedup-blocks event 2.
     * fetchDelivery must NOT be called (step 6 never reached).
     */
    @Test
    @Order(1)
    void idem1_linkedOutcome_anyVersion_alwaysBlocks() {
        String tracking  = "IDEM1";
        String updatedAt = "idem1-epoch";
        String idemKey   = BostaWebhookJob.sha256(tracking + ":22:" + updatedAt);

        // E1: processed as linked (error=NULL), matcher_version=NULL (old/legacy)
        jdbc.update(
            "INSERT INTO webhook_events " +
            "    (source, tenant_id, topic, payload, status, external_event_id, error, matcher_version) " +
            "VALUES ('bosta_poll'::webhook_source, ?, 'delivery_update', '{}'::jsonb, " +
            "        'processed', ?, NULL, NULL)",
            tenantId, idemKey);

        // E2: pending, same payload (will compute same idemKey)
        String payload = buildPayload(tracking, 22, updatedAt);
        long e2 = jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status) " +
            "VALUES ('bosta_poll'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending') RETURNING id",
            Long.class, tenantId, payload);

        webhookJob.process(e2, tenantId);

        // Step 4 blocked before step 6 — fetchDelivery not called
        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());

        String error = jdbc.queryForObject(
            "SELECT error FROM webhook_events WHERE id = ?", String.class, e2);
        assertThat(error).as("E2 must be marked duplicate").contains("duplicate");
    }

    // ── idem2: unlinked at current version blocks re-process (steady state) ──────────

    /**
     * Step-4: unlinked outcome with matcher_version = current → already retried
     * with this exact logic → block. This is the Guard-3 backstop at the job level:
     * even if Guard 3 didn't catch it, step 4 prevents a loop.
     */
    @Test
    @Order(2)
    void idem2_unlinkedAtCurrentVersion_blocks() {
        String tracking  = "IDEM2";
        String updatedAt = "idem2-epoch";
        String idemKey   = BostaWebhookJob.sha256(tracking + ":22:" + updatedAt);
        String current   = matcherVersionHolder.get();

        // E1: processed as unlinked, current version
        jdbc.update(
            "INSERT INTO webhook_events " +
            "    (source, tenant_id, topic, payload, status, external_event_id, error, matcher_version) " +
            "VALUES ('bosta_poll'::webhook_source, ?, 'delivery_update', '{}'::jsonb, " +
            "        'processed', ?, ?, ?)",
            tenantId, idemKey, "unlinked:" + tracking, current);

        String payload = buildPayload(tracking, 22, updatedAt);
        long e2 = jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status) " +
            "VALUES ('bosta_poll'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending') RETURNING id",
            Long.class, tenantId, payload);

        webhookJob.process(e2, tenantId);

        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());

        String error = jdbc.queryForObject(
            "SELECT error FROM webhook_events WHERE id = ?", String.class, e2);
        assertThat(error).as("E2 must be deduplicated (same version unlinked)").contains("duplicate");
    }

    // ── idem3: unlinked at NULL/old version allows retry (logic-deploy path) ─────────

    /**
     * Step-4: unlinked outcome with matcher_version IS NULL (pre-V44 legacy row).
     * NULL = current evaluates to NULL (not true) → NOT included in EXISTS → alreadyDone=false.
     * The job proceeds past step 4. fetchDelivery IS called (proving step 6 reached).
     *
     * This is the exact 9730639058 recovery scenario: a prior event at old/null version
     * should not permanently block re-processing after a V44 deploy.
     */
    @Test
    @Order(3)
    void idem3_unlinkedAtNullVersion_allowsRetry() {
        String tracking  = "IDEM3";
        String updatedAt = "idem3-epoch";
        String idemKey   = BostaWebhookJob.sha256(tracking + ":22:" + updatedAt);

        // E1: processed as unlinked, matcher_version IS NULL (pre-V44 legacy)
        jdbc.update(
            "INSERT INTO webhook_events " +
            "    (source, tenant_id, topic, payload, status, external_event_id, error, matcher_version) " +
            "VALUES ('bosta_poll'::webhook_source, ?, 'delivery_update', '{}'::jsonb, " +
            "        'processed', ?, ?, NULL)",
            tenantId, idemKey, "unlinked:" + tracking);

        // Set up courier account and shipment so the job can complete past step 4
        String rawKey = "idem3-key";
        jdbc.update("INSERT INTO courier_accounts " +
                    "(tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (?, 'bosta', ?, 'h', 'active')",
                    tenantId, encryptionService.encrypt(rawKey));
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, status) " +
            "VALUES (?, ?, 'EXT-IDEM3', 'new'::order_status) RETURNING id",
            UUID.class, tenantId, storeId);
        jdbc.update("INSERT INTO shipments " +
                    "(tenant_id, order_id, provider, tracking_number, internal_state) " +
                    "VALUES (?, ?, 'bosta', ?, 'with_courier'::shipment_internal_state)",
                    tenantId, orderId, tracking);

        ObjectNode raw = mapper.createObjectNode();
        raw.put("updatedAt", updatedAt);
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 22, "SEND", 0, null, null, raw));

        String payload = buildPayload(tracking, 22, updatedAt);
        long e2 = jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status) " +
            "VALUES ('bosta_poll'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending') RETURNING id",
            Long.class, tenantId, payload);

        webhookJob.process(e2, tenantId);

        // Step 4 allowed retry → step 6 ran → fetchDelivery was called.
        // (If step 4 had short-circuited, step 6 would never be reached.)
        verify(bostaGateway, atLeastOnce()).fetchDelivery(anyString(), eq(tracking));

        // E2's error must NOT be "already processed" (the step-4 markDuplicate note).
        // "concurrent duplicate" from step 11 is an acceptable outcome: it means step 4
        // passed (retry was allowed), but step 11 could not claim the idemKey because E1
        // already held it. Both E1 and E2 end up processed — retry semantics are correct.
        String error = jdbc.queryForObject(
            "SELECT error FROM webhook_events WHERE id = ?", String.class, e2);
        assertThat(error)
            .as("E2 must not be step-4-deduplicated ('already processed') — retry was allowed")
            .doesNotContain("already processed");
    }

    // ── idem4: Guard 3 blocks at current version and same state (no loop) ────────────

    /**
     * Guard 3 in BostaIngestionHelper: blocks re-enqueue when an unlinked row exists
     * at the same state AND the current matcher version. This is the "steady state"
     * gate — prevents a retry loop on every poll cycle after an unlinked result.
     *
     * Explicit assertion: ingestDelivery() returns false; no new webhook_events row.
     */
    @Test
    @Order(4)
    void idem4_guard3_currentVersion_sameState_blocksReenqueue() throws Exception {
        String tracking = "IDEM4";
        String rawKey   = "idem4-key";
        String current  = matcherVersionHolder.get();

        jdbc.update("INSERT INTO courier_accounts " +
                    "(tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (?, 'bosta', ?, 'h', 'active')",
                    tenantId, encryptionService.encrypt(rawKey));

        // Unlinked row at state=22, matcher_version = current
        jdbc.update(
            "INSERT INTO unlinked_bosta_deliveries " +
            "    (tenant_id, tracking_number, bosta_state_code, bosta_order_type, match_reason, matcher_version) " +
            "VALUES (?, ?, 22, 'SEND', 'NO_MATCH', ?)",
            tenantId, tracking, current);

        ObjectNode raw = mapper.createObjectNode();
        raw.put("updatedAt", "idem4-epoch");
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 22, "SEND", 0, null, null, raw));

        boolean result = TenantContext.runAs(tenantId,
            () -> ingestionHelper.ingestDelivery(tenantId, rawKey, tracking, "bosta_poll_discovery"));

        // Guard 3 fired: current-version unlinked at same state → skip
        assertThat(result)
            .as("Guard 3 must block re-enqueue for current-version unlinked at same state")
            .isFalse();

        Long eventCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ?",
            Long.class, tenantId, tracking);
        assertThat(eventCount)
            .as("no webhook_events row must be created when Guard 3 fires")
            .isZero();
    }

    // ── idem5: Guard 3 passes at NULL version, same state (legacy row → retry) ────────

    /**
     * Guard 3 (version-aware): an unlinked row with matcher_version IS NULL is a pre-V44
     * legacy row. NULL = current evaluates to NULL (not true) → COUNT = 0 → Guard 3 passes.
     * Explicit: uses IS NULL to document the intent, not relying on 3-valued-logic coincidence.
     *
     * Result: ingestDelivery() returns true (event created) — the legacy delivery retries.
     * After the retry, recordUnlinked() upserts with the current version, and idem4 guards
     * against further retries in the same deploy cycle.
     */
    @Test
    @Order(5)
    void idem5_guard3_nullVersion_sameState_allowsRetry() throws Exception {
        String tracking = "IDEM5";
        String rawKey   = "idem5-key";

        jdbc.update("INSERT INTO courier_accounts " +
                    "(tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (?, 'bosta', ?, 'h', 'active')",
                    tenantId, encryptionService.encrypt(rawKey));

        // Unlinked row at state=22, matcher_version IS NULL (pre-V44 legacy)
        // NULL <> current → Guard 3 predicate (AND matcher_version = ?) is not satisfied
        jdbc.update(
            "INSERT INTO unlinked_bosta_deliveries " +
            "    (tenant_id, tracking_number, bosta_state_code, bosta_order_type, match_reason) " +
            "VALUES (?, ?, 22, 'SEND', 'NO_MATCH')",
            tenantId, tracking);

        // Confirm NULL was stored (explicit — see MatcherVersionHolder javadoc on NULL semantics)
        // param order must match SQL: tracking_number first, tenant_id second
        String storedVersion = jdbc.queryForObject(
            "SELECT matcher_version FROM unlinked_bosta_deliveries WHERE tracking_number = ? AND tenant_id = ?",
            String.class, tracking, tenantId);
        assertThat(storedVersion)
            .as("seeded row must have NULL matcher_version (pre-V44 legacy)")
            .isNull();

        ObjectNode raw = mapper.createObjectNode();
        raw.put("updatedAt", "idem5-epoch");
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 22, "SEND", 0, null, null, raw));

        boolean result = TenantContext.runAs(tenantId,
            () -> ingestionHelper.ingestDelivery(tenantId, rawKey, tracking, "bosta_poll_discovery"));

        // Guard 3 passed: NULL version ≠ current → not blocked → event created
        assertThat(result)
            .as("Guard 3 must pass for NULL-version unlinked row (legacy retry path)")
            .isTrue();

        Long eventCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ?",
            Long.class, tenantId, tracking);
        assertThat(eventCount)
            .as("one webhook_events row must be created when Guard 3 passes for legacy row")
            .isEqualTo(1L);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────

    private String buildPayload(String trackingNumber, int state, String updatedAt) {
        return String.format(
            "{\"trackingNumber\":\"%s\",\"state\":%d,\"type\":\"SEND\",\"updatedAt\":\"%s\"}",
            trackingNumber, state, updatedAt);
    }
}
