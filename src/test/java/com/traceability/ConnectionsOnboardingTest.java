package com.traceability;

import com.traceability.account.ConnectionsController;
import com.traceability.account.OnboardingController;
import com.traceability.identity.CustomUserDetails;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * FR-1.2 (connections + onboarding) backend tests.
 *
 * Matrix:
 *   c1 — connections: no integrations → both false
 *   c2 — connections: Shopify connected → shopify.connected=true
 *   c3 — connections: Bosta active → bosta.connected=true
 *   o1 — onboarding: fresh tenant → all steps pending
 *   o2 — onboarding: Shopify connected → step 1 done
 *   o3 — onboarding: import completed → step 3 done
 *   o4 — onboarding: label reprint exists → step 4 done
 *   o5 — onboarding: finalized receipt → step 5 done
 *   o6 — onboarding: all steps done → allDone=true
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectionsOnboardingTest {

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

    @Autowired ConnectionsController connCtl;
    @Autowired OnboardingController  onboardCtl;
    @Autowired EncryptionService     encSvc;
    @Autowired JdbcTemplate          jdbc;
    @MockBean  BostaGateway          bostaGateway;
    @MockBean  JobScheduler          jobScheduler;

    UUID tenantId, ownerId, storeId, locationId, variantId;

    @BeforeAll
    void setup() {
        tenantId   = UUID.randomUUID();
        ownerId    = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        locationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ConnTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'o@c.local', 'h', 'owner')", ownerId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'conn.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_default) " +
                    "VALUES (?, ?, 'Main', true)", locationId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-CO', 'Widget', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-CO', 'Red', 'W-CO')", variantId, tenantId, productId);
    }

    @BeforeEach
    void ctx() {
        TenantContext.set(tenantId);
        var p = principal();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities()));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        // Reset integration state
        jdbc.update("UPDATE stores SET status='disconnected', import_status='idle' WHERE tenant_id=?", tenantId);
        jdbc.update("DELETE FROM courier_accounts WHERE tenant_id=?", tenantId);
        jdbc.update("DELETE FROM label_reprints WHERE tenant_id=?", tenantId);
        jdbc.update("DELETE FROM receipt_lines   WHERE tenant_id=?", tenantId);
        jdbc.update("DELETE FROM receipts        WHERE tenant_id=?", tenantId);
    }

    CustomUserDetails principal() {
        return new CustomUserDetails(ownerId, tenantId, "owner");
    }

    // ── Connections ───────────────────────────────────────────────────────────

    @Test
    void c1_noIntegrations_bothFalse() {
        Map<String, Object> status = connCtl.status(principal());
        @SuppressWarnings("unchecked")
        Map<String, Object> shopify = (Map<String, Object>) status.get("shopify");
        @SuppressWarnings("unchecked")
        Map<String, Object> bosta = (Map<String, Object>) status.get("bosta");
        assertThat(shopify.get("connected")).isEqualTo(false);
        assertThat(bosta.get("connected")).isEqualTo(false);
    }

    @Test
    void c2_shopifyConnected_reflectsInStatus() {
        jdbc.update("UPDATE stores SET status='connected' WHERE id=?", storeId);

        Map<String, Object> status = connCtl.status(principal());
        @SuppressWarnings("unchecked")
        Map<String, Object> shopify = (Map<String, Object>) status.get("shopify");
        assertThat(shopify.get("connected")).isEqualTo(true);
        assertThat(shopify.get("shopDomain")).isEqualTo("conn.myshopify.com");
    }

    @Test
    void c3_bostaActive_reflectsInStatus() {
        jdbc.update(
            "INSERT INTO courier_accounts (id, tenant_id, provider, api_key_encrypted, webhook_secret, status, business_ref) " +
            "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'wh', 'active', 'Acme Logistics')",
            tenantId, encSvc.encrypt("test-key"));

        Map<String, Object> status = connCtl.status(principal());
        @SuppressWarnings("unchecked")
        Map<String, Object> bosta = (Map<String, Object>) status.get("bosta");
        assertThat(bosta.get("connected")).isEqualTo(true);
        assertThat(bosta.get("businessName")).isEqualTo("Acme Logistics");
    }

    // ── Onboarding ────────────────────────────────────────────────────────────

    @Test
    void o1_freshTenant_allPending() {
        Map<String, Object> result = onboardCtl.status(principal());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        assertThat(steps).allMatch(s -> "pending".equals(s.get("status")));
        assertThat(result.get("allDone")).isEqualTo(false);
    }

    @Test
    void o2_shopifyConnected_step1Done() {
        jdbc.update("UPDATE stores SET status='connected' WHERE id=?", storeId);

        Map<String, Object> result = onboardCtl.status(principal());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        assertThat(stepStatus(steps, "connect_shopify")).isEqualTo("done");
        assertThat(stepStatus(steps, "connect_bosta")).isEqualTo("pending");
    }

    @Test
    void o3_importCompleted_step3Done() {
        jdbc.update("UPDATE stores SET import_status='completed' WHERE id=?", storeId);

        Map<String, Object> result = onboardCtl.status(principal());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        assertThat(stepStatus(steps, "initial_import")).isEqualTo("done");
    }

    @Test
    void o4_labelReprint_step4Done() {
        // Create a receipt to satisfy FK
        UUID receiptId = jdbc.queryForObject(
            "INSERT INTO receipts (id, tenant_id, received_by, location_id, status) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'open') RETURNING id",
            UUID.class, tenantId, ownerId, locationId);
        jdbc.update(
            "INSERT INTO label_reprints (tenant_id, receipt_id, reprinted_by, piece_count) " +
            "VALUES (?, ?, ?, 5)", tenantId, receiptId, ownerId);

        Map<String, Object> result = onboardCtl.status(principal());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        assertThat(stepStatus(steps, "test_label")).isEqualTo("done");
    }

    @Test
    void o5_finalizedReceipt_step5Done() {
        jdbc.update(
            "INSERT INTO receipts (id, tenant_id, received_by, location_id, status, finalized_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'finalized', now())",
            tenantId, ownerId, locationId);

        Map<String, Object> result = onboardCtl.status(principal());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) result.get("steps");
        assertThat(stepStatus(steps, "first_receiving")).isEqualTo("done");
    }

    @Test
    void o6_allSignalsPresent_allDoneTrue() {
        // ① Shopify
        jdbc.update("UPDATE stores SET status='connected', import_status='completed' WHERE id=?", storeId);
        // ② Bosta
        jdbc.update(
            "INSERT INTO courier_accounts (id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'wh', 'active')",
            tenantId, encSvc.encrypt("k2"));
        // ③ already done via import_status='completed'
        // ④ label reprint
        UUID receiptId = jdbc.queryForObject(
            "INSERT INTO receipts (id, tenant_id, received_by, location_id, status, finalized_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'finalized', now()) RETURNING id",
            UUID.class, tenantId, ownerId, locationId);
        jdbc.update("INSERT INTO label_reprints (tenant_id, receipt_id, reprinted_by, piece_count) " +
                    "VALUES (?, ?, ?, 1)", tenantId, receiptId, ownerId);

        Map<String, Object> result = onboardCtl.status(principal());
        assertThat(result.get("allDone")).isEqualTo(true);
    }

    private static String stepStatus(List<Map<String, Object>> steps, String key) {
        return steps.stream()
            .filter(s -> key.equals(s.get("key")))
            .map(s -> (String) s.get("status"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Step not found: " + key));
    }
}
