package com.traceability.web;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Set;

/**
 * Sentry configuration: PII scrubbing + tenant context tagging.
 *
 * PII policy (GDPR / pilot requirement):
 *   - customer phone, name, address MUST NOT appear in Sentry events or breadcrumbs
 *   - tenant_id IS allowed (needed for triage)
 *   - send-default-pii is set false in application.yml (no automatic HTTP body capture)
 *
 * When sentry.dsn is empty/absent, Sentry is a no-op hub and this callback
 * is registered but never invoked.
 */
@Configuration
public class SentryConfig {

    private static final Set<String> PII_KEYS = Set.of(
        "phone", "customer_phone", "secondPhone",
        "name",  "customer_name",  "fullName", "firstName", "lastName",
        "address", "firstLine", "secondLine",
        "email", "customer_email",
        "receiver"
    );

    /**
     * BeforeSend hook: scrubs PII from event extras/tags, attaches tenant_id
     * from MDC for triage. Picked up automatically by Sentry Spring Boot
     * auto-configuration ({@code @Autowired(required=false) BeforeSendCallback}).
     */
    @Bean
    public SentryOptions.BeforeSendCallback sentryBeforeSend() {
        return (SentryEvent event, Hint hint) -> {
            // Attach tenant_id for triage — NOT a PII field.
            String tenantId = MDC.get("tenantId");
            if (tenantId != null) {
                event.setTag("tenant_id", tenantId);
            }

            // Scrub known PII keys from free-form extras.
            Map<String, Object> extras = event.getExtras();
            if (extras != null) {
                PII_KEYS.forEach(extras::remove);
            }

            // Scrub PII keys accidentally set as tags (defensive).
            Map<String, String> tags = event.getTags();
            if (tags != null) {
                PII_KEYS.forEach(tags::remove);
            }

            return event;
        };
    }
}
