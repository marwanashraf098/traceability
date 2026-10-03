package com.traceability.review;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/** Review mode S5 — refusals of the review-tenant ops endpoints ({code, message_en, message_ar}). */
public class ReviewTenantException extends ApiException {

    private ReviewTenantException(String code, String en, String ar, HttpStatus status) {
        super(code, en, ar, status);
    }

    static ReviewTenantException notInternalEmail() {
        return new ReviewTenantException("NOT_INTERNAL_EMAIL",
            "The review tenant's owner must use a @tracedtech.com address.",
            "يجب أن يستخدم مالك حساب المراجعة بريدًا من نطاق ‎@tracedtech.com.", HttpStatus.BAD_REQUEST);
    }

    static ReviewTenantException notSimulated() {
        return new ReviewTenantException("NOT_SIMULATED",
            "This tenant is not flagged as a simulated-courier review tenant (run review-tenant-flag.sql first).",
            "هذا الحساب غير مُعلَّم كحساب مراجعة بشحن محاكى (شغّل review-tenant-flag.sql أولًا).", HttpStatus.CONFLICT);
    }

    static ReviewTenantException fixtureExists() {
        return new ReviewTenantException("FIXTURE_EXISTS",
            "This tenant isn't empty (a previous seed or a reviewer's data) — reset it first.",
            "هذا الحساب ليس فارغًا (بيانات مراجعة سابقة أو بيانات المراجع) — أعد ضبطه أولًا.", HttpStatus.CONFLICT);
    }

    static ReviewTenantException missingUsers() {
        return new ReviewTenantException("MISSING_USERS",
            "The review tenant needs its owner and one worker before seeding.",
            "يحتاج حساب المراجعة إلى المالك وعامل واحد قبل إضافة البيانات.", HttpStatus.CONFLICT);
    }
}
