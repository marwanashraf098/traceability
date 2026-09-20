package com.traceability.demo;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown by the public demo-start endpoint (FR-DEMO Day 2). Extends {@link ApiException} so
 * it flows through the same generic handler as {@code ShopifyOAuthException} — same
 * {@code {code, message_en, message_ar}} body shape.
 */
public class DemoException extends ApiException {

    public enum Code {
        DEMO_INPUT_INVALID,
        DEMO_RATE_LIMITED
    }

    private final Code code;

    public DemoException(Code code, String messageEn, String messageAr, HttpStatus httpStatus) {
        super(code.name(), messageEn, messageAr, httpStatus);
        this.code = code;
    }

    public Code code() { return code; }

    public static DemoException inputInvalid() {
        return new DemoException(
                Code.DEMO_INPUT_INVALID,
                "Please check your details and try again",
                "يرجى التحقق من بياناتك والمحاولة مرة أخرى",
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    public static DemoException rateLimited() {
        return new DemoException(
                Code.DEMO_RATE_LIMITED,
                "Too many attempts — please try again later",
                "محاولات كثيرة جدًا — يرجى المحاولة لاحقًا",
                HttpStatus.TOO_MANY_REQUESTS);
    }
}
