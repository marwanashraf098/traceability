package com.traceability.integrations.bosta;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Review mode (S2) — an action that would book a real Bosta trip (return pickup / exchange) was
 * requested for a simulated-courier tenant (V130). 409 with the usual {code, message_en,
 * message_ar} body via ApiExceptionHandler's ApiException handler.
 */
public class ReviewModeUnavailableException extends ApiException {

    public static final String CODE = "REVIEW_MODE_UNAVAILABLE";

    public ReviewModeUnavailableException() {
        super(CODE, "Not available in review mode.", "غير متاح في وضع المراجعة.", HttpStatus.CONFLICT);
    }
}
