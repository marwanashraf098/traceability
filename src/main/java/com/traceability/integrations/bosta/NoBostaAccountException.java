package com.traceability.integrations.bosta;

/**
 * Thrown when AWB printing is requested for a tenant with no active Bosta courier
 * account. Same {code, message_en, message_ar} contract as
 * ShopifyFulfillmentActivationException (see ApiExceptionHandler) — a typed exception
 * + its own @ExceptionHandler, not a plain ResponseStatusException, because
 * ApiExceptionHandler.handleResponseStatus() deliberately discards the reason string
 * for the generic ResponseStatusException case (returns a bodyless response).
 */
public class NoBostaAccountException extends RuntimeException {

    private static final String CODE = "NO_BOSTA_ACCOUNT";

    private final String messageEn;
    private final String messageAr;

    public NoBostaAccountException(String messageEn, String messageAr) {
        super(CODE + ": " + messageEn);
        this.messageEn = messageEn;
        this.messageAr = messageAr;
    }

    public String code()      { return CODE; }
    public String messageEn() { return messageEn; }
    public String messageAr() { return messageAr; }
}
