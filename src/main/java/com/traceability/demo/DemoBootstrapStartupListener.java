package com.traceability.demo;

import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * FR-DEMO — closes the "never bootstrapped before the first 30-min DemoReseedJob tick" gap.
 *
 * DemoReseedJob's {@code cron = "*&#47;30 * * * *"} fires at fixed wall-clock minutes (:00/:30),
 * not "30 minutes after boot" — a deploy landing at, say, minute :05 leaves up to a ~30-minute
 * window where the demo tenant genuinely does not exist, and any real traffic to
 * {@code POST /api/v1/public/demo/start} in that window 500s (confirmed prod incident).
 *
 * This listener fires {@link DemoSeeder#ensureBootstrapped()} once at application startup
 * instead — DemoSeeder and DemoReseedJob are both untouched. {@code ensureBootstrapped()}
 * itself uses the ordinary primary (app_user) connection under
 * {@code TenantContext.runAs(DEMO_TENANT_ID, ...)} — the fixed, known tenant id means there is
 * no chicken-and-egg problem requiring the BYPASSRLS owner connection {@code reseed()} needs
 * for its cross-tenant resolve; this listener's own existence check (done purely to pick the
 * right log line — DemoSeeder's own internal log line only fires on the create path) mirrors
 * that exact same connection/context.
 *
 * Deliberately does NOT call {@code reseed()} — the fixture load/refresh stays exclusively
 * owned by DemoReseedJob's recurring schedule.
 */
@Component
public class DemoBootstrapStartupListener {

    private static final Logger log = LoggerFactory.getLogger(DemoBootstrapStartupListener.class);

    private final DemoSeeder   demoSeeder;
    private final JdbcTemplate jdbc;

    public DemoBootstrapStartupListener(DemoSeeder demoSeeder, JdbcTemplate jdbc) {
        this.demoSeeder = demoSeeder;
        this.jdbc       = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        boolean existedBefore = TenantContext.runAs(DemoSeeder.DEMO_TENANT_ID, () -> Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM tenants WHERE id = ?)",
                        Boolean.class, DemoSeeder.DEMO_TENANT_ID)));

        demoSeeder.ensureBootstrapped();

        if (existedBefore) {
            log.info("Demo tenant already present");
        } else {
            log.info("Demo tenant bootstrapped: {}", DemoSeeder.DEMO_TENANT_ID);
        }
    }
}
