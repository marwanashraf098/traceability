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
     * answer for every cause — unknown, used, expired, unpaired, replaced, session ended, worker
     * switched — because hatch #15 deliberately doesn't say which (no oracle).
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

    static ScanPairException badOutcome() {
        return new ScanPairException("BAD_OUTCOME", "Outcome must be accepted or rejected.",
            "النتيجة يجب أن تكون مقبولة أو مرفوضة.", HttpStatus.BAD_REQUEST);
    }
}
