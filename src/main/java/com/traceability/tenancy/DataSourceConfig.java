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
 *       of size 5 connecting as app_user. RLS is always enforced here.</li>
 *   <li>{@code ownerDataSource} (@FlywayDataSource) — HikariPool of size 2 connecting as the
 *       Flyway owner (postgres). Flyway migrations and JobRunr share this pool. Size 2 + 5 = 7
 *       total connections, comfortably within Supabase free plan's 15-connection cap.</li>
 * </ul>
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
            @Value("${spring.datasource.hikari.connection-timeout:5000}") long timeoutMs) {

        rejectTransactionPooler(url);

        HikariDataSource raw = new HikariDataSource();
        raw.setJdbcUrl(url);
        raw.setUsername(username);
        raw.setPassword(password);
        raw.setDriverClassName("org.postgresql.Driver");
        raw.setMaximumPoolSize(poolSize);
        raw.setMinimumIdle(minIdle);
        raw.setConnectionTimeout(timeoutMs);
        return new TenantAwareDataSource(raw);
    }

    /**
     * Owner datasource — Flyway (DDL migrations) and JobRunr share this 2-connection pool.
     * Declared as @FlywayDataSource so Spring Boot uses it instead of auto-creating a
     * separate HikariPool (default size 10) from spring.flyway.url properties.
     * Total connection budget with Supabase free plan (15 max):
     *   owner-pool (2) + app_user pool (5) = 7 — leaves 8 slots for pgAdmin / psql.
     */
    @Bean
    @FlywayDataSource
    public DataSource ownerDataSource(
            @Value("${spring.flyway.url}") String url,
            @Value("${spring.flyway.user}") String user,
            @Value("${spring.flyway.password}") String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setMaximumPoolSize(2);
        ds.setMinimumIdle(1);
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
