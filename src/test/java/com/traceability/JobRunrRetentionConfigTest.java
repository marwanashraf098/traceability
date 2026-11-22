package com.traceability;

import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.server.BackgroundJobServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JobRunr retention (disk IO fix, 2026-11-22) — the values the job server is actually built with: succeeded jobs
 * are moved to DELETED after 6 h (JobRunr default 36 h) and deleted jobs purged after 24 h (default 72 h), from
 * application.yml (TRACED_JOBRUNR_DELETE_SUCCEEDED_AFTER / TRACED_JOBRUNR_PERMANENTLY_DELETE_AFTER).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class JobRunrRetentionConfigTest {

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
        // The shared test config switches the job server off; this class needs the real one (its own empty DB).
        r.add("org.jobrunr.background-job-server.enabled", () -> "true");
    }

    @Autowired BackgroundJobServer backgroundJobServer;
    @MockBean  JobScheduler jobScheduler;

    @Test
    void rt1_succeededDeletedAfterSixHours_deletedPurgedAfterADay() {
        var cfg = backgroundJobServer.getConfiguration();
        assertThat(cfg.getDeleteSucceededJobsAfter()).isEqualTo(Duration.ofHours(6));
        assertThat(cfg.getPermanentlyDeleteDeletedJobsAfter()).isEqualTo(Duration.ofHours(24));
    }
}
