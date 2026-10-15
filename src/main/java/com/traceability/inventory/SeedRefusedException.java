package com.traceability.inventory;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The initial seed is only for an EMPTY Traced location (approved 2026-10-14): once Shopify started
 * counting there through receiving (V167 shopify_counting_source = 'receiving'), the location already
 * holds Traced's count and a seed could only double it or skip it. 409 with the usual
 * {code, message_en, message_ar} body via ApiExceptionHandler's ApiException handler. A tenant whose
 * count started with the seed (The Snouts) is never refused.
 */
public class SeedRefusedException extends ApiException {

    public static final String CODE = "SEED_NOT_NEEDED";

    public SeedRefusedException() {
        super(CODE,
            "Shopify already counts this warehouse's stock — every received item was added as it arrived, so a stock seed isn't needed.",
            "Shopify يحسب مخزون هذا المستودع بالفعل — تمت إضافة كل قطعة عند استلامها، لذلك لا حاجة لتحميل المخزون الأولي.",
            HttpStatus.CONFLICT);
    }
}
