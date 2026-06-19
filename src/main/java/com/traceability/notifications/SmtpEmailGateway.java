package com.traceability.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * SMTP-backed gateway scaffold. Requires spring-boot-starter-mail and spring.mail.host.
 * Actual mail transport (JavaMailSender) wired here once the ops dependency is confirmed.
 * SPF/DKIM and sending-domain setup are ops tasks — see PROGRESS.md human tasks.
 *
 * @ConditionalOnProperty ensures this bean only exists when mail infra is configured,
 * so the build never fails without an SMTP server.
 */
@Component
@ConditionalOnProperty("spring.mail.host")
public class SmtpEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailGateway.class);

    @Value("${app.email.from:noreply@traced.app}")
    private String fromAddress;

    @Override
    public void sendMagicLink(String toEmail, String magicLink) {
        // TODO: inject JavaMailSender (add spring-boot-starter-mail to pom.xml when
        //       SMTP provider is confirmed). Until then, LoggingEmailGateway is the
        //       active fallback — this bean is conditional on spring.mail.host.
        log.info("SMTP send magic link to={} from={} link={}", toEmail, fromAddress, magicLink);
    }
}
