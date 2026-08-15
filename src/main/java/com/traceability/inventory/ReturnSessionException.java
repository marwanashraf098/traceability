package com.traceability.inventory;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * FR-24: returns-session command failures that need a structured JSON body beyond a bare
 * status code — "session already open" (frontend needs the existing session's id to render
 * the mockup's Resume-session state) and "close blocked" (frontend needs the exact list of
 * still-pending items, not just a message). Every other returns-session failure (not found,
 * not open, bad request) stays a plain ResponseStatusException — same convention as the rest
 * of the codebase (ApiExceptionHandler.handleResponseStatus() returns a bodyless response for
 * those; no body is needed there). ApiExceptionHandler maps this to
 * {code, message_en, message_ar, details} — same shape family as TransferException, with an
 * added free-form `details` map since the two codes here carry different payload shapes.
 */
public class ReturnSessionException extends RuntimeException {

    public enum Code {
        SESSION_ALREADY_OPEN,
        SESSION_CLOSE_BLOCKED
    }

    private final Code                code;
    private final String              messageEn;
    private final String              messageAr;
    private final HttpStatus          httpStatus;
    private final Map<String, Object> details;

    public ReturnSessionException(Code code, String messageEn, String messageAr,
                                  HttpStatus httpStatus, Map<String, Object> details) {
        super(code.name() + ": " + messageEn);
        this.code       = code;
        this.messageEn  = messageEn;
        this.messageAr  = messageAr;
        this.httpStatus = httpStatus;
        this.details    = details;
    }

    public Code               code()       { return code; }
    public String             messageEn()  { return messageEn; }
    public String             messageAr()  { return messageAr; }
    public HttpStatus         httpStatus() { return httpStatus; }
    public Map<String, Object> details()   { return details; }
}
