package com.traceability;

import com.traceability.inventory.ReceivingService;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Flexible labeling slice 0 — batchReceive stays under PgJDBC's 65,535 bind-parameter limit.
 *
 * Before the fix, one finalize bound 8 parameters per piece in a single INSERT, so a session of
 * more than 8,191 units failed at finalize ("PreparedStatement can have at most 65,535
 * parameters"). Count-only receiving (slice 2) receives far more.
 *
 *   bc1 — one finalize of 8,193 units (two variants): every piece and every received event is
 *         written, short codes are one contiguous, gap-free range, nothing is half-received
 *   bc2 — chunk boundaries: a finalize of exactly CHUNK and CHUNK + 1 units writes every row
 *   bc3 — the 1,000-piece fast path still finalizes within 10 s
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BatchReceiveChunkingTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
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

    /** Must equal InventoryLedger.RECEIVE_CHUNK — the boundary test exercises it from both sides. */
    static final int CHUNK = 1000;

    @Autowired ReceivingService receivingSvc;
    @Autowired JdbcTemplate     jdbc;

    UUID tenantId;
    UUID actorId;
    UUID variantA;
    UUID variantB;
    UUID locationId;

    @BeforeAll
    void setup() {
        tenantId   = UUID.randomUUID();
        actorId    = UUID.randomUUID();
        UUID storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantA   = UUID.randomUUID();
        variantB   = UUID.randomUUID();
        locationId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'BCTestTenant')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES " +
            "(?, ?, 'Actor', 'actor@bctest.local', 'hash', 'owner')",
            actorId, tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES " +
            "(?, ?, 'shopify', 'bctest.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES " +
            "(?, ?, ?, 'BC-P001', 'BC Widget', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES " +
            "(?, ?, ?, 'BC-V001', 'BC Widget Blue', 'BCBLUE'), " +
            "(?, ?, ?, 'BC-V002', 'BC Widget Red',  'BCRED')",
            variantA, tenantId, productId, variantB, tenantId, productId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default) VALUES " +
            "(?, ?, 'BC Warehouse', 'warehouse', true)",
            locationId, tenantId);
    }

    @BeforeEach
    void setContext() { TenantContext.set(tenantId); }

    @AfterEach
    void teardown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM piece_events  WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces        WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM receipt_lines WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM receipts      WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_counters WHERE tenant_id = ?", tenantId);
    }

    @Test
    void bc1_one_finalize_of_8193_units_writes_every_piece_and_event() {
        UUID s = receivingSvc.createSession(actorId, locationId, "BC1", null, null);
        receivingSvc.addLine(s, variantA, 5000);
        receivingSvc.addLine(s, variantB, 3193);

        int created = receivingSvc.finalize(s, actorId);

        assertThat(created).isEqualTo(8193);
        assertReceived(s, Map.of(variantA, 5000, variantB, 3193));
    }

    @Test
    void bc2_chunk_boundaries_write_every_row() throws Exception {
        var f = com.traceability.inventory.InventoryLedger.class.getDeclaredField("RECEIVE_CHUNK");
        f.setAccessible(true);
        assertThat(f.getInt(null)).as("test CHUNK mirrors InventoryLedger.RECEIVE_CHUNK").isEqualTo(CHUNK);

        for (int n : new int[] { CHUNK, CHUNK + 1 }) {
            UUID s = receivingSvc.createSession(actorId, locationId, "BC2-" + n, null, null);
            receivingSvc.addLine(s, variantA, n);
            assertThat(receivingSvc.finalize(s, actorId)).isEqualTo(n);
            assertReceived(s, Map.of(variantA, n));
        }
    }

    @Test
    void bc3_thousand_piece_fast_path_stays_within_ten_seconds() {
        UUID s = receivingSvc.createSession(actorId, locationId, "BC3", null, null);
        receivingSvc.addLine(s, variantA, 1000);

        long t0 = System.nanoTime();
        receivingSvc.finalize(s, actorId);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(ms).as("1,000-piece finalize took %d ms", ms).isLessThan(10_000);
        assertReceived(s, Map.of(variantA, 1000));
    }

    private void assertReceived(UUID receiptId, Map<UUID, Integer> expectedPerVariant) {
        int total = expectedPerVariant.values().stream().mapToInt(Integer::intValue).sum();

        for (var e : expectedPerVariant.entrySet()) {
            Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pieces WHERE receipt_id = ? AND variant_id = ? AND status = 'available'",
                Integer.class, receiptId, e.getKey());
            assertThat(n).as("pieces of variant %s", e.getKey()).isEqualTo(e.getValue());
        }

        Integer events = jdbc.queryForObject(
            "SELECT count(*) FROM piece_events pe JOIN pieces p ON p.id = pe.piece_id " +
            "WHERE p.receipt_id = ? AND pe.event_type = 'received' AND pe.from_status IS NULL " +
            "AND pe.to_status = 'available' AND pe.actor_user_id = ? AND pe.tenant_id = ?",
            Integer.class, receiptId, actorId, tenantId);
        assertThat(events).as("one received event per piece").isEqualTo(total);

        List<Long> codes = jdbc.queryForList(
            "SELECT substring(short_code FROM 2)::bigint FROM pieces WHERE receipt_id = ? ORDER BY 1",
            Long.class, receiptId);
        assertThat(codes).hasSize(total);
        assertThat(codes.get(total - 1) - codes.get(0))
            .as("one contiguous short-code range per finalize")
            .isEqualTo(total - 1L);

        Integer barcodes = jdbc.queryForObject(
            "SELECT count(DISTINCT barcode) FROM pieces WHERE receipt_id = ? AND barcode = 'PC-' || id",
            Integer.class, receiptId);
        assertThat(barcodes).isEqualTo(total);
    }
}
