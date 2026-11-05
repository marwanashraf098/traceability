package com.traceability.jobs.watchdog;

import com.traceability.notifications.EmailGateway;
import com.traceability.tenancy.DataSourceConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jobrunr.server.BackgroundJobServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Wires the {@link JobServerWatchdog}: only where the JobRunr server runs (never in tests, where it's off) and
 * {@code traced.watchdog.enabled} (default true; env TRACED_WATCHDOG_ENABLED).
 *
 * <p>Off JobRunr on purpose: a dedicated one-thread {@link ThreadPoolTaskScheduler} ("jobrunr-watchdog-1"),
 * fixed delay {@code interval-seconds}, and its own DB pool "watchdog-pool": ONE connection, owner credentials
 * (the same role as the owner pool — JobRunr's tables and the cross-tenant count need it; it reads aggregates
 * only), read-only, statement_timeout 10 s, JDBC socket timeout 15 s, TCP keep-alive. Supavisor budget for the
 * owner user: owner-pool 4 + watchdog 1 = 5 of 15.
 */
@Configuration
@ConditionalOnProperty(name = {"org.jobrunr.background-job-server.enabled", "traced.watchdog.enabled"},
                       havingValue = "true")
public class JobServerWatchdogConfig {

    private static final Logger log = LoggerFactory.getLogger(JobServerWatchdogConfig.class);

    /** Holder, not a DataSource bean: keeps the pool out of type-based autowiring and the actuator health check. */
    public record WatchdogPool(HikariDataSource ds) implements AutoCloseable {
        @Override public void close() { ds.close(); }
    }

    @Bean(destroyMethod = "close")
    WatchdogPool watchdogPool(
            @Value("${spring.flyway.url}") String url,
            @Value("${spring.flyway.user}") String user,
            @Value("${spring.flyway.password}") String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setPoolName("watchdog-pool");
        ds.setMaximumPoolSize(1);
        ds.setMinimumIdle(0);
        ds.setConnectionTimeout(5000);
        ds.setReadOnly(true);
        // Read-only AT THE DB too (not only the JDBC flag): every transaction on these sessions is read-only,
        // so no statement through this pool can write, whatever the code does.
        ds.setConnectionInitSql("SET default_transaction_read_only = on; SET statement_timeout = '10s'");
        DataSourceConfig.applyNetworkSafety(ds, 15, 0);
        return new WatchdogPool(ds);
    }

    @Bean
    JobServerWatchdog jobServerWatchdog(
            WatchdogPool watchdogPool,
            ObjectProvider<BackgroundJobServer> server,
            EmailGateway email,
            @Value("${traced.watchdog.alert-emails:}") String alertEmails,
            @Value("${traced.watchdog.webhook-stale-minutes:3}") long staleMinutes,
            @Value("${shopify.webhook.retry.max-attempts:5}") int maxAttempts,
            @Value("${traced.watchdog.liveness-recurring-job-id:bosta-discovery-poll}") String livenessJobId,
            @Value("${bosta.poll.discovery-cron:*/20 * * * *}") String livenessCron,
            @Value("${traced.watchdog.liveness-max-age-minutes:5}") long livenessMinutes,
            @Value("${traced.watchdog.heartbeat-max-age-seconds:60}") long heartbeatSeconds,
            @Value("${traced.watchdog.startup-grace-minutes:5}") long graceMinutes,
            @Value("${traced.watchdog.escalate-after-minutes:5}") long escalateMinutes,
            @Value("${traced.watchdog.restart-timeout-seconds:120}") long restartTimeoutSeconds,
            @Value("${traced.watchdog.exit-on-escalation:false}") boolean exitOnEscalation) {
        List<String> recipients = Arrays.stream(alertEmails.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (recipients.isEmpty()) {
            log.warn("JobRunr watchdog: TRACED_WATCHDOG_ALERT_EMAILS is not set — alerts go to the log only");
        }
        WatchdogDbProbe probe = new WatchdogDbProbe(watchdogPool.ds(),
            () -> { BackgroundJobServer s = server.getIfAvailable(); return s == null ? null : s.getId(); },
            Duration.ofMinutes(staleMinutes), maxAttempts, livenessJobId, livenessCron,
            Duration.ofMinutes(livenessMinutes), Duration.ofSeconds(heartbeatSeconds));
        log.info("JobRunr watchdog: liveness job '{}' window {} min, exit on escalation: {}",
            livenessJobId, probe.livenessWindow().toMinutes(), exitOnEscalation);
        return new JobServerWatchdog(probe,
            new JobRunrServerControl(server::getIfAvailable, Duration.ofSeconds(restartTimeoutSeconds)),
            new EmailWatchdogAlerter(email, recipients, Duration.ofSeconds(30)),
            new ThreadDumps(), Clock.systemUTC(),
            Duration.ofMinutes(graceMinutes), Duration.ofMinutes(escalateMinutes),
            exitOnEscalation, JobServerWatchdog.halting(Duration.ofSeconds(30)));
    }

    /** The watchdog's own scheduler thread — never shared with @Scheduled or JobRunr. */
    @Bean
    SmartLifecycle jobServerWatchdogScheduler(JobServerWatchdog watchdog,
            @Value("${traced.watchdog.interval-seconds:60}") long intervalSeconds) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("jobrunr-watchdog-");
        scheduler.setDaemon(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return new SmartLifecycle() {
            private volatile boolean running;
            @Override public void start() {
                scheduler.initialize();
                scheduler.scheduleWithFixedDelay(watchdog::tick, Duration.ofSeconds(intervalSeconds));
                running = true;
                log.info("JobRunr watchdog: started (every {} s)", intervalSeconds);
            }
            @Override public void stop() { scheduler.shutdown(); running = false; }
            @Override public boolean isRunning() { return running; }
        };
    }
}
