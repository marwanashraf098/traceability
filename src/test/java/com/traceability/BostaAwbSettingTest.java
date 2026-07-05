package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.bosta.*;
import com.traceability.security.EncryptionService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for the per-tenant AWB size setting (A4 / A6).
 *
 * Covers:
 *   (1) Default awb_format='A4' is passed to the Bosta gateway.
 *   (2) Changing to A6 via PUT /bosta/settings → gateway receives 'A6'.
 *   (3) PUT /bosta/settings persists awbFormat to courier_accounts.
 *   (4) PUT /bosta/settings is owner-only; managers get 403.
 *   (5) GET /connections exposes awbFormat and awbLang for the Settings UI.
 *
 * BostaGateway is @MockBean — no real Bosta API calls.
 * DB tests run as postgres (BYPASSRLS); TenantContext.runAs() sets the GUC
 * inside BostaAwbService so RLS-relevant paths are exercised even in the
 * postgres-role test. Strict RLS isolation for AWB service is inherited
 * from the general pattern in InventoryLedgerTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaAwbSettingTest {

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
        r.add("bosta.backfill.inter-fetch-delay-ms", () -> "0");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate   rest;
    @Autowired JdbcTemplate       jdbc;
    @Autowired JwtService         jwtService;
    @Autowired BostaAwbService    awbService;
    @Autowired EncryptionService  encryptionService;
    @Autowired ObjectMapper       mapper;
    @MockBean  BostaGateway       bostaGateway;

    private String ownerToken;
    private String managerToken;
    private UUID   ownerTenantId;
    private UUID   storeId;

    private static final byte[] FAKE_PDF = new byte[]{0x25, 0x50, 0x44, 0x46}; // %PDF magic

    @BeforeAll
    void setupOwner() {
        SignupRequest req = new SignupRequest(
            "AWB Co", "awb_owner", "awb@test.com", "password99", true);
        ResponseEntity<TokenResponse> resp = rest.postForEntity(
            base() + "/api/v1/auth/signup", req, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ownerToken    = resp.getBody().accessToken();
        ownerTenantId = UUID.fromString(
            (String) jwtService.verify(ownerToken).getClaim("tenant"));

        storeId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'awb-test.myshopify.com', 'disconnected')",
            storeId, ownerTenantId);

        // Create a manager to test owner-only enforcement
        HttpHeaders oh = new HttpHeaders();
        oh.setBearerAuth(ownerToken); oh.setContentType(MediaType.APPLICATION_JSON);
        rest.exchange(base() + "/api/v1/users", HttpMethod.POST,
            new HttpEntity<>(Map.of("name", "Mgr", "email", "awb_mgr@test.com",
                "role", "manager", "password", "password99"), oh), Map.class);
        HttpHeaders lh = new HttpHeaders(); lh.setContentType(MediaType.APPLICATION_JSON);
        var loginResp = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", "awb_mgr@test.com", "password", "password99"), lh),
            Map.class);
        managerToken = (String) loginResp.getBody().get("accessToken");
    }

    @BeforeEach
    void cleanUp() {
        jdbc.execute("DELETE FROM shipments");
        jdbc.execute("DELETE FROM order_items");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM courier_accounts");
        reset(bostaGateway);
    }

    // ── (1) Default A4 is passed to the gateway ───────────────────────────────

    @Test
    void awbPrint_defaultA4_passedToGateway() {
        setupCourierAccount("awb-key-1", "A4", "ar");
        UUID shipmentId = createShipmentWithOrder("BOS-AWB-A4");

        when(bostaGateway.printMassAwb(anyString(), anyList(), anyString(), anyString()))
            .thenReturn(new AwbPrintResult(FAKE_PDF, null));

        BostaAwbService.AwbBatchResult result =
            awbService.printAwb(ownerTenantId, List.of(shipmentId), null, null);

        assertThat(result.pdfBase64List()).hasSize(1);
        verify(bostaGateway).printMassAwb(anyString(), eq(List.of("BOS-AWB-A4")), eq("A4"), eq("ar"));
    }

    // ── (2) A6 setting is passed to the gateway ───────────────────────────────

    @Test
    void awbPrint_a6Setting_passedToGateway() {
        setupCourierAccount("awb-key-2", "A6", "ar");
        UUID shipmentId = createShipmentWithOrder("BOS-AWB-A6");

        when(bostaGateway.printMassAwb(anyString(), anyList(), anyString(), anyString()))
            .thenReturn(new AwbPrintResult(FAKE_PDF, null));

        BostaAwbService.AwbBatchResult result =
            awbService.printAwb(ownerTenantId, List.of(shipmentId), null, null);

        assertThat(result.pdfBase64List()).hasSize(1);
        verify(bostaGateway).printMassAwb(anyString(), eq(List.of("BOS-AWB-A6")), eq("A6"), eq("ar"));
    }

    // ── (3) PUT /bosta/settings persists awbFormat ────────────────────────────

    @Test
    void bostaSettings_updateAwbFormat_persistsToDb() {
        setupCourierAccount("awb-key-3", "A4", "ar");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(ownerToken); h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Void> resp = rest.exchange(
            base() + "/api/v1/bosta/settings",
            HttpMethod.PUT,
            new HttpEntity<>(Map.of("awbFormat", "A6"), h),
            Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        String stored = jdbc.queryForObject(
            "SELECT awb_format FROM courier_accounts " +
            "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active'",
            String.class, ownerTenantId);
        assertThat(stored).isEqualTo("A6");

        // Follow-up print uses the persisted A6
        UUID shipmentId = createShipmentWithOrder("BOS-AWB-PERSIST");
        when(bostaGateway.printMassAwb(anyString(), anyList(), anyString(), anyString()))
            .thenReturn(new AwbPrintResult(FAKE_PDF, null));
        awbService.printAwb(ownerTenantId, List.of(shipmentId), null, null);
        verify(bostaGateway).printMassAwb(anyString(), any(), eq("A6"), any());
    }

    // ── (4) PUT /bosta/settings is owner-only ────────────────────────────────

    @Test
    void bostaSettings_manager_gets403() {
        setupCourierAccount("awb-key-4", "A4", "ar");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(managerToken); h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = rest.exchange(
            base() + "/api/v1/bosta/settings",
            HttpMethod.PUT,
            new HttpEntity<>(Map.of("awbFormat", "A6"), h),
            String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // DB must not be changed
        String stored = jdbc.queryForObject(
            "SELECT awb_format FROM courier_accounts " +
            "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active'",
            String.class, ownerTenantId);
        assertThat(stored).isEqualTo("A4");
    }

    // ── (5) GET /connections exposes awbFormat and awbLang ────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void connections_exposesAwbFormatAndLang() {
        setupCourierAccount("awb-key-5", "A6", "en");

        HttpHeaders h = new HttpHeaders(); h.setBearerAuth(ownerToken);
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/connections", HttpMethod.GET,
            new HttpEntity<>(h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> bosta = (Map<String, Object>) resp.getBody().get("bosta");
        assertThat(bosta.get("connected")).isEqualTo(true);
        assertThat(bosta.get("awbFormat")).isEqualTo("A6");
        assertThat(bosta.get("awbLang")).isEqualTo("en");
    }

    // ── (5b) GET /connections returns null awbFormat when not connected ───────

    @Test
    @SuppressWarnings("unchecked")
    void connections_bostaNotConnected_awbFormatIsNull() {
        // No courier_account — not connected

        HttpHeaders h = new HttpHeaders(); h.setBearerAuth(ownerToken);
        ResponseEntity<Map> resp = rest.exchange(
            base() + "/api/v1/connections", HttpMethod.GET,
            new HttpEntity<>(h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> bosta = (Map<String, Object>) resp.getBody().get("bosta");
        assertThat(bosta.get("connected")).isEqualTo(false);
        assertThat(bosta.get("awbFormat")).isNull();
        assertThat(bosta.get("awbLang")).isNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String base() { return "http://localhost:" + port; }

    private void setupCourierAccount(String rawApiKey, String awbFormat, String awbLang) {
        jdbc.update(
            "INSERT INTO courier_accounts " +
            "  (tenant_id, provider, api_key_encrypted, webhook_secret, status, awb_format, awb_lang) " +
            "VALUES (?, 'bosta', ?, 'test-hash', 'active', ?, ?)",
            ownerTenantId, encryptionService.encrypt(rawApiKey), awbFormat, awbLang);
    }

    private UUID createShipmentWithOrder(String trackingNumber) {
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, status) " +
            "VALUES (?, ?, ?, 'new'::order_status) RETURNING id",
            UUID.class, ownerTenantId, storeId, "EXT-" + trackingNumber);

        return jdbc.queryForObject(
            "INSERT INTO shipments " +
            "  (id, tenant_id, order_id, tracking_number, internal_state, provider, raw) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'with_courier'::shipment_internal_state, 'bosta', ?::jsonb) " +
            "RETURNING id",
            UUID.class, ownerTenantId, orderId, trackingNumber,
            "{\"type\":\"SEND\"}");
    }
}
