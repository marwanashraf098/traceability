package com.traceability.inventory;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Resolve was asked for an alert that only clears itself ({@link ExceptionService#CONDITION_ONLY}):
 * its detector reads live state and never an exception_resolutions row, so a row would hide
 * nothing. 409 with the usual {code, message_en, message_ar} body; no row is written.
 */
public class ConditionOnlyAlertException extends ApiException {

    public static final String CODE = "CONDITION_ONLY";

    public ConditionOnlyAlertException() {
        super(CODE, "This alert clears by itself once the problem is fixed — it can't be marked resolved.",
            "يختفي هذا التنبيه وحده بعد حل المشكلة — لا يمكن تحديده كمُعالَج.", HttpStatus.CONFLICT);
    }
}
