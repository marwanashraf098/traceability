package com.traceability;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Sets app_user's password after Flyway has created the role (V1 migration).
 * Runs once per test application context via ApplicationReadyEvent.
 * The hard-coded password is test-only; app_user's production password
 * is set out-of-band and never appears in source code.
 */
@Component
class TestSetup {

    private final JdbcTemplate jdbc;

    TestSetup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        jdbc.execute("ALTER USER app_user WITH PASSWORD 'testpw'");
    }
}
