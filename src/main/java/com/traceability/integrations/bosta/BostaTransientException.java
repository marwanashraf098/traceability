package com.traceability.integrations.bosta;

/**
 * Network error or 5xx response from Bosta — safe to retry.
 * Rethrowing this from BostaWebhookJob causes JobRunr to schedule a retry.
 */
public class BostaTransientException extends BostaException {
    public BostaTransientException(String message) { super(message); }
    public BostaTransientException(String message, Throwable cause) { super(message, cause); }
}
