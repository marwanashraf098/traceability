package com.traceability.jobs.watchdog;

import com.traceability.notifications.EmailGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Sends the watchdog's HIGH alert through the app's existing email path ({@link EmailGateway}, SMTP in prod) to
 * the operators in {@code traced.watchdog.alert-emails}, and logs it at ERROR (Sentry picks ERROR logs up).
 *
 * <p>Not through the tenant exception system: a job-server stall is not one merchant's alert, and the
 * existing alert emails are sent BY a JobRunr job (ExceptionImmediateAlertJob) — the very thing that is
 * stalled. Each send runs on its own virtual thread and is waited for at most {@code sendTimeout}, so a hung
 * SMTP server can't hang the watchdog.
 */
public final class EmailWatchdogAlerter implements JobServerWatchdog.Alerter {

    private static final Logger log = LoggerFactory.getLogger(EmailWatchdogAlerter.class);
    private static final ExecutorService SENDER = Executors.newVirtualThreadPerTaskExecutor();

    private final EmailGateway email;
    private final List<String> recipients;
    private final Duration sendTimeout;

    public EmailWatchdogAlerter(EmailGateway email, List<String> recipients, Duration sendTimeout) {
        this.email = email;
        this.recipients = List.copyOf(recipients);
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void alert(String subject, String body) {
        log.error("JobRunr watchdog ALERT: {} — {}", subject, body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
        if (recipients.isEmpty()) {
            log.error("JobRunr watchdog: no alert recipients (TRACED_WATCHDOG_ALERT_EMAILS) — email not sent");
            return;
        }
        for (String to : recipients) {
            try {
                CompletableFuture.runAsync(() -> email.send(to, subject, body), SENDER)
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("JobRunr watchdog: alert email to {} failed: {}", to, e.toString());
            }
        }
    }
}
