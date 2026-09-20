package com.traceability;

import com.traceability.demo.DemoBootstrapStartupListener;
import com.traceability.demo.DemoSeeder;
import com.traceability.demo.DemoStartRequest;
import com.traceability.demo.DemoStartResponse;
import com.traceability.demo.DemoStartService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-DEMO hotfix — proves DemoStartService.start()'s OWN ensureBootstrapped() call (layer 2
 * of the fix) self-heals even if the startup listener (layer 1) never ran — e.g. a startup
 * ordering issue, or (as actually happened in prod) a build that predates the listener.
 *
 * DemoBootstrapStartupListener is @MockBean'd here specifically to neutralize layer 1: Spring
 * still fires the real ApplicationReadyEvent, but the mocked bean's onApplicationReady() is a
 * no-op, so nothing bootstraps the demo tenant during context startup. This isolates layer 2
 * as the only thing standing between a cold, empty database and a working /demo/start call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoStartColdBootTest {

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
        r.add("shopify.api-version",        () -> "2024-10");
        r.add("shopify.client-id",          () -> "test-client-id");
        r.add("shopify.client-secret",      () -> "test-client-secret");
        r.add("shopify.scopes",             () -> "read_products");
        r.add("shopify.webhook-base-url",   () -> "https://test.example.com");
        r.add("bosta.api-base-url",         () -> "https://app.bosta.co");
    }

    @MockBean JobScheduler                  jobScheduler;
    @MockBean ShopifyGateway                shopifyGateway;
    @MockBean ShopifyTokenProvider          tokenProvider;
    // Neutralizes layer 1 — see class javadoc.
    @MockBean DemoBootstrapStartupListener  demoBootstrapStartupListener;

    @Autowired JdbcTemplate      jdbc;
    @Autowired DemoStartService  demoStartService;

    @Test
    void start_onColdEmptyDatabase_selfHealsAndReturns200_notA500() {
        // Confirm the premise: no demo tenant exists yet — layer 1 was neutralized and
        // nothing else in this test class pre-seeds it.
        Long tenantCountBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenants WHERE is_demo = true", Long.class);
        assertThat(tenantCountBefore).as("cold DB: no demo tenant before start()").isZero();

        DemoStartRequest req = new DemoStartRequest(
                "Cold Boot Visitor", "cold-" + UUID.randomUUID() + "@example.com", "0100", true);

        DemoStartResponse resp = demoStartService.start(req, "203.0.113.5");

        assertThat(resp).isNotNull();
        assertThat(resp.accessToken()).isNotBlank();
        assertThat(resp.redirect()).isEqualTo("/overview");

        Long tenantCountAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenants WHERE is_demo = true", Long.class);
        assertThat(tenantCountAfter).as("self-healed exactly one demo tenant").isEqualTo(1L);

        Boolean isDemo = jdbc.queryForObject(
                "SELECT is_demo FROM tenants WHERE id = ?", Boolean.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(isDemo).isTrue();
    }
}
