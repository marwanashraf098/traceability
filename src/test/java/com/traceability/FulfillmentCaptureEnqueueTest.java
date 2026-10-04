package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaFulfillmentLinkService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.FulfillmentTrackingCapture;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.jobrunr.jobs.lambdas.JobLambda;
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

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FulfillmentTrackingCapture's link-job enqueue happens after commit on a virtual thread (2026-10-04,
 * B3) — the capture's DB connection is back in the pool while JobRunr's enqueue waits for the owner pool.
 *
 *   ce1 an enqueue that blocks: capture returns at once, no app connection is held while the enqueue is
 *       still waiting, and the enqueue does happen
 *   ce2 an enqueue that fails: the tracking row becomes 'retry' (never counted) so the sweeper links it
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FulfillmentCaptureEnqueueTest {

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

    @Autowired JdbcTemplate               jdbc;
    @Autowired ObjectMapper               mapper;
    @Autowired FulfillmentTrackingCapture capture;
    @Autowired DataSource                 dataSource;
    @MockBean  BostaGateway               bostaGateway;
    @MockBean  JobScheduler               jobScheduler;

    @BeforeEach void resetMocks() { reset(jobScheduler); }

    @Test
    void ce1_blockingEnqueue_captureReturns_noConnectionHeld() throws Exception {
        UUID[] t = tenantStoreOrder("ce1", "gid://shopify/Order/1891239001");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        when(jobScheduler.enqueue(any(UUID.class), any(JobLambda.class))).thenAnswer(inv -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        });

        long t0 = System.nanoTime();
        TenantContext.runAs(t[0], () -> capture.capture(t[1], "gid://shopify/Order/1891239001", payload("9432163061")));
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(entered.await(3, TimeUnit.SECONDS)).as("the enqueue started").isTrue();
        HikariDataSource pool = (HikariDataSource) ((TenantAwareDataSource) dataSource).getTargetDataSource();
        assertThat(pool.getHikariPoolMXBean().getActiveConnections())
            .as("no app connection held while the enqueue waits").isZero();
        assertThat(ms).as("capture didn't wait for the enqueue").isLessThan(2_000);
        release.countDown();
        verify(jobScheduler, timeout(3_000)).enqueue(eq(BostaFulfillmentLinkService.jobId(t[0], t[2], "9432163061")),
            any(JobLambda.class));
    }

    @Test
    void ce2_failedEnqueue_rowBecomesRetry() {
        UUID[] t = tenantStoreOrder("ce2", "gid://shopify/Order/1891239002");
        when(jobScheduler.enqueue(any(UUID.class), any(JobLambda.class)))
            .thenThrow(new IllegalStateException("owner-pool - Connection is not available"));

        TenantContext.runAs(t[0], () -> capture.capture(t[1], "gid://shopify/Order/1891239002", payload("5829813860")));

        long deadline = System.currentTimeMillis() + 3_000;
        Map<String, Object> row = null;
        while (System.currentTimeMillis() < deadline) {
            row = jdbc.queryForMap("SELECT link_status, link_reason, link_attempts FROM order_fulfillment_tracking " +
                "WHERE tracking_number = '5829813860'");
            if ("retry".equals(row.get("link_status"))) break;
            try { Thread.sleep(50); } catch (InterruptedException ignored) { }
        }
        assertThat(row.get("link_status")).isEqualTo("retry");
        assertThat((String) row.get("link_reason")).startsWith("link job enqueue failed");
        assertThat(row.get("link_attempts")).isEqualTo(0);
    }

    private UUID[] tenantStoreOrder(String tag, String gid) {
        UUID tenantId = UUID.randomUUID(), storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantId, "CE-" + tag);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            storeId, tenantId, tag + "-" + tenantId.toString().substring(0, 8) + ".myshopify.com");
        UUID orderId = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, now()) RETURNING id", UUID.class, tenantId, storeId, gid, "BRK-" + tag);
        return new UUID[]{tenantId, storeId, orderId};
    }

    private ObjectNode payload(String tn) {
        ObjectNode p = mapper.createObjectNode();
        ObjectNode f = p.putArray("fulfillments").addObject();
        f.put("id", 7260882305303L);
        f.put("status", "success");
        f.put("service", "manual");
        f.put("tracking_company", "Bosta");
        f.put("tracking_number", tn);
        f.putArray("tracking_numbers").add(tn);
        return p;
    }
}
