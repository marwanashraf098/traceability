package com.traceability.notifications;

import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends the welcome email for STANDARD email/password signup only (Shopify Path-2
 * provisioning is a separate seam — see MagicLinkService — and does not enqueue this job).
 *
 * Enqueued from AuthService.signup() after the tenant+owner transaction has committed.
 * Does no DB lookup — the payload (email, display name) is passed in at enqueue time.
 */
@Component
public class WelcomeEmailJob {

    private static final Logger log = LoggerFactory.getLogger(WelcomeEmailJob.class);

    private final EmailGateway emailGateway;

    public WelcomeEmailJob(EmailGateway emailGateway) {
        this.emailGateway = emailGateway;
    }

    @Job(name = "Welcome email — %0")
    public void run(String email, String displayName) {
        String subject = "Welcome to Traced · مرحبًا بك في Traced";
        String greetingEn = (displayName != null && !displayName.isBlank())
                ? "Hi " + displayName + ","
                : "Hi there,";
        String greetingAr = (displayName != null && !displayName.isBlank())
                ? "مرحبًا " + displayName + "،"
                : "أهلاً بك،";

        String body = """
                <div style="font-family: Arial, sans-serif; font-size: 15px; color: #111; line-height: 1.5;">
                  <p>%s</p>
                  <p>Welcome to Traced — you're all set up. Traced tracks every piece of inventory
                  from the moment it arrives until it reaches your customer, so you always know
                  exactly what you have and where it is.</p>
                  <p>Log in any time to get started.</p>
                  <p>— The Traced team</p>
                </div>
                <hr style="border: none; border-top: 1px solid #ddd; margin: 24px 0;">
                <div dir="rtl" style="font-family: Arial, sans-serif; font-size: 15px; color: #111; line-height: 1.5;">
                  <p>%s</p>
                  <p>مرحبًا بك في Traced — حسابك جاهز الآن. تتيح لك Traced تتبّع كل قطعة من مخزونك
                  منذ لحظة استلامها وحتى وصولها إلى عميلك، حتى تعرف دائمًا بالضبط ما تملكه وأين يوجد.</p>
                  <p>يمكنك تسجيل الدخول في أي وقت للبدء.</p>
                  <p>— فريق Traced</p>
                </div>
                """.formatted(greetingEn, greetingAr);

        emailGateway.send(email, subject, body);
        log.info("Welcome email sent to={}", email);
    }
}
