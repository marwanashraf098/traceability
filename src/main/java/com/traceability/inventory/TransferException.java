package com.traceability.inventory;

import org.springframework.http.HttpStatus;

/**
 * Thrown for FR-22 transfer "command" failures (create/reconcile/close — not the scan
 * family, which returns a ScanOutResult/ScanBackResult rejected() value instead, mirroring
 * FulfillService.scan()). ApiExceptionHandler maps this to {code, message_en, message_ar} —
 * one handler for every code, same shape as ShopifyOAuthException.
 */
public class TransferException extends RuntimeException {

    public enum Code {
        TRANSFER_NOT_FOUND,
        TRANSFER_NOT_OPEN,
        TRANSFER_NOT_RECONCILING,
        TRANSFER_LINE_NOT_FOUND,
        SHORTFALL_INVALID_COUNTS,
        SHORTFALL_EMPTY_REQUEST,
        SHORTFALL_EXCEEDS_OUTSTANDING,
        SHORTFALL_RACE_CONFLICT,
        TRANSFER_HAS_OUTSTANDING_PIECES,
        TRANSFER_CLOSE_RACE_CONFLICT,
        TRANSFER_NO_OUTSTANDING_PIECES,
        TRANSFER_TYPE_INVALID,
        TRANSFER_DESTINATION_NOT_FOUND,
        TRANSFER_DESTINATION_IS_FULFILLMENT
    }

    private final Code       code;
    private final String     messageEn;
    private final String     messageAr;
    private final HttpStatus httpStatus;

    public TransferException(Code code, String messageEn, String messageAr, HttpStatus httpStatus) {
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
