package com.traceability.integrations.bosta;

/**
 * Bosta answered 401 / 403 to a request made with the tenant's API key: the key was revoked, changed or
 * lacks access. Says nothing about the waybills sent — a print reports them BOSTA_AUTH ("Bosta connection
 * problem — check Settings → Connections"), marks nobody and stops (merged-PDF slice B).
 */
public class BostaAuthException extends BostaException {

    private final int httpStatus;

    public BostaAuthException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int httpStatus() { return httpStatus; }
}
