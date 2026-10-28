package com.traceability.places;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * A refused places request, rendered by ApiExceptionHandler as {code, message_en, message_ar, details} with its HTTP
 * status (same shape as LabelCodeException).
 */
public class PlaceException extends RuntimeException {

    public enum Code {
        LOCATION_NOT_FOUND, PLACE_NOT_FOUND, INVALID_KIND, INVALID_PARENT, INVALID_CODE, INVALID_NAME, CODE_TAKEN,
        INVALID_BULK, PLACE_ARCHIVED, PLACE_NOT_EMPTY, PLACE_HAS_CHILDREN, NOT_A_BOX, PURPOSE_NOT_ALLOWED,
        NO_STICKERS
    }

    static PlaceException locationNotFound() {
        return new PlaceException(HttpStatus.NOT_FOUND, Code.LOCATION_NOT_FOUND,
            "Location not found", "لم يتم العثور على الموقع");
    }

    static PlaceException placeNotFound() {
        return new PlaceException(HttpStatus.NOT_FOUND, Code.PLACE_NOT_FOUND,
            "Place not found", "لم يتم العثور على المكان");
    }

    static PlaceException invalidKind() {
        return new PlaceException(HttpStatus.BAD_REQUEST, Code.INVALID_KIND,
            "A place is a zone, rack, shelf or box", "المكان يكون منطقة أو وحدة رفوف أو رفًا أو صندوقًا");
    }

    static PlaceException invalidParent() {
        return new PlaceException(HttpStatus.BAD_REQUEST, Code.INVALID_PARENT,
            "A rack goes in a zone, a shelf on a rack, and a box in a zone or on a shelf — in the same location",
            "وحدة الرفوف تكون داخل منطقة، والرف على وحدة رفوف، والصندوق في منطقة أو على رف — في نفس الموقع");
    }

    static PlaceException invalidCode() {
        return new PlaceException(HttpStatus.BAD_REQUEST, Code.INVALID_CODE,
            "Use 1 to 40 Latin letters, digits, '-', '_' or '.' for the code",
            "استخدم من 1 إلى 40 حرفًا لاتينيًا أو رقمًا أو '-' أو '_' أو '.' للرمز");
    }

    static PlaceException invalidName() {
        return new PlaceException(HttpStatus.BAD_REQUEST, Code.INVALID_NAME,
            "A name is at most 60 characters", "الاسم 60 حرفًا كحد أقصى");
    }

    static PlaceException codeTaken(String code) {
        return new PlaceException(HttpStatus.CONFLICT, Code.CODE_TAKEN,
            code + " is already used in this location", code + " مستخدم بالفعل في هذا الموقع",
            List.of(Map.of("code", code)));
    }

    static PlaceException invalidBulk() {
        return new PlaceException(HttpStatus.BAD_REQUEST, Code.INVALID_BULK,
            "Choose 0–99 racks with 1–20 shelves each and 0–200 boxes, 2,000 places at most",
            "اختر من 0 إلى 99 وحدة رفوف بكل منها من 1 إلى 20 رفًا، ومن 0 إلى 200 صندوق، و2,000 مكان كحد أقصى");
    }

    static PlaceException archived() {
        return new PlaceException(HttpStatus.CONFLICT, Code.PLACE_ARCHIVED,
            "This place is archived", "هذا المكان مؤرشف");
    }

    static PlaceException notEmpty(int pieces) {
        return new PlaceException(HttpStatus.CONFLICT, Code.PLACE_NOT_EMPTY,
            pieces + " pieces are still here — move them first", "لا تزال " + pieces + " قطعة هنا — انقلها أولًا",
            List.of(Map.of("pieces", pieces)));
    }

    static PlaceException hasChildren(int children) {
        return new PlaceException(HttpStatus.CONFLICT, Code.PLACE_HAS_CHILDREN,
            "Archive or move the " + children + " places inside it first",
            "أرشف أو انقل الأماكن الـ " + children + " الموجودة داخله أولًا",
            List.of(Map.of("children", children)));
    }

    static PlaceException notABox() {
        return new PlaceException(HttpStatus.BAD_REQUEST, Code.NOT_A_BOX,
            "Only a box can move", "الصندوق فقط يمكن نقله");
    }

    static PlaceException purposeNotAllowed() {
        return new PlaceException(HttpStatus.BAD_REQUEST, Code.PURPOSE_NOT_ALLOWED,
            "Only a zone or a box can be the Damaged place", "المنطقة أو الصندوق فقط يمكن أن يكون مكان التالف");
    }

    static PlaceException noStickers() {
        return new PlaceException(HttpStatus.BAD_REQUEST, Code.NO_STICKERS,
            "Choose 1 to 2,000 shelves or boxes to print", "اختر من 1 إلى 2,000 رف أو صندوق للطباعة");
    }

    private final HttpStatus status;
    private final Code code;
    private final String messageAr;
    private final List<Map<String, Object>> details;

    PlaceException(HttpStatus status, Code code, String messageEn, String messageAr) {
        this(status, code, messageEn, messageAr, List.of());
    }

    PlaceException(HttpStatus status, Code code, String messageEn, String messageAr, List<Map<String, Object>> details) {
        super(messageEn);
        this.status = status;
        this.code = code;
        this.messageAr = messageAr;
        this.details = details;
    }

    public HttpStatus status() { return status; }
    public Code code() { return code; }
    public String messageEn() { return getMessage(); }
    public String messageAr() { return messageAr; }
    public List<Map<String, Object>> details() { return details; }
}
