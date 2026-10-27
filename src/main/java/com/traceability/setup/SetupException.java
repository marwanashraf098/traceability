package com.traceability.setup;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/** Onboarding gate errors — {code, message_en, message_ar} through ApiExceptionHandler. */
public class SetupException extends ApiException {

    public SetupException(String code, String messageEn, String messageAr, HttpStatus status) {
        super(code, messageEn, messageAr, status);
    }

    static SetupException invalid(String code, String en, String ar) {
        return new SetupException(code, en, ar, HttpStatus.BAD_REQUEST);
    }
}
