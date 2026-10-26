package com.traceability.labels;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * A refused label-code request, rendered by ApiExceptionHandler as
 * {code, message_en, message_ar, details} with its HTTP status (same shape as the other domain errors).
 */
public class LabelCodeException extends RuntimeException {

    public enum Code {
        INVALID_KIND, INVALID_COUNT, INVALID_IDEMPOTENCY_KEY, IDEMPOTENCY_KEY_REUSED,
        INVALID_VOID_REQUEST, VOID_REFUSED, BATCH_NOT_FOUND, NO_ISSUED_CODES,
        // slice 3 — named codes, binding, Tag stock
        UNKNOWN_VARIANT, INVALID_ORDER_REF, UNKNOWN_CODE, CODE_VOIDED, NO_UNTAGGED_STOCK, NO_MAIN_WAREHOUSE,
        TOO_MANY_LABELS, TAG_SESSION_NOT_FOUND, TAG_SESSION_ENDED,
        // slice 4 — packing labels
        INVALID_PRINT_REQUEST, ORDER_NOT_PACKABLE,
        // slice 6 — factory runs
        FACTORY_LABEL_NOT_RECEIVED
    }

    static LabelCodeException invalidPrintRequest() {
        return new LabelCodeException(HttpStatus.BAD_REQUEST, Code.INVALID_PRINT_REQUEST,
            "Choose which orders to print labels for", "اختر الطلبات التي تريد طباعة ملصقاتها");
    }

    static LabelCodeException orderNotPackable() {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.ORDER_NOT_PACKABLE,
            "This order isn't waiting to be packed", "هذا الطلب ليس في انتظار التجهيز");
    }

    /** Kind factory asked of the generic issue endpoint — those codes come only from a factory run's print. */
    static LabelCodeException factoryKindOnlyFromRun() {
        return new LabelCodeException(HttpStatus.BAD_REQUEST, Code.INVALID_KIND,
            "Factory labels are printed from a factory run (Receiving → Factory runs)",
            "تُطبع ملصقات المصنع من دفعة مصنع (الاستلام ← دفعات المصنع)");
    }

    static LabelCodeException invalidKind() {
        return new LabelCodeException(HttpStatus.BAD_REQUEST, Code.INVALID_KIND,
            "Label kind must be pack, tag, factory or replacement",
            "نوع الملصق يجب أن يكون pack أو tag أو factory أو replacement");
    }

    public static LabelCodeException invalidCount() {
        return new LabelCodeException(HttpStatus.BAD_REQUEST, Code.INVALID_COUNT,
            "Choose 1 to 2,000 labels in all, at least 1 for each product",
            "اختر من 1 إلى 2,000 ملصق إجمالًا، وملصقًا واحدًا على الأقل لكل منتج");
    }

    static LabelCodeException invalidKey() {
        return new LabelCodeException(HttpStatus.BAD_REQUEST, Code.INVALID_IDEMPOTENCY_KEY,
            "A request key of 1 to 100 characters is required", "مطلوب مفتاح طلب من 1 إلى 100 حرف");
    }

    static LabelCodeException keyReused() {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.IDEMPOTENCY_KEY_REUSED,
            "This request key was already used for a different batch", "مفتاح الطلب هذا استُخدم بالفعل لدفعة مختلفة");
    }

    public static LabelCodeException unknownVariant() {
        return new LabelCodeException(HttpStatus.BAD_REQUEST, Code.UNKNOWN_VARIANT,
            "A product in this request isn't in your catalog", "منتج في هذا الطلب غير موجود في الكتالوج");
    }

    static LabelCodeException invalidOrderRef() {
        return new LabelCodeException(HttpStatus.BAD_REQUEST, Code.INVALID_ORDER_REF,
            "The order line doesn't match this product", "سطر الطلب لا يطابق هذا المنتج");
    }

    /** Scanned text that is no label code of this tenant. */
    public static LabelCodeException unknownCode() {
        return new LabelCodeException(HttpStatus.NOT_FOUND, Code.UNKNOWN_CODE,
            "This label isn't one of yours — check you scanned a Traced label",
            "هذا الملصق ليس من ملصقاتك — تأكد أنك مسحت ملصق Traced");
    }

    /** A factory code: it becomes a piece only when its factory run is received — never tagged or packed. */
    public static LabelCodeException factoryLabelNotReceived() {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.FACTORY_LABEL_NOT_RECEIVED,
            UnusedLabels.FACTORY_MESSAGE_EN, UnusedLabels.FACTORY_MESSAGE_AR);
    }

    public static LabelCodeException codeVoided() {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.CODE_VOIDED,
            "This label was voided — throw it away and print a new one",
            "تم إلغاء هذا الملصق — تخلّص منه واطبع ملصقًا جديدًا");
    }

    public static LabelCodeException noUntaggedStock() {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.NO_UNTAGGED_STOCK,
            "No untagged piece of this product is left in the main warehouse — keep this label for the next one",
            "لا توجد قطعة غير مُلصقة من هذا المنتج في المخزن الرئيسي — احتفظ بهذا الملصق للقطعة التالية");
    }

    public static LabelCodeException noMainWarehouse() {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.NO_MAIN_WAREHOUSE,
            "Set a main warehouse first", "حدّد المخزن الرئيسي أولًا");
    }

    public static LabelCodeException tooManyLabels(int untagged) {
        return new LabelCodeException(HttpStatus.BAD_REQUEST, Code.TOO_MANY_LABELS,
            "Only " + untagged + " untagged pieces of this product — print that many labels at most",
            "توجد " + untagged + " قطعة غير مُلصقة فقط من هذا المنتج — اطبع هذا العدد من الملصقات كحد أقصى");
    }

    public static LabelCodeException tagSessionNotFound() {
        return new LabelCodeException(HttpStatus.NOT_FOUND, Code.TAG_SESSION_NOT_FOUND,
            "Tag stock session not found", "لم يتم العثور على جلسة لصق المخزون");
    }

    public static LabelCodeException tagSessionEnded() {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.TAG_SESSION_ENDED,
            "This Tag stock session has ended — start a new one", "انتهت جلسة لصق المخزون هذه — ابدأ جلسة جديدة");
    }

    private final HttpStatus status;
    private final Code code;
    private final String messageAr;
    private final List<Map<String, Object>> details;

    public LabelCodeException(HttpStatus status, Code code, String messageEn, String messageAr) {
        this(status, code, messageEn, messageAr, List.of());
    }

    public LabelCodeException(HttpStatus status, Code code, String messageEn, String messageAr,
                              List<Map<String, Object>> details) {
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
