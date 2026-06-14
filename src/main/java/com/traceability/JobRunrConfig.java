package com.traceability;

import com.zaxxer.hikari.HikariDataSource;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.storage.sql.postgres.PostgresStorageProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires JobRunr's StorageProvider to the Flyway (owner/DDL) datasource so that
 * JobRunr can CREATE TABLE jobrunr_* on first start without needing the
 * runtime app_user to have DDL privileges.
 *
 * The primary TenantAwareDataSource is intentionally NOT used here — JobRunr's
 * own tables have no RLS and don't need the tenant GUC machinery.
 */
@Configuration
public class JobRunrConfig {

    @Bean
    public StorageProvider storageProvider(
            @Value("${spring.flyway.url}") String url,
            @Value("${spring.flyway.user}") String user,
            @Value("${spring.flyway.password}") String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setMaximumPoolSize(3);
        ds.setPoolName("jobrunr-owner-pool");
        return new PostgresStorageProvider(ds);
    }
}
