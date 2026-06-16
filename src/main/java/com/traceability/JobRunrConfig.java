package com.traceability;

import org.jobrunr.storage.StorageProvider;
import org.jobrunr.storage.sql.postgres.PostgresStorageProvider;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wires JobRunr's StorageProvider to the shared owner datasource declared in
 * DataSourceConfig (@FlywayDataSource). Flyway and JobRunr share that 2-connection
 * pool — no third pool is created. The primary TenantAwareDataSource is intentionally
 * NOT used here — JobRunr's own tables have no RLS.
 */
@Configuration
public class JobRunrConfig {

    @Bean
    public StorageProvider storageProvider(@FlywayDataSource DataSource ownerDs) {
        return new PostgresStorageProvider(ownerDs);
    }
}
