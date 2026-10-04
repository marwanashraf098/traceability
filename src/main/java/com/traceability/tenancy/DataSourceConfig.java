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
            @Value("${traced.datasource.leak-detection-ms:20000}") long leakDetectionMs) {

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
            @Value("${traced.datasource.leak-detection-ms:20000}") long leakDetectionMs) {
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
        return ds;
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
