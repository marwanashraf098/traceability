package com.traceability.bazar;

import org.springframework.http.HttpStatus;

/** Bazar POS command failures. ApiExceptionHandler maps it to {code, message_en, message_ar}, like TransferException. */
public class BazarException extends RuntimeException {

    public enum Code {
        BAZAR_NOT_ENABLED,
        BAZAR_EVENT_NOT_FOUND,
        BAZAR_INVALID_INPUT,
        BAZAR_LEAVE_MODE_UNSUPPORTED,
        BAZAR_LOCATION_NOT_FOUND,
        BAZAR_LOCATION_NOT_EVENT,
        BAZAR_LOCATION_NAME_TAKEN,
        BAZAR_EVENT_NOT_DRAFT,
        BAZAR_EVENT_ENDED,
        BAZAR_TRANSFER_NOT_SENT,
        BAZAR_LOCATION_HAS_LIVE_EVENT,
        BAZAR_VARIANT_NOT_FOUND,
        BAZAR_NO_DEFAULT_PRICE
    }

    private final Code       code;
    private final String     messageEn;
    private final String     messageAr;
    private final HttpStatus httpStatus;

    public BazarException(Code code, String messageEn, String messageAr, HttpStatus httpStatus) {
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
