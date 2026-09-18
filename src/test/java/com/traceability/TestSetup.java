package com.traceability;

import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Sets app_user's password after Flyway has created the role (V1 migration).
 * Runs once per test application context via ApplicationReadyEvent.
 * The hard-coded password is test-only; app_user's production password
 * is set out-of-band and never appears in source code.
 *
 * Deliberately uses the @FlywayDataSource (owner/postgres) bean, NOT the @Primary
 * `dataSource` bean — most test classes override spring.datasource.username to the
 * Testcontainers superuser for RLS-friction-free testing, but some (e.g.
 * ShopifyConnectAmbientContextTest) deliberately configure the @Primary bean as
 * app_user itself, to genuinely exercise RLS end to end. ALTER USER needs superuser/
 * CREATEROLE privilege, which app_user does not have — this must keep working
 * regardless of what the @Primary bean is wired to in a given test class.
 */
@Component
class TestSetup {

    private final JdbcTemplate ownerJdbc;

    TestSetup(@FlywayDataSource DataSource ownerDataSource) {
        this.ownerJdbc = new JdbcTemplate(ownerDataSource);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ownerJdbc.execute("ALTER USER app_user WITH PASSWORD 'testpw'");
    }
}
