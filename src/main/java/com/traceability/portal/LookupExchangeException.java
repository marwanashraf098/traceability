package com.traceability.portal;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * C2 Lookup Exchange — a refusal, as {@code {code, message_en, message_ar}} (ApiExceptionHandler) so Lookup's
 * Exchange dialog and the request drawer can show it in the merchant's language.
 */
public class LookupExchangeException extends ApiException {

    private LookupExchangeException(String code, String en, String ar, HttpStatus status) {
        super(code, en, ar, status);
    }

    public static LookupExchangeException unavailable() {
        return new LookupExchangeException("LOOKUP_EXCHANGE_UNAVAILABLE",
            "Exchanges from Lookup are for stores that ship with their own courier (Settings → Couriers) and have no "
                + "Bosta account connected.",
            "الاستبدال من البحث متاح للمتاجر التي تشحن مع شركة شحن خاصة بها (الإعدادات ← شركات الشحن) وليس لديها حساب بوسطة متصل.",
            HttpStatus.CONFLICT);
    }

    public static LookupExchangeException notAtMainWarehouse() {
        return new LookupExchangeException("NOT_AT_MAIN_WAREHOUSE",
            "Move this piece to your main warehouse first (Transfers), then start the exchange.",
            "انقل هذه القطعة إلى مخزنك الرئيسي أولاً (التحويلات)، ثم ابدأ الاستبدال.",
            HttpStatus.CONFLICT);
    }

    public static LookupExchangeException pieceUntagged() {
        return new LookupExchangeException("PIECE_UNTAGGED",
            "This piece has no label yet. Tag it in Tag stock first, then start the exchange.",
            "هذه القطعة ليس لها ملصق بعد. ضع لها ملصقًا من تمييز المخزون أولاً، ثم ابدأ الاستبدال.",
            HttpStatus.CONFLICT);
    }

    public static LookupExchangeException pieceNotAvailable() {
        return new LookupExchangeException("PIECE_NOT_AVAILABLE",
            "Only an available piece can be sent as an exchange.",
            "يمكن إرسال قطعة متاحة فقط كاستبدال.",
            HttpStatus.CONFLICT);
    }

    public static LookupExchangeException orderNotFound() {
        return new LookupExchangeException("ORDER_NOT_FOUND",
            "That order can't be exchanged. Search for the customer's original order.",
            "لا يمكن استبدال هذا الطلب. ابحث عن الطلب الأصلي للعميل.",
            HttpStatus.NOT_FOUND);
    }

    public static LookupExchangeException returningNotEligible() {
        return new LookupExchangeException("RETURNING_NOT_ELIGIBLE",
            "That item can't come back on this order — it isn't delivered, or it's already in another return.",
            "لا يمكن إرجاع هذا المنتج على هذا الطلب — لم يتم توصيله، أو هو بالفعل ضمن إرجاع آخر.",
            HttpStatus.CONFLICT);
    }

    public static LookupExchangeException replacementNotReserved() {
        return new LookupExchangeException("REPLACEMENT_NOT_RESERVED",
            "The replacement piece isn't reserved for this exchange any more, so it can't be handed to the courier.",
            "القطعة البديلة لم تعد محجوزة لهذا الاستبدال، لذلك لا يمكن تسليمها لشركة الشحن.",
            HttpStatus.CONFLICT);
    }

    public static LookupExchangeException notLookupExchange() {
        return new LookupExchangeException("NOT_LOOKUP_EXCHANGE",
            "Only an exchange started from Lookup can be cancelled here.",
            "يمكن إلغاء الاستبدال الذي بدأ من البحث فقط من هنا.",
            HttpStatus.CONFLICT);
    }

    public static LookupExchangeException replacementLeft() {
        return new LookupExchangeException("REPLACEMENT_LEFT",
            "The replacement already left with the courier — this exchange can't be cancelled.",
            "خرج البديل بالفعل مع شركة الشحن — لا يمكن إلغاء هذا الاستبدال.",
            HttpStatus.CONFLICT);
    }

    public static LookupExchangeException reservedToExchange(String reference) {
        return new LookupExchangeException("RESERVED_TO_LOOKUP_EXCHANGE",
            "This piece is reserved for exchange " + reference + ". Use Cancel exchange in that return request to free it.",
            "هذه القطعة محجوزة للاستبدال " + reference + ". استخدم إلغاء الاستبدال في طلب الإرجاع هذا لتحريرها.",
            HttpStatus.CONFLICT);
    }
}
