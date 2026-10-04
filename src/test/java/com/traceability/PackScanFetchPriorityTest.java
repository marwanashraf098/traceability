package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.inventory.ShipmentLinkService;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The pack scan's Bosta fetch (ShipmentLinkService.fetchAndStoreProviderDeliveryId) runs USER_FACING
 * in the shared limiter (2026-10-04) — a packer is waiting, it goes ahead of the polls.
 *
 *   pp1 linkByAwbScan → the fetch runs with the USER_FACING override; the override is gone after
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackScanFetchPriorityTest {

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

    @Autowired ShipmentLinkService linkSvc;
    @Autowired JdbcTemplate        jdbc;
    @Autowired EncryptionService   encryptionService;
    @Autowired ObjectMapper        mapper;
    @MockBean  BostaGateway        bostaGateway;
    @MockBean  JobScheduler        jobScheduler;

    UUID tenantId, actorId, storeId;

    @BeforeAll
    void fixture() {
        tenantId = UUID.randomUUID();
        actorId  = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'PrioTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Owner', 'owner@prio.local', 'h', 'owner')", actorId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'prio.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO courier_accounts (id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'h', 'active')", tenantId, encryptionService.encrypt("prio-key"));
    }

    @BeforeEach void ctx() { TenantContext.set(tenantId); }
    @AfterEach  void clear() { TenantContext.clear(); }

    @Test
    void pp1_packScanFetch_isUserFacing() {
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, cod_amount, placed_at) " +
            "VALUES (?, ?, 'EXT-PRIO1', 'PRIO1', 'packed'::order_status, 'cod', 0, now()) RETURNING id",
            UUID.class, tenantId, storeId);
        AtomicReference<BostaRateLimiter.Priority> seen = new AtomicReference<>();
        ObjectNode raw = mapper.createObjectNode().put("_id", "BOSTA-PRIO-1");
        when(bostaGateway.fetchDelivery(eq("prio-key"), eq("5000000101"))).thenAnswer(inv -> {
            seen.set(BostaRateLimiter.priorityOverride());
            return new BostaDelivery("5000000101", 41, "SEND", 0, null, null, raw);
        });

        linkSvc.linkByAwbScan(orderId, "5000000101", actorId);

        assertThat(seen.get()).as("the fetch ran user-facing").isEqualTo(BostaRateLimiter.Priority.USER_FACING);
        assertThat(BostaRateLimiter.priorityOverride()).as("override cleared afterwards").isNull();
        assertThat(jdbc.queryForObject("SELECT provider_delivery_id FROM shipments WHERE tracking_number = '5000000101'",
            String.class)).isEqualTo("BOSTA-PRIO-1");
    }
}
