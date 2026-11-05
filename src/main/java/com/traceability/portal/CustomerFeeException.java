package com.traceability.portal;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;

/** Returns portal fees F1 — a refused Customer fees / exchange pricing settings change (EN + AR). */
public class CustomerFeeException extends ApiException {

    private CustomerFeeException(String code, String en, String ar, HttpStatus status) {
        super(code, en, ar, status);
    }

    static CustomerFeeException notAvailable() {
        return new CustomerFeeException("NOT_FOUND", "Not found.", "غير موجود.", HttpStatus.NOT_FOUND);
    }

    static CustomerFeeException kind() {
        return new CustomerFeeException("FEE_KIND_INVALID", "The fee must be for exchanges or refunds.",
            "يجب أن تكون الرسوم على الاستبدال أو الاسترداد.", HttpStatus.BAD_REQUEST);
    }

    static CustomerFeeException mode() {
        return new CustomerFeeException("FEE_MODE_INVALID", "Choose a fixed amount or a percentage.",
            "اختر مبلغًا ثابتًا أو نسبة مئوية.", HttpStatus.BAD_REQUEST);
    }

    static CustomerFeeException amount(boolean zeroAllowed) {
        return zeroAllowed
            ? new CustomerFeeException("FEE_AMOUNT_INVALID", "Enter a whole amount from 0 to 30,000 EGP.",
                "أدخل مبلغًا صحيحًا من 0 إلى 30,000 جنيه.", HttpStatus.BAD_REQUEST)
            : new CustomerFeeException("FEE_AMOUNT_INVALID", "Enter a whole amount from 1 to 30,000 EGP.",
                "أدخل مبلغًا صحيحًا من 1 إلى 30,000 جنيه.", HttpStatus.BAD_REQUEST);
    }

    static CustomerFeeException percent(boolean zeroAllowed) {
        return new CustomerFeeException("FEE_PERCENT_INVALID",
            zeroAllowed ? "Enter a percentage from 0 to 100, at most 2 decimals."
                        : "Enter a percentage above 0 and up to 100, at most 2 decimals.",
            zeroAllowed ? "أدخل نسبة من 0 إلى 100، بحد أقصى رقمين عشريين."
                        : "أدخل نسبة أكبر من 0 وحتى 100، بحد أقصى رقمين عشريين.",
            HttpStatus.BAD_REQUEST);
    }

    static CustomerFeeException minMax() {
        return new CustomerFeeException("FEE_MIN_MAX_INVALID",
            "Minimum and maximum are whole amounts from 0 to 30,000 EGP, and the minimum can't be above the maximum.",
            "الحد الأدنى والأقصى مبالغ صحيحة من 0 إلى 30,000 جنيه، ولا يزيد الحد الأدنى عن الأقصى.",
            HttpStatus.BAD_REQUEST);
    }

    static CustomerFeeException reason() {
        return new CustomerFeeException("FEE_REASON_INVALID", "One of the return reasons isn't recognised.",
            "أحد أسباب الإرجاع غير معروف.", HttpStatus.BAD_REQUEST);
    }

    static CustomerFeeException maxDifference() {
        return new CustomerFeeException("MAX_DIFFERENCE_INVALID", "Enter a whole amount from 0 to 30,000 EGP, or leave it empty.",
            "أدخل مبلغًا صحيحًا من 0 إلى 30,000 جنيه، أو اتركه فارغًا.", HttpStatus.BAD_REQUEST);
    }

    static CustomerFeeException area() {
        return new CustomerFeeException("FEE_AREA_UNKNOWN", "Choose a city (and optionally one of its districts) from the list.",
            "اختر مدينة (ومنطقة منها إن أردت) من القائمة.", HttpStatus.BAD_REQUEST);
    }

    static CustomerFeeException overrideExists() {
        return new CustomerFeeException("FEE_OVERRIDE_EXISTS", "This area already has its own fee.",
            "هذه المنطقة لها رسوم خاصة بالفعل.", HttpStatus.CONFLICT);
    }

    static CustomerFeeException overrideNotFound() {
        return new CustomerFeeException("FEE_OVERRIDE_NOT_FOUND", "That area fee no longer exists.",
            "رسوم هذه المنطقة لم تعد موجودة.", HttpStatus.NOT_FOUND);
    }
}
