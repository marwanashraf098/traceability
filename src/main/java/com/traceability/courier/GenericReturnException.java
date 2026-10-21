package com.traceability.courier;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Non-Bosta couriers G4a — a refusal on the "book the return with your own courier" path, as
 * {@code {code, message_en, message_ar}} (ApiExceptionHandler) so the drawer and Settings can show it.
 */
public class GenericReturnException extends ApiException {

    private GenericReturnException(String code, String en, String ar, HttpStatus status) {
        super(code, en, ar, status);
    }

    public static GenericReturnException exchangeOtherCourier() {
        return new GenericReturnException("EXCHANGE_OTHER_COURIER",
            "Exchanges for orders shipped with other couriers are coming soon. Switch it to a refund or reject it.",
            "الاستبدال للطلبات المشحونة مع شركات شحن أخرى قادم قريبًا. حوّله إلى استرداد أو ارفضه.",
            HttpStatus.CONFLICT);
    }

    public static GenericReturnException notManual() {
        return new GenericReturnException("NOT_MANUAL",
            "This return is booked through Traced, not with your own courier.",
            "هذا الإرجاع يُحجز من خلال Traced، وليس مع شركة الشحن الخاصة بك.",
            HttpStatus.CONFLICT);
    }

    public static GenericReturnException alreadyBooked() {
        return new GenericReturnException("ALREADY_BOOKED",
            "The courier and tracking number can only be set while the request is approved and has no return parcel yet.",
            "يمكن تحديد شركة الشحن ورقم التتبع فقط بينما الطلب مقبول وليس له طرد إرجاع بعد.",
            HttpStatus.CONFLICT);
    }

    public static GenericReturnException carrierRequired() {
        return new GenericReturnException("CARRIER_REQUIRED",
            "Choose the courier (80 characters at most).",
            "اختر شركة الشحن (80 حرفًا كحد أقصى).",
            HttpStatus.BAD_REQUEST);
    }

    public static GenericReturnException trackingInvalid() {
        return new GenericReturnException("TRACKING_INVALID",
            "Enter the tracking number with its letters and digits.",
            "أدخل رقم التتبع بحروفه وأرقامه.",
            HttpStatus.BAD_REQUEST);
    }

    public static GenericReturnException sameAsOrderWaybill() {
        return new GenericReturnException("SAME_AS_ORDER_WAYBILL",
            "That's the order's own delivery waybill. Enter the return's own tracking number, or leave it empty — "
                + "the return can still be scanned in by its pieces.",
            "هذه بوليصة توصيل الطلب نفسه. أدخل رقم تتبع الإرجاع، أو اتركه فارغًا — يمكن استلام الإرجاع بمسح قطعه.",
            HttpStatus.CONFLICT);
    }

    public static GenericReturnException trackingTaken() {
        return new GenericReturnException("TRACKING_TAKEN",
            "Another shipment in Traced already has that tracking number.",
            "يوجد شحنة أخرى في Traced بنفس رقم التتبع.",
            HttpStatus.CONFLICT);
    }

    public static GenericReturnException otherCouriersOff() {
        return new GenericReturnException("OTHER_COURIERS_OFF",
            "Switch on \"I ship with other couriers\" in Settings → Couriers first.",
            "فعّل \"أشحن مع شركات شحن أخرى\" من الإعدادات ← شركات الشحن أولًا.",
            HttpStatus.CONFLICT);
    }
}
