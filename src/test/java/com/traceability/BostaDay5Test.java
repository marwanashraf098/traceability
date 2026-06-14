package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.bosta.*;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Day 5 integration tests for Bosta connect + webhook ingestion.
 *
 * BostaGateway and JobScheduler are @MockBean — no real Bosta API calls.
 * BostaWebhookJob is invoked directly as a Java method to test job logic.
 * BostaStateMapper reads from real DB (seeded by V2 migration).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaDay5Test {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired BostaWebhookJob bostaWebhookJob;
    @Autowired BostaStateMapper bostaStateMapper;
    @Autowired EncryptionService encryptionService;
    @Autowired ObjectMapper mapper;
    @MockBean  BostaGateway bostaGateway;
    @MockBean  JobScheduler jobScheduler;

    private String ownerToken;
    private UUID   ownerTenantId;

    @BeforeAll
    void setupOwner() {
        SignupRequest req = new SignupRequest("Bosta Co", "bosta_owner", "bosta@test.com", "password99");
        ResponseEntity<TokenResponse> resp = rest.postForEntity(
                base() + "/api/v1/auth/signup", req, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ownerToken    = resp.getBody().accessToken();
        ownerTenantId = UUID.fromString(
                (String) jwtService.verify(ownerToken).getClaim("tenant"));
    }

    @BeforeEach
    void cleanUp() {
        jdbc.execute("DELETE FROM webhook_events");
        jdbc.execute("DELETE FROM courier_accounts");
    }

    // -------------------------------------------------------------------------
    // (1) Non-owner cannot connect Bosta
    // -------------------------------------------------------------------------
    @Test
    void connect_nonOwner_returns403() {
        UUID mgId = UUID.randomUUID();
        String mgEmail = "bosta-mgr-" + mgId + "@test.com";
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, 'Mgr', ?, ?, 'manager', true)",
            mgId, ownerTenantId, mgEmail, passwordEncoder.encode("mgpass"));

        HttpHeaders lh = new HttpHeaders(); lh.setContentType(MediaType.APPLICATION_JSON);
        String mgToken = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", mgEmail, "password", "mgpass"), lh),
            TokenResponse.class).getBody().accessToken();

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(mgToken); h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = rest.exchange(
            base() + "/api/v1/bosta/connect", HttpMethod.POST,
            new HttpEntity<>(Map.of("apiKey", "test-key"), h), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -------------------------------------------------------------------------
    // (2) Successful connect: API key encrypted, webhook secret returned once
    // -------------------------------------------------------------------------
    @Test
    @SuppressWarnings("unchecked")
    void connect_success_encryptsKeyAndReturnsSecret() {
        when(bostaGateway.fetchBusinessProfile(eq("valid-api-key"))).thenReturn("Test Business");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ownerToken); headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/bosta/connect", HttpMethod.POST,
            new HttpEntity<>(Map.of("apiKey", "valid-api-key"), headers), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String accountId    = (String) resp.getBody().get("accountId");
        String webhookSecret = (String) resp.getBody().get("webhookSecret");
        assertThat(accountId).isNotNull();
        assertThat(webhookSecret).isNotNull().hasSize(64);  // 32 bytes = 64 hex chars

        // API key must be stored encrypted, not in plaintext
        String storedEncrypted = jdbc.queryForObject(
            "SELECT api_key_encrypted FROM courier_accounts WHERE id = ?::uuid", String.class, accountId);
        assertThat(storedEncrypted).isNotEqualTo("valid-api-key");

        // webhook_secret must be stored as SHA-256 hash, not the raw secret
        String storedHash = jdbc.queryForObject(
            "SELECT webhook_secret FROM courier_accounts WHERE id = ?::uuid", String.class, accountId);
        assertThat(storedHash).isNotEqualTo(webhookSecret).hasSize(64);
    }

    // -------------------------------------------------------------------------
    // (3) State mapper: code 41 SEND → with_courier, RTO → returning
    // -------------------------------------------------------------------------
    @Test
    void stateMapper_code41_send_mapsToWithCourier() {
        BostaStateMapper.MappedState m = bostaStateMapper.map(41, "SEND");
        assertThat(m.shipmentInternalState()).isEqualTo("with_courier");
        assertThat(m.isException()).isFalse();
    }

    @Test
    void stateMapper_code41_rto_mapsToReturning() {
        BostaStateMapper.MappedState m = bostaStateMapper.map(41, "RTO");
        assertThat(m.shipmentInternalState()).isEqualTo("returning");
        assertThat(m.pieceStatusAfter()).isEqualTo("return_in_transit");
        assertThat(m.isException()).isFalse();
    }

    @Test
    void stateMapper_unknownCode_returnsExceptionState() {
        BostaStateMapper.MappedState m = bostaStateMapper.map(999, "SEND");
        assertThat(m.isException()).isTrue();
    }

    @Test
    void stateMapper_code45_mapsToDelivered() {
        BostaStateMapper.MappedState m = bostaStateMapper.map(45, "ALL");
        assertThat(m.shipmentInternalState()).isEqualTo("delivered");
        assertThat(m.pieceStatusAfter()).isEqualTo("delivered");
    }

    // -------------------------------------------------------------------------
    // (4) Webhook: unknown secret → 401
    // -------------------------------------------------------------------------
    @Test
    void webhook_unknownSecret_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer unknown-secret-abc123");
        headers.setContentType(MediaType.APPLICATION_JSON);
        ObjectNode payload = mapper.createObjectNode().put("trackingNumber", "BOS-001");
        ResponseEntity<String> resp = rest.exchange(
            base() + "/api/v1/webhooks/bosta", HttpMethod.POST,
            new HttpEntity<>(payload, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -------------------------------------------------------------------------
    // (5) Webhook: valid secret → payload persisted + 200
    // -------------------------------------------------------------------------
    @Test
    void webhook_validSecret_persistsAndReturns200() {
        String rawSecret = setupBostaAccount();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + rawSecret);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ObjectNode payload = mapper.createObjectNode()
            .put("trackingNumber", "BOS-001")
            .put("state", 45)
            .put("updatedAt", "2026-06-14T10:00:00Z");

        ResponseEntity<String> resp = rest.exchange(
            base() + "/api/v1/webhooks/bosta", HttpMethod.POST,
            new HttpEntity<>(payload, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        int count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM webhook_events WHERE source = 'bosta' AND status = 'pending'",
            Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // (6) Webhook job: fetches from Bosta (not just uses payload) — verify-by-fetch proof
    //     Payload says state=41, fetchDelivery returns state=45 (different).
    //     Job must call fetchDelivery and process based on the fetched state 45.
    // -------------------------------------------------------------------------
    @Test
    void webhookJob_actsOnFetchedState_notPayloadState() {
        String encryptedKey = encryptionService.encrypt("bosta-api-key");
        setupBostaAccountWithEncryptedKey(encryptedKey);

        // Insert webhook event — payload says state=41 (out for delivery)
        Long webhookId = jdbc.queryForObject("""
            INSERT INTO webhook_events(source, tenant_id, topic, payload, status, received_at)
            VALUES('bosta', ?, 'delivery_update',
                   '{"trackingNumber":"BOS-FETCH-001","state":41,"updatedAt":"2026-06-14T10:00:00Z"}'::jsonb,
                   'pending', now())
            RETURNING id
            """, Long.class, ownerTenantId);

        // Mock: fetchDelivery returns state=45 (delivered) — DIFFERENT from payload's 41
        BostaDelivery fetched = new BostaDelivery(
            "BOS-FETCH-001", 45, "SEND", 1, "ORD-001", mapper.createObjectNode());
        when(bostaGateway.fetchDelivery(eq("bosta-api-key"), eq("BOS-FETCH-001")))
            .thenReturn(fetched);

        bostaWebhookJob.process(webhookId, ownerTenantId);

        // Verify: fetchDelivery was called with the tracking number from the payload
        verify(bostaGateway).fetchDelivery(eq("bosta-api-key"), eq("BOS-FETCH-001"));

        // Verify: webhook row marked processed
        String status = jdbc.queryForObject(
            "SELECT status FROM webhook_events WHERE id = ?", String.class, webhookId);
        assertThat(status).isEqualTo("processed");

        // Verify: idempotency key is based on payload (state=41, NOT the fetched state=45)
        String extId = jdbc.queryForObject(
            "SELECT external_event_id FROM webhook_events WHERE id = ?", String.class, webhookId);
        String expectedKey = BostaWebhookJob.sha256("BOS-FETCH-001:41:2026-06-14T10:00:00Z");
        assertThat(extId).isEqualTo(expectedKey);
    }

    // -------------------------------------------------------------------------
    // (7) Webhook job: redelivered event marked processed/duplicate (not left pending)
    // -------------------------------------------------------------------------
    @Test
    void webhookJob_redeliveredEvent_markedDuplicate() {
        String encryptedKey = encryptionService.encrypt("bosta-api-key");
        setupBostaAccountWithEncryptedKey(encryptedKey);

        String commonPayload =
            "{\"trackingNumber\":\"BOS-DUP-001\",\"state\":45,\"updatedAt\":\"2026-06-14T12:00:00Z\"}";

        // First delivery
        Long firstId = jdbc.queryForObject(
            "INSERT INTO webhook_events(source,tenant_id,topic,payload,status,received_at) " +
            "VALUES('bosta',?,'delivery_update',?::jsonb,'pending',now()) RETURNING id",
            Long.class, ownerTenantId, commonPayload);

        // Second delivery (redelivery of the same logical event)
        Long secondId = jdbc.queryForObject(
            "INSERT INTO webhook_events(source,tenant_id,topic,payload,status,received_at) " +
            "VALUES('bosta',?,'delivery_update',?::jsonb,'pending',now()) RETURNING id",
            Long.class, ownerTenantId, commonPayload);

        BostaDelivery fetched = new BostaDelivery(
            "BOS-DUP-001", 45, "SEND", 1, "ORD-002", mapper.createObjectNode());
        when(bostaGateway.fetchDelivery(anyString(), eq("BOS-DUP-001"))).thenReturn(fetched);

        // Process first event
        bostaWebhookJob.process(firstId, ownerTenantId);

        // Process second (redelivered) event
        bostaWebhookJob.process(secondId, ownerTenantId);

        // Both rows must be 'processed', neither left 'pending'
        int pending = jdbc.queryForObject(
            "SELECT COUNT(*) FROM webhook_events WHERE status = 'pending'", Integer.class);
        assertThat(pending).isZero();

        int processed = jdbc.queryForObject(
            "SELECT COUNT(*) FROM webhook_events WHERE status = 'processed'", Integer.class);
        assertThat(processed).isEqualTo(2);

        // Second row should be marked as duplicate
        String secondError = jdbc.queryForObject(
            "SELECT error FROM webhook_events WHERE id = ?", String.class, secondId);
        assertThat(secondError).contains("duplicate");
    }

    // ---- helpers ------------------------------------------------------------

    private String base() { return "http://localhost:" + port; }

    /**
     * Sets up a Bosta courier account for the test tenant and returns the raw webhook secret.
     * Uses the connect endpoint so the full flow is tested.
     */
    @SuppressWarnings("unchecked")
    private String setupBostaAccount() {
        when(bostaGateway.fetchBusinessProfile(anyString())).thenReturn("Test Biz");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ownerToken); headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/bosta/connect", HttpMethod.POST,
            new HttpEntity<>(Map.of("apiKey", "bosta-test-key"), headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("webhookSecret");
    }

    /** Inserts a courier_accounts row directly with a known encrypted key. */
    private void setupBostaAccountWithEncryptedKey(String encryptedKey) {
        // Use a fixed test webhook secret so we can look it up by hash
        jdbc.update(
            "INSERT INTO courier_accounts(tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES(?, 'bosta', ?, 'test-hash', 'active') " +
            "ON CONFLICT DO NOTHING",
            ownerTenantId, encryptedKey);
    }
}
