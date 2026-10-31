package com.traceability.inventory;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Unpack-order S3 — why FulfillService.unpackOrder refused (nothing was changed: it is thrown before any write,
 * or rolls the transaction back). Body {code, message_en, message_ar, details} (ApiExceptionHandler); details
 * carries the open pickup session for IN_PICKUP_SESSION so the screen can link to it.
 */
public class UnpackOrderException extends RuntimeException {

    public enum Code {
        BAD_REASON(HttpStatus.BAD_REQUEST),
        NOT_PACKED(HttpStatus.CONFLICT),
        CANCEL_REQUESTED(HttpStatus.CONFLICT),
        LOOKUP_EXCHANGE(HttpStatus.CONFLICT),
        LEFT_CUSTODY(HttpStatus.CONFLICT),
        WAYBILL_ENDED(HttpStatus.CONFLICT),
        IN_PICKUP_SESSION(HttpStatus.CONFLICT),
        HELD_BY_PACKER(HttpStatus.CONFLICT);

        final HttpStatus status;
        Code(HttpStatus status) { this.status = status; }
    }

    private final Code code;
    private final String messageEn;
    private final String messageAr;
    private final Map<String, Object> details;

    UnpackOrderException(Code code, String messageEn, String messageAr) {
        this(code, messageEn, messageAr, Map.of());
    }

    UnpackOrderException(Code code, String messageEn, String messageAr, Map<String, Object> details) {
        super(code + ": " + messageEn);
        this.code = code;
        this.messageEn = messageEn;
        this.messageAr = messageAr;
        this.details = details;
    }

    public Code code()                    { return code; }
    public HttpStatus httpStatus()        { return code.status; }
    public String messageEn()             { return messageEn; }
    public String messageAr()             { return messageAr; }
    public Map<String, Object> details()  { return details; }
}
