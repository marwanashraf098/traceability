package com.traceability.notifications;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * SMTP-backed gateway. Requires spring.mail.host to be set (e.g. Resend's SMTP relay).
 * SPF/DKIM and sending-domain setup are ops tasks — see PROGRESS.md human tasks.
 *
 * @ConditionalOnProperty ensures this bean only exists when mail infra is configured,
 * so the build never fails without an SMTP server.
 */
@Component
@ConditionalOnProperty("spring.mail.host")
public class SmtpEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailGateway.class);

    private final JavaMailSender mailSender;

    @Value("${app.email.from:no-reply@tracedtech.com}")
    private String fromAddress;

    public SmtpEmailGateway(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void sendMagicLink(String toEmail, String magicLink) {
        // Left as a log-only stub deliberately — not rerouted through send() this pass
        // (that touches a working auth path; a separate follow-up).
        log.info("SMTP send magic link to={} from={} link={}", toEmail, fromAddress, magicLink);
    }

    @Override
    public void send(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("SMTP send to={} from={} subject={}", to, fromAddress, subject);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send email to " + to, e);
        }
    }
}
