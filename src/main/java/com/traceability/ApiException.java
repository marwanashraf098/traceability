package com.traceability;

import org.springframework.http.HttpStatus;

/**
 * Base type for exceptions {@link ApiExceptionHandler} maps to an HTTP response with body
 * {@code {code, message_en, message_ar}}. Extracted from {@code ShopifyOAuthException} (FR-DEMO
 * Day 2) so a second, unrelated exception family (demo public-endpoint errors) can share the
 * same generic handler instead of duplicating it — {@code ShopifyOAuthException} now extends
 * this with no behavior change (same fields, same accessors, same JSON shape).
 *
 * Subclasses keep their own typed {@code Code} enum and a covariant {@code code()} accessor
 * (e.g. {@code ShopifyOAuthException.Code}) — {@link #errorCode()} here is deliberately a
 * separate, plain-String accessor so it never collides with a subclass's enum-typed {@code code()}.
 */
public abstract class ApiException extends RuntimeException {

    private final String     errorCode;
    private final String     messageEn;
    private final String     messageAr;
    private final HttpStatus httpStatus;

    protected ApiException(String errorCode, String messageEn, String messageAr, HttpStatus httpStatus) {
        super(errorCode + ": " + messageEn);
        this.errorCode  = errorCode;
        this.messageEn  = messageEn;
        this.messageAr  = messageAr;
        this.httpStatus = httpStatus;
    }

    public String     errorCode()  { return errorCode; }
    public String     messageEn()  { return messageEn; }
    public String     messageAr()  { return messageAr; }
    public HttpStatus httpStatus() { return httpStatus; }
}
