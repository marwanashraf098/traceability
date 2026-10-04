package com.traceability;

import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.tenancy.TenantAwareDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.server.BackgroundJobServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup configuration for DB / job reliability (2026-10-04) — the values the app actually runs with.
 *
 *   cf1 app_user pool 12 (Supavisor allows 15 per user+db), leak detection 20 s, timeout 5 s
 *   cf2 owner (postgres) pool 4, connection timeout 3 s, leak detection 20 s
 *   cf3 JobRunr: 8 workers (not cores × 16), poll interval 5 s (not 15 s)
 *   cf4 the v0 Bosta gateway has HTTP timeouts: connect 5 s, read 20 s
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class PoolAndJobRunrConfigTest {

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
        // The shared test config switches the job server off; this class needs the real one to read
        // the worker count and poll interval it was built with (its own empty DB — recurring jobs no-op).
        r.add("org.jobrunr.background-job-server.enabled", () -> "true");
    }

    @Autowired DataSource dataSource;
    @Autowired @FlywayDataSource DataSource ownerDataSource;
    @Autowired BackgroundJobServer backgroundJobServer;
    @Autowired BostaGateway bostaGateway;
    @MockBean  JobScheduler jobScheduler;

    @Test
    void cf1_appPool() {
        HikariDataSource app = (HikariDataSource) ((TenantAwareDataSource) dataSource).getTargetDataSource();
        assertThat(app.getMaximumPoolSize()).isEqualTo(12);
        assertThat(app.getLeakDetectionThreshold()).isEqualTo(20_000);
        assertThat(app.getConnectionTimeout()).isEqualTo(5_000);
    }

    @Test
    void cf2_ownerPool() {
        HikariDataSource owner = (HikariDataSource) ownerDataSource;
        assertThat(owner.getMaximumPoolSize()).isEqualTo(4);
        assertThat(owner.getConnectionTimeout()).isEqualTo(3_000);
        assertThat(owner.getLeakDetectionThreshold()).isEqualTo(20_000);
    }

    @Test
    void cf3_jobRunrWorkersAndPollInterval() throws Exception {
        var cfg = backgroundJobServer.getConfiguration();
        assertThat(cfg.getPollInterval()).isEqualTo(Duration.ofSeconds(5));
        Object policy = cfg.getBackgroundJobServerWorkerPolicy();
        Field f = policy.getClass().getDeclaredField("workerCount");
        f.setAccessible(true);
        assertThat(f.getInt(policy)).isEqualTo(8);
    }

    @Test
    void cf4_bostaGatewayTimeouts() throws Exception {
        Object restClient = field(bostaGateway, "restClient");
        Object factory = field(restClient, "clientRequestFactory");
        assertThat(factory).isInstanceOf(JdkClientHttpRequestFactory.class);
        assertThat(field(factory, "readTimeout")).isEqualTo(Duration.ofSeconds(20));
        HttpClient client = (HttpClient) field(factory, "httpClient");
        assertThat(client.connectTimeout()).contains(Duration.ofSeconds(5));
    }

    private static Object field(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
