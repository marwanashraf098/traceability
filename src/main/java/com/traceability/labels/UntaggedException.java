package com.traceability.labels;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Flexible labeling slice 2 — refusals around count-only receiving and untagged pieces (a piece with
 * no short code yet). {code, message_en, message_ar} via ApiExceptionHandler's ApiException handler.
 * v1 rule: an untagged piece can't be reprinted, transferred, held, damaged or written off — and a
 * stock take can't run over one — until a label is bound to it (packing or Tag stock).
 */
public class UntaggedException extends ApiException {

    private UntaggedException(String code, String en, String ar, HttpStatus status) {
        super(code, en, ar, status);
    }

    /** Any single-piece action (reprint, scan by internal code, hold, damage, void, write off, transfer). */
    public static UntaggedException piece() {
        return new UntaggedException("PIECE_UNTAGGED",
            "This piece has no barcode yet (untagged). It gets one when it's packed or in a Tag stock session — tag it first.",
            "هذه القطعة بدون باركود بعد (غير مُلصقة). ستحصل على ملصق عند التغليف أو في جلسة لصق المخزون — ألصق لها ملصقًا أولًا.",
            HttpStatus.CONFLICT);
    }

    /** A label print / reprint for a count-only receiving session. */
    public static UntaggedException countOnlyReceipt() {
        return new UntaggedException("COUNT_ONLY_NO_LABELS",
            "This was a count-only receiving session — no labels are printed for it. Its pieces get labels when they're packed or in a Tag stock session.",
            "كانت هذه جلسة استلام بالعدد فقط — لا تُطبع لها ملصقات. تحصل قطعها على ملصقات عند التغليف أو في جلسة لصق المخزون.",
            HttpStatus.CONFLICT);
    }

    /** mode = count_only while the tenant doesn't have flexible labeling. */
    public static UntaggedException countOnlyUnavailable() {
        return new UntaggedException("COUNT_ONLY_NOT_AVAILABLE",
            "Count-only receiving isn't available for this account.",
            "الاستلام بالعدد فقط غير متاح لهذا الحساب.",
            HttpStatus.BAD_REQUEST);
    }

    /** A count-only session must receive into the main warehouse (where packing labels untagged pieces). */
    public static UntaggedException countOnlyMainWarehouse() {
        return new UntaggedException("COUNT_ONLY_MAIN_WAREHOUSE_ONLY",
            "Count-only receiving goes into the main warehouse only.",
            "الاستلام بالعدد فقط يكون في المخزن الرئيسي فقط.",
            HttpStatus.BAD_REQUEST);
    }

    /** Stock take start / finalize over a location (and scope) holding untagged pieces. */
    public static UntaggedException stockTakeUntagged(String locationName, int n) {
        String where = locationName == null || locationName.isBlank() ? "This location" : locationName;
        String whereAr = locationName == null || locationName.isBlank() ? "هذا الموقع" : locationName;
        return new UntaggedException("STOCK_TAKE_UNTAGGED_IN_SCOPE",
            "Can't start a stock take here yet — " + where + " has " + n + " untagged piece" + (n == 1 ? "" : "s")
                + ". Tag them before running a stock take, so nothing is wrongly written off as lost.",
            "لا يمكن بدء جرد هنا بعد — يوجد في " + whereAr + " عدد " + n
                + " قطعة غير مُلصقة. ألصق لها ملصقات قبل الجرد، حتى لا يُشطب شيء على أنه مفقود عن طريق الخطأ.",
            HttpStatus.CONFLICT);
    }
}
