package com.traceability;

import com.traceability.demo.DemoSeeder;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-DEMO hotfix — DEFECT 2: DemoBootstrapStartupListener was fail-dangerous. An uncaught
 * exception from ensureBootstrapped() (as happened in prod — see DemoSeederConcurrentBootstrapTest's
 * class javadoc for the race that caused it) propagated out of an
 * {@code @EventListener(ApplicationReadyEvent.class)} method, which aborts the WHOLE
 * application context — every tenant on the box, not just the demo one.
 *
 * A real DemoSeeder bean is replaced (via @Primary in a nested @TestConfiguration, not a
 * Mockito @MockBean) with one whose ensureBootstrapped() unconditionally throws. Mockito
 * @MockBean stubbing (when(...)/doThrow(...)) can only be configured from a @Test method,
 * which runs AFTER ApplicationReadyEvent has already fired during context startup — too late
 * to affect this listener's very first (and only) invocation. Overriding the bean itself
 * guarantees the throwing behavior is active from the moment the real event fires, which is
 * what makes this test a genuine, end-to-end proof that startup is NOT aborted: if this test
 * class's @SpringBootTest context comes up at all, the listener already survived the throw.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(DemoBootstrapFailSoftTest.ThrowingDemoSeederConfig.class)
class DemoBootstrapFailSoftTest {

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

    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    @Autowired ConfigurableApplicationContext context;
    @Autowired DemoSeeder                     demoSeeder;

    @TestConfiguration
    static class ThrowingDemoSeederConfig {
        @Bean
        @Primary
        DemoSeeder demoSeeder(JdbcTemplate jdbc,
                              @FlywayDataSource DataSource ownerDs,
                              PasswordEncoder passwordEncoder,
                              PlatformTransactionManager txm) {
            return new DemoSeeder(jdbc, ownerDs, passwordEncoder, txm) {
                @Override
                public void ensureBootstrapped() {
                    throw new RuntimeException("Simulated demo bootstrap failure (test)");
                }
            };
        }
    }

    @Test
    void bootstrapFailureAtStartup_doesNotAbortApplicationContext() {
        // If this test method runs at all, @SpringBootTest's context finished starting —
        // meaning the real ApplicationReadyEvent fired, DemoBootstrapStartupListener called
        // this (overridden, throwing) ensureBootstrapped(), it threw, and the listener's
        // try/catch swallowed it rather than propagating and aborting context refresh.
        assertThat(context.isActive()).isTrue();
        // Confirms the bean in the context really is the throwing override (i.e. this test
        // is exercising the intended failure path, not silently using a working DemoSeeder).
        assertThat(demoSeeder.getClass()).isNotEqualTo(DemoSeeder.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(demoSeeder::ensureBootstrapped)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated demo bootstrap failure");
    }
}
