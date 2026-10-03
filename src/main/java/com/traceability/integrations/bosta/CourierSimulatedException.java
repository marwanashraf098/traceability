package com.traceability.integrations.bosta;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Review mode — a real-courier action was requested for a tenant whose courier is simulated
 * (a row in tenant_courier_simulation). 409 with the usual {code, message_en, message_ar} body
 * via ApiExceptionHandler's ApiException handler.
 */
public class CourierSimulatedException extends ApiException {

    public static final String CODE = "COURIER_SIMULATED";

    public CourierSimulatedException() {
        super(CODE,
            "This account is in review mode — the courier is simulated, so Bosta can't be connected or synced.",
            "هذا الحساب في وضع المراجعة — شركة الشحن محاكاة، لذلك لا يمكن ربط بوسطة أو مزامنتها.",
            HttpStatus.CONFLICT);
    }
}
