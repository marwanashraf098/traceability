package com.traceability.integrations.bosta;

/**
 * Bosta mass-awb answered HTTP 400. {@link #bostaMessage()} is Bosta's own {@code message} (or the raw
 * body when it has none) — e.g. {@code "Couldn't find deliveries with ids: [] or trackingNumbers: [...]"}
 * when a waybill in the request was deleted in Bosta. BostaAwbService decides from it whether one bad
 * waybill can be isolated (only for the per-waybill messages it recognises); every other error keeps the
 * whole-chunk rejection. {@link #getMessage()} is the same text a plain BostaException carried before.
 */
public class BostaAwbRejectedException extends BostaException {

    private final int httpStatus;
    private final String bostaMessage;

    public BostaAwbRejectedException(String message, int httpStatus, String bostaMessage, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.bostaMessage = bostaMessage;
    }

    public int httpStatus() { return httpStatus; }

    public String bostaMessage() { return bostaMessage; }
}
