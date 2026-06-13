package com.traceability.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Declares the primary application DataSource as a {@link TenantAwareDataSource}.
 *
 * Flyway does NOT use this bean. When {@code spring.flyway.url/user/password}
 * are set, Spring Boot's FlywayAutoConfiguration creates a separate internal
 * datasource from those properties and never touches the primary bean declared
 * here. Flyway therefore runs as the owner role (postgres / DDL privileges)
 * while the app runtime connects as app_user (no BYPASSRLS → RLS enforced).
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize,
            @Value("${spring.datasource.hikari.connection-timeout:5000}") long timeoutMs) {

        rejectTransactionPooler(url);

        HikariDataSource raw = new HikariDataSource();
        raw.setJdbcUrl(url);
        raw.setUsername(username);
        raw.setPassword(password);
        raw.setDriverClassName("org.postgresql.Driver");
        raw.setMaximumPoolSize(poolSize);
        raw.setConnectionTimeout(timeoutMs);
        return new TenantAwareDataSource(raw);
    }

    // Port 6543 = Supabase transaction-mode pooler. SET LOCAL app.current_tenant is
    // reset between statements in transaction mode — every authenticated query returns
    // zero rows under RLS with no error. Fail loudly at startup rather than silently
    // at runtime. Package-private for direct unit testing without a Spring context.
    static void rejectTransactionPooler(String url) {
        try {
            URI uri = new URI(url.replaceFirst("^jdbc:", ""));
            if (uri.getPort() == 6543) {
                throw new IllegalStateException(
                    "App datasource is on the transaction-mode pooler (port 6543). " +
                    "SET LOCAL app.current_tenant would be reset between statements — " +
                    "every authenticated query would return zero rows under RLS. " +
                    "Use the direct host (db.<ref>.supabase.co:5432) or session-mode pooler (:5432).");
            }
        } catch (URISyntaxException e) {
            // Unparseable URL — let HikariCP surface the connection error at startup.
        }
    }
}
