package com.traceability.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback EmailGateway when no SMTP is configured (no spring.mail.host).
 * Not a @Component — instantiated by EmailGatewayConfig when no other bean is present.
 */
public class LoggingEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailGateway.class);

    @Override
    public void sendMagicLink(String toEmail, String magicLink) {
        log.warn("EMAIL NOT SENT (configure spring.mail.host): to={} link={}", toEmail, magicLink);
    }
}
