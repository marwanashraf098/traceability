package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Two finalizes of ONE receiving session at the same moment (a double click, two tabs) — every mode.
 *
 *   f1  print_stick / count_only: both calls start while a third connection holds the variant row (the pieces'
 *       FK check waits on it), so both have passed the open-check before either writes. Exactly one finalize
 *       wins: the session's pieces are created once, Shopify +N is claimed once, the other call gets 409.
 *
 * Revert-checked 2026-10-27: without the receipt FOR UPDATE and the conditional finalize UPDATE, both calls
 * succeed — 2N pieces and two Shopify calls.
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReceivingFinalizeRaceTest {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired ReceivingService receiving;
    @MockBean  ShopifyInventoryService shopifyInventory;

    UUID tenant, owner, variant, main;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID(); owner = UUID.randomUUID(); variant = UUID.randomUUID(); main = UUID.randomUUID();
        UUID store = UUID.randomUUID(), product = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, flexible_labeling_enabled) VALUES (?, 'Race Tenant', true)", tenant);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'O', 'race-o@test.local', 'h', 'owner')",
            owner, tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'race.myshopify.com', 'disconnected')",
            store, tenant);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'RACE-P', 'Widget', 'active')",
            product, tenant, store);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'RACE-V', 'Blue', 'RACEBLUE')",
            variant, tenant, product);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) VALUES (?, ?, 'Race Main', 'warehouse', true, true)",
            main, tenant);
    }

    @AfterEach
    void clean() {
        TenantContext.clear();
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM receipt_lines WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM receipts WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM piece_counters WHERE tenant_id = ?", tenant);
    }

    @ParameterizedTest
    @ValueSource(strings = {ReceivingService.MODE_PRINT_STICK, ReceivingService.MODE_COUNT_ONLY})
    void f1_two_concurrent_finalizes_create_the_pieces_once(String mode) throws Exception {
        reset(shopifyInventory);
        UUID session;
        TenantContext.set(tenant);
        try {
            session = receiving.createSession(owner, main, "RACE", null, null, mode);
            receiving.addLine(session, variant, 3);
        } finally {
            TenantContext.clear();
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Object>> calls = new ArrayList<>();
        try (Connection holder = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres")) {
            holder.setAutoCommit(false);
            try (PreparedStatement ps = holder.prepareStatement("SELECT id FROM variants WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, variant);
                ps.executeQuery();
            }
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    TenantContext.set(tenant);
                    try {
                        return (Object) receiving.finalize(session, owner);
                    } catch (Exception e) {
                        return e;
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            // Both finalizes are now inside their transactions, each waiting on a lock (the variant row, or — with
            // the fix — the receipt row the other one holds). Only then let them go.
            long deadline = System.currentTimeMillis() + 15_000;
            while (waitingOnLocks() < 2) {
                if (System.currentTimeMillis() > deadline) fail("the two finalizes never both reached a lock wait");
                Thread.sleep(25);
            }
            holder.commit();
        }

        List<Object> results = new ArrayList<>();
        for (Future<Object> f : calls) results.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(results).filteredOn(r -> Integer.valueOf(3).equals(r)).as("one finalize wins").hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof ResponseStatusException e && e.getStatusCode() == HttpStatus.CONFLICT)
            .as("the other is refused").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pieces WHERE receipt_id = ?", Integer.class, session))
            .as("the session's pieces exist once").isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM piece_events e JOIN pieces p ON p.id = e.piece_id " +
            "WHERE p.receipt_id = ? AND e.event_type = 'received'", Integer.class, session)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM receipts WHERE id = ?", String.class, session)).isEqualTo("finalized");
        verify(shopifyInventory, times(1)).onReceivingSessionClose(eq(tenant), eq(session), eq(main), eq(Map.of(variant, 3)));
        verifyNoMoreInteractions(shopifyInventory);
    }

    private int waitingOnLocks() {
        Integer n = jdbc.queryForObject(
            "SELECT count(*)::int FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
            Integer.class);
        return n == null ? 0 : n;
    }
}
