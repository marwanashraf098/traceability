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

    /**
     * Stock take start / finalize over a location (and scope) holding untagged pieces — switch OFF only
     * (slice 7 counts them while it's on). Tag stock is hidden then, so the message never sends them there.
     */
    public static UntaggedException stockTakeUntagged(String locationName, int n) {
        String where = locationName == null || locationName.isBlank() ? "This location" : locationName;
        String whereAr = locationName == null || locationName.isBlank() ? "هذا الموقع" : locationName;
        return new UntaggedException("STOCK_TAKE_UNTAGGED_IN_SCOPE",
            "Can't start a stock take here yet — " + where + " has " + n + " untagged piece" + (n == 1 ? "" : "s")
                + " from flexible labeling. Turn flexible labeling back on to count them.",
            "لا يمكن بدء جرد هنا بعد — يوجد في " + whereAr + " عدد " + n
                + " قطعة غير مُلصقة من الملصقات المرنة. أعد تفعيل الملصقات المرنة لعدّها.",
            HttpStatus.CONFLICT);
    }

    /** Slice 7: finalize while some variant with untagged pieces has no count typed (0 must be typed). */
    public static UntaggedException untaggedCountMissing(int products) {
        return new UntaggedException("UNTAGGED_COUNT_MISSING",
            "Enter the untagged count for " + products + " product" + (products == 1 ? "" : "s") + " (type 0 if there are none).",
            "أدخل عدد القطع غير المُلصقة لعدد " + products + " منتج (اكتب 0 إذا لم يوجد شيء).",
            HttpStatus.CONFLICT);
    }

    /** Slice 7: an untagged count typed while the tenant doesn't have flexible labeling. */
    public static UntaggedException untaggedCountUnavailable() {
        return new UntaggedException("UNTAGGED_COUNT_NOT_AVAILABLE",
            "Untagged counts aren't available for this account.",
            "عدّ القطع غير المُلصقة غير متاح لهذا الحساب.",
            HttpStatus.BAD_REQUEST);
    }

    /** Slice 7: an untagged count for a variant this stock take has no untagged pieces of. */
    public static UntaggedException notUntaggedInSession() {
        return new UntaggedException("NOT_UNTAGGED_IN_SESSION",
            "This product has no untagged pieces in this stock take.",
            "لا توجد قطع غير مُلصقة من هذا المنتج في هذا الجرد.",
            HttpStatus.BAD_REQUEST);
    }

    /** Slice 7: a negative or absurd untagged count. */
    public static UntaggedException invalidUntaggedCount(int max) {
        return new UntaggedException("INVALID_UNTAGGED_COUNT",
            "Enter a whole number from 0 to " + max + ".",
            "أدخل رقمًا صحيحًا من 0 إلى " + max + ".",
            HttpStatus.BAD_REQUEST);
    }

    /** Slice 7: Found / write-off / mark damaged on one untagged piece — they're counted per product. */
    public static UntaggedException countedPerProduct() {
        return new UntaggedException("UNTAGGED_COUNTED_PER_PRODUCT",
            "Untagged pieces are counted per product, not one by one — enter the untagged count instead.",
            "تُعدّ القطع غير المُلصقة لكل منتج وليس قطعة قطعة — أدخل عدد القطع غير المُلصقة بدلًا من ذلك.",
            HttpStatus.CONFLICT);
    }
}
