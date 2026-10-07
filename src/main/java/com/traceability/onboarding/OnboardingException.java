package com.traceability.onboarding;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/** Build D onboarding errors — mapped by ApiExceptionHandler to {code, message_en, message_ar}. */
public class OnboardingException extends ApiException {

    public enum Code { EMAIL_TAKEN, RATE_LIMITED }

    private final Code code;

    private OnboardingException(Code code, String en, String ar, HttpStatus status) {
        super(code.name(), en, ar, status);
        this.code = code;
    }

    public Code code() { return code; }

    /** Same wording as the web signup's "email taken" message (signup.errors.emailTaken). */
    static OnboardingException emailTaken() {
        return new OnboardingException(Code.EMAIL_TAKEN,
            "This email is already registered. Sign in instead.",
            "هذا البريد الإلكتروني مسجل بالفعل. قم بتسجيل الدخول.",
            HttpStatus.CONFLICT);
    }

    static OnboardingException rateLimited() {
        return new OnboardingException(Code.RATE_LIMITED,
            "Too many attempts. Wait a few minutes and try again.",
            "محاولات كثيرة جدًا. انتظر بضع دقائق ثم حاول مرة أخرى.",
            HttpStatus.TOO_MANY_REQUESTS);
    }
}
