package com.traceability;

import com.traceability.integrations.bosta.BostaDiscoveryPollJob;
import com.traceability.integrations.bosta.BostaGateway;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Mirrors the bed889d fix's intent for BostaStatusPollJob, applied to
 * BostaDiscoveryPollJob: disabling via config must NOT remove the Spring bean. A
 * class-level @ConditionalOnProperty means the bean is simply absent when the flag is
 * false, but JobRunr's own persistent recurring-job entry still fires on its cron
 * schedule regardless — it can't resolve the missing bean, marks that run failed, and
 * immediately reschedules, producing rapid-fire (~5s) re-execution until someone
 * notices (the exact bug bed889d fixed for BostaStatusPollJob; BostaDiscoveryPollJob
 * had the same class-level annotation until this change).
 *
 * This test verifies the two things that are practically unit-testable here: the bean
 * still exists when bosta.poll.discovery-enabled=false, and discoverAll() is then a
 * safe no-op. It does not attempt to reproduce JobRunr's internal reschedule timing —
 * that needs a live BackgroundJobServer against real persistent storage, not a fit for
 * a fast unit test, and bed889d itself shipped without one for the same reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class BostaDiscoveryKillSwitchTest {

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
        r.add("bosta.poll.discovery-enabled", () -> "false");
    }

    @Autowired BostaDiscoveryPollJob discoveryPollJob;
    @MockBean  BostaGateway          bostaGateway;
    @MockBean  JobScheduler          jobScheduler;

    @Test
    void discoveryDisabled_beanStillExists_discoverAllIsSafeNoOp() {
        assertThat(discoveryPollJob).isNotNull();

        discoveryPollJob.discoverAll();

        verify(bostaGateway, never()).listDeliveriesPage(anyString(), anyInt(), anyInt());
        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());
    }
}
