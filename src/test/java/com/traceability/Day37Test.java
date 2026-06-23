package com.traceability;

import com.traceability.inventory.*;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
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
 * FR-7.9 (blocklist CRUD) + FR-7.8a (blocked-customer gate) integration tests.
 *
 * bl1  — add phone (raw +20 form), stored canonical, audit_log written
 * bl2  — duplicate international/local forms (+201001234567 vs 01001234567) map to same canonical → conflict
 * bl3  — order imported with blocked phone → on_hold=true, hold_reason contains "blocked_customer"
 * bl4  — release hold → on_hold=false, audit_log written
 * bl5  — cancel held order → status=cancelled via existing cancelOrder path
 * bl6  — order with null phone at import → NOT held (pre-PCD no-phone handled gracefully)
 * bl7  — blocklist index on (tenant_id, phone_canonical) is present in schema
 * bl8  — tenant isolation: blocklist entry in tenantA not visible / not applied in tenantB
 * bl9  — remove (soft-delete) works; removed entry no longer blocks new orders
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Day37Test {

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

    @Autowired BlocklistService blocklist;
    @Autowired FulfillService   fulfillSvc;
    @Autowired JdbcTemplate     jdbc;
    @MockBean  JobScheduler     jobScheduler;

    UUID tenantA, tenantB, actorId, storeId;

    @BeforeAll
    void setup() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        actorId = UUID.randomUUID();
        storeId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'D37A')", tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'D37B')", tenantB);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Actor', 'd37@test.com', 'x', 'owner'::user_role)",
                    actorId, tenantA);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "    access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'd37.myshopify.com', 'connected', 'completed', 'enc', " +
            "    now() + interval '876000 hours')",
            storeId, tenantA);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM audit_log   WHERE tenant_id = ?", tenantA);
        jdbc.update("UPDATE orders SET on_hold = false, hold_reason = NULL WHERE tenant_id = ?", tenantA);
        jdbc.update("DELETE FROM orders      WHERE tenant_id = ?", tenantA);
        jdbc.update("UPDATE blocklist SET active = false WHERE tenant_id IN (?, ?)", tenantA, tenantB);
    }

    // ── bl1: add phone in +20 form → stored canonical, audit written ──────────
    @Test
    void bl1_add_normalizesToCanonical_auditWritten() {
        TenantContext.set(tenantA);
        try {
            BlocklistService.BlocklistEntry entry =
                blocklist.add("+201001234567", "fraud", actorId);

            assertThat(entry.phoneCanonical()).isEqualTo("01001234567");
            assertThat(entry.reason()).isEqualTo("fraud");

            // Persisted in DB as canonical
            String stored = jdbc.queryForObject(
                "SELECT phone_canonical FROM blocklist WHERE id = ?::uuid AND active = true",
                String.class, entry.id());
            assertThat(stored).isEqualTo("01001234567");

            // Audit record written
            Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'blocklist_add'",
                Integer.class, tenantA);
            assertThat(auditCount).isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
    }

    // ── bl2: +20 and local form map to same canonical → conflict on second add ─
    @Test
    void bl2_duplicateFormats_sameCanonical_conflict() {
        TenantContext.set(tenantA);
        try {
            // Add via local form
            blocklist.add("01009876543", "scammer", actorId);

            // Add same number via +20 international form — must conflict
            assertThatThrownBy(() -> blocklist.add("+201009876543", "another reason", actorId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(409);
        } finally {
            TenantContext.clear();
        }
    }

    // ── bl3: order imported with blocked phone → on_hold=true ────────────────
    @Test
    void bl3_importedOrderWithBlockedPhone_isHeld() {
        TenantContext.set(tenantA);
        try {
            blocklist.add("01112223344", "known fraudster", actorId);

            UUID orderId = createOrder("01112223344");

            boolean onHold = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT on_hold FROM orders WHERE id = ?", Boolean.class, orderId));
            String holdReason = jdbc.queryForObject(
                "SELECT hold_reason FROM orders WHERE id = ?", String.class, orderId);

            assertThat(onHold).isTrue();
            assertThat(holdReason).contains("blocked_customer");
        } finally {
            TenantContext.clear();
        }
    }

    // ── bl4: release hold → on_hold=false, audit written ─────────────────────
    @Test
    void bl4_releaseHold_clearsHold_audited() {
        TenantContext.set(tenantA);
        try {
            blocklist.add("01223344556", "test", actorId);
            UUID orderId = createOrder("01223344556");

            assertThat(jdbc.queryForObject(
                "SELECT on_hold FROM orders WHERE id = ?", Boolean.class, orderId)).isTrue();

            fulfillSvc.releaseHold(orderId, actorId);

            assertThat(jdbc.queryForObject(
                "SELECT on_hold FROM orders WHERE id = ?", Boolean.class, orderId)).isFalse();
            assertThat(jdbc.queryForObject(
                "SELECT hold_reason FROM orders WHERE id = ?", String.class, orderId)).isNull();

            Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'release_hold'",
                Integer.class, tenantA);
            assertThat(auditCount).isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
    }

    // ── bl5: cancel held order → status=cancelled ────────────────────────────
    @Test
    void bl5_cancelHeldOrder_cancelled() {
        TenantContext.set(tenantA);
        try {
            blocklist.add("01334455667", "bad actor", actorId);
            UUID orderId = createOrder("01334455667");

            FulfillService.CancelResult result = fulfillSvc.cancelOrder(orderId, actorId);

            assertThat(result.status()).isEqualTo("cancelled");
            String dbStatus = jdbc.queryForObject(
                "SELECT status::text FROM orders WHERE id = ?", String.class, orderId);
            assertThat(dbStatus).isEqualTo("cancelled");
        } finally {
            TenantContext.clear();
        }
    }

    // ── bl6: null phone at import → NOT held (graceful no-op) ────────────────
    @Test
    void bl6_nullPhoneAtImport_notHeld_noError() {
        TenantContext.set(tenantA);
        try {
            // Add a blocklist entry with a real phone — should not affect null-phone order
            blocklist.add("01445566778", "irrelevant", actorId);

            UUID orderId = createOrder(null);   // pre-PCD: phone is null

            boolean onHold = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT on_hold FROM orders WHERE id = ?", Boolean.class, orderId));
            assertThat(onHold).isFalse();
        } finally {
            TenantContext.clear();
        }
    }

    // ── bl7: index on (tenant_id, phone_canonical) exists ────────────────────
    @Test
    void bl7_index_onTenantPhoneExists() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pg_indexes " +
            "WHERE tablename = 'blocklist' AND indexname = 'idx_blocklist_tenant_phone'",
            Integer.class);
        assertThat(count).isGreaterThan(0);
    }

    // ── bl8: tenant isolation ────────────────────────────────────────────────
    @Test
    void bl8_tenantIsolation_blocklistNotCrossContaminated() {
        // Add entry in tenantA
        TenantContext.set(tenantA);
        try {
            blocklist.add("01556677889", "tenantA block", actorId);
        } finally {
            TenantContext.clear();
        }

        // Under tenantB: the same phone must not be seen as blocked
        TenantContext.set(tenantB);
        try {
            String reason = blocklist.isBlocked("01556677889", tenantB);
            assertThat(reason).isNull();

            // List must be empty for tenantB
            List<BlocklistService.BlocklistEntry> list = blocklist.list();
            assertThat(list).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    // ── bl9: remove (soft-delete) → phone no longer blocks new orders ─────────
    @Test
    void bl9_remove_unblocks_subsequentOrderNotHeld() {
        TenantContext.set(tenantA);
        try {
            BlocklistService.BlocklistEntry entry =
                blocklist.add("01667788990", "temp block", actorId);

            // First order: blocked
            UUID order1 = createOrder("01667788990");
            assertThat(jdbc.queryForObject(
                "SELECT on_hold FROM orders WHERE id = ?", Boolean.class, order1)).isTrue();

            // Unblock
            blocklist.remove(UUID.fromString(entry.id()), actorId);

            // Audit for remove
            Integer removeAuditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'blocklist_remove'",
                Integer.class, tenantA);
            assertThat(removeAuditCount).isEqualTo(1);

            // Second order: no longer held
            UUID order2 = createOrder("01667788990");
            assertThat(jdbc.queryForObject(
                "SELECT on_hold FROM orders WHERE id = ?", Boolean.class, order2)).isFalse();
        } finally {
            TenantContext.clear();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Creates a minimal order with given phone (may be null) and applies blocklist gate. */
    private UUID createOrder(String customerPhone) {
        UUID tenantId = TenantContext.require();
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, " +
            "    customer_phone, status, payment_method, placed_at) " +
            "VALUES (?, ?, gen_random_uuid()::text, '#D37-' || floor(random()*99999), " +
            "    ?, 'new'::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantId, storeId, customerPhone);

        // Run the gate directly (mirrors what ShopifySyncService does after UPSERT)
        blocklist.checkAndHoldIfBlocked(orderId, customerPhone, tenantId);

        return orderId;
    }
}
