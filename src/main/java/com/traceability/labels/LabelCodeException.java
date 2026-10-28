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
        FACTORY_LABEL_NOT_RECEIVED, INVALID_RUN, RUN_TOO_LARGE, RUN_NOT_FOUND, RUN_CLOSED, INVALID_PART,
        // slice 6b — factory receiving (scan rejections, then session / finalize refusals)
        PIECE_ALREADY_IN_STOCK, NOT_FACTORY_LABEL, ALREADY_RECEIVED, OTHER_RUN, ALREADY_SCANNED, SCANNED_IN_OTHER_SESSION,
        NOT_FACTORY_SESSION, FACTORY_SESSION_NO_LINES, NO_FACTORY_SCANS, NOTHING_TO_UNDO, UNDO_SCANS_FIRST,
        FACTORY_CODES_CHANGED, FACTORY_REPRINT_ONLY, FACTORY_MAIN_WAREHOUSE_ONLY,
        // slice 6c — closing a run
        INVALID_CLOSE, RUN_HAS_LIVE_SCANS
    }

    // ── slice 6b: factory receiving ─────────────────────────────────────────

    /** A piece's own short code (an old Traced label) — not a label code at all. */
    public static LabelCodeException pieceAlreadyInStock() {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.PIECE_ALREADY_IN_STOCK,
            "This piece is already in stock — it isn't a new factory piece",
            "هذه القطعة موجودة في المخزون بالفعل — ليست قطعة جديدة من المصنع");
    }

    /** A pack / tag / replacement label scanned in factory receiving. */
    public static LabelCodeException notFactoryLabel(String kind) {
        String en = switch (kind) {
            case "pack" -> "This is a packing label, not a factory label";
            case "replacement" -> "This is a replacement label, not a factory label";
            default -> "This is a Tag stock label, not a factory label";
        };
        String ar = switch (kind) {
            case "pack" -> "هذا ملصق تغليف، وليس ملصق مصنع";
            case "replacement" -> "هذا ملصق بديل، وليس ملصق مصنع";
            default -> "هذا ملصق من لصق المخزون، وليس ملصق مصنع";
        };
        return new LabelCodeException(HttpStatus.CONFLICT, Code.NOT_FACTORY_LABEL, en, ar);
    }

    public static LabelCodeException alreadyReceived() {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.ALREADY_RECEIVED,
            "Already received — this piece is in stock", "تم استلامها بالفعل — هذه القطعة في المخزون");
    }

    /** {@code run}: "factory · reference" of the run the label belongs to. */
    public static LabelCodeException otherRun(String run) {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.OTHER_RUN,
            "This label belongs to factory run «" + run + "»", "هذا الملصق يخص دفعة المصنع «" + run + "»");
    }

    public static LabelCodeException alreadyScanned() {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.ALREADY_SCANNED,
            "Already scanned in this session", "تم مسحها بالفعل في هذه الجلسة");
    }

    /** {@code session}: the other session's reference, or its start date. */
    public static LabelCodeException scannedInOtherSession(String session) {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.SCANNED_IN_OTHER_SESSION,
            "Already scanned in another receiving session (" + session + ")",
            "تم مسحها بالفعل في جلسة استلام أخرى (" + session + ")");
    }

    public static LabelCodeException notFactorySession() {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.NOT_FACTORY_SESSION,
            "This isn't a factory-labeled receiving session", "هذه ليست جلسة استلام بملصقات المصنع");
    }

    public static LabelCodeException factorySessionNoLines() {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.FACTORY_SESSION_NO_LINES,
            "Factory-labeled receiving takes scans — scan each piece instead of adding products",
            "الاستلام بملصقات المصنع يتم بالمسح — امسح كل قطعة بدلًا من إضافة منتجات");
    }

    public static LabelCodeException noFactoryScans() {
        return new LabelCodeException(HttpStatus.UNPROCESSABLE_ENTITY, Code.NO_FACTORY_SCANS,
            "Scan at least one piece before finishing", "امسح قطعة واحدة على الأقل قبل الإنهاء");
    }

    public static LabelCodeException nothingToUndo() {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.NOTHING_TO_UNDO,
            "Nothing to undo — no piece is scanned in this session", "لا شيء للتراجع عنه — لم تُمسح أي قطعة في هذه الجلسة");
    }

    public static LabelCodeException undoScansFirst(int scans) {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.UNDO_SCANS_FIRST,
            "Undo the scans first — " + scans + " pieces are scanned in this session",
            "تراجع عن المسح أولًا — توجد " + scans + " قطعة ممسوحة في هذه الجلسة");
    }

    /** Finalize found scanned codes that are no longer issued (voided or received since) — nothing was received. */
    public static LabelCodeException factoryCodesChanged(List<Map<String, Object>> codes) {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.FACTORY_CODES_CHANGED,
            "Some scanned labels changed since they were scanned (voided or received elsewhere) — nothing was received. "
                + "Undo those scans, then finish again",
            "تغيّرت بعض الملصقات الممسوحة منذ مسحها (أُلغيت أو استُلمت في مكان آخر) — لم يُستلم شيء. "
                + "تراجع عن مسحها ثم أنهِ مرة أخرى",
            codes);
    }

    /** A factory receipt's pieces already wear their labels: only the logged reprint prints them. */
    public static LabelCodeException factoryReprintOnly() {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.FACTORY_REPRINT_ONLY,
            "These pieces already carry their factory labels — use Print session labels to reprint them",
            "هذه القطع تحمل ملصقات المصنع بالفعل — استخدم طباعة ملصقات الجلسة لإعادة طباعتها");
    }

    public static LabelCodeException factoryMainWarehouseOnly() {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.FACTORY_MAIN_WAREHOUSE_ONLY,
            "Factory-labeled pieces are received into the main warehouse only",
            "تُستلم القطع بملصقات المصنع في المخزن الرئيسي فقط");
    }

    // ── slice 6c: closing a run ─────────────────────────────────────────────

    static LabelCodeException invalidClose(String en, String ar, List<Map<String, Object>> details) {
        return new LabelCodeException(HttpStatus.BAD_REQUEST, Code.INVALID_CLOSE, en, ar, details);
    }

    /** {@code sessions}: each open receiving session of the run that still holds scans. */
    static LabelCodeException runHasLiveScans(String sessionNames, List<Map<String, Object>> sessions) {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.RUN_HAS_LIVE_SCANS,
            "Finalize or undo receiving session " + sessionNames + " first",
            "أنهِ جلسة الاستلام " + sessionNames + " أو تراجع عن مسحها أولًا",
            sessions);
    }

    static LabelCodeException invalidPrintRequest() {
        return new LabelCodeException(HttpStatus.BAD_REQUEST, Code.INVALID_PRINT_REQUEST,
            "Choose which orders to print labels for", "اختر الطلبات التي تريد طباعة ملصقاتها");
    }

    static LabelCodeException orderNotPackable() {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.ORDER_NOT_PACKABLE,
            "This order isn't waiting to be packed", "هذا الطلب ليس في انتظار التجهيز");
    }

    static LabelCodeException invalidRun(String en, String ar) {
        return new LabelCodeException(HttpStatus.BAD_REQUEST, Code.INVALID_RUN, en, ar);
    }

    static LabelCodeException runTooLarge(int max) {
        return new LabelCodeException(HttpStatus.BAD_REQUEST, Code.RUN_TOO_LARGE,
            "A factory run can have at most " + max + " pieces — split it into two runs",
            "يمكن أن تضم دفعة المصنع " + max + " قطعة كحد أقصى — قسّمها إلى دفعتين");
    }

    public static LabelCodeException runNotFound() {
        return new LabelCodeException(HttpStatus.NOT_FOUND, Code.RUN_NOT_FOUND,
            "Factory run not found", "لم يتم العثور على دفعة المصنع");
    }

    public static LabelCodeException runClosed() {
        return new LabelCodeException(HttpStatus.CONFLICT, Code.RUN_CLOSED,
            "This factory run is closed", "دفعة المصنع هذه مغلقة");
    }

    static LabelCodeException invalidPart() {
        return new LabelCodeException(HttpStatus.NOT_FOUND, Code.INVALID_PART,
            "This part of the labels doesn't exist — reload the run", "هذا الجزء من الملصقات غير موجود — أعد تحميل الدفعة");
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
