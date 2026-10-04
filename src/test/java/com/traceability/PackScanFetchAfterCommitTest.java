package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The AWB scan's Bosta _id fetch runs AFTER the scan's transaction commits (2026-10-04, B1) — no Bosta
 * call, limiter wait or HTTP while the scan holds its DB connection. Replaces build 1's USER_FACING test:
 * nobody waits on the fetch any more, so it runs BACKGROUND in its job.
 *
 *   pa1 inside the scan's transaction: no fetch and nothing enqueued yet; after commit the job is
 *       enqueued; running it fetches (BACKGROUND — no user-facing override) and stores the _id
 *   pa2 a rate-limited fetch reschedules the job (never sleeps, never sets the flag yet)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackScanFetchAfterCommitTest {

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

    @Autowired ShipmentLinkService        linkSvc;
    @Autowired JdbcTemplate               jdbc;
    @Autowired EncryptionService          encryptionService;
    @Autowired ObjectMapper               mapper;
    @Autowired PlatformTransactionManager txm;
    @MockBean  BostaGateway               bostaGateway;
    @MockBean  JobScheduler               jobScheduler;

    UUID tenantId, actorId, storeId;

    @BeforeAll
    void fixture() {
        tenantId = UUID.randomUUID();
        actorId  = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'AfterCommitTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Owner', 'owner@ac.local', 'h', 'owner')", actorId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'ac.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO courier_accounts (id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'h', 'active')", tenantId, encryptionService.encrypt("ac-key"));
    }

    @BeforeEach void ctx() { TenantContext.set(tenantId); reset(bostaGateway, jobScheduler); }
    @AfterEach  void clear() { TenantContext.clear(); }

    @Test
    void pa1_fetchOnlyAfterCommit_storesId() throws Exception {
        UUID orderId = order("AC1");
        AtomicReference<BostaRateLimiter.Priority> seen = new AtomicReference<>(BostaRateLimiter.Priority.USER_FACING);
        ObjectNode raw = mapper.createObjectNode().put("_id", "BOSTA-AC-1");
        when(bostaGateway.fetchDelivery(eq("ac-key"), eq("5000000201"))).thenAnswer(inv -> {
            seen.set(BostaRateLimiter.priorityOverride());
            return new BostaDelivery("5000000201", 41, "SEND", 0, null, null, raw);
        });

        new TransactionTemplate(txm).executeWithoutResult(s -> {
            linkSvc.linkByAwbScan(orderId, "5000000201", actorId);
            verify(bostaGateway, never()).fetchDelivery(any(), any());
            verify(jobScheduler, never()).enqueue(any(JobLambda.class));   // not before commit
        });

        runEnqueued();
        assertThat(seen.get()).as("background — nobody is waiting on it").isNull();
        assertThat(id("5000000201")).isEqualTo("BOSTA-AC-1");
    }

    @Test
    void pa2_rateLimited_reschedules() throws Exception {
        UUID orderId = order("AC2");
        when(bostaGateway.fetchDelivery(eq("ac-key"), eq("5000000202"))).thenThrow(new BostaRateLimitException(90));

        linkSvc.linkByAwbScan(orderId, "5000000202", actorId);
        runEnqueued();

        verify(jobScheduler, times(1)).schedule(any(java.time.Instant.class), any(JobLambda.class));
        assertThat(jdbc.queryForObject("SELECT provider_id_fetch_failed FROM shipments WHERE tracking_number = '5000000202'",
            Boolean.class)).isFalse();
    }

    private void runEnqueued() throws Exception {
        ArgumentCaptor<JobLambda> job = ArgumentCaptor.forClass(JobLambda.class);
        verify(jobScheduler, timeout(3_000)).enqueue(job.capture());
        job.getValue().run();
        TenantContext.set(tenantId);
    }

    private UUID order(String number) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, cod_amount, placed_at) " +
            "VALUES (?, ?, ?, ?, 'packed'::order_status, 'cod', 0, now()) RETURNING id",
            UUID.class, tenantId, storeId, "EXT-" + number, number);
    }

    private String id(String tn) {
        return jdbc.queryForObject("SELECT provider_delivery_id FROM shipments WHERE tracking_number = ?", String.class, tn);
    }
}
