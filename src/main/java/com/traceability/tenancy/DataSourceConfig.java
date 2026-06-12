package com.traceability.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

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

        HikariDataSource raw = new HikariDataSource();
        raw.setJdbcUrl(url);
        raw.setUsername(username);
        raw.setPassword(password);
        raw.setDriverClassName("org.postgresql.Driver");
        raw.setMaximumPoolSize(poolSize);
        raw.setConnectionTimeout(timeoutMs);
        return new TenantAwareDataSource(raw);
    }
}
