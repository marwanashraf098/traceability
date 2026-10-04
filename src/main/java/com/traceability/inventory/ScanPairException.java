package com.traceability.inventory;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * S6 — a phone-as-scanner request that can't go ahead. Rendered by ApiExceptionHandler as
 * {@code {code, message_en, message_ar}} with the given status.
 */
public class ScanPairException extends ApiException {

    public ScanPairException(String code, String messageEn, String messageAr, HttpStatus status) {
        super(code, messageEn, messageAr, status);
    }

    /**
     * The phone's pair code or device secret doesn't resolve (hatch #15 returned nothing). One
     * answer for every cause — unknown, used, expired, unpaired, replaced, worker switched, station
     * locked, signed out, worker deactivated — because hatch #15 deliberately doesn't say which
     * (no oracle).
     */
    static ScanPairException ended() {
        return new ScanPairException("PAIRING_ENDED",
            "Pairing ended — scan the QR code on the tablet again.",
            "انتهى الاقتران — امسح رمز QR على الجهاز اللوحي مرة أخرى.", HttpStatus.UNAUTHORIZED);
    }

    static ScanPairException tooFast() {
        return new ScanPairException("TOO_FAST", "Too many scans at once — slow down.",
            "عمليات مسح كثيرة في وقت واحد — تمهّل.", HttpStatus.TOO_MANY_REQUESTS);
    }

    static ScanPairException badScan() {
        return new ScanPairException("BAD_SCAN", "That scan couldn't be read.",
            "تعذّرت قراءة هذا المسح.", HttpStatus.BAD_REQUEST);
    }

    static ScanPairException eventNotFound() {
        return new ScanPairException("EVENT_NOT_FOUND", "Scan not found.",
            "لم يتم العثور على المسح.", HttpStatus.NOT_FOUND);
    }

    /** Q1: the tablet's id (localStorage) is missing or not V139's shape. */
    static ScanPairException badDevice() {
        return new ScanPairException("BAD_DEVICE", "This tablet's id couldn't be read — reload the page.",
            "تعذّرت قراءة معرّف هذا الجهاز اللوحي — أعد تحميل الصفحة.", HttpStatus.BAD_REQUEST);
    }

    /** Q1: no live pairing on this tablet (the stream has nothing to carry). */
    static ScanPairException noPairing() {
        return new ScanPairException("NO_PAIRING", "No phone is paired with this tablet.",
            "لا يوجد هاتف مقترن بهذا الجهاز اللوحي.", HttpStatus.CONFLICT);
    }

    /** Q1: the tablet's live pairing is another worker's. */
    static ScanPairException notYours() {
        return new ScanPairException("PAIRING_NOT_YOURS", "The phone paired with this tablet belongs to another worker.",
            "الهاتف المقترن بهذا الجهاز اللوحي يخص عاملاً آخر.", HttpStatus.FORBIDDEN);
    }

    /** Q1: a concurrent pairing on the same tablet / by the same worker won. */
    static ScanPairException busy() {
        return new ScanPairException("PAIRING_BUSY", "Another pairing was just started here — try again.",
            "بدأ اقتران آخر للتو هنا — حاول مرة أخرى.", HttpStatus.CONFLICT);
    }

    static ScanPairException badReason() {
        return new ScanPairException("BAD_REASON", "Unknown reason.", "سبب غير معروف.", HttpStatus.BAD_REQUEST);
    }

    static ScanPairException badOutcome() {
        return new ScanPairException("BAD_OUTCOME", "Outcome must be accepted or rejected.",
            "النتيجة يجب أن تكون مقبولة أو مرفوضة.", HttpStatus.BAD_REQUEST);
    }
}
