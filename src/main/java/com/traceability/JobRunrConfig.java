package com.traceability;

import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.storage.sql.postgres.PostgresStorageProvider;
import org.jobrunr.utils.mapper.jackson.JacksonJsonMapper;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wires JobRunr's StorageProvider to the shared owner datasource declared in
 * DataSourceConfig (@FlywayDataSource). Flyway and JobRunr share that 2-connection
 * pool — no third pool is created. The primary TenantAwareDataSource is intentionally
 * NOT used here — JobRunr's own tables have no RLS.
 *
 * setJobMapper() is called here (rather than relying on BackgroundJobServer to call
 * it later) because RecurringJobPostProcessor is a BeanPostProcessor that fires per-bean
 * during context initialization — potentially before BackgroundJobServer is created.
 * If jobMapper is null when RecurringJobTable is first constructed, the requireNonNull
 * in its constructor throws NPE. Wiring it here guarantees it is set at bean creation time.
 *
 * JacksonJsonMapper() no-arg creates its own ObjectMapper with JobRunr-specific type
 * settings. Do NOT pass Spring's shared ObjectMapper — JacksonJsonMapper mutates it
 * with activateDefaultTyping(), which breaks Spring MVC JSON deserialization globally.
 */
@Configuration
public class JobRunrConfig {

    @Bean
    public StorageProvider storageProvider(@FlywayDataSource DataSource ownerDs) {
        PostgresStorageProvider provider = new PostgresStorageProvider(ownerDs);
        provider.setJobMapper(new JobMapper(new JacksonJsonMapper()));
        return provider;
    }
}
