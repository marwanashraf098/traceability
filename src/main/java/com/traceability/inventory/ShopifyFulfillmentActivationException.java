package com.traceability.inventory;

import org.springframework.http.HttpStatus;

/**
 * Thrown for FR-17 v2 fulfillment-activation guard failures (deliveryProfileUpdate).
 * Same shape as TransferException — ApiExceptionHandler maps this to
 * {code, message_en, message_ar} so the frontend can show the exact guard reason (e.g. the
 * CC-reissue scope message) instead of a bare status code. A plain ResponseStatusException
 * would NOT do this: ApiExceptionHandler.handleResponseStatus() deliberately returns a Void
 * body for the generic ResponseStatusException case (see api.ts's documented note on
 * resolveStockTake for the same reason PieceCommittedException etc. all use a typed
 * exception + typed body instead of relying on that generic handler).
 */
public class ShopifyFulfillmentActivationException extends RuntimeException {

    public enum Code {
        MISSING_SCOPE,
        NO_DEFAULT_PROFILE,
        AMBIGUOUS_LOCATION_GROUPS,
        MUTATION_FAILED
    }

    private final Code       code;
    private final String     messageEn;
    private final String     messageAr;
    private final HttpStatus httpStatus;

    public ShopifyFulfillmentActivationException(Code code, String messageEn, String messageAr,
                                                  HttpStatus httpStatus) {
        super(code.name() + ": " + messageEn);
        this.code       = code;
        this.messageEn  = messageEn;
        this.messageAr  = messageAr;
        this.httpStatus = httpStatus;
    }

    public Code       code()       { return code; }
    public String     messageEn()  { return messageEn; }
    public String     messageAr()  { return messageAr; }
    public HttpStatus httpStatus() { return httpStatus; }
}
