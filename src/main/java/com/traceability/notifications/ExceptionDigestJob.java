package com.traceability.notifications;

import com.traceability.inventory.ExceptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Hybrid exception notifications, digest half: once a day at 08:00 Africa/Cairo, emails
 * each tenant's owners+managers a consolidated summary of ALL currently-open exceptions
 * (every severity) via {@link ExceptionService#detectAllOpen()} — the SAME full-detect
 * method the exceptions list page and the immediate sweep use.
 *
 * "New since last summary" = open exceptions with no exception_notifications row for
 * channel='digest' yet — deliberately NOT an occurred_at time window, because occurred_at
 * is unreliable as a "first arose" signal for several detectors (last-sync proxies,
 * threshold-delayed onset). No cross-channel special-casing: an exception that already
 * got an immediate CRITICAL/HIGH email still appears in the digest's "new" section — the
 * consolidated morning list is intentional, not a duplicate-suppression bug.
 *
 * Send-then-record: digest-channel ledger rows are inserted only after send() succeeds
 * for every recipient, and only for the newly-included items (the roll-up counts never
 * touch the ledger).
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ExceptionDigestJob {

    private static final Logger log = LoggerFactory.getLogger(ExceptionDigestJob.class);

    // {{DIGEST_DATE}} template token format — computed here (Africa/Cairo, via the injected
    // Clock) and passed into the formatter; the formatter never recomputes a server-local date.
    private static final DateTimeFormatter DIGEST_DATE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final JdbcTemplate ownerJdbc;
    private final JdbcTemplate jdbc;
    private final ExceptionService exceptionService;
    private final EmailGateway emailGateway;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Value("${shopify.app-url:http://localhost:5173}")
    private String appUrl;

    public ExceptionDigestJob(
            @FlywayDataSource DataSource ownerDs,
            JdbcTemplate jdbc,
            ExceptionService exceptionService,
            EmailGateway emailGateway,
            PlatformTransactionManager txm,
            Clock clock) {
        this.ownerJdbc        = new JdbcTemplate(ownerDs);
        this.jdbc             = jdbc;
        this.exceptionService = exceptionService;
        this.emailGateway     = emailGateway;
        this.tx               = new TransactionTemplate(txm);
        this.clock            = clock;
    }

    @Recurring(id = "exception-daily-digest", cron = "0 8 * * *", zoneId = "Africa/Cairo")
    @Job(name = "Exception daily digest")
    public void run() {
        List<UUID> tenantIds = ownerJdbc.queryForList("SELECT id FROM tenants", UUID.class);
        for (UUID tenantId : tenantIds) {
            try {
                processTenant(tenantId);
            } catch (Exception e) {
                log.warn("Exception digest failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    private void processTenant(UUID tenantId) {
        TenantContext.set(tenantId);
        try {
            List<Map<String, Object>> allOpen = exceptionService.detectAllOpen();
            if (allOpen.isEmpty()) return;

            Set<String> alreadyDigested = tx.execute(s -> {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT exception_type, subject_key FROM exception_notifications " +
                        "WHERE tenant_id = ? AND channel = 'digest'",
                        tenantId);
                Set<String> keys = new HashSet<>();
                for (Map<String, Object> row : rows) {
                    keys.add(row.get("exception_type") + " " + row.get("subject_key"));
                }
                return keys;
            });

            List<Map<String, Object>> newSinceLast = allOpen.stream()
                    .filter(e -> !alreadyDigested.contains(itemKey(e)))
                    .toList();

            ExceptionService.OpenExceptionCounts counts = exceptionService.countOpenExceptionsBySeverity();

            List<String> recipients = tx.execute(s -> jdbc.queryForList(
                    "SELECT email FROM users WHERE tenant_id = ? AND role IN ('owner','manager') AND active = true",
                    String.class, tenantId));
            if (recipients.isEmpty()) {
                log.info("Digest: tenant {} has {} open exception(s) but no active owner/manager " +
                        "recipients — skipping", tenantId, allOpen.size());
                return;
            }

            String subject = buildSubject();
            String body = ExceptionEmailFormatter.buildDigestBody(
                    newSinceLast, counts.total(), counts.critical(), counts.warning(),
                    DIGEST_DATE_FORMAT.format(LocalDate.now(clock)), appUrl);

            for (String email : recipients) {
                emailGateway.send(email, subject, body);
            }

            // Only reached once every send() above has returned successfully. Roll-up counts
            // never touch the ledger — only the newly-itemized items do.
            tx.execute(s -> {
                for (Map<String, Object> e : newSinceLast) {
                    jdbc.update(
                            "INSERT INTO exception_notifications " +
                            "(tenant_id, exception_type, subject_key, channel) VALUES (?, ?, ?, 'digest') " +
                            "ON CONFLICT (tenant_id, exception_type, subject_key, channel) DO NOTHING",
                            tenantId, e.get("type"), e.get("subject_key"));
                }
                return null;
            });
        } finally {
            TenantContext.clear();
        }
    }

    private static String itemKey(Map<String, Object> item) {
        return item.get("type") + " " + item.get("subject_key");
    }

    private String buildSubject() {
        return "Daily exceptions summary — " + LocalDate.now(clock) + " · Traced";
    }

}
