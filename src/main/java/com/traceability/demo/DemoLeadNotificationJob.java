package com.traceability.demo;

import com.traceability.notifications.EmailGateway;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * FR-DEMO Day 2 — notifies the team of a new demo lead. Enqueued from DemoStartService after
 * the demo_leads row commits, same fire-and-forget shape as WelcomeEmailJob: the payload is
 * passed in at enqueue time rather than read back from the DB, so no owner-connection read of
 * demo_leads is needed here at all (app_user has no SELECT on it — V93 — and this job doesn't
 * need one).
 *
 * NOT tenant-iterating — this is a direct send to a fixed team address, structurally unrelated
 * to ExceptionDigestJob/ExceptionImmediateAlertJob's per-tenant `is_demo = false` filter (that
 * guard stops those two jobs from emailing a TENANT's own owner about that tenant's exceptions;
 * it has no code path that could reach or suppress this call).
 */
@Component
public class DemoLeadNotificationJob {

    private static final Logger log = LoggerFactory.getLogger(DemoLeadNotificationJob.class);

    private static final String TEAM_EMAIL = "tracedtechnology@gmail.com";

    private final EmailGateway emailGateway;

    public DemoLeadNotificationJob(EmailGateway emailGateway) {
        this.emailGateway = emailGateway;
    }

    @Job(name = "Demo lead notification — %0")
    public void run(String name, String email, String phone, String ip) {
        String subject = "New demo lead: " + name;
        String body = "<p>New demo request:</p>"
                + "<ul>"
                + "<li>Name: " + escape(name) + "</li>"
                + "<li>Email: " + escape(email) + "</li>"
                + "<li>Phone: " + escape(phone) + "</li>"
                + "<li>IP: " + escape(ip == null ? "unknown" : ip) + "</li>"
                + "</ul>";
        emailGateway.send(TEAM_EMAIL, subject, body);
        log.info("Demo lead notification sent for email={}", email);
    }

    private static String escape(String s) {
        return s == null ? "" : s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
