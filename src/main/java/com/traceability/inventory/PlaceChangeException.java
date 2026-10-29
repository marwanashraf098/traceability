package com.traceability.inventory;

import org.springframework.http.HttpStatus;

/**
 * Warehouse places S2 — a place change {@link InventoryLedger#recordPlaceChange} refused. Always thrown before
 * anything is written, so a scan loop may catch it and carry on. Rendered by ApiExceptionHandler as
 * {code, message_en, message_ar, details} (the LabelCodeException shape).
 */
public class PlaceChangeException extends RuntimeException {

    public enum Code {
        PIECE_NOT_FOUND, UNTAGGED, NO_LOCATION, STATUS_NOT_PLACEABLE, PLACE_NOT_FOUND, NOT_A_SHELF_OR_BOX,
        PLACE_ARCHIVED, OTHER_LOCATION
    }

    static PlaceChangeException pieceNotFound() {
        return new PlaceChangeException(HttpStatus.NOT_FOUND, Code.PIECE_NOT_FOUND,
            "Piece not found", "لم يتم العثور على القطعة");
    }

    static PlaceChangeException untagged() {
        return new PlaceChangeException(HttpStatus.CONFLICT, Code.UNTAGGED,
            "This piece has no label yet — tag it first", "هذه القطعة ليس عليها ملصق بعد — ألصق ملصقها أولًا");
    }

    static PlaceChangeException noLocation() {
        return new PlaceChangeException(HttpStatus.CONFLICT, Code.NO_LOCATION,
            "This piece isn't at a location", "هذه القطعة ليست في أي موقع");
    }

    static PlaceChangeException statusNotPlaceable(String status) {
        return new PlaceChangeException(HttpStatus.CONFLICT, Code.STATUS_NOT_PLACEABLE,
            "Can't be placed — this piece is " + status.replace('_', ' '),
            "لا يمكن وضعها في مكان — حالة هذه القطعة: " + status.replace('_', ' '));
    }

    static PlaceChangeException placeNotFound() {
        return new PlaceChangeException(HttpStatus.NOT_FOUND, Code.PLACE_NOT_FOUND,
            "Place not found", "لم يتم العثور على المكان");
    }

    static PlaceChangeException notAShelfOrBox() {
        return new PlaceChangeException(HttpStatus.CONFLICT, Code.NOT_A_SHELF_OR_BOX,
            "Pieces go on a shelf or in a box — scan a shelf or box", "توضع القطع على رف أو في صندوق — امسح رفًا أو صندوقًا");
    }

    static PlaceChangeException placeArchived() {
        return new PlaceChangeException(HttpStatus.CONFLICT, Code.PLACE_ARCHIVED,
            "This place is archived", "هذا المكان مؤرشف");
    }

    static PlaceChangeException otherLocation() {
        return new PlaceChangeException(HttpStatus.CONFLICT, Code.OTHER_LOCATION,
            "This place is in another location than the piece", "هذا المكان في موقع غير موقع القطعة");
    }

    private final HttpStatus status;
    private final Code code;
    private final String messageAr;

    PlaceChangeException(HttpStatus status, Code code, String messageEn, String messageAr) {
        super(messageEn);
        this.status = status;
        this.code = code;
        this.messageAr = messageAr;
    }

    public HttpStatus status() { return status; }
    public Code code() { return code; }
    public String messageEn() { return getMessage(); }
    public String messageAr() { return messageAr; }
}
