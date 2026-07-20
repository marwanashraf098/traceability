package com.traceability;

import com.traceability.account.AuditService;
import com.traceability.account.UserService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * FR-2.2 User CRUD + deactivation rules.
 *
 * Matrix:
 *   u1 — Owner can create Worker (PIN stored hashed)
 *   u2 — Owner can create Manager (password hashed)
 *   u3 — Manager cannot create an Owner
 *   u4 — Worker created without password → 400
 *   u5 — Deactivate removes user from auth_lookup_user (active=false)
 *   u6 — Deactivated user's piece_event attribution row intact (no hard delete)
 *   u7 — Manager cannot deactivate an Owner
 *   u8 — Cannot deactivate own account
 *   u9 — create/deactivate each write audit_log entry
 *  u10 — update writes audit_log; Manager cannot change Owner's role
 *  u11 — list returns only tenant-scoped users
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserCrudTest {

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

    @Autowired UserService    userSvc;
    @Autowired AuditService   auditSvc;
    @Autowired JdbcTemplate   jdbc;
    @MockBean  BostaGateway   bostaGateway;
    @MockBean  JobScheduler   jobScheduler;

    UUID tenantId, ownerUserId, managerUserId, variantId;

    @BeforeAll
    void setup() {
        tenantId      = UUID.randomUUID();
        ownerUserId   = UUID.randomUUID();
        managerUserId = UUID.randomUUID();
        UUID storeId    = UUID.randomUUID();
        UUID productId  = UUID.randomUUID();
        variantId       = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'UserTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'owner@u.local', 'h', 'owner')", ownerUserId, tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Manager', 'mgr@u.local', 'h', 'manager')", managerUserId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'user.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-USR', 'Widget', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-USR', 'Blue', 'W-USR')", variantId, tenantId, productId);
    }

    @BeforeEach void ctx() { TenantContext.set(tenantId); }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM audit_log  WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces      WHERE tenant_id = ?", tenantId);
        // Remove only test-created users (keep owner and manager)
        jdbc.update("DELETE FROM users WHERE tenant_id = ? " +
                    "AND id NOT IN (?, ?)", tenantId, ownerUserId, managerUserId);
    }

    @Test
    void u1_ownerCanCreateWorkerWithPin() {
        Map<String, Object> result = userSvc.create(
            ownerUserId, "owner", "Alice Worker", null, "worker", null, "1234");

        UUID newId = UUID.fromString((String) result.get("id"));
        String pinHash = jdbc.queryForObject(
            "SELECT pin_code FROM users WHERE id = ?", String.class, newId);
        assertThat(pinHash).isNotNull().isNotEqualTo("1234"); // stored as hash
        assertThat(result.get("role")).isEqualTo("worker");
    }

    @Test
    void u2_ownerCanCreateManagerWithPassword() {
        Map<String, Object> result = userSvc.create(
            ownerUserId, "owner", "Bob Manager", "bob@u.local", "manager", "securePass1", null);

        UUID newId = UUID.fromString((String) result.get("id"));
        String hash = jdbc.queryForObject(
            "SELECT password_hash FROM users WHERE id = ?", String.class, newId);
        assertThat(hash).isNotNull().isNotEqualTo("securePass1");
        assertThat(result.get("role")).isEqualTo("manager");
    }

    @Test
    void u3_managerCannotCreateOwner() {
        assertThatThrownBy(() ->
            userSvc.create(managerUserId, "manager", "Rogue Owner", "rogue@u.local",
                "owner", "password123", null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Manager");
    }

    @Test
    void u4_workerRequiresPin() {
        assertThatThrownBy(() ->
            userSvc.create(ownerUserId, "owner", "NoPinWorker", null, "worker", null, null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("PIN");
    }

    @Test
    void u5_deactivatedUserExcludedFromAuthLookup() {
        UUID userId = UUID.fromString((String) userSvc.create(
            ownerUserId, "owner", "To Deactivate", "deact@u.local", "manager",
            "password123", null).get("id"));
        // auth_lookup_user only returns active=true users
        userSvc.deactivate(ownerUserId, "owner", userId);

        Boolean active = jdbc.queryForObject(
            "SELECT active FROM users WHERE id = ?", Boolean.class, userId);
        assertThat(active).isFalse();

        // auth_lookup_user returns nothing for this email
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM auth_lookup_user(?)", Integer.class, "deact@u.local");
        assertThat(count).isZero();
    }

    @Test
    void u6_deactivatedUser_attributionRowIntact() {
        UUID userId = UUID.fromString((String) userSvc.create(
            ownerUserId, "owner", "Keep Attribution", "keep@u.local", "manager",
            "password123", null).get("id"));

        // Write a piece_event attributed to this user (simulate picking)
        String pieceId = "01" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available')",
                    pieceId, tenantId, variantId, "BC-" + pieceId, pieceId);
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, " +
                    "from_status, to_status, occurred_at) " +
                    "VALUES (?, ?, 'picked', ?, 'available', 'reserved', now())",
                    tenantId, pieceId, userId);

        userSvc.deactivate(ownerUserId, "owner", userId);

        // User row still present (not hard-deleted)
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId);
        assertThat(count).isOne();

        // piece_event attribution still intact
        UUID attr = jdbc.queryForObject(
            "SELECT actor_user_id FROM piece_events WHERE piece_id = ? AND tenant_id = ?",
            UUID.class, pieceId, tenantId);
        assertThat(attr).isEqualTo(userId);
    }

    @Test
    void u7_managerCannotDeactivateOwner() {
        assertThatThrownBy(() ->
            userSvc.deactivate(managerUserId, "manager", ownerUserId))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Manager");
    }

    @Test
    void u8_cannotDeactivateOwnAccount() {
        assertThatThrownBy(() ->
            userSvc.deactivate(ownerUserId, "owner", ownerUserId))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("own account");
    }

    @Test
    void u9_createAndDeactivate_writeAuditEntries() {
        UUID userId = UUID.fromString((String) userSvc.create(
            ownerUserId, "owner", "Audited User", "aud@u.local", "worker", null, "9999").get("id"));

        userSvc.deactivate(ownerUserId, "owner", userId);

        Map<String, Object> log = auditSvc.list(null, ownerUserId, null, null, 0, 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) log.get("items");
        List<String> actions = items.stream()
            .map(i -> (String) i.get("action")).toList();
        assertThat(actions).contains("user_create", "user_deactivate");
    }

    @Test
    void u10_update_writesAuditEntry_managerCannotChangeOwnerRole() {
        UUID userId = UUID.fromString((String) userSvc.create(
            ownerUserId, "owner", "Updatable", "upd@u.local", "manager",
            "password123", null).get("id"));

        userSvc.update(ownerUserId, "owner", userId, "Updated Name", null);

        Map<String, Object> log = auditSvc.list("user_update", null, null, null, 0, 10);
        @SuppressWarnings("unchecked")
        List<?> items = (List<?>) log.get("items");
        assertThat(items).hasSize(1);

        // Manager cannot change Owner's role
        assertThatThrownBy(() ->
            userSvc.update(managerUserId, "manager", ownerUserId, null, "worker"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Manager");
    }

    @Test
    void u11_list_scopedToTenant() {
        UUID otherTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Other')", otherTenant);
        UUID otherId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Stranger', 's@other.local', 'h', 'owner')", otherId, otherTenant);

        List<Map<String, Object>> users = userSvc.list();
        List<Object> ids = users.stream().map(u -> u.get("id")).toList();
        assertThat(ids).doesNotContain(otherId);
        assertThat(ids).contains(ownerUserId, managerUserId);
    }
}
