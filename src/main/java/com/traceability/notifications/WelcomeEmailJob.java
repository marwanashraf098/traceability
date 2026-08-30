package com.traceability.notifications;

import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Sends the welcome email for STANDARD email/password signup only (Shopify Path-2
 * provisioning is a separate seam — see MagicLinkService — and does not enqueue this job).
 *
 * Enqueued from AuthService.signup() after the tenant+owner transaction has committed.
 * Does no DB lookup — the payload (email, display name) is passed in at enqueue time.
 *
 * Body is the approved bilingual template at classpath:emails/welcome.html, loaded once
 * and cached (see {@link #template()}), with {{APP_URL}} and the two heading tokens
 * substituted per send.
 */
@Component
public class WelcomeEmailJob {

    private static final Logger log = LoggerFactory.getLogger(WelcomeEmailJob.class);

    private static final String TEMPLATE_PATH = "emails/welcome.html";

    // Exact literals as they appear in the template — plain String.replace, not regex,
    // so the Arabic text needs no escaping.
    private static final String EN_HEADING_TOKEN = "Welcome to Traced {{OWNER_NAME}}";
    private static final String AR_HEADING_TOKEN =
            "مرحبًا بك في Traced {{OWNER_NAME}}";
    private static final String EN_HEADING_BASE = "Welcome to Traced";
    private static final String AR_HEADING_BASE =
            "مرحبًا بك في Traced";
    private static final char ARABIC_COMMA = '،';
    private static final String APP_URL_TOKEN = "{{APP_URL}}";

    private final EmailGateway emailGateway;

    // Same config property MagicLinkService uses to build the magic link — not a new hardcode.
    @Value("${shopify.app-url:http://localhost:5173}")
    private String appUrl;

    private volatile String templateCache;

    public WelcomeEmailJob(EmailGateway emailGateway) {
        this.emailGateway = emailGateway;
    }

    /** Loads the template from the classpath once, then serves the cached copy. */
    private String template() {
        String t = templateCache;
        if (t == null) {
            synchronized (this) {
                t = templateCache;
                if (t == null) {
                    try {
                        t = new ClassPathResource(TEMPLATE_PATH).getContentAsString(StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to load " + TEMPLATE_PATH, e);
                    }
                    templateCache = t;
                }
            }
        }
        return t;
    }

    @Job(name = "Welcome email — %0")
    public void run(String email, String displayName) {
        String subject = "Welcome to Traced · مرحبًا بك في Traced";

        boolean hasName = displayName != null && !displayName.isBlank();
        String trimmedName = hasName ? displayName.trim() : null;

        String enHeading = hasName ? EN_HEADING_BASE + ", " + trimmedName : EN_HEADING_BASE;
        String arHeading = hasName ? AR_HEADING_BASE + ARABIC_COMMA + " " + trimmedName : AR_HEADING_BASE;

        String body = template()
                .replace(EN_HEADING_TOKEN, enHeading)
                .replace(AR_HEADING_TOKEN, arHeading)
                .replace(APP_URL_TOKEN, appUrl);

        emailGateway.send(email, subject, body);
        log.info("Welcome email sent to={}", email);
    }
}
