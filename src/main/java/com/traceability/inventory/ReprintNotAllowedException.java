package com.traceability.inventory;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Lookup "Reprint label" for a piece whose status isn't in
 * {@link PieceLabelReprintService#REPRINTABLE}. 422 with the usual {code, message_en,
 * message_ar} body via ApiExceptionHandler's ApiException handler.
 */
public class ReprintNotAllowedException extends ApiException {

    public static final String CODE = "REPRINT_NOT_ALLOWED";

    public ReprintNotAllowedException() {
        super(CODE,
            "Only pieces in the warehouse can be relabelled.",
            "يمكن إعادة طباعة الملصق للقطع الموجودة في المخزن فقط.",
            HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
