package com.traceability.labels;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * A refused label-code request, rendered by ApiExceptionHandler as
 * {code, message_en, message_ar, details} with its HTTP status (same shape as the other domain errors).
 */
public class LabelCodeException extends RuntimeException {

    public enum Code {
        INVALID_KIND, INVALID_COUNT, INVALID_IDEMPOTENCY_KEY, IDEMPOTENCY_KEY_REUSED,
        INVALID_VOID_REQUEST, VOID_REFUSED, BATCH_NOT_FOUND, NO_ISSUED_CODES
    }

    private final HttpStatus status;
    private final Code code;
    private final String messageAr;
    private final List<Map<String, Object>> details;

    public LabelCodeException(HttpStatus status, Code code, String messageEn, String messageAr) {
        this(status, code, messageEn, messageAr, List.of());
    }

    public LabelCodeException(HttpStatus status, Code code, String messageEn, String messageAr,
                              List<Map<String, Object>> details) {
        super(messageEn);
        this.status = status;
        this.code = code;
        this.messageAr = messageAr;
        this.details = details;
    }

    public HttpStatus status() { return status; }
    public Code code() { return code; }
    public String messageEn() { return getMessage(); }
    public String messageAr() { return messageAr; }
    public List<Map<String, Object>> details() { return details; }
}
