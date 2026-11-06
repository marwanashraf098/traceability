package com.traceability.inventory;

import com.traceability.notifications.EmailGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Internal (Traced operators) alert for the Main reconcile sweep — a store whose sweep failed twice in a row.
 * Logged at ERROR (Sentry picks ERROR logs up) and emailed to {@code traced.main-reconcile.alert-emails}, which defaults
 * to the watchdog's operator list. Not the tenant exception system: it is a Traced job failing, not something the
 * merchant can act on. Each send runs on its own virtual thread, waited for at most 30 s, so a hung SMTP server can't
 * hold the sweep (the JobServerWatchdog's EmailWatchdogAlerter pattern).
 */
@Component
public class MainReconcileAlerter {

    private static final Logger log = LoggerFactory.getLogger(MainReconcileAlerter.class);
    private static final ExecutorService SENDER = Executors.newVirtualThreadPerTaskExecutor();
    private static final long SEND_TIMEOUT_MS = 30_000;

    private final EmailGateway email;
    private final List<String> recipients;

    public MainReconcileAlerter(EmailGateway email,
                                @Value("${traced.main-reconcile.alert-emails:${traced.watchdog.alert-emails:}}") String csv) {
        this.email = email;
        this.recipients = Arrays.stream(csv == null ? new String[0] : csv.split(","))
            .map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    public void alert(String subject, String body) {
        log.error("Main reconcile ALERT: {} — {}", subject, body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
        if (recipients.isEmpty()) {
            log.error("Main reconcile: no alert recipients (TRACED_MAIN_RECONCILE_ALERT_EMAILS / TRACED_WATCHDOG_ALERT_EMAILS) — email not sent");
            return;
        }
        for (String to : recipients) {
            try {
                CompletableFuture.runAsync(() -> email.send(to, subject, body), SENDER).get(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Main reconcile: alert email to {} failed: {}", to, e.toString());
            }
        }
    }
}
