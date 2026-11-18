package com.traceability.bazar;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Bazar POS command failures. ApiExceptionHandler maps it to {code, message_en, message_ar}, like TransferException. */
public class BazarException extends RuntimeException {

    public enum Code {
        BAZAR_NOT_ENABLED,
        BAZAR_EVENT_NOT_FOUND,
        BAZAR_INVALID_INPUT,
        BAZAR_LEAVE_MODE_UNSUPPORTED,
        BAZAR_LOCATION_NOT_FOUND,
        BAZAR_LOCATION_NOT_EVENT,
        BAZAR_LOCATION_NAME_TAKEN,
        BAZAR_EVENT_NOT_DRAFT,
        BAZAR_EVENT_ENDED,
        BAZAR_TRANSFER_NOT_SENT,
        BAZAR_LOCATION_HAS_LIVE_EVENT,
        BAZAR_VARIANT_NOT_FOUND,
        BAZAR_NO_DEFAULT_PRICE,
        // B2 — selling
        BAZAR_EVENT_NOT_LIVE,
        BAZAR_NOT_A_SELLER,
        BAZAR_PIECE_NOT_FOUND,
        BAZAR_PIECE_NOT_HERE,
        BAZAR_PIECE_UNTAGGED,
        BAZAR_ALREADY_SOLD,
        BAZAR_NO_PRICE,
        BAZAR_PAYMENT_METHOD_REQUIRED,
        BAZAR_PAYMENT_METHOD_NOT_ALLOWED,
        BAZAR_SALE_NOT_FOUND,
        BAZAR_SALE_UNDONE,
        BAZAR_UNDO_NOT_ALLOWED,
        BAZAR_INVALID_PHONE,
        // B3 — return / exchange
        BAZAR_NOT_SOLD_HERE,
        BAZAR_ALREADY_RETURNED,
        // B4 — close the day
        BAZAR_DAY_CLOSED,
        BAZAR_DAY_NOT_CLOSABLE,
        BAZAR_OPENING_FLOAT_REQUIRED,
        BAZAR_MANAGERS_ONLY,
        BAZAR_PIECE_DAMAGED,
        // B5 — end event, leftovers
        BAZAR_DAYS_NOT_CLOSED,
        BAZAR_EVENT_NOT_ENDED,
        BAZAR_ALREADY_BACK,
        BAZAR_PIECE_MARKED_LOST,
        BAZAR_NOT_OUTSTANDING,
        BAZAR_LEFTOVERS_OUTSTANDING,
        // B6c — cart
        BAZAR_CART_DUPLICATE_PIECE,
        BAZAR_INVALID_DISCOUNT
    }

    private final Code       code;
    private final String     messageEn;
    private final String     messageAr;
    private final HttpStatus httpStatus;
    private final Map<String, Object> details;

    public BazarException(Code code, String messageEn, String messageAr, HttpStatus httpStatus) {
        this(code, messageEn, messageAr, httpStatus, null);
    }

    /** {@code details}: extra fields for the client (e.g. BAZAR_ALREADY_SOLD's sale); omitted from the body when null. */
    public BazarException(Code code, String messageEn, String messageAr, HttpStatus httpStatus,
                          Map<String, Object> details) {
        super(code.name() + ": " + messageEn);
        this.code       = code;
        this.messageEn  = messageEn;
        this.messageAr  = messageAr;
        this.httpStatus = httpStatus;
        this.details    = details;
    }

    public Code       code()       { return code; }
    public String     messageEn()  { return messageEn; }
    public String     messageAr()  { return messageAr; }
    public HttpStatus httpStatus() { return httpStatus; }
    public Map<String, Object> details() { return details; }
}
