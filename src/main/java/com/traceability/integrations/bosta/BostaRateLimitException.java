package com.traceability.integrations.bosta;

/**
 * Bosta API rate-limit response (HTTP 429 or success:false/errorCode:429).
 *
 * NOT a BostaTransientException — we do not want JobRunr to retry webhook jobs
 * immediately when rate-limited. Instead, the status poll manages backoff via
 * BostaStatusPollJob.rateLimitRetryUntilByTenant, and the webhook job marks the
 * event 'failed' (the shipment stays non-terminal so the next poll re-ingests it
 * once the window clears).
 */
public class BostaRateLimitException extends BostaException {

    private final long retryAfterSeconds;

    public BostaRateLimitException(long retryAfterSeconds) {
        super("Bosta rate limit — retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
