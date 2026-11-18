package com.traceability.inventory;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 409 EXCHANGE_NOT_MAPPED (2026-11-17) — an AWB scanned to link to an order at pack is a Bosta exchange whose
 * replacement isn't mapped yet (an exchanges row with no outbound_order_id). It belongs to no packed order: linking
 * it would make that order the exchange's forward leg and the exchange would never reach mapping. Same words as
 * WaybillResolver's EXCHANGE_NOT_MAPPED answer in waybill mode.
 */
public class ExchangeNotMappedException extends ApiException {

    public static final String CODE = "EXCHANGE_NOT_MAPPED";
    static final String MESSAGE_EN = "This exchange waybill isn't mapped to a replacement order yet. Give it to a manager.";
    static final String MESSAGE_AR = "بوليصة الاستبدال هذه لم تُربط بطلب بديل بعد. سلّمها للمدير.";

    public ExchangeNotMappedException() {
        super(CODE, MESSAGE_EN, MESSAGE_AR, HttpStatus.CONFLICT);
    }
}
