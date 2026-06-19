package com.traceability.notifications;

/**
 * Outbound email abstraction. Tested via mock; SMTP impl requires spring.mail.host.
 * Actual deliverability (SPF/DKIM, sending domain) is an ops task — see PROGRESS.md.
 */
public interface EmailGateway {

    /**
     * Sends the magic-link sign-in email.
     *
     * @param toEmail   the recipient address (provisioned owner email from Shopify)
     * @param magicLink the full link including raw token (https://app/auth/magic?token=...)
     */
    void sendMagicLink(String toEmail, String magicLink);
}
