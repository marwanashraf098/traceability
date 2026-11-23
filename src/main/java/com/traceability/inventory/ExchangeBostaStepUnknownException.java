package com.traceability.inventory;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 409 EXCHANGE_BOSTA_STEP_UNKNOWN (S3b follow-up, 2026-11-22) — Bosta moved this exchange past "Pickup requested" but no
 * step Traced recognises past it is in its timeline ({@link ExchangeBostaStep#unknownPastPickup}). Mapping it would create
 * a replacement Traced can't place, left 'created' and pickable; a person checks it in Bosta first.
 */
public class ExchangeBostaStepUnknownException extends ApiException {

    public static final String CODE = "EXCHANGE_BOSTA_STEP_UNKNOWN";
    static final String MESSAGE_EN = "Bosta shows this exchange already moved — check it in Bosta before mapping";
    static final String MESSAGE_AR = "يُظهر Bosta أن هذا الاستبدال تحرّك بالفعل — راجعه في Bosta قبل الربط";

    public ExchangeBostaStepUnknownException() {
        super(CODE, MESSAGE_EN, MESSAGE_AR, HttpStatus.CONFLICT);
    }
}
