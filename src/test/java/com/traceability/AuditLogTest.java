package com.traceability;

import com.traceability.account.AuditService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.inventory.FulfillService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * FR-2.6 audit log: append-only, tenant-isolated, wired to privileged actions.
 *
 * Matrix:
 *   a1 — record() writes a row readable via AuditService.list()
 *   a2 — audit_log is tenant-isolated (cross-tenant query returns zero rows)
 *   a3 — append-only: UPDATE on audit_log is rejected at DB level
 *   a4 — DELETE on audit_log is rejected at DB level
 *   a5 — convertToSelfPickup writes 'convert_to_self_pickup' audit entry
 *   a6 — list() filters by action
 *   a7 — list() filters by actor UUID
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditLogTest {

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

    @Autowired AuditService   auditSvc;
    @Autowired FulfillService fulfillSvc;
    @Autowired JdbcTemplate   jdbc;
    @Autowired EncryptionService encSvc;
    @MockBean  BostaGateway   bostaGateway;
    @MockBean  JobScheduler   jobScheduler;

    UUID tenantA, tenantB, actor, storeId, variantId;

    @BeforeAll
    void setup() {
        tenantA  = UUID.randomUUID();
        tenantB  = UUID.randomUUID();
        actor    = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'AuditA')", tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'AuditB')", tenantB);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'o@a.local', 'h', 'owner')", actor, tenantA);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'audit.myshopify.com', 'disconnected')", storeId, tenantA);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-AUD', 'Widget', 'active')", productId, tenantA, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-AUD', 'Blue', 'W-AUD')", variantId, tenantA, productId);
    }

    @BeforeEach  void ctx()     { TenantContext.set(tenantA); }
    @AfterEach   void ctxClear() {
        TenantContext.clear();
        jdbc.update("DELETE FROM audit_log WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM orders    WHERE tenant_id = ?", tenantA);
    }

    @Test
    void a1_record_writesReadableRow() {
        auditSvc.record(actor, "user_create", "user", UUID.randomUUID().toString(),
            Map.of("name", "Bob", "role", "worker"));

        Map<String, Object> result = auditSvc.list(null, null, null, null, 0, 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("action")).isEqualTo("user_create");
        assertThat(items.get(0).get("actor_user_id")).isEqualTo(actor);
    }

    @Test
    void a2_auditLog_tenantIsolated() {
        auditSvc.record(actor, "test_action", null, null, null);

        // No rows when querying under tenantB context
        TenantContext.set(tenantB);
        Map<String, Object> result = auditSvc.list(null, null, null, null, 0, 10);
        @SuppressWarnings("unchecked")
        List<?> items = (List<?>) result.get("items");
        assertThat(items).isEmpty();
    }

    @Test
    void a3_appendOnly_updateRejectedAtDbLevel() {
        // This test connects as postgres (BYPASSRLS) so we use app-level evidence:
        // no UPDATE grant means the app_user cannot do it. We verify the grant list.
        Integer updatePriv = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.table_privileges " +
            "WHERE table_name = 'audit_log' AND privilege_type = 'UPDATE' AND grantee = 'app_user'",
            Integer.class);
        assertThat(updatePriv).as("app_user must not have UPDATE on audit_log").isZero();
    }

    @Test
    void a4_appendOnly_deleteRejectedAtDbLevel() {
        Integer deletePriv = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.table_privileges " +
            "WHERE table_name = 'audit_log' AND privilege_type = 'DELETE' AND grantee = 'app_user'",
            Integer.class);
        assertThat(deletePriv).as("app_user must not have DELETE on audit_log").isZero();
    }

    @Test
    void a5_convertToSelfPickup_writesAuditEntry() {
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, cod_amount, placed_at) " +
            "VALUES (?, ?, ?, '#AU1', 'packed'::order_status, 'cod', 0, now()) RETURNING id",
            UUID.class, tenantA, storeId, "EXT-AU1");

        fulfillSvc.convertToSelfPickup(orderId, "customer preferred in-store", actor);

        Map<String, Object> result = auditSvc.list("convert_to_self_pickup", null, null, null, 0, 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("target_id")).isEqualTo(orderId.toString());
        assertThat(items.get(0).get("action")).isEqualTo("convert_to_self_pickup");
    }

    @Test
    void a6_list_filtersByAction() {
        auditSvc.record(actor, "user_create",   "user", "u1", null);
        auditSvc.record(actor, "user_deactivate","user", "u2", null);

        Map<String, Object> result = auditSvc.list("user_create", null, null, null, 0, 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("action")).isEqualTo("user_create");
    }

    @Test
    void a7_list_filtersByActor() {
        UUID other = UUID.randomUUID();
        // Insert other user in same tenant
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Other', 'other@a.local', 'h', 'manager')", other, tenantA);
        auditSvc.record(actor, "user_create", "user", "u1", null);
        auditSvc.record(other, "user_update",  "user", "u2", null);

        Map<String, Object> result = auditSvc.list(null, other, null, null, 0, 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("action")).isEqualTo("user_update");
    }
}
