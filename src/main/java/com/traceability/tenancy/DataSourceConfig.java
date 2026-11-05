package com.traceability.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Declares two datasources:
 * <ul>
 *   <li>{@code dataSource} (@Primary) — {@link TenantAwareDataSource} wrapping a HikariPool
 *       ({@code spring.datasource.hikari.maximum-pool-size}, 12) connecting as app_user. RLS is
 *       always enforced here.</li>
 *   <li>{@code ownerDataSource} (@FlywayDataSource) — HikariPool ({@code traced.owner-pool.size}, 4)
 *       connecting as the Flyway owner (postgres). Flyway migrations, JobRunr's storage and the
 *       owner-connection jobs share it.</li>
 * </ul>
 * Budget (2026-10-04, Supabase Nano): Supavisor session mode allows 15 connections per user+db and
 * Postgres max_connections is 60. These are the app's ONLY two pools — app_user 12 ≤ 15 and
 * postgres 4 ≤ 15, each with headroom (the dashboard / mgmt-api's own postgres sessions are direct,
 * not through Supavisor). Was 5 + 2: a 50-event Shopify burst (2026-10-03 06:18:55) released 56 jobs
 * at once onto 5 connections and 9 failed on the 5 s connection timeout. Both pools log a leak
 * warning for a connection held over {@code traced.datasource.leak-detection-ms} (20 s).
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.hikari.maximum-pool-size:5}") int poolSize,
            @Value("${spring.datasource.hikari.minimum-idle:1}") int minIdle,
            @Value("${spring.datasource.hikari.connection-timeout:5000}") long timeoutMs,
            @Value("${traced.datasource.leak-detection-ms:20000}") long leakDetectionMs,
            @Value("${traced.datasource.app-socket-timeout-seconds:300}") int socketTimeoutSeconds,
            @Value("${traced.datasource.keepalive-ms:120000}") long keepaliveMs) {

        rejectTransactionPooler(url);

        HikariDataSource raw = new HikariDataSource();
        raw.setJdbcUrl(url);
        raw.setUsername(username);
        raw.setPassword(password);
        raw.setDriverClassName("org.postgresql.Driver");
        raw.setMaximumPoolSize(poolSize);
        raw.setMinimumIdle(minIdle);
        raw.setConnectionTimeout(timeoutMs);
        raw.setLeakDetectionThreshold(leakDetectionMs);
        raw.setPoolName("app-pool");
        applyNetworkSafety(raw, socketTimeoutSeconds, keepaliveMs);
        return new TenantAwareDataSource(raw);
    }

    /**
     * Owner datasource — Flyway (DDL migrations), JobRunr's storage and owner-connection jobs share
     * this pool. Declared as @FlywayDataSource so Spring Boot uses it instead of auto-creating a
     * separate HikariPool (default size 10) from spring.flyway.url properties — there is no other
     * postgres pool in the app. Connection timeout {@code traced.owner-pool.connection-timeout-ms}
     * (3 s): a JobRunr enqueue waiting on it must fail fast (the Shopify webhook endpoint answers
     * within its deadline either way).
     */
    @Bean
    @FlywayDataSource
    public DataSource ownerDataSource(
            @Value("${spring.flyway.url}") String url,
            @Value("${spring.flyway.user}") String user,
            @Value("${spring.flyway.password}") String password,
            @Value("${traced.owner-pool.size:4}") int poolSize,
            @Value("${traced.owner-pool.connection-timeout-ms:3000}") long timeoutMs,
            @Value("${traced.datasource.leak-detection-ms:20000}") long leakDetectionMs,
            @Value("${traced.owner-pool.socket-timeout-seconds:600}") int socketTimeoutSeconds,
            @Value("${traced.datasource.keepalive-ms:120000}") long keepaliveMs) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setMaximumPoolSize(poolSize);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(timeoutMs);
        ds.setLeakDetectionThreshold(leakDetectionMs);
        ds.setPoolName("owner-pool");
        applyNetworkSafety(ds, socketTimeoutSeconds, keepaliveMs);
        return ds;
    }

    /**
     * Network safety for every pool (2026-11-05, after the 2026-11-04 stall where the pooler dropped ~12 app
     * connections mid-query). pgjdbc's defaults are socketTimeout=0 (a read on a dead socket waits FOREVER —
     * the thread and its connection are lost for good) and no TCP keep-alive.
     * <ul>
     *   <li>{@code socketTimeout} (seconds): the longest a single read from the DB may block. Not a query
     *       timeout on the server — it bounds a dead connection. Set above the longest legitimate statement on
     *       that pool (app 300 s; owner 600 s because Flyway migrations run there; JobRunr's own calls get 60 s
     *       on top, {@code JobRunrConfig}; watchdog 15 s).</li>
     *   <li>{@code tcpKeepAlive=true}: the kernel probes an idle-looking connection, so a peer that vanished
     *       is noticed (timing comes from the container's net.ipv4.tcp_keepalive_* sysctls,
     *       deploy/docker-compose.yml: first probe after 60 s, dead after ~2 min).</li>
     *   <li>Hikari {@code keepaliveTime} (0 = off): idle pool connections are pinged so one silently dropped by
     *       the pooler / NAT is replaced before a request borrows it.</li>
     * </ul>
     */
    public static void applyNetworkSafety(HikariDataSource ds, int socketTimeoutSeconds, long keepaliveMs) {
        ds.addDataSourceProperty("socketTimeout", String.valueOf(socketTimeoutSeconds));
        ds.addDataSourceProperty("tcpKeepAlive", "true");
        if (keepaliveMs > 0) ds.setKeepaliveTime(keepaliveMs);
    }

    // Port 6543 = Supabase transaction-mode pooler. SET LOCAL app.current_tenant is
    // reset between statements in transaction mode — every authenticated query returns
    // zero rows under RLS with no error. Fail loudly at startup rather than silently
    // at runtime. Package-private for direct unit testing without a Spring context.
    //
    // Regex //[^/?#]*:(\d+) matches the authority section of the JDBC URL and extracts
    // the host port. [^/?#]* is greedy but stops at / ? # so it never crosses into the
    // database path or query string. This means ?sslmode=require and similar params are
    // ignored, and embedded user:pass@host:port credentials are handled correctly —
    // the greedy match backtracks past user:pass@ to land on the real host port.
    // If no port is present in the URL (defaults to 5432) the regex finds no match and
    // the method returns normally — that is the only legitimate fail-open path.
    private static final Pattern HOST_PORT = Pattern.compile("//[^/?#]*:(\\d+)");

    static void rejectTransactionPooler(String url) {
        Matcher m = HOST_PORT.matcher(url);
        if (m.find() && Integer.parseInt(m.group(1)) == 6543) {
            throw new IllegalStateException(
                "App datasource is on the transaction-mode pooler (port 6543). " +
                "SET LOCAL app.current_tenant would be reset between statements — " +
                "every authenticated query would return zero rows under RLS. " +
                "Use the direct host (db.<ref>.supabase.co:5432) or session-mode pooler (:5432).");
        }
    }
}
