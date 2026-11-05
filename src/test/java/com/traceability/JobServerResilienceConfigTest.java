package com.traceability;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.jobs.watchdog.JobServerWatchdog;
import com.traceability.jobs.watchdog.JobServerWatchdogConfig;
import com.traceability.tenancy.NetworkTimeoutDataSource;
import com.traceability.tenancy.TenantAwareDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the app actually starts with after the 2026-11-05 job-server hardening (job server ON, like prod):
 *
 *   r1 both pools: JDBC socketTimeout (app 300 s, owner 600 s), tcpKeepAlive, Hikari keepalive 2 min
 *   r2 JobRunr's StorageProvider uses a NetworkTimeoutDataSource (60 s) over the owner pool
 *   r3 a connection borrowed through it really can't block past its timeout (pg_sleep), and the pool gets
 *      the connection back with its own timeout (the next plain borrower isn't cut short)
 *   r4 the main Shopify Admin API client: connect 5 s, read 30 s
 *   r5 the watchdog is wired, with its own one-connection read-only pool — not a DataSource bean
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class JobServerResilienceConfigTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = TestPostgres.newContainer()
        .withDatabaseName("traceability_test").withUsername("postgres").withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("org.jobrunr.background-job-server.enabled", () -> "true");
    }

    @Autowired DataSource dataSource;
    @Autowired @FlywayDataSource DataSource ownerDataSource;
    @Autowired StorageProvider storageProvider;
    @Autowired ShopifyGateway shopifyGateway;
    @Autowired ApplicationContext ctx;
    @MockBean  JobScheduler jobScheduler;

    @Test
    void r1_poolsHaveSocketTimeoutAndKeepAlive() {
        HikariDataSource app = (HikariDataSource) ((TenantAwareDataSource) dataSource).getTargetDataSource();
        assertThat(app.getDataSourceProperties()).containsEntry("socketTimeout", "300").containsEntry("tcpKeepAlive", "true");
        assertThat(app.getKeepaliveTime()).isEqualTo(120_000);
        HikariDataSource owner = (HikariDataSource) ownerDataSource;
        assertThat(owner.getDataSourceProperties()).containsEntry("socketTimeout", "600").containsEntry("tcpKeepAlive", "true");
        assertThat(owner.getKeepaliveTime()).isEqualTo(120_000);
    }

    @Test
    void r2_jobRunrStorage_hasNetworkTimeout() throws Exception {
        Object ds = field(storageProvider, "dataSource");
        assertThat(ds).isInstanceOf(NetworkTimeoutDataSource.class);
        assertThat(field(ds, "timeoutMs")).isEqualTo(60_000);
        try (Connection c = ((DataSource) ds).getConnection()) {
            assertThat(c.getNetworkTimeout()).isEqualTo(60_000);
        }
    }

    @Test
    void r3_borrowedConnection_cannotBlockPastItsTimeout_andPoolResetsIt() throws Exception {
        NetworkTimeoutDataSource oneSecond = new NetworkTimeoutDataSource(ownerDataSource, 1000);
        long start = System.nanoTime();
        try (Connection c = oneSecond.getConnection(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute("SELECT pg_sleep(5)")).isInstanceOf(SQLException.class);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));

        try (Connection c = ownerDataSource.getConnection(); Statement s = c.createStatement()) {
            assertThat(c.getNetworkTimeout()).isNotEqualTo(1000);
            s.execute("SELECT pg_sleep(1.5)");            // not cut at 1 s
        }
    }

    @Test
    void r4_shopifyMainClient_hasTimeouts() throws Exception {
        Object restClient = field(shopifyGateway, "restClient");
        Object factory = field(restClient, "clientRequestFactory");
        assertThat(readTimeout(factory)).isEqualTo(30_000L);
        assertThat(connectTimeout(factory)).isEqualTo(5_000L);
    }

    @Test
    void r5_watchdogWired_withItsOwnPool() {
        assertThat(ctx.getBeansOfType(JobServerWatchdog.class)).hasSize(1);
        JobServerWatchdogConfig.WatchdogPool pool = ctx.getBean(JobServerWatchdogConfig.WatchdogPool.class);
        assertThat(pool.ds().getMaximumPoolSize()).isEqualTo(1);
        assertThat(pool.ds().isReadOnly()).isTrue();
        assertThat(pool.ds().getDataSourceProperties()).containsEntry("socketTimeout", "15");
        assertThat(ctx.getBeansOfType(DataSource.class).values()).doesNotContain(pool.ds());
    }

    /** Read / connect timeout in ms, whichever factory type Spring Boot picked. */
    static long readTimeout(Object factory) throws Exception {
        for (String n : new String[] {"readTimeout"}) {
            Object v = fieldOrNull(factory, n);
            if (v instanceof Duration d) return d.toMillis();
            if (v instanceof Number num) return num.longValue();
        }
        throw new AssertionError("no read timeout on " + factory.getClass());
    }

    static long connectTimeout(Object factory) throws Exception {
        Object v = fieldOrNull(factory, "connectTimeout");
        if (v instanceof Duration d) return d.toMillis();
        if (v instanceof Number num) return num.longValue();
        Object client = fieldOrNull(factory, "httpClient");
        if (client instanceof java.net.http.HttpClient h) return h.connectTimeout().orElseThrow().toMillis();
        throw new AssertionError("no connect timeout on " + factory.getClass());
    }

    static Object field(Object target, String name) throws Exception {
        Object v = fieldOrNull(target, name);
        if (v == null) throw new NoSuchFieldException(name + " on " + target.getClass());
        return v;
    }

    static Object fieldOrNull(Object target, String name) throws Exception {
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
        return null;
    }
}
