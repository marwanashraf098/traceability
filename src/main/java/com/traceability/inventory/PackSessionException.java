package com.traceability.inventory;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Pick &amp; Pack S3 — a pack-session request that can't go ahead. Rendered by ApiExceptionHandler
 * as {@code {code, message_en, message_ar}} with the given status.
 */
public class PackSessionException extends ApiException {

    public PackSessionException(String code, String messageEn, String messageAr, HttpStatus status) {
        super(code, messageEn, messageAr, status);
    }

    static PackSessionException notFound() {
        return new PackSessionException("SESSION_NOT_FOUND", "Pack session not found.",
            "جلسة التغليف غير موجودة.", HttpStatus.NOT_FOUND);
    }

    static PackSessionException notYours() {
        return new PackSessionException("SESSION_NOT_YOURS", "This pack session belongs to another packer.",
            "جلسة التغليف هذه تخص موظفاً آخر.", HttpStatus.FORBIDDEN);
    }

    static PackSessionException ended() {
        return new PackSessionException("SESSION_ENDED", "This pack session has ended. Start a new one.",
            "انتهت جلسة التغليف هذه. ابدأ جلسة جديدة.", HttpStatus.CONFLICT);
    }

    static PackSessionException orderOpen() {
        return new PackSessionException("ORDER_OPEN",
            "Finish or set aside the open order first.",
            "أكمل الطلب المفتوح أو ضعه جانباً أولاً.", HttpStatus.CONFLICT);
    }

    static PackSessionException orderNotOpen() {
        return new PackSessionException("ORDER_NOT_OPEN",
            "That order isn't open in this session.",
            "هذا الطلب غير مفتوح في هذه الجلسة.", HttpStatus.CONFLICT);
    }

    static PackSessionException modeNotWaybill() {
        return new PackSessionException("MODE_NOT_WAYBILL",
            "This store packs from the order queue. An owner can switch to waybill scan in Settings › Pick & Pack.",
            "هذا المتجر يغلّف من قائمة الطلبات. يمكن للمالك التحويل إلى مسح البوليصة من الإعدادات › التجميع.",
            HttpStatus.CONFLICT);
    }

    static PackSessionException badReason() {
        return new PackSessionException("BAD_REASON",
            "Pick a reason: piece_missing, damaged_piece, waybill_damaged or other.",
            "اختر سبباً.", HttpStatus.BAD_REQUEST);
    }
}
